package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Staff drop proof: the drop session, its frames and segments, the audit and the final manifest. */
@Slf4j
@RequiredArgsConstructor
final class DropProof
{
	private final AccountConnectPlugin plugin;

	// ---- DROP-TRADE PROOF: session lifecycle, segmented capture, pile-centred candidates ----
	//
	// This is a SECOND consumer of the same frame-capture machinery the store path uses, not a rival
	// pipeline. It shares the sampler, the encoder, the downscaler and the multipart upload idiom.
	// What differs is WHAT is retained and WHEN: the store path keeps one 360-frame budget per visit
	// (StoreVisitClip), and a drop trade must keep everything however long the customer takes.

	/** Session state machine. Owns start, the 5-second tail, and which piles are still live. */
	final DropSessionRecorder dropSession = new DropSessionRecorder();
	/** Segmented frame buffer. Bounded to one segment in memory, never the whole session. */
	volatile DropFrameSegmenter dropSegmenter;
	/** Armed while drop capture is running. Separate from clipCapturing so the two never interfere. */
	volatile boolean dropCapturing;
	/** Set on a sampled tick to request one frame; cleared when that frame arrives. */
	private volatile boolean dropFramePending;
	/** Wall-clock (nanoTime) of the next drop frame to sample. */
	private volatile long nextDropSampleAt;
	/** Per-frame render callback, registered with DrawManager only while drop capture runs. */
	private final Runnable dropFrameTick = this::onDropFrameTick;
	/**
	 * Adaptive rate controller: 8fps baseline, 30fps around the Drop, the spawn, the removal and a
	 * new drop inside the tail. The sampler still runs at 30fps, because the two seconds BEFORE a
	 * removal cannot be chosen after it happens; this decides which sampled frames survive.
	 */
	volatile DropCaptureRate dropRate;
	/** Segments uploaded for the CURRENT session, counted so the final manifest can be checked. */
	final AtomicInteger dropSegmentsSent =
		new AtomicInteger();
	/** Segments whose upload FAILED after every retry. A clip missing a segment must say so. */
	final AtomicInteger dropSegmentsFailed =
		new AtomicInteger();

	/** Per-session reconciliation that survives async segment callbacks and the next session starting. */
	@RequiredArgsConstructor
	static final class DropSessionAudit
	{
		final String sessionId;
		final String accountHash;
		final String rsn;
		final String tokenFingerprint;
		final long startedAtMillis;
		final AtomicInteger dropEvents = new AtomicInteger();
		final AtomicInteger removals = new AtomicInteger();
		final AtomicInteger resolvedCounterparties = new AtomicInteger();
		final ConcurrentHashMap<Integer, String> segmentResults = new ConcurrentHashMap<>();
		volatile DropFrameSegmenter segmenter;
		volatile int declaredSegments = -1;
		volatile int declaredDrops = -1;
		volatile int frames;
		volatile int framesRejected;
		volatile boolean truncated;
		volatile boolean recorderComplete;
		volatile int localFailures;
		volatile String recorderReason;
		volatile long endedAtMillis;
		volatile boolean closed;
		volatile boolean finalEmitted;
	}

	final ConcurrentHashMap<String, DropSessionAudit> dropAudits =
		new ConcurrentHashMap<>();
	/**
	 * Live piles of the CURRENT session, keyed exactly as DropSessionRecorder keys them.
	 *
	 * Maps a tracked pile to its session id and drop sequence, which is what lets a removal name the
	 * drop that produced it. Without this a removal can only say "a pile of item X went", and the
	 * manifest could not join one removal to one drop when a session drops the same item twice.
	 */
	private final Map<OwnPiles.DroppedGroundItem, int[]> dropPileSeq =
		Collections.synchronizedMap(new LinkedHashMap<>());
	/** Session id each tracked pile belongs to. Parallel to dropPileSeq; separate to keep types simple. */
	final Map<OwnPiles.DroppedGroundItem, String> dropPileSession =
		Collections.synchronizedMap(new LinkedHashMap<>());
	/** Sequence number assigned to the next drop ACTION, read when its pile finally spawns. */
	volatile int pendingDropSeq;
	/** Session id at the moment of the last drop action, so a late spawn attaches to the right one. */
	volatile String pendingDropSessionId;
	/**
	 * THE ACCOUNT IDENTITY THIS RECORDING BELONGS TO: the link token as it read when the session
	 * started. Null when no session is bound to an identity.
	 *
	 * WHY IT EXISTS. uploadAllowed() only asks whether SOME valid token is configured. A mid-session
	 * swap from token A to a different valid token B therefore left every gate open, and the frames
	 * captured under A were uploaded with B in the form body. That attributes one account's evidence
	 * to another account, which is an identity-boundary failure, not an accounting untidiness.
	 *
	 * Every drop-proof path now compares the live token against THIS value rather than against the
	 * shape of a token. A mismatch of any kind — a swap, a clear, a malformed value — ends the
	 * session and destroys what it captured.
	 */
	private volatile String dropSessionToken;

	/** Frame rate for drop capture. Same sampler as the store path, so the same constant. */
	static final int DROP_CLIP_FPS = StoreClips.CLIP_FPS;

	/**
	 * Does the live token still name the account this recording was started for?
	 *
	 * True when no session is bound to an identity, so a caller can ask this unconditionally.
	 */
	boolean dropSessionIdentityIntact()
	{
		String bound = dropSessionToken;
		return bound == null || bound.equals(plugin.currentLinkToken());
	}

	/**
	 * THE IDENTITY BOUNDARY. A changed link token ends the running session at once and destroys
	 * everything captured under the old one.
	 *
	 * It covers all three shapes of change with one rule, because they are the same event: a swap to
	 * another VALID token, a clear to empty, and a change to a malformed value. Only the first was
	 * ever missed, and only because the old gate asked whether a token was well-formed rather than
	 * whether it was the SAME token.
	 *
	 * A newly configured token starts nothing here. It can only be the identity of a FUTURE session,
	 * which is what stops a token that comes back from resurrecting the recording it left.
	 */
	void enforceDropSessionTokenIdentity()
	{
		if (dropSessionToken == null || dropSessionIdentityIntact())
		{
			return;
		}
		discardDropSessionOnWithdrawnConsent();
	}

	/**
	 * A Drop action was clicked. Starts or joins the session and arms capture.
	 *
	 * Called from the menu-click path, NOT from the drop EVENT, and that ordering is the requirement:
	 * the event only exists once the inventory loss and the ground spawn agree, which is ticks after
	 * the click. Starting there would cut the drop itself off the front of the clip.
	 */
	void onDropActionForProof()
	{
		if (!plugin.grants.dropProofEnabled())
		{
			return;
		}
		long now = System.currentTimeMillis();
		boolean wasActive = dropSession.active();
		boolean inTail = dropSession.stopPending();
		String newId = wasActive ? null : newDropSessionId();
		pendingDropSeq = dropSession.onDropAction(newId, now);
		pendingDropSessionId = dropSession.sessionId();
		if (!wasActive)
		{
			String auditHash = plugin.activeHash;
			String auditRsn = plugin.activeRsn;
			if (plugin.client != null)
			{
				if (auditHash == null)
				{
					auditHash = Long.toString(plugin.client.getAccountHash());
				}
				if (auditRsn == null && plugin.client.getLocalPlayer() != null)
				{
					auditRsn = plugin.client.getLocalPlayer().getName();
				}
			}
			DropSessionAudit audit = new DropSessionAudit(pendingDropSessionId, auditHash, auditRsn,
				plugin.events.currentEventTokenFingerprint(), now);
			dropAudits.put(pendingDropSessionId, audit);
			// Bind the identity BEFORE capture arms, so no frame can exist that is not attributable.
			dropSessionToken = plugin.currentLinkToken();
			startDropCapture();
			audit.segmenter = dropSegmenter;
		}
		// A drop inside the tail is its own moment: it cancels the stop, so the clip must show it at
		// full rate rather than at the baseline the tail had settled into.
		markDropMoment(inTail ? DropCaptureRate.Event.TAIL_DROP : DropCaptureRate.Event.DROP_ACTION, now);
	}

	/**
	 * Longest a session id may be on the wire. The server truncates at 24 and the staff-ops stitcher
	 * puts the id inside a D1 `LIKE` pattern, which D1 refuses past 50 characters. Keeping the id
	 * short at the source means no downstream limit has to be widened for it.
	 */
	static final int DROP_SESSION_ID_MAX = 24;

	/**
	 * Session id: time-ordered and unique per client, so two staff clients cannot collide.
	 *
	 * Hyphen-free and lower-case hex, because the id becomes part of an R2 key and the server's
	 * sanitizer drops anything outside [a-z0-9_-]. An id that loses characters to sanitizing would
	 * stop matching the manifest the stitcher joins on.
	 */
	private String newDropSessionId()
	{
		String id = Long.toHexString(System.currentTimeMillis())
			+ Integer.toHexString(ThreadLocalRandom.current().nextInt(1 << 24));
		return id.length() > DROP_SESSION_ID_MAX ? id.substring(0, DROP_SESSION_ID_MAX) : id;
	}

	/** Arm frame capture for a session. Idempotent. */
	void startDropCapture()
	{
		if (dropCapturing || !plugin.grants.dropProofEnabled())
		{
			return;
		}
		dropSegmenter = new DropFrameSegmenter();
		dropRate = new DropCaptureRate();
		dropSegmentsSent.set(0);
		dropSegmentsFailed.set(0);
		nextDropSampleAt = 0L;		// first render tick samples immediately
		dropFramePending = false;
		dropCapturing = true;
		if (plugin.drawManager != null)
		{
			plugin.drawManager.registerEveryFrameListener(dropFrameTick);
		}
	}

	/**
	 * Wall-clock decimation for drop frames. Its own gate, so drop capture and store capture running
	 * at the same time cannot steal each other's sample slots.
	 */
	boolean shouldSampleDropFrame(long nowNanos)
	{
		if (nowNanos < nextDropSampleAt)
		{
			return false;
		}
		final long period = 1_000_000_000L / DROP_CLIP_FPS;
		long next = nextDropSampleAt + period;
		nextDropSampleAt = (next <= nowNanos) ? nowNanos + period : next;
		return true;
	}

	/** Render-thread frame hook. Encodes off-thread; at most one frame is ever in flight. */
	void onDropFrameTick()
	{
		if (!dropCapturing || dropFramePending)
		{
			return;
		}
		if (!shouldSampleDropFrame(System.nanoTime()))
		{
			return;
		}
		dropFramePending = true;
		plugin.drawManager.requestNextFrameListener(img ->
		{
			if (img == null)
			{
				dropFramePending = false;
				return;
			}
			final BufferedImage scaled = StoreClips.downscaleRgb(StoreClips.toRgbFrame(img), StoreClips.MAX_FRAME_WIDTH);
			if (scaled == null)
			{
				dropFramePending = false;
				return;
			}
			plugin.executor.submit(() ->
			{
				try
				{
					acceptDropFrame(AccountConnectPlugin.encodeJpeg(scaled), System.currentTimeMillis());
				}
				finally
				{
					dropFramePending = false;
				}
			});
		});
	}

	/**
	 * Hand one encoded frame to the segmenter and upload the segment it completes.
	 *
	 * Package-private so the memory bound and the segment ordering can be driven under test with
	 * synthetic bytes, with no client and no render loop.
	 */
	void acceptDropFrame(byte[] encoded, long nowMillis)
	{
		// RE-CHECK THE GATE ON THE FRAME PATH (finding F2). The frame path used to test only that a
		// segmenter existed, so a user who unticked the upload switch mid-session kept being
		// recorded until something else ended the session. A frame taken after consent is withdrawn
		// is never accepted, so it can never be uploaded later.
		if (!dropCapturing || !plugin.grants.dropProofEnabled())
		{
			return;
		}
		// AND RE-CHECK THE IDENTITY. This runs on the encode executor, which can deliver a frame
		// after the token already changed. Refusing here does not end the session — the config
		// listener and the tick poll do that, both on the client thread — it only guarantees that
		// no frame captured under the old account is ever buffered.
		if (!dropSessionIdentityIntact())
		{
			return;
		}
		DropFrameSegmenter seg = dropSegmenter;
		if (seg == null)
		{
			return;
		}
		DropCaptureRate rate = dropRate;
		if (rate != null && rate.offer(encoded, nowMillis) != DropCaptureRate.Decision.KEEP)
		{
			return;		// held as pre-roll, or discarded by the baseline cadence
		}
		DropFrameSegmenter.Segment full = seg.add(encoded, nowMillis);
		if (full != null)
		{
			uploadDropSegment(full, dropSession.sessionId());
		}
	}

	/**
	 * A moment worth full detail: start a 30fps burst and flush the held pre-roll into the clip.
	 *
	 * The pre-roll is committed FIRST and in order, so the two seconds leading up to the moment sit
	 * before it in the clip rather than after.
	 */
	void markDropMoment(DropCaptureRate.Event event, long nowMillis)
	{
		DropCaptureRate rate = dropRate;
		DropFrameSegmenter seg = dropSegmenter;
		if (rate == null || seg == null)
		{
			return;
		}
		rate.onEvent(event, nowMillis);
		for (byte[] held : rate.claimPreRoll(nowMillis))
		{
			DropFrameSegmenter.Segment full = seg.add(held, nowMillis);
			if (full != null)
			{
				uploadDropSegment(full, dropSession.sessionId());
			}
		}
	}

	/**
	 * Stop capture, flush the tail segment, and emit the session manifest event.
	 *
	 * The manifest is an EVENT, not a media row, so the join from clip to session to drops to
	 * removals lives in the same event stream everything else does.
	 */
	void stopDropCapture(DropSessionRecorder.Outcome outcome, String sessionId, int dropCount,
		long startedAtMillis, String reason)
	{
		if (!dropCapturing)
		{
			return;
		}
		dropCapturing = false;
		if (plugin.drawManager != null)
		{
			plugin.drawManager.unregisterEveryFrameListener(dropFrameTick);
		}
		DropFrameSegmenter seg = dropSegmenter;
		dropFramePending = false;
		if (seg == null)
		{
			DropSessionAudit audit = audit(sessionId);
			if (audit != null)
			{
				audit.declaredSegments = 0;
				audit.declaredDrops = dropCount;
				audit.recorderComplete = false;
				audit.recorderReason = reason == null ? "segmenter_missing" : reason;
				audit.localFailures++;
				audit.endedAtMillis = System.currentTimeMillis();
				audit.closed = true;
				maybeEmitFinalDropManifest(sessionId);
			}
			dropSegmenter = null;
			dropRate = null;
			return;
		}
		// THE MANIFEST IS EMITTED WHATEVER THE TAIL UPLOAD DOES.
		//
		// The manifest is the only record of how many segments a clip should have, so losing it to
		// an upload fault turns a recoverable partial clip into an unreadable one: the stitcher
		// cannot tell a complete clip from one missing its last segment. The upload is therefore
		// wrapped, its failure is counted in segments_failed, and the manifest still goes out.
		try
		{
			DropFrameSegmenter.Segment tail = seg.flushRemainder();
			if (tail != null)
			{
				uploadDropSegment(tail, sessionId);
			}
		}
		catch (RuntimeException e)
		{
			dropSegmentsFailed.incrementAndGet();
			plugin.events.healthDropSegmentsLost.incrementAndGet();
			noteDropAuditLocalFailure(sessionId);
			log.debug("OSRS BiS drop tail segment failed to submit", e);
		}
		emitDropSessionManifest(seg, outcome, sessionId, dropCount, startedAtMillis, reason);
		closeDropAudit(seg, outcome, sessionId, dropCount, reason);
		// Cleared AFTER the manifest: emitDropSessionManifest reads the rate controller's counts.
		dropSegmenter = null;
		DropCaptureRate done = dropRate;
		dropRate = null;
		if (done != null)
		{
			done.clear();
		}
	}

	private DropSessionAudit audit(String sessionId)
	{
		return sessionId == null ? null : dropAudits.get(sessionId);
	}

	void noteDropAuditEvent(String sessionId)
	{
		DropSessionAudit audit = audit(sessionId);
		if (audit != null)
		{
			audit.dropEvents.incrementAndGet();
		}
	}

	void noteDropAuditRemoval(String sessionId, boolean resolvedCounterparty)
	{
		DropSessionAudit audit = audit(sessionId);
		if (audit != null)
		{
			audit.removals.incrementAndGet();
			if (resolvedCounterparty)
			{
				audit.resolvedCounterparties.incrementAndGet();
			}
		}
	}

	private void noteDropAuditLocalFailure(String sessionId)
	{
		DropSessionAudit audit = audit(sessionId);
		if (audit != null)
		{
			audit.localFailures++;
		}
	}

	private void settleDropAuditSegment(String sessionId, int segmentIndex, String result)
	{
		if (result == null)
		{
			return;
		}
		DropSessionAudit audit = audit(sessionId);
		if (audit != null && audit.segmentResults.putIfAbsent(segmentIndex, result) != null)
		{
			return;
		}
		if ("uploaded".equals(result))
		{
			plugin.events.healthDropSegmentsSent.incrementAndGet();
			plugin.events.healthLastMediaUploadMs.set(System.currentTimeMillis());
		}
		else if ("refused".equals(result))
		{
			plugin.events.healthDropSegmentsRefused.incrementAndGet();
		}
		else
		{
			plugin.events.healthDropSegmentsLost.incrementAndGet();
		}
		if (audit != null)
		{
			maybeEmitFinalDropManifest(sessionId);
			// Consent-withdrawn audits deliberately emit no manifest, but remain as identity/segmenter
			// tombstones until every already-submitted callback has settled. Remove only after that point.
			if (audit.finalEmitted && audit.closed && audit.declaredSegments >= 0
				&& audit.segmentResults.size() >= audit.declaredSegments)
			{
				dropAudits.remove(sessionId, audit);
			}
		}
	}

	private void closeDropAudit(DropFrameSegmenter seg, DropSessionRecorder.Outcome outcome,
		String sessionId, int dropCount, String reason)
	{
		DropSessionAudit audit = audit(sessionId);
		if (audit == null || seg == null)
		{
			return;
		}
		audit.declaredSegments = seg.segmentCount();
		audit.declaredDrops = dropCount;
		audit.frames = seg.acceptedFrames();
		audit.framesRejected = seg.rejectedFrames();
		audit.truncated = seg.truncated();
		audit.recorderComplete = outcome == DropSessionRecorder.Outcome.COMPLETE;
		audit.recorderReason = reason;
		audit.endedAtMillis = System.currentTimeMillis();
		audit.closed = true;
		plugin.events.healthDropFramesRejected.addAndGet(audit.framesRejected);
		maybeEmitFinalDropManifest(sessionId);
	}

	void maybeEmitFinalDropManifest(String sessionId)
	{
		DropSessionAudit audit = audit(sessionId);
		if (audit == null)
		{
			return;
		}
		Map<String, Object> fields;
		synchronized (audit)
		{
			if (!audit.closed || audit.finalEmitted || audit.declaredSegments < 0
				|| audit.segmentResults.size() < audit.declaredSegments)
			{
				return;
			}
			int uploaded = 0;
			int refused = 0;
			int failed = 0;
			for (String result : audit.segmentResults.values())
			{
				if ("uploaded".equals(result)) uploaded++;
				else if ("refused".equals(result)) refused++;
				else failed++;
			}
			int missing = Math.max(0, audit.declaredSegments - uploaded);
			boolean complete = audit.recorderComplete && !audit.truncated && audit.framesRejected == 0
				&& audit.localFailures == 0 && audit.declaredSegments > 0 && uploaded == audit.declaredSegments
				&& audit.dropEvents.get() == audit.declaredDrops;
			fields = new LinkedHashMap<>();
			fields.put("drop_session_id", audit.sessionId);
			if (audit.accountHash != null) fields.put("account_hash", audit.accountHash);
			if (audit.rsn != null) fields.put("rsn", audit.rsn);
			fields.put("manifest_stage", "FINAL");
			fields.put("proof_status", complete ? "COMPLETE" : "PARTIAL");
			fields.put("session_outcome", audit.recorderComplete ? "COMPLETE" : "INTERRUPTED");
			if (!audit.recorderComplete && audit.recorderReason != null && !audit.recorderReason.isEmpty())
			{
				fields.put("outcome_reason", audit.recorderReason);
			}
			fields.put("drops_declared", audit.declaredDrops);
			fields.put("drop_events", audit.dropEvents.get());
			fields.put("ground_removals", audit.removals.get());
			fields.put("counterparties_resolved", audit.resolvedCounterparties.get());
			fields.put("segments_declared", audit.declaredSegments);
			fields.put("segments_uploaded", uploaded);
			fields.put("segments_refused", refused);
			fields.put("segments_failed", failed);
			fields.put("missing_segments", missing);
			fields.put("frames", audit.frames);
			fields.put("frames_rejected", audit.framesRejected);
			fields.put("local_failures", audit.localFailures);
			fields.put("started_at", audit.startedAtMillis);
			fields.put("ended_at", audit.endedAtMillis);
			fields.put("finalized_at", System.currentTimeMillis());
			fields.put("media_kind", DROP_MEDIA_KIND);
			audit.finalEmitted = true;
		}
		if (audit.tokenFingerprint != null
			&& audit.tokenFingerprint.equals(plugin.events.currentEventTokenFingerprint())
			&& plugin.grants.dropProofEnabled())
		{
			plugin.events.emitEventBound("drop_trade_clip_final", fields, audit.tokenFingerprint);
		}
		else
		{
			// Identity/grant changed after capture. Losing the final row is safer than publishing old
			// evidence under a new identity or after the capture grant was withdrawn.
			plugin.events.healthEventsLost.incrementAndGet();
		}
		dropAudits.remove(sessionId, audit);
	}

	/**
	 * The manifest row: everything needed to join a stitched clip back to what it shows.
	 *
	 * segments is the count the stitcher must find. A clip assembled from fewer is INCOMPLETE and the
	 * surface says so rather than playing a gap as if it were the footage.
	 */
	private void emitDropSessionManifest(DropFrameSegmenter seg, DropSessionRecorder.Outcome outcome,
		String sessionId, int dropCount, long startedAtMillis, String reason)
	{
		if (sessionId == null)
		{
			return;
		}
		Map<String, Object> fields = Fields.of("drop_session_id", sessionId, "manifest_stage", "PROVISIONAL");
		// COMPLETE IS A CLAIM ABOUT EVIDENCE, so it is only written when the recorder proved it. The
		// recorder refuses COMPLETE for a live pile, an outstanding pending drop, an expired
		// pending, an abandoned pile and any external interrupt — see DropSessionRecorder.finish.
		boolean complete = outcome == DropSessionRecorder.Outcome.COMPLETE;
		fields.put("outcome", complete ? "COMPLETE" : "INTERRUPTED");
		if (!complete)
		{
			// WHY it is not complete, so a reader never has to guess whether a partial clip means a
			// logout or a drop that was never observed landing.
			fields.put("outcome_reason",
				reason == null || reason.isEmpty() ? DropSessionRecorder.REASON_EXTERNAL : reason);
		}
		fields.put("drops", dropCount);
		fields.put("started_at", startedAtMillis);
		fields.put("ended_at", System.currentTimeMillis());
		fields.put("segments", seg.segmentCount());
		// ⚠ THESE TWO COUNTS ARE A SNAPSHOT, NOT A RECONCILIATION, and the manifest says so.
		//
		// Uploads are async: stopDropCapture submits the last segments and emits this row in the
		// same breath, so the in-flight ones have neither succeeded nor failed yet. Measured live
		// 2026-09-20 on a real session: segments 5, uploaded 3, failed 0 — which does not add up
		// and read like two lost segments when both were simply still on the wire.
		//
		// Waiting here is the wrong fix. It would block the client thread on a network round trip,
		// and a client that logs out immediately after a trade would publish nothing at all. So the
		// row states what it knows AT EMIT TIME and names the gap explicitly. `segments` is the
		// declared total and is authoritative; the stitcher counts what actually arrived in R2 and
		// compares against THAT, which is the only count that can be complete.
		fields.put("segments_uploaded_at_emit", dropSegmentsSent.get());
		fields.put("segments_failed_at_emit", dropSegmentsFailed.get());
		int inFlight = seg.segmentCount() - dropSegmentsSent.get() - dropSegmentsFailed.get();
		if (inFlight > 0)
		{
			fields.put("segments_in_flight_at_emit", inFlight);
		}
		fields.put("frames", seg.acceptedFrames());
		fields.put("frames_dropped", seg.rejectedFrames());
		fields.put("bytes", seg.sessionBytes());
		// The memory claim, measured rather than asserted: the most this client ever held at once,
		// and how many segments were still unacknowledged when the manifest was written.
		fields.put("peak_held_bytes", seg.peakHeldBytes());
		fields.put("unsettled_at_emit", seg.unsettledSegments());
		// ADAPTIVE RATE, reported so a viewer knows what they are watching. `fps` is the SAMPLE
		// rate the bursts play at; `baseline_fps` is the continuous rate between them. A stitcher
		// that assumed one flat rate would play the baseline stretches too fast.
		fields.put("fps", DROP_CLIP_FPS);
		fields.put("baseline_fps", DropCaptureRate.BASELINE_FPS);
		DropCaptureRate rate = dropRate;
		if (rate != null)
		{
			fields.put("frames_baseline", rate.keptBaseline());
			fields.put("frames_burst", rate.keptBurst());
			fields.put("bursts", rate.bursts());
		}
		fields.put("media_kind", DROP_MEDIA_KIND);
		if (seg.truncated())
		{
			// FAIL VISIBLY (operator decision 2026-09-20). Truncation is no longer an accepted
			// outcome: the adaptive rate exists so a long session fits. Reaching the budget anyway
			// means the sizing assumption is wrong for this session, so the row says the coverage
			// is INCOMPLETE in as many words, and the stitcher refuses to call the clip complete.
			fields.put("truncated", true);
			fields.put("coverage", "INCOMPLETE_BUDGET_EXCEEDED");
		}
		else
		{
			fields.put("coverage", "FULL");
		}
		plugin.events.emitEvent("drop_trade_clip", fields);
	}

	/**
	 * Media kind for drop footage. DISTINCT from the store path's `store_frames`, deliberately: a
	 * drop clip is not a shop visit, and the collector, the stitcher and the staff surfaces all key
	 * off this string to tell them apart.
	 */
	static final String DROP_MEDIA_KIND = "drop_frames";

	/** Upload retries for one segment. A dropped segment is a hole in the evidence, so it is retried. */
	static final int DROP_SEGMENT_RETRIES = 3;

	/**
	 * POST one segment to the frames ingest, with retries.
	 *
	 * The segment index and the session id travel as form fields, so reassembly is deterministic and
	 * does not infer order from timestamps the way the store path must.
	 */
	private void finishDropSegment(DropFrameSegmenter.Segment segment, String sessionId, String result)
	{
		if (segment == null || result == null)
		{
			return;
		}
		DropSessionAudit audit = audit(sessionId);
		DropFrameSegmenter owner = audit != null && audit.segmenter != null ? audit.segmenter : dropSegmenter;
		boolean legacyCurrent = audit == null || !audit.closed;
		if ("uploaded".equals(result))
		{
			if (legacyCurrent) dropSegmentsSent.incrementAndGet();
			if (owner != null) owner.segmentAcknowledged(segment.index);
		}
		else
		{
			if (legacyCurrent) dropSegmentsFailed.incrementAndGet();
			if (owner != null) owner.segmentAbandoned(segment.index);
		}
		settleDropAuditSegment(sessionId, segment.index, result);
	}

	private void uploadDropSegment(DropFrameSegmenter.Segment segment, String sessionId)
	{
		if (segment == null)
		{
			return;
		}
		// Every early return below strands the segment's bytes in the retry buffer unless they are
		// released. That is the whole failure this budget change could introduce: a session that
		// cannot upload at all would fill the buffer with segments nobody is waiting on and stop
		// capturing, which is exactly the truncation the change exists to remove.
		if (!plugin.uploadAllowed() || segment.frames.isEmpty() || sessionId == null)
		{
			finishDropSegment(segment, sessionId, "failed");
			return;
		}
		// AND THE GRANT ITSELF. uploadAllowed() is the user's own switch plus a well-formed token,
		// and neither of those moves when the OPERATOR withdraws drop proof: a server withdrawal
		// only clears serverDropProofEnabled. Without this line the guard set is inconsistent —
		// the user switch and a token swap both stop this call and the operator's own documented
		// "turn it off" does not.
		if (!plugin.grants.dropProofEnabled())
		{
			finishDropSegment(segment, sessionId, "refused");
			return;
		}
		String token = plugin.currentLinkToken();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			finishDropSegment(segment, sessionId, "failed");
			return;
		}
		final String base = plugin.apiBase();
		if (plugin.executor == null)
		{
			// No executor means the segment cannot be sent at all. COUNT it rather than throwing:
			// a throw here would propagate out of stopDropCapture and kill the manifest, so an
			// upload fault would also erase the record of what was captured.
			finishDropSegment(segment, sessionId, "failed");
			return;
		}
		try
		{
			final String captureAccount = plugin.activeHash;
			plugin.executor.submit(() -> postDropSegment(base, token, segment, sessionId, 0, captureAccount));
		}
		catch (RuntimeException e)
		{
			finishDropSegment(segment, sessionId, "failed");
			log.debug("OSRS BiS drop segment {} could not be scheduled", segment.index, e);
		}
	}

	/** One attempt, re-enqueueing itself on failure up to DROP_SEGMENT_RETRIES. */
	void postDropSegment(String base, String token, DropFrameSegmenter.Segment segment,
		String sessionId, int attempt)
	{
		postDropSegment(base, token, segment, sessionId, attempt, plugin.activeHash);
	}

	void postDropSegment(String base, String token, DropFrameSegmenter.Segment segment,
		String sessionId, int attempt, String captureAccount)
	{
		if (!plugin.uploadAllowed())
		{
			// The user turned uploads off mid-session. Nothing more will be sent, so release the
			// bytes rather than leaving them held for the rest of the session.
			finishDropSegment(segment, sessionId, "refused");
			return;
		}
		// THE GRANT ARM, and it belongs here rather than only at the entry point. A retry runs this
		// method again minutes later: DROP_SEGMENT_RETRIES attempts at a
		// CLIP_UPLOAD_TIMEOUT_SECONDS call timeout each. discardDropSessionOnWithdrawnConsent
		// cannot reach a Runnable already on the executor — that closure holds its own Segment with
		// the frame bytes inside it, and discardAll() drops the accounting, not those bytes. So the
		// only place a queued retry can be stopped is the attempt itself.
		//
		// The server does not catch it either: /store-frames-ingest authorizes on the staff token
		// and never reads drop_proof, deliberately, because rollout is not authorization. A staff
		// token is still staff after the operator withdraws the rollout flag, so the late POST is
		// stored. Both components are self-consistent and the seam between them leaked.
		if (!plugin.grants.dropProofEnabled())
		{
			finishDropSegment(segment, sessionId, "refused");
			return;
		}
		// THE OUTSTANDING RETRY ARM. This segment's bytes were captured while `token` was configured,
		// and `token` is what its form body carries. A retry that fires after the user swapped to a
		// different token would file one account's footage under whoever is linked now, so the
		// attempt is stranded instead. Retries are the only way a segment survives the swap at all:
		// the segmenter refuses to produce a new one.
		if (!token.equals(plugin.currentLinkToken()) || !plugin.grants.captureAccountMatches(captureAccount))
		{
			finishDropSegment(segment, sessionId, "refused");
			return;
		}
		try
		{
			Request request = new Request.Builder()
				.url(base + "/store-frames-ingest")
			.post(ServerGrants.withMediaAccount(buildDropSegmentBody(segment, token, sessionId), captureAccount))
			.build();
		StoreClips.uploadClient(plugin.okHttpClient).newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				retryOrGiveUp(e == null ? "io" : String.valueOf(e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.isSuccessful())
					{
						// A 200 IS NOT ALWAYS A STORE. /store-frames-ingest answers a deliberate
						// refusal with 200 {ok:true, dropped:'not_staff'} or
						// 200 {ok:true, dropped:'clips_disabled'}, on purpose: a 200 stops a public
						// plugin error-retrying something the server meant to throw away. Counting
						// those as sent made segments_uploaded_at_emit overstate what reached R2,
						// so a manifest could read as a clean success for footage that does not
						// exist. Reachable today the moment a staff token gets clips: 'off' in
						// acct:policy_map, which account-ingest explicitly permits.
						//
						// A refusal is NOT retried. The server meant it, and retrying it would be
						// the error loop the 200 exists to prevent. The bytes are released and the
						// segment is counted as failed, which is what it is.
						if (dropSegmentWasRefused(response))
						{
							finishDropSegment(segment, sessionId, "refused");
							log.debug("OSRS BiS drop segment {} refused by the server with a 200",
								segment.index);
							return;
						}
						finishDropSegment(segment, sessionId, "uploaded");
						return;
					}
					// A 4xx other than 429 will fail identically forever, so it is not retried.
					int code = response.code();
					if (code == 429 || code >= 500)
					{
						retryOrGiveUp("http " + code);
					}
					else
					{
						// FINDING E2. A 403 IS A REVOCATION, NOT AN ORDINARY FAILURE.
						// /store-frames-ingest answers a revoked link with 403, from revocationBlock,
						// which runs before anything is stored. So no data reaches the server — but
						// without this the recorder keeps capturing the rendered screen and keeps
						// posting segments that will all get the same 403, until the player logs out.
						// Recording locally with nowhere to send is exactly what the grant being
						// withdrawn is supposed to stop.
						//
						// It clears the rollout flag rather than tearing the session down here,
						// because this runs on an OkHttp callback thread and the teardown touches
						// the DrawManager and the segmenter, which belong to the client thread.
						// Clearing the flag makes dropProofEnabled() false, and the next
						// pollDropSession — the client thread, next game tick — runs the SAME
						// withdrawal path an X-Drop-Proof: off header runs. One revocation path, not
						// two. A server that has not in fact revoked the link restores the flag on
						// its next policy response.
						//
						// The flag is cleared BEFORE the counters, so an observer that waits on the
						// segment count can never read the flag in its old state.
						if (code == 403)
						{
							plugin.grants.serverDropProofEnabled = false;
							log.debug("OSRS BiS drop segment {} got a 403: treating the grant as "
								+ "withdrawn", segment.index);
						}
						finishDropSegment(segment, sessionId, code == 403 ? "refused" : "failed");
						log.debug("OSRS BiS drop segment {} rejected: {}", segment.index, code);
					}
				}
				finally
				{
					response.close();
				}
			}

			private void retryOrGiveUp(String why)
			{
				if (attempt + 1 >= DROP_SEGMENT_RETRIES)
				{
					finishDropSegment(segment, sessionId, "failed");
					log.debug("OSRS BiS drop segment {} lost after {} attempts: {}",
						segment.index, DROP_SEGMENT_RETRIES, why);
					return;
				}
				try
				{
					plugin.executor.submit(() -> postDropSegment(base, token, segment, sessionId, attempt + 1, captureAccount));
				}
				catch (RuntimeException e)
				{
					finishDropSegment(segment, sessionId, "failed");
					log.debug("OSRS BiS drop segment {} retry could not be scheduled", segment.index, e);
				}
			}
			});
		}
		catch (RuntimeException e)
		{
			finishDropSegment(segment, sessionId, "failed");
			log.debug("OSRS BiS drop segment {} could not build or enqueue request", segment.index, e);
		}
	}

	/**
	 * Did a 2xx actually REFUSE the segment?
	 *
	 * /store-frames-ingest returns 200 {ok:true, dropped:'<why>'} for a deliberate drop, so the
	 * status code alone cannot distinguish a store from a refusal. The body is peeked with
	 * peekBody, which does NOT consume the response: the caller still closes it in its own finally
	 * block, and a peek that fails for any reason reports "not refused" so an unreadable body can
	 * never turn a real store into a phantom failure.
	 *
	 * The bound is small on purpose. A refusal body is a few dozen bytes; a larger body is a
	 * successful store and there is no reason to hold it in memory.
	 */
	static boolean dropSegmentWasRefused(Response response)
	{
		if (response == null)
		{
			return false;
		}
		try
		{
			String body = response.peekBody(DROP_REFUSAL_PEEK_BYTES).string();
			return body != null && body.contains("\"dropped\"");
		}
		catch (IOException | RuntimeException e)
		{
			return false;	// unreadable body: never invent a failure
		}
	}

	/** Enough of a 2xx body to see a refusal marker. A store's body is larger and is not read. */
	static final long DROP_REFUSAL_PEEK_BYTES = 512L;

	/**
	 * Multipart body for a drop segment.
	 *
	 * Same field names the store path uses for the frames themselves, so the server's existing parser
	 * handles them unchanged. The three EXTRA fields are what make a drop clip reassemble
	 * deterministically: the session id, the segment index and the media kind.
	 */
	static MultipartBody buildDropSegmentBody(DropFrameSegmenter.Segment segment, String token,
		String sessionId)
	{
		MultipartBody.Builder builder = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("token", token)
			.addFormDataPart("captured_at", Long.toString(segment.firstFrameMillis / 1000L))
			.addFormDataPart("fps", Integer.toString(DROP_CLIP_FPS))
			.addFormDataPart("kind", DROP_MEDIA_KIND)
			.addFormDataPart("drop_session_id", sessionId)
			.addFormDataPart("segment_index", Integer.toString(segment.index));
		for (int i = 0; i < segment.frames.size(); i++)
		{
			builder.addFormDataPart("frames[]", String.format("frame-%03d.jpg", i),
				RequestBody.create(AccountConnectPlugin.JPEG, segment.frames.get(i)));
		}
		builder.addFormDataPart("frame_count", Integer.toString(segment.frames.size()));
		// THE SEGMENT'S OWN SIZE, distinct from this attempt's size. Every attempt at this index
		// carries the same Segment object, so this value never changes between a first attempt and
		// a retry. The server refuses an attempt whose frame count disagrees with it, which is what
		// stops a short retry from silently replacing a complete attempt.
		builder.addFormDataPart("segment_frame_total", Integer.toString(segment.frameCount()));
		return builder.build();
	}

	/**
	 * Register a freshly spawned pile with the running session.
	 *
	 * Called from the ground-spawn path, where the pile first exists. The sequence number comes from
	 * the drop ACTION that armed it, so a removal can name the exact drop even when a session drops
	 * the same item onto the same tile twice.
	 */
	void attachPileToDropSession(OwnPiles.DroppedGroundItem g)
	{
		attachPileToDropSession(g, pendingDropSessionId, pendingDropSeq);
	}

	void attachPileToDropSession(OwnPiles.DroppedGroundItem g, String sid, int seq)
	{
		if (g == null || !dropSession.active())
		{
			return;
		}
		if (sid == null || seq <= 0 || !sid.equals(dropSession.sessionId()))
		{
			return;
		}
		dropPileSeq.put(g, new int[]{seq});
		dropPileSession.put(g, sid);
		dropSession.pileActive(DropSessionRecorder.pileKey(g.item, g.x, g.y, g.plane, seq), seq);
		markDropMoment(DropCaptureRate.Event.PILE_SPAWN, System.currentTimeMillis());
	}

	/**
	 * A drop MERGED into a pile that is already tracked on that tile. ADOPT the pile.
	 *
	 * FINDING F1 PATH A, and the reason the recorder used to run forever. Dropping a stackable onto
	 * an existing stack of the same item on the same tile does not spawn a second ground item: the
	 * game merges them and fires exactly ONE ItemDespawned when the merged stack goes. Tracking a
	 * second pile here produced a second session key that no despawn could ever release, so the
	 * tail never armed and only a logout stopped the recording.
	 *
	 * ROUND 5, FINDING B2 — WHY OWNERSHIP IS NOT PART OF THE MERGE DECISION. The caller used to look
	 * for the pile with liveSessionPileAt, which required membership of the CURRENT session. That is
	 * the right rule for ATTRIBUTION and the wrong rule for MERGE DETECTION: a merge is a fact about
	 * the game's ground state, not about who owns the pile. Three ordinary routes put a tracked pile
	 * on the tile that the session does not own, with no session ending at all:
	 *
	 *   1. ground tracking is gated on activityLogActive() and the session on dropProofEnabled(), so
	 *      a pile tracked before the grant arrives is still on the ground, unowned, when it opens;
	 *   2. the same, through the user's own upload switch going off and then back on;
	 *   3. a late pile whose own pending already expired (PENDING_DROP_EXPIRY_MILLIS), which
	 *      attachPileToDropSession refuses because pendingDropSeq is 0 by then.
	 *
	 * In every one of them the ownership test refused the pile, the caller minted a SECOND
	 * DroppedGroundItem for ONE physical pile, the single despawn resolved to the OLDEST match
	 * (the unowned entry), the session's key was never released and the recorder ran until a logout.
	 *
	 * So the pile is found regardless of ownership and ADOPTED into the running session: it takes
	 * this session's id and this drop's sequence number, and the recorder holds exactly one key for
	 * it. The one despawn the game fires then releases that one key. A pile the session ALREADY owns
	 * needs no adoption, only the pending resolved against it, because its key already exists.
	 */
	void adoptMergedPileIntoDropSession(OwnPiles.DroppedGroundItem existing, String sid, int seq)
	{
		if (existing == null || !dropSession.active())
		{
			return;
		}
		if (sid == null || seq <= 0 || !sid.equals(dropSession.sessionId()))
		{
			return;
		}
		if (sid.equals(dropPileSession.get(existing)))
		{
			// Already ours: one pile, one key, and the pending is resolved by the pile already live.
			dropSession.pendingDropResolved(seq);
			markDropMoment(DropCaptureRate.Event.PILE_SPAWN, System.currentTimeMillis());
			return;
		}
		// Not ours yet. Adopt it through the same call the spawn path uses, so the pile gets this
		// session's id, this drop's sequence and exactly ONE recorder key, and the pending is
		// resolved by that key rather than left outstanding.
		attachPileToDropSession(existing, sid, seq);
	}

	/**
	 * The client stopped tracking a pile without seeing it leave the ground.
	 *
	 * FINDING F1 PATH C. trackGroundDrop evicts the oldest pile past GROUND_TRACK_MAX, and nothing
	 * used to tell the session, so a 30-item drop trade stranded keys the recorder waited on
	 * forever. The key is released here so the tail can still arm, and the session is marked
	 * unprovable so the released key is never mistaken for the pile actually going: that session
	 * reports INTERRUPTED, never COMPLETE.
	 */
	void abandonPileFromDropSession(OwnPiles.DroppedGroundItem g)
	{
		if (g == null)
		{
			return;
		}
		dropSession.pileAbandoned(detachPile(g), System.currentTimeMillis());
	}

	/** Forget the pile's session link; its recorder key, or null when it was not part of a session (a no-op there). */
	private String detachPile(OwnPiles.DroppedGroundItem g)
	{
		int seq = dropSeqForPile(g);
		dropPileSeq.remove(g);
		dropPileSession.remove(g);
		return seq > 0 ? DropSessionRecorder.pileKey(g.item, g.x, g.y, g.plane, seq) : null;
	}

	/** The drop sequence a tracked pile belongs to, or -1 when it is not part of a session. */
	int dropSeqForPile(OwnPiles.DroppedGroundItem g)
	{
		int[] v = dropPileSeq.get(g);
		return v == null ? -1 : v[0];
	}

	/** The session id a tracked pile belongs to, or null. */
	String dropSessionForPile(OwnPiles.DroppedGroundItem g)
	{
		return dropPileSession.get(g);
	}

	/**
	 * Does the ground tracker still hold a pile the CURRENT session owns?
	 *
	 * ROUND 5. This is the caller half of the backstop invariant. The recorder waits on pile KEYS;
	 * this map is where the piles those keys stand for actually live. When the recorder holds a key
	 * and this returns false, nothing can ever release that key, because a removal is only reported
	 * for a pile that is tracked here.
	 */
	boolean dropSessionHoldsOwnedPile()
	{
		String sid = dropSession.sessionId();
		if (sid == null)
		{
			return false;
		}
		synchronized (plugin.piles.groundDrops)
		{
			for (OwnPiles.DroppedGroundItem g : plugin.piles.groundDrops)
			{
				if (sid.equals(dropPileSession.get(g)))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A tracked pile left the ground. Tells the session, which arms the 5-second tail when it was
	 * the last one.
	 */
	void releasePileFromDropSession(OwnPiles.DroppedGroundItem g)
	{
		if (g == null)
		{
			return;
		}
		String key = detachPile(g);
		if (key != null)
		{
			// Mark the moment FIRST. The pre-roll it flushes is the two seconds leading up to the
			// pile vanishing, which is the single most important stretch in the whole clip: it is
			// where a player walks onto the tile.
			markDropMoment(DropCaptureRate.Event.PILE_REMOVED, System.currentTimeMillis());
			dropSession.pileRemoved(key, System.currentTimeMillis());
		}
	}

	/**
	 * THE ONE TEARDOWN. Every path that ends a drop session calls exactly this, and nothing else
	 * clears drop-session state.
	 *
	 * WHY IT IS ONE METHOD. This teardown used to be five copied lines at three call sites, with a
	 * fourth half of it (clearGroundDrops) written out separately at the four GameState arms. The
	 * withdrawal path got the five lines and not the ground half, and that single omission is
	 * finding F1 path A coming back through a different door:
	 *
	 *   1. the maps are cleared while the pile is still physically on the ground and still tracked;
	 *   2. the player re-drops the same stackable onto the same tile, the game MERGES it into that
	 *      pile and will fire exactly ONE ItemDespawned for the result;
	 *   3. liveSessionPileAt refuses the stale pile, because dropPileSession no longer names it, so
	 *      trackGroundDrop mints a SECOND DroppedGroundItem for ONE physical pile;
	 *   4. the one despawn resolves to the oldest match, which is the stale entry, and the new
	 *      session's key is never released. The tail can never arm and the recorder never stops.
	 *
	 * So untracking the session's piles is part of ENDING a session, not a courtesy the GameState
	 * arms happen to perform first. Only piles this session owned are untracked: groundDrops also
	 * holds ordinary piles that have nothing to do with a drop session, and clearing those would
	 * silently drop their ground_removed events. The GameState arms keep their own broader
	 * clearGroundDrops() call, which is about the scene and the world, not about this session.
	 */
	private void endDropSessionTracking()
	{
		synchronized (plugin.piles.groundDrops)
		{
			// Read the ownership BEFORE the maps are cleared, or every pile looks unowned.
			List<OwnPiles.DroppedGroundItem> owned = new ArrayList<>();
			for (OwnPiles.DroppedGroundItem g : plugin.piles.groundDrops)
			{
				if (dropPileSession.get(g) != null || dropPileSeq.get(g) != null)
				{
					owned.add(g);
				}
			}
			plugin.piles.groundDrops.removeAll(owned);
		}
		dropPileSeq.clear();
		dropPileSession.clear();
		pendingDropSessionId = null;
		pendingDropSeq = 0;
		dropSessionToken = null;	// the next session binds its own identity; this one cannot return
	}

	/**
	 * Per-tick session poll: stop when the tail has elapsed.
	 *
	 * Reads the session's identity BEFORE finishing it, because finish() clears the state the
	 * manifest needs.
	 */
	void pollDropSession()
	{
		if (!dropSession.active())
		{
			return;
		}
		long now = System.currentTimeMillis();
		// CHECK THE GATE EVERY TICK, not only when capture starts. The user can untick the upload
		// switch, clear the token, or have the grant withdrawn while a session runs. Before this,
		// dropCapturing was only ever cleared by a stop, so capture continued through a withdrawal
		// and the frames taken during it uploaded as soon as the switch went back on (finding F2).
		if (!plugin.grants.dropProofEnabled())
		{
			discardDropSessionOnWithdrawnConsent();
			return;
		}
		// AND CHECK THE IDENTITY EVERY TICK. The config listener is the immediate path, but it is an
		// event from the client and this poll must not depend on one arriving. Both call the same
		// method, so the two paths cannot disagree about what a changed token means.
		if (!dropSessionIdentityIntact())
		{
			enforceDropSessionTokenIdentity();
			return;
		}
		// EXPIRE PENDING DROPS FIRST. A Drop click that never produces a pile leaves nothing for a
		// removal to release, so without this the tail can never arm and the session runs until a
		// logout (finding F1 path B).
		for (Integer expiredSeq : dropSession.settle(now))
		{
			if (expiredSeq != null && expiredSeq == pendingDropSeq)
			{
				pendingDropSeq = 0;
				pendingDropSessionId = null;
			}
		}
		// THE PHYSICAL CHECK (round 8). Ask the game, every poll, whether the piles this session
		// still holds keys for are actually on the ground. Runs BEFORE the backstops, so a key this
		// releases is counted by the invariant on this same poll.
		releaseSessionPilesGoneFromScene(now);
		// THE STRUCTURAL BACKSTOP (round 5). Two bounds that do not care how the state went wrong:
		// the recorder waiting on a key no tracked pile of this session can release, and a hard
		// maximum session duration. Both end the session as INTERRUPTED and name the bound in
		// outcome_reason. This runs AFTER settle, so a pending that expires on this very poll is
		// counted before the invariant reads it, and BEFORE shouldStop, so a bound that fires ends
		// the session on this same poll rather than a tick later.
		dropSession.enforceBackstops(now, dropSessionHoldsOwnedPile());
		if (!dropSession.shouldStop(now))
		{
			return;
		}
		String sid = dropSession.sessionId();
		int drops = dropSession.dropCount();
		long started = dropSession.startedAtMillis();
		String reason = dropSession.reason();		// read BEFORE finish(), which clears it
		DropSessionRecorder.Outcome outcome = dropSession.finish();
		endDropSessionTracking();
		stopDropCapture(outcome, sid, drops, started, reason);
	}

	/**
	 * ROUND 8, FINDING R5 — RELEASE A KEY WHOSE PILE THE SCENE SAYS IS GONE.
	 *
	 * The disclosure promises the recording stops five seconds after the final dropped pile
	 * disappears. Round 7 made that true in the EARLY direction and false in the LATE one: on the
	 * ambiguous route the despawn we saw was credited to the rival's record, so our own record
	 * lingers for a pile that is physically gone. dropSessionHoldsOwnedPile then keeps returning
	 * true, the 30-second orphaned-state invariant can never accumulate its grace, and only the
	 * 30-minute hard cap ends the session. That is up to 30 minutes of recording past the moment the
	 * disclosure names, and every one of those frames uploads.
	 *
	 * The game knows the pile is gone. This asks it once per poll: for every pile this session still
	 * holds a key for, is an item of that id still on that tile? NONE means the pile physically
	 * left, so the key is released and the tail arms normally, which is exactly the five seconds the
	 * disclosure promises. UNKNOWN — the tile is out of scene, or the scene could not be read —
	 * changes nothing, and the orphaned-state invariant and the hard cap remain the backstops for
	 * that case.
	 *
	 * THE SESSION IS MARKED UNPROVABLE by the release, so this can never manufacture a COMPLETE. See
	 * DropSessionRecorder.pileGoneFromScene.
	 */
	/**
	 * ROUND 9 — WHICH of our own piles on one tile are the ones that left.
	 *
	 * {@code release} is already decided by the count. This only chooses WHICH records it names.
	 * Every quantity the scene still shows is matched against one of our records, oldest first; a
	 * record that survives the matching is still physically down. What is left over went, and the
	 * OLDEST {@code release} of those are released. When the reader cannot expose quantities, the
	 * oldest {@code release} records are released, which is the order they were dropped in.
	 *
	 * A scene quantity that matches NONE of our records is a stranger's pile. It is simply not
	 * matched, and because the return is capped at {@code release} it can never cause an extra key
	 * to be released.
	 */
	private static List<OwnPiles.DroppedGroundItem> pilesGoneByQuantity(List<OwnPiles.DroppedGroundItem> own,
		OwnPiles.SceneTileItems scene, int release)
	{
		List<OwnPiles.DroppedGroundItem> candidates = new ArrayList<>(own);
		if (scene.quantities != null)
		{
			for (long q : scene.quantities)
			{
				for (Iterator<OwnPiles.DroppedGroundItem> it = candidates.iterator(); it.hasNext(); )
				{
					if (it.next().qty == q)
					{
						it.remove();	// this record is still physically down
						break;
					}
				}
			}
		}
		List<OwnPiles.DroppedGroundItem> out = new ArrayList<>();
		for (OwnPiles.DroppedGroundItem g : candidates)
		{
			if (out.size() >= release)
			{
				break;
			}
			out.add(g);
		}
		return out;
	}

	void releaseSessionPilesGoneFromScene(long nowMillis)
	{
		String sid = dropSession.sessionId();
		if (sid == null)
		{
			return;
		}
		List<OwnPiles.DroppedGroundItem> gone = null;
		synchronized (plugin.piles.groundDrops)
		{
			// ROUND 9, FINDING R8 — COUNT PER (ITEM, TILE), NOT PRESENCE.
			//
			// Round 8 asked the tile a yes/no question once per RECORD, so two of our records for
			// one item on one tile asked the SAME question and got the SAME answer: released
			// together or kept together. The count says how many of ours went. We own |S| records
			// there and the scene shows C piles of that id, so |S| - C of ours have left. Release
			// exactly that many and no more, choosing by QUANTITY where the reader exposes it, so
			// the key released stands for a pile that is really gone. When the scene cannot say
			// which quantities remain, the OLDEST are released, which is the order they were
			// dropped in.
			//
			// CONSERVATIVE ON ANY DOUBT. UNKNOWN releases nothing. A count at or above ours releases
			// nothing. Keeping a key too long is bounded by the invariant and the hard cap;
			// releasing one too early would shorten a recording the disclosure promised.
			Map<String, List<OwnPiles.DroppedGroundItem>> byTile = new LinkedHashMap<>();
			for (OwnPiles.DroppedGroundItem g : plugin.piles.groundDrops)
			{
				if (!sid.equals(dropPileSession.get(g)))
				{
					continue;
				}
				// The ITEM ID is part of the key. A tile emptied of some other item says nothing
				// about our pile, and reading "the tile is clear" off the wrong id would release a
				// key while our pile is still lying there.
				byTile.computeIfAbsent(g.item + ":" + g.x + ":" + g.y + ":" + g.plane,
					k -> new ArrayList<>()).add(g);
			}
			for (List<OwnPiles.DroppedGroundItem> own : byTile.values())
			{
				OwnPiles.DroppedGroundItem first = own.get(0);
				OwnPiles.SceneTileItems scene = plugin.piles.sceneTileItems(first.item, first.x, first.y, first.plane, null);
				if (scene.state == OwnPiles.ScenePileState.UNKNOWN)
				{
					continue;
				}
				int release = own.size() - scene.count;
				if (release <= 0)
				{
					continue;
				}
				for (OwnPiles.DroppedGroundItem g : pilesGoneByQuantity(own, scene, release))
				{
					if (gone == null)
					{
						gone = new ArrayList<>();
					}
					gone.add(g);
				}
			}
			if (gone != null)
			{
				plugin.piles.groundDrops.removeAll(gone);
			}
		}
		if (gone == null)
		{
			return;
		}
		for (OwnPiles.DroppedGroundItem g : gone)
		{
			dropSession.pileGoneFromScene(detachPile(g), nowMillis);
		}
	}

	/**
	 * A hop, logout, disconnect or scene reload happened mid-session.
	 *
	 * The footage is uploaded and the manifest says INTERRUPTED. Discarding it would delete the
	 * evidence for exactly the sessions somebody will need to look at.
	 */
	void interruptDropSession()
	{
		if (!dropSession.active())
		{
			return;
		}
		String sid = dropSession.sessionId();
		int drops = dropSession.dropCount();
		long started = dropSession.startedAtMillis();
		dropSession.interrupt();
		String reason = dropSession.reason();		// read BEFORE finish(), which clears it
		DropSessionRecorder.Outcome outcome = dropSession.finish();
		endDropSessionTracking();
		stopDropCapture(outcome, sid, drops, started, reason);
	}

	/**
	 * CONSENT WAS WITHDRAWN MID-SESSION. End the session and DESTROY what was captured.
	 *
	 * This is the one path where footage is deliberately thrown away, and it is the opposite of
	 * interruptDropSession on purpose. A hop or a logout is an accident of the session and the
	 * user still consents, so that footage is evidence and it uploads. Unticking the upload switch,
	 * clearing the token or losing the grant is the user or the operator saying stop, so the frames
	 * already in the buffer must not survive to be uploaded when the switch goes back on. Finding
	 * F2 measured exactly that leak: OFF-window frames reached the uploader after a re-enable.
	 *
	 * No manifest is emitted either. A manifest is an upload about a recording the user withdrew
	 * consent for, so publishing one would be the same disclosure defect in a smaller shape.
	 */
	void discardDropSessionOnWithdrawnConsent()
	{
		String discardedSessionId = dropSession.sessionId();
		DropSessionAudit discardedAudit = discardedSessionId == null ? null : dropAudits.get(discardedSessionId);
		if (dropSession.active())
		{
			dropSession.interrupt();
			dropSession.finish();
		}
		endDropSessionTracking();
		dropCapturing = false;
		dropFramePending = false;
		if (plugin.drawManager != null)
		{
			plugin.drawManager.unregisterEveryFrameListener(dropFrameTick);
		}
		DropFrameSegmenter seg = dropSegmenter;
		dropSegmenter = null;
		if (discardedAudit != null)
		{
			synchronized (discardedAudit)
			{
				discardedAudit.segmenter = seg;
				discardedAudit.declaredSegments = seg == null
					? discardedAudit.segmentResults.size() : seg.segmentCount();
				// Set the no-publish tombstone BEFORE closed. maybeEmitFinalDropManifest uses the same
				// monitor, so no callback can observe closed=true while finalEmitted is still false.
				discardedAudit.finalEmitted = true;
				discardedAudit.closed = true;
			}
			if (discardedAudit.segmentResults.size() >= discardedAudit.declaredSegments)
			{
				dropAudits.remove(discardedSessionId, discardedAudit);
			}
		}
		if (seg != null)
		{
			// discardAll, NOT clear. clear() empties only the segment being filled and leaves the
			// RETRY BUFFER accounted, so a segment already handed to the uploader would still be
			// retried under whatever token is configured by then. discardAll drops that accounting
			// and latches the segmenter closed, so this object can never yield another segment even
			// to a caller still holding a reference to it.
			seg.discardAll();
		}
		DropCaptureRate rate = dropRate;
		dropRate = null;
		if (rate != null)
		{
			rate.clear();		// the held pre-roll dies too
		}
	}

	/**
	 * The drop-proof capability may have just changed. Called after every policy application.
	 *
	 * A capability that has gone away must stop a running recorder now rather than at the next tick.
	 */
	void onDropProofCapabilityChanged()
	{
		if (!plugin.grants.dropProofEnabled())
		{
			if (dropSession.active() || dropCapturing)
			{
				discardDropSessionOnWithdrawnConsent();
			}
		}
	}

	/**
	 * Players observed right now, as plain values, for pile-centred candidate resolution.
	 *
	 * Separate from nearbyPlayersSnapshot, which measures from OUR character. In a drop trade the
	 * staff member routinely walks away before the customer arrives, so a distance measured from us
	 * says nothing about who was on the pile.
	 */
	List<DropCandidates.Observed> observedPlayers()
	{
		if (!plugin.grants.captureEnabled()) return Collections.emptyList();

		List<DropCandidates.Observed> out = new ArrayList<>();
		if (plugin.client == null)
		{
			return out;
		}
		for (Player p : WildernessPlayers.otherPlayers(plugin.client))
		{
			WorldPoint loc = p.getWorldLocation();
			out.add(new DropCandidates.Observed(Text.removeTags(p.getName()),
				loc.getX(), loc.getY(), loc.getPlane(), p.getCombatLevel(), p.getAnimation()));
		}
		return out;
	}
}
