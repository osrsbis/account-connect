package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * WAVE 2 — real-time sync. Every tracked event flushes live (coalesced into a ~1s window so a burst is ONE
 * POST, not one per line), and a state-changing event (trade/ge/store/death) forces an immediate snapshot.
 */
public class RealtimeFlushTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private MockWebServer server;
	private AccountConnectPlugin plugin;

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

	private AccountConnectConfig cfg()
	{
		final String base = server.url("/").toString();
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public String apiBaseUrl()
			{
				return base;
			}
		};
	}

	private static Map<String, Object> field(String k, Object v)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(k, v);
		return m;
	}

	/** A burst of events schedules exactly one coalesced flush (not one per event). */
	@Test
	public void burstOfEventsSchedulesFlushOnce() throws Exception
	{
		plugin = new AccountConnectPlugin();
		inject("config", cfg());
		ScheduledExecutorService exec = mock(ScheduledExecutorService.class);
		inject("executor", exec);

		plugin.emitEvent("chat", field("text", "line one"));
		plugin.emitEvent("chat", field("text", "line two"));
		plugin.emitEvent("chat", field("text", "line three"));

		verify(exec, times(1)).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
		assertEquals("all three buffered for a single coalesced flush", 3, plugin.pendingEvents.size());
	}

	/** A rapid burst becomes exactly ONE /event-ingest POST carrying every event; a later event opens a new window. */
	@Test
	public void burstBecomesSinglePost() throws Exception
	{
		plugin = new AccountConnectPlugin();
		inject("config", cfg());
		inject("gson", new Gson());
		inject("okHttpClient", new OkHttpClient());
		ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
		inject("executor", exec);
		plugin.eventCoalesceMillis = 120L;
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(new MockResponse().setResponseCode(200));

		plugin.emitEvent("chat", field("text", "a"));
		plugin.emitEvent("chat", field("text", "b"));
		plugin.emitEvent("chat", field("text", "c"));

		RecordedRequest r1 = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("the burst must flush", r1);
		assertTrue("chat flushes to /event-ingest (not a forced snapshot)", r1.getPath().contains("/event-ingest"));
		Map<?, ?> body = new Gson().fromJson(r1.getBody().readUtf8(), Map.class);
		assertEquals("three events coalesced into one POST", 3, ((List<?>) body.get("events")).size());
		assertNull("no second POST for the same burst", server.takeRequest(300, TimeUnit.MILLISECONDS));

		plugin.emitEvent("chat", field("text", "d"));
		RecordedRequest r2 = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("a later event opens a fresh window -> a second POST", r2);
		exec.shutdownNow();
	}

	/** A state-changing event forces an immediate snapshot AND flushes the event; chat only flushes the event. */
	@Test
	public void stateChangingEventForcesImmediateSnapshot() throws Exception
	{
		plugin = new AccountConnectPlugin();
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(player.getName()).thenReturn("TestPlayer");
		when(player.getCombatLevel()).thenReturn(100);
		ItemManager itemManager = mock(ItemManager.class);

		inject("client", client);
		inject("itemManager", itemManager);
		inject("config", cfg());
		inject("gson", new Gson());
		inject("okHttpClient", new OkHttpClient());
		inject("executor", Executors.newSingleThreadScheduledExecutor());
		plugin.eventCoalesceMillis = 60L;
		server.enqueue(new MockResponse().setResponseCode(200));
		server.enqueue(new MockResponse().setResponseCode(200));

		plugin.emitEvent("ge_buy", field("item", 4151));

		boolean sawSnapshot = false;
		boolean sawEvent = false;
		for (int i = 0; i < 2; i++)
		{
			RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
			assertNotNull("expected both a snapshot and an event POST", r);
			if (r.getPath().contains("/account-ingest"))
			{
				sawSnapshot = true;
			}
			if (r.getPath().contains("/event-ingest"))
			{
				sawEvent = true;
			}
		}
		assertTrue("a state-changing event must force an immediate snapshot", sawSnapshot);
		assertTrue("a state-changing event must also flush the event", sawEvent);
	}
}
