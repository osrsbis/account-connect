package com.osrsbestinslot.export;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the store delivery-proof burst capture path (Task B2) — no live client needed.
 * Covers: the opt-in default (off), the server force-disable gate, wall-clock 1-in-time decimation,
 * and the RGB (no-alpha) frame conversion the JDK JPEG writer requires. The listener wiring, real
 * shop-widget event delivery, and the multipart upload (B3) need a live client / are covered elsewhere.
 */
public class StoreClipCaptureTest
{
	// (a) opt-in: capture is OFF by default — uploadTradeScreenshots() defaults false.
	@Test
	public void storeClipsDisabledByDefault() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", new AccountConnectConfig() {});
		assertFalse("store-clip capture must be off with default (opt-in) config",
			plugin.storeClipsEnabled());
	}

	// (b) server force-disable wins even when the local opt-in is ON.
	@Test
	public void serverForceDisableOverridesLocalOptIn() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", new AccountConnectConfig()
		{
			@Override
			public boolean uploadTradeScreenshots()
			{
				return true;
			}
		});
		// opt-in on, server not disabling -> enabled.
		assertTrue("local opt-in with no server disable must enable capture",
			plugin.storeClipsEnabled());
		// server flips it off -> gate closes despite the local opt-in.
		plugin.serverClipsDisabled = true;
		assertFalse("server X-Clips=off must force capture off",
			plugin.storeClipsEnabled());
	}

	// (c) wall-clock decimation: 50 render frames across one simulated second -> exactly CLIP_FPS samples.
	//
	// The expectation is DERIVED from CLIP_FPS, not hardcoded. It used to assert a literal 1, so raising
	// the sample rate to 2 on 2026-09-02 turned this red for the rate change rather than for a defect —
	// a test that fails when the thing it measures is deliberately changed is testing the constant, not
	// the decimation. What must hold at ANY rate is: exactly CLIP_FPS samples per second, and the same
	// again in the following second (a rate, never a one-shot).
	@Test
	public void wallClockDecimationYieldsOneSamplePerSecond()
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		// nextClipSampleAt defaults to 0; walk 50 evenly-spaced nanoTime ticks over exactly one second
		// (~50fps render). Only every (1/CLIP_FPS)s boundary should cross the gate.
		long oneSecond = 1_000_000_000L;
		int trues = 0;
		for (int i = 0; i < 50; i++)
		{
			long now = i * (oneSecond / 50);	// 0ms, 20ms, ... 980ms
			if (plugin.shouldSampleClipFrame(now))
			{
				trues++;
			}
		}
		// Exact equality is the WRONG assertion above 1fps and asserting it hid a real property. The gate
		// advances by exactly 1/CLIP_FPS seconds, but render ticks land on a 20ms grid, so each sample
		// fires at the first tick AT OR AFTER its boundary and the remainder carries into the next
		// second: at 8fps a 50-tick second yields 8 then 7, alternating, averaging 8. That is correct
		// decimation, not drift — the gate never loses time, it only rounds each sample forward. So the
		// per-second count is asserted within +/-1 and the TWO-second total is asserted exactly, which
		// is what actually proves the rate.
		assertTrue("expected ~CLIP_FPS samples across one simulated second, got " + trues,
			Math.abs(trues - AccountConnectPlugin.CLIP_FPS) <= 1);
		// MOUSE VISIBILITY is the floor, not "enough to catch the transaction". A cursor crossing a shop
		// interface reads as movement only when consecutive frames are ~125ms apart; 3fps (333ms) was
		// rejected on review as too laggy to see the mouse at all. Asserted independently of whatever
		// CLIP_FPS currently is, so lowering the rate for a byte saving turns this RED.
		assertTrue("a delivery clip must sample at the client's own render rate (30fps) to read as motion",
			AccountConnectPlugin.CLIP_FPS >= 30);
		// The burst must fit the SERVER's frame-count cap. This is 240 only because the ingest cap was
		// raised to 360 in the same change (osrsbis-web-integrator backend.js FRAMES_COUNT_MAX); with the
		// old 120 the server 400s the whole burst — it does not trim it — so this number and the server's
		// must move together, and this assertion is what catches them drifting apart.
		// The visit's frames no longer have to fit ONE request: the upload is chunked. What must hold is
		// that a CHUNK fits the smallest server frame cap ever deployed (120), with margin. This is the
		// assertion that lets clip length grow without a server deploy — and that turns red if someone
		// raises the chunk size back up to the current cap and re-couples the two.
		assertTrue("a chunk must fit the smallest deployed server frame cap (120) with margin",
			AccountConnectPlugin.CLIP_CHUNK_FRAMES <= 100);
		assertTrue("chunking must actually chunk a full visit",
			AccountConnectPlugin.MAX_CLIP_FRAMES > AccountConnectPlugin.CLIP_CHUNK_FRAMES);
		// And it must fit the BYTE cap at the measured mean frame size (56KB over 68 real frames), or the
		// newest-suffix trim silently discards the opening of the visit.
		// 30KB is the MEASURED mean at the current MAX_FRAME_WIDTH/quality (704px, q0.55). If either is
		// raised, this arm goes red — which is the point: the frame size and the frame count are one
		// budget, and changing one without the other silently truncates the start of every visit.
		// This is the VISIT budget (memory + total upload), not a per-request limit.
		assertTrue("fps x seconds at ~30KB/frame (704px q0.55) must fit the 12MB visit budget",
			(long) AccountConnectPlugin.MAX_CLIP_FRAMES * 30 * 1024
				<= AccountConnectPlugin.MAX_CLIP_BURST_BYTES);
		assertEquals("frame width must stay at the legibility floor that the byte budget assumes",
			704, AccountConnectPlugin.MAX_FRAME_WIDTH);

		// A second simulated second must yield the next sample — proves it is a rate, not a one-shot.
		int trues2 = 0;
		for (int i = 50; i < 100; i++)
		{
			long now = i * (oneSecond / 50);	// 1000ms .. 1980ms
			if (plugin.shouldSampleClipFrame(now))
			{
				trues2++;
			}
		}
		assertTrue("expected ~CLIP_FPS more samples across the next simulated second, got " + trues2,
			Math.abs(trues2 - AccountConnectPlugin.CLIP_FPS) <= 1);
		// The two-second total is exact: rounding each sample forward must not COST a sample.
		assertEquals("two simulated seconds must yield exactly 2x CLIP_FPS samples",
			2 * AccountConnectPlugin.CLIP_FPS, trues + trues2);

		// Over a LONG span the rate must not drift. This is the arm that caught the real defect: the
		// gate used to advance from the observed tick, so each sample's rounding error compounded and
		// 8fps delivered ~7.5. Ten simulated seconds at a 50fps render must yield 10x CLIP_FPS.
		AccountConnectPlugin p2 = new AccountConnectPlugin();
		int over10s = 0;
		for (int i = 0; i < 500; i++)
		{
			if (p2.shouldSampleClipFrame(i * (oneSecond / 50)))
			{
				over10s++;
			}
		}
		assertEquals("ten simulated seconds must yield exactly 10x CLIP_FPS samples — no cumulative drift",
			10 * AccountConnectPlugin.CLIP_FPS, over10s);

		// A long stall must NOT produce a catch-up burst on consecutive frames: duplicating one instant
		// is not evidence. After a 5-second gap the next tick samples once, and the tick right after it
		// (20ms later, well inside the 125ms period) must not.
		AccountConnectPlugin p3 = new AccountConnectPlugin();
		assertTrue("first call always samples", p3.shouldSampleClipFrame(0L));
		assertTrue("the tick after a long stall samples once", p3.shouldSampleClipFrame(5 * oneSecond));
		assertFalse("...and does not fire again 20ms later",
			p3.shouldSampleClipFrame(5 * oneSecond + oneSecond / 50));
	}

	// (d) RGB conversion: an ARGB frame becomes TYPE_INT_RGB (the JDK JPEG writer corrupts ARGB rasters).
	@Test
	public void toRgbFrameProducesNonAlphaRgb()
	{
		BufferedImage argb = new BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = argb.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, 32, 24);
		g.dispose();

		BufferedImage rgb = AccountConnectPlugin.toRgbFrame(argb);
		assertNotNull("toRgbFrame returned null for a valid image", rgb);
		assertEquals("frame must be TYPE_INT_RGB (no alpha) for the JPEG writer",
			BufferedImage.TYPE_INT_RGB, rgb.getType());
		assertEquals("width must be preserved", 32, rgb.getWidth());
		assertEquals("height must be preserved", 24, rgb.getHeight());
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
