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
        for (AccountConnectPlugin plugin : plugins) plugin.spooler.crashSpoolForTest();
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
        plugin.session.sessionActive = true; plugin.spooler.setSpoolDataRootForTest(data); return plugin;
    }
    private static void set(AccountConnectPlugin plugin, String name, Object value) throws Exception
    { Field f = AccountConnectPlugin.class.getDeclaredField(name); f.setAccessible(true); f.set(plugin, value); }
    private void grant() throws Exception
    {
        Response r = new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Queue", "own-history-v1").build();
        p.grants.applyServerPolicy(r, token.get()); p.spooler.awaitSpoolIdleForTest();
    }
    private void emit() throws Exception
    {
        Map<String,Object> fields = new LinkedHashMap<>(); fields.put("item", 2); fields.put("qty", 1);
        fields.put("other_player_name", "must-not-send"); p.events.emitEvent("ge_progress", fields); p.spooler.awaitSpoolIdleForTest();
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
        while (p.events.eventPostInFlight && System.nanoTime() < until) Thread.sleep(5);
        assertFalse(p.events.eventPostInFlight); p.spooler.awaitSpoolIdleForTest();
    }
    private String receipt(String id)
    { return "{\"receipt_version\":1,\"results\":[{\"index\":0,\"event_id\":\""+id+"\",\"status\":\"queued\",\"broker_ack\":true}]}"; }

    private void gameplayOnlyGrant(boolean gameplay) throws Exception
    {
        Response r=new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Own-Gameplay",gameplay?"on":"off").build();
        p.grants.applyServerPolicy(r,token.get());p.spooler.awaitSpoolIdleForTest();
    }
    @Test public void anOwnQueueScopeNoServerSendsGrantsNothingAndOwnRowsKeepTheLegacyReceiver() throws Exception
    {
        // The server grants only ge-v1, own-history-v1 or off. An own-* scope is ignored: no Queue grant, no journal.
        Response r=new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Queue","own-combat-v1")
            .header("X-Own-Gameplay","on").build();
        p.grants.applyServerPolicy(r,token.get());p.spooler.awaitSpoolIdleForTest();
        Map<String,Object> hit=new LinkedHashMap<>(Map.of("type","combat_hit","combat_schema",1,
            "gameplay_session","e".repeat(32),"source_sequence",1,"source_tick",42,
            "observed_at_ms",System.currentTimeMillis(),"source","HitsplatApplied","direction","received","amount",0,"hitsplat_type",16));
        assertTrue(p.emitOwnCombat(hit));p.spooler.awaitSpoolIdleForTest();
        assertEquals(1,p.events.pendingEvents.size());
        assertEquals(0,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"stored\":1}"));p.events.flushEvents();
        JsonObject body=request();assertFalse(body.has("ack_protocol"));
        assertEquals("combat_hit",body.getAsJsonArray("events").get(0).getAsJsonObject().get("type").getAsString());done();
        assertTrue(p.events.pendingEvents.isEmpty());
    }
    @Test public void grantedClosedGameplayWithoutQueueScopeUsesLegacyWriter() throws Exception
    {
        Map<String,Object> hit=new LinkedHashMap<>(Map.of("type","combat_hit","combat_schema",1,
            "gameplay_session","e".repeat(32),"source_sequence",1,"source_tick",42,
            "observed_at_ms",System.currentTimeMillis(),"source","HitsplatApplied","direction","dealt_to_npc","amount",3,"hitsplat_type",16));
        gameplayOnlyGrant(false);
        assertFalse("no grant, nothing on the legacy path",p.emitOwnCombat(hit));p.spooler.awaitSpoolIdleForTest();
        assertTrue(p.events.pendingEvents.isEmpty());
        gameplayOnlyGrant(true);
        assertTrue(p.emitOwnCombat(hit));p.spooler.awaitSpoolIdleForTest();
        assertEquals(1,p.events.pendingEvents.size());assertEquals("combat_hit",p.events.pendingEvents.get(0).get("type"));
        assertNotNull("legacy combat carries an event_id for server dedupe",p.events.pendingEvents.get(0).get("event_id"));
        p.events.pendingEvents.clear();
        Map<String,Object> state=OwnStateEventTest.goalEvent();
        assertTrue("closed own_state uses the granted legacy receiver",p.emitOwnGameplayEvent(state));
        p.spooler.awaitSpoolIdleForTest();assertEquals(1,p.events.pendingEvents.size());
        assertEquals("own_state",p.events.pendingEvents.get(0).get("type"));p.events.pendingEvents.clear();
        p.events.emitEvent("own_state",Map.of("state_schema",1));p.spooler.awaitSpoolIdleForTest();
        assertTrue(p.events.pendingEvents.isEmpty());
        gameplayOnlyGrant(false);
        p.events.emitEvent("combat_hit",Map.of("combat_schema",1));p.spooler.awaitSpoolIdleForTest();
        assertTrue("a revoked grant stops the legacy combat path",p.events.pendingEvents.isEmpty());
    }
    @Test public void grantedOwnSignalWithoutQueueScopeUsesLegacyWriterOnlyWhenValid() throws Exception
    {
        Map<String,Object> signal=new LinkedHashMap<>(OwnSignalEventTest.event("farming.compost"));
        gameplayOnlyGrant(false);
        assertFalse("no grant, no legacy signal",p.emitOwnGameplayEvent(signal));p.spooler.awaitSpoolIdleForTest();
        assertTrue(p.events.pendingEvents.isEmpty());
        gameplayOnlyGrant(true);
        assertTrue(p.emitOwnGameplayEvent(signal));p.spooler.awaitSpoolIdleForTest();
        assertEquals(1,p.events.pendingEvents.size());assertEquals("own_signal",p.events.pendingEvents.get(0).get("type"));
        assertNotNull(p.events.pendingEvents.get(0).get("event_id"));
        p.events.pendingEvents.clear();
        Map<String,Object> broken=new LinkedHashMap<>(signal);broken.remove("signal_value");
        assertFalse("an invalid signal never reaches the legacy writer",p.emitOwnGameplayEvent(broken));
        p.spooler.awaitSpoolIdleForTest();assertTrue(p.events.pendingEvents.isEmpty());
        gameplayOnlyGrant(false);
        p.events.emitEvent("own_signal",Map.of("signal_schema",1));p.spooler.awaitSpoolIdleForTest();
        assertTrue("a revoked grant stops the legacy signal path",p.events.pendingEvents.isEmpty());
    }
    @Test public void combatCannotUsePreviousAccountOrWorldProfileBeforeSessionTaskCatchesUp() throws Exception
    {
        gameplayOnlyGrant(true);net.runelite.api.Client client=mock(net.runelite.api.Client.class);
        set(p,"client",client);when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(net.runelite.api.WorldType.class));
        when(client.getAccountHash()).thenReturn(456L);
        assertFalse(p.emitOwnCombat(Map.of("type","combat_hit")));assertTrue(p.events.pendingEvents.isEmpty());
        when(client.getAccountHash()).thenReturn(123L);
        when(client.getWorldType()).thenReturn(EnumSet.of(net.runelite.api.WorldType.SEASONAL));
        assertFalse(p.emitOwnCombat(Map.of("type","combat_hit")));assertTrue(p.events.pendingEvents.isEmpty());
    }
    @Test public void ownVarbitContextSurvivesCrashAndCannotBeForgedByCallerFields() throws Exception
    {
        net.runelite.api.Client client=mock(net.runelite.api.Client.class);
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        when(client.getAccountHash()).thenReturn(123L);when(client.getTickCount()).thenReturn(50);
        set(p,"client",client);p.events.refreshEventObservationContext();
        // Missing definition/varps must not turn the mock/default0 into NORMAL.
        p.events.emitEvent("pickup",Map.of("item",2,"qty",1));
        assertNull(((Map<?,?>)p.events.pendingEvents.get(0).get("observation_context")).get("account_mode"));
        p.events.pendingEvents.clear();
        net.runelite.api.VarbitComposition def=mock(net.runelite.api.VarbitComposition.class);
        when(def.getIndex()).thenReturn(1);when(def.getLeastSignificantBit()).thenReturn(0);
        when(def.getMostSignificantBit()).thenReturn(2);when(client.getVarps()).thenReturn(new int[]{0,1});
        when(client.getVarbit(SnapshotBuilder.ACCOUNT_TYPE_VARBIT)).thenReturn(def);
        p.events.refreshEventObservationContext();grant();
        p.events.emitEvent("pickup",Map.of("item",2,"qty",1,"observation_context",Map.of("other_player","must-not-send")));
        p.spooler.awaitSpoolIdleForTest();Map<?,?> before=(Map<?,?>)p.events.pendingEvents.get(0).get("observation_context");
        assertEquals("IRONMAN",before.get("account_mode"));assertEquals("varbit_1777",before.get("account_mode_source"));
        Field version = AccountConnectPlugin.class.getDeclaredField("PLUGIN_VERSION"); version.setAccessible(true);
        assertEquals(version.get(null), before.get("plugin_version")); // artifact equality is covered by VersionDriftTest
        assertEquals("STANDARD",p.events.pendingEvents.get(0).get("profile_type"));
        assertFalse(before.toString().contains("must-not-send"));
        String session=(String)before.get("collector_session");Object sequence=before.get("sequence");
        p.spooler.crashSpoolForTest();p=fresh("STANDARD",7);grant();
        Map<?,?> replay=(Map<?,?>)p.events.pendingEvents.get(0).get("observation_context");
        assertEquals(session,replay.get("collector_session"));
        assertEquals(((Number)sequence).longValue(),((Number)replay.get("sequence")).longValue());
        assertEquals("IRONMAN",replay.get("account_mode"));
        p.events.emitEvent("pickup",Map.of("item",3,"qty",1));p.spooler.awaitSpoolIdleForTest();
        Map<?,?> newProcess=(Map<?,?>)p.events.pendingEvents.get(1).get("observation_context");
        assertNotEquals(session,newProcess.get("collector_session"));assertNull(newProcess.get("account_mode"));
        // Early callbacks can already identify a new account/profile before the slower
        // session tracker. Never attach the previous account's context to those rows.
        p.events.emitEvent("pickup",Map.of("item",3,"qty",1,"account_hash","456"));p.spooler.awaitSpoolIdleForTest();
        assertFalse(p.events.pendingEvents.get(2).containsKey("observation_context"));
        p.events.emitEvent("pickup",Map.of("item",3,"qty",1,"profile_type","LEAGUE"));p.spooler.awaitSpoolIdleForTest();
        assertFalse(p.events.pendingEvents.get(3).containsKey("observation_context"));
    }
    @Test public void emittedEventSurvivesProcessDeathAndGeneric200CannotRemoveIt() throws Exception
    {
        grant(); emit(); String id = (String)p.events.pendingEvents.get(0).get("event_id");
        assertEquals(1, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.spooler.crashSpoolForTest(); p = fresh("STANDARD", 7);
        p.outbox.pumpPublicOutbox(); p.spooler.awaitSpoolIdleForTest(); assertTrue(p.events.pendingEvents.isEmpty()); // no fresh grant
        grant(); assertEquals(id, p.events.pendingEvents.get(0).get("event_id"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"stored\":1}")); p.events.flushEvents();
        assertEquals("queue-v1", request().get("ack_protocol").getAsString()); done();
        assertEquals(1, p.events.pendingEvents.size()); assertEquals(1, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.events.queueRetryBackoffUntilMs = 0; server.enqueue(new MockResponse().setBody(receipt(id))); p.events.flushEvents(); request(); done();
        assertEquals(0, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.spooler.crashSpoolForTest(); p = fresh("STANDARD", 7); grant(); assertTrue(p.events.pendingEvents.isEmpty());
    }
    @Test public void logoutPausesAndModeOrRuneLiteProfileCannotReplayPriorRows() throws Exception
    {
        grant(); emit(); String standardId = (String)p.events.pendingEvents.get(0).get("event_id");
        p.session.sessionActive = false; p.events.flushEvents(); assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        p.spooler.crashSpoolForTest(); p = fresh("LEAGUE", 7); grant(); assertTrue(p.events.pendingEvents.isEmpty());
        emit(); String leagueId = (String)p.events.pendingEvents.get(0).get("event_id");
        p.spooler.crashSpoolForTest(); p = fresh("LEAGUE", 8); grant(); assertTrue(p.events.pendingEvents.isEmpty());
        p.spooler.crashSpoolForTest(); p = fresh("STANDARD", 7); grant();
        assertEquals(1, p.events.pendingEvents.size()); assertEquals(standardId, p.events.pendingEvents.get(0).get("event_id"));
        server.enqueue(new MockResponse().setBody(receipt(standardId))); p.events.flushEvents();
        assertEquals(standardId, request().getAsJsonArray("events").get(0).getAsJsonObject().get("event_id").getAsString()); done();
        p.spooler.crashSpoolForTest(); p = fresh("LEAGUE", 7); grant();
        assertEquals(1, p.events.pendingEvents.size()); assertEquals(leagueId, p.events.pendingEvents.get(0).get("event_id"));
    }
    @Test public void revokedTokenPurgesBeforeAnyRestartReplay() throws Exception
    {
        grant(); emit(); server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":\"revoked\"}"));
        p.events.flushEvents(); request(); done(); assertEquals(0, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.spooler.crashSpoolForTest(); p = fresh("STANDARD", 7); grant(); assertTrue(p.events.pendingEvents.isEmpty());
    }
    @Test public void tokenConfigWithdrawalCannotSendOldRowsUnderNewToken() throws Exception
    {
        grant(); emit(); String replacement = "ffffffffffffffffffffffffffffffff"; token.set(replacement);
        ConfigChanged e = mock(ConfigChanged.class); when(e.getGroup()).thenReturn("accountconnect");
        // Actual constant is used by the production callback; do not silently test a different key.
        Field group = AccountConnectPlugin.class.getDeclaredField("CONFIG_GROUP"); group.setAccessible(true);
        when(e.getGroup()).thenReturn((String)group.get(null)); when(e.getKey()).thenReturn("linkToken");
        when(e.getOldValue()).thenReturn(TOKEN); when(e.getNewValue()).thenReturn(replacement);
        p.onConfigChanged(e); p.spooler.awaitSpoolIdleForTest(); grant(); p.events.flushEvents();
        assertTrue(p.events.pendingEvents.isEmpty()); assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }
    @Test public void publicDurableRowsAreNotMixedWithUnjournaledQueueRows() throws Exception
    {
        // A pre-grant event retains the existing memory path; its receipt must not strand later disk rows.
        emit(); String first = (String)p.events.pendingEvents.get(0).get("event_id"); grant(); emit();
        String second = (String)p.events.pendingEvents.get(1).get("event_id");
        server.enqueue(new MockResponse().setBody(receipt(first))); p.events.flushEvents();
        assertEquals(1, request().getAsJsonArray("events").size()); done();
        server.enqueue(new MockResponse().setBody(receipt(second))); p.events.flushEvents();
        assertEquals(1, request().getAsJsonArray("events").size()); done();
        assertEquals(0, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void coldRevocationPurgesDiskBeforeAnyFreshGrantOrAccount() throws Exception
    {
        grant(); emit(); p.spooler.crashSpoolForTest(); p = fresh("STANDARD", 7);
        p.session.sessionActive = false; set(p, "activeHash", null);
        p.spooler.spoolOnRevoked(TOKEN); p.spooler.awaitSpoolIdleForTest();
        p.spooler.crashSpoolForTest(); p = fresh("STANDARD", 7); grant();
        assertTrue(p.events.pendingEvents.isEmpty());
        try (java.util.stream.Stream<Path> paths = Files.walk(data))
        { assertFalse(paths.anyMatch(path -> path.toString().endsWith(".spl"))); }
    }
    @Test public void agedMemoryCopyCannotBypassTheDurableReplayBound() throws Exception
    {
        grant(); emit();
        long future = System.currentTimeMillis() + EventSpool.MAX_AGE_MS + 10000;
        p.spooler.wallClock = () -> future;
        p.events.flushEvents(); done();
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        assertEquals(1, ((Number)p.events.captureHealthSnapshot().get("public_outbox_dropped_total")).intValue());
    }
    @Test public void anotherProcessErasureMarkerStopsAlreadyStagedMemoryBeforeSend() throws Exception
    {
        grant(); emit();
        PublicEventOutbox.Context owner = new PublicEventOutbox.Context(TOKEN, "123", "STANDARD", "7");
        Path identity = data.resolve("osrsbis/public-events-v1").resolve(owner.identity);
        EventSpool.purgeIdentityDir(identity, System::currentTimeMillis);
        p.events.flushEvents(); done();
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
    }
    @Test public void lateErasureReceiptDoesNotPurgeTheNewAccountOnTheSameToken() throws Exception
    {
        grant(); emit(); String erasedId = (String)p.events.pendingEvents.get(0).get("event_id");
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
            p.events.flushEvents(); request(); set(p, "activeHash", "456"); grant(); emit();
            String keptId = (String)p.events.pendingEvents.get(0).get("event_id");
            release.countDown(); done();
            assertEquals(1, p.events.pendingEvents.size()); assertEquals(keptId, p.events.pendingEvents.get(0).get("event_id"));
            assertEquals(1, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        }
        finally { release.countDown(); }
    }
    @Test public void diskFailureKeepsBoundedQueueRetryAndRecoversWhenStorageReturns() throws Exception
    {
        grant();
        Path blockedRoot = data.resolve("osrsbis/public-events-v1");
        Files.createDirectories(blockedRoot.getParent()); Files.write(blockedRoot, new byte[]{1});
        emit(); String id = (String)p.events.pendingEvents.get(0).get("event_id");
        assertEquals(1, ((Number)p.events.captureHealthSnapshot().get("public_outbox_not_durable_total")).intValue());
        assertEquals(0, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        server.enqueue(new MockResponse().setResponseCode(500)); p.events.flushEvents(); request(); done();
        assertEquals(1, p.events.pendingEvents.size()); assertTrue(p.events.queueRetryBackoffUntilMs > 0);
        p.events.queueRetryBackoffUntilMs = 0;
        server.enqueue(new MockResponse().setBody(receipt(id))); p.events.flushEvents(); request(); done();
        assertTrue(p.events.pendingEvents.isEmpty());
        Files.delete(blockedRoot); emit();
        assertEquals(1, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    private void bankGrant(boolean enabled, boolean queue) throws Exception
    {
        Response.Builder r = new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Spool", "off")
            .header("X-Bank-Evidence", enabled ? "on" : "off");
        if (enabled) r.header("X-Bank-Receipt", "event-id-v1");
        if (queue) r.header("X-Event-Queue", "own-history-v1");
        p.grants.applyServerPolicy(r.build(), token.get()); p.spooler.awaitSpoolIdleForTest();
    }
    private String emitBank() throws Exception
    {
        p.events.emitEvent("bank_visit_close", Map.of("visit_id", "a".repeat(32), "closed_at_ms", System.currentTimeMillis(),
            "close_reason", "logout", "manifest_complete", true, "moves_complete", false));
        p.spooler.awaitSpoolIdleForTest(); return (String)p.events.pendingEvents.get(p.events.pendingEvents.size()-1).get("event_id");
    }
    @Test public void publicBankSurvivesCrashAndOnlyItsOwnLegacyReceiptSettlesIt() throws Exception
    {
        bankGrant(true, false); String id=emitBank();
        assertEquals(1, ((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.spooler.crashSpoolForTest(); p=fresh("STANDARD", 7);
        p.outbox.pumpPublicOutbox(); p.spooler.awaitSpoolIdleForTest(); assertTrue(p.events.pendingEvents.isEmpty());
        bankGrant(true, false); assertEquals(id,p.events.pendingEvents.get(0).get("event_id"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"stored\":1}")); p.events.flushEvents();
        assertFalse("bank replay uses the existing legacy per-ID endpoint", request().has("ack_protocol")); done();
        assertEquals(1,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.events.eventRetryBackoffUntilMs=0;
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"results\":[{\"event_id\":\""+id+"\",\"status\":\"duplicate\"}]}"));
        p.events.flushEvents(); request(); done();
        assertEquals(0,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.spooler.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);assertTrue(p.events.pendingEvents.isEmpty());
    }
    @Test public void publicBankGrantDoesNotJournalUnrelatedTrafficOrWidenQueue()
        throws Exception
    {
        emitBank(); assertEquals(0,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.spooler.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);
        emit();String bankId=emitBank();
        assertEquals(1,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.spooler.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);
        assertEquals(1,p.events.pendingEvents.size());assertEquals(bankId,p.events.pendingEvents.get(0).get("event_id"));
        bankGrant(false,true);p.events.flushEvents();done();assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
        bankGrant(true,false);server.enqueue(new MockResponse().setBody("{\"ok\":true}"));p.events.flushEvents();
        assertFalse(request().has("ack_protocol"));done();
    }
    @Test public void publicBankAndQueueUseSeparateBatchesAndReceiptFormats() throws Exception
    {
        bankGrant(true,true);emit();String geId=(String)p.events.pendingEvents.get(0).get("event_id");String bankId=emitBank();
        server.enqueue(new MockResponse().setBody(receipt(geId)));p.events.flushEvents();
        JsonObject ge=request();assertEquals("queue-v1",ge.get("ack_protocol").getAsString());
        assertEquals(1,ge.getAsJsonArray("events").size());done();
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"results\":[{\"event_id\":\""+bankId+"\",\"status\":\"stored\"}]}"));
        p.events.flushEvents();JsonObject bank=request();assertFalse(bank.has("ack_protocol"));assertEquals(1,bank.getAsJsonArray("events").size());done();
        assertEquals(0,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void capturedBankCloseFinishesDiskWriteAfterLogoutButCannotReplayInAnotherMode() throws Exception
    {
        bankGrant(true,false);
        Field f=SpoolDriver.class.getDeclaredField("spoolExec");f.setAccessible(true);
        java.util.concurrent.ExecutorService disk=(java.util.concurrent.ExecutorService)f.get(p.spooler);
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        disk.execute(()->{started.countDown();try {release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(started.await(1,TimeUnit.SECONDS));
        try {
            p.events.emitEvent("bank_visit_close",Map.of("visit_id","a".repeat(32),"closed_at_ms",System.currentTimeMillis(),
                "close_reason","logout","manifest_complete",true,"moves_complete",false));
            p.session.sessionActive = false;set(p,"activeProfileType",null);
        } finally {release.countDown();}
        p.spooler.awaitSpoolIdleForTest();assertEquals(1,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        assertTrue(p.events.pendingEvents.isEmpty());p.spooler.crashSpoolForTest();p=fresh("LEAGUE",7);bankGrant(true,false);
        assertTrue(p.events.pendingEvents.isEmpty());p.spooler.crashSpoolForTest();p=fresh("STANDARD",8);bankGrant(true,false);
        assertTrue(p.events.pendingEvents.isEmpty());p.spooler.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);
        assertEquals(1,p.events.pendingEvents.size());
    }
    @Test public void aBankCloseCapturedAtLogoutIsSentBeforeTheNextLogin() throws Exception
    {
        // Live 0.7.62: a close journaled at logout waited for the same account's next login, often days.
        bankGrant(true,false);String id=emitBank();p.session.trackLogout("manual");p.spooler.awaitSpoolIdleForTest();
        p.outbox.pumpPublicOutbox();p.spooler.awaitSpoolIdleForTest();
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"results\":[{\"event_id\":\""+id+"\",\"status\":\"stored\"}]}"));
        p.events.flushEvents();JsonObject sent=request();
        assertEquals(id,sent.getAsJsonArray("events").get(0).getAsJsonObject().get("event_id").getAsString());done();
        assertEquals(0,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void aClientCloseSendsTheBankCloseAfterTheOtherRows() throws Exception
    {
        // One request carries one kind of row: the bank close goes in a later batch of the same close.
        bankGrant(true,false);emit();String bankId=emitBank();
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"stored\":1}"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"results\":[{\"event_id\":\""+bankId+"\",\"status\":\"stored\"}]}"));
        net.runelite.client.events.ClientShutdown close=new net.runelite.client.events.ClientShutdown();
        p.onClientShutdown(close);
        JsonObject first=request();JsonObject second=request();
        assertEquals("ge_progress",first.getAsJsonArray("events").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(bankId,second.getAsJsonArray("events").get(0).getAsJsonObject().get("event_id").getAsString());
        done();assertEquals(0,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void anEndedSessionsRowsWaitForTheirOwnProfile() throws Exception
    {
        bankGrant(true,false);emitBank();p.session.trackLogout("manual");p.spooler.awaitSpoolIdleForTest();
        ConfigManager other=mock(ConfigManager.class);ConfigProfile profile=mock(ConfigProfile.class);
        when(profile.getId()).thenReturn(8L);when(other.getProfile()).thenReturn(profile);set(p,"configManager",other);
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"stored\":1}"));
        p.outbox.pumpPublicOutbox();p.spooler.awaitSpoolIdleForTest();p.events.flushEvents();
        RecordedRequest r=server.takeRequest(500,TimeUnit.MILLISECONDS);
        assertTrue("only the logout row may go",r==null||!r.getBody().readUtf8().contains("bank_visit_close"));done();
        p.events.flushEvents();assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
    }
    @Test public void anEndedSessionsRowsWaitWhenTheTokenChanges() throws Exception
    {
        bankGrant(true,false);emitBank();p.session.trackLogout("manual");p.spooler.awaitSpoolIdleForTest();
        token.set("ffffffffffffffffffffffffffffffff");p.outbox.pumpPublicOutbox();p.spooler.awaitSpoolIdleForTest();p.events.flushEvents();
        assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
        assertEquals(1,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void publicBankRevocationPurgesBeforeColdRecovery() throws Exception
    {
        bankGrant(true,false);emitBank();server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":\"revoked\"}"));
        p.events.flushEvents();request();done();p.spooler.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);
        assertTrue(p.events.pendingEvents.isEmpty());assertEquals(0,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void publicBankDiskFailureKeepsBoundedLegacyRetryWithoutClaimingDurability() throws Exception
    {
        bankGrant(true,false);Path blocked=data.resolve("osrsbis/public-bank-events-v1");
        Files.createDirectories(blocked.getParent());Files.write(blocked,new byte[]{1});String id=emitBank();
        assertEquals(1,((Number)p.events.captureHealthSnapshot().get("public_outbox_not_durable_total")).intValue());
        assertEquals(0,((Number)p.events.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","5"));p.events.flushEvents();
        assertFalse(request().has("ack_protocol"));done();assertEquals(id,p.events.pendingEvents.get(0).get("event_id"));
        assertTrue(p.events.eventRetryBackoffUntilMs>0);assertEquals(0,p.events.queueRetryBackoffUntilMs);
    }
    @Test public void publicBankJournalIsSeparateFromAnOlderQueueClientRoot() throws Exception
    {
        bankGrant(true,false);String id=emitBank();p.spooler.crashSpoolForTest();
        assertTrue(Files.isDirectory(data.resolve("osrsbis/public-bank-events-v1")));
        PublicEventOutbox.Context owner=new PublicEventOutbox.Context(TOKEN,"123","STANDARD","7");
        PublicEventOutbox queueOnly=new PublicEventOutbox(data,new Gson(),System::currentTimeMillis);
        try { assertTrue(queueOnly.pending(owner,"own-history-v1",100).isEmpty()); }
        finally { queueOnly.close(); }
        p=fresh("STANDARD",7);bankGrant(true,false);assertEquals(id,p.events.pendingEvents.get(0).get("event_id"));
    }
    @Test public void diskFailureFallbackCannotCrossTheAccountBoundary() throws Exception
    {
        grant(); Path blockedRoot = data.resolve("osrsbis/public-events-v1");
        Files.createDirectories(blockedRoot.getParent()); Files.write(blockedRoot, new byte[]{1});
        emit(); set(p, "activeHash", "456"); p.events.flushEvents(); done();
        assertTrue(p.events.pendingEvents.isEmpty()); assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        assertEquals(1, ((Number)p.events.captureHealthSnapshot().get("events_lost_total")).intValue());
    }
}
