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

	// ---------------------------------------------------------------- harness

	/**
	 * Click Drop, then let the ground spawn confirm it. `remaining` is the inventory AFTER the drop, so a
	 * multi-item sequence keeps the not-yet-dropped items present — which is what the real client shows and
	 * what the drop's own before/after count depends on.
	 */
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
