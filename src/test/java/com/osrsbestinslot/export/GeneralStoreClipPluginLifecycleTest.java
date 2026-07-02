/*
 * OSRS Best in Slot — store-visit clip capability (hosted by the merged AccountConnectPlugin).
 *
 * Functional lifecycle test of the PLUGIN WIRING (the encoder test covers frame -> mp4 already).
 * Proves, without a live game: shop-open event -> the plugin registers a frame listener and
 * captures throttled frames -> shop-close event -> a decodable MP4 holding those frames is
 * POSTed as multipart (token + clip file part) to the /store-clip-ingest route.
 *
 * Test doubles, least-invasive first:
 *  - DrawManager is a plain non-final class with a no-arg constructor, so a recording SUBCLASS
 *    stands in for it: it tracks every-frame listener (un)registration and, on
 *    requestNextFrameListener, immediately supplies a synthetic 800x500 frame whose colour
 *    varies per call.
 *  - AccountConnectConfig is an interface whose methods all have defaults — one anonymous
 *    implementation carries the link token, base URL and the clip settings (the clip path now
 *    reads the same config object as the rest of the plugin); no mocking framework needed.
 *  - OkHttpClient is REAL, pointed at a MockWebServer (mockwebserver pinned to the okhttp
 *    version the RuneLite client ships).
 *  - Client / Gson / ItemManager / executor are not stubbed: the clip path never touches them,
 *    and the trade handlers sharing onWidgetLoaded/onWidgetClosed early-return because
 *    uploadTradeScreenshots() stays at its false default.
 * Dependencies land in the plugin's private @Inject fields via reflection, so production code
 * is completely unchanged by this test.
 *
 * The capture throttle keys off System.nanoTime(), so the frame listener is driven with real
 * sleeps slightly LONGER than the 1/fps interval — every tick is then guaranteed to pass the
 * throttle (nanoTime is monotonic; a sleep can only overshoot), keeping the captured-frame
 * count deterministic without touching the production throttle.
 */
package com.osrsbestinslot.export;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.DrawManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GeneralStoreClipPluginLifecycleTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int CLIP_FPS = 5;
	private static final int CLIP_SECONDS = 2;
	private static final int RING_CAPACITY = CLIP_FPS * CLIP_SECONDS; // 10
	private static final int TICKS = 20;
	// > 1/fps (200ms) so every driven tick is guaranteed to pass the nanoTime throttle.
	private static final long TICK_SPACING_MS = 210;
	// Source frames are 800x500; the plugin downscales to width 640 preserving aspect -> 640x400
	// (already even, so the encoder's even-rounding changes nothing).
	private static final int SRC_W = 800;
	private static final int SRC_H = 500;
	private static final int CLIP_W = 640;
	private static final int CLIP_H = 400;

	private MockWebServer server;
	private AccountConnectPlugin plugin;
	private RecordingDrawManager drawManager;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
	}

	@After
	public void tearDown() throws Exception
	{
		if (plugin != null)
		{
			plugin.shutDown();
		}
		server.shutdown();
	}

	@Test
	public void shopOpenCapturesFramesAndShopCloseUploadsDecodableClip() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200));
		buildPlugin(true);
		plugin.startUp();

		// Shop opens -> the plugin must hook the render loop.
		plugin.onWidgetLoaded(widgetLoaded(InterfaceID.SHOPMAIN));
		assertEquals("shop open must register exactly one every-frame listener",
			1, drawManager.everyFrameListeners.size());

		// Drive the render loop: more ticks than the ring holds, so the bounded-buffer path runs too.
		Runnable frameTick = drawManager.everyFrameListeners.get(0);
		for (int i = 0; i < TICKS; i++)
		{
			frameTick.run();
			Thread.sleep(TICK_SPACING_MS);
		}
		assertEquals("every spaced tick must pass the fps throttle and request a frame",
			TICKS, drawManager.framesSupplied.get());

		// Shop closes -> capture stops, encode + upload happen off-thread.
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.SHOPMAIN, 0, false));
		assertTrue("shop close must unregister the frame listener",
			drawManager.everyFrameListeners.isEmpty());

		RecordedRequest request = server.takeRequest(15, TimeUnit.SECONDS);
		assertNotNull("shop close must produce an upload within the timeout", request);
		assertEquals("POST", request.getMethod());
		assertEquals("/store-clip-ingest", request.getPath());
		assertEquals("exactly one upload per shop visit", 1, server.getRequestCount());

		// Multipart body: token field + clip file part typed video/mp4.
		String contentType = request.getHeader("Content-Type");
		assertNotNull(contentType);
		assertTrue("upload must be multipart/form-data, was: " + contentType,
			contentType.startsWith("multipart/form-data"));
		List<Part> parts = parseMultipart(request.getBody().readByteArray(), boundaryOf(contentType));
		assertEquals(2, parts.size());

		Part tokenPart = partNamed(parts, "token");
		assertEquals("token field must carry the configured link token",
			TOKEN, new String(tokenPart.content, StandardCharsets.ISO_8859_1));

		Part clipPart = partNamed(parts, "clip");
		assertTrue("clip part must be typed video/mp4, headers: " + clipPart.headers,
			clipPart.headers.contains("content-type: video/mp4"));
		assertTrue("clip part must carry a filename", clipPart.headers.contains("filename="));

		// The uploaded bytes must decode as MP4 with the downscaled dimensions and the frames
		// the ring buffer retained (last RING_CAPACITY of the TICKS captured; jcodec round-trips
		// them exactly, small tolerance kept for codec edge behaviour).
		File clip = File.createTempFile("osrsbis-lifecycle-received-", ".mp4");
		clip.deleteOnExit();
		try (FileOutputStream fos = new FileOutputStream(clip))
		{
			fos.write(clipPart.content);
		}
		FrameGrab grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(clip));
		Picture first = grab.getNativeFrame();
		assertNotNull("at least one frame must decode from the uploaded clip", first);
		assertEquals(CLIP_W, first.getWidth());
		assertEquals(CLIP_H, first.getHeight());
		int decoded = 1;
		while (grab.getNativeFrame() != null && decoded < TICKS * 2)
		{
			decoded++;
		}
		assertTrue("decoded frame count " + decoded + " should be ~ring capacity " + RING_CAPACITY,
			decoded >= RING_CAPACITY - 2 && decoded <= RING_CAPACITY + 2);
	}

	@Test
	public void toggleOffCapturesNothingAndSendsNothing() throws Exception
	{
		buildPlugin(false);
		plugin.startUp();

		plugin.onWidgetLoaded(widgetLoaded(InterfaceID.SHOPMAIN));
		assertTrue("recording toggle off: no frame listener may be registered",
			drawManager.everyFrameListeners.isEmpty());
		assertEquals(0, drawManager.framesSupplied.get());

		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.SHOPMAIN, 0, false));
		assertNull("recording toggle off: no upload may happen",
			server.takeRequest(500, TimeUnit.MILLISECONDS));
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void nonShopWidgetDoesNotStartCapture() throws Exception
	{
		buildPlugin(true);
		plugin.startUp();

		plugin.onWidgetLoaded(widgetLoaded(InterfaceID.SHOPMAIN + 1));
		assertTrue("non-shop widget group: no frame listener may be registered",
			drawManager.everyFrameListeners.isEmpty());

		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.SHOPMAIN, 0, false));
		assertNull("nothing was captured, so nothing may upload",
			server.takeRequest(500, TimeUnit.MILLISECONDS));
		assertEquals(0, server.getRequestCount());
	}

	// ---- arrange helpers ----

	private void buildPlugin(boolean recordingEnabled) throws Exception
	{
		plugin = new AccountConnectPlugin();
		drawManager = new RecordingDrawManager();

		String baseUrl = server.url("/").toString();
		AccountConnectConfig config = new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public String apiBaseUrl()
			{
				return baseUrl;
			}

			@Override
			public boolean recordGeneralStoreClips()
			{
				return recordingEnabled;
			}

			@Override
			public int clipSeconds()
			{
				return CLIP_SECONDS;
			}

			@Override
			public int clipFps()
			{
				return CLIP_FPS;
			}
		};

		inject("drawManager", drawManager);
		inject("okHttpClient", new OkHttpClient());
		inject("config", config);
		// The plugin's other @Inject fields (client, gson, itemManager, executor) are left null on
		// purpose: the clip path never reads them, and the trade branches early-return because
		// uploadTradeScreenshots() stays at its false default.
	}

	private void inject(String fieldName, Object value) throws Exception
	{
		Field field = AccountConnectPlugin.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(plugin, value);
	}

	private static WidgetLoaded widgetLoaded(int groupId)
	{
		WidgetLoaded event = new WidgetLoaded();
		event.setGroupId(groupId);
		return event;
	}

	/**
	 * Stand-in for RuneLite's DrawManager: records listener (un)registration and answers every
	 * frame request immediately with a synthetic frame whose colour varies per call.
	 */
	private static final class RecordingDrawManager extends DrawManager
	{
		final List<Runnable> everyFrameListeners = new CopyOnWriteArrayList<>();
		final AtomicInteger framesSupplied = new AtomicInteger();

		@Override
		public void registerEveryFrameListener(Runnable listener)
		{
			everyFrameListeners.add(listener);
		}

		@Override
		public void unregisterEveryFrameListener(Runnable listener)
		{
			everyFrameListeners.remove(listener);
		}

		@Override
		public void requestNextFrameListener(Consumer<Image> consumer)
		{
			int i = framesSupplied.getAndIncrement();
			BufferedImage frame = new BufferedImage(SRC_W, SRC_H, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = frame.createGraphics();
			try
			{
				g.setColor(new Color((i * 37) % 256, (i * 53) % 256, (i * 91) % 256));
				g.fillRect(0, 0, SRC_W, SRC_H);
			}
			finally
			{
				g.dispose();
			}
			consumer.accept(frame);
		}
	}

	// ---- multipart parsing (okhttp 3.x has no MultipartReader, so parse the raw body) ----

	private static final class Part
	{
		final String headers; // lower-cased raw header block
		final byte[] content;

		Part(String headers, byte[] content)
		{
			this.headers = headers;
			this.content = content;
		}
	}

	private static String boundaryOf(String contentType)
	{
		int at = contentType.indexOf("boundary=");
		assertTrue("Content-Type must carry a boundary: " + contentType, at >= 0);
		String boundary = contentType.substring(at + "boundary=".length());
		if (boundary.startsWith("\""))
		{
			boundary = boundary.substring(1, boundary.indexOf('"', 1));
		}
		return boundary;
	}

	/**
	 * Minimal multipart/form-data parser. ISO-8859-1 maps bytes to chars one-to-one, so header
	 * scanning happens on a String while part content round-trips back to the exact bytes.
	 */
	private static List<Part> parseMultipart(byte[] body, String boundary)
	{
		String s = new String(body, StandardCharsets.ISO_8859_1);
		String delimiter = "--" + boundary;
		List<Part> parts = new ArrayList<>();
		int at = s.indexOf(delimiter);
		assertTrue("body must start with the boundary", at >= 0);
		while (true)
		{
			int cursor = at + delimiter.length();
			if (s.startsWith("--", cursor))
			{
				break; // closing delimiter
			}
			int headerStart = cursor + 2; // skip CRLF after the delimiter
			int headerEnd = s.indexOf("\r\n\r\n", headerStart);
			assertTrue("part must have a header block", headerEnd >= 0);
			int contentStart = headerEnd + 4;
			int next = s.indexOf("\r\n" + delimiter, contentStart);
			assertTrue("part must be terminated by the next boundary", next >= 0);
			parts.add(new Part(
				s.substring(headerStart, headerEnd).toLowerCase(),
				s.substring(contentStart, next).getBytes(StandardCharsets.ISO_8859_1)));
			at = next + 2;
		}
		return parts;
	}

	private static Part partNamed(List<Part> parts, String name)
	{
		for (Part part : parts)
		{
			if (part.headers.contains("name=\"" + name + "\""))
			{
				return part;
			}
		}
		throw new AssertionError("no multipart part named '" + name + "'");
	}
}
