package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The store visit clip keeps every sale, not the last 12 seconds.
 *
 * Field defect, Arengees 2026-09-25: sells 16:37:59-16:39:51, uploaded frames 16:39:55-16:40:06, every
 * frame static. The old ring kept the NEWEST 360 frames (12s at 30fps), so any visit that ran more
 * than 12 seconds past its last sale uploaded no sale at all.
 *
 * Known-bad control: replacing StoreVisitClip's keep policy with "keep every frame, evict the oldest"
 * (the old ring) turns everySaleOfALongVisitIsInTheUpload RED.
 */
public class StoreVisitClipTest
{
	/** Measured mean frame size at 704px / q0.55 (StoreClipCaptureTest pins the width). */
	private static final int FRAME_BYTES = 30 * 1024;
	private static final long FRAME_MS = 1000L / AccountConnectPlugin.CLIP_FPS;

	private static StoreVisitClip visit()
	{
		return new StoreVisitClip(AccountConnectPlugin.MAX_CLIP_FRAMES,
			AccountConnectPlugin.MAX_CLIP_BURST_BYTES, AccountConnectPlugin.MAX_CLIP_FRAME_BYTES);
	}

	/** Drive a visit: a frame every 1/30s from 0 to closeMs, a moment at each sale time. */
	private static StoreVisitClip.Snapshot run(StoreVisitClip v, long closeMs, long... sales)
	{
		int next = 0;
		for (long t = 0; t <= closeMs; t += FRAME_MS)
		{
			while (next < sales.length && sales[next] <= t)
			{
				v.onMoment(sales[next]);
				next++;
			}
			v.offer(new byte[FRAME_BYTES], t);
		}
		return v.snapshot();
	}

	private static int framesBetween(StoreVisitClip.Snapshot s, long from, long to)
	{
		int n = 0;
		for (long t : s.capturedAtMillis)
		{
			if (t >= from && t <= to)
			{
				n++;
			}
		}
		return n;
	}

	private static void assertWithinBudget(StoreVisitClip v, StoreVisitClip.Snapshot s)
	{
		assertTrue("frame budget exceeded: " + s.frames.size(),
			s.frames.size() <= AccountConnectPlugin.MAX_CLIP_FRAMES);
		long bytes = 0;
		for (byte[] f : s.frames)
		{
			bytes += f.length;
		}
		assertTrue("byte budget exceeded: " + bytes, bytes <= AccountConnectPlugin.MAX_CLIP_BURST_BYTES);
		assertEquals("byteSize must match the snapshot", bytes, v.byteSize());
		for (int i = 1; i < s.capturedAtMillis.size(); i++)
		{
			assertTrue("frames must be in capture order",
				s.capturedAtMillis.get(i - 1) <= s.capturedAtMillis.get(i));
		}
	}

	/** Every sale shows the click and the change after it: frames before and after the click. */
	private static void assertSaleVisible(StoreVisitClip.Snapshot s, long sale, int minAfter)
	{
		int after = framesBetween(s, sale, sale + StoreVisitClip.CORE_AFTER_MILLIS);
		assertTrue("sale at " + sale + "ms has only " + after + " frames in the 1.2s after the click",
			after >= minAfter);
		if (sale > 0)
		{
			assertTrue("sale at " + sale + "ms has no frame in the 0.5s before the click",
				framesBetween(s, sale - StoreVisitClip.CORE_BEFORE_MILLIS, sale - 1) >= 1);
		}
	}

	/** The brief's case: sales at 0s, 60s and 110s, shop closed at 125s. */
	@Test
	public void everySaleOfALongVisitIsInTheUpload()
	{
		StoreVisitClip v = visit();
		long[] sales = {0L, 60_000L, 110_000L};
		StoreVisitClip.Snapshot s = run(v, 125_000L, sales);

		assertWithinBudget(v, s);
		for (long sale : sales)
		{
			// 1.2s at 30fps is 36 frames; at least half must survive the budget.
			assertSaleVisible(s, sale, 18);
		}
		// The gaps between sales are not blank: the baseline shows the tile in between.
		assertTrue("the middle of the visit must have baseline frames",
			framesBetween(s, 20_000L, 50_000L) >= 10);
		// The visit start is in the clip.
		assertTrue("the visit start must be in the clip", s.capturedAtMillis.get(0) <= 1_000L);
		// And the old failure is gone: the clip does not start in the last 12 seconds.
		assertTrue("the clip must not be just the last 12 seconds",
			s.capturedAtMillis.get(0) < 125_000L - 12_000L);
	}

	/** A 2-minute visit with nothing sold: start plus 1fps baseline, well inside the budget. */
	@Test
	public void aTwoMinuteIdleVisitStaysWithinBudget()
	{
		StoreVisitClip v = visit();
		StoreVisitClip.Snapshot s = run(v, 120_000L);

		assertWithinBudget(v, s);
		assertEquals("no moments were marked", 0, v.momentCount());
		// 2s start at 30fps (60) + one baseline frame a second for the other ~118s.
		assertTrue("idle visit should be about 180 frames, got " + s.frames.size(),
			s.frames.size() >= 170 && s.frames.size() <= 185);
		assertTrue("the pre-roll held for lookbehind stays bounded to 2s at 30fps",
			v.preRollSize() <= 61);
	}

	/** Twenty sales spaced out over a long visit: the cap holds and every sale is still shown. */
	@Test
	public void twentySpacedSalesStayInsideTheCapAndAllAreShown()
	{
		StoreVisitClip v = visit();
		long[] sales = new long[20];
		for (int i = 0; i < 20; i++)
		{
			sales[i] = 5_000L + i * 7_000L;
		}
		StoreVisitClip.Snapshot s = run(v, 150_000L, sales);

		assertWithinBudget(v, s);
		assertEquals("the frame cap must be reached, not exceeded",
			AccountConnectPlugin.MAX_CLIP_FRAMES, s.frames.size());
		for (long sale : sales)
		{
			assertSaleVisible(s, sale, 3);
		}
	}

	/** Twenty rapid sells one second apart (a multi-item delivery): every click is still covered. */
	@Test
	public void twentyRapidSalesAreAllShown()
	{
		StoreVisitClip v = visit();
		long[] sales = new long[20];
		for (int i = 0; i < 20; i++)
		{
			sales[i] = 10_000L + i * 1_000L;
		}
		StoreVisitClip.Snapshot s = run(v, 90_000L, sales);

		assertWithinBudget(v, s);
		for (long sale : sales)
		{
			assertSaleVisible(s, sale, 3);
		}
		assertTrue("the visit start survives many sales", s.capturedAtMillis.get(0) <= 2_000L);
	}

	/** The byte cap binds too: frames larger than the measured mean still keep every sale. */
	/**
	 * PIO-014: the GS video budget is 16 MiB. A full 360-frame visit at 44 KB/frame (~15.8 MB, the
	 * current quality at a busy scene) keeps every frame, both in the visit selector and in the final
	 * upload selection. Under the old 12MB budget about a quarter of these frames were cut.
	 */
	@Test
	public void aFullVisitAt44KbPerFrameKeepsAll360Frames()
	{
		StoreVisitClip v = visit();
		long[] sales = {2_000L, 5_000L, 8_000L, 11_000L};	// windows cover the whole 12 s
		int next = 0;
		int offered = 0;
		for (long t = 0; offered < AccountConnectPlugin.MAX_CLIP_FRAMES; t += FRAME_MS)
		{
			while (next < sales.length && sales[next] <= t)
			{
				v.onMoment(sales[next++]);
			}
			v.offer(new byte[44_000], t);
			offered++;
		}
		StoreVisitClip.Snapshot s = v.snapshot();
		assertEquals("every frame of a 360-frame visit at 44 KB is kept", 360, s.frames.size());
		assertWithinBudget(v, s);
		assertEquals(360, AccountConnectPlugin.selectStoreClipFrames(s.frames, AccountConnectPlugin.MAX_CLIP_FRAMES,
			AccountConnectPlugin.MAX_CLIP_FRAME_BYTES, AccountConnectPlugin.MAX_CLIP_BURST_BYTES).size());
		assertEquals("the retry budget is two visits (32 MiB)", 32L * 1024 * 1024, AccountConnectPlugin.CLIP_RETRY_BYTE_BUDGET);
	}

	@Test
	public void theByteCapIsRespectedWithLargeFrames()
	{
		StoreVisitClip v = new StoreVisitClip(AccountConnectPlugin.MAX_CLIP_FRAMES,
			AccountConnectPlugin.MAX_CLIP_BURST_BYTES, AccountConnectPlugin.MAX_CLIP_FRAME_BYTES);
		long[] sales = {3_000L, 40_000L, 80_000L};
		int next = 0;
		for (long t = 0; t <= 100_000L; t += FRAME_MS)
		{
			while (next < sales.length && sales[next] <= t)
			{
				v.onMoment(sales[next++]);
			}
			v.offer(new byte[100_000], t);	// 100KB: the byte cap (16 MiB = 167 frames) binds first
		}
		StoreVisitClip.Snapshot s = v.snapshot();
		assertTrue("16 MiB cap", v.byteSize() <= AccountConnectPlugin.MAX_CLIP_BURST_BYTES);
		assertTrue("bytes cap bound the frame count", s.frames.size() <= 167);
		for (long sale : sales)
		{
			assertSaleVisible(s, sale, 3);
		}
	}

	/** Frames that fail to encode or exceed the per-frame cap are never kept. */
	@Test
	public void emptyAndOversizedFramesAreIgnored()
	{
		StoreVisitClip v = visit();
		v.offer(null, 0L);
		v.offer(new byte[0], 10L);
		v.offer(new byte[AccountConnectPlugin.MAX_CLIP_FRAME_BYTES + 1], 20L);
		assertEquals(0, v.size());
		v.offer(new byte[10], 30L);
		assertEquals(1, v.size());
	}

	/**
	 * WIRING: a real Sell click during a capturing visit marks a moment on the visit clip. Without
	 * this call the clip falls back to start + baseline and the sale is only seen by chance.
	 */
	@Test
	public void aSellClickMarksAMomentOnTheVisitClip() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", new AccountConnectConfig() { });
		inject(plugin, "client", org.mockito.Mockito.mock(net.runelite.api.Client.class));
		StoreVisitClip v = visit();
		inject(plugin, "clipVisit", v);
		inject(plugin, "clipCapturing", true);
		inject(plugin, "shopOpen", true);

		net.runelite.api.events.MenuOptionClicked sell =
			org.mockito.Mockito.mock(net.runelite.api.events.MenuOptionClicked.class);
		org.mockito.Mockito.when(sell.getMenuOption()).thenReturn("Sell 1");
		org.mockito.Mockito.when(sell.getItemId()).thenReturn(1511);
		plugin.onMenuOptionClicked(sell);

		assertEquals("the sell click must mark one moment", 1, v.momentCount());
		assertTrue("and still flag the visit for upload", plugin.storeTxThisVisit);

		// Outside a capturing visit nothing is marked.
		inject(plugin, "clipCapturing", false);
		plugin.onMenuOptionClicked(sell);
		assertEquals(1, v.momentCount());
	}

	/** The uploader stamps each chunk with its OWN first frame's capture time, not a derived one. */
	@Test
	public void chunksAreStampedWithTheirFirstFramesRealCaptureTime()
	{
		StoreVisitClip v = visit();
		StoreVisitClip.Snapshot s = run(v, 125_000L, 0L, 60_000L, 110_000L);
		List<Integer> idx = AccountConnectPlugin.selectStoreClipFrameIndexes(s.frames,
			AccountConnectPlugin.MAX_CLIP_FRAMES, AccountConnectPlugin.MAX_CLIP_FRAME_BYTES,
			AccountConnectPlugin.MAX_CLIP_BURST_BYTES);
		assertEquals("the visit clip is already inside the budget, so nothing is trimmed",
			s.frames.size(), idx.size());
		int chunks = (idx.size() + AccountConnectPlugin.CLIP_CHUNK_FRAMES - 1) / AccountConnectPlugin.CLIP_CHUNK_FRAMES;
		assertTrue("a full visit is still at most 9 chunks of 40", chunks <= 9);
		long prev = -1;
		for (int off = 0; off < idx.size(); off += AccountConnectPlugin.CLIP_CHUNK_FRAMES)
		{
			// captured_at goes on the wire in whole SECONDS and the collector orders chunks on it, so
			// two chunks sharing a second would be ordered by a random burst id. 40 frames at 30fps
			// span at least 1.3s, so the seconds must strictly increase.
			long atSec = s.capturedAtMillis.get(idx.get(off)) / 1000L;
			assertTrue("chunk captured_at seconds must strictly increase", atSec > prev);
			prev = atSec;
		}
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
