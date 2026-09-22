package com.osrsbestinslot.export;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The drop-trade session state machine: when recording starts, when it may stop, and which drops
 * belong to one session.
 *
 * The clock is a parameter everywhere, so the races that only show at real timing — a second drop
 * landing inside the 5-second tail, a pile spawning after the action — are driven exactly rather
 * than slept for.
 */
public class DropSessionRecorderTest
{
	private static final long T0 = 1_000_000L;

	private static String key(int item, int seq)
	{
		return DropSessionRecorder.pileKey(item, 3200, 3200, 0, seq);
	}

	// ---- start ----

	@Test
	public void theFirstDropStartsASessionAndNumbersFromOne()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		assertFalse("idle before any drop", r.active());
		assertEquals(1, r.onDropAction("sess-a", T0));
		assertTrue(r.active());
		assertEquals("sess-a", r.sessionId());
		assertEquals(T0, r.startedAtMillis());
	}

	@Test
	public void aSecondDropJoinsTheSameSessionAndNeverStartsANewOne()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("sess-a", T0);
		int seq = r.onDropAction("sess-b-MUST-BE-IGNORED", T0 + 400);
		assertEquals("sequence continues within the session", 2, seq);
		assertEquals("the id of a running session is never replaced", "sess-a", r.sessionId());
		assertEquals(2, r.dropCount());
	}

	/** Rapid multi-drop: eight drops in one tick are ONE session with eight sequence numbers. */
	@Test
	public void rapidMultiDropProducesOneSessionAndUniqueSequences()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		java.util.Set<Integer> seqs = new java.util.LinkedHashSet<>();
		for (int i = 0; i < 8; i++)
		{
			seqs.add(r.onDropAction("sess-" + i, T0));
		}
		assertEquals("eight distinct sequence numbers", 8, seqs.size());
		assertEquals("one session", "sess-0", r.sessionId());
		assertEquals(8, r.dropCount());
	}

	// ---- the tail ----

	@Test
	public void theTailArmsOnlyWhenTheLastPileGoes()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(995, 1));
		r.onDropAction("s", T0);
		r.pileActive(key(995, 2));

		r.pileRemoved(key(995, 1), T0 + 1_000);
		assertFalse("one pile still on the ground — no stop yet", r.stopPending());
		assertFalse(r.shouldStop(T0 + 100_000));

		r.pileRemoved(key(995, 2), T0 + 2_000);
		assertTrue("last pile gone — tail armed", r.stopPending());
		assertFalse("not yet elapsed", r.shouldStop(T0 + 2_000 + 4_999));
		assertTrue("exactly 5s later", r.shouldStop(T0 + 2_000 + 5_000));
	}

	@Test
	public void theTailIsExactlyFiveSeconds()
	{
		assertEquals(5_000L, DropSessionRecorder.TAIL_MILLIS);
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(1, 1));
		r.pileRemoved(key(1, 1), T0);
		assertEquals(5_000L, r.tailRemainingMillis(T0));
		assertEquals(1L, r.tailRemainingMillis(T0 + 4_999));
		assertEquals(0L, r.tailRemainingMillis(T0 + 5_000));
		assertEquals("an overdue tail reads as due now, never negative", 0L, r.tailRemainingMillis(T0 + 9_999));
	}

	/**
	 * THE REQUIREMENT THAT IS EASIEST TO GET WRONG: a drop inside the tail cancels the stop and
	 * continues the SAME session. A naive implementation starts a second recording here, and the two
	 * then overlap.
	 */
	@Test
	public void aDropInsideTheTailCancelsTheStopAndKeepsTheSession()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(995, 1));
		r.pileRemoved(key(995, 1), T0 + 1_000);
		assertTrue(r.stopPending());

		// 3 seconds into the 5-second tail.
		int seq = r.onDropAction("SHOULD-NOT-BE-USED", T0 + 4_000);
		assertFalse("the stop is cancelled", r.stopPending());
		assertEquals("same session", "s", r.sessionId());
		assertEquals("sequence continues", 2, seq);
		assertFalse("and the old deadline must not fire", r.shouldStop(T0 + 6_000));
	}

	@Test
	public void aPileSpawningAfterTheActionAlsoCancelsAnArmedStop()
	{
		// A pile can spawn a tick or two after the click, so a fast session could arm its stop
		// between the action and the spawn. pileActive must clear it.
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(1, 1));
		r.pileRemoved(key(1, 1), T0 + 100);
		assertTrue(r.stopPending());
		r.pileActive(key(1, 2));
		assertFalse(r.stopPending());
	}

	// ---- overlapping piles and repeated items ----

	/**
	 * Two piles of the SAME item on the SAME tile must stay distinguishable. This is the ordinary
	 * shape of a drop trade, not an edge case: a key built from item and tile alone collapses them,
	 * the second removal then finds nothing, and the session stops while a pile is still live.
	 */
	@Test
	public void twoPilesOfTheSameItemOnTheSameTileAreDistinct()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		int a = r.onDropAction("s", T0);
		r.pileActive(key(995, a));
		int b = r.onDropAction("s", T0);
		r.pileActive(key(995, b));
		assertEquals("two live piles", 2, r.activePileCount());

		r.pileRemoved(key(995, a), T0 + 500);
		assertEquals(1, r.activePileCount());
		assertFalse("the other pile is still live", r.stopPending());
		r.pileRemoved(key(995, b), T0 + 600);
		assertTrue(r.stopPending());
	}

	@Test
	public void anUnknownPileNeverArmsAStop()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(995, 1));
		r.pileRemoved(key(4151, 9), T0 + 100);	// never ours
		assertFalse("an unknown pile must not arm the tail", r.stopPending());
		assertEquals(1, r.activePileCount());
	}

	@Test
	public void removingTheSamePileTwiceDoesNotDoubleCount()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(995, 1));
		r.pileActive(key(995, 2));
		r.pileRemoved(key(995, 1), T0 + 100);
		r.pileRemoved(key(995, 1), T0 + 200);	// duplicate
		assertFalse("a duplicate removal must not empty the set", r.stopPending());
		assertEquals(1, r.activePileCount());
	}

	// ---- finishing ----

	@Test
	public void aCleanSessionFinishesComplete()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(1, 1));
		r.pileRemoved(key(1, 1), T0 + 100);
		assertTrue(r.shouldStop(T0 + 5_100));
		assertEquals(DropSessionRecorder.Outcome.COMPLETE, r.finish());
		assertFalse("finish clears the session", r.active());
		assertNull(r.sessionId());
	}

	@Test
	public void anInterruptFinishesInterrupted()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(1, 1));
		r.interrupt();
		assertTrue("an interrupt is due immediately", r.shouldStop(T0));
		assertEquals(DropSessionRecorder.Outcome.INTERRUPTED, r.finish());
	}

	/**
	 * A session still holding live piles is INTERRUPTED even with no explicit interrupt. The only
	 * way to reach finish() with piles outstanding is an abnormal end, and calling that COMPLETE
	 * would label a partial clip as whole.
	 */
	@Test
	public void finishingWithLivePilesIsInterrupted()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(1, 1));
		assertEquals(DropSessionRecorder.Outcome.INTERRUPTED, r.finish());
	}

	@Test
	public void finishIsIdempotentAndASecondSessionStartsClean()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.finish();
		assertEquals("a second finish on an idle recorder changes nothing",
			DropSessionRecorder.Outcome.COMPLETE, r.finish());

		assertEquals("a new session numbers from 1 again", 1, r.onDropAction("s2", T0 + 60_000));
		assertEquals("s2", r.sessionId());
		assertEquals(1, r.dropCount());
	}

	@Test
	public void callsOnAnIdleRecorderAreSafeNoOps()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.pileActive(key(1, 1));
		r.pileRemoved(key(1, 1), T0);
		r.interrupt();
		assertFalse("none of those may start a session", r.active());
		assertFalse(r.stopPending());
		assertFalse(r.shouldStop(T0 + 1_000_000));
	}

	// ---- long sessions ----

	/**
	 * A drop trade where the customer arrives four minutes later is ONE session, and the recorder
	 * never stops in the middle of it. This is the case the 12-second ring could not serve.
	 */
	@Test
	public void aFourMinuteSessionNeverStopsWhileAPileIsLive()
	{
		DropSessionRecorder r = new DropSessionRecorder();
		r.onDropAction("s", T0);
		r.pileActive(key(995, 1));
		for (long t = T0; t <= T0 + 240_000; t += 5_000)
		{
			assertFalse("must not stop while the pile is on the ground at t=" + (t - T0), r.shouldStop(t));
		}
		r.pileRemoved(key(995, 1), T0 + 240_000);
		assertTrue(r.shouldStop(T0 + 245_000));
	}

	@Test
	public void pileKeyIncludesEveryDiscriminator()
	{
		String a = DropSessionRecorder.pileKey(995, 3200, 3200, 0, 1);
		assertEquals("995:3200:3200:0:1", a);
		assertNotNull(a);
		// Each field alone must change the key.
		assertFalse(a.equals(DropSessionRecorder.pileKey(996, 3200, 3200, 0, 1)));
		assertFalse(a.equals(DropSessionRecorder.pileKey(995, 3201, 3200, 0, 1)));
		assertFalse(a.equals(DropSessionRecorder.pileKey(995, 3200, 3201, 0, 1)));
		assertFalse(a.equals(DropSessionRecorder.pileKey(995, 3200, 3200, 1, 1)));
		assertFalse(a.equals(DropSessionRecorder.pileKey(995, 3200, 3200, 0, 2)));
	}
}
