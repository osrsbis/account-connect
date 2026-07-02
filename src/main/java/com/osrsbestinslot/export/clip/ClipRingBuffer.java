/*
 * OSRS Best in Slot — store-visit clip capability.
 *
 * A bounded ring buffer of rendered frames. The recorder pushes the last few seconds of
 * frames in; when the shop closes, the buffer is snapshotted and encoded off the client
 * thread. Oldest frames are evicted once the capacity is reached, so memory stays bounded
 * regardless of how long a shop stays open.
 *
 * Standalone + unit-testable: it depends only on java.awt (no RuneLite, no encoder), so the
 * frame -> buffer path can be driven with synthetic BufferedImages under test.
 */
package com.osrsbestinslot.export.clip;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Fixed-capacity FIFO of the most recent frames. Thread-safe (client thread adds, encode thread snapshots). */
public class ClipRingBuffer
{
	private final int capacity;
	private final Deque<BufferedImage> frames;

	/**
	 * @param capacity max frames retained (= fps * seconds). Must be &gt;= 1.
	 */
	public ClipRingBuffer(int capacity)
	{
		if (capacity < 1)
		{
			throw new IllegalArgumentException("capacity must be >= 1");
		}
		this.capacity = capacity;
		this.frames = new ArrayDeque<>(capacity);
	}

	/** Append a frame, evicting the oldest if at capacity. Null frames are ignored. */
	public synchronized void add(BufferedImage frame)
	{
		if (frame == null)
		{
			return;
		}
		if (frames.size() >= capacity)
		{
			frames.pollFirst();
		}
		frames.addLast(frame);
	}

	/** Immutable-ish ordered copy (oldest -> newest) for off-thread encoding. */
	public synchronized List<BufferedImage> snapshot()
	{
		return new ArrayList<>(frames);
	}

	public synchronized int size()
	{
		return frames.size();
	}

	public int capacity()
	{
		return capacity;
	}

	public synchronized void clear()
	{
		frames.clear();
	}
}
