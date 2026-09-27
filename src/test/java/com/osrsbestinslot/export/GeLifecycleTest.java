package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GE offer lifecycle (owner defaults PIO-014 item 6, GE/product): placed, partly filled, finished,
 * cancelled, collected. One row per real step, the login replay is a baseline, and a big offer that
 * fills in hundreds of steps stays bounded.
 */
public class GeLifecycleTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private AccountConnectPlugin p;
	private Client client;
	private int tick = 1000;
	private GameState gameState = GameState.LOGGED_IN;

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
		when(client.getGameState()).thenAnswer(i -> gameState);
		inject("client", client);
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, value);
	}

	private void offer(int slot, GrandExchangeOfferState s, int item, int sold, int total, int price, int spent)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getState()).thenReturn(s);
		when(o.getItemId()).thenReturn(item);
		when(o.getQuantitySold()).thenReturn(sold);
		when(o.getTotalQuantity()).thenReturn(total);
		when(o.getPrice()).thenReturn(price);
		when(o.getSpent()).thenReturn(spent);
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

	private List<String> types()
	{
		List<String> out = new ArrayList<>();
		for (Map<String, Object> e : ge())
		{
			out.add((String) e.get("type"));
		}
		return out;
	}

	@Test
	public void aFullBuyEmitsPlacedPartialBoughtCollected() throws Exception
	{
		setUp();
		offer(3, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick++;
		offer(3, GrandExchangeOfferState.BUYING, 560, 0, 1000, 250, 0);
		tick += 5;
		offer(3, GrandExchangeOfferState.BUYING, 560, 400, 1000, 250, 100_000);	// within a minute of the placement
		tick += 200;
		offer(3, GrandExchangeOfferState.BUYING, 560, 600, 1000, 250, 150_000);
		tick += 3;
		offer(3, GrandExchangeOfferState.BOUGHT, 560, 1000, 1000, 250, 250_000);
		tick += 50;
		offer(3, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);

		assertEquals(java.util.Arrays.asList("ge_offer", "ge_progress", "ge_buy", "ge_collect"), types());
		Map<String, Object> placed = ge().get(0);
		assertEquals(560, placed.get("item"));
		assertEquals(0, placed.get("qty"));
		assertEquals(1000, placed.get("qty_total"));
		assertEquals(250, placed.get("price"));
		assertEquals("BUYING", placed.get("state"));
		assertEquals("buy", placed.get("side"));
		assertEquals(3, placed.get("slot"));
		Map<String, Object> part = ge().get(1);
		assertEquals("the rate-limited step is folded into the next row", 600, part.get("qty"));
		assertEquals(150_000, part.get("gp"));
		Map<String, Object> bought = ge().get(2);
		assertEquals(1000, bought.get("qty"));
		assertEquals(250_000, bought.get("gp"));
		Map<String, Object> collected = ge().get(3);
		assertEquals("a collect names what the slot held", 560, collected.get("item"));
		assertEquals(1000, collected.get("qty"));
		assertEquals("BOUGHT", collected.get("state"));
		assertEquals("buy", collected.get("side"));
	}

	@Test
	public void aCancelledSellEmitsCancelWithSideAndCollect() throws Exception
	{
		setUp();
		offer(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(0, GrandExchangeOfferState.SELLING, 4151, 0, 2, 1_500_000, 0);
		offer(0, GrandExchangeOfferState.CANCELLED_SELL, 4151, 0, 2, 1_500_000, 0);
		offer(0, GrandExchangeOfferState.CANCELLED_SELL, 4151, 0, 2, 1_500_000, 0);	// repeat: no second row
		offer(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		assertEquals(java.util.Arrays.asList("ge_offer", "ge_cancel", "ge_collect"), types());
		assertEquals("sell", ge().get(1).get("side"));
		assertEquals("CANCELLED_SELL", ge().get(2).get("state"));
	}

	@Test
	public void theLoginReplayIsABaselineNotActivity() throws Exception
	{
		setUp();
		GameStateChanged gs = new GameStateChanged();
		gs.setGameState(GameState.LOGGED_IN);
		p.onGameStateChanged(gs);
		// the client replays every slot in the first ticks after login
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);
		offer(2, GrandExchangeOfferState.SELLING, 4151, 0, 1, 1_500_000, 0);
		offer(4, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick += 2;
		offer(5, GrandExchangeOfferState.BUYING, 1511, 3, 100, 5, 15);
		assertTrue("an old finished offer is not reported again at login: " + types(), types().isEmpty());

		// real activity after the replay window
		tick += 10;
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(2, GrandExchangeOfferState.SOLD, 4151, 1, 1, 1_500_000, 1_500_000);
		assertEquals(java.util.Arrays.asList("ge_collect", "ge_sell"), types());
		assertEquals(560, ge().get(0).get("item"));
	}

	private void gameState(GameState s)
	{
		gameState = s;
		GameStateChanged gs = new GameStateChanged();
		gs.setGameState(s);
		p.onGameStateChanged(gs);
	}

	@Test
	public void anOfferThatFinishedDuringAHopIsStillReported() throws Exception
	{
		setUp();
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(1, GrandExchangeOfferState.BUYING, 560, 0, 10, 250, 0);
		gameState(GameState.HOPPING);
		tick += 5;
		gameState(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// in the replay window
		assertEquals(java.util.Arrays.asList("ge_offer", "ge_buy"), types());
	}

	@Test
	public void aSceneLoadIsNotALoginReplay() throws Exception
	{
		setUp();
		gameState(GameState.LOGGED_IN);
		tick += 50;
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(2, GrandExchangeOfferState.SELLING, 4151, 0, 5, 1_500_000, 0);
		tick += 150;
		gameState(GameState.LOADING);
		gameState(GameState.LOGGED_IN);		// region change, not a login
		offer(2, GrandExchangeOfferState.SELLING, 4151, 2, 5, 1_500_000, 3_000_000);	// a fill right after it
		assertEquals(java.util.Arrays.asList("ge_offer", "ge_progress"), types());
	}

	@Test
	public void anotherAccountsSlotNeverMakesAReplayLookFinished() throws Exception
	{
		setUp();
		when(client.getAccountHash()).thenReturn(111L);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(1, GrandExchangeOfferState.BUYING, 560, 0, 10, 250, 0);
		gameState(GameState.LOGIN_SCREEN);
		when(client.getAccountHash()).thenReturn(222L);
		tick += 100;
		gameState(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// the other account's own old offer
		assertEquals(java.util.Arrays.asList("ge_offer"), types());
	}

	/**
	 * Review HIGH-1: the client clears every slot to EMPTY while hopping / logging in / at the login
	 * screen. That clear is not a collect, and it must not erase the slot so the replay still sees
	 * that the offer finished while away.
	 */
	@Test
	public void theHopClearIsNotACollectAndAFinishWhileAwayIsKept() throws Exception
	{
		setUp();
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(1, GrandExchangeOfferState.BUYING, 560, 0, 10, 250, 0);
		gameState(GameState.HOPPING);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);		// the client's own clear
		tick += 5;
		gameState(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);
		assertEquals(java.util.Arrays.asList("ge_offer", "ge_buy"), types());
	}

	@Test
	public void theLogoutClearIsNotACollect() throws Exception
	{
		setUp();
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(2, GrandExchangeOfferState.SELLING, 4151, 0, 1, 1_500_000, 0);
		gameState(GameState.LOGIN_SCREEN);
		for (int s = 0; s < 8; s++)
		{
			offer(s, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		}
		assertEquals(java.util.Arrays.asList("ge_offer"), types());
	}

	/** Review LOW-2: a finished slot that turns active again (its collect unseen) is a new offer. */
	@Test
	public void aFinishedSlotReusedIsANewOffer() throws Exception
	{
		setUp();
		offer(4, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);
		tick += 50;
		offer(4, GrandExchangeOfferState.SELLING, 4151, 0, 1, 1_500_000, 0);
		assertEquals(java.util.Arrays.asList("ge_buy", "ge_offer"), types());
	}

	/** Review MED-2: started while already logged in, a region load is not a login replay. */
	@Test
	public void startedMidSessionTheFirstRegionLoadIsNotAReplay() throws Exception
	{
		setUp();
		p.startUp();
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(2, GrandExchangeOfferState.SELLING, 4151, 0, 5, 1_500_000, 0);
		tick += 150;
		gameState(GameState.LOADING);
		gameState(GameState.LOGGED_IN);
		offer(2, GrandExchangeOfferState.SELLING, 4151, 2, 5, 1_500_000, 3_000_000);
		assertEquals(java.util.Arrays.asList("ge_offer", "ge_progress"), types());
	}

	@Test
	public void anOfferFirstSeenPartFilledIsProgressNotANewOffer() throws Exception
	{
		setUp();
		offer(6, GrandExchangeOfferState.BUYING, 1511, 40, 100, 5, 200);
		assertEquals(java.util.Arrays.asList("ge_progress"), types());
	}

	@Test
	public void aTerminalRepeatStillEmitsOnce() throws Exception
	{
		setUp();
		offer(2, GrandExchangeOfferState.BOUGHT, 20997, 3, 3, 100, 300);
		offer(2, GrandExchangeOfferState.BOUGHT, 20997, 3, 3, 100, 300);
		assertEquals(java.util.Arrays.asList("ge_buy"), types());
	}

	/**
	 * Volume: eight slots, each a 10 000-unit offer filling one unit per tick for an hour. Rows are bounded
	 * by the one-per-minute-per-slot rule: at most 8 x 60 progress rows plus the placements, far under the
	 * server's 6000 rows per token per hour.
	 */
	@Test
	public void eightSlotsFillingEveryTickForAnHourStayBounded() throws Exception
	{
		setUp();
		for (int s = 0; s < 8; s++)
		{
			offer(s, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
			offer(s, GrandExchangeOfferState.BUYING, 560, 0, 10_000, 250, 0);
		}
		for (int t = 1; t <= 6000; t++)
		{
			tick++;
			for (int s = 0; s < 8; s++)
			{
				offer(s, GrandExchangeOfferState.BUYING, 560, t, 10_000, 250, t * 250);
			}
		}
		int rows = ge().size();
		assertTrue("rows/hour " + rows, rows <= 8 + 8 * 61);
		assertTrue("fills are still reported: " + rows, rows >= 8 + 8 * 59);
	}
}
