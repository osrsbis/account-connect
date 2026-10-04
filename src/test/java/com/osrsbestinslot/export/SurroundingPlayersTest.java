package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Staff Wilderness surrounding-player snapshots on `drop` and `trade`: current-at-event only, staff only,
 * Wilderness only, bounded to the server's per-event size, presence never attribution.
 */
public class SurroundingPlayersTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	/** Live /event-ingest per-event fields cap. Over it the event is skipped with a 200. */
	private static final int SERVER_FIELDS_CAP = 8192;

	static Player player(String name, int x, int y, int cb)
	{
		Player p = mock(Player.class);
		when(p.getName()).thenReturn(name);
		when(p.getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
		when(p.getCombatLevel()).thenReturn(cb);
		return p;
	}

	/** A plugin wired to a mocked client. staff = both server grants; wild = IN_WILDERNESS. */
	static final class Rig
	{
		final AccountConnectPlugin plugin = new AccountConnectPlugin();
		final Client client = mock(Client.class);
		final Player self = player("StaffMule", 3200, 3700, 3);
		final List<Player> players = new ArrayList<>();
		int wild;

		Rig(boolean staff, boolean wilderness) throws Exception
		{
			inject(plugin, "config", new AccountConnectConfig()
			{
				@Override
				public String linkToken()
				{
					return TOKEN;
				}
			});
			wild = wilderness ? 1 : 0;
			when(client.getLocalPlayer()).thenReturn(self);
			when(client.getPlayers()).thenAnswer(i -> new ArrayList<>(players));
			when(client.getVarbitValue(Varbits.IN_WILDERNESS)).thenAnswer(i -> wild);
			when(client.getWorld()).thenReturn(301);
			when(client.getTickCount()).thenReturn(100);
			inject(plugin, "client", client);
			players.add(self);
			plugin.setStoreToolsForTest(staff);
			plugin.setDropProofRolloutForTest(staff);
		}
	}

	static void inject(Object target, String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Map<String, Object> eventOfType(AccountConnectPlugin p, String type)
	{
		for (Map<String, Object> e : p.pendingEvents)
		{
			if (type.equals(e.get("type")))
			{
				return e;
			}
		}
		return null;
	}

	/** A confirmed drop: arm the pending with the click-time Wilderness flag, then the spawn completes it. */
	private static Map<String, Object> drop(Rig r, boolean wildAtClick) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("invDeltaPendings");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		java.util.Deque<AccountConnectPlugin.InvDeltaPending> d =
			(java.util.Deque<AccountConnectPlugin.InvDeltaPending>) f.get(r.plugin);
		d.clear();
		d.add(new AccountConnectPlugin.InvDeltaPending("drop", 526, null, 1L, 0L, null, wildAtClick, 100));
		r.plugin.resolveDropPendingOnGroundSpawn(526, 1, 0L, 101);
		Map<String, Object> e = eventOfType(r.plugin, "drop");
		assertNotNull("the own-account drop row must still go out", e);
		return e;
	}

	/** An accepted trade with something given, so emitTradeEvent emits. */
	private static Map<String, Object> trade(Rig r, String receivedText) throws Exception
	{
		List<Map<String, Object>> given = new ArrayList<>();
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", 995);
		item.put("qty", 1_000_000);
		given.add(item);
		inject(r.plugin, "pendingTradeGiven", given);
		inject(r.plugin, "pendingCounterparty", "Partner");
		inject(r.plugin, "pendingReceivedText", receivedText);
		r.plugin.emitTradeEvent();
		Map<String, Object> e = eventOfType(r.plugin, "trade");
		assertNotNull("the own-account trade row must still go out", e);
		return e;
	}

	/** The server's fields blob: every key except the reserved columns. The internal key never leaves. */
	private static int serverFieldsLength(Map<String, Object> ev)
	{
		Map<String, Object> extra = new LinkedHashMap<>(ev);
		for (String k : new String[]{"type", "ts", "account_hash", "rsn", "__delivery_token_fp"})
		{
			extra.remove(k);
		}
		return new Gson().toJson(extra).length();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> block(Map<String, Object> ev, String key)
	{
		return (Map<String, Object>) ev.get(key);
	}

	// ---- the live set ----

	@Test
	public void everyLoadedPlayerIsListedNearestFirstWithNameTiebreakAndNoFloorFilter() throws Exception
	{
		Rig r = new Rig(true, true);
		r.players.add(player("Far", 3290, 3700, 50));		// 90 tiles
		r.players.add(player("Bravo", 3202, 3700, 60));
		r.players.add(player("Alpha", 3198, 3700, 70));
		Player otherFloor = mock(Player.class);			// impossible on a real client, but never dropped
		when(otherFloor.getName()).thenReturn("Upstairs");
		when(otherFloor.getWorldLocation()).thenReturn(new WorldPoint(3205, 3700, 1));
		r.players.add(otherFloor);
		r.players.add(null);
		r.players.add(player(null, 3201, 3700, 1));
		List<Map<String, Object>> rows = r.plugin.surroundingPlayersNow();
		assertEquals(4, rows.size());
		assertEquals("Alpha", rows.get(0).get("rsn"));		// equal distance 2: name decides
		assertEquals("Bravo", rows.get(1).get("rsn"));
		assertEquals("Upstairs", rows.get(2).get("rsn"));
		assertEquals(5, rows.get(2).get("dist"));			// 2D Chebyshev: floor unknown
		assertEquals("Far", rows.get(3).get("rsn"));
		assertEquals(90, rows.get(3).get("dist"));
		assertEquals(-2, rows.get(0).get("dx"));
		assertEquals(70, rows.get(0).get("cb"));
	}

	@Test
	public void noClientOrNoSelfMeansAnEmptyList() throws Exception
	{
		assertTrue(new AccountConnectPlugin().surroundingPlayersNow().isEmpty());
		Rig r = new Rig(true, true);
		when(r.client.getLocalPlayer()).thenReturn(null);
		r.players.add(player("Someone", 3201, 3700, 3));
		assertTrue(r.plugin.surroundingPlayersNow().isEmpty());
	}

	// ---- drop: staff x Wilderness ----

	@Test
	public void aStaffWildernessDropCarriesTheSnapshot() throws Exception
	{
		Rig r = new Rig(true, true);
		r.players.add(player("Buyer", 3203, 3701, 90));
		r.players.add(player("Watcher", 3230, 3710, 40));
		Map<String, Object> s = block(drop(r, true), "surrounding_at_drop");
		assertNotNull("a staff Wilderness drop must carry who was loaded", s);
		assertEquals("client_loaded", s.get("observation"));
		assertEquals(AccountConnectPlugin.SURROUNDING_OBSERVATION, s.get("observation"));
		assertEquals(false, s.get("plane_known"));
		assertEquals(true, s.get("wilderness"));
		assertEquals(java.util.Arrays.asList(3200, 3700, 0), s.get("anchor"));
		assertEquals(2, s.get("observed_count"));
		assertEquals(2, s.get("emitted_count"));
		assertEquals(false, s.get("truncated"));
		assertFalse(s.containsKey("truncation_reason"));
		List<?> rows = (List<?>) s.get("players");
		assertEquals("Buyer", ((Map<?, ?>) rows.get(0)).get("rsn"));
		assertEquals(3, ((Map<?, ?>) rows.get(0)).get("dx"));
		assertEquals(1, ((Map<?, ?>) rows.get(0)).get("dy"));
		assertEquals("Watcher", ((Map<?, ?>) rows.get(1)).get("rsn"));
	}

	@Test
	public void theDropGateIsTheClickTimeWildernessFlag() throws Exception
	{
		Rig r = new Rig(true, true);
		r.players.add(player("Bystander", 3201, 3700, 3));
		assertFalse("dropped outside the Wilderness: no snapshot even if the varbit reads 1 later",
			drop(r, false).containsKey("surrounding_at_drop"));
	}

	@Test
	public void aStaffDropOutsideTheWildernessCarriesNothing() throws Exception
	{
		Rig r = new Rig(true, false);
		r.players.add(player("Bystander", 3201, 3700, 3));
		assertFalse(drop(r, true).containsKey("surrounding_at_drop"));
	}

	@Test
	public void aPublicWildernessDropCarriesNothing() throws Exception
	{
		Rig r = new Rig(false, true);
		r.players.add(player("Bystander", 3201, 3700, 3));
		Map<String, Object> e = drop(r, true);
		assertFalse(e.containsKey("surrounding_at_drop"));
		assertFalse(new Gson().toJson(e).contains("Bystander"));
	}

	@Test
	public void storeToolsWithoutTheDropProofGrantCarriesNothing() throws Exception
	{
		Rig r = new Rig(true, true);
		r.plugin.setDropProofRolloutForTest(false);
		r.players.add(player("Bystander", 3201, 3700, 3));
		assertFalse(drop(r, true).containsKey("surrounding_at_drop"));
		assertFalse(trade(r, null).containsKey("surrounding_at_trade"));
	}

	@Test
	public void clipsForcedOffOnAStaffTokenAlsoSwitchesTheSnapshotsOff() throws Exception
	{
		Rig r = new Rig(true, true);
		r.plugin.serverClipsDisabled = true;
		r.players.add(player("Bystander", 3201, 3700, 3));
		assertFalse(drop(r, true).containsKey("surrounding_at_drop"));
		assertFalse(trade(r, null).containsKey("surrounding_at_trade"));
	}

	// ---- trade: staff x Wilderness ----

	@Test
	public void aStaffWildernessTradeCarriesTheSnapshotAndKeepsItsCounterparty() throws Exception
	{
		Rig r = new Rig(true, true);
		r.players.add(player("Partner", 3201, 3700, 100));
		r.players.add(player("Onlooker", 3196, 3696, 80));
		Map<String, Object> e = trade(r, null);
		assertEquals("presence never changes the trade partner", "Partner", e.get("counterparty"));
		Map<String, Object> s = block(e, "surrounding_at_trade");
		assertNotNull("a staff Wilderness trade must carry who was loaded", s);
		assertEquals("client_loaded", s.get("observation"));
		assertEquals(false, s.get("plane_known"));
		assertEquals(true, s.get("wilderness"));
		assertEquals(java.util.Arrays.asList(3200, 3700, 0), s.get("anchor"));
		assertEquals(2, s.get("observed_count"));
		List<?> rows = (List<?>) s.get("players");
		assertEquals("Partner", ((Map<?, ?>) rows.get(0)).get("rsn"));
		assertEquals("Onlooker", ((Map<?, ?>) rows.get(1)).get("rsn"));
		assertFalse(e.containsKey("recipient"));
	}

	@Test
	public void aStaffTradeOutsideTheWildernessCarriesNothing() throws Exception
	{
		Rig r = new Rig(true, false);
		r.players.add(player("Onlooker", 3201, 3700, 3));
		assertFalse(trade(r, null).containsKey("surrounding_at_trade"));
	}

	/** A public user's whole event stream carries no other-player name except the existing counterparty. */
	@Test
	public void aPublicWildernessTradeAndDropLeakNoBystanderName() throws Exception
	{
		Rig r = new Rig(false, true);
		r.players.add(player("Bystander", 3201, 3700, 3));
		trade(r, null);
		drop(r, true);
		for (Map<String, Object> e : r.plugin.pendingEvents)
		{
			assertFalse(e.get("type") + " must not carry a bystander", new Gson().toJson(e).contains("Bystander"));
			assertFalse(e.containsKey("surrounding_at_trade"));
			assertFalse(e.containsKey("surrounding_at_drop"));
		}
	}

	// ---- bounds ----

	@Test
	public void aCrowdIsCappedAt64RowsWithExactCounts() throws Exception
	{
		Rig r = new Rig(true, true);
		for (int i = 0; i < 140; i++)
		{
			r.players.add(player(String.format("P%03d", i), 3201 + (i % 50), 3700 + i / 50, 3));
		}
		Map<String, Object> s = block(drop(r, true), "surrounding_at_drop");
		assertEquals(140, s.get("observed_count"));
		assertEquals(AccountConnectPlugin.SURROUNDING_ROW_CAP, s.get("emitted_count"));
		assertEquals(true, s.get("truncated"));
		assertEquals("row_cap", s.get("truncation_reason"));
		List<?> rows = (List<?>) s.get("players");
		assertEquals(64, rows.size());
		assertEquals("the nearest are kept", "P000", ((Map<?, ?>) rows.get(0)).get("rsn"));
	}

	@Test
	public void theWorstCaseCrowdStaysUnderTheServerCapOnDropAndTrade() throws Exception
	{
		Rig r = new Rig(true, true);
		for (int i = 0; i < 150; i++)
		{
			// Worst case: 12-char names, 3-digit offsets, 3-digit combat level.
			r.players.add(player(String.format("Abcdefgh%04d", i), 3200 - 104 + i, 3700 - 104, 126));
		}
		Map<String, Object> d = drop(r, true);
		Map<String, Object> t = trade(r, null);
		for (Map<String, Object> e : new Map[]{d, t})
		{
			int len = serverFieldsLength(e);
			assertTrue(e.get("type") + " fields " + len + " must fit the budget",
				len <= AccountConnectPlugin.SURROUNDING_EVENT_BUDGET);
			assertTrue(len < SERVER_FIELDS_CAP);
		}
	}

	/** A long received_text shrinks only the presence rows, never the trade's own fields. */
	@Test
	public void aLongTradeShrinksOnlyThePresenceRowsToTheExactLargestFit() throws Exception
	{
		Rig r = new Rig(true, true);
		for (int i = 0; i < 64; i++)
		{
			r.players.add(player(String.format("Abcdefgh%04d", i), 3200 - 104 + i, 3700 - 104, 126));
		}
		StringBuilder text = new StringBuilder();
		while (text.length() < 4000)
		{
			text.append("Blood rune x 100, ");
		}
		Map<String, Object> e = trade(r, text.toString());
		assertEquals(text.toString(), e.get("received_text"));
		assertEquals("Partner", e.get("counterparty"));
		assertNotNull(e.get("given"));
		Map<String, Object> s = block(e, "surrounding_at_trade");
		int emitted = (Integer) s.get("emitted_count");
		assertTrue("some rows must have been cut, got " + emitted, emitted > 0 && emitted < 64);
		assertEquals("size_budget", s.get("truncation_reason"));
		assertEquals(true, s.get("truncated"));
		assertEquals(64, s.get("observed_count"));
		int len = serverFieldsLength(e);
		assertTrue("fits: " + len, len <= AccountConnectPlugin.SURROUNDING_EVENT_BUDGET);
		// One more row would not have fitted: the cut is the exact largest count.
		List<Map<String, Object>> all = r.plugin.surroundingPlayersNow();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) s.get("players");
		rows.add(all.get(emitted));
		assertTrue("one more row must exceed the budget",
			serverFieldsLength(e) > AccountConnectPlugin.SURROUNDING_EVENT_BUDGET);
	}

	@Test
	public void noRoomAtAllLeavesAnEmptyBlockThatSaysSoAndTheTradeStillShips() throws Exception
	{
		Rig r = new Rig(true, true);
		r.players.add(player("Onlooker", 3201, 3700, 3));
		StringBuilder text = new StringBuilder();
		while (text.length() < 7800)
		{
			text.append("Blood rune x 100, ");
		}
		Map<String, Object> e = trade(r, text.toString());
		Map<String, Object> s = block(e, "surrounding_at_trade");
		assertEquals(0, s.get("emitted_count"));
		assertEquals(1, s.get("observed_count"));
		assertEquals(true, s.get("truncated"));
		assertEquals("size_budget", s.get("truncation_reason"));
		assertTrue(((List<?>) s.get("players")).isEmpty());
		assertEquals(text.toString(), e.get("received_text"));
	}

	@Test
	public void nobodyLoadedIsAnHonestEmptySnapshot() throws Exception
	{
		Rig r = new Rig(true, true);
		Map<String, Object> s = block(drop(r, true), "surrounding_at_drop");
		assertEquals(0, s.get("observed_count"));
		assertEquals(0, s.get("emitted_count"));
		assertEquals(false, s.get("truncated"));
	}

	/** Only current-at-event snapshots: a player who left before the event is not in it. */
	@Test
	public void aSnapshotIsTheMomentNotAHistory() throws Exception
	{
		Rig r = new Rig(true, true);
		Player gone = player("LeftEarlier", 3201, 3700, 3);
		r.players.add(gone);
		drop(r, true);
		r.plugin.pendingEvents.clear();
		r.players.remove(gone);
		r.players.add(player("ArrivedLater", 3202, 3700, 3));
		String json = new Gson().toJson(trade(r, null));
		assertTrue(json.contains("ArrivedLater"));
		assertFalse("no history across events", json.contains("LeftEarlier"));
		assertFalse(json.contains("surrounding_seen"));
	}

	@Test
	public void aRenamedPlayerOnTheSameIndexIsJustTheCurrentNameNeverAnAlias() throws Exception
	{
		Rig r = new Rig(true, true);
		Player p = player("OldName", 3201, 3700, 90);
		r.players.add(p);
		drop(r, true);
		r.plugin.pendingEvents.clear();
		when(p.getName()).thenReturn("NewName");
		String json = new Gson().toJson(drop(r, true));
		assertTrue(json.contains("NewName"));
		assertFalse(json.contains("OldName"));
		assertFalse(json.contains("alias"));
		assertFalse(json.contains("previous"));
	}

	/**
	 * The fit lands on the exact largest row count for EVERY padding of the trade's own text, measured on
	 * the event as the server sees it (event_id included). A sweep, so the cut lands in every position
	 * relative to a row boundary and a budget that forgot any part of the event overshoots somewhere.
	 */
	@Test
	public void theFitIsExactAcrossEveryPadding() throws Exception
	{
		for (int pad = 0; pad < 80; pad++)
		{
			Rig r = new Rig(true, true);
			for (int i = 0; i < 64; i++)
			{
				r.players.add(player(String.format("Abcdefgh%04d", i), 3200 - 104 + i, 3700 - 104, 126));
			}
			StringBuilder text = new StringBuilder();
			while (text.length() < 4000 + pad)
			{
				text.append('x');
			}
			Map<String, Object> e = trade(r, text.toString());
			Map<String, Object> s = block(e, "surrounding_at_trade");
			int emitted = (Integer) s.get("emitted_count");
			int len = serverFieldsLength(e);
			assertTrue("pad " + pad + ": " + len + " over budget", len <= AccountConnectPlugin.SURROUNDING_EVENT_BUDGET);
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> rows = (List<Map<String, Object>>) s.get("players");
			rows.add(r.plugin.surroundingPlayersNow().get(emitted));
			assertTrue("pad " + pad + ": one more row would have fitted",
				serverFieldsLength(e) > AccountConnectPlugin.SURROUNDING_EVENT_BUDGET);
		}
	}
}
