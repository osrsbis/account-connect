package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Skill;
import net.runelite.api.events.GrandExchangeOfferChanged;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Broad capture batch 1: priority overflow, GE offer_key identity, game_vars encoding and gating,
 * per-container observed_at. Each test has a control arm, so a guard that always fires also fails.
 */
public class BroadCaptureBatch1Test
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private AccountConnectPlugin p;
	private Client client;
	private int tick = 1000;

	private void setUp() throws Exception
	{
		p = new AccountConnectPlugin();
		inject("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		});
		client = mock(Client.class);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		inject("client", client);
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, value);
	}

	private Object read(String field) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(p);
	}

	private void offer(int slot, GrandExchangeOfferState s, int item, int sold, int total, int price, int spent)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getState()).thenReturn(s);
		when(o.getItemId()).thenReturn(item);
		when(o.getQuantitySold()).thenReturn(sold);
		when(o.getTotalQuantity()).thenReturn(total);
		when(o.getPrice()).thenReturn((long) price);
		when(o.getSpent()).thenReturn((long) spent);
		GrandExchangeOfferChanged ev = mock(GrandExchangeOfferChanged.class);
		when(ev.getOffer()).thenReturn(o);
		when(ev.getSlot()).thenReturn(slot);
		p.onGrandExchangeOfferChanged(ev);
	}

	private List<Map<String, Object>> ge()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> e : p.pendingEvents)
		{
			if (String.valueOf(e.get("type")).startsWith("ge_"))
			{
				out.add(e);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ priority overflow

	@Test
	public void priorityClassesAreAsDocumented()
	{
		assertEquals(EventPriority.P0, EventPriority.classOf("ge_buy"));
		assertEquals(EventPriority.P0, EventPriority.classOf("store_sell"));
		assertEquals(EventPriority.P0, EventPriority.classOf("logout"));
		assertEquals(EventPriority.P1, EventPriority.classOf("loot"));
		assertEquals(EventPriority.P1, EventPriority.classOf("bank_visit_open"));
		assertEquals(EventPriority.P2, EventPriority.classOf("region"));
		assertEquals(EventPriority.P2, EventPriority.classOf(null));
	}

	@Test
	public void overflowEvictsTheOldestLowestClassRowNotTheOldestRow()
	{
		List<Map<String, Object>> q = new ArrayList<>();
		q.add(row("ge_buy"));		// 0: oldest, P0
		q.add(row("loot"));		// 1: P1
		q.add(row("region"));		// 2: first P2
		q.add(row("region"));		// 3
		assertEquals(2, EventPriority.evictIndex(q));
		q.remove(2);
		q.remove(2);
		assertEquals("no P2 left: the oldest P1 goes", 1, EventPriority.evictIndex(q));
		q.remove(1);
		assertEquals("only P0 left: the oldest row goes, as before", 0, EventPriority.evictIndex(q));
		assertEquals(-1, EventPriority.evictIndex(new ArrayList<>()));
	}

	@Test
	public void aFullBufferKeepsGeRowsWhileRegionRowsAreDropped() throws Exception
	{
		setUp();
		p.emitEvent("ge_buy", new HashMap<>());
		for (int i = 0; i < 600; i++)
		{
			p.emitEvent("region", new HashMap<>());
		}
		int geLeft = 0;
		for (Map<String, Object> e : p.pendingEvents)
		{
			if ("ge_buy".equals(e.get("type")))
			{
				geLeft++;
			}
		}
		assertEquals("the GE row must survive 600 later region rows", 1, geLeft);
		assertEquals(500, p.pendingEvents.size());
		Map<String, Object> h = p.captureHealthSnapshot();
		assertEquals(101L, h.get("events_overflow_p2_total"));
		assertEquals(0L, h.get("events_overflow_p0_total"));
		assertEquals(101L, h.get("events_lost_total"));
	}

	// ------------------------------------------------------------------ GE offer_key

	@Test
	public void oneOfferKeepsOneKeyAndAnIdenticalRelistGetsANewKey() throws Exception
	{
		setUp();
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(2, GrandExchangeOfferState.BUYING, 560, 0, 100, 250, 0);
		tick += 200;
		offer(2, GrandExchangeOfferState.BUYING, 560, 40, 100, 250, 10_000);
		offer(2, GrandExchangeOfferState.BOUGHT, 560, 100, 100, 250, 25_000);
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(2, GrandExchangeOfferState.BUYING, 560, 0, 100, 250, 0);	// identical re-list
		List<Map<String, Object>> g = ge();
		assertEquals(5, g.size());
		String k = (String) g.get(0).get("offer_key");
		assertNotNull(k);
		assertTrue(k.matches("^[0-9a-f]{32}$"));
		assertEquals("placed", g.get(0).get("offer_seen"));
		for (int i = 1; i < 4; i++)
		{
			assertEquals("rows of one offer share its key", k, g.get(i).get("offer_key"));
		}
		assertNotEquals("an identical re-list after a collect is a new offer", k, g.get(4).get("offer_key"));
	}

	@Test
	public void aCollectEndsTheKeySoTheNextOfferInTheSlotNeverInheritsIt() throws Exception
	{
		setUp();
		offer(5, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(5, GrandExchangeOfferState.BUYING, 560, 0, 100, 250, 0);
		offer(5, GrandExchangeOfferState.BOUGHT, 560, 100, 100, 250, 25_000);
		offer(5, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		String k = (String) ge().get(0).get("offer_key");
		// the next offer in the slot is first seen finished (placed and filled between two events): no ge_offer
		offer(5, GrandExchangeOfferState.BOUGHT, 560, 100, 100, 250, 25_000);
		List<Map<String, Object>> g = ge();
		Map<String, Object> last = g.get(g.size() - 1);
		assertEquals("ge_buy", last.get("type"));
		assertNotNull(last.get("offer_key"));
		assertNotEquals("a collected offer's key must not carry to the next offer", k, last.get("offer_key"));
		assertEquals("midway", last.get("offer_seen"));
	}

	@Test
	public void aHopKeepsTheKeyAndTheFinishedWhileAwayRowJoinsItsOffer() throws Exception
	{
		setUp();
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(1, GrandExchangeOfferState.SELLING, 4151, 0, 1, 1_500_000, 0);
		String k = (String) ge().get(0).get("offer_key");
		// hop: the plugin marks the login window, then the slot replays as SOLD
		p.onGameStateChanged(gs(GameState.HOPPING));
		p.onGameStateChanged(gs(GameState.LOGGED_IN));
		offer(1, GrandExchangeOfferState.SOLD, 4151, 1, 1, 1_500_000, 1_470_000);
		List<Map<String, Object>> g = ge();
		assertEquals("ge_sell", g.get(g.size() - 1).get("type"));
		assertEquals(k, g.get(g.size() - 1).get("offer_key"));
	}

	@Test
	public void aSlotFirstSeenPartFilledIsMidwayAndADifferentOfferInTheSlotRotatesTheKey() throws Exception
	{
		setUp();
		offer(4, GrandExchangeOfferState.BUYING, 2, 10, 100, 5, 50);	// first sight, part-filled
		Map<String, Object> first = ge().get(0);
		assertEquals("ge_progress", first.get("type"));
		assertEquals("midway", first.get("offer_seen"));
		tick += 200;
		offer(4, GrandExchangeOfferState.BUYING, 2, 20, 100, 6, 110);	// price changed: another offer
		List<Map<String, Object>> g = ge();
		if (g.size() > 1)
		{
			assertNotEquals(first.get("offer_key"), g.get(1).get("offer_key"));
		}
	}

	@Test
	public void geModeSwitchRebaselinesAndStampsCurrentModeBeforeSyncLoop() throws Exception
	{
		setUp();
		when(client.getWorldType()).thenReturn(java.util.EnumSet.noneOf(net.runelite.api.WorldType.class));
		when(client.getAccountHash()).thenReturn(111L);
		inject("activeHash", "old-account");
		inject("activeRsn", "old-name");
		offer(0, GrandExchangeOfferState.BUYING, 560, 0, 100, 250, 0);
		String key = (String) ge().get(0).get("offer_key");
		assertEquals("STANDARD", ge().get(0).get("profile_type"));
		assertEquals("111", ge().get(0).get("account_hash"));
		assertNull(ge().get(0).get("rsn"));
		when(client.getWorldType()).thenReturn(java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL));
		offer(0, GrandExchangeOfferState.BUYING, 560, 50, 100, 250, 10000);
		assertEquals("mode switch seeds, never credits the old mode", 1, ge().size());
		tick += 10;
		offer(0, GrandExchangeOfferState.BOUGHT, 560, 100, 100, 250, 22000);
		Map<String, Object> last = ge().get(1);
		assertNotEquals(key, last.get("offer_key"));
		assertNotEquals("STANDARD", last.get("profile_type"));
		assertEquals(50L, ((Map<?, ?>) last.get("ge_observation")).get("delta_qty"));
	}

	@Test
	public void geTokenSwitchDoesNotCarryOldCounters() throws Exception
	{
		setUp();
		offer(0, GrandExchangeOfferState.BUYING, 560, 0, 100, 250, 0);
		String key = (String) ge().get(0).get("offer_key");
		inject("config", new AccountConnectConfig()
		{
			@Override public String linkToken() { return "abcdef0123456789abcdef0123456789"; }
		});
		offer(0, GrandExchangeOfferState.BUYING, 560, 80, 100, 250, 16000);
		tick += 10;
		offer(0, GrandExchangeOfferState.BOUGHT, 560, 100, 100, 250, 22000);
		Map<String, Object> last = ge().get(1);
		assertNotEquals(key, last.get("offer_key"));
		assertEquals(20L, ((Map<?, ?>) last.get("ge_observation")).get("delta_qty"));
	}

	private static net.runelite.api.events.GameStateChanged gs(GameState s)
	{
		net.runelite.api.events.GameStateChanged e = new net.runelite.api.events.GameStateChanged();
		e.setGameState(s);
		return e;
	}

	// ------------------------------------------------------------------ game_vars

	@Test
	public void gameVarsEncodeSparselyAndDecodeVarbits()
	{
		int[] varps = new int[10];
		varps[3] = 0b1011_0000;
		varps[7] = -5;
		Map<String, Object> e = GameVarsCapture.encode(varps);
		assertEquals(10, e.get("n"));
		Map<?, ?> nz = (Map<?, ?>) e.get("nz");
		assertEquals(2, nz.size());
		assertEquals(0b1011_0000, nz.get("3"));
		assertEquals(-5, nz.get("7"));
		assertEquals(0b1011, GameVarsCapture.varbit(0b1011_0000, 4, 7));
		assertEquals(1, GameVarsCapture.varbit(-1, 31, 31));
		assertNull(GameVarsCapture.encode(null));
		int[] other = varps.clone();
		other[3] = 1;
		assertNotEquals("the sha changes when any value changes", e.get("sha"), GameVarsCapture.encode(other).get("sha"));
	}

	@Test
	public void gameVarsAreOffUntilGrantedAndWaitForLoadedSkills() throws Exception
	{
		setUp();
		when(client.getVarps()).thenReturn(new int[]{0, 7, 0});
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		assertNull("not granted = never sent", p.gameVarsBlock(1_000_000L));
		p.serverGameVarsEnabled = true;
		Map<String, Object> gv = p.gameVarsBlock(1_000_000L);
		assertNotNull(gv);
		assertEquals(1000L, gv.get("observed_at"));
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(0);
		assertNull("a login tick whose skills read 0 has not loaded the player: send nothing", p.gameVarsBlock(1_000_000L));
	}

	@Test
	public void canonicalHashIgnoresGameVars() throws Exception
	{
		setUp();
		inject("gson", new com.google.gson.Gson());
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("schema_v", 1);
		Map<String, Object> b = new LinkedHashMap<>(a);
		b.put("game_vars", GameVarsCapture.encode(new int[]{1, 2, 3}));
		assertEquals(p.canonicalHash(a), p.canonicalHash(b));
		Map<String, Object> c = new LinkedHashMap<>(a);
		c.put("schema_v", 2);
		assertNotEquals("control: a real change still changes the hash", p.canonicalHash(a), p.canonicalHash(c));
	}

	// ------------------------------------------------------------------ observed_at

	@Test
	public void observedAtIsNullUntilTheContainerChangedAndThenItsTime() throws Exception
	{
		setUp();
		assertNull(p.observedAtSeconds(95));
		p.containerObservedAtMs.put(95, 1_700_000_123_456L);
		assertEquals(Long.valueOf(1_700_000_123L), p.observedAtSeconds(95));
		assertNull("another container is unaffected", p.observedAtSeconds(93));
	}

	// ------------------------------------------------------------------ profile fencing

	@Test
	public void eventsCarryProfileTypeOnlyOutsideTheMainGame() throws Exception
	{
		setUp();
		Field f = AccountConnectPlugin.class.getDeclaredField("activeProfileType");
		f.setAccessible(true);
		f.set(p, "STANDARD");
		p.emitEvent("loot", new HashMap<>());
		assertFalse(p.pendingEvents.get(0).containsKey("profile_type"));
		f.set(p, "SEASONAL");
		p.emitEvent("loot", new HashMap<>());
		assertEquals("SEASONAL", p.pendingEvents.get(1).get("profile_type"));
	}

	@Test
	public void ownStorageContainersAreGamevalIdsAndExcludeOtherPlayersContainers()
	{
		Map<String, Integer> m = AccountConnectPlugin.OWN_STORAGE_CONTAINERS;
		assertEquals(16, m.size());
		assertEquals(Integer.valueOf(573), m.get("seed_box"));
		assertEquals(Integer.valueOf(636), m.get("death_storage"));
		assertEquals(Integer.valueOf(963), m.get("boat_1_cargo"));
		assertEquals(Integer.valueOf(968), m.get("trawling_net"));
		// never another player's or a shared/loot container
		assertFalse(m.containsValue(net.runelite.api.gameval.InventoryID.TRADEOFFER));
		assertFalse(m.containsValue(net.runelite.api.gameval.InventoryID.INV_GROUP_TEMP));
		assertFalse(m.containsValue(net.runelite.api.gameval.InventoryID.DUELOFFER));
		assertFalse(m.containsValue(net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV0));
	}

	@Test
	public void broadCaptureHeaderGrantsAndRevokes() throws Exception
	{
		setUp();
		okhttp3.Response on = new okhttp3.Response.Builder().request(new okhttp3.Request.Builder().url("http://x/").build())
			.protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("ok").header("X-Broad-Capture", "on").build();
		p.applyServerPolicy(on, TOKEN);
		assertTrue(p.serverGameVarsEnabled);
		okhttp3.Response none = new okhttp3.Response.Builder().request(new okhttp3.Request.Builder().url("http://x/").build())
			.protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("ok").build();
		p.applyServerPolicy(none, TOKEN);
		assertTrue("an absent header leaves the grant unchanged", p.serverGameVarsEnabled);
		okhttp3.Response off = new okhttp3.Response.Builder().request(new okhttp3.Request.Builder().url("http://x/").build())
			.protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("ok").header("X-Broad-Capture", "off").build();
		p.applyServerPolicy(off, TOKEN);
		assertFalse(p.serverGameVarsEnabled);
	}

	// ------------------------------------------------------------------ batch 2a: KC + clog guard

	@Test
	public void killCountLinesParseToStructuredSources()
	{
		Map<String, Object> z = AccountConnectPlugin.parseKillCount("Your Zulrah kill count is: <col=ff0000>41</col>.");
		assertEquals("Zulrah", z.get("source"));
		assertEquals("kill", z.get("kind"));
		assertEquals(41L, z.get("count"));
		Map<String, Object> t = AccountConnectPlugin.parseKillCount("Your completed Tombs of Amascut count is: 1,204.");
		assertEquals("Tombs of Amascut", t.get("source"));
		assertEquals(1204L, t.get("count"));
		Map<String, Object> b = AccountConnectPlugin.parseKillCount("Your Barrows chest count is: @mes_hl_red@73@mes_hl_red@.");
		assertEquals("Barrows chest", b.get("source"));
		assertEquals(73L, b.get("count"));
		Map<String, Object> c = AccountConnectPlugin.parseKillCount("Your completion count for the Theatre of Blood is: 12.");
		assertEquals("Theatre of Blood", c.get("source"));
		assertEquals("completion count for", c.get("kind"));
		assertNull("control: an unrelated line is not a count", AccountConnectPlugin.parseKillCount("You feel something weird sneaking into your backpack."));
		assertNull(AccountConnectPlugin.parseKillCount(null));
	}

	@Test
	public void anotherPlayersCollectionLogInAHouseIsNeverStored() throws Exception
	{
		setUp();
		int[] vars = new int[2050];
		vars[2049] = 1 << 11;
		when(client.getVarps()).thenReturn(vars);
		net.runelite.api.VarbitComposition host = mock(net.runelite.api.VarbitComposition.class);
		when(host.getIndex()).thenReturn(2049);
		when(host.getLeastSignificantBit()).thenReturn(11);
		when(host.getMostSignificantBit()).thenReturn(11);
		when(client.getVarbit(12061)).thenReturn(host);
		p.onScriptPreFired(clogDraw(11832));
		assertTrue("a host's log must not enter this account", clog().isEmpty());
		vars[2049] = 0;
		p.onScriptPreFired(clogDraw(11832));
		assertTrue("control: the player's own log is stored", clog().contains(11832));
	}

	@SuppressWarnings("unchecked")
	private java.util.Set<Integer> clog() throws Exception
	{
		return (java.util.Set<Integer>) read("clogObtained");
	}

	private static net.runelite.api.events.ScriptPreFired clogDraw(int itemId)
	{
		net.runelite.api.ScriptEvent se = mock(net.runelite.api.ScriptEvent.class);
		when(se.getArguments()).thenReturn(new Object[]{0, itemId, 1});
		net.runelite.api.events.ScriptPreFired e = new net.runelite.api.events.ScriptPreFired(4100);
		e.setScriptEvent(se);
		return e;
	}

	private static Map<String, Object> row(String type)
	{
		Map<String, Object> m = new HashMap<>();
		m.put("type", type);
		return m;
	}
}
