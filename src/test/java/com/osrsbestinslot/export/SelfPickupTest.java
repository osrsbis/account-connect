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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CONTROLLED DROP -> SELF-PICKUP. Reproduces the live 2026-09-11 grokerini test: three items dropped in
 * rapid succession, one left on the ground, two picked back up by the same account.
 */
public class SelfPickupTest
{
	private static final int CHAPS = 2495;
	private static final int DIAMOND = 1617;
	private static final int COINS = 995;

	@Test
	public void dropThreeThenPickTwoBackUp() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1, DIAMOND, 1, COINS, 10);

		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", CHAPS));
		tick(plugin, 105);
		plugin.onMenuOptionClicked(menu("Drop", "", DIAMOND));
		tick(plugin, 108);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));

		setInventory(plugin);	// inventory now empty of all three
		plugin.resolveDropPendingOnGroundSpawn(CHAPS, 0, 0L, 100);
		plugin.resolveDropPendingOnGroundSpawn(DIAMOND, 0, 0L, 105);
		plugin.resolveDropPendingOnGroundSpawn(COINS, 0, 0L, 108);

		assertEquals("all three drops must emit", 3, eventsOfType(plugin, "drop").size());

		// --- pick the diamond back up ---
		tick(plugin, 140);
		plugin.onMenuOptionClicked(menu("Take", "Uncut diamond", DIAMOND));
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(containerChanged(plugin));

		// --- pick the 10 coins back up ---
		tick(plugin, 150);
		plugin.onMenuOptionClicked(menu("Take", "Coins", COINS));
		setInventory(plugin, DIAMOND, 1, COINS, 10);
		plugin.onItemContainerChanged(containerChanged(plugin));

		List<Integer> picked = eventsOfType(plugin, "pickup");
		assertEquals("both self-pickups must emit exactly once: " + picked, 2, picked.size());
		assertTrue("diamond self-pickup lost", picked.contains(DIAMOND));
		assertTrue("coin self-pickup lost", picked.contains(COINS));
	}


	/** Isolates the PICKUP stage: one drop only, so the single-slot drop defect cannot confound it. */
	@Test
	public void oneDropThenPickItBackUp() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(DIAMOND, 1);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", DIAMOND));
		setInventory(plugin);
		plugin.resolveDropPendingOnGroundSpawn(DIAMOND, 0, 0L, 100);
		assertEquals("the single drop must emit", 1, eventsOfType(plugin, "drop").size());

		tick(plugin, 140);
		plugin.onMenuOptionClicked(menu("Take", "Uncut diamond", DIAMOND));
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(containerChanged(plugin));
		assertEquals("self-pickup after own drop must emit", 1, eventsOfType(plugin, "pickup").size());
	}

	/** Bare pickup, no preceding drop — the general mechanism the corpus already shows working. */
	@Test
	public void barePickupWithNoPrecedingDrop() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 200);
		plugin.onMenuOptionClicked(menu("Take", "Uncut diamond", DIAMOND));
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(containerChanged(plugin));
		assertEquals("bare pickup must emit", 1, eventsOfType(plugin, "pickup").size());
	}

	/** Ground-item Take menu entries that carry no item id (getItemId() == -1). */
	@Test
	public void pickupWhoseMenuEntryCarriesNoItemId() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 200);
		plugin.onMenuOptionClicked(menu("Take", "Uncut diamond", -1));
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(containerChanged(plugin));
		assertEquals("documents behaviour when the menu entry has no item id",
			0, eventsOfType(plugin, "pickup").size());
	}


	/** EXACT live shape: three drops each confirmed by its own ground spawn, then a Take. */
	@Test
	public void liveShapeThreeConfirmedDropsThenTake() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1, DIAMOND, 1, COINS, 10);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", CHAPS));
		plugin.resolveDropPendingOnGroundSpawn(CHAPS, 0, 0L, 100);
		tick(plugin, 105);
		plugin.onMenuOptionClicked(menu("Drop", "", DIAMOND));
		plugin.resolveDropPendingOnGroundSpawn(DIAMOND, 0, 0L, 105);
		tick(plugin, 108);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		plugin.resolveDropPendingOnGroundSpawn(COINS, 0, 0L, 108);
		setInventory(plugin);
		assertEquals("live shape: all three drops emitted", 3, eventsOfType(plugin, "drop").size());

		tick(plugin, 140);
		plugin.onMenuOptionClicked(menu("Take", "Uncut diamond", DIAMOND));
		setInventory(plugin, DIAMOND, 1);
		plugin.onItemContainerChanged(containerChanged(plugin));
		assertEquals("the self-pickup after the live drop shape must emit",
			1, eventsOfType(plugin, "pickup").size());
	}

	/** A Drop click that never gets a ground spawn leaves a pending. Does it swallow a later Take? */
	@Test
	public void staleUnconfirmedDropDoesNotSwallowALaterPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(CHAPS, 1);
		tick(plugin, 100);
		plugin.onMenuOptionClicked(menu("Drop", "", CHAPS));	// no ground spawn ever arrives

		tick(plugin, 102);					// 2 ticks later, well inside DROP_PENDING_MAX_TICKS
		plugin.onMenuOptionClicked(menu("Take", "Uncut diamond", DIAMOND));
		setInventory(plugin, CHAPS, 1, DIAMOND, 1);
		plugin.onItemContainerChanged(containerChanged(plugin));
		assertEquals("a stale drop pending must not swallow a real pickup",
			1, eventsOfType(plugin, "pickup").size());
	}

	// ---------------------------------------------------------------- harness

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
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3210, 3420, 0));
		inject(plugin, "client", client);
		return plugin;
	}

	private static void setInventory(AccountConnectPlugin plugin, int... idQtyPairs) throws Exception
	{
		ItemContainer next = container(idQtyPairs);
		when(client(plugin).getItemContainer(InventoryID.INVENTORY)).thenReturn(next);
	}

	/** The real client hands the changed INVENTORY container to onItemContainerChanged. */
	private static net.runelite.api.events.ItemContainerChanged containerChanged(AccountConnectPlugin plugin)
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

	private static void tick(AccountConnectPlugin plugin, int t) throws Exception
	{
		when(client(plugin).getTickCount()).thenReturn(t);
	}

	private static List<Integer> eventsOfType(AccountConnectPlugin plugin, String type)
	{
		List<Integer> out = new ArrayList<>();
		for (Map<String, Object> e : plugin.pendingEvents)
		{
			if (type.equals(e.get("type")))
			{
				out.add((Integer) e.get("item"));
			}
		}
		return out;
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
