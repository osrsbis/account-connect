package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * The two /screenshot-ingest uploads, byte for byte apart from the multipart boundary and the capture second: every
 * part, its order, its headers and its value. The expected bodies were recorded from the 0.7.72 base before the two
 * uploads shared their form builder.
 */
public class ScreenshotUploadWireTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private MockWebServer server;
    private AccountConnectPlugin plugin;

    @Before
    public void setup() throws Exception
    {
        server = new MockWebServer();
        server.start();
        plugin = new AccountConnectPlugin();
        AccountConnectConfig config = mock(AccountConnectConfig.class);
        when(config.linkToken()).thenReturn(TOKEN);
        when(config.apiBaseUrl()).thenReturn(server.url("/api").toString());
        put("config", config);
        put("gson", new Gson());
        put("okHttpClient", new OkHttpClient());
        put("activeHash", "12345");
    }

    @After
    public void teardown() throws Exception
    {
        server.shutdown();
    }

    private void put(String key, Object value) throws Exception
    {
        Field f = AccountConnectPlugin.class.getDeclaredField(key);
        f.setAccessible(true);
        f.set(plugin, value);
    }

    /** The body with the boundary replaced by "B" and the given capture second by "T". */
    private static String normalized(RecordedRequest request, String second)
    {
        String type = request.getHeader("Content-Type");
        Matcher m = Pattern.compile("boundary=(.+)$").matcher(type);
        assertTrue(type, m.find());
        String body = request.getBody().readUtf8().replace(m.group(1), "B");
        return (second == null ? body : body.replace(second, "T")).replace("\r\n", "|");
    }

    @Test
    public void tradeScreenshotBodyIsUnchanged() throws Exception
    {
        plugin.grants.staffApiBase = server.url("/api").toString();
        plugin.grants.setStaffCapture(TOKEN, "12345");
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        plugin.trade.uploadTradeScreenshot(TOKEN, new byte[]{'P', 'N', 'G'}, "confirm", "12345");
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/screenshot-ingest", request.getPath());
        assertEquals("multipart/form-data; boundary=", request.getHeader("Content-Type").replaceAll("boundary=.*", "boundary="));
        String raw = request.getBody().clone().readUtf8();
        Matcher second = Pattern.compile("trade-confirm-([0-9]+)\\.png").matcher(raw);
        assertTrue(raw, second.find());
        assertEquals("--B|Content-Disposition: form-data; name=\"token\"|Content-Length: 32||" + TOKEN + "|"
            + "--B|Content-Disposition: form-data; name=\"account_hash\"|Content-Length: 5||12345|"
            + "--B|Content-Disposition: form-data; name=\"kind\"|Content-Length: 5||trade|"
            + "--B|Content-Disposition: form-data; name=\"phase\"|Content-Length: 7||confirm|"
            + "--B|Content-Disposition: form-data; name=\"captured_at\"|Content-Length: " + second.group(1).length() + "||T|"
            + "--B|Content-Disposition: form-data; name=\"file\"; filename=\"trade-confirm-T.png\"|"
            + "Content-Type: image/png|Content-Length: 3||PNG|--B--|", normalized(request, second.group(1)));
        for (int i = 0; i < 50 && plugin.events.healthLastMediaUploadMs.get() == 0; i++) Thread.sleep(20);
        assertTrue("a stored screenshot still dates the last media upload", plugin.events.healthLastMediaUploadMs.get() > 0);
    }

    @Test
    public void verificationFrameBodyIsUnchanged() throws Exception
    {
        plugin.grants.applyServerPolicy(new Response.Builder().request(new Request.Builder().url(server.url("/")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("X-Verification", "on").header("X-Verification-Frames", "on").build());
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"id\":1}"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"id\":2}"));
        Map<String, Object> common = Fields.of("ref", "0123456789abcdef");
        plugin.verify.uploadVerificationFrame(plugin.verify.currentOwner(TOKEN), new byte[]{'P', 'N', 'G'},
            "0123456789abcdef", 1234000L, common, 1, false, null);
        String head = "--B|Content-Disposition: form-data; name=\"token\"|Content-Length: 32||" + TOKEN + "|"
            + "--B|Content-Disposition: form-data; name=\"account_hash\"|Content-Length: 5||12345|"
            + "--B|Content-Disposition: form-data; name=\"kind\"|Content-Length: 6||verify|"
            + "--B|Content-Disposition: form-data; name=\"ref\"|Content-Length: 16||0123456789abcdef|"
            + "--B|Content-Disposition: form-data; name=\"captured_at\"|Content-Length: 4||1234|";
        String file = "--B|Content-Disposition: form-data; name=\"file\"; filename=\"verify-0123456789abcdef.png\"|"
            + "Content-Type: image/png|Content-Length: 3||PNG|--B--|";
        assertEquals(head + file, normalized(server.takeRequest(3, TimeUnit.SECONDS), null));
        plugin.verify.uploadVerificationFrame(plugin.verify.currentOwner(TOKEN), new byte[]{'P', 'N', 'G'},
            "0123456789abcdef", 1234000L, common, 1, false, "{\"v\":1}");
        assertEquals(head + "--B|Content-Disposition: form-data; name=\"pairing\"|Content-Length: 7||{\"v\":1}|" + file,
            normalized(server.takeRequest(3, TimeUnit.SECONDS), null));
    }
}
