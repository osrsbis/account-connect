package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
	private void arm(AccountConnectPlugin.InvDeltaPending p) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("invDeltaPendings");
		f.setAccessible(true);
		((java.util.Deque<AccountConnectPlugin.InvDeltaPending>) f.get(plugin)).addLast(p);
	}

	private void inventoryChange(ItemContainer next)
	{
		inv = next;
		plugin.onItemContainerChanged(new ItemContainerChanged(INV, next));
	}

	private Object trigger()
	{
		return plugin.lastBuiltSnapshot == null ? null : plugin.lastBuiltSnapshot.get("trigger_event_id");
	}

	private List<Map<String, Object>> rows(String type)
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

	@Test
	public void theForcedBuildAfterAnAlchNamesItOnTheWire() throws Exception
	{
		arm(new AccountConnectPlugin.InvDeltaPending("alch", STAFF, "high", 1L, 100L, null, null, tick));
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
		Map<String, Object> a = plugin.lastBuiltSnapshot;
		Map<String, Object> b = new LinkedHashMap<>(a);
		b.put("trigger_event_id", "0123456789abcdef0123456789abcdef");
		assertEquals(plugin.canonicalHash(a), plugin.canonicalHash(b));
	}

	@Test
	public void periodicBankOpenAndPlainForcedBuildsNameNothing() throws Exception
	{
		plugin.syncTask();
		assertFalse("periodic", plugin.lastBuiltSnapshot.containsKey("trigger_event_id"));
		plugin.handleCaptureOnOpenWidgetLoaded(12);
		assertFalse("bank open", plugin.lastBuiltSnapshot.containsKey("trigger_event_id"));
		plugin.forceSendSnapshot();
		assertFalse("forced with no row", plugin.lastBuiltSnapshot.containsKey("trigger_event_id"));
	}

	@Test
	public void twoDropsFromOneChangeNameOnlyTheFirst() throws Exception
	{
		arm(new AccountConnectPlugin.InvDeltaPending("drop", BOW, null, 1L, 100L, null, Boolean.FALSE, tick));
		arm(new AccountConnectPlugin.InvDeltaPending("drop", STAFF, null, 1L, 100L, null, Boolean.FALSE, tick));
		tick++;
		plugin.resolveDropPendingOnGroundSpawn(BOW, 0, 1L, tick);	// spawns first, the loss not yet seen
		plugin.resolveDropPendingOnGroundSpawn(STAFF, 0, 1L, tick);
		assertNull(plugin.lastBuiltSnapshot);
		inventoryChange(container(COINS, 100, RUNE, 5));			// one change removes both

		List<Map<String, Object>> drops = rows("drop");
		assertEquals(2, drops.size());
		List<Object> sent = new ArrayList<>();
		for (int i = 0; i < 2; i++)
		{
			RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
			assertNotNull("each drop forces a build", req);
			Map<?, ?> body = new Gson().fromJson(req.getBody().readUtf8(), Map.class);
			sent.add(((Map<?, ?>) body.get("snapshot")).get("trigger_event_id"));
		}
		assertEquals("one build names the first row", 1, sent.stream().filter(java.util.Objects::nonNull).count());
		assertEquals(drops.get(0).get("event_id"), sent.stream().filter(java.util.Objects::nonNull).findFirst().get());
	}

	@Test
	public void theFirstOfTwoDropsFromOneChangeIsTheNamedOne() throws Exception
	{
		List<Object> names = new ArrayList<>();
		arm(new AccountConnectPlugin.InvDeltaPending("drop", BOW, null, 1L, 100L, null, Boolean.FALSE, tick));
		arm(new AccountConnectPlugin.InvDeltaPending("drop", STAFF, null, 1L, 100L, null, Boolean.FALSE, tick));
		tick++;
		inventoryChange(container(COINS, 100, RUNE, 5));			// the loss first: no row yet
		assertEquals(0, rows("drop").size());
		plugin.resolveDropPendingOnGroundSpawn(BOW, 0, 0L, tick);
		names.add(trigger());
		plugin.resolveDropPendingOnGroundSpawn(STAFF, 0, 0L, tick);
		names.add(trigger());

		List<Map<String, Object>> drops = rows("drop");
		assertEquals(2, drops.size());
		assertEquals("the first build after the change names the first row", drops.get(0).get("event_id"), names.get(0));
		assertNull("the second build is not the first after the change", names.get(1));
	}

	@Test
	public void aBuildBetweenTheChangeAndTheRowMeansNoName() throws Exception
	{
		arm(new AccountConnectPlugin.InvDeltaPending("drop", BOW, null, 1L, 100L, null, Boolean.FALSE, tick));
		tick++;
		inventoryChange(container(COINS, 100, RUNE, 5, STAFF, 1));	// the loss first
		plugin.syncTask();											// a periodic build already holds it
		plugin.resolveDropPendingOnGroundSpawn(BOW, 0, 0L, tick);
		assertEquals(1, rows("drop").size());
		assertNull(trigger());
	}

	@Test
	public void aMeasuredStoreBuyNamesItsRow() throws Exception
	{
		plugin.storePendings.put(RUNE, new AccountConnectPlugin.StorePending("store_buy", RUNE, 10, 100L, 5L, tick, false, false));
		tick++;
		inventoryChange(container(COINS, 60, RUNE, 15, BOW, 1, STAFF, 1));
		Map<String, Object> buy = rows("store_buy").get(0);
		assertEquals(buy.get("event_id"), trigger());
	}

	@Test
	public void aRowWithNoItemChangeOfItsOwnNamesNothing() throws Exception
	{
		plugin.resolveDeathPending(new LinkedHashMap<>(), true);	// nothing pending: no row
		inventoryChange(container(COINS, 100, RUNE, 4, BOW, 1, STAFF, 1));	// a change that resolves no row
		plugin.emitEvent("death", new LinkedHashMap<>());			// a trigger row emitted outside a change
		assertEquals(1, rows("death").size());
		assertNotNull(plugin.lastBuiltSnapshot);
		assertNull(trigger());
	}
}
