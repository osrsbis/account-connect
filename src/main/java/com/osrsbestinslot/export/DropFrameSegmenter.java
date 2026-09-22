/*
 * Segmented frame buffering for a drop-trade session — the replacement for the store clip's ring.
 *
 * WHY NOT THE RING. The store path keeps the last 12 seconds in a ClipRingBuffer and uploads it at
 * the end. That is right for a shop visit, whose evidence is the transaction and the seconds around
 * it, and wrong for a drop trade: the DROP is at the START and the customer may arrive minutes
 * later, so a ring loses the very thing the clip exists to show. Requirement: keep the start.
 *
 * WHY MEMORY IS STILL BOUNDED. Nothing accumulates for the length of the session. Frames fill one
 * SEGMENT_FRAMES-sized segment, the segment is handed to the uploader, and the buffer starts empty
 * again. The uploader holds each segment only until the server acknowledges it, and the segmenter
 * frees those bytes when it does. Retained bytes are therefore one segment plus whatever is still
 * unacknowledged, not the whole session — a 20-minute trade costs the same heap as a 10-second one
 * while uploads keep up.
 *
 * WHY SEGMENTS CARRY AN INDEX. The store path reassembles chunks by comparing `captured_at`
 * timestamps, which works but infers the order. A drop clip must join deterministically to its
 * session, so each segment carries its own 0-based index and the session id. Two segments uploaded
 * out of order, or one retried after a failure, still reassemble into exactly one ordering.
 *
 * Standalone and unit-testable: JDK only, no RuneLite and no AWT, so the whole path can be driven
 * with synthetic byte arrays.
 */
package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Accumulates encoded frames and yields bounded, ordered segments ready for upload. */
public class DropFrameSegmenter
{
	/**
	 * Frames per segment. MUST stay at or below the store path's CLIP_CHUNK_FRAMES, because one
	 * segment is one POST and the server writes one R2 object per frame inside ONE Worker
	 * invocation. A Worker has a hard subrequest ceiling, and a 100-frame chunk was measured dying
	 * at 45-49 frames written, leaving orphaned frames and no manifest. 40 keeps it inside budget.
	 */
	public static final int SEGMENT_FRAMES = 40;

	/** A frame larger than this failed to encode sanely. Mirrors the store path's per-frame cap. */
	public static final int MAX_FRAME_BYTES = 1_000_000;

	/**
	 * Maximum bytes held LOCALLY at once: the segment being filled, plus every segment handed to the
	 * uploader that the server has not acknowledged yet.
	 *
	 * THIS IS NOT A SESSION CAP, and the difference is the whole point. It used to be a lifetime
	 * total, which made a long session truncate however well the network was working — measured
	 * live, a 196-second session at an 8fps baseline hit 72MB and lost its own tail. Segments upload
	 * continuously while the session runs, and an acknowledged segment's bytes are freed, so a
	 * session of any length costs the same memory as a short one as long as uploads keep up.
	 *
	 * What it still bounds is the RETRY BUFFER. If the server stops acknowledging, unacknowledged
	 * segments accumulate here and nothing else frees them. At that point the client refuses new
	 * frames and records the session as truncated, which is the visible failure: a clip that is
	 * short because the network died says so, rather than quietly pretending to be complete.
	 */
	public static final long UNACKED_BYTE_BUDGET = 72_000_000L;

	private final int segmentFrames;
	private final int maxFrameBytes;
	private final long unackedByteBudget;

	private final List<byte[]> current = new ArrayList<>();
	private int currentBytes;
	private int nextSegmentIndex;
	private long sessionBytes;
	private int acceptedFrames;
	private int rejectedFrames;
	private boolean truncated;
	/** Bytes of each segment handed out and not yet settled, keyed by segment index. */
	private final java.util.Map<Integer, Integer> outstanding = new java.util.LinkedHashMap<>();
	/** Sum of the map above, kept alongside it so the hot path does not walk the map. */
	private long outstandingBytes;
	/** The highest heldBytes() ever reached. Reported, so "bounded memory" is measured not claimed. */
	private long peakHeldBytes;
	/** Wall time of the first frame in the segment being filled. 0 when the segment is empty. */
	private long currentFirstFrameMillis;
	/**
	 * Set by {@link #discardAll()} and never cleared. A discarded segmenter accepts nothing and
	 * yields nothing, so a reference held by an in-flight callback cannot produce a late upload.
	 */
	private boolean discarded;

	public DropFrameSegmenter()
	{
		this(SEGMENT_FRAMES, MAX_FRAME_BYTES, UNACKED_BYTE_BUDGET);
	}

	public DropFrameSegmenter(int segmentFrames, int maxFrameBytes, long unackedByteBudget)
	{
		if (segmentFrames < 1)
		{
			throw new IllegalArgumentException("segmentFrames must be >= 1");
		}
		this.segmentFrames = segmentFrames;
		this.maxFrameBytes = maxFrameBytes;
		this.unackedByteBudget = unackedByteBudget;
	}

	/** One flushable unit of a session's footage. Immutable once handed out. */
	public static final class Segment
	{
		/** 0-based position in the session. THE ordering key — never inferred from a timestamp. */
		public final int index;
		/** Wall time of this segment's FIRST frame, for playback timing and for the stitcher. */
		public final long firstFrameMillis;
		public final List<byte[]> frames;
		public final int bytes;

		Segment(int index, long firstFrameMillis, List<byte[]> frames, int bytes)
		{
			this.index = index;
			this.firstFrameMillis = firstFrameMillis;
			this.frames = Collections.unmodifiableList(frames);
			this.bytes = bytes;
		}

		public int frameCount()
		{
			return frames.size();
		}
	}

	/**
	 * Add one already-encoded frame.
	 *
	 * @return a full segment ready to upload, or null when the current segment still has room.
	 */
	public synchronized Segment add(byte[] frame, long nowMillis)
	{
		if (discarded)
		{
			// The identity this footage belonged to is gone. Refusing here, rather than only at the
			// caller, is what makes a stale reference to this object harmless.
			rejectedFrames++;
			return null;
		}
		if (frame == null || frame.length == 0 || frame.length > maxFrameBytes)
		{
			rejectedFrames++;
			return null;
		}
		if (heldBytes() + frame.length > unackedByteBudget)
		{
			// The RETRY BUFFER is full, which means the server has stopped acknowledging. A working
			// upload path frees these bytes continuously, so reaching this at all is a network
			// failure, not a long session. Keep what is captured and stop taking more. Recording the
			// fact is the point: a truncated clip that says so is evidence, and a truncated clip
			// that does not is a lie about what happened after the cut.
			truncated = true;
			rejectedFrames++;
			return null;
		}
		if (current.isEmpty())
		{
			currentFirstFrameMillis = nowMillis;
		}
		current.add(frame);
		currentBytes += frame.length;
		sessionBytes += frame.length;
		acceptedFrames++;
		peakHeldBytes = Math.max(peakHeldBytes, heldBytes());
		if (current.size() >= segmentFrames)
		{
			return takeSegment();
		}
		return null;
	}

	/**
	 * Hand over the partially-filled segment at the end of a session, or null when it is empty.
	 *
	 * Called once, at stop. A session whose frame count divides exactly by segmentFrames ends with
	 * nothing here, which is correct and not an error.
	 */
	public synchronized Segment flushRemainder()
	{
		if (discarded || current.isEmpty())
		{
			return null;
		}
		return takeSegment();
	}

	private Segment takeSegment()
	{
		Segment seg = new Segment(nextSegmentIndex++, currentFirstFrameMillis,
			new ArrayList<>(current), currentBytes);
		// The bytes move from the segment being filled to the uploader. They are still HELD, because
		// the uploader keeps the frames alive until the server answers, so the total is unchanged
		// here by design.
		outstanding.put(seg.index, seg.bytes);
		outstandingBytes += seg.bytes;
		current.clear();
		currentBytes = 0;
		currentFirstFrameMillis = 0L;
		return seg;
	}

	/**
	 * The server stored this segment. Free its bytes.
	 *
	 * This is what turns the budget into a retry buffer rather than a session cap. Idempotent: a
	 * duplicate acknowledgement frees nothing twice, because the index is removed on the first one.
	 */
	public synchronized void segmentAcknowledged(int index)
	{
		settle(index);
	}

	/**
	 * This segment will never be sent: retries are exhausted, or the upload could not start.
	 *
	 * Its bytes are freed too. Holding them would let one dead segment shrink the buffer for the
	 * rest of the session, so a single failure would slowly strangle capture. The segment is LOST
	 * and the caller counts it as failed, which is what the manifest reports. Freeing the memory
	 * does not hide that.
	 */
	public synchronized void segmentAbandoned(int index)
	{
		settle(index);
	}

	private void settle(int index)
	{
		Integer bytes = outstanding.remove(index);
		if (bytes != null)
		{
			outstandingBytes -= bytes;
		}
	}

	/** Bytes held right now: the segment being filled plus every unacknowledged segment. */
	public synchronized long heldBytes()
	{
		return outstandingBytes + currentBytes;
	}

	/** Segments handed out and not yet acknowledged or abandoned. */
	public synchronized int unsettledSegments()
	{
		return outstanding.size();
	}

	/** The highest heldBytes() this session ever reached. The memory claim, measured. */
	public synchronized long peakHeldBytes()
	{
		return peakHeldBytes;
	}

	/** Segments handed out so far. After flushRemainder this is the session's total segment count. */
	public synchronized int segmentCount()
	{
		return nextSegmentIndex;
	}

	public synchronized int acceptedFrames()
	{
		return acceptedFrames;
	}

	/** Frames refused: failed encode, oversized, or past the retry buffer. Reported, never hidden. */
	public synchronized int rejectedFrames()
	{
		return rejectedFrames;
	}

	public synchronized long sessionBytes()
	{
		return sessionBytes;
	}

	/** True when the retry buffer filled, so the clip does not cover the whole session. */
	public synchronized boolean truncated()
	{
		return truncated;
	}

	/** Frames held in memory right now. The memory bound this class exists to enforce. */
	public synchronized int bufferedFrames()
	{
		return current.size();
	}

	/** Drop everything held. Used when a session ends with nothing worth uploading. */
	public synchronized void clear()
	{
		current.clear();
		currentBytes = 0;
		currentFirstFrameMillis = 0L;
	}

	/**
	 * THE IDENTITY WITHDRAWAL PATH. Drop the filling segment AND the retry buffer, then refuse
	 * everything afterwards.
	 *
	 * {@link #clear()} only empties the segment being filled. That is enough when a session ends
	 * tidily, because every segment already handed to the uploader belongs to the same account and
	 * is still allowed to finish. It is NOT enough when the account identity behind the footage is
	 * withdrawn or swapped: the segments counted in the outstanding map were captured under the old
	 * token and must never reach the network, so their accounting is dropped here and the caller
	 * strands the in-flight attempts themselves.
	 *
	 * The discarded flag is the part that makes the bytes UNREACHABLE rather than merely
	 * unaccounted. After this call add() refuses every frame and flushRemainder() yields nothing,
	 * so a caller still holding this object cannot produce a segment from it.
	 */
	public synchronized void discardAll()
	{
		discarded = true;
		current.clear();
		currentBytes = 0;
		currentFirstFrameMillis = 0L;
		outstanding.clear();
		outstandingBytes = 0L;
	}

	/** True once discardAll() has run. Nothing can come out of this segmenter again. */
	public synchronized boolean discarded()
	{
		return discarded;
	}
}
