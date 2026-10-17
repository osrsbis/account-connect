package com.osrsbestinslot.export;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.events.ConfigChanged;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real plugin emitter, journal, restart and HTTP receipt path; no live game client/account. */
public class PublicEventOutboxPluginTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private final AtomicReference<String> token = new AtomicReference<>(TOKEN);
    private final List<AccountConnectPlugin> plugins = new ArrayList<>();
    private MockWebServer server;
    private OkHttpClient http;
    private Path data;
    private AccountConnectPlugin p;
    @Before public void setup() throws Exception
    {
        data = Files.createTempDirectory("public-plugin"); server = new MockWebServer(); server.start();
        http = new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(); p = fresh("STANDARD", 7);
    }
    @After public void close() throws Exception
    {
        for (AccountConnectPlugin plugin : plugins) plugin.crashSpoolForTest();
        server.shutdown(); http.dispatcher().executorService().shutdownNow(); http.connectionPool().evictAll();
        try (java.util.stream.Stream<Path> paths = Files.walk(data))
        { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
    }
    private AccountConnectPlugin fresh(String mode, long profileId) throws Exception
    {
        AccountConnectPlugin plugin = new AccountConnectPlugin(); plugins.add(plugin);
        AccountConnectConfig config = mock(AccountConnectConfig.class);
        when(config.linkToken()).thenAnswer(call -> token.get()); when(config.apiBaseUrl()).thenReturn(server.url("/api").toString());
        ConfigManager manager = mock(ConfigManager.class); ConfigProfile profile = mock(ConfigProfile.class);
        when(profile.getId()).thenReturn(profileId); when(manager.getProfile()).thenReturn(profile);
        set(plugin, "config", config); set(plugin, "configManager", manager); set(plugin, "gson", new Gson());
        set(plugin, "okHttpClient", http); set(plugin, "activeHash", "123"); set(plugin, "activeProfileType", mode);
        set(plugin, "sessionActive", true); plugin.setSpoolDataRootForTest(data); return plugin;
    }
    private static void set(AccountConnectPlugin plugin, String name, Object value) throws Exception
    { Field f = AccountConnectPlugin.class.getDeclaredField(name); f.setAccessible(true); f.set(plugin, value); }
    private void grant() throws Exception
    {
        Response r = new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Queue", "own-history-v1").build();
        p.applyServerPolicy(r, token.get()); p.awaitSpoolIdleForTest();
    }
    private void emit() throws Exception
    {
        Map<String,Object> fields = new LinkedHashMap<>(); fields.put("item", 2); fields.put("qty", 1);
        fields.put("other_player_name", "must-not-send"); p.emitEvent("ge_progress", fields); p.awaitSpoolIdleForTest();
    }
    private JsonObject request() throws Exception
    {
        RecordedRequest r = server.takeRequest(3, TimeUnit.SECONDS); assertNotNull(r);
        String body = r.getBody().readUtf8(); assertFalse(body.contains("__public")); assertFalse(body.contains("must-not-send"));
        return new JsonParser().parse(body).getAsJsonObject();
    }
    private void done() throws Exception
    {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (p.eventPostInFlight && System.nanoTime() < until) Thread.sleep(5);
        assertFalse(p.eventPostInFlight); p.awaitSpoolIdleForTest();
    }
    private String receipt(String id)
    { return "{\"receipt_version\":1,\"results\":[{\"index\":0,\"event_id\":\""+id+"\",\"status\":\"queued\",\"broker_ack\":true}]}"; }
    @Test public void ownVarbitContextSurvivesCrashAndCannotBeForgedByCallerFields() throws Exception
    {
        net.runelite.api.Client client=mock(net.runelite.api.Client.class);
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        when(client.getAccountHash()).thenReturn(123L);when(client.getTickCount()).thenReturn(50);
        set(p,"client",client);p.refreshEventObservationContext();
        // Missing definition/varps must not turn the mock/default0 into NORMAL.
        p.emitEvent("pickup",Map.of("item",2,"qty",1));
        assertNull(((Map<?,?>)p.pendingEvents.get(0).get("observation_context")).get("account_mode"));
        p.pendingEvents.clear();
        net.runelite.api.VarbitComposition def=mock(net.runelite.api.VarbitComposition.class);
        when(def.getIndex()).thenReturn(1);when(def.getLeastSignificantBit()).thenReturn(0);
        when(def.getMostSignificantBit()).thenReturn(2);when(client.getVarps()).thenReturn(new int[]{0,1});
        when(client.getVarbit(AccountConnectPlugin.ACCOUNT_TYPE_VARBIT)).thenReturn(def);
        p.refreshEventObservationContext();grant();
        p.emitEvent("pickup",Map.of("item",2,"qty",1,"observation_context",Map.of("other_player","must-not-send")));
        p.awaitSpoolIdleForTest();Map<?,?> before=(Map<?,?>)p.pendingEvents.get(0).get("observation_context");
        assertEquals("IRONMAN",before.get("account_mode"));assertEquals("varbit_1777",before.get("account_mode_source"));
        assertEquals("0.7.18",before.get("plugin_version"));
        assertEquals("STANDARD",p.pendingEvents.get(0).get("profile_type"));
        assertFalse(before.toString().contains("must-not-send"));
        String session=(String)before.get("collector_session");Object sequence=before.get("sequence");
        p.crashSpoolForTest();p=fresh("STANDARD",7);grant();
        Map<?,?> replay=(Map<?,?>)p.pendingEvents.get(0).get("observation_context");
        assertEquals(session,replay.get("collector_session"));
        assertEquals(((Number)sequence).longValue(),((Number)replay.get("sequence")).longValue());
        assertEquals("IRONMAN",replay.get("account_mode"));
        p.emitEvent("pickup",Map.of("item",3,"qty",1));p.awaitSpoolIdleForTest();
        Map<?,?> newProcess=(Map<?,?>)p.pendingEvents.get(1).get("observation_context");
        assertNotEquals(session,newProcess.get("collector_session"));assertNull(newProcess.get("account_mode"));
        // Early callbacks can already identify a new account/profile before the slower
        // session tracker. Never attach the previous account's context to those rows.
        p.emitEvent("pickup",Map.of("item",3,"qty",1,"account_hash","456"));p.awaitSpoolIdleForTest();
        assertFalse(p.pendingEvents.get(2).containsKey("observation_context"));
        p.emitEvent("pickup",Map.of("item",3,"qty",1,"profile_type","LEAGUE"));p.awaitSpoolIdleForTest();
        assertFalse(p.pendingEvents.get(3).containsKey("observation_context"));
    }
    @Test public void emittedEventSurvivesProcessDeathAndGeneric200CannotRemoveIt() throws Exception
    {
        grant(); emit(); String id = (String)p.pendingEvents.get(0).get("event_id");
        assertEquals(1, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.crashSpoolForTest(); p = fresh("STANDARD", 7);
        p.pumpPublicOutbox(); p.awaitSpoolIdleForTest(); assertTrue(p.pendingEvents.isEmpty()); // no fresh grant
        grant(); assertEquals(id, p.pendingEvents.get(0).get("event_id"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"stored\":1}")); p.flushEvents();
        assertEquals("queue-v1", request().get("ack_protocol").getAsString()); done();
        assertEquals(1, p.pendingEvents.size()); assertEquals(1, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.queueRetryBackoffUntilMs = 0; server.enqueue(new MockResponse().setBody(receipt(id))); p.flushEvents(); request(); done();
        assertEquals(0, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.crashSpoolForTest(); p = fresh("STANDARD", 7); grant(); assertTrue(p.pendingEvents.isEmpty());
    }
    @Test public void logoutPausesAndModeOrRuneLiteProfileCannotReplayPriorRows() throws Exception
    {
        grant(); emit(); set(p, "sessionActive", false); p.flushEvents(); assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        p.crashSpoolForTest(); p = fresh("LEAGUE", 7); grant(); assertTrue(p.pendingEvents.isEmpty());
        emit(); p.crashSpoolForTest(); p = fresh("LEAGUE", 8); grant(); assertTrue(p.pendingEvents.isEmpty());
    }
    @Test public void revokedTokenPurgesBeforeAnyRestartReplay() throws Exception
    {
        grant(); emit(); server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":\"revoked\"}"));
        p.flushEvents(); request(); done(); assertEquals(0, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.crashSpoolForTest(); p = fresh("STANDARD", 7); grant(); assertTrue(p.pendingEvents.isEmpty());
    }
    @Test public void tokenConfigWithdrawalCannotSendOldRowsUnderNewToken() throws Exception
    {
        grant(); emit(); String replacement = "ffffffffffffffffffffffffffffffff"; token.set(replacement);
        ConfigChanged e = mock(ConfigChanged.class); when(e.getGroup()).thenReturn("accountconnect");
        // Actual constant is used by the production callback; do not silently test a different key.
        Field group = AccountConnectPlugin.class.getDeclaredField("CONFIG_GROUP"); group.setAccessible(true);
        when(e.getGroup()).thenReturn((String)group.get(null)); when(e.getKey()).thenReturn("linkToken");
        when(e.getOldValue()).thenReturn(TOKEN); when(e.getNewValue()).thenReturn(replacement);
        p.onConfigChanged(e); p.awaitSpoolIdleForTest(); grant(); p.flushEvents();
        assertTrue(p.pendingEvents.isEmpty()); assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }
    @Test public void publicDurableRowsAreNotMixedWithUnjournaledQueueRows() throws Exception
    {
        // A pre-grant event retains the existing memory path; its receipt must not strand later disk rows.
        emit(); String first = (String)p.pendingEvents.get(0).get("event_id"); grant(); emit();
        String second = (String)p.pendingEvents.get(1).get("event_id");
        server.enqueue(new MockResponse().setBody(receipt(first))); p.flushEvents();
        assertEquals(1, request().getAsJsonArray("events").size()); done();
        server.enqueue(new MockResponse().setBody(receipt(second))); p.flushEvents();
        assertEquals(1, request().getAsJsonArray("events").size()); done();
        assertEquals(0, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void coldRevocationPurgesDiskBeforeAnyFreshGrantOrAccount() throws Exception
    {
        grant(); emit(); p.crashSpoolForTest(); p = fresh("STANDARD", 7);
        set(p, "sessionActive", false); set(p, "activeHash", null);
        p.spoolOnRevoked(TOKEN); p.awaitSpoolIdleForTest();
        p.crashSpoolForTest(); p = fresh("STANDARD", 7); grant();
        assertTrue(p.pendingEvents.isEmpty());
        try (java.util.stream.Stream<Path> paths = Files.walk(data))
        { assertFalse(paths.anyMatch(path -> path.toString().endsWith(".spl"))); }
    }
    @Test public void agedMemoryCopyCannotBypassTheDurableReplayBound() throws Exception
    {
        grant(); emit();
        long future = System.currentTimeMillis() + EventSpool.MAX_AGE_MS + 10000;
        p.wallClock = () -> future;
        p.flushEvents(); done();
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        assertEquals(1, ((Number)p.captureHealthSnapshot().get("public_outbox_dropped_total")).intValue());
    }
    @Test public void anotherProcessErasureMarkerStopsAlreadyStagedMemoryBeforeSend() throws Exception
    {
        grant(); emit();
        PublicEventOutbox.Context owner = new PublicEventOutbox.Context(TOKEN, "123", "STANDARD", "7");
        Path identity = data.resolve("osrsbis/public-events-v1").resolve(owner.identity);
        EventSpool.purgeIdentityDir(identity, System::currentTimeMillis);
        p.flushEvents(); done();
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }
    @Test public void lateErasureReceiptDoesNotPurgeTheNewAccountOnTheSameToken() throws Exception
    {
        grant(); emit(); String erasedId = (String)p.pendingEvents.get(0).get("event_id");
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher()
        {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException
            {
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return new MockResponse().setBody("{\"receipt_version\":1,\"results\":[{\"index\":0,\"event_id\":\""
                    + erasedId + "\",\"status\":\"rejected\",\"reason\":\"erased\"}]}");
            }
        });
        try
        {
            p.flushEvents(); request(); set(p, "activeHash", "456"); grant(); emit();
            String keptId = (String)p.pendingEvents.get(0).get("event_id");
            release.countDown(); done();
            assertEquals(1, p.pendingEvents.size()); assertEquals(keptId, p.pendingEvents.get(0).get("event_id"));
            assertEquals(1, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        }
        finally { release.countDown(); }
    }
    @Test public void diskFailureKeepsBoundedQueueRetryAndRecoversWhenStorageReturns() throws Exception
    {
        grant();
        Path blockedRoot = data.resolve("osrsbis/public-events-v1");
        Files.createDirectories(blockedRoot.getParent()); Files.write(blockedRoot, new byte[]{1});
        emit(); String id = (String)p.pendingEvents.get(0).get("event_id");
        assertEquals(1, ((Number)p.captureHealthSnapshot().get("public_outbox_not_durable_total")).intValue());
        assertEquals(0, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        server.enqueue(new MockResponse().setResponseCode(500)); p.flushEvents(); request(); done();
        assertEquals(1, p.pendingEvents.size()); assertTrue(p.queueRetryBackoffUntilMs > 0);
        p.queueRetryBackoffUntilMs = 0;
        server.enqueue(new MockResponse().setBody(receipt(id))); p.flushEvents(); request(); done();
        assertTrue(p.pendingEvents.isEmpty());
        Files.delete(blockedRoot); emit();
        assertEquals(1, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void diskFailureFallbackCannotCrossTheAccountBoundary() throws Exception
    {
        grant(); Path blockedRoot = data.resolve("osrsbis/public-events-v1");
        Files.createDirectories(blockedRoot.getParent()); Files.write(blockedRoot, new byte[]{1});
        emit(); set(p, "activeHash", "456"); p.flushEvents(); done();
        assertTrue(p.pendingEvents.isEmpty()); assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        assertEquals(1, ((Number)p.captureHealthSnapshot().get("events_lost_total")).intValue());
    }
}
