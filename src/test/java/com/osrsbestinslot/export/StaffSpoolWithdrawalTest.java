package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
 * A staged staff row the server withdrew while its batch was in flight is never sent again, never written to the
 * plaintext exit file, and a staged row dropped at the flush is counted once (by the spool). Independent review of
 * the 0.7.72 candidate, findings 1 and 2.
 */
public class StaffSpoolWithdrawalTest
{
	static final String TOKEN = EventSpoolPluginTest.TOKEN_A;
	static final String ACCOUNT = "12345";
	static final long T0 = 1_762_990_000_000L;

	private MockWebServer server;
	private Path pluginData;
	private final List<AccountConnectPlugin> plugins = new ArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private final LinkedBlockingQueue<Function<String, MockResponse>> script = new LinkedBlockingQueue<>();

	@Before
	public void setUp() throws Exception
	{
		pluginData = Files.createTempDirectory("staff-spool-withdrawal");
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				String body = request.getBody().readUtf8();
				bodies.add(body);
				Function<String, MockResponse> f = script.poll();
				return (f == null ? (Function<String, MockResponse>) EventSpoolPluginTest::ackAll : f).apply(body);
			}
		});
		server.start();
	}

	@After
	public void tearDown() throws Exception
	{
		for (AccountConnectPlugin p : plugins) SpoolHooks.crash(p.spooler);
		server.shutdown();
		EventSpoolCodecTest.deleteTree(pluginData);
	}

	private AccountConnectPlugin staffClient() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		EventSpoolPluginTest.inject(p, "gson", new Gson());
		EventSpoolPluginTest.inject(p, "okHttpClient", new OkHttpClient.Builder()
			.connectTimeout(2, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build());
		AccountConnectConfig config = mock(AccountConnectConfig.class);
		when(config.linkToken()).thenReturn(TOKEN);
		when(config.apiBaseUrl()).thenReturn(server.url("/api").toString().replaceAll("/+$", ""));
		EventSpoolPluginTest.inject(p, "config", config);
		EventSpoolPluginTest.inject(p, "activeHash", ACCOUNT);
		EventSpoolPluginTest.inject(p, "activeRsn", "Staff One");
		SpoolHooks.dataRoot(p.spooler, pluginData);
		p.spooler.wallClock = () -> T0 + 60_000L;
		plugins.add(p);
		return p;
	}

	private static void spool(AccountConnectPlugin p, String value) throws Exception
	{
		p.grants.applyServerPolicy(new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
			.protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Spool", value).build());
		SpoolHooks.awaitIdle(p.spooler);
		p.grants.staffApiBase = p.config.apiBaseUrl();
		p.grants.setStaffCapture(TOKEN, ACCOUNT);
		assertTrue(p.grants.captureEnabled());
	}

	private static Map<String, Object> f(Object... kv)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private void flush(AccountConnectPlugin p, Function<String, MockResponse> answer) throws Exception
	{
		SpoolHooks.awaitIdle(p.spooler);
		int before = bodies.size();
		script.add(answer);
		p.events.eventRetryBackoffUntilMs = 0L;
		p.events.flushEvents();
		EventSpoolPluginTest.waitFor(() -> bodies.size() > before && !p.events.eventPostInFlight, "flush answered");
		SpoolHooks.awaitIdle(p.spooler);
	}

	private static int restagedInMemory(AccountConnectPlugin p)
	{
		int n = 0;
		synchronized (p.events.pendingEvents)
		{
			for (Map<String, Object> ev : p.events.pendingEvents) if (ev.containsKey(SpoolDriver.EVENT_STAFF_SPOOL)) n++;
		}
		return n;
	}

	private void heldFailedBatchThenWithdraw(AccountConnectPlugin p) throws Exception
	{
		CountDownLatch inFlight = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		int before = bodies.size();
		script.add(body ->
		{
			inFlight.countDown();
			try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
			return new MockResponse().setResponseCode(503);
		});
		p.events.eventRetryBackoffUntilMs = 0L;
		p.events.flushEvents();
		assertTrue(inFlight.await(5, TimeUnit.SECONDS));
		p.grants.applyServerPolicy(new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
			.protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Spool", "off").build());
		SpoolHooks.awaitIdle(p.spooler);
		assertTrue("the withdrawal purged the disk rows", SpoolHooks.pendingIds(p.spooler).isEmpty());
		release.countDown();
		EventSpoolPluginTest.waitFor(() -> bodies.size() > before && !p.events.eventPostInFlight, "503 answered");
		SpoolHooks.awaitIdle(p.spooler);
	}

	@Test
	public void withdrawnRowsInAFailedInFlightBatchAreNeverSentAfterARegrant() throws Exception
	{
		AccountConnectPlugin p = staffClient();
		spool(p, "on");
		for (int i = 0; i < 3; i++) p.events.emitEvent("trade", f("ts", T0 + i, "counterparty", "Other " + i));
		flush(p, EventSpoolPluginTest::legacyStored);
		List<String> durable = SpoolHooks.pendingIds(p.spooler);
		assertEquals(3, durable.size());
		p.spooler.spoolReplayBackoffUntilMs = 0L;
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals(3, restagedInMemory(p));
		heldFailedBatchThenWithdraw(p);
		spool(p, "on");
		int sentBefore = bodies.size();
		p.events.emitEvent("trade", f("ts", T0 + 10, "counterparty", "Later"));	// one live row, so a request goes out
		flush(p, EventSpoolPluginTest::ackAll);
		int resent = 0;
		for (int i = sentBefore; i < bodies.size(); i++) for (String id : durable) if (bodies.get(i).contains(id)) resent++;
		assertEquals("a withdrawn row is never sent again", 0, resent);
		assertEquals("no withdrawn staged row is left in memory", 0, restagedInMemory(p));
	}

	@Test
	public void withdrawnStagedRowsNeverReachTheExitFileOrALaterStart() throws Exception
	{
		AccountConnectPlugin p = staffClient();
		spool(p, "on");
		for (int i = 0; i < 2; i++) p.events.emitEvent("trade", f("ts", System.currentTimeMillis() - 1000 + i, "counterparty", "Other " + i));
		flush(p, EventSpoolPluginTest::legacyStored);
		List<String> durable = SpoolHooks.pendingIds(p.spooler);
		assertEquals(2, durable.size());
		p.spooler.spoolReplayBackoffUntilMs = 0L;
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		heldFailedBatchThenWithdraw(p);
		p.events.persistPendingEvents();
		Path exit = pluginData.resolve("osrsbis").resolve(EventQueue.EXIT_PENDING_FILE);
		String text = Files.exists(exit) ? new String(Files.readAllBytes(exit), java.nio.charset.StandardCharsets.UTF_8) : "";
		assertTrue("no staged row in the exit file", !text.contains(SpoolDriver.EVENT_STAFF_SPOOL) && !text.contains("counterparty"));
		for (String id : durable) assertTrue("withdrawn id " + id + " not in the exit file", !text.contains(id));
		AccountConnectPlugin q = staffClient();
		q.events.restorePendingEvents();
		int sentBefore = bodies.size();
		script.add(EventSpoolPluginTest::ackAll);
		q.events.eventRetryBackoffUntilMs = 0L;
		q.events.flushEvents();
		SpoolHooks.awaitIdle(q.spooler);
		EventSpoolPluginTest.waitFor(() -> !q.events.eventPostInFlight, "flush settled");
		for (int i = sentBefore; i < bodies.size(); i++) for (String id : durable) assertTrue("withdrawn id sent by a later start", !bodies.get(i).contains(id));
	}

	@Test
	public void aStagedRowDroppedAtTheFlushIsCountedOnceByTheSpool() throws Exception
	{
		AccountConnectPlugin p = staffClient();
		spool(p, "on");
		p.events.emitEvent("store_taken", f("ts", T0, "item", 4151, "qty", 1, "taker", "Other"));
		flush(p, EventSpoolPluginTest::legacyStored);
		assertEquals(1, SpoolHooks.pendingIds(p.spooler).size());
		p.spooler.spoolReplayBackoffUntilMs = 0L;
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals(1, restagedInMemory(p));
		long lost = ((Number) p.events.captureHealthSnapshot().get("events_lost_total")).longValue();
		p.grants.clearStaffCaptureGrant();
		p.events.eventRetryBackoffUntilMs = 0L;
		p.events.flushEvents();
		SpoolHooks.awaitIdle(p.spooler);
		long lostAfter = ((Number) p.events.captureHealthSnapshot().get("events_lost_total")).longValue();
		p.spooler.pumpSpool();
		SpoolHooks.awaitIdle(p.spooler);
		assertEquals("a staged row is not an event-counter loss", 0, lostAfter - lost);
		assertEquals("it is quarantined once by the spool", 1L, ((Number) p.events.captureHealthSnapshot().get("spool_quarantined_total")).longValue());
	}
}
