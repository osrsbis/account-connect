package com.osrsbestinslot.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.ScriptEvent;
import net.runelite.api.Skill;
import net.runelite.api.VarbitComposition;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 0.7.74 account capture: the X-Account-Capture grant (official endpoint, this token, the logged-in account, broad
 * capture on), the four row states, the prompt order, the upload footer, and that the grant sends nothing new.
 */
public class AccountCaptureTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final String OTHER_TOKEN = "fedcba9876543210fedcba9876543210";
	private static final String C = AccountCaptureOverlay.CAPTURED, M = AccountCaptureOverlay.MISSING;

	private MockWebServer server;
	private Path data;
	private Client client;
	private AccountConnectPlugin plugin;
	private AccountCaptureOverlay overlay;
	private final Map<Integer, ItemContainer> containers = new HashMap<>();
	private final int[] vars = new int[5000];
	private int tick = 100;
	private int houseLocation = 3;
	private int xp = 1000;
	private String token = TOKEN;
	private String apiBase;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		data = Files.createTempDirectory("account-capture");
		apiBase = server.url("/api").toString();
		client = mock(Client.class);
		when(client.getVarps()).thenReturn(vars);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Fixture Main");
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getWorldType()).thenReturn(java.util.EnumSet.noneOf(WorldType.class));
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		when(client.getSkillExperience(Skill.ATTACK)).thenAnswer(i -> xp);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getItemContainer(anyInt())).thenAnswer(i -> containers.get((Integer) i.getArgument(0)));
		when(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION)).thenAnswer(i -> houseLocation);
		VarbitComposition host = mock(VarbitComposition.class);	// the house host's collection log: not open
		when(host.getIndex()).thenReturn(2049);
		when(host.getLeastSignificantBit()).thenReturn(11);
		when(host.getMostSignificantBit()).thenReturn(11);
		when(client.getVarbit(12061)).thenReturn(host);
		when(client.getWidget(net.runelite.api.gameval.InterfaceID.Collection.SEARCH_TOGGLE)).thenReturn(mock(Widget.class));

		plugin = new AccountConnectPlugin();
		put("client", client);
		put("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return token;
			}

			@Override
			public String apiBaseUrl()
			{
				return apiBase;
			}
		});
		put("gson", new Gson());
		put("okHttpClient", new OkHttpClient());
		put("itemManager", mock(ItemManager.class));
		put("activeHash", "42");
		plugin.grants.staffApiBase = apiBase;	// this rig's stand-in for osrsbestinslot.com
		SpoolHooks.dataRoot(plugin.spooler, data);
		overlay = new AccountCaptureOverlay(plugin);
	}

	@After
	public void tearDown() throws Exception
	{
		SpoolHooks.crash(plugin.spooler);
		server.shutdown();
		EventSpoolCodecTest.deleteTree(data);
	}

	private void put(String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	/** An ingest answer from the given base with the given header pairs. */
	private Response answer(String base, int code, String... headers)
	{
		Response.Builder b = new Response.Builder().request(new Request.Builder().url(base + "/account-ingest").build())
			.protocol(Protocol.HTTP_1_1).code(code).message("x");
		for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
		return b.build();
	}

	private Response answer(int code, String... headers)
	{
		return answer(apiBase, code, headers);
	}

	private static String[] granted(String account)
	{
		return new String[]{"X-Broad-Capture", "on", "X-Account-Capture", "on", "X-Account-Capture-Account", account};
	}

	private boolean grantAndCheck()
	{
		plugin.grants.applyServerPolicy(answer(200, granted("42")), TOKEN);
		return plugin.grants.accountCaptureEnabled();
	}

	@Test
	public void theGrantOpensOnlyForThisTokenAndAccountOnTheOfficialEndpoint() throws Exception
	{
		assertFalse("off before any answer", plugin.grants.accountCaptureEnabled());
		assertTrue("the official grant for the logged-in account", grantAndCheck());

		plugin.grants.applyServerPolicy(answer(200, "X-Broad-Capture", "on"), TOKEN);
		assertFalse("the next answer without the header ends it", plugin.grants.accountCaptureEnabled());
		assertNull(plugin.grants.accountCaptureGrant);

		plugin.grants.applyServerPolicy(answer(200, granted("99")), TOKEN);
		assertFalse("a grant naming another account", plugin.grants.accountCaptureEnabled());
		plugin.grants.applyServerPolicy(answer(200, "X-Broad-Capture", "on", "X-Account-Capture", "on"), TOKEN);
		assertFalse("a grant naming no account", plugin.grants.accountCaptureEnabled());
		plugin.grants.applyServerPolicy(answer(200, "X-Broad-Capture", "on", "X-Account-Capture", "yes",
			"X-Account-Capture-Account", "42"), TOKEN);
		assertFalse("any value but on", plugin.grants.accountCaptureEnabled());
		plugin.grants.applyServerPolicy(answer(200, "X-Account-Capture", "on", "X-Account-Capture-Account", "42"), TOKEN);
		assertFalse("without broad capture (which carries the costume room)", plugin.grants.accountCaptureEnabled());

		assertTrue(grantAndCheck());
		put("activeHash", "99");
		assertFalse("off while another account is the session's", plugin.grants.accountCaptureEnabled());
		put("activeHash", "42");
		when(client.getAccountHash()).thenReturn(99L);
		assertFalse("off while the client has another account logged in", plugin.grants.accountCaptureEnabled());
		when(client.getAccountHash()).thenReturn(42L);
		assertTrue(plugin.grants.accountCaptureEnabled());

		plugin.grants.applyServerPolicy(answer(503, "X-Broad-Capture", "on"), TOKEN);
		assertTrue("a 5xx says nothing about the grant", plugin.grants.accountCaptureEnabled());
		plugin.grants.applyServerPolicy(answer(200, "X-Broad-Capture", "on"), OTHER_TOKEN);
		assertTrue("a late answer for a token this client no longer holds changes nothing", plugin.grants.accountCaptureEnabled());
		plugin.grants.applyServerPolicy(answer(403, granted("42")), TOKEN);
		assertFalse("a refused token ends it", plugin.grants.accountCaptureEnabled());
		assertNull(plugin.grants.accountCaptureGrant);
		plugin.grants.applyServerPolicy(answer(401), TOKEN);
		assertFalse(plugin.grants.accountCaptureEnabled());
	}

	@Test
	public void aTokenChangeEndsTheGrant() throws Exception
	{
		assertTrue(grantAndCheck());
		token = OTHER_TOKEN;
		assertFalse("another token is off at once", plugin.grants.accountCaptureEnabled());
		ConfigChanged change = new ConfigChanged();
		change.setGroup(AccountConnectPlugin.CONFIG_GROUP);
		change.setKey("linkToken");
		change.setOldValue(TOKEN);
		change.setNewValue(OTHER_TOKEN);
		plugin.onConfigChanged(change);
		assertNull("the change clears it", plugin.grants.accountCaptureGrant);
		token = TOKEN;
		assertFalse("A -> B -> A cannot restore it", plugin.grants.accountCaptureEnabled());
	}

	@Test
	public void onlyTheOfficialEndpointCanGrantIt() throws Exception
	{
		plugin.grants.applyServerPolicy(answer("https://example.invalid/api", 200, granted("42")), TOKEN);
		assertFalse("an answer from another server", plugin.grants.accountCaptureEnabled());
		plugin.grants.staffApiBase = ServerGrants.OFFICIAL_API_BASE;
		plugin.grants.applyServerPolicy(answer(200, granted("42")), TOKEN);
		assertFalse("a custom API base never grants it", plugin.grants.accountCaptureEnabled());
		assertNull(plugin.grants.accountCaptureGrant);

		plugin.grants.staffApiBase = apiBase;
		assertTrue(grantAndCheck());
		apiBase = "https://example.invalid/api";
		assertFalse("a grant holds only while the setting names the official endpoint", plugin.grants.accountCaptureEnabled());
	}

	private ItemContainer container(int id, int... itemIds)
	{
		ItemContainer c = mock(ItemContainer.class);
		Item[] items = new Item[itemIds.length];
		for (int i = 0; i < itemIds.length; i++) items[i] = new Item(itemIds[i], 1);
		when(c.getId()).thenReturn(id);
		when(c.getItems()).thenReturn(items);
		return c;
	}

	/** The game sent this container during this login. */
	private void read(int id, int... itemIds)
	{
		containers.put(id, container(id, itemIds));
		plugin.snapshots.containerObservedAtMs.put(id, System.currentTimeMillis());
	}

	/** The player opens their own collection log and the scan sees every obtained slot. */
	private void scanWholeLog()
	{
		vars[2943] = 2;
		vars[2944] = 1600;
		plugin.collectionScanReady();
		tick += 2;
		plugin.onScriptPostFired(new ScriptPostFired(7797));
		plugin.onGameTick(new GameTick());
		for (int item : new int[]{4151, 11832})
		{
			ScriptEvent e = mock(ScriptEvent.class);
			when(e.getArguments()).thenReturn(new Object[]{4100, item, 1});
			ScriptPreFired fired = new ScriptPreFired(4100);
			fired.setScriptEvent(e);
			plugin.onScriptPreFired(fired);
		}
		tick += 3;
		plugin.onGameTick(new GameTick());
	}

	@Test
	public void eachRowStateComesFromWhatThisLoginRead() throws Exception
	{
		assertArrayEquals("nothing read: the looting bag is unknown until the bank is", new String[]{M, M, M, M}, overlay.states());

		read(InventoryID.BANK, ItemID.ABYSSAL_WHIP);
		assertArrayEquals("a bank without a bag and none carried: no looting bag owned",
			new String[]{C, AccountCaptureOverlay.NOT_NEEDED, M, M}, overlay.states());
		containers.put(InventoryID.INV, container(InventoryID.INV, ItemID.LOOTING_BAG_OPEN));
		assertEquals("a bag in the inventory needs checking", M, overlay.states()[1]);
		containers.remove(InventoryID.INV);
		read(InventoryID.BANK, ItemID.LOOTING_BAG);
		assertEquals("a bag in the bank needs checking", M, overlay.states()[1]);

		containers.put(InventoryID.LOOTING_BAG, container(InventoryID.LOOTING_BAG, ItemID.COINS));
		assertEquals("a bag the game kept from before this login is not a read", M, overlay.states()[1]);
		read(InventoryID.LOOTING_BAG, ItemID.COINS);
		assertEquals(C, overlay.states()[1]);

		scanWholeLog();
		assertEquals("the whole collection log, verified against the game's counts", C, overlay.states()[2]);
		vars[2943] = 3;
		assertEquals("a new item since the scan: the log is no longer whole", M, overlay.states()[2]);
		vars[2943] = 2;

		houseLocation = 0;
		assertEquals(AccountCaptureOverlay.NO_HOUSE, overlay.states()[3]);
		houseLocation = 3;
		assertEquals(M, overlay.states()[3]);
		containers.put(InventoryID.POH_COSTUMES, container(InventoryID.POH_COSTUMES, ItemID.ABYSSAL_WHIP));
		assertEquals("a costume room the game kept from before this login is not a read", M, overlay.states()[3]);
		read(InventoryID.POH_COSTUMES, ItemID.ABYSSAL_WHIP);
		assertArrayEquals(new String[]{C, C, C, C}, overlay.states());

		containers.remove(InventoryID.BANK);	// closed; the client keeps the other containers
		plugin.session.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		tick += 1;
		assertArrayEquals("a logout clears every read", new String[]{M, M, M, M}, overlay.states());
	}

	private static GameStateChanged gameState(GameState s)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		return e;
	}

	@Test
	public void thePromptIsTheFirstMissingRow()
	{
		String nn = AccountCaptureOverlay.NOT_NEEDED, nh = AccountCaptureOverlay.NO_HOUSE;
		assertEquals("Open your bank.", AccountCaptureOverlay.prompt(new String[]{M, M, M, M}));
		assertEquals("Check your looting bag.", AccountCaptureOverlay.prompt(new String[]{C, M, M, M}));
		assertEquals("Open your collection log.", AccountCaptureOverlay.prompt(new String[]{C, nn, M, M}));
		assertEquals("Enter your house.", AccountCaptureOverlay.prompt(new String[]{C, C, C, M}));
		assertEquals("Check your looting bag.", AccountCaptureOverlay.prompt(new String[]{C, M, C, M}));
		assertNull("nothing missing, no prompt", AccountCaptureOverlay.prompt(new String[]{C, nn, C, nh}));
	}

	@Test
	public void theFooterFollowsTheUploader()
	{
		SnapshotUploader u = plugin.uploader;
		u.lastBuiltHash = "a";
		assertEquals("Uploading...", overlay.footer());
		u.lastUploadedHash = "a";
		assertEquals("Upload complete.", overlay.footer());
		u.lastBuiltHash = "b";
		assertEquals("a newer state not yet sent", "Uploading...", overlay.footer());
		u.snapshotRetryPending = true;
		assertEquals("Upload failed. It will retry.", overlay.footer());
		u.snapshotsInFlight.incrementAndGet();
		assertEquals("the retry is on its way", "Uploading...", overlay.footer());
	}

	private List<String> drawn() throws Exception
	{
		overlay.setClearChildren(false);
		overlay.getPanelComponent().getChildren().clear();
		assertNotNull(overlay.render(new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB).createGraphics()));
		List<String> out = new ArrayList<>();
		for (LayoutableRenderableEntity child : overlay.getPanelComponent().getChildren())
		{
			for (String name : new String[]{"text", "left", "right"})
			{
				try
				{
					Field f = child.getClass().getDeclaredField(name);
					f.setAccessible(true);
					if (f.get(child) != null) out.add((String) f.get(child));
				}
				catch (NoSuchFieldException ignored)
				{
					// a title has text, a line left and right
				}
			}
		}
		return out;
	}

	@Test
	public void thePanelDrawsOnlyTheApprovedTextAndOnlyWhenGranted() throws Exception
	{
		assertNull("off: nothing drawn", overlay.render(new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics()));
		assertTrue(grantAndCheck());
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		assertNull("not drawn at the login screen", overlay.render(new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics()));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		read(InventoryID.BANK, ItemID.ABYSSAL_WHIP);
		assertEquals(java.util.Arrays.asList("Account capture", "Bank", C, "Looting bag", "Not needed", "Collection log", M,
			"House storage", M, "Open your collection log.", "Uploading..."), drawn());
	}

	private String nextSnapshotKeys(MockResponse answer) throws Exception
	{
		server.enqueue(answer);
		xp += 100;
		plugin.syncTask();
		RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull(r);
		assertEquals("/api/account-ingest", r.getPath());
		for (int i = 0; i < 500 && plugin.uploader.snapshotsInFlight.get() > 0; i++) Thread.sleep(10);
		JsonObject body = new JsonParser().parse(r.getBody().readUtf8()).getAsJsonObject();
		assertEquals(new TreeSet<>(java.util.Arrays.asList("token", "snapshot")), body.keySet());
		TreeSet<String> keys = new TreeSet<>(body.getAsJsonObject("snapshot").keySet());
		keys.remove("game_vars");	// on its own 15-minute interval, granted or not
		return keys.toString();
	}

	@Test
	public void theGrantSendsNothingNew() throws Exception
	{
		plugin.uploader.minUploadIntervalMillis = 1;
		read(InventoryID.POH_COSTUMES, ItemID.ABYSSAL_WHIP);
		MockResponse broadOnly = new MockResponse().setBody("{\"ok\":true}").setHeader("X-Broad-Capture", "on");
		MockResponse withGrant = broadOnly.clone().setHeader("X-Account-Capture", "on").setHeader("X-Account-Capture-Account", "42");
		String ungranted = nextSnapshotKeys(broadOnly);
		assertFalse("no broad capture yet: no costume room", ungranted.contains("poh_costume_room"));
		String broad = nextSnapshotKeys(withGrant);
		assertTrue(broad.contains("poh_costume_room"));
		assertTrue(plugin.grants.accountCaptureEnabled());
		String withAccountCapture = nextSnapshotKeys(broadOnly);
		assertFalse(plugin.grants.accountCaptureEnabled());
		assertEquals("the account capture grant adds nothing to the snapshot", broad, withAccountCapture);
		assertEquals(broad, nextSnapshotKeys(broadOnly));
	}
}
