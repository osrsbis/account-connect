package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.VarbitComposition;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A logout and a login of the same account in the same client, the collection log not opened again. Each upload
 * after the login carries only this login's pages (none), and the listing fields are this login's own readings.
 * Driven through the plugin's event handlers and the real upload path; the body checked is the one sent.
 */
public class RelogListingFieldsTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int KC_VARP = SnapshotBuilder.KILL_COUNT_VARPS[0];

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private Client client;
	private Widget header;
	private final int[] vars = new int[6000];
	private int tick = 1000;
	private GameState state = GameState.LOGGED_IN;
	private long hash = 42L;
	private String launcherName = "Fixture";

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				return new MockResponse().setResponseCode(200);
			}
		});
		server.start();
		client = mock(Client.class);
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Fixture Main");
		when(client.getGameState()).thenAnswer(i -> state);
		when(client.getLocalPlayer()).thenAnswer(i -> state == GameState.LOGGED_IN ? player : null);
		when(client.getAccountHash()).thenAnswer(i -> hash);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getVarps()).thenReturn(vars);
		when(client.getVarpValue(anyInt())).thenAnswer(i -> vars[(Integer) i.getArgument(0)]);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		when(client.getSkillExperience(any(Skill.class))).thenReturn(0);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getLauncherDisplayName()).thenAnswer(i -> launcherName);
		VarbitComposition host = mock(VarbitComposition.class);	// varbit 12061: the player's own log
		when(host.getIndex()).thenReturn(2049);
		when(host.getLeastSignificantBit()).thenReturn(11);
		when(host.getMostSignificantBit()).thenReturn(11);
		when(client.getVarbit(12061)).thenReturn(host);
		header = mock(Widget.class);
		when(client.getWidget(InterfaceID.Collection.HEADER_TEXT)).thenReturn(header);
		Widget title = mock(Widget.class);
		when(title.getText()).thenReturn("Abyssal Sire");
		Widget obtained = mock(Widget.class);
		when(obtained.getText()).thenReturn("Obtained: 0/9");
		Widget kills = mock(Widget.class);
		when(kills.getText()).thenReturn("Abyssal Sire kills: 0");
		when(header.getChildren()).thenReturn(new Widget[]{title, obtained, kills});

		plugin = new AccountConnectPlugin();
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

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick++;
			plugin.onGameTick(new GameTick());
		}
	}

	private void login()
	{
		gameState(GameState.LOGGING_IN);
		tick++;
		gameState(GameState.LOGGED_IN);
		ticks(AccountConnectPlugin.LOGIN_SETTLE_TICKS);
	}

	/** Drains the uploads already sent and returns the snapshot of the last one. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> lastUpload() throws Exception
	{
		RecordedRequest last = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("an upload was sent", last);
		for (RecordedRequest next; (next = server.takeRequest(200, TimeUnit.MILLISECONDS)) != null; ) last = next;
		Map<String, Object> body = new Gson().fromJson(last.getBody().readUtf8(), Map.class);
		return (Map<String, Object>) body.get("snapshot");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> block(Map<String, Object> snap, String key)
	{
		return (Map<String, Object>) snap.get(key);
	}

	@Test
	public void aRelogSendsNoPageOfThePreviousLoginAndFreshListingValues() throws Exception
	{
		vars[KC_VARP] = 7;
		vars[VarPlayer.MEMBERSHIP_DAYS] = 30;
		login();
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.COLLECTION);
		plugin.onWidgetLoaded(open);
		ticks(3);
		plugin.syncTask();
		Map<String, Object> first = lastUpload();
		assertTrue("the open page is sent", block(first, "collection_log_pages").containsKey("Abyssal Sire"));
		assertEquals(7.0, block(first, "kill_counts_raw").get(Integer.toString(KC_VARP)));
		assertEquals(30.0, block(first, "account").get("membership_days"));
		assertEquals(true, block(first, "account").get("jagex_launcher"));

		// Logout with the log still open (no WidgetClosed), then the same account logs in again.
		gameState(GameState.LOGIN_SCREEN);
		vars[KC_VARP] = 8;
		vars[VarPlayer.MEMBERSHIP_DAYS] = 29;
		launcherName = null;
		login();
		plugin.syncTask();
		Map<String, Object> second = lastUpload();
		assertEquals("no page of the previous login", 0, block(second, "collection_log_pages").size());
		assertEquals("not_scanned", block(second, "collection_log").get("scan_status"));
		assertEquals("this login's varp", 8.0, block(second, "kill_counts_raw").get(Integer.toString(KC_VARP)));
		assertEquals(29.0, block(second, "account").get("membership_days"));
		assertNull("no launcher name this login", block(second, "account").get("jagex_launcher"));

		// A later upload of the same login, the log still not reopened.
		vars[KC_VARP] = 9;
		ticks(2);
		plugin.uploader.lastSendMillis = 0;
		plugin.syncTask();
		Map<String, Object> third = lastUpload();
		assertEquals(0, block(third, "collection_log_pages").size());
		assertEquals(9.0, block(third, "kill_counts_raw").get(Integer.toString(KC_VARP)));
	}
}
