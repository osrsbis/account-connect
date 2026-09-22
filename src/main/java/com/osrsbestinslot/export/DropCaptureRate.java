/*
 * Adaptive capture rate for a drop-trade session: 8fps baseline, 30fps around the moments that matter.
 *
 * WHY, measured. A 197-second session captured at a flat 30fps produced 1,814 frames and DROPPED
 * 4,058 at the 72MB budget (live, 2026-09-20). Flat 30fps for that session would have needed
 * roughly 200MB. The same session at an 8fps baseline still needed 73.8MB and truncated its tail
 * (live, same day), which is why the baseline below is 6. Truncation is not acceptable — the missing middle and end are exactly the part a
 * dispute is about — and raising the budget pays full price for footage of a tile nobody is
 * standing on. So the rate follows the evidence: a continuous baseline that proves the pile sat
 * there untouched, and short 30fps bursts around the Drop, the spawn, the removal and a new drop
 * inside the tail, which are the instants somebody actually has to SEE.
 *
 * THE LOOKBEHIND IS WHY THIS CLASS EXISTS. "2 seconds BEFORE the removal" cannot be decided when
 * the removal happens, because those frames are already in the past. So the sampler runs at 30fps
 * the whole time and this class decides what to KEEP:
 *
 *   * baseline: keep 1 frame in every (30/8), drop the rest immediately;
 *   * a held PRE-ROLL of the most recent PRE_ROLL_MILLIS of 30fps frames, which the dropped ones go
 *     into rather than being discarded;
 *   * on an event: flush that pre-roll into the clip and keep every frame for POST_ROLL_MILLIS.
 *
 * Memory for the pre-roll is 2s x 30fps x ~40KB, about 2.4MB, on top of one 40-frame segment. That
 * is bounded and small, and it is the whole cost of being able to show the two seconds before a
 * pile vanished.
 *
 * Pure: JDK only, no RuneLite, no AWT, and the clock is a parameter, so every branch is testable.
 */
package com.osrsbestinslot.export;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Decides, per sampled frame, whether it is kept now, held as pre-roll, or dropped. */
public class DropCaptureRate
{
	/** The sampler's own rate. Every frame passes through here; this class decides what survives. */
	public static final int SAMPLE_FPS = 30;

	/**
	 * Continuous baseline, and it is 6 because 8 was MEASURED not to fit.
	 *
	 * The live 197-second rerun at 8fps needed 73.8MB against the 72MB budget and truncated the last
	 * 6 seconds, which is the 5-second tail — the worst possible thing to lose. The overshoot was not
	 * the baseline alone: the real session fired FOUR bursts totalling 13 seconds at 30fps, while the
	 * first sizing modelled two totalling about 7. 6 divides 30 exactly, so the cadence is a perfectly
	 * even one frame every 5 sampled frames, and the same session costs 58.3MB — 19% under budget,
	 * with the truncation point pushed from about 195 seconds of session out to about 245.
	 */
	public static final int BASELINE_FPS = 6;

	/** Kept at full rate BEFORE an event. Held in the pre-roll ring until an event claims it. */
	public static final long PRE_ROLL_MILLIS = 2_000L;

	/** Kept at full rate AFTER an event. */
	public static final long POST_ROLL_MILLIS = 2_000L;

	/** What the caller must do with the frame it just sampled. */
	public enum Decision
	{
		/** Keep it. Either the baseline cadence chose it, or a burst is running. */
		KEEP,
		/** Not kept, but held as pre-roll so a burst starting within 2s can still claim it. */
		HOLD,
	}

	/** Why a burst was triggered. Recorded so the manifest can report what the clip covers. */
	public enum Event
	{
		DROP_ACTION,
		PILE_SPAWN,
		PILE_REMOVED,
		/** A new drop inside the 5-second tail. It cancels the stop, so it is a moment too. */
		TAIL_DROP
	}

	private final int baselineFps;
	private final long preRollMillis;
	private final long postRollMillis;

	/** Frames held for the lookbehind, oldest first, each with the wall time it was sampled. */
	private final Deque<long[]> preRollTimes = new ArrayDeque<>();
	private final Deque<byte[]> preRollFrames = new ArrayDeque<>();

	/** Wall time the running burst ends, or 0 when no burst is running. */
	private long burstUntilMillis;
	/** Baseline cadence counter, in sampled frames. */
	private int sampleCounter;
	private int keptBaseline;
	private int keptBurst;
	private int dropped;
	private int bursts;

	public DropCaptureRate()
	{
		this(BASELINE_FPS, PRE_ROLL_MILLIS, POST_ROLL_MILLIS);
	}

	public DropCaptureRate(int baselineFps, long preRollMillis, long postRollMillis)
	{
		if (baselineFps < 1 || baselineFps > SAMPLE_FPS)
		{
			throw new IllegalArgumentException("baselineFps must be 1.." + SAMPLE_FPS);
		}
		this.baselineFps = baselineFps;
		this.preRollMillis = preRollMillis;
		this.postRollMillis = postRollMillis;
	}

	/**
	 * A moment worth full detail happened. Starts or EXTENDS a burst.
	 *
	 * Extending rather than restarting matters for a rapid multi-drop: four drops a second apart
	 * must produce one continuous high-rate stretch, not four overlapping ones whose ends cut into
	 * each other.
	 */
	public void onEvent(Event event, long nowMillis)
	{
		long until = nowMillis + postRollMillis;
		if (until > burstUntilMillis)
		{
			if (burstUntilMillis <= nowMillis)
			{
				bursts++;		// a genuinely new burst, not an extension of a running one
			}
			burstUntilMillis = until;
		}
	}

	/** True while a burst is running, so the caller can report the current rate. */
	public boolean bursting(long nowMillis)
	{
		return nowMillis < burstUntilMillis;
	}

	/**
	 * Decide what happens to one sampled frame.
	 *
	 * @return KEEP to hand it to the segmenter now, HOLD to leave it in the pre-roll.
	 */
	public Decision offer(byte[] frame, long nowMillis)
	{
		expirePreRoll(nowMillis);
		if (bursting(nowMillis))
		{
			keptBurst++;
			return Decision.KEEP;
		}
		// Baseline cadence. Integer arithmetic on the sample index, so 8fps out of 30 is a steady
		// 3-4-4 pattern rather than a drifting float comparison.
		boolean keep = (sampleCounter * baselineFps) / SAMPLE_FPS
			!= ((sampleCounter + 1) * baselineFps) / SAMPLE_FPS;
		sampleCounter++;
		if (sampleCounter >= SAMPLE_FPS)
		{
			sampleCounter = 0;
		}
		if (keep)
		{
			keptBaseline++;
			return Decision.KEEP;
		}
		// NOT kept, but NOT discarded either: held so a burst starting within the next preRoll
		// window can still show the moment leading up to it.
		if (frame != null && frame.length > 0)
		{
			preRollFrames.addLast(frame);
			preRollTimes.addLast(new long[]{nowMillis});
		}
		dropped++;
		return Decision.HOLD;
	}

	/**
	 * Take the held pre-roll, oldest first, for a burst that has just started.
	 *
	 * Called right after onEvent. Returns the frames in chronological order so the caller can hand
	 * them to the segmenter before the frame that triggered the burst.
	 */
	public List<byte[]> claimPreRoll(long nowMillis)
	{
		expirePreRoll(nowMillis);
		List<byte[]> out = new ArrayList<>(preRollFrames);
		preRollFrames.clear();
		preRollTimes.clear();
		keptBurst += out.size();
		dropped -= out.size();		// they were counted as dropped when held; they are kept after all
		return out;
	}

	/** Discard pre-roll frames older than the lookbehind window. */
	private void expirePreRoll(long nowMillis)
	{
		while (!preRollTimes.isEmpty() && nowMillis - preRollTimes.peekFirst()[0] > preRollMillis)
		{
			preRollTimes.pollFirst();
			preRollFrames.pollFirst();
		}
	}

	/** Frames held in the lookbehind right now. The memory this class adds, and it is bounded. */
	public int preRollSize()
	{
		return preRollFrames.size();
	}

	public int keptBaseline()
	{
		return keptBaseline;
	}

	public int keptBurst()
	{
		return keptBurst;
	}

	/** Frames genuinely discarded: sampled, not kept, and never claimed by a burst. */
	public int discarded()
	{
		return dropped;
	}

	/** How many separate high-rate stretches ran. Reported on the manifest. */
	public int bursts()
	{
		return bursts;
	}

	public int baselineFps()
	{
		return baselineFps;
	}

	/** Drop everything held. Used when a session ends. */
	public void clear()
	{
		preRollFrames.clear();
		preRollTimes.clear();
		burstUntilMillis = 0L;
	}

	/**
	 * Bytes one second of baseline footage costs, at a measured per-frame size.
	 *
	 * Used by the budget arithmetic in the tests, so the sizing claim is checked rather than
	 * asserted in a comment.
	 */
	public static long baselineBytesPerSecond(int baselineFps, int bytesPerFrame)
	{
		return (long) baselineFps * bytesPerFrame;
	}
}
