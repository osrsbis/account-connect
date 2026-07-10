package com.osrsbestinslot.export;

import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.ui.DrawManager;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the trade-screenshot path — no live client needed. Covered here: PNG encoding
 * (valid magic bytes from a synthetic frame), the toggle-off early-return (no capture, no state),
 * and the state machine's arm / commit / discard transitions including a real multipart POST to a
 * local JDK HttpServer stub. NOT coverable without a live client (manual test plan in the report):
 * real WidgetLoaded/ChatMessage event delivery order on a live trade, actual game-frame contents,
 * Guice injection wiring, and the production ingest endpoint.
 */
public class TradeScreenshotTest
{
	private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";

	// ---- PNG encode path ----

	@Test
	public void encodePngProducesValidNonEmptyPng()
	{
		byte[] bytes = AccountConnectPlugin.encodePng(syntheticFrame(320, 240));
		assertNotNull("encode returned null", bytes);
		assertTrue("PNG is empty", bytes.length > 0);
		for (int i = 0; i < PNG_MAGIC.length; i++)
		{
			assertEquals("PNG magic byte " + i + " wrong", PNG_MAGIC[i], bytes[i]);
		}
	}

	@Test
	public void toBufferedImagePassesThroughAndEncodesEndToEnd()
	{
		BufferedImage converted = AccountConnectPlugin.toBufferedImage(syntheticFrame(64, 48));
		byte[] bytes = AccountConnectPlugin.encodePng(converted);
		assertNotNull(bytes);
		assertTrue(startsWith(bytes, PNG_MAGIC));
	}

	// ---- toggle OFF: handlers return early, nothing captured ----

	@Test
	public void toggleOffPerformsNoCaptureAndHoldsNoState() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		// Defaults only: uploadTradeScreenshots() == false. drawManager / executor / okHttpClient
		// are left null on purpose — if any handler got past the toggle guard and tried to capture
		// or upload, it would NPE and fail this test.
		inject(plugin, "config", new AccountConnectConfig() {});

		plugin.handleTradeContainerChanged(90);
		assertFalse("toggle off must not arm tradeActive", plugin.tradeActive);

		plugin.handleTradeWidgetLoaded(334);
		assertNull("toggle off must not buffer a frame", plugin.pendingTradeFrame.get());

		plugin.handleTradeChat("Accepted trade.");
		assertFalse(plugin.tradeActive);
		assertNull(plugin.pendingTradeFrame.get());
	}

	@Test
	public void togglingOffMidTradeDropsTheBufferedFrame() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", new AccountConnectConfig() {});
		// Simulate a frame buffered while the toggle was on, then the toggle switched off:
		plugin.tradeActive = true;
		plugin.pendingTradeFrame.set(syntheticFrame(8, 8));

		plugin.handleTradeChat("Accepted trade.");	// guard runs first — must clear, never upload
		assertFalse(plugin.tradeActive);
		assertNull("disabled guard must drop the stale frame", plugin.pendingTradeFrame.get());
	}

	// ---- toggle ON: arm -> frame -> commit uploads one multipart PNG ----

	@Test
	public void armCommitUploadsBothConfirmAndCompletedScreenshots() throws Exception
	{
		// Delivery proof = TWO shots per completed trade: the confirm window (items + partner) and the
		// post-accept frame (the "Accepted trade." chat line, window closed). Both must reach the ingest
		// route, each tagged with its phase.
		java.util.concurrent.BlockingQueue<Capture> received = new java.util.concurrent.LinkedBlockingQueue<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange ->
		{
			byte[] reqBody = readAll(exchange.getRequestBody());
			byte[] resp = "{\"ok\":true,\"id\":1}".getBytes(StandardCharsets.UTF_8);
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
			DrawManager drawManager = new DrawManager();
			inject(plugin, "drawManager", drawManager);
			inject(plugin, "executor", Executors.newSingleThreadScheduledExecutor());
			inject(plugin, "okHttpClient", new OkHttpClient());

			plugin.handleTradeContainerChanged(90);	// trade genuinely in progress
			assertTrue(plugin.tradeActive);

			plugin.handleTradeWidgetLoaded(334);	// confirm screen -> one-shot frame request
			drawManager.processDrawComplete(() -> syntheticFrame(320, 240));	// deliver the confirm frame
			assertNotNull("frame must be buffered after 334-load + draw", plugin.pendingTradeFrame.get());

			plugin.handleTradeChat("Accepted trade.");	// commit: uploads confirm + arms the completed grab
			assertFalse(plugin.tradeActive);
			assertNull("commit must consume the buffered frame", plugin.pendingTradeFrame.get());
			drawManager.processDrawComplete(() -> syntheticFrame(320, 240));	// deliver the completed frame

			Capture a = received.poll(15, TimeUnit.SECONDS);
			Capture b = received.poll(15, TimeUnit.SECONDS);
			assertNotNull("first screenshot upload missing", a);
			assertNotNull("second (completed) screenshot upload missing", b);
			String phases = "";
			for (Capture c : new Capture[] {a, b})
			{
				assertEquals("/wp-json/osrsbis/v1/screenshot-ingest", c.path);
				assertTrue("not multipart: " + c.contentType, c.contentType.startsWith("multipart/form-data"));
				String body = new String(c.body, StandardCharsets.ISO_8859_1);
				assertTrue("token field missing", body.contains("name=\"token\"") && body.contains(TEST_TOKEN));
				assertTrue("kind field missing", body.contains("name=\"kind\"") && body.contains("trade"));
				assertTrue("phase field missing", body.contains("name=\"phase\""));
				assertTrue("captured_at field missing", body.contains("name=\"captured_at\""));
				assertTrue("file part missing", body.contains("name=\"file\""));
				assertTrue("file part not image/png", body.contains("image/png"));
				assertTrue("body carries no PNG payload", indexOf(c.body, PNG_MAGIC) >= 0);
				phases += body;
			}
			assertTrue("confirm-phase screenshot not uploaded", phases.contains("confirm"));
			assertTrue("completed-phase screenshot not uploaded", phases.contains("completed"));
		}
		finally
		{
			server.stop(0);
		}
	}

	// ---- discard paths: decline / window closed / disconnect — never upload ----

	@Test
	public void declineDiscardsFrameWithoutUpload() throws Exception
	{
		AccountConnectPlugin plugin = armedPluginWithoutNetwork();
		plugin.handleTradeChat("Other player declined trade.");
		assertFalse(plugin.tradeActive);
		assertNull(plugin.pendingTradeFrame.get());
	}

	@Test
	public void widgetClosedDiscardsFrameWithoutUpload() throws Exception
	{
		AccountConnectPlugin plugin = armedPluginWithoutNetwork();
		plugin.handleTradeWidgetClosed(334);
		assertFalse(plugin.tradeActive);
		assertFalse(plugin.tradeArmed);
		assertNull(plugin.pendingTradeFrame.get());

		plugin = armedPluginWithoutNetwork();
		plugin.handleTradeWidgetClosed(335);	// first trade screen closing while ARMED also discards
		assertFalse(plugin.tradeActive);
		assertFalse(plugin.tradeArmed);
		assertNull(plugin.pendingTradeFrame.get());
	}

	@Test
	public void firstScreenCloseBeforeConfirmDoesNotKillTheTrade() throws Exception
	{
		// Normal 335 -> 334 transition: the first trade screen closes while we are tradeActive but
		// not yet ARMED. That close must NOT reset state, or the confirm screen could never arm.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", enabledConfig(null));
		plugin.tradeActive = true;

		plugin.handleTradeWidgetClosed(335);
		assertTrue("335 closing pre-ARMED must not clear tradeActive", plugin.tradeActive);
	}

	@Test
	public void disconnectClearsPendingFrameWithoutUpload() throws Exception
	{
		AccountConnectPlugin plugin = armedPluginWithoutNetwork();
		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.CONNECTION_LOST);
		plugin.onGameStateChanged(event);
		assertFalse(plugin.tradeActive);
		assertNull(plugin.pendingTradeFrame.get());
	}

	@Test
	public void malformedTokenNeverUploads() throws Exception
	{
		// Toggle ON but token invalid: commit path must stop at the token guard, before touching
		// the (null) executor/okHttpClient — an upload attempt would NPE and fail the test.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", new AccountConnectConfig()
		{
			@Override
			public boolean uploadTradeScreenshots()
			{
				return true;
			}
			// linkToken() default is "" — fails ^[a-f0-9]{32}$
		});
		plugin.tradeActive = true;
		plugin.pendingTradeFrame.set(syntheticFrame(8, 8));

		plugin.handleTradeChat("Accepted trade.");
		assertNull(plugin.pendingTradeFrame.get());
		assertFalse(plugin.tradeActive);
	}

	// ---- helpers ----

	/** Toggle ON, ARMED (frame buffered), but no executor/okHttpClient: any upload attempt would NPE. */
	private static AccountConnectPlugin armedPluginWithoutNetwork() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", enabledConfig(null));
		plugin.tradeActive = true;
		plugin.tradeArmed = true;
		plugin.pendingTradeFrame.set(syntheticFrame(8, 8));
		return plugin;
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

	private static BufferedImage syntheticFrame(int w, int h)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, w, h);
		g.setColor(Color.GREEN);
		g.fillRect(0, 0, w / 2, h / 2);
		g.dispose();
		return img;
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

	private static boolean startsWith(byte[] data, byte[] prefix)
	{
		if (data.length < prefix.length)
		{
			return false;
		}
		for (int i = 0; i < prefix.length; i++)
		{
			if (data[i] != prefix[i])
			{
				return false;
			}
		}
		return true;
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
