package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the change-gated upload path: hash-gating, the min-upload-interval debounce, 429/Retry-After
 * backoff, and the logout flush.
 */
public class UploadGatingTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private Client client;
	private ItemManager itemManager;

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

	private void buildPlugin() throws Exception
	{
		plugin = new AccountConnectPlugin();

		client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(player.getName()).thenReturn("TestPlayer");
		when(player.getCombatLevel()).thenReturn(100);

		itemManager = mock(ItemManager.class);

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
	}

	private void setSkillXp(int xp)
	{
		when(client.getSkillExperience(Skill.ATTACK)).thenReturn(xp);
	}

	/** Waits for the async OkHttp callback to record an accepted upload with the given hash. */
	private void awaitUploadedHash(String expectedHash, long timeoutMillis) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline)
		{
			if (expectedHash.equals(plugin.lastUploadedHash))
			{
				return;
			}
			Thread.sleep(10);
		}
		fail("lastUploadedHash never became " + expectedHash + " (was " + plugin.lastUploadedHash + ")");
	}

	/** Waits for the async OkHttp callback to arm a 429 backoff window. */
	private void awaitBackoffArmed(long timeoutMillis) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline)
		{
			if (plugin.backoffUntilMillis > System.currentTimeMillis())
			{
				return;
			}
			Thread.sleep(10);
		}
		fail("backoff was never armed");
	}

	@Test
	public void identicalStateAcrossTicksProducesExactlyOnePost() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 1;
		server.enqueue(new MockResponse().setResponseCode(200));
		setSkillXp(1000);

		plugin.syncTask();
		RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("first tick must upload", first);
		awaitUploadedHash(plugin.lastBuiltHash, 5000);

		plugin.syncTask();
		assertNull("second tick with identical state must not upload",
			server.takeRequest(500, TimeUnit.MILLISECONDS));
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void stateChangeSendsButNeverBeforeMinInterval() throws Exception
	{
		buildPlugin();
		long interval = 300L;
		plugin.minUploadIntervalMillis = interval;
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(new MockResponse().setResponseCode(200));

		setSkillXp(1000);
		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		awaitUploadedHash(plugin.lastBuiltHash, 5000);

		setSkillXp(2000);
		plugin.syncTask();
		assertNull("debounce must hold the send until the interval elapses",
			server.takeRequest(200, TimeUnit.MILLISECONDS));
		assertEquals(1, server.getRequestCount());

		Thread.sleep(interval + 50);
		plugin.syncTask();
		assertNotNull("once the interval elapses, the changed state must upload",
			server.takeRequest(5, TimeUnit.SECONDS));
		assertEquals(2, server.getRequestCount());
	}

	@Test
	public void volatileFieldsExcludedFromHashProduceNoSecondPost() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 1;
		server.enqueue(new MockResponse().setResponseCode(200));

		ItemContainer inv = mock(ItemContainer.class);
		when(inv.getItems()).thenReturn(new Item[] {new Item(995, 1000)});
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
		when(itemManager.getItemPrice(995)).thenReturn(1);
		setSkillXp(1000);

		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		awaitUploadedHash(plugin.lastBuiltHash, 5000);

		// force captured_at to cross a second boundary and jitter the GE price (wealth) —
		// both are excluded from the canonical hash, so no re-upload should occur.
		Thread.sleep(1100);
		when(itemManager.getItemPrice(995)).thenReturn(999);

		plugin.syncTask();
		assertNull("captured_at/wealth-only differences must not trigger a re-upload",
			server.takeRequest(500, TimeUnit.MILLISECONDS));
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void rateLimitBackoffHonorsRetryAfterThenResumes() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 1;
		server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "2"));
		server.enqueue(new MockResponse().setResponseCode(200));

		setSkillXp(1000);
		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		awaitBackoffArmed(5000);

		setSkillXp(2000);
		plugin.syncTask();
		assertNull("while backed off, ticks must build+track but never send",
			server.takeRequest(500, TimeUnit.MILLISECONDS));
		assertEquals(1, server.getRequestCount());

		Thread.sleep(2100);
		plugin.syncTask();
		assertNotNull("once Retry-After elapses, a changed snapshot must upload",
			server.takeRequest(5, TimeUnit.SECONDS));
		assertEquals(2, server.getRequestCount());
	}

	@Test
	public void logoutFlushSendsCachedSnapshotBypassingDebounce() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 120_000L;
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(new MockResponse().setResponseCode(200));

		setSkillXp(1000);
		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		awaitUploadedHash(plugin.lastBuiltHash, 5000);

		setSkillXp(2000);
		plugin.syncTask();
		assertNull("debounce blocks the scheduled tick from sending the changed state",
			server.takeRequest(200, TimeUnit.MILLISECONDS));
		assertEquals(1, server.getRequestCount());

		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(event);

		assertNotNull("logout must flush the pending changed snapshot",
			server.takeRequest(5, TimeUnit.SECONDS));
		assertEquals(2, server.getRequestCount());
	}

	@Test
	public void syncIntervalClampsUpToFiveSecondFloor() throws Exception
	{
		buildPlugin();
		inject("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public int syncIntervalSeconds()
			{
				return 1;	// below the floor
			}
		});
		plugin.applyConfiguredInterval();
		assertEquals("interval must clamp up to the 5s floor", 5_000L, plugin.minUploadIntervalMillis);
	}

	@Test
	public void syncIntervalClampsDownToSixHundredSecondCeiling() throws Exception
	{
		buildPlugin();
		inject("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public int syncIntervalSeconds()
			{
				return 99_999;	// above the ceiling
			}
		});
		plugin.applyConfiguredInterval();
		assertEquals("interval must clamp down to the 600s ceiling", 600_000L, plugin.minUploadIntervalMillis);
	}
}
