package com.osrsbestinslot.export;

import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.runelite.client.ui.DrawManager;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the store delivery-proof burst UPLOAD path (Task B3) — no live client needed.
 * Covers: JPEG encode (SOI + ImageIO round-trip), the multipart burst builder (frame_count field
 * exactly equals the attached frames[] parts), the per-frame cap filter, the newest-suffix (tail)
 * truncation at the 12MB burst cap, the mandatory no-transaction privacy discard (US-08), and one
 * happy-path POST through a local JDK HttpServer stub proving submitStoreClipUpload targets the real
 * /store-frames-ingest route with the real constants. Live-client wiring is covered by the manual plan.
 */
public class StoreClipUploadTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";
	private static final byte[] JPEG_SOI = {(byte) 0xFF, (byte) 0xD8};

	// (a) encodeJpeg emits a real JPEG: SOI marker 0xFFD8 and round-trips via ImageIO at the same dims.
	@Test
	public void encodeJpegProducesSoiAndRoundTrips() throws Exception
	{
		byte[] bytes = AccountConnectPlugin.encodeJpeg(rgbFrame(48, 36));
		assertNotNull("encodeJpeg returned null", bytes);
		assertTrue("JPEG too short", bytes.length > 2);
		assertEquals("SOI byte 0", JPEG_SOI[0], bytes[0]);
		assertEquals("SOI byte 1", JPEG_SOI[1], bytes[1]);

		BufferedImage back = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
		assertNotNull("JPEG did not decode", back);
		assertEquals("width not preserved", 48, back.getWidth());
		assertEquals("height not preserved", 36, back.getHeight());
	}

	// (b) burst builder: frame_count field must EXACTLY equal the number of frames[] parts attached
	// (the server 400s on a mismatch), and the token/captured_at/fps fields must be present.
	@Test
	public void buildStoreClipBodyFrameCountMatchesAttachedParts() throws Exception
	{
		List<byte[]> kept = new ArrayList<>();
		kept.add(new byte[]{1, 2, 3});
		kept.add(new byte[]{4, 5, 6});
		kept.add(new byte[]{7, 8, 9});

		MultipartBody mb = AccountConnectPlugin.buildStoreClipBody(kept, TEST_TOKEN, 1700000000L, AccountConnectPlugin.CLIP_FPS);

		int frameParts = 0;
		int declaredCount = -1;
		boolean hasToken = false;
		boolean hasCapturedAt = false;
		boolean hasFps = false;
		for (int i = 0; i < mb.size(); i++)
		{
			MultipartBody.Part p = mb.part(i);
			String cd = p.headers().get("Content-Disposition");
			assertNotNull("part missing Content-Disposition", cd);
			if (cd.contains("name=\"frames[]\""))
			{
				frameParts++;
			}
			else if (cd.contains("name=\"frame_count\""))
			{
				declaredCount = Integer.parseInt(readPartUtf8(p).trim());
			}
			else if (cd.contains("name=\"token\""))
			{
				hasToken = TEST_TOKEN.equals(readPartUtf8(p).trim());
			}
			else if (cd.contains("name=\"captured_at\""))
			{
				hasCapturedAt = true;
			}
			else if (cd.contains("name=\"fps\""))
			{
				hasFps = true;
			}
		}
		assertEquals("expected 3 frames[] parts", 3, frameParts);
		assertEquals("frame_count must equal attached parts", 3, declaredCount);
		assertTrue("token field missing/mismatched", hasToken);
		assertTrue("captured_at field missing", hasCapturedAt);
		assertTrue("fps field missing", hasFps);
	}

	// (c) any frame over the per-frame cap is dropped, and frame_count still matches what is attached.
	@Test
	public void overPerFrameCapFramesDroppedAndCountMatches() throws Exception
	{
		List<byte[]> encoded = new ArrayList<>();
		encoded.add(sized(500_000, 0));					// ok
		encoded.add(sized(AccountConnectPlugin.MAX_CLIP_FRAME_BYTES + 1, 1));	// over per-frame cap -> dropped
		encoded.add(sized(500_000, 2));					// ok

		List<byte[]> kept = AccountConnectPlugin.selectStoreClipFrames(
			encoded, AccountConnectPlugin.MAX_CLIP_FRAMES,
			AccountConnectPlugin.MAX_CLIP_FRAME_BYTES, AccountConnectPlugin.MAX_CLIP_BURST_BYTES);

		assertEquals("oversized frame not dropped", 2, kept.size());
		assertEquals("wrong survivor 0", 0, kept.get(0)[0]);
		assertEquals("wrong survivor 1", 2, kept.get(1)[0]);

		MultipartBody mb = AccountConnectPlugin.buildStoreClipBody(kept, TEST_TOKEN, 1700000000L, AccountConnectPlugin.CLIP_FPS);
		int frameParts = 0;
		int declaredCount = -1;
		for (int i = 0; i < mb.size(); i++)
		{
			String cd = mb.part(i).headers().get("Content-Disposition");
			if (cd != null && cd.contains("name=\"frames[]\""))
			{
				frameParts++;
			}
			else if (cd != null && cd.contains("name=\"frame_count\""))
			{
				declaredCount = Integer.parseInt(readPartUtf8(mb.part(i)).trim());
			}
		}
		assertEquals("attached parts wrong after drop", 2, frameParts);
		assertEquals("frame_count must match attached after drop", 2, declaredCount);
	}

	// (d) truncation keeps the NEWEST suffix (the chronological tail) — the shop-close / sale frames are
	// the actual evidence; walking oldest-first would drop them. 15 frames of 1MB each exceed the 12MB
	// burst cap; exactly the last 12 (indices 3..14) must survive, in order.
	@Test
	public void truncationKeepsNewestSuffix() throws Exception
	{
		List<byte[]> encoded = new ArrayList<>();
		for (int i = 0; i < 15; i++)
		{
			encoded.add(sized(1_000_000, i));			// each == per-frame cap, allowed
		}
		List<byte[]> kept = AccountConnectPlugin.selectStoreClipFrames(
			encoded, AccountConnectPlugin.MAX_CLIP_FRAMES,
			AccountConnectPlugin.MAX_CLIP_FRAME_BYTES, AccountConnectPlugin.MAX_CLIP_BURST_BYTES);

		assertEquals("12MB cap must keep exactly 12 x 1MB frames", 12, kept.size());
		assertEquals("kept set must start at the tail (index 3)", 3, kept.get(0)[0]);
		assertEquals("kept set must end at the newest (index 14)", 14, kept.get(11)[0]);
		// prove strict chronological order across the kept suffix
		for (int i = 0; i < kept.size(); i++)
		{
			assertEquals("kept order broken at " + i, 3 + i, kept.get(i)[0]);
		}
	}

	// (e) MANDATORY PRIVACY (US-08): a shop visit with NO buy/sell uploads NOTHING. Drive the real
	// stopStoreClipCapture(true) with storeTxThisVisit=false and prove submitStoreClipUpload is never
	// reached (subclass-override seam) and the ring is cleared. Positive control: tx=true DOES upload.
	@Test
	public void noStoreTransactionUploadsNothing() throws Exception
	{
		final boolean[] uploaded = {false};
		AccountConnectPlugin plugin = new AccountConnectPlugin()
		{
			@Override
			void submitStoreClipUpload(List<BufferedImage> frames)
			{
				uploaded[0] = true;
			}
		};
		inject(plugin, "config", enabledConfig(null));
		inject(plugin, "drawManager", new DrawManager());
		ClipRingBuffer ring = new ClipRingBuffer(AccountConnectPlugin.MAX_CLIP_FRAMES);
		ring.add(rgbFrame(16, 12));
		ring.add(rgbFrame(16, 12));
		inject(plugin, "clipRing", ring);
		inject(plugin, "clipCapturing", true);
		plugin.storeTxThisVisit = false;			// the visit had NO buy/sell

		plugin.stopStoreClipCapture(true);			// upload requested, but tx flag is false

		assertFalse("no-transaction visit must NEVER upload", uploaded[0]);
		assertEquals("frames must be discarded", 0, ring.size());

		// positive control: an identical visit WITH a transaction does upload.
		final boolean[] uploaded2 = {false};
		AccountConnectPlugin plugin2 = new AccountConnectPlugin()
		{
			@Override
			void submitStoreClipUpload(List<BufferedImage> frames)
			{
				uploaded2[0] = true;
			}
		};
		inject(plugin2, "config", enabledConfig(null));
		inject(plugin2, "drawManager", new DrawManager());
		ClipRingBuffer ring2 = new ClipRingBuffer(AccountConnectPlugin.MAX_CLIP_FRAMES);
		ring2.add(rgbFrame(16, 12));
		inject(plugin2, "clipRing", ring2);
		inject(plugin2, "clipCapturing", true);
		plugin2.storeTxThisVisit = true;			// a buy/sell happened

		plugin2.stopStoreClipCapture(true);

		assertTrue("a visit with a transaction must upload", uploaded2[0]);
	}

	// (glue) happy path: submitStoreClipUpload actually POSTs the burst to the real /store-frames-ingest
	// route with the real constants — the pure-seam tests above prove the logic but not the wiring.
	@Test
	public void submitStoreClipUploadPostsBurstToIngestRoute() throws Exception
	{
		BlockingQueue<Capture> received = new LinkedBlockingQueue<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange ->
		{
			byte[] reqBody = readAll(exchange.getRequestBody());
			byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
			received.add(new Capture(exchange.getRequestURI().getPath(),
				exchange.getRequestHeaders().getFirst("Content-Type"), reqBody));
		});
		server.start();
		try
		{
			String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/wp-json/osrsbis/v1";
			AccountConnectPlugin plugin = new AccountConnectPlugin();
			inject(plugin, "config", enabledConfig(base));
			inject(plugin, "executor", Executors.newSingleThreadScheduledExecutor());
			inject(plugin, "okHttpClient", new OkHttpClient());

			List<BufferedImage> frames = new ArrayList<>();
			frames.add(rgbFrame(40, 30));
			frames.add(rgbFrame(40, 30));
			frames.add(rgbFrame(40, 30));
			plugin.submitStoreClipUpload(frames);

			Capture c = received.poll(15, TimeUnit.SECONDS);
			assertNotNull("burst upload never arrived", c);
			assertEquals("/wp-json/osrsbis/v1/store-frames-ingest", c.path);
			assertTrue("not multipart: " + c.contentType, c.contentType.startsWith("multipart/form-data"));
			String body = new String(c.body, StandardCharsets.ISO_8859_1);
			assertTrue("token field missing", body.contains("name=\"token\"") && body.contains(TEST_TOKEN));
			assertTrue("captured_at field missing", body.contains("name=\"captured_at\""));
			assertTrue("fps field missing", body.contains("name=\"fps\""));
			assertTrue("frame_count field missing", body.contains("name=\"frame_count\""));
			assertTrue("frames[] parts missing", body.contains("name=\"frames[]\""));
			assertTrue("parts not image/jpeg", body.contains("image/jpeg"));
			assertTrue("no JPEG payload in body", indexOf(c.body, JPEG_SOI) >= 0);
		}
		finally
		{
			server.stop(0);
		}
	}

	// ---- helpers ----

	private static byte[] sized(int len, int tag)
	{
		byte[] b = new byte[len];
		b[0] = (byte) tag;
		return b;
	}

	private static BufferedImage rgbFrame(int w, int h)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(0, 0, w, h);
		g.setColor(Color.YELLOW);
		g.fillRect(0, 0, w / 2, h / 2);
		g.dispose();
		return img;
	}

	private static AccountConnectConfig enabledConfig(String baseUrl)
	{
		return new AccountConnectConfig()
		{
			@Override
			public boolean uploadTradeScreenshots()
			{
				return true;
			}

			@Override
			public String linkToken()
			{
				return TEST_TOKEN;
			}

			@Override
			public String apiBaseUrl()
			{
				return baseUrl != null ? baseUrl : AccountConnectConfig.super.apiBaseUrl();
			}
		};
	}

	private static String readPartUtf8(MultipartBody.Part part) throws java.io.IOException
	{
		Buffer buffer = new Buffer();
		part.body().writeTo(buffer);
		return buffer.readUtf8();
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static byte[] readAll(InputStream in) throws java.io.IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int n;
		while ((n = in.read(chunk)) != -1)
		{
			out.write(chunk, 0, n);
		}
		return out.toByteArray();
	}

	private static int indexOf(byte[] haystack, byte[] needle)
	{
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++)
		{
			for (int j = 0; j < needle.length; j++)
			{
				if (haystack[i + j] != needle[j])
				{
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static final class Capture
	{
		final String path;
		final String contentType;
		final byte[] body;

		Capture(String path, String contentType, byte[] body)
		{
			this.path = path;
			this.contentType = contentType;
			this.body = body;
		}
	}
}
