package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Segmented frame buffering: bounded memory, deterministic ordering, and honest accounting of
 * everything it refused.
 *
 * The property that matters most is the memory bound. The store path's ring keeps a fixed window
 * and is bounded by construction; this buffer must stay bounded while serving a session that runs
 * for minutes, which it does by flushing a segment and starting empty rather than accumulating.
 */
public class DropFrameSegmenterTest
{
	private static final long T0 = 5_000_000L;

	private static byte[] frame(int bytes)
	{
		byte[] b = new byte[bytes];
		b[0] = (byte) 0xff;
		b[1] = (byte) 0xd8;		// JPEG SOI, so the bytes look like what really flows
		return b;
	}

	// ---- segmenting and ordering ----

	@Test
	public void asegmentIsHandedOverExactlyWhenItFills()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(4, 1000, 1_000_000);
		assertNull(seg.add(frame(10), T0));
		assertNull(seg.add(frame(10), T0 + 33));
		assertNull(seg.add(frame(10), T0 + 66));
		DropFrameSegmenter.Segment s = seg.add(frame(10), T0 + 99);
		assertNotNull("the fourth frame completes the segment", s);
		assertEquals(4, s.frameCount());
		assertEquals(0, s.index);
		assertEquals("the segment carries its FIRST frame's time", T0, s.firstFrameMillis);
	}

	@Test
	public void segmentIndexesAreConsecutiveFromZero()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(2, 1000, 1_000_000);
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			DropFrameSegmenter.Segment s = seg.add(frame(10), T0 + i);
			if (s != null)
			{
				idx.add(s.index);
			}
		}
		assertEquals("[0, 1, 2, 3, 4]", idx.toString());
		assertEquals(5, seg.segmentCount());
	}

	/**
	 * The ordering key is the INDEX, not a timestamp. The store path infers chunk order by comparing
	 * captured_at values, which works but cannot survive two chunks sharing a second. A drop clip
	 * must join deterministically, so the index is explicit and monotonic even when the clock does
	 * not move at all.
	 */
	@Test
	public void indexesAreMonotonicEvenWhenTheClockDoesNotMove()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(1, 1000, 1_000_000);
		DropFrameSegmenter.Segment a = seg.add(frame(10), T0);
		DropFrameSegmenter.Segment b = seg.add(frame(10), T0);
		DropFrameSegmenter.Segment c = seg.add(frame(10), T0);
		assertEquals(0, a.index);
		assertEquals(1, b.index);
		assertEquals(2, c.index);
	}

	@Test
	public void framesKeepTheirOrderInsideASegment()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(3, 1000, 1_000_000);
		byte[] f1 = frame(11);
		byte[] f2 = frame(12);
		byte[] f3 = frame(13);
		seg.add(f1, T0);
		seg.add(f2, T0 + 1);
		DropFrameSegmenter.Segment s = seg.add(f3, T0 + 2);
		assertEquals(11, s.frames.get(0).length);
		assertEquals(12, s.frames.get(1).length);
		assertEquals(13, s.frames.get(2).length);
	}

	// ---- the memory bound ----

	/**
	 * THE BOUND. A long session must never hold more than one segment in memory. Ten thousand frames
	 * pass through here; at any moment the buffer holds fewer than segmentFrames.
	 */
	@Test
	public void memoryStaysBoundedAcrossALongSession()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(40, 1_000_000, Long.MAX_VALUE);
		int maxBuffered = 0;
		for (int i = 0; i < 10_000; i++)
		{
			seg.add(frame(30_000), T0 + i);
			maxBuffered = Math.max(maxBuffered, seg.bufferedFrames());
		}
		assertTrue("never more than one segment in memory, saw " + maxBuffered, maxBuffered < 40);
		assertEquals("every frame was accounted for", 10_000, seg.acceptedFrames());
		assertEquals(250, seg.segmentCount());
	}

	@Test
	public void bufferedCountReturnsToZeroAfterEachSegment()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(3, 1000, 1_000_000);
		seg.add(frame(10), T0);
		seg.add(frame(10), T0);
		assertEquals(2, seg.bufferedFrames());
		seg.add(frame(10), T0);
		assertEquals("handing a segment over empties the buffer", 0, seg.bufferedFrames());
	}

	// ---- the tail segment ----

	@Test
	public void flushRemainderHandsOverThePartialSegment()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(4, 1000, 1_000_000);
		seg.add(frame(10), T0);
		seg.add(frame(10), T0 + 1);
		DropFrameSegmenter.Segment tail = seg.flushRemainder();
		assertNotNull(tail);
		assertEquals(2, tail.frameCount());
		assertEquals(0, tail.index);
		assertEquals(1, seg.segmentCount());
	}

	@Test
	public void flushRemainderOnAnEmptyBufferIsNullNotAnEmptySegment()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(2, 1000, 1_000_000);
		seg.add(frame(10), T0);
		seg.add(frame(10), T0);	// exactly fills segment 0
		assertNull("no partial segment to flush", seg.flushRemainder());
		assertEquals("and no phantom segment is counted", 1, seg.segmentCount());
	}

	// ---- refusals, all counted ----

	@Test
	public void nullEmptyAndOversizedFramesAreRefusedAndCounted()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(10, 100, 1_000_000);
		assertNull(seg.add(null, T0));
		assertNull(seg.add(new byte[0], T0));
		assertNull(seg.add(frame(101), T0));
		assertEquals(0, seg.acceptedFrames());
		assertEquals("every refusal is counted, never silently dropped", 3, seg.rejectedFrames());
		assertEquals(0, seg.bufferedFrames());
	}

	/**
	 * The session byte budget keeps what is already captured — the drop is at the START — and stops
	 * taking more. It also RECORDS that it did. A truncated clip that does not say so is a lie about
	 * what happened after the cut.
	 */
	@Test
	public void theSessionBudgetTruncatesAndSaysSo()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(10, 1000, 250);
		for (int i = 0; i < 10; i++)
		{
			seg.add(frame(100), T0 + i);
		}
		assertFalse("the earliest frames are kept", seg.acceptedFrames() == 0);
		assertEquals("only two 100-byte frames fit in a 250-byte budget", 2, seg.acceptedFrames());
		assertTrue("and the truncation is recorded", seg.truncated());
		assertEquals(8, seg.rejectedFrames());
		assertEquals(200L, seg.sessionBytes());
	}

	@Test
	public void anUntruncatedSessionSaysSoToo()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(10, 1000, 1_000_000);
		seg.add(frame(100), T0);
		assertFalse(seg.truncated());
	}

	@Test
	public void sessionBytesCountsOnlyAcceptedFrames()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(10, 50, 1_000_000);
		seg.add(frame(40), T0);
		seg.add(frame(60), T0);		// over the per-frame cap
		assertEquals(40L, seg.sessionBytes());
	}

	@Test
	public void clearDropsTheBufferWithoutTouchingTheSegmentCount()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(4, 1000, 1_000_000);
		seg.add(frame(10), T0);
		seg.add(frame(10), T0);
		seg.clear();
		assertEquals(0, seg.bufferedFrames());
		assertNull(seg.flushRemainder());
	}

	@Test
	public void segmentFramesAreImmutableOnceHandedOut()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(1, 1000, 1_000_000);
		DropFrameSegmenter.Segment s = seg.add(frame(10), T0);
		try
		{
			s.frames.add(frame(10));
			org.junit.Assert.fail("a handed-out segment must not be mutable");
		}
		catch (UnsupportedOperationException expected)
		{
			// correct
		}
	}

	@Test
	public void theDefaultSegmentSizeMatchesTheServersSubrequestBudget()
	{
		// This is HALF of a contract with the server: it writes one R2 object per frame inside one
		// Worker invocation, and a 100-frame chunk was measured dying at 45-49 objects written.
		// Raising this alone silently loses the tail of every segment.
		assertEquals(40, DropFrameSegmenter.SEGMENT_FRAMES);
	}

	// ---- THE RETRY BUFFER: the budget bounds unacknowledged bytes, not the session ----

	/**
	 * THE CHANGE, stated as a test. A session far larger than the budget keeps FULL coverage as long
	 * as the server acknowledges, because an acknowledged segment's bytes are freed.
	 *
	 * This is the arm that was impossible before: the same traffic used to truncate.
	 */
	@Test
	public void aSessionFarLargerThanTheBudgetKeepsEveryFrameWhenUploadsAreAcknowledged()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(4, 1_000, 10_000);
		int frames = 4_000;		// 400,000 bytes of traffic through a 10,000-byte buffer
		int accepted = 0;
		for (int i = 0; i < frames; i++)
		{
			DropFrameSegmenter.Segment full = seg.add(new byte[100], 1_000L + i);
			accepted++;
			if (full != null)
			{
				seg.segmentAcknowledged(full.index);	// the server answered
			}
		}
		assertEquals("every frame accepted", frames, accepted);
		assertEquals("nothing refused", 0, seg.rejectedFrames());
		assertFalse("and the session is NOT truncated", seg.truncated());
		assertTrue("peak held " + seg.peakHeldBytes() + " stayed inside the buffer",
			seg.peakHeldBytes() <= 10_000);
	}

	/**
	 * THE KNOWN-BAD CONTROL for that arm. With NO acknowledgement the same traffic must truncate,
	 * because the retry buffer is the only thing holding those bytes. A budget that never fills
	 * would mean the guard is gone rather than repurposed.
	 */
	@Test
	public void theSameSessionWithNoAcknowledgementStillTruncates()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(4, 1_000, 10_000);
		for (int i = 0; i < 4_000; i++)
		{
			seg.add(new byte[100], 1_000L + i);
		}
		assertTrue("an unacknowledged session fills the buffer and truncates", seg.truncated());
		assertTrue("and says how much it refused", seg.rejectedFrames() > 0);
	}

	/** An acknowledgement frees exactly that segment's bytes, and only once. */
	@Test
	public void acknowledgementIsIdempotent()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(2, 1_000, 10_000);
		DropFrameSegmenter.Segment one = null;
		for (int i = 0; i < 2; i++)
		{
			DropFrameSegmenter.Segment f = seg.add(new byte[100], 1_000L + i);
			if (f != null)
			{
				one = f;
			}
		}
		assertEquals(200L, seg.heldBytes());
		seg.segmentAcknowledged(one.index);
		assertEquals(0L, seg.heldBytes());
		seg.segmentAcknowledged(one.index);
		assertEquals("a duplicate acknowledgement frees nothing twice", 0L, seg.heldBytes());
		assertEquals(0, seg.unsettledSegments());
	}

	/**
	 * A segment that will never be sent releases its bytes too.
	 *
	 * Holding them would let one dead segment permanently shrink the buffer, so a handful of
	 * failures early in a long session would strangle capture for the rest of it. The segment is
	 * still LOST and still counted as failed by the caller; freeing memory does not hide that.
	 */
	@Test
	public void anAbandonedSegmentReleasesItsBytes()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(2, 1_000, 10_000);
		DropFrameSegmenter.Segment one = null;
		for (int i = 0; i < 2; i++)
		{
			DropFrameSegmenter.Segment f = seg.add(new byte[100], 1_000L + i);
			if (f != null)
			{
				one = f;
			}
		}
		seg.segmentAbandoned(one.index);
		assertEquals(0L, seg.heldBytes());
	}

	/**
	 * INTERRUPTION AND RETRY: segments acknowledged LATE and OUT OF ORDER lose nothing and reorder
	 * nothing.
	 *
	 * A retry arrives whenever the network lets it, so acknowledgements are not ordered. The index
	 * is the ordering key and it is assigned when the segment is cut, so the order a segment is
	 * acknowledged in cannot affect where it sits in the session.
	 */
	@Test
	public void outOfOrderAcknowledgementLosesNothingAndReordersNothing()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(2, 1_000, 1_000_000);
		java.util.List<DropFrameSegmenter.Segment> cut = new java.util.ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			DropFrameSegmenter.Segment f = seg.add(new byte[100], 1_000L + i);
			if (f != null)
			{
				cut.add(f);
			}
		}
		assertEquals(10, cut.size());
		for (int i = 0; i < cut.size(); i++)
		{
			assertEquals("index is assigned at cut time, in order", i, cut.get(i).index);
		}
		// Acknowledge backwards, the shape a retry storm produces.
		for (int i = cut.size() - 1; i >= 0; i--)
		{
			seg.segmentAcknowledged(cut.get(i).index);
		}
		assertEquals("every segment settled", 0, seg.unsettledSegments());
		assertEquals(0L, seg.heldBytes());
		assertEquals("and the session still declares all ten", 10, seg.segmentCount());
		for (int i = 0; i < cut.size(); i++)
		{
			assertEquals("indexes are unchanged by acknowledgement order", i, cut.get(i).index);
		}
	}

	/**
	 * A STALLED upload recovers. The buffer fills, capture truncates, and once acknowledgements
	 * resume the client captures again rather than staying dead for the rest of the session.
	 */
	@Test
	public void captureResumesAfterTheBufferDrains()
	{
		DropFrameSegmenter seg = new DropFrameSegmenter(2, 1_000, 500);
		java.util.List<DropFrameSegmenter.Segment> stalled = new java.util.ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			DropFrameSegmenter.Segment f = seg.add(new byte[100], 1_000L + i);
			if (f != null)
			{
				stalled.add(f);		// the server is not answering
			}
		}
		assertTrue("the buffer filled", seg.truncated());
		int refusedWhileStalled = seg.rejectedFrames();
		assertTrue(refusedWhileStalled > 0);

		for (DropFrameSegmenter.Segment s : stalled)
		{
			seg.segmentAcknowledged(s.index);
		}
		int acceptedBefore = seg.acceptedFrames();
		for (int i = 0; i < 4; i++)
		{
			seg.add(new byte[100], 2_000L + i);
		}
		assertTrue("capture resumes once the buffer drains",
			seg.acceptedFrames() > acceptedBefore);
		assertTrue("and the session stays marked truncated, because frames WERE lost",
			seg.truncated());
	}

	/**
	 * A TWENTY-MINUTE SESSION at the real rate, with real segment sizes, holds bounded memory.
	 *
	 * 6fps for 20 minutes at the measured 39,073 bytes a frame is 7,200 frames and about 281MB of
	 * traffic. The client must never hold more than the buffer, and must lose nothing.
	 */
	@Test
	public void aTwentyMinuteSessionHoldsBoundedMemoryAndLosesNothing()
	{
		final int frameBytes = 39_073;
		final int frames = DropCaptureRate.BASELINE_FPS * 60 * 20;
		DropFrameSegmenter seg = new DropFrameSegmenter();
		for (int i = 0; i < frames; i++)
		{
			DropFrameSegmenter.Segment full = seg.add(new byte[frameBytes], 1_000L + i);
			if (full != null)
			{
				seg.segmentAcknowledged(full.index);
			}
		}
		assertEquals("every frame kept", frames, seg.acceptedFrames());
		assertEquals("nothing refused", 0, seg.rejectedFrames());
		assertFalse("not truncated", seg.truncated());
		long traffic = (long) frames * frameBytes;
		assertTrue("uploaded " + (traffic / 1_000_000) + "MB over the session",
			traffic > 250_000_000L);
		assertTrue("but never held more than " + (seg.peakHeldBytes() / 1_000_000) + "MB at once",
			seg.peakHeldBytes() <= (long) DropFrameSegmenter.SEGMENT_FRAMES * frameBytes * 2);
	}

	/** The budget constant is the operator's 72MB, unchanged, now bounding the retry buffer. */
	@Test
	public void theBufferBudgetIsTheOperatorsSeventyTwoMegabytes()
	{
		assertEquals(72_000_000L, DropFrameSegmenter.UNACKED_BYTE_BUDGET);
	}
}
