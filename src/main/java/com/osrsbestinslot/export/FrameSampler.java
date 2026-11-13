package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;

/**
 * The staff frame sampler that the store clip and the drop recorder both use: it samples the drawn frame at
 * CLIP_FPS by wall clock, copies and downscales it on the frame consumer, JPEG-encodes it on the executor and
 * hands the bytes to its owner. At most one frame is in flight, so the owner sees them in order.
 */
abstract class FrameSampler
{
	/** Set on a sampled tick to request one frame; cleared when that frame is encoded or turns out empty. */
	volatile boolean pending;
	/** Wall-clock (nanoTime) of the next frame to sample; a render tick before this is skipped. */
	private volatile long nextSampleAt;
	/** Per-frame render callback, registered with DrawManager only while capturing. */
	final Runnable tick = this::onFrame;

	abstract AccountConnectPlugin plugin();

	/** The owner's capture flag. */
	abstract boolean capturing();

	/** One encoded frame (possibly null or empty), on the executor. sampledAtMillis is the wall time of the sampled tick. */
	abstract void frame(byte[] jpeg, long sampledAtMillis);

	/** Reset the clock and the latch and listen to the render loop. */
	void start()
	{
		nextSampleAt = 0L;		// 0 => the first render tick samples immediately
		pending = false;
		if (plugin().drawManager != null)
		{
			plugin().drawManager.registerEveryFrameListener(tick);
		}
	}

	void stop()
	{
		if (plugin().drawManager != null)
		{
			plugin().drawManager.unregisterEveryFrameListener(tick);
		}
		pending = false;
	}

	/**
	 * Wall-clock 1-in-time decimation: true at most once per (1/CLIP_FPS) second regardless of render fps
	 * (render fluctuates ~20-50fps, so a frame-count divisor would drift).
	 */
	boolean due(long nowNanos)
	{
		if (nowNanos < nextSampleAt)
		{
			return false;
		}
		final long period = 1_000_000_000L / StoreClips.CLIP_FPS;
		// Advance from the SCHEDULED boundary, not from the observed tick.
		//
		// `nextSampleAt = nowNanos + period` looks equivalent and is not: a render tick lands on its own grid
		// (~20ms at 50fps), so each sample fires slightly AFTER its boundary and that remainder is then baked
		// into the next deadline. The error compounds: at 8fps it cost a real frame every two seconds (measured
		// 15 samples where 16 were due). Advancing the deadline by whole periods keeps the rate exact.
		//
		// After a long gap (a hitch, or a client that was not rendering) the deadline can sit far in the past;
		// walking it forward in whole periods would fire a burst of catch-up samples on consecutive frames.
		// Re-base to `nowNanos` instead: a gap loses frames either way, and the same instant twice is not evidence.
		if (nextSampleAt == 0L || nowNanos - nextSampleAt >= period)
		{
			nextSampleAt = nowNanos + period;
		}
		else
		{
			nextSampleAt += period;
		}
		return true;
	}

	/**
	 * Render-loop callback. Decimates by wall clock, then asks DrawManager for the next composited frame; that
	 * frame arrives on the consumer, is converted to an RGB (no-alpha) buffer and downscaled there (the image is
	 * DrawManager's buffer and is not ours to keep), and is JPEG-encoded on the executor so the render thread is
	 * never blocked by ImageIO. Owners keep encoded bytes only: decoded frames would freeze the client.
	 */
	void onFrame()
	{
		if (!capturing() || pending)
		{
			return;
		}
		if (!due(System.nanoTime()))
		{
			return;
		}
		pending = true;
		final long sampledAt = System.currentTimeMillis();
		plugin().drawManager.requestNextFrameListener(img ->
		{
			// A null image (the GPU plugin owns the surface) must release the latch too, or capture stops.
			final BufferedImage scaled = StoreClips.downscaleRgb(StoreClips.toRgbFrame(img), StoreClips.MAX_FRAME_WIDTH);
			if (scaled == null)
			{
				pending = false;
				return;
			}
			plugin().executor.submit(() ->
			{
				try
				{
					frame(AccountConnectPlugin.encodeJpeg(scaled), sampledAt);
				}
				finally
				{
					pending = false;
				}
			});
		});
	}
}
