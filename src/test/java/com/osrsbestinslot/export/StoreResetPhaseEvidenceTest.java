package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The evidence-grade shop cycle phase behind store_taken.ms_from_reset_cycle.
 *
 * The countdown anchor may move on ONE probe vanishing (a buyer can do that) or on TWO changes one period
 * apart (a coincidence can do that). A reader uses ms_from_reset_cycle to tell a decay (about 0) from a buy,
 * so a phase from either of those would turn a buy into a decay or a decay into a buy. The field is emitted
 * only once at least three changes on three distinct cycles agree, and never counts the change it judges.
 */
public class StoreResetPhaseEvidenceTest
{
	private static final long P = 60_000L;
	private static final long T0 = 1_790_000_000_000L;	// a realistic epoch ms

	private long now = T0;
	private AccountConnectPlugin p;

	private void setUp() throws Exception
	{
		p = new AccountConnectPlugin()
		{
			@Override
			long nowMs()
			{
				return now;
			}
		};
		inject("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return "0123456789abcdef0123456789abcdef";
			}
		});
		inject("serverClipsDisabled", true);	// no render hook in a unit test
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		inject("client", client);
	}

	/** A visit that sold these items (the first is the junk probe) with this stock already read. */
	private void visit(int[][] stock) throws Exception
	{
		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>();
		java.util.Map<Integer, Integer> base = new java.util.LinkedHashMap<>();
		for (int[] pair : stock)
		{
			sold.add(pair[0]);
			base.put(pair[0], pair[1]);
		}
		inject("soldThisVisit", sold);
		inject("shopStock", base);
		inject("storeProbeItem", stock[0][0]);
	}

	/** Our own store click through the real menu path ("Sell 1" or "Buy 1"). */
	private void click(long atMs, String option, int item) throws Exception
	{
		now = atMs;
		inject("shopOpen", true);
		net.runelite.api.events.MenuOptionClicked e = mock(net.runelite.api.events.MenuOptionClicked.class);
		when(e.getMenuOption()).thenReturn(option);
		when(e.getItemId()).thenReturn(item);
		p.onMenuOptionClicked(e);
	}

	/** Every row that carries a cycle distance must carry the TRUE one (within one tolerance). */
	private void assertNoFalsePhase(long trueTickMs)
	{
		for (Map<String, Object> e : taken())
		{
			if (!e.containsKey("ms_from_reset_cycle"))
			{
				continue;
			}
			long ms = ((Number) e.get("ms_from_reset_cycle")).longValue();
			long at = ((Number) e.get("at_test_ms")).longValue();
			long r = Math.floorMod(at - trueTickMs, P);
			long truth = Math.min(r, P - r);
			assertTrue("phase off the true tick: row " + e + " truth " + truth,
				Math.abs(ms - truth) <= AccountConnectPlugin.PHASE_ALIGN_TOL_MS);
		}
	}

	private void stock(long atMs, int[][] pairs)
	{
		now = atMs;
		ItemContainer c = mock(ItemContainer.class);
		Item[] items = new Item[pairs.length];
		for (int i = 0; i < pairs.length; i++)
		{
			items[i] = new Item(pairs[i][0], pairs[i][1]);
		}
		when(c.getItems()).thenReturn(items);
		int before = p.pendingEvents.size();
		p.handleShopStockChanged(c);
		for (int i = before; i < p.pendingEvents.size(); i++)
		{
			p.pendingEvents.get(i).put("at_test_ms", atMs);	// the test clock, for assertNoFalsePhase
		}
	}

	// ---- review r1 HIGH-1: only shop-made decay may support the phase ----

	/**
	 * Reviewer probe A. True tick at +0 mod 60 s. We sell right after the countdown (+1.8 s, once +2.4 s),
	 * buyers take the item a few seconds later, and the last item decays on the true tick. Before the fix the
	 * own sells confirmed a phase at +1.8 s and the true decay read 1800 ms off the cycle.
	 */
	@Test
	public void ownSellsAtAFixedOffsetNeverConfirmAFalsePhase() throws Exception
	{
		setUp();
		java.util.Map<Integer, Integer> base = new java.util.LinkedHashMap<>();
		base.put(1511, 1);
		base.put(999, 3);
		inject("shopStock", base);
		inject("soldThisVisit", new java.util.LinkedHashSet<>(java.util.Arrays.asList(1511)));
		inject("storeProbeItem", 1511);
		stock(T0, new int[][]{{999, 3}});					// probe decays on the tick
		long[] sellOff = {1_800L, 1_800L, 2_400L, 1_800L, 1_800L};
		long[] buyOff = {6_000L, 9_000L, 4_200L, 11_400L, -1L};
		for (int k = 0; k < 5; k++)
		{
			int item = 201 + k;
			click(T0 + k * P + sellOff[k] - 600L, "Sell 1", item);
			stock(T0 + k * P + sellOff[k], new int[][]{{999, 3}, {item, 1}});
			if (buyOff[k] >= 0)
			{
				stock(T0 + k * P + buyOff[k], new int[][]{{999, 3}});
			}
		}
		stock(T0 + 5 * P, new int[][]{{999, 3}});				// 205 decays on the true tick
		assertEquals(6, taken().size());
		assertNoFalsePhase(T0);
		for (Map<String, Object> e : taken())
		{
			if (e.containsKey("ms_from_reset_cycle"))
			{
				assertTrue("never 1.8 s off: " + e, ((Number) e.get("ms_from_reset_cycle")).longValue() < 1_000L);
			}
		}
	}

	/** Reviewer probe B: a native Pot sold once a minute and its normalisation echo never confirm. */
	@Test
	public void aNativeItemSoldEveryMinuteAndItsEchoNeverConfirm() throws Exception
	{
		setUp();
		java.util.Map<Integer, Integer> base = new java.util.LinkedHashMap<>();
		base.put(1931, 5);
		base.put(999, 3);
		inject("shopStock", base);
		// 999 = merchandise we sold earlier, so the final buy of it is a store_taken row to judge
		inject("soldThisVisit", new java.util.LinkedHashSet<>(java.util.Collections.singletonList(999)));
		for (int k = 0; k < 5; k++)
		{
			click(T0 + k * P - 600L, "Sell 1", 1931);			// first click marks 1931 as default stock
			stock(T0 + k * P, new int[][]{{1931, 6}, {999, 3}});
			stock(T0 + k * P + 3_000L, new int[][]{{1931, 5}, {999, 3}});
		}
		stock(T0 + 5 * P + 20_000L, new int[][]{{1931, 5}, {999, 2}});
		assertEquals("the final buy of 999 is judged", 999, last().get("item"));
		for (Map<String, Object> e : taken())
		{
			assertFalse("an own sell and its echo are not a phase: " + e, e.containsKey("ms_from_reset_cycle"));
		}
		assertNoFalsePhase(T0);
	}

	/**
	 * Native stock falling by one, well outside the quiet window, every minute: it is the shop normalising
	 * its own goods (defaultStockSoldThisVisit), never player-added decay.
	 */
	@Test
	public void defaultStockFallsNeverCount() throws Exception
	{
		setUp();
		java.util.Map<Integer, Integer> base = new java.util.LinkedHashMap<>();
		base.put(1931, 5);
		base.put(999, 3);
		inject("shopStock", base);
		inject("soldThisVisit", new java.util.LinkedHashSet<>(java.util.Collections.singletonList(999)));
		for (int k = 0; k < 5; k++)
		{
			click(T0 + k * P - 20_000L, "Sell 1", 1931);
			stock(T0 + k * P - 19_400L, new int[][]{{1931, 6}, {999, 3}});
			stock(T0 + k * P, new int[][]{{1931, 5}, {999, 3}});		// the one-unit fall lands 20 s later, every minute
		}
		stock(T0 + 5 * P + 20_000L, new int[][]{{1931, 5}, {999, 2}});
		assertEquals("the final buy of 999 is judged", 999, last().get("item"));
		for (Map<String, Object> e : taken())
		{
			assertFalse("native stock normalising is not a decay: " + e, e.containsKey("ms_from_reset_cycle"));
		}
	}

	/**
	 * A dead-drop buyer who always buys one tick after our sell: every fall is exactly one unit of our sold
	 * item, and every sell is on the same offset. The quiet window keeps these from confirming.
	 */
	@Test
	public void aBuyerWhoAlwaysBuysOneTickAfterOurSellNeverConfirms() throws Exception
	{
		setUp();
		java.util.Map<Integer, Integer> base = new java.util.LinkedHashMap<>();
		base.put(999, 3);
		inject("shopStock", base);
		inject("soldThisVisit", new java.util.LinkedHashSet<Integer>());
		for (int k = 0; k < 6; k++)
		{
			int item = 301 + k;
			click(T0 + k * P + 1_200L, "Sell 1", item);
			stock(T0 + k * P + 1_800L, new int[][]{{999, 3}, {item, 1}});
			stock(T0 + k * P + 2_400L, new int[][]{{999, 3}});			// bought one tick later
		}
		assertEquals(6, taken().size());
		for (Map<String, Object> e : taken())
		{
			assertFalse("a buyer tied to our sells is not a phase: " + e, e.containsKey("ms_from_reset_cycle"));
		}
	}

	/** The quiet window applies to our buy-back clicks too, not only sells. */
	@Test
	public void aFallRightAfterOurOwnBuyNeverCounts() throws Exception
	{
		setUp();
		visit(new int[][]{{1511, 4}, {999, 3}});
		for (int k = 0; k < 4; k++)
		{
			click(T0 + k * P - 5_000L, "Buy 1", 555);				// our buy of something else, 5 s before
			stock(T0 + k * P, new int[][]{{1511, 3 - k}, {999, 3}});
		}
		stock(T0 + 4 * P + 30_000L, new int[][]{{999, 2}});
		for (Map<String, Object> e : taken())
		{
			assertFalse("changes within 10 s of our own buy are not evidence: " + e, e.containsKey("ms_from_reset_cycle"));
		}
	}

	/** A clean decay chain of our own junk, with no action of ours within 10 s, still confirms at 3. */
	@Test
	public void aCleanDecayChainAfterAQuietSellStillConfirmsAtThree() throws Exception
	{
		setUp();
		inject("shopStock", new java.util.LinkedHashMap<Integer, Integer>(java.util.Collections.singletonMap(999, 3)));
		inject("soldThisVisit", new java.util.LinkedHashSet<>(java.util.Collections.singletonList(999)));
		click(T0 - 30_000L, "Sell 3", 1511);
		stock(T0 - 29_400L, new int[][]{{1511, 3}, {999, 3}});			// our sell lands (a rise): not evidence
		stock(T0, new int[][]{{1511, 2}, {999, 3}});
		stock(T0 + P, new int[][]{{1511, 1}, {999, 3}});
		stock(T0 + 2 * P, new int[][]{{999, 3}});
		for (Map<String, Object> e : taken())
		{
			assertFalse(e.containsKey("ms_from_reset_cycle"));
		}
		stock(T0 + 2 * P + 30_000L, new int[][]{{999, 2}});
		assertEquals(30_000L, ((Number) last().get("ms_from_reset_cycle")).longValue());
		assertEquals(3, ((Number) last().get("reset_cycle_obs")).intValue());
	}

	/**
	 * A buyer who takes several units at once, once a minute at the same offset: player-added stock decays
	 * one unit per cycle, so a fall of more than one is a buy and never supports the phase.
	 */
	@Test
	public void aMultiUnitFallNeverCounts() throws Exception
	{
		setUp();
		visit(new int[][]{{1511, 1}, {999, 20}});
		for (int k = 0; k < 5; k++)
		{
			stock(T0 + k * P, new int[][]{{1511, 1}, {999, 18 - 2 * k}});		// 2 units every minute
		}
		stock(T0 + 5 * P + 30_000L, new int[][]{{1511, 1}, {999, 9}});
		assertEquals(6, taken().size());
		for (Map<String, Object> e : taken())
		{
			assertFalse("a two-unit fall is a buy, not a decay: " + e, e.containsKey("ms_from_reset_cycle"));
		}
	}

	/** A change where one item falls by one while another rises (a restock or a sell landing) never counts. */
	@Test
	public void aFallTogetherWithARiseNeverCounts() throws Exception
	{
		setUp();
		visit(new int[][]{{1511, 4}, {999, 3}});
		for (int k = 0; k < 4; k++)
		{
			stock(T0 + k * P, new int[][]{{1511, 3 - k}, {999, 3 + k + 1}});	// 999 rises in the same change
		}
		stock(T0 + 4 * P + 30_000L, new int[][]{{999, 3}});
		for (Map<String, Object> e : taken())
		{
			assertFalse("a change with a rise is not a pure decay: " + e, e.containsKey("ms_from_reset_cycle"));
		}
	}

	private List<Map<String, Object>> taken()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> e : p.pendingEvents)
		{
			if ("store_taken".equals(e.get("type")))
			{
				out.add(e);
			}
		}
		return out;
	}

	private Map<String, Object> last()
	{
		List<Map<String, Object>> t = taken();
		return t.get(t.size() - 1);
	}

	private void inject(String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, value);
	}

	private long anchor() throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("storeResetAnchorMs");
		f.setAccessible(true);
		return f.getLong(p);
	}

	/** Three decay steps of a 3-stack probe, one period apart: confirms the phase at the third step. */
	private void confirmPhaseByDecay() throws Exception
	{
		visit(new int[][]{{1511, 3}, {999, 3}});
		stock(T0, new int[][]{{1511, 2}, {999, 3}});
		stock(T0 + P, new int[][]{{1511, 1}, {999, 3}});
		stock(T0 + 2 * P, new int[][]{{999, 3}});
	}

	// ---- (a) a probe bought mid-cycle ----

	/**
	 * A buyer takes the junk probe 10 s into the cycle. The countdown anchors there (unchanged behaviour),
	 * but that is ONE observation and a buy, so it must not become the evidence phase: a later off-phase
	 * fall of the merchandise carries no cycle distance, rather than a confident wrong one.
	 */
	@Test
	public void aProbeBoughtMidCycleDoesNotBecomeTheEvidencePhase() throws Exception
	{
		setUp();
		visit(new int[][]{{111, 1}, {999, 3}});
		long boughtAt = T0 + 10_000L;
		stock(boughtAt, new int[][]{{999, 3}});				// probe bought out by a player
		assertEquals("the countdown still anchors on the probe vanishing", boughtAt, anchor());

		stock(boughtAt + 25_000L, new int[][]{{999, 2}});		// a real buy, off that false phase
		stock(boughtAt + P + 40_000L, new int[][]{{999, 1}});		// another, later
		assertEquals(3, taken().size());
		for (Map<String, Object> e : taken())
		{
			assertFalse("no confirmed phase, so no cycle distance: " + e, e.containsKey("ms_from_reset_cycle"));
			assertFalse("and no support count: " + e, e.containsKey("reset_cycle_obs"));
		}
		assertEquals("the countdown anchor is not moved by merchandise", boughtAt, anchor());
	}

	// ---- (b) a coincidence of two ----

	@Test
	public void twoUnrelatedChangesExactlyOnePeriodApartDoNotConfirm() throws Exception
	{
		setUp();
		visit(new int[][]{{111, 5}, {999, 3}});
		stock(T0, new int[][]{{111, 5}, {999, 2}});
		stock(T0 + P, new int[][]{{111, 5}, {999, 1}});
		stock(T0 + P + 17_000L, new int[][]{{111, 5}});
		for (Map<String, Object> e : taken())
		{
			assertFalse("two agreeing changes can be chance: " + e, e.containsKey("ms_from_reset_cycle"));
		}
		assertEquals(3, taken().size());
	}

	// ---- (c) a real decay chain ----

	@Test
	public void threeDecayStepsConfirmThePhaseAndLaterFallsCarryIt() throws Exception
	{
		setUp();
		confirmPhaseByDecay();
		assertEquals(3, taken().size());
		for (Map<String, Object> e : taken())
		{
			assertFalse("a change never judges itself against a phase it creates: " + e,
				e.containsKey("ms_from_reset_cycle"));
		}

		stock(T0 + 2 * P + 30_000L, new int[][]{{999, 2}});		// off-cycle: a buy
		Map<String, Object> off = last();
		assertEquals(30_000L, ((Number) off.get("ms_from_reset_cycle")).longValue());
		assertEquals(3, ((Number) off.get("reset_cycle_obs")).intValue());

		stock(T0 + 3 * P + 200L, new int[][]{{999, 1}});		// on the cycle: a decay
		Map<String, Object> on = last();
		assertEquals(200L, ((Number) on.get("ms_from_reset_cycle")).longValue());
		assertEquals("support read before this change", 3, ((Number) on.get("reset_cycle_obs")).intValue());

		stock(T0 + 4 * P - 300L, new int[][]{});			// on the next cycle, early by 0.3 s
		Map<String, Object> next = last();
		assertEquals(300L, ((Number) next.get("ms_from_reset_cycle")).longValue());
		assertEquals("each aligned cycle raises the support", 4, ((Number) next.get("reset_cycle_obs")).intValue());
	}

	/** Two aligned changes in ONE cycle are one observation of that cycle, not two. */
	@Test
	public void twoChangesInOneCycleCountOnce() throws Exception
	{
		setUp();
		confirmPhaseByDecay();
		stock(T0 + 3 * P + 100L, new int[][]{{999, 2}});
		stock(T0 + 3 * P + 500L, new int[][]{{999, 1}});
		stock(T0 + 3 * P + 20_000L, new int[][]{});
		assertEquals(4, ((Number) last().get("reset_cycle_obs")).intValue());
	}

	// ---- (d) busy-shop noise ----

	/**
	 * Changes that sit 0.8-4.6 s away from whole periods of each other. With a tolerance of one game tick
	 * none of them agree; a wide tolerance would lock onto them.
	 */
	@Test
	public void nearMissNoiseNeverConfirms() throws Exception
	{
		setUp();
		visit(new int[][]{{111, 9}, {999, 9}});
		long[] offs = {0L, 1_500L, 3_200L, -1_400L, 2_400L};
		int q = 9;
		for (int k = 0; k < offs.length; k++)
		{
			q--;
			stock(T0 + k * P + offs[k], new int[][]{{111, 9}, {999, q}});
		}
		stock(T0 + 5 * P + 20_000L, new int[][]{{111, 9}, {999, 1}});
		for (Map<String, Object> e : taken())
		{
			assertFalse("near misses are not a phase: " + e, e.containsKey("ms_from_reset_cycle"));
		}
	}

	/** A busy shop: changes at many times, none aligned with any other to within a tick, never confirm. */
	@Test
	public void busyShopChangesAtUnalignedTimesNeverConfirm() throws Exception
	{
		setUp();
		visit(new int[][]{{111, 50}, {999, 50}});
		java.util.Random r = new java.util.Random(7);
		List<Long> times = new ArrayList<>();
		long t = T0;
		for (int tries = 0; times.size() < 40 && tries < 5_000; tries++)
		{
			t += 1_000L + r.nextInt(20_000);
			boolean clash = false;
			for (long o : times)
			{
				if (AccountConnectPlugin.phaseAligned(o, t, AccountConnectPlugin.PHASE_ALIGN_TOL_MS))
				{
					clash = true;
					break;
				}
			}
			if (!clash)
			{
				times.add(t);
			}
		}
		int q = 50;
		for (long at : times)
		{
			q--;
			stock(at, new int[][]{{111, 50}, {999, q}});
		}
		assertEquals(40, taken().size());
		for (Map<String, Object> e : taken())
		{
			assertFalse("unaligned traffic is not a phase: " + e, e.containsKey("ms_from_reset_cycle"));
		}
	}

	/**
	 * A REAL busy shop: changes at random times (a change every 8 s on average for 10 minutes), with no
	 * filtering. Among ~75 changes some three line up by pure chance in most visits, so a fixed "any 3"
	 * rule confirms a false phase (measured by simulation: ~94% of such visits). The support needed grows with
	 * the history, so none of these visits may report a cycle distance.
	 */
	@Test
	public void aBusyShopWithRandomTrafficDoesNotConfirmByChance() throws Exception
	{
		for (int seed = 1; seed <= 20; seed++)
		{
			now = T0;
			setUp();
			visit(new int[][]{{111, 1000}, {999, 1000}});
			java.util.Random r = new java.util.Random(seed);
			long t = T0;
			int q = 1000;
			while (t < T0 + 10 * P)
			{
				t += (long) (-Math.log(1 - r.nextDouble()) * 8_000.0) + 1;
				q--;
				stock(t, new int[][]{{111, 1000}, {999, q}});
			}
			for (Map<String, Object> e : taken())
			{
				assertFalse("seed " + seed + ": chance alignment is not a phase: " + e,
					e.containsKey("ms_from_reset_cycle"));
			}
		}
	}

	/** Real cycle ticks inside moderate traffic still confirm: the stricter rule does not blind a real shop. */
	@Test
	public void realTicksInsideModerateTrafficStillConfirm() throws Exception
	{
		setUp();
		visit(new int[][]{{111, 1000}, {999, 1000}});
		java.util.Random r = new java.util.Random(3);
		List<Long> times = new ArrayList<>();
		for (int k = 0; k < 10; k++)
		{
			times.add(T0 + k * P + 7_000L);		// the true phase: 7 s after T0
		}
		long t = T0;
		while (t < T0 + 10 * P)
		{
			t += (long) (-Math.log(1 - r.nextDouble()) * 30_000.0) + 1;
			times.add(t);
		}
		java.util.Collections.sort(times);
		int q = 1000;
		for (long at : times)
		{
			q--;
			stock(at, new int[][]{{111, 1000}, {999, q}});
		}
		Map<String, Object> e = last();
		assertTrue("ten real ticks confirm: " + e, e.containsKey("ms_from_reset_cycle"));
		long off = ((Number) e.get("ms_from_reset_cycle")).longValue();
		long truth = Math.floorMod(now - (T0 + 7_000L), P);
		assertEquals("the confirmed phase is the true one", Math.min(truth, P - truth), off, 250L);
	}

	@Test
	public void theSupportNeededGrowsWithTheHistory()
	{
		assertEquals("a quiet shop: three ticks are enough", 3, AccountConnectPlugin.requiredPhaseSupport(3));
		assertEquals(3, AccountConnectPlugin.requiredPhaseSupport(5));
		assertTrue("never below three", AccountConnectPlugin.requiredPhaseSupport(2) > 2);
		int prev = 0;
		for (int n = 0; n <= AccountConnectPlugin.RESET_PHASE_HISTORY; n++)
		{
			int s = AccountConnectPlugin.requiredPhaseSupport(n);
			assertTrue("n=" + n + " needs " + s, s >= AccountConnectPlugin.RESET_PHASE_MIN_OBS);
			assertTrue("monotone at n=" + n, s >= prev);
			prev = s;
		}
		assertTrue("a full history needs more than three", AccountConnectPlugin.requiredPhaseSupport(32) > 3);
	}

	// ---- (e) the phase is per visit ----

	@Test
	public void reOpeningTheShopClearsThePhase() throws Exception
	{
		setUp();
		confirmPhaseByDecay();
		p.handleActivityWidgetLoaded(net.runelite.api.gameval.InterfaceID.SHOPMAIN);
		visit(new int[][]{{222, 1}, {999, 3}});
		stock(T0 + 3 * P + 25_000L, new int[][]{{222, 1}, {999, 2}});
		assertFalse("a new visit starts with no phase: " + last(), last().containsKey("ms_from_reset_cycle"));
	}

	@Test
	public void everyTeardownClearsThePhase() throws Exception
	{
		for (GameState s : new GameState[]{GameState.HOPPING, GameState.LOGGING_IN, GameState.LOGIN_SCREEN,
			GameState.CONNECTION_LOST})
		{
			now = T0;
			setUp();
			confirmPhaseByDecay();
			GameStateChanged ev = new GameStateChanged();
			ev.setGameState(s);
			p.onGameStateChanged(ev);
			visit(new int[][]{{222, 1}, {999, 3}});
			stock(T0 + 3 * P + 25_000L, new int[][]{{222, 1}, {999, 2}});
			assertFalse(s + " must drop the phase: " + last(), last().containsKey("ms_from_reset_cycle"));
		}
		now = T0;
		setUp();
		confirmPhaseByDecay();
		p.shutDown();
		visit(new int[][]{{222, 1}, {999, 3}});
		stock(T0 + 3 * P + 25_000L, new int[][]{{222, 1}, {999, 2}});
		assertFalse("shutDown must drop the phase: " + last(), last().containsKey("ms_from_reset_cycle"));
	}

	@Test
	public void closingTheShopClearsThePhase() throws Exception
	{
		setUp();
		confirmPhaseByDecay();
		p.onWidgetClosed(new net.runelite.api.events.WidgetClosed(net.runelite.api.gameval.InterfaceID.SHOPMAIN, 0, true));
		visit(new int[][]{{222, 1}, {999, 3}});
		stock(T0 + 3 * P + 25_000L, new int[][]{{222, 1}, {999, 2}});
		assertFalse(last().containsKey("ms_from_reset_cycle"));
	}

	// ---- the pure rule ----

	@Test
	public void alignmentIsWholePeriodsWithinOneTick()
	{
		long tol = AccountConnectPlugin.PHASE_ALIGN_TOL_MS;
		assertTrue(tol >= 600L && tol < 1_000L);
		assertTrue(AccountConnectPlugin.phaseAligned(T0, T0 + P, tol));
		assertTrue(AccountConnectPlugin.phaseAligned(T0, T0 + 3 * P + tol, tol));
		assertTrue("order does not matter", AccountConnectPlugin.phaseAligned(T0 + 2 * P - tol, T0, tol));
		assertFalse(AccountConnectPlugin.phaseAligned(T0, T0 + P + tol + 1, tol));
		assertFalse("same cycle is k = 0", AccountConnectPlugin.phaseAligned(T0, T0 + 300L, tol));
		assertFalse(AccountConnectPlugin.phaseAligned(T0, T0, tol));
		assertFalse("half a period", AccountConnectPlugin.phaseAligned(T0, T0 + P / 2, tol));
	}

	@Test
	public void alignedPhaseNeedsEveryPairToAgree()
	{
		long tol = AccountConnectPlugin.PHASE_ALIGN_TOL_MS;
		assertNull(AccountConnectPlugin.alignedPhase(new long[0], tol));
		long[] two = AccountConnectPlugin.alignedPhase(new long[]{T0, T0 + P}, tol);
		assertEquals(2L, two[1]);
		long[] three = AccountConnectPlugin.alignedPhase(new long[]{T0, T0 + 17_000L, T0 + P + 400L, T0 + 2 * P - 250L}, tol);
		assertNotNull(three);
		assertEquals(3L, three[1]);
		assertEquals("the phase is the latest member", T0 + 2 * P - 250L, three[0]);
		// a and b agree, b and c agree, but a and c are 1.2 s apart: not one phase
		long[] chain = AccountConnectPlugin.alignedPhase(new long[]{T0, T0 + P + 600L, T0 + 2 * P + 1_200L}, tol);
		assertEquals(2L, chain[1]);
		assertTrue(AccountConnectPlugin.RESET_PHASE_MIN_OBS >= 3);
	}
}
