package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PRODUCTION-EQUIVALENT INGEST CONTRACT — opt-in, network-dependent.
 *
 * <p>Everything else in this suite runs against a local MockWebServer. This class additionally checks
 * the payload the plugin actually builds against the REAL production ingest endpoint, because a locally
 * green test proves the client is self-consistent, not that the server accepts what it emits.
 *
 * <p><b>It writes nothing.</b> The token used is a syntactically valid but unowned 32-hex string, so
 * production replies {@code {"ok":true,"stored":0}} — the request is parsed and accepted, and zero rows
 * are persisted. That is exactly the signal wanted: schema acceptance without touching customer data.
 *
 * <p>Skipped unless {@code -DrunProdContract=true}, so the normal offline build stays hermetic and CI
 * never depends on the network.
 */
public class ProductionIngestContractTest
{
	private static final String PROD = "https://www.osrsbestinslot.com/wp-json/osrsbis/v1";
	/** Valid shape, owned by nobody → parsed and accepted, stored:0. */
	private static final String UNOWNED_TOKEN = "0123456789abcdef0123456789abcdef";

	private AccountConnectPlugin plugin;
	private MockWebServer proxy;

	/**
	 * Opt-in switch. Reads an ENV VAR, not a system property: Gradle forks the test JVM and does not
	 * forward {@code -D} flags to it unless build.gradle explicitly wires them, so a system-property
	 * gate silently evaluated false and these tests reported PASS in 0.376s without executing a single
	 * assertion — a false green, which is worse than no test. Env vars are inherited by the fork.
	 */
	private static boolean enabled()
	{
		return "true".equals(System.getenv("RUN_PROD_CONTRACT"));
	}

	/** Fails loudly if an enabled run somehow skips, so a silent no-op can never look like a pass again. */
	private static void requireEnabled()
	{
		if (!enabled())
		{
			throw new IllegalStateException("prod contract test ran without RUN_PROD_CONTRACT=true");
		}
	}

	@Before
	public void setUp() throws Exception
	{
		plugin = new AccountConnectPlugin();
		inject("gson", new Gson());
		inject("okHttpClient", new OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(10, TimeUnit.SECONDS)
			.build());
	}

	@After
	public void tearDown() throws Exception
	{
		if (proxy != null)
		{
			proxy.shutdown();
		}
	}

	private void inject(String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void pointAt(String baseUrl)
	{
		AccountConnectConfig config = mock(AccountConnectConfig.class);
		when(config.linkToken()).thenReturn(UNOWNED_TOKEN);
		when(config.apiBaseUrl()).thenReturn(baseUrl);
		try
		{
			inject("config", config);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	private void seed(String type, Map<String, Object> fields)
	{
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("type", type);
		ev.put("ts", System.currentTimeMillis());
		ev.putAll(fields);
		synchronized (plugin.pendingEvents)
		{
			plugin.pendingEvents.add(ev);
		}
	}

	/**
	 * The 0.7.4 trade payload — counterparty + itemised received[] — is ACCEPTED by production.
	 *
	 * <p>This is the schema half of the trade validation. It proves the server takes the new fields
	 * (which it does opaquely: `fields` is stored as a JSON blob, so no server change is needed). It does
	 * NOT prove the widget read works in a live client — only a genuine two-sided in-game trade can, and
	 * that is recorded as a separate manual step.
	 */
	@Test
	public void productionAcceptsTheNew074TradePayload() throws Exception
	{
		if (!enabled())
		{
			return;
		}
		requireEnabled();
		pointAt(PROD);
		Map<String, Object> fields = new LinkedHashMap<>();
		List<Map<String, Object>> given = new ArrayList<>();
		Map<String, Object> g = new LinkedHashMap<>();
		g.put("id", 995);
		g.put("qty", 1_000_000);
		given.add(g);
		List<Map<String, Object>> received = new ArrayList<>();
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("id", 4151);
		r.put("qty", 1);
		received.add(r);
		fields.put("given", given);
		fields.put("received", received);		// 0.7.4: itemised, was always []
		fields.put("counterparty", "ZZZ_CONTRACT_TEST");	// 0.7.4: was never present
		seed("trade", fields);

		plugin.flushEvents();
		Thread.sleep(4000);

		synchronized (plugin.pendingEvents)
		{
			assertTrue("a 2xx from production must drain the buffer — if this fails the payload was "
				+ "rejected and requeued", plugin.pendingEvents.isEmpty());
		}
		assertEquals("a successful delivery resets the backoff ladder", 0L, plugin.eventRetryBackoffMs);
	}

	/**
	 * The 0.7.4 store payload with an exact gp_total and NO unit_price_gp (the merged-quantity case) is
	 * accepted by production.
	 */
	@Test
	public void productionAcceptsTheMergedStorePayload() throws Exception
	{
		if (!enabled())
		{
			return;
		}
		requireEnabled();
		pointAt(PROD);
		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			"store_buy", 453, 20, 1_000_000L, 998_000L, false, true);
		assertEquals(2_000L, fields.get("gp_total"));
		assertTrue("merged qty must carry no unit price", !fields.containsKey("unit_price_gp"));
		seed("store_buy", fields);

		plugin.flushEvents();
		Thread.sleep(4000);

		synchronized (plugin.pendingEvents)
		{
			assertTrue("production must accept the merged store payload", plugin.pendingEvents.isEmpty());
		}
	}

	/**
	 * REAL-WORLD RETRY: a forced 429 in front of the REAL production endpoint.
	 *
	 * <p>A local proxy returns 429 for the first attempt and transparently forwards the second to
	 * production. So the failure is injected, but the eventual delivery is a genuine production accept —
	 * proving the batch survives a rate-limit against the live server and lands exactly once.
	 */
	@Test
	public void forced429InFrontOfProductionRetriesAndLandsExactlyOnce() throws Exception
	{
		if (!enabled())
		{
			return;
		}
		requireEnabled();
		final AtomicInteger attempts = new AtomicInteger();
		final List<String> bodies = new ArrayList<>();
		proxy = new MockWebServer();
		proxy.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				int n = attempts.incrementAndGet();
				synchronized (bodies)
				{
					bodies.add(request.getBody().readUtf8());
				}
				if (n == 1)
				{
					return new MockResponse().setResponseCode(429).setHeader("Retry-After", "1");
				}
				// Second attempt: forward the real payload to production and mirror its status.
				try
				{
					String body;
					synchronized (bodies)
					{
						body = bodies.get(bodies.size() - 1);
					}
					okhttp3.OkHttpClient c = new okhttp3.OkHttpClient();
					okhttp3.Request fwd = new okhttp3.Request.Builder()
						.url(PROD + "/event-ingest")
						.post(okhttp3.RequestBody.create(
							okhttp3.MediaType.parse("application/json"), body))
						.build();
					try (okhttp3.Response resp = c.newCall(fwd).execute())
					{
						String payload = resp.body() == null ? "" : resp.body().string();
						return new MockResponse().setResponseCode(resp.code()).setBody(payload);
					}
				}
				catch (Exception e)
				{
					return new MockResponse().setResponseCode(500);
				}
			}
		});
		proxy.start();
		pointAt(proxy.url("/api").toString().replaceAll("/+$", ""));

		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("skill", "Attack");
		fields.put("level", 99);
		seed("level_up", fields);

		plugin.flushEvents();				// attempt 1 → 429
		Thread.sleep(2000);
		synchronized (plugin.pendingEvents)
		{
			assertEquals("the batch must survive the 429", 1, plugin.pendingEvents.size());
		}
		assertTrue("the 429 must arm the backoff", plugin.eventRetryBackoffUntilMs > 0);

		plugin.eventRetryBackoffUntilMs = 0L;	// jump the wait rather than sleeping it out
		plugin.flushEvents();				// attempt 2 → forwarded to production
		Thread.sleep(5000);

		synchronized (plugin.pendingEvents)
		{
			assertTrue("production accepted the retry, so the buffer must be empty",
				plugin.pendingEvents.isEmpty());
		}
		assertEquals("exactly two attempts: the refused one and the accepted one", 2, attempts.get());
		synchronized (bodies)
		{
			assertNotNull(bodies.get(0));
			assertEquals("both attempts must carry the same single event", bodies.get(0), bodies.get(1));
		}
	}
}
