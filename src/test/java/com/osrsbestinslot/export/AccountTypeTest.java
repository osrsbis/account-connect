package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 0.7.17: snapshot.account.account_type is the IRON MODE read from the ACCOUNT_TYPE varbit (1777).
 *
 * THE BUG THIS PINS. 0.7.0-0.7.16 sent RuneScapeProfileType.getCurrent(client).name(). That enum has
 * STANDARD/BETA/DEADMAN/LEAGUE/... and no ironman variant, so every main-game account, iron or not, shipped
 * "STANDARD", and the site's /IRONMAN/ arm never fired. The wire assertions below drive the real syncTask, so
 * the old line fails every iron case here (it answers STANDARD for each).
 *
 * The varbit value map (0 normal, 1 ironman, 2 ultimate, 3 hardcore, 4 group, 5 hardcore group, 6 unranked
 * group) is RuneLite's documented Varbits.ACCOUNT_TYPE meaning. 0-5 are cross-checked against the names of the
 * deprecated net.runelite.api.vars.AccountType enum; 6 is not in that enum and must stay distinct.
 */
public class AccountTypeTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private MockWebServer server;

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

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field field = AccountConnectPlugin.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(plugin, value);
	}

	/** Drive the real login-tick snapshot send with the varbit at {@code raw}; return snapshot.account. */
	private Map<?, ?> accountOnWire(int raw) throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(player.getName()).thenReturn("IronTester");
		when(client.getVarbitValue(1777)).thenReturn(raw);

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
		inject(plugin, "client", client);
		inject(plugin, "config", config);
		inject(plugin, "gson", new Gson());
		inject(plugin, "okHttpClient", new OkHttpClient());
		inject(plugin, "itemManager", mock(ItemManager.class));

		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.syncTask();
		RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("login tick must send a snapshot", req);
		Map<?, ?> body = new Gson().fromJson(req.getBody().readUtf8(), Map.class);
		return (Map<?, ?>) ((Map<?, ?>) body.get("snapshot")).get("account");
	}

	@Test
	public void everyIronModeReachesTheWireByName() throws Exception
	{
		String[] expected = {"NORMAL", "IRONMAN", "ULTIMATE_IRONMAN", "HARDCORE_IRONMAN", "GROUP_IRONMAN",
			"HARDCORE_GROUP_IRONMAN", "UNRANKED_GROUP_IRONMAN"};
		for (int raw = 0; raw < expected.length; raw++)
		{
			Map<?, ?> account = accountOnWire(raw);
			assertEquals("varbit " + raw, expected[raw], account.get("account_type"));
			// The old value is preserved under its own key, not overloaded onto account_type.
			assertEquals("varbit " + raw, "STANDARD", account.get("profile_type"));
		}
	}

	@Test
	public void unknownValuesAreNeverGuessed()
	{
		assertEquals("UNKNOWN_7", AccountConnectPlugin.accountTypeName(7));
		assertEquals("UNKNOWN_-1", AccountConnectPlugin.accountTypeName(-1));
	}

	@Test
	public void unrankedGroupIsNotCollapsedIntoGroupIronman()
	{
		assertEquals("UNRANKED_GROUP_IRONMAN", AccountConnectPlugin.accountTypeName(6));
		assertEquals(false, AccountConnectPlugin.accountTypeName(6).equals(AccountConnectPlugin.accountTypeName(4)));
	}

	@Test
	public void varbitIdIsTheAccountTypeVarbit()
	{
		assertEquals(1777, AccountConnectPlugin.ACCOUNT_TYPE_VARBIT);
		@SuppressWarnings("deprecation")
		int legacy = Varbits.ACCOUNT_TYPE;
		assertEquals(legacy, AccountConnectPlugin.ACCOUNT_TYPE_VARBIT);
	}

	@Test
	@SuppressWarnings("deprecation")
	public void namesMatchRuneLiteAccountTypeOrdinals()
	{
		net.runelite.api.vars.AccountType[] rl = net.runelite.api.vars.AccountType.values();
		assertEquals("RuneLite enum shape changed; re-verify the map", 6, rl.length);
		for (int i = 0; i < rl.length; i++)
		{
			assertEquals("ordinal " + i, rl[i].name(), AccountConnectPlugin.accountTypeName(i));
		}
	}
}
