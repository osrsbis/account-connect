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
	// (a) opt-in: capture is OFF by default — recordStoreClips() defaults false.
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
			public boolean recordStoreClips()
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

	// (c) wall-clock decimation: 50 render frames across one simulated second -> exactly 1 sample.
	@Test
	public void wallClockDecimationYieldsOneSamplePerSecond()
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		// nextClipSampleAt defaults to 0; walk 50 evenly-spaced nanoTime ticks over exactly one second
		// (~50fps render). Only the first tick should cross the 1s gate.
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
		assertEquals("expected ~1 sample across one simulated second", 1, trues);

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
		assertEquals("expected ~1 more sample across the next simulated second", 1, trues2);
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
