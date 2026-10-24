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
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Agreed with the server and the readers on 2026-10-24, after the live 0.7.55 logs:
 * - snapshot.captured_at_ms orders a snapshot against an event in the same second; captured_at is its second.
 * - bank.open_at_capture marks bank items read from the live container of an open bank, so a reader can tell
 *   them from a read retained after the close.
 * - login/logout carry wealth only when this login has read the bank: without it the same field held
 *   inventory + gear only, a second meaning (11.38M with the bank, 5.05M without on one account).
 * Neither new snapshot field may make an unchanged account look changed.
 */
public class SnapshotFreshnessTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int BANK_GROUP = 12;
	private static final int COINS = 995;
	private static final int BONES = 530;

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private Client client;
	private ItemContainer bank;
	private int tick = 1000;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		plugin = new AccountConnectPlugin();
		client = mock(Client.class);
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Fixture Main");
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
		inject("okHttpClient", new OkHttpClient());
		inject("itemManager", mock(ItemManager.class));
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

	private static ItemContainer container(Item... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		when(c.getItems()).thenReturn(items);
		when(c.getId()).thenReturn(net.runelite.api.gameval.InventoryID.BANK);
		return c;
	}

	private void change(ItemContainer b)
	{
		bank = b;
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.BANK, b));
	}

	/** The bank interface loads: the snapshot sent for the open is returned. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> open(ItemContainer b) throws Exception
	{
		bank = b;
		server.enqueue(new MockResponse().setResponseCode(200));
		plugin.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);
		RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("the open sends a snapshot", req);
		return (Map<String, Object>) new Gson().fromJson(req.getBody().readUtf8(), Map.class).get("snapshot");
	}

	private void close()
	{
		plugin.handleBankWidgetClosed(BANK_GROUP);
		bank = null;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> snapshot() throws Exception
	{
		Method build = AccountConnectPlugin.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		return (Map<String, Object>) build.invoke(plugin);
	}

	private static Object openAtCapture(Map<String, Object> snap)
	{
		return ((Map<?, ?>) snap.get("bank")).get("open_at_capture");
	}

	@Test
	public void capturedAtMsIsTheSameClockReadAsCapturedAt() throws Exception
	{
		Map<String, Object> snap = snapshot();
		Object ms = snap.get("captured_at_ms");
		assertTrue("an integer of milliseconds", ms instanceof Long);
		assertEquals(snap.get("captured_at"), (Long) ms / 1000L);

		String json = new Gson().toJson(snap);
		assertTrue("an integer on the wire, never 1.7E12", json.contains("\"captured_at_ms\":" + ms + ","));
	}

	@Test
	public void theNewFieldsNeverMakeAnUnchangedAccountLookChanged() throws Exception
	{
		Map<String, Object> a = snapshot();
		Map<String, Object> b = new LinkedHashMap<>(a);
		b.put("captured_at_ms", (Long) a.get("captured_at_ms") + 1234L);
		Map<String, Object> bankBlock = new LinkedHashMap<>((Map<String, Object>) a.get("bank"));
		bankBlock.put("open_at_capture", true);
		b.put("bank", bankBlock);
		assertEquals(plugin.canonicalHash(a), plugin.canonicalHash(b));

		Map<String, Object> moved = new LinkedHashMap<>(bankBlock);
		moved.put("value_gp", 1L);
		b.put("bank", moved);
		assertFalse("a real bank change still counts", plugin.canonicalHash(a).equals(plugin.canonicalHash(b)));
	}

	@Test
	public void anOpenBankIsMarkedAndARetainedReadIsNot() throws Exception
	{
		assertNull("no bank this login", openAtCapture(snapshot()));

		ItemContainer b = container(new Item(COINS, 100), new Item(BONES, 1));
		change(b);										// this open's contents arrive in the open's own tick
		assertEquals("the snapshot sent for the open", true, openAtCapture(open(b)));
		tick++;
		assertEquals("still open", true, openAtCapture(snapshot()));
		change(container(new Item(COINS, 100)));
		assertEquals("a change while open", true, openAtCapture(snapshot()));

		close();
		Map<String, Object> after = snapshot();
		assertEquals("the read is kept after the close", true, ((Map<?, ?>) after.get("capture_flags")).get("bank_synced"));
		assertNull("but it is not an open bank", openAtCapture(after));
	}

	/**
	 * Live 0.7.57 QA (2026-10-24): the snapshot sent at the open read a populated container before the open's
	 * ItemContainerChanged. Nothing proves that read is this visit's contents and not the last visit's copy.
	 */
	@Test
	public void anOpenWhoseContentsNoChangeConfirmedIsNotMarked() throws Exception
	{
		change(container(new Item(COINS, 100), new Item(BONES, 1)));	// the last visit
		close();
		tick += 50;
		ItemContainer stale = container(new Item(COINS, 100), new Item(BONES, 1));
		assertNull("the open read only a container no change has confirmed", openAtCapture(open(stale)));
		tick++;
		assertNull("still unconfirmed a tick later", openAtCapture(snapshot()));
		change(container(new Item(COINS, 100)));			// this visit's contents arrive
		assertEquals(true, openAtCapture(snapshot()));
	}

	@Test
	public void aChangeOfTheLastVisitNeverConfirmsAReopenInTheSameTick() throws Exception
	{
		change(container(new Item(COINS, 100), new Item(BONES, 1)));	// the last visit's change in tick T
		close();
		ItemContainer stale = container(new Item(COINS, 100), new Item(BONES, 1));
		assertNull("reopened in the same tick T, no change of this open yet", openAtCapture(open(stale)));
		tick++;
		assertNull(openAtCapture(snapshot()));
	}

	@Test
	public void anUnreadableCloseIsNotAnOpenBank() throws Exception
	{
		open(container(new Item(COINS, 100)));
		bank = container();								// the close reads an empty container: the close waits
		plugin.handleBankWidgetClosed(BANK_GROUP);
		bank = container(new Item(COINS, 100));			// a read arriving after the close
		assertNull(openAtCapture(snapshot()));
	}

	@Test
	public void anOpenThatHasNotLoadedIsNotMarkedUntilItLoads() throws Exception
	{
		assertNull("an empty open is no read", openAtCapture(open(container())));
		change(container(new Item(COINS, 100)));			// the first populated read
		assertEquals(true, openAtCapture(snapshot()));
	}

	@Test
	public void loginAndLogoutCarryWealthOnlyOnceTheBankIsRead() throws Exception
	{
		plugin.syncTask();
		server.takeRequest(5, TimeUnit.SECONDS);
		Map<String, Object> login = plugin.pendingEvents.get(0);
		assertEquals("login", login.get("type"));
		assertFalse("no bank read yet: no wealth at all", login.containsKey("wealth"));
		assertNull(plugin.lastKnownNetWorth());

		open(container(new Item(COINS, 100)));
		close();
		plugin.pendingEvents.clear();
		plugin.trackLogout("manual");
		Map<String, Object> logout = plugin.pendingEvents.get(0);
		assertEquals("logout", logout.get("type"));
		assertNotNull("the bank was read this login", logout.get("wealth"));
	}
}
