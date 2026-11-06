package com.osrsbestinslot.export;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.runelite.api.Actor;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.util.Text;

/** Own ground piles: tracking after a drop, the scene reads, telegrab sightings and the removal rows. */
final class OwnPiles
{
	private final AccountConnectPlugin plugin;

	OwnPiles(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Own dropped piles still believed to be on the ground. Same bound as the inventory that fed them. */
	static final int GROUND_TRACK_MAX = 28;
	/**
	 * How far before its reported despawn tick a removal counts as EARLY rather than the timer expiring.
	 * The client's despawnTime is authoritative for the pile, so this is only slack for tick rounding.
	 */
	static final int GROUND_EARLY_MARGIN_TICKS = 2;
	/**
	 * Below this many ticks on the ground, a removal may NOT be called `despawn_timer`.
	 *
	 * FAIL-CLOSED GUARD, not a model of the game. Its only effect is `despawn_timer` -> `unknown`;
	 * no path here produces a stronger claim than the one it replaces. Measured evidence: the real
	 * despawn is about 300 ticks (live read 2026-09-12, tick=244 against despawn_time=530), and 357
	 * of 358 correct fleet rows read exactly 299. 100 is a loose floor that cannot reject a genuine
	 * full-length expiry. An item with a shorter natural lifetime would report `unknown`, which is
	 * the acceptable direction to be wrong in.
	 */
	static final int GROUND_TIMER_MIN_TICKS = 100;
	/**
	 * How long a removal may wait for its pickup to resolve. INV_DELTA_PENDING_MAX_TICKS is the
	 * window the pickup pending itself lives for, so waiting the same span plus one tick cannot
	 * outlive the evidence it is waiting for: if the pickup has not landed by then it never will.
	 */
	static final int REMOVAL_RESOLVE_MAX_TICKS = OffBookPendings.INV_DELTA_PENDING_MAX_TICKS + 1;
	final Deque<DroppedGroundItem> groundDrops = new ArrayDeque<>();
	/**
	 * Telekinetic Grab sightings aimed at the tile of a pile we track. Only tracked tiles are kept, one
	 * row per projectile or impact, pruned to the attribution window and capped, so a busy scene of
	 * casters cannot grow it. Read once per removal to flag a grab from range.
	 */
	final List<DropCandidates.TelegrabSighting> telegrabSightings = new ArrayList<>();
	static final int TELEGRAB_SIGHTING_CAP = 16;
	/** Projectiles already recorded. ProjectileMoved fires every client cycle for one projectile. */
	private final Set<Object> telegrabProjectilesSeen =
		Collections.newSetFromMap(new WeakHashMap<>());
	/**
	 * Removals seen but NOT yet finalized, because a local Take could still explain them.
	 *
	 * The client fires ItemDespawned the instant a pile leaves the ground, and the inventory change
	 * that proves WE took it arrives after. Publishing the cause at despawn time therefore states a
	 * conclusion before the evidence exists, and a real self-pickup went out as `removed_early`
	 * (measured live 2026-09-12 07:39:40). A removal with a plausible pending Take waits here for a
	 * bounded number of ticks, then finalizes on whatever actually resolved. Exactly one
	 * `ground_removed` is ever emitted per pile lifetime: the entry is removed from this map as it
	 * fires, and the pile is already out of `groundDrops` by then.
	 */
	private final Map<DroppedGroundItem, Integer> pendingRemovals =
		new LinkedHashMap<>();
	/**
	 * Set while the scene is reloading or the account is leaving. A scene reload despawns EVERY ground
	 * pile at once for reasons unrelated to anyone taking them, so removals seen in that state are
	 * reported as UNKNOWN, never as early removal.
	 */
	volatile boolean groundObservationUnreliable;

	// ---- ROUND 8: THE SCENE IS THE SOURCE OF TRUTH ----

	/**
	 * What the GAME'S OWN WORLD STATE says about one item id on one tile.
	 *
	 * ROUND 8, FINDING R4. Seven rounds of this resolver inferred "is our pile still on the ground?"
	 * from our own event bookkeeping, and every round a pile the bookkeeping never knew about broke
	 * it: another player's pile, a pile from a session that ended while the pile was still down, a
	 * pile dropped before the token was linked. The game already knows the answer, and the scene is
	 * where it keeps it.
	 *
	 * THREE ANSWERS, AND THE THIRD IS WHY THIS IS AN ENUM. A tile outside the loaded scene is not an
	 * empty tile. Collapsing UNKNOWN into NONE would turn every world hop, every scene reload and
	 * every walk out of render distance into "your pile is gone", which is a new false negative in
	 * place of the old false positive. UNKNOWN means the scene cannot answer, and every caller here
	 * treats it as "change nothing".
	 */
	enum ScenePileState
	{
		/** The tile is in the loaded scene and holds at least one item of that id. */
		PRESENT,
		/** The tile is in the loaded scene and holds NO item of that id. */
		NONE,
		/** The scene cannot answer: no client, no world view, the tile is out of scene, or it is null. */
		UNKNOWN
	}

	/**
	 * ROUND 9 — WHAT THE SCENE SAYS ABOUT ITEMS OF ONE ID ON ONE TILE.
	 *
	 * Round 8 asked the scene a YES/NO question — "is an item of this id still on this tile?" — and
	 * FINDING R7 is the price of that question: it cannot tell OUR OWN second pile from a
	 * stranger's. Ten of our own piles on one tile made the first nine despawns look exactly like an
	 * untracked rival, so nine of ten evidence rows were never published and the surviving row
	 * described the wrong pile.
	 *
	 * A COUNT can tell them apart. With |S| of our own records still on that tile, a remaining count
	 * BELOW |S| means one of OURS left; a count at or above |S| means a stranger's pile may be the
	 * one that went. The QUANTITIES say WHICH of ours, where the reader can supply them.
	 */
	static final class SceneTileItems
	{
		/** UNKNOWN when the scene could not be read; NONE for a count of zero; PRESENT otherwise. */
		final ScenePileState state;
		/** Piles of that item id on that tile, excluding the one the caller asked to exclude. */
		final int count;
		/** One quantity per counted pile, or null when this reader cannot say. */
		final long[] quantities;

		private SceneTileItems(ScenePileState state, int count, long[] quantities)
		{
			this.state = state;
			this.count = count;
			this.quantities = quantities;
		}

		static SceneTileItems unknown()
		{
			return new SceneTileItems(ScenePileState.UNKNOWN, 0, null);
		}

		/** @param quantities one per pile, or null when the reader cannot expose them. */
		static SceneTileItems of(int count, long[] quantities)
		{
			return new SceneTileItems(
				count <= 0 ? ScenePileState.NONE : ScenePileState.PRESENT,
				Math.max(count, 0),
				quantities);
		}
	}

	/**
	 * Reads the scene, so a test can model a rival pile, our pile going, or a tile out of scene.
	 * The live implementation is {@link #readSceneTileItems}; nothing else in this class reads the
	 * scene.
	 */
	interface SceneGroundReader
	{
		/**
		 * @param excluding a TileItem to IGNORE while counting, or null. See
		 *                  {@link #scenePileState} for why the despawn path passes one.
		 */
		SceneTileItems itemsOnTile(int item, int x, int y, int plane, Object excluding);
	}

	/** Defaults to the live client read; swapped in tests through {@link #setSceneReaderForTest}. */
	private volatile SceneGroundReader sceneReader = this::readSceneTileItems;

	void setSceneReaderForTest(SceneGroundReader r)
	{
		sceneReader = r == null ? this::readSceneTileItems : r;
	}

	/**
	 * The callers' view of the scene. Never throws; answers UNKNOWN when it cannot read.
	 *
	 * WHY {@code excluding} EXISTS. RuneLite fires ItemDespawned from inside the client's own scene
	 * update, and nothing in the 1.12.39 obfuscated client proves whether the TileItem being
	 * despawned has already left the tile's item layer at the moment the event is posted. If it has
	 * not, a scene read at despawn time would still see the very pile that is going and answer
	 * PRESENT for it, which would make EVERY despawn look ambiguous. Excluding that one TileItem by
	 * OBJECT IDENTITY makes the answer the same either way, so this does not depend on an ordering
	 * we cannot verify without a live client. The poll path passes null: nothing is going there.
	 */
	ScenePileState scenePileState(int item, int x, int y, int plane, Object excluding)
	{
		return sceneTileItems(item, x, y, plane, excluding).state;
	}

	/** The counting form of {@link #scenePileState}. Never throws; answers UNKNOWN when it cannot read. */
	SceneTileItems sceneTileItems(int item, int x, int y, int plane, Object excluding)
	{
		SceneGroundReader r = sceneReader;
		if (r == null)
		{
			return SceneTileItems.unknown();
		}
		try
		{
			SceneTileItems s = r.itemsOnTile(item, x, y, plane, excluding);
			return s == null || s.state == null ? SceneTileItems.unknown() : s;
		}
		catch (RuntimeException e)
		{
			// A scene read that threw told us nothing. UNKNOWN is the only honest answer, and it is
			// the one that changes no behaviour.
			return SceneTileItems.unknown();
		}
	}

	/**
	 * READ-ONLY scene lookup, RuneLite 1.12.39 API, CLIENT THREAD ONLY.
	 *
	 * Both callers run on the client thread already: onItemDespawned is an event subscriber and
	 * pollDropSession runs from onGameTick. Nothing here mutates the scene or the client.
	 *
	 * The route is: Client.getTopLevelWorldView() -> WorldView.getScene() ->
	 * Scene.getTiles()[plane][sceneX][sceneY] -> Tile.getGroundItems() -> List&lt;TileItem&gt;, with
	 * the world-to-scene conversion done by LocalPoint.fromWorld(WorldView, x, y), which already
	 * returns null for a point outside the scene (verified in the 1.12.39 runelite-api bytecode:
	 * fromWorld calls WorldPoint.isInScene first and returns null when it is false).
	 *
	 * EVERY failure to read answers UNKNOWN, never NONE. A missing client, a missing world view, a
	 * plane outside the tile array, a null tile and a null ground-item list are all "the scene
	 * cannot tell us", and the difference between that and "the tile is empty" is the entire point
	 * of this method.
	 */
	private SceneTileItems readSceneTileItems(int item, int x, int y, int plane, Object excluding)
	{
		if (plugin.client == null)
		{
			return SceneTileItems.unknown();
		}
		WorldView wv = plugin.client.getTopLevelWorldView();
		if (wv == null)
		{
			return SceneTileItems.unknown();
		}
		Scene scene = wv.getScene();
		if (scene == null)
		{
			return SceneTileItems.unknown();
		}
		LocalPoint lp =
			LocalPoint.fromWorld(wv, x, y);
		if (lp == null)
		{
			return SceneTileItems.unknown();	// the tile is not in the loaded scene
		}
		Tile[][][] tiles = scene.getTiles();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return SceneTileItems.unknown();
		}
		int sx = lp.getSceneX();
		int sy = lp.getSceneY();
		Tile[][] atPlane = tiles[plane];
		if (atPlane == null || sx < 0 || sx >= atPlane.length)
		{
			return SceneTileItems.unknown();
		}
		Tile[] col = atPlane[sx];
		if (col == null || sy < 0 || sy >= col.length)
		{
			return SceneTileItems.unknown();
		}
		Tile tile = col[sy];
		if (tile == null)
		{
			return SceneTileItems.unknown();
		}
		List<TileItem> items = tile.getGroundItems();
		if (items == null)
		{
			// The tile exists but carries no item layer at all. RuneLite's Tile.getGroundItems
			// returns null when there is no item layer, which is a genuinely EMPTY tile, not a
			// failure to read. Verified in the 1.12.39 injected client: the null return happens only
			// on a missing ItemLayer.
			return SceneTileItems.of(0, new long[0]);
		}
		// ROUND 9. COUNT them, do not stop at the first match. The count is what separates our own
		// second pile from a stranger's, and TileItem.getQuantity() is what says WHICH of ours went.
		// Both signatures read from runelite-api-1.12.39 with javap: getId()I and getQuantity()I.
		List<Long> qty = new ArrayList<>();
		for (TileItem it : items)
		{
			if (it != null && it != excluding && it.getId() == item)
			{
				qty.add((long) it.getQuantity());
			}
		}
		long[] q = new long[qty.size()];
		for (int i = 0; i < q.length; i++)
		{
			q[i] = qty.get(i);
		}
		return SceneTileItems.of(q.length, q);
	}

	/**
	 * A ground item WE dropped, tracked from its own-tile spawn until it leaves the ground.
	 *
	 * This exists to answer one question honestly: did the item we dropped stay there, or did it go?
	 * It deliberately CANNOT answer who took it. A ground item's pickup happens in the other player's
	 * client and nothing in our packet stream names the taker, so no field here ever carries a
	 * recipient. What we can separate is EARLY removal from the natural despawn TIMER, and even that
	 * separation is refused whenever the scene reloaded or the tile left render distance, because both
	 * fire ItemDespawned for reasons that have nothing to do with anyone taking the item.
	 */
	static final class DroppedGroundItem
	{
		final int item;
		final long qty;
		final int x;
		final int y;
		final int plane;
		final Map<String, Object> location;	// {region_id, plane} — same shape the drop event carries
		final int dropTick;
		/** Client's own despawn deadline for this pile, in ticks. -1 when the client did not report one. */
		final int despawnTick;
		/** Set when OUR OWN account picked this item back up, so the despawn is explained, not ambiguous. */
		volatile boolean selfPickedUp;
		/**
		 * How many times settlement has considered this parked removal. A frozen tick counter makes
		 * the elapsed-tick test unreachable, so the attempt count is the backstop that guarantees
		 * every parked removal is eventually published.
		 */
		volatile int settleAttempts;
		/**
		 * Set when a local Take of this item could have been aimed at this pile.
		 *
		 * Once set it never clears. A pile a local Take named may have been recovered by us, so
		 * `removed_early` - which reads as somebody else took it - is unsupported, and so is
		 * `despawn_timer`. The pile reports `unknown` unless the pickup is proven exactly.
		 */
		volatile boolean takeArmed;
		/**
		 * Tick a PROVISIONAL self-pickup mark landed, or -1 when no such mark is outstanding.
		 *
		 * A mark set while the pile is STILL ON THE GROUND is a guess: the gain that produced it can
		 * equally have come from a trade, a store sale, or an untracked pile of the same item. A pile
		 * we really recovered leaves the ground at once, so a pile still lying there more than
		 * GROUND_EARLY_MARGIN_TICKS later was not the source of that gain. The mark is then withdrawn
		 * and the row falls to `unknown`, which `takeArmed` already guarantees. Withdrawing can only
		 * WEAKEN a verdict; nothing on this path ever sets `selfPickedUp`.
		 */
		volatile int provisionalMarkTick = -1;
		/**
		 * Set when this pile was PARKED because a local Take of its item was live at the moment the
		 * pile left the ground.
		 *
		 * The live Take is the reason the removal was held, so it is a fact about the REMOVAL and it
		 * must be read at removal time. A Take can go stale, or be pruned by an unrelated inventory
		 * change, while the removal is still parked. Asking `hasArmedPickupFor` later then answers
		 * "no Take" about a pile whose removal a Take really could explain, and the deadline reset
		 * publishes `despawn_timer` - "nobody took it" - for our own recovery after a long walk, or
		 * for a customer collection. This flag remembers the removal-time answer, so the reset
		 * refuses and the row stays `unknown`. Like every other condition on that path it can only
		 * WEAKEN the verdict.
		 */
		volatile boolean parkedForLiveTake;
		/**
		 * Set when this record was minted for a drop the game reported as a MERGE into an existing
		 * stack, so it is a SHADOW of a physical pile rather than a pile of its own.
		 *
		 * ROUND 7, FINDING R2. The merge branch in trackGroundDrop is scoped to a running session,
		 * so with no session a stackable dropped twice onto one tile leaves two records for ONE
		 * physical pile. The single despawn consumes one and the other lingers on a tile that is now
		 * physically empty: the R1 phantom. A record carrying this flag is therefore known NOT to
		 * stand for an independent pile, which is what lets the despawn resolver tell the R1 phantom
		 * (one physical pile, two records) apart from the R2 case (two physical piles of an
		 * unstackable, only one of them ours). Without the distinction, closing R2 re-opens R1.
		 */
		volatile boolean mergeShadow;
		/**
		 * Set at despawn time when an OWNED and an UNOWNED real record both matched this item and
		 * tile, so which physical pile actually went is unknowable.
		 *
		 * ROUND 7, FINDING R2. The row this record publishes then carries NO drop_session_id and no
		 * drop_seq, because a guess about which pile went is a fabricated attribution. Carried on
		 * the record rather than passed as an argument so a PARKED removal, settled ticks later,
		 * publishes the same refusal the despawn decided.
		 */
		volatile boolean attributionAmbiguous;
		/**
		 * ROUND 9. Set when the count proved one of OUR OWN piles left this tile, but two or more of
		 * our own records on it carry the SAME quantity, so which of those equal piles went cannot
		 * be told apart.
		 *
		 * This is WEAKER than {@link #attributionAmbiguous} on purpose, and the difference is the
		 * whole point. The pile that left was certainly OURS and its quantity is certainly this one,
		 * so the row keeps its drop_session_id and its qty and stays honest evidence. What is
		 * uncertain is only WHICH of our equal piles it was, which is drop_seq and therefore
		 * ticks_on_ground. The row says so in `attribution_uncertain`, and the SESSION is not marked
		 * unprovable: two of our own equal piles are not a stranger.
		 */
		volatile boolean qtyTieUncertain;

		DroppedGroundItem(int item, long qty, int x, int y, int plane, Map<String, Object> location,
			int dropTick, int despawnTick)
		{
			this.item = item;
			this.qty = qty;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.location = location;
			this.dropTick = dropTick;
			this.despawnTick = despawnTick;
		}
	}

	// ---- ground-item lifecycle: our own dropped piles, tracked from spawn to removal ----

	/**
	 * Record a pile WE dropped, so a later ItemDespawned on the same tile can be attributed to it.
	 * Called only from the own-tile OWNERSHIP_SELF spawn path, so a foreign pile is never tracked.
	 */
	void trackGroundDrop(int item, long qty, int x, int y, int plane, Map<String, Object> location,
		int dropTick, int despawnTick)
	{
		trackGroundDrop(item, qty, x, y, plane, location, dropTick, despawnTick, false);
	}

	/**
	 * @param quantityMerge true when this arrived as an ItemQuantityChanged growth, meaning the game
	 *                      merged the drop into an existing stack instead of spawning a new pile.
	 */
	void trackGroundDrop(int item, long qty, int x, int y, int plane, Map<String, Object> location,
		int dropTick, int despawnTick, boolean quantityMerge)
	{
		trackGroundDrop(item, qty, x, y, plane, location, dropTick, despawnTick, quantityMerge,
			plugin.dropProof.pendingDropSessionId, plugin.dropProof.pendingDropSeq);
	}

	void trackGroundDrop(int item, long qty, int x, int y, int plane, Map<String, Object> location,
		int dropTick, int despawnTick, boolean quantityMerge, String sid, int seq)
	{
		synchronized (groundDrops)
		{
			// A MERGED STACK IS ONE PILE. Dropping a stackable onto a live pile of the same item on
			// the same tile merges it in the game, and the client will fire exactly one
			// ItemDespawned for the result. Tracking a second DroppedGroundItem here made a second
			// session key that no despawn could release (finding F1 path A).
			//
			// FINDING B2: the lookup is ownership-AGNOSTIC on purpose, and it is findGroundDrop —
			// the SAME resolver onItemDespawned uses. So the entry adopted here is exactly the entry
			// that despawn will resolve to. Requiring session membership here is what let one
			// physical pile become two tracked entries whenever the pile predated the session.
			//
			// STILL SCOPED TO A RUNNING SESSION. With no session there are no keys to strand, and
			// two tracked records for one stack is the pre-existing behaviour the ground-removal
			// arms pin: it makes the removal report cause `unknown` rather than fabricate an
			// attribution. Widening the merge branch to the no-session case would change that, and
			// it has nothing to do with this defect.
			DroppedGroundItem merged =
				quantityMerge && plugin.dropProof.dropSession.active() ? findGroundDrop(item, x, y, plane) : null;
			if (merged != null)
			{
				plugin.dropProof.adoptMergedPileIntoDropSession(merged, sid, seq);
				return;
			}
			while (groundDrops.size() >= GROUND_TRACK_MAX)
			{
				// An evicted pile is one we will never see leave the ground. Tell the session, or
				// its key is stranded and the recorder waits on it forever (finding F1 path C).
				plugin.dropProof.abandonPileFromDropSession(groundDrops.pollFirst());
			}
			DroppedGroundItem g =
				new DroppedGroundItem(item, qty, x, y, plane, location, dropTick, despawnTick);
			// The game called this drop a MERGE and the merge branch above did not take it, so this
			// record shadows a pile already on the tile rather than standing for one of its own.
			// FINDING R2 needs that distinction; see DroppedGroundItem.mergeShadow.
			g.mergeShadow = quantityMerge;
			groundDrops.addLast(g);
			plugin.dropProof.attachPileToDropSession(g, sid, seq);
		}
	}

	/**
	 * Resolve a removal or a merge at this item and tile: the RUNNING SESSION'S OWN record first,
	 * then the oldest match.
	 *
	 * ROUND 6, FINDING R1 — WHY OWNERSHIP IS PART OF THE RESOLUTION ORDER. The merge branch above is
	 * scoped to a running session, so with no session a stackable dropped twice onto one tile is
	 * still TWO records for ONE physical pile. The single despawn consumed the OLDEST of them and
	 * left the other on a tile that is now physically empty: a PHANTOM. It is unowned, so no
	 * teardown removes it, and no despawn will ever come for it.
	 *
	 * A later session then dropped on that same tile and the ONE despawn it gets resolved to the
	 * phantom again, because the phantom is older. The session's own key was never released, the
	 * tail never armed, and only MAX_SESSION_MILLIS ended the recording — 30 minutes rather than the
	 * five seconds after the final pile both user-facing strings promise. The invariant backstop
	 * cannot see it either: dropSessionHoldsOwnedPile asks whether the session owns ANY tracked
	 * record, and it still owns the record whose physical pile is gone. The disagreement is between
	 * the tracked RECORD and the PHYSICAL pile, which neither half of that invariant can observe.
	 *
	 * So when a session is running its own record wins. A despawn on a tile the session dropped on
	 * is the session's pile far more often than it is a stale record, and the session's record is
	 * the only one whose resolution can release a recorder key.
	 *
	 * NO-SESSION BEHAVIOUR IS UNCHANGED, deliberately. With no session there is no owner, the loop
	 * falls straight through to the oldest match, and the two-records-for-one-stack attribution the
	 * ground-removal arms pin is exactly as it was. That behaviour makes a removal report `unknown`
	 * rather than fabricate an attribution, and this change must not touch it.
	 *
	 * ONE RESOLVER, BOTH CALLERS. trackGroundDrop's merge lookup and onItemDespawned use this same
	 * method, so the record a merge ADOPTS is exactly the record the despawn will later resolve to.
	 * Finding B2 was caused by those two asking different questions.
	 */
	DroppedGroundItem findGroundDrop(int item, int x, int y, int plane)
	{
		String sid = plugin.dropProof.dropSession.sessionId();
		synchronized (groundDrops)
		{
			if (sid != null)
			{
				for (DroppedGroundItem g : groundDrops)
				{
					if (g.item == item && g.x == x && g.y == y && g.plane == plane
						&& sid.equals(plugin.dropProof.dropPileSession.get(g)))
					{
						return g;
					}
				}
			}
			for (DroppedGroundItem g : groundDrops)
			{
				if (g.item == item && g.x == x && g.y == y && g.plane == plane)
				{
					return g;
				}
			}
		}
		return null;
	}

	/**
	 * Resolve the ONE ItemDespawned the game fires at this item and tile, and say whether the
	 * choice was a GUESS.
	 *
	 * ROUND 7, FINDING R2 — WHY THE DESPAWN PATH NEEDS ITS OWN RESOLVER. Round 6 made
	 * findGroundDrop prefer the running session's own record, which closed R1. On a tile holding
	 * TWO REAL PILES of the same UNSTACKABLE, only one of them the session's, that preference took
	 * the despawn of the OTHER pile: the session's key was released while its pile was still on the
	 * ground, the recording stopped early, and the manifest claimed COMPLETE on a clip missing the
	 * collection. The row published for the other pile carried our session id, our sequence, our
	 * quantity and our time on ground.
	 *
	 * WHICH PILE WENT IS UNKNOWABLE. The game tells us an item of that id left that tile. It does
	 * not tell us which of two identical piles it was. Round 6 guessed "ours" and round 5 guessed
	 * "the oldest", and both guesses are wrong half the time. So this method REFUSES: it reports the
	 * choice as ambiguous and the callers then decline to claim anything.
	 *
	 *   ATTRIBUTION goes back to the pre-round-6 answer — the oldest UNOWNED record. That is the
	 *   other pile's own record, so the published row is the honest one it always was, with no
	 *   session linkage on it (see attributionAmbiguous).
	 *
	 *   THE RECORDER keeps its key. Our pile may still be lying there, so releasing the key would be
	 *   the early stop all over again. The session is marked unprovable instead, which puts COMPLETE
	 *   permanently out of reach, and it still ends: by the later despawn that is no longer
	 *   ambiguous, by the orphaned-state invariant, or by the 30-minute cap.
	 *
	 * A MERGE SHADOW IS NOT A SECOND PILE, and that is what keeps R1 closed. The R1 phantom is one
	 * physical pile tracked twice, because the merge branch is scoped to a running session: the
	 * second record was minted from a quantityMerge and stands for nothing of its own. Treating it
	 * as a rival pile here would make every R1 route ambiguous again and strand the recorder exactly
	 * as round 3 measured. So only a NON-shadow unowned record can make a despawn ambiguous.
	 */
	static final class GroundDespawnMatch
	{
		final DroppedGroundItem record;
		final boolean ambiguous;

		GroundDespawnMatch(DroppedGroundItem record, boolean ambiguous)
		{
			this.record = record;
			this.ambiguous = ambiguous;
		}
	}

	/** Pure: picks the record and classifies the choice. Changes no state. */
	GroundDespawnMatch matchDespawnedGroundDrop(int item, int x, int y, int plane)
	{
		String sid = plugin.dropProof.dropSession.sessionId();
		if (sid != null)
		{
			DroppedGroundItem owned = null;
			DroppedGroundItem unownedReal = null;
			synchronized (groundDrops)
			{
				// groundDrops is append-ordered, so the first match in each class is the oldest.
				for (DroppedGroundItem g : groundDrops)
				{
					if (g.item != item || g.x != x || g.y != y || g.plane != plane)
					{
						continue;
					}
					if (sid.equals(plugin.dropProof.dropPileSession.get(g)))
					{
						if (owned == null)
						{
							owned = g;
						}
					}
					else if (!g.mergeShadow && unownedReal == null)
					{
						unownedReal = g;
					}
				}
			}
			if (owned != null && unownedReal != null)
			{
				return new GroundDespawnMatch(unownedReal, true);
			}
			if (owned != null)
			{
				return new GroundDespawnMatch(owned, false);
			}
		}
		return new GroundDespawnMatch(findGroundDrop(item, x, y, plane), false);
	}

	/**
	 * Resolve a despawn and APPLY the refusal when the choice was a guess. The one entry point the
	 * despawn path uses, so nothing can resolve a despawn and forget to record the ambiguity.
	 */
	DroppedGroundItem resolveDespawnedGroundDrop(int item, int x, int y, int plane)
	{
		return resolveDespawnedGroundDrop(item, x, y, plane, null);
	}

	/**
	 * Every record THIS SESSION owns for that item on that tile, oldest first.
	 *
	 * ROUND 9. The count of these is |S| in the despawn rule below. groundDrops is append-ordered,
	 * so the returned order is the order the piles were dropped.
	 */
	List<DroppedGroundItem> sessionOwnedPilesAt(int item, int x, int y, int plane)
	{
		List<DroppedGroundItem> out = new ArrayList<>();
		String sid = plugin.dropProof.dropSession.sessionId();
		if (sid == null)
		{
			return out;
		}
		synchronized (groundDrops)
		{
			for (DroppedGroundItem g : groundDrops)
			{
				if (g.item == item && g.x == x && g.y == y && g.plane == plane
					&& sid.equals(plugin.dropProof.dropPileSession.get(g)))
				{
					out.add(g);
				}
			}
		}
		return out;
	}

	/**
	 * A despawning pile that can state its own quantity.
	 *
	 * The live path passes a {@code net.runelite.api.TileItem}, whose {@code getQuantity()} was read
	 * from the resolved runelite-api-1.12.39 jar with javap. The test harness models a physical pile
	 * with its own type, so this interface is the seam that lets both answer the same question
	 * without the harness having to implement the whole TileItem surface.
	 */
	interface DespawnQuantity
	{
		long despawnedQuantity();
	}

	/** The quantity of the pile that is going, or null when nothing can say. */
	static Long despawningQuantity(Object despawnedItem)
	{
		if (despawnedItem instanceof TileItem)
		{
			return (long) ((TileItem) despawnedItem).getQuantity();
		}
		if (despawnedItem instanceof DespawnQuantity)
		{
			return ((DespawnQuantity) despawnedItem).despawnedQuantity();
		}
		return null;
	}

	/**
	 * ROUND 8, FINDING R4 — THE SCENE DECIDES WHETHER A DESPAWN IS AMBIGUOUS, NOT OUR BOOKKEEPING.
	 *
	 * Round 7 asked "do I hold TWO matching RECORDS?". That question can only see a rival the plugin
	 * TRACKED, and groundDrops only ever holds OWNERSHIP_SELF piles that had an armed drop pending.
	 * Three real piles are invisible to it: another player's pile, our own pile from a session that
	 * ended while the pile was still down (every end path runs endDropSessionTracking, which removes
	 * the records and leaves the PILES), and a pile dropped before the token was linked. When one of
	 * those despawns, round 7 saw exactly one matching record — OURS — called it unambiguous,
	 * released the key, stopped recording five seconds later with our pile still on the ground, and
	 * published COMPLETE with our session id on a row describing somebody else's pile.
	 *
	 * SO THE QUESTION CHANGES to one the game can answer: after this despawn, is there still an item
	 * of this id on this tile? If there is, the despawn is AMBIGUOUS whatever we tracked, because
	 * our pile may be the one still lying there. If there is not, it is unambiguous: nothing of that
	 * kind is left, so our pile went.
	 *
	 * THE SCENE CAN ONLY MAKE A DESPAWN MORE AMBIGUOUS, NEVER LESS. A round-7 ambiguity stands
	 * whatever the scene says. UNKNOWN changes nothing, because a tile out of scene is not an empty
	 * tile and this must not invent certainty from a failed read.
	 *
	 * @param despawnedItem the TileItem that is going, excluded from the scene count by identity, or
	 *                      null. See {@link #scenePileState}.
	 */
	DroppedGroundItem resolveDespawnedGroundDrop(int item, int x, int y, int plane,
		Object despawnedItem)
	{
		GroundDespawnMatch m = matchDespawnedGroundDrop(item, x, y, plane);
		if (m.record != null && m.ambiguous)
		{
			// ROUND 7. Two records matched and one of them is a real rival pile, so the OTHER pile's
			// own record answers the despawn and the flag rides on it. Carried on the RECORD, because
			// a parked removal publishes its row ticks later and must publish the same refusal this
			// despawn decided. Our own record stays tracked and our key stays held.
			m.record.attributionAmbiguous = true;
			plugin.dropProof.dropSession.resolutionAmbiguous(System.currentTimeMillis());
			return m.record;
		}
		// ROUND 9, FINDING R7 — COUNT THE TILE, AND USE THE QUANTITY. DO NOT ASK PRESENCE.
		//
		// Round 8 asked "is an item of this id still on this tile?". That question cannot tell our
		// OWN second pile from a stranger's, so a session that dropped ten piles of one item on one
		// tile had the first nine despawns refused, published one of its ten evidence rows, and that
		// row described the WRONG pile. An ordinary store delivery is exactly that shape.
		//
		// The counting question CAN tell them apart. |S| is how many records this session still owns
		// for that item on that tile, INCLUDING the one this despawn is about to credit. C is how
		// many piles of that id the scene still shows, with the despawning TileItem excluded by
		// identity. After this removal the scene holds C piles while we still claim |S|, so:
		//
		//   C >= |S|  AMBIGUOUS. At least as many piles are left as we own, so at least one of them
		//             is not ours, and the one that left may equally have been that stranger's.
		//   C <  |S|  UNAMBIGUOUS. There are fewer piles left than we own, so one of OURS went.
		//
		// ONLY a despawn about to be credited to our own pile is tested. A record that is not ours
		// releases no key and claims no session linkage, so there is nothing here for the scene to
		// protect. UNKNOWN keeps round-8 behaviour: no refusal is ever inferred from a failed read,
		// and the invariant and the hard cap remain the backstops.
		if (m.record != null
			&& plugin.dropProof.dropSession.sessionId() != null
			&& plugin.dropProof.dropSession.sessionId().equals(plugin.dropProof.dropSessionForPile(m.record)))
		{
			SceneTileItems scene = sceneTileItems(item, x, y, plane, despawnedItem);
			if (scene.state != ScenePileState.UNKNOWN)
			{
				List<DroppedGroundItem> own = sessionOwnedPilesAt(item, x, y, plane);
				if (scene.count >= own.size())
				{
					// THE DESPAWN IS REFUSED WHOLE, which is the round-7 handling with no rival
					// record to hand it to. Returning null leaves our record TRACKED and our key
					// HELD, so the tail cannot arm while our pile may still be down, and publishes
					// NO row: a row built from our record would describe a pile that did not
					// necessarily go, which is the fabricated attribution round 7 exists to refuse.
					//
					// attributionAmbiguous is deliberately NOT set on our record. Our pile is still
					// down and its own later removal may be perfectly attributable; the flag never
					// clears, so setting it here would refuse an honest linkage we have not yet
					// lost. COMPLETE is already out of reach through the recorder, which is the
					// property that matters.
					plugin.dropProof.dropSession.resolutionAmbiguous(System.currentTimeMillis());
					return null;
				}
				if (own.size() > 1)
				{
					return chooseOwnPileByQuantity(own, despawnedItem);
				}
			}
		}
		return m.record;
	}

	/**
	 * ROUND 9 — WHICH of our own piles just left, when several of ours are on that tile.
	 *
	 * The count already proved one of OURS went. The QUANTITY says which one.
	 * {@code TileItem.getQuantity()} was read from runelite-api-1.12.39 with javap.
	 *
	 * <ul>
	 * <li>EXACTLY ONE of our records carries that quantity: that is the pile, with its own
	 *     drop_seq, its own ticks_on_ground and its own qty. This is the ordinary delivery.</li>
	 * <li>SEVERAL of our records carry it: they are indistinguishable BY QUANTITY, and nothing else
	 *     on the tile can separate them. The oldest is released so its row is published, and ONLY
	 *     that row's attribution is marked uncertain. The session is NOT marked unprovable: every
	 *     candidate is ours, every quantity is the same, and the row's own qty is right. Refusing
	 *     the whole session over two of our own equal piles would be FINDING R7 again.</li>
	 * <li>NONE of our records carries it, or nothing could state the despawning quantity: a pile of
	 *     a size we never dropped left that tile, so it was a stranger's. Refuse.</li>
	 * </ul>
	 */
	private DroppedGroundItem chooseOwnPileByQuantity(List<DroppedGroundItem> own,
		Object despawnedItem)
	{
		Long q = despawningQuantity(despawnedItem);
		List<DroppedGroundItem> matches = new ArrayList<>();
		if (q != null)
		{
			for (DroppedGroundItem g : own)
			{
				if (g.qty == q)
				{
					matches.add(g);
				}
			}
		}
		if (matches.isEmpty())
		{
			plugin.dropProof.dropSession.resolutionAmbiguous(System.currentTimeMillis());
			return null;
		}
		DroppedGroundItem chosen = matches.get(0);	// oldest, groundDrops is append-ordered
		if (matches.size() > 1)
		{
			chosen.qtyTieUncertain = true;
		}
		return chosen;
	}

	void clearGroundDrops()
	{
		synchronized (groundDrops)
		{
			groundDrops.clear();
		}
		synchronized (telegrabSightings)
		{
			telegrabSightings.clear();
		}
	}

	/** True when a pile we track (live or parked for a Take) lies on this tile. */
	boolean isTrackedPileTile(int x, int y, int plane)
	{
		synchronized (groundDrops)
		{
			for (DroppedGroundItem g : groundDrops)
			{
				if (g.x == x && g.y == y && g.plane == plane)
				{
					return true;
				}
			}
		}
		synchronized (pendingRemovals)
		{
			for (DroppedGroundItem g : pendingRemovals.keySet())
			{
				if (g.x == x && g.y == y && g.plane == plane)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Record one Telekinetic Grab sighting when it targets a tracked pile's tile. Prunes rows older than
	 * the attribution window and keeps at most TELEGRAB_SIGHTING_CAP (oldest dropped).
	 */
	void recordTelegrabSighting(DropCandidates.TelegrabSighting s)
	{
		if (s == null || !isTrackedPileTile(s.x, s.y, s.plane))
		{
			return;
		}
		synchronized (telegrabSightings)
		{
			telegrabSightings.removeIf(o -> s.tick - o.tick > DropCandidates.TELEGRAB_WINDOW_TICKS || o.tick > s.tick);
			telegrabSightings.add(s);
			while (telegrabSightings.size() > TELEGRAB_SIGHTING_CAP)
			{
				telegrabSightings.remove(0);
			}
		}
	}

	/**
	 * A Telekinetic Grab projectile in flight. Recorded once per projectile, with its caster, when it
	 * targets the tile of a pile we track. Other projectiles return on the first id compare.
	 */
	void onProjectileMoved(ProjectileMoved event)
	{
		if (!plugin.grants.captureEnabled() || plugin.client == null || event == null || event.getProjectile() == null
			|| event.getProjectile().getId() != DropCandidates.TELEGRAB_PROJECTILE)
		{
			return;
		}
		Projectile pr = event.getProjectile();
		if (!telegrabProjectilesSeen.add(pr))
		{
			return;
		}
		WorldPoint target = pr.getTargetPoint();
		if (target == null && event.getPosition() != null)
		{
			target = WorldPoint.fromLocal(plugin.client, event.getPosition());
		}
		if (target == null)
		{
			return;
		}
		Actor src = pr.getSourceActor();
		boolean self = src != null && src == plugin.client.getLocalPlayer();
		String caster = src == null || src.getName() == null ? null : Text.removeTags(src.getName());
		WorldPoint from = src != null && src.getWorldLocation() != null ? src.getWorldLocation() : pr.getSourcePoint();
		recordTelegrabSighting(new DropCandidates.TelegrabSighting(target.getX(), target.getY(), target.getPlane(),
			plugin.client.getTickCount(), "projectile", self ? null : caster, self,
			from == null ? target.getX() : from.getX(), from == null ? target.getY() : from.getY()));
	}

	/** A Telekinetic Grab impact graphic. It carries no caster, only the tile. */
	void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (plugin.client == null || event == null || event.getGraphicsObject() == null
			|| event.getGraphicsObject().getId() != DropCandidates.TELEGRAB_IMPACT
			|| event.getGraphicsObject().getLocation() == null)
		{
			return;
		}
		GraphicsObject go = event.getGraphicsObject();
		WorldPoint wp = WorldPoint.fromLocal(plugin.client, go.getLocation());
		if (wp == null)
		{
			return;
		}
		recordTelegrabSighting(new DropCandidates.TelegrabSighting(wp.getX(), wp.getY(), go.getLevel(),
			plugin.client.getTickCount(), "impact", null, false, wp.getX(), wp.getY()));
	}

	int groundDropCount()
	{
		synchronized (groundDrops)
		{
			return groundDrops.size();
		}
	}

	/**
	 * Could this Take have been aimed at this pile?
	 *
	 * Item, and the tile whenever the client gave us one. A Take the client gave NO tile for could
	 * have been aimed at any pile of its item, so it matches all of them here.
	 *
	 * This is the ONLY match rule in the narrow candidate, and it is used in both directions. To
	 * WEAKEN a verdict, one match is enough: the pile may have been ours. To CLAIM a pile, the
	 * caller additionally requires that it be the ONLY match, so a no-tile Take against two piles
	 * claims neither, and a tiled Take against two piles on that tile claims neither. Identity comes
	 * from the uniqueness, not from the tile alone, and the two directions can never disagree
	 * because there is only one predicate.
	 */
	static boolean takeCouldName(OffBookPendings.InvDeltaPending take, DroppedGroundItem g)
	{
		return g.item == take.item && (take.takeX < 0
			|| (g.x == take.takeX && g.y == take.takeY && g.plane == take.takePlane));
	}

	/**
	 * Every pile we still hold state for: on the ground, and parked awaiting a verdict.
	 *
	 * One snapshot, because a pile the Take named may already have despawned - which is the ORDINARY
	 * case for a completed pickup, since the despawn callback runs before the inventory change.
	 * Taken under both locks, then released, so callers iterate a copy and never hold two at once.
	 */
	private List<DroppedGroundItem> trackedPiles()
	{
		List<DroppedGroundItem> all = new ArrayList<>();
		synchronized (pendingRemovals)
		{
			all.addAll(pendingRemovals.keySet());
		}
		synchronized (groundDrops)
		{
			all.addAll(groundDrops);
		}
		return all;
	}

	/**
	 * Mark every tracked pile a Take on this item and tile could have been aimed at.
	 *
	 * Run at CLICK time, not at resolution time. A Take is pruned by any unrelated inventory change,
	 * and a store account's inventory changes constantly, so waiting until the arrival lands loses
	 * the flag exactly when the walk was long enough to matter - and the pile then published
	 * `removed_early` over our own recovery.
	 */
	void flagTakeArmedPiles(OffBookPendings.InvDeltaPending take)
	{
		for (DroppedGroundItem g : trackedPiles())
		{
			g.takeArmed = g.takeArmed || takeCouldName(take, g);
		}
	}

	/**
	 * The ONE tracked pile this Take could have been aimed at, or null when there is not exactly one.
	 *
	 * Two candidates are indistinguishable to the client, so nothing is claimed and both report
	 * `unknown`. That covers two piles on one tile and a no-tile Take against two piles of its item
	 * with the same rule. Looks in both collections because the pile may already have despawned and
	 * be parked awaiting its verdict, which is the ordinary case for a completed pickup.
	 */
	private DroppedGroundItem soleClaimablePile(OffBookPendings.InvDeltaPending take)
	{
		DroppedGroundItem found = null;
		for (DroppedGroundItem g : trackedPiles())
		{
			if (!g.selfPickedUp && takeCouldName(take, g))
			{
				if (found != null)
				{
					return null;	// two candidates: indistinguishable, so claim neither
				}
				found = g;
			}
		}
		return found;
	}

	/**
	 * Attribute a recovered pile to a Take, but ONLY when the evidence is unambiguous.
	 *
	 * Every condition below is necessary and none is negotiable, because the failure this guards
	 * against is naming a staff member as the taker of a pile a CUSTOMER collected:
	 *
	 *   * exactly ONE armed Take of the item in this pass - two Takes and the client cannot say
	 *     which one the server actually ran, and OSRS runs the LAST click of a burst while the
	 *     queue hands the arrival to the FIRST pending;
	 *   * exactly ONE tracked pile that Take could have been aimed at - two piles on the tile, or a
	 *     no-tile Take against two piles of the item, name nothing;
	 *   * the measured gain equals that pile EXACTLY - a ground pile is recovered whole, so a
	 *     1,500-coin store sale is not a 1,200-coin pile.
	 *
	 * The Take's own freshness is enforced earlier, in `resolveOnePending`: a Take past its window
	 * is discarded before it can produce a `pickup` row at all, so it cannot reach this method.
	 *
	 * Anything else marks nothing. The pile keeps its `takeArmed` flag and reports `unknown`, which
	 * is the truthful answer: we may well have taken it, so `removed_early` would be as wrong as
	 * claiming the pickup.
	 *
	 * @return true when a pile was marked, so the caller can publish it at once.
	 */
	boolean markGroundDropSelfPickedUp(OffBookPendings.InvDeltaPending take, long rawGain,
		List<OffBookPendings.InvDeltaPending> snapshot, int currentTick)
	{
		if (take == null)
		{
			return false;
		}
		for (OffBookPendings.InvDeltaPending other : snapshot)
		{
			if (other != take && "pickup".equals(other.base) && other.item == take.item)
			{
				return false;	// a second armed Take: nothing says which click the server ran
			}
		}
		DroppedGroundItem g = soleClaimablePile(take);
		if (g == null || rawGain != g.qty)
		{
			return false;
		}
		g.selfPickedUp = true;
		// A pile still lying on the ground has NOT been recovered yet, whatever the inventory did.
		// Record the tick, so the removal can check the pile really left. See `provisionalMarkTick`.
		if (stillOnGround(g))
		{
			g.provisionalMarkTick = currentTick;
		}
		return true;
	}

	/** True while the pile is still lying on the ground, so its removal has not been observed yet. */
	private boolean stillOnGround(DroppedGroundItem g)
	{
		synchronized (groundDrops)
		{
			return groundDrops.contains(g);
		}
	}

	/**
	 * Finalize removals whose resolution window has closed, or whose pickup has landed.
	 *
	 * A pile marked `selfPickedUp` finalizes at once - the evidence is in. One that ran out of
	 * window finalizes on what can actually be proven, which is never `self_pickup`. Called from the
	 * tick hook and again whenever a pickup resolves, so the common case publishes immediately
	 * rather than waiting out the window.
	 */
	/**
	 * @param tickPass true only for the once-per-tick call. The budget counts TICKS, not calls: a
	 * pickup resolving re-enters this method, so counting every call let ten pickups landing in one
	 * tick spend the whole budget of every other parked pile. The re-entrant call exists to PUBLISH
	 * a pile whose answer just arrived, never to age one.
	 */
	/**
	 * @param unreliable true for the lifecycle disarms, which take EVERY parked entry and publish it
	 * as `unknown`. Observation is genuinely unreliable across a hop, a logout or a region boundary,
	 * so no stronger cause is supportable there.
	 * @param tickPass true only for the once-per-tick call. The attempt budget counts TICKS, not
	 * calls: a pickup resolving re-enters this method, so counting every call let ten pickups landing
	 * in one tick spend the whole budget of every other parked pile. The re-entrant call exists to
	 * PUBLISH a pile whose answer just arrived, never to age one.
	 */
	void settlePendingRemovals(int currentTick, boolean tickPass, boolean unreliable)
	{
		Map<DroppedGroundItem, Integer> due = new LinkedHashMap<>();
		synchronized (pendingRemovals)
		{
			Iterator<Map.Entry<DroppedGroundItem, Integer>> it =
				pendingRemovals.entrySet().iterator();
			while (it.hasNext())
			{
				Map.Entry<DroppedGroundItem, Integer> e = it.next();
				int waited = currentTick - e.getValue();
				// `waited < 0` means the tick counter moved BACKWARDS under us; `settleAttempts`
				// counts attempts instead of trusting the clock, because a counter that is FROZEN
				// never lets the difference reach the window either. Without both, the entry strands
				// in the map forever and its event is lost. Finalize: a late verdict beats none.
				if (tickPass)
				{
					e.getKey().settleAttempts++;
				}
				if (unreliable || e.getKey().selfPickedUp || waited >= REMOVAL_RESOLVE_MAX_TICKS
					|| waited < 0 || e.getKey().settleAttempts > REMOVAL_RESOLVE_MAX_TICKS)
				{
					due.put(e.getKey(), e.getValue());
					it.remove();		// removed as it fires - never two finals for one lifecycle
				}
			}
		}
		for (Map.Entry<DroppedGroundItem, Integer> e : due.entrySet())
		{
			// The tick the pile ACTUALLY left the ground, not the tick we got around to concluding.
			// Passing `currentTick` here let up to REMOVAL_RESOLVE_MAX_TICKS of drift cross the
			// despawn deadline, silently turning a genuine `removed_early` into `despawn_timer` -
			// a STRONGER claim than the evidence supports, and the opposite of failing closed.
			emitGroundRemoval(e.getKey(), e.getValue(), unreliable || groundObservationUnreliable);
		}
	}

	/**
	 * Discard the parked removals - but PUBLISH them first, as `unknown`.
	 *
	 * A region boundary, a hop or a logout can land inside the resolution window. Clearing the map
	 * silently made the pile's removal vanish from the record entirely, which is worse than the
	 * weaker verdict the same event got before it was ever deferred: a delivery simply disappeared.
	 * Observation is genuinely unreliable across these transitions, so the honest cause is `unknown`
	 * and never `despawn_timer` or `removed_early`. Each entry is emitted at the tick it was
	 * actually removed, and removed from the map as it fires, so this cannot double-emit.
	 */
	void clearPendingRemovals()
	{
		settlePendingRemovals(plugin.client == null ? 0 : plugin.client.getTickCount(), false, true);
	}

	/**
	 * Our dropped pile left the ground. Emit what we can HONESTLY say, and nothing more.
	 *
	 * `cause` is the whole point of this event and has exactly three values:
	 *   "self_pickup"   — our own account took it back; we saw the inventory rise, so this is certain.
	 *   "despawn_timer" — removal happened at or after the client's own reported despawn deadline.
	 *                     Nobody took it; it timed out.
	 *   "removed_early" — the pile went BEFORE its deadline while we were watching normally.
	 *
	 * "removed_early" means SOMETHING removed it early. It does NOT mean a customer collected it,
	 * and it names nobody. A different player picking it up and an unobserved client-side quirk are
	 * indistinguishable here, which is why the field says removed, not delivered. Any consumer that
	 * renders this as a delivery is reading it wrong.
	 *
	 * When observation was unreliable (scene reload, hop, logout, render-distance loss) the cause is
	 * "unknown" — the ambiguity is preserved explicitly rather than resolved by guessing.
	 */
	/**
	 * May a pile a Take once named still report `despawn_timer`? Only at its OWN deadline.
	 *
	 * `takeArmed` is set at click time and never clears, so a Take the client gave no tile for costs
	 * every same-item pile its timer evidence for the whole pile lifetime. This buys that evidence
	 * back in the one case where the Take cannot explain anything, and every condition is required:
	 *
	 *   * observation was reliable, and the client did report a deadline for this pile;
	 *   * the pile left AT that deadline, inside the margin on BOTH sides - so this can only ever
	 *     produce `despawn_timer`, never `removed_early`, which is the label the old
	 *     withholding-expiry rule got wrong (round 8). The upper bound matters as much as the lower
	 *     one: a removal a hundred ticks PAST a deadline means the record does not describe the pile
	 *     that just left, which is the merged-stack double-tracking residual, and a stale record
	 *     must never be read as a timer expiry;
	 *   * NO Take of this item is still live, so nothing in flight could still explain the removal,
	 *     and no Take was live when the pile actually LEFT the ground either - a Take that expired
	 *     while the removal sat parked still explains that removal, so the pile stays ambiguous;
	 *   * no gain was ever claimed for this pile, provisionally or otherwise - a claim that was made
	 *     and then withdrawn leaves the pile ambiguous, and ambiguous stays `unknown`.
	 *
	 * This method does NOT repeat the GROUND_TIMER_MIN_TICKS floor. Passing here only clears the way
	 * to the deadline branch below, which applies that floor itself, so a pile removed too fast for a
	 * real timer still reads `unknown`. `theTimerFloorStillRefusesAnImplausiblyFastExpiry` proves it.
	 *
	 * The reset is bound to the pile's own deadline and to nothing else. Resetting on TAKE EXPIRY
	 * instead is round 8 again: a walk longer than the Take window would publish our own recovery as
	 * `removed_early`. `aTakeThatExpiredWithThePileStillOnTheGroundStaysUnknown` holds that line.
	 */
	private boolean timerExpiryIsStillProvable(DroppedGroundItem g, int currentTick,
		boolean observationUnreliable)
	{
		return !observationUnreliable
			&& g.despawnTick >= 0
			&& currentTick >= g.despawnTick - GROUND_EARLY_MARGIN_TICKS
			&& currentTick <= g.despawnTick + GROUND_EARLY_MARGIN_TICKS
			&& g.provisionalMarkTick < 0
			&& !g.parkedForLiveTake
			&& !plugin.offBook.hasArmedPickupFor(g);
	}

	void emitGroundRemoval(DroppedGroundItem g, int currentTick, boolean observationUnreliable)
	{
		Map<String, Object> fields = Fields.of("item", g.item, "qty", g.qty);
		if (g.location != null)
		{
			fields.put("location", g.location);
		}
		fields.put("ticks_on_ground", Math.max(0, currentTick - g.dropTick));
		// Reconcile a PROVISIONAL mark against what the pile actually did. A pile we truly recovered
		// leaves the ground in the same tick, or in the next tick or two. One still lying there long
		// after the gain was never the source of that gain, so the mark is withdrawn and `takeArmed`
		// carries the row to `unknown`. This branch only WEAKENS: it never sets `selfPickedUp`.
		//
		// A NEGATIVE wait means the tick counter moved BACKWARDS under us, so the removal cannot be
		// timed against the mark at all. A bare `> margin` test reads every negative difference as
		// "inside the margin" and keeps the claim, which is the strongest reading of the weakest
		// evidence. `settlePendingRemovals` already treats `waited < 0` as a reason to stop trusting
		// the clock; the provisional path does the same and withdraws.
		int sinceMark = currentTick - g.provisionalMarkTick;
		if (g.selfPickedUp && g.provisionalMarkTick >= 0
			&& (sinceMark < 0 || sinceMark > GROUND_EARLY_MARGIN_TICKS))
		{
			g.selfPickedUp = false;
		}
		String cause;
		if (g.selfPickedUp)
		{
			cause = "self_pickup";
		}
		else if (g.takeArmed && !timerExpiryIsStillProvable(g, currentTick, observationUnreliable))
		{
			// A local Take named this pile and the recovery was not proven exactly. `removed_early`
			// would read as somebody else taking it and `despawn_timer` as nobody taking it, and we
			// can support neither. Refusing to attribute must not become a different wrong answer.
			cause = "unknown";
		}
		else if (observationUnreliable)
		{
			cause = "unknown";
		}
		else if (g.despawnTick < 0)
		{
			cause = "unknown";	// the client never reported a deadline — we cannot call early vs timer
		}
		else if (currentTick >= g.despawnTick - GROUND_EARLY_MARGIN_TICKS)
		{
			// FAIL CLOSED. `despawnTick` is an ABSOLUTE future tick (measured live 2026-09-12:
			// tick=244 while despawn_time=530 on a freshly dropped pile), so a pile that genuinely
			// reaches its deadline has been on the ground for the full timer. A removal that
			// satisfies the comparison after only a handful of ticks means the deadline we stored
			// does not describe this pile - a stale baseline, a re-tracked tile, a client whose tick
			// counter moved under us. Saying "nobody took it" there is an assertion we cannot
			// support. Unknown is the honest answer; a wrong cause is worse than no cause.
			cause = (currentTick - g.dropTick) < GROUND_TIMER_MIN_TICKS ? "unknown" : "despawn_timer";
		}
		else
		{
			cause = "removed_early";
		}
		fields.put("cause", cause);
		// Stated on every row so no downstream reader has to know this rule: the taker is never observable.
		fields.put("recipient", "UNKNOWN");
		// DROP-SESSION LINKAGE. Written on every removal of a session pile, whatever the cause, so a
		// self-pickup and a despawn join the manifest exactly like an early removal does. A removal
		// that carries no session id simply predates the feature or happened outside a session.
		// FINDING R2. An AMBIGUOUS despawn resolved between two real piles of one item on one tile
		// and could not know which went. Naming our session on that row would be a fabricated
		// attribution, so the row carries no linkage at all. `cause` is left to the ordinary rules,
		// which already answer `unknown` when the evidence does not support a claim.
		String dropSid = g.attributionAmbiguous ? null : plugin.dropProof.dropSessionForPile(g);
		int dropSeq = plugin.dropProof.dropSeqForPile(g);
		boolean resolvedForAudit = false;
		if (dropSid != null)
		{
			fields.put("drop_session_id", dropSid);
			fields.put("drop_seq", dropSeq);
			// ROUND 9. Two or more of OUR OWN piles of this item on this tile carried the same
			// quantity, so which of them left cannot be told apart. The linkage and the qty are
			// still ours and still right; only drop_seq and ticks_on_ground name one of several
			// equal candidates. Said on the row rather than hidden, so a reader is never misled.
			if (g.qtyTieUncertain)
			{
				fields.put("attribution_uncertain", "equal_quantity_own_piles");
			}
		}
		// The pile's own tile, on every row. A reader must be able to locate the pile without
		// knowing where our character stood, and the candidate offsets below are relative to THIS.
		Map<String, Object> tile = Fields.of("x", g.x, "y", g.y, "plane", g.plane);
		fields.put("tile", tile);
		// A Telekinetic Grab aimed at THIS tile just before it went. A grab takes the pile from range, so
		// a player standing on the tile may be a bystander; the reader must know. Absent when none seen.
		// Not on a self-pickup: our own Take already explains the removal.
		if (!"self_pickup".equals(cause))
		{
			Map<String, Object> grab;
			synchronized (telegrabSightings)
			{
				grab = DropCandidates.telegrabAt(telegrabSightings, g.x, g.y, g.plane, currentTick);
			}
			if (grab != null)
			{
				fields.put("telegrab", grab);
			}
		}
		// Who was standing here when an early removal happened. The pickup itself runs in another
		// player's client, so this is EVIDENCE, never an answer: it goes out under the same
		// `taken_by_candidates` inference field the store handoff uses, for the server-side resolver
		// (one candidate at dist 0 -> counterparty, anything else stays UNKNOWN). Only `removed_early`
		// carries it: presence at a timer expiry or a self-pickup says nothing about a taker.
		// The key is absent when nobody is around, matching the store path, so readers keep one rule.
		if ("removed_early".equals(cause))
		{
			// PILE-CENTRED, not player-centred. The old snapshot measured from OUR character, which
			// answers the wrong question: in a drop trade the staff member walks off before the
			// customer arrives, so a player at distance 0 from us is not on the pile and a player on
			// the pile can be many tiles away. Measured from the pile's own tile instead.
			List<Map<String, Object>> present =
				DropCandidates.candidatesAt(plugin.dropProof.observedPlayers(), g.x, g.y, g.plane);
			if (!present.isEmpty())
			{
				fields.put("taken_by_candidates", present);
			}
			// Resolve ONLY on exactly one player standing on the pile's tile. Two on it is
			// AMBIGUOUS, an empty tile is UNKNOWN, and neither ever names anybody. The Take itself
			// happens in the other client and nothing in our stream proves it.
			String resolved = fields.containsKey("telegrab") ? null : DropCandidates.resolveCounterparty(present);
			String status = DropCandidates.statusFor(present, resolved);
			fields.put("counterparty_status", status);
			if (resolved != null)
			{
				resolvedForAudit = true;
				// `counterparty_inferred`, never `counterparty`. The trade path's `counterparty` is
				// read from the trade window and IS the other party; this one is an inference from
				// where somebody stood, and the field name has to keep those apart.
				fields.put("counterparty_inferred", resolved);
			}
		}
		else
		{
			// Self-pickup, despawn timer and unknown all carry the status too, so a reader never has
			// to infer "no status means nobody asked" from an absent key.
			fields.put("counterparty_status", DropCandidates.STATUS_UNKNOWN);
		}
		plugin.events.emitEvent("ground_removed", fields);
		plugin.dropProof.noteDropAuditRemoval(dropSid, resolvedForAudit);
		// Tell the session this pile is gone. When it is the last one, the 5-second tail arms.
		plugin.dropProof.releasePileFromDropSession(g);
	}

	/**
	 * RuneLite fires ItemDespawned for a pile leaving the ground — taken, timed out, or simply
	 * out of scene. Only piles WE dropped and are still tracking reach an emit.
	 */
	void onItemDespawned(ItemDespawned event)
	{
		if (event != null && plugin.gameplayReady()) plugin.ownGameplay.groundClues.despawned(plugin.client, event.getItem(), System.currentTimeMillis());
		if (plugin.client == null || event == null || event.getItem() == null || event.getTile() == null)
		{
			return;
		}
		WorldPoint tw = event.getTile().getWorldLocation();
		if (tw == null)
		{
			return;
		}
		DroppedGroundItem g = resolveDespawnedGroundDrop(
			event.getItem().getId(), tw.getX(), tw.getY(), tw.getPlane(), event.getItem());
		if (g == null)
		{
			// Not one of ours — every other pile on the map despawns constantly — OR the scene
			// refused this despawn because an item of that id is still on the tile (round 8).
			return;
		}
		synchronized (groundDrops)
		{
			groundDrops.remove(g);
		}
		int now = plugin.client.getTickCount();
		// Do NOT conclude yet if a local Take for this item is still in flight: the inventory gain
		// that proves it arrives AFTER this callback. Hold the removal for a bounded window and let
		// whatever actually resolves decide the cause. Piles with no pending Take finalize
		// immediately, so ordinary despawns are not delayed at all.
		if (!g.selfPickedUp && plugin.offBook.hasArmedPickupFor(g))
		{
			g.takeArmed = true;
			// Recorded HERE, at removal time, and never re-derived at settlement: the Take that
			// justified parking this pile can expire or be pruned before the row is published.
			g.parkedForLiveTake = true;
			synchronized (pendingRemovals)
			{
				pendingRemovals.put(g, now);
			}
			return;
		}
		emitGroundRemoval(g, now, groundObservationUnreliable);
	}
}
