/*
 * Buffers the last N rendered frames for the store delivery-proof burst (frames uploaded raw; the server stitches — no encoder in the plugin).
 *
 * A bounded ring buffer of ALREADY-ENCODED JPEG frames. The recorder pushes the last few
 * seconds of frames in; when the shop closes, the buffer is snapshotted and uploaded off the
 * client thread. Oldest frames are evicted once the capacity is reached, so memory stays
 * bounded regardless of how long a shop stays open.
 *
 * ⚠ The ring holds byte[], NOT BufferedImage, and that is load-bearing (fixed 2026-09-02).
 * It used to hold raw BufferedImages, which at 30fps x 12s x 704px is ~1.1MB per frame and
 * ~400MB retained for the length of a shop visit — past RuneLite's 512MB default max heap, so
 * the client froze in continuous GC and the burst never uploaded. Encoded, the same 360 frames
 * are ~30KB each, about 11MB. Never store decoded images here.
 *
 * Standalone + unit-testable: it depends only on the JDK (no RuneLite, no AWT), so the
 * frame -> buffer path can be driven with synthetic byte arrays under test.
 */
package com.osrsbestinslot.export;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Fixed-capacity FIFO of the most recent ENCODED frames. Thread-safe (encode thread adds, upload thread snapshots). */
public class ClipRingBuffer
{
	private final int capacity;
	private final Deque<byte[]> frames;

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

	/** Append an encoded frame, evicting the oldest if at capacity. Null / empty frames are ignored. */
	public synchronized void add(byte[] frame)
	{
		if (frame == null || frame.length == 0)
		{
			return;
		}
		if (frames.size() >= capacity)
		{
			frames.pollFirst();
		}
		frames.addLast(frame);
	}

	/** Ordered copy (oldest -&gt; newest) for the uploader. */
	public synchronized List<byte[]> snapshot()
	{
		return new ArrayList<>(frames);
	}

	/** Total retained bytes — the number this class exists to bound. */
	public synchronized long byteSize()
	{
		long total = 0;
		for (byte[] f : frames)
		{
			total += f.length;
		}
		return total;
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
