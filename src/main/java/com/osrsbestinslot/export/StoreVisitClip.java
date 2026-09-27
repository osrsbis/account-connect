/*
 * Which frames of a general-store visit are uploaded: the visit start, every buy / sell / taken
 * moment, and a low-rate baseline between them, inside one fixed per-visit frame budget.
 *
 * WHY, measured. The store path used to keep only the NEWEST 12 seconds (360 frames at 30fps) in a
 * ring. A delivery visit is longer than that. Arengees, 2026-09-25: sells from 16:37:59 to 16:39:51,
 * uploaded frames 16:39:55 to 16:40:06, every frame static. The clip was intact and showed no sale.
 * The sales are the evidence, so the budget now follows the sales instead of the clock.
 *
 * WHAT IS KEPT
 *   * the first START_MILLIS of the visit at the full sample rate;
 *   * for every moment (a buy/sell click, a store_taken): PRE_ROLL_MILLIS before it and
 *     POST_ROLL_MILLIS after it at the full sample rate. The frames before a click cannot be chosen
 *     after it, so frames that are not kept are HELD in a short pre-roll until they expire;
 *   * one frame every BASELINE_PERIOD_MILLIS in between, so the gaps are not blank.
 *
 * THE BUDGET. maxFrames (360) and maxBytes (16 MiB since PIO-014; the ring had 12MB) for the WHOLE
 * visit. That keeps a visit at the same number of 40-frame chunks, so it spends the same share of
 * the server's per-token burst rate limit as before. When a new frame would exceed the budget, one
 * frame is evicted from the LARGEST group (start, each moment, baseline). Groups therefore shrink
 * toward equal size, and no moment can be pushed out by later ones. Inside a group the frame
 * farthest from its moment goes first, until only the core span around the moment is left; after
 * that the core is thinned evenly. So a visit with many sales still shows every click and the
 * inventory change after it, at a lower rate, rather than losing whole sales.
 *
 * Why not DropCaptureRate: it drops the capture time of pre-roll frames (the upload needs it for
 * each chunk's captured_at) and it has no whole-visit budget, because a drop session streams its
 * segments. Changing it would touch the drop-proof path, which is reviewed separately.
 *
 * Pure: JDK only, the clock is a parameter, thread-safe (encode thread offers, client thread marks
 * moments, the uploader snapshots).
 */
package com.osrsbestinslot.export;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StoreVisitClip
{
	/** Full-rate footage at the start of the visit: the approach to the shop. */
	public static final long START_MILLIS = 2_000L;
	/** Full-rate footage kept BEFORE a moment. */
	public static final long PRE_ROLL_MILLIS = 2_000L;
	/** Full-rate footage kept AFTER a moment. A click resolves on the next game tick (0.6s). */
	public static final long POST_ROLL_MILLIS = 3_000L;
	/** One baseline frame per this many milliseconds between moments. */
	public static final long BASELINE_PERIOD_MILLIS = 1_000L;
	/**
	 * The core span of a moment: from just before the click to two game ticks after it, which is
	 * where the item leaves the inventory. Edges outside it are evicted first; the core is only
	 * ever thinned, never cut away.
	 */
	public static final long CORE_BEFORE_MILLIS = 500L;
	public static final long CORE_AFTER_MILLIS = 1_200L;

	/** Group id of baseline frames. The start is group 0; moments are 1, 2, 3 ... */
	static final int BASELINE = -1;
	static final int START = 0;

	private static final class Frame
	{
		final byte[] jpeg;
		final long at;
		final int group;

		Frame(byte[] jpeg, long at, int group)
		{
			this.jpeg = jpeg;
			this.at = at;
			this.group = group;
		}
	}

	/** A snapshot for the uploader: frames and their capture times, oldest first. */
	public static final class Snapshot
	{
		public final List<byte[]> frames;
		public final List<Long> capturedAtMillis;

		Snapshot(List<byte[]> frames, List<Long> capturedAtMillis)
		{
			this.frames = frames;
			this.capturedAtMillis = capturedAtMillis;
		}
	}

	private final int maxFrames;
	private final long maxBytes;
	private final int maxFrameBytes;

	private final List<Frame> kept = new ArrayList<>();
	private final Deque<Frame> preRoll = new ArrayDeque<>();
	/** Anchor time per group id: index 0 is the visit start, then one entry per moment. */
	private final List<Long> anchors = new ArrayList<>();
	private long keptBytes;
	private long lastBaselineMillis = Long.MIN_VALUE;

	public StoreVisitClip(int maxFrames, long maxBytes, int maxFrameBytes)
	{
		if (maxFrames < 1 || maxBytes < 1 || maxFrameBytes < 1)
		{
			throw new IllegalArgumentException("budget must be positive");
		}
		this.maxFrames = maxFrames;
		this.maxBytes = maxBytes;
		this.maxFrameBytes = maxFrameBytes;
	}

	/** One sampled, encoded frame. The visit starts at the first frame offered. */
	public synchronized void offer(byte[] jpeg, long atMillis)
	{
		if (jpeg == null || jpeg.length == 0 || jpeg.length > maxFrameBytes)
		{
			return;
		}
		if (anchors.isEmpty())
		{
			anchors.add(atMillis);
		}
		expirePreRoll(atMillis);
		int latest = anchors.size() - 1;
		long latestAt = anchors.get(latest);
		int group;
		if (latest > START && atMillis >= latestAt - PRE_ROLL_MILLIS && atMillis <= latestAt + POST_ROLL_MILLIS)
		{
			group = latest;
		}
		else if (atMillis - anchors.get(START) < START_MILLIS)
		{
			group = START;
		}
		else if (lastBaselineMillis == Long.MIN_VALUE || atMillis - lastBaselineMillis >= BASELINE_PERIOD_MILLIS)
		{
			group = BASELINE;
			lastBaselineMillis = atMillis;
		}
		else
		{
			preRoll.addLast(new Frame(jpeg, atMillis, BASELINE));
			return;
		}
		keep(new Frame(jpeg, atMillis, group));
	}

	/**
	 * A buy, sell or taken moment happened now. Claims the held pre-roll and opens a full-rate window.
	 * A moment before the first frame is anchored at its own time and still gets its window.
	 */
	public synchronized void onMoment(long atMillis)
	{
		if (anchors.isEmpty())
		{
			anchors.add(atMillis);
		}
		anchors.add(atMillis);
		int group = anchors.size() - 1;
		expirePreRoll(atMillis);
		List<Frame> held = new ArrayList<>(preRoll);
		preRoll.clear();
		for (Frame f : held)
		{
			if (f.at >= atMillis - PRE_ROLL_MILLIS)
			{
				keep(new Frame(f.jpeg, f.at, group));
			}
		}
	}

	/** Kept frames and their capture times, oldest first. */
	public synchronized Snapshot snapshot()
	{
		List<Frame> sorted = new ArrayList<>(kept);
		sorted.sort((a, b) -> Long.compare(a.at, b.at));
		List<byte[]> frames = new ArrayList<>(sorted.size());
		List<Long> times = new ArrayList<>(sorted.size());
		for (Frame f : sorted)
		{
			frames.add(f.jpeg);
			times.add(f.at);
		}
		return new Snapshot(frames, times);
	}

	public synchronized int size()
	{
		return kept.size();
	}

	public synchronized long byteSize()
	{
		return keptBytes;
	}

	/** Frames held for the lookbehind. Bounded by PRE_ROLL_MILLIS at the sample rate. */
	public synchronized int preRollSize()
	{
		return preRoll.size();
	}

	/** Number of buy / sell / taken moments marked this visit. */
	public synchronized int momentCount()
	{
		return Math.max(0, anchors.size() - 1);
	}

	public synchronized void clear()
	{
		kept.clear();
		preRoll.clear();
		anchors.clear();
		keptBytes = 0;
		lastBaselineMillis = Long.MIN_VALUE;
	}

	private void expirePreRoll(long nowMillis)
	{
		while (!preRoll.isEmpty() && nowMillis - preRoll.peekFirst().at > PRE_ROLL_MILLIS)
		{
			preRoll.pollFirst();
		}
	}

	private void keep(Frame f)
	{
		kept.add(f);
		keptBytes += f.jpeg.length;
		while (!kept.isEmpty() && (kept.size() > maxFrames || keptBytes > maxBytes))
		{
			Frame victim = kept.remove(victimIndex());
			keptBytes -= victim.jpeg.length;
		}
	}

	/** The frame to evict: from the largest group, its farthest edge, else its densest spot. */
	private int victimIndex()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		for (Frame f : kept)
		{
			counts.merge(f.group, 1, Integer::sum);
		}
		int group = BASELINE;
		int best = -1;
		for (Map.Entry<Integer, Integer> e : counts.entrySet())
		{
			int g = e.getKey();
			int n = e.getValue();
			// Ties go to the baseline, then to the OLDEST moment: a moment still filling keeps its frames.
			if (n > best || (n == best && group != BASELINE && (g == BASELINE || g < group)))
			{
				best = n;
				group = g;
			}
		}
		if (group != BASELINE)
		{
			long anchor = anchors.get(group);
			int far = -1;
			long farDist = -1;
			for (int i = 0; i < kept.size(); i++)
			{
				Frame f = kept.get(i);
				if (f.group != group)
				{
					continue;
				}
				// Before the anchor counts double: the outcome after a click matters more than the approach.
				long dist = f.at < anchor ? 2 * (anchor - f.at) : f.at - anchor;
				boolean inCore = f.at >= anchor - CORE_BEFORE_MILLIS && f.at <= anchor + CORE_AFTER_MILLIS;
				if (!inCore && dist > farDist)
				{
					farDist = dist;
					far = i;
				}
			}
			if (far >= 0)
			{
				return far;
			}
		}
		return densestIndex(group);
	}

	/** The frame of this group closest in time to the previous one: removing it thins evenly. */
	private int densestIndex(int group)
	{
		int prev = -1;
		int pick = -1;
		long pickGap = Long.MAX_VALUE;
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < kept.size(); i++)
		{
			if (kept.get(i).group == group)
			{
				idx.add(i);
			}
		}
		idx.sort((a, b) -> Long.compare(kept.get(a).at, kept.get(b).at));
		for (int i : idx)
		{
			if (prev >= 0)
			{
				long gap = kept.get(i).at - kept.get(prev).at;
				if (gap < pickGap)
				{
					pickGap = gap;
					pick = i;
				}
			}
			prev = i;
		}
		return pick >= 0 ? pick : idx.get(0);
	}
}
