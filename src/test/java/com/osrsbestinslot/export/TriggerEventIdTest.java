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
 * Agreed with the server and the wealth reader on 2026-10-25, after the 0.7.59 alch run: a forced snapshot is
 * built in the same millisecond as its trigger row, so the time alone cannot place the row inside it.
 * The snapshot names the row in trigger_event_id when it is the FIRST snapshot built after the row's item change.
 * Two rows from one change name only the first build. Any build in between means no name at all.
 */
public class TriggerEventIdTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int INV = net.runelite.api.gameval.InventoryID.INV;
	private static final int COINS = 995;
	private static final int RUNE = 560;
	private static final int BOW = 841;
	private static final int STAFF = 1381;

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private Client client;
	private ItemContainer inv;
	private int tick = 1000;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request)
			{
				return new MockResponse().setResponseCode(200);
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
		when(client.getItemContainer(InventoryID.INVENTORY)).thenAnswer(i -> inv);
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
		inject("okHttpClient", new OkHttpClient());
		inject("itemManager", mock(ItemManager.class));
		inv = container(COINS, 100, RUNE, 5, BOW, 1, STAFF, 1);
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static ItemContainer container(int... idQty)
	{
		ItemContainer c = mock(ItemContainer.class);
		Item[] items = new Item[idQty.length / 2];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = new Item(idQty[i * 2], idQty[i * 2 + 1]);
		}
		when(c.getItems()).thenReturn(items);
		when(c.getId()).thenReturn(INV);
		return c;
	}

	@SuppressWarnings("unchecked")
	private void arm(OffBookPendings.InvDeltaPending p) throws Exception
	{
		Field f = OffBookPendings.class.getDeclaredField("invDeltaPendings");
		f.setAccessible(true);
		((java.util.Deque<OffBookPendings.InvDeltaPending>) f.get(plugin.offBook)).addLast(p);
	}

	private void inventoryChange(ItemContainer next)
	{
		inv = next;
		plugin.onItemContainerChanged(new ItemContainerChanged(INV, next));
	}

	private Object trigger()
	{
		return plugin.uploader.lastBuiltSnapshot == null ? null : plugin.uploader.lastBuiltSnapshot.get("trigger_event_id");
	}

	private List<Map<String, Object>> rows(String type)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> e : plugin.events.pendingEvents)
		{
			if (type.equals(e.get("type")))
			{
				out.add(e);
			}
		}
		return out;
	}

	@Test
	public void theForcedBuildAfterAnAlchNamesItOnTheWire() throws Exception
	{
		arm(new OffBookPendings.InvDeltaPending("alch", STAFF, "high", 1L, 100L, null, null, tick));
		tick++;
		inventoryChange(container(COINS, 1300, RUNE, 5, BOW, 1));
		Map<String, Object> alch = rows("alch").get(0);
		String id = (String) alch.get("event_id");
		assertEquals(id, trigger());

		RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull(req);
		Map<?, ?> body = new Gson().fromJson(req.getBody().readUtf8(), Map.class);
		assertEquals("a top-level key of the sent snapshot", id, ((Map<?, ?>) body.get("snapshot")).get("trigger_event_id"));
	}

	@Test
	public void theHashIgnoresTheName() throws Exception
	{
		plugin.syncTask();
		Map<String, Object> a = plugin.uploader.lastBuiltSnapshot;
		Map<String, Object> b = new LinkedHashMap<>(a);
		b.put("trigger_event_id", "0123456789abcdef0123456789abcdef");
		assertEquals(plugin.snapshots.canonicalHash(a), plugin.snapshots.canonicalHash(b));
	}

	@Test
	public void periodicBankOpenAndPlainForcedBuildsNameNothing() throws Exception
	{
		plugin.syncTask();
		assertFalse("periodic", plugin.uploader.lastBuiltSnapshot.containsKey("trigger_event_id"));
		plugin.bankVisit.handleCaptureOnOpenWidgetLoaded(12);
		assertFalse("bank open", plugin.uploader.lastBuiltSnapshot.containsKey("trigger_event_id"));
		plugin.uploader.forceSendSnapshot();
		assertFalse("forced with no row", plugin.uploader.lastBuiltSnapshot.containsKey("trigger_event_id"));
	}

	@Test
	public void twoDropsFromOneChangeNameOnlyTheFirst() throws Exception
	{
		arm(new OffBookPendings.InvDeltaPending("drop", BOW, null, 1L, 100L, null, Boolean.FALSE, tick));
		arm(new OffBookPendings.InvDeltaPending("drop", STAFF, null, 1L, 100L, null, Boolean.FALSE, tick));
		tick++;
		OffBookHooks.resolveDropPendingOnGroundSpawn(plugin.offBook,BOW, 0, 1L, tick);	// spawns first, the loss not yet seen
		OffBookHooks.resolveDropPendingOnGroundSpawn(plugin.offBook,STAFF, 0, 1L, tick);
		assertNull(plugin.uploader.lastBuiltSnapshot);
		inventoryChange(container(COINS, 100, RUNE, 5));			// one change removes both

		List<Map<String, Object>> drops = rows("drop");
		assertEquals(2, drops.size());
		List<Object> sent = new ArrayList<>();
		for (int i = 0; i < 2; i++)
		{
			RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
			assertNotNull("each drop gets a build: the first at once, a second held while the first was in flight", req);
			Map<?, ?> body = new Gson().fromJson(req.getBody().readUtf8(), Map.class);
			sent.add(((Map<?, ?>) body.get("snapshot")).get("trigger_event_id"));
			if (i == 0) tickAfterAnswers();
		}
		assertEquals("one build names the first row", 1, sent.stream().filter(java.util.Objects::nonNull).count());
		assertEquals(drops.get(0).get("event_id"), sent.stream().filter(java.util.Objects::nonNull).findFirst().get());
	}

	@Test
	public void theFirstOfTwoDropsFromOneChangeIsTheNamedOne() throws Exception
	{
		List<Object> names = new ArrayList<>();
		arm(new OffBookPendings.InvDeltaPending("drop", BOW, null, 1L, 100L, null, Boolean.FALSE, tick));
		arm(new OffBookPendings.InvDeltaPending("drop", STAFF, null, 1L, 100L, null, Boolean.FALSE, tick));
		tick++;
		inventoryChange(container(COINS, 100, RUNE, 5));			// the loss first: no row yet
		assertEquals(0, rows("drop").size());
		OffBookHooks.resolveDropPendingOnGroundSpawn(plugin.offBook,BOW, 0, 0L, tick);
		names.add(trigger());
		OffBookHooks.resolveDropPendingOnGroundSpawn(plugin.offBook,STAFF, 0, 0L, tick);
		tickAfterAnswers();	// a second drop while the first upload is in flight is built on the tick after it answers
		names.add(trigger());

		List<Map<String, Object>> drops = rows("drop");
		assertEquals(2, drops.size());
		assertEquals("the first build after the change names the first row", drops.get(0).get("event_id"), names.get(0));
		assertNull("the second build is not the first after the change", names.get(1));
	}

	@Test
	public void aBuildBetweenTheChangeAndTheRowMeansNoName() throws Exception
	{
		arm(new OffBookPendings.InvDeltaPending("drop", BOW, null, 1L, 100L, null, Boolean.FALSE, tick));
		tick++;
		inventoryChange(container(COINS, 100, RUNE, 5, STAFF, 1));	// the loss first
		plugin.syncTask();											// a periodic build already holds it
		OffBookHooks.resolveDropPendingOnGroundSpawn(plugin.offBook,BOW, 0, 0L, tick);
		assertEquals(1, rows("drop").size());
		assertNull(trigger());
	}

	/** Waits until no snapshot upload is in flight, then runs one game tick (which sends a held forced snapshot). */
	private void tickAfterAnswers() throws Exception
	{
		long until = System.currentTimeMillis() + 5000;
		while (plugin.uploader.snapshotsInFlight.get() > 0 && System.currentTimeMillis() < until)
		{
			TimeUnit.MILLISECONDS.sleep(10);
		}
		assertEquals("every upload answered", 0, plugin.uploader.snapshotsInFlight.get());
		plugin.onGameTick(null);
	}

	@Test
	public void aBurstOfForcedEventsSendsOneUploadThenOneWithTheNewestState() throws Exception
	{
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request) throws InterruptedException
			{
				release.await(5, TimeUnit.SECONDS);	// the first upload stays in flight until the burst is over
				return new MockResponse().setResponseCode(200).setBody("{\"ok\":true}");
			}
		});
		for (int i = 0; i < 6; i++)
		{
			plugin.uploader.maybeForceSnapshotForEvent("alch", null);
		}
		assertNotNull("the first event sends at once", server.takeRequest(5, TimeUnit.SECONDS));
		assertNull("the other five wait for its answer", server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals(true, plugin.uploader.deferredForce);
		inv = container(COINS, 6100, RUNE, 5, BOW, 1);	// state moved on during the burst
		release.countDown();
		tickAfterAnswers();
		RecordedRequest trailing = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("one upload after the answer carries the burst", trailing);
		assertFalse(plugin.uploader.deferredForce);
		assertNull("and only one", server.takeRequest(300, TimeUnit.MILLISECONDS));
		assertEquals(2, server.getRequestCount());
		assertEquals("it holds the newest state", true, trailing.getBody().readUtf8().contains("6100"));
	}

	@Test
	public void aHeldSendWaitsOutTheRetryAfterInsteadOfBeingDropped() throws Exception
	{
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		server.setDispatcher(new Dispatcher()
		{
			@Override
			public MockResponse dispatch(RecordedRequest request) throws InterruptedException
			{
				if (release.getCount() > 0)
				{
					release.await(5, TimeUnit.SECONDS);
					return new MockResponse().setResponseCode(409).setHeader("Retry-After", "5")
						.setBody("{\"ok\":false,\"error\":\"Another upload for this account was stored first. Retry later.\"}");
				}
				return new MockResponse().setResponseCode(200).setBody("{\"ok\":true}");
			}
		});
		plugin.uploader.maybeForceSnapshotForEvent("drop", null);
		assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
		plugin.uploader.maybeForceSnapshotForEvent("drop", null);	// held: the first is in flight
		assertTrue(plugin.uploader.deferredForce);
		release.countDown();
		tickAfterAnswers();
		assertTrue("the 409's Retry-After is in force", System.currentTimeMillis() < plugin.uploader.backoffUntilMillis);
		assertTrue("the held send is kept through the wait, not dropped", plugin.uploader.deferredForce);
		assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS));
		plugin.uploader.backoffUntilMillis = 0L;	// the wait ends
		plugin.onGameTick(null);
		assertNotNull("then it goes", server.takeRequest(5, TimeUnit.SECONDS));
		assertFalse(plugin.uploader.deferredForce);
	}

	@Test
	public void aMeasuredStoreBuyNamesItsRow() throws Exception
	{
		plugin.store.storePendings.put(RUNE, new StoreTools.StorePending("store_buy", RUNE, 10, 100L, 5L, tick, false, false));
		tick++;
		inventoryChange(container(COINS, 60, RUNE, 15, BOW, 1, STAFF, 1));
		Map<String, Object> buy = rows("store_buy").get(0);
		assertEquals(buy.get("event_id"), trigger());
	}

	@Test
	public void aRowWithNoItemChangeOfItsOwnNamesNothing() throws Exception
	{
		plugin.death.resolveDeathPending(new LinkedHashMap<>(), true);	// nothing pending: no row
		inventoryChange(container(COINS, 100, RUNE, 4, BOW, 1, STAFF, 1));	// a change that resolves no row
		plugin.events.emitEvent("death", new LinkedHashMap<>());			// a trigger row emitted outside a change
		assertEquals(1, rows("death").size());
		assertNotNull(plugin.uploader.lastBuiltSnapshot);
		assertNull(trigger());
	}
}
