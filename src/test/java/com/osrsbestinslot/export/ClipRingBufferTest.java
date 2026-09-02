package com.osrsbestinslot.export;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClipRingBufferTest
{
	/** One encoded frame, tagged by its first byte so order is checkable. */
	private static byte[] frame(int tag, int size)
	{
		byte[] b = new byte[size];
		b[0] = (byte) tag;
		return b;
	}

	private static byte[] frame(int tag)
	{
		return frame(tag, 4);
	}

	@Test
	public void evictsOldestAtCapacity()
	{
		ClipRingBuffer ring = new ClipRingBuffer(3);
		for (int i = 1; i <= 5; i++) { ring.add(frame(i)); }
		List<byte[]> snap = ring.snapshot();
		assertEquals(3, snap.size());
		assertEquals(3, snap.get(0)[0]);	// oldest surviving = #3
		assertEquals(5, snap.get(2)[0]);	// newest = #5
	}

	@Test
	public void ignoresNullEmptyAndClears()
	{
		ClipRingBuffer ring = new ClipRingBuffer(2);
		ring.add(null);
		ring.add(new byte[0]);		// a failed encode returns empty — must not occupy a slot
		assertEquals(0, ring.size());
		ring.add(frame(1));
		ring.clear();
		assertEquals(0, ring.size());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsZeroCapacity()
	{
		new ClipRingBuffer(0);
	}

	/**
	 * THE REGRESSION THIS CLASS EXISTS FOR (2026-09-02). The ring used to hold decoded BufferedImages:
	 * at 30fps x 12s x 704px that is ~1.1MB per frame and ~400MB retained for a whole shop visit, past
	 * RuneLite's 512MB default heap — the client froze in continuous GC and no burst ever uploaded.
	 * Holding ENCODED bytes, a full ring is ~30KB per frame.
	 *
	 * Falsified 2026-09-02: re-run with jpegBytes = 1_115_136 (one decoded 704x396 RGB frame) and this
	 * arm FAILS, which is the state that shipped in 0.7.5.
	 */
	@Test
	public void retainedBytesAreBoundedByCapacityNotVisitLength()
	{
		final int fps = 30, seconds = 12, jpegBytes = 30_000;
		ClipRingBuffer ring = new ClipRingBuffer(fps * seconds);
		// Ten times more frames than fit — a long shop visit.
		for (int i = 0; i < fps * seconds * 10; i++) { ring.add(frame(i & 0x7F, jpegBytes)); }
		assertEquals(fps * seconds, ring.size());
		assertEquals((long) fps * seconds * jpegBytes, ring.byteSize());
		// ~11MB, an order of magnitude under the ~400MB decoded footprint that froze the client.
		assertTrue("retained " + ring.byteSize() + " bytes", ring.byteSize() < 20_000_000L);
	}

	/** An empty ring must report zero bytes, not throw — the degenerate arm. */
	@Test
	public void emptyRingReportsZeroBytes()
	{
		assertEquals(0L, new ClipRingBuffer(8).byteSize());
	}
}
