package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 0.7.4 item 2 — BOUNDED RELIABLE EVENT DELIVERY.
 *
 * <p>Before this change {@code flushEvents} cleared the buffer BEFORE sending and {@code onFailure} was
 * a bare {@code log.debug}, so any 429 / 5xx / timeout destroyed that batch silently and permanently.
 * Production evidence: the shared ingest ceiling was saturated for six consecutive hours on 2026-08-14,
 * which is exactly when clients would have been receiving those responses.
 *
 * <p>These tests drive a REAL {@link MockWebServer} over real OkHttp rather than a mocked call, because
 * the defect lives in the callback wiring — a stubbed client would test the stub. Each asserts the whole
 * contract: the batch SURVIVES, it is RETRIED, and on success it is delivered EXACTLY ONCE.
 */
public class EventDeliveryRetryTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private MockWebServer server;
	private AccountConnectPlugin plugin;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		plugin = new AccountConnectPlugin();
		inject("gson", new Gson());
		inject("okHttpClient", new OkHttpClient.Builder()
			.connectTimeout(2, TimeUnit.SECONDS)
			.readTimeout(2, TimeUnit.SECONDS)
			.build());

		AccountConnectConfig config = mock(AccountConnectConfig.class);
		when(config.linkToken()).thenReturn(TOKEN);
		when(config.apiBaseUrl()).thenReturn(server.url("/api").toString().replaceAll("/+$", ""));
		inject("config", config);
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

	/** Seed the buffer directly — this suite tests DELIVERY, not the emit path. */
	private void seed(String type)
	{
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("type", type);
		ev.put("ts", 1_700_000_000_000L);
		synchronized (plugin.pendingEvents)
		{
			plugin.pendingEvents.add(ev);
		}
	}

	private int buffered()
	{
		synchronized (plugin.pendingEvents)
		{
			return plugin.pendingEvents.size();
		}
	}

	/** Clear the backoff so a test can drive consecutive attempts without sleeping. */
	private void clearBackoff()
	{
		plugin.eventRetryBackoffUntilMs = 0L;
	}

	// ---------------------------------------------------------------- 5xx

	@Test
	public void serverErrorRequeuesTheBatchAndASubsequentFlushDeliversItExactlyOnce() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(503));
		server.enqueue(new MockResponse().setResponseCode(200));

		seed("trade");
		plugin.flushEvents();
		RecordedRequest first = server.takeRequest(3, TimeUnit.SECONDS);
		assertNotNull("the first POST should have been made", first);

		// The batch must be BACK in the buffer, not destroyed.
		waitFor(() -> buffered() == 1, "batch requeued after 503");
		assertTrue("a failure must arm the backoff", plugin.eventRetryBackoffUntilMs > 0);

		clearBackoff();
		plugin.flushEvents();
		RecordedRequest second = server.takeRequest(3, TimeUnit.SECONDS);
		assertNotNull("the batch should have been retried", second);
		assertTrue("the retry must carry the same event", second.getBody().readUtf8().contains("trade"));

		// Delivered: buffer drains and stays drained — exactly one successful delivery.
		waitFor(() -> buffered() == 0, "buffer drained after 200");
		plugin.flushEvents();
		assertNull("no third POST — an empty buffer must not resend",
			server.takeRequest(1, TimeUnit.SECONDS));
		assertEquals("exactly 2 requests total: the failure and the successful retry",
			2, server.getRequestCount());
	}

	// ---------------------------------------------------------------- 429

	@Test
	public void rateLimitRequeuesAndHonoursRetryAfter() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "42"));

		seed("store_buy");
		plugin.flushEvents();
		assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));

		waitFor(() -> buffered() == 1, "batch requeued after 429");
		// 42s from the server beats the 5s base rung of the ladder, and is under the 300s ceiling.
		long waitMs = plugin.eventRetryBackoffUntilMs - plugin.nowMs();
		assertTrue("Retry-After should push the next attempt out to ~42s, got " + waitMs + "ms",
			waitMs > 30_000L && waitMs <= 42_000L);

		// While backing off, a flush must NOT fire — that is the point of the backoff.
		plugin.flushEvents();
		assertNull("no POST while backing off", server.takeRequest(1, TimeUnit.SECONDS));
		assertEquals(1, buffered());
	}

	// ---------------------------------------------------------------- network failure

	@Test
	public void networkFailureRequeuesTheBatch() throws Exception
	{
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

		seed("ge_buy");
		plugin.flushEvents();

		waitFor(() -> buffered() == 1, "batch requeued after a network failure");
		assertTrue("a network failure must arm the backoff", plugin.eventRetryBackoffUntilMs > 0);
	}

	// ---------------------------------------------------------------- ordering + bound

	@Test
	public void requeuePreservesChronologicalOrderAgainstEventsLoggedDuringTheFlight() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(500));

		seed("older");
		plugin.flushEvents();
		assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
		waitFor(() -> buffered() >= 1, "requeued");

		seed("newer");	// logged after the failed batch was taken
		synchronized (plugin.pendingEvents)
		{
			assertEquals("the requeued (older) event must sit in FRONT of the newer one",
				"older", plugin.pendingEvents.get(0).get("type"));
			assertEquals("newer", plugin.pendingEvents.get(1).get("type"));
		}
	}

	@Test
	public void requeueIsBoundedByMaxPendingEventsSoAnOutageCannotGrowMemory() throws Exception
	{
		// A batch larger than the cap: the requeue must drop the OLDEST, exactly like logEvent does.
		List<Map<String, Object>> big = new ArrayList<>();
		for (int i = 0; i < 600; i++)
		{
			Map<String, Object> ev = new LinkedHashMap<>();
			ev.put("type", "e" + i);
			big.add(ev);
		}
		plugin.requeueEvents(big, 0L);

		assertEquals("requeue must respect MAX_PENDING_EVENTS (500)", 500, buffered());
		synchronized (plugin.pendingEvents)
		{
			assertEquals("the OLDEST should have been dropped, keeping the newest 500",
				"e100", plugin.pendingEvents.get(0).get("type"));
			assertEquals("e599", plugin.pendingEvents.get(499).get("type"));
		}
	}

	@Test
	public void backoffLadderDoublesAndIsCapped() throws Exception
	{
		List<Map<String, Object>> one = new ArrayList<>();
		one.add(new LinkedHashMap<>());

		plugin.requeueEvents(one, 0L);
		assertEquals("first rung is the 5s flush period", 5_000L, plugin.eventRetryBackoffMs);
		plugin.requeueEvents(one, 0L);
		assertEquals(10_000L, plugin.eventRetryBackoffMs);
		plugin.requeueEvents(one, 0L);
		assertEquals(20_000L, plugin.eventRetryBackoffMs);

		for (int i = 0; i < 20; i++)
		{
			plugin.requeueEvents(one, 0L);
		}
		assertEquals("the ladder must cap at 5 minutes", 300_000L, plugin.eventRetryBackoffMs);
	}

	// ---------------------------------------------------------------- non-retryable

	@Test
	public void nonRetryableClientErrorDropsTheBatchRatherThanLoopingForever() throws Exception
	{
		// A 400 would fail identically on every retry (malformed body / bad token), so retrying it
		// would be an infinite loop against the server. It is dropped deliberately.
		server.enqueue(new MockResponse().setResponseCode(400));

		seed("trade");
		plugin.flushEvents();
		assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));

		waitFor(() -> buffered() == 0, "a 400 drops the batch");
		assertEquals("a non-retryable failure must NOT arm the backoff", 0L, plugin.eventRetryBackoffUntilMs);
	}

	@Test
	public void successResetsTheBackoffLadder() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(503));
		server.enqueue(new MockResponse().setResponseCode(200));

		seed("trade");
		plugin.flushEvents();
		assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
		waitFor(() -> plugin.eventRetryBackoffMs == 5_000L, "ladder armed");

		clearBackoff();
		plugin.flushEvents();
		assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
		waitFor(() -> plugin.eventRetryBackoffMs == 0L, "a delivery resets the ladder");
	}

	// ---------------------------------------------------------------- helper

	/** OkHttp callbacks land on another thread; poll briefly rather than sleeping a fixed time. */
	private void waitFor(java.util.function.BooleanSupplier cond, String what) throws Exception
	{
		long deadline = System.currentTimeMillis() + 3_000L;
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
