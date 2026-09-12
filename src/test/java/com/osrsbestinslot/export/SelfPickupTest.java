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
	private static final int POT = 1931;
	private static final int TILE_X = 3210;
	private static final int TILE_Y = 3420;

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

	/**
	 * THE REAL GROUND-TAKE SHAPE, captured from a live client on 2026-09-12.
	 *
	 * Dropping a Pot and taking it back on world 308 produced exactly this MenuOptionClicked:
	 *   option=Take target=<col=ff9040>Pot id=1931 itemId=-1 param0=49 param1=54
	 *   type=GROUND_ITEM_THIRD_OPTION
	 *
	 * This test previously asserted that NO pickup is emitted here, which encoded the defect as
	 * correct behaviour and is why a green suite missed a live failure on two real staff accounts.
	 */
	@Test
	public void aRealGroundTakeEmitsItsPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 200);
		plugin.onMenuOptionClicked(groundTake("Pot", POT));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(containerChanged(plugin));
		assertEquals("a ground Take carries its item id in the entry identifier, not getItemId()",
			1, eventsOfType(plugin, "pickup").size());
		assertEquals(Integer.valueOf(POT), eventsOfType(plugin, "pickup").get(0));
	}

	/** An entry with NEITHER source populated must still be skipped, not guessed at. */
	@Test
	public void aTakeWithNoUsableIdAnywhereStillEmitsNothing() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin();
		tick(plugin, 200);
		plugin.onMenuOptionClicked(groundTake("Pot", -1));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(containerChanged(plugin));
		assertEquals(0, eventsOfType(plugin, "pickup").size());
	}

	/** The fallback is pickup-ONLY: an inventory entry's identifier is an option index, not an item. */
	@Test
	public void theIdentifierFallbackDoesNotApplyToOtherOpcodes() throws Exception
	{
		MenuOptionClicked drop = mock(MenuOptionClicked.class);
		when(drop.getMenuOption()).thenReturn("Drop");
		when(drop.getItemId()).thenReturn(-1);
		when(drop.getId()).thenReturn(7);          // the real Drop entry's identifier: option index
		assertEquals("a Drop must never adopt the option index as an item id",
			-1, AccountConnectPlugin.offBookItemId(drop, "drop"));
	}

	/**
	 * The identifier is only an item id on a GROUND_ITEM_* opcode.
	 *
	 * Matching the option word "Take" alone would adopt the identifier of any other entry that
	 * happens to be labelled Take — a widget button, a future interface — where the identifier is
	 * an option index or a widget id, not an item. The opcode is what makes the field meaningful.
	 */
	@Test
	public void aTakeOnANonGroundOpcodeNeverAdoptsItsIdentifier()
	{
		MenuOptionClicked m = mock(MenuOptionClicked.class);
		when(m.getMenuOption()).thenReturn("Take");
		when(m.getItemId()).thenReturn(-1);
		when(m.getId()).thenReturn(9764864);	// a widget id, not an item
		when(m.getMenuAction()).thenReturn(net.runelite.api.MenuAction.CC_OP_LOW_PRIORITY);
		assertEquals("only a ground-item opcode puts an item id in the identifier",
			-1, AccountConnectPlugin.offBookItemId(m, "pickup"));
	}

	/** Every ground-item option index is a valid source, not just the third. */
	@Test
	public void everyGroundItemOpcodeCarriesItsItemIdInTheIdentifier()
	{
		for (net.runelite.api.MenuAction a : new net.runelite.api.MenuAction[]{
			net.runelite.api.MenuAction.GROUND_ITEM_FIRST_OPTION,
			net.runelite.api.MenuAction.GROUND_ITEM_SECOND_OPTION,
			net.runelite.api.MenuAction.GROUND_ITEM_THIRD_OPTION,
			net.runelite.api.MenuAction.GROUND_ITEM_FOURTH_OPTION,
			net.runelite.api.MenuAction.GROUND_ITEM_FIFTH_OPTION})
		{
			MenuOptionClicked m = mock(MenuOptionClicked.class);
			when(m.getItemId()).thenReturn(-1);
			when(m.getId()).thenReturn(POT);
			when(m.getMenuAction()).thenReturn(a);
			assertEquals(a.toString(), POT, AccountConnectPlugin.offBookItemId(m, "pickup"));
		}
	}

	/** An inventory action with a good getItemId() keeps using it, identifier ignored. */
	@Test
	public void aNormalInventoryActionStillUsesGetItemId()
	{
		MenuOptionClicked drop = mock(MenuOptionClicked.class);
		when(drop.getItemId()).thenReturn(1931);
		when(drop.getId()).thenReturn(7);
		assertEquals(1931, AccountConnectPlugin.offBookItemId(drop, "drop"));
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
