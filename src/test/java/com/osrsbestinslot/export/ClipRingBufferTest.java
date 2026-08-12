package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClipRingBufferTest
{
	private static BufferedImage img(int tag)
	{
		BufferedImage b = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		b.setRGB(0, 0, tag);
		return b;
	}

	@Test
	public void evictsOldestAtCapacity()
	{
		ClipRingBuffer ring = new ClipRingBuffer(3);
		for (int i = 1; i <= 5; i++) { ring.add(img(i)); }
		List<BufferedImage> snap = ring.snapshot();
		assertEquals(3, snap.size());
		assertEquals(3, snap.get(0).getRGB(0, 0) & 0xFFFFFF); // oldest surviving = #3
		assertEquals(5, snap.get(2).getRGB(0, 0) & 0xFFFFFF); // newest = #5
	}

	@Test
	public void ignoresNullAndClears()
	{
		ClipRingBuffer ring = new ClipRingBuffer(2);
		ring.add(null);
		assertEquals(0, ring.size());
		ring.add(img(1));
		ring.clear();
		assertEquals(0, ring.size());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsZeroCapacity()
	{
		new ClipRingBuffer(0);
	}
}
