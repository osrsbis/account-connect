package com.osrsbestinslot.export;

import java.util.*;
import java.util.function.IntUnaryOperator;

/**
 * Item ledger between two holdings of the same account (C4 wealth lane, contract v1). Pure derivation.
 *
 * Per item, over locations readable in BOTH holdings only:
 *   net change = transfer between own locations (matched opposite moves, always net zero)
 *              + trade (GE fills; C1 records when given, else slot counter differences)
 *              + C3 flows (consumption/loss, reward, transform, transfer out of view) taken as facts
 *              + GE collect-box movement (bounded by what the box can hold)
 *              + unexplained (the residual; never silently given a class)
 * Value change = quantity effect at the FROM prices + revaluation (TO price minus FROM price on TO quantities), so
 * a price move is never reported as earned GP. Platinum tokens fold into coins at 1000 each.
 */
final class WealthReconciliation
{
	static final String CONTRACT = "c4-wealth-ledger-v1";
	static final String TRANSFER = "transfer";
	static final String TRADE = "trade";
	static final String GE_SETTLEMENT_INFERRED = "ge_settlement_inferred";
	static final String GE_COLLECT_BOX = "ge_collect_box";
	static final String CONSUMPTION_LOSS = "consumption_loss";
	static final String REWARD = "reward";
	static final String TRANSFORM = "transform";
	static final String TRANSFER_OUT_OF_VIEW = "transfer_out_of_view";
	static final String TRANSFER_INFERRED_CARRIED = "transfer_inferred_carried";
	/** C3 bank_session flow whose bank side was not read in this window: own move, recorded by an event, value 0. */
	static final String TRANSFER_OWN_UNREAD = "transfer_own_unread";
	/** Snapshot times are whole seconds; a flow or fill in the same second as either snapshot cannot be placed. */
	static final long SNAPSHOT_TIME_RESOLUTION_MS = 1000L;
	static final String UNEXPLAINED = "unexplained";

	private WealthReconciliation()
	{
	}

	/** A trade fill as C1 publishes it: delta to the account (item in/out, coins out/in). */
	static final class TradeFact
	{
		final int itemId;
		final long qtyDelta;
		final Long coinsDelta;
		final String source;

		TradeFact(int itemId, long qtyDelta, Long coinsDelta, String source)
		{
			this.itemId = itemId;
			this.qtyDelta = qtyDelta;
			this.coinsDelta = coinsDelta;
			this.source = source;
		}
	}

	/** A C3 item flow, taken as a fact. flowClass uses C3's names. */
	static final class Flow
	{
		final String flowId;
		final int itemId;
		final long qtyDelta;
		final String flowClass;
		final String provenance;
		final String origin;

		Flow(String flowId, int itemId, long qtyDelta, String flowClass, String provenance)
		{
			this(flowId, itemId, qtyDelta, flowClass, provenance, null);
		}

		Flow(String flowId, int itemId, long qtyDelta, String flowClass, String provenance, String origin)
		{
			this.flowId = Objects.requireNonNull(flowId, "flow_id");
			this.itemId = itemId;
			this.qtyDelta = qtyDelta;
			this.flowClass = Objects.requireNonNull(flowClass, "flow_class");
			this.provenance = provenance;
			this.origin = origin;
		}

		/** C3 marks bank deposits/withdrawals as transferred with origin bank_deposit / bank_withdraw. */
		boolean ownBankMove()
		{
			return "bank_deposit".equals(origin) || "bank_withdraw".equals(origin);
		}
	}

	/**
	 * Trade facts from C1's derived offer records (GeOfferLifecycle.reduce output, record_schema_v 1). Only fills
	 * whose observation interval lies inside (fromMs, toMs] count. A fill interval that crosses either end cannot be
	 * placed in this window, so it is excluded and listed; the snapshot residual then stays visible. Fill quantity with
	 * no interval (untimed_fill_qty, after a lost row) is listed too, never spread over a guessed time. Quarantined
	 * offers and offers of another account/profile are excluded too. Sell gp is RuneLite's counter, tax unknown.
	 */
	static List<TradeFact> tradeFactsFromC1(Map<?, ?> reduced, String accountHash, String profileType, long fromMs,
		long toMs, List<String> excluded)
	{
		List<TradeFact> out = new ArrayList<>();
		Object offers = reduced == null ? null : reduced.get("offers");
		if (!(offers instanceof List))
		{
			return out;
		}
		for (Object o : (List<?>) offers)
		{
			Map<?, ?> offer = WealthLedger.asMap(o);
			if (offer == null)
			{
				continue;
			}
			String key = String.valueOf(offer.get("offer_key"));
			if (!String.valueOf(offer.get("account_hash")).equals(accountHash)
				|| !String.valueOf(offer.get("profile_type")).equals(profileType))
			{
				excluded.add(key + ":other_account");
				continue;
			}
			if (Boolean.TRUE.equals(offer.get("quarantined")))
			{
				excluded.add(key + ":quarantined");
				continue;
			}
			Long item = WealthLedger.asLong(offer.get("item_id"));
			boolean buy = "buy".equals(offer.get("side"));
			Object fills = offer.get("fills");
			if (item == null || !(fills instanceof List))
			{
				excluded.add(key + ":malformed");
				continue;
			}
			for (Object f : (List<?>) fills)
			{
				Map<?, ?> fill = WealthLedger.asMap(f);
				Long qty = fill == null ? null : WealthLedger.asLong(fill.get("qty"));
				Long gp = fill == null ? null : WealthLedger.asLong(fill.get("gp"));
				Long start = fill == null ? null : WealthLedger.asLong(fill.get("interval_start_ms"));
				Long end = fill == null ? null : WealthLedger.asLong(fill.get("interval_end_ms"));
				if (qty == null || start == null || end == null)
				{
					excluded.add(key + ":fill_unknown");
					continue;
				}
				if (end <= fromMs || start >= toMs + SNAPSHOT_TIME_RESOLUTION_MS)
				{
					continue;	// outside the window
				}
				// Snapshot times are whole seconds: a fill touching the second of either snapshot may already be in it.
				if (start < fromMs + SNAPSHOT_TIME_RESOLUTION_MS || end > toMs)
				{
					excluded.add(key + ":fill_straddles_window");
					continue;
				}
				out.add(new TradeFact(item.intValue(), buy ? qty : -qty, gp == null ? null : buy ? -gp : gp,
					"c1_offer_lifecycle"));
			}
			// A lost middle row: the offer total is right but part of it has no interval (C1 untimed_fill_qty). That part
			// cannot be placed in any window, so it is listed and the snapshot residual for it stays unexplained.
			Long untimed = WealthLedger.asLong(offer.get("untimed_fill_qty"));
			Long first = WealthLedger.asLong(offer.get("first_seen_at_ms"));
			Long last = WealthLedger.asLong(offer.get("as_of_observed_ms"));
			boolean overlaps = (first == null || first <= toMs) && (last == null || last > fromMs);
			if (untimed != null && untimed > 0 && overlaps)
			{
				excluded.add(key + ":untimed_fill_qty=" + untimed);
			}
		}
		return out;
	}

	/**
	 * Flows from C3 ActivityFlows.classify output (c3-activity-flows-v1). Only flows observed inside (fromMs, toMs]
	 * count. A flow with no item or quantity (a death without a trusted items_lost diff) becomes an unknown flow:
	 * it is kept with itemId 0 so the ledger reports it and never calls the window explained.
	 */
	static List<Flow> flowsFromC3(List<? extends Map<?, ?>> c3Flows, long fromMs, long toMs, List<String> excluded)
	{
		List<Flow> out = new ArrayList<>();
		if (c3Flows == null)
		{
			return out;
		}
		for (Map<?, ?> f : c3Flows)
		{
			if (f.get("flow_id") == null || f.get("flow_class") == null)
			{
				excluded.add("row" + out.size() + ":missing_flow_id_or_class");
				continue;
			}
			String id = String.valueOf(f.get("flow_id"));
			Long at = WealthLedger.asLong(f.get("observed_at_ms"));
			if (at == null)
			{
				excluded.add(id + ":no_time");
				continue;
			}
			if (at < fromMs || at >= toMs + SNAPSHOT_TIME_RESOLUTION_MS)
			{
				continue;
			}
			if (at < fromMs + SNAPSHOT_TIME_RESOLUTION_MS || at >= toMs)
			{
				excluded.add(id + ":same_second_as_snapshot");	// may already be inside the FROM or TO snapshot
				continue;
			}
			Long item = WealthLedger.asLong(f.get("item_id")), qty = WealthLedger.asLong(f.get("qty_delta"));
			String cls = String.valueOf(f.get("flow_class"));
			Object origin = f.get("origin");
			out.add(item == null || qty == null || item <= 0
				? new Flow(id, 0, 0, cls, "unknown", origin == null ? null : origin.toString())
				: new Flow(id, item.intValue(), qty, cls, String.valueOf(f.get("provenance")),
					origin == null ? null : origin.toString()));
		}
		return out;
	}

	static String ledgerClass(String c3FlowClass)
	{
		switch (c3FlowClass)
		{
			case "consumed_observed":
			case "consumed_inferred":
			case "lost_death":
				return CONSUMPTION_LOSS;
			case "output_reward":
				return REWARD;
			case "transformed":
				return TRANSFORM;
			case "transferred":
				return TRANSFER_OUT_OF_VIEW;
			default:
				return UNEXPLAINED;
		}
	}

	/**
	 * Reconcile. tradeFacts null means "derive GE fills from the two snapshots' slot counters"; pass C1's records
	 * instead when they exist, never both. Prices may be null (then no GP figures). Missing prices are listed.
	 */
	static Map<String, Object> reconcile(WealthLedger.Holdings from, WealthLedger.Holdings to,
		List<TradeFact> tradeFacts, List<Flow> flows, Map<Integer, Long> priceFrom, Map<Integer, Long> priceTo)
	{
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("contract", CONTRACT);
		out.put("account_key", to.accountKey);
		out.put("from_ms", from.asOfMs);
		out.put("to_ms", to.asOfMs);
		if (from.accountKey == null || !from.accountKey.equals(to.accountKey))
		{
			// An account, game profile or iron-mode switch starts a new ledger. Never diff two accounts.
			out.put("status", "not_comparable_account");
			return out;
		}
		if (to.asOfMs <= from.asOfMs)
		{
			out.put("status", "not_comparable_order");
			return out;
		}

		List<String> comparable = new ArrayList<>(), carried = new ArrayList<>(), stale = new ArrayList<>();
		Map<String, String> notComparable = new LinkedHashMap<>();
		for (String name : to.locations.keySet())
		{
			WealthLedger.Location a = from.locations.get(name), b = to.locations.get(name);
			if (a == null || !a.readable() || !b.readable())
			{
				if (!WealthLedger.UNSUPPORTED.equals(b.state))
				{
					notComparable.put(name, "from=" + (a == null ? "absent" : a.state) + " to=" + b.state);
				}
				continue;
			}
			comparable.add(name);
			if ((WealthLedger.CARRIED.equals(a.state) || WealthLedger.CARRIED.equals(b.state)
				|| b.observedAtMs != null && b.observedAtMs <= from.asOfMs) && !name.equals("ge_escrow")
				&& !name.equals("inventory") && !name.equals("equipment"))
			{
				// The TO reading was taken at or before the FROM snapshot: nothing in this window was read there, so
				// changes made out of view (for example items sent straight to the bank) are unseen.
				carried.add(name);
			}
			if (WealthLedger.STALE.equals(a.state) || WealthLedger.STALE.equals(b.state)
				|| WealthLedger.UNDATED.equals(a.state) || WealthLedger.UNDATED.equals(b.state))
			{
				stale.add(name);	// readable but not freshly dated at one end
			}
		}

		// Per item: sum of positive and negative location deltas over comparable locations.
		SortedMap<Integer, long[]> moves = new TreeMap<>();	// [pos, neg]
		for (String name : comparable)
		{
			SortedMap<Integer, Long> a = fold(from.locations.get(name).items), b = fold(to.locations.get(name).items);
			Set<Integer> ids = new TreeSet<>(a.keySet());
			ids.addAll(b.keySet());
			for (int id : ids)
			{
				long d = b.getOrDefault(id, 0L) - a.getOrDefault(id, 0L);
				if (d != 0)
				{
					long[] pn = moves.computeIfAbsent(id, k -> new long[2]);
					pn[d > 0 ? 0 : 1] += d;
				}
			}
		}

		// Facts by item and class.
		Map<Integer, Map<String, Long>> facts = new TreeMap<>();
		List<String> tradeSources = new ArrayList<>();
		boolean geComparable = comparable.contains("ge_escrow");
		Map<Integer, long[]> settleBounds = new HashMap<>();	// item -> [min, max] for vanished/replaced offers
		Map<Integer, long[]> boxBounds = new HashMap<>();		// item -> [max collected out, max collected in]
		List<String> inferredTrades = new ArrayList<>();
		Map<Integer, long[]> settlePrice = new HashMap<>();	// item -> [max buy price, max ask] of vanished open offers
		IntUnaryOperator canon = to.canonical;
		if (geComparable)
		{
			geFacts(from, to, tradeFacts == null, facts, settleBounds, boxBounds, tradeSources, inferredTrades, settlePrice);
		}
		if (tradeFacts != null)
		{
			for (TradeFact t : tradeFacts)
			{
				add(facts, canon.applyAsInt(t.itemId), TRADE, t.qtyDelta);
				if (t.coinsDelta != null)
				{
					add(facts, WealthLedger.COINS, TRADE, t.coinsDelta);
				}
				if (!tradeSources.contains(t.source))
				{
					tradeSources.add(t.source);
				}
			}
		}
		Map<Integer, Long> outOfView = new HashMap<>(), outOfViewFull = new HashMap<>(), ownUnread = new HashMap<>();
		boolean bankFresh = comparable.contains("bank") && !carried.contains("bank")
			&& WealthLedger.OBSERVED.equals(from.locations.get("bank").state)
			&& WealthLedger.OBSERVED.equals(to.locations.get("bank").state);
		List<String> unknownFlows = new ArrayList<>();
		Set<String> seenFlows = new HashSet<>();
		int duplicateFlows = 0;
		List<String> conflictingFlows = new ArrayList<>();
		if (flows != null)
		{
			// Group by flow_id first. Identical copies (a replayed upload) count once. Copies that disagree (one
			// event_id with different contents) are all dropped and listed, so first-wins can never hide a conflict.
			Map<String, Flow> byId = new LinkedHashMap<>();
			Set<String> conflicted = new LinkedHashSet<>();
			for (Flow f : flows)
			{
				Flow first = byId.putIfAbsent(f.flowId, f);
				if (first != null)
				{
					duplicateFlows++;
					if (first.itemId != f.itemId || first.qtyDelta != f.qtyDelta || !first.flowClass.equals(f.flowClass)
						|| !Objects.equals(first.origin, f.origin))
					{
						conflicted.add(f.flowId);
					}
				}
			}
			conflictingFlows.addAll(conflicted);
			for (Flow f : byId.values())
			{
				seenFlows.add(f.flowId);
				if (conflicted.contains(f.flowId))
				{
					continue;
				}
				if (f.itemId <= 0)
				{
					unknownFlows.add(f.flowId + ":" + f.flowClass);	// e.g. a death with no trusted item diff
					continue;
				}
				int base = canon.applyAsInt(f.itemId);
				int id = base == WealthLedger.PLATINUM_TOKEN ? WealthLedger.COINS : base;
				long q = base == WealthLedger.PLATINUM_TOKEN ? f.qtyDelta * WealthLedger.PLATINUM_TOKEN_COINS : f.qtyDelta;
				String cls = ledgerClass(f.flowClass);
				if (cls.equals(TRANSFER_OUT_OF_VIEW))
				{
					if (f.ownBankMove())
					{
						// A bank read freshly at both ends shows this move in view already; the flow adds nothing and
						// must not absorb an unrelated residual. Only an unread or carried bank needs it.
						if (!bankFresh)
						{
							ownUnread.merge(id, q, Long::sum);
						}
					}
					else if (f.origin != null)
					{
						// Trade, drop and pickup never land in a read own location: apply in full.
						outOfViewFull.merge(id, q, Long::sum);
					}
					else
					{
						outOfView.merge(id, q, Long::sum);	// origin unknown: only the part not seen in view
					}
				}
				else
				{
					add(facts, id, cls, q);
				}
			}
		}
		// Carried locations: an item can move into or out of them while they are out of view.
		Map<Integer, Long> carriedHeld = new HashMap<>();
		boolean anyCarried = false;
		for (String name : comparable)
		{
			if (WealthLedger.CARRIED.equals(to.locations.get(name).state))
			{
				anyCarried = true;
				to.locations.get(name).items.forEach((id, q) -> carriedHeld.merge(id, q, Long::sum));
			}
		}
		Long tokens = carriedHeld.remove(WealthLedger.PLATINUM_TOKEN);
		if (tokens != null)
		{
			carriedHeld.merge(WealthLedger.COINS, tokens * WealthLedger.PLATINUM_TOKEN_COINS, Long::sum);
		}

		Set<Integer> items = new TreeSet<>(moves.keySet());
		items.addAll(facts.keySet());
		items.addAll(outOfView.keySet());
		items.addAll(ownUnread.keySet());
		items.addAll(outOfViewFull.keySet());
		// Items first, coins last: a coin settlement must follow the items its vanished offers actually settled.
		List<Integer> order = new ArrayList<>(items);
		if (order.remove(Integer.valueOf(WealthLedger.COINS)))
		{
			order.add(WealthLedger.COINS);
		}
		long[] coinSettle = new long[2];
		List<Map<String, Object>> rows = new ArrayList<>();
		Map<String, Long> classValue = new LinkedHashMap<>();
		Map<String, Long> classQtyItems = new LinkedHashMap<>();
		List<Integer> unpriced = new ArrayList<>();
		long revaluation = 0, atRisk = 0;
		boolean priced = priceFrom != null && priceTo != null;
		for (int id : order)
		{
			long[] pn = moves.getOrDefault(id, new long[2]);
			long net = pn[0] + pn[1];
			Map<String, Long> cls = new LinkedHashMap<>();
			long transfer = Math.min(pn[0], -pn[1]);
			if (transfer > 0)
			{
				cls.put(TRANSFER, transfer);
			}
			long remaining = net;
			Map<String, Long> f = facts.getOrDefault(id, Collections.emptyMap());
			for (Map.Entry<String, Long> e : f.entrySet())
			{
				if (e.getValue() != 0)
				{
					cls.merge(e.getKey(), e.getValue(), Long::sum);
					remaining -= e.getValue();
				}
			}
			// Out-of-view flows first. A transferred flow whose other side is already seen in view was used up as the
			// in-view transfer, so only the part beyond the in-view moves on that side can explain a residual.
			remaining = applyFull(cls, remaining, ownUnread.get(id), TRANSFER_OWN_UNREAD);
			remaining = applyFull(cls, remaining, outOfViewFull.get(id), TRANSFER_OUT_OF_VIEW);
			remaining = applyOutOfView(cls, remaining, outOfView.get(id), pn, TRANSFER_OUT_OF_VIEW);
			long[] box = boxBounds.get(id);
			if (remaining != 0 && box != null)
			{
				// remaining > 0: items left the collect box into view. remaining < 0: items entered the box (unseen).
				long take = remaining > 0 ? Math.min(remaining, box[0]) : Math.max(remaining, -box[1]);
				if (take != 0)
				{
					cls.merge(GE_COLLECT_BOX, take, Long::sum);
					remaining -= take;
				}
			}
			long[] settle = id == WealthLedger.COINS ? coinSettle : settleBounds.get(id);
			if (remaining != 0 && settle != null)
			{
				// Only the part the vanished offer's terms can justify; any excess stays unexplained.
				long take = Math.max(settle[0], Math.min(settle[1], remaining));
				if (take != 0)
				{
					cls.merge(GE_SETTLEMENT_INFERRED, take, Long::sum);
					remaining -= take;
					long[] pr = settlePrice.get(id);
					if (pr != null && id != WealthLedger.COINS)
					{
						// Bought items cost at most the bid; sold items bring at least the ask (counted up to the ask).
						if (take > 0)
						{
							coinSettle[0] -= Math.multiplyExact(take, pr[0]);
						}
						else
						{
							coinSettle[1] += Math.multiplyExact(-take, pr[1]);
						}
					}
				}
			}
			if (remaining != 0 && anyCarried)
			{
				// Withdrawn from (remaining > 0, at most what the carried reading held) or deposited into (remaining < 0)
				// a location that was not resent. Inferred, never proof.
				long take = remaining > 0 ? Math.min(remaining, carriedHeld.getOrDefault(id, 0L)) : remaining;
				if (take != 0)
				{
					cls.merge(TRANSFER_INFERRED_CARRIED, take, Long::sum);
					remaining -= take;
				}
			}
			if (remaining != 0)
			{
				cls.merge(UNEXPLAINED, remaining, Long::sum);
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("item_id", id);
			row.put("net_change", net);
			row.put("classes", cls);
			Long pa = priced ? priceOf(priceFrom, id) : null, pb = priced ? priceOf(priceTo, id) : null;
			if (priced && (pa == null || pb == null))
			{
				unpriced.add(id);
			}
			if (pa != null && pb != null)
			{
				for (Map.Entry<String, Long> e : cls.entrySet())
				{
					// Transfers and collect-box moves stay inside the account: they move value, they never create it.
					if (e.getKey().equals(GE_SETTLEMENT_INFERRED))
					{
						// The item and coin sides of an out-of-view settlement cannot be paired, so no GP value is claimed.
						atRisk = Math.addExact(atRisk, Math.multiplyExact(Math.abs(e.getValue()), pa));
						continue;
					}
					boolean inferredInternal = e.getKey().equals(GE_COLLECT_BOX) || e.getKey().equals(TRANSFER_INFERRED_CARRIED)
						|| e.getKey().equals(TRANSFER_OWN_UNREAD);
					boolean internal = e.getKey().equals(TRANSFER) || inferredInternal;
					if (inferredInternal)
					{
						// Valued 0 as an own move, but it is inferred: report how much value it could be hiding.
						atRisk = Math.addExact(atRisk, Math.multiplyExact(Math.abs(e.getValue()), pa));
					}
					long v = internal ? 0 : Math.multiplyExact(e.getValue(), pa);
					classValue.merge(e.getKey(), v, Math::addExact);
				}
			}
			rows.add(row);
		}
		if (priced)
		{
			// Revaluation over every comparable holding, including items whose quantity did not change.
			SortedMap<Integer, Long> held = new TreeMap<>();
			for (String name : comparable)
			{
				fold(to.locations.get(name).items).forEach((id, q) -> held.merge(id, q, Long::sum));
			}
			for (Map.Entry<Integer, Long> e : held.entrySet())
			{
				Long pa = priceOf(priceFrom, e.getKey()), pb = priceOf(priceTo, e.getKey());
				if (pa == null || pb == null)
				{
					if (!unpriced.contains(e.getKey()))
					{
						unpriced.add(e.getKey());
					}
					continue;
				}
				revaluation = Math.addExact(revaluation, Math.multiplyExact(e.getValue(), pb - pa));
			}
		}

		out.put("status", "reconciled");
		out.put("comparable_locations", comparable);
		out.put("not_comparable_locations", notComparable);
		out.put("carried_forward_locations", carried);
		out.put("stale_locations", stale);
		out.put("trade_sources", tradeSources);
		out.put("trade_gp_semantics", "runelite_spent_tax_unknown");
		out.put("duplicate_flows_ignored", duplicateFlows);
		out.put("contributing_flow_ids", new ArrayList<>(new TreeSet<>(seenFlows)));
		out.put("items", rows);
		if (priced)
		{
			out.put("value_by_class_gp_at_from_prices", classValue);
			out.put("unvalued_classes", Collections.singletonList(GE_SETTLEMENT_INFERRED));
			out.put("inferred_value_at_risk_gp", atRisk);
			out.put("revaluation_gp", revaluation);
			out.put("unpriced_item_ids", unpriced);
		}
		boolean conserved = true;
		for (Map<String, Object> row : rows)
		{
			@SuppressWarnings("unchecked") Map<String, Long> cls = (Map<String, Long>) row.get("classes");
			long sum = 0;
			for (Map.Entry<String, Long> e : cls.entrySet())
			{
				if (!e.getKey().equals(TRANSFER))
				{
					sum += e.getValue();
				}
			}
			conserved &= sum == (long) row.get("net_change");
		}
		out.put("conservation_holds", conserved);
		out.put("unknown_flows", unknownFlows);
		out.put("conflicting_flow_ids", conflictingFlows);
		out.put("inferred_trade_slots", inferredTrades);
		// The collect box is never read: its use is inferred from slot counters, so it never counts as explained.
		boolean explained = rowsHave(rows, UNEXPLAINED) == 0 && rowsHave(rows, GE_SETTLEMENT_INFERRED) == 0
			&& rowsHave(rows, TRANSFER_INFERRED_CARRIED) == 0 && rowsHave(rows, GE_COLLECT_BOX) == 0
			&& rowsHave(rows, TRANSFER_OWN_UNREAD) == 0
			&& unknownFlows.isEmpty() && conflictingFlows.isEmpty() && inferredTrades.isEmpty();
		boolean core = true;
		for (String name : WealthLedger.CORE)
		{
			core &= comparable.contains(name) && !carried.contains(name) && !stale.contains(name)
				&& WealthLedger.OBSERVED.equals(from.locations.get(name).state)
				&& WealthLedger.OBSERVED.equals(to.locations.get(name).state);
		}
		out.put("explained", explained);
		out.put("core_comparable", core);
		// core_complete = bank, inventory, equipment and GE read freshly at both ends and every change classified
		// from evidence. It is NOT an account total: optional storages stay listed in not_comparable_locations.
		out.put("core_complete", explained && core);
		return out;
	}

	/**
	 * GE slot evidence between two snapshots. The same offer persists when slot, item, side, total and price match
	 * and no counter went down; then the counter difference is an observed fill. An offer first seen at TO with fills
	 * is a placement that was not observed: its fills may predate the window (for example a read taken before the
	 * login GE replay), so they are derived but listed in inferredTrades. A vanished or replaced offer was collected
	 * out of view.
	 *
	 * Collect-box room (box[0] = most that can have left the box into view, box[1] = most that can have entered it):
	 * FROM box contents plus fills, released locked coins/items on a cancel. A vanished offer's FROM box contents are
	 * box room; only fills after FROM (at most the unfilled remainder) can be settlement.
	 */
	private static void geFacts(WealthLedger.Holdings from, WealthLedger.Holdings to, boolean deriveFills,
		Map<Integer, Map<String, Long>> facts, Map<Integer, long[]> settle, Map<Integer, long[]> box, List<String> sources,
		List<String> inferredTrades, Map<Integer, long[]> settlePrice)
	{
		Map<Integer, WealthLedger.Escrow> fromSlot = new HashMap<>(), toSlot = new HashMap<>();
		for (WealthLedger.Escrow a : from.escrow)
		{
			fromSlot.put(a.slot, a);
		}
		for (WealthLedger.Escrow b : to.escrow)
		{
			toSlot.put(b.slot, b);
			WealthLedger.Escrow a = fromSlot.get(b.slot);
			boolean same = same(a, b);
			long q = b.done - (same ? a.done : 0), s = b.spent - (same ? a.spent : 0);
			boolean buy = b.side.equals("buy");
			if (same)
			{
				// A cancel moves the still-locked coins (buy) or items (sell) into the collect box.
				long releasedCoins = a.lockedCoins - b.lockedCoins - (buy ? Math.multiplyExact(q, b.price) : 0);
				long releasedItems = a.lockedItems - b.lockedItems - (buy ? 0 : q);
				bound(box, WealthLedger.COINS, 0, releasedCoins);
				bound(box, b.itemId, 0, releasedItems);
			}
			else
			{
				// Placed inside the window (real 0.7.42 run: a buy placed and cancelled between two snapshots). All of
				// its collect-box contents entered the box inside the window, fills and released escrow alike.
				bound(box, WealthLedger.COINS, 0, b.maxUncollectedCoins);
				bound(box, b.itemId, 0, b.maxUncollectedItems);
			}
			if (q <= 0 && s <= 0)
			{
				continue;
			}
			if (deriveFills && !same && a == null && b.done > 0)
			{
				inferredTrades.add("slot" + b.slot + ":placement_not_observed");
			}
			if (deriveFills)
			{
				add(facts, b.itemId, TRADE, buy ? q : -q);
				add(facts, WealthLedger.COINS, TRADE, buy ? -s : s);
				if (!sources.contains("snapshot_slot_counter"))
				{
					sources.add("snapshot_slot_counter");
				}
			}
			if (!same)
			{
				continue;	// box room for a new offer is its whole collect box, counted above
			}
			if (buy)
			{
				bound(box, b.itemId, 0, q);
				bound(box, WealthLedger.COINS, 0, Math.multiplyExact(q, b.price) - s);	// overpay refund
			}
			else
			{
				bound(box, WealthLedger.COINS, 0, s);
			}
		}
		for (WealthLedger.Escrow a : from.escrow)
		{
			bound(box, a.itemId, a.maxUncollectedItems, 0);
			bound(box, WealthLedger.COINS, a.maxUncollectedCoins, 0);
			if (same(a, toSlot.get(a.slot)))
			{
				continue;
			}
			long remaining = a.total - a.done;
			boolean open = a.offerState.equals("BUYING") || a.offerState.equals("SELLING");
			if (!open || remaining <= 0)
			{
				continue;	// a finished or cancelled offer has nothing left to fill; its box contents are box room above
			}
			// Item side only. The coin side is derived per item from what actually settled (see reconcile).
			long[] pr = settlePrice.computeIfAbsent(a.itemId, k -> new long[2]);
			if (a.side.equals("buy"))
			{
				range(settle, a.itemId, 0, remaining);
				pr[0] = Math.max(pr[0], a.price);
			}
			else
			{
				range(settle, a.itemId, -remaining, 0);
				pr[1] = Math.max(pr[1], a.price);
			}
		}
	}

	private static boolean same(WealthLedger.Escrow a, WealthLedger.Escrow b)
	{
		return a != null && b != null && a.itemId == b.itemId && a.side.equals(b.side) && a.total == b.total
			&& a.price == b.price && b.done >= a.done && b.spent >= a.spent;
	}

	/**
	 * Use a transferred flow for the residual. A negative flow (left a read location) whose arrival is seen in view
	 * was already counted as an in-view transfer; only the part beyond the in-view arrivals (pn[0]) is out of view.
	 * The same holds for a positive flow and the in-view departures (pn[1]).
	 */
	private static long applyOutOfView(Map<String, Long> cls, long remaining, Long flow, long[] pn, String name)
	{
		if (remaining == 0 || flow == null || flow == 0)
		{
			return remaining;
		}
		long away = flow < 0 ? Math.min(0, flow + pn[0]) : Math.max(0, flow + pn[1]);
		if (away == 0 || Long.signum(away) != Long.signum(remaining))
		{
			return remaining;
		}
		long take = remaining > 0 ? Math.min(remaining, away) : Math.max(remaining, away);
		cls.merge(name, take, Long::sum);
		return remaining - take;
	}

	/** Use a flow that never lands in a read location for the residual, in full, when the signs agree. */
	private static long applyFull(Map<String, Long> cls, long remaining, Long flow, String name)
	{
		if (remaining == 0 || flow == null || flow == 0 || Long.signum(flow) != Long.signum(remaining))
		{
			return remaining;
		}
		long take = remaining > 0 ? Math.min(remaining, flow) : Math.max(remaining, flow);
		cls.merge(name, take, Long::sum);
		return remaining - take;
	}

	private static void bound(Map<Integer, long[]> box, int id, long out, long in)
	{
		long[] b = box.computeIfAbsent(id, k -> new long[2]);
		b[0] += Math.max(0, out);
		b[1] += Math.max(0, in);
	}

	private static void range(Map<Integer, long[]> settle, int id, long min, long max)
	{
		long[] r = settle.computeIfAbsent(id, k -> new long[2]);
		r[0] += min;
		r[1] += max;
	}

	private static void add(Map<Integer, Map<String, Long>> facts, int id, String cls, long q)
	{
		if (q != 0)
		{
			facts.computeIfAbsent(id, k -> new LinkedHashMap<>()).merge(cls, q, Long::sum);
		}
	}

	private static SortedMap<Integer, Long> fold(SortedMap<Integer, Long> items)
	{
		SortedMap<Integer, Long> out = new TreeMap<>(items);
		Long tokens = out.remove(WealthLedger.PLATINUM_TOKEN);
		if (tokens != null)
		{
			out.merge(WealthLedger.COINS, tokens * WealthLedger.PLATINUM_TOKEN_COINS, Long::sum);
		}
		return out;
	}

	private static Long priceOf(Map<Integer, Long> prices, int id)
	{
		return id == WealthLedger.COINS ? Long.valueOf(1L) : prices.get(id);
	}

	private static int rowsHave(List<Map<String, Object>> rows, String cls)
	{
		int n = 0;
		for (Map<String, Object> row : rows)
		{
			if (((Map<?, ?>) row.get("classes")).containsKey(cls))
			{
				n++;
			}
		}
		return n;
	}
}
