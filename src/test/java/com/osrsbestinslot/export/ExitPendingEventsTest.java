package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.runelite.client.events.ClientShutdown;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Events queued in memory when the client closes while the server answers 429 (the global event hour is
 * full) were lost: 11 events of one session were never sent (2026-10-11). The close now writes what the
 * final batch could not deliver, and the next start sends it.
 */
public class ExitPendingEventsTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final String OTHER = "fedcba9876543210fedcba9876543210";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private MockWebServer server;
	private Path data;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		data = tmp.newFolder("plugin-data").toPath();
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	private AccountConnectPlugin plugin(String token) throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "gson", new Gson());
		inject(p, "okHttpClient", new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build());
		String base = server.url("/api").toString().replaceAll("/+$", "");
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return token;
			}

			@Override
			public String apiBaseUrl()
			{
				return base;
			}
		});
		p.setSpoolDataRootForTest(data);
		return p;
	}

	private static void inject(AccountConnectPlugin p, String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, value);
	}

	private static void emit(AccountConnectPlugin p, String skill)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("skill", skill);
		fields.put("level", 50);
		p.emitEvent("level_up", fields);
	}

	private static List<Object> skills(RecordedRequest r)
	{
		Map<?, ?> body = new Gson().fromJson(r.getBody().readUtf8(), Map.class);
		return ((List<?>) body.get("events")).stream().map(e -> ((Map<?, ?>) e).get("skill")).collect(Collectors.toList());
	}

	private void closeClient(AccountConnectPlugin p) throws Exception
	{
		ClientShutdown close = new ClientShutdown();
		p.onClientShutdown(close);
		close.waitForAllConsumers(java.time.Duration.ofSeconds(10));
	}

	private Path file()
	{
		return data.resolve("osrsbis").resolve(AccountConnectPlugin.EXIT_PENDING_FILE);
	}

	@Test
	public void eventsRefusedAtCloseAreSentByTheNextStart() throws Exception
	{
		AccountConnectPlugin first = plugin(TOKEN);
		emit(first, "Attack");
		emit(first, "Mining");
		server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"Event capacity reached\"}"));
		closeClient(first);
		assertEquals(List.of("Attack", "Mining"), skills(server.takeRequest(5, TimeUnit.SECONDS)));
		assertTrue("the refused rows are on disk", Files.exists(file()));
		assertFalse("the token itself is never written", new String(Files.readAllBytes(file())).contains(TOKEN));

		AccountConnectPlugin next = plugin(TOKEN);
		next.startUp();
		assertFalse("the file is consumed at start", Files.exists(file()));
		emit(next, "Fishing");
		server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"stored\":3}"));
		next.flushEvents();
		RecordedRequest replay = server.takeRequest(5, TimeUnit.SECONDS);
		assertEquals("the old rows go first, in order, then the new one",
			List.of("Attack", "Mining", "Fishing"), skills(replay));
	}

	@Test
	public void restoredRowsKeepTheirEventIdAndWholeNumbers() throws Exception
	{
		AccountConnectPlugin first = plugin(TOKEN);
		emit(first, "Attack");
		Object id = first.pendingEvents.get(0).get("event_id");
		Object ts = first.pendingEvents.get(0).get("ts");
		first.persistPendingEvents();

		AccountConnectPlugin next = plugin(TOKEN);
		next.restorePendingEvents();
		Map<String, Object> back = next.pendingEvents.get(0);
		assertEquals("a retry carries the same id, so the server can drop a duplicate", id, back.get("event_id"));
		assertEquals(ts, back.get("ts"));
		assertEquals(50L, back.get("level"));
	}

	@Test
	public void anotherTokenNeverSendsTheRows() throws Exception
	{
		AccountConnectPlugin first = plugin(TOKEN);
		emit(first, "Attack");
		first.persistPendingEvents();
		assertTrue(Files.exists(file()));

		AccountConnectPlugin other = plugin(OTHER);
		other.restorePendingEvents();
		assertTrue(other.pendingEvents.isEmpty());
		assertFalse("rows of a token no longer configured are deleted", Files.exists(file()));
	}

	@Test
	public void aPluginStopKeepsTheRowsToo() throws Exception
	{
		AccountConnectPlugin first = plugin(TOKEN);
		emit(first, "Attack");
		first.shutDown();
		assertTrue(first.pendingEvents.isEmpty());

		AccountConnectPlugin next = plugin(TOKEN);
		next.startUp();
		assertEquals(1, next.pendingEvents.size());
		assertEquals("Attack", next.pendingEvents.get(0).get("skill"));
	}

	@Test
	public void aDeliveredCloseLeavesNoFile() throws Exception
	{
		AccountConnectPlugin first = plugin(TOKEN);
		emit(first, "Attack");
		server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"stored\":1}"));
		closeClient(first);
		server.takeRequest(5, TimeUnit.SECONDS);
		assertFalse(Files.exists(file()));
	}
}
