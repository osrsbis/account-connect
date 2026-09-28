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

	private static AccountConnectPlugin withToken(String token) throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		Field field = AccountConnectPlugin.class.getDeclaredField("config");
		field.setAccessible(true);
		field.set(p, new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return token;
			}
		});
		return p;
	}

	/** The gate is the link token alone: a valid token allows uploads. */
	@Test
	public void aValidTokenAllowsUploads() throws Exception
	{
		assertTrue("a valid token must allow uploads", withToken(TOKEN).uploadAllowed());
	}

	/** No valid token, no uploads. */
	@Test
	public void noValidTokenAllowsNoUploads() throws Exception
	{
		for (String bad : new String[]{"", "   ", "not-a-token", "0123456789abcdef", null})
		{
			assertFalse("token '" + bad + "' must not allow uploads", withToken(bad).uploadAllowed());
		}
	}

	/** The settings panel shows exactly two items: the link token and the API base URL. */
	@Test
	public void configDeclaresExactlyLinkTokenAndApiBaseUrl()
	{
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (java.lang.reflect.Method m : AccountConnectConfig.class.getDeclaredMethods())
		{
			net.runelite.client.config.ConfigItem item =
				m.getAnnotation(net.runelite.client.config.ConfigItem.class);
			if (item != null)
			{
				keys.add(item.keyName());
			}
		}
		assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("linkToken", "apiBaseUrl")), keys);
	}

	/** The approved linkToken description: the 0.7.13 text with the two upload-switch phrases removed. */
	@Test
	public void linkTokenDescriptionIsTheApprovedText() throws Exception
	{
		String approved =
			"Paste the token from osrsbestinslot.com (Connect account) to link this client. This "
			+ "uploads YOUR OWN account to osrsbestinslot.com, a "
			+ "3rd-party server not controlled or verified by the RuneLite developers: your display "
			+ "name, account hash, account type, current world and location, skills, total and combat "
			+ "level, quests, achievement diaries, combat achievements, slayer task, collection log, "
			+ "equipment, inventory, bank, rune pouch, seed vault, Grand Exchange offers, wealth, "
			+ "spellbook, attack style, active prayers, Kourend favour and minigame points. In a Group "
			+ "Ironman group it also uploads your shared group storage, which can include items other "
			+ "members deposited. It also uploads your account activity: Grand Exchange and "
			+ "general-store buys and sells, completed trades INCLUDING the other player's name and the "
			+ "items each side exchanged, items you loot from kills and from reward chests (raids, "
			+ "Barrows, clue caskets and similar), items you drop, pick up or alch, deaths, level-ups, "
			+ "and login and logout times. It also uploads screenshots as delivery proof: your trade "
			+ "confirmation window when a trade completes, which shows the other player's name and the "
			+ "items traded, and a short series of your game screen while a shop window is open, which "
			+ "may include on-screen chat and other players' names (discarded if the visit had no "
			+ "purchase or sale). Your IP address reaches the server with every upload. Clear the "
			+ "token to stop all of it.";
		net.runelite.client.config.ConfigItem item = AccountConnectConfig.class
			.getDeclaredMethod("linkToken").getAnnotation(net.runelite.client.config.ConfigItem.class);
		assertEquals(approved, item.description());
		assertEquals("", item.warning());
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

	// ================================================================
	// ROUND 4, FINDING C — THE POLICY HEARTBEAT
	//
	// applyServerPolicy has exactly one caller, inside the postSnapshot response callback, and
	// postSnapshot's four callers are all snapshot sends. So an idle logged-in client whose
	// canonical hash is stable had NO policy channel at all, and X-Drop-Proof: off could not reach
	// it. ops/ACTIVATION.md A0d claimed it stops "on that poll", which was a poll the client did
	// not make.
	//
	// The heartbeat is scoped to clients that HOLD the grant, so no ordinary user pays for it.
	// ================================================================

	@Test
	public void anIdleGrantedClientStillPollsSoARevocationCanReachIt() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 1;
		plugin.setStoreToolsForTest(true);
		plugin.setDropProofRolloutForTest(true);
		assertTrue("the rig holds the grant", plugin.dropProofEnabled());

		server.enqueue(new MockResponse().setResponseCode(200));
		setSkillXp(1000);
		plugin.syncTask();
		assertNotNull("the first tick uploads", server.takeRequest(5, TimeUnit.SECONDS));
		awaitUploadedHash(plugin.lastBuiltHash, 5000);

		// Idle: the state has not changed, so the hash gate holds the send.
		plugin.syncTask();
		assertNull("an unchanged tick inside the heartbeat window must not send",
			server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals(1, server.getRequestCount());

		// The heartbeat window elapses. Nothing about the player has changed.
		plugin.lastSendMillis =
			System.currentTimeMillis() - AccountConnectPlugin.DROP_PROOF_POLICY_HEARTBEAT_MILLIS - 1L;

		server.enqueue(new MockResponse().setResponseCode(200).setHeader("X-Drop-Proof", "off"));
		plugin.syncTask();
		assertNotNull("FINDING C: a granted idle client must poll once the window elapses",
			server.takeRequest(5, TimeUnit.SECONDS));
		assertEquals(2, server.getRequestCount());

		// And the revocation the poll carried must actually land.
		long deadline = System.currentTimeMillis() + 5000L;
		while (System.currentTimeMillis() < deadline && plugin.dropProofEnabled())
		{
			Thread.sleep(10);
		}
		assertFalse("and the revocation it carried must revoke the grant",
			plugin.dropProofEnabled());
	}

	/**
	 * THE COST CONTROL. A client with no grant has nothing to revoke, so it must get no extra
	 * traffic at all. Without this arm the heartbeat could quietly become a clock firehose against
	 * the server's 150 requests per hour per token.
	 */
	@Test
	public void anIdleClientWithoutTheGrantNeverHeartbeats() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 1;
		assertFalse("the rig holds no grant", plugin.dropProofEnabled());

		server.enqueue(new MockResponse().setResponseCode(200));
		setSkillXp(1000);
		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		awaitUploadedHash(plugin.lastBuiltHash, 5000);

		plugin.lastSendMillis =
			System.currentTimeMillis() - AccountConnectPlugin.DROP_PROOF_POLICY_HEARTBEAT_MILLIS * 10L;
		plugin.syncTask();
		plugin.syncTask();
		plugin.syncTask();
		assertNull("an ungranted client must never pay for the heartbeat",
			server.takeRequest(500, TimeUnit.MILLISECONDS));
		assertEquals(1, server.getRequestCount());
	}

	/** The heartbeat must not defeat the 429 backoff: the server explicitly said stop. */
	@Test
	public void theHeartbeatStillHonoursA429Backoff() throws Exception
	{
		buildPlugin();
		plugin.minUploadIntervalMillis = 1;
		plugin.setStoreToolsForTest(true);
		plugin.setDropProofRolloutForTest(true);

		server.enqueue(new MockResponse().setResponseCode(200));
		setSkillXp(1000);
		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		awaitUploadedHash(plugin.lastBuiltHash, 5000);

		plugin.backoffUntilMillis = System.currentTimeMillis() + 60_000L;
		plugin.lastSendMillis =
			System.currentTimeMillis() - AccountConnectPlugin.DROP_PROOF_POLICY_HEARTBEAT_MILLIS - 1L;
		plugin.syncTask();
		assertNull("a heartbeat must never override an active backoff",
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
		when(itemManager.getItemPrice(995)).thenReturn(1L);
		setSkillXp(1000);

		plugin.syncTask();
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		awaitUploadedHash(plugin.lastBuiltHash, 5000);

		// force captured_at to cross a second boundary and jitter the GE price (wealth) —
		// both are excluded from the canonical hash, so no re-upload should occur.
		Thread.sleep(1100);
		when(itemManager.getItemPrice(995)).thenReturn(999L);

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
				return TOKEN;	// the token IS the gate now (the opt-in toggle was removed 2026-09-02)
			}
		});
		assertTrue("linked + no server override -> enabled", plugin.screenshotsEnabled());

		plugin.applyServerPolicy(policyResponse("X-Screenshots", "off"));
		assertFalse("server force-disable overrides the local link", plugin.screenshotsEnabled());

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
