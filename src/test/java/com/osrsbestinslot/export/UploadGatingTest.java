package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Map;
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
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
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

	/** Build a bare 200 response carrying the given header key/value pairs, for applyServerPolicy. */
	private static Response policyResponse(String... headerKV)
	{
		Response.Builder b = new Response.Builder()
			.request(new Request.Builder().url("http://localhost/account-ingest").build())
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK");
		for (int i = 0; i + 1 < headerKV.length; i += 2)
		{
			b.header(headerKV[i], headerKV[i + 1]);
		}
		return b.build();
	}

	@Test
	public void serverSyncIntervalHeaderSetsAndClampsCadence() throws Exception
	{
		buildPlugin();
		plugin.applyServerPolicy(policyResponse("X-Sync-Interval", "5"));
		assertEquals("server cadence directive applies", 5_000L, plugin.minUploadIntervalMillis);

		plugin.applyServerPolicy(policyResponse("X-Sync-Interval", "1"));	// below floor
		assertEquals("clamps up to the 5s floor", 5_000L, plugin.minUploadIntervalMillis);

		plugin.applyServerPolicy(policyResponse("X-Sync-Interval", "99999"));	// above ceiling
		assertEquals("clamps down to the 600s ceiling", 600_000L, plugin.minUploadIntervalMillis);

		long before = plugin.minUploadIntervalMillis;
		plugin.applyServerPolicy(policyResponse());	// no header
		assertEquals("absent directive leaves cadence unchanged", before, plugin.minUploadIntervalMillis);

		plugin.applyServerPolicy(policyResponse("X-Sync-Interval", "notanumber"));
		assertEquals("malformed directive leaves cadence unchanged", before, plugin.minUploadIntervalMillis);
	}

	@Test
	public void serverCanForceScreenshotsOffButNeverOn() throws Exception
	{
		buildPlugin();
		// local opt-in ON
		inject("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public boolean uploadTradeScreenshots()
			{
				return true;
			}
		});
		assertTrue("opt-in on + no server override -> enabled", plugin.screenshotsEnabled());

		plugin.applyServerPolicy(policyResponse("X-Screenshots", "off"));
		assertFalse("server force-disable overrides the local opt-in", plugin.screenshotsEnabled());

		plugin.applyServerPolicy(policyResponse("X-Screenshots", "allow"));
		assertTrue("server can re-allow (local opt-in still governs)", plugin.screenshotsEnabled());
	}

	@Test
	public void serverPauseCapsCadenceToMaxAndBeatsInterval() throws Exception
	{
		buildPlugin();
		// even with a fast interval in the same response, a pause wins and caps to the 600s max
		plugin.applyServerPolicy(policyResponse("X-Uploads-Enabled", "false", "X-Sync-Interval", "5"));
		assertEquals("pause soft-throttles to the 600s max", 600_000L, plugin.minUploadIntervalMillis);
	}

	// ---- FIX 1: the wire actually carries the current plugin version (not a drifted constant) ----

	@Test
	public void snapshotReportsCurrentPluginVersionOnTheWire() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 1;
		server.enqueue(new MockResponse().setResponseCode(200));
		setSkillXp(1000);

		plugin.syncTask();
		RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("first tick must upload", req);

		Map<?, ?> body = new Gson().fromJson(req.getBody().readUtf8(), Map.class);
		Map<?, ?> snapshot = (Map<?, ?>) body.get("snapshot");
		Map<?, ?> source = (Map<?, ?>) snapshot.get("source");
		String wireVersion = (String) source.get("plugin_version");

		Field f = AccountConnectPlugin.class.getDeclaredField("PLUGIN_VERSION");
		f.setAccessible(true);
		assertEquals("snapshot must report the current PLUGIN_VERSION on the wire",
			f.get(null), wireVersion);
	}

	// ---- FIX 2: manual re-sync force-send bypasses the hash gate + debounce (but not the 429 backoff) ----

	@Test
	public void forceSendBypassesHashGateAndDebounce() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 120_000L;	// debounce WOULD hold a scheduled send back
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(new MockResponse().setResponseCode(200));
		setSkillXp(1000);

		// Normal tick: uploads once, records lastUploadedHash AND trips the debounce (lastSendMillis=now).
		plugin.syncTask();
		assertNotNull("first tick must upload", server.takeRequest(5, TimeUnit.SECONDS));
		awaitUploadedHash(plugin.lastBuiltHash, 5000);
		assertEquals(1, server.getRequestCount());

		// Prove BOTH gates are now blocking: an ordinary tick with identical state sends nothing
		// (unchanged hash) and, even if it changed, the 120s debounce would hold it.
		plugin.syncTask();
		assertNull("sanity: hash gate + debounce block the scheduled tick",
			server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals(1, server.getRequestCount());

		// Force-send with the SAME unchanged state must override both gates and send immediately.
		plugin.forceSendSnapshot();
		assertNotNull("force must send despite the unchanged-hash gate and the debounce",
			server.takeRequest(5, TimeUnit.SECONDS));
		assertEquals(2, server.getRequestCount());
	}

	@Test
	public void forceSendStillHonorsActiveServerBackoff() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 1;
		plugin.backoffUntilMillis = System.currentTimeMillis() + 60_000L;	// server 429 said stop
		setSkillXp(1000);

		plugin.forceSendSnapshot();
		assertNull("force must NOT override an active server-imposed 429 backoff",
			server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals(0, server.getRequestCount());
	}

	// ---- FIX 3: opening the bank / collection log forces an immediate send (no debounce wait) ----

	@Test
	public void bankOpenForcesImmediateSend() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 120_000L;	// without capture-on-open, the bank would wait this long
		server.enqueue(new MockResponse().setResponseCode(200));
		setSkillXp(1000);

		plugin.handleCaptureOnOpenWidgetLoaded(12);	// BANKMAIN (verified via javap)

		assertNotNull("opening the bank must sync immediately", server.takeRequest(5, TimeUnit.SECONDS));
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void collectionLogOpenForcesImmediateSend() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 120_000L;
		server.enqueue(new MockResponse().setResponseCode(200));
		setSkillXp(1000);

		plugin.handleCaptureOnOpenWidgetLoaded(621);	// COLLECTION (verified via javap)

		assertNotNull("opening the collection log must sync immediately",
			server.takeRequest(5, TimeUnit.SECONDS));
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void unrelatedWidgetOpenDoesNotForceSend() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 120_000L;
		setSkillXp(1000);

		plugin.handleCaptureOnOpenWidgetLoaded(161);	// an unrelated interface group

		assertNull("an unrelated widget open must not trigger a send",
			server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals(0, server.getRequestCount());
	}
}
