package com.osrsbestinslot.export;

import java.util.*;
import java.util.function.IntUnaryOperator;

/**
 * Own-account holdings read model (C4 wealth lane, contract v1). Pure derivation over one built snapshot map
 * (the same map buildSnapshot uploads). No client calls, no prices from the network, no writes.
 *
 * Rules:
 * - A location the snapshot did not observe is UNKNOWN, never empty. A location the plugin cannot read is
 *   UNSUPPORTED. Neither is ever counted as zero.
 * - GE escrow counts only what is provably still inside the GE. Bought items, sale proceeds and refunds that the
 *   player may already have collected are kept as an upper bound, outside the known subtotal, so a collected
 *   item is never counted twice (once in the inventory/bank and once in the offer).
 * - No complete account total is claimed. The result carries a known subtotal and the list of locations that
 *   are unknown, stale, unsupported or only bounded.
 */
final class WealthLedger
{
	static final String CONTRACT = "c4-wealth-holdings-v1";
	static final int COINS = 995;
	static final int PLATINUM_TOKEN = 13204;
	static final long PLATINUM_TOKEN_COINS = 1000L;
	/** Display policy only: a storage reading older than this is reported stale. Not a correctness claim. */
	static final long DEFAULT_STALE_AFTER_MS = 60L * 60L * 1000L;

	static final String OBSERVED = "observed";
	static final String STALE = "stale";
	static final String UNKNOWN = "unknown";
	static final String UNSUPPORTED = "unsupported";
	static final String BOUNDED = "bounded";
	/** Container present at capture but the plugin recorded no send time (seen on a real 0.7.39 first bank open). */
	static final String UNDATED = "undated";
	/** Not present in this snapshot; the reading is carried from an earlier snapshot of the same account. */
	static final String CARRIED = "carried";

	/** The four core locations. The account view is "core complete" only when all four are observed and exact. */
	static final List<String> CORE = List.of("inventory", "equipment", "bank", "ge_escrow");
	/** Own storages the snapshot carries only after the game sent them this session (opened or checked). */
	static final List<String> OPTIONAL_STORAGE = List.of("looting_bag", "seed_vault", "bonds_pouch", "bonds_escrow", "quiver_ammo",
		"seed_box", "tackle_box", "forestry_kit", "huntsmans_kit", "barbarian_knapsack", "death_storage",
		"gravestone", "poh_costume_room", "poh_menagerie", "potion_storage_device");
	/**
	 * Own wealth the plugin does not read as items today. rune_pouch is raw type indexes, not item ids. Group
	 * storage is out of the own-player contract. The others have no container read in the plugin.
	 */
	static final List<String> UNSUPPORTED_STORAGE = List.of("rune_pouch", "poh_storage", "stash_units",
		"herb_sack_gem_bag_coal_bag", "item_charges", "group_storage", "boat_storage");

	private WealthLedger()
	{
	}

	/** One location's reading. items is null unless the location is observed (or stale). */
	static final class Location
	{
		final String name;
		final String state;
		final Long observedAtMs;
		final SortedMap<Integer, Long> items;
		final String note;

		Location(String name, String state, Long observedAtMs, SortedMap<Integer, Long> items, String note)
		{
			this.name = name;
			this.state = state;
			this.observedAtMs = observedAtMs;
			this.items = items;
			this.note = note;
		}

		/** Items are present. For BOUNDED escrow they are the provably locked part; more may sit in the collect box. */
		boolean readable()
		{
			return OBSERVED.equals(state) || STALE.equals(state) || BOUNDED.equals(state) || UNDATED.equals(state)
				|| CARRIED.equals(state);
		}
	}

	/** One active GE slot, split into the provably locked part and the possibly-uncollected part. */
	static final class Escrow
	{
		final int slot;
		final String side;
		final int itemId;
		final String offerState;
		final long lockedCoins;
		final long lockedItems;
		final long maxUncollectedCoins;
		final long maxUncollectedItems;
		final String escrowState;
		/** Raw slot counters, kept so two snapshots can tell the same offer from a replaced one. */
		long total, done, price, spent;

		Escrow(int slot, String side, int itemId, String offerState, long lockedCoins, long lockedItems,
			long maxUncollectedCoins, long maxUncollectedItems)
		{
			this.slot = slot;
			this.side = side;
			this.itemId = itemId;
			this.offerState = offerState;
			this.lockedCoins = lockedCoins;
			this.lockedItems = lockedItems;
			this.maxUncollectedCoins = maxUncollectedCoins;
			this.maxUncollectedItems = maxUncollectedItems;
			this.escrowState = maxUncollectedCoins == 0 && maxUncollectedItems == 0 ? OBSERVED : BOUNDED;
		}
	}

	static final class Holdings
	{
		final String accountKey;
		final String accountKeySource;
		final long asOfMs;
		final Map<String, Location> locations;
		final List<Escrow> escrow;

		Holdings(String accountKey, String accountKeySource, long asOfMs, Map<String, Location> locations, List<Escrow> escrow)
		{
			this.accountKey = accountKey;
			this.accountKeySource = accountKeySource;
			this.asOfMs = asOfMs;
			this.locations = locations;
			this.escrow = escrow;
		}

		/** Names of locations in the given state, in fixed order. */
		List<String> inState(String state)
		{
			List<String> out = new ArrayList<>();
			for (Location l : locations.values())
			{
				if (l.state.equals(state))
				{
					out.add(l.name);
				}
			}
			return out;
		}

		boolean coreComplete()
		{
			for (String name : CORE)
			{
				if (!OBSERVED.equals(locations.get(name).state))
				{
					return false;
				}
			}
			return true;
		}

		/**
		 * Known item totals over locations read in THIS snapshot (observed, stale, undated, bounded escrow). Carried
		 * readings are left out: the real 0.7.39 run withdrew a coin after the bank closed, so adding the carried bank
		 * would count that coin twice.
		 */
		SortedMap<Integer, Long> knownItems()
		{
			return itemsWhere(false);
		}

		/** Items in carried locations only: last readings from earlier snapshots, possibly out of date. */
		SortedMap<Integer, Long> carriedItems()
		{
			return itemsWhere(true);
		}

		private SortedMap<Integer, Long> itemsWhere(boolean carried)
		{
			SortedMap<Integer, Long> out = new TreeMap<>();
			for (Location l : locations.values())
			{
				if (l.readable() && CARRIED.equals(l.state) == carried)
				{
					l.items.forEach((id, qty) -> out.merge(id, qty, Math::addExact));
				}
			}
			return out;
		}

		/** Coins plus platinum tokens at 1000 each, over locations read in this snapshot only. */
		long knownCoins()
		{
			return coins(knownItems());
		}

		static long coins(SortedMap<Integer, Long> items)
		{
			return Math.addExact(items.getOrDefault(COINS, 0L),
				Math.multiplyExact(items.getOrDefault(PLATINUM_TOKEN, 0L), PLATINUM_TOKEN_COINS));
		}

		/**
		 * Ownership answer for C5. "yes" lists where the item was seen. "no_in_observed_locations" never means
		 * "not owned" while any location is unknown, stale, bounded or unsupported. "unknown" when nothing was read.
		 */
		Map<String, Object> owns(int itemId)
		{
			Map<String, Object> out = new LinkedHashMap<>();
			List<Map<String, Object>> seen = new ArrayList<>();
			boolean anyRead = false;
			for (Location l : locations.values())
			{
				if (!l.readable())
				{
					continue;
				}
				anyRead = true;
				Long qty = l.items.get(itemId);
				if (qty != null && qty > 0)
				{
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("location", l.name);
					row.put("qty", qty);
					row.put("as_of_ms", l.observedAtMs);
					row.put("state", l.state);
					seen.add(row);
				}
			}
			out.put("item_id", itemId);
			out.put("answer", !seen.isEmpty() ? "yes" : anyRead ? "no_in_observed_locations" : UNKNOWN);
			out.put("seen", seen);
			out.put("unknown_locations", inState(UNKNOWN));
			out.put("stale_locations", inState(STALE));
			out.put("bounded_locations", inState(BOUNDED));
			out.put("unsupported_locations", inState(UNSUPPORTED));
			out.put("undated_locations", inState(UNDATED));
			out.put("carried_locations", inState(CARRIED));
			return out;
		}

		/**
		 * Fill locations this snapshot did not read (bank closed, storage not opened) with the earlier reading of
		 * the same account, marked CARRIED with its original time. A carried reading can be out of date: the game
		 * does not resend a closed bank, and play on another client or on mobile between the two snapshots is not
		 * visible. It is never OBSERVED, so it never makes a view core-complete.
		 */
		Holdings carryForward(Holdings earlier)
		{
			if (earlier == null || accountKey == null || !accountKey.equals(earlier.accountKey) || earlier.asOfMs >= asOfMs)
			{
				return this;
			}
			Map<String, Location> locs = new LinkedHashMap<>(locations);
			for (Location now : locations.values())
			{
				Location before = earlier.locations.get(now.name);
				if (UNKNOWN.equals(now.state) && before != null && before.readable() && !now.name.equals("ge_escrow")
					&& !now.name.equals("inventory") && !now.name.equals("equipment"))
				{
					locs.put(now.name, new Location(now.name, CARRIED, before.observedAtMs, before.items,
						"carried from snapshot at " + earlier.asOfMs + " (" + before.state + "); may be out of date"));
				}
			}
			return new Holdings(accountKey, accountKeySource, asOfMs, Collections.unmodifiableMap(locs), escrow);
		}

		/** Compact JSON-ready view: the derived result other lanes and the site read. */
		Map<String, Object> toMap()
		{
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("contract", CONTRACT);
			out.put("account_key", accountKey);
			out.put("account_key_source", accountKeySource);
			out.put("as_of_ms", asOfMs);
			Map<String, Object> locs = new LinkedHashMap<>();
			for (Location l : locations.values())
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("state", l.state);
				m.put("observed_at_ms", l.observedAtMs);
				m.put("stacks", l.items == null ? null : l.items.size());
				m.put("coins", l.items == null ? null
					: l.items.getOrDefault(COINS, 0L) + l.items.getOrDefault(PLATINUM_TOKEN, 0L) * PLATINUM_TOKEN_COINS);
				if (l.note != null)
				{
					m.put("note", l.note);
				}
				locs.put(l.name, m);
			}
			out.put("locations", locs);
			List<Map<String, Object>> esc = new ArrayList<>();
			long maxCoins = 0;
			for (Escrow e : escrow)
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("slot", e.slot);
				m.put("side", e.side);
				m.put("item_id", e.itemId);
				m.put("offer_state", e.offerState);
				m.put("locked_coins", e.lockedCoins);
				m.put("locked_items", e.lockedItems);
				m.put("max_uncollected_coins", e.maxUncollectedCoins);
				m.put("max_uncollected_items", e.maxUncollectedItems);
				m.put("escrow_state", e.escrowState);
				esc.add(m);
				maxCoins += e.maxUncollectedCoins;
			}
			out.put("ge_escrow", esc);
			out.put("known_coins", knownCoins());
			out.put("carried_coins_last_read", coins(carriedItems()));
			out.put("ge_possibly_uncollected_coins_max", maxCoins);
			out.put("core_complete", coreComplete());
			out.put("account_total_claimed", false);
			out.put("unknown_locations", inState(UNKNOWN));
			out.put("stale_locations", inState(STALE));
			out.put("bounded_locations", inState(BOUNDED));
			out.put("unsupported_locations", inState(UNSUPPORTED));
			out.put("undated_locations", inState(UNDATED));
			out.put("carried_locations", inState(CARRIED));
			return out;
		}
	}

	static Holdings fromSnapshot(Map<?, ?> snap)
	{
		return fromSnapshot(snap, DEFAULT_STALE_AFTER_MS, null, null);
	}

	/**
	 * Project a built snapshot. asOf is the snapshot's captured_at. Inventory, equipment and GE slots are live
	 * reads at that moment. Bank and other storages carry their own observed_at (last time the game sent them in
	 * this login session; the plugin clears these on login, so a previous account never leaks in).
	 * There is no shared public account/mode epoch yet (integrator contract 2026-10-19). A caller that has one passes
	 * callerIdentity; otherwise the key comes from the snapshot's own account block.
	 *
	 * canonical maps a noted or placeholder item id to its base id (in the plugin: ItemManager::canonicalize). The
	 * snapshot keeps raw ids, so without it a noted withdraw shows as -N of one id and +N of another. null = no mapping,
	 * and then noted moves stay unexplained (visible, never hidden).
	 */
	static Holdings fromSnapshot(Map<?, ?> snap, long staleAfterMs, String callerIdentity, IntUnaryOperator canonical)
	{
		IntUnaryOperator canon = canonical == null ? IntUnaryOperator.identity() : canonical;
		Long capturedS = asLong(snap.get("captured_at"));
		if (capturedS == null || capturedS <= 0)
		{
			throw new IllegalArgumentException("snapshot without captured_at");
		}
		long asOf = capturedS * 1000L;
		Map<?, ?> flags = asMap(snap.get("capture_flags"));
		Map<String, Location> locs = new LinkedHashMap<>();

		locs.put("inventory", flag(flags, "inventory_synced")
			? new Location("inventory", OBSERVED, asOf, list(snap.get("inventory"), canon), null)
			: new Location("inventory", UNKNOWN, null, null, "inventory container not sent"));
		locs.put("equipment", flag(flags, "equipment_synced")
			? new Location("equipment", OBSERVED, asOf, equipment(snap.get("equipment"), canon), null)
			: new Location("equipment", UNKNOWN, null, null, "equipment container not sent"));
		locs.put("bank", storage("bank", flag(flags, "bank_synced") ? asMap(snap.get("bank")) : null, asOf, staleAfterMs,
			"bank not opened this login session", canon));

		List<Escrow> escrow = new ArrayList<>();
		Object offers = snap.get("ge_offers");
		if (offers instanceof List)
		{
			SortedMap<Integer, Long> locked = new TreeMap<>();
			boolean bounded = false;
			Set<Integer> slots = new HashSet<>();
			for (Object o : (List<?>) offers)
			{
				Escrow e = escrow(asMap(o), canon);
				if (!slots.add(e.slot))
				{
					throw new IllegalArgumentException("two ge_offers rows for slot " + e.slot);
				}
				escrow.add(e);
				bounded |= BOUNDED.equals(e.escrowState);
				if (e.lockedCoins > 0)
				{
					locked.merge(COINS, e.lockedCoins, Math::addExact);
				}
				if (e.lockedItems > 0)
				{
					locked.merge(e.itemId, e.lockedItems, Math::addExact);
				}
			}
			locs.put("ge_escrow", new Location("ge_escrow", bounded ? BOUNDED : OBSERVED, asOf, locked,
				bounded ? "filled part may already be collected; counted as an upper bound only" : null));
		}
		else
		{
			locs.put("ge_escrow", new Location("ge_escrow", UNKNOWN, null, null, "no GE slot read in snapshot"));
		}

		for (String name : OPTIONAL_STORAGE)
		{
			locs.put(name, storage(name, asMap(snap.get(name)), asOf, staleAfterMs, "not opened or checked this login session",
				canon));
		}
		for (String name : UNSUPPORTED_STORAGE)
		{
			locs.put(name, new Location(name, UNSUPPORTED, null, null, null));
		}
		String key = callerIdentity != null ? callerIdentity : accountKey(asMap(snap.get("account")));
		return new Holdings(key, callerIdentity != null ? "caller" : "snapshot_account_block", asOf,
			Collections.unmodifiableMap(locs),
			Collections.unmodifiableList(escrow));
	}

	/**
	 * Account key = account hash + game profile (STANDARD/LEAGUE/...) + iron mode. Bank Memory keys the same way so
	 * a League or Deadman bank never merges into the main game. null when the hash is missing or -1.
	 */
	static String accountKey(Map<?, ?> account)
	{
		if (account == null)
		{
			return null;
		}
		Object hash = account.get("account_hash");
		if (hash == null || "-1".equals(String.valueOf(hash)) || String.valueOf(hash).isEmpty())
		{
			return null;
		}
		return hash + "|" + account.get("profile_type") + "|" + account.get("account_type");
	}

	/**
	 * GE slot escrow. The collect box holds bought items/sale coins/refunds; the player may collect them while the
	 * offer is still open, and RuneLite's counters stay cumulative, so that part is only an upper bound.
	 */
	static Escrow escrow(Map<?, ?> o)
	{
		return escrow(o, IntUnaryOperator.identity());
	}

	static Escrow escrow(Map<?, ?> o, IntUnaryOperator canon)
	{
		if (o == null)
		{
			throw new IllegalArgumentException("ge offer row");
		}
		int slot = (int) req(o, "slot");
		int itemId = canon.applyAsInt((int) req(o, "item_id"));
		String state = String.valueOf(o.get("state"));
		long total = req(o, "total_qty");
		long done = req(o, "transferred_qty");
		long price = req(o, "price_per_item");
		long spent = req(o, "spent");
		if (total < 0 || done < 0 || done > total || price < 0 || spent < 0)
		{
			throw new IllegalArgumentException("ge offer counters");
		}
		boolean buy = state.equals("BUYING") || state.equals("BOUGHT") || state.equals("CANCELLED_BUY");
		boolean open = state.equals("BUYING") || state.equals("SELLING");
		Escrow e = escrowOf(slot, itemId, state, buy, open, total, done, price, spent);
		e.total = total;
		e.done = done;
		e.price = price;
		e.spent = spent;
		return e;
	}

	private static Escrow escrowOf(int slot, int itemId, String state, boolean buy, boolean open, long total, long done,
		long price, long spent)
	{
		if (buy)
		{
			// Unfilled coins stay locked while the offer is open. Bought items and the overpay refund may be collected.
			long unfilledCoins = Math.multiplyExact(total - done, price);
			long refund = Math.max(0L, Math.multiplyExact(done, price) - spent);
			if (open)
			{
				return new Escrow(slot, "buy", itemId, state, unfilledCoins, 0, refund, done);
			}
			// Finished or cancelled: everything left sits in the collect box, which the player may have emptied in part.
			return new Escrow(slot, "buy", itemId, state, 0, 0, unfilledCoins + refund, done);
		}
		long unsold = total - done;
		if (open)
		{
			return new Escrow(slot, "sell", itemId, state, 0, unsold, spent, 0);
		}
		return new Escrow(slot, "sell", itemId, state, 0, 0, spent, unsold);
	}

	private static Location storage(String name, Map<?, ?> block, long asOf, long staleAfterMs, String missing,
		IntUnaryOperator canon)
	{
		if (block == null)
		{
			return new Location(name, UNKNOWN, null, null, missing);
		}
		Long observedS = asLong(block.get("observed_at"));
		if (observedS == null)
		{
			// The container was present when the snapshot was built, so its items are the client's copy at that moment,
			// but the plugin has no send time for it. Real 0.7.39 run: first bank open after login had no observed_at.
			return new Location(name, UNDATED, asOf, list(block.get("items"), canon),
				"present at capture, game send time not recorded");
		}
		long at = observedS * 1000L;
		String state = asOf - at > staleAfterMs ? STALE : OBSERVED;
		return new Location(name, state, at, list(block.get("items"), canon), null);
	}

	private static SortedMap<Integer, Long> list(Object raw, IntUnaryOperator canon)
	{
		SortedMap<Integer, Long> out = new TreeMap<>();
		if (raw == null)
		{
			return out;
		}
		if (!(raw instanceof List))
		{
			throw new IllegalArgumentException("item list");
		}
		for (Object row : (List<?>) raw)
		{
			add(out, asMap(row), canon);
		}
		return out;
	}

	private static SortedMap<Integer, Long> equipment(Object raw, IntUnaryOperator canon)
	{
		SortedMap<Integer, Long> out = new TreeMap<>();
		Map<?, ?> slots = asMap(raw);
		if (slots != null)
		{
			for (Object row : slots.values())
			{
				add(out, asMap(row), canon);
			}
		}
		return out;
	}

	private static void add(SortedMap<Integer, Long> out, Map<?, ?> row, IntUnaryOperator canon)
	{
		if (row == null)
		{
			throw new IllegalArgumentException("item row");
		}
		long id = req(row, "id");
		long qty = req(row, "qty");
		if (id <= 0 || id > Integer.MAX_VALUE || qty < 0)
		{
			throw new IllegalArgumentException("item id/qty");
		}
		if (qty > 0)
		{
			int base = canon.applyAsInt((int) id);
			if (base <= 0)
			{
				throw new IllegalArgumentException("canonical item id");
			}
			out.merge(base, qty, Math::addExact);
		}
	}

	private static boolean flag(Map<?, ?> flags, String key)
	{
		return flags != null && Boolean.TRUE.equals(flags.get(key));
	}

	private static long req(Map<?, ?> m, String key)
	{
		Long v = asLong(m.get(key));
		if (v == null)
		{
			throw new IllegalArgumentException(key);
		}
		return v;
	}

	static Long asLong(Object x)
	{
		if (!(x instanceof Number))
		{
			return null;
		}
		Number n = (Number) x;
		long v = n.longValue();
		return n.doubleValue() == v ? v : null;
	}

	static Map<?, ?> asMap(Object x)
	{
		return x instanceof Map ? (Map<?, ?>) x : null;
	}
}
