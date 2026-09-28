package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
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
 * GE login / replay window, aligned with the RuneLite GE plugin: the client replays every slot from
 * LOGGING_IN / HOPPING until shortly after LOGGED_IN, the account hash is -1 before login, and a plugin
 * enabled while in the world gets no replay at all. Each arm drives the real game state sequence.
 */
public class GeLoginWindowTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final long A = 111L;
	private static final long B = 222L;

	private AccountConnectPlugin p;
	private Client client;
	private int tick = 1000;
	private GameState gameState = GameState.LOGGED_IN;
	private long hash = A;
	private GrandExchangeOffer[] slots = new GrandExchangeOffer[8];

	private void setUp(GameState start) throws Exception
	{
		gameState = start;
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
		when(client.getAccountHash()).thenAnswer(i -> hash);
		when(client.getGrandExchangeOffers()).thenAnswer(i -> slots);
		inject("client", client);
		p.startUp();
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, value);
	}

	private static GrandExchangeOffer o(GrandExchangeOfferState s, int item, int sold, int total, int price, int spent)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getState()).thenReturn(s);
		when(o.getItemId()).thenReturn(item);
		when(o.getQuantitySold()).thenReturn(sold);
		when(o.getTotalQuantity()).thenReturn(total);
		when(o.getPrice()).thenReturn((long) price);
		when(o.getSpent()).thenReturn((long) spent);
		return o;
	}

	private void offer(int slot, GrandExchangeOfferState s, int item, int sold, int total, int price, int spent)
	{
		GrandExchangeOffer offer = o(s, item, sold, total, price, spent);
		GrandExchangeOfferChanged ev = mock(GrandExchangeOfferChanged.class);
		when(ev.getOffer()).thenReturn(offer);
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

	// ---- (a) fresh client first login ----

	/**
	 * A fresh client knows no slot. The replay arrives while LOGGING_IN / LOADING, before LOGGED_IN. It is a
	 * baseline, so an old finished offer is never reported as a new buy.
	 */
	@Test
	public void aFreshClientLoginReplayBeforeLoggedInEmitsNothing() throws Exception
	{
		for (boolean knownHash : new boolean[]{false, true})
		{
			hash = -1L;
			setUp(GameState.LOGIN_SCREEN);
			state(GameState.LOGIN_SCREEN);
			state(GameState.LOGGING_IN);
			hash = knownHash ? A : -1L;
			offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);
			offer(2, GrandExchangeOfferState.SELLING, 4151, 0, 1, 1_500_000, 0);
			tick += 3;
			state(GameState.LOADING);
			offer(3, GrandExchangeOfferState.BUYING, 1511, 3, 100, 5, 15);
			tick += 2;
			hash = A;
			state(GameState.LOGGED_IN);
			tick += 1;
			offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// the in-world replay
			assertTrue("hash known=" + knownHash + ": the login replay is a baseline: " + types(), types().isEmpty());

			tick += 20;
			offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);	// real activity afterwards still counts
			assertEquals("hash known=" + knownHash, Arrays.asList("ge_collect"), types());
			assertEquals(560, ge().get(0).get("item"));
		}
	}

	// ---- (b) identical replay after a hop ----

	@Test
	public void anIdenticalReplayAfterAHopEmitsNothing() throws Exception
	{
		setUp(GameState.LOGGED_IN);
		offer(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(0, GrandExchangeOfferState.BUYING, 560, 4, 10, 250, 1000);
		state(GameState.HOPPING);
		offer(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);		// the client's own clear
		state(GameState.LOADING);
		tick += 4;
		state(GameState.LOGGED_IN);
		tick += 3;							// outside the 2-tick burst
		offer(0, GrandExchangeOfferState.BUYING, 560, 4, 10, 250, 1000);
		assertEquals(Arrays.asList("ge_offer"), types());
	}

	// ---- (c) finished while away, replayed before LOGGED_IN ----

	@Test
	public void anOfferFinishedDuringAHopAndReplayedWhileLoggingInIsOneBuy() throws Exception
	{
		setUp(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(1, GrandExchangeOfferState.BUYING, 560, 0, 10, 250, 0);
		state(GameState.HOPPING);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		state(GameState.LOGGING_IN);
		tick += 2;
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// replay before LOGGED_IN
		state(GameState.LOADING);
		tick += 3;
		state(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// replayed again in the burst
		tick += 10;
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// and re-sent later
		assertEquals(Arrays.asList("ge_offer", "ge_buy"), types());
		assertEquals(10, ge().get(1).get("qty"));
	}

	// ---- (d) hash -1 in the middle ----

	@Test
	public void anEventWithNoAccountHashDoesNotClearTheSlots() throws Exception
	{
		setUp(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(1, GrandExchangeOfferState.BUYING, 560, 0, 10, 250, 0);
		state(GameState.LOGIN_SCREEN);
		hash = -1L;
		state(GameState.LOGGING_IN);
		offer(1, GrandExchangeOfferState.BUYING, 560, 0, 10, 250, 0);		// replay, account not known yet
		offer(2, GrandExchangeOfferState.SOLD, 4151, 1, 1, 1_500_000, 1_500_000);
		hash = A;
		tick += 5;
		state(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// finished while away
		assertEquals(Arrays.asList("ge_offer", "ge_buy"), types());
	}

	/**
	 * A -1 event must not write slot state either. Here it would record a BOUGHT for slot 1 under the old
	 * account's key, so the real BOUGHT replay that follows would look like a repeat and the finish would
	 * be lost.
	 */
	@Test
	public void anEventWithNoAccountHashDoesNotOverwriteASlot() throws Exception
	{
		setUp(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(1, GrandExchangeOfferState.BUYING, 560, 0, 10, 250, 0);
		state(GameState.HOPPING);
		hash = -1L;
		state(GameState.LOGGING_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// account not known: skipped
		hash = A;
		tick += 5;
		state(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// the same finish, account known
		assertEquals(Arrays.asList("ge_offer", "ge_buy"), types());
	}

	// ---- (e) enabled while logged in ----

	@Test
	public void enabledWhileLoggedInSeedsTheSlotsAndEmitsNothingForTheSeed() throws Exception
	{
		slots[0] = o(GrandExchangeOfferState.BUYING, 560, 0, 1000, 250, 0);
		slots[1] = o(GrandExchangeOfferState.SELLING, 4151, 1, 5, 1_500_000, 1_500_000);
		slots[2] = o(GrandExchangeOfferState.BOUGHT, 1511, 100, 100, 5, 500);
		for (int s = 3; s < 8; s++)
		{
			slots[s] = o(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		}
		setUp(GameState.LOGGED_IN);
		assertTrue("the seed itself is not activity: " + types(), types().isEmpty());

		tick += 5;
		offer(0, GrandExchangeOfferState.BUYING, 560, 0, 1000, 250, 0);	// re-sent unchanged: not a new offer
		assertTrue("an unchanged slot is not a placement: " + types(), types().isEmpty());

		tick += 5;
		offer(0, GrandExchangeOfferState.BUYING, 560, 300, 1000, 250, 75_000);
		assertEquals(Arrays.asList("ge_progress"), types());
		assertEquals(300, ge().get(0).get("qty"));

		tick += 5;
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);		// collect the offer that was done at enable
		assertEquals(Arrays.asList("ge_progress", "ge_collect"), types());
		Map<String, Object> c = ge().get(1);
		assertEquals("the collect names what the seeded slot held", 1511, c.get("item"));
		assertEquals("BOUGHT", c.get("state"));
		assertEquals(100, c.get("qty"));
	}

	/** Enabled at the login screen: no seed, the replay window handles the baseline. */
	@Test
	public void enabledOutsideTheWorldDoesNotSeed() throws Exception
	{
		slots[2] = o(GrandExchangeOfferState.BOUGHT, 1511, 100, 100, 5, 500);
		hash = -1L;
		setUp(GameState.LOGIN_SCREEN);
		hash = A;
		tick += 5;
		state(GameState.LOGGED_IN);
		tick += 10;
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		assertTrue("nothing seeded, nothing replayed, so no collect can be named: " + types(), types().isEmpty());
	}

	// ---- (f) a real account switch ----

	@Test
	public void aRealAccountSwitchClearsTheOldSlotsAndTheNewReplayEmitsNothing() throws Exception
	{
		setUp(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		offer(1, GrandExchangeOfferState.BUYING, 560, 0, 10, 250, 0);
		state(GameState.LOGIN_SCREEN);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		hash = -1L;
		state(GameState.LOGGING_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// B's own old offer, hash not known
		hash = B;
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);	// B's own old offer, hash known
		tick += 5;
		state(GameState.LOGGED_IN);
		offer(1, GrandExchangeOfferState.BOUGHT, 560, 10, 10, 250, 2500);
		assertEquals("A's open offer never makes B's replay look finished", Arrays.asList("ge_offer"), types());

		tick += 20;
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		assertEquals(Arrays.asList("ge_offer", "ge_collect"), types());
		assertEquals(560, ge().get(1).get("item"));
	}

	/** Another account's replay of the same item in the same slot is never our sale. */
	@Test
	public void anotherAccountsReplayOfTheSameItemIsNotOurSale() throws Exception
	{
		setUp(GameState.LOGGED_IN);
		offer(3, GrandExchangeOfferState.SELLING, 4151, 0, 1, 1_500_000, 0);
		state(GameState.LOGIN_SCREEN);
		hash = B;
		state(GameState.LOGGING_IN);
		offer(3, GrandExchangeOfferState.SOLD, 4151, 1, 1, 1_500_000, 1_500_000);
		assertEquals("another account's replay of the same item is not our sale",
			Arrays.asList("ge_offer"), types());
	}
}
