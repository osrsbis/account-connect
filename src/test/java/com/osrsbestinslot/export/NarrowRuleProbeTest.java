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
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The W4 adversarial probes, re-aimed at the NARROW attribution rule.
 *
 * Every probe below found a real defect in the wide 54d5a0a candidate. The narrow rule answers each
 * of them the same way: `unknown`, and no fabricated pickup row. The expectations are rewritten to
 * that answer, so this class is the standing proof that the narrow rule closes the class rather than
 * a record of what the wide one did.
 *
 * F1 / F1b  a single Take plus a larger unrelated gain must never claim a customer collection.
 * F2 / F2b  a slow or interrupted walk must never publish our own recovery as somebody else's.
 * F3 / F3b  a stale Take must never claim a pile and must never fabricate a pickup row.
 * F5        a stale merged-stack record must never label a later customer collection.
 * F6        two piles on one tile must never get swapped labels.
 */
public class NarrowRuleProbeTest
{
	private static final int COINS = 995;
	private static final int POT = 1931;
	private static final int TILE_X = 3210;
	private static final int TILE_Y = 3420;

	/** F1: ONE Take, customer collects the 1,200 pile, an unrelated 1,500 gain lands 2 ticks later. */
	@Test
	public void f1_singleTakeLargerUnrelatedGainReportsUnknown() throws Exception
	{
		assertEquals("F1: a 1,500-coin sale is not a 1,200-coin pile (single click)",
			"unknown", singleTakeThenGain(1500));
	}

	/** F1b: an exact double of the pile with a single click. */
	@Test
	public void f1b_singleTakeExactDoubleGainReportsUnknown() throws Exception
	{
		assertEquals("F1b: 2,400 is not one 1,200-coin pile", "unknown", singleTakeThenGain(2400));
	}

	/** F1 control: a SMALLER unrelated gain is refused too. */
	@Test
	public void f1_control_smallerUnrelatedGainReportsUnknown() throws Exception
	{
		assertEquals("control: 700 is refused", "unknown", singleTakeThenGain(700));
	}

	/** And the EXACT pile size still attributes, so the rule is not simply refusing everything. */
	@Test
	public void f1_control_theExactPileSizeStillAttributes() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 1200);
		dropItemAt(plugin, COINS, 1200, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		tick(plugin, 151);
		plugin.onItemDespawned(despawn(COINS, 1200, TILE_X, TILE_Y, 0));
		tick(plugin, 153);
		setInventory(plugin, COINS, 1200);		// exactly the pile
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 165);
		plugin.onGameTick(null);
		assertEquals("an exact-size recovery on a lone Take is still ours",
			"self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	private static String singleTakeThenGain(int gain) throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 1200);
		dropItemAt(plugin, COINS, 1200, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		tick(plugin, 151);
		plugin.onItemDespawned(despawn(COINS, 1200, TILE_X, TILE_Y, 0));	// CUSTOMER collects
		tick(plugin, 153);
		setInventory(plugin, COINS, gain);					// unrelated gain
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 165);
		plugin.onGameTick(null);
		return String.valueOf(onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * F2: a lone Take served after a 6-tick walk loses its label and reports `unknown`.
	 *
	 * The pickup pending lives 5 ticks, so a longer walk expires it. The narrow rule does not try to
	 * revive a stale Take, because every attempt at that in the wide candidate (defects 22 and 23)
	 * produced its own regression. The pile is never mislabelled, only unlabelled.
	 */
	@Test
	public void f2_sixTickWalkReportsUnknown() throws Exception
	{
		assertEquals("walk 6", "unknown", walkAndRecover(6));
	}

	/** F2 control: a 5-tick walk is inside the window and still attributes. */
	@Test
	public void f2_control_fiveTickWalkIsSelfPickup() throws Exception
	{
		assertEquals("walk 5", "self_pickup", walkAndRecover(5));
	}

	private static String walkAndRecover(int walk) throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(plugin, 150 + walk);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 150 + walk + 10);
		plugin.onGameTick(null);
		return String.valueOf(onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** F2b: an unrelated container change 6 ticks into the walk prunes the Take; our recovery then reads removed_early. */
	@Test
	public void f2b_containerChangeDuringWalkMustNotPublishOwnRecoveryAsRemovedEarly() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(plugin, 156);
		setInventory(plugin, COINS, 5);						// unrelated change, no pot
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 158);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));	// we pick it up
		setInventory(plugin, COINS, 5, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 170);
		plugin.onGameTick(null);

		// The unrelated container change at 156 prunes the Take, so at 158 no Take is armed and the
		// pile is not deferred. It is NOT mislabelled either: `takeArmed` was set when the Take was
		// clicked and never clears, so the pile reports `unknown` and names nobody.
		assertEquals("F2b: our own recovery is never `somebody else took it`",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
		assertEquals("F2b: no pickup row may be fabricated from an expired Take",
			0, eventsOfType(plugin, "pickup").size());
	}

	/** F3: a stale unserved Take on the SAME tile sits ahead of the fresh one and eats its evidence. */
	@Test
	public void f3_staleSameTileTakeReportsUnknown() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));	// never served
		tick(plugin, 200);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));	// served
		tick(plugin, 203);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 215);
		plugin.onGameTick(null);

		// Two armed Takes of one item: the client cannot say which click the server ran, so nothing
		// is claimed. The stale one at the queue head no longer STEALS the evidence either - it is
		// discarded on sight for being past its own window - but two Takes is still two Takes.
		assertEquals("F3: two armed Takes cannot prove which one landed",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** F3 control: without the stale Take the same recovery is self_pickup. */
	@Test
	public void f3_control_freshTakeAloneIsSelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 1);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 200);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(plugin, 203);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 215);
		plugin.onGameTick(null);

		assertEquals("control", "self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** F3b: stale Take at the head, then a stale-Take resolution fabricates a pickup row off an unrelated gain much later. */
	@Test
	public void f3b_staleTakeFabricatesNoPickupRowFromALaterUnrelatedGain() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(COINS, 1000);
		dropItemAt(plugin, COINS, 1000, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));	// never served
		tick(plugin, 405);
		plugin.onItemDespawned(despawn(COINS, 1000, TILE_X, TILE_Y, 0));	// timer expiry
		tick(plugin, 420);
		plugin.onGameTick(null);
		tick(plugin, 900);
		setInventory(plugin, COINS, 250);					// unrelated store sale, 750 ticks later
		plugin.onItemContainerChanged(invChanged(plugin));

		assertEquals("F3b: no pickup happened, so no pickup row may exist",
			0, eventsOfType(plugin, "pickup").size());
		assertEquals("F3b: a Take of ours named this pile, so its timer expiry is not assertable",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** F5: merged-stack double record. After a clean self_pickup the stale second record survives and labels a later customer collection on the same tile. */
	@Test
	public void f5_staleMergedRecordNeverLabelsALaterCustomerCollection() throws Exception
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
		assertEquals("one real stack is tracked twice", 2, plugin.groundDropCount());

		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Coins", COINS, TILE_X, TILE_Y));
		tick(plugin, 152);
		plugin.onItemDespawned(despawn(COINS, 1200, TILE_X, TILE_Y, 0));	// we recover it
		setInventory(plugin, COINS, 1200);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 160);
		plugin.onGameTick(null);
		// Two tracked records describe one real 1,200-coin stack, so the Take has two candidates and
		// claims neither. `unknown`, never a fabricated attribution.
		assertEquals("first removal is unattributable", "unknown",
			onlyEvent(plugin, "ground_removed").get("cause"));

		// Next delivery on the same tile 400 ticks later: drop 800, the customer collects at once.
		tick(plugin, 550);
		plugin.onMenuOptionClicked(menu("Drop", "", COINS));
		setInventory(plugin, COINS, 400);
		plugin.onItemSpawned(new net.runelite.api.events.ItemSpawned(
			tile(TILE_X, TILE_Y, 0), tileItem(COINS, 800, 850)));
		tick(plugin, 560);
		plugin.onItemDespawned(despawn(COINS, 800, TILE_X, TILE_Y, 0));	// CUSTOMER collects
		tick(plugin, 570);
		plugin.onGameTick(null);

		List<Map<String, Object>> all = eventsOfType(plugin, "ground_removed");
		Map<String, Object> second = all.get(all.size() - 1);
		assertNotEquals("F5: a customer collection 10 ticks after the drop is not a timer expiry",
			"despawn_timer", second.get("cause"));
		assertEquals("F5: nor is it ours", "unknown", second.get("cause"));
	}

	/** F6: two own unstackable piles on ONE tile. Customer takes first, we take second. */
	@Test
	public void f6_sameTileCustomerFirstThenOurs_bothUnknown() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 2);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y, POT, 1);
		dropItemAt(plugin, POT, 1, 101, 401, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(plugin, 151);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));	// customer takes one
		tick(plugin, 152);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));	// we take the other
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 170);
		plugin.onGameTick(null);
		List<Map<String, Object>> all = eventsOfType(plugin, "ground_removed");
		assertEquals(2, all.size());
		// The two piles are indistinguishable, so the narrow rule claims neither. Both read
		// `unknown`: no stranger is named for a pile we hold proof of recovering, and no staff
		// member is named for the pile the customer collected.
		for (Map<String, Object> r : all)
		{
			assertEquals("F6: two piles on one tile are both unknown: " + all,
				"unknown", r.get("cause"));
		}
	}

	/**
	 * A gain that lands while the pile is STILL ON THE GROUND is not proof of recovery.
	 *
	 * The Take is aimed at our pile, and one Pot really does arrive two ticks later, but it came from
	 * somewhere else: a trade, a store sale, or a same-item pile we never tracked. Our pile is still
	 * lying there sixty ticks after the gain, and a customer collects it two hundred ticks later.
	 * Naming the staff member as the taker of that pile is the exact failure the narrow rule exists
	 * to stop, so the provisional mark is withdrawn and the row falls to `unknown`.
	 */
	@Test
	public void aGainThatLandsWhileThePileStaysOnTheGroundIsNeverASelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 0);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(plugin, 152);
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 160);
		plugin.onGameTick(null);
		assertEquals("the pile is still on the ground, so nothing was recovered",
			1, plugin.groundDropCount());
		tick(plugin, 350);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(plugin, 360);
		plugin.onGameTick(null);
		assertEquals("a pile collected 198 ticks after the gain is unknown, never ours",
			"unknown", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/** The same start, but nobody takes the pile at all and it reaches its own deadline. */
	@Test
	public void aPileThatOutlivesAProvisionalMarkAndTimesOutIsNeverASelfPickup() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 0);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(plugin, 152);
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		tick(plugin, 400);
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(plugin, 410);
		assertNotEquals("a pile that lived its full timer was not recovered by us",
			"self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	/**
	 * The GREEN control for the two probes above: the ordinary recovery order still attributes.
	 *
	 * The inventory change is processed first and the despawn lands in the same tick, which is the
	 * live callback order when the pile really is the one we picked up. The mark is provisional for
	 * that instant and the pile leaves the ground inside the margin, so the row keeps `self_pickup`.
	 * Without this control, a rule that simply refused every mark would look correct.
	 */
	@Test
	public void aRecoveryWhoseDespawnLandsInTheSameTickStillAttributes() throws Exception
	{
		AccountConnectPlugin plugin = newPlugin(POT, 0);
		dropItemAt(plugin, POT, 1, 100, 400, TILE_X, TILE_Y);
		tick(plugin, 150);
		plugin.onMenuOptionClicked(groundTakeAt("Pot", POT, TILE_X, TILE_Y));
		tick(plugin, 152);
		setInventory(plugin, POT, 1);
		plugin.onItemContainerChanged(invChanged(plugin));
		plugin.onItemDespawned(despawn(POT, 1, TILE_X, TILE_Y, 0));
		tick(plugin, 160);
		plugin.onGameTick(null);
		assertEquals("control: the ordinary recovery still attributes",
			"self_pickup", onlyEvent(plugin, "ground_removed").get("cause"));
	}

	// ---- helpers (copied from GroundRemovalTest; they are private there) ----

	private static void standAt(AccountConnectPlugin plugin, int x, int y) throws Exception
	{
		when(client(plugin).getLocalPlayer().getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
	}

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

	private static Map<String, Object> onlyEvent(AccountConnectPlugin plugin, String type)
	{
		List<Map<String, Object>> all = eventsOfType(plugin, type);
		assertEquals("expected exactly one " + type + " event, got " + all, 1, all.size());
		return all.get(0);
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
