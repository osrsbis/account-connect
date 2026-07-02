/*
 * OSRS Best in Slot — store-visit clip capability.
 *
 * Encodes a list of frames to an MP4 (H.264) clip using jcodec's pure-Java AWTSequenceEncoder.
 * jcodec is a pure-Java codec (no native code, no subprocess) — the reason it is the standard
 * choice for a plugin. This class is the encoder half of the frame -> clip path and is fully
 * unit-testable: give it synthetic BufferedImages, get back a decodable .mp4 File.
 *
 * jcodec encodes to YUV420, which requires even frame dimensions and constant dimensions across
 * the whole sequence. We normalise every frame to the (even) dimensions of the first frame, so
 * odd-sized or size-varying inputs still produce a valid stream.
 */
package com.osrsbestinslot.export.clip;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.jcodec.api.awt.AWTSequenceEncoder;

public final class ClipEncoder
{
	private ClipEncoder()
	{
	}

	/**
	 * Encode frames to an MP4 file.
	 *
	 * @param frames ordered frames (oldest -> newest); must be non-empty
	 * @param fps    playback frame rate (must be &gt;= 1)
	 * @param out    destination .mp4 file
	 * @return the written file
	 * @throws IOException              on encode/IO failure
	 * @throws IllegalArgumentException if frames is empty or fps &lt; 1
	 */
	public static File encodeMp4(List<BufferedImage> frames, int fps, File out) throws IOException
	{
		if (frames == null || frames.isEmpty())
		{
			throw new IllegalArgumentException("no frames to encode");
		}
		if (fps < 1)
		{
			throw new IllegalArgumentException("fps must be >= 1");
		}

		// Target dimensions = first frame, forced even (YUV420 requirement).
		int w = evenDown(frames.get(0).getWidth());
		int h = evenDown(frames.get(0).getHeight());
		if (w < 2 || h < 2)
		{
			throw new IllegalArgumentException("frame too small to encode (" + w + "x" + h + ")");
		}

		AWTSequenceEncoder enc = AWTSequenceEncoder.createSequenceEncoder(out, fps);
		try
		{
			for (BufferedImage frame : frames)
			{
				enc.encodeImage(normalise(frame, w, h));
			}
		}
		finally
		{
			enc.finish();
		}
		return out;
	}

	/** Draw any frame onto a fresh TYPE_3BYTE_BGR image of the exact target size. */
	private static BufferedImage normalise(BufferedImage src, int w, int h)
	{
		if (src.getType() == BufferedImage.TYPE_3BYTE_BGR
			&& src.getWidth() == w && src.getHeight() == h)
		{
			return src;
		}
		BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = dst.createGraphics();
		try
		{
			g.drawImage(src, 0, 0, w, h, null);
		}
		finally
		{
			g.dispose();
		}
		return dst;
	}

	private static int evenDown(int v)
	{
		return v - (v & 1);
	}
}
