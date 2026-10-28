package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 0.7.62, agreed with the server on 2026-10-28: the server keeps only the newest complete bank of a player, from
 * the snapshot. So a player's bank visit sends no open or close item list when a snapshot queued for upload holds
 * that same bank, and says so with manifest_in_snapshot. Without such a snapshot the list goes as chunks, as
 * before. A staff visit always sends its lists.
 */
public class LatestBankSnapshotTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int BANK_GROUP = 12;
	private static final int BANK = net.runelite.api.gameval.InventoryID.BANK;

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private Client client;
	private ItemContainer bank;
	private boolean staff;
	private int tick = 1000;
	private int failNext;		// refuse this many uploads with a 503
	private int dropNext;		// fail this many uploads on the network
	private final DirectExecutor executor = new DirectExecutor();

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				if (failNext > 0)
				{
					failNext--;	// a refused upload that still carries the token's grants
					return grant(new MockResponse().setResponseCode(503));
				}
				return grant(new MockResponse().setResponseCode(200));
			}
		});
		server.start();
		plugin = new AccountConnectPlugin();
		client = mock(Client.class);
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Fixture Main");
		when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		when(client.getSkillExperience(any(Skill.class))).thenReturn(0);
		when(client.getVarps()).thenReturn(new int[5000]);
		when(client.getItemContainer(InventoryID.BANK)).thenAnswer(i -> bank);
		String baseUrl = server.url("/").toString();
		inject("config", new AccountConnectConfig()
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
		});
		inject("client", client);
		inject("gson", new Gson());
		// Calls run on the calling thread, so a response (which clears a 429 backoff) lands before the next step.
		inject("okHttpClient", new OkHttpClient.Builder().dispatcher(new okhttp3.Dispatcher(executor))
			.addInterceptor(chain ->
			{
				if (dropNext > 0)
				{
					dropNext--;
					throw new java.io.IOException("connection lost");	// the plugin's onFailure path
				}
				return chain.proceed(chain.request());
			}).build());
		inject("itemManager", mock(ItemManager.class));
		inject("activeHash", "42");
	}

	private static final class DirectExecutor extends java.util.concurrent.AbstractExecutorService
	{
		private boolean shutdown;

		boolean hold;
		final List<Runnable> held = new ArrayList<>();

		@Override
		public void execute(Runnable command)
		{
			if (hold)
			{
				held.add(command);
				return;
			}
			command.run();
		}

		void release()
		{
			hold = false;
			for (Runnable r : new ArrayList<>(held))
			{
				r.run();
			}
			held.clear();
		}

		@Override
		public void shutdown()
		{
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			shutdown = true;
			return new ArrayList<>();
		}

		@Override
		public boolean isShutdown()
		{
			return shutdown;
		}

		@Override
		public boolean isTerminated()
		{
			return shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit)
		{
			return true;
		}
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	/** A player gets the bank receipt; staff gets the spool instead, never both. */
	private MockResponse grant(MockResponse r)
	{
		r.addHeader("X-Bank-Evidence", "on");
		return staff ? r.addHeader("X-Event-Spool", "on") : r.addHeader("X-Bank-Receipt", "event-id-v1");
	}

	private void policy() throws Exception
	{
		okhttp3.Response.Builder b = new okhttp3.Response.Builder()
			.request(new okhttp3.Request.Builder().url("http://localhost/").build())
			.protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").header("X-Bank-Evidence", "on");
		if (staff)
		{
			b.header("X-Event-Spool", "on");
		}
		else
		{
			b.header("X-Bank-Receipt", "event-id-v1");
		}
		plugin.applyServerPolicy(b.build(), TOKEN);
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static ItemContainer container(int[]... rows)
	{
		ItemContainer c = mock(ItemContainer.class);
		Item[] arr = new Item[rows.length];
		for (int i = 0; i < rows.length; i++)
		{
			arr[i] = new Item(rows[i][0], rows[i][1]);
		}
		when(c.getItems()).thenReturn(arr);
		when(c.getId()).thenReturn(BANK);
		return c;
	}

	/** The bank's contents arrive in the tick it opens, so the open snapshot's read has a known time. */
	private void open(ItemContainer b)
	{
		bank = b;
		plugin.onItemContainerChanged(new ItemContainerChanged(BANK, b));
		plugin.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);
	}

	private void change(ItemContainer b)
	{
		bank = b;
		plugin.onItemContainerChanged(new ItemContainerChanged(BANK, b));
	}

	/**
	 * The close, the next regular snapshot (the 120 s upload interval has passed), and game ticks until the close
	 * goes out: at once when an accepted snapshot holds its bank, otherwise after the wait.
	 */
	private void close()
	{
		plugin.handleBankWidgetClosed(BANK_GROUP);
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		regularUpload();
		tick++;
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		tick += AccountConnectPlugin.PUBLIC_CLOSE_WAIT_TICKS;
		plugin.onGameTick(new net.runelite.api.events.GameTick());
	}

	private void regularUpload()
	{
		plugin.lastSendMillis = 0L;
		plugin.syncTask();
	}

	private List<Map<String, Object>> of(String type)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> e : plugin.pendingEvents)
		{
			if (type.equals(e.get("type")))
			{
				out.add(e);
			}
		}
		return out;
	}

	private List<Map<String, Object>> chunks(String phase)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> e : of("bank_visit_manifest_chunk"))
		{
			if (phase.equals(e.get("phase")))
			{
				out.add(e);
			}
		}
		return out;
	}

	/** Bank blocks of every snapshot the server received, in order. */
	private List<Map<?, ?>> sentBanks() throws Exception
	{
		List<Map<?, ?>> out = new ArrayList<>();
		RecordedRequest req;
		while ((req = server.takeRequest(200, TimeUnit.MILLISECONDS)) != null)
		{
			Map<?, ?> body = new Gson().fromJson(req.getBody().readUtf8(), Map.class);
			Object snap = body.get("snapshot");
			if (snap instanceof Map)
			{
				out.add((Map<?, ?>) ((Map<?, ?>) snap).get("bank"));
			}
		}
		return out;
	}

	@Test
	public void theSnapshotBankCarriesTheVisitHashAndItsStackCount() throws Exception
	{
		policy();
		bank = container(new int[]{4151, 1}, new int[]{995, 1000}, new int[]{995, 5});
		plugin.forceSendSnapshot();
		Map<?, ?> sent = sentBanks().get(0);
		assertNull("one item in two slots does not match its items list, so no hash", sent.get("manifest_sha256"));

		bank = container(new int[]{4151, 1}, new int[]{995, 1000});
		plugin.forceSendSnapshot();
		sent = sentBanks().get(0);
		Map<Integer, Long> counts = new LinkedHashMap<>();
		counts.put(4151, 1L);
		counts.put(995, 1000L);
		assertEquals(BankVisitManifest.sha256(BankVisitManifest.canonical(counts)), sent.get("manifest_sha256"));
		assertEquals(2.0, sent.get("item_stacks"));
		assertEquals("item_stacks counts the items list of the same block", 2, ((List<?>) sent.get("items")).size());
	}

	@Test
	public void anUnreadBankHasNoHash() throws Exception
	{
		policy();
		plugin.forceSendSnapshot();
		Map<?, ?> sent = sentBanks().get(0);
		assertTrue(((List<?>) sent.get("items")).isEmpty());
		assertFalse(sent.containsKey("manifest_sha256"));
		assertFalse(sent.containsKey("item_stacks"));
	}

	@Test
	public void aPlayersVisitSendsNoItemListWhenAQueuedSnapshotHoldsIt() throws Exception
	{
		policy();
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));
		change(container(new int[]{995, 1000}));			// the whip leaves
		close();

		Map<String, Object> open = of("bank_visit_open").get(0);
		Map<String, Object> close = of("bank_visit_close").get(0);
		assertEquals(true, open.get("manifest_in_snapshot"));
		assertEquals(2, open.get("item_stacks"));
		assertNotNull(open.get("manifest_sha256"));
		assertEquals(true, close.get("manifest_in_snapshot"));
		assertEquals(1, close.get("end_item_stacks"));
		assertFalse(close.containsKey("end_chunks"));
		assertTrue("no open or close list", chunks("open").isEmpty() && chunks("close").isEmpty());
		assertEquals(true, close.get("moves_complete"));
		assertEquals("the moves still go inline", 1, ((List<?>) close.get("withdrawn")).size());

		List<Map<?, ?>> banks = sentBanks();
		assertEquals("the open hash was queued", open.get("manifest_sha256"), banks.get(0).get("manifest_sha256"));
		Map<?, ?> last = banks.get(banks.size() - 1);
		assertEquals("the end bank was queued", close.get("end_manifest_sha256"), last.get("manifest_sha256"));
		assertNotNull("with a known read time", last.get("observed_at"));
	}

	@Test
	public void withNoSnapshotQueuedTheListsGoAsChunks() throws Exception
	{
		policy();
		plugin.backoffUntilMillis = Long.MAX_VALUE;		// a 429 backoff: no snapshot can be queued
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));
		change(container(new int[]{995, 1000}));
		close();

		Map<String, Object> open = of("bank_visit_open").get(0);
		Map<String, Object> close = of("bank_visit_close").get(0);
		assertFalse(open.containsKey("manifest_in_snapshot"));
		assertFalse(close.containsKey("manifest_in_snapshot"));
		assertFalse(close.containsKey("end_item_stacks"));
		assertEquals(1, chunks("open").size());
		assertEquals(1, chunks("close").size());
		assertEquals(1, close.get("end_chunks"));
		assertTrue(sentBanks().isEmpty());
	}

	@Test
	public void aSnapshotQueuedForAnotherAccountDoesNotCount() throws Exception
	{
		policy();
		bank = container(new int[]{995, 1000});
		plugin.forceSendSnapshot();						// queued under account 42
		when(client.getAccountHash()).thenReturn(43L);		// a relog to another account
		inject("activeHash", "43");
		plugin.backoffUntilMillis = Long.MAX_VALUE;
		open(container(new int[]{995, 1000}));
		close();
		assertFalse(of("bank_visit_open").get(0).containsKey("manifest_in_snapshot"));
		assertEquals(1, chunks("open").size());
		assertEquals(1, chunks("close").size());
	}

	@Test
	public void aQueuedSnapshotOfAnOlderBankDoesNotCount() throws Exception
	{
		policy();
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));	// the open snapshot is queued
		plugin.backoffUntilMillis = Long.MAX_VALUE;
		change(container(new int[]{995, 1000}));
		close();
		assertEquals(true, of("bank_visit_open").get(0).get("manifest_in_snapshot"));
		Map<String, Object> close = of("bank_visit_close").get(0);
		assertFalse("the queued bank still holds the whip", close.containsKey("manifest_in_snapshot"));
		assertEquals(1, chunks("close").size());
	}

	@Test
	public void theOpenCountsAnUntimedOpenSnapshotButTheCloseNeedsATimedOne() throws Exception
	{
		policy();
		bank = container(new int[]{995, 1000});
		plugin.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);			// contents not confirmed in this tick
		plugin.backoffUntilMillis = Long.MAX_VALUE;
		Map<?, ?> queued = sentBanks().get(0);
		assertNull(queued.get("observed_at"));
		assertNotNull(queued.get("manifest_sha256"));
		close();
		assertEquals("the bank-open snapshot is never timed", true,
			of("bank_visit_open").get(0).get("manifest_in_snapshot"));
		assertTrue(chunks("open").isEmpty());
		assertFalse("only a timed bank stands for the close", of("bank_visit_close").get(0).containsKey("manifest_in_snapshot"));
		assertEquals(1, chunks("close").size());
	}

	@Test
	public void theCloseForcesNoUploadAndWaitsForAnAcceptedRegularOne() throws Exception
	{
		policy();
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));
		change(container(new int[]{995, 1000}));
		int before = server.getRequestCount();
		plugin.handleBankWidgetClosed(BANK_GROUP);			// the close script's WidgetClosed
		tick++;
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals("no extra upload for a visit", before, server.getRequestCount());
		assertTrue("waits for a snapshot holding the end bank", of("bank_visit_close").isEmpty());
		regularUpload();
		assertEquals(before + 1, server.getRequestCount());
		tick++;
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals(true, of("bank_visit_close").get(0).get("manifest_in_snapshot"));
		assertTrue(chunks("close").isEmpty());
	}

	@Test
	public void anUploadStillInFlightDoesNotStandForTheClose() throws Exception
	{
		policy();
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));
		change(container(new int[]{995, 1000}));
		plugin.handleBankWidgetClosed(BANK_GROUP);
		executor.hold = true;
		regularUpload();									// sent, no answer yet
		tick++;
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		assertTrue("not before the server accepted it", of("bank_visit_close").isEmpty());
		executor.release();
		tick++;
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals(true, of("bank_visit_close").get(0).get("manifest_in_snapshot"));
	}

	@Test
	public void aLostUploadIsNeverClaimed() throws Exception
	{
		policy();
		dropNext = 1;										// the bank-open snapshot is lost on the network
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));
		assertFalse(of("bank_visit_open").get(0).containsKey("manifest_in_snapshot"));
		assertEquals(1, chunks("open").size());
		plugin.handleBankWidgetClosed(BANK_GROUP);
		assertTrue("a backoff after the loss", plugin.backoffUntilMillis > System.currentTimeMillis());
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));	// a reopen during the backoff
		Map<String, Object> second = of("bank_visit_open").get(1);
		assertFalse("no claim from the refused upload", second.containsKey("manifest_in_snapshot"));
		assertEquals(2, chunks("open").size());
	}

	@Test
	public void anUploadThatFailsAfterTheCloseLeavesTheListAsChunks() throws Exception
	{
		policy();
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));
		change(container(new int[]{995, 1000}));
		plugin.handleBankWidgetClosed(BANK_GROUP);
		failNext = 1;
		regularUpload();									// fails: nothing on the server holds the end bank
		tick += AccountConnectPlugin.PUBLIC_CLOSE_WAIT_TICKS;
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		Map<String, Object> close = of("bank_visit_close").get(0);
		assertFalse(close.containsKey("manifest_in_snapshot"));
		assertEquals(1, chunks("close").size());
	}

	@Test
	public void aShopCloseWithAPendingBuyBuildsOnTheNextTick() throws Exception
	{
		policy();
		plugin.storePendings.put(560, new AccountConnectPlugin.StorePending("store_buy", 560, 10, 100L, 5L, tick, false, false));
		int before = server.getRequestCount();
		plugin.onWidgetClosed(new net.runelite.api.events.WidgetClosed(net.runelite.api.gameval.InterfaceID.SHOPMAIN, 0, false));
		assertEquals(1, of("store_buy").size());
		assertEquals("no build inside the shop close script", before, server.getRequestCount());
		tick++;
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals(before + 1, server.getRequestCount());
	}

	@Test
	public void aLogoutBeforeTheNextTickSendsTheCloseWithItsList() throws Exception
	{
		policy();
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));
		change(container(new int[]{995, 1000}));
		plugin.handleBankWidgetClosed(BANK_GROUP);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		net.runelite.api.events.GameStateChanged e = new net.runelite.api.events.GameStateChanged();
		e.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(e);
		Map<String, Object> close = of("bank_visit_close").get(0);
		assertFalse(close.containsKey("manifest_in_snapshot"));
		assertEquals(1, close.get("end_chunks"));
		assertEquals(1, chunks("close").size());
	}

	@Test
	public void aStaffVisitAlwaysSendsItsLists() throws Exception
	{
		staff = true;
		policy();
		open(container(new int[]{995, 1000}, new int[]{4151, 1}));
		change(container(new int[]{995, 1000}));
		close();

		Map<String, Object> open = of("bank_visit_open").get(0);
		Map<String, Object> close = of("bank_visit_close").get(0);
		assertFalse(open.containsKey("manifest_in_snapshot"));
		assertFalse(close.containsKey("manifest_in_snapshot"));
		assertEquals(1, chunks("open").size());
		assertEquals(1, chunks("close").size());
		assertEquals(1, close.get("end_chunks"));
	}

	@Test
	public void aTeardownCloseIsUnchanged() throws Exception
	{
		policy();
		open(container(new int[]{995, 1000}));
		plugin.handleBankWidgetClosed(BANK_GROUP, true);	// unload: hop, logout, disconnect
		Map<String, Object> close = of("bank_visit_close").get(0);
		assertFalse(close.containsKey("manifest_in_snapshot"));
		assertFalse(close.containsKey("end_manifest_sha256"));
		assertTrue(chunks("close").isEmpty());
	}

	@Test
	public void thePublicProjectionKeepsTheNewFields()
	{
		Map<String, Object> close = new LinkedHashMap<>();
		close.put("type", "bank_visit_close");
		close.put("event_id", "0123456789abcdef0123456789abcdef");
		close.put("visit_id", "0123456789abcdef0123456789abcdef");
		close.put("end_item_stacks", 3);
		close.put("manifest_in_snapshot", true);
		Map<String, Object> out = PublicTransport.event(close, false);
		assertEquals(3, out.get("end_item_stacks"));
		assertEquals(true, out.get("manifest_in_snapshot"));

		Map<String, Object> open = new LinkedHashMap<>();
		open.put("type", "bank_visit_open");
		open.put("event_id", "0123456789abcdef0123456789abcdef");
		open.put("manifest_in_snapshot", true);
		assertEquals(true, PublicTransport.event(open, false).get("manifest_in_snapshot"));
	}
}
