package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Live run 2026-10-23 (G6): account A logged out to the login screen and account B logged in to the same client.
 * The login stat burst sent four level_up rows under A's hash and RSN carrying B's levels, three level_up
 * rows under B that compared B's levels with A's, and B's first snapshot carried A's skills.
 * The level baseline is now cleared at every account boundary, a row stamped with the account that logged
 * out is dropped, and no snapshot is built until the login stat burst has landed.
 */
public class AccountSwitchTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final long MAIN = 1111111111111111111L;
	private static final long ALT = 2222222222222222222L;

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private Client client;
	private Player player;
	private int tick = 1000;
	private long hash = MAIN;
	private String name = "Fixture Main";
	private GameState state = GameState.LOGGED_IN;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		plugin = new AccountConnectPlugin();
		client = mock(Client.class);
		player = mock(Player.class);
		when(client.getGameState()).thenAnswer(i -> state);
		when(client.getLocalPlayer()).thenAnswer(i -> state == GameState.LOGGED_IN ? player : null);
		when(client.getAccountHash()).thenAnswer(i -> hash);
		when(player.getName()).thenAnswer(i -> name);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getSkillExperience(any(Skill.class))).thenReturn(0);
		String baseUrl = server.url("/").toString();
		inject("config", new AccountConnectConfig()
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
		});
		inject("client", client);
		inject("gson", new Gson());
		inject("okHttpClient", new OkHttpClient());
		inject("itemManager", mock(ItemManager.class));
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void gameState(GameState s)
	{
		state = s;
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		plugin.onGameStateChanged(e);
	}

	/** MAIN in the world: its session started and its levels seen. */
	private void mainLoggedIn() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		plugin.onStatChanged(new StatChanged(Skill.ATTACK, 1_210_421, 74, 74));
		plugin.onStatChanged(new StatChanged(Skill.COOKING, 21_750, 34, 34));
		plugin.pendingEvents.clear();
	}

	/** MAIN logs out to the login screen and the alt logs in to the same client. */
	private void switchToAlt()
	{
		gameState(GameState.LOGIN_SCREEN);
		plugin.pendingEvents.clear();	// MAIN's own logout row
		hash = -1L;
		gameState(GameState.LOGGING_IN);
		hash = ALT;
		name = "Fixture Alt";
		tick++;
		gameState(GameState.LOGGED_IN);
	}

	private static Map<?, ?> accountOf(RecordedRequest req)
	{
		Map<?, ?> body = new Gson().fromJson(req.getBody().readUtf8(), Map.class);
		return (Map<?, ?>) ((Map<?, ?>) body.get("snapshot")).get("account");
	}

	private List<Map<String, Object>> of(String type)
	{
		return plugin.pendingEvents.stream().filter(e -> type.equals(e.get("type"))).collect(Collectors.toList());
	}

	@Test
	public void theLoginStatBurstOfAnotherAccountIsNoLevelUp() throws Exception
	{
		mainLoggedIn();
		switchToAlt();
		plugin.onStatChanged(new StatChanged(Skill.ATTACK, 3_000_000, 84, 84));	// the alt's real level, not a level-up
		plugin.onStatChanged(new StatChanged(Skill.COOKING, 273_742, 60, 60));
		assertEquals(0, of("level_up").size());
	}

	@Test
	public void aRowStampedWithTheAccountThatLoggedOutIsDropped() throws Exception
	{
		mainLoggedIn();
		switchToAlt();
		plugin.emitEvent("level_up", null);			// before the alt's session starts, activeHash still names MAIN
		assertEquals("never filed under MAIN", 0, plugin.pendingEvents.size());

		tick += AccountConnectPlugin.LOGIN_SETTLE_TICKS;
		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.syncTask();							// the alt's session starts
		plugin.pendingEvents.clear();
		plugin.emitEvent("level_up", null);
		assertEquals(1, plugin.pendingEvents.size());
		assertEquals(Long.toString(ALT), plugin.pendingEvents.get(0).get("account_hash"));
		assertEquals("Fixture Alt", plugin.pendingEvents.get(0).get("rsn"));
	}

	@Test
	public void noSnapshotIsBuiltBeforeTheLoginStatBurstLands() throws Exception
	{
		mainLoggedIn();
		server.enqueue(new MockResponse().setResponseCode(200));
		switchToAlt();
		RecordedRequest logoutFlush = server.takeRequest(300, TimeUnit.MILLISECONDS);	// MAIN's own cached snapshot, if unsent
		if (logoutFlush != null)
		{
			assertEquals(Long.toString(MAIN), accountOf(logoutFlush).get("account_hash"));
		}
		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.syncTask();
		assertNull("a snapshot now would carry MAIN's skills", server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals("no login row either", 0, of("login").size());

		tick += AccountConnectPlugin.LOGIN_SETTLE_TICKS;
		plugin.syncTask();
		RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull(req);
		assertEquals(Long.toString(ALT), accountOf(req).get("account_hash"));
		List<Map<String, Object>> login = of("login");
		assertEquals(1, login.size());
		assertEquals(Long.toString(ALT), login.get(0).get("account_hash"));
	}

	@Test
	public void whileTheHashStillReadsUnknownTheNameDecides() throws Exception
	{
		mainLoggedIn();
		gameState(GameState.LOGIN_SCREEN);
		plugin.pendingEvents.clear();
		hash = -1L;
		name = "Fixture Alt";
		gameState(GameState.LOGGING_IN);
		tick++;
		gameState(GameState.LOGGED_IN);
		plugin.emitEvent("level_up", null);
		assertEquals("the alt's row never filed under MAIN", 0, plugin.pendingEvents.size());

		name = "Fixture Main";
		plugin.emitEvent("level_up", null);
		assertEquals("MAIN back in the world: MAIN's own row is kept", 1, plugin.pendingEvents.size());
	}

	@Test
	public void aRelogToTheSameAccountKeepsItsRows() throws Exception
	{
		mainLoggedIn();
		gameState(GameState.LOGIN_SCREEN);
		plugin.pendingEvents.clear();
		gameState(GameState.LOGGING_IN);
		tick++;
		gameState(GameState.LOGGED_IN);
		plugin.emitEvent("level_up", null);
		assertEquals(1, plugin.pendingEvents.size());
		assertEquals(Long.toString(MAIN), plugin.pendingEvents.get(0).get("account_hash"));
	}
}
