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
 * Live run 2026-10-23 (G6): D logged out to the login screen and 21 Djinn 736 logged in to the same client.
 * The login stat burst sent four level_up rows under D's hash and RSN carrying djinn's levels, three level_up
 * rows under djinn that compared djinn's levels with D's, and djinn's first snapshot carried D's skills.
 * The level baseline is now cleared at every account boundary, a row stamped with the account that logged
 * out is dropped, and no snapshot is built until the login stat burst has landed.
 */
public class AccountSwitchTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final long D = 4340155410150241529L;
	private static final long DJINN = 84507830909010332L;

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private Client client;
	private Player player;
	private int tick = 1000;
	private long hash = D;
	private String name = "3 Arrow 2605";
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

	/** D in the world: its session started and its levels seen. */
	private void dLoggedIn() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		plugin.onStatChanged(new StatChanged(Skill.ATTACK, 1_210_421, 74, 74));
		plugin.onStatChanged(new StatChanged(Skill.COOKING, 21_750, 34, 34));
		plugin.pendingEvents.clear();
	}

	/** D logs out to the login screen and djinn logs in to the same client. */
	private void switchToDjinn()
	{
		gameState(GameState.LOGIN_SCREEN);
		plugin.pendingEvents.clear();	// D's own logout row
		hash = -1L;
		gameState(GameState.LOGGING_IN);
		hash = DJINN;
		name = "21 Djinn 736";
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
		dLoggedIn();
		switchToDjinn();
		plugin.onStatChanged(new StatChanged(Skill.ATTACK, 3_000_000, 84, 84));	// djinn's real level, not a level-up
		plugin.onStatChanged(new StatChanged(Skill.COOKING, 273_742, 60, 60));
		assertEquals(0, of("level_up").size());
	}

	@Test
	public void aRowStampedWithTheAccountThatLoggedOutIsDropped() throws Exception
	{
		dLoggedIn();
		switchToDjinn();
		plugin.emitEvent("level_up", null);			// before djinn's session starts, activeHash still names D
		assertEquals("never filed under D", 0, plugin.pendingEvents.size());

		tick += AccountConnectPlugin.LOGIN_SETTLE_TICKS;
		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.syncTask();							// djinn's session starts
		plugin.pendingEvents.clear();
		plugin.emitEvent("level_up", null);
		assertEquals(1, plugin.pendingEvents.size());
		assertEquals(Long.toString(DJINN), plugin.pendingEvents.get(0).get("account_hash"));
		assertEquals("21 Djinn 736", plugin.pendingEvents.get(0).get("rsn"));
	}

	@Test
	public void noSnapshotIsBuiltBeforeTheLoginStatBurstLands() throws Exception
	{
		dLoggedIn();
		server.enqueue(new MockResponse().setResponseCode(200));
		switchToDjinn();
		RecordedRequest logoutFlush = server.takeRequest(300, TimeUnit.MILLISECONDS);	// D's own cached snapshot, if unsent
		if (logoutFlush != null)
		{
			assertEquals(Long.toString(D), accountOf(logoutFlush).get("account_hash"));
		}
		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.syncTask();
		assertNull("a snapshot now would carry D's skills", server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals("no login row either", 0, of("login").size());

		tick += AccountConnectPlugin.LOGIN_SETTLE_TICKS;
		plugin.syncTask();
		RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull(req);
		assertEquals(Long.toString(DJINN), accountOf(req).get("account_hash"));
		List<Map<String, Object>> login = of("login");
		assertEquals(1, login.size());
		assertEquals(Long.toString(DJINN), login.get(0).get("account_hash"));
	}

	@Test
	public void whileTheHashStillReadsUnknownTheNameDecides() throws Exception
	{
		dLoggedIn();
		gameState(GameState.LOGIN_SCREEN);
		plugin.pendingEvents.clear();
		hash = -1L;
		name = "21 Djinn 736";
		gameState(GameState.LOGGING_IN);
		tick++;
		gameState(GameState.LOGGED_IN);
		plugin.emitEvent("level_up", null);
		assertEquals("djinn's row never filed under D", 0, plugin.pendingEvents.size());

		name = "3 Arrow 2605";
		plugin.emitEvent("level_up", null);
		assertEquals("D back in the world: D's own row is kept", 1, plugin.pendingEvents.size());
	}

	@Test
	public void aRelogToTheSameAccountKeepsItsRows() throws Exception
	{
		dLoggedIn();
		gameState(GameState.LOGIN_SCREEN);
		plugin.pendingEvents.clear();
		gameState(GameState.LOGGING_IN);
		tick++;
		gameState(GameState.LOGGED_IN);
		plugin.emitEvent("level_up", null);
		assertEquals(1, plugin.pendingEvents.size());
		assertEquals(Long.toString(D), plugin.pendingEvents.get(0).get("account_hash"));
	}
}
