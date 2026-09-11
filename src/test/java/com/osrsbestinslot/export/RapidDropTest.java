package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAPID MULTI-ITEM DROP TRADE — the defect this suite exists to prove and then prevent.
 *
 * A "drop trade" delivery is N items dropped in quick succession: the staff member right-clicks Drop on one
 * inventory slot after another, often within the same or adjacent game ticks, and the customer picks them up.
 *
 * The pre-fix implementation held ONE pending slot (`invDeltaPending`). Arming a second Drop before the first
 * had been confirmed by its ground spawn OVERWROTE the first pending, and the first drop then emitted
 * NOTHING — silently, with no error and no trace anywhere in the pipeline. The account's inventory still
 * showed the item gone, so a periodic snapshot looks perfectly consistent while the event is simply absent.
 * That is why snapshots cannot certify event completeness: they confirm final state, not telemetry.
 *
 * These tests drive the real callback order the client produces (arm on MenuOptionClicked, confirm on
 * ItemSpawned / inventory change) and assert on the emitted event buffer.
 */
public class RapidDropTest
{
	private static final int DEATH_RUNE = 560;
	private static final int TBOW = 20997;
	private static final int SCYTHE = 22486;
	private static final int FANG = 26219;

	// ---------------------------------------------------------------- the defect

	/**
	 * FOUR items dropped in rapid succession — the exact shape of a real multi-item drop trade.
	 * Every one is a genuine, completed drop: each has its own menu click, its own ground spawn and its own
	 * inventory loss. All four MUST be emitted.
	 */
	@Test
	public void fourRapidDropsAllEmit() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		int[] items = {TBOW, SCYTHE, FANG, DEATH_RUNE};

		// The player clicks Drop on four slots across two ticks, then each item lands.
		for (int i = 0; i < items.length; i++)
		{
			tick(plugin, 100 + (i / 2));           // two drops per tick
			plugin.onMenuOptionClicked(menu("Drop", "", items[i]));
		}
		for (int i = 0; i < items.length; i++)
		{
			plugin.resolveDropPendingOnGroundSpawn(items[i], 0, 0L, 101);
		}

		List<Integer> emitted = droppedItemIds(plugin);
		assertEquals("every real drop must emit exactly one event; lost drops = silent data loss",
			4, emitted.size());
		assertTrue("tbow drop lost", emitted.contains(TBOW));
		assertTrue("scythe drop lost", emitted.contains(SCYTHE));
		assertTrue("fang drop lost", emitted.contains(FANG));
		assertTrue("rune drop lost", emitted.contains(DEATH_RUNE));
	}

	/** Two drops on the SAME tick — the tightest real case, and the one a single slot always loses. */
	@Test
	public void twoDropsOnTheSameTickBothEmit() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 200);
		plugin.onMenuOptionClicked(menu("Drop", "", TBOW));
		plugin.onMenuOptionClicked(menu("Drop", "", SCYTHE));
		plugin.resolveDropPendingOnGroundSpawn(TBOW, 0, 0L, 200);
		plugin.resolveDropPendingOnGroundSpawn(SCYTHE, 0, 0L, 200);

		List<Integer> emitted = droppedItemIds(plugin);
		assertEquals("both same-tick drops must emit", 2, emitted.size());
		assertTrue(emitted.contains(TBOW));
		assertTrue(emitted.contains(SCYTHE));
	}

	/** Ground spawns arriving in a DIFFERENT order than the clicks — the server decides packet order. */
	@Test
	public void outOfOrderGroundSpawnsStillMatchTheirOwnDrop() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 300);
		plugin.onMenuOptionClicked(menu("Drop", "", TBOW));
		plugin.onMenuOptionClicked(menu("Drop", "", SCYTHE));
		plugin.onMenuOptionClicked(menu("Drop", "", FANG));
		// spawns land reversed
		plugin.resolveDropPendingOnGroundSpawn(FANG, 0, 0L, 300);
		plugin.resolveDropPendingOnGroundSpawn(SCYTHE, 0, 0L, 300);
		plugin.resolveDropPendingOnGroundSpawn(TBOW, 0, 0L, 300);

		List<Integer> emitted = droppedItemIds(plugin);
		assertEquals(3, emitted.size());
		assertTrue(emitted.contains(TBOW));
		assertTrue(emitted.contains(SCYTHE));
		assertTrue(emitted.contains(FANG));
	}

	// ---------------------------------------------------------------- no regressions

	/** A single ordinary drop must behave EXACTLY as before. */
	@Test
	public void singleDropUnchanged() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 400);
		plugin.onMenuOptionClicked(menu("Drop", "", DEATH_RUNE));
		assertNotNull("a single drop still arms", firstPending(plugin));
		plugin.resolveDropPendingOnGroundSpawn(DEATH_RUNE, 0, 0L, 400);

		List<Integer> emitted = droppedItemIds(plugin);
		assertEquals(1, emitted.size());
		assertEquals(Integer.valueOf(DEATH_RUNE), emitted.get(0));
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals(5L, e.get("qty"));
		assertEquals(true, e.get("wilderness"));
		assertNotNull("location preserved", e.get("location"));
	}

	/** No duplicates: one ground spawn cannot emit the same drop twice. */
	@Test
	public void aSecondSpawnForAnAlreadyEmittedDropDoesNotDuplicate() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 500);
		plugin.onMenuOptionClicked(menu("Drop", "", TBOW));
		plugin.resolveDropPendingOnGroundSpawn(TBOW, 0, 0L, 500);
		plugin.resolveDropPendingOnGroundSpawn(TBOW, 0, 0L, 500);   // another player's tbow appearing
		assertEquals("a drop must never emit twice", 1, droppedItemIds(plugin).size());
	}

	/**
	 * An inventory removal with NO ground spawn must still never fabricate a drop, even with several
	 * pendings armed — the M3 guarantee must survive the queue.
	 */
	@Test
	public void inventoryRemovalAloneStillNeverFabricatesADrop() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 600);
		plugin.onMenuOptionClicked(menu("Drop", "", TBOW));
		plugin.onMenuOptionClicked(menu("Drop", "", SCYTHE));
		plugin.resolveInvDeltaPending(0L, 0L, 601);       // items left the inventory: bank / equip / destroy
		assertTrue("no ground spawn = no drop, ever", plugin.pendingEvents.isEmpty());
	}

	/** A direct trade must never become a drop event. */
	@Test
	public void aTradeDoesNotArmOrEmitADrop() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 700);
		plugin.onMenuOptionClicked(menu("Trade with", "Leegsonly", TBOW));
		assertNull("a trade click must not arm a drop pending", firstPending(plugin));
		plugin.resolveDropPendingOnGroundSpawn(TBOW, 0, 0L, 700);
		assertTrue("a trade must never emit a drop", plugin.pendingEvents.isEmpty());
	}

	/** Stale pendings expire without emitting and must not block later drops. */
	@Test
	public void anExpiredDropDoesNotBlockALaterDrop() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 800);
		plugin.onMenuOptionClicked(menu("Drop", "", TBOW));     // never lands
		tick(plugin, 900);
		plugin.onMenuOptionClicked(menu("Drop", "", SCYTHE));
		plugin.resolveDropPendingOnGroundSpawn(SCYTHE, 0, 0L, 900);

		List<Integer> emitted = droppedItemIds(plugin);
		assertEquals("the later real drop must still emit", 1, emitted.size());
		assertEquals(Integer.valueOf(SCYTHE), emitted.get(0));
	}

	// ---------------------------------------------------------------- helpers

	private static AccountConnectPlugin newPlugin() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		ItemContainer inv = container(TBOW, 1, SCYTHE, 1, FANG, 1, DEATH_RUNE, 5);
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
		when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(100);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3100, 3900, 0));
		inject(plugin, "client", client);
		return plugin;
	}

	private static void tick(AccountConnectPlugin plugin, int t) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("client");
		f.setAccessible(true);
		Client c = (Client) f.get(plugin);
		when(c.getTickCount()).thenReturn(t);
	}

	private static List<Integer> droppedItemIds(AccountConnectPlugin plugin)
	{
		List<Integer> out = new ArrayList<>();
		for (Map<String, Object> e : plugin.pendingEvents)
		{
			if ("drop".equals(e.get("type")))
			{
				out.add((Integer) e.get("item"));
			}
		}
		return out;
	}

	private static AccountConnectPlugin.InvDeltaPending firstPending(AccountConnectPlugin plugin) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("invDeltaPendings");
		f.setAccessible(true);
		java.util.Deque<?> d = (java.util.Deque<?>) f.get(plugin);
		return d.isEmpty() ? null : (AccountConnectPlugin.InvDeltaPending) d.peekFirst();
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
				if (rt == boolean.class || rt == Boolean.class)
				{
					return Boolean.TRUE;
				}
				if (rt == String.class)
				{
					return "0123456789abcdef0123456789abcdef";
				}
				if (rt == int.class || rt == Integer.class)
				{
					return 0;
				}
				return null;
			});
	}
}
