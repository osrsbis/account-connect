package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The staff-only durable event spool, driven through the plugin with a real MockWebServer.
 *
 * <p>The server is mocked in its FINAL ack contract: a 2xx carries {@code results} with one entry per
 * event_id and a status of stored, duplicate or filtered. The current server shape
 * ({@code {"ok":true,"stored":N}}) is also mocked, and must delete nothing durable.
 */
public class EventSpoolPluginTest
{
	static final String TOKEN_A = "0123456789abcdef0123456789abcdef";
	static final String TOKEN_B = "fedcba9876543210fedcba9876543210";

	private MockWebServer server;
	private Path pluginData;
	private final List<AccountConnectPlugin> plugins = new ArrayList<>();
	/** Every request body the server received, in order. */
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	/** Scripted replies; when empty the server answers with the default. */
	private final LinkedBlockingQueue<Function<String, MockResponse>> script = new LinkedBlockingQueue<>();
	private volatile Function<String, MockResponse> fallback = EventSpoolPluginTest::ackAll;
	private final List<Runnable> onRequest = new CopyOnWriteArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		pluginData = Files.createTempDirectory("spool-plugin");
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				String body = request.getBody().readUtf8();
				bodies.add(body);
				for (Runnable r : onRequest)
				{
					r.run();
				}
				Function<String, MockResponse> f = script.poll();
				return (f == null ? fallback : f).apply(body);
			}
		});
		server.start();
	}

	@After
	public void tearDown() throws Exception
	{
		for (AccountConnectPlugin p : plugins)
		{
			SpoolHooks.crash(p.spooler);
		}
		server.shutdown();
		EventSpoolCodecTest.deleteTree(pluginData);
	}

	// ------------------------------------------------------------------ harness

	private AccountConnectPlugin plugin(String token, String accountHash) throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "gson", new Gson());
		inject(p, "okHttpClient", new OkHttpClient.Builder()
			.connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build());
		setToken(p, token);
		if (accountHash != null)
		{
			inject(p, "activeHash", accountHash);
			inject(p, "activeRsn", "Staff One");
		}
		SpoolHooks.dataRoot(p.spooler,pluginData);
		plugins.add(p);
		return p;
	}

	private void setToken(AccountConnectPlugin p, String token) throws Exception
	{
		AccountConnectConfig config = mock(AccountConnectConfig.class);
		when(config.linkToken()).thenReturn(token);
		when(config.apiBaseUrl()).thenReturn(server.url("/api").toString().replaceAll("/+$", ""));
		inject(p, "config", config);
	}

	static void inject(Object target, String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Response policy(String... kv)
	{
		Response.Builder b = new Response.Builder()
			.request(new Request.Builder().url("http://localhost/").build())
			.protocol(Protocol.HTTP_1_1).code(200).message("OK");
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			b.header(kv[i], kv[i + 1]);
		}
		return b.build();
	}

	private void grant(AccountConnectPlugin p) throws Exception
	{
		p.grants.applyServerPolicy(policy("X-Event-Spool", "on"));
		SpoolHooks.awaitIdle(p.spooler);
	}

	private Map<String, Object> fields(String k, Object v)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(k, v);
		return m;
	}

	private void emit(AccountConnectPlugin p, String type) throws Exception
	{
		p.events.emitEvent(type, fields("item", "Abyssal whip"));
		SpoolHooks.awaitIdle(p.spooler);
	}

	private Path spoolRoot()
	{
		return pluginData.resolve("osrsbis").resolve("spool");
	}

	private Path identityDir(String token, String hash)
	{
		return spoolRoot().resolve(EventSpool.identityKey(token, hash));
	}

	private List<Path> allPaths() throws Exception
	{
		try (Stream<Path> s = Files.walk(pluginData))
		{
			return s.filter(x -> !x.equals(pluginData)).collect(Collectors.toList());
		}
	}

	private String allDiskText() throws Exception
	{
		StringBuilder sb = new StringBuilder();
		for (Path p : allPaths())
		{
			sb.append(p.toString()).append('\n');
			if (Files.isRegularFile(p))
			{
				sb.append(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1)).append('\n');
			}
		}
		return sb.toString();
	}

	static List<String> idsIn(String body)
	{
		List<String> out = new ArrayList<>();
		JsonObject o = new JsonParser().parse(body).getAsJsonObject();
		if (!o.has("events"))
		{
			return out;
		}
		for (JsonElement e : o.getAsJsonArray("events"))
		{
			JsonElement id = e.getAsJsonObject().get("event_id");
			out.add(id == null ? null : id.getAsString());
		}
		return out;
	}

	private List<String> postedIds()
	{
		List<String> out = new ArrayList<>();
		for (String b : bodies)
		{
			out.addAll(idsIn(b));
		}
		return out;
	}

	private static List<String> pendingIds(AccountConnectPlugin p)
	{
		try
		{
			return SpoolHooks.pendingIds(p.spooler);
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private List<String> lastMemoryIds(AccountConnectPlugin p)
	{
		List<String> out = new ArrayList<>();
		synchronized (p.events.pendingEvents)
		{
			for (Map<String, Object> ev : p.events.pendingEvents)
			{
				out.add(String.valueOf(ev.get("event_id")));
			}
		}
		return out;
	}

	static MockResponse ackAll(String body)
	{
		return results(body, "stored");
	}

	static MockResponse results(String body, String status)
	{
		JsonArray arr = new JsonArray();
		for (String id : idsIn(body))
		{
			JsonObject r = new JsonObject();
			r.addProperty("event_id", id);
			r.addProperty("status", status);
			arr.add(r);
		}
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.add("results", arr);
		return new MockResponse().setResponseCode(200).setBody(o.toString());
	}

	static MockResponse legacyStored(String body)
	{
		return new MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"stored\":" + idsIn(body).size() + "}");
	}

	private void flushAndWait(AccountConnectPlugin p, int expectedRequests) throws Exception
	{
		p.events.flushEvents();
		waitFor(() -> bodies.size() >= expectedRequests && !p.events.eventPostInFlight, "memory flush settled");
		SpoolHooks.awaitIdle(p.spooler);
	}

	/** The pump stages the durable rows the sender does not hold; the sender sends them. Backoffs are skipped. */
	private void replayAndWait(AccountConnectPlugin p, int expectedRequests) throws Exception
	{
		p.spooler.spoolReplayBackoffUntilMs = 0L;
		p.events.eventRetryBackoffUntilMs = 0L;
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		p.events.flushEvents();
		waitFor(() -> bodies.size() >= expectedRequests && !p.events.eventPostInFlight, "replay settled");
		SpoolHooks.awaitIdle(p.spooler);
	}

	private void releaseToReplay(AccountConnectPlugin p) throws Exception
	{
		// The current server shape: a 2xx with no per-id results. The memory copy is done; the durable
		// row stays and the pump stages it again.
		script.add(EventSpoolPluginTest::legacyStored);
		flushAndWait(p, bodies.size() + 1);
	}

	// ------------------------------------------------------------------ gate: public never touches disk

	@Test
	public void publicTokenNeverCreatesAnySpoolFileOrDirectoryAcrossEmitFlush5xxAndRestart() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		p.grants.applyServerPolicy(policy("X-Max-Capture", "on", "X-Drop-Proof", "on"));	// other grants are not the spool
		emit(p, "trade");
		emit(p, "drop");
		script.add(b -> new MockResponse().setResponseCode(503));
		flushAndWait(p, 1);
		p.events.eventRetryBackoffUntilMs = 0L;
		flushAndWait(p, 2);
		p.spooler.pumpSpool();
		p.grants.applyServerPolicy(policy("X-Event-Spool", "off"));
		emit(p, "trade");
		flushAndWait(p, 3);
		SpoolHooks.awaitIdle(p.spooler);
		SpoolHooks.crash(p.spooler);

		AccountConnectPlugin restarted = plugin(TOKEN_A, "12345");
		restarted.startUp();
		emit(restarted, "login");
		flushAndWait(restarted, 4);
		SpoolHooks.awaitIdle(restarted.spooler);

		assertEquals("no file, no directory, no pointer for a non-staff token: " + allPaths(),
			Collections.emptyList(), allPaths());
		assertFalse((boolean) restarted.events.captureHealthSnapshot().get("spool_active"));
	}

	@Test
	public void eventIdIsGeneratedOnceAtEmitAndSentOnTheWireForPublicTokensToo() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		emit(p, "trade");
		script.add(b -> new MockResponse().setResponseCode(503));
		flushAndWait(p, 1);
		p.events.eventRetryBackoffUntilMs = 0L;
		flushAndWait(p, 2);
		List<String> first = idsIn(bodies.get(0));
		List<String> second = idsIn(bodies.get(1));
		assertEquals(1, first.size());
		assertTrue(first.get(0).matches("^[0-9a-f]{32}$"));
		assertEquals("stable across retries", first, second);
		assertFalse(bodies.get(1).contains("__"));
	}

	// ------------------------------------------------------------------ disk content

	@Test
	public void rawTokenAndInternalBindingNeverReachDisk() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "login");
		emit(p, "trade");
		emit(p, "drop");
		String disk = allDiskText();
		assertTrue("the spool wrote something", disk.contains("Abyssal whip"));
		assertFalse("raw token in a file or path", disk.contains(TOKEN_A));
		assertFalse(disk.contains("__delivery_token_fp"));
		assertFalse(disk.contains(EventSpool.sha256Hex("event-token:" + TOKEN_A)));
		assertFalse(disk.contains("__spool"));
		assertTrue(Files.isDirectory(identityDir(TOKEN_A, "12345")));
		assertEquals(Collections.singletonList(EventSpool.identityKey(TOKEN_A, "12345")), EventSpool.identities(spoolRoot()));
		assertFalse("no identity index is written", Files.exists(spoolRoot().resolve("active.json")));
		assertFalse(Files.exists(spoolRoot().resolve("index.lock")));
	}

	@Test
	public void onlyAllowlistedTypesReachDisk() throws Exception
	{
		assertEquals(new HashSet<>(Arrays.asList("trade", "ge_offer", "ge_progress", "ge_buy", "ge_sell", "ge_cancel",
			"ge_collect", "store_buy", "store_sell", "store_taken", "drop", "pickup", "ground_removed", "bank_session",
			"login", "logout", "world_hop", "drop_trade_clip", "drop_trade_clip_final", "death",
			"bank_visit_open", "bank_visit_manifest_chunk", "bank_visit_close")),
			SpoolDriver.SPOOL_ALLOWLIST);

		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		for (String t : Arrays.asList("fh_batch", "chat", "region", "xp_gain", "equip_change", "loot", "level_up", "alch"))
		{
			p.events.emitEvent(t, fields("marker", "MEMONLY_" + t));
		}
		p.events.emitEvent("trade", fields("given", Arrays.asList(fields("id", 995))));
		SpoolHooks.awaitIdle(p.spooler);
		String disk = allDiskText();
		assertTrue(disk.contains(pendingIds(p).get(0)));
		assertFalse("memory-only types never touch disk", disk.contains("MEMONLY_"));
		assertEquals(1, pendingIds(p).size());
	}

	// ------------------------------------------------------------------ identity changes

	@Test
	public void tokenChangePurgesTheOldIdentityAndItsRowsNeverPostUnderTheNewToken() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		String aId = pendingIds(p).get(0);
		assertTrue(Files.isDirectory(identityDir(TOKEN_A, "12345")));

		setToken(p, TOKEN_B);
		p.onConfigChanged(null);
		SpoolHooks.awaitIdle(p.spooler);
		assertFalse("A purged at the token change", Files.exists(identityDir(TOKEN_A, "12345")));
		assertEquals("no identity of A is left", Collections.emptyList(), EventSpool.identities(spoolRoot()));

		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		replayAndWait(p, bodies.size() + 1);
		for (String b : bodies)
		{
			if (b.contains(TOKEN_B))
			{
				assertFalse("an A row posted under B", idsIn(b).contains(aId));
			}
		}
	}

	@Test
	public void tokenClearPurgesBeforeAnyFurtherSend() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		int before = bodies.size();
		setToken(p, "");
		p.onConfigChanged(null);
		SpoolHooks.awaitIdle(p.spooler);
		assertFalse(Files.exists(identityDir(TOKEN_A, "12345")));
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		p.events.flushEvents();
		Thread.sleep(300);
		assertEquals("nothing sent after the clear", before, bodies.size());
	}

	@Test
	public void accountSwitchOnTheSameTokenUsesASeparateShardAndNeverCrossReplays() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "111");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		String acct1 = pendingIds(p).get(0);

		inject(p, "activeHash", "222");
		p.spooler.syncSpoolState();
		SpoolHooks.awaitIdle(p.spooler);
		assertFalse("the previous account's identity is purged", Files.exists(identityDir(TOKEN_A, "111")));
		emit(p, "trade");
		assertTrue(Files.isDirectory(identityDir(TOKEN_A, "222")));
		releaseToReplay(p);
		String acct2 = pendingIds(p).get(0);
		int before = bodies.size();
		replayAndWait(p, before + 1);
		List<String> replayed = idsIn(bodies.get(before));
		assertEquals(Collections.singletonList(acct2), replayed);
		for (int i = before; i < bodies.size(); i++)
		{
			assertFalse(idsIn(bodies.get(i)).contains(acct1));
		}
	}

	@Test
	public void spoolHeaderOffPurgesTheCurrentIdentityBeforeAnyFurtherSend() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		int before = bodies.size();
		p.grants.applyServerPolicy(policy("X-Event-Spool", "off"));
		SpoolHooks.awaitIdle(p.spooler);
		assertFalse(Files.exists(identityDir(TOKEN_A, "12345")));
		assertEquals(Collections.emptyList(), EventSpool.identities(spoolRoot()));
		replayAndWaitNoRequest(p, before);
		emit(p, "trade");
		assertFalse("no writes after off", Files.exists(identityDir(TOKEN_A, "12345")));
		assertEquals(1L, p.events.captureHealthSnapshot().get("spool_purged_total"));
	}

	@Test
	public void absentHeaderLeavesTheGrantUnchanged() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		p.grants.applyServerPolicy(policy("X-Sync-Interval", "30"));
		SpoolHooks.awaitIdle(p.spooler);
		emit(p, "trade");
		assertEquals(1, pendingIds(p).size());
	}

	@Test
	public void revocation403PurgesTheIdentity() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		script.add(b -> new MockResponse().setResponseCode(403).setBody("{\"ok\":false}"));
		replayAndWait(p, bodies.size() + 1);
		waitFor(() -> !Files.exists(identityDir(TOKEN_A, "12345")), "purged after 403");
		int before = bodies.size();
		replayAndWaitNoRequest(p, before);
	}

	@Test
	public void operatorPauseRetainsEveryRowAndResumesReplay() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		p.grants.applyServerPolicy(policy("X-Uploads-Enabled", "false"));
		SpoolHooks.awaitIdle(p.spooler);
		emit(p, "drop");	// writes continue during a pause
		releaseToReplay(p);
		assertEquals(2, pendingIds(p).size());
		int before = bodies.size();
		replayAndWaitNoRequest(p, before);
		assertTrue("nothing purged by a pause", Files.isDirectory(identityDir(TOKEN_A, "12345")));
		assertEquals(2, pendingIds(p).size());

		p.grants.applyServerPolicy(policy("X-Uploads-Enabled", "true"));
		SpoolHooks.awaitIdle(p.spooler);
		replayAndWait(p, before + 1);
		assertEquals(2, idsIn(bodies.get(before)).size());
		waitFor(() -> pendingIds(p).isEmpty(), "acked after resume");
	}

	// ------------------------------------------------------------------ ack contract

	@Test
	public void twoXxWithoutResultsDeletesNothingAndCounts() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		emit(p, "drop");
		releaseToReplay(p);
		assertEquals(2, pendingIds(p).size());
		assertEquals(2L, p.events.captureHealthSnapshot().get("spool_unacked_2xx_total"));
		script.add(EventSpoolPluginTest::legacyStored);
		replayAndWait(p, bodies.size() + 1);
		assertEquals("aggregate stored:N deletes nothing", 2, pendingIds(p).size());
		assertTrue("a 2xx without results backs replay off", p.spooler.spoolReplayBackoffUntilMs > p.nowMs());
	}

	@Test
	public void resultsDeleteOnlyTheListedIdsWithAKnownStatus() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		for (int i = 0; i < 5; i++)
		{
			emit(p, "trade");
		}
		releaseToReplay(p);
		List<String> ids = pendingIds(p);
		assertEquals(5, ids.size());
		script.add(b -> {
			JsonArray arr = new JsonArray();
			arr.add(result(ids.get(0), "stored"));
			arr.add(result(ids.get(1), "duplicate"));
			arr.add(result(ids.get(2), "filtered"));
			arr.add(result(ids.get(3), "weird"));		// unknown status: keep
			arr.add(result("ffffffffffffffffffffffffffffffff", "stored"));	// not in this batch
			JsonObject bad = new JsonObject();
			bad.addProperty("status", "stored");		// malformed: no id
			arr.add(bad);
			JsonObject o = new JsonObject();
			o.addProperty("ok", true);
			o.add("results", arr);
			return new MockResponse().setResponseCode(200).setBody(o.toString());
		});
		replayAndWait(p, bodies.size() + 1);
		assertEquals(Arrays.asList(ids.get(3), ids.get(4)), pendingIds(p));
		Map<String, Object> h = p.events.captureHealthSnapshot();
		assertEquals(3L, h.get("spool_acked_total"));
		assertEquals(1L, h.get("spool_filtered_total"));
		assertNotNull(h.get("last_spool_ack_at"));
	}

	@Test
	public void nonArrayResultsDeleteNothing() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		script.add(b -> new MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"results\":{\"stored\":1}}"));
		replayAndWait(p, bodies.size() + 1);
		assertEquals(1, pendingIds(p).size());
		script.add(b -> new MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"results\":[\"" + idsIn(b).get(0) + "\"]}"));
		replayAndWait(p, bodies.size() + 1);
		assertEquals(1, pendingIds(p).size());
	}

	@Test
	public void memoryPathResultsAckTheDurableRowDirectly() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		assertEquals(1, pendingIds(p).size());
		flushAndWait(p, 1);	// default reply: per-id stored
		waitFor(() -> pendingIds(p).isEmpty(), "acked through the real-time path");
		int before = bodies.size();
		replayAndWaitNoRequest(p, before);
	}

	private void replayRetryAfterIsBounded(int code, String seconds, long expectedDelay) throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		script.add(b -> new MockResponse().setResponseCode(code).setHeader("Retry-After", seconds));
		replayAndWait(p, bodies.size() + 1);
		assertEquals(1, pendingIds(p).size());
		assertEquals("respect the bounded server delay", expectedDelay, p.events.eventRetryBackoffMs);
		assertTrue("the row waits in the sender, not sent again before the delay", p.events.eventRetryBackoffUntilMs > p.nowMs());
	}

	@Test public void transient404ReplayHonorsRetryAfter() throws Exception
	{
		replayRetryAfterIsBounded(404, "120", 120000L);
	}
	@Test public void transient408ReplayHonorsRetryAfter() throws Exception
	{
		replayRetryAfterIsBounded(408, "120", 120000L);
	}
	@Test public void transient503ReplayHonorsRetryAfter() throws Exception
	{
		replayRetryAfterIsBounded(503, "120", 120000L);
	}
	@Test public void huge429RetryAfterCannotOverflowBeforeCapping() throws Exception
	{
		replayRetryAfterIsBounded(429, Long.toString(Long.MAX_VALUE), 300000L);
	}

	private void transientReplayRetainsPayload(int code) throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		List<String> original = pendingIds(p);
		int before = bodies.size();
		script.add(b -> new MockResponse().setResponseCode(code).setBody("<html>temporary route failure</html>"));
		if (code == 408) script.add(b -> new MockResponse().setResponseCode(code).setBody("request timeout"));
		replayAndWait(p, before + (code == 408 ? 2 : 1));
		assertEquals("transient response must retain the durable row", original, pendingIds(p));
		assertEquals(0L, p.events.captureHealthSnapshot().get("spool_quarantined_total"));
		assertTrue("retry remains bounded", p.events.eventRetryBackoffUntilMs > 0);
		replayAndWait(p, bodies.size() + 1);
		assertTrue("a later per-ID receipt settles the original row", pendingIds(p).isEmpty());
		assertEquals(original, idsIn(bodies.get(bodies.size() - 1)));
	}

	@Test
	public void transient408ReplayRetainsPayload() throws Exception
	{
		transientReplayRetainsPayload(408);
	}

	@Test
	public void provider404ReplayRetainsPayload() throws Exception
	{
		transientReplayRetainsPayload(404);
	}

	@Test
	public void otherClientErrorQuarantinesMetadataAndNeverRetries() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		script.add(b -> new MockResponse().setResponseCode(400));
		replayAndWait(p, bodies.size() + 1);
		waitFor(() -> pendingIds(p).isEmpty(), "quarantined");
		assertEquals(1L, p.events.captureHealthSnapshot().get("spool_quarantined_total"));
		int before = bodies.size();
		replayAndWaitNoRequest(p, before);
	}

	// ------------------------------------------------------------------ pacing, splitting, ordering

	@Test
	public void requestsSplitAt100EventsAndUnder256KiB() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		for (int i = 0; i < 150; i++)
		{
			p.events.emitEvent("ge_offer", fields("slot", i % 8));
		}
		flushAndWait(p, 1);
		assertEquals(100, idsIn(bodies.get(0)).size());
		flushAndWait(p, 2);
		assertEquals(50, idsIn(bodies.get(1)).size());

		List<Map<String,Object>> big = new ArrayList<>();
		for (int i = 0; i < 4_000; i++)
		{
			Map<String,Object> item = fields("id", 10000 + i);
			item.put("qty", 100000);
			big.add(item);
		}
		for (int i = 0; i < 3; i++)
		{
			p.events.emitEvent("bank_visit_manifest_chunk", fields("items", big));
		}
		flushAndWait(p, 3);
		assertEquals(2, idsIn(bodies.get(2)).size());
		assertTrue(bodies.get(2).getBytes(StandardCharsets.UTF_8).length < 256 * 1024);
		flushAndWait(p, 4);
		assertEquals(1, idsIn(bodies.get(3)).size());
	}

	/**
	 * Rewritten for the one sender (was: replaySplitsAt100AndIsPacedAndStopsOn429). Same guarantees: durable rows go
	 * in requests of at most 100, a 429 keeps every row and nothing is sent again before its Retry-After, and each row
	 * is then acked once. The old 5 s pacing of a second sender is gone with that sender.
	 */
	@Test
	public void durableRowsGoInRequestsOfAtMost100AndA429HoldsThemUntilRetryAfter() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		for (int i = 0; i < 150; i++)
		{
			p.events.emitEvent("trade", fields("n", i));
		}
		SpoolHooks.awaitIdle(p.spooler);
		fallback = EventSpoolPluginTest::legacyStored;
		flushAndWait(p, 1);
		flushAndWait(p, 2);
		assertEquals(150, pendingIds(p).size());
		List<String> all = pendingIds(p);
		fallback = EventSpoolPluginTest::ackAll;
		int before = bodies.size();

		script.add(b -> new MockResponse().setResponseCode(429).setHeader("Retry-After", "60"));
		replayAndWait(p, before + 1);
		assertEquals(all.subList(0, 100), idsIn(bodies.get(before)));
		assertTrue("Retry-After honoured", p.events.eventRetryBackoffUntilMs - p.nowMs() > 50_000L);

		p.spooler.spoolReplayBackoffUntilMs = 0L;
		p.spooler.pumpSpool();					// stages the other 50 behind the held 100
		SpoolHooks.awaitIdle(p.spooler);
		p.events.flushEvents();
		Thread.sleep(300);
		assertEquals("nothing is sent while the 429 delay runs", before + 1, bodies.size());
		assertEquals(150, lastMemoryIds(p).size());
		assertEquals("every row kept", 150, pendingIds(p).size());

		p.events.eventRetryBackoffUntilMs = 0L;
		flushAndWait(p, before + 2);
		assertEquals(all.subList(0, 100), idsIn(bodies.get(before + 1)));
		flushAndWait(p, before + 3);
		assertEquals(all.subList(100, 150), idsIn(bodies.get(before + 2)));
		waitFor(() -> pendingIds(p).isEmpty(), "every row acked");
	}

	/**
	 * Rewritten for the one sender (was: recoveredRowsReplayAheadOfNewRowsAndNoIdIsInFlightTwice). Same guarantees:
	 * recovered rows go ahead of rows emitted after them, and an id the sender holds (staged or in flight) is never
	 * staged a second time.
	 */
	@Test
	public void recoveredRowsGoAheadOfNewRowsAndNoIdIsStagedOrInFlightTwice() throws Exception
	{
		AccountConnectPlugin first = plugin(TOKEN_A, "12345");
		grant(first);
		emit(first, "trade");
		emit(first, "drop");
		List<String> old = new ArrayList<>(pendingIds(first));
		SpoolHooks.crash(first.spooler);

		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		emit(p, "trade");
		List<String> expect = new ArrayList<>(old);
		expect.add(lastMemoryIds(p).get(2));
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals("recovered first, each id once", expect, lastMemoryIds(p));
		assertEquals("nothing the sender holds is a candidate", Collections.emptyList(), SpoolHooks.replayCandidates(p.spooler));

		java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
		script.add(b -> {
			try
			{
				hold.await(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException ignored)
			{
			}
			return ackAll(b);
		});
		p.events.flushEvents();
		waitFor(() -> bodies.size() == 1, "in flight");
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals("an in-flight id is not staged again", Collections.emptyList(), lastMemoryIds(p));
		hold.countDown();
		waitFor(() -> !p.events.eventPostInFlight, "answered");
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals(expect, idsIn(bodies.get(0)));
		assertTrue(pendingIds(p).isEmpty());
		replayAndWaitNoRequest(p, 1);
	}

	// ------------------------------------------------------------------ cold start + restarts

	@Test
	public void coldStartNeitherReplaysNorDeletesUntilTheHeaderThenReplaysOnlyThisIdentity() throws Exception
	{
		// A fresh idle foreign leftover. Every identity directory is listed, so under D2 it is purged at this
		// identity's first grant, before anything is replayed.
		EventSpool q = new EventSpool(pluginData, identityDir(TOKEN_B, "999"), System::currentTimeMillis,
			EventSpool.ATOMIC, EventSpool.Limits.DEFAULT, new EventSpool.Counters());
		String qId = EventSpool.newEventId();
		assertTrue(q.append(qId, "trade", System.currentTimeMillis(), "{\"type\":\"trade\",\"event_id\":\"" + qId + "\"}"));
		List<String> qIds = Collections.singletonList(qId);
		q.crashForTest();
		AccountConnectPlugin a = plugin(TOKEN_A, "12345");
		grant(a);
		emit(a, "trade");
		List<String> aIds = new ArrayList<>(pendingIds(a));
		SpoolHooks.crash(a.spooler);
		String before = treeDigest();

		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		p.startUp();
		emit(p, "trade");		// not active yet: memory only
		String memoryOnly = lastMemoryIds(p).get(0);
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals("no replay, no delete, no write before the header", before, treeDigest());
		assertEquals(0, bodies.size());

		grant(p);
		replayAndWait(p, 1);
		// One sender: the memory-only row and the recovered rows share the request.
		List<String> expect = new ArrayList<>(Collections.singletonList(memoryOnly));
		expect.addAll(aIds);
		assertEquals(expect, idsIn(bodies.get(0)));
		for (String b : bodies)
		{
			for (String id : qIds)
			{
				assertFalse("a foreign identity is never replayed", idsIn(b).contains(id));
			}
		}
		assertFalse("an idle foreign identity is purged at the grant, never replayed", Files.exists(identityDir(TOKEN_B, "999")));
	}

	@Test
	public void restartAToBWithNoATokenPurgesAAtBsFirstGrantBeforeAnyReplay() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "111");
		grant(a);
		emit(a, "trade");
		emit(a, "drop");
		Set<String> aIds = new HashSet<>(pendingIds(a));
		SpoolHooks.crash(a.spooler);
		assertEquals(Collections.singletonList(EventSpool.identityKey(TOKEN_A, "111")), EventSpool.identities(spoolRoot()));
		Path aDir = identityDir(TOKEN_A, "111");
		List<Boolean> aDirAtFirstRequest = new CopyOnWriteArrayList<>();
		onRequest.add(() -> aDirAtFirstRequest.add(Files.exists(aDir)));

		AccountConnectPlugin b = plugin(TOKEN_B, "222");
		b.startUp();
		grant(b);
		assertFalse("A purged at B's first grant", Files.exists(aDir));
		emit(b, "trade");
		releaseToReplay(b);
		replayAndWait(b, bodies.size() + 1);
		assertFalse(aDirAtFirstRequest.isEmpty());
		assertFalse("A was still on disk when B first sent", aDirAtFirstRequest.get(0));
		for (String id : postedIds())
		{
			assertFalse("an A row was posted", aIds.contains(id));
		}
		assertEquals(Collections.singletonList(EventSpool.identityKey(TOKEN_B, "222")), EventSpool.identities(spoolRoot()));
	}

	@Test
	public void twoProcessesSameIdentityEachWriteTheirOwnShardAndRecoverOnlyFreeOnes() throws Exception
	{
		AccountConnectPlugin p1 = plugin(TOKEN_A, "12345");
		grant(p1);
		emit(p1, "trade");
		AccountConnectPlugin p2 = plugin(TOKEN_A, "12345");
		grant(p2);
		emit(p2, "drop");
		assertEquals("p2 did not take p1's live rows", 1, pendingIds(p2).size());
		try (Stream<Path> s = Files.list(identityDir(TOKEN_A, "12345")))
		{
			assertEquals(2, s.filter(x -> x.toString().endsWith(".spl")).count());
		}
		String p1Row = pendingIds(p1).get(0);
		SpoolHooks.crash(p1.spooler);
		SpoolHooks.recoverAbandonedShards(p2.spooler);
		SpoolHooks.awaitIdle(p2.spooler);
		assertTrue(pendingIds(p2).contains(p1Row));
		assertEquals(p1Row, SpoolHooks.replayCandidates(p2.spooler).get(0));
	}

	@Test
	/**
	 * D3, restated under round 3 F1. A grant for ANOTHER identity never marks or purges a live identity (the
	 * round-1 version of this test asserted the opposite and was rewritten). The marker now comes only from a
	 * WITHDRAWAL of the identity itself (X-Event-Spool off / 403) in another process: the live shard survives the
	 * purge, a marker appears, and the marked writer stops, deletes its own shard and leaves the spool path.
	 */
	public void withdrawalInAnotherProcessLeavesTheLiveShardAndAMarkerThenThatWriterStops() throws Exception
	{
		AccountConnectPlugin p1 = plugin(TOKEN_A, "111");
		grant(p1);
		emit(p1, "trade");
		releaseToReplay(p1);
		Path pDir = identityDir(TOKEN_A, "111");
		Path live = SpoolHooks.shardFile(p1.spooler);
		assertTrue(Files.exists(live));

		AccountConnectPlugin other = plugin(TOKEN_B, "222");
		other.startUp();
		grant(other);
		assertTrue("another identity's grant leaves a live identity alone", Files.exists(live));
		assertFalse("and never marks it", Files.exists(pDir.resolve(EventSpool.MARKER)));

		AccountConnectPlugin p2 = plugin(TOKEN_A, "111");
		grant(p2);
		p2.grants.applyServerPolicy(policy("X-Event-Spool", "off"));
		SpoolHooks.awaitIdle(p2.spooler);
		assertTrue("a live shard is never deleted by another process", Files.exists(live));
		assertTrue(Files.exists(pDir.resolve(EventSpool.MARKER)));

		int before = bodies.size();
		replayAndWaitNoRequest(p1, before);
		SpoolHooks.awaitIdle(p1.spooler);
		assertFalse("the marked writer deletes its own shard", Files.exists(live));
		assertFalse(Files.exists(pDir));
		emit(p1, "trade");
		assertFalse("and leaves the spool path", Files.exists(pDir));
		assertEquals(false, p1.events.captureHealthSnapshot().get("spool_active"));
	}

	@Test
	public void tokenClearedAtTheLoginScreenAfterARestartPurgesThePointerIdentity() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "111");
		grant(a);
		emit(a, "trade");
		SpoolHooks.crash(a.spooler);
		Path aDir = identityDir(TOKEN_A, "111");
		assertTrue(Files.isDirectory(aDir));

		AccountConnectPlugin p = plugin(TOKEN_A, null);	// restarted, no account seen yet
		p.startUp();
		setToken(p, "");
		net.runelite.client.events.ConfigChanged ev = new net.runelite.client.events.ConfigChanged();
		ev.setGroup(AccountConnectPlugin.CONFIG_GROUP);
		ev.setKey("linkToken");
		ev.setOldValue(TOKEN_A);
		ev.setNewValue("");
		p.onConfigChanged(ev);
		SpoolHooks.awaitIdle(p.spooler);
		assertFalse("the user withdrew the token: its spool is destroyed", Files.exists(aDir));
		assertEquals(Collections.emptyList(), EventSpool.identities(spoolRoot()));
	}

	@Test
	public void aRowDroppedByTheMemoryCapStaysDurableAndBecomesReplayEligible() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		for (int i = 0; i < 501; i++)
		{
			p.events.emitEvent("trade", fields("n", i));
		}
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals(501, pendingIds(p).size());
		String oldest = pendingIds(p).get(0);
		assertEquals("only the row the memory cap dropped is replay-eligible",
			Collections.singletonList(oldest), SpoolHooks.replayCandidates(p.spooler));
	}

	// ------------------------------------------------------------------ round 2: ordering

	/** Rewritten without the append hook: the disk thread is held instead. Same guarantee: force before network. */
	@Test
	public void anAllowlistedEventIsNotPostedUntilItsAppendAndForceComplete() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		Runnable disk = SpoolHooks.block(p.spooler);
		p.events.emitEvent("trade", fields("item", "Abyssal whip"));

		p.events.flushEvents();
		Thread.sleep(300);
		assertEquals("nothing may be POSTed before the row is durable", 0, bodies.size());
		assertEquals("the sender does not have it yet", Collections.emptyList(), lastMemoryIds(p));

		disk.run();
		SpoolHooks.awaitIdle(p.spooler);
		String id = pendingIds(p).get(0);
		assertEquals("durable, then handed to the sender", Collections.singletonList(id), lastMemoryIds(p));
		flushAndWait(p, 1);
		assertEquals(Collections.singletonList(id), idsIn(bodies.get(0)));
		p.events.flushEvents();
		Thread.sleep(200);
		assertEquals("POSTed exactly once", 1, bodies.size());
	}

	/** Rewritten without the append hook: the spool directory cannot be created (the data root is a file). */
	@Test
	public void anAppendFailureReleasesTheEventToMemoryOnlyAndCountsIt() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		Path notADirectory = pluginData.resolve("blocked");
		Files.write(notADirectory, new byte[]{1});
		SpoolHooks.dataRoot(p.spooler, notADirectory);
		grant(p);
		emit(p, "trade");
		String id = lastMemoryIds(p).get(0);
		flushAndWait(p, 1);
		assertEquals("the event still goes out, memory-only", Collections.singletonList(id), idsIn(bodies.get(0)));
		assertTrue("nothing durable", pendingIds(p).isEmpty());
		assertEquals(1L, p.events.captureHealthSnapshot().get("spool_append_failed_total"));
		assertFalse("not held as a durable row", p.spooler.spoolMemoryOwned.containsKey(id));
	}

	/**
	 * Rewritten for the staging model (was: theMemoryCapReleasesADurabilityPendingId). Same guarantee: events that
	 * wait for the disk while the memory cap is hit are never stuck: every one is durable, the sender holds 500, and
	 * the one the cap dropped is staged again by the pump.
	 */
	@Test
	public void theMemoryCapWhileTheDiskWaitsLeavesNoRowStuck() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		Runnable disk = SpoolHooks.block(p.spooler);
		for (int i = 0; i < 501; i++)
		{
			p.events.emitEvent("trade", fields("n", i));
		}
		disk.run();
		SpoolHooks.awaitIdle(p.spooler);
		List<String> durable = pendingIds(p);
		assertEquals(501, durable.size());
		assertEquals(500, lastMemoryIds(p).size());
		assertEquals("only the row the cap dropped is a candidate", Collections.singletonList(durable.get(0)),
			SpoolHooks.replayCandidates(p.spooler));
	}

	/** Rewritten (was: publicTokensNeverEnterTheDurabilityGate): a public token's event never waits for the disk thread. */
	@Test
	public void publicTokensNeverWaitForTheDiskThread() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		Runnable disk = SpoolHooks.block(p.spooler);
		try
		{
			p.events.emitEvent("trade", fields("item", "x"));
			assertEquals(1, lastMemoryIds(p).size());
			flushAndWaitWhileDiskHeld(p, 1);
			assertEquals(1, idsIn(bodies.get(0)).size());
		}
		finally
		{
			disk.run();
		}
	}

	private void flushAndWaitWhileDiskHeld(AccountConnectPlugin p, int expectedRequests) throws Exception
	{
		p.events.flushEvents();
		waitFor(() -> bodies.size() >= expectedRequests && !p.events.eventPostInFlight, "memory flush settled");
	}

	/** Rewritten without the after-append hook: the process dies right after the first durable append, before any send. */
	@Test
	public void theIdentityDirectoryIsListedByTheFirstSensitiveRowSoACrashRightAfterItStillPurges() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "111");
		grant(a);
		a.events.emitEvent("trade", fields("item", "Abyssal whip"));
		SpoolHooks.awaitIdle(a.spooler);
		String aId = pendingIds(a).get(0);
		List<String> pointerAtFirstAppend = EventSpool.identities(spoolRoot());
		SpoolHooks.crash(a.spooler);
		Path aDir = identityDir(TOKEN_A, "111");
		assertTrue("the row is on disk", Files.isDirectory(aDir));
		assertEquals("the identity directory was listed when its first row became durable",
			Collections.singletonList(EventSpool.identityKey(TOKEN_A, "111")), pointerAtFirstAppend);

		List<Boolean> aDirAtRequest = new CopyOnWriteArrayList<>();
		onRequest.add(() -> aDirAtRequest.add(Files.exists(aDir)));
		AccountConnectPlugin c = plugin(TOKEN_B, "222");	// A's token is not available anywhere
		c.startUp();
		grant(c);
		assertFalse("A purged at C's first grant", Files.exists(aDir));
		emit(c, "trade");
		releaseToReplay(c);
		replayAndWait(c, bodies.size() + 1);
		assertFalse(aDirAtRequest.contains(Boolean.TRUE));
		assertFalse("no A event_id ever reached the server", postedIds().contains(aId));
	}

	// ------------------------------------------------------------------ round 3

	@Test
	public void twoLiveClientsOnTheSameTokenNeverPurgeOrMarkEachOtherAThenB() throws Exception
	{
		runTwoClients(TOKEN_A, "111", TOKEN_A, "222");
	}

	@Test
	public void twoLiveClientsOnTheSameTokenNeverPurgeOrMarkEachOtherBThenA() throws Exception
	{
		runTwoClients(TOKEN_A, "222", TOKEN_A, "111");
	}

	@Test
	public void twoLiveClientsOnDifferentTokensNeverPurgeOrMarkEachOther() throws Exception
	{
		runTwoClients(TOKEN_A, "111", TOKEN_B, "222");
	}

	private void runTwoClients(String t1, String a1, String t2, String a2) throws Exception
	{
		AccountConnectPlugin c1 = plugin(t1, a1);
		grant(c1);
		emit(c1, "trade");
		releaseToReplay(c1);
		String row1 = pendingIds(c1).get(0);

		AccountConnectPlugin c2 = plugin(t2, a2);
		c2.startUp();
		grant(c2);
		emit(c2, "trade");
		releaseToReplay(c2);
		String row2 = pendingIds(c2).get(0);

		Path d1 = identityDir(t1, a1);
		Path d2 = identityDir(t2, a2);
		assertTrue("the first client's live identity survives the second grant", Files.isDirectory(d1));
		assertFalse("no marker in a live identity", Files.exists(d1.resolve(EventSpool.MARKER)));
		assertEquals("the durable-only row is not lost", Collections.singletonList(row1), pendingIds(c1));

		// both keep appending; a grant refresh on either side must not touch the other
		grant(c1);
		emit(c1, "drop");
		emit(c2, "drop");
		assertFalse(Files.exists(d1.resolve(EventSpool.MARKER)));
		assertFalse(Files.exists(d2.resolve(EventSpool.MARKER)));
		assertEquals(2, pendingIds(c1).size());
		assertEquals(2, pendingIds(c2).size());
		assertEquals(true, c1.events.captureHealthSnapshot().get("spool_active"));
		assertEquals(true, c2.events.captureHealthSnapshot().get("spool_active"));

		// each replays only its own rows, under its own token
		releaseToReplay(c1);
		releaseToReplay(c2);
		int before = bodies.size();
		replayAndWait(c1, before + 1);
		List<String> r1 = idsIn(bodies.get(before));
		assertTrue(r1.contains(row1));
		assertFalse(r1.contains(row2));
		assertTrue(bodies.get(before).contains(t1));
		waitFor(() -> pendingIds(c1).isEmpty(), "c1 acked");
		replayAndWait(c2, before + 2);
		List<String> r2 = idsIn(bodies.get(before + 1));
		assertTrue(r2.contains(row2));
		assertFalse(r2.contains(row1));
		waitFor(() -> pendingIds(c2).isEmpty(), "c2 acked");

		List<String> idx = EventSpool.identities(spoolRoot());
		assertTrue("both identities stay listed",
			idx.contains(EventSpool.identityKey(t1, a1)) && idx.contains(EventSpool.identityKey(t2, a2)));

		// once c1 is gone, a fresh process for c2's identity cleans c1 up (the offline purge still works)
		emit(c1, "trade");
		emit(c2, "trade");
		String c2Row = pendingIds(c2).get(0);
		SpoolHooks.crash(c1.spooler);
		SpoolHooks.crash(c2.spooler);
		AccountConnectPlugin c3 = plugin(t2, a2);
		c3.startUp();
		grant(c3);
		assertFalse("an idle older identity is purged at the next grant", Files.exists(d1));
		assertEquals("its own identity's rows are recovered", Collections.singletonList(c2Row), pendingIds(c3));
	}

	@Test
	public void anOlderOfflineIdentityIsStillPurgedAfterANewerOneWasRecorded() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "111");
		grant(a);
		emit(a, "trade");
		AccountConnectPlugin b = plugin(TOKEN_A, "222");
		b.startUp();
		grant(b);
		emit(b, "trade");
		Path aDir = identityDir(TOKEN_A, "111");
		Path bDir = identityDir(TOKEN_A, "222");
		assertTrue(Files.isDirectory(aDir));
		SpoolHooks.crash(a.spooler);
		SpoolHooks.crash(b.spooler);
		assertEquals("both identities are listed", new HashSet<>(Arrays.asList(EventSpool.identityKey(TOKEN_A, "111"),
			EventSpool.identityKey(TOKEN_A, "222"))), new HashSet<>(EventSpool.identities(spoolRoot())));

		AccountConnectPlugin c = plugin(TOKEN_B, "333");	// neither A nor B's token is available
		c.startUp();
		grant(c);
		assertFalse("the OLDER identity is still purged", Files.exists(aDir));
		assertFalse(Files.exists(bDir));
		assertEquals("no directory before C's first row", Collections.emptyList(), EventSpool.identities(spoolRoot()));
		emit(c, "trade");
		assertEquals(Collections.singletonList(EventSpool.identityKey(TOKEN_B, "333")), EventSpool.identities(spoolRoot()));
	}

	@Test
	public void markedWhileLiveThenGracefulCloseLeavesNothingOfThatIdentity() throws Exception
	{
		AccountConnectPlugin p1 = plugin(TOKEN_A, "111");
		grant(p1);
		emit(p1, "trade");
		Path dir = identityDir(TOKEN_A, "111");
		AccountConnectPlugin p2 = plugin(TOKEN_A, "111");
		grant(p2);
		emit(p2, "drop");
		// p2 withdraws the identity (server says off) while p1 still holds its shard: p1 gets a marker.
		p2.grants.applyServerPolicy(policy("X-Event-Spool", "off"));
		SpoolHooks.awaitIdle(p2.spooler);
		assertTrue(Files.exists(dir.resolve(EventSpool.MARKER)));
		p1.shutDown();
		SpoolHooks.awaitIdle(p1.spooler);
		assertFalse("nothing of a withdrawn identity survives a graceful close", Files.exists(dir));
		assertEquals(false, p1.events.captureHealthSnapshot().get("spool_active"));
	}

	@Test
	public void restartWithAMarkedDirectoryAndNoLiveShardFinishesThePurgeAndANewGrantWorks() throws Exception
	{
		AccountConnectPlugin p1 = plugin(TOKEN_A, "111");
		grant(p1);
		emit(p1, "trade");
		Path dir = identityDir(TOKEN_A, "111");
		Files.write(dir.resolve(EventSpool.MARKER), "{\"v\":1,\"requested_ms\":1}".getBytes(StandardCharsets.UTF_8));
		SpoolHooks.crash(p1.spooler);

		AccountConnectPlugin p = plugin(TOKEN_A, "111");
		p.startUp();
		grant(p);
		assertFalse("the old marked rows are not recovered", pendingIds(p).size() > 0);
		emit(p, "trade");
		assertEquals(1, pendingIds(p).size());
		assertFalse(Files.exists(dir.resolve(EventSpool.MARKER)));
		assertEquals(true, p.events.captureHealthSnapshot().get("spool_active"));
	}

	@Test
	public void retirementByAMarkerTurnsSpoolActiveOffAndStopsWrites() throws Exception
	{
		AccountConnectPlugin p1 = plugin(TOKEN_A, "111");
		grant(p1);
		emit(p1, "trade");
		Path dir = identityDir(TOKEN_A, "111");
		Files.write(dir.resolve(EventSpool.MARKER), "{\"v\":1,\"requested_ms\":1}".getBytes(StandardCharsets.UTF_8));
		emit(p1, "drop");	// the writer sees the marker and retires
		assertEquals(false, p1.events.captureHealthSnapshot().get("spool_active"));
		assertFalse(Files.exists(dir));
		emit(p1, "trade");
		assertFalse("no write after retirement until a new grant", Files.exists(dir));
	}

	/** Rewritten without the dispatch hook: rows staged from disk under one token are held once the token changes. */
	@Test
	public void aReplayBatchBuiltBeforeATokenChangeIsNeverSentUnderTheNewToken() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		String aId = pendingIds(p).get(0);
		int before = bodies.size();
		p.spooler.spoolReplayBackoffUntilMs = 0L;
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals("staged under A", Collections.singletonList(aId), lastMemoryIds(p));
		setToken(p, TOKEN_B);	// the token changes after the rows are staged, before the sender runs
		p.events.flushEvents();
		Thread.sleep(300);
		assertEquals("the old-identity row is never dispatched", before, bodies.size());
		assertFalse(postedIds().subList(Math.min(before, postedIds().size()), postedIds().size()).contains(aId));
		assertFalse(p.events.eventPostInFlight);
	}

	// ------------------------------------------------------------------ round 4

	/** A mock of the final server: stored the first time an event_id is seen, duplicate after that. */
	private final java.util.Map<String, Integer> serverStored = new java.util.concurrent.ConcurrentHashMap<>();

	private MockResponse dedupingServer(String body)
	{
		JsonArray arr = new JsonArray();
		for (String id : idsIn(body))
		{
			boolean first = serverStored.putIfAbsent(id, 1) == null;
			arr.add(result(id, first ? "stored" : "duplicate"));
		}
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.add("results", arr);
		return new MockResponse().setResponseCode(200).setBody(o.toString());
	}

	/**
	 * Rewritten without the append hook (the disk thread is held) and with the bound kept: after it the events go
	 * out in order with their ids, and the row that becomes durable late is settled, so the server stores it once.
	 */
	@Test
	public void aHungAppendCannotStallDeliveryAndTheLateRowIsStoredOnce() throws Exception
	{
		fallback = this::dedupingServer;
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
		try
		{
			inject(p, "executor", timer);
			p.spooler.durabilityGateMaxMs = 300L;
			grant(p);
			Runnable disk = SpoolHooks.block(p.spooler);
			p.events.emitEvent("trade", fields("item", "Abyssal whip"));
			p.events.emitEvent("xp_gain", fields("skill", "Attack"));	// memory-only, queued behind it

			p.events.flushEvents();
			Thread.sleep(150);
			assertEquals("before the bound, force-before-network still holds", 0, bodies.size());

			waitFor(() -> lastMemoryIds(p).size() == 2, "handed over at the bound");
			List<String> order = lastMemoryIds(p);
			String hung = order.get(0);
			p.events.flushEvents();	// the disk thread is still held: do not wait on it
			waitFor(() -> bodies.size() >= 1 && !p.events.eventPostInFlight, "memory flush after the bound");
			assertEquals("delivery resumes in order", order, idsIn(bodies.get(0)));
			assertEquals(1L, p.events.captureHealthSnapshot().get("spool_gate_timeout_total"));

			disk.run();	// the write completes late
			SpoolHooks.awaitIdle(p.spooler);
			replayAndWait(p, bodies.size());
			waitFor(() -> pendingIds(p).isEmpty(), "the late row is settled");
			assertEquals("one stored row total on the server", Integer.valueOf(1), serverStored.get(hung));
			for (String b : bodies)
			{
				assertTrue("never twice in one request", idsIn(b).stream().filter(hung::equals).count() <= 1);
			}
		}
		finally
		{
			timer.shutdownNow();
		}
	}

	/**
	 * Rewritten for the staging model (was: aPurgeDecidedBeforeAReactivationNeverReleasesTheNewGenerationsGate).
	 * Same guarantee: a purge of X decided before X is re-activated never lets the re-activated X's row out before
	 * it is durable, and that row ends up durable in the re-activated identity. Purge and staging share the disk
	 * thread, so no generation count is needed.
	 */
	@Test
	public void aPurgeDecidedBeforeAReactivationNeverReleasesTheNewRowBeforeItIsDurable() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "111");
		grant(p);
		Runnable disk = SpoolHooks.block(p.spooler);
		p.events.emitEvent("trade", fields("n", 0));	// X, before the purge
		inject(p, "activeHash", "222");
		p.spooler.syncSpoolState();						// X -> Y: "purge X" is queued behind the busy thread
		inject(p, "activeHash", "111");
		p.spooler.syncSpoolState();						// Y -> X: X is active again
		p.events.emitEvent("trade", fields("n", 1));	// X again: queued behind the purge
		try
		{
			p.events.flushEvents();
			Thread.sleep(300);
			assertTrue("nothing is sent before it is durable", bodies.isEmpty());
		}
		finally
		{
			disk.run();
		}
		SpoolHooks.awaitIdle(p.spooler);
		List<String> memory = lastMemoryIds(p);
		assertEquals(2, memory.size());
		String fresh = memory.get(1);
		assertEquals("the fresh row is durable in the re-activated identity", Collections.singletonList(fresh),
			pendingIds(p));
		assertTrue(p.spooler.spoolMemoryOwned.containsKey(fresh));
		assertFalse("the purged row is memory-only now", p.spooler.spoolMemoryOwned.containsKey(memory.get(0)));
	}

	@Test
	public void the48HourBoundIsEnforcedAtReplayTimeWithoutAnyAppendOrRecover() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		final long[] offset = {-(EventSpool.MAX_AGE_MS - 2_000L)};
		p.spooler.wallClock = () -> System.currentTimeMillis() + offset[0];
		grant(p);
		emit(p, "trade");						// created 47 h 59 m 58 s "ago"
		String old = lastMemoryIds(p).get(0);
		offset[0] = 0L;
		emit(p, "drop");						// fresh; this append still keeps the old row (not yet 48 h)
		releaseToReplay(p);
		List<String> both = pendingIds(p);
		assertEquals(2, both.size());
		String fresh = both.get(1);
		long droppedBefore = (Long) p.events.captureHealthSnapshot().get("spool_dropped_total");

		offset[0] = 3_000L;						// the old row crosses 48 h; no append, no recover
		int before = bodies.size();
		replayAndWait(p, before + 1);
		assertEquals("only the fresh row replays", Collections.singletonList(fresh), idsIn(bodies.get(before)));
		assertFalse("a row older than 48 h is never POSTed by replay", postedIds().subList(before, postedIds().size()).contains(old));
		assertEquals(droppedBefore + 1, p.events.captureHealthSnapshot().get("spool_dropped_total"));
		SpoolHooks.crash(p.spooler);
		AccountConnectPlugin q = plugin(TOKEN_A, "12345");
		q.spooler.wallClock = () -> System.currentTimeMillis() - 1_000L;	// even a clock that is back inside the window
		q.startUp();
		grant(q);
		assertFalse("the age-out is durable (tombstoned)", pendingIds(q).contains(old));
	}

	/** Rewritten without the dispatch hook: a staged row that passes 48 h before the sender takes it is never sent. */
	@Test
	public void aReplayBatchThatCrossesTheAgeBoundBeforeDispatchIsNeverSent() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		final long[] offset = {-(EventSpool.MAX_AGE_MS - 2_000L)};
		p.spooler.wallClock = () -> System.currentTimeMillis() + offset[0];
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		String old = pendingIds(p).get(0);
		int before = bodies.size();
		p.spooler.spoolReplayBackoffUntilMs = 0L;
		p.spooler.pumpSpool();					// staged inside the window
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals(Collections.singletonList(old), lastMemoryIds(p));
		offset[0] = 3_000L;						// the clock then passes 48 h before the sender runs
		p.events.flushEvents();
		Thread.sleep(200);
		assertEquals("a row past 48 h at dispatch is never on the wire", before, bodies.size());
		assertEquals(1L, p.events.captureHealthSnapshot().get("spool_replay_aged_at_dispatch_total"));
		replayAndWaitNoRequest(p, before);
		assertFalse("and the next tick ages it out durably", pendingIds(p).contains(old));
	}

	// ------------------------------------------------------------------ crash semantics of the one sender
	//
	// A row is acknowledged once its append is forced. Whatever point the process dies at, every acknowledged row that
	// the server has not settled is sent again after the restart and the server stores it exactly once (it dedupes by
	// event_id); nothing acknowledged is lost, and a write that never completed is never sent.

	/** Emit rows, then kill the process at the given point of their first send; restart and settle. */
	private void killAndRestart(java.util.function.BiConsumer<AccountConnectPlugin, Runnable> killPoint) throws Exception
	{
		fallback = this::dedupingServer;
		AccountConnectPlugin a = plugin(TOKEN_A, "12345");
		grant(a);
		for (int i = 0; i < 5; i++)
		{
			emit(a, i % 2 == 0 ? "trade" : "ge_buy");
		}
		List<String> acknowledged = pendingIds(a);
		assertEquals(5, acknowledged.size());
		killPoint.accept(a, () -> { });

		AccountConnectPlugin b = plugin(TOKEN_A, "12345");
		b.startUp();
		grant(b);
		for (int i = 0; i < 3 && !pendingIds(b).isEmpty(); i++)
		{
			replayAndWait(b, bodies.size());
		}
		assertTrue("every acknowledged row is settled after the restart", pendingIds(b).isEmpty());
		for (String id : acknowledged)
		{
			assertEquals("stored exactly once: " + id, Integer.valueOf(1), serverStored.get(id));
		}
		assertEquals("nothing else reached the server", new HashSet<>(acknowledged), serverStored.keySet());
	}

	@Test
	public void aKillAfterTheForcedAppendAndBeforeAnySendLosesNoRow() throws Exception
	{
		killAndRestart((a, unused) ->
		{
			try
			{
				SpoolHooks.crash(a.spooler);
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
			assertTrue(bodies.isEmpty());
		});
	}

	@Test
	public void aKillWhileTheRequestIsInFlightSendsEachRowOnceMoreAndTheServerKeepsOne() throws Exception
	{
		killAndRestart((a, unused) ->
		{
			try
			{
				java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(1);
				java.util.concurrent.CountDownLatch answer = new java.util.concurrent.CountDownLatch(1);
				script.add(body ->
				{
					MockResponse r = dedupingServer(body);	// the server stores the rows ...
					inFlight.countDown();
					try
					{
						answer.await(3, TimeUnit.SECONDS);
					}
					catch (InterruptedException ignored)
					{
					}
					return r;
				});
				a.events.flushEvents();
				assertTrue(inFlight.await(3, TimeUnit.SECONDS));
				SpoolHooks.crash(a.spooler);				// ... and the client dies before the answer arrives
				answer.countDown();
				Thread.sleep(200);
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		});
		assertEquals("the server saw each row twice: stored, then duplicate", 10, postedIds().size());
	}

	@Test
	public void aKillAfterTheServerStoredButBeforeTheAckIsWrittenResolvesAsADuplicate() throws Exception
	{
		killAndRestart((a, unused) ->
		{
			try
			{
				Runnable disk = SpoolHooks.block(a.spooler);
				flushAndWaitWhileDiskHeld(a, 1);			// stored on the server; the ack waits for the disk thread
				SpoolHooks.crashWhileBlocked(a.spooler, disk);
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		});
	}

	@Test
	public void aKillBeforeTheAppendRanLeavesNothingAndSendsNothing() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "12345");
		grant(a);
		emit(a, "trade");
		String acknowledged = pendingIds(a).get(0);
		Runnable disk = SpoolHooks.block(a.spooler);
		a.events.emitEvent("drop", fields("item", "Abyssal whip"));	// never written: the process dies first
		a.events.flushEvents();
		SpoolHooks.crashWhileBlocked(a.spooler, disk);
		AccountConnectPlugin b = plugin(TOKEN_A, "12345");
		b.startUp();
		grant(b);
		assertEquals("only the forced row survives", Collections.singletonList(acknowledged), pendingIds(b));
		replayAndWait(b, bodies.size() + 1);
		assertTrue(pendingIds(b).isEmpty());
		assertEquals(Collections.singletonList(acknowledged), idsIn(bodies.get(bodies.size() - 1)));
	}

	@Test
	public void aWriteTornByAKillIsNeverSentAndTheRowsBeforeItAre() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "12345");
		grant(a);
		emit(a, "trade");
		emit(a, "drop");
		List<String> ids = pendingIds(a);
		Path shard = SpoolHooks.shardFile(a.spooler);
		SpoolHooks.crash(a.spooler);
		byte[] bytes = Files.readAllBytes(shard);
		Files.write(shard, Arrays.copyOf(bytes, bytes.length - 7));	// the second frame was being written
		AccountConnectPlugin b = plugin(TOKEN_A, "12345");
		b.startUp();
		grant(b);
		assertEquals(Collections.singletonList(ids.get(0)), pendingIds(b));
		replayAndWait(b, bodies.size() + 1);
		assertEquals(Collections.singletonList(ids.get(0)), postedIds());
		assertTrue(pendingIds(b).isEmpty());
	}

	@Test
	public void healthCarriesTheSpoolCounters() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		Map<String, Object> h = p.events.captureHealthSnapshot();
		for (String k : Arrays.asList("spool_active", "spool_records_pending", "spool_bytes", "spool_oldest_age_s",
			"spool_replayed_total", "spool_acked_total", "spool_dropped_total", "spool_corrupt_total",
			"spool_quarantined_total", "spool_filtered_total", "spool_unacked_2xx_total", "spool_purged_total",
			"spool_append_failed_total", "spool_gate_timeout_total"))
		{
			assertTrue(k, h.containsKey(k));
		}
		assertEquals(true, h.get("spool_active"));
		assertEquals(1, h.get("spool_records_pending"));
	}

	// ------------------------------------------------------------------ helpers

	private static JsonObject result(String id, String status)
	{
		JsonObject r = new JsonObject();
		r.addProperty("event_id", id);
		r.addProperty("status", status);
		return r;
	}

	private void replayAndWaitNoRequest(AccountConnectPlugin p, int before) throws Exception
	{
		p.spooler.spoolReplayBackoffUntilMs = 0L;
		p.events.eventRetryBackoffUntilMs = 0L;
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		p.events.flushEvents();
		Thread.sleep(300);
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals("no replay request expected", before, bodies.size());
	}

	private String treeDigest() throws Exception
	{
		StringBuilder sb = new StringBuilder();
		List<Path> paths = allPaths();
		Collections.sort(paths);
		for (Path x : paths)
		{
			sb.append(x).append('=').append(Files.isRegularFile(x) ? EventSpoolCodecTest.sha(x) : "dir").append('\n');
		}
		return sb.toString();
	}

	static void waitFor(java.util.function.BooleanSupplier cond, String what) throws Exception
	{
		long deadline = System.currentTimeMillis() + 4_000L;
		while (System.currentTimeMillis() < deadline)
		{
			if (cond.getAsBoolean())
			{
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("timed out waiting for: " + what);
	}
}
