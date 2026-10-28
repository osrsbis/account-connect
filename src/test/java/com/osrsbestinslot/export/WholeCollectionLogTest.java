package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.ScriptEvent;
import net.runelite.api.Skill;
import net.runelite.api.VarbitComposition;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.widgets.Widget;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Before;
import org.junit.Test;

/**
 * 0.7.62: the collection log is whole for every linked player. Opening one's own collection log runs the same
 * search scan the account-progress capture uses, and when the scan has seen as many distinct obtained items as the
 * game counts, the snapshot's collection_log carries that whole list with complete:true and the game's counts.
 * Without a verified scan the block is the seen items only, exactly as before.
 */
public class WholeCollectionLogTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int SETUP = 7797;
	private static final int SLOT = 4100;
	private static final int COLLECTION_GROUP = net.runelite.api.gameval.InterfaceID.COLLECTION;

	private Client client;
	private AccountConnectPlugin plugin;
	private int[] vars;
	private int tick = 100;

	@Before
	public void setUp() throws Exception
	{
		client = mock(Client.class);
		vars = new int[5000];
		when(client.getVarps()).thenReturn(vars);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Fixture Main");
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getWorldType()).thenReturn(java.util.EnumSet.noneOf(WorldType.class));
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		when(client.getTickCount()).thenAnswer(i -> tick);
		VarbitComposition host = mock(VarbitComposition.class);	// POH host book: off
		when(host.getIndex()).thenReturn(2049);
		when(host.getLeastSignificantBit()).thenReturn(11);
		when(host.getMostSignificantBit()).thenReturn(11);
		when(client.getVarbit(12061)).thenReturn(host);
		when(client.getWidget(net.runelite.api.gameval.InterfaceID.Collection.SEARCH_TOGGLE)).thenReturn(mock(Widget.class));
		plugin = new AccountConnectPlugin();
		inject("client", client);
		inject("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		});
		inject("gson", new Gson());
		settle();
	}

	private void inject(String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void settle()
	{
		plugin.collectionScanReady();
		tick += 2;
	}

	private void counts(int obtained, int total)
	{
		vars[2943] = obtained;
		vars[2944] = total;
	}

	/** The player opens their collection log: the setup script runs, then the scan draws each obtained slot. */
	private void openAndScan(int[]... slots)
	{
		long before = scriptRuns();
		plugin.onScriptPostFired(new ScriptPostFired(SETUP));
		assertEquals("never inside the setup script", before, scriptRuns());
		plugin.onGameTick(new GameTick());				// the scan starts on the next tick, outside the script
		for (int[] s : slots)
		{
			ScriptEvent e = mock(ScriptEvent.class);
			when(e.getArguments()).thenReturn(new Object[]{SLOT, s[0], s[1]});
			ScriptPreFired fired = new ScriptPreFired(SLOT);
			fired.setScriptEvent(e);
			plugin.onScriptPreFired(fired);
		}
		tick += 3;	// the quiet period after the last slot
		plugin.onGameTick(new GameTick());
	}

	private long scriptRuns()
	{
		return org.mockito.Mockito.mockingDetails(client).getInvocations().stream()
			.filter(i -> i.getMethod().getName().equals("runScript") || i.getMethod().getName().equals("menuAction")).count();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> log() throws Exception
	{
		Method build = AccountConnectPlugin.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		return (Map<String, Object>) ((Map<String, Object>) build.invoke(plugin)).get("collection_log");
	}

	@Test
	public void aVerifiedScanSendsTheWholeLog() throws Exception
	{
		counts(3, 1600);
		openAndScan(new int[]{11832, 1}, new int[]{4151, 2}, new int[]{12073, 0}, new int[]{13576, 1});
		verify(client).menuAction(-1, net.runelite.api.gameval.InterfaceID.Collection.SEARCH_TOGGLE,
			MenuAction.CC_OP, 1, -1, "Search", null);
		Map<String, Object> log = log();
		assertEquals(true, log.get("complete"));
		assertEquals("obtained only, sorted", Arrays.asList(4151, 11832, 13576), log.get("obtained_item_ids"));
		assertEquals(3, log.get("obtained_count"));
		assertEquals(3, log.get("game_obtained_count"));
		assertEquals(1600, log.get("game_total_count"));
		assertEquals(true, log.get("synced"));
	}

	@Test
	public void anOpenLogIsScannedEvenWithoutTheSetupScriptEvent() throws Exception
	{
		counts(1, 1600);
		inject("backoffUntilMillis", Long.MAX_VALUE);		// no upload in this fixture
		net.runelite.api.events.WidgetLoaded loaded = new net.runelite.api.events.WidgetLoaded();
		loaded.setGroupId(COLLECTION_GROUP);
		plugin.onWidgetLoaded(loaded);
		plugin.onGameTick(new GameTick());
		assertEquals("not on the open tick", 0, scriptRuns());
		tick += 2;
		plugin.onGameTick(new GameTick());
		verify(client, times(1)).runScript(2240);
		assertEquals("scanning", log().get("scan_status"));
		ScriptEvent e = mock(ScriptEvent.class);
		when(e.getArguments()).thenReturn(new Object[]{SLOT, 4151, 1});
		ScriptPreFired fired = new ScriptPreFired(SLOT);
		fired.setScriptEvent(e);
		plugin.onScriptPreFired(fired);
		tick += 3;
		plugin.onGameTick(new GameTick());
		assertEquals(true, log().get("complete"));
		assertEquals("count_verified", log().get("scan_status"));
		tick += 200;
		plugin.onGameTick(new GameTick());
		verify(client, times(1)).runScript(2240);
	}

	@Test
	public void aShortScanIsNotComplete() throws Exception
	{
		counts(3, 1600);
		openAndScan(new int[]{11832, 1}, new int[]{4151, 2});
		Map<String, Object> log = log();
		assertFalse(log.containsKey("complete"));
		assertFalse(log.containsKey("game_obtained_count"));
		assertEquals("the seen items still go, as before", Arrays.asList(4151, 11832), log.get("obtained_item_ids"));
	}

	@Test
	public void aNewItemAfterTheScanEndsCompletenessUntilTheNextScan() throws Exception
	{
		counts(2, 1600);
		openAndScan(new int[]{11832, 1}, new int[]{4151, 1});
		assertEquals(true, log().get("complete"));
		plugin.onWidgetClosed(new WidgetClosed(COLLECTION_GROUP, 0, false));
		counts(3, 1600);								// a new collection log item
		plugin.onGameTick(new GameTick());
		assertFalse(log().containsKey("complete"));
		tick += 100;									// past the scan cooldown
		clearInvocations(client);
		openAndScan(new int[]{11832, 1}, new int[]{4151, 1}, new int[]{13576, 1});
		assertEquals(true, log().get("complete"));
		assertEquals(Arrays.asList(4151, 11832, 13576), log().get("obtained_item_ids"));
	}

	@Test
	public void aReopenWithUnchangedCountsDoesNotScanAgain() throws Exception
	{
		counts(1, 1600);
		openAndScan(new int[]{4151, 1});
		plugin.onWidgetClosed(new WidgetClosed(COLLECTION_GROUP, 0, false));
		tick += 100;
		plugin.onScriptPostFired(new ScriptPostFired(SETUP));
		plugin.onGameTick(new GameTick());
		verify(client, times(1)).runScript(2240);
		assertEquals(true, log().get("complete"));
	}

	@Test
	public void anotherPlayersLogInAHouseIsNeverScanned() throws Exception
	{
		counts(1, 1600);
		vars[2049] = 1 << 11;							// viewing a house host's collection log
		openAndScan(new int[]{4151, 1});
		verify(client, never()).runScript(2240);
		Map<String, Object> log = log();
		assertFalse(log.containsKey("complete"));
		assertTrue(((List<?>) log.get("obtained_item_ids")).isEmpty());
	}

	@Test
	public void noScanWithoutALinkOrWithUploadsOff() throws Exception
	{
		inject("config", new AccountConnectConfig()
		{
		});
		counts(1, 1600);
		openAndScan(new int[]{4151, 1});
		verify(client, never()).runScript(2240);
		assertFalse(log().containsKey("complete"));
	}

	@Test
	public void aRelogToAnotherAccountClearsTheVerifiedLog() throws Exception
	{
		counts(1, 1600);
		openAndScan(new int[]{4151, 1});
		assertEquals(true, log().get("complete"));
		when(client.getAccountHash()).thenReturn(43L);
		assertFalse(plugin.collectionScanReady());
		assertFalse(log().containsKey("complete"));
	}

	@Test
	public void theAccountProgressGrantKeepsItsOwnScan() throws Exception
	{
		plugin.applyServerPolicy(new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
			.protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Account-Progress", "on").build(), TOKEN);
		plugin.progressBlock();
		tick += 2;
		assertFalse("the grant holder does not use the player scan", plugin.collectionScanReady());
		counts(1, 1600);
		openAndScan(new int[]{4151, 1});
		verify(client, times(1)).runScript(2240);
		assertEquals(true, log().get("complete"));
		Map<?, ?> progress = (Map<?, ?>) plugin.progressBlock().get("collection_log");
		assertEquals(true, progress.get("complete"));
	}

	@Test
	public void theHashSeesTheWholeLog() throws Exception
	{
		counts(2, 1600);
		openAndScan(new int[]{4151, 1});
		Method build = AccountConnectPlugin.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		@SuppressWarnings("unchecked")
		String before = plugin.canonicalHash((Map<String, Object>) build.invoke(plugin));
		openAndScan(new int[]{11832, 1});					// the same scan sees the second item
		@SuppressWarnings("unchecked")
		String after = plugin.canonicalHash((Map<String, Object>) build.invoke(plugin));
		assertEquals(true, log().get("complete"));
		assertFalse("a now complete log is uploaded", before.equals(after));
		assertNull(plugin.progressBlock());
	}
}
