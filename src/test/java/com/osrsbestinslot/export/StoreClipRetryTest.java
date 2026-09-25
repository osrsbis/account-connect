package com.osrsbestinslot.export;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Store-clip chunk RETRY (finding F-A3, 2026-09-25). One failed POST used to lose the whole chunk
 * silently. These tests drive the real submitStoreClipUpload against a local HTTP stub, with the
 * retry delay seam recording the requested wait and running the retry at once.
 */
public class StoreClipRetryTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";
	private static final Pattern CAPTURED_AT = Pattern.compile("name=\"captured_at\"\\r\\n(?:[^\\r\\n]*\\r\\n)*\\r\\n(\\d+)");

	private HttpServer server;
	private final Map<String, AtomicInteger> requestsPerChunk = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> storedPerChunk = new ConcurrentHashMap<>();
	private final AtomicInteger totalRequests = new AtomicInteger();

	/** A reply: status, body, optional Retry-After. */
	private static final class Reply
	{
		final int code;
		final String body;
		final String retryAfter;

		Reply(int code, String body, String retryAfter)
		{
			this.code = code;
			this.body = body;
			this.retryAfter = retryAfter;
		}
	}

	private static class RecordingPlugin extends AccountConnectPlugin
	{
		final List<Long> delays = Collections.synchronizedList(new ArrayList<>());

		@Override
		void scheduleClipRetry(Runnable retry, long delayMs)
		{
			delays.add(delayMs);
			retry.run();
		}
	}

	@After
	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
		}
	}

	/** Start a stub; `policy` maps (captured_at, attempt number from 1) to the reply. */
	private String startServer(java.util.function.BiFunction<String, Integer, Reply> policy) throws Exception
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange ->
		{
			String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.ISO_8859_1);
			Matcher m = CAPTURED_AT.matcher(body);
			String key = m.find() ? m.group(1) : "?";
			int n = requestsPerChunk.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
			totalRequests.incrementAndGet();
			Reply r = policy.apply(key, n);
			if (r.code == 200 && !r.body.contains("dropped"))
			{
				storedPerChunk.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
			}
			if (r.retryAfter != null)
			{
				exchange.getResponseHeaders().add("Retry-After", r.retryAfter);
			}
			byte[] resp = r.body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(r.code, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/wp-json/osrsbis/v1";
	}

	private static RecordingPlugin plugin(String base) throws Exception
	{
		RecordingPlugin p = new RecordingPlugin();
		inject(p, "config", config(base));
		inject(p, "executor", Executors.newSingleThreadScheduledExecutor());
		inject(p, "okHttpClient", new OkHttpClient());
		return p;
	}

	/** `n` frames, two chunks when n > CLIP_CHUNK_FRAMES, chunk first-frame times 1s apart. */
	private static void upload(AccountConnectPlugin p, int n)
	{
		List<byte[]> frames = new ArrayList<>();
		List<Long> times = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			frames.add(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) i, 1, 2, 3});
			times.add(1_700_000_000_000L + (i / AccountConnectPlugin.CLIP_CHUNK_FRAMES) * 1000L + i);
		}
		p.submitStoreClipUpload(frames, times);
	}

	private static void awaitFinished(AccountConnectPlugin p, int chunks) throws InterruptedException
	{
		long end = System.currentTimeMillis() + 20_000L;
		while (p.clipChunksSent.get() + p.clipChunksLost.get() < chunks
			&& System.currentTimeMillis() < end)
		{
			Thread.sleep(20);
		}
		Thread.sleep(200);	// let any wrong extra retry land, so it is counted
	}

	/** 500 then 200: every chunk is stored exactly once, and its bytes are released. */
	@Test
	public void aChunkThatFailsWith500IsRetriedAndStoredExactlyOnce() throws Exception
	{
		String base = startServer((key, attempt) -> attempt == 1
			? new Reply(500, "{\"ok\":false,\"error\":\"Storage failed.\"}", null)
			: new Reply(200, "{\"ok\":true,\"id\":1}", null));
		RecordingPlugin p = plugin(base);
		upload(p, AccountConnectPlugin.CLIP_CHUNK_FRAMES + 5);	// two chunks
		awaitFinished(p, 2);

		assertEquals("two chunks, two distinct captured_at values", 2, requestsPerChunk.size());
		for (Map.Entry<String, AtomicInteger> e : requestsPerChunk.entrySet())
		{
			assertEquals("chunk " + e.getKey() + ": one failed POST then one retry", 2, e.getValue().get());
			assertEquals("chunk " + e.getKey() + " must be stored exactly once",
				1, storedPerChunk.get(e.getKey()).get());
		}
		assertEquals("both chunks stored", 2, p.clipChunksSent.get());
		assertEquals("nothing lost", 0, p.clipChunksLost.get());
		assertEquals("retry bytes must be released", 0L, p.clipRetryHeldBytes.get());
		assertEquals("first retry waits the first ladder step",
			AccountConnectPlugin.CLIP_CHUNK_RETRY_DELAYS_MS[0], (long) p.delays.get(0));
	}

	/** Every attempt 503: give up after CLIP_CHUNK_ATTEMPTS, and release the bytes. */
	@Test
	public void aChunkGivesUpAfterTheBoundedAttemptsAndReleasesItsMemory() throws Exception
	{
		String base = startServer((key, attempt) -> new Reply(503, "{}", null));
		RecordingPlugin p = plugin(base);
		upload(p, 3);
		awaitFinished(p, 1);

		assertEquals("exactly CLIP_CHUNK_ATTEMPTS POSTs", AccountConnectPlugin.CLIP_CHUNK_ATTEMPTS, totalRequests.get());
		assertEquals("the chunk is counted lost", 1, p.clipChunksLost.get());
		assertEquals("nothing stored", 0, p.clipChunksSent.get());
		assertEquals("retry memory released after giving up", 0L, p.clipRetryHeldBytes.get());
		List<Long> expected = new ArrayList<>();
		for (long d : AccountConnectPlugin.CLIP_CHUNK_RETRY_DELAYS_MS)
		{
			expected.add(d);
		}
		assertEquals("backoff ladder", expected, p.delays);
		long sum = 0;
		for (long d : AccountConnectPlugin.CLIP_CHUNK_RETRY_DELAYS_MS)
		{
			sum += d;
		}
		assertTrue("the retry window must stay bounded (waits " + sum + "ms)", sum <= 10 * 60_000L);
		assertEquals("one delay per retry", AccountConnectPlugin.CLIP_CHUNK_ATTEMPTS - 1,
			AccountConnectPlugin.CLIP_CHUNK_RETRY_DELAYS_MS.length);
	}

	/** A network failure (nothing listening) is retried too. */
	@Test
	public void aNetworkFailureIsRetried() throws Exception
	{
		int port;
		try (ServerSocket s = new ServerSocket(0))
		{
			port = s.getLocalPort();
		}
		RecordingPlugin p = plugin("http://127.0.0.1:" + port);
		upload(p, 3);
		awaitFinished(p, 1);

		assertEquals("a refused connection must be retried the full ladder",
			AccountConnectPlugin.CLIP_CHUNK_ATTEMPTS - 1, p.delays.size());
		assertEquals(1, p.clipChunksLost.get());
		assertEquals(0L, p.clipRetryHeldBytes.get());
	}

	/** A 400 fails the same way forever: exactly one POST, no retry. */
	@Test
	public void a400IsNotRetried() throws Exception
	{
		String base = startServer((key, attempt) -> new Reply(400, "{\"ok\":false,\"error\":\"Frame count mismatch.\"}", null));
		RecordingPlugin p = plugin(base);
		upload(p, 3);
		awaitFinished(p, 1);

		assertEquals("a 400 must be sent once", 1, totalRequests.get());
		assertTrue("no retry may be scheduled for a 400", p.delays.isEmpty());
		assertEquals(1, p.clipChunksLost.get());
		assertEquals(0L, p.clipRetryHeldBytes.get());
	}

	/** A 200 {"dropped":...} is a deliberate refusal: not retried, not counted as stored. */
	@Test
	public void a200RefusalIsNotRetried() throws Exception
	{
		String base = startServer((key, attempt) -> new Reply(200, "{\"ok\":true,\"dropped\":\"not_staff\"}", null));
		RecordingPlugin p = plugin(base);
		upload(p, 3);
		long end = System.currentTimeMillis() + 10_000L;
		while (totalRequests.get() < 1 && System.currentTimeMillis() < end)
		{
			Thread.sleep(20);
		}
		Thread.sleep(500);

		assertEquals("a refusal must be sent once", 1, totalRequests.get());
		assertTrue("no retry for a refusal", p.delays.isEmpty());
		assertEquals("a refusal is not a store", 0, p.clipChunksSent.get());
		assertEquals("a refusal is not a loss either", 0, p.clipChunksLost.get());
	}

	/** 429 with Retry-After: the retry waits at least that long. */
	@Test
	public void a429HonoursRetryAfter() throws Exception
	{
		String base = startServer((key, attempt) -> attempt == 1
			? new Reply(429, "{\"ok\":false}", "100")
			: new Reply(200, "{\"ok\":true,\"id\":2}", null));
		RecordingPlugin p = plugin(base);
		upload(p, 3);
		awaitFinished(p, 1);

		assertEquals("one retry", 1, p.delays.size());
		assertEquals("the retry waits the server's Retry-After", 100_000L, (long) p.delays.get(0));
		assertEquals(1, p.clipChunksSent.get());
		assertEquals(0L, p.clipRetryHeldBytes.get());
	}

	/** 429 without Retry-After falls back to the ladder; one longer than the window gives up. */
	@Test
	public void a429WithoutOrWithAnOverlongRetryAfter() throws Exception
	{
		String base = startServer((key, attempt) -> attempt == 1
			? new Reply(429, "{}", null)
			: new Reply(200, "{\"ok\":true}", null));
		RecordingPlugin p = plugin(base);
		upload(p, 3);
		awaitFinished(p, 1);
		assertEquals(AccountConnectPlugin.CLIP_CHUNK_RETRY_DELAYS_MS[0], (long) p.delays.get(0));
		assertEquals(1, p.clipChunksSent.get());
		stop();

		requestsPerChunk.clear();
		totalRequests.set(0);
		String base2 = startServer((key, attempt) -> new Reply(429, "{}", "3600"));
		RecordingPlugin p2 = plugin(base2);
		upload(p2, 3);
		awaitFinished(p2, 1);
		assertEquals("an hour-long Retry-After is not waited out with the bytes held", 1, totalRequests.get());
		assertTrue(p2.delays.isEmpty());
		assertEquals(1, p2.clipChunksLost.get());
		assertEquals(0L, p2.clipRetryHeldBytes.get());
	}

	/** With the retry byte budget full, a failing chunk is given up instead of held. */
	@Test
	public void aFullRetryBudgetGivesUpInsteadOfHolding() throws Exception
	{
		String base = startServer((key, attempt) -> new Reply(500, "{}", null));
		RecordingPlugin p = plugin(base);
		p.clipRetryHeldBytes.set(AccountConnectPlugin.CLIP_RETRY_BYTE_BUDGET);
		upload(p, 3);
		awaitFinished(p, 1);

		assertEquals("no room: one POST, no retry", 1, totalRequests.get());
		assertTrue(p.delays.isEmpty());
		assertEquals(1, p.clipChunksLost.get());
		assertEquals("the budget is not exceeded", AccountConnectPlugin.CLIP_RETRY_BYTE_BUDGET, p.clipRetryHeldBytes.get());
		assertTrue("the budget is bounded to a small number of visits",
			AccountConnectPlugin.CLIP_RETRY_BYTE_BUDGET <= 3L * AccountConnectPlugin.MAX_CLIP_BURST_BYTES);
	}

	/** A retry after the user linked another token is dropped, never filed under the new token. */
	@Test
	public void aRetryAfterATokenSwapIsNotSent() throws Exception
	{
		String base = startServer((key, attempt) -> new Reply(500, "{}", null));
		final String[] tok = {TEST_TOKEN};
		RecordingPlugin p = new RecordingPlugin()
		{
			@Override
			void scheduleClipRetry(Runnable retry, long delayMs)
			{
				delays.add(delayMs);
				tok[0] = "fedcba9876543210fedcba9876543210";
				retry.run();
			}
		};
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return tok[0];
			}

			@Override
			public String apiBaseUrl()
			{
				return base;
			}
		});
		inject(p, "executor", Executors.newSingleThreadScheduledExecutor());
		inject(p, "okHttpClient", new OkHttpClient());
		upload(p, 3);
		awaitFinished(p, 1);

		assertEquals("the retry must not be sent under the new token", 1, totalRequests.get());
		assertEquals(1, p.clipChunksLost.get());
		assertEquals(0L, p.clipRetryHeldBytes.get());
	}

	// ---- helpers ----

	private static AccountConnectConfig config(String baseUrl)
	{
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TEST_TOKEN;
			}

			@Override
			public String apiBaseUrl()
			{
				return baseUrl;
			}
		};
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static byte[] readAll(InputStream in) throws java.io.IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int n;
		while ((n = in.read(chunk)) != -1)
		{
			out.write(chunk, 0, n);
		}
		return out.toByteArray();
	}
}
