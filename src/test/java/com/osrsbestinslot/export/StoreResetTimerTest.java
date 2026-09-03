package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The store reset clock (0.7.8).
 *
 * A general store cycles its stock on a fixed 60s clock. An item put into the shop vanishes at the
 * next tick of that clock, NOT 60s after it was sold — so an item sold 1s after a reset is gone in
 * 59s, and one sold at 58s is gone in 2. Staff currently feel this out by selling a junk item and
 * watching it disappear, which is a guess repeated by eye on every visit; measured over 56 real
 * sessions the gap between that probe and the first real item ran 5s to 146s.
 *
 * One observed reset fixes the PHASE, and every later reset follows from it while the shop stays
 * open (operator report, 2026-09-03). These arms hold the two properties that make the countdown safe to
 * trust: the arithmetic is a phase, not a per-item duration, and an UNKNOWN phase shows nothing
 * rather than a guess.
 */
public class StoreResetTimerTest
{
	private static final long P = 60_000L;

	// ---- phase arithmetic ----

	/**
	 * THE PROPERTY THE WHOLE FEATURE RESTS ON. Time remaining depends on where we are in the cycle,
	 * never on when an item was sold. A naive "60s from the sell" would pass a test that only ever
	 * measured from the anchor, so measure from three different offsets instead.
	 */
	@Test
	public void countdownIsAPhaseNotAPerItemDuration()
	{
		long anchor = 1_000_000L;
		assertEquals("1s after a reset -> 59s left", 59_000L, AccountConnectPlugin.msUntilNextReset(anchor, anchor + 1_000L));
		assertEquals("30s in -> 30s left", 30_000L, AccountConnectPlugin.msUntilNextReset(anchor, anchor + 30_000L));
		assertEquals("58s in -> 2s left", 2_000L, AccountConnectPlugin.msUntilNextReset(anchor, anchor + 58_000L));
	}

	/** The anchor keeps working across later cycles — one probe pins the phase for the whole visit. */
	@Test
	public void oneAnchorSurvivesManyCycles()
	{
		long anchor = 5_000_000L;
		assertEquals("into the 5th cycle, 40s elapsed", 20_000L,
			AccountConnectPlugin.msUntilNextReset(anchor, anchor + (4 * P) + 40_000L));
		assertEquals("an hour later the phase still holds", 45_000L,
			AccountConnectPlugin.msUntilNextReset(anchor, anchor + (60 * P) + 15_000L));
	}

	/** Landing exactly on a tick means a FULL period remains — never 0, which means "unknown". */
	@Test
	public void exactTickYieldsAFullPeriodNotZero()
	{
		long anchor = 2_000_000L;
		assertEquals(P, AccountConnectPlugin.msUntilNextReset(anchor, anchor));
		assertEquals(P, AccountConnectPlugin.msUntilNextReset(anchor, anchor + P));
		assertEquals(P, AccountConnectPlugin.msUntilNextReset(anchor, anchor + (7 * P)));
	}

	/**
	 * DEGENERATE ARMS. An unknown phase must return the "show nothing" sentinel rather than a plausible
	 * number — a confidently wrong countdown is worse than no countdown, because staff would push a
	 * high-value item into a window that has already closed.
	 */
	@Test
	public void unknownPhaseShowsNothing()
	{
		assertEquals("no anchor yet", 0L, AccountConnectPlugin.msUntilNextReset(0L, 9_999_999L));
		assertEquals("negative anchor is not an anchor", 0L, AccountConnectPlugin.msUntilNextReset(-1L, 9_999_999L));
		assertEquals("clock went backwards", 0L, AccountConnectPlugin.msUntilNextReset(5_000L, 4_000L));
		assertFalse(AccountConnectPlugin.resetPhaseKnown(0L));
		assertTrue(AccountConnectPlugin.resetPhaseKnown(1L));
	}

	// ---- anchor detection ----




	/**
	 * THE PROBE IS FIXED AT THE FIRST SELL AND NEVER RE-ARMED.
	 *
	 * If each sell overwrote the probe, the high-value merchandise would become the probe and the
	 * customer's purchase would anchor the clock — precisely the defect this model exists to avoid.
	 * Drives the REAL sell path rather than injecting the field, because the bug lives in that branch.
	 * (Mutation-found 2026-09-03: relaxing the `storeProbeItem == 0` guard survived every other arm.)
	 */
	@Test
	public void theProbeIsFixedAtTheFirstSellAndNotReArmed() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		inject(plugin, "shopOpen", true);
		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		inject(plugin, "client", client);

		plugin.onMenuOptionClicked(sellClick(111));	// junk probe first
		plugin.onMenuOptionClicked(sellClick(999));	// then the merchandise

		assertEquals("the probe must remain the FIRST item sold", 111, probeItem(plugin));
	}




	/**
	 * THE COUNTDOWN MUST COME BACK FOR THE NEXT CYCLE WITHOUT A NEW OBSERVATION.
	 *
	 * RuneLite's Timer infobox removes ITSELF at zero, so one observed reset buys exactly one 60s
	 * countdown. The phase is still known, so the tick handler re-arms it. Without this the timer
	 * shows once and disappears — half of the live "not working" report on 2026-09-03.
	 *
	 * This asserts the re-arm CONDITION on the real fields, not a mock InfoBoxManager: showResetTimer
	 * needs an injected manager, and the decision being tested is the guard, not the drawing.
	 */
	@Test
	public void theTickReArmsTheCountdownWhenThePhaseIsKnownAndNoTimerIsUp() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		inject(plugin, "shopOpen", true);
		inject(plugin, "storeResetAnchorMs", System.currentTimeMillis());
		inject(plugin, "infoBoxManager", null);		// no manager: showResetTimer is a safe no-op
		inject(plugin, "resetTimer", null);		// the state after a Timer removed itself

		plugin.onGameTick(new net.runelite.api.events.GameTick());

		// The guard ran and reached showResetTimer, which returned early with no manager. What this
		// arm pins is that the tick path is REACHED at all — deleting the re-arm block, or negating
		// any of its three conditions, changes the reachability asserted below.
		assertTrue("phase must still be known after the tick", plugin.resetPhaseKnownForTest());
		assertTrue("the tick must have attempted a re-arm", plugin.reArmAttemptsForTest() > 0);
	}

	/** No re-arm while a countdown is already showing — that would restart it every tick. */
	@Test
	public void theTickDoesNotReArmWhileACountdownIsAlreadyUp() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		inject(plugin, "shopOpen", true);
		inject(plugin, "storeResetAnchorMs", System.currentTimeMillis());
		inject(plugin, "resetTimer", org.mockito.Mockito.mock(
			net.runelite.client.ui.overlay.infobox.Timer.class));

		plugin.onGameTick(new net.runelite.api.events.GameTick());

		assertEquals("must not re-arm over a live countdown", 0, plugin.reArmAttemptsForTest());
	}

	/** No re-arm outside a shop, and none while the phase is unknown. */
	@Test
	public void theTickDoesNotReArmOutsideAShopOrWithAnUnknownPhase() throws Exception
	{
		AccountConnectPlugin outside = plugin();
		inject(outside, "shopOpen", false);
		inject(outside, "storeResetAnchorMs", System.currentTimeMillis());
		outside.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals("no re-arm outside a shop", 0, outside.reArmAttemptsForTest());

		AccountConnectPlugin unknown = plugin();
		inject(unknown, "shopOpen", true);
		inject(unknown, "storeResetAnchorMs", 0L);
		unknown.onGameTick(new net.runelite.api.events.GameTick());
		assertEquals("no re-arm with an unknown phase", 0, unknown.reArmAttemptsForTest());
	}

	// ---- anchor 1: THE ITEM WE SOLD VANISHING (measured live 2026-09-03) ----
	//
	// This is the event staff watch. They sell a junk item the shop does NOT natively stock and wait
	// for it to disappear. Measured: player-added item 1191 decayed 5->4->3->...->0, every step
	// exactly 60.0s apart, and left the shop at zero. Selling a Pot into Varrock GS — where a Pot IS
	// default stock — raised it 5->6 and the shop normalised it back to 5 in 1.2-5.4 SECONDS, which
	// is not the cycle at all. ZERO is the discriminator between those two.

	@Test
	public void theItemWeSoldReachingZeroAnchorsTheClock() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		plugin.setStoreToolsForTest(true);
		seedVisit(plugin, new int[][]{{1191, 1}});		// our junk, one left
		long before = System.currentTimeMillis();

		plugin.handleShopStockChanged(shop(new int[][]{{550, 5}}));	// 1191 gone entirely

		assertTrue("the sold item vanishing must anchor", anchor(plugin) >= before);
	}

	/**
	 * A FALL TO A NON-ZERO NUMBER IS NOT THE TICK. This is the exact shape that produced 72-second
	 * "cycles" drifting 36s/24s/12s from the truth: the shop absorbing its own default stock a few
	 * seconds after a sell. If this arm goes green with a naive "stock fell" rule, the countdown is
	 * confidently wrong again.
	 */
	@Test
	public void theShopAbsorbingItsOwnDefaultStockDoesNotAnchor() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		plugin.setStoreToolsForTest(true);
		seedVisit(plugin, new int[][]{{1931, 6}});		// a Pot sold in, above the default of 5

		plugin.handleShopStockChanged(shop(new int[][]{{1931, 5}}));	// normalised, NOT gone

		assertEquals("6->5 is the shop normalising, not a tick", 0L, anchor(plugin));
	}

	@Test
	public void anItemWeDidNotSellVanishingDoesNotAnchor() throws Exception
	{
		// Another player's stock reaching zero is a real tick, but we cannot tell it from a customer
		// clearing the last of a stack. Only OUR OWN sold item is unambiguous.
		AccountConnectPlugin plugin = plugin();
		plugin.setStoreToolsForTest(true);
		seedVisit(plugin, new int[][]{{1191, 1}});
		Field f = AccountConnectPlugin.class.getDeclaredField("shopStock");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<Integer, Integer> st = (Map<Integer, Integer>) f.get(plugin);
		st.put(9999, 1);			// present in stock, never sold by us

		plugin.handleShopStockChanged(shop(new int[][]{{1191, 1}}));	// 9999 vanished, ours did not

		assertEquals("only our own sold item may anchor", 0L, anchor(plugin));
	}

	@Test
	public void anItemThatWasNeverInStockCannotVanish() throws Exception
	{
		// had == 0 must not read as a fall to zero. Otherwise every item we ever sold anchors the
		// clock on every single container change, forever.
		AccountConnectPlugin plugin = plugin();
		plugin.setStoreToolsForTest(true);
		seedVisit(plugin, new int[][]{{1191, 0}});

		plugin.handleShopStockChanged(shop(new int[][]{{550, 5}}));

		assertEquals("0->0 is not a disappearance", 0L, anchor(plugin));
	}

	// ---- anchor detection: PERIODICITY (measured live 2026-09-03) ----
	//
	// Two models were refuted by live measurement before this one. Anchoring on "the junk item we
	// sold vanished" gave 72s cycles drifting 36s/24s/12s from the truth, because a shop's default
	// stock normalises 0.6-2.4s after a sell on its own schedule. Anchoring on "a rise and a fall
	// together" refused all five ticks in a five-minute observation, because a full shop has
	// nothing to restock and every tick was fall-only. The PERIOD held at 60.0s every time.

	@Test
	public void twoChangesOnePeriodApartAnchorTheClock()
	{
		long t = 1_000_000L;
		assertTrue(AccountConnectPlugin.isPeriodAgreement(t, t + 60_000L));
	}

	@Test
	public void theToleranceCoversRealJitterAndNoMore()
	{
		long t = 1_000_000L;
		long tol = AccountConnectPlugin.PERIOD_TOLERANCE_MS;
		assertTrue("at the edge, inside", AccountConnectPlugin.isPeriodAgreement(t, t + 60_000L + tol));
		assertTrue("at the edge, early", AccountConnectPlugin.isPeriodAgreement(t, t + 60_000L - tol));
		assertFalse("one ms past the edge", AccountConnectPlugin.isPeriodAgreement(t, t + 60_000L + tol + 1));
		assertFalse("one ms early past the edge", AccountConnectPlugin.isPeriodAgreement(t, t + 60_000L - tol - 1));
	}

	@Test
	public void ordinaryShopTrafficDoesNotAnchor()
	{
		// Real gaps measured between non-tick changes: a buy 9s after the last change, a sell 15s,
		// an absorption 2s. None may lock the clock.
		long t = 1_000_000L;
		for (long gap : new long[]{600L, 1_800L, 2_400L, 9_000L, 15_000L, 30_000L, 45_000L, 120_000L})
		{
			assertFalse("a " + gap + "ms gap must not anchor",
				AccountConnectPlugin.isPeriodAgreement(t, t + gap));
		}
	}

	/**
	 * DEGENERATE ARMS. Each passes a naive check while proving nothing, and each would anchor the
	 * clock on a non-event.
	 */
	@Test
	public void theFirstChangeOfAVisitCannotAnchor()
	{
		// With no previous change there is nothing to be periodic WITH. Opening a shop, or the very
		// first stock reading, must leave the clock unknown rather than anchoring to that instant.
		assertFalse(AccountConnectPlugin.isPeriodAgreement(0L, 1_000_000L));
		assertFalse(AccountConnectPlugin.isPeriodAgreement(-1L, 1_000_000L));
	}

	@Test
	public void aClockGoingBackwardsNeverAnchors()
	{
		assertFalse(AccountConnectPlugin.isPeriodAgreement(2_000_000L, 1_000_000L));
		assertFalse("identical timestamps are not a period",
			AccountConnectPlugin.isPeriodAgreement(1_000_000L, 1_000_000L));
	}

	@Test
	public void twoPeriodsIsNotOnePeriod()
	{
		// A tick MISSED (the shop unchanged for a whole cycle) lands 120s later. Treating that as
		// agreement would be right by luck here and wrong whenever the gap is any other multiple.
		long t = 1_000_000L;
		assertFalse(AccountConnectPlugin.isPeriodAgreement(t, t + 120_000L));
		assertFalse(AccountConnectPlugin.isPeriodAgreement(t, t + 180_000L));
	}

	/** The real container path: two changes one period apart anchor; nothing else does. */
	@Test
	public void theContainerPathAnchorsOnlyOnPeriodAgreement() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		plugin.setStoreToolsForTest(true);
		inject(plugin, "shopOpen", true);
		inject(plugin, "shopStock", new java.util.LinkedHashMap<Integer, Integer>());
		inject(plugin, "soldThisVisit", new java.util.LinkedHashSet<Integer>());

		// First reading of the visit — establishes the baseline, must not anchor.
		plugin.handleShopStockChanged(shop(new int[][]{{1115, 10}, {1191, 2}}));
		assertEquals("the first reading must not anchor", 0L, anchor(plugin));

		// A change — records the time, still nothing to be periodic with.
		plugin.handleShopStockChanged(shop(new int[][]{{1115, 9}, {1191, 1}}));
		assertEquals("one change is not a period", 0L, anchor(plugin));

		// A change one period later. Drive the clock through the field rather than sleeping 60s.
		inject(plugin, "lastStockChangeMs", System.currentTimeMillis() - 60_000L);
		plugin.handleShopStockChanged(shop(new int[][]{{1115, 8}, {1191, 1}}));
		assertTrue("two changes one period apart must anchor", anchor(plugin) > 0);
	}

	@Test
	public void anUnchangedContainerIsNotAChangeAtAll() throws Exception
	{
		// The shop container fires on events that leave the stock identical. Counting those as
		// changes would reset the periodicity chain constantly and stop the clock ever locking.
		AccountConnectPlugin plugin = plugin();
		plugin.setStoreToolsForTest(true);
		inject(plugin, "shopOpen", true);
		inject(plugin, "shopStock", new java.util.LinkedHashMap<Integer, Integer>());
		inject(plugin, "soldThisVisit", new java.util.LinkedHashSet<Integer>());
		plugin.handleShopStockChanged(shop(new int[][]{{1115, 10}}));

		long marker = System.currentTimeMillis() - 60_000L;
		inject(plugin, "lastStockChangeMs", marker);
		plugin.handleShopStockChanged(shop(new int[][]{{1115, 10}}));	// identical
		Field f = AccountConnectPlugin.class.getDeclaredField("lastStockChangeMs");
		f.setAccessible(true);
		assertEquals("an unchanged read must not touch the chain", marker, f.getLong(plugin));
		assertEquals("and must not anchor", 0L, anchor(plugin));
	}

	private static net.runelite.api.events.MenuOptionClicked sellClick(int itemId)
	{
		net.runelite.api.events.MenuOptionClicked e =
			org.mockito.Mockito.mock(net.runelite.api.events.MenuOptionClicked.class);
		org.mockito.Mockito.when(e.getMenuOption()).thenReturn("Sell 1");
		org.mockito.Mockito.when(e.getItemId()).thenReturn(itemId);
		return e;
	}

	private static int probeItem(AccountConnectPlugin p) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("storeProbeItem");
		f.setAccessible(true);
		return f.getInt(p);
	}


	// ---- infobox lifecycle: a countdown left on screen is the obvious failure of this feature ----

	/**
	 * Every exit path must remove the box. RuneLite does NOT clear a plugin's infoboxes for it, so a
	 * missed teardown leaves a stale countdown ticking against a shop that is no longer open — exactly
	 * the "confidently wrong number" this feature exists to replace.
	 */
	@Test
	public void everyExitPathRemovesTheTimer() throws Exception
	{
		for (String path : new String[]{"shutdown", "hop", "logout", "connection-lost"})
		{
			AccountConnectPlugin plugin = plugin();
			java.util.List<Object> boxes = new java.util.ArrayList<>();
			installInfoBoxManager(plugin, boxes);
			plugin.setStoreToolsForTest(true);	// the countdown is staff-gated
			seedVisit(plugin, new int[][]{{111, 1}, {222, 1}});
			inject(plugin, "lastStockChangeMs", System.currentTimeMillis() - 60_000L);
			plugin.handleShopStockChanged(shop(new int[][]{{111, 0}, {222, 1}}));	// period -> box
			assertEquals(path + ": a reset should show the countdown", 1, boxes.size());

			switch (path)
			{
				case "shutdown":
					plugin.shutDown();
					break;
				default:
					net.runelite.api.events.GameStateChanged ev = new net.runelite.api.events.GameStateChanged();
					ev.setGameState("hop".equals(path) ? net.runelite.api.GameState.HOPPING
						: "logout".equals(path) ? net.runelite.api.GameState.LOGIN_SCREEN
						: net.runelite.api.GameState.CONNECTION_LOST);
					plugin.onGameStateChanged(ev);
					break;
			}
			assertEquals(path + ": the countdown must be removed", 0, boxes.size());
			assertEquals(path + ": the phase must be dropped too", 0L, anchor(plugin));
		}
	}

	/** Repeated resets replace the box rather than stacking a new one every minute. */
	@Test
	public void repeatedResetsDoNotAccumulateBoxes() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		java.util.List<Object> boxes = new java.util.ArrayList<>();
		installInfoBoxManager(plugin, boxes);
		plugin.setStoreToolsForTest(true);	// the countdown is staff-gated
		for (int i = 0; i < 4; i++)
		{
			seedVisit(plugin, new int[][]{{111, 1}, {222, 1}});
			inject(plugin, "lastStockChangeMs", System.currentTimeMillis() - 60_000L);
			plugin.handleShopStockChanged(shop(new int[][]{{111, 0}, {222, 1}}));
		}
		assertEquals("four resets must leave exactly one box", 1, boxes.size());
	}

	/**
	 * THE COUNTDOWN IS STAFF-GATED TOO, not only the panel. Both surfaces render from the same
	 * staff-only feature, so an ungranted token must get neither.
	 */
	@Test
	public void anUngrantedTokenGetsNoCountdown() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		java.util.List<Object> boxes = new java.util.ArrayList<>();
		installInfoBoxManager(plugin, boxes);
		// store tools deliberately NOT granted
		seedVisit(plugin, new int[][]{{111, 1}, {222, 1}});
		inject(plugin, "lastStockChangeMs", System.currentTimeMillis() - 60_000L);

		plugin.handleShopStockChanged(shop(new int[][]{{111, 0}, {222, 1}}));	// a real tick

		assertEquals("an ungranted token must see no countdown", 0, boxes.size());
		assertTrue("but the phase is still tracked for when it IS granted", anchor(plugin) > 0);
	}

	/** Revoking the grant mid-session must clear a countdown that is already on screen. */
	@Test
	public void revokingTheGrantRemovesALiveCountdown() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		java.util.List<Object> boxes = new java.util.ArrayList<>();
		installInfoBoxManager(plugin, boxes);
		plugin.setStoreToolsForTest(true);
		seedVisit(plugin, new int[][]{{111, 1}, {222, 1}});
		inject(plugin, "lastStockChangeMs", System.currentTimeMillis() - 60_000L);
		plugin.handleShopStockChanged(shop(new int[][]{{111, 0}, {222, 1}}));
		assertEquals("granted: countdown shows", 1, boxes.size());

		plugin.applyServerPolicy(policyResponse("off"));

		assertEquals("revoked: countdown must be cleared", 0, boxes.size());
	}

	/** A 200 with the grant header present turns the feature on for this token. */
	@Test
	public void theServerHeaderGrantsAndRevokesTheFeature() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		assertFalse("default is OFF", plugin.storeToolsEnabled());
		plugin.applyServerPolicy(policyResponse("on"));
		assertTrue("header on grants the feature", plugin.storeToolsEnabled());
		plugin.applyServerPolicy(policyResponse("off"));
		assertFalse("header off revokes it", plugin.storeToolsEnabled());
	}

	/** Anything that is not an explicit grant word must leave the feature OFF. */
	@Test
	public void unrecognisedHeaderValuesDoNotGrantTheFeature() throws Exception
	{
		for (String v : new String[]{"", " ", "maybe", "allow", "yes", "2", "OFF"})
		{
			AccountConnectPlugin plugin = plugin();
			plugin.applyServerPolicy(policyResponse(v));
			assertFalse("'" + v + "' must not grant store tools", plugin.storeToolsEnabled());
		}
	}

	/** A response with NO header must not change a grant that is already in force. */
	@Test
	public void anAbsentHeaderLeavesTheCurrentGrantAlone() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		plugin.applyServerPolicy(policyResponse("on"));
		assertTrue(plugin.storeToolsEnabled());
		plugin.applyServerPolicy(policyResponse(null));		// header omitted entirely
		assertTrue("an absent header must not silently revoke", plugin.storeToolsEnabled());
	}

	/** Build a 200 carrying (or omitting) the X-Store-Tools header. */
	private static okhttp3.Response policyResponse(String storeTools)
	{
		okhttp3.Response.Builder b = new okhttp3.Response.Builder()
			.request(new okhttp3.Request.Builder().url("https://example.invalid/x").build())
			.protocol(okhttp3.Protocol.HTTP_1_1)
			.code(200)
			.message("OK");
		if (storeTools != null)
		{
			b.header("X-Store-Tools", storeTools);
		}
		return b.build();
	}

	/** A real InfoBoxManager whose add/remove we can observe. */
	private static void installInfoBoxManager(AccountConnectPlugin plugin, java.util.List<Object> boxes) throws Exception
	{
		net.runelite.client.ui.overlay.infobox.InfoBoxManager m =
			org.mockito.Mockito.mock(net.runelite.client.ui.overlay.infobox.InfoBoxManager.class);
		org.mockito.Mockito.doAnswer(inv -> { boxes.add(inv.getArgument(0)); return null; })
			.when(m).addInfoBox(org.mockito.Mockito.any());
		org.mockito.Mockito.doAnswer(inv -> { boxes.remove(inv.getArgument(0)); return null; })
			.when(m).removeInfoBox(org.mockito.Mockito.any());
		inject(plugin, "infoBoxManager", m);
	}

	// ---- helpers ----

	private static AccountConnectPlugin plugin() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return "0123456789abcdef0123456789abcdef";
			}
		});
		return p;
	}

	/** Put the plugin in a shop visit that has sold the given {item, qty} pairs into the shop. */
	private static void seedVisit(AccountConnectPlugin p, int[][] stock) throws Exception
	{
		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>();
		java.util.Map<Integer, Integer> base = new java.util.LinkedHashMap<>();
		for (int[] pair : stock)
		{
			sold.add(pair[0]);
			base.put(pair[0], pair[1]);
		}
		inject(p, "soldThisVisit", sold);
		inject(p, "shopStock", base);
	}

	/** net.runelite.api.Item is FINAL and cannot be mocked — build real ones. */
	private static net.runelite.api.ItemContainer shop(int[][] pairs)
	{
		net.runelite.api.ItemContainer c = org.mockito.Mockito.mock(net.runelite.api.ItemContainer.class);
		net.runelite.api.Item[] items = new net.runelite.api.Item[pairs.length];
		for (int i = 0; i < pairs.length; i++)
		{
			items[i] = new net.runelite.api.Item(pairs[i][0], pairs[i][1]);
		}
		org.mockito.Mockito.when(c.getItems()).thenReturn(items);
		return c;
	}

	private static long anchor(AccountConnectPlugin p) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("storeResetAnchorMs");
		f.setAccessible(true);
		return f.getLong(p);
	}

	private static void inject(AccountConnectPlugin plugin, String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
