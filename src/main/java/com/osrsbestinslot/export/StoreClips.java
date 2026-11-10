package com.osrsbestinslot.export;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Staff store clips: frame sampling during a shop visit and the chunked upload after a buy or sell. */
@Slf4j
@RequiredArgsConstructor
final class StoreClips
{
	private final AccountConnectPlugin plugin;

	// ---- store delivery-proof: burst frame capture (Task B2) ----
	// While a shop is open, sample the render at a WALL-CLOCK rate into a bounded StoreVisitClip. On
	// shop-close, if the visit had a buy/sell the frames go to the (B3) uploader; otherwise they are
	// dropped. Frames are captured raw here — no video encode in the plugin; the server stitches them.
	// SAMPLE RATE — raised 1 -> 3 on 2026-09-02, with the retained window cut 120s -> 40s so the ring
	// stays at 120 frames. A general-store delivery is EVIDENCE a customer may dispute, and at 1fps the
	// click that moves an item can fall between two frames entirely: the quantity menu, the click and
	// the inventory change all happen inside one second.
	//
	// 30fps — the client's own render rate, so the clip IS the delivery rather than a flipbook of it.
	// 1 -> 3 -> 8 were each rejected on review; at 8fps (125ms) the cursor still steps between positions
	// and the review verdict was "it doesn't feel like a clip, its absolutely lagging". 30fps is 33ms,
	// which is motion. There is no rate above this worth having: the client does not render faster.
	//
	// Paying for it: 30fps x 12s = 360 frames, and 360 frames only fitted the old 12MB burst cap at a smaller
	// frame. Measured on a real captured frame: 768px/q0.70 = 45KB (16.2MB — over), 704px/q0.55 = 30KB
	// (10.8MB — fits). The 704px/q0.55 frame was checked for LEGIBILITY, not just size: shop item text
	// and chat remain readable, which is the property MAX_FRAME_WIDTH exists to protect.
	//
	// 360 frames is the per-VISIT frame budget. It used to be the NEWEST 12 seconds of the visit, and
	// that lost the transaction whenever the visit ran longer than 12 seconds after the last sale
	// (Arengees 2026-09-25: sells 16:37:59-16:39:51, frames 16:39:55-16:40:06, no sale on video).
	// The same budget is now spent by StoreVisitClip on the visit start, a window around every
	// buy / sell / taken moment, and a 1fps baseline between them.
	static final int CLIP_FPS = 30;					// sample rate (constant, NOT a user setting)
	static final int CLIP_SECONDS = 12;				// budget expressed as seconds at CLIP_FPS
	static final int MAX_CLIP_FRAMES = CLIP_FPS * CLIP_SECONDS;	// per-visit frame budget = 360 frames
	// Task-0 legibility verdict (PRD): 768px keeps store text readable at the server stitch size.
	// (Plan body text says 640px; 768 is the ratified Task-0 override.) Lowered 768 -> 704 on 2026-09-02
	// to pay for 30fps: 360 frames only fitted the old 12MB burst cap at ~30KB/frame. 704 was chosen over 640
	// because it was CHECKED for legibility — shop item text and chat still read at 704/q0.55, and store
	// text is the thing being proven. Below 704 that stops being true, so this is a floor, not a knob.
	static final int MAX_FRAME_WIDTH = 704;
	// Server ingest caps (mirror /store-frames-ingest): a JPEG over this is dropped; the visit keeps the
	// newest suffix that fits both the frame-count cap and this total-bytes cap.
	//
	// ⚠ MAX_CLIP_BURST_BYTES bounds the WHOLE VISIT here, not one POST. Since the upload is chunked
	// (CLIP_CHUNK_FRAMES), the server's per-request limit is never the binding constraint — a
	// 40-frame chunk at ~45KB is ~1.8MB. The visit budget is 16 MiB (owner decision PIO-014, 2026-09-27,
	// was 12MB): a 360-frame visit at ~44KB/frame (~15.8MB) now keeps every frame at the current quality.
	// It caps what one shop visit can ever cost in memory and upload.
	static final int MAX_CLIP_FRAME_BYTES = 1_000_000;		// 1MB per frame
	static final int MAX_CLIP_BURST_BYTES = 16 * 1024 * 1024;	// 16 MiB per VISIT (all chunks together)
	/** Armed between shop-open and shop-close while capture is running. */
	volatile boolean clipCapturing;
	/** Set the moment a general-store buy/sell fires during the visit — a visit without one is dropped. */
	volatile boolean storeTxThisVisit;
	/** The visit's kept frames (start, every moment, baseline); created per capture, cleared on stop. */
	volatile StoreVisitClip clipVisit;
	/** Wall time (ms) the frame in flight was sampled at. The frame is kept at THIS time, not its encode time. */
	private volatile long clipFrameSampledAtMillis;
	/** Wall-clock (nanoTime) of the next frame to sample; a render tick before this is skipped. */
	private volatile long nextClipSampleAt;
	/** Set on a sampled tick to request one frame from DrawManager; cleared when that frame arrives. */
	volatile boolean clipFramePending;
	/** Per-frame render callback, registered with DrawManager only while capturing. */
	private final Runnable clipFrameTick = this::onClipFrameTick;

	/**
	 * Arm capture for a shop visit: fresh ring, reset the decimation clock + tx flag, and start listening
	 * to the render loop. Idempotent — a second shop-open while already capturing is ignored.
	 */
	void startStoreClipCapture()
	{
		if (clipCapturing || !plugin.grants.storeClipsEnabled())
		{
			return;
		}
		clipVisit = new StoreVisitClip(MAX_CLIP_FRAMES, MAX_CLIP_BURST_BYTES, MAX_CLIP_FRAME_BYTES);
		storeTxThisVisit = false;
		nextClipSampleAt = 0L;			// 0 => the first render tick samples immediately
		clipFramePending = false;
		clipCapturing = true;
		plugin.drawManager.registerEveryFrameListener(clipFrameTick);
	}

	/**
	 * Wall-clock 1-in-time decimation: returns true at most once per (1/CLIP_FPS) second regardless of
	 * render fps (render fluctuates ~20-50fps, so a frame-count divisor would drift). Advances the gate
	 * on every hit so it is a steady rate, not a one-shot.
	 */
	boolean shouldSampleClipFrame(long nowNanos)
	{
		if (nowNanos < nextClipSampleAt)
		{
			return false;
		}
		final long period = 1_000_000_000L / CLIP_FPS;
		// Advance from the SCHEDULED boundary, not from the observed tick.
		//
		// `nextClipSampleAt = nowNanos + period` looks equivalent and is not: a render tick lands on its
		// own grid (~20ms at 50fps), so each sample fires slightly AFTER its boundary and that remainder
		// is then baked into the next deadline. The error compounds. At 1fps it hid — 20ms against a
		// 1000ms period — but at 8fps it is 20ms against 125ms and it cost a real frame every two
		// seconds: measured 15 samples where 16 were due, ~6% of a 25-second clip silently missing.
		// Advancing the deadline by whole periods keeps the rate exact over any span.
		//
		// After a long gap (a hitch, or a client that was not rendering) the deadline can sit far in the
		// past; walking it forward in whole periods would then fire a burst of catch-up samples on
		// consecutive frames. Re-base to `nowNanos` in that case: a gap loses frames either way, and
		// duplicating the same instant several times is not evidence.
		if (nextClipSampleAt == 0L || nowNanos - nextClipSampleAt >= period)
		{
			nextClipSampleAt = nowNanos + period;
		}
		else
		{
			nextClipSampleAt += period;
		}
		return true;
	}

	/**
	 * Render-loop callback (runs every frame while capturing). Decimates by wall clock, then asks
	 * DrawManager for the next composited frame; that frame arrives on the consumer, is converted to an
	 * RGB (no-alpha) buffer, downscaled, JPEG-ENCODED, and the BYTES are stored.
	 *
	 * ⚠ Encoding happens HERE, per frame, not at upload time — the ring must never hold decoded images
	 * (see ClipRingBuffer's header: 360 raw frames is ~400MB and freezes the client). The encode runs on
	 * the executor so the render thread is not blocked by ImageIO, and clipFramePending is cleared only
	 * once that encode has finished, so at most ONE frame is ever in flight and the ring stays in
	 * chronological order even though the executor is a shared pool.
	 */
	void onClipFrameTick()
	{
		if (!clipCapturing || clipFramePending)
		{
			return;
		}
		if (!shouldSampleClipFrame(System.nanoTime()))
		{
			return;
		}
		clipFramePending = true;
		final long sampledAt = System.currentTimeMillis();
		clipFrameSampledAtMillis = sampledAt;
		plugin.drawManager.requestNextFrameListener(img ->
		{
			if (img == null)
			{
				clipFramePending = false;
				return;
			}
			// Copy + downscale on this thread: `img` is DrawManager's buffer and is not ours to keep.
			final BufferedImage scaled = downscaleRgb(toRgbFrame(img), MAX_FRAME_WIDTH);
			if (scaled == null)
			{
				clipFramePending = false;
				return;
			}
			plugin.executor.submit(() ->
			{
				try
				{
					StoreVisitClip visit = clipVisit;
					if (visit != null)
					{
						visit.offer(AccountConnectPlugin.encodeJpeg(scaled), sampledAt);	// null/empty is ignored
					}
				}
				finally
				{
					clipFramePending = false;
				}
			});
		});
	}

	/**
	 * Stop capturing: unregister the render listener, snapshot the ring, and — only when upload is
	 * requested AND the visit had a buy/sell — hand the frames to the uploader. Always clears state so a
	 * dropped visit leaks nothing. Idempotent.
	 */
	void stopStoreClipCapture(boolean upload)
	{
		if (!clipCapturing)
		{
			return;
		}
		clipCapturing = false;
		plugin.drawManager.unregisterEveryFrameListener(clipFrameTick);
		StoreVisitClip visit = clipVisit;
		clipVisit = null;
		clipFramePending = false;
		boolean hadTx = storeTxThisVisit;
		storeTxThisVisit = false;
		if (visit == null)
		{
			return;
		}
		if (!upload || !hadTx)
		{
			visit.clear();		// no purchase, or shutdown/hop drop — discard without uploading
			return;
		}
		StoreVisitClip.Snapshot snap = visit.snapshot();	// already JPEG-encoded at capture time
		visit.clear();
		if (!snap.frames.isEmpty())
		{
			plugin.submitStoreClipUpload(snap.frames, snap.capturedAtMillis);
		}
	}

	/**
	 * A buy / sell click or a store_taken during a capturing visit. The clip keeps the seconds before
	 * and after it at the full rate. No-op when no visit is capturing.
	 */
	void markStoreClipMoment()
	{
		StoreVisitClip visit = clipVisit;
		if (clipCapturing && visit != null)
		{
			visit.onMoment(System.currentTimeMillis());
		}
	}

	/**
	 * Copy any rendered Image into a TYPE_INT_RGB (no-alpha) BufferedImage. The JDK JPEG writer corrupts
	 * ARGB rasters, so store-clip frames are always RGB. This is deliberately SEPARATE from the trade
	 * path's ARGB ImageUtil.bufferedImageFromImage — never reuse that here.
	 */
	static BufferedImage toRgbFrame(Image img)
	{
		if (img == null)
		{
			return null;
		}
		int w = img.getWidth(null);
		int h = img.getHeight(null);
		if (w <= 0 || h <= 0)
		{
			return null;
		}
		BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return rgb;
	}

	/**
	 * Downscale to at most maxWidth (aspect preserved) into a fresh TYPE_INT_RGB buffer. Frames already
	 * within maxWidth are returned unchanged. Keeps store text legible at the server stitch size (Task-0).
	 */
	static BufferedImage downscaleRgb(BufferedImage src, int maxWidth)
	{
		if (src == null || maxWidth <= 0 || src.getWidth() <= maxWidth)
		{
			return src;
		}
		int w = maxWidth;
		int h = Math.max(1, (int) Math.round(src.getHeight() * (maxWidth / (double) src.getWidth())));
		BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = dst.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return dst;
	}

	/**
	 * Encode the visit's frames to JPEG on the background executor, keep the newest suffix that fits the
	 * server caps, and POST them as ONE multipart burst to /store-frames-ingest. Mirrors the trade-upload
	 * okhttp idioms: token guard, apiBaseUrl trim, RequestBody.create(MediaType, bytes), async enqueue.
	 * Encoding + upload run off the render thread; a bad token or an empty burst is a silent no-op.
	 */
	/**
	 * Max frames in ONE POST. This is NOT the clip length — a visit's frames are split across as many
	 * chunked bursts as it takes, and the collector reassembles them into one video.
	 *
	 * Why chunk at all: the ingest endpoint REJECTS a burst over its frame cap with a 400 rather than
	 * trimming it, and the deployed cap is 120. A 30fps clip is 360 frames. Chunking means the clip
	 * length stops being hostage to the server cap: 30fps works against the CURRENTLY DEPLOYED server,
	 * today, and a longer clip later costs another chunk rather than another deploy.
	 *
	 * 100, not 120, on purpose — the plugin cannot see the server's cap, so it sits below the smallest
	 * one that has ever been deployed rather than exactly at the current one.
	 */
	// SUBREQUEST BUDGET, not a size preference (lowered 100 -> 40 on 2026-09-03). The server writes one
	// R2 object per frame inside ONE Worker invocation, and a Worker has a hard subrequest ceiling: a
	// 100-frame chunk killed it mid-loop, leaving orphaned frames, no manifest and no database row, and
	// the plugin saw a dead socket rather than an error. Measured on production: real chunks died at 49,
	// 45 and 48 frames written. 40 keeps the server inside its budget. This value is HALF of a contract
	// with the server's MEDIA.FRAMES_PER_REQUEST_MAX — raising it here alone silently loses every clip.
	static final int CLIP_CHUNK_FRAMES = 40;

	/**
	 * Upload timeout for ONE clip chunk. Sized to the payload, not to a page fetch.
	 * A 40-frame chunk at the measured ~37KB/frame is ~1.5MB; the same shape took ~6s in a production
	 * probe and a 100-frame chunk took 13.9-16.6s. 90s leaves room for a slow domestic uplink without
	 * hanging a staff client forever. The shared RuneLite client's 10s default is what silently
	 * destroyed every clip before 2026-09-03 (see postStoreClipChunk).
	 */
	static final int CLIP_UPLOAD_TIMEOUT_SECONDS = 90;

	/*
	 * POST one chunk of a (possibly multi-chunk) store-clip burst (postStoreClipChunk below).
	 *
	 * ⚠ THE TIMEOUT IS THE WHOLE POINT OF THIS METHOD'S CLIENT OVERRIDE (2026-09-03).
	 * RuneLite's injected OkHttpClient keeps OkHttp's DEFAULT 10s read/write timeouts, and a real
	 * frame chunk does not upload in 10s on an ordinary connection. The client abandoned the request
	 * mid-body, the server had already written part of the burst to storage, and because the request
	 * was CANCELLED rather than failed, the server's own cleanup never ran either: orphaned frames,
	 * no manifest, no database row, and nothing logged above debug. Every delivery clip from the
	 * chunked-upload release until this fix was destroyed exactly this way.
	 *
	 * Reproduced on production 2026-09-03, one chunk, two arms, identical bytes:
	 *   10s cap -> 49 objects stored, NO manifest (the exact production signature)
	 *   60s cap -> 101 objects stored, manifest written, 200 OK
	 *
	 * So the upload gets its own client with a timeout sized to the payload, not to a page fetch.
	 * Do NOT drop this back to the shared client.
	 */

	/**
	 * Attempts for ONE store-clip chunk, the first included (finding F-A3, 2026-09-25).
	 *
	 * Before this, one failed POST lost the whole chunk silently: a 500/503 from a loaded server or
	 * a dropped connection, logged only at debug. The rig lost three sell visits exactly this way
	 * while the activity events of the same minutes were retried and arrived. So a chunk is retried
	 * on a network failure, a 5xx or a 429, and never on another 4xx or a 200 refusal.
	 */
	static final int CLIP_CHUNK_ATTEMPTS = 5;

	/**
	 * Wait before retry 1..4. Sum 7 minutes; with the 90s call timeout on every attempt the worst
	 * case window is about 14 minutes, and the chunk bytes are released at its end either way.
	 * The first retry is short on purpose: the media collector joins a chunk into its visit only
	 * when the server stores it within 120s of the visit's other chunks, so an early success keeps
	 * the video in one piece. A later success is still stored, as its own short clip.
	 */
	static final long[] CLIP_CHUNK_RETRY_DELAYS_MS = {15_000L, 45_000L, 120_000L, 240_000L};

	/**
	 * A 429 Retry-After longer than this is not waited for: the chunk is given up instead. Retrying
	 * early would ignore what the server asked for, and holding the bytes for up to an hour would
	 * break the bounded retry window.
	 */
	static final long CLIP_RETRY_AFTER_MAX_MS = 240_000L;

	/**
	 * Total bytes of chunks waiting for a retry, across all visits. Two full visits (2 x 16 MiB = 32 MiB). A chunk that
	 * would push the total over this is given up rather than held, so a long server outage cannot
	 * grow memory without bound. Same idea as the drop path's unacked byte budget.
	 */
	static final long CLIP_RETRY_BYTE_BUDGET = 2L * MAX_CLIP_BURST_BYTES;

	/** Bytes currently held for a pending store-clip retry. Never above CLIP_RETRY_BYTE_BUDGET. */
	final AtomicLong clipRetryHeldBytes = new AtomicLong();
	/** Chunks the server stored. */
	final AtomicInteger clipChunksSent = new AtomicInteger();
	/** Chunks that will never be stored: attempts spent, not retryable, or no room to hold them. */
	final AtomicInteger clipChunksLost = new AtomicInteger();

	/**
	 * One attempt for one chunk. `held` is true when this chunk's bytes are already counted in
	 * clipRetryHeldBytes (every retry), so the count is released exactly once when the chunk ends.
	 *
	 * A RETRY RESENDS THE SAME BODY: the same frames and the same captured_at. The server does not
	 * dedupe: every stored attempt is a new random directory and a new account_media row. That is
	 * harmless for a real failure (a 5xx, a 429 or a refused connection stores no row), but when the
	 * server stored the chunk and the reply was lost (a timeout after the insert), the retry stores a
	 * second row. That second row carries the same (link_token, captured_at, frame_count, bytes), so
	 * a consumer can recognise it. The plugin cannot tell the two cases apart from its side.
	 */
	void postStoreClipChunk(String base, String token, List<byte[]> chunk, long capturedAt,
		int attempt, boolean held, String captureAccount)
	{
		if (!plugin.grants.captureAccountMatches(captureAccount) || !plugin.grants.storeClipsEnabled() || !plugin.uploadAllowed() || !token.equals(plugin.currentLinkToken()))
		{
			// Uploads switched off, or a retry after the user linked a different token: this chunk
			// was captured under the old token and must not be filed under the new one.
			endStoreClipChunk(chunk, held, false, "upload no longer allowed for this token", attempt);
			return;
		}
		Request request = new Request.Builder()
			.url(base + "/store-frames-ingest")
			.post(ServerGrants.withMediaAccount(buildStoreClipBody(chunk, token, capturedAt, CLIP_FPS), captureAccount))
			.build();

		uploadClient(plugin.okHttpClient).newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				retryOrGiveUp(e == null ? "io" : String.valueOf(e.getMessage()), 0L);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					int code = response.code();
					if (response.isSuccessful())
					{
						// A 200 {"dropped":...} is a deliberate refusal (not staff, clips off).
						// Retrying it is the error loop that 200 exists to prevent.
						if (DropProof.dropSegmentWasRefused(response))
						{
							endStoreClipChunk(chunk, held, false, null, attempt);
							log.debug("OSRS BiS store-clip chunk refused by the server with a 200");
							return;
						}
						endStoreClipChunk(chunk, held, true, null, attempt);
						return;
					}
					if (code == 429)
					{
						Long ra = SnapshotUploader.parseRetryAfterSeconds(response.header("Retry-After"));
						retryOrGiveUp("http 429", ra == null ? 0L : ra * 1000L);
					}
					else if (code >= 500)
					{
						retryOrGiveUp("http " + code, 0L);
					}
					else
					{
						// Any other 4xx fails identically on every attempt.
						endStoreClipChunk(chunk, held, false, "http " + code, attempt);
					}
				}
				finally
				{
					response.close();
				}
			}

			private void retryOrGiveUp(String why, long retryAfterMs)
			{
				if (attempt + 1 >= CLIP_CHUNK_ATTEMPTS)
				{
					endStoreClipChunk(chunk, held, false, why, attempt);
					return;
				}
				if (retryAfterMs > CLIP_RETRY_AFTER_MAX_MS)
				{
					endStoreClipChunk(chunk, held, false, why + ", Retry-After " + (retryAfterMs / 1000L)
						+ "s is longer than the retry window", attempt);
					return;
				}
				if (!held && !reserveClipRetryBytes(chunkBytes(chunk)))
				{
					endStoreClipChunk(chunk, false, false, why + ", retry memory budget full", attempt);
					return;
				}
				long delay = Math.max(CLIP_CHUNK_RETRY_DELAYS_MS[attempt], retryAfterMs);
				plugin.scheduleClipRetry(() -> postStoreClipChunk(base, token, chunk, capturedAt, attempt + 1, true, captureAccount),
					delay);
			}
		});
	}

	/** Add bytes to the retry total if they fit the budget. False (and nothing added) if not. */
	boolean reserveClipRetryBytes(long bytes)
	{
		while (true)
		{
			long cur = clipRetryHeldBytes.get();
			if (cur + bytes > CLIP_RETRY_BYTE_BUDGET)
			{
				return false;
			}
			if (clipRetryHeldBytes.compareAndSet(cur, cur + bytes))
			{
				return true;
			}
		}
	}

	static long chunkBytes(List<byte[]> chunk)
	{
		long n = 0;
		for (byte[] b : chunk)
		{
			n += b == null ? 0 : b.length;
		}
		return n;
	}

	/**
	 * The chunk is finished: stored, refused or lost. Releases its retry bytes (when held) and
	 * counts it. `lossReason` non-null means lost, and that is logged at WARN: log only, never
	 * chat text. A null reason with stored=false is a deliberate server refusal, not a loss.
	 */
	private void endStoreClipChunk(List<byte[]> chunk, boolean held, boolean stored,
		String lossReason, int attempt)
	{
		if (held)
		{
			clipRetryHeldBytes.addAndGet(-chunkBytes(chunk));
		}
		if (stored)
		{
			clipChunksSent.incrementAndGet();
			plugin.events.healthLastMediaUploadMs.set(System.currentTimeMillis());
			return;
		}
		if (lossReason != null)
		{
			clipChunksLost.incrementAndGet();
			log.warn("OSRS BiS store-clip chunk ({} frames) lost after {} attempt(s): {}",
				chunk.size(), attempt + 1, lossReason);
		}
	}

	/**
	 * Choose which encoded frames go in the burst. Pass 1: drop any frame that failed to encode or exceeds
	 * the per-frame cap. Pass 2: keep the NEWEST suffix that fits both maxFrames and maxBurstBytes, walking
	 * from the END — the shop-close / sale frames are the actual delivery evidence, so an oversized burst
	 * must drop the OLDEST frames, never the newest. Chronological order within the kept suffix is preserved.
	 * Returns indexes into `encoded`, so a caller can keep each frame's capture time.
	 */
	static List<Integer> selectStoreClipFrameIndexes(List<byte[]> encoded, int maxFrames, int maxFrameBytes, int maxBurstBytes)
	{
		List<Integer> valid = new ArrayList<>(encoded.size());
		for (int i = 0; i < encoded.size(); i++)
		{
			byte[] b = encoded.get(i);
			if (b != null && b.length > 0 && b.length <= maxFrameBytes)
			{
				valid.add(i);
			}
		}
		int start = valid.size();
		long total = 0;
		int kept = 0;
		while (start > 0 && kept < maxFrames && total + encoded.get(valid.get(start - 1)).length <= maxBurstBytes)
		{
			total += encoded.get(valid.get(start - 1)).length;
			start--;
			kept++;
		}
		return new ArrayList<>(valid.subList(start, valid.size()));
	}

	/**
	 * Build the one multipart burst body: token, captured_at, fps, then the kept frames[] parts, then
	 * frame_count LAST so it always equals the number of parts actually attached (the server 400s on a
	 * mismatch). Frames are JPEG, named frame-000.jpg upward in chronological order.
	 */
	static MultipartBody buildStoreClipBody(List<byte[]> kept, String token, long capturedAt, int fps)
	{
		MultipartBody.Builder builder = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("token", token)
			.addFormDataPart("captured_at", Long.toString(capturedAt))
			.addFormDataPart("fps", Integer.toString(fps));
		for (int i = 0; i < kept.size(); i++)
		{
			builder.addFormDataPart("frames[]", String.format("frame-%03d.jpg", i),
				RequestBody.create(AccountConnectPlugin.JPEG, kept.get(i)));
		}
		builder.addFormDataPart("frame_count", Integer.toString(kept.size()));
		return builder.build();
	}

	/** The shared client with the clip upload's write, read and call timeouts (store clips and drop segments). */
	static OkHttpClient uploadClient(OkHttpClient base)
	{
		return base.newBuilder()
			.writeTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.readTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.callTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.build();
	}
}
