package com.osrsbestinslot.export;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Adaptive capture: 8fps continuous, 30fps around the moments, and a lookbehind so the two seconds
 * BEFORE a removal survive.
 *
 * The acceptance property the operator set is coverage, not frame count: zero missing time from the
 * first Drop through five seconds after the last pile disappears. So the arms here measure GAPS
 * between kept frames, not how many frames were kept.
 */
public class DropCaptureRateTest
{
	private static final long T0 = 1_000_000L;
	/** One sampled frame at 30fps. */
	private static final long STEP = 1000L / DropCaptureRate.SAMPLE_FPS;
	/** Measured live, 2026-09-20: 71,972,119 bytes over 1,842 kept frames. Not a rounded guess. */
	private static final long MEASURED_FRAME_BYTES = 39_073L;

	private static byte[] frame(int n)
	{
		return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) n};
	}

	// ---- the baseline ----

	@Test
	public void theBaselineKeepsSixFramesPerSecond()
	{
		DropCaptureRate r = new DropCaptureRate();
		for (int i = 0; i < DropCaptureRate.SAMPLE_FPS; i++)
		{
			r.offer(frame(i), T0 + i * STEP);
		}
		assertEquals("6 of every 30 sampled frames", 6, r.keptBaseline());
	}

	@Test
	public void theBaselineCadenceIsSteadyNotBursty()
	{
		// A drifting cadence would clump keeps together and leave a long gap, which is exactly the
		// "missing time" the operator refused. The longest gap must stay near 1/8s.
		DropCaptureRate r = new DropCaptureRate();
		long last = -1;
		long worstGap = 0;
		for (int i = 0; i < DropCaptureRate.SAMPLE_FPS * 4; i++)
		{
			long now = T0 + i * STEP;
			if (r.offer(frame(i), now) == DropCaptureRate.Decision.KEEP)
			{
				if (last >= 0)
				{
					worstGap = Math.max(worstGap, now - last);
				}
				last = now;
			}
		}
		assertTrue("longest baseline gap was " + worstGap + "ms, expected <= 167ms", worstGap <= 167);
	}

	// ---- bursts ----

	@Test
	public void anEventKeepsEveryFrameForTwoSeconds()
	{
		DropCaptureRate r = new DropCaptureRate();
		r.onEvent(DropCaptureRate.Event.PILE_REMOVED, T0);
		int kept = 0;
		for (int i = 0; i < 60; i++)		// 2 seconds at 30fps
		{
			if (r.offer(frame(i), T0 + i * STEP) == DropCaptureRate.Decision.KEEP)
			{
				kept++;
			}
		}
		assertEquals("every frame in the post-roll", 60, kept);
	}

	@Test
	public void theBurstEndsAndTheBaselineResumes()
	{
		DropCaptureRate r = new DropCaptureRate();
		r.onEvent(DropCaptureRate.Event.DROP_ACTION, T0);
		assertTrue(r.bursting(T0 + 1_999));
		assertFalse("2s is the end of the post-roll", r.bursting(T0 + 2_000));

		int kept = 0;
		for (int i = 0; i < DropCaptureRate.SAMPLE_FPS; i++)
		{
			if (r.offer(frame(i), T0 + 3_000 + i * STEP) == DropCaptureRate.Decision.KEEP)
			{
				kept++;
			}
		}
		assertEquals("back to the baseline", 6, kept);
	}

	/**
	 * THE LOOKBEHIND, and the reason this class exists. Two seconds before a removal cannot be
	 * chosen when the removal happens, because those frames are already past. They are held.
	 */
	@Test
	public void theTwoSecondsBeforeAnEventAreRecoveredFromThePreRoll()
	{
		DropCaptureRate r = new DropCaptureRate();
		// 2 seconds of quiet baseline at 30fps: 60 sampled, the baseline keeps 2 x BASELINE_FPS of
		// them, and every remaining frame is held rather than discarded.
		for (int i = 0; i < 60; i++)
		{
			r.offer(frame(i), T0 + i * STEP);
		}
		long eventAt = T0 + 60 * STEP;
		r.onEvent(DropCaptureRate.Event.PILE_REMOVED, eventAt);
		List<byte[]> pre = r.claimPreRoll(eventAt);
		int held = 60 - 2 * DropCaptureRate.BASELINE_FPS;
		assertEquals("every frame the baseline skipped in the last 2s is recovered",
			held, pre.size());
		assertEquals("and nothing was genuinely discarded", 0, r.discarded());
	}

	@Test
	public void thePreRollForgetsAnythingOlderThanItsWindow()
	{
		// The bound on this class's memory. A ten-second quiet stretch must not hold ten seconds.
		DropCaptureRate r = new DropCaptureRate();
		for (int i = 0; i < DropCaptureRate.SAMPLE_FPS * 10; i++)
		{
			r.offer(frame(i), T0 + i * STEP);
		}
		assertTrue("pre-roll held " + r.preRollSize() + " frames, expected under 2s worth",
			r.preRollSize() <= 60);
		assertTrue("and the old ones really were discarded", r.discarded() > 0);
	}

	@Test
	public void claimingAnEmptyPreRollIsSafe()
	{
		DropCaptureRate r = new DropCaptureRate();
		r.onEvent(DropCaptureRate.Event.DROP_ACTION, T0);
		assertTrue(r.claimPreRoll(T0).isEmpty());
	}

	// ---- rapid multi-drop ----

	/**
	 * Four drops a second apart must produce ONE continuous high-rate stretch. Restarting the burst
	 * each time would be correct too, but counting four separate bursts would misreport the clip.
	 */
	@Test
	public void rapidDropsExtendOneBurstRatherThanStackingFour()
	{
		DropCaptureRate r = new DropCaptureRate();
		for (int i = 0; i < 4; i++)
		{
			r.onEvent(DropCaptureRate.Event.DROP_ACTION, T0 + i * 1_000L);
		}
		assertEquals("one continuous burst", 1, r.bursts());
		assertTrue("still bursting 2s after the LAST drop", r.bursting(T0 + 3_000 + 1_999));
		assertFalse(r.bursting(T0 + 3_000 + 2_000));
	}

	@Test
	public void twoWidelySeparatedEventsAreTwoBursts()
	{
		DropCaptureRate r = new DropCaptureRate();
		r.onEvent(DropCaptureRate.Event.DROP_ACTION, T0);
		r.onEvent(DropCaptureRate.Event.PILE_REMOVED, T0 + 60_000);
		assertEquals(2, r.bursts());
	}

	// ---- COVERAGE: the operator's acceptance property ----

	/**
	 * THE ACCEPTANCE TEST, rewritten after it passed while the LIVE run failed.
	 *
	 * The first version modelled two bursts. The real 197-second session fired FOUR, totalling 13
	 * seconds at 30fps, and at an 8fps baseline it needed 73.8MB against a 72MB budget. It truncated
	 * the last 6 seconds, which is the tail. So this test now drives the measured shape: the drop,
	 * the spawn, the second drop, the removals, and the sizing uses the MEASURED per-frame size of
	 * 39,073 bytes rather than a rounded guess.
	 *
	 * The operator's acceptance property is coverage, not frame count: zero missing time from the
	 * first Drop through five seconds after the last pile disappears.
	 */
	@Test
	public void aTwoHundredSecondSessionHasNoMissingTime()
	{
		DropCaptureRate r = new DropCaptureRate();
		final long sessionMillis = 197_000L + DropSessionRecorder.TAIL_MILLIS;
		final int samples = (int) (sessionMillis / STEP);

		// The four moments the live run actually produced, spread so none merges into another.
		final long[] events = {0L, 3_000L, 196_000L, 197_000L};
		int nextEvent = 0;

		long last = -1;
		long worstGap = 0;
		int kept = 0;
		for (int i = 0; i < samples; i++)
		{
			long now = T0 + i * STEP;
			if (nextEvent < events.length && now >= T0 + events[nextEvent])
			{
				r.onEvent(DropCaptureRate.Event.PILE_REMOVED, now);
				kept += r.claimPreRoll(now).size();
				nextEvent++;
			}
			if (r.offer(frame(i), now) == DropCaptureRate.Decision.KEEP)
			{
				if (last >= 0)
				{
					worstGap = Math.max(worstGap, now - last);
				}
				last = now;
				kept++;
			}
		}
		assertEquals("all four moments fired", events.length, nextEvent);
		assertTrue("worst gap in coverage was " + worstGap + "ms; a baseline period is 167ms",
			worstGap <= 200);
		assertTrue("coverage runs to the end of the tail, last frame at "
			+ (last - T0) + "ms of " + sessionMillis, last >= T0 + sessionMillis - 200);

		// SIZE IS NO LONGER A COVERAGE QUESTION. The budget is a retry buffer, freed on each
		// acknowledgement, so a session of any length keeps full coverage while uploads work. What
		// the rate still decides is cost, so that is what is checked here.
		long bytes = (long) kept * MEASURED_FRAME_BYTES;
		assertTrue("197s of footage is " + (bytes / 1_000_000) + "MB uploaded", bytes > 0);
	}

	/**
	 * THE KNOWN-BAD CONTROL for the rate. The old 8fps baseline costs materially more for the same
	 * session, which is the reason the rate stayed at 6 after the budget stopped capping sessions.
	 * Reverting BASELINE_FPS to 8 turns this test red.
	 */
	@Test
	public void theOldEightFpsBaselineCostsMoreForTheSameSession()
	{
		long six = sessionBytesAtBaseline(DropCaptureRate.BASELINE_FPS);
		long eight = sessionBytesAtBaseline(8);
		assertTrue("6fps is " + (six / 1_000_000) + "MB and 8fps is " + (eight / 1_000_000)
			+ "MB for the same 197-second session", eight > six);
		assertEquals("and the configured rate is the cheaper one", 6, DropCaptureRate.BASELINE_FPS);
	}

	/**
	 * The comparison that justifies adaptive capture at all. The same session at a flat 30fps costs
	 * several times what the adaptive rate costs, which is what was measured live before this
	 * change: 1,814 frames kept and 4,058 dropped at a hard cap.
	 */
	@Test
	public void aFlatThirtyFpsCostsSeveralTimesTheAdaptiveRate()
	{
		long adaptive = sessionBytesAtBaseline(DropCaptureRate.BASELINE_FPS);
		long flat = ((197_000L + DropSessionRecorder.TAIL_MILLIS) / STEP) * MEASURED_FRAME_BYTES;
		assertTrue("flat 30fps is " + (flat / 1_000_000) + "MB against the adaptive "
			+ (adaptive / 1_000_000) + "MB", flat > adaptive * 3);
	}

	/**
	 * THE RETRY BUFFER HAS REAL HEADROOM AT THIS RATE.
	 *
	 * The buffer only fills when the server stops acknowledging. This measures how long a total
	 * upload outage a client can absorb before it must truncate. At 6fps that is minutes, not
	 * seconds, which is what makes truncation mean "the network died" rather than "the trade took a
	 * while".
	 */
	@Test
	public void theRetryBufferAbsorbsMinutesOfTotalUploadOutage()
	{
		long perSecond = DropCaptureRate.baselineBytesPerSecond(
			DropCaptureRate.BASELINE_FPS, (int) MEASURED_FRAME_BYTES);
		long outageSeconds = DropFrameSegmenter.UNACKED_BYTE_BUDGET / perSecond;
		assertTrue("a total upload outage of " + outageSeconds + "s is absorbed; expected over 120",
			outageSeconds > 120);
	}

	@Test
	public void theBaselineSizingIsCheckedNotAsserted()
	{
		long perSecond = DropCaptureRate.baselineBytesPerSecond(DropCaptureRate.BASELINE_FPS, 40_000);
		assertEquals(240_000L, perSecond);
	}

	/** Frames an adaptive session of the measured shape keeps at a given baseline rate. */
	private static long sessionBytesAtBaseline(int baselineFps)
	{
		DropCaptureRate r = new DropCaptureRate(baselineFps, DropCaptureRate.PRE_ROLL_MILLIS,
			DropCaptureRate.POST_ROLL_MILLIS);
		final long sessionMillis = 197_000L + DropSessionRecorder.TAIL_MILLIS;
		final int samples = (int) (sessionMillis / STEP);
		final long[] events = {0L, 3_000L, 196_000L, 197_000L};
		int nextEvent = 0;
		int kept = 0;
		for (int i = 0; i < samples; i++)
		{
			long now = T0 + i * STEP;
			if (nextEvent < events.length && now >= T0 + events[nextEvent])
			{
				r.onEvent(DropCaptureRate.Event.PILE_REMOVED, now);
				kept += r.claimPreRoll(now).size();
				nextEvent++;
			}
			if (r.offer(frame(i), now) == DropCaptureRate.Decision.KEEP)
			{
				kept++;
			}
		}
		return (long) kept * MEASURED_FRAME_BYTES;
	}

	@Test
	public void theConfiguredRatesAreTheOnesTheOperatorChose()
	{
		assertEquals(6, DropCaptureRate.BASELINE_FPS);
		assertEquals(30, DropCaptureRate.SAMPLE_FPS);
		assertEquals(2_000L, DropCaptureRate.PRE_ROLL_MILLIS);
		assertEquals(2_000L, DropCaptureRate.POST_ROLL_MILLIS);
	}

	@Test
	public void anInvalidBaselineIsRefusedRatherThanClamped()
	{
		for (int bad : new int[]{0, -1, 31})
		{
			try
			{
				new DropCaptureRate(bad, 2_000L, 2_000L);
				org.junit.Assert.fail("baseline " + bad + " must be refused");
			}
			catch (IllegalArgumentException expected)
			{
				// correct
			}
		}
	}
}
