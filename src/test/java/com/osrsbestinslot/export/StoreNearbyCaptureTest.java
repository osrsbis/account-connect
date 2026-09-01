package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Store-transfer counterparty capture — nearby[] (players seen over a shop visit), world, loc attached to
 * store events. Unmasks the untracked receiver of the general-store method: staff sells an item cheap into
 * the shared store, an untracked player standing there buys it out (no trade event). The receiver is in the
 * nearby set — presence + persistence + proximity finger them. Logic proven against mocked players; real
 * WorldView timing is field-verified on a live shop visit.
 */
public class StoreNearbyCaptureTest
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

	private static Player player(String name, int x, int y, int cb)
	{
		Player p = mock(Player.class);
		when(p.getName()).thenReturn(name);
		when(p.getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
		when(p.getCombatLevel()).thenReturn(cb);
		return p;
	}

	/** plugin wired to a client at self-loc, given nearby players list + world + tick. */
	private static AccountConnectPlugin plugin(Player self, List<Player> players, int world, int tick) throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(self);
		when(client.getPlayers()).thenReturn(players);
		when(client.getWorld()).thenReturn(world);
		when(client.getTickCount()).thenReturn(tick);
		inject(plugin, "client", client);
		return plugin;
	}

	@Test
	public void accumulateTracksClosestApproachAndPersistence() throws Exception
	{
		Player self = player("Staff", 3164, 3486, 90);
		Player b = mock(Player.class);
		when(b.getName()).thenReturn("Receiver");
		when(b.getCombatLevel()).thenReturn(126);
		Client client = mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(self);
		List<Player> players = new ArrayList<>();
		players.add(self);
		players.add(b);
		when(client.getPlayers()).thenReturn(players);

		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "client", client);

		// tick 10: Receiver 3 tiles east
		when(b.getWorldLocation()).thenReturn(new WorldPoint(3167, 3486, 0));
		when(client.getTickCount()).thenReturn(10);
		plugin.accumulateShopNearby();
		// tick 13: Receiver steps adjacent (1 tile)
		when(b.getWorldLocation()).thenReturn(new WorldPoint(3165, 3486, 0));
		when(client.getTickCount()).thenReturn(13);
		plugin.accumulateShopNearby();

		@SuppressWarnings("unchecked")
		Map<String, int[]> seen = (Map<String, int[]>) field(plugin, "shopVisitNearby");
		int[] rec = seen.get("Receiver");
		assertEquals("closest approach kept", 1, rec[0]);
		assertEquals("first tick", 10, rec[1]);
		assertEquals("last tick", 13, rec[2]);
		assertEquals("combat level", 126, rec[3]);
	}

	@Test
	public void storeContextAttachesNearbyWorldLocNearestFirst() throws Exception
	{
		Player self = player("Staff", 3164, 3486, 90);
		Player near = player("Adjacent", 3165, 3486, 100);	// dist 1
		Player far = player("Bystander", 3170, 3486, 50);	// dist 6
		List<Player> players = new ArrayList<>();
		players.add(self);
		players.add(far);
		players.add(near);
		AccountConnectPlugin plugin = plugin(self, players, 302, 20);

		plugin.accumulateShopNearby();		// build the visit set
		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(fields);

		assertEquals(302, fields.get("world"));
		assertEquals(java.util.Arrays.asList(3164, 3486, 0), fields.get("loc"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nearby = (List<Map<String, Object>>) fields.get("nearby");
		assertEquals(2, nearby.size());
		assertEquals("nearest first", "Adjacent", nearby.get(0).get("rsn"));
		assertEquals(1, nearby.get(0).get("dist"));
		assertEquals("Bystander", nearby.get(1).get("rsn"));
		assertEquals(6, nearby.get(1).get("dist"));
	}

	@Test
	public void selfAndNamelessExcluded() throws Exception
	{
		Player self = player("Staff", 3164, 3486, 90);
		Player nameless = mock(Player.class);
		when(nameless.getName()).thenReturn(null);
		when(nameless.getWorldLocation()).thenReturn(new WorldPoint(3165, 3486, 0));
		List<Player> players = new ArrayList<>();
		players.add(self);		// self must be excluded
		players.add(nameless);	// unnamed (rendering) player excluded
		AccountConnectPlugin plugin = plugin(self, players, 302, 5);

		plugin.accumulateShopNearby();
		@SuppressWarnings("unchecked")
		Map<String, int[]> seen = (Map<String, int[]>) field(plugin, "shopVisitNearby");
		assertTrue("no self, no nameless", seen.isEmpty());
	}

	@Test
	public void liveSnapshotBackfillsWhenAccumulatorEmpty() throws Exception
	{
		// store event fires before any tick accumulated -> nearby[] falls back to a live snapshot.
		Player self = player("Staff", 3164, 3486, 90);
		Player near = player("Walker", 3166, 3486, 70);	// dist 2
		List<Player> players = new ArrayList<>();
		players.add(self);
		players.add(near);
		AccountConnectPlugin plugin = plugin(self, players, 308, 1);

		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(fields);	// no accumulate() first

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nearby = (List<Map<String, Object>>) fields.get("nearby");
		assertEquals(1, nearby.size());
		assertEquals("Walker", nearby.get(0).get("rsn"));
		assertEquals(2, nearby.get(0).get("dist"));
		assertEquals("live snapshot carries offsets", 2, nearby.get(0).get("dx"));
	}

	@Test
	public void noPlayersNoNearbyStillCarriesWorldLoc() throws Exception
	{
		Player self = player("Staff", 3164, 3486, 90);
		List<Player> players = new ArrayList<>();
		players.add(self);
		AccountConnectPlugin plugin = plugin(self, players, 302, 5);

		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(fields);

		assertNull("no nearby field when nobody around", fields.get("nearby"));
		assertEquals(302, fields.get("world"));
		assertFalse("world/loc still present", fields.isEmpty());
	}

	private static Object field(AccountConnectPlugin plugin, String name) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
