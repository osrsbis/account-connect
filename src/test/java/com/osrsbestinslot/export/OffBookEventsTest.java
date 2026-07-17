package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
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
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Off-book value events: drop / pickup / alch (inventory-delta arm+resolve), death items_lost + death_kind,
 * and the looting-bag / seed-vault / group-storage snapshot blocks. Buffer-only (no live client for the emit
 * assertions); the menu-shape (esp. alch spell-on-item) and death-tick timing are flagged [verify in-client].
 */
public class OffBookEventsTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int COINS = 995;

	private static AccountConnectConfig onConfig()
	{
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		};
	}

	// ---------- classifier ----------

	@Test
	public void classifiesDropTakeAndAlch()
	{
		assertEquals("drop", AccountConnectPlugin.offBookMenuAction(menu("Drop", "", 0)));
		assertEquals("pickup", AccountConnectPlugin.offBookMenuAction(menu("Take", "", 0)));
		// menu shape A: the spell name is on the option itself
		assertEquals("alch_high", AccountConnectPlugin.offBookMenuAction(menu("Cast High Level Alchemy", "", 0)));
		// menu shape B (the likely runtime shape): option "Cast", spell name in the target
		assertEquals("alch_low",
			AccountConnectPlugin.offBookMenuAction(menu("Cast", "<col=00ff00>Low Level Alchemy</col> -> Item", 0)));
		assertNull(AccountConnectPlugin.offBookMenuAction(menu("Wield", "", 0)));
		assertNull(AccountConnectPlugin.offBookMenuAction(menu("Drop-all", "", 0))); // not the bare "Drop"
	}

	// ---------- drop ----------

	@Test
	public void dropArmsThenResolvesOnlyViaGroundSpawn() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		ItemContainer inv = container(560, 5); // 5 death runes — build the stub before nesting it in when()
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
		when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(10);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3100, 3900, 0)); // deep wild
		inject(plugin, "client", client);

		plugin.onMenuOptionClicked(menu("Drop", "", 560));
		AccountConnectPlugin.InvDeltaPending p = invDeltaPending(plugin);
		assertNotNull("drop must arm a pending", p);
		assertEquals("drop", p.base);
		assertEquals(560, p.item);
		assertEquals(5L, p.beforeCount);
		assertEquals(Boolean.TRUE, p.wilderness);
		assertTrue("drop defers emit", plugin.pendingEvents.isEmpty());

		// an inventory removal ALONE must never emit a drop (it could be a bank / destroy / equip)
		plugin.resolveInvDeltaPending(0L, 0L, 11);
		assertTrue("inventory delta alone never emits a drop", plugin.pendingEvents.isEmpty());
		assertNotNull("pending survives awaiting the ground spawn", invDeltaPending(plugin));

		// the ground spawn at our tile + the inventory loss together confirm the drop
		plugin.resolveDropPendingOnGroundSpawn(560, 0, 0L, 11);
		assertNull("pending consumed", invDeltaPending(plugin));
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("drop", e.get("type"));
		assertEquals(560, e.get("item"));
		assertEquals(5L, e.get("qty"));
		assertEquals(true, e.get("wilderness"));
		assertNotNull("drop carries a location", e.get("location"));
	}

	@Test
	public void dropOutsideWildernessFlagsFalse() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("drop", 526, null, 1L, 0L, null, Boolean.FALSE, 5));
		plugin.resolveDropPendingOnGroundSpawn(526, 1, 0L, 6);
		assertEquals(false, plugin.pendingEvents.get(0).get("wilderness"));
	}

	/**
	 * M3 core guarantee: an armed drop whose item leaves the inventory for a NON-drop reason (equip after a
	 * cancelled drop-warning, bank "Deposit inventory", a Destroy confirm) spawns NO ground item — so no drop
	 * event can be fabricated, no matter what the inventory does.
	 */
	@Test
	public void inventoryRemovalWithoutGroundSpawnNeverFabricatesDrop() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("drop", 20997, null, 1L, 0L, null, Boolean.FALSE, 5));
		// tbow leaves the inventory (equip / deposit / destroy) — inv-change path sees it, must not emit
		plugin.resolveInvDeltaPending(0L, 0L, 6);
		assertTrue("no fabricated drop", plugin.pendingEvents.isEmpty());
		// window passes with no ground spawn -> pending expires silently
		plugin.resolveInvDeltaPending(0L, 0L, 30);
		assertNull("stale drop pending expired", invDeltaPending(plugin));
		assertTrue(plugin.pendingEvents.isEmpty());
	}

	/**
	 * The reverse guard: a ground item appearing at our tile that we did NOT lose from the inventory
	 * (another player's drop becoming visible) must not resolve the pending.
	 */
	@Test
	public void groundSpawnWithoutInventoryLossDoesNotEmit() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("drop", 560, null, 5L, 0L, null, Boolean.FALSE, 5));
		// same item id spawns at our feet but our inventory still holds all 5 -> not our drop
		plugin.resolveDropPendingOnGroundSpawn(560, 0, 5L, 6);
		assertTrue("no emit without a matching inventory loss", plugin.pendingEvents.isEmpty());
		assertNotNull("pending kept (the real drop may still land)", invDeltaPending(plugin));
	}

	/**
	 * Death guard: dying spawns YOUR items on the ground at your tile WITH an inventory loss — exactly the
	 * dual drop signal. An armed drop pending must be killed by the death (the loss belongs to the death
	 * event's items_lost, not a fabricated player-initiated drop).
	 */
	@Test
	public void deathKillsArmedDropPendingSoDeathLootIsNotADrop() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		ItemContainer inv = container(20997, 1);
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3100, 3900, 0));
		inject(plugin, "client", client);
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("drop", 20997, null, 1L, 0L, null, Boolean.TRUE, 5));

		plugin.onActorDeath(new net.runelite.api.events.ActorDeath((net.runelite.api.Actor) player));
		assertNull("death disarms any pending drop/pickup/alch", invDeltaPending(plugin));

		// the death's ground spawns + inventory wipe must not resolve as a drop
		plugin.resolveDropPendingOnGroundSpawn(20997, 0, 0L, 6);
		assertTrue("no fabricated drop from death loot", plugin.pendingEvents.isEmpty());
	}

	/** Distance guard: a matching spawn far from the player is someone else's item, never ours. */
	@Test
	public void groundSpawnFarAwayIsIgnored() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("drop", 560, null, 5L, 0L, null, Boolean.FALSE, 5));
		plugin.resolveDropPendingOnGroundSpawn(560, 7, 0L, 6);
		assertTrue("distant spawn ignored", plugin.pendingEvents.isEmpty());
		assertNotNull(invDeltaPending(plugin));
	}

	// ---------- pickup ----------

	@Test
	public void pickupResolvesOnItemGain() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Map<String, Object> loc = new LinkedHashMap<>();
		loc.put("region_id", 12850);
		loc.put("plane", 0);
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("pickup", 526, null, 0L, 0L, loc, null, 5));
		plugin.resolveInvDeltaPending(1L, 0L, 6); // gained 1
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("pickup", e.get("type"));
		assertEquals(526, e.get("item"));
		assertEquals(1L, e.get("qty"));
		assertNotNull(e.get("location"));
		assertFalse("pickup carries no wilderness flag", e.containsKey("wilderness"));
	}

	// ---------- alch ----------

	@Test
	public void alchResolvesWithSpellAndConfirmedGp() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("alch", 1305, "high", 1L, 100L, null, null, 5));
		plugin.resolveInvDeltaPending(0L, 1300L, 6); // item consumed, coins 100 -> 1300
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("alch", e.get("type"));
		assertEquals(1305, e.get("item"));
		assertEquals(1L, e.get("qty"));
		assertEquals("high", e.get("spell"));
		assertEquals(1200L, e.get("gp")); // confirmed via the coin delta
	}

	/**
	 * Alch hardening: an alch always yields coins, so an item removal WITHOUT a coin gain is not an alch
	 * (e.g. the item was equipped/banked after the cast was cancelled). No coin gain -> no emit at all.
	 */
	@Test
	public void alchWithoutCoinGainNeverEmits() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("alch", 1305, "low", 1L, 100L, null, null, 5));
		plugin.resolveInvDeltaPending(0L, 100L, 6); // item left but no coin gain -> not an alch
		assertTrue("no alch emit without a confirmed coin gain", plugin.pendingEvents.isEmpty());
		// window passes -> stale pending expires silently
		plugin.resolveInvDeltaPending(0L, 100L, 12);
		assertNull("stale alch pending expired", invDeltaPending(plugin));
		assertTrue(plugin.pendingEvents.isEmpty());
	}

	// ---------- resolve discipline ----------

	@Test
	public void resolveWaitsForCorrectDeltaThenExpires() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "invDeltaPending",
			new AccountConnectPlugin.InvDeltaPending("pickup", 560, null, 0L, 0L, null, null, 5));
		// an unrelated inventory change (item unchanged) within the window must NOT emit and must keep waiting
		plugin.resolveInvDeltaPending(0L, 0L, 6);
		assertTrue("no emit before the expected delta lands", plugin.pendingEvents.isEmpty());
		assertNotNull("pending kept until it lands or expires", invDeltaPending(plugin));
		// past the window with still no gain -> drop the pending, emit nothing
		plugin.resolveInvDeltaPending(0L, 0L, 12);
		assertTrue(plugin.pendingEvents.isEmpty());
		assertNull("stale pending cleared", invDeltaPending(plugin));
	}

	// ---------- death items_lost + kind ----------

	@Test
	public void deathEmitsItemsLostDiffAndWildernessKind() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Map<Integer, Long> pre = new LinkedHashMap<>();
		pre.put(4151, 1L);  // abyssal whip
		pre.put(560, 100L); // death runes
		Map<String, Object> loc = new LinkedHashMap<>();
		loc.put("region_id", 12345);
		loc.put("plane", 0);
		inject(plugin, "deathPending", new AccountConnectPlugin.DeathPending(loc, "wilderness", pre, 5));

		Map<Integer, Long> post = new LinkedHashMap<>();
		post.put(560, 100L); // kept the runes, lost the whip
		plugin.resolveDeathPending(post, true); // settle-window path: live+settled containers -> compute the loss

		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("death", e.get("type"));
		assertEquals("wilderness", e.get("death_kind"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> lost = (List<Map<String, Object>>) e.get("items_lost");
		assertEquals("only the whip left the account", 1, lost.size());
		assertEquals(4151, lost.get(0).get("id"));
		assertEquals(1L, lost.get(0).get("qty"));
		assertNull("death is consumed", deathPending(plugin));
	}

	/**
	 * M1: a feed-death then instant logout resolves via the logout path (computeLoss=false), where the
	 * containers may be null OR readable-but-not-yet-settled — either way we CANNOT trust a diff. Emit the
	 * death with kind + location but OMIT items_lost (a wrong items_lost on a monitoring feed is worse than none).
	 */
	@Test
	public void deathOnLogoutOmitsItemsLostWhenContainersUntrustworthy() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Map<Integer, Long> pre = new LinkedHashMap<>();
		pre.put(4151, 1L);
		pre.put(560, 100L);
		Map<String, Object> loc = new LinkedHashMap<>();
		loc.put("region_id", 12345);
		loc.put("plane", 0);
		inject(plugin, "deathPending", new AccountConnectPlugin.DeathPending(loc, "wilderness", pre, 5));

		plugin.resolveDeathPending(null, false); // logout path: containers untrustworthy

		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("death", e.get("type"));
		assertEquals("wilderness", e.get("death_kind"));
		assertNotNull("still records where the death happened", e.get("location"));
		assertFalse("items_lost OMITTED when the post-death containers can't be trusted", e.containsKey("items_lost"));
		assertNull("death is consumed", deathPending(plugin));
	}

	/**
	 * M2: a safe (PvM / minigame) death is a gp-sink reclaim, NOT an inter-account transfer. Emit the death
	 * with kind + location for the timeline, but never an items_lost list (it would be polluted by the
	 * post-death consumption in the settle window and misread as a transfer).
	 */
	@Test
	public void safeDeathOmitsItemsLostEvenOnSettlePath() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Map<Integer, Long> pre = new LinkedHashMap<>();
		pre.put(4151, 1L);
		inject(plugin, "deathPending", new AccountConnectPlugin.DeathPending(null, "safe", pre, 5));

		Map<Integer, Long> post = new LinkedHashMap<>(); // whip "gone" (e.g. eaten/left inv) but it's a safe death
		plugin.resolveDeathPending(post, true);

		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("death", e.get("type"));
		assertEquals("safe", e.get("death_kind"));
		assertFalse("safe deaths never carry items_lost", e.containsKey("items_lost"));
	}

	/**
	 * A conflicting same-item click (Wield after a cancelled drop-warning) must NOT kill the pending —
	 * the ground-spawn requirement already makes the equip unable to fabricate, and a REAL drop that
	 * follows must still log (the old disarm approach silently missed every warned drop).
	 */
	@Test
	public void conflictingClickDoesNotKillPendingAndRealDropStillLogs() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		ItemContainer inv = container(20997, 1); // twisted bow
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
		when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenReturn(0);
		when(client.getTickCount()).thenReturn(10);
		inject(plugin, "client", client);

		plugin.onMenuOptionClicked(menu("Drop", "", 20997));
		assertNotNull("drop armed a pending", invDeltaPending(plugin));

		// the drop-warning confirmation (or any same-item click) must not disarm
		plugin.onMenuOptionClicked(menu("Yes", "", 20997));
		assertNotNull("pending survives the confirmation click", invDeltaPending(plugin));

		// the real drop lands: ground spawn at our feet + the item left the inventory
		plugin.resolveDropPendingOnGroundSpawn(20997, 0, 0L, 12);
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("drop", e.get("type"));
		assertEquals(20997, e.get("item"));
		assertEquals(1L, e.get("qty"));
	}

	// ---------- snapshot container blocks ----------

	@Test
	public void containerSnapshotOnlyWhenPresent() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		ItemManager im = mock(ItemManager.class);
		when(im.getItemPrice(anyInt())).thenReturn(2);
		inject(plugin, "itemManager", im);

		Map<String, Object> snap = new LinkedHashMap<>();
		plugin.addContainerSnapshot(snap, "looting_bag", null); // never opened -> omitted entirely
		assertFalse("absent container adds no block", snap.containsKey("looting_bag"));

		plugin.addContainerSnapshot(snap, "looting_bag", container(560, 10));
		@SuppressWarnings("unchecked")
		Map<String, Object> block = (Map<String, Object>) snap.get("looting_bag");
		assertNotNull(block);
		assertEquals(20L, block.get("value_gp")); // 10 * price(2)
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) block.get("items");
		assertEquals(1, items.size());
		assertEquals(560, items.get(0).get("id"));
	}

	// ---------- helpers ----------

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
			items[i] = new Item(idQtyPairs[i * 2], idQtyPairs[i * 2 + 1]); // Item is final — construct, don't mock
		}
		when(c.getItems()).thenReturn(items);
		return c;
	}

	private static AccountConnectPlugin.InvDeltaPending invDeltaPending(AccountConnectPlugin plugin) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("invDeltaPending");
		f.setAccessible(true);
		return (AccountConnectPlugin.InvDeltaPending) f.get(plugin);
	}

	private static AccountConnectPlugin.DeathPending deathPending(AccountConnectPlugin plugin) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("deathPending");
		f.setAccessible(true);
		return (AccountConnectPlugin.DeathPending) f.get(plugin);
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
