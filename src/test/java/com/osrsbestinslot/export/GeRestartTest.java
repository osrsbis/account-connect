package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * GE offers across a client restart. The tracker lived in memory, so a restart forgot every open offer: the
 * login replay was only a baseline, a fill or finish while away was never reported, and the collect came under
 * a new offer_key. Each arm closes one plugin instance and starts a new one on the same data folder, then
 * drives the real login sequence.
 */
public class GeRestartTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final String OTHER_TOKEN = "fedcba9876543210fedcba9876543210";
	private static final long A = 111L;
	private static final long B = 222L;
	private static final int CHISEL = 1755;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private Path data;
	private AccountConnectPlugin p;
	private Client client;
	private int tick = 1000;
	private GameState gameState = GameState.LOGIN_SCREEN;
	private long hash = A;
	private String token = TOKEN;
	private EnumSet<WorldType> world = EnumSet.noneOf(WorldType.class);
	private final List<Map<String, Object>> all = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		data = tmp.newFolder("plugin-data").toPath();
	}

	/** A new client: a fresh plugin instance on the same data folder, at the login screen. */
	private void startClient() throws Exception
	{
		if (p != null)
		{
			all.addAll(ge());
		}
		tick = 50;	// the tick count restarts with the client
		gameState = GameState.LOGIN_SCREEN;
		hash = -1L;
		p = new AccountConnectPlugin();
		inject("gson", new Gson());
		final String t = token;
		inject("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return t;
			}
		});
		client = mock(Client.class);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getGameState()).thenAnswer(i -> gameState);
		when(client.getAccountHash()).thenAnswer(i -> hash);
		when(client.getWorldType()).thenAnswer(i -> world);
		when(client.getGrandExchangeOffers()).thenAnswer(i -> new GrandExchangeOffer[8]);
		inject("client", client);
		p.setSpoolDataRootForTest(data);
		p.startUp();
		p.pendingEvents.clear();	// rows the last close wrote to pending-events.json, already counted above
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, value);
	}

	private void offer(int slot, GrandExchangeOfferState s, int sold, int total, int price, int spent)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getState()).thenReturn(s);
		when(o.getItemId()).thenReturn(s == GrandExchangeOfferState.EMPTY ? 0 : CHISEL);
		when(o.getQuantitySold()).thenReturn(sold);
		when(o.getTotalQuantity()).thenReturn(total);
		when(o.getPrice()).thenReturn((long) price);
		when(o.getSpent()).thenReturn((long) spent);
		GrandExchangeOfferChanged ev = mock(GrandExchangeOfferChanged.class);
		when(ev.getOffer()).thenReturn(o);
		when(ev.getSlot()).thenReturn(slot);
		p.onGrandExchangeOfferChanged(ev);
	}

	private void state(GameState s)
	{
		gameState = s;
		GameStateChanged gs = new GameStateChanged();
		gs.setGameState(s);
		p.onGameStateChanged(gs);
	}

	/** Log in as {@code account}; the client replays this slot state while LOGGING_IN. */
	private void login(long account, Runnable replay)
	{
		state(GameState.LOGGING_IN);
		hash = account;
		replay.run();
		tick += 3;
		state(GameState.LOGGED_IN);
		tick += 5;
	}

	private void logout()
	{
		state(GameState.LOGIN_SCREEN);
		hash = -1L;
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

	private List<Map<String, Object>> since(int from)
	{
		List<Map<String, Object>> g = ge();
		return g.subList(from, g.size());
	}

	private static Map<?, ?> obs(Map<String, Object> row)
	{
		return (Map<?, ?>) row.get("ge_observation");
	}

	/** Place a 60 chisel buy in slot 0 and see 40 fill, then close the client. Returns the offer_key. */
	private Object placeAndFill40ThenRestart() throws Exception
	{
		startClient();
		login(A, () -> { });
		offer(0, GrandExchangeOfferState.BUYING, 0, 60, 110, 0);
		offer(0, GrandExchangeOfferState.BUYING, 40, 60, 110, 3760);
		tick += AccountConnectPlugin.GE_PROGRESS_MIN_TICKS;
		offer(0, GrandExchangeOfferState.BUYING, 40, 60, 110, 3760);
		Object key = ge().get(0).get("offer_key");
		assertEquals("ge_offer", ge().get(0).get("type"));
		p.shutDown();
		startClient();
		return key;
	}

	@Test
	public void anOfferFinishedWhileAwayIsReportedOnceUnderTheSameKey() throws Exception
	{
		Object key = placeAndFill40ThenRestart();
		login(A, () -> offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640));

		List<Map<String, Object>> rows = ge();
		assertEquals("one terminal row for the offline finish", 1, rows.size());
		Map<String, Object> buy = rows.get(0);
		assertEquals("ge_buy", buy.get("type"));
		assertEquals("the offer keeps its key across the restart", key, buy.get("offer_key"));
		assertEquals("account_profile", obs(buy).get("offer_key_scope"));
		assertEquals("inferred_across_gap", obs(buy).get("identity"));
		assertEquals(40L, obs(buy).get("delta_from_qty"));
		assertEquals(20L, obs(buy).get("delta_qty"));
		assertEquals(1880L, obs(buy).get("delta_gp"));
		assertEquals("unknown", obs(buy).get("fill_time"));
		assertTrue("the interval starts before the restart",
			((Number) obs(buy).get("interval_start_ms")).longValue() < ((Number) obs(buy).get("interval_end_ms")).longValue());

		offer(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0);
		Map<String, Object> collect = ge().get(1);
		assertEquals("ge_collect", collect.get("type"));
		assertEquals(key, collect.get("offer_key"));
		assertEquals(0L, obs(collect).get("delta_qty"));
	}

	@Test
	public void aPartialFillWhileAwayIsOneProgressRow() throws Exception
	{
		Object key = placeAndFill40ThenRestart();
		login(A, () -> offer(0, GrandExchangeOfferState.BUYING, 50, 60, 110, 4700));
		List<Map<String, Object>> rows = ge();
		assertEquals(1, rows.size());
		assertEquals("ge_progress", rows.get(0).get("type"));
		assertEquals(key, rows.get(0).get("offer_key"));
		assertEquals(10L, obs(rows.get(0)).get("delta_qty"));
	}

	@Test
	public void noChangeWhileAwaySendsNothingAndTheCancelKeepsTheKey() throws Exception
	{
		Object key = placeAndFill40ThenRestart();
		login(A, () -> offer(0, GrandExchangeOfferState.BUYING, 40, 60, 110, 3760));
		assertEquals("an unchanged offer is not a row", 0, ge().size());
		offer(0, GrandExchangeOfferState.CANCELLED_BUY, 40, 60, 110, 3760);
		Map<String, Object> cancel = ge().get(0);
		assertEquals("ge_cancel", cancel.get("type"));
		assertEquals(key, cancel.get("offer_key"));
		assertEquals("no fill is invented at the cancel", 0L, obs(cancel).get("delta_qty"));
	}

	@Test
	public void aCollectDoneElsewhereIsNeverCounted() throws Exception
	{
		placeAndFill40ThenRestart();
		// The slot was collected on another client and is empty at this login: the client sends no EMPTY
		// before LOGGED_IN, and a new offer in that slot later is a different offer.
		login(A, () -> { });
		assertEquals(0, ge().size());
		offer(0, GrandExchangeOfferState.BUYING, 0, 5, 200, 0);
		Map<String, Object> placed = ge().get(0);
		assertEquals("ge_offer", placed.get("type"));
	}

	@Test
	public void slotReuseByADifferentOfferGetsANewKey() throws Exception
	{
		Object key = placeAndFill40ThenRestart();
		// Collected and relisted elsewhere: same slot, other terms. First sight of it is only a baseline.
		login(A, () -> offer(0, GrandExchangeOfferState.SELLING, 0, 3, 900, 0));
		assertEquals(0, ge().size());
		offer(0, GrandExchangeOfferState.SOLD, 3, 3, 900, 2700);
		List<Map<String, Object>> rows = ge();
		assertEquals(1, rows.size());
		assertNotEquals("another offer never inherits the old key", key, rows.get(0).get("offer_key"));
		assertEquals("collector_session", obs(rows.get(0)).get("offer_key_scope"));
	}

	@Test
	public void anotherAccountNeverReadsTheSavedSlots() throws Exception
	{
		placeAndFill40ThenRestart();
		login(B, () -> offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640));
		assertEquals("account B's replay is only a baseline", 0, ge().size());
		logout();
		login(A, () -> offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640));
		assertEquals("account A still reconciles its own slot", "ge_buy", ge().get(0).get("type"));
	}

	@Test
	public void anotherTokenNeverReadsTheSavedSlots() throws Exception
	{
		startClient();
		login(A, () -> { });
		offer(0, GrandExchangeOfferState.BUYING, 0, 60, 110, 0);
		p.shutDown();
		token = OTHER_TOKEN;
		startClient();
		login(A, () -> offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640));
		assertEquals(0, ge().size());
	}

	@Test
	public void anotherProfileNeverReadsTheSavedSlots() throws Exception
	{
		placeAndFill40ThenRestart();
		world = EnumSet.of(WorldType.SEASONAL);
		login(A, () -> offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640));
		assertEquals(0, ge().size());
	}

	@Test
	public void theFileHoldsNoTokenAndSurvivesARestartWithoutLogin() throws Exception
	{
		Object key = placeAndFill40ThenRestart();
		Path file = data.resolve("osrsbis").resolve(AccountConnectPlugin.GE_SLOTS_FILE);
		assertTrue(Files.exists(file));
		assertTrue("the token itself is never written", !new String(Files.readAllBytes(file)).contains(TOKEN));
		p.shutDown();
		startClient();										// a second restart before any login
		login(A, () -> offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640));
		assertEquals(key, ge().get(0).get("offer_key"));
	}

	// ---- An identical offer relisted while away (953, 2026-10-22) ----
	//
	// The game gives an offer no id. If the saved offer finished and was collected on another client, and an
	// offer with the same item, side, price and quantity was placed in the same slot, the login replay looks
	// exactly like the saved offer. No counter or state can tell the two apart. These arms pin what is sent.

	/** Limit: a relist whose counter is at or above the saved one reads as the same offer, labelled as a gap. */
	@Test
	public void anIdenticalRelistAboveTheSavedCounterReadsAsTheSameOfferAcrossAGap() throws Exception
	{
		Object key = placeAndFill40ThenRestart();
		login(A, () -> offer(0, GrandExchangeOfferState.BUYING, 50, 60, 110, 4700));
		Map<String, Object> row = ge().get(0);
		assertEquals(key, row.get("offer_key"));
		assertEquals("never shown as an observed same-session fill", "inferred_across_gap", obs(row).get("identity"));
		assertEquals("unknown", obs(row).get("fill_time"));
	}

	/** A relist below the saved counter cannot continue the saved offer: nothing is credited from it. */
	@Test
	public void anIdenticalRelistBelowTheSavedCounterIsNeverCredited() throws Exception
	{
		placeAndFill40ThenRestart();
		login(A, () -> offer(0, GrandExchangeOfferState.BUYING, 10, 60, 110, 940));
		assertEquals(0, ge().size());
		offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640);
		Map<String, Object> end = ge().get(0);
		assertEquals("ambiguous_counter_regression", obs(end).get("identity"));
		assertEquals(null, obs(end).get("delta_qty"));
		assertEquals(null, obs(end).get("delta_gp"));
	}

	/** A saved offer that had finished, seen active again with the same terms, is a new offer of unknown start. */
	@Test
	public void aFinishedSavedOfferSeenActiveAgainIsNotItsContinuation() throws Exception
	{
		startClient();
		login(A, () -> { });
		offer(0, GrandExchangeOfferState.BUYING, 0, 60, 110, 0);
		offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640);
		Object key = ge().get(1).get("offer_key");
		p.shutDown();
		startClient();
		login(A, () -> offer(0, GrandExchangeOfferState.BUYING, 20, 60, 110, 1880));
		assertEquals(0, ge().size());
		offer(0, GrandExchangeOfferState.BOUGHT, 60, 60, 110, 5640);
		Map<String, Object> end = ge().get(0);
		assertNotEquals(key, end.get("offer_key"));
		assertEquals(null, obs(end).get("delta_qty"));
	}

	/**
	 * Live run 2026-10-22 (R5 on D): a buy reached 119/150 inside the one-minute progress hold, the client
	 * closed 3.6 s later and the offer finished while away. Without the saved slots the login replay was a
	 * baseline at 150/150 and none of the 150 bars was credited. The held counter is saved too, so the whole
	 * fill since the last row sent is reported once, across the gap.
	 */
	@Test
	public void aFillHeldBackBeforeTheCloseIsCreditedAfterTheRestart() throws Exception
	{
		startClient();
		login(A, () -> { });
		offer(0, GrandExchangeOfferState.BUYING, 0, 150, 66, 0);
		tick += 5;
		offer(0, GrandExchangeOfferState.BUYING, 119, 150, 66, 7854);	// held: inside GE_PROGRESS_MIN_TICKS
		assertEquals(1, ge().size());
		Object key = ge().get(0).get("offer_key");
		p.shutDown();
		startClient();
		login(A, () -> offer(0, GrandExchangeOfferState.BOUGHT, 150, 150, 66, 9900));

		Map<String, Object> buy = ge().get(0);
		assertEquals("ge_buy", buy.get("type"));
		assertEquals(key, buy.get("offer_key"));
		assertEquals("from the last row sent, not the held counter", 0L, obs(buy).get("delta_from_qty"));
		assertEquals(150L, obs(buy).get("delta_qty"));
		assertEquals(9900L, obs(buy).get("delta_gp"));
	}
}
