package com.osrsbestinslot.export;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.client.ui.DrawManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The store clip and the drop recorder share one frame sampler. These pin the two timing details the merge had
 * to keep: the sampling gate is the one both recorders used, and each recorder keeps its own frame time.
 */
public class FrameSamplerTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final long PERIOD = 1_000_000_000L / StoreClips.CLIP_FPS;

	/** The drop recorder's own gate before the merge, verbatim. */
	private static final class FormerDropGate
	{
		long next;

		boolean due(long now)
		{
			if (now < next)
			{
				return false;
			}
			long n = next + PERIOD;
			next = (n <= now) ? now + PERIOD : n;
			return true;
		}
	}

	@Test
	public void theSharedGateDecidesExactlyAsTheFormerDropGateForEveryRunningClientClock()
	{
		Random rnd = new Random(77);
		for (int run = 0; run < 200; run++)
		{
			AccountConnectPlugin p = new AccountConnectPlugin();
			FormerDropGate former = new FormerDropGate();
			// System.nanoTime() of a running client is far past one period; render ticks of 5-60 ms, with hitches.
			long now = PERIOD + (long) (rnd.nextDouble() * 1_000_000_000_000L);
			for (int i = 0; i < 2_000; i++)
			{
				assertEquals("run " + run + " tick " + i, former.due(now), p.dropProof.sampler.due(now));
				now += rnd.nextInt(20) == 0 ? (long) (rnd.nextDouble() * 5_000_000_000L) : 5_000_000L + rnd.nextInt(55_000_000);
			}
		}
		// Both re-base after a hitch: one sample, then nothing until a period has passed.
		AccountConnectPlugin p = new AccountConnectPlugin();
		assertTrue(p.dropProof.sampler.due(10 * PERIOD));
		assertTrue(p.dropProof.sampler.due(500 * PERIOD));
		assertFalse(p.dropProof.sampler.due(500 * PERIOD + PERIOD / 2));
		assertTrue(p.dropProof.sampler.due(501 * PERIOD));
	}

	@Test
	public void storeFramesKeepTheirSampleTimeAndDropFramesTheirEncodeTime() throws Exception
	{
		AccountConnectPlugin p = staffPlugin();
		AtomicReference<Consumer<Image>> requested = new AtomicReference<>();
		inject(p, "drawManager", new DrawManager()
		{
			@Override
			public void requestNextFrameListener(Consumer<Image> consumer)
			{
				requested.set(consumer);
			}
		});
		ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1);
		inject(p, "executor", exec);
		try
		{
			p.clips.clipCapturing = true;
			p.clips.clipVisit = new StoreVisitClip(StoreClips.MAX_CLIP_FRAMES, StoreClips.MAX_CLIP_BURST_BYTES,
				StoreClips.MAX_CLIP_FRAME_BYTES);
			long[] store = sampleOne(p.clips.sampler, requested, exec);
			StoreVisitClip.Snapshot snap = p.clips.clipVisit.snapshot();
			assertEquals(1, snap.frames.size());
			long kept = snap.capturedAtMillis.get(0);
			assertTrue("a store frame is kept at its sample time", kept >= store[0] && kept <= store[1]);

			p.dropProof.dropCapturing = true;
			p.dropProof.dropSegmenter = new DropFrameSegmenter();
			p.dropProof.dropRate = null;		// keep every frame
			long[] drop = sampleOne(p.dropProof.sampler, requested, exec);
			DropFrameSegmenter.Segment seg = p.dropProof.dropSegmenter.flushRemainder();
			assertNotNull("the drop frame reached the segmenter", seg);
			assertTrue("a drop frame is kept at the time its encode finished", seg.firstFrameMillis >= drop[2]);
		}
		finally
		{
			exec.shutdownNow();
		}
	}

	/** One sampled tick, delivered 60 ms after the request. Returns {before request, after request, at delivery}. */
	private static long[] sampleOne(FrameSampler s, AtomicReference<Consumer<Image>> requested,
		ScheduledThreadPoolExecutor exec) throws Exception
	{
		requested.set(null);
		long before = System.currentTimeMillis();
		s.onFrame();
		long after = System.currentTimeMillis();
		assertNotNull("the tick requested a frame", requested.get());
		assertTrue(s.pending);
		Thread.sleep(60L);
		long delivered = System.currentTimeMillis();
		BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
		img.setRGB(5, 5, 0xff8800);
		requested.get().accept(img);
		exec.submit(() -> { }).get(5, TimeUnit.SECONDS);
		assertFalse("the latch is released after the encode", s.pending);
		return new long[]{before, after, delivered};
	}

	private static AccountConnectPlugin staffPlugin() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		});
		Client c = mock(Client.class);
		when(c.getAccountHash()).thenReturn(12345L);
		inject(p, "client", c);
		inject(p, "activeHash", "12345");
		p.grants.staffApiBase = ServerGrants.OFFICIAL_API_BASE;
		p.grants.setStaffCapture(TOKEN, "12345");
		p.grants.serverStoreToolsEnabled = true;
		p.grants.serverDropProofEnabled = true;
		assertTrue("fixture has the drop grant", p.grants.dropProofEnabled());
		return p;
	}

	private static void inject(Object o, String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, value);
	}
}
