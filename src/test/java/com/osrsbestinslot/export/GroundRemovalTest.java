package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GROUND-ITEM LIFECYCLE. A dropped item leaving the ground is observable; WHY it left mostly is not.
 *
 * These tests pin the honest boundary. "removed_early" means the pile went before its own despawn
 * deadline while we were watching normally — it never names a taker and never means delivered.
 * Anything we cannot separate stays "unknown" rather than being resolved by a guess.
 */
public class GroundRemovalTest
{
	private static final int CHAPS = 2495;
	private static final int DIAMOND = 1617;
	private static final int COINS = 995;
	private static final int POT = 1931;
	private static final int TILE_X = 3210;
	private static final int TILE_Y = 3420;

	/** The chaps case: dropped, left alone, removed before the timer. Ambiguous by nature. */
	@Test
	public void droppedItemRemovedBeforeItsDeadlineIsEarlyAndNamesNobody() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 300);
		assertEquals("the pile must be tracked", 1, plugin.groundDropCount());

		tick(plugin, 140);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));

		Map<String, Object> ev = onlyEvent(plugin, "ground_removed");
		assertEquals("removed_early", ev.get("cause"));
		assertEquals("UNKNOWN", ev.get("recipient"));
		assertEquals(CHAPS, ev.get("item"));
		assertEquals(40, ev.get("ticks_on_ground"));
		assertTrue("a removal must never claim a recipient",
			!String.valueOf(ev).toLowerCase().contains("customer"));
		assertEquals("the pile is no longer tracked", 0, plugin.groundDropCount());
	}

	/** Left to time out: removal at/after the client's own deadline is the timer, not a taker. */
	@Test
	public void removalAtTheDespawnDeadlineIsTheTimer() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 300);
		tick(plugin, 300);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		assertEquals("despawn_timer", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** We took it back ourselves. That is certain, so it must not read as an ambiguous removal. */
	@Test
	public void selfPickupExplainsItsOwnRemoval() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(DIAMOND, 1);
		dropItem(plugin, DIAMOND, 1, 100, 300);

		tick(plugin, 140);
		plugin.onMenuOptionClicked(menu("Take", "Uncut diamond", DIAMOND));
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		assertEquals("the self-pickup must emit", 1, eventsOfType(plugin, "pickup").size());

		plugin.onItemDespawned(despawn(DIAMOND, 1, TILE_X, TILE_Y, 0));
		assertEquals("self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** A scene reload despawns every pile for unrelated reasons. It must not read as early removal. */
	@Test
	public void sceneReloadNeverReportsEarlyRemoval() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 300);

		plugin.onGameStateChanged(gameState(GameState.LOADING));
		tick(plugin, 140);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));

		assertNull("a scene-reload despawn must emit nothing about our pile",
			firstEvent(plugin, "ground_removed"));
		assertEquals(0, plugin.groundDropCount());
	}

	/** Another player's pile despawning on our tile is not ours and must emit nothing. */
	@Test
	public void aForeignPileDespawningEmitsNothing() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 300);
		tick(plugin, 140);
		plugin.onItemDespawned(despawn(DIAMOND, 1, TILE_X, TILE_Y, 0));	// different item
		assertNull(firstEvent(plugin, "ground_removed"));
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X + 6, TILE_Y, 0));	// different tile
		assertNull(firstEvent(plugin, "ground_removed"));
		assertEquals("our pile is still tracked", 1, plugin.groundDropCount());
	}

	/** No reported deadline means we cannot tell early from timer. Preserve the ambiguity. */
	@Test
	public void noDespawnDeadlineStaysUnknown() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, -1);
		tick(plugin, 140);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		assertEquals("unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** The live three-item test: each pile is tracked and removed independently. */
	@Test
	public void threeDroppedItemsTrackAndRemoveIndependently() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1, DIAMOND, 1, COINS, 10);
		dropItem(plugin, CHAPS, 1, 100, 400, DIAMOND, 1, COINS, 10);
		dropItem(plugin, DIAMOND, 1, 105, 405, COINS, 10);
		dropItem(plugin, COINS, 10, 108, 408);
		assertEquals(3, plugin.groundDropCount());
		assertEquals("all three drops emitted", 3, eventsOfType(plugin, "drop").size());

		tick(plugin, 150);
		plugin.onItemDespawned(despawn(DIAMOND, 1, TILE_X, TILE_Y, 0));
		plugin.onItemDespawned(despawn(COINS, 10, TILE_X, TILE_Y, 0));
		assertEquals("only the removed piles emit", 2, eventsOfType(plugin, "ground_removed").size());
		assertEquals("the untouched pile stays tracked", 1, plugin.groundDropCount());
	}

	/** A pile we never dropped must never be adopted, even on our own tile with our ownership tag. */
	@Test
	public void aSpawnWithNoArmedDropIsNotTracked() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		spawn(plugin, CHAPS, 1, 300);
		assertEquals("nothing was dropped, so nothing may be tracked", 0, plugin.groundDropCount());
	}


	/**
	 * The scene-reload case above also discards tracking, so it cannot prove the unreliable-observation
	 * guard by itself. This one keeps a pile tracked and makes observation unreliable independently, so
	 * the guard is the only thing standing between a real removal and a false "removed_early".
	 */
	@Test
	public void aTrackedPileRemovedWhileObservationIsUnreliableStaysUnknown() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 300);
		assertEquals("the pile must still be tracked for this test to mean anything",
			1, plugin.groundDropCount());

		setUnreliable(plugin, true);
		tick(plugin, 140);			// well before the deadline: would be "removed_early" if trusted
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));

		assertEquals("an untrustworthy observation must never harden into removed_early",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * A pile of a DIFFERENT item appearing on our tile while one of our own drops is in flight. The
	 * spawn handler runs (a pending exists), so only the per-item check stops us adopting a stranger's
	 * pile and later reporting its despawn as our item being taken.
	 */
	@Test
	public void aForeignPileIsNotAdoptedWhileOneOfOurDropsIsInFlight() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", CHAPS));	// armed, not yet confirmed

		spawn(plugin, DIAMOND, 1, 300);				// someone else's pile, same tile

		assertEquals("only the item we actually dropped may be tracked", 0, plugin.groundDropCount());
	}


	/**
	 * SANDRA'S EXACT FAILING SHAPE, end to end, with the REAL ground-Take callback.
	 *
	 * Petalcurse 2026-09-11: dropped 4 junk items, took 2 back, and the telemetry emitted zero
	 * pickups while labelling one genuine self-pickup `despawn_timer` at 23 ticks ("nobody took
	 * it"). Every prior pickup test mocked getItemId() to a real id, so the suite was green while
	 * the live path failed. This drives the shape the client actually sends.
	 */
	@Test
	public void aRealGroundTakeMarksItsOwnPileAndTheRemovalSaysSelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(DIAMOND, 1);
		dropItem(plugin, DIAMOND, 1, 100, 400);
		assertEquals("the pile must be tracked", 1, plugin.groundDropCount());

		tick(plugin, 123);
		plugin.onMenuOptionClicked(groundTake("Uncut diamond", DIAMOND));
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		assertEquals("the self-pickup must emit exactly once", 1, eventsOfType(plugin, "pickup").size());

		plugin.onItemDespawned(despawn(DIAMOND, 1, TILE_X, TILE_Y, 0));
		Map<String, Object> ev = onlyEvent(plugin, "ground_removed");
		assertEquals("self_pickup", ev.get("cause"));
		assertEquals("UNKNOWN", ev.get("recipient"));
	}

	/**
	 * FAIL CLOSED. A removal that satisfies the deadline comparison after a handful of ticks cannot
	 * be the ~300-tick timer, whatever the stored deadline says — that combination is what printed
	 * "nobody took it" over Sandra's real pickup at 23 ticks. Unknown is the honest answer.
	 */
	@Test
	public void animplausiblyFastTimerExpiryDegradesToUnknown() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 105);	// a deadline already reached 5 ticks later
		tick(plugin, 123);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		assertEquals("23 ticks is not a 300-tick timer expiring",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** A genuine full-length timer expiry is untouched by the floor. */
	@Test
	public void aGenuineTimerExpiryIsStillReportedAsTheTimer() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 400);
		tick(plugin, 399);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		assertEquals("despawn_timer", onlyEvent(plugin, "ground_removed").get("cause"));
	}


	/**
	 * DEFECT 2, PRESERVED — callback ORDER, reproduced from the live run of 2026-09-12 07:39:40.
	 *
	 * The client fires ItemDespawned the moment the pile leaves the ground. The inventory change
	 * that RESOLVES the pickup arrives afterwards. So emitGroundRemoval has already published a
	 * cause before markGroundDropSelfPickedUp is ever called, and a genuine self-pickup goes out as
	 * `removed_early` — i.e. as if a stranger took it.
	 *
	 * On the live client the pickup event itself is now correct (defect 1 is fixed); only the cause
	 * on the removal is wrong. This test drives the REAL order and is expected to FAIL until the
	 * ordering is repaired. It is committed failing on purpose, as the preserved evidence.
	 */
	@Test
	public void despawnArrivingBeforeTheInventoryChangeStillResolvesToSelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(DIAMOND, 1);
		dropItem(plugin, DIAMOND, 1, 100, 400);

		tick(plugin, 132);
		plugin.onMenuOptionClicked(groundTake("Uncut diamond", DIAMOND));
		// REAL ORDER: the pile despawns first...
		plugin.onItemDespawned(despawn(DIAMOND, 1, TILE_X, TILE_Y, 0));
		// ...and only then does the inventory gain land.
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(invChanged(plugin));

		assertEquals("the pickup itself is captured", 1, eventsOfType(plugin, "pickup").size());
		assertEquals("a removal we ourselves caused must never read as a stranger taking it",
			"self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}


	/** The OTHER order — inventory gain first, despawn second — must reach the same conclusion. */
	@Test
	public void inventoryGainBeforeDespawnAlsoResolvesToSelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(DIAMOND, 1);
		dropItem(plugin, DIAMOND, 1, 100, 400);
		tick(plugin, 132);
		plugin.onMenuOptionClicked(groundTake("Uncut diamond", DIAMOND));
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		plugin.onItemDespawned(despawn(DIAMOND, 1, TILE_X, TILE_Y, 0));
		assertEquals("self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** No local Take at all: the removal must NEVER be upgraded to self_pickup by the wait. */
	@Test
	public void anEarlyRemovalWithNoLocalPickupIsNeverSelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 400);
		tick(plugin, 140);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		Map<String, Object> ev = onlyEvent(plugin, "ground_removed");
		assertEquals("removed_early", ev.get("cause"));
		assertEquals("UNKNOWN", ev.get("recipient"));
	}

	/**
	 * A Take that is armed but never lands (walked away, server refused) must not invent a pickup.
	 *
	 * NARROW CANDIDATE: the cause is `unknown`, not `removed_early`. We clicked Take on this exact
	 * pile, so "somebody else took it" is a claim the client cannot support either. Only a proven
	 * recovery upgrades it, and only a pile no Take of ours ever named can read `removed_early`.
	 */
	@Test
	public void anArmedTakeThatNeverLandsFinalizesWithoutSelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 400);
		tick(plugin, 140);
		plugin.onMenuOptionClicked(groundTake("Red d'hide chaps", CHAPS));
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		assertNull("nothing may publish while the answer is still pending",
			firstEvent(plugin, "ground_removed"));

		// the inventory gain never arrives; the window closes
		for (int t = 141; t <= 140 + AccountConnectPlugin.REMOVAL_RESOLVE_MAX_TICKS + 1; t++)
		{
			tick(plugin, t);
			plugin.onGameTick(null);
		}
		Map<String, Object> ev = onlyEvent(plugin, "ground_removed");
		assertEquals("a pile we clicked Take on is never `somebody else took it`",
			"unknown", ev.get("cause"));
		assertEquals("and it is certainly not a proven pickup", "UNKNOWN", ev.get("recipient"));
		assertEquals("no pickup may be invented", 0, eventsOfType(plugin, "pickup").size());
	}

	/** A duplicate despawn for the same pile must not produce a second lifecycle event. */
	@Test
	public void aDuplicateDespawnEmitsOnlyOneFinalEvent() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(DIAMOND, 1);
		dropItem(plugin, DIAMOND, 1, 100, 400);
		tick(plugin, 132);
		plugin.onMenuOptionClicked(groundTake("Uncut diamond", DIAMOND));
		plugin.onItemDespawned(despawn(DIAMOND, 1, TILE_X, TILE_Y, 0));
		plugin.onItemDespawned(despawn(DIAMOND, 1, TILE_X, TILE_Y, 0));	// duplicate callback
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 140);
		plugin.onGameTick(null);
		assertEquals("exactly one ground_removed per lifecycle",
			1, eventsOfType(plugin, "ground_removed").size());
		assertEquals("self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * A deferred removal must not survive a logout — but it must not VANISH either.
	 *
	 * Discarding the parked entry silently made the pile's removal disappear from the record
	 * entirely, which is worse than the weaker verdict the same event received before it was ever
	 * deferred. It is published at the logout, at the tick it was actually removed, as `unknown`:
	 * observation is genuinely unreliable across a session boundary, so no stronger cause is
	 * supportable. What must never happen is the entry finalizing LATER, against the next session.
	 */
	@Test
	public void aDeferredRemovalIsPublishedAtLogoutAndNeverAfterIt() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 400);
		tick(plugin, 140);
		plugin.onMenuOptionClicked(groundTake("Red d'hide chaps", CHAPS));
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		plugin.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));

		Map<String, Object> ev = onlyEvent(plugin, "ground_removed");
		assertEquals("a session boundary cannot support any stronger cause", "unknown", ev.get("cause"));
		assertEquals("published at the tick it left the ground, not at the logout",
			Integer.valueOf(40), ev.get("ticks_on_ground"));

		tick(plugin, 200);
		plugin.onGameTick(null);
		assertEquals("and it must never finalize a SECOND time against the next session",
			1, eventsOfType(plugin, "ground_removed").size());
	}

	/**
	 * FINDING 2. A region boundary inside the resolution window must not swallow the removal.
	 * GameState.LOADING fires on every region change, and a pile that despawned a few ticks earlier
	 * was still parked. Before this the event was simply lost.
	 */
	@Test
	public void aRegionBoundaryInsideTheWindowStillPublishesTheRemoval() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 140);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		plugin.onGameStateChanged(gameState(GameState.LOADING));	// within the 6-tick window

		Map<String, Object> ev = onlyEvent(plugin, "ground_removed");
		assertEquals("a scene reload cannot tell us who took it, but the removal is real",
			"unknown", ev.get("cause"));
	}

	/**
	 * FINDING 3. A tick counter that never advances must still let a parked removal finalize.
	 * The elapsed-tick test is unreachable when the clock is frozen, so settlement counts its own
	 * attempts as the backstop.
	 */
	@Test
	public void aFrozenTickCounterStillFinalizesTheRemoval() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 140);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));

		for (int i = 0; i < 50; i++)
		{
			plugin.onGameTick(null);	// the clock never moves
		}
		assertEquals("a parked removal must never be stranded by a frozen tick counter",
			1, eventsOfType(plugin, "ground_removed").size());
	}

	/**
	 * FINDING 1. ONE arriving item cannot satisfy TWO armed Takes.
	 *
	 * Every pending is measured against the same container, so two Takes of one item share a
	 * `beforeCount` and a single arriving pot resolved both: two `pickup` events, doubled
	 * quantities, and a pile the CUSTOMER collected marked `self_pickup`. That is a delivery
	 * erased from the record, which is the worst class of error this event stream can produce.
	 */
	@Test
	public void oneArrivingItemResolvesOnlyOneOfTwoArmedTakes() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 105, 405, TILE_X + 1, TILE_Y);

		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + 1, TILE_Y));	// second click
		setInventory(plugin, POT, 1);							// only ONE pot arrives
		plugin.onItemContainerChanged(invChanged(plugin));

		List<Map<String, Object>> pickups = eventsOfType(plugin, "pickup");
		assertEquals("one item arrived, so exactly one pickup is provable", 1, pickups.size());
		assertEquals("and its quantity is the one item, not the container total",
			1L, ((Number) pickups.get(0).get("qty")).longValue());
	}

	/**
	 * A STACKABLE that merged on the ground must not be under-reported.
	 *
	 * Dropping coins onto a stack that is ALREADY on the tile merges them in the client, so the
	 * pile we record describes only the coins we added while the real ground stack is larger.
	 * Capping the pickup at our own recorded pile reported a genuine 150-coin recovery as 100.
	 */
	@Test
	public void aMergedGroundStackIsCreditedAtItsRealSize() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 100);
		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin);						// all 100 coins left the inventory
		// The drop MERGES into a stack of 50 already lying there: the tile now holds 150.
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 150, 400), tile(TILE_X, TILE_Y, 0), 50, 150));

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		setInventory(plugin, COINS, 150);				// the whole stack comes back
		plugin.onItemContainerChanged(invChanged(plugin));

		List<Map<String, Object>> pickups = eventsOfType(plugin, "pickup");
		assertEquals(1, pickups.size());
		assertEquals("the whole merged stack came back, not just the coins we ourselves dropped",
			150L, ((Number) pickups.get(0).get("qty")).longValue());
	}

	/**
	 * TWO drops of a stackable onto ONE tile record two piles describing one growing stack.
	 *
	 * The tile ends up holding 250 coins, but we tracked a pile of 100 (the first drop) and a pile
	 * of 250 (the tile after the second). Sizing the cap by the FIRST pile found reports a genuine
	 * 250-coin recovery as 100. The cap must be the largest stack the Take could have recovered.
	 */
	@Test
	public void aStackGrownByTwoDropsIsCreditedAtItsFullSize() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 250);
		standAt(plugin, TILE_X, TILE_Y);

		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin, COINS, 150);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(COINS, 100, 400)));	// tile now holds 100

		tick(plugin, 105);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin);
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 250, 405), tile(TILE_X, TILE_Y, 0), 100, 250));	// tile now holds 250
		assertEquals("both drops tracked a pile", 2, plugin.groundDropCount());

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		setInventory(plugin, COINS, 250);
		plugin.onItemContainerChanged(invChanged(plugin));

		List<Map<String, Object>> pickups = eventsOfType(plugin, "pickup");
		assertEquals(1, pickups.size());
		assertEquals("the cap must be the largest pile on the tile, not the first one recorded",
			250L, ((Number) pickups.get(0).get("qty")).longValue());
	}

	/**
	 * REVIEW FINDING 1. A burst of pickups in ONE tick must not age an unrelated parked pile.
	 *
	 * Settlement re-enters once per resolved pickup. Counting every call as an attempt let a burst
	 * of pickups spend the whole budget of every other parked pile, finalizing a genuine self-pickup
	 * as `removed_early` — which a consumer reads as somebody else took it.
	 *
	 * Every Take here names its OWN tile and each is answered by its own arrival, so attribution is
	 * unambiguous and the only thing under test is the settle budget.
	 */
	@Test
	public void aBurstOfPickupsInOneTickDoesNotAgeAnotherParkedPile() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 10);
		for (int i = 0; i < 10; i++)
		{
			dropItemAt(plugin, POT, 1, 100 + i, 400 + i, TILE_X + i, TILE_Y, POT, 9 - i);
		}
		tick(plugin, 150);
		for (int i = 0; i < 10; i++)
		{
			standAt(plugin, TILE_X + i, TILE_Y);
			plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + i, TILE_Y));
			plugin.onItemDespawned(despawn(POT, 1, TILE_X + i, TILE_Y, 0));
			// each Take is answered before the next is armed, so no two are ever armed together
			setInventory(plugin, POT, i + 1);
			plugin.onItemContainerChanged(invChanged(plugin));
		}
		plugin.onGameTick(null);

		assertEquals("all ten pots came back", 10, eventsOfType(plugin, "pickup").size());
		List<Map<String, Object>> removals = eventsOfType(plugin, "ground_removed");
		assertEquals("one removal per pile", 10, removals.size());
		for (Map<String, Object> r : removals)
		{
			assertEquals("a pile WE took must never read as an early removal",
				"self_pickup", r.get("cause"));
		}
	}

	/**
	 * THE SETTLE BUDGET COUNTS TICKS, NOT CALLS.
	 *
	 * Settlement re-enters once per resolved pickup that matches one of our own piles. When it
	 * counted every call, a run of such pickups spent an unrelated parked pile's whole budget inside
	 * a single tick and finalized it early — as `removed_early`, which a consumer reads as somebody
	 * else took it. Here the parked pot has an armed Take that has not landed yet, and eight of our
	 * own chaps piles are recovered around it, each one re-entering settlement.
	 */
	@Test
	public void aRunOfPickupsDoesNotSpendAParkedPilesBudget() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1, CHAPS, 8);
		// the pile under test: taken, despawned, still waiting for its inventory gain
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, CHAPS, 8);
		for (int i = 0; i < 8; i++)
		{
			dropItemAt(plugin, CHAPS, 1, 110 + i, 410 + i, TILE_X + 10 + i, TILE_Y, CHAPS, 7 - i);
		}
		tick(plugin, 150);
		standAt(plugin, TILE_X, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));

		// eight of OUR OWN chaps piles come back, one at a time, all on the same tick
		for (int i = 0; i < 8; i++)
		{
			standAt(plugin, TILE_X + 10 + i, TILE_Y);
			plugin.onMenuOptionClicked(groundTakeAt("Red d'hide chaps", CHAPS, TILE_X + 10 + i, TILE_Y));
			setInventory(plugin, CHAPS, i + 1);
			plugin.onItemContainerChanged(invChanged(plugin));
		}
		assertEquals("every chaps pile was recovered", 8, eventsOfType(plugin, "pickup").size());
		assertEquals("the parked pot must still be waiting, not aged out by other piles",
			0, eventsOfType(plugin, "ground_removed").size());

		setInventory(plugin, CHAPS, 8, POT, 1);		// the pot finally lands
		plugin.onItemContainerChanged(invChanged(plugin));
		assertEquals("and it resolves as what it was: our own pickup",
			"self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * REVIEW FINDING 2. One arrival must not be credited again on a LATER container change.
	 *
	 * An unresolved Take stays armed for five ticks. Re-measuring the same raised count on the next
	 * container change credited the same pot twice and published the pile the CUSTOMER collected
	 * as `self_pickup` — a delivery erased from the record.
	 */
	@Test
	public void anArrivalIsNotCreditedAgainOnALaterContainerChange() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y);

		tick(plugin, 150);
		standAt(plugin, TILE_X, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		standAt(plugin, TILE_X + 1, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + 1, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 1, TILE_Y, 0));	// the customer collects
		tick(plugin, 151);
		setInventory(plugin, POT, 1);					// OUR pot comes back
		plugin.onItemContainerChanged(invChanged(plugin));
		assertEquals("one pot, one pickup", 1, eventsOfType(plugin, "pickup").size());

		tick(plugin, 153);
		setInventory(plugin, POT, 1, COINS, 40);			// an unrelated coin drop
		plugin.onItemContainerChanged(invChanged(plugin));

		assertEquals("the same pot must not be claimed twice",
			1, eventsOfType(plugin, "pickup").size());

		tick(plugin, 160);				// let the parked removal finish waiting
		plugin.onGameTick(null);
		// Both Takes named different tiles, so which pile the arrival came from is unknowable and
		// the cause is withheld. What matters here is that the pot is not claimed twice, and that
		// the customer's pile is never published as our own pickup.
		assertEquals("and the customer's pile is not ours",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * REVIEW FINDING 4. A stack that grew WITHOUT a drop of ours must still credit in full.
	 * We drop 100 coins, a stranger adds 50, we take all 150 back. The tracked pile still says 100.
	 */
	@Test
	public void aStackAStrangerAddedToIsStillCreditedInFull() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 100);
		dropItemAt(plugin, COINS, 100, 100, 400, TILE_X, TILE_Y);
		// a stranger adds 50: no drop of ours is armed, so the pile is never re-tracked
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 150, 400), tile(TILE_X, TILE_Y, 0), 100, 150));

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		setInventory(plugin, COINS, 150);
		plugin.onItemContainerChanged(invChanged(plugin));

		List<Map<String, Object>> pickups = eventsOfType(plugin, "pickup");
		assertEquals(1, pickups.size());
		assertEquals("a stale pile size must never shrink a measured recovery",
			150L, ((Number) pickups.get(0).get("qty")).longValue());
	}

	/**
	 * The cap, when it DOES apply, must still be the largest stack on the tile.
	 *
	 * Two Takes of coins are armed, so the cap is live. The tile holds one real stack of 250 that we
	 * grew with two drops, tracked as a pile of 100 and a pile of 250. Sizing the cap by the first
	 * pile found reports a genuine 250-coin recovery as 100.
	 */
	@Test
	public void theCapUsesTheLargestStackEvenWithASiblingTakeArmed() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 250);
		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin, COINS, 150);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(COINS, 100, 400)));
		tick(plugin, 105);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin);
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 250, 405), tile(TILE_X, TILE_Y, 0), 100, 250));

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		// a SECOND armed Take of the same item keeps the cap switched on
		standAt(plugin, TILE_X + 4, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X + 4, TILE_Y));
		setInventory(plugin, COINS, 250);
		plugin.onItemContainerChanged(invChanged(plugin));

		List<Map<String, Object>> pickups = eventsOfType(plugin, "pickup");
		assertEquals(1, pickups.size());
		assertEquals("the cap must be the largest pile on the tile, not the first recorded",
			250L, ((Number) pickups.get(0).get("qty")).longValue());
	}

	/**
	 * REVIEW FINDING (round 4). Rapid Takes on DIFFERENT tiles must not fabricate an attribution.
	 *
	 * OSRS runs the LAST click of a burst while the queue hands the arrival to the FIRST pending, so
	 * the Take we credit is routinely not the one that succeeded. The pile we really took was
	 * published as `removed_early` while an untouched pile carried the `self_pickup` — and when the
	 * customer later collected THAT pile, their collection was recorded as ours. Nothing in the
	 * client says which click ran, so nothing is claimed.
	 */
	@Test
	public void rapidTakesOnDifferentTilesNeverFabricateASelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 3);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 2);
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 102, 402, TILE_X + 2, TILE_Y);

		tick(plugin, 150);
		for (int i = 0; i < 3; i++)
		{
			standAt(plugin, TILE_X + i, TILE_Y);
			plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + i, TILE_Y));
		}
		// the client ran the LAST click: the third pile is the one that left
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 2, TILE_Y, 0));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 160);
		plugin.onGameTick(null);

		assertEquals("an item genuinely arrived, so the pickup is still reported",
			1, eventsOfType(plugin, "pickup").size());
		Map<String, Object> taken = onlyEvent(plugin, "ground_removed");
		assertEquals("a pile we may well have taken is UNKNOWN, never `nobody took it`",
			"unknown", taken.get("cause"));
	}

	/** The customer then collects one of those piles: it must never read as our own pickup. */
	@Test
	public void aCustomerCollectionAfterAnAmbiguousBurstIsNeverOurs() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 3);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 2);
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 102, 402, TILE_X + 2, TILE_Y);

		tick(plugin, 150);
		for (int i = 0; i < 3; i++)
		{
			standAt(plugin, TILE_X + i, TILE_Y);
			plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + i, TILE_Y));
		}
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 2, TILE_Y, 0));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));

		tick(plugin, 200);					// the customer collects the first pile
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(plugin, 210);
		plugin.onGameTick(null);

		// NARROW CANDIDATE: BOTH piles read `unknown`, and that is the whole trade. A Take named
		// each of them, so neither can be called `removed_early` (somebody else took it) and neither
		// was recovered provably enough to be called ours. The 54d5a0a delta let the second pile
		// reclaim `removed_early` once the Take aged out; the machinery that decided when a Take
		// stopped counting is exactly what produced defects 20 through 28, so the narrow candidate
		// does not have it and pays for that with a weaker label here.
		List<Map<String, Object>> causes = eventsOfType(plugin, "ground_removed");
		assertEquals(2, causes.size());
		for (Map<String, Object> r : causes)
		{
			assertEquals("a pile a Take of ours named can only be unknown: " + causes,
				"unknown", r.get("cause"));
		}
	}

	/**
	 * NARROW CANDIDATE: several clicks on the SAME pile no longer attribute.
	 *
	 * The delta reasoned that two clicks on one tile agree about which pile an arrival came from, so
	 * it kept `self_pickup` here. That same-tile / different-tile distinction is what defects 25 and
	 * 26 were, in both directions. The narrow rule is blunt: more than one armed Take of an item
	 * means the client cannot say which click the server ran, so nothing is claimed and the pile
	 * reports `unknown`. The `pickup` row is still emitted, because an item genuinely arrived.
	 *
	 * This is a deliberately GIVEN-UP label, recorded here so nobody restores it by accident.
	 */
	@Test
	public void repeatedClicksOnOnePileNoLongerAttributeButNeverMislabel() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 165);
		plugin.onGameTick(null);

		assertEquals("the item genuinely arrived", 1, eventsOfType(plugin, "pickup").size());
		assertEquals("two clicks cannot prove which one the server ran",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * A large UNTRACKED stack must never be under-reported by the sibling cap.
	 *
	 * The cap is sized by the pile the Take was aimed at, and a pile we never dropped has no
	 * recorded size. Falling back to one unit published a genuine 5,000-coin recovery as qty=1 — a
	 * 5000x under-report. Uncapped, the whole gain goes to one pending and the siblings find nothing
	 * left, so two recoveries merge into ONE row carrying the correct TOTAL. A merged row is a
	 * weaker record. A wrong quantity is a false one, and value is what this stream exists to prove.
	 */
	@Test
	public void aLargeUntrackedStackIsNeverUnderReportedByTheCap() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 150);
		standAt(plugin, TILE_X, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		standAt(plugin, TILE_X + 5, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X + 5, TILE_Y));
		setInventory(plugin, COINS, 5000);
		plugin.onItemContainerChanged(invChanged(plugin));

		long total = 0;
		for (Map<String, Object> e : eventsOfType(plugin, "pickup"))
		{
			total += ((Number) e.get("qty")).longValue();
		}
		assertEquals("the coins recovered must be reported in full, however the rows split",
			5000L, total);
	}

	/**
	 * REVIEW FINDING (round 5). Two Takes with NO tile must not read as agreeing.
	 *
	 * A missing tile is unknown identity, not a coordinate. Both no-tile Takes report (-1,-1,-1), so
	 * the ambiguity test found no disagreement and attribution proceeded — and the pile the CUSTOMER
	 * collected was published as our own pickup.
	 */
	@Test
	public void twoTakesWithNoTileNeverAttributeACustomerCollection() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y);

		tick(plugin, 150);
		plugin.onMenuOptionClicked(takeWithNoTile("Pot", POT));
		plugin.onMenuOptionClicked(takeWithNoTile("Pot", POT));
		// the CUSTOMER collects both of our piles
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 1, TILE_Y, 0));
		setInventory(plugin, POT, 1);				// a pot arrives from somewhere else
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 160);
		plugin.onGameTick(null);

		for (Map<String, Object> r : eventsOfType(plugin, "ground_removed"))
		{
			assertNotEquals("a collection by somebody else is never our pickup",
				"self_pickup", r.get("cause"));
		}
	}

	/**
	 * REVIEW FINDING (round 6). ONE no-tile Take against TWO own piles must not swap the answers.
	 *
	 * A Take the client gave no tile for names no pile. It used to match the first pile recorded, so
	 * with two piles the answers came out swapped: the pile we took read `removed_early` while the
	 * pile the CUSTOMER collected read `self_pickup`. That is the worst pair this stream can print.
	 */
	@Test
	public void aLoneNoTileTakeAgainstTwoPilesNeverSwapsTheCauses() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);		// pile A, recorded first
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y);		// pile B

		tick(plugin, 150);
		standAt(plugin, TILE_X + 1, TILE_Y);
		plugin.onMenuOptionClicked(takeWithNoTile("Pot", POT));			// we take B
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 1, TILE_Y, 0));		// B leaves
		tick(plugin, 300);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));		// customer collects A
		tick(plugin, 310);
		plugin.onGameTick(null);

		for (Map<String, Object> r : eventsOfType(plugin, "ground_removed"))
		{
			assertNotEquals("with two candidates and no tile, no pile may claim the pickup",
				"self_pickup", r.get("cause"));
		}
	}

	/** With exactly ONE pile of that item there is only one thing a no-tile Take can be. */
	@Test
	public void aLoneNoTileTakeAgainstOnePileStillAttributes() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(takeWithNoTile("Pot", POT));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));

		assertEquals("one candidate is not ambiguous",
			"self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * A no-tile Take BESIDE a tiled one is ambiguous even with a single tracked pile.
	 *
	 * The candidate count is 1, so the count rule alone would attribute. But the no-tile Take may
	 * have been aimed at untracked loot while the tiled one named our pile, and only one item
	 * arrived — so which of the two landed is unknown, and the pile must not claim the pickup.
	 */
	@Test
	public void aNoTileTakeBesideATiledOneIsAmbiguousEvenWithOnePile() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);

		tick(plugin, 150);
		plugin.onMenuOptionClicked(takeWithNoTile("Pot", POT));			// untracked loot, maybe
		standAt(plugin, TILE_X, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));	// our own pile
		setInventory(plugin, POT, 1);						// only ONE pot arrives
		plugin.onItemContainerChanged(invChanged(plugin));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(plugin, 160);
		plugin.onGameTick(null);

		assertEquals("two competing takes and one arrival cannot prove which landed",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * REVIEW FINDING (round 7). TWO no-tile Takes against ONE pile must not attribute.
	 *
	 * Both report (-1,-1,-1), so the coordinate comparison finds no disagreement, and with a single
	 * tracked pile the candidate count does not object either. At most one of the two Takes landed
	 * and nothing says which — the other may have named untracked loot while the customer collected
	 * ours. This is the only combination the explicit no-tile branch covers.
	 */
	@Test
	public void twoNoTileTakesAgainstOnePileNeverAttribute() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);

		tick(plugin, 150);
		plugin.onMenuOptionClicked(takeWithNoTile("Pot", POT));
		plugin.onMenuOptionClicked(takeWithNoTile("Pot", POT));
		setInventory(plugin, POT, 1);					// ONE pot arrives
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 152);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		// The pile parks while the second take is still armed; let its resolution window close.
		// The removal is still CLASSIFIED at tick 152, which is inside the take window, so the
		// withheld verdict stands.
		tick(plugin, 159);
		plugin.onGameTick(null);

		assertEquals("two untiled takes and one arrival cannot prove which landed",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * NARROW CANDIDATE: a no-tile Take permanently costs every same-item pile its timer evidence.
	 *
	 * The delta let a withholding LAPSE once the Take could no longer explain the pile, which
	 * restored `despawn_timer` here. Rounds 20 through 23 were that lapse rule and its own
	 * regressions, so the narrow candidate does not have it: `takeArmed` never clears. The cost is
	 * exactly this case, a no-tile Take on a busy tile weakening piles that really did time out.
	 *
	 * A no-tile Take is the RARE shape - the live capture carries scene coordinates - so this trade
	 * is cheap in practice. It is recorded as a give-up, not as correct behaviour.
	 */
	@Test
	public void aPileThatOutlivesANoTileTakeLosesItsTimerEvidence() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 3);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 2);
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 102, 402, TILE_X + 2, TILE_Y);

		tick(plugin, 150);
		plugin.onMenuOptionClicked(takeWithNoTile("Pot", POT));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));

		tick(plugin, 401);					// two piles sit out their FULL timer
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 1, TILE_Y, 0));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 2, TILE_Y, 0));
		plugin.onGameTick(null);

		for (Map<String, Object> r : eventsOfType(plugin, "ground_removed"))
		{
			assertEquals("a no-tile Take could have named this pile, so we refuse to call it",
				"unknown", r.get("cause"));
		}
	}

	/**
	 * REVIEW FINDING (round 8). Withholding is measured from the tick the ambiguity was DECIDED.
	 *
	 * A Take stays armed until an inventory change prunes it, so the server routinely runs one
	 * several ticks after the click — walking to the pile is enough. Measuring the withholding from
	 * the CLICK declared it expired while the Take was still live, and the pile the staff member
	 * really recovered was published as `removed_early` in the same run that emitted its `pickup`.
	 */
	@Test
	public void aSlowlyServedTakeIsStillWithheldWhateverTheWalkCost() throws Exception
	{
		for (int walk : new int[]{1, 6, 7, 9, 20})
		{
			AccountConnectPlugin plugin = newPlugin(POT, 2);
			dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
			dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y);

			tick(plugin, 150);				// two competing clicks
			standAt(plugin, TILE_X, TILE_Y);
			plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
			standAt(plugin, TILE_X + 1, TILE_Y);
			plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + 1, TILE_Y));

			tick(plugin, 150 + walk);			// the server runs one after the walk
			plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
			setInventory(plugin, POT, 1);
			plugin.onItemContainerChanged(invChanged(plugin));
			tick(plugin, 150 + walk + 8);
			plugin.onGameTick(null);

			assertEquals("walk of " + walk + " ticks: a pile we may have taken is never `not ours`",
				"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
		}
	}

	/**
	 * REVIEW FINDING (round 9). A STALE Take must not claim a pile it never recovered.
	 *
	 * A landed inventory gain is credited to a pending of ANY age: the age check only runs when
	 * nothing landed, so a Take clicked but never served stays armed indefinitely. Inside the
	 * parking window that retroactively flipped an already-removed pile — a customer collected
	 * 5,000 coins, a 1,200-coin store sale fifty ticks later, and the collection was published as
	 * our own pickup.
	 */
	@Test
	public void aStaleTakeNeverClaimsAPileACustomerCollected() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 5000);
		dropItemAt(plugin, COINS, 5000, 100, 400, TILE_X, TILE_Y);

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));	// never served
		tick(plugin, 200);
		plugin.onItemDespawned(despawn(COINS, 5000, TILE_X, TILE_Y, 0));		// customer collects
		tick(plugin, 203);
		setInventory(plugin, COINS, 5000);		// the SAME amount: only AGE disqualifies this Take
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 210);
		plugin.onGameTick(null);

		assertEquals("a Take 50 ticks old is not evidence about this pile",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** A Take served inside its own window still claims its pile, exactly as before. */
	@Test
	public void aFreshTakeStillClaimsItsPile() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 5000);
		dropItemAt(plugin, COINS, 5000, 100, 400, TILE_X, TILE_Y);

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		tick(plugin, 153);						// inside the pending window
		plugin.onItemDespawned(despawn(COINS, 5000, TILE_X, TILE_Y, 0));
		setInventory(plugin, COINS, 5000);
		plugin.onItemContainerChanged(invChanged(plugin));

		assertEquals("self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * REVIEW FINDING (round 10). A withholding backed by a REAL gain must never lapse.
	 *
	 * The expiry exists so an untouched pile can reclaim its own timer evidence. A pile we
	 * demonstrably gained an item from has no such claim: letting its withholding lapse published
	 * `removed_early` — somebody else took it — over a recovery the player actually made.
	 */
	@Test
	public void aSlowlyServedTakeNeverDegradesIntoAnEarlyRemoval() throws Exception
	{
		for (int despawnLag : new int[]{0, 3, 6, 9, 20})
		{
			AccountConnectPlugin plugin = newPlugin(POT, 1);
			dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);

			tick(plugin, 150);
			plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
			tick(plugin, 157);					// served 7 ticks later: a walk
			setInventory(plugin, POT, 1);
			plugin.onItemContainerChanged(invChanged(plugin));
			tick(plugin, 157 + despawnLag);
			plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
			tick(plugin, 157 + despawnLag + 10);
			plugin.onGameTick(null);

			assertNotEquals("despawn lag " + despawnLag + ": a pile we recovered is never `theirs`",
				"removed_early", onlyEvent(plugin, "ground_removed").get("cause"));
		}
	}

	/**
	 * A backwards tick counter must not lapse an AMBIGUOUS withholding into a definite cause.
	 *
	 * The expiry subtracts two ticks. When the counter jumps backwards the difference goes negative,
	 * which is not evidence that anything aged out — the same case settlePendingRemovals guards.
	 */
	@Test
	public void aBackwardsTickCounterDoesNotLapseAWithheldPile() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y);

		tick(plugin, 150);					// two competing clicks: ambiguous
		standAt(plugin, TILE_X, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		standAt(plugin, TILE_X + 1, TILE_Y);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + 1, TILE_Y));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));

		tick(plugin, 20);					// the counter jumps BACKWARDS
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(plugin, 30);
		plugin.onGameTick(null);

		assertEquals("a clock going backwards is not evidence about who took this pile",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * REVIEW FINDING (round 11). When BOTH competing Takes land, neither pile may read as theirs.
	 *
	 * The plugin emitted both `pickup` rows, proving the staff recovered both pots, and then
	 * published `removed_early` over one of them in the same run — a positive claim that somebody
	 * else took a pile we hold proof of taking. One credited gain accounts for one pile, so two
	 * gains account for both.
	 */
	@Test
	public void twoCompetingTakesThatBothLandNeverReadAsAnEarlyRemoval() throws Exception
	{
		for (int lag : new int[]{0, 4, 5, 6, 12, 20})
		{
			AccountConnectPlugin plugin = newPlugin(POT, 2);
			dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
			dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y);

			tick(plugin, 150);
			standAt(plugin, TILE_X, TILE_Y);
			plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
			standAt(plugin, TILE_X + 1, TILE_Y);
			plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + 1, TILE_Y));
			setInventory(plugin, POT, 2);				// BOTH pots arrive
			plugin.onItemContainerChanged(invChanged(plugin));

			tick(plugin, 150 + lag);
			plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
			plugin.onItemDespawned(despawn(POT, 1, TILE_X + 1, TILE_Y, 0));
			tick(plugin, 150 + lag + 10);
			plugin.onGameTick(null);

			// NARROW CANDIDATE: no per-pile quantity cap exists, so the FIRST pending absorbs the
			// whole 2-pot arrival and the second finds nothing left. Two recoveries merge into ONE
			// row carrying the correct TOTAL. A merged row is a weaker record; a wrong quantity
			// would be a false one, and the cap machinery that split them is defects 24 to 28.
			long total = 0;
			for (Map<String, Object> e : eventsOfType(plugin, "pickup"))
			{
				total += ((Number) e.get("qty")).longValue();
			}
			assertEquals("lag " + lag + ": both pots must be reported somewhere", 2L, total);
			for (Map<String, Object> r : eventsOfType(plugin, "ground_removed"))
			{
				assertEquals("lag " + lag + ": a Take named both piles, so neither is attributable",
					"unknown", r.get("cause"));
			}
		}
	}

	/**
	 * REVIEW FINDING (round 11). Two clicks on ONE tile are not competitors.
	 *
	 * They name a single pile, so there is nothing to split and the per-pile cap only truncates.
	 * Measured before this: a double-click recovering a 5,000-coin stack published 2,000, because
	 * each pending was capped at the 1,000 coins we had dropped there ourselves.
	 */
	@Test
	public void adoubleClickOnOneTileReportsTheWholeStack() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 1000);
		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(COINS, 1000, 400)));
		// somebody else adds 4,000 to the tile; no drop of ours is armed, so we never re-track it
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 5000, 400), tile(TILE_X, TILE_Y, 0), 1000, 5000));

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));	// double click
		setInventory(plugin, COINS, 5000);
		plugin.onItemContainerChanged(invChanged(plugin));

		long total = 0;
		for (Map<String, Object> e : eventsOfType(plugin, "pickup"))
		{
			total += ((Number) e.get("qty")).longValue();
		}
		assertEquals("the whole recovered stack must be reported", 5000L, total);
	}

	/**
	 * NARROW CANDIDATE: two piles on ONE tile, both recovered, both read `unknown`.
	 *
	 * Two piles on a tile are indistinguishable to the client, so nothing is claimed. Both arrivals
	 * merge into one `pickup` row carrying the correct total of 2, because there is no per-pile cap
	 * to split them. Neither removal reads `removed_early`, which is the property that matters: no
	 * stranger is ever named for a pile we hold proof of recovering.
	 */
	@Test
	public void twoPilesOnOneTileBothRecoveredAreNeverMislabelled() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(POT, 1, 401)));
		tick(plugin, 101);
		plugin.onMenuOptionClicked(menu("Drop", "", POT));
		setInventory(plugin);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(POT, 1, 401)));

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		setInventory(plugin, POT, 2);					// BOTH pots arrive
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 165);
		plugin.onGameTick(null);

		List<Map<String, Object>> pickups = eventsOfType(plugin, "pickup");
		long total = 0;
		for (Map<String, Object> e : pickups)
		{
			total += ((Number) e.get("qty")).longValue();
		}
		assertEquals("both pots are reported", 2L, total);
		for (Map<String, Object> r : eventsOfType(plugin, "ground_removed"))
		{
			assertEquals("two piles on one tile are indistinguishable",
				"unknown", r.get("cause"));
		}
	}

	/**
	 * REVIEW FINDING (round 12). A pile of N can only be recovered as exactly N.
	 *
	 * A 5,000-coin delivery pile the CUSTOMER collected was published as `self_pickup` because an
	 * unrelated 1,200-coin store sale landed three ticks after our Take, and nothing compared the
	 * amounts. The pile size was already known; it simply was not consulted.
	 */
	@Test
	public void aGainOfTheWrongSizeNeverClaimsAPile() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 5000);
		dropItemAt(plugin, COINS, 5000, 100, 400, TILE_X, TILE_Y);

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		tick(plugin, 151);
		plugin.onItemDespawned(despawn(COINS, 5000, TILE_X, TILE_Y, 0));	// customer collects
		tick(plugin, 153);
		setInventory(plugin, COINS, 1200);					// unrelated store sale
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 165);
		plugin.onGameTick(null);

		assertNotEquals("1,200 coins cannot be a 5,000-coin pile",
			"self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * REVIEW FINDING (round 13). A grown stack must be reported in full, not truncated.
	 *
	 * Dropping onto a stack already on the tile merges it in the client while we record a second
	 * pile holding the whole grown total, so summing our piles counts the same coins several times.
	 * That inflated total switched the cap on, and the tile-scoped cap then truncated a real
	 * 2,700-coin recovery to 2,400.
	 */
	@Test
	public void aGrownStackOnOneTileIsRecoveredInFull() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 1200);
		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin, COINS, 200);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(COINS, 1000, 400)));
		tick(plugin, 101);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin);
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 1200, 401), tile(TILE_X, TILE_Y, 0), 1000, 1200));
		// the customer adds 1,500: the tile now holds 2,700 and our record is already stale
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 2700, 401), tile(TILE_X, TILE_Y, 0), 1200, 2700));

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		setInventory(plugin, COINS, 2700);
		plugin.onItemContainerChanged(invChanged(plugin));

		long total = 0;
		for (Map<String, Object> e : eventsOfType(plugin, "pickup"))
		{
			total += ((Number) e.get("qty")).longValue();
		}
		assertEquals("every coin recovered must be reported", 2700L, total);
	}

	/**
	 * REVIEW FINDING (round 13). A trimmed quantity must not be able to satisfy the size check.
	 *
	 * The cap can force the credited amount down to exactly the pile size, and comparing that
	 * against the same pile size always agrees — so the check that exists to refuse a claim could
	 * not refuse a value the cap itself had manufactured.
	 */
	@Test
	public void aCappedQuantityCannotSatisfyTheSizeCheck() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 1200);
		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin, COINS, 200);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(COINS, 1000, 400)));
		tick(plugin, 101);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin);
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 1200, 401), tile(TILE_X, TILE_Y, 0), 1000, 1200));

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		tick(plugin, 151);
		plugin.onItemDespawned(despawn(COINS, 1200, TILE_X, TILE_Y, 0));	// CUSTOMER collects
		tick(plugin, 153);
		setInventory(plugin, COINS, 1500);					// unrelated sale
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 165);
		plugin.onGameTick(null);

		for (Map<String, Object> r : eventsOfType(plugin, "ground_removed"))
		{
			assertNotEquals("a 1,500-coin sale is not a 1,200-coin pile",
				"self_pickup", r.get("cause"));
		}
	}

	/**
	 * NARROW CANDIDATE: a stack that grew WITHOUT a drop of ours loses its `self_pickup` label.
	 *
	 * Our record says 1,000 and the tile holds 1,700, so the measured gain does not equal the pile
	 * and the exact-size rule refuses to claim it. The delta had a stale-record ESCAPE that accepted
	 * any gain above the tracked ceiling, and W4 finding F1 is that escape: with a single click on a
	 * 1,200-coin pile it accepted an unrelated 1,500-coin store sale and published a customer's
	 * collection as ours. The narrow candidate has no escape, and pays for it here.
	 *
	 * The QUANTITY is untouched: the full 1,700 is still reported on the `pickup` row.
	 */
	@Test
	public void aStaleRecordCostsTheLabelButNeverTheQuantity() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 1000);
		dropItemAt(plugin, COINS, 1000, 100, 400, TILE_X, TILE_Y);
		// a stranger adds 700: our record still says 1,000, the tile holds 1,700
		plugin.onItemQuantityChanged(new net.runelite.api.events.ItemQuantityChanged(
			tileItem(COINS, 1700, 400), tile(TILE_X, TILE_Y, 0), 1000, 1700));

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		setInventory(plugin, COINS, 1700);
		plugin.onItemContainerChanged(invChanged(plugin));
		plugin.onItemDespawned(despawn(COINS, 1700, TILE_X, TILE_Y, 0));

		assertEquals("1,700 is not the 1,000-coin pile we recorded, so nothing is claimed",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
		assertEquals("the recovered value is still reported in full",
			1700L, ((Number) eventsOfType(plugin, "pickup").get(0).get("qty")).longValue());
	}

	/**
	 * NARROW CANDIDATE: two items arrive on two armed Takes and merge into ONE row of quantity 2.
	 *
	 * The baseline-raise still stops the double count - without it each pending would report 2 and
	 * the total would be 4, which is the defect-7 shape and the worst thing this stream can do. What
	 * the narrow candidate drops is the per-pile CAP that split the arrivals into one row each. A
	 * merged row is a weaker record; a doubled quantity is a false one.
	 */
	@Test
	public void twoArrivingItemsMergeIntoOneRowCarryingTheTotal() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 105, 405, TILE_X + 1, TILE_Y);

		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + 1, TILE_Y));
		setInventory(plugin, POT, 2);
		plugin.onItemContainerChanged(invChanged(plugin));

		List<Map<String, Object>> pickups = eventsOfType(plugin, "pickup");
		long total = 0;
		for (Map<String, Object> e : pickups)
		{
			total += ((Number) e.get("qty")).longValue();
		}
		assertEquals("exactly two pots arrived and exactly two must be reported", 2L, total);
	}

	/**
	 * THE FULL DELIVERY SHAPE. Staff drops three pots, double-clicks Take and recovers ONE, and the
	 * customer collects the other two. Exactly one self_pickup; the customer's two are never ours.
	 */
	@Test
	public void aRealDeliveryWhereTheCustomerTakesTwoAndStaffRecoversOne() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 3);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 2);
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X + 1, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 102, 402, TILE_X + 2, TILE_Y);

		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));	// double click, one pile
		setInventory(plugin, POT, 1);						// one pot recovered
		plugin.onItemContainerChanged(invChanged(plugin));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));		// ours leaves
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 1, TILE_Y, 0));		// customer collects
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 2, TILE_Y, 0));
		tick(plugin, 200);
		plugin.onGameTick(null);

		assertEquals("one pot came back, so one pickup", 1, eventsOfType(plugin, "pickup").size());
		List<Map<String, Object>> removals = eventsOfType(plugin, "ground_removed");
		assertEquals("three piles, three removals", 3, removals.size());
		// NARROW CANDIDATE: the double click makes the recovery unattributable, so the staff pile
		// reads `unknown` instead of `self_pickup`. The property that matters is unchanged and is
		// asserted directly: no pile the CUSTOMER collected is ever published as ours.
		for (Map<String, Object> r : removals)
		{
			assertNotEquals("no pile may be claimed off an ambiguous burst: " + removals,
				"self_pickup", r.get("cause"));
		}
		// The two piles the Take never named keep `removed_early`, which is honest: no Take of ours
		// was aimed at their tiles, so their own timing evidence is all there is. Only the pile our
		// double click DID name is weakened to `unknown`.
		assertEquals("exactly the pile our Take named is weakened: " + removals,
			1, countCause(removals, "unknown"));
		assertEquals("the two piles no Take named keep their own evidence: " + removals,
			2, countCause(removals, "removed_early"));
	}


	/**
	 * The tick floor is a fail-closed guard: it may only WEAKEN a verdict, never strengthen one.
	 * Proves it cannot turn an ambiguous removal into a definite pickup or a definite despawn.
	 */
	@Test
	public void aDespawnFloorCannotUpgradeAnUnknown() throws Exception
	{
		// unreliable observation + a deadline already reached: the floor must not rescue it into a
		// stronger claim, and must not convert it into self_pickup or removed_early either.
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 105);
		setUnreliable(plugin, true);
		tick(plugin, 123);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		String cause = String.valueOf(onlyEvent(plugin, "ground_removed").get("cause"));
		assertEquals("unknown", cause);
	}

	/** And the floor never invents a pickup where no Take existed. */
	@Test
	public void theFloorNeverProducesSelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		dropItem(plugin, CHAPS, 1, 100, 105);
		tick(plugin, 110);
		plugin.onItemDespawned(despawn(CHAPS, 1, TILE_X, TILE_Y, 0));
		assertEquals("unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	private static int countCause(List<Map<String, Object>> rows, String cause)
	{
		int n = 0;
		for (Map<String, Object> r : rows)
		{
			if (cause.equals(r.get("cause")))
			{
				n++;
			}
		}
		return n;
	}

	// ---------------------------------------------------------------- harness

	/**
	 * Click Drop, then let the ground spawn confirm it. `remaining` is the inventory AFTER the drop, so a
	 * multi-item sequence keeps the not-yet-dropped items present — which is what the real client shows and
	 * what the drop's own before/after count depends on.
	 */
	// ---- REVIEW FINDINGS 1, 2, 4, 5: pile identity and removal-tick regressions ----

	/**
	 * TWO OWN PILES OF THE SAME ITEM, ONE TAKEN. The shape of every drop trade.
	 *
	 * Item id alone is not an identity. Before the tile check, the still-on-the-ground sibling won
	 * the match, so the pile we took reported `removed_early` and the untouched pile later emitted
	 * a FABRICATED `self_pickup` when its own timer expired.
	 */
	@Test
	public void twoOwnPilesOfOneItemAndOnlyTheTakenPileReadsSelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 105, 405, TILE_X + 1, TILE_Y);
		assertEquals("both piles tracked", 2, plugin.groundDropCount());

		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 132);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));	// despawn-first, the hard order
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));

		assertEquals("the pile we actually took", "self_pickup",
			onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** The untouched sibling must never claim a pickup when its own timer later expires. */
	@Test
	public void theUntakenSiblingNeverFabricatesASelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 105, 405, TILE_X + 1, TILE_Y);

		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 132);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));

		tick(plugin, 404);	// the sibling reaches its own deadline, untouched
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 1, TILE_Y, 0));

		List<Map<String, Object>> all = eventsOfType(plugin, "ground_removed");
		assertEquals("one event per pile", 2, all.size());
		assertEquals("taken pile", "self_pickup", all.get(0).get("cause"));
		assertEquals("an untouched pile timing out is NEVER a pickup",
			"despawn_timer", all.get(1).get("cause"));
	}

	/**
	 * STAFF TAKES ONE, CUSTOMER TAKES THE OTHER — the real delivery shape.
	 *
	 * Both removals land in the pending map and there is exactly ONE inventory gain. Before the
	 * tile check the two causes came out SWAPPED: the customer's pile read `self_pickup` and the
	 * staff pile read `removed_early`, which is the worst possible pair to publish as proof.
	 */
	@Test
	public void staffTakesOneAndTheCustomerTakesTheOtherWithoutSwappingCauses() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 105, 405, TILE_X + 1, TILE_Y);

		standAt(plugin, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));	// staff takes ITS pile
		plugin.onItemDespawned(despawn(POT, 1, TILE_X + 1, TILE_Y, 0));		// customer takes the other
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));		// then ours leaves
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 200);
		plugin.onGameTick(new net.runelite.api.events.GameTick());

		List<Map<String, Object>> all = eventsOfType(plugin, "ground_removed");
		assertEquals("one event per pile", 2, all.size());
		Map<String, Object> customerPile = all.get(0);
		Map<String, Object> staffPile = all.get(1);
		assertEquals("the pile a customer took is not our pickup",
			"removed_early", customerPile.get("cause"));
		assertEquals("our own pile is the one we took", "self_pickup", staffPile.get("cause"));
	}

	/**
	 * A STRANGER'S PILE of the same item, taken while OUR pile times out on its own.
	 * The Take is real and the inventory rise is real, but neither belongs to our pile.
	 */
	@Test
	public void takingAStrangersPileNeverExplainsOurOwnTimedOutPile() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);

		standAt(plugin, TILE_X + 5, TILE_Y);
		tick(plugin, 399);
		// a DIFFERENT pot, five tiles away, that we never dropped
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + 5, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));	// ours reaches its deadline
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));

		assertEquals("a Take on somebody else's pile cannot explain our pile",
			"despawn_timer", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * AN UNRELATED INVENTORY RISE inside the resolution window. A Take was clicked, we walked away,
	 * a stranger took our pile early, and pots arrived from some other source (a bank, a trade, a
	 * kill) while the pending was still live.
	 */
	@Test
	public void anUnrelatedInventoryRiseDoesNotResolveTheArmedTake() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);

		standAt(plugin, TILE_X + 9, TILE_Y + 9);
		tick(plugin, 200);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X + 9, TILE_Y + 9));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));	// a stranger takes ours, early
		tick(plugin, 203);
		setInventory(plugin, POT, 1);					// pots from somewhere else
		plugin.onItemContainerChanged(invChanged(plugin));

		assertEquals("an inventory rise elsewhere is not proof we took THIS pile",
			"removed_early", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * FINDING 2. A deferred removal must be classified at the tick it was REMOVED, not the tick we
	 * got around to concluding. Settling at `currentTick` let up to REMOVAL_RESOLVE_MAX_TICKS of
	 * drift cross the despawn deadline, silently upgrading a genuine `removed_early` to
	 * `despawn_timer` — a STRONGER claim than the evidence supports.
	 */
	@Test
	public void aDeferredEarlyRemovalStaysEarly() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);

		tick(plugin, 396);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(plugin, 397);						// genuinely BEFORE the deadline
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(plugin, 403);						// the Take never lands; window closes past 400
		plugin.onGameTick(new net.runelite.api.events.GameTick());

		Map<String, Object> ev = onlyEvent(plugin, "ground_removed");
		// NARROW CANDIDATE: a Take named this pile, so the cause is `unknown` rather than
		// `removed_early`. What this test actually pins is the STORED REMOVAL TICK, and that is
		// unchanged: the removal is classified at 397, not at the 403 settlement, so the deferral
		// can never drift a verdict across the 400 deadline into a stronger `despawn_timer`.
		assertEquals("a pile our own Take named cannot be blamed on a stranger",
			"unknown", ev.get("cause"));
		assertEquals("ticks_on_ground must measure the removal, not the settlement",
			Integer.valueOf(297), ev.get("ticks_on_ground"));
	}

	/** BOUNDARY. One tick before the deadline is early; at the deadline it is the timer. */
	@Test
	public void theDeadlineBoundaryIsNotBlurredByTheDeferral() throws Exception
	{
		AccountConnectPlugin early = newPlugin(POT, 1);
		dropItemAt(early, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(early, 396);
		early.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(early, 397);
		early.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(early, 500);
		early.onGameTick(new net.runelite.api.events.GameTick());
		// NARROW CANDIDATE: a Take named the pile, so both arms read `unknown` on the CAUSE. The
		// boundary property is pinned on ticks_on_ground instead, which is what the deferral could
		// blur: 297 for the early arm, 298 for the on-time one, both measured at the removal tick.
		assertEquals("unknown", onlyEvent(early, "ground_removed").get("cause"));
		assertEquals("classified at the removal tick 397, not the 500 settlement",
			Integer.valueOf(297), onlyEvent(early, "ground_removed").get("ticks_on_ground"));

		AccountConnectPlugin onTime = newPlugin(POT, 1);
		dropItemAt(onTime, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(onTime, 396);
		onTime.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(onTime, 398);	// inside GROUND_EARLY_MARGIN_TICKS of the 400 deadline
		onTime.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(onTime, 500);
		onTime.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals("unknown", onlyEvent(onTime, "ground_removed").get("cause"));
		assertEquals("classified at the removal tick 398, not the 500 settlement",
			Integer.valueOf(298), onlyEvent(onTime, "ground_removed").get("ticks_on_ground"));
	}

	/**
	 * FINDING 5. A tick counter that moves BACKWARDS must not strand a parked removal forever.
	 * `currentTick - stored` goes negative and never reaches the window, so the event is lost.
	 * A late verdict beats a missing one.
	 */
	@Test
	public void aBackwardsTickCounterStillFinalizesTheRemoval() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 5000, 5300, TILE_X, TILE_Y);
		tick(plugin, 5001);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));

		tick(plugin, 3);	// the counter jumped backwards; no state change fired
		plugin.onGameTick(new net.runelite.api.events.GameTick());

		assertEquals("a parked removal must never be stranded by a backwards tick",
			1, eventsOfType(plugin, "ground_removed").size());
	}

	private static void dropItem(AccountConnectPlugin plugin, int item, int qty, int atTick, int despawnTick,
		int... remaining) throws Exception
	{
		tick(plugin, atTick);
		plugin.onMenuOptionClicked(menu("Drop", "", item));
		setInventory(plugin, remaining);
		spawn(plugin, item, qty, despawnTick);
	}

	private static void spawn(AccountConnectPlugin plugin, int item, int qty, int despawnTick)
		throws Exception
	{
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(item, qty, despawnTick)));
	}

	/** Move the local player, so a drop on a different tile is close enough to be confirmed. */
	private static void standAt(AccountConnectPlugin plugin, int x, int y) throws Exception
	{
		when(client(plugin).getLocalPlayer().getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
	}

	/** Drop one item on an EXPLICIT tile: walk there first, then drop and confirm the spawn. */
	private static void dropItemAt(AccountConnectPlugin plugin, int item, int qty, int atTick,
		int despawnTick, int x, int y, int... remaining) throws Exception
	{
		standAt(plugin, x, y);
		tick(plugin, atTick);
		plugin.onMenuOptionClicked(menu("Drop", "", item));
		setInventory(plugin, remaining);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(x, y, 0), tileItem(item, qty, despawnTick)));
	}

	private static net.runelite.api.events.ItemDespawned despawn(int item, int qty, int x, int y, int plane)
	{
		return new net.runelite.api.events.ItemDespawned(tile(x, y, plane), tileItem(item, qty, 0));
	}

	private static net.runelite.api.Tile tile(int x, int y, int plane)
	{
		net.runelite.api.Tile t = mock(net.runelite.api.Tile.class);
		when(t.getWorldLocation()).thenReturn(new WorldPoint(x, y, plane));
		return t;
	}

	private static net.runelite.api.TileItem tileItem(int id, int qty, int despawnTick)
	{
		net.runelite.api.TileItem it = mock(net.runelite.api.TileItem.class);
		when(it.getId()).thenReturn(id);
		when(it.getQuantity()).thenReturn(qty);
		when(it.getDespawnTime()).thenReturn(despawnTick);
		when(it.getOwnership()).thenReturn(net.runelite.api.TileItem.OWNERSHIP_SELF);
		return it;
	}

	private static net.runelite.api.events.GameStateChanged gameState(GameState st)
	{
		net.runelite.api.events.GameStateChanged ev = new net.runelite.api.events.GameStateChanged();
		ev.setGameState(st);
		return ev;
	}

	private static AccountConnectPlugin newPlugin(int... idQtyPairs) throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		ItemContainer inv0 = container(idQtyPairs);
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv0);
		when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenReturn(0);
		when(client.getTickCount()).thenReturn(100);
		// WorldPoint.fromScene() reads the world view's base coordinates. Base 0 makes the scene
		// coordinates in a Take menu entry equal to world coordinates, which keeps the tile
		// assertions readable. Without this the conversion returns null and every tile check
		// silently degrades to item-id-only matching — i.e. passes for the wrong reason.
		net.runelite.api.WorldView wv = mock(net.runelite.api.WorldView.class);
		when(wv.getBaseX()).thenReturn(0);
		when(wv.getBaseY()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(wv);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(TILE_X, TILE_Y, 0));
		inject(plugin, "client", client);
		return plugin;
	}

	private static void setInventory(AccountConnectPlugin plugin, int... idQtyPairs) throws Exception
	{
		ItemContainer next = container(idQtyPairs);
		when(client(plugin).getItemContainer(InventoryID.INVENTORY)).thenReturn(next);
	}

	private static net.runelite.api.events.ItemContainerChanged invChanged(AccountConnectPlugin plugin)
		throws Exception
	{
		ItemContainer inv = client(plugin).getItemContainer(InventoryID.INVENTORY);
		return new net.runelite.api.events.ItemContainerChanged(InventoryID.INVENTORY.getId(), inv);
	}

	private static Client client(AccountConnectPlugin plugin) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("client");
		f.setAccessible(true);
		return (Client) f.get(plugin);
	}

	private static void setUnreliable(AccountConnectPlugin plugin, boolean v) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("groundObservationUnreliable");
		f.setAccessible(true);
		f.setBoolean(plugin, v);
	}

	private static void tick(AccountConnectPlugin plugin, int t) throws Exception
	{
		when(client(plugin).getTickCount()).thenReturn(t);
	}

	private static List<Map<String, Object>> eventsOfType(AccountConnectPlugin plugin, String type)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> e : plugin.pendingEvents)
		{
			if (type.equals(e.get("type")))
			{
				out.add(e);
			}
		}
		return out;
	}

	private static Map<String, Object> firstEvent(AccountConnectPlugin plugin, String type)
	{
		List<Map<String, Object>> all = eventsOfType(plugin, type);
		return all.isEmpty() ? null : all.get(0);
	}

	private static Map<String, Object> onlyEvent(AccountConnectPlugin plugin, String type)
	{
		List<Map<String, Object>> all = eventsOfType(plugin, type);
		assertEquals("expected exactly one " + type + " event, got " + all, 1, all.size());
		return all.get(0);
	}

	/** The REAL ground-item Take entry: itemId is -1, the item id sits in the identifier. */
	private static MenuOptionClicked groundTake(String name, int itemId)
	{
		return groundTakeAt(name, itemId, TILE_X, TILE_Y);
	}

	/**
	 * The REAL ground-item Take entry, aimed at one specific tile.
	 *
	 * itemId is -1, the item id sits in the identifier, the opcode is GROUND_ITEM_THIRD_OPTION and
	 * param0/param1 carry the pile's SCENE coordinates. The test client mock reports base 0, so the
	 * scene coordinates ARE the world coordinates here.
	 */
	/** A Take the client gave us no usable tile for: the opcode is right, the scene coords are not. */
	private static MenuOptionClicked takeWithNoTile(String name, int itemId)
	{
		MenuOptionClicked m = mock(MenuOptionClicked.class);
		when(m.getMenuOption()).thenReturn("Take");
		when(m.getMenuTarget()).thenReturn("<col=ff9040>" + name);
		when(m.getItemId()).thenReturn(-1);
		when(m.getId()).thenReturn(itemId);
		when(m.getMenuAction()).thenReturn(net.runelite.api.MenuAction.GROUND_ITEM_THIRD_OPTION);
		when(m.getParam0()).thenThrow(new IllegalStateException("no scene coords"));
		return m;
	}

	private static MenuOptionClicked groundTakeAt(String name, int itemId, int x, int y)
	{
		MenuOptionClicked m = mock(MenuOptionClicked.class);
		when(m.getMenuOption()).thenReturn("Take");
		when(m.getMenuTarget()).thenReturn("<col=ff9040>" + name);
		when(m.getItemId()).thenReturn(-1);
		when(m.getId()).thenReturn(itemId);
		when(m.getMenuAction()).thenReturn(net.runelite.api.MenuAction.GROUND_ITEM_THIRD_OPTION);
		when(m.getParam0()).thenReturn(x);
		when(m.getParam1()).thenReturn(y);
		return m;
	}

	private static MenuOptionClicked menu(String option, String target, int itemId)
	{
		MenuOptionClicked m = mock(MenuOptionClicked.class);
		when(m.getMenuOption()).thenReturn(option);
		when(m.getMenuTarget()).thenReturn(target);
		when(m.getItemId()).thenReturn(itemId);
		return m;
	}

	private static ItemContainer container(int... idQtyPairs)
	{
		ItemContainer c = mock(ItemContainer.class);
		Item[] items = new Item[idQtyPairs.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idQtyPairs[i * 2], idQtyPairs[i * 2 + 1]);
		}
		when(c.getItems()).thenReturn(items);
		return c;
	}

	private static void inject(Object target, String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static AccountConnectConfig onConfig()
	{
		return (AccountConnectConfig) java.lang.reflect.Proxy.newProxyInstance(
			AccountConnectConfig.class.getClassLoader(),
			new Class<?>[]{AccountConnectConfig.class},
			(proxy, method, args) -> {
				Class<?> rt = method.getReturnType();
				if (rt == boolean.class || rt == Boolean.class) { return Boolean.TRUE; }
				if (rt == String.class) { return "0123456789abcdef0123456789abcdef"; }
				if (rt == int.class || rt == Integer.class) { return 0; }
				return null;
			});
	}
}
