package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Isolated audit of transient failure handling on the active legacy transport. */
public class LegacyTransientDeliveryAuditTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private AccountConnectPlugin plugin;
    private MockWebServer server;
    private OkHttpClient http;

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        plugin = new AccountConnectPlugin();
        http = new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build();
        set("gson", new Gson());
        set("okHttpClient", http);
        AccountConnectConfig config = mock(AccountConnectConfig.class);
        when(config.linkToken()).thenReturn(TOKEN);
        when(config.apiBaseUrl()).thenReturn(server.url("/api").toString());
        set("config", config);
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("event_id", ID);
        event.put("type", "level_up");
        event.put("skill", "Attack");
        event.put("level", 50);
        event.put("ts", 1700000000000L);
        plugin.events.pendingEvents.add(event);
    }

    @After public void close() throws Exception {
        server.shutdown();
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }
    private void set(String name, Object value) throws Exception {
        Field f = AccountConnectPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(plugin, value);
    }
    private void settled() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (plugin.events.eventPostInFlight && System.nanoTime() < until)
            TimeUnit.MILLISECONDS.sleep(5);
        assertFalse("callback must complete", plugin.events.eventPostInFlight);
    }
    private RecordedRequest request() throws Exception {
        RecordedRequest r = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull("expected actual HTTP request", r);
        return r;
    }
    private void transientFailureSurvives(int status, String body, boolean doubleResponse) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(status).setBody(body));
        if (doubleResponse) server.enqueue(new MockResponse().setResponseCode(status).setBody(body));
        plugin.events.flushEvents();
        String first = request().getBody().readUtf8();
        assertFalse("exercise the currently active legacy lane", first.contains("ack_protocol"));
        if (doubleResponse) request();
        settled();
        System.out.println("status=" + status + " pending=" + plugin.events.pendingEvents.size()
            + " lost=" + plugin.events.captureHealthSnapshot().get("events_lost_total")
            + " backoff=" + plugin.events.eventRetryBackoffUntilMs);
        assertEquals("transient HTTP " + status + " must not destroy the event", 1, plugin.events.pendingEvents.size());
        assertEquals(0L, plugin.events.captureHealthSnapshot().get("events_lost_total"));
        assertTrue("retry must be bounded", plugin.events.eventRetryBackoffUntilMs > 0);
        plugin.events.eventRetryBackoffUntilMs = 0;
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        plugin.events.flushEvents();
        String retry = request().getBody().readUtf8();
        assertTrue("the same event identity must be retried", retry.contains(ID));
        settled();
        assertTrue(plugin.events.pendingEvents.isEmpty());
        assertEquals(1L, plugin.events.captureHealthSnapshot().get("events_sent_total"));
        assertEquals(0L, plugin.events.captureHealthSnapshot().get("events_lost_total"));
    }
    @Test public void requestTimeoutSurvivesAfterOkHttpAutomaticRetry() throws Exception {
        transientFailureSurvives(408, "{\"error\":\"request_timeout\"}", true);
    }
    @Test public void providerHtml404SurvivesForRecovery() throws Exception {
        transientFailureSurvives(404, "<html>provider route temporarily unavailable</html>", false);
    }
    @Test public void existing503RetryIsPositiveControl() throws Exception {
        transientFailureSurvives(503, "{\"error\":\"overloaded\"}", false);
    }
    private void hugeRetryAfterIsCapped(boolean queue) throws Exception {
        if (queue) {
            plugin.grants.eventQueueGrantToken = TOKEN;
            plugin.grants.eventQueueScope = "own-history-v1";
        }
        server.enqueue(new MockResponse().setResponseCode(429)
            .setHeader("Retry-After", Long.toString(Long.MAX_VALUE)).setBody("{\"error\":\"rate_limit\"}"));
        plugin.events.flushEvents();
        String body = request().getBody().readUtf8();
        assertEquals(queue, body.contains("ack_protocol"));
        settled();
        assertEquals(1, plugin.events.pendingEvents.size());
        assertEquals(300000L, queue ? plugin.events.queueRetryBackoffMs : plugin.events.eventRetryBackoffMs);
    }
    @Test public void hugeRetryAfterCapsLegacyMemoryDelay() throws Exception { hugeRetryAfterIsCapped(false); }
    @Test public void hugeRetryAfterCapsQueueMemoryDelay() throws Exception { hugeRetryAfterIsCapped(true); }
    @Test public void explicitApplication400RemainsTerminalControl() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_event\"}"));
        plugin.events.flushEvents();
        request();
        settled();
        assertTrue(plugin.events.pendingEvents.isEmpty());
        assertEquals(1L, plugin.events.captureHealthSnapshot().get("events_lost_total"));
        assertEquals(0L, plugin.events.captureHealthSnapshot().get("events_sent_total"));
    }
}
