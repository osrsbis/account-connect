package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * WAVE 5 — live-state snapshot fields: spellbook, attack_style, prayer_active[], location, world,
 * kourend_favour, minigame_points. Drives syncTask (which force-sends on the first/login tick) and asserts
 * the fields on the wire. Every varbit / varp id used here is javap-verified against runelite-api 1.12.32.
 */
public class Wave5LiveStateTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private Client client;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	private void inject(String fieldName, Object value) throws Exception
	{
		Field field = AccountConnectPlugin.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(plugin, value);
	}

	@Test
	public void snapshotCarriesLiveStateFields() throws Exception
	{
		plugin = new AccountConnectPlugin();
		client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(player.getName()).thenReturn("TestPlayer");
		when(player.getCombatLevel()).thenReturn(100);

		// Live-state stubs (verified ids). Unstubbed varbits/varps default to 0; unstubbed prayers to false.
		when(client.getVarbitValue(Varbits.SPELLBOOK)).thenReturn(2);          // lunar (raw)
		when(client.getVarpValue(VarPlayer.ATTACK_STYLE)).thenReturn(3);
		when(client.isPrayerActive(Prayer.THICK_SKIN)).thenReturn(true);
		when(client.getWorld()).thenReturn(330);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(2000, 3000, 1));
		when(client.getVarbitValue(Varbits.KOUREND_FAVOR_HOSIDIUS)).thenReturn(1500);
		when(client.getVarbitValue(Varbits.NMZ_POINTS)).thenReturn(5000);
		when(client.getVarbitValue(Varbits.TITHE_FARM_POINTS)).thenReturn(42);

		ItemManager itemManager = mock(ItemManager.class);
		String baseUrl = server.url("/").toString();
		AccountConnectConfig config = new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public String apiBaseUrl()
			{
				return baseUrl;
			}
		};
		inject("client", client);
		inject("config", config);
		inject("gson", new Gson());
		inject("okHttpClient", new OkHttpClient());
		inject("itemManager", itemManager);

		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.syncTask();	// first tick = new login -> force-sends the snapshot

		RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("login tick must send a snapshot", req);
		Map<?, ?> body = new Gson().fromJson(req.getBody().readUtf8(), Map.class);
		Map<?, ?> snap = (Map<?, ?>) body.get("snapshot");

		assertEquals(2.0, ((Number) snap.get("spellbook")).doubleValue(), 0.0);
		assertEquals(3.0, ((Number) snap.get("attack_style")).doubleValue(), 0.0);
		assertEquals(330.0, ((Number) snap.get("world")).doubleValue(), 0.0);

		List<?> prayers = (List<?>) snap.get("prayer_active");
		assertTrue("active prayer must be listed by name", prayers.contains("THICK_SKIN"));

		Map<?, ?> loc = (Map<?, ?>) snap.get("location");
		assertEquals(new WorldPoint(2000, 3000, 1).getRegionID() * 1.0,
			((Number) loc.get("region_id")).doubleValue(), 0.0);
		assertEquals(1.0, ((Number) loc.get("plane")).doubleValue(), 0.0);

		Map<?, ?> favour = (Map<?, ?>) snap.get("kourend_favour");
		assertEquals(1500.0, ((Number) favour.get("hosidius")).doubleValue(), 0.0);

		Map<?, ?> minigames = (Map<?, ?>) snap.get("minigame_points");
		assertEquals(5000.0, ((Number) minigames.get("nmz")).doubleValue(), 0.0);
		assertEquals(42.0, ((Number) minigames.get("tithe")).doubleValue(), 0.0);
	}
}
