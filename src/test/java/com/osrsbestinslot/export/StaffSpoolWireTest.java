package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The staff journal against recordings of the 0.7.72 client (the separate replay sender, before the journal used the
 * one sender). The fixture under {@code staff-spool-0772/} was written by that client running the scenario below:
 * its request bodies, its spool state and counters after each answer, and the shard files it left on disk when it
 * was killed. This client must send the same bytes, settle the same rows and read that on-disk spool.
 *
 * <p>Set {@code STAFF_SPOOL_GOLDEN_OUT} to a directory to record the fixture again instead of comparing.
 */
public class StaffSpoolWireTest
{
	static final String TOKEN = EventSpoolPluginTest.TOKEN_A;
	static final String ACCOUNT = "12345";
	/** A fixed wall clock for the spool, so created_ms and the 48-hour bound do not depend on the day the test runs. */
	static final long T0 = 1_762_990_000_000L;
	private static final String FIXTURE = "staff-spool-0772/";

	private MockWebServer server;
	private Path pluginData;
	private final List<AccountConnectPlugin> plugins = new ArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private final LinkedBlockingQueue<Function<String, MockResponse>> script = new LinkedBlockingQueue<>();

	@Before
	public void setUp() throws Exception
	{
		pluginData = Files.createTempDirectory("staff-spool-wire");
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
		for (AccountConnectPlugin p : plugins)
		{
			SpoolHooks.crash(p.spooler);
		}
		server.shutdown();
		EventSpoolCodecTest.deleteTree(pluginData);
	}

	// ------------------------------------------------------------------ the recorded scenario

	private AccountConnectPlugin staffClient() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		EventSpoolPluginTest.inject(p, "gson", new Gson());
		EventSpoolPluginTest.inject(p, "okHttpClient", new OkHttpClient.Builder()
			.connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build());
		AccountConnectConfig config = mock(AccountConnectConfig.class);
		when(config.linkToken()).thenReturn(TOKEN);
		String base = server.url("/api").toString().replaceAll("/+$", "");
		when(config.apiBaseUrl()).thenReturn(base);
		EventSpoolPluginTest.inject(p, "config", config);
		EventSpoolPluginTest.inject(p, "activeHash", ACCOUNT);
		EventSpoolPluginTest.inject(p, "activeRsn", "Staff One");
		SpoolHooks.dataRoot(p.spooler, pluginData);
		p.spooler.wallClock = () -> T0 + 60_000L;
		plugins.add(p);
		return p;
	}

	private static void grant(AccountConnectPlugin p) throws Exception
	{
		p.grants.applyServerPolicy(new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
			.protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Spool", "on").build());
		SpoolHooks.awaitIdle(p.spooler);
		// The staff capture answer, as for every staff client: the spool is granted only to staff tokens.
		p.grants.staffApiBase = p.config.apiBaseUrl();
		p.grants.setStaffCapture(TOKEN, ACCOUNT);
		assertTrue(p.grants.captureEnabled());
	}

	private static Map<String, Object> f(Object... kv)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	private static void emit(AccountConnectPlugin p, String type, Map<String, Object> fields)
	{
		p.events.emitEvent(type, fields);
	}

	private void flush(AccountConnectPlugin p, Function<String, MockResponse> answer) throws Exception
	{
		SpoolHooks.awaitIdle(p.spooler);
		int before = bodies.size();
		script.add(answer);
		p.events.flushEvents();
		EventSpoolPluginTest.waitFor(() -> bodies.size() > before && !p.events.eventPostInFlight, "flush answered");
		SpoolHooks.awaitIdle(p.spooler);
	}

	/** A 2xx with per-id results by position in the request; a null status leaves that id out. */
	private static Function<String, MockResponse> byPosition(String... statuses)
	{
		return body ->
		{
			List<String> ids = EventSpoolPluginTest.idsIn(body);
			JsonArray arr = new JsonArray();
			for (int i = 0; i < ids.size(); i++)
			{
				String status = i < statuses.length ? statuses[i] : "stored";
				if (status == null)
				{
					continue;
				}
				JsonObject r = new JsonObject();
				r.addProperty("event_id", ids.get(i));
				r.addProperty("status", status);
				arr.add(r);
			}
			JsonObject o = new JsonObject();
			o.addProperty("ok", true);
			o.add("results", arr);
			return new MockResponse().setResponseCode(200).setBody(o.toString());
		};
	}

	/** The live part: rows emitted, sent by the memory path and answered every way the ack contract knows. */
	private void live(AccountConnectPlugin p) throws Exception
	{
		emit(p, "login", f("ts", T0 + 1, "world", 302));
		emit(p, "trade", f("ts", T0 + 2, "partner", "Other Name",
			"given", Collections.singletonList(f("id", 995, "qty", 1_500_000L)),
			"received", Collections.singletonList(f("id", 4151, "qty", 1, "name", "Abyssal whip"))));
		emit(p, "chat", f("ts", T0 + 3, "text", "hello é ✓"));
		emit(p, "ge_offer", f("ts", T0 + 4, "slot", 3, "item", 11802, "qty", 1, "price", 2_147_483_648L,
			"state", "BUYING", "ratio", 0.25));
		emit(p, "xp_gain", f("ts", T0 + 5, "skill", "Attack", "xp", 1234));
		emit(p, "bank_visit_manifest_chunk", f("ts", T0 + 6, "chunk", 0,
			"items", Arrays.asList(Arrays.asList(995L, 5000L), Arrays.asList(4151L, 1L))));
		emit(p, "drop", f("ts", T0 + 7, "item", 4151, "qty", 1, "x", 3200, "y", 3201, "plane", 0));
		emit(p, "logout", f("ts", T0 + 8, "reason", "manual"));
		flush(p, byPosition("stored", "duplicate", "stored", "filtered", "stored", "weird", null, "stored"));

		emit(p, "trade", f("ts", T0 + 9, "partner", "Second Name"));
		emit(p, "store_sell", f("ts", T0 + 10, "item", 1215, "qty", 2, "price", 30_000));
		flush(p, b -> new MockResponse().setResponseCode(503).setHeader("Retry-After", "1"));
		p.events.eventRetryBackoffUntilMs = 0L;
		flush(p, b -> new MockResponse().setResponseCode(400));

		emit(p, "pickup", f("ts", T0 + 11, "item", 995, "qty", 12));
		emit(p, "ge_buy", f("ts", T0 + 12, "item", 11802, "qty", 1, "gp", 2_147_483_648L));
		flush(p, EventSpoolPluginTest::legacyStored);

		for (int i = 0; i < 120; i++)
		{
			emit(p, "ge_progress", f("ts", T0 + 100 + i, "slot", i % 8, "n", i));
		}
		flush(p, EventSpoolPluginTest::legacyStored);
		flush(p, EventSpoolPluginTest::legacyStored);
	}

	/** The restarted client: every row the killed one left is sent, and acked, before the disk is empty. */
	private void replay(AccountConnectPlugin p) throws Exception
	{
		for (int round = 0; round < 6 && !SpoolHooks.pendingIds(p.spooler).isEmpty(); round++)
		{
			SpoolHooks.resend(p);
		}
	}

	private static final Pattern EVENT_ID = Pattern.compile("\"event_id\":\"([0-9a-f]{32})\"");
	private static final List<String> COUNTERS = Arrays.asList("spool_active", "spool_records_pending", "spool_bytes",
		"spool_replayed_total", "spool_acked_total", "spool_dropped_total", "spool_corrupt_total",
		"spool_quarantined_total", "spool_filtered_total", "spool_unacked_2xx_total", "spool_purged_total",
		"spool_append_failed_total", "spool_gate_timeout_total", "spool_replay_aged_at_dispatch_total",
		"events_queued_total", "events_sent_total", "events_lost_total", "events_pending");

	/** The bodies with each generated event_id replaced by the order it first appeared in. */
	private static String normalized(List<String> raw, Map<String, String> ordinals)
	{
		StringBuilder out = new StringBuilder();
		for (String body : raw)
		{
			Matcher m = EVENT_ID.matcher(body);
			StringBuffer sb = new StringBuffer();
			while (m.find())
			{
				String id = m.group(1);
				ordinals.putIfAbsent(id, "#" + ordinals.size());
				m.appendReplacement(sb, Matcher.quoteReplacement("\"event_id\":\"" + ordinals.get(id) + "\""));
			}
			m.appendTail(sb);
			out.append(sb).append('\n');
		}
		return out.toString();
	}

	private static String state(AccountConnectPlugin p, Map<String, String> ordinals) throws Exception
	{
		List<String> pending = new ArrayList<>();
		for (String id : SpoolHooks.pendingIds(p.spooler))
		{
			pending.add(ordinals == null ? id : ordinals.getOrDefault(id, "?" + id));
		}
		Map<String, Object> h = p.events.captureHealthSnapshot();
		StringBuilder sb = new StringBuilder("STATE pending=").append(pending);
		for (String k : COUNTERS)
		{
			sb.append(' ').append(k).append('=').append(h.get(k));
		}
		return sb.append('\n').toString();
	}

	private Path identityDir()
	{
		return pluginData.resolve("osrsbis").resolve("spool").resolve(EventSpool.identityKey(TOKEN, ACCOUNT));
	}

	private static String resource(String name) throws Exception
	{
		try (InputStream in = StaffSpoolWireTest.class.getResourceAsStream(FIXTURE + name))
		{
			if (in == null)
			{
				throw new AssertionError("missing fixture " + FIXTURE + name);
			}
			byte[] b = new byte[in.available()];
			int n = 0;
			while (n < b.length)
			{
				int r = in.read(b, n, b.length - n);
				if (r < 0)
				{
					break;
				}
				n += r;
			}
			return new String(b, 0, n, StandardCharsets.ISO_8859_1);
		}
	}

	private static Path goldenOut()
	{
		String out = System.getenv("STAFF_SPOOL_GOLDEN_OUT");
		return out == null || out.isEmpty() ? null : Paths.get(out);
	}

	private static void write(Path dir, String name, String text) throws Exception
	{
		Files.createDirectories(dir);
		Files.write(dir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
	}

	// ------------------------------------------------------------------ tests

	/**
	 * Same wire: for the same rows and the same server answers, every request body (token, envelope, field order,
	 * number encoding, event_id) and every settlement (which rows stay on disk, every spool counter) equals what the
	 * 0.7.72 client did. Only the generated event_id values are numbered, since they are random per run.
	 */
	@Test
	public void liveRequestsAndSettlementAreByteIdenticalToThe0772Client() throws Exception
	{
		AccountConnectPlugin p = staffClient();
		grant(p);
		live(p);
		Map<String, String> ordinals = new LinkedHashMap<>();
		String recorded = normalized(bodies, ordinals) + state(p, ordinals);
		Path out = goldenOut();
		if (out != null)
		{
			write(out, "live.txt", recorded);
			return;
		}
		assertEquals(new String(resource("live.txt").getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8), recorded);
	}

	/**
	 * Upgrade: the spool the 0.7.72 client left when it was killed (unacked, unknown-status and never-answered rows,
	 * 124 in all) is read by this client and every row is sent exactly once, in the same request bodies the 0.7.72
	 * replay sent, then acked and deleted. Nothing is lost and nothing is sent twice.
	 */
	@Test
	public void aSpoolWrittenByThe0772ClientIsSentExactlyOnceInTheSameBodiesAfterUpgrade() throws Exception
	{
		Path out = goldenOut();
		Path dir = identityDir();
		if (out == null)
		{
			Files.createDirectories(dir);
			for (String name : resource("shards.txt").trim().split("\n"))
			{
				Files.write(dir.resolve(name), resource(name).getBytes(StandardCharsets.ISO_8859_1));
			}
		}
		else
		{
			AccountConnectPlugin old = staffClient();
			grant(old);
			live(old);
			SpoolHooks.crash(old.spooler);
			bodies.clear();
			List<String> names = new ArrayList<>();
			try (Stream<Path> files = Files.list(dir))
			{
				for (Path file : files.sorted().collect(Collectors.toList()))
				{
					if (file.getFileName().toString().endsWith(".spl"))
					{
						names.add(file.getFileName().toString());
						Files.createDirectories(out);
						Files.copy(file, out.resolve(file.getFileName().toString()));
					}
				}
			}
			write(out, "shards.txt", String.join("\n", names) + "\n");
		}
		AccountConnectPlugin p = staffClient();
		p.startUp();
		grant(p);
		List<String> onDisk = SpoolHooks.pendingIds(p.spooler);
		replay(p);
		StringBuilder recorded = new StringBuilder();
		for (String body : bodies)
		{
			recorded.append(body).append('\n');
		}
		recorded.append(state(p, null));
		if (out != null)
		{
			write(out, "replay.txt", recorded.toString());
			return;
		}
		assertEquals(124, onDisk.size());
		List<String> posted = new ArrayList<>();
		for (String body : bodies)
		{
			posted.addAll(EventSpoolPluginTest.idsIn(body));
		}
		assertEquals("every row of the old spool is sent exactly once, in its order", onDisk, posted);
		assertTrue("and acked", SpoolHooks.pendingIds(p.spooler).isEmpty());
		assertEquals(new String(resource("replay.txt").getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8),
			recorded.toString());
	}
}
