package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bank-visit evidence (staff only, consolidated 0.7.17). Follows the server's bank-visit
 * ingest contract. One visit per bank open: bank_visit_open, manifest chunks at open and at close,
 * bank_visit_close. Logic proven on mocked containers; real WidgetLoaded/close timing is the rig's job.
 */
public class BankVisitEvidenceTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int BANK_GROUP = 12;

	private static ItemContainer bank(int[]... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		Item[] arr = new Item[items.length];
		for (int i = 0; i < items.length; i++)
		{
			arr[i] = new Item(items[i][0], items[i][1]);
		}
		when(c.getItems()).thenReturn(arr);
		return c;
	}

	private static ItemContainer bigBank(int stacks)
	{
		int[][] items = new int[stacks][];
		for (int i = 0; i < stacks; i++)
		{
			items[i] = new int[]{30_000 + i, 2_000_000_000 - i};	// worst-case widths
		}
		return bank(items);
	}

	private static Response policy(String... kv)
	{
		Response.Builder b = new Response.Builder()
			.request(new Request.Builder().url("http://localhost/").build())
			.protocol(Protocol.HTTP_1_1).code(200).message("OK");
		for (int i = 0; i + 1 < kv.length; i += 2)
		{
			b.header(kv[i], kv[i + 1]);
		}
		return b.build();
	}

	private static void inject(Object target, String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static final class Rig
	{
		final AccountConnectPlugin p;
		final Client client;

		Rig(AccountConnectPlugin p, Client client)
		{
			this.p = p;
			this.client = client;
		}

		void grant(String value)
		{
			p.applyServerPolicy(value == null ? policy() : policy("X-Bank-Evidence", value), TOKEN);
		}

		void open(ItemContainer b)
		{
			when(client.getItemContainer(InventoryID.BANK)).thenReturn(b);
			p.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);
		}

		void change(ItemContainer b)
		{
			when(client.getItemContainer(InventoryID.BANK)).thenReturn(b);
			p.handleBankContainerChanged(b);
		}

		void close()
		{
			p.handleBankWidgetClosed(BANK_GROUP);
		}

		void state(GameState s)
		{
			when(client.getGameState()).thenReturn(s);
			GameStateChanged e = new GameStateChanged();
			e.setGameState(s);
			p.onGameStateChanged(e);
		}

		List<Map<String, Object>> of(String type)
		{
			List<Map<String, Object>> out = new ArrayList<>();
			for (Map<String, Object> e : p.pendingEvents)
			{
				if (type.equals(e.get("type")))
				{
					out.add(e);
				}
			}
			return out;
		}

		List<Map<String, Object>> chunks(String phase)
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
	}

	private static Rig rig() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		});
		inject(p, "gson", new Gson());
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		inject(p, "client", client);
		return new Rig(p, client);
	}

	@SuppressWarnings("unchecked")
	private static List<long[]> items(List<Map<String, Object>> chunks)
	{
		List<long[]> out = new ArrayList<>();
		for (Map<String, Object> c : chunks)
		{
			out.addAll((List<long[]>) c.get("items"));
		}
		return out;
	}

	// ---- the staff grant ----

	@Test
	public void noGrantSendsNoBankVisitEvents() throws Exception
	{
		Rig r = rig();
		r.open(bank(new int[]{995, 10}));
		r.close();
		assertTrue(r.of("bank_visit_open").isEmpty());
		assertTrue(r.of("bank_visit_manifest_chunk").isEmpty());
		assertTrue(r.of("bank_visit_close").isEmpty());
	}

	@Test
	public void grantOffOrMissingHeaderMeansOff() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.grant(null);	// a newer snapshot response without the header withdraws the grant
		r.open(bank(new int[]{995, 10}));
		r.close();
		assertTrue(r.of("bank_visit_open").isEmpty());
		r.grant("on");
		r.grant("off");
		r.open(bank(new int[]{995, 10}));
		r.close();
		assertTrue(r.of("bank_visit_open").isEmpty());
	}

	@Test
	public void grantForAnotherTokenDoesNotCount() throws Exception
	{
		Rig r = rig();
		r.p.applyServerPolicy(policy("X-Bank-Evidence", "on"), "ffffffffffffffffffffffffffffffff");
		r.open(bank(new int[]{995, 10}));
		r.close();
		assertTrue(r.of("bank_visit_open").isEmpty());
	}

	@Test
	public void grantIsReadOnceAtOpen() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		r.grant("off");	// withdrawn mid-visit: this visit still closes; the server marks it grant_revoked
		r.close();
		assertEquals(1, r.of("bank_visit_open").size());
		assertEquals(1, r.of("bank_visit_close").size());
		r.open(bank(new int[]{995, 10}));	// the next visit sees the grant off
		r.close();
		assertEquals(1, r.of("bank_visit_open").size());
	}

	// ---- a full visit ----

	@Test
	public void zeroMoveVisitSendsOpenManifestAndClose() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		ItemContainer b = bank(new int[]{995, 1000}, new int[]{4151, 1});
		r.open(b);
		r.close();
		Map<String, Object> open = r.of("bank_visit_open").get(0);
		Map<String, Object> close = r.of("bank_visit_close").get(0);
		assertEquals("loaded", open.get("bank_state"));
		assertEquals(2, open.get("item_stacks"));
		assertEquals(open.get("visit_id"), close.get("visit_id"));
		assertEquals(((String) open.get("visit_id")).length(), 32);
		assertEquals("closed", close.get("close_reason"));
		assertEquals(true, close.get("manifest_complete"));
		assertEquals(true, close.get("moves_complete"));
		assertEquals(1, r.chunks("open").size());
		assertEquals(1, r.chunks("close").size());
		assertEquals(open.get("manifest_sha256"), r.chunks("open").get(0).get("manifest_sha256"));
		assertEquals(close.get("end_manifest_sha256"), r.chunks("close").get(0).get("manifest_sha256"));
		assertEquals(1, close.get("end_chunks"));
		// zero moves: empty lists inline
		assertTrue(((List<?>) close.get("deposited")).isEmpty());
		assertTrue(((List<?>) close.get("withdrawn")).isEmpty());
		// still emits the legacy bank_session? No: it has never emitted on zero moves, and must not now.
		assertTrue(r.of("bank_session").isEmpty());
	}

	@Test
	public void canonicalListAndHashMatchTheContract() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		// unsorted, a duplicate id, a placeholder (qty 0) and an empty slot (-1)
		r.open(bank(new int[]{995, 5}, new int[]{4151, 1}, new int[]{995, 7}, new int[]{1931, 0}, new int[]{-1, 0}));
		r.close();
		List<long[]> got = items(r.chunks("open"));
		assertEquals(2, got.size());
		assertEquals(995L, got.get(0)[0]);
		assertEquals(12L, got.get(0)[1]);
		assertEquals(4151L, got.get(1)[0]);
		String json = "[[995,12],[4151,1]]";
		assertEquals(json, BankVisitManifest.json(got));
		assertEquals(EventSpool.sha256Hex(json), r.of("bank_visit_open").get(0).get("manifest_sha256"));
	}

	@Test
	public void grossMovesInTheCloseKeepBothDirections() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 1000}, new int[]{4151, 1}));
		r.change(bank(new int[]{995, 1000}));	// withdraw the whip
		r.change(bank(new int[]{995, 1000}, new int[]{4151, 1}));	// deposit it back
		r.close();
		Map<String, Object> close = r.of("bank_visit_close").get(0);
		assertEquals("[[4151,1]]", BankVisitManifest.json(asList(close.get("deposited"))));
		assertEquals("[[4151,1]]", BankVisitManifest.json(asList(close.get("withdrawn"))));
		// the legacy bank_session still goes out unchanged
		assertEquals(1, r.of("bank_session").size());
	}

	@SuppressWarnings("unchecked")
	private static List<long[]> asList(Object o)
	{
		return (List<long[]>) o;
	}

	// ---- empty vs unreadable ----

	@Test
	public void emptyReadAtOpenWaitsForTheRealBank() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank());	// not loaded yet
		// the open goes out at once as unloaded_unknown, with no item list
		assertEquals(1, r.of("bank_visit_open").size());
		Map<String, Object> open = r.of("bank_visit_open").get(0);
		assertEquals("unloaded_unknown", open.get("bank_state"));
		assertNull(open.get("manifest_sha256"));
		assertTrue("never an empty list for an unread bank", r.of("bank_visit_manifest_chunk").isEmpty());
		r.change(bank(new int[]{995, 3}));	// the contents arrive: phase:open chunks in the SAME visit
		assertEquals("no second open", 1, r.of("bank_visit_open").size());
		List<Map<String, Object>> cs = r.chunks("open");
		assertEquals(1, cs.size());
		assertEquals(open.get("visit_id"), cs.get(0).get("visit_id"));
		assertEquals("[[995,3]]", BankVisitManifest.json(items(cs)));
		r.change(bank(new int[]{995, 4}));	// later reads do not resend the open list
		assertEquals(1, r.chunks("open").size());
		r.close();
		assertEquals(true, r.of("bank_visit_close").get(0).get("manifest_complete"));
	}

	@Test
	public void bankThatNeverLoadsIsUnloadedUnknownNeverEmpty() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank());
		r.close();
		r.p.onGameTick(new GameTick());
		Map<String, Object> open = r.of("bank_visit_open").get(0);
		assertEquals("unloaded_unknown", open.get("bank_state"));
		assertNull(open.get("manifest_sha256"));
		assertTrue(r.of("bank_visit_manifest_chunk").isEmpty());
		Map<String, Object> close = r.of("bank_visit_close").get(0);
		assertEquals(false, close.get("manifest_complete"));
		assertEquals(false, close.get("moves_complete"));
		assertNull("an empty end read is not an end manifest", close.get("end_manifest_sha256"));
		for (Map<String, Object> e : r.p.pendingEvents)
		{
			assertFalse("verified_empty is never sent by this build", "verified_empty".equals(e.get("bank_state")));
		}
	}

	// ---- teardowns ----

	@Test
	public void hopDuringVisitClosesIncompleteWithNoEndManifest() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		r.state(GameState.HOPPING);
		Map<String, Object> close = r.of("bank_visit_close").get(0);
		assertEquals("hop", close.get("close_reason"));
		assertEquals(false, close.get("moves_complete"));
		assertNull(close.get("end_manifest_sha256"));
		assertTrue(r.chunks("close").isEmpty());
	}

	@Test
	public void logoutAndDisconnectReasons() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		r.state(GameState.LOGIN_SCREEN);
		assertEquals("logout", r.of("bank_visit_close").get(0).get("close_reason"));
		r.state(GameState.LOGGED_IN);
		r.open(bank(new int[]{995, 10}));
		r.state(GameState.CONNECTION_LOST);
		assertEquals("disconnect", r.of("bank_visit_close").get(1).get("close_reason"));
	}

	@Test
	public void teardownCloseFromWidgetUnloadUsesTheGameState() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		when(r.client.getGameState()).thenReturn(GameState.HOPPING);
		r.p.handleBankWidgetClosed(BANK_GROUP, true);
		assertEquals("hop", r.of("bank_visit_close").get(0).get("close_reason"));
		r.state(GameState.HOPPING);	// the game state change that follows must not close it twice
		assertEquals(1, r.of("bank_visit_close").size());
	}

	@Test
	public void unreadableWidgetCloseBeforeLogoutUsesTheLaterTeardownReason() throws Exception
	{
		for (ItemContainer cleared : new ItemContainer[]{null, bank()})
		{
			Rig r = rig();
			r.grant("on");
			r.open(bank(new int[]{995, 10}));
			r.change(bank(new int[]{995, 11}));
			when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(cleared);
			// The client unloads the widget before publishing LOGIN_SCREEN.
			r.p.onWidgetClosed(new WidgetClosed(BANK_GROUP, 0, true));
			assertTrue("an unreadable close must not prematurely claim a normal close",
				r.of("bank_visit_close").isEmpty());
			r.state(GameState.LOGIN_SCREEN);
			assertEquals(1, r.of("bank_visit_close").size());
			Map<String, Object> close = r.of("bank_visit_close").get(0);
			assertEquals("logout", close.get("close_reason"));
			assertEquals(true, close.get("manifest_complete")); // the OPEN manifest was read
			assertEquals(false, close.get("moves_complete"));
			assertNull(close.get("end_manifest_sha256"));
			assertTrue(r.chunks("close").isEmpty());
			assertEquals(false, r.of("bank_session").get(0).get("complete"));
		}
	}

	@Test
	public void unreadableNormalCloseSettlesOnNextTickWithoutBorrowingLaterInventory() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(null);
		r.p.onWidgetClosed(new WidgetClosed(BANK_GROUP, 0, true));
		assertTrue(r.of("bank_visit_close").isEmpty());
		ItemContainer laterBank = bank(new int[]{995, 999});
		when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(laterBank);
		r.p.handleBankContainerChanged(laterBank);
		r.p.onGameTick(new GameTick());
		assertEquals(1, r.of("bank_visit_close").size());
		Map<String, Object> close = r.of("bank_visit_close").get(0);
		assertEquals("closed", close.get("close_reason"));
		assertNull("a later cache is not the bank at the observed close", close.get("end_manifest_sha256"));
		assertTrue(r.chunks("close").isEmpty());
		assertTrue("post-close inventory must not fabricate a deposit", r.of("bank_session").isEmpty());
		r.p.onGameTick(new GameTick());
		assertEquals(1, r.of("bank_visit_close").size());
	}

	@Test
	public void reopeningBeforeNextTickDoesNotApplyThePendingCloseToTheNewVisit() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(null);
		r.p.onWidgetClosed(new WidgetClosed(BANK_GROUP, 0, true));
		r.open(bank(new int[]{995, 999}));
		assertEquals(1, r.of("bank_visit_close").size());
		assertEquals(r.of("bank_visit_open").get(0).get("visit_id"), r.of("bank_visit_close").get(0).get("visit_id"));
		assertNull(r.of("bank_visit_close").get(0).get("end_manifest_sha256"));
		assertTrue(r.of("bank_session").isEmpty());
		r.p.onGameTick(new GameTick());
		assertEquals(1, r.of("bank_visit_close").size());
		r.close();
		assertEquals(2, r.of("bank_visit_close").size());
		assertEquals(r.of("bank_visit_open").get(1).get("visit_id"), r.of("bank_visit_close").get(1).get("visit_id"));
		assertEquals(1, r.chunks("close").size());
	}

	@Test
	public void reopenWithoutCloseStartsANewVisit() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		r.open(bank(new int[]{995, 10}));
		r.close();
		List<Map<String, Object>> opens = r.of("bank_visit_open");
		assertEquals(2, opens.size());
		assertFalse(opens.get(0).get("visit_id").equals(opens.get(1).get("visit_id")));
		assertEquals(1, r.of("bank_visit_close").size());
		assertEquals(opens.get(1).get("visit_id"), r.of("bank_visit_close").get(0).get("visit_id"));
	}

	@Test
	public void pluginShutdownDropsTheVisitWithoutAClose() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(null);
		r.p.onWidgetClosed(new WidgetClosed(BANK_GROUP, 0, true));
		r.p.shutDown();
		r.p.onGameTick(new GameTick());
		r.p.handleBankWidgetClosed(BANK_GROUP);	// a close after re-enable must not finish the old visit
		assertTrue(r.of("bank_visit_close").isEmpty());
	}

	@Test
	public void tokenChangeDropsTheVisitAndTheGrant() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(null);
		r.p.onWidgetClosed(new WidgetClosed(BANK_GROUP, 0, true));
		net.runelite.client.events.ConfigChanged c = new net.runelite.client.events.ConfigChanged();
		c.setGroup(AccountConnectPlugin.CONFIG_GROUP);
		c.setKey("linkToken");
		c.setOldValue(TOKEN);
		c.setNewValue("ffffffffffffffffffffffffffffffff");
		r.p.onConfigChanged(c);
		r.p.onGameTick(new GameTick());
		r.close();
		assertTrue(r.of("bank_visit_close").isEmpty());
		assertFalse(r.p.bankEvidenceEnabled());
	}

	// ---- size limits ----

	@Test
	public void largeBankChunksStayUnderTheServerLimitAndReassemble() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		ItemContainer b = bigBank(1200);
		r.open(b);
		r.close();
		Gson gson = new Gson();
		for (String phase : new String[]{"open", "close"})
		{
			List<Map<String, Object>> cs = r.chunks(phase);
			assertTrue(cs.size() >= 8);
			for (int i = 0; i < cs.size(); i++)
			{
				Map<String, Object> c = cs.get(i);
				assertEquals(i, c.get("chunk"));
				assertEquals(cs.size(), c.get("chunks"));
				assertTrue(((List<?>) c.get("items")).size() <= BankVisitManifest.MAX_STACKS);
				Map<String, Object> fields = new LinkedHashMap<>(c);
				for (String k : new String[]{"type", "ts", "__delivery_token_fp", "account_hash", "rsn"})
				{
					fields.remove(k);
				}
				int len = gson.toJson(fields).length();
				assertTrue("chunk " + i + " is " + len + " chars", len <= BankVisitManifest.FIELDS_BUDGET);
				assertTrue("server hard limit", len < 8192);
			}
			List<long[]> all = items(cs);
			assertEquals(1200, all.size());
			assertEquals(cs.get(0).get("manifest_sha256"), BankVisitManifest.sha256(all));
		}
	}

	@Test
	public void largeMoveListsGoToChunksNotTheClose() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{30_000, 2_000_000_000}));	// the first stack of bigBank(600), unchanged
		r.change(bigBank(600));	// 599 new stacks deposited, gross; nothing withdrawn
		r.close();
		Map<String, Object> close = r.of("bank_visit_close").get(0);
		assertNull(close.get("deposited"));
		assertTrue((Integer) close.get("moves_chunks") >= 4);
		assertEquals(close.get("moves_chunks"), close.get("deposited_chunks"));
		// an empty chunked move list names sha256("[]") and a count of 0
		assertEquals(0, close.get("withdrawn_chunks"));
		assertEquals("4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", close.get("withdrawn_sha256"));
		List<long[]> dep = items(r.chunks("deposited"));
		assertEquals(599, dep.size());
		assertEquals(close.get("deposited_sha256"), BankVisitManifest.sha256(dep));
		Map<String, Object> fields = new LinkedHashMap<>(close);
		for (String k : new String[]{"type", "ts", "__delivery_token_fp", "account_hash", "rsn"})
		{
			fields.remove(k);
		}
		assertTrue(new Gson().toJson(fields).length() <= BankVisitManifest.FIELDS_BUDGET);
	}

	/** The size budget, not only the 150-stack cap, splits chunks: a big fixed overhead leaves room for few stacks. */
	@Test
	public void chunkSplitsOnTheSizeBudgetToo()
	{
		List<long[]> list = new ArrayList<>();
		for (int i = 0; i < 100; i++)
		{
			list.add(new long[]{1_000_000_000L + i, 9_000_000_000L});	// 25 chars each
		}
		int overhead = 7680 - 10 * 26;	// room for exactly 10 stacks with separators
		List<List<long[]>> cs = BankVisitManifest.chunk(list, overhead);
		assertEquals(10, cs.size());
		for (List<long[]> c : cs)
		{
			assertEquals(10, c.size());
			assertTrue(overhead + BankVisitManifest.json(c).length() - 2 <= 7680);
		}
	}

	// ---- durability ----

	@Test
	public void bankVisitTypesAreSpooled() throws Exception
	{
		assertTrue(AccountConnectPlugin.SPOOL_ALLOWLIST.contains("bank_visit_open"));
		assertTrue(AccountConnectPlugin.SPOOL_ALLOWLIST.contains("bank_visit_manifest_chunk"));
		assertTrue(AccountConnectPlugin.SPOOL_ALLOWLIST.contains("bank_visit_close"));
		assertTrue(AccountConnectPlugin.SPOOL_ALLOWLIST.contains("bank_session"));
	}

	@Test
	public void everyBankVisitEventHasItsOwnEventId() throws Exception
	{
		Rig r = rig();
		r.grant("on");
		r.open(bank(new int[]{995, 10}));
		r.close();
		java.util.Set<Object> ids = new java.util.HashSet<>();
		int n = 0;
		for (Map<String, Object> e : r.p.pendingEvents)
		{
			if (((String) e.get("type")).startsWith("bank_visit_"))
			{
				n++;
				assertEquals(32, ((String) e.get("event_id")).length());
				ids.add(e.get("event_id"));
			}
		}
		assertEquals(4, n);	// open, open chunk, close chunk, close
		assertEquals(n, ids.size());
	}
}
