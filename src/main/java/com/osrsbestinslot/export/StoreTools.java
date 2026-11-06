package com.osrsbestinslot.export;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntToLongFunction;
import net.runelite.api.Actor;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.infobox.Timer;
import net.runelite.client.util.Text;

/** Staff store tools: shop visit state, store transaction rows, the nearby-player feed and the reset clock. */
final class StoreTools
{
	private final AccountConnectPlugin plugin;

	StoreTools(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Shop interface (SHOPMAIN 300) open — so a Buy/Sell menu click is a general-store transaction. */
	volatile boolean shopOpen;

	/** Is a shop interface open right now? Read by the overlays to decide whether to draw. */
	boolean isShopOpen()
	{
		return shopOpen;
	}

	/**
	 * Where the shop's item grid ENDS on screen, so an overlay can sit directly beneath it.
	 *
	 * RuneLite draws overlays over the interface, never inside it, so "below the items" has to be
	 * computed from the live widget each frame — the shop window moves with the client size and the
	 * fixed/resizable layout. Child 16 is the item grid (the same child the stock reader uses).
	 *
	 * Returns null when the shop is closed or the widget is not laid out yet; the caller then falls
	 * back to its own anchor rather than drawing at a stale position.
	 */
	Point shopItemsBottomLeft()
	{
		if (plugin.client == null || !shopOpen)
		{
			return null;
		}
		Widget grid = plugin.client.getWidget(AccountConnectPlugin.SHOP_GROUP_ID, 16);
		if (grid == null || grid.isHidden())
		{
			return null;
		}
		net.runelite.api.Point loc = grid.getCanvasLocation();
		if (loc == null || grid.getHeight() <= 0)
		{
			return null;
		}
		// ANCHOR TO THE GRID BOX, NOT THE ITEMS. Measured twice on a live client 2026-09-03:
		//   - grid bottom alone put the panels ON the "Value check" / "Quantity" controls, because
		//     the widget box extends past them;
		//   - lowest-occupied-item put them INSIDE the grid as soon as the shop gained a third row.
		// The grid box is stable whatever the stock count, so use it and clear the control row by a
		// fixed margin measured from that same box.
		int gridBottom = loc.getY() + grid.getHeight();
		// RIGHT EDGE, not a fraction of the width. The caller subtracts its own panel width, so both
		// panels end flush with the shop's right side however wide each one is.
		//
		// Prefer the SHOP WINDOW's right edge over the item grid's: measured on a live client the
		// grid box ends ~58px inside the window, so aligning to it left the panels short of the
		// right side that was asked for. Fall back to the grid when the root widget is unreadable.
		int right = loc.getX() + grid.getWidth();
		Widget root = plugin.client.getWidget(AccountConnectPlugin.SHOP_GROUP_ID, 0);
		if (root != null && !root.isHidden() && root.getCanvasLocation() != null && root.getWidth() > 0)
		{
			right = root.getCanvasLocation().getX() + root.getWidth();
		}
		int gridRight = right - 10;
		// +58 clears the control row entirely. Measured: the grid box bottom sits ~14px above the
		// "Value check" / "Quantity" row, which is ~26px tall, so +30 landed ON it (seen live).
		return new Point(gridRight, gridBottom + 58);
	}

	/** Test seam: drive the shop-open gate without a game client. */
	void setShopOpenForTest(boolean open)
	{
		shopOpen = open;
	}

	// Store-transfer counterparty inference: while a shop is open, accumulate every nearby player seen
	// (rsn -> [closestTileDist, firstTick, lastTick, combatLevel]). Attached to the store event as `nearby`
	// so an UNTRACKED receiver of the general-store method (staff sells cheap, receiver buys it out) can be
	// inferred from who stood there over the visit — presence + persistence + proximity. Reset on shop open.
	final Map<String, int[]> shopVisitNearby = new LinkedHashMap<>();
	/**
	 * Item -> quantity in the SHOP's own stock, from the last container change while a shop is open.
	 *
	 * This is what makes the counterparty visible on the general-store method. The staff account sells
	 * an item into the shop and an untracked player buys it out; that second half is invisible in the
	 * staff account's own inventory, so the store event alone can never say who received the value.
	 * The shop container CAN see it: the stock of an item we just sold goes DOWN when somebody takes it.
	 * Pairing that moment with who is standing there names the receiver.
	 */
	final Map<Integer, Integer> shopStock = new HashMap<>();
	/** Items this visit's own sells put INTO the shop — only these are watched for a taker. */
	final Set<Integer> soldThisVisit = new LinkedHashSet<>();
	/**
	 * Of those, the items the shop already stocked when we sold (stock > 0 at the click): default stock.
	 * The shop normalises default stock back down one at a time within seconds of a sale (F-A1, rig
	 * 2026-09-27: Pot 6->5 after every sale), which is not a customer.
	 */
	final Set<Integer> defaultStockSoldThisVisit = new HashSet<>();
	/** Client tick of our most recent sell of each item this visit, so a store_taken can say how soon after it came. */
	final Map<Integer, Integer> lastSellTickThisVisit = new HashMap<>();
	/** Name of the NPC we were trading with when the shop opened. A buyer must be trading with the same NPC. */
	String shopkeeperName;
	/** The shopkeeper NPC itself (by index). with_shopkeeper compares identity, never the name: two NPCs can share a name. */
	int shopkeeperIndex = -1;
	/**
	 * Nearby players AT THE MOMENT of the transaction, as opposed to across the whole visit.
	 *
	 * A visit-wide list answers "who was around at some point", which is a weaker claim than the one a
	 * dispute needs. This is sampled when the buy/sell actually fires, so the event can state who was
	 * present AT the transaction and for how long around it.
	 */
	volatile List<Map<String, Object>> nearbyAtTx;
	// ---- STORE RESET CLOCK ----
	//
	// A general store's stock cycles on a fixed 60s clock. An item put into the shop disappears at the
	// next tick of that clock, NOT 60s after it was sold — so an item sold 1s after a reset is gone in
	// 59s, and one sold at 58s is gone in 2. That difference is the whole risk of the delivery method:
	// a seller currently puts a junk item in and watches it vanish to feel out where the clock is,
	// which is a guess repeated by eye every visit. Measured over 56 real sessions, the gap between the
	// junk probe and the first real item ran 5s to 146s — that spread IS the guesswork.
	//
	// The clock's PHASE is what matters, and one observation fixes it: the moment any tracked item
	// vanishes from the shop, we know a reset just happened, and every reset after that is anchored to
	// it. So a single junk probe pins the phase for the rest of the visit.
	//
	// The anchor is only valid while the shop stays open. On close, hop or logout it is dropped rather
	// than carried, because a stale phase shown as a live countdown is worse than no countdown at all —
	// the user would push a high-value item into a window that has already closed.
	private static final long STORE_RESET_PERIOD_MS = 60_000L;
	private static final int COINS_ITEM_ID = 995;		// infobox icon
	/**
	 * The junk PROBE item — the first thing sold into the shop this visit.
	 *
	 * Its disappearance is the reset (see handleShopStockChanged). Everything sold after it is real
	 * merchandise whose disappearance means a customer, so only this one may move the clock.
	 */
	int storeProbeItem;
	/** Item id of our most recent own store buy, and when — see the buy-back note in onMenuOptionClicked. */
	int lastSelfBuyItem;
	long lastSelfBuyAtMs;
	/** How long a self-buy suppresses the counterparty inference for that item. */
	private static final long SELF_BUY_SUPPRESS_MS = 3_000L;
	/** Wall-clock ms of an OBSERVED reset; 0 = phase unknown, show nothing. */
	long storeResetAnchorMs;
	/** When the shop container last changed at all. The anchor needs two observations, not one. */
	long lastStockChangeMs;
	/**
	 * EVIDENCE-GRADE cycle phase, kept apart from storeResetAnchorMs on purpose.
	 *
	 * The countdown anchor may move on one probe vanishing (a buyer can cause that) or on two changes one
	 * period apart (a coincidence can cause that). A countdown can live with that; an evidence field that a
	 * reader uses to tell a decay from a buy cannot. This phase is confirmed only by at least
	 * RESET_PHASE_MIN_OBS container changes on distinct cycles that all sit whole periods apart (more when
	 * the shop is busy, see requiredPhaseSupport), and it is the only phase store_taken reports.
	 * 0 = not confirmed.
	 */
	long evidencePhaseMs;
	/** How many container changes support evidencePhaseMs (>= RESET_PHASE_MIN_OBS once confirmed). */
	int evidencePhaseSupport;
	/** Cycle index (from evidencePhaseMs) of the last change that raised the support, so one cycle counts once. */
	private long evidencePhaseLastCycle;
	/** Wall-clock ms of our own last store_sell or store_buy click this visit; 0 = none. */
	long lastOwnStoreClickMs;
	/** Recent container change times (ms), oldest first, bounded by count and age. */
	private final ArrayDeque<Long> resetPhaseObs = new ArrayDeque<>();
	/** Changes on distinct cycles needed before a phase is evidence. Two can be a coincidence. */
	static final int RESET_PHASE_MIN_OBS = 3;
	/** How far a gap may sit from a whole number of periods: just over one game tick. */
	static final long PHASE_ALIGN_TOL_MS = 700L;
	/**
	 * A change this soon after our own store click never supports the evidence phase: one full period plus
	 * the tolerance. Our sells land at a time the seller picks from the countdown, and anything that follows
	 * them (a buyer reacting after any delay, or the sold unit's own first decay, which comes within one
	 * period) sits at a fixed offset from our click, not from the cycle. Only a fall with a full quiet period
	 * behind it (the 2nd and later decay steps of a stack, or a quiet visit) can be the shop's own tick.
	 */
	static final long OWN_ACTION_QUIET_MS = STORE_RESET_PERIOD_MS + PHASE_ALIGN_TOL_MS;
	/** History bounds: at most this many change times, none older than RESET_PHASE_WINDOW_PERIODS periods. */
	static final int RESET_PHASE_HISTORY = 32;
	static final int RESET_PHASE_WINDOW_PERIODS = 5;
	/**
	 * Accepted chance that unrelated changes line up by luck. In a busy shop many changes sit in the history,
	 * and three of them agree by chance far more often than one would guess (simulated: over half of
	 * 10-minute visits with a change every 15 s). So the support needed grows with the history size, and is
	 * never below RESET_PHASE_MIN_OBS. Busy shops then mostly report no phase: a missing field, never a
	 * wrong one.
	 */
	static final double RESET_PHASE_FALSE_RATE = 0.01;
	/** requiredPhaseSupport(n) for n = 0 .. RESET_PHASE_HISTORY, computed once. */
	private static final int[] PHASE_SUPPORT_NEEDED = phaseSupportTable();
	private static final int MAX_NEARBY_TRACKED = 64;	// bound the per-visit map
	static final int NEARBY_FIELD_CAP = 24;		// bound the emitted nearby[] list

	// Store transacted-price capture: a store buy/sell click snapshots the pre-transaction coin count and
	// arms this pending; the next INVENTORY change reads the post count and the |delta| is the exact gp that
	// changed hands (see store-price-feasibility.md). Emit is deferred to that inventory change, not the click.
	// ONE PENDING PER ITEM, in click order. A single slot kept only the last click: two different items bought
	// in one tick gave one store_buy, and the other item got no event at all (Torva helm + fury, 2026-09-26).
	// Client thread only.
	final Map<Integer, StorePending> storePendings = new LinkedHashMap<>();
	/** Ticks after which an unresolved store pending is stale (a failed click fires no inventory change). */
	static final int STORE_PENDING_MAX_TICKS = 3;
	/** No item-count baseline was captured, so the executed quantity cannot be measured. */
	static final long UNKNOWN_ITEM_COUNT = -1L;

	/** An armed store buy/sell awaiting its inventory-change resolution. coinsBefore is a long: bank-stack totals overflow int. */
	static final class StorePending
	{
		final String type;			// "store_buy" | "store_sell"
		final int item;
		final int qty;				// the CLICK's quantity (intent) — see qtyExecuted resolution
		final long coinsBefore;
		final long itemBefore;		// item count at arm time, so the EXECUTED quantity can be measured
		final int tick;				// client tick at arm time, for staleness + same-tick-batch detection
		final boolean ambiguous;	// a second click landed on the same tick → delta merges two txns, omit price
		/**
		 * True when {@code qty} is the SUM of several same-tick clicks rather than one click's quantity.
		 * The coin delta is still ground truth, so {@code gp_total} stays exact — but a click can FAIL
		 * (out of stock, full inventory, not enough coins) while still having been counted here, so the
		 * merged quantity is click INTENT, not confirmed executed quantity. Any figure divided by it
		 * (i.e. unit_price_gp) would therefore be wrong — half price if one of two clicks failed — so it
		 * is omitted. Never derive a unit price from an uncertain denominator.
		 */
		final boolean qtyMerged;

		StorePending(String type, int item, int qty, long coinsBefore, int tick, boolean ambiguous)
		{
			this(type, item, qty, coinsBefore, tick, ambiguous, false);
		}

		StorePending(String type, int item, int qty, long coinsBefore, int tick, boolean ambiguous, boolean qtyMerged)
		{
			this(type, item, qty, coinsBefore, UNKNOWN_ITEM_COUNT, tick, ambiguous, qtyMerged);
		}

		StorePending(String type, int item, int qty, long coinsBefore, long itemBefore, int tick,
			boolean ambiguous, boolean qtyMerged)
		{
			this.type = type;
			this.item = item;
			this.qty = qty;
			this.coinsBefore = coinsBefore;
			this.itemBefore = itemBefore;
			this.tick = tick;
			this.ambiguous = ambiguous;
			this.qtyMerged = qtyMerged;
		}
	}

	/**
	 * Shop closed with a click still awaiting its inventory change (buy-then-close is a common sequence).
	 * The coin delta can no longer be attributed, but the transaction itself did happen — emit the degraded
	 * {item, qty} form rather than losing the event, which is what the pre-price behaviour reported anyway.
	 * Package-private seam so the close path is unit testable without a live client.
	 *
	 * Carried forward from 0.7.1 (the build currently on the Hub). Dropping the pending here, as this
	 * branch previously did, silently loses every buy-then-close transaction.
	 */
	void flushStorePendingOnShopClose()
	{
		int tick = plugin.client == null ? 0 : plugin.client.getTickCount();
		// A click whose window passed with no change moving its item did not execute: no row for it.
		storePendings.values().removeIf(p -> tick - p.tick > STORE_PENDING_MAX_TICKS);
		List<StorePending> armed = new ArrayList<>(storePendings.values());
		storePendings.clear();
		if (!plugin.activityLogActive())
		{
			return;
		}
		for (StorePending p : armed)
		{
			// coinsAfter == coinsBefore -> zero delta -> fails the sign check -> {item, qty} only, never a guess.
			plugin.events.emitEvent(p.type, buildStoreTxFields(p.type, p.item, p.qty, p.coinsBefore, p.coinsBefore, p.ambiguous, p.qtyMerged));
		}
	}

	/**
	 * Fold a new store click into any pending one still inside the resolution window.
	 *
	 * <p>Production evidence (2026-08-15 audit): on the newest clients, {@code gp_total} was missing from
	 * <b>37.1% of multi-quantity store buys</b> (140 of 377 on 2026-08-15) against 8.0% at qty=1. Cause:
	 * a player spam-clicking "Buy 10" lands two clicks in quick succession, which the previous code
	 * flagged {@code ambiguous} and then dropped the gold for — deliberately, "rather than misattribute
	 * it". General-store selling is how gold is delivered, so that was the delivery path going dark.
	 *
	 * <p>The flag was over-cautious for the common case. If {@code prev} is still armed then it was never
	 * resolved, and a pending is only consumed by an inventory change — so no inventory change has
	 * happened yet, so {@code prev.coinsBefore} still predates BOTH transactions. When the two clicks are
	 * the same type on the same item, the eventual coin delta therefore covers exactly their combined
	 * quantity: keep the ORIGINAL {@code coinsBefore} and SUM the quantities, and {@code gp_total} comes
	 * out exact rather than absent.
	 *
	 * <p><b>The window is UNRESOLVED-ness, not same-tick.</b> Field-observed 2026-08-17: two "Buy 10"
	 * clicks a single tick apart produced {@code qty:10, gp_total:11325, unit_price_gp:1132} — one
	 * click's quantity against both clicks' gold, with no {@code qty_merged} label, so a fabricated unit
	 * price (true ~755) was indistinguishable downstream from a correct single-click row. That is the
	 * exact half-price failure the conservative rule exists to prevent, arriving through the door the
	 * {@code prev.tick == tick} equality left open. What makes {@code prev.coinsBefore} usable is that
	 * nothing has consumed the pending, so the merge now spans the same {@link #STORE_PENDING_MAX_TICKS}
	 * window the resolver already uses to decide a pending is still live. Beyond it the resolver would
	 * discard the pending anyway, so the two agree by construction.
	 *
	 * <p>Genuine ambiguity is still respected. Clicks on a DIFFERENT item, or in a different direction,
	 * cannot be split out of one delta, so those stay {@code ambiguous} and still degrade to
	 * {item, qty} — the audit's rule that we never emit a guessed number is unchanged.
	 */
	static StorePending mergeStorePending(StorePending prev, String type, int item, int qty, long coinsBefore, int tick)
	{
		return mergeStorePending(prev, type, item, qty, coinsBefore, UNKNOWN_ITEM_COUNT, tick);
	}

	static StorePending mergeStorePending(StorePending prev, String type, int item, int qty, long coinsBefore,
		long itemBefore, int tick)
	{
		if (prev != null && tick - prev.tick >= 0 && tick - prev.tick <= STORE_PENDING_MAX_TICKS)
		{
			if (prev.item == item && prev.type.equals(type) && !prev.ambiguous)
			{
				// Same item, same direction, prev never resolved → one delta covers both. Both baselines
				// stay the ORIGINAL pre-transaction readings, for the same reason: nothing has consumed
				// this pending, so no inventory change has been applied to either count yet.
				return new StorePending(type, item, prev.qty + qty, prev.coinsBefore, prev.itemBefore,
					tick, false, true);
			}
			// Different item or direction inside one window — the delta merges unrelated transactions.
			return new StorePending(type, item, qty, coinsBefore, itemBefore, tick, true, false);
		}
		return new StorePending(type, item, qty, coinsBefore, itemBefore, tick, false, false);
	}

	/**
	 * Resolve an armed store pending against the post-transaction coin count. Consumes the pending (one
	 * inventory change resolves at most one click) and emits the store event, with the exact-gp price when it
	 * can be cleanly attributed. Stale pendings (a failed click that never moved the inventory, then some
	 * unrelated later change) are dropped rather than paired. Package-private seam so the async path is unit
	 * testable without a live client.
	 */
	void resolveStorePendingOnInventoryChange(long coinsAfter, int currentTick)
	{
		resolveStorePendingOnInventoryChange(coinsAfter, UNKNOWN_ITEM_COUNT, currentTick);
	}

	/**
	 * @param itemAfter post-transaction count of the transacted item, or {@link #UNKNOWN_ITEM_COUNT} when it
	 *                  could not be read. Its distance from the pending's baseline is the quantity the
	 *                  server ACTUALLY moved, which is what makes a unit price safe to derive — see
	 *                  {@link #buildStoreTxFields}.
	 */
	void resolveStorePendingOnInventoryChange(long coinsAfter, long itemAfter, int currentTick)
	{
		resolveStorePendingsOnInventoryChange(coinsAfter, id -> itemAfter, currentTick);
	}

	/**
	 * Resolve the armed store pendings against one INVENTORY change. {@code countAfter} gives an item's count
	 * after the change, or is null when the inventory could not be read.
	 *
	 * <p>Stale pendings are dropped. When the item counts are known they decide whose change this is, also for
	 * a single click: a click resolves only on a change that moved its item its way. A failed sell (0 gp sale
	 * refused, item still held) used to resolve on any inventory change inside the window and send a store_sell
	 * for a sale that never happened (live run 2026-10-19, beside the real retry). One item moved: the whole
	 * coin delta is that item's, so its gp_total is exact, and the others stay armed with this change's coin
	 * count as their new baseline. Several items moved in this one change: each still gets its own event, but
	 * one coin delta cannot be split, so none carries gp_total.
	 */
	void resolveStorePendingsOnInventoryChange(long coinsAfter, IntToLongFunction countAfter,
		int currentTick)
	{
		storePendings.values().removeIf(p -> currentTick - p.tick > STORE_PENDING_MAX_TICKS);
		if (storePendings.isEmpty())
		{
			return;
		}
		List<StorePending> resolved = new ArrayList<>();
		if (countAfter == null || storePendings.values().stream().anyMatch(p -> p.itemBefore == UNKNOWN_ITEM_COUNT
			|| countAfter.applyAsLong(p.item) == UNKNOWN_ITEM_COUNT))
		{
			// Nothing to tell the clicks apart: this change resolves them all. Several get no price.
			resolved.addAll(storePendings.values());
		}
		else
		{
			for (StorePending p : storePendings.values())
			{
				long executed = executedQty(p, countAfter.applyAsLong(p.item));
				if (executed > 0)
				{
					resolved.add(p);
				}
			}
		}
		boolean shared = resolved.size() > 1;
		for (StorePending p : resolved)
		{
			storePendings.remove(p.item);
		}
		// The coins of this change belong to the resolved clicks. What stays armed counts from here.
		for (Map.Entry<Integer, StorePending> e : storePendings.entrySet())
		{
			StorePending p = e.getValue();
			e.setValue(new StorePending(p.type, p.item, p.qty, coinsAfter, p.itemBefore, p.tick, p.ambiguous, p.qtyMerged));
		}
		if (!plugin.activityLogActive())
		{
			return;
		}
		for (StorePending p : resolved)
		{
			long executed = executedQty(p, countAfter == null ? UNKNOWN_ITEM_COUNT : countAfter.applyAsLong(p.item));
			Map<String, Object> fields = buildStoreTxFields(p.type, p.item, p.qty, p.coinsBefore, coinsAfter,
				p.ambiguous || shared, p.qtyMerged, executed);
			addStoreContextFields(fields);	// nearby[] + world + loc — the candidate receivers of a store transfer
			plugin.events.emitEvent(p.type, fields);
		}
	}

	/**
	 * The quantity the server actually moved, from the item-count delta, or {@link #UNKNOWN_ITEM_COUNT} when
	 * either baseline is missing. Sign is normalised: a buy adds items, a sell removes them, so the
	 * magnitude is what matters and a delta pointing the wrong way is not this transaction's.
	 */
	static long executedQty(StorePending p, long itemAfter)
	{
		if (p.itemBefore == UNKNOWN_ITEM_COUNT || itemAfter == UNKNOWN_ITEM_COUNT)
		{
			return UNKNOWN_ITEM_COUNT;
		}
		long delta = "store_buy".equals(p.type) ? itemAfter - p.itemBefore : p.itemBefore - itemAfter;
		return delta < 0 ? UNKNOWN_ITEM_COUNT : delta;
	}

	/**
	 * Attach store-transfer context to a store event: `nearby` (players seen over the shop visit — the
	 * candidate receivers of the general-store method: staff sells cheap, an untracked player buys it out),
	 * `world`, `loc`. Instance method (needs the live client). nearby[] comes from the visit accumulator; a
	 * live snapshot backfills if the accumulator is empty (event fired before a tick ran).
	 */
	void addStoreContextFields(Map<String, Object> fields)
	{
		if (plugin.client == null)
		{
			return;
		}
		List<Map<String, Object>> nearby = nearbyFromVisit();
		if (nearby.isEmpty())
		{
			nearby = nearbyPlayersSnapshot(NEARBY_FIELD_CAP);
		}
		if (!nearby.isEmpty())
		{
			fields.put("nearby", nearby);		// everyone seen across the whole shop visit
		}
		// AND, separately, who was standing there at the CLICK. The two answer different questions and
		// a dispute is about the second: "nobody was around at some point in the visit" is far weaker
		// than "nobody was within N tiles when the item moved". Captured at the click rather than here
		// because this runs on the inventory change, which is one or more ticks later.
		List<Map<String, Object>> atTx = nearbyAtTx;
		if (atTx != null && !atTx.isEmpty())
		{
			fields.put("nearby_at_tx", atTx);
		}
		nearbyAtTx = null;
		fields.put("world", plugin.client.getWorld());
		Player self = plugin.client.getLocalPlayer();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me != null)
		{
			fields.put("loc", Arrays.asList(me.getX(), me.getY(), me.getPlane()));
		}
	}

	/**
	 * Ephemeral rows for the staff store overlay only. The upload readers below stay empty;
	 * rendering a visible player must not populate event context, journals or snapshots.
	 */
	List<Map<String, Object>> nearbyPlayersForOverlay(int cap)
	{
		List<Map<String, Object>> rows = new ArrayList<>();
		if (cap <= 0 || plugin.client == null || !isShopOpen() || !plugin.grants.storeToolsEnabled() || !plugin.grants.staffCaptureEnabled())
		{
			return rows;
		}
		Player self = plugin.client.getLocalPlayer();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me == null)
		{
			return rows;
		}
		List<Player> players = plugin.client.getPlayers();
		if (players == null)
		{
			return rows;
		}
		for (Player player : players)
		{
			if (player == null || player == self)
			{
				continue;
			}
			String name = player.getName();
			WorldPoint location = player.getWorldLocation();
			if (name == null || location == null)
			{
				continue;
			}
			name = Text.removeTags(name);
			int distance = location.distanceTo(me);
			if (name.isEmpty() || distance > StoreNearbyOverlay.DISPLAY_RANGE_TILES)
			{
				continue;
			}
			Map<String, Object> row = Fields.of("rsn", name, "dist", distance, "cb", player.getCombatLevel());
			rows.add(row);
		}
		rows.sort(Comparator
			.comparingInt((Map<String, Object> row) -> (Integer) row.get("dist"))
			.thenComparing(row -> (String) row.get("rsn")));
		int limit = Math.min(cap, StoreNearbyOverlay.DISPLAY_CAP);
		return new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
	}

	/** Snapshot up to {@code cap} nearest OTHER players right now: {rsn, dx, dy, dist, cb}, nearest first. */
	List<Map<String, Object>> nearbyPlayersSnapshot(int cap)
	{
		return nearbyPlayersSnapshot(cap, false);
	}

	/**
	 * The name of the NPC our own character is interacting with, or null. Read when the shop opens: the
	 * Trade click faces the shopkeeper, so this names the shop's owner for the buyer check.
	 */
	String currentShopkeeperName()
	{
		if (plugin.client == null || plugin.client.getLocalPlayer() == null)
		{
			return null;
		}
		Actor t = plugin.client.getLocalPlayer().getInteracting();
		return t instanceof NPC && t.getName() != null ? Text.removeTags(t.getName()) : null;
	}

	/** Index of the NPC our character faces when the shop opens, or -1. */
	int currentShopkeeperIndex()
	{
		if (plugin.client == null || plugin.client.getLocalPlayer() == null)
		{
			return -1;
		}
		Actor t = plugin.client.getLocalPlayer().getInteracting();
		return t instanceof NPC ? ((NPC) t).getIndex() : -1;
	}

	/**
	 * Same snapshot, and with {@code activity} each row also says what the player was doing: {@code anim}
	 * (animation id, -1 idle) and {@code interacting_npc} when they face an NPC, plus {@code with_shopkeeper}
	 * when that NPC is the shopkeeper we are trading with. Another player's shop screen is never visible,
	 * so this is the closest mechanical sign that they are trading with the same shop. Player targets are
	 * not named.
	 */
	List<Map<String, Object>> nearbyPlayersSnapshot(int cap, boolean activity)
	{
		if (!plugin.grants.captureEnabled()) return Collections.emptyList();

		List<Map<String, Object>> out = new ArrayList<>();
		if (plugin.client == null)
		{
			return out;
		}
		Player self = plugin.client.getLocalPlayer();
		List<Player> players = plugin.client.getPlayers();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me == null || players == null)
		{
			return out;
		}
		List<Player> others = new ArrayList<>();
		for (Player p : players)
		{
			if (p == null || p == self || p.getName() == null || p.getName().isEmpty() || p.getWorldLocation() == null)
			{
				continue;
			}
			others.add(p);
		}
		others.sort((a, b) -> Integer.compare(a.getWorldLocation().distanceTo(me), b.getWorldLocation().distanceTo(me)));
		for (Player p : others)
		{
			if (out.size() >= cap)
			{
				break;
			}
			WorldPoint loc = p.getWorldLocation();
			Map<String, Object> m = Fields.of(
				"rsn", Text.removeTags(p.getName()), "dx", loc.getX() - me.getX(), "dy", loc.getY() - me.getY(),
				"dist", loc.distanceTo(me), "cb", p.getCombatLevel());
			if (activity)
			{
				m.put("anim", p.getAnimation());
				Actor t = p.getInteracting();
				if (t instanceof NPC && t.getName() != null)
				{
					String npc = Text.removeTags(t.getName());
					m.put("interacting_npc", npc);
					// The SAME NPC, by index. A second trader of the same shop (a shop assistant) reads false here
					// but still shows its name in interacting_npc; a same-named NPC elsewhere never reads true.
					m.put("with_shopkeeper", shopkeeperIndex >= 0
						&& ((NPC) t).getIndex() == shopkeeperIndex);
				}
			}
			out.add(m);
		}
		return out;
	}

	/**
	 * Milliseconds until the next store reset, given an observed anchor and the current time.
	 *
	 * Returns 0 when the phase is unknown (no reset observed yet this visit) — the caller shows nothing
	 * rather than a guess. Pure and static so the phase arithmetic is testable without a game client.
	 */
	static long msUntilNextReset(long anchorMs, long nowMs)
	{
		if (anchorMs <= 0 || nowMs < anchorMs)
		{
			return 0;			// no anchor, or a clock that went backwards — refuse to guess
		}
		long since = nowMs - anchorMs;
		long remainder = since % STORE_RESET_PERIOD_MS;
		// Land exactly ON a tick => a full period remains, never 0 (0 is reserved for "unknown").
		return STORE_RESET_PERIOD_MS - remainder;
	}

	/** True when the phase is known and a countdown may be shown. */
	static boolean resetPhaseKnown(long anchorMs)
	{
		return anchorMs > 0;
	}

	/** The live countdown infobox, or null when none is shown. Client thread only. */
	Timer resetTimer;

	/**
	 * Show (or re-point) the countdown for the next reset.
	 *
	 * Called once per observed reset, so the box is replaced rather than accumulated — a leaked infobox
	 * is the obvious failure of this feature and the tests assert against it explicitly.
	 */
	void showResetTimer()
	{
		if (plugin.infoBoxManager == null || !plugin.grants.storeToolsEnabled() || !resetPhaseKnown(storeResetAnchorMs))
		{
			return;
		}
		removeResetTimer();
		long remainMs = msUntilNextReset(storeResetAnchorMs, System.currentTimeMillis());
		if (remainMs <= 0)
		{
			return;
		}
		BufferedImage icon = plugin.itemManager != null ? plugin.itemManager.getImage(COINS_ITEM_ID) : null;
		Timer t = new Timer(remainMs, ChronoUnit.MILLIS, icon, plugin);
		t.setTooltip("General store resets");
		resetTimer = t;
		plugin.infoBoxManager.addInfoBox(t);
	}

	/** Counts tick-driven re-arms. Test seam: the re-arm decision is otherwise invisible. */
	int reArmAttempts;

	int reArmAttemptsForTest()
	{
		return reArmAttempts;
	}

	boolean resetPhaseKnownForTest()
	{
		return resetPhaseKnown(storeResetAnchorMs);
	}

	/** Milliseconds until the next reset, or 0 when the phase has not been observed yet. */
	long msUntilNextResetForDisplay()
	{
		return msUntilNextReset(storeResetAnchorMs, System.currentTimeMillis());
	}

	/**
	 * DIAGNOSTIC ONLY — writes what the shop container actually does, to a LOCAL file.
	 *
	 * The reset model is not yet confirmed against a real client: a full store session produced no
	 * anchor on 2026-09-03 while the operator saw several resets happen. Rather than guess at a
	 * second model, record the ground truth. Nothing here is uploaded and nothing about another
	 * player is written — only item ids and quantities from the shop's own container.
	 *
	 * Enabled only by the system property, so an ordinary build writes nothing at all.
	 */
	private void traceLine(String text)
	{
		String path = System.getProperty("osrsbis.shoptrace");
		if (path == null || path.isEmpty())
		{
			return;
		}
		try
		{
			Files.write(Paths.get(path),
				(System.currentTimeMillis() + " " + text + "\n")
					.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
		}
		catch (Exception ignored)
		{
			// a diagnostic must never affect the plugin
		}
	}

	private void traceShopStock(Map<Integer, Integer> now)
	{
		String path = System.getProperty("osrsbis.shoptrace");
		if (path == null || path.isEmpty())
		{
			return;
		}
		try
		{
			StringBuilder sb = new StringBuilder();
			sb.append(System.currentTimeMillis()).append(' ');
			sb.append("gate=").append(plugin.grants.storeToolsEnabled()).append(' ');
			sb.append("probe=").append(storeProbeItem).append(' ');
			sb.append("anchor=").append(storeResetAnchorMs).append(' ');
			sb.append("delta=");
			boolean any = false;
			Set<Integer> keys = new TreeSet<>();
			keys.addAll(shopStock.keySet());
			keys.addAll(now.keySet());
			for (Integer k : keys)
			{
				int a = shopStock.getOrDefault(k, 0);
				int b = now.getOrDefault(k, 0);
				if (a != b)
				{
					sb.append(k).append(':').append(a).append("->").append(b).append(' ');
					any = true;
				}
			}
			if (!any)
			{
				sb.append("(none)");
			}
			sb.append('\n');
			Files.write(Paths.get(path),
				sb.toString().getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
		}
		catch (Exception ignored)
		{
			// a diagnostic must never affect the plugin
		}
	}

	/**
	 * Milliseconds from {@code nowMs} to the NEAREST shop cycle tick of an observed anchor, or null when no
	 * phase is known (or the clock went backwards). Pure and static so it is testable without a client.
	 */
	static Long msFromResetCycle(long anchorMs, long nowMs)
	{
		if (anchorMs <= 0 || nowMs < anchorMs)
		{
			return null;
		}
		long r = (nowMs - anchorMs) % STORE_RESET_PERIOD_MS;
		return Math.min(r, STORE_RESET_PERIOD_MS - r);
	}

	/**
	 * Do two change times sit a WHOLE number of periods apart (k >= 1), within tolMs? Two changes in the same
	 * cycle (k = 0) never align: they are one observation of the cycle, not two. Pure and static.
	 */
	static boolean phaseAligned(long a, long b, long tolMs)
	{
		long gap = Math.abs(b - a);
		long k = (gap + STORE_RESET_PERIOD_MS / 2) / STORE_RESET_PERIOD_MS;
		return k >= 1 && Math.abs(gap - k * STORE_RESET_PERIOD_MS) <= tolMs;
	}

	/**
	 * The largest set of change times in which EVERY pair is phaseAligned, found greedily from each start.
	 * Returns {latest member, set size}, or null for no times. Greedy can miss a larger set, which only
	 * makes confirmation rarer: it never invents one. Pure and static so the rule is testable.
	 */
	static long[] alignedPhase(long[] obs, long tolMs)
	{
		if (obs == null || obs.length == 0)
		{
			return null;
		}
		long[] best = null;
		for (int i = 0; i < obs.length; i++)
		{
			List<Long> set = new ArrayList<>();
			set.add(obs[i]);
			for (int j = 0; j < obs.length; j++)
			{
				if (j == i)
				{
					continue;
				}
				boolean all = true;
				for (long m : set)
				{
					if (!phaseAligned(m, obs[j], tolMs))
					{
						all = false;
						break;
					}
				}
				if (all)
				{
					set.add(obs[j]);
				}
			}
			long latest = Long.MIN_VALUE;
			for (long m : set)
			{
				latest = Math.max(latest, m);
			}
			if (best == null || set.size() > best[1])
			{
				best = new long[]{latest, set.size()};
			}
		}
		return best;
	}

	/**
	 * Aligned changes needed before a history of n changes is evidence. Each other change lands inside the
	 * tolerance of a given phase with chance q = 2 * tol / period, and there are 1 / q distinct phases, so the
	 * chance that SOME phase gathers s of n by luck is at most (1 / q) * P(Binomial(n, q) >= s). Returns the
	 * smallest s >= RESET_PHASE_MIN_OBS that keeps this at or below RESET_PHASE_FALSE_RATE, or n + 1 (never).
	 * Pure and static so it is testable.
	 */
	static int requiredPhaseSupport(int n)
	{
		if (n >= 0 && n < PHASE_SUPPORT_NEEDED.length)
		{
			return PHASE_SUPPORT_NEEDED[n];
		}
		return computePhaseSupport(n);
	}

	private static int[] phaseSupportTable()
	{
		int[] t = new int[RESET_PHASE_HISTORY + 1];
		for (int n = 0; n < t.length; n++)
		{
			t[n] = computePhaseSupport(n);
		}
		return t;
	}

	private static int computePhaseSupport(int n)
	{
		double q = 2.0 * PHASE_ALIGN_TOL_MS / STORE_RESET_PERIOD_MS;
		for (int s = RESET_PHASE_MIN_OBS; s <= n; s++)
		{
			double tail = 0;
			for (int k = s; k <= n; k++)
			{
				double c = 1;
				for (int i = 0; i < k; i++)
				{
					c = c * (n - i) / (i + 1);
				}
				tail += c * Math.pow(q, k) * Math.pow(1 - q, n - k);
			}
			if (tail / q <= RESET_PHASE_FALSE_RATE)
			{
				return s;
			}
		}
		return Math.max(n, RESET_PHASE_MIN_OBS - 1) + 1;
	}

	/**
	 * Record one shop container change for the evidence phase. Before confirmation the bounded history is
	 * searched for requiredPhaseSupport(history size) aligned changes (at least 3). After it, a change that lands on the confirmed phase
	 * in a cycle not yet counted raises the support. Nothing here touches the countdown.
	 */
	private void registerResetPhaseObservation(long nowMs)
	{
		if (evidencePhaseMs > 0)
		{
			Long off = msFromResetCycle(evidencePhaseMs, nowMs);
			long k = (nowMs - evidencePhaseMs + STORE_RESET_PERIOD_MS / 2) / STORE_RESET_PERIOD_MS;
			if (off != null && off <= PHASE_ALIGN_TOL_MS && k > evidencePhaseLastCycle)
			{
				evidencePhaseSupport++;
				evidencePhaseLastCycle = k;
			}
			return;
		}
		long oldest = nowMs - RESET_PHASE_WINDOW_PERIODS * STORE_RESET_PERIOD_MS;
		while (!resetPhaseObs.isEmpty() && (resetPhaseObs.peekFirst() < oldest || resetPhaseObs.peekFirst() > nowMs))
		{
			resetPhaseObs.pollFirst();	// aged out, or a clock that went backwards
		}
		resetPhaseObs.addLast(nowMs);
		while (resetPhaseObs.size() > RESET_PHASE_HISTORY)
		{
			resetPhaseObs.pollFirst();
		}
		long[] obs = new long[resetPhaseObs.size()];
		int i = 0;
		for (long t : resetPhaseObs)
		{
			obs[i++] = t;
		}
		long[] best = alignedPhase(obs, PHASE_ALIGN_TOL_MS);
		if (best != null && best[1] >= requiredPhaseSupport(obs.length))
		{
			evidencePhaseMs = best[0];
			evidencePhaseSupport = (int) best[1];
			evidencePhaseLastCycle = 0;
			resetPhaseObs.clear();
		}
	}

	/**
	 * May this container change support the evidence phase? Only a change the shop made by itself on its
	 * cycle may: player-added stock decaying by one. So ALL of these must hold, read against the stock as it
	 * was before the change:
	 * (1) an item we sold this visit that is not the shop's own default stock fell by exactly one unit;
	 * (2) no item rose (a rise is our own sell, another seller, or a restock landing);
	 * (3) it is more than OWN_ACTION_QUIET_MS (one period plus tolerance) after our own last store click this
	 *     visit (our sells, our buy-backs and a buyer reacting to our sell all sit at a fixed offset from it).
	 * Everything else (buys by others, normalisation of native stock, our own actions) never counts.
	 */
	private boolean isShopMadeDecay(Map<Integer, Integer> now, long nowMs)
	{
		if (lastOwnStoreClickMs > 0 && nowMs - lastOwnStoreClickMs <= OWN_ACTION_QUIET_MS)
		{
			return false;
		}
		for (Map.Entry<Integer, Integer> e : now.entrySet())
		{
			if (e.getValue() > shopStock.getOrDefault(e.getKey(), 0))
			{
				return false;
			}
		}
		for (Integer item : soldThisVisit)
		{
			if (item == null || defaultStockSoldThisVisit.contains(item))
			{
				continue;
			}
			int before = shopStock.getOrDefault(item, 0);
			if (before > 0 && before - now.getOrDefault(item, 0) == 1)
			{
				return true;
			}
		}
		return false;
	}

	/** Forget the evidence phase. Called wherever the countdown anchor is dropped. */
	void clearResetPhaseEvidence()
	{
		evidencePhaseMs = 0;
		evidencePhaseSupport = 0;
		evidencePhaseLastCycle = 0;
		resetPhaseObs.clear();
	}

	/** How far a gap may sit from one full period and still count as the cycle. */
	static final long PERIOD_TOLERANCE_MS = 3_000L;

	/**
	 * Do two consecutive container changes sit exactly one store period apart?
	 *
	 * This is the whole anchor rule. It is deliberately conservative: a shop where nothing else
	 * happens produces a clean 60s chain, while a busy shop full of customers produces changes at
	 * arbitrary times that mostly will NOT be 60s apart, so the clock simply stays unknown rather
	 * than locking onto a coincidence. Showing nothing beats showing a wrong countdown.
	 *
	 * Pure and static so the rule is testable without a client.
	 */
	static boolean isPeriodAgreement(long previousChangeMs, long nowMs)
	{
		if (previousChangeMs <= 0 || nowMs <= previousChangeMs)
		{
			return false;		// nothing to compare against, or a clock that went backwards
		}
		long gap = nowMs - previousChangeMs;
		return Math.abs(gap - STORE_RESET_PERIOD_MS) <= PERIOD_TOLERANCE_MS;
	}

	/** Remove the countdown. Idempotent — every teardown path calls it. */
	void removeResetTimer()
	{
		Timer t = resetTimer;
		resetTimer = null;
		if (t != null && plugin.infoBoxManager != null)
		{
			plugin.infoBoxManager.removeInfoBox(t);
		}
	}

	/**
	 * Watch the SHOP's stock for someone taking an item we just sold into it — the general-store
	 * counterparty, which no other signal can see.
	 *
	 * The method works because the shop is a dead drop: staff sells low, an untracked player buys it
	 * out. The staff account's own inventory shows only the first half. The shop container shows the
	 * second: stock of a sold item DROPS when a player takes it. So a drop, paired with who is standing
	 * there at that moment, names the likely receiver.
	 *
	 * ⚠ This is an INFERENCE and is emitted as one (`taken_by_candidates`, never `counterparty`). Stock
	 * also falls when the shop's own restock timer runs, and any player in the shop could be the taker,
	 * so the candidate list is the players present — evidence to weigh, not an accusation. Only items
	 * this visit actually SOLD are watched, so a staff buy that lowers stock is never mistaken for it.
	 */
	void handleShopStockChanged(ItemContainer shop)
	{
		if (shop == null)
		{
			return;
		}
		Map<Integer, Integer> now = new HashMap<>();
		Item[] items = shop.getItems();
		if (items != null)
		{
			for (Item it : items)
			{
				if (it != null && it.getId() > 0)
				{
					now.merge(it.getId(), it.getQuantity(), Integer::sum);
				}
			}
		}
		traceShopStock(now);
		long nowMs = plugin.nowMs();
		// The evidence phase as it was BEFORE this change. Player-added stock decays on the cycle tick, so a
		// store_taken that lands on it may be the shop, not a buyer. The change being judged must never count
		// toward its own phase, so read it before this change is registered below.
		long evidencePhaseBefore = evidencePhaseMs;
		int evidenceSupportBefore = evidencePhaseSupport;

		// ANCHOR 1 — THE ITEM WE SOLD VANISHING. This is the event the user actually watches, and it
		// is the strongest signal available: a junk item the shop does NOT natively stock is sold in,
		// and it is removed on the next tick.
		//
		// The discriminator is ZERO, measured live 2026-09-03. Player-added stock decays 1 per tick
		// until it reaches 0 and leaves the shop entirely (item 1191: 5->4->3->…->0, each step exactly
		// 60.0s apart; item 1159: 1->0). Shop DEFAULT stock behaves completely differently — selling a
		// Pot into Varrock GS raised it 5->6 and the shop normalised it back to 5 in 1.2-5.4 SECONDS,
		// never touching the cycle. So a sold item falling to a NON-ZERO number is the shop
		// normalising its own goods and means nothing; falling to ZERO is the tick.
		// ONLY THE PROBE, never the merchandise. Reported from the field by Snaauz on 2026-09-19:
		// delivering more than one item made the timer reset as soon as the first item was bought.
		// A delivery visit sells the junk probe and then the goods, so soldThisVisit holds both. A
		// customer buying the GOODS out takes their stock to zero at an arbitrary moment in the cycle,
		// which is a purchase and not a tick. Anchoring there threw the phase away and every number
		// after it was wrong. storeProbeItem already records the first item sold, which is the junk
		// whose decay IS the cycle, so read that one item and no other.
		if (storeProbeItem != 0)
		{
			int had = shopStock.getOrDefault(storeProbeItem, 0);
			int has = now.getOrDefault(storeProbeItem, 0);
			if (had > 0 && has == 0)
			{
				storeResetAnchorMs = nowMs;
				traceLine("ANCHOR probe-vanished item=" + storeProbeItem + " " + had + "->0");
				showResetTimer();
			}
		}

		// ANCHOR 2 — PERIODICITY, for a visit where nothing of ours has vanished yet. Two consecutive
		// container changes exactly one period apart can only be the cycle. Confirmed live: two
		// restock ticks 60.015s apart anchored, while two buys 15.0s apart were correctly refused.
		//
		// Two earlier models were refuted by measurement, both by watching a real shop. "the junk item
		// we sold vanishing IS the reset" gave 72s cycles drifting 36s/24s/12s, because a shop's
		// DEFAULT stock normalises on its own schedule. "a tick is a rise and a fall together" refused
		// all five ticks of a five-minute observation, because a full shop has nothing to restock. What
		// survived every observation is the PERIOD: ticks landed at 60.0s with no deviation, twice.
		if (!now.equals(shopStock) && !shopStock.isEmpty())
		{
			if (storeResetAnchorMs == 0 && isPeriodAgreement(lastStockChangeMs, nowMs))
			{
				storeResetAnchorMs = nowMs;
				traceLine("ANCHOR period gap=" + (nowMs - lastStockChangeMs));
				showResetTimer();
			}
			lastStockChangeMs = nowMs;
			if (isShopMadeDecay(now, nowMs))
			{
				registerResetPhaseObservation(nowMs);
			}
		}
		for (Integer item : soldThisVisit)
		{
			int before = shopStock.getOrDefault(item, -1);
			int after = now.getOrDefault(item, 0);
			if (before < 0 || after >= before)
			{
				continue;		// no baseline yet, or stock did not fall
			}
			if (item != null && item == lastSelfBuyItem
				&& System.currentTimeMillis() - lastSelfBuyAtMs <= SELF_BUY_SUPPRESS_MS)
			{
				continue;	// WE bought it back — not a customer. See onMenuOptionClicked.
			}
			if (after > 0 && before - after == 1 && defaultStockSoldThisVisit.contains(item))
			{
				continue;	// F-A1: the shop normalising its own default stock, not a customer
			}
			if (after == 0)
			{
				defaultStockSoldThisVisit.remove(item);	// native stock is gone: anything sold in later is ours
			}
			Map<String, Object> f = Fields.of(
				"item", item, "qty", before - after, "stock_before", before, "stock_after", after);
			// Every other player in the shop area, each with what they were doing, so the reader can keep
			// only the mechanically plausible buyers (facing the same shopkeeper) and name one if one remains.
			List<Map<String, Object>> present = nearbyPlayersSnapshot(NEARBY_FIELD_CAP, true);
			if (!present.isEmpty())
			{
				f.put("taken_by_candidates", present);
			}
			if (shopkeeperName != null)
			{
				f.put("shopkeeper", shopkeeperName);
			}
			// Distance in ms to the nearest shop cycle tick, when the EVIDENCE phase was confirmed before this
			// change. Near 0 = the fall landed on the cycle, where player-added stock decays by itself (measured
			// live 2026-09-27: a sold log fell to 0 exactly 60.0 s after the previous one with no buyer).
			// reset_cycle_obs = how many changes support that phase. Both absent = phase not confirmed, so the
			// reader cannot tell a decay from a buy by timing. The countdown anchor is never used here.
			Long msFromCycle = msFromResetCycle(evidencePhaseBefore, nowMs);
			if (msFromCycle != null)
			{
				f.put("ms_from_reset_cycle", msFromCycle);
				f.put("reset_cycle_obs", evidenceSupportBefore);
			}
			if (plugin.client != null)
			{
				f.put("world", plugin.client.getWorld());
				Integer soldAt = lastSellTickThisVisit.get(item);
				if (soldAt != null && plugin.client.getTickCount() >= soldAt)
				{
					f.put("ticks_since_sell", plugin.client.getTickCount() - soldAt);
				}
			}
			plugin.events.emitEvent("store_taken", f);
			plugin.clips.markStoreClipMoment();
		}
		shopStock.clear();
		shopStock.putAll(now);
	}

	/** Merge the current nearby players into the shop-visit accumulator (called each tick while shop open). */
	void accumulateShopNearby()
	{
		if (!plugin.grants.captureEnabled())
		{
			shopVisitNearby.clear();
			return;
		}
		if (plugin.client == null)
		{
			return;
		}
		Player self = plugin.client.getLocalPlayer();
		List<Player> players = plugin.client.getPlayers();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me == null || players == null)
		{
			return;
		}
		int tick = plugin.client.getTickCount();
		for (Player p : players)
		{
			if (p == null || p == self || p.getName() == null || p.getName().isEmpty() || p.getWorldLocation() == null)
			{
				continue;
			}
			int dist = p.getWorldLocation().distanceTo(me);
			String rsn = Text.removeTags(p.getName());
			int[] rec = shopVisitNearby.get(rsn);
			if (rec == null)
			{
				if (shopVisitNearby.size() >= MAX_NEARBY_TRACKED)
				{
					continue;	// map full — don't grow unbounded on a crowded world
				}
				shopVisitNearby.put(rsn, new int[]{dist, tick, tick, p.getCombatLevel()});
			}
			else
			{
				if (dist < rec[0])
				{
					rec[0] = dist;	// closest approach this visit
				}
				rec[2] = tick;		// last seen
			}
		}
	}

	/** Emit nearby[] from the visit accumulator, nearest-first: {rsn, dist, first_tick, last_tick, cb}. */
	private List<Map<String, Object>> nearbyFromVisit()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		List<Map.Entry<String, int[]>> entries = new ArrayList<>(shopVisitNearby.entrySet());
		entries.sort((a, b) -> Integer.compare(a.getValue()[0], b.getValue()[0]));
		for (Map.Entry<String, int[]> e : entries)
		{
			if (out.size() >= NEARBY_FIELD_CAP)
			{
				break;
			}
			int[] r = e.getValue();
			Map<String, Object> m = Fields.of(
				"rsn", e.getKey(), "dist", r[0], "first_tick", r[1], "last_tick", r[2], "cb", r[3]);
			out.add(m);
		}
		return out;
	}

	/**
	 * Build the store event fields. {item, qty} always; gp_total = the exact coins that moved, added ONLY
	 * when the delta is cleanly attributable — right sign for the direction (buy → coins fell, sell → rose)
	 * and not a same-tick batch. unit_price_gp is a LABELLED average (gp_total/qty), only meaningful for
	 * qty>1; per-item price scales mid-batch as stock moves, so it is never a flat rate. When the delta can't
	 * be trusted, degrade to {item, qty} only (option D) rather than emit a guess. Pure/static: no client.
	 */
	static Map<String, Object> buildStoreTxFields(String type, int item, int qty, long coinsBefore, long coinsAfter, boolean ambiguous)
	{
		return buildStoreTxFields(type, item, qty, coinsBefore, coinsAfter, ambiguous, false);
	}

	/**
	 * @param qtyMerged qty is the SUM of same-tick clicks (intent), not a confirmed executed quantity —
	 *                  gp_total stays exact (it is the measured coin delta) but unit_price_gp is OMITTED,
	 *                  because dividing an exact total by an unconfirmed denominator yields a wrong unit
	 *                  price (half price when one of two merged clicks failed). An absent field is
	 *                  recoverable; a plausible wrong number is not.
	 */
	static Map<String, Object> buildStoreTxFields(String type, int item, int qty, long coinsBefore, long coinsAfter,
		boolean ambiguous, boolean qtyMerged)
	{
		return buildStoreTxFields(type, item, qty, coinsBefore, coinsAfter, ambiguous, qtyMerged,
			UNKNOWN_ITEM_COUNT);
	}

	/**
	 * @param executedQty the quantity the server ACTUALLY moved, measured from the item-count delta, or
	 *                    {@link #UNKNOWN_ITEM_COUNT} when it could not be measured.
	 *
	 *                    <p>Field-observed 2026-08-17: a SINGLE "Buy 50" click on a shop holding 5 emitted
	 *                    {@code qty:50, gp_total:3925, unit_price_gp:78} against a true unit of ~785, and a
	 *                    single "Sell 50" of 27 items emitted {@code unit_price_gp:43} against ~80. A
	 *                    single click's quantity is click INTENT too — the shop's stock, the inventory's
	 *                    free space and the player's coins all cap what actually executes — so "one click
	 *                    means a confirmed denominator" was simply false, and both rows were 10x wrong
	 *                    while looking exact.
	 *
	 *                    <p>So a unit price is derived only when the measured executed quantity CONFIRMS
	 *                    the click: it is then a real per-item rate over a real denominator. When the two
	 *                    disagree the row carries {@code qty_partial: true} and the executed quantity in
	 *                    {@code qty_executed}, and no unit price — the same "label it, never guess it"
	 *                    rule the merged case already follows. When nothing could be measured, there is no
	 *                    evidence either way, so no unit price either.
	 */
	static Map<String, Object> buildStoreTxFields(String type, int item, int qty, long coinsBefore, long coinsAfter,
		boolean ambiguous, boolean qtyMerged, long executedQty)
	{
		Map<String, Object> fields = Fields.of("item", item, "qty", qty);
		long delta = coinsAfter - coinsBefore;
		boolean signOk = "store_buy".equals(type) ? delta < 0 : delta > 0;
		if (!ambiguous && signOk)
		{
			long gp = Math.abs(delta);
			fields.put("gp_total", gp);
			if (qtyMerged)
			{
				// Label the residual uncertainty rather than let a bare exact-looking number imply a
				// pairing we cannot guarantee. gp_total is always the coins that really moved in this
				// resolution window. qty is the SUM of same-tick click intents, and two things can make
				// it overstate what those coins bought: a click that failed (out of stock, full
				// inventory, insufficient coins), or the server settling the two clicks on DIFFERENT
				// ticks, in which case the first ItemContainerChanged carries only the first
				// transaction's delta while qty already counts both.
				//
				// Neither is detectable client-side — the plugin sees one inventory update and cannot
				// ask what the server did — so this is honest labelling, not a guess. A consumer must
				// treat qty as an UPPER BOUND on a merged row and must never divide gp_total by it.
				// unit_price_gp is omitted for exactly that reason.
				fields.put("qty_merged", true);
				if (executedQty != UNKNOWN_ITEM_COUNT)
				{
					// The measured quantity is not uncertain even when the click intent was — publish it
					// so a consumer has a real denominator instead of only an upper bound.
					fields.put("qty_executed", executedQty);
				}
			}
			else if (executedQty != UNKNOWN_ITEM_COUNT && executedQty != qty)
			{
				// The server moved a different quantity than the click asked for — partial fill (stock ran
				// out, inventory filled, coins ran short) or nothing at all. qty stays the click's own
				// number for continuity with every historical row; the truth sits beside it, labelled.
				fields.put("qty_partial", true);
				fields.put("qty_executed", executedQty);
			}
			else if (executedQty == qty && qty > 1)
			{
				// The measured executed quantity CONFIRMS the click, so gp_total/qty is a real per-item
				// rate over a real denominator. An unmeasurable quantity does not reach here: no evidence
				// is not the same as confirmation, and this release exists to stop plausible wrong numbers.
				fields.put("unit_price_gp", gp / qty);	// average — see note above
			}
		}
		else if (executedQty != UNKNOWN_ITEM_COUNT)
		{
			// A SALE THE SHOP PAID 0 GP FOR. The item count measured the sell and the coins were read before
			// and after (a measured quantity needs the inventory read at the click, which is where the coins
			// come from) and did not move, on a row of its own. Without this the row looked exactly like a sale
			// whose coins were never read (live 0.7.55 audit 2026-10-27: 181 such rows, 7 of 1M+ items).
			// coins_unchanged tells a reader this 0 was measured; it is never sent without gp_total 0.
			if ("store_sell".equals(type) && !ambiguous && !qtyMerged && delta == 0 && executedQty > 0)
			{
				fields.put("gp_total", 0L);
				fields.put("coins_unchanged", true);
			}
			// The coins cannot be split (two buys settled in one inventory change), but each item's own count
			// still measured what the server moved. Live 0.7.55 (2026-10-24): a player buying two runes per
			// world sent half of all rows as a bare {item, qty:50} while about 30 had really moved.
			if (qtyMerged)
			{
				fields.put("qty_merged", true);
			}
			else if (executedQty != qty)
			{
				fields.put("qty_partial", true);
			}
			fields.put("qty_executed", executedQty);
		}
		return fields;
	}

	/**
	 * The general-store transaction type for a menu click — "store_buy", "store_sell", or null if it is
	 * neither. SINGLE source of truth for what counts as a store buy/sell: the emit path derives its event
	 * type from this and isStoreBuyOrSell delegates to it, so the two can never drift (grill F5).
	 */
	static String storeTxType(MenuOptionClicked event)
	{
		String opt = event.getMenuOption() == null ? "" : event.getMenuOption();
		if (opt.startsWith("Buy"))
		{
			return "store_buy";
		}
		if (opt.startsWith("Sell"))
		{
			return "store_sell";
		}
		return null;
	}

	/** True when a menu click is a general-store buy/sell (delegates to storeTxType — never a second match). */
	static boolean isStoreBuyOrSell(MenuOptionClicked event)
	{
		return storeTxType(event) != null;
	}
}
