package com.osrsbestinslot.export;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;

/** Off-book item moves: drop, pickup and alch clicks armed as pendings and resolved against the inventory and the ground. */
@RequiredArgsConstructor
final class OffBookPendings
{
	private final AccountConnectPlugin plugin;

	/**
	 * The quantity in a store menu option, once tags are stripped: the trailing run of digits.
	 * Capped at 9 digits so a pathological string cannot overflow the parse.
	 */
	private static final Pattern TRAILING_QTY = Pattern.compile("(\\d{1,9})\\s*$");

	// ---- off-book value events: drop / pickup / alch. A menu click arms an inventory-delta pending, resolved
	// on the next INVENTORY change from the item-count delta (reuses the store-pending arm/resolve discipline). ----
	static final int INV_DELTA_PENDING_MAX_TICKS = 5;	// pickup: wait a few ticks (a "Take" may land after a walk)
	static final int ALCH_PENDING_MAX_TICKS = 2;		// an alch lands next tick — short window, nothing to wait for
	static final int DROP_PENDING_MAX_TICKS = 16;		// drop: the warning dialog can sit ~10s before the player confirms
	static final int DROP_SPAWN_MAX_DIST = 2;			// own drops land on/adjacent to the player's tile
	static final int DROP_CORROBORATION_MAX_TICKS = 2;	// spawn + inventory loss must land within ~1 tick of each other
	/**
	 * Armed off-book pendings, OLDEST FIRST.
	 *
	 * This was a SINGLE slot, and that silently lost drops. A drop trade is N items dropped in quick
	 * succession, so the second Drop click armed a new pending over the first before its ground spawn had
	 * confirmed it, and the first drop emitted NOTHING — no error, no trace anywhere downstream. Measured
	 * with the real callback order: four rapid drops produced ONE event; three were lost. The inventory
	 * still showed the items gone, so a periodic snapshot looks perfectly consistent while the telemetry is
	 * simply incomplete. That is why a snapshot can confirm final state but can never certify event capture.
	 *
	 * Bounded at INV_PENDING_MAX so a stream of clicks that never land cannot grow without limit; the OLDEST
	 * is evicted first, which is also the one most likely to be stale. Resolution walks the queue and matches
	 * by ITEM ID, so out-of-order ground spawns (packet order is the server's choice) still find their own
	 * pending rather than consuming someone else's.
	 */
	static final int INV_PENDING_MAX = 28;
	// one inventory's worth of clicks; drops cannot exceed it
	final Deque<InvDeltaPending> invDeltaPendings = new ArrayDeque<>();

	static final class InvDeltaPending
	{
		final String base;			// "drop" | "pickup" | "alch"
		final int item;
		final String spell;			// "high" | "low" for alch, else null
		/**
		 * Count of `item` in the inventory at click time. RAISED when another pending consumes an
		 * arrival of the same item, so this pending cannot claim that same arrival. Two Takes of one
		 * item armed on the same tick share a baseline, and a SINGLE arriving item then satisfied
		 * both: two `pickup` rows for one real recovery. Not final for exactly that reason.
		 */
		volatile long beforeCount;
		final long beforeCoins;		// carried coins at click time (alch gp confirmation)
		final Map<String, Object> location;	// {region_id, plane} at click, or null (alch)
		final Boolean wilderness;	// drop only: true if dropped inside the Wilderness (instantly visible)
		final int tick;
		/** Identity of this click, never the most recent click when its signals arrive. */
		final String dropSessionId;
		final int dropSeq;
		/** Quantity added by this ground event; -1 for callers without ground quantity evidence. */
		volatile long spawnQuantity = -1;
		// drop only: tick a matching ground spawn was seen at our tile BEFORE the inventory loss was visible.
		// Makes resolution order-independent — whichever of (spawn, inventory-decrement) the client processes
		// first records itself; the second completes the emit. -1 = no corroboration yet.
		volatile int spawnCorroboratedTick = -1;
		// drop only: the build count when the inventory loss was seen before its ground spawn. -1 = not seen.
		volatile long lossBuildSeq = -1;
		// drop only: the WORLD tile the confirming ground spawn landed on, so the drop row carries the pile's
		// exact tile itself. -1 = the spawn came with no tile (a test overload), and the row then omits it.
		volatile int spawnX = -1;
		volatile int spawnY = -1;
		volatile int spawnPlane = -1;
		/**
		 * pickup only: the WORLD tile of the ground pile this Take was clicked on, or -1 when the
		 * client did not give us one. Item id alone cannot tell two piles of the same item apart,
		 * and a drop trade is exactly two piles of the same item.
		 */
		final int takeX;
		final int takeY;
		final int takePlane;

		/** No tile: an inventory action, or a Take the client gave us no usable coordinates for. */
		InvDeltaPending(String base, int item, String spell, long beforeCount, long beforeCoins,
			Map<String, Object> location, Boolean wilderness, int tick)
		{
			this(base, item, spell, beforeCount, beforeCoins, location, wilderness, tick, -1, -1, -1);
		}

		InvDeltaPending(String base, int item, String spell, long beforeCount, long beforeCoins,
			Map<String, Object> location, Boolean wilderness, int tick,
			int takeX, int takeY, int takePlane)
		{
			this(base, item, spell, beforeCount, beforeCoins, location, wilderness, tick,
				takeX, takeY, takePlane, null, 0);
		}

		InvDeltaPending(String base, int item, String spell, long beforeCount, long beforeCoins,
			Map<String, Object> location, Boolean wilderness, int tick,
			int takeX, int takeY, int takePlane, String dropSessionId, int dropSeq)
		{
			this.dropSessionId = dropSessionId;
			this.dropSeq = dropSeq;
			this.takeX = takeX;
			this.takeY = takeY;
			this.takePlane = takePlane;
			this.base = base;
			this.item = item;
			this.spell = spell;
			this.beforeCount = beforeCount;
			this.beforeCoins = beforeCoins;
			this.location = location;
			this.wilderness = wilderness;
			this.tick = tick;
		}
	}

	/**
	 * "Buy 10" -&gt; 10, "Sell 10&lt;col=ff9040&gt;" -&gt; 10, "Buy" -&gt; 1 (default).
	 *
	 * <p>Colour tags are stripped first, then the trailing digits are read. Field-observed 2026-08-17: the
	 * client's SELL options carry a trailing colour tag that BUY options do not ("Sell 10&lt;col=ff9040&gt;"),
	 * so token-parsing threw and silently defaulted every sell to qty 1 while gp_total stayed correct — a
	 * 10x-wrong implied unit price on the gold-delivery path. Stripping matters rather than just scanning
	 * for digits: "ff9040" inside the tag would otherwise read as the quantity.
	 */
	static int parseTrailingQty(String option)
	{
		Matcher m = TRAILING_QTY.matcher(AccountConnectPlugin.MENU_TAG.matcher(option).replaceAll(""));
		if (!m.find())
		{
			return 1;
		}
		try
		{
			return Integer.parseInt(m.group(1));
		}
		catch (NumberFormatException e)
		{
			return 1;	// absurdly long digit run — treat as unparseable rather than overflow
		}
	}

	/**
	 * Off-book value events. A "Drop", ground-item "Take", or High/Low-Alchemy cast arms an inventory-delta
	 * pending, resolved on the next INVENTORY change from the item-count delta (alch also confirms the gp gained
	 * from the coin delta). Own gate on the activity log; not shop-scoped. Last-click-wins like the store pending
	 * (a fresh click supersedes an unresolved one — rapid drop-all logs the last drop; the wealth snapshot nets
	 * the rest). getItemId() carries the item for Drop/Take (same accessor the store path uses); for alch it is
	 * [verify in-client] (a spell-on-item click may report -1), so an unresolvable item id is skipped, not guessed.
	 */
	void maybeArmInvDeltaPending(MenuOptionClicked event)
	{
		if (!plugin.activityLogActive())
		{
			return;
		}
		String action = offBookMenuAction(event);
		if (action == null)
		{
			return;
		}
		int item = offBookItemId(event, action);
		if (item <= 0)
		{
			return;	// no resolvable item id (e.g. alch spell-on-item may report -1) — skip rather than guess
		}
		String base = action.startsWith("alch") ? "alch" : action;
		if ("drop".equals(base))
		{
			// START THE CLIP AT THE CLICK, not at the drop EVENT. The event only exists once the
			// inventory loss and the ground spawn agree, which is ticks later, so starting there
			// would cut the drop itself off the front of the evidence.
			plugin.dropProof.onDropActionForProof();
		}
		String spell = "alch_high".equals(action) ? "high" : ("alch_low".equals(action) ? "low" : null);
		ItemContainer inv = plugin.client == null ? null : plugin.client.getItemContainer(InventoryID.INVENTORY);
		long beforeCount = plugin.snapshots.countItem(inv, item);
		long beforeCoins = plugin.snapshots.countItem(inv, SnapshotBuilder.COINS_ID);
		int tick = plugin.client == null ? 0 : plugin.client.getTickCount();
		Map<String, Object> location = "alch".equals(base) ? null : plugin.snapshots.currentLocation();
		Boolean wilderness = "drop".equals(base)
			? (plugin.client != null && plugin.client.getVarbitValue(Varbits.IN_WILDERNESS) > 0)
			: null;
		// A ground Take carries the pile's tile; anything else has none, which reads as (-1,-1,-1).
		int[] tile = "pickup".equals(base) ? groundTakeTile(event) : new int[]{-1, -1, -1};
		InvDeltaPending armed = new InvDeltaPending(base, item, spell, beforeCount, beforeCoins,
			location, wilderness, tick, tile[0], tile[1], tile[2],
			"drop".equals(base) && plugin.grants.dropProofEnabled() ? plugin.dropProof.pendingDropSessionId : null,
			"drop".equals(base) && plugin.grants.dropProofEnabled() ? plugin.dropProof.pendingDropSeq : 0);
		synchronized (invDeltaPendings)
		{
			// A second Drop before the first lands is the NORMAL shape of a drop trade, never a mistake.
			// Append; never overwrite.
			while (invDeltaPendings.size() >= INV_PENDING_MAX)
			{
				invDeltaPendings.pollFirst();	// evict the oldest — also the most likely to be stale
			}
			invDeltaPendings.addLast(armed);
		}
		if ("pickup".equals(base))
		{
			// Flag every pile this Take could have been aimed at, NOW, while they are all still
			// tracked. From here on none of them may be published as `removed_early` (somebody else
			// took it) or `despawn_timer` (nobody took it): we clicked Take on one of them, so both
			// are claims the client cannot support. Only a proven, unambiguous recovery upgrades a
			// flagged pile back to `self_pickup`.
			plugin.piles.flagTakeArmedPiles(armed);
		}
	}

	/**
	 * WORLD tile of the ground pile a Take was clicked on, or null when it cannot be established.
	 *
	 * A ground-item menu entry carries the pile's SCENE coordinates in param0/param1 (live capture
	 * 2026-09-12: `option=Take id=1931 itemId=-1 param0=49 param1=54`). Scene coordinates are
	 * relative to the loaded region, so they are converted to world coordinates here, which is the
	 * space `DroppedGroundItem` stores and what makes the two comparable.
	 *
	 * Returns null rather than a guess whenever the opcode is not a ground-item option, the client
	 * is unavailable, or the conversion fails.
	 */
	int[] groundTakeTile(MenuOptionClicked event)
	{
		int[] none = {-1, -1, -1};
		if (plugin.client == null || event == null || !isGroundItemOpcode(event.getMenuAction()))
		{
			return none;
		}
		try
		{
			WorldPoint w = WorldPoint.fromScene(
				plugin.client, event.getParam0(), event.getParam1(), plugin.client.getPlane());
			return w == null ? none : new int[]{w.getX(), w.getY(), w.getPlane()};
		}
		catch (RuntimeException e)
		{
			return none;	// no scene loaded / coordinates out of range — degrade, never guess
		}
	}

	/** True for the ground-item menu opcodes, the only ones whose identifier is an item id. */
	static boolean isGroundItemOpcode(MenuAction a)
	{
		return a == MenuAction.GROUND_ITEM_FIRST_OPTION
			|| a == MenuAction.GROUND_ITEM_SECOND_OPTION
			|| a == MenuAction.GROUND_ITEM_THIRD_OPTION
			|| a == MenuAction.GROUND_ITEM_FOURTH_OPTION
			|| a == MenuAction.GROUND_ITEM_FIFTH_OPTION;
	}

	/**
	 * The item id for an off-book action, from whichever field the client actually populates.
	 *
	 * `getItemId()` is the right source for an INVENTORY action (Drop, alch): the entry is a widget
	 * op on a slot that holds the item. A GROUND-item Take is a different opcode entirely, and the
	 * client leaves `getItemId()` at -1 while putting the item id in the entry IDENTIFIER.
	 *
	 * Measured on the live client 2026-09-12, dropping and re-taking a Pot on world 308 - the real
	 * MenuOptionClicked the plugin received:
	 *
	 *     option=Take target=&lt;col=ff9040&gt;Pot id=1931 itemId=-1 param0=49 param1=54
	 *     type=GROUND_ITEM_THIRD_OPTION
	 *
	 * against the inventory Drop of the same item moments earlier:
	 *
	 *     option=Drop target=&lt;col=ff9040&gt;Pot id=7 itemId=1931 param0=1 param1=9764864
	 *     type=CC_OP_LOW_PRIORITY
	 *
	 * So a self-pickup could never arm a pending, no `pickup` was ever emitted for this opcode, and
	 * the item's later removal fell through to the despawn comparison and was labelled as if a
	 * stranger or the timer had taken it. Both symptoms, one cause.
	 *
	 * The fallback is deliberately narrow: pickup only, only when `getItemId()` is unusable, AND
	 * only on a GROUND_ITEM_* opcode. `identifier` on an inventory entry is the OPTION index (7 for
	 * Drop above), never an item, so it must not be consulted anywhere else. Matching the option
	 * word "Take" alone would adopt the identifier of any future non-ground entry labelled Take; the
	 * opcode is what makes that identifier an item id.
	 */
	static int offBookItemId(MenuOptionClicked event, String action)
	{
		int item = event.getItemId();
		if (item > 0 || !"pickup".equals(action) || !isGroundItemOpcode(event.getMenuAction()))
		{
			return item;
		}
		return event.getId();	// ground Take: the item id lives in the entry identifier
	}

	/** The oldest matching click which has not already consumed a ground spawn. */
	InvDeltaPending pendingDropForSpawn(int item, int tick)
	{
		if (plugin.death.deathPending != null)
		{
			clearInvDeltaPendings();
			return null;
		}
		synchronized (invDeltaPendings)
		{
			InvDeltaPending expiredCorroboration = null;
			Iterator<InvDeltaPending> it = invDeltaPendings.iterator();
			while (it.hasNext())
			{
				InvDeltaPending p = it.next();
				if (!"drop".equals(p.base) || p.item != item) continue;
				if (tick - p.tick > DROP_PENDING_MAX_TICKS)
				{
					it.remove();
					continue;
				}
				if (p.spawnCorroboratedTick < 0) return p;
				if (expiredCorroboration == null
					&& tick - p.spawnCorroboratedTick > DROP_CORROBORATION_MAX_TICKS)
				{
					expiredCorroboration = p;
				}
			}
			// A stray spawn can expire, but it must not steal a later click's first ground event.
			return expiredCorroboration;
		}
	}

	/**
	 * Ground-spawn confirmation for drops (the M3 redesign). A "drop" pending resolves ONLY when BOTH signals
	 * co-occur: the armed item APPEARS ON THE GROUND on/adjacent to the player's tile, AND the player's
	 * inventory holds fewer of it than at click time. An inventory removal alone (equip / eat / bank deposit /
	 * destroy — including via widget buttons that carry no item id) spawns no ground item, so it can never
	 * fabricate a drop; a ground item alone (another player's drop becoming visible) shows no inventory loss,
	 * so it never resolves either.
	 */
	void resolveDropPendingOnGroundSpawn(InvDeltaPending p, long invCountAfter, int currentTick,
		int tileX, int tileY, int tilePlane, long quantityAdded)
	{
		if (p == null) return;
		synchronized (invDeltaPendings)
		{
			if (!invDeltaPendings.contains(p)) return;
			if (tileX >= 0 && tileY >= 0 && tilePlane >= 0)
			{
				p.spawnX = tileX;
				p.spawnY = tileY;
				p.spawnPlane = tilePlane;
			}
			p.spawnCorroboratedTick = currentTick;
			p.spawnQuantity = quantityAdded;
			if (p.beforeCount <= invCountAfter) return; // inventory signal has not arrived yet
			invDeltaPendings.remove(p);
		}
		long outer = plugin.uploader.triggerChangeBuildSeq;
		plugin.uploader.triggerChangeBuildSeq = p.lossBuildSeq;
		try
		{
			completeDropPending(p, p.beforeCount - invCountAfter);
		}
		finally
		{
			plugin.uploader.triggerChangeBuildSeq = outer;
		}
	}

	/** A shared inventory decrement must not be emitted once for each same-item click. */
	private long completeDropPending(InvDeltaPending p, long available)
	{
		long qty = p.spawnQuantity > 0 ? Math.min(available, p.spawnQuantity) : available;
		synchronized (invDeltaPendings)
		{
			for (InvDeltaPending other : invDeltaPendings)
			{
				if (other != p && "drop".equals(other.base) && other.item == p.item
					&& other.beforeCount == p.beforeCount)
				{
					other.beforeCount = Math.max(0, other.beforeCount - qty);
				}
			}
		}
		emitDropEvent(p, qty);
		return qty;
	}

	/** Drop every armed pending — used by the logout / hop / disconnect / death disarms. */
	void clearInvDeltaPendings()
	{
		synchronized (invDeltaPendings)
		{
			invDeltaPendings.clear();
		}
	}

	/** Remove exactly one resolved/expired pending. */
	private void removeInvDeltaPending(InvDeltaPending p)
	{
		synchronized (invDeltaPendings)
		{
			invDeltaPendings.remove(p);
		}
	}

	/** Shared drop emit — reached from either signal order. */
	private void emitDropEvent(InvDeltaPending p, long qty)
	{
		if (!plugin.activityLogActive())
		{
			return;
		}
		Map<String, Object> fields = Fields.of("item", p.item, "qty", qty);
		if (p.location != null)
		{
			fields.put("location", p.location);
		}
		fields.put("wilderness", p.wilderness != null && p.wilderness);
		// The pile's exact tile and the world, on the drop row itself, in the same {x,y,plane} shape the
		// ground_removed row uses. Before this only the removal row carried the tile, so a drop whose
		// removal was never seen (out of scene, logout) could not be located at all.
		if (p.spawnX >= 0)
		{
			Map<String, Object> tile = Fields.of("x", p.spawnX, "y", p.spawnY, "plane", p.spawnPlane);
			fields.put("tile", tile);
		}
		plugin.putWorldContext(fields);
		// SESSION LINKAGE on the drop row itself. drop_seq is what lets the manifest join one drop
		// to one removal when a session drops the same item, same quantity, onto the same tile twice
		// — which is the ordinary shape of a drop trade, not an edge case.
		String auditSessionId = null;
		if (plugin.dropProof.dropSession.active() && p.dropSessionId != null
			&& p.dropSessionId.equals(plugin.dropProof.dropSession.sessionId()) && p.dropSeq > 0)
		{
			auditSessionId = p.dropSessionId;
			fields.put("drop_session_id", auditSessionId);
			fields.put("drop_seq", p.dropSeq);
		}
		// Staff Wilderness drops only: who the client had loaded around us at this drop. The Wilderness
		// flag is the one sampled at the Drop click, so the gate and the row agree.
		if (p.wilderness != null && p.wilderness)
		{
			plugin.wilderness.attachSurroundingSnapshot(fields, "surrounding_at_drop");
		}
		plugin.events.emitEvent("drop", fields);
		plugin.dropProof.noteDropAuditEvent(auditSessionId);
	}

	/** Live wrapper for the ground-spawn resolvers: distance from the local player + current inventory count. */
	private void handleGroundItemForDropPending(TileItem it, Tile tile,
		boolean stackGrew, boolean quantityMerge, long quantityAdded)
	{
		if (plugin.client == null || it == null || tile == null)
		{
			return;	// cheap early-out — ItemSpawned fires constantly for scenery/other players' items
		}
		// Ownership discriminator (review 2026-07-18): the client tags ground items with ownership,
		// and a fresh OWN drop is always OWNERSHIP_SELF. Anything else (another player's pile, an NPC drop,
		// an ownerless world spawn, a GIM partner's item) can never corroborate OUR drop — this closes the
		// whole coincidental-foreign-source fabrication class in one check. [verify in-client]
		if (it.getOwnership() != TileItem.OWNERSHIP_SELF)
		{
			return;
		}
		Player me = plugin.client.getLocalPlayer();
		WorldPoint pw = me == null ? null : me.getWorldLocation();
		WorldPoint tw = tile.getWorldLocation();
		if (pw == null || tw == null || pw.getPlane() != tw.getPlane())
		{
			return;
		}
		int dist = Math.abs(pw.getX() - tw.getX()) + Math.abs(pw.getY() - tw.getY());
		long invCount = plugin.snapshots.countItem(plugin.client.getItemContainer(InventoryID.INVENTORY), it.getId());
		int tick = plugin.client.getTickCount();
		// Track the pile BEFORE resolving: resolution may consume the pending, and the pile is ours either
		// way (OWNERSHIP_SELF, our own tile). Only track when an armed drop of this item is waiting, so a
		// pile we did not drop this session is never adopted.
		if (dist > DROP_SPAWN_MAX_DIST || !stackGrew || quantityAdded <= 0) return;
		InvDeltaPending p = pendingDropForSpawn(it.getId(), tick);
		if (p == null) return;
		plugin.piles.trackGroundDrop(it.getId(), it.getQuantity(), tw.getX(), tw.getY(), tw.getPlane(),
			plugin.snapshots.currentLocation(), tick, it.getDespawnTime(), quantityMerge, p.dropSessionId, p.dropSeq);
		resolveDropPendingOnGroundSpawn(p, invCount, tick, tw.getX(), tw.getY(), tw.getPlane(), quantityAdded);
	}

	/** A fresh ground item at our tile — the primary own-drop confirmation signal. */
	void onItemSpawned(ItemSpawned event)
	{
		if (event != null && plugin.gameplayReady()) plugin.ownGameplay.groundClues.spawned(plugin.client, event.getItem(), System.currentTimeMillis());
		// A genuine NEW ground item, never a merge into an existing stack.
		handleGroundItemForDropPending(event.getItem(), event.getTile(), true, false,
			event.getItem() == null ? 0 : event.getItem().getQuantity());
	}

	/**
	 * Dropping a stackable onto an existing ground stack merges instead of spawning — same confirmation, but
	 * ONLY when the stack GREW (a shrinking stack is another player looting it, never our drop landing).
	 */
	void onItemQuantityChanged(ItemQuantityChanged event)
	{
		// A GROWN stack is a merge: the game holds ONE pile and will fire ONE ItemDespawned for it.
		handleGroundItemForDropPending(event.getItem(), event.getTile(),
			event.getNewQuantity() > event.getOldQuantity(), true,
			(long) event.getNewQuantity() - event.getOldQuantity());
	}

	/**
	 * True when a local Take that could explain THIS pile is still in flight.
	 *
	 * Used only to DEFER a removal, never to attribute one. Matching on item id alone made a Take on
	 * a stranger's pile ten tiles away defer our pile's removal, which delayed a verdict that was
	 * already knowable.
	 */
	boolean hasArmedPickupFor(OwnPiles.DroppedGroundItem g)
	{
		synchronized (invDeltaPendings)
		{
			for (InvDeltaPending p : invDeltaPendings)
			{
				if ("pickup".equals(p.base) && OwnPiles.takeCouldName(p, g))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Classify a menu click as an off-book value action: "drop" (inventory Drop), "pickup" (ground Take), or
	 * "alch_high"/"alch_low" (High/Low Level Alchemy cast). Alch menu shape varies — option "Cast High Level
	 * Alchemy", or option "Cast" with the spell name in the target — so it matches the spell name across the
	 * combined option+target text. Returns null for anything else. Static + pure (no client) so it is unit-testable.
	 */
	static String offBookMenuAction(MenuOptionClicked event)
	{
		String opt = event.getMenuOption() == null ? "" : event.getMenuOption();
		if (opt.equals("Drop"))
		{
			return "drop";
		}
		if (opt.equals("Take"))
		{
			return "pickup";
		}
		if (opt.startsWith("Cast"))
		{
			String combined = opt + " " + (event.getMenuTarget() == null ? "" : event.getMenuTarget());
			if (combined.contains("High Level Alchemy"))
			{
				return "alch_high";
			}
			if (combined.contains("Low Level Alchemy"))
			{
				return "alch_low";
			}
		}
		return null;
	}

	/**
	 * Resolve EVERY armed pending against one inventory change, each against ITS OWN item count.
	 *
	 * A single pending slot could rely on the head being the only candidate. A queue cannot: a Drop whose
	 * ground spawn never arrives stays armed for DROP_PENDING_MAX_TICKS, and while it sits at the head a
	 * real "Take" armed behind it resolves against nothing and is lost. The pre-queue build hid this because
	 * a fresh click overwrote the stale pending. Walking the queue is what keeps a self-pickup observable.
	 */
	void resolveInvDeltaPendings(ItemContainer inv, long coinsAfter, int currentTick)
	{
		List<InvDeltaPending> snapshot;
		synchronized (invDeltaPendings)
		{
			if (invDeltaPendings.isEmpty())
			{
				return;
			}
			snapshot = new ArrayList<>(invDeltaPendings);
		}
		// ONE unit of evidence resolves ONE pending. Every pending is measured against the same
		// container, so two Takes of the same item armed on the same tick share a `beforeCount` and a
		// SINGLE arriving item satisfied both: two `pickup` rows and doubled quantities for one real
		// recovery. A resolved pickup therefore raises every other still-armed pickup's baseline by
		// what it consumed.
		for (InvDeltaPending p : snapshot)
		{
			long used = resolveOnePending(p, plugin.snapshots.countItem(inv, p.item), coinsAfter, currentTick, snapshot);
			if (used > 0 && "pickup".equals(p.base))
			{
				for (InvDeltaPending other : snapshot)
				{
					if (other != p && "pickup".equals(other.base) && other.item == p.item)
					{
						other.beforeCount += used;
					}
				}
			}
		}
	}

	/**
	 * Resolve exactly ONE armed pending. Removes it only when its own signals landed or its window
	 * expired. Returns the quantity it CONSUMED, so the caller can withhold that evidence from the
	 * remaining pendings; 0 when nothing landed.
	 *
	 * An armed pickup/alch pending resolves against the post-change inventory. pickup fires when the item count
	 * ROSE (an inventory removal can't fake a rise); alch fires only when the item fell AND coins rose in the
	 * same change (an alch always yields coins — an item removal without a coin gain is an equip/bank/destroy,
	 * never an alch). "drop" pendings NEVER resolve here: an inventory removal alone could be a bank deposit /
	 * destroy / equip, so drops resolve exclusively via ground-spawn confirmation
	 * (resolveDropPendingOnGroundSpawn) — this path only expires a stale drop pending. Unrelated inventory
	 * changes are ignored and the pending kept until it lands or its tick window expires (so a "Take" that
	 * completes after a short walk still logs).
	 */
	long resolveOnePending(InvDeltaPending p, long itemCountAfter, long coinsAfter,
		int currentTick, List<InvDeltaPending> snapshot)
	{
		// Resolution removes ONE pending — the one whose signals landed. The lifecycle disarms (logout, hop,
		// disconnect, death) are the only places that clear them all; a single item's loss must never discard
		// another item's armed drop, which is the whole point of the queue.
		if ("drop".equals(p.base))
		{
			// Order-independence: if a matching ground spawn already corroborated (spawn processed before the
			// inventory decrement) and the loss now lands within the corroboration window, complete the emit.
			long dropDelta = p.beforeCount - itemCountAfter;
			int spawnTick = p.spawnCorroboratedTick;
			if (dropDelta > 0 && spawnTick >= 0 && currentTick - spawnTick <= DROP_CORROBORATION_MAX_TICKS)
			{
				removeInvDeltaPending(p);
				return completeDropPending(p, dropDelta);
			}
			if (dropDelta > 0 && spawnTick < 0 && p.lossBuildSeq < 0)
			{
				p.lossBuildSeq = plugin.uploader.triggerChangeBuildSeq;	// the loss is this change; its spawn completes the row
			}
			if (currentTick - p.tick > DROP_PENDING_MAX_TICKS)
			{
				removeInvDeltaPending(p);	// no ground spawn confirmed it — expire silently, emit nothing
			}
			return 0;
		}
		if ("pickup".equals(p.base) && currentTick - p.tick > INV_DELTA_PENDING_MAX_TICKS)
		{
			// A Take past its OWN window is no longer evidence about anything, whether or not an
			// item arrived. The window used to be checked only when nothing landed, so a Take
			// clicked but never served stayed armed forever and the next same-item gain revived it:
			// a customer's collection published as ours, and a `pickup` row for a recovery that
			// never happened. Expire it silently and emit nothing.
			removeInvDeltaPending(p);
			return 0;
		}
		long delta = "pickup".equals(p.base) ? (itemCountAfter - p.beforeCount) : (p.beforeCount - itemCountAfter);
		long gp = coinsAfter - p.beforeCoins;
		boolean landed = delta > 0 && (!"alch".equals(p.base) || gp > 0);
		if (!landed)
		{
			int window = "pickup".equals(p.base) ? INV_DELTA_PENDING_MAX_TICKS : ALCH_PENDING_MAX_TICKS;
			if (currentTick - p.tick > window)
			{
				removeInvDeltaPending(p);
			}
			return 0;
		}
		removeInvDeltaPending(p);	// consume — the expected delta landed
		if (!plugin.activityLogActive())
		{
			return delta;
		}
		Map<String, Object> fields = Fields.of("item", p.item, "qty", delta);
		if ("alch".equals(p.base))
		{
			fields.put("spell", p.spell);
			fields.put("gp", gp);	// always present now — the coin gain is the alch confirmation itself
			plugin.events.emitEvent("alch", fields);
			return delta;
		}
		if (p.location != null)
		{
			fields.put("location", p.location);
		}
		// If this recovers a pile WE dropped, say so on the pile: its later removal is then explained
		// as self_pickup rather than counted as an ambiguous early removal. The rule is deliberately
		// strict — see markGroundDropSelfPickedUp. Anything it refuses leaves the pile `unknown`.
		if (plugin.piles.markGroundDropSelfPickedUp(p, delta, snapshot, currentTick))
		{
			// The pile may already be waiting on this answer; publish now rather than idle out the
			// window. settlePendingRemovals removes the entry as it fires, so this cannot double-emit.
			plugin.piles.settlePendingRemovals(currentTick, false, false);
		}
		plugin.events.emitEvent("pickup", fields);	// only pickup reaches here — drop is spawn-confirmed, alch returned above
		return delta;
	}
}
