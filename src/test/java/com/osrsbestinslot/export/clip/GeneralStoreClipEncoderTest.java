/*
 * OSRS Best in Slot — store-visit clip capability.
 *
 * Proves the frame -> clip path end to end WITHOUT a live game: synthetic solid-colour frames are
 * pushed through the bounded ring buffer, snapshotted, and encoded, then the produced MP4 is decoded
 * back with jcodec to assert it is non-empty AND decodable (frames read back with matching dimensions).
 * (The existing AccountConnectPluginTest is a dev launcher, not a unit test, so this is the build's
 * real test coverage.)
 */
package com.osrsbestinslot.export.clip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GeneralStoreClipEncoderTest
{
	private static final int W = 256;
	private static final int H = 160;
	private static final int FPS = 10;
	private static final int N_FRAMES = 30;

	private static BufferedImage solidFrame(int i)
	{
		BufferedImage bi = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = bi.createGraphics();
		// Vary colour per frame so successive frames genuinely differ (a real inter-frame codec test).
		g.setColor(new Color((i * 37) % 256, (i * 53) % 256, (i * 91) % 256));
		g.fillRect(0, 0, W, H);
		g.dispose();
		return bi;
	}

	@Test
	public void frameToClipProducesDecodableMp4() throws Exception
	{
		// 1. Feed synthetic frames through the ring buffer (capacity holds all N here).
		ClipRingBuffer ring = new ClipRingBuffer(FPS * 6);
		for (int i = 0; i < N_FRAMES; i++)
		{
			ring.add(solidFrame(i));
		}
		List<BufferedImage> frames = ring.snapshot();
		assertEquals(N_FRAMES, frames.size());

		// 2. Encode to MP4.
		File out = File.createTempFile("osrsbis-clip-test-", ".mp4");
		out.deleteOnExit();
		ClipEncoder.encodeMp4(frames, FPS, out);

		// 3. Non-empty file.
		assertTrue("clip file should exist", out.exists());
		assertTrue("clip file should be non-empty", out.length() > 0L);

		// 4. Decodable: read frames back with jcodec and assert dims + a sane decoded count.
		int decoded = 0;
		FrameGrab grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(out));
		Picture first = grab.getNativeFrame();
		assertNotNull("first frame must decode", first);
		assertEquals(W, first.getWidth());
		assertEquals(H, first.getHeight());
		decoded++;
		Picture p;
		while ((p = grab.getNativeFrame()) != null && decoded < N_FRAMES * 2)
		{
			assertEquals(W, p.getWidth());
			assertEquals(H, p.getHeight());
			decoded++;
		}
		// jcodec should round-trip all encoded frames.
		assertEquals("decoded frame count should match encoded", N_FRAMES, decoded);
	}

	@Test
	public void ringBufferKeepsOnlyMostRecentWhenOverCapacity() throws Exception
	{
		int cap = FPS * 6; // 60
		ClipRingBuffer ring = new ClipRingBuffer(cap);
		for (int i = 0; i < cap * 2; i++)
		{
			ring.add(solidFrame(i));
		}
		assertEquals("buffer must stay bounded at capacity", cap, ring.size());

		// The retained frames must still encode to a decodable clip.
		File out = File.createTempFile("osrsbis-clip-bounded-", ".mp4");
		out.deleteOnExit();
		ClipEncoder.encodeMp4(ring.snapshot(), FPS, out);
		assertTrue(out.length() > 0L);
		FrameGrab grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(out));
		assertNotNull(grab.getNativeFrame());
	}
}
