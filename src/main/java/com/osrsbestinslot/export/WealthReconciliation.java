package com.osrsbestinslot.export;

import java.util.*;

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

		Flow(String flowId, int itemId, long qtyDelta, String flowClass, String provenance)
		{
			this.flowId = flowId;
			this.itemId = itemId;
			this.qtyDelta = qtyDelta;
			this.flowClass = flowClass;
			this.provenance = provenance;
		}
	}

	/**
	 * Trade facts from C1's derived offer records (GeOfferLifecycle.reduce output, record_schema_v 1). Only fills
	 * whose observation interval lies inside (fromMs, toMs] count. A fill interval that crosses either end cannot be
	 * placed in this window, so it is excluded and listed; the snapshot residual then stays visible. Quarantined
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
				if (end <= fromMs || start > toMs)
				{
					continue;	// outside the window
				}
				if (start < fromMs || end > toMs)
				{
					excluded.add(key + ":fill_straddles_window");
					continue;
				}
				out.add(new TradeFact(item.intValue(), buy ? qty : -qty, gp == null ? null : buy ? -gp : gp,
					"c1_offer_lifecycle"));
			}
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
			if (a.observedAtMs != null && a.observedAtMs.equals(b.observedAtMs) && !name.equals("ge_escrow")
				&& !name.equals("inventory") && !name.equals("equipment"))
			{
				// Same reading carried forward: the game did not resend it, so changes made out of view are unseen.
				carried.add(name);
			}
			if (WealthLedger.STALE.equals(a.state) || WealthLedger.STALE.equals(b.state))
			{
				stale.add(name);
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
		if (geComparable)
		{
			geFacts(from, to, tradeFacts == null, facts, settleBounds, boxBounds, tradeSources);
		}
		if (tradeFacts != null)
		{
			for (TradeFact t : tradeFacts)
			{
				add(facts, t.itemId, TRADE, t.qtyDelta);
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
		Map<Integer, Long> outOfView = new HashMap<>();
		Set<String> seenFlows = new HashSet<>();
		int duplicateFlows = 0;
		if (flows != null)
		{
			for (Flow f : flows)
			{
				if (!seenFlows.add(f.flowId))
				{
					duplicateFlows++;	// a replayed or duplicated upload must not count twice
					continue;
				}
				int id = f.itemId == WealthLedger.PLATINUM_TOKEN ? WealthLedger.COINS : f.itemId;
				long q = f.itemId == WealthLedger.PLATINUM_TOKEN ? f.qtyDelta * WealthLedger.PLATINUM_TOKEN_COINS : f.qtyDelta;
				String cls = ledgerClass(f.flowClass);
				if (cls.equals(TRANSFER_OUT_OF_VIEW))
				{
					// C3 also marks bank deposits as transferred. Use the flow only for the part no in-view location shows.
					outOfView.merge(id, q, Long::sum);
				}
				else
				{
					add(facts, id, cls, q);
				}
			}
		}

		Set<Integer> items = new TreeSet<>(moves.keySet());
		items.addAll(facts.keySet());
		items.addAll(outOfView.keySet());
		List<Map<String, Object>> rows = new ArrayList<>();
		Map<String, Long> classValue = new LinkedHashMap<>();
		Map<String, Long> classQtyItems = new LinkedHashMap<>();
		List<Integer> unpriced = new ArrayList<>();
		long revaluation = 0;
		boolean priced = priceFrom != null && priceTo != null;
		for (int id : items)
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
			long[] settle = settleBounds.get(id);
			if (remaining != 0 && settle != null && remaining >= settle[0] && remaining <= settle[1])
			{
				cls.merge(GE_SETTLEMENT_INFERRED, remaining, Long::sum);
				remaining = 0;
			}
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
			Long away = outOfView.get(id);
			if (remaining != 0 && away != null && Long.signum(away) == Long.signum(remaining))
			{
				long take = remaining > 0 ? Math.min(remaining, away) : Math.max(remaining, away);
				cls.merge(TRANSFER_OUT_OF_VIEW, take, Long::sum);
				remaining -= take;
			}
			if (remaining != 0)
			{
				cls.put(UNEXPLAINED, remaining);
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
					boolean internal = e.getKey().equals(TRANSFER) || e.getKey().equals(GE_COLLECT_BOX);
					long v = internal ? 0 : e.getValue() * pa;
					classValue.merge(e.getKey(), v, Long::sum);
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
				revaluation += e.getValue() * (pb - pa);
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
		boolean explained = rowsHave(rows, UNEXPLAINED) == 0 && rowsHave(rows, GE_SETTLEMENT_INFERRED) == 0;
		boolean core = true;
		for (String name : WealthLedger.CORE)
		{
			core &= comparable.contains(name) && !carried.contains(name) && !stale.contains(name);
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
	 * and no counter went down; then the counter difference is an observed fill. An offer that is new at TO was
	 * placed inside the interval, so its counters count from zero. A vanished or replaced offer was collected out
	 * of view, which gives only a bounded, inferred settlement for its item and coins.
	 *
	 * Collect-box bounds: box[0] = most that can have left the box into view (box contents at FROM plus fills);
	 * box[1] = most that can have entered the box out of view (fills inside the interval).
	 */
	private static void geFacts(WealthLedger.Holdings from, WealthLedger.Holdings to, boolean deriveFills,
		Map<Integer, Map<String, Long>> facts, Map<Integer, long[]> settle, Map<Integer, long[]> box, List<String> sources)
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
			if (q <= 0 && s <= 0)
			{
				continue;
			}
			boolean buy = b.side.equals("buy");
			if (deriveFills)
			{
				add(facts, b.itemId, TRADE, buy ? q : -q);
				add(facts, WealthLedger.COINS, TRADE, buy ? -s : s);
				if (!sources.contains("snapshot_slot_counter"))
				{
					sources.add("snapshot_slot_counter");
				}
			}
			if (buy)
			{
				bound(box, b.itemId, 0, q);
				bound(box, WealthLedger.COINS, 0, q * b.price - s);	// overpay refund
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
			if (a.side.equals("buy"))
			{
				// Unfilled coins that came back are a net-zero round trip; only new fills cost coins.
				range(settle, a.itemId, 0, remaining + a.maxUncollectedItems);
				range(settle, WealthLedger.COINS, -(remaining * a.price), a.maxUncollectedCoins);
			}
			else
			{
				// A sell can fill above its ask, so the coin side has no useful upper bound.
				range(settle, a.itemId, -remaining, a.maxUncollectedItems);
				range(settle, WealthLedger.COINS, 0, UNBOUNDED);
			}
		}
	}

	private static final long UNBOUNDED = Long.MAX_VALUE / 4;

	private static boolean same(WealthLedger.Escrow a, WealthLedger.Escrow b)
	{
		return a != null && b != null && a.itemId == b.itemId && a.side.equals(b.side) && a.total == b.total
			&& a.price == b.price && b.done >= a.done && b.spent >= a.spent;
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
		r[1] = r[1] >= UNBOUNDED || max >= UNBOUNDED ? UNBOUNDED : r[1] + max;
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
