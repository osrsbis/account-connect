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
			p.crashSpoolForTest();
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
		p.setSpoolDataRootForTest(pluginData);
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
		p.applyServerPolicy(policy("X-Event-Spool", "on"));
		p.awaitSpoolIdleForTest();
	}

	private Map<String, Object> fields(String k, Object v)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(k, v);
		return m;
	}

	private void emit(AccountConnectPlugin p, String type) throws Exception
	{
		p.emitEvent(type, fields("item", "Abyssal whip"));
		p.awaitSpoolIdleForTest();
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
			return p.spoolPendingIdsForTest();
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	private List<String> lastMemoryIds(AccountConnectPlugin p)
	{
		List<String> out = new ArrayList<>();
		synchronized (p.pendingEvents)
		{
			for (Map<String, Object> ev : p.pendingEvents)
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
		p.flushEvents();
		waitFor(() -> bodies.size() >= expectedRequests && !p.eventPostInFlight, "memory flush settled");
		p.awaitSpoolIdleForTest();
	}

	private void replayAndWait(AccountConnectPlugin p, int expectedRequests) throws Exception
	{
		p.spoolReplayNotBeforeMs = 0L;
		p.spoolReplayBackoffUntilMs = 0L;
		p.replaySpoolTick();
		waitFor(() -> bodies.size() >= expectedRequests && !p.spoolReplayInFlight, "replay settled");
		p.awaitSpoolIdleForTest();
	}

	private void releaseToReplay(AccountConnectPlugin p) throws Exception
	{
		// The current server shape: a 2xx with no per-id results. The memory copy is done; the durable
		// row stays and becomes replay-eligible.
		script.add(EventSpoolPluginTest::legacyStored);
		flushAndWait(p, bodies.size() + 1);
	}

	// ------------------------------------------------------------------ gate: public never touches disk

	@Test
	public void publicTokenNeverCreatesAnySpoolFileOrDirectoryAcrossEmitFlush5xxAndRestart() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		p.applyServerPolicy(policy("X-Max-Capture", "on", "X-Drop-Proof", "on"));	// other grants are not the spool
		emit(p, "trade");
		emit(p, "drop");
		script.add(b -> new MockResponse().setResponseCode(503));
		flushAndWait(p, 1);
		p.eventRetryBackoffUntilMs = 0L;
		flushAndWait(p, 2);
		p.replaySpoolTick();
		p.applyServerPolicy(policy("X-Event-Spool", "off"));
		emit(p, "trade");
		flushAndWait(p, 3);
		p.awaitSpoolIdleForTest();
		p.crashSpoolForTest();

		AccountConnectPlugin restarted = plugin(TOKEN_A, "12345");
		restarted.startUp();
		emit(restarted, "login");
		flushAndWait(restarted, 4);
		restarted.awaitSpoolIdleForTest();

		assertEquals("no file, no directory, no pointer for a non-staff token: " + allPaths(),
			Collections.emptyList(), allPaths());
		assertFalse((boolean) restarted.captureHealthSnapshot().get("spool_active"));
	}

	@Test
	public void eventIdIsGeneratedOnceAtEmitAndSentOnTheWireForPublicTokensToo() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		emit(p, "trade");
		script.add(b -> new MockResponse().setResponseCode(503));
		flushAndWait(p, 1);
		p.eventRetryBackoffUntilMs = 0L;
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
		assertFalse(disk.contains(AccountConnectPlugin.sha256Hex("event-token:" + TOKEN_A)));
		assertFalse(disk.contains("__spool"));
		assertTrue(Files.isDirectory(identityDir(TOKEN_A, "12345")));
		assertEquals(EventSpool.identityKey(TOKEN_A, "12345"), EventSpool.readPointer(spoolRoot()));
	}

	@Test
	public void onlyAllowlistedTypesReachDisk() throws Exception
	{
		assertEquals(new HashSet<>(Arrays.asList("trade", "ge_offer", "ge_progress", "ge_buy", "ge_sell", "ge_cancel",
			"ge_collect", "store_buy", "store_sell", "store_taken", "drop", "pickup", "ground_removed", "bank_session",
			"login", "logout", "world_hop", "drop_trade_clip", "drop_trade_clip_final", "death")),
			AccountConnectPlugin.SPOOL_ALLOWLIST);

		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		for (String t : Arrays.asList("fh_batch", "chat", "region", "xp_gain", "equip_change", "loot", "level_up", "alch"))
		{
			p.emitEvent(t, fields("marker", "MEMONLY_" + t));
		}
		p.emitEvent("trade", fields("marker", "SPOOLED_trade"));
		p.awaitSpoolIdleForTest();
		String disk = allDiskText();
		assertTrue(disk.contains("SPOOLED_trade"));
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
		p.awaitSpoolIdleForTest();
		assertFalse("A purged at the token change", Files.exists(identityDir(TOKEN_A, "12345")));
		assertNull("pointer to A removed", EventSpool.readPointer(spoolRoot()));

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
		p.awaitSpoolIdleForTest();
		assertFalse(Files.exists(identityDir(TOKEN_A, "12345")));
		p.spoolReplayNotBeforeMs = 0L;
		p.replaySpoolTick();
		p.flushEvents();
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
		p.syncSpoolState();
		p.awaitSpoolIdleForTest();
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
		p.applyServerPolicy(policy("X-Event-Spool", "off"));
		p.awaitSpoolIdleForTest();
		assertFalse(Files.exists(identityDir(TOKEN_A, "12345")));
		assertNull(EventSpool.readPointer(spoolRoot()));
		replayAndWaitNoRequest(p, before);
		emit(p, "trade");
		assertFalse("no writes after off", Files.exists(identityDir(TOKEN_A, "12345")));
		assertEquals(1L, p.captureHealthSnapshot().get("spool_purged_total"));
	}

	@Test
	public void absentHeaderLeavesTheGrantUnchanged() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		p.applyServerPolicy(policy("X-Sync-Interval", "30"));
		p.awaitSpoolIdleForTest();
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
		p.applyServerPolicy(policy("X-Uploads-Enabled", "false"));
		p.awaitSpoolIdleForTest();
		emit(p, "drop");	// writes continue during a pause
		releaseToReplay(p);
		assertEquals(2, pendingIds(p).size());
		int before = bodies.size();
		replayAndWaitNoRequest(p, before);
		assertTrue("nothing purged by a pause", Files.isDirectory(identityDir(TOKEN_A, "12345")));
		assertEquals(2, pendingIds(p).size());

		p.applyServerPolicy(policy("X-Uploads-Enabled", "true"));
		p.awaitSpoolIdleForTest();
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
		assertEquals(2L, p.captureHealthSnapshot().get("spool_unacked_2xx_total"));
		script.add(EventSpoolPluginTest::legacyStored);
		replayAndWait(p, bodies.size() + 1);
		assertEquals("aggregate stored:N deletes nothing", 2, pendingIds(p).size());
		assertTrue("a 2xx without results backs replay off", p.spoolReplayBackoffUntilMs > p.nowMs());
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
		Map<String, Object> h = p.captureHealthSnapshot();
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
		assertEquals(1L, p.captureHealthSnapshot().get("spool_quarantined_total"));
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
			p.emitEvent("chat", fields("n", i));
		}
		flushAndWait(p, 1);
		assertEquals(100, idsIn(bodies.get(0)).size());
		flushAndWait(p, 2);
		assertEquals(50, idsIn(bodies.get(1)).size());

		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 100_000; i++)
		{
			big.append('x');
		}
		for (int i = 0; i < 3; i++)
		{
			p.emitEvent("chat", fields("blob", big.toString()));
		}
		flushAndWait(p, 3);
		assertEquals(2, idsIn(bodies.get(2)).size());
		assertTrue(bodies.get(2).getBytes(StandardCharsets.UTF_8).length < 256 * 1024);
		flushAndWait(p, 4);
		assertEquals(1, idsIn(bodies.get(3)).size());
	}

	@Test
	public void replaySplitsAt100AndIsPacedAndStopsOn429() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		for (int i = 0; i < 150; i++)
		{
			p.emitEvent("trade", fields("n", i));
		}
		p.awaitSpoolIdleForTest();
		fallback = EventSpoolPluginTest::legacyStored;
		flushAndWait(p, 1);
		flushAndWait(p, 2);
		assertEquals(150, pendingIds(p).size());
		fallback = EventSpoolPluginTest::ackAll;
		int before = bodies.size();

		p.spoolReplayBackoffUntilMs = 0L;
		p.spoolReplayNotBeforeMs = 0L;
		script.add(b -> new MockResponse().setResponseCode(429).setHeader("Retry-After", "60"));
		p.replaySpoolTick();
		waitFor(() -> bodies.size() == before + 1 && !p.spoolReplayInFlight, "first replay");
		assertEquals(100, idsIn(bodies.get(before)).size());
		long until = p.spoolReplayBackoffUntilMs;
		assertTrue("Retry-After honoured", until - p.nowMs() > 50_000L);

		p.spoolReplayNotBeforeMs = 0L;
		p.replaySpoolTick();
		Thread.sleep(300);
		assertEquals("replay stops on 429 until the backoff ends", before + 1, bodies.size());

		p.spoolReplayBackoffUntilMs = 0L;
		p.replaySpoolTick();
		waitFor(() -> bodies.size() == before + 2 && !p.spoolReplayInFlight, "second replay");
		p.replaySpoolTick();
		Thread.sleep(300);
		assertEquals("at most one replay request per 5 s", before + 2, bodies.size());
		assertTrue(p.spoolReplayNotBeforeMs - p.nowMs() > 4_000L);
	}

	@Test
	public void recoveredRowsReplayAheadOfNewRowsAndNoIdIsInFlightTwice() throws Exception
	{
		AccountConnectPlugin first = plugin(TOKEN_A, "12345");
		grant(first);
		emit(first, "trade");
		emit(first, "drop");
		List<String> old = new ArrayList<>(pendingIds(first));
		first.crashSpoolForTest();

		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		String fresh = lastMemoryIds(p).get(0);
		List<String> expect = new ArrayList<>(old);
		assertEquals("the memory-owned row is not replay-eligible yet", expect, p.spoolReplayCandidatesForTest());

		// The memory flush is held open while a replay tick runs: the ids must not overlap.
		java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
		script.add(b -> {
			try
			{
				hold.await(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException ignored)
			{
			}
			return legacyStored(b);
		});
		p.flushEvents();
		waitFor(() -> bodies.size() == 1, "memory flush in flight");
		replayAndWait(p, 2);
		hold.countDown();
		waitFor(() -> !p.eventPostInFlight, "memory flush done");
		p.awaitSpoolIdleForTest();
		assertEquals(Collections.singletonList(fresh), idsIn(bodies.get(0)));
		assertEquals("recovered first, without the in-flight id", old, idsIn(bodies.get(1)));
		replayAndWait(p, 3);
		assertEquals(Collections.singletonList(fresh), idsIn(bodies.get(2)));
	}

	// ------------------------------------------------------------------ cold start + restarts

	@Test
	public void coldStartNeitherReplaysNorDeletesUntilTheHeaderThenReplaysOnlyThisIdentity() throws Exception
	{
		// A fresh foreign leftover that the pointer does NOT name (under D2 a pointer-named identity is
		// purged at the next grant, so this is the only way a foreign identity is still on disk).
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
		a.crashSpoolForTest();
		String before = treeDigest();

		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		p.startUp();
		emit(p, "trade");		// not active yet: memory only
		p.spoolReplayNotBeforeMs = 0L;
		p.replaySpoolTick();
		p.awaitSpoolIdleForTest();
		assertEquals("no replay, no delete, no write before the header", before, treeDigest());
		assertEquals(0, bodies.size());

		grant(p);
		replayAndWait(p, 1);
		assertEquals(aIds, idsIn(bodies.get(0)));
		for (String b : bodies)
		{
			for (String id : qIds)
			{
				assertFalse("a foreign identity is never replayed", idsIn(b).contains(id));
			}
		}
		assertTrue("a foreign fresh shard is left alone", Files.isDirectory(identityDir(TOKEN_B, "999")));
	}

	@Test
	public void restartAToBWithNoATokenPurgesAAtBsFirstGrantBeforeAnyReplay() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "111");
		grant(a);
		emit(a, "trade");
		emit(a, "drop");
		Set<String> aIds = new HashSet<>(pendingIds(a));
		a.crashSpoolForTest();
		assertEquals(EventSpool.identityKey(TOKEN_A, "111"), EventSpool.readPointer(spoolRoot()));
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
		assertEquals(EventSpool.identityKey(TOKEN_B, "222"), EventSpool.readPointer(spoolRoot()));
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
		p1.crashSpoolForTest();
		p2.recoverAbandonedShardsNow();
		p2.awaitSpoolIdleForTest();
		assertTrue(pendingIds(p2).contains(p1Row));
		assertEquals(p1Row, p2.spoolReplayCandidatesForTest().get(0));
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
		Path live = p1.spoolShardFileForTest();
		assertTrue(Files.exists(live));

		AccountConnectPlugin other = plugin(TOKEN_B, "222");
		other.startUp();
		grant(other);
		assertTrue("another identity's grant leaves a live identity alone", Files.exists(live));
		assertFalse("and never marks it", Files.exists(pDir.resolve(EventSpool.MARKER)));

		AccountConnectPlugin p2 = plugin(TOKEN_A, "111");
		grant(p2);
		p2.applyServerPolicy(policy("X-Event-Spool", "off"));
		p2.awaitSpoolIdleForTest();
		assertTrue("a live shard is never deleted by another process", Files.exists(live));
		assertTrue(Files.exists(pDir.resolve(EventSpool.MARKER)));

		int before = bodies.size();
		replayAndWaitNoRequest(p1, before);
		p1.awaitSpoolIdleForTest();
		assertFalse("the marked writer deletes its own shard", Files.exists(live));
		assertFalse(Files.exists(pDir));
		emit(p1, "trade");
		assertFalse("and leaves the spool path", Files.exists(pDir));
		assertEquals(false, p1.captureHealthSnapshot().get("spool_active"));
	}

	@Test
	public void tokenClearedAtTheLoginScreenAfterARestartPurgesThePointerIdentity() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "111");
		grant(a);
		emit(a, "trade");
		a.crashSpoolForTest();
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
		p.awaitSpoolIdleForTest();
		assertFalse("the user withdrew the token: its spool is destroyed", Files.exists(aDir));
		assertNull(EventSpool.readPointer(spoolRoot()));
	}

	@Test
	public void aRowDroppedByTheMemoryCapStaysDurableAndBecomesReplayEligible() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		for (int i = 0; i < 501; i++)
		{
			p.emitEvent("trade", fields("n", i));
		}
		p.awaitSpoolIdleForTest();
		assertEquals(501, pendingIds(p).size());
		String oldest = pendingIds(p).get(0);
		assertEquals("only the row the memory cap dropped is replay-eligible",
			Collections.singletonList(oldest), p.spoolReplayCandidatesForTest());
	}

	// ------------------------------------------------------------------ round 2: ordering

	@Test
	public void anAllowlistedEventIsNotPostedUntilItsAppendAndForceComplete() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
		p.spoolBeforeAppendHookForTest = () ->
		{
			entered.countDown();
			try
			{
				gate.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ignored)
			{
			}
		};
		p.emitEvent("trade", fields("item", "Abyssal whip"));
		assertTrue("the spool thread is inside the append", entered.await(3, TimeUnit.SECONDS));
		String id = lastMemoryIds(p).get(0);

		p.flushEvents();
		Thread.sleep(300);
		assertEquals("nothing may be POSTed before the row is durable", 0, bodies.size());
		assertEquals("the event is still waiting in memory", Collections.singletonList(id), lastMemoryIds(p));

		gate.countDown();
		p.awaitSpoolIdleForTest();
		assertEquals("durable now", Collections.singletonList(id), pendingIds(p));
		flushAndWait(p, 1);
		assertEquals(Collections.singletonList(id), idsIn(bodies.get(0)));
		p.flushEvents();
		Thread.sleep(200);
		assertEquals("POSTed exactly once", 1, bodies.size());
		assertEquals(0, p.spoolDurabilityPendingCountForTest());
	}

	@Test
	public void anAppendFailureReleasesTheEventToMemoryOnlyAndCountsIt() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		p.spoolBeforeAppendHookForTest = () ->
		{
			throw new java.io.UncheckedIOException(new java.io.IOException("disk full"));
		};
		emit(p, "trade");
		String id = lastMemoryIds(p).get(0);
		flushAndWait(p, 1);
		assertEquals("the event still goes out, memory-only", Collections.singletonList(id), idsIn(bodies.get(0)));
		assertTrue("nothing durable", pendingIds(p).isEmpty());
		assertEquals(1L, p.captureHealthSnapshot().get("spool_append_failed_total"));
		assertEquals(0, p.spoolDurabilityPendingCountForTest());
	}

	@Test
	public void theMemoryCapReleasesADurabilityPendingId() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
		p.spoolBeforeAppendHookForTest = () ->
		{
			try
			{
				gate.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ignored)
			{
			}
		};
		for (int i = 0; i < 501; i++)
		{
			p.emitEvent("trade", fields("n", i));
		}
		assertEquals("the cap dropped one event and released its id", 500, p.spoolDurabilityPendingCountForTest());
		gate.countDown();
		p.awaitSpoolIdleForTest();
		assertEquals("never a stuck id", 0, p.spoolDurabilityPendingCountForTest());
	}

	@Test
	public void publicTokensNeverEnterTheDurabilityGate() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		p.emitEvent("trade", fields("item", "x"));
		assertEquals(0, p.spoolDurabilityPendingCountForTest());
		flushAndWait(p, 1);
		assertEquals(1, idsIn(bodies.get(0)).size());
	}

	@Test
	public void thePointerIsDurableBeforeTheFirstSensitiveRowSoACrashRightAfterItStillPurges() throws Exception
	{
		AccountConnectPlugin a = plugin(TOKEN_A, "111");
		grant(a);
		List<String> pointerAtFirstAppend = new CopyOnWriteArrayList<>();
		a.spoolBeforeAppendHookForTest = () -> pointerAtFirstAppend.add(String.valueOf(EventSpool.readPointer(spoolRoot())));
		a.spoolAfterAppendHookForTest = () ->
		{
			throw new IllegalStateException("simulated process death right after the first durable append");
		};
		a.emitEvent("trade", fields("item", "Abyssal whip"));
		a.awaitSpoolIdleForTest();
		String aId = lastMemoryIds(a).get(0);
		a.crashSpoolForTest();
		Path aDir = identityDir(TOKEN_A, "111");
		assertTrue("the row is on disk", Files.isDirectory(aDir));
		assertEquals("the pointer named this identity before its first row was written",
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
		assertEquals(true, c1.captureHealthSnapshot().get("spool_active"));
		assertEquals(true, c2.captureHealthSnapshot().get("spool_active"));

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

		List<String> idx = EventSpool.readIndex(spoolRoot());
		assertTrue("both identities stay indexed",
			idx.contains(EventSpool.identityKey(t1, a1)) && idx.contains(EventSpool.identityKey(t2, a2)));

		// once c1 is gone, a fresh process for c2's identity cleans c1 up (the offline purge still works)
		emit(c1, "trade");
		emit(c2, "trade");
		String c2Row = pendingIds(c2).get(0);
		c1.crashSpoolForTest();
		c2.crashSpoolForTest();
		AccountConnectPlugin c3 = plugin(t2, a2);
		c3.startUp();
		grant(c3);
		assertFalse("an idle older identity is purged at the next grant", Files.exists(d1));
		assertEquals("its own identity's rows are recovered", Collections.singletonList(c2Row), pendingIds(c3));
	}

	@Test
	public void theIndexStillPurgesAnOlderOfflineIdentityAfterANewerOneWasRecorded() throws Exception
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
		a.crashSpoolForTest();
		b.crashSpoolForTest();
		assertEquals("the latest recorded identity is B", EventSpool.identityKey(TOKEN_A, "222"),
			EventSpool.readPointer(spoolRoot()));

		AccountConnectPlugin c = plugin(TOKEN_B, "333");	// neither A nor B's token is available
		c.startUp();
		grant(c);
		assertFalse("the OLDER identity is still purged", Files.exists(aDir));
		assertFalse(Files.exists(bDir));
		assertEquals(Collections.singletonList(EventSpool.identityKey(TOKEN_B, "333")), EventSpool.readIndex(spoolRoot()));
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
		p2.applyServerPolicy(policy("X-Event-Spool", "off"));
		p2.awaitSpoolIdleForTest();
		assertTrue(Files.exists(dir.resolve(EventSpool.MARKER)));
		p1.shutDown();
		p1.awaitSpoolIdleForTest();
		assertFalse("nothing of a withdrawn identity survives a graceful close", Files.exists(dir));
		assertEquals(false, p1.captureHealthSnapshot().get("spool_active"));
	}

	@Test
	public void restartWithAMarkedDirectoryAndNoLiveShardFinishesThePurgeAndANewGrantWorks() throws Exception
	{
		AccountConnectPlugin p1 = plugin(TOKEN_A, "111");
		grant(p1);
		emit(p1, "trade");
		Path dir = identityDir(TOKEN_A, "111");
		Files.write(dir.resolve(EventSpool.MARKER), "{\"v\":1,\"requested_ms\":1}".getBytes(StandardCharsets.UTF_8));
		p1.crashSpoolForTest();

		AccountConnectPlugin p = plugin(TOKEN_A, "111");
		p.startUp();
		grant(p);
		assertFalse("the old marked rows are not recovered", pendingIds(p).size() > 0);
		emit(p, "trade");
		assertEquals(1, pendingIds(p).size());
		assertFalse(Files.exists(dir.resolve(EventSpool.MARKER)));
		assertEquals(true, p.captureHealthSnapshot().get("spool_active"));
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
		assertEquals(false, p1.captureHealthSnapshot().get("spool_active"));
		assertFalse(Files.exists(dir));
		emit(p1, "trade");
		assertFalse("no write after retirement until a new grant", Files.exists(dir));
	}

	@Test
	public void aReplayBatchBuiltBeforeATokenChangeIsNeverSentUnderTheNewToken() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		releaseToReplay(p);
		String aId = pendingIds(p).get(0);
		int before = bodies.size();
		p.spoolReplayBeforeDispatchHookForTest = () ->
		{
			try
			{
				setToken(p, TOKEN_B);	// the token changes after the batch is built, before dispatch
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		};
		p.spoolReplayNotBeforeMs = 0L;
		p.spoolReplayBackoffUntilMs = 0L;
		p.replaySpoolTick();
		p.awaitSpoolIdleForTest();
		Thread.sleep(300);
		p.spoolReplayBeforeDispatchHookForTest = null;
		assertEquals("the old-identity batch is never dispatched", before, bodies.size());
		assertFalse(postedIds().subList(Math.min(before, postedIds().size()), postedIds().size()).contains(aId));
		assertFalse(p.spoolReplayInFlight);
	}

	@Test
	public void healthCarriesTheSpoolCounters() throws Exception
	{
		AccountConnectPlugin p = plugin(TOKEN_A, "12345");
		grant(p);
		emit(p, "trade");
		Map<String, Object> h = p.captureHealthSnapshot();
		for (String k : Arrays.asList("spool_active", "spool_records_pending", "spool_bytes", "spool_oldest_age_s",
			"spool_replayed_total", "spool_acked_total", "spool_dropped_total", "spool_corrupt_total",
			"spool_quarantined_total", "spool_filtered_total", "spool_unacked_2xx_total", "spool_purged_total",
			"spool_append_failed_total"))
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
		p.spoolReplayNotBeforeMs = 0L;
		p.spoolReplayBackoffUntilMs = 0L;
		p.replaySpoolTick();
		Thread.sleep(300);
		p.awaitSpoolIdleForTest();
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
