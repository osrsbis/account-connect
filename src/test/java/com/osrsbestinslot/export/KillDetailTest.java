package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Kill detail: timings and personal bests, the Sepulchre and Guardians of the Rift count lines, and reward-pool loot.
 * The message texts are the game's own lines, as RuneLite's Chat Commands and Loot Tracker tests record them.
 */
public class KillDetailTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	@SuppressWarnings("unchecked")
	private static Map<String, Object> only(Map<String, Object> parsed)
	{
		List<Map<String, Object>> t = (List<Map<String, Object>>) parsed.get("timings");
		assertEquals(1, t.size());
		return t.get(0);
	}

	@Test
	public void fightDurationWithPreviousBest()
	{
		Map<String, Object> t = only(KillTiming.parse("Fight duration: <col=ff0000>2:06.40</col>. Personal best: 1:19.20."));
		assertEquals("fight duration", t.get("label"));
		assertEquals(126.4, (Double) t.get("duration_s"), 1e-9);
		assertEquals(false, t.get("new_pb"));
		assertEquals(79.2, (Double) t.get("personal_best_s"), 1e-9);
	}

	@Test
	public void newPersonalBestCarriesItsOwnTime()
	{
		Map<String, Object> t = only(KillTiming.parse("Fight duration: <col=ff0000>1:36</col> (new personal best)."));
		assertEquals(96.0, (Double) t.get("duration_s"), 1e-9);
		assertEquals(true, t.get("new_pb"));
		assertEquals(96.0, (Double) t.get("personal_best_s"), 1e-9);
	}

	@Test
	public void longDurationsAndOtherLabels()
	{
		Map<String, Object> d = only(KillTiming.parse("Duration: <col=ff0000>172:18.40</col>. Personal best: 134:52.20"));
		assertEquals("duration", d.get("label"));
		assertEquals(10338.4, (Double) d.get("duration_s"), 1e-9);
		assertEquals(8092.2, (Double) d.get("personal_best_s"), 1e-9);
		Map<String, Object> lap = only(KillTiming.parse("Lap duration: @mes_hl_red@1:01.20</col> (new personal best)."));
		assertEquals("lap duration", lap.get("label"));
		assertEquals(61.2, (Double) lap.get("duration_s"), 1e-9);
		Map<String, Object> sub = only(KillTiming.parse("Subdued in <col=ef1020>5:20.60</col> (new personal best)."));
		assertEquals("subdued in", sub.get("label"));
		assertEquals(320.6, (Double) sub.get("duration_s"), 1e-9);
		Map<String, Object> ch = only(KillTiming.parse("Challenge duration: <col=ff0000>1:10.00</col>. Personal best: <col=ff0000>0:59.20</col>"));
		assertEquals(70.0, (Double) ch.get("duration_s"), 1e-9);
		assertEquals(59.2, (Double) ch.get("personal_best_s"), 1e-9);
	}

	@Test
	public void raidCompletionHasTeamSize()
	{
		Map<String, Object> r = KillTiming.parse("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: "
			+ "<col=ff0000>11-15 players</col> Duration:</col> <col=ff0000>23:25.40</col> Personal best: </col><col=ff0000>20:19.20</col>");
		assertEquals("11-15 players", r.get("team_size"));
		Map<String, Object> t = only(r);
		assertEquals("raid duration", t.get("label"));
		assertEquals(1405.4, (Double) t.get("duration_s"), 1e-9);
		assertEquals(1219.2, (Double) t.get("personal_best_s"), 1e-9);
		Map<String, Object> pb = KillTiming.parse("<col=ef20ff>Congratulations - your raid is complete!</col><br>Team size: "
			+ "<col=ff0000>24+ players</col> Duration:</col> <col=ff0000>37:04.20</col> (new personal best)</col>>");
		assertEquals("24+ players", pb.get("team_size"));
		assertEquals(true, only(pb).get("new_pb"));
		assertEquals(2224.2, (Double) only(pb).get("personal_best_s"), 1e-9);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void toaChallengeLineGivesRoomAndModedRaidTime()
	{
		Map<String, Object> r = KillTiming.parse("Challenge complete: The Wardens. Duration: <col=ef1020>9:40</col><br>"
			+ "Tombs of Amascut: Expert Mode challenge completion time: <col=ef1020>9:40</col>. Personal best: 8:31");
		assertEquals("The Wardens", r.get("room"));
		List<Map<String, Object>> t = (List<Map<String, Object>>) r.get("timings");
		assertEquals(2, t.size());
		assertEquals("duration", t.get(0).get("label"));
		assertEquals(580.0, (Double) t.get(0).get("duration_s"), 1e-9);
		assertEquals("tombs of amascut: expert mode challenge completion time", t.get(1).get("label"));
		assertEquals(511.0, (Double) t.get(1).get("personal_best_s"), 1e-9);
	}

	@Test
	public void ordinaryLinesAreNotTimings()
	{
		assertNull(KillTiming.parse("Your Zulrah kill count is: 1,024."));
		assertNull(KillTiming.parse("You have a funny feeling like you're being followed."));
		assertNull(KillTiming.parse("The duration of your antifire potion is ending."));
		assertNull(KillTiming.parse(null));
	}

	@Test
	public void sepulchreAndRiftCountLines()
	{
		Map<String, Object> f = KillTiming.parseActivityCount(
			"You have completed Floor 5 of the Hallowed Sepulchre! Total completions: <col=ff0000>1,114</col>.");
		assertEquals("Hallowed Sepulchre Floor 5", f.get("source"));
		assertEquals("completion", f.get("kind"));
		assertEquals(1114L, f.get("count"));
		Map<String, Object> c = KillTiming.parseActivityCount("You have opened the Grand Hallowed Coffin <col=ff0000>36</col> times!");
		assertEquals("Grand Hallowed Coffin", c.get("source"));
		assertEquals(36L, c.get("count"));
		Map<String, Object> g = KillTiming.parseActivityCount("Amount of Rifts you have closed: <col=ff0000>167</col>.");
		assertEquals("Guardians of the Rift", g.get("source"));
		assertEquals(167L, g.get("count"));
		assertNull(KillTiming.parseActivityCount("Your Vorkath kill count is: 5."));
	}

	@Test
	public void rewardLineNeedsItsRegion()
	{
		assertEquals("Reward pool (Tempoross)", RewardLootCapture.source("You found some loot: 3 x Raw harpoonfish.", 12588));
		assertEquals("Reward cart (Wintertodt)", RewardLootCapture.source("You found some loot: 20 x Coal.", 6461));
		assertEquals("Guardians of the Rift", RewardLootCapture.source("You found some loot: 40 x Nature rune.", 14484));
		assertNull("the same words elsewhere are not a reward pool", RewardLootCapture.source("You found some loot: 1 x Coins.", 12850));
		assertEquals("Herbiboar", RewardLootCapture.source(RewardLootCapture.HERBIBOAR_LINE, 1));
	}

	@Test
	public void rewardIsTheGainOnTheNextInventoryChange()
	{
		RewardLootCapture r = new RewardLootCapture();
		Map<Integer, Long> before = new HashMap<>();
		before.put(995, 100L);
		before.put(311, 1L);
		r.arm("Reward pool (Tempoross)", 1000, before);
		Map<Integer, Long> removedOnly = new HashMap<>();
		removedOnly.put(995, 100L);
		assertNull("a change that only removes items is not the reward", r.inventoryChanged(1001, removedOnly));
		Map<Integer, Long> after = new HashMap<>();
		after.put(995, 100L);
		after.put(311, 1L);
		after.put(25564, 7L);
		after.put(554, 30L);
		Map<String, Object> got = r.inventoryChanged(1002, after);
		assertEquals("Reward pool (Tempoross)", got.get("source"));
		List<?> items = (List<?>) got.get("items");
		assertEquals(2, items.size());
		assertFalse("one answered change disarms", r.armed());
	}

	@Test
	public void lateChangeIsNotAReward()
	{
		RewardLootCapture r = new RewardLootCapture();
		r.arm("Herbiboar", 1000, new HashMap<>());
		Map<Integer, Long> after = new HashMap<>();
		after.put(199, 3L);
		assertNull(r.inventoryChanged(1000 + RewardLootCapture.ARM_TICKS + 1, after));
		assertFalse(r.armed());
	}

	// ---- plugin wiring and the server grant ----

	private static AccountConnectPlugin plug() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		Field f = AccountConnectPlugin.class.getDeclaredField("config");
		f.setAccessible(true);
		f.set(p, new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		});
		return p;
	}

	private static Response policy(String... kv)
	{
		Response.Builder b = new Response.Builder().request(new Request.Builder().url("http://localhost/account-ingest").build())
			.protocol(Protocol.HTTP_1_1).code(200).message("OK");
		for (int i = 0; i + 1 < kv.length; i += 2) b.header(kv[i], kv[i + 1]);
		return b.build();
	}

	@Test
	public void nothingIsSentWithoutTheGrant() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.emitKillDetail("Fight duration: <col=ff0000>1:36</col> (new personal best).");
		p.emitKillDetail("Amount of Rifts you have closed: <col=ff0000>167</col>.");
		assertTrue(p.pendingEvents.isEmpty());
		p.applyServerPolicy(policy("X-Kill-Detail", "off"));
		p.emitKillDetail("Fight duration: <col=ff0000>1:36</col> (new personal best).");
		assertTrue(p.pendingEvents.isEmpty());
	}

	@Test
	public void grantedTokenSendsTimingAndCountRows() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.applyServerPolicy(policy("X-Kill-Detail", "on"));
		p.emitKillDetail("Fight duration: <col=ff0000>2:06.40</col>. Personal best: 1:19.20.");
		p.emitKillDetail("You have completed Floor 5 of the Hallowed Sepulchre! Total completions: <col=ff0000>1,114</col>.");
		assertEquals(2, p.pendingEvents.size());
		Map<String, Object> t = p.pendingEvents.get(0);
		assertEquals("kill_timing", t.get("type"));
		assertEquals(126.4, (Double) t.get("duration_s"), 1e-9);
		assertEquals(0, t.get("seq"));
		assertEquals("kill_count", p.pendingEvents.get(1).get("type"));
		assertEquals("Hallowed Sepulchre Floor 5", p.pendingEvents.get(1).get("source"));
		Map<String, Object> wire = PublicTransport.event(t);
		assertEquals("the public projection keeps the timing", 79.2, (Double) wire.get("personal_best_s"), 1e-9);
	}

	@Test
	public void aRefusedAnswerRevokesTheGrant() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.applyServerPolicy(policy("X-Kill-Detail", "on"));
		assertTrue(p.killDetailGranted());
		p.applyServerPolicy(policy());
		assertFalse("an answer without the header turns it off", p.killDetailGranted());
	}

	// ---- kill context, slayer tasks, progress and failure (X-Kill-Context) ----

	@Test
	@SuppressWarnings("unchecked")
	public void killContextKeepsChargesBoostsAndSupplies()
	{
		java.util.List<long[]> worn = java.util.Arrays.asList(new long[]{22324, 1}, new long[]{11212, 87}, new long[]{0, 1});
		java.util.List<long[]> inv = java.util.Arrays.asList(new long[]{2434, 1}, new long[]{385, 12});
		Map<String, Object> c = KillContext.build(worn, inv, new int[]{118, 118, 99, 112, 99, 99, 77}, new int[]{99, 99, 99, 99, 99, 99, 77}, 750, true);
		assertEquals("the empty slot is dropped", 2, ((List<?>) c.get("worn")).size());
		assertEquals(java.util.Arrays.asList(11212L, 87L), ((List<?>) c.get("worn")).get(1));
		assertEquals(java.util.Arrays.asList(118, 99), ((Map<String, Object>) c.get("levels_boosted_real")).get("attack"));
		assertEquals("varp 300 is tenths of a percent", 75, c.get("special_energy"));
		assertNull("a missing read sends nothing", KillContext.build(null, inv, new int[7], new int[7], 0, true));
		Map<String, Object> row = new java.util.LinkedHashMap<>(c);
		row.put("type", "kill_context");
		row.put("source", "Vorkath");
		row.put("count", 499L);
		Map<String, Object> wire = PublicTransport.event(row);
		assertEquals("lists and levels survive the public projection", 2, ((List<?>) wire.get("inventory")).size());
		assertEquals(java.util.Arrays.asList(118, 99), ((Map<String, Object>) wire.get("levels_boosted_real")).get("attack"));
	}

	@Test
	public void slayerTaskOnlyOnAOneStepCounterRise()
	{
		KillContext.SlayerTasks t = new KillContext.SlayerTasks();
		assertNull("first read is the baseline", t.tick(41, 25, 3));
		assertNull(t.tick(41, 25, 1));
		Map<String, Object> done = t.tick(42, 0, 0);
		assertEquals(42, done.get("tasks_completed"));
		assertEquals("the task that just ended", 25, done.get("task_target_id"));
		assertNull("no second report", t.tick(42, 0, 0));
		assertNull("a jump (another account) is a new baseline", t.tick(300, 10, 50));
		assertNull("a drop is a new baseline", t.tick(5, 10, 50));
	}

	@Test
	public void activityCountersReportChangesAfterSettling()
	{
		KillContext.ActivityCounters a = new KillContext.ActivityCounters();
		Map<String, Integer> v = new java.util.LinkedHashMap<>();
		v.put("doom_level_8_completions", 0);
		v.put("tempoross_permits", 40);
		assertTrue("first read is the baseline", a.tick(v).isEmpty());
		v.put("tempoross_permits", 45);
		assertTrue("still settling after login", a.tick(v).isEmpty());
		v.put("doom_level_8_completions", 1);
		List<Map<String, Object>> out = a.tick(v);
		assertEquals("a first clear from 0 counts once settled", 1, out.size());
		assertEquals("doom_level_8_completions", out.get(0).get("key"));
		assertEquals(0, out.get(0).get("from"));
		assertEquals(1, out.get(0).get("to"));
		assertTrue("unchanged sends nothing", a.tick(v).isEmpty());
		v.put("tempoross_permits", 30);
		assertEquals("spending permits is a change too", 30, a.tick(v).get(0).get("to"));
		a.reset();
		v.put("tempoross_permits", 99);
		assertTrue("after a reset the first read is a baseline again", a.tick(v).isEmpty());
		Map<String, Object> row = new java.util.LinkedHashMap<>(out.get(0));
		row.put("type", "activity_counter");
		assertEquals(1, PublicTransport.event(row).get("to"));
		assertEquals("total", PublicTransport.event(row).get("kind"));
	}

	@Test
	public void serviceTotalsReadTheGamesOwnCountersOnce()
	{
		Map<String, Object[]> byKey = new java.util.HashMap<>();
		for (Object[] c : KillContext.ACTIVITY_COUNTERS)
		{
			assertNull("each key once: " + c[0], byKey.put((String) c[0], c));
			assertTrue("varp or varbit: " + c[0], "varp".equals(c[1]) || "varbit".equals(c[1]));
		}
		// The most-ordered services with an unverified or mode-ambiguous chat line read the game's own total.
		assertEquals(1585, byKey.get("zuk_kills")[2]);
		assertEquals(5894, byKey.get("fractured_archive_completions")[2]);
		assertEquals(1528, byKey.get("wintertodt_subdued")[2]);
		assertEquals(1735, byKey.get("cox_cm_completions")[2]);
		assertEquals(3057, byKey.get("tob_hard_completions")[2]);
		assertEquals(3647, byKey.get("toa_expert_completions")[2]);
		assertEquals(2354, byKey.get("corrupted_gauntlet_completions")[2]);
		assertEquals(4807, byKey.get("doom_levels_total")[2]);
		for (String k : new String[]{"zuk_kills", "cox_cm_completions", "tob_hard_completions", "soul_wars_zeal", "mixology_orders"})
		{
			assertEquals("a game total is a varp: " + k, "varp", byKey.get(k)[1]);
		}
	}

	@Test
	public void minigameCountersLabelBalancesAndReadWidgetNumbers()
	{
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (Object[] c : KillContext.ACTIVITY_COUNTERS) keys.add((String) c[0]);
		for (Object[] c : KillContext.WIDGET_COUNTERS) keys.add((String) c[0]);
		for (String b : KillContext.BALANCES) assertTrue("a balance key is a real counter: " + b, keys.contains(b));
		for (String k : new String[]{"castle_wars_wins", "ba_high_gambles", "ba_queen_kills", "golem_crafting_count",
			"soul_wars_wins", "warriors_guild_games", "nmz_reward_points", "vale_totems_research_points", "mta_alchemist_points",
			"pest_control_points", "lms_points", "volcanic_mine_points", "mahogany_homes_points"})
		{
			assertTrue("covered: " + k, keys.contains(k));
		}
		KillContext.ActivityCounters a = new KillContext.ActivityCounters();
		Map<String, Integer> v = new java.util.LinkedHashMap<>();
		v.put("tithe_points", 200);
		v.put("castle_wars_wins", 7);
		a.tick(v); a.tick(v);
		v.put("tithe_points", 50);
		v.put("castle_wars_wins", 8);
		List<Map<String, Object>> out = a.tick(v);
		assertEquals(2, out.size());
		assertEquals("a spend is a balance change", "balance", out.get(0).get("kind"));
		assertEquals("a name-only var is never a counted total", "unverified", out.get(1).get("kind"));
		for (Object[] c : KillContext.ACTIVITY_COUNTERS)
		{
			String k = (String) c[0];
			assertFalse("one kind per key: " + k, KillContext.TOTALS.contains(k) && KillContext.BALANCES.contains(k));
		}
		for (String t : KillContext.TOTALS) assertTrue("a total key is a real counter: " + t, keys.contains(t));
		assertEquals("unverified", KillContext.kindOf("warriors_guild_games"));
		assertEquals("unverified", KillContext.kindOf("ba_queen_kills"));
		assertEquals("total", KillContext.kindOf("zuk_kills"));
		assertEquals(Integer.valueOf(1250), KillContext.widgetNumber("<col=ffffff>Points: 1,250</col>"));
		assertEquals(Integer.valueOf(42), KillContext.widgetNumber("42"));
		assertEquals(Integer.valueOf(1250), KillContext.widgetNumber("1250"));
		for (String bad : new String[]{"Points: -", "Points: -42", "Points: 1.5K", "Points: 1,,250", "1 / 500 points",
			"Points: 12,50", "Points: 1,250 (max)", "", "Points:"})
		{
			assertNull("unknown format stays unknown: " + bad, KillContext.widgetNumber(bad));
		}
		assertNull(KillContext.widgetNumber(null));
		assertNull("too large is unknown, not wrapped", KillContext.widgetNumber("99,999,999,999"));
	}

	@Test
	public void aWidgetSeenLateIsABaselineNotAChange()
	{
		KillContext.ActivityCounters a = new KillContext.ActivityCounters();
		Map<String, Integer> v = new java.util.LinkedHashMap<>();
		v.put("pest_control_points", null);
		for (int i = 0; i < 4; i++) assertTrue(a.tick(v).isEmpty());
		v.put("pest_control_points", 380);
		List<Map<String, Object>> first = a.tick(v);
		assertEquals("a shop opened once still reports its balance", 1, first.size());
		assertEquals("first", first.get(0).get("seen"));
		assertEquals(380, first.get(0).get("to"));
		assertFalse("a first sight has no from: never earned or spent", first.get(0).containsKey("from"));
		assertTrue("unchanged sends nothing", a.tick(v).isEmpty());
		v.put("pest_control_points", null);
		assertTrue("closing the shop sends nothing", a.tick(v).isEmpty());
		v.put("pest_control_points", 420);
		assertEquals(380, a.tick(v).get(0).get("from"));
		Map<String, Object> wire = new java.util.LinkedHashMap<>(first.get(0));
		wire.put("type", "activity_counter");
		Map<String, Object> pub = PublicTransport.event(wire);
		assertEquals("kind survives the public projection", "balance", pub.get("kind"));
		assertEquals("first", pub.get("seen"));
	}

	@Test
	public void countersNeverCompareTwoAccountsAcrossARelog() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.applyServerPolicy(policy("X-Kill-Context", "on"));
		Map<String, Integer> a = new java.util.LinkedHashMap<>();
		a.put("tempoross_permits", 400);
		for (int i = 0; i < 3; i++) assertTrue(p.activityCounters.tick(a).isEmpty());
		assertNull(p.slayerTasks.tick(41, 25, 3));
		// Logout and login to account B with the same token and grant, with no game tick in between.
		net.runelite.api.events.GameStateChanged out = new net.runelite.api.events.GameStateChanged();
		out.setGameState(net.runelite.api.GameState.LOGIN_SCREEN);
		p.onGameStateChanged(out);
		Map<String, Integer> b = new java.util.LinkedHashMap<>();
		b.put("tempoross_permits", 7);
		assertTrue("B's first read is a baseline, not a change from A's", p.activityCounters.tick(b).isEmpty());
		assertNull("B's slayer counter is a baseline, not A's next task", p.slayerTasks.tick(42, 10, 50));
		assertTrue(p.killContextGranted());
	}

	@Test
	public void killContextCarriesTheToaRaidLevelOnlyWhenRead()
	{
		java.util.List<long[]> none = java.util.Collections.emptyList();
		int[] lv = new int[KillContext.SKILLS.length];
		assertEquals(350, KillContext.build(none, none, lv, lv, 1000, true, 350).get("toa_raid_level"));
		assertFalse(KillContext.build(none, none, lv, lv, 1000, true).containsKey("toa_raid_level"));
		Map<String, Object> row = new java.util.LinkedHashMap<>(KillContext.build(none, none, lv, lv, 1000, true, 350));
		row.put("type", "kill_context");
		assertEquals(350, PublicTransport.event(row).get("toa_raid_level"));
	}

	@Test
	public void partyFailureLine()
	{
		assertTrue(KillContext.partyFailed(KillTiming.clean("<col=ff0000>Your party failed to complete the challenge.</col>")));
		assertFalse(KillContext.partyFailed("Your party has entered the dungeon."));
	}

	@Test
	public void contextRowsNeedTheirOwnGrant() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.applyServerPolicy(policy("X-Kill-Detail", "on"));
		p.emitProgressAndFailure("Your party failed to complete the challenge.");
		p.emitProgressAndFailure("New item added to your collection log: Vorkath's head");
		assertTrue("X-Kill-Detail alone does not grant context rows", p.pendingEvents.isEmpty());
		p.applyServerPolicy(policy("X-Kill-Context", "on"));
		p.emitProgressAndFailure("Your party failed to complete the challenge.");
		p.emitProgressAndFailure("New item added to your collection log: Vorkath's head");
		assertEquals(2, p.pendingEvents.size());
		assertEquals("attempt_end", p.pendingEvents.get(0).get("type"));
		assertEquals("failed", p.pendingEvents.get(0).get("outcome"));
		assertEquals("progress", p.pendingEvents.get(1).get("type"));
		assertEquals("collection_log_add", p.pendingEvents.get(1).get("kind"));
		assertEquals("Vorkath's head", PublicTransport.event(p.pendingEvents.get(1)).get("subject"));
		p.applyServerPolicy(policy());
		assertFalse(p.killContextGranted());
	}
}
