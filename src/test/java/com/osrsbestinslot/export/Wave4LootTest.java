package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WAVE 4 — structured loot events. All four loot classes were javap-verified present in client 1.12.32;
 * we consume ServerNpcLoot (NPC) + PlayerLootReceived (PvP), mirroring RuneLite's own LootTrackerPlugin
 * to avoid the ServerNpcLoot/NpcLootReceived double-fire. These tests exercise the emit + adapter logic
 * with real ItemStacks and mocked NPCComposition/Player; the events are fired by LootManager (a core
 * service) at runtime, which no unit test can stand in for.
 */
public class Wave4LootTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";

	private static AccountConnectConfig onConfig()
	{
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TEST_TOKEN;
			}
		};
	}

	@Test
	public void emitLootBuffersItemsAndSource() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		plugin.emitLoot("Goblin", "npc", Arrays.asList(new ItemStack(995, 40), new ItemStack(526, 1)));

		assertEquals(1, plugin.pendingEvents.size());
		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("loot", ev.get("type"));
		assertEquals("Goblin", ev.get("source_name"));
		assertEquals("npc", ev.get("source_type"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) ev.get("items");
		assertEquals("one kill's drops coalesce into one event", 2, items.size());
		assertEquals(995, items.get(0).get("id"));
		assertEquals(40, items.get(0).get("qty"));
		assertEquals(0L, ev.get("value")); // no ItemManager injected → value 0, still present
	}

	@Test
	public void emitLootValuesViaItemManager() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ItemManager im = mock(ItemManager.class);
		when(im.getItemPrice(995)).thenReturn(1);
		when(im.getItemPrice(560)).thenReturn(200);
		inject(plugin, "itemManager", im);

		plugin.emitLoot("Vorkath", "npc", Arrays.asList(new ItemStack(995, 5000), new ItemStack(560, 100)));

		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals(5000L + 200L * 100L, ev.get("value")); // 5000 + 20000 = 25000
	}

	@Test
	public void emitLootSkipsBlankStacksAndEmptyDrops() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());

		plugin.emitLoot("Goblin", "npc", Collections.emptyList());
		assertTrue("empty drop → no event", plugin.pendingEvents.isEmpty());

		plugin.emitLoot("Goblin", "npc", Arrays.asList(new ItemStack(-1, 5), new ItemStack(995, 0)));
		assertTrue("all-blank drop → no event", plugin.pendingEvents.isEmpty());

		plugin.emitLoot("Goblin", "npc", Arrays.asList(new ItemStack(-1, 5), new ItemStack(526, 3)));
		assertEquals(1, plugin.pendingEvents.size());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) plugin.pendingEvents.get(0).get("items");
		assertEquals("only the real item survives", 1, items.size());
		assertEquals(526, items.get(0).get("id"));
	}

	@Test
	public void onServerNpcLootEmitsNpcLoot() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		NPCComposition comp = mock(NPCComposition.class);
		when(comp.getName()).thenReturn("Zulrah");
		ServerNpcLoot event = new ServerNpcLoot(comp, Collections.singletonList(new ItemStack(12934, 250)));

		plugin.onServerNpcLoot(event);

		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("loot", ev.get("type"));
		assertEquals("npc", ev.get("source_type"));
		assertEquals("Zulrah", ev.get("source_name"));
	}

	@Test
	public void onPlayerLootWithholdsVictimName() throws Exception
	{
		// Compliance: capture the PvP loot ITEMS but never the killed player's name (Lukas decision, TODO).
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Player victim = mock(Player.class);
		PlayerLootReceived event = new PlayerLootReceived(victim, Collections.singletonList(new ItemStack(11832, 1)));

		plugin.onPlayerLootReceived(event);

		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("loot", ev.get("type"));
		assertEquals("player", ev.get("source_type"));
		assertFalse("victim name must NOT be sent", ev.containsKey("source_name"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) ev.get("items");
		assertEquals(11832, items.get(0).get("id"));
	}

	@Test
	public void lootGatedOnToken() throws Exception
	{
		// No token → activity log inactive → loot buffers nothing (same gate as every event).
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", new AccountConnectConfig() {});
		plugin.emitLoot("Goblin", "npc", Collections.singletonList(new ItemStack(995, 10)));
		assertTrue(plugin.pendingEvents.isEmpty());
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
