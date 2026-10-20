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

    private void combatGrant(boolean gameplay) throws Exception
    {
        Response r=new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Queue","own-combat-v1")
            .header("X-Own-Gameplay",gameplay?"on":"off").build();
        p.applyServerPolicy(r,token.get());p.awaitSpoolIdleForTest();
    }
    private void stateGrant(boolean gameplay, String responseToken) throws Exception
    {
        Response r=new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Queue","own-state-v1")
            .header("X-Own-Gameplay",gameplay?"on":"off").build();
        p.applyServerPolicy(r,responseToken);p.awaitSpoolIdleForTest();
    }
    private void varsGrant(boolean gameplay,String responseToken) throws Exception
    {
        Response r=new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Queue","own-vars-v1")
            .header("X-Own-Gameplay",gameplay?"on":"off").build();
        p.applyServerPolicy(r,responseToken);p.awaitSpoolIdleForTest();
    }
    @Test public void lateJournalIdentitySettlementReobservesCurrentStateWithoutImportingEarlierValues() throws Exception
    {
        net.runelite.api.Client client=mock(net.runelite.api.Client.class);int[] tick={100};
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        when(client.getAccountHash()).thenReturn(123L);when(client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS)).thenReturn(10);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(net.runelite.api.WorldType.class));
        when(client.getTickCount()).thenAnswer(i->tick[0]);set(p,"client",client);set(p,"activeHash","999");
        Field field=AccountConnectPlugin.class.getDeclaredField("ownGameplay");field.setAccessible(true);
        OwnGameplayCapture capture=(OwnGameplayCapture)field.get(p);long at=System.currentTimeMillis();
        varsGrant(true,token.get());assertFalse(p.gameplayReady());tick[0]+=2;assertTrue(p.gameplayReady());
        capture.state("effects.surge","varbit:16270",Map.of("timer_raw",55),tick[0],at);p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());
        set(p,"activeHash","123");assertFalse(p.gameplayReady());tick[0]+=2;assertTrue(p.gameplayReady());
        capture.state("effects.surge","varbit:16270",Map.of("timer_raw",0),tick[0],at+1200);p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());Map<String,Object> event=p.pendingEvents.get(0);
        assertEquals("baseline",event.get("observation"));assertEquals(0L,((Map<?,?>)event.get("values")).get("timer_raw"));
        assertEquals("123",event.get("account_hash"));assertEquals(1L,capture.snapshot().get("vars_events_handed_off"));
        set(p,"sessionActive",false);assertFalse(p.gameplayReady());tick[0]+=2;assertTrue(p.gameplayReady());
        capture.state("effects.surge","varbit:16270",Map.of("timer_raw",99),tick[0],at+2400);p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());
        set(p,"sessionActive",true);assertFalse(p.gameplayReady());tick[0]+=2;assertTrue(p.gameplayReady());
        capture.state("effects.surge","varbit:16270",Map.of("timer_raw",0),tick[0],at+3600);p.awaitSpoolIdleForTest();
        assertEquals(2,p.pendingEvents.size());assertEquals("baseline",p.pendingEvents.get(1).get("observation"));
        assertEquals(0L,((Map<?,?>)p.pendingEvents.get(1).get("values")).get("timer_raw"));
    }

    @Test public void sameTokenScopePromotionStartsFreshJournalBaselineAndIdenticalPolicyDoesNot() throws Exception
    {
        net.runelite.api.Client client=mock(net.runelite.api.Client.class);int[] tick={100};
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        when(client.getAccountHash()).thenReturn(123L);when(client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS)).thenReturn(10);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(net.runelite.api.WorldType.class));
        when(client.getTickCount()).thenAnswer(i->tick[0]);set(p,"client",client);
        Field field=AccountConnectPlugin.class.getDeclaredField("ownGameplay");field.setAccessible(true);
        OwnGameplayCapture capture=(OwnGameplayCapture)field.get(p);long at=System.currentTimeMillis();
        stateGrant(true,token.get());assertFalse(p.gameplayReady());tick[0]+=2;assertTrue(p.gameplayReady());
        capture.state("effects.surge","varbit:16270",Map.of("timer_raw",0),tick[0],at);p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());assertEquals(1L,capture.snapshot().get("vars_events_not_handed_off"));
        varsGrant(true,token.get());assertFalse("broader scope needs a new source baseline",p.gameplayReady());
        tick[0]+=2;assertTrue(p.gameplayReady());
        capture.state("effects.surge","varbit:16270",Map.of("timer_raw",0),tick[0],at+1200);p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());assertEquals("baseline",p.pendingEvents.get(0).get("observation"));
        String session=(String)p.pendingEvents.get(0).get("gameplay_session");
        varsGrant(true,token.get());assertTrue(p.gameplayReady());
        stateGrant(true,"f".repeat(32));assertTrue(p.gameplayReady());
        tick[0]++;capture.state("effects.surge","varbit:16270",Map.of("timer_raw",0),tick[0],at+1800);p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());assertEquals(session,capture.snapshot().get("collector_session").toString().replace("-",""));
        stateGrant(true,token.get());assertFalse(p.gameplayReady());tick[0]+=2;assertTrue(p.gameplayReady());
        varsGrant(true,token.get());assertFalse(p.gameplayReady());tick[0]+=2;assertTrue(p.gameplayReady());
        capture.state("effects.surge","varbit:16270",Map.of("timer_raw",0),tick[0],at+4200);p.awaitSpoolIdleForTest();
        assertEquals(2,p.pendingEvents.size());assertEquals("baseline",p.pendingEvents.get(1).get("observation"));
        assertNotEquals(session,p.pendingEvents.get(1).get("gameplay_session"));
        assertNotEquals(p.pendingEvents.get(0).get("event_id"),p.pendingEvents.get(1).get("event_id"));
    }

    @Test public void rawVarsRequireCurrentScopeAndSurviveRollbackRestartWithUnknownFields() throws Exception
    {
        Map<String,Object> event=OwnVarsEventTest.event();
        stateGrant(true,token.get());assertFalse(p.emitOwnGameplayEvent(event));
        varsGrant(false,token.get());assertFalse(p.emitOwnGameplayEvent(event));
        varsGrant(true,"f".repeat(32));assertFalse(p.emitOwnGameplayEvent(event));
        varsGrant(true,token.get());assertTrue(p.emitOwnGameplayEvent(event));p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());String id=(String)p.pendingEvents.get(0).get("event_id");
        p.crashSpoolForTest();p=fresh("STANDARD",7);stateGrant(true,token.get());p.pumpPublicOutbox();p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());
        varsGrant(true,token.get());p.pumpPublicOutbox();p.awaitSpoolIdleForTest();assertEquals(1,p.pendingEvents.size());
        assertEquals(id,p.pendingEvents.get(0).get("event_id"));
        assertNull(((Map<?,?>)p.pendingEvents.get(0).get("values")).get("timer_raw"));
        server.enqueue(new MockResponse().setBody(receipt(id)));p.flushEvents();request();done();
        assertTrue(p.pendingEvents.isEmpty());
        Map<?,?> health=(Map<?,?>)p.captureHealthSnapshot().get("queue_delivery");
        assertEquals(1L,((Map<?,?>)((Map<?,?>)health.get("by_family")).get("own_vars_effects.surge")).get("broker_receipts"));
    }
    private void signalGrant(String responseToken) throws Exception
    {
        Response response=new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Queue","own-signals-v1").header("X-Own-Gameplay","on").build();
        p.applyServerPolicy(response,responseToken);p.awaitSpoolIdleForTest();
    }
    @Test public void signalsRequireNewScopeAndPreserveExactIdsThroughOldScopeRestartAndAck() throws Exception
    {
        Map<String,Object> event=OwnSignalEventTest.event("combat.chompy_kc");
        varsGrant(true,token.get());assertFalse(p.emitOwnGameplayEvent(event));signalGrant("f".repeat(32));assertFalse(p.emitOwnGameplayEvent(event));
        signalGrant(token.get());assertTrue(p.emitOwnGameplayEvent(event));p.awaitSpoolIdleForTest();assertEquals(1,p.pendingEvents.size());
        String id=(String)p.pendingEvents.get(0).get("event_id");p.crashSpoolForTest();p=fresh("STANDARD",7);
        varsGrant(true,token.get());p.pumpPublicOutbox();p.awaitSpoolIdleForTest();assertTrue(p.pendingEvents.isEmpty());
        signalGrant(token.get());p.pumpPublicOutbox();p.awaitSpoolIdleForTest();assertEquals(1,p.pendingEvents.size());
        assertEquals(id,p.pendingEvents.get(0).get("event_id"));assertNull(((Map<?,?>)p.pendingEvents.get(0).get("signal_value")).get("total_kills"));
        server.enqueue(new MockResponse().setBody(receipt(id)));p.flushEvents();request();done();assertTrue(p.pendingEvents.isEmpty());
        Map<?,?> family=(Map<?,?>)((Map<?,?>)((Map<?,?>)p.captureHealthSnapshot().get("queue_delivery")).get("by_family")).get("own_signal_combat.chompy_kc");
        assertEquals(1L,family.get("broker_receipts"));
    }
    @Test public void signalScopeRaceCannotReachLegacyAndMalformedProjectionCannotThrow() throws Exception
    {
        signalGrant(token.get());Map<String,Object> event=OwnSignalEventTest.event("storage.coal_bag");event.put("signal_kind",null);
        assertFalse(p.emitOwnGameplayEvent(event));
        Map<String,Object> fields=OwnSignalEventTest.event("storage.coal_bag");fields.remove("type");varsGrant(true,token.get());
        p.emitEvent("own_signal",fields);p.awaitSpoolIdleForTest();assertTrue(p.pendingEvents.isEmpty());assertEquals(1L,p.captureHealthSnapshot().get("events_lost_total"));
    }

    @Test public void rawVarsScopeRaceCannotFallBackToLegacy() throws Exception
    {
        varsGrant(true,token.get());Map<String,Object> fields=OwnVarsEventTest.event();fields.remove("type");
        stateGrant(true,token.get());p.emitEvent("own_vars",fields);p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());assertEquals(1L,p.captureHealthSnapshot().get("events_lost_total"));
    }
    @Test public void stateRequiresCurrentExplicitGrantAndKeepsOldRowsAcrossScopeRollback() throws Exception
    {
        Map<String,Object> event=OwnStateEventTest.goalEvent();
        stateGrant(false,token.get());assertFalse(p.emitOwnGameplayEvent(event));
        combatGrant(true);assertFalse(p.emitOwnGameplayEvent(event));
        stateGrant(true,"f".repeat(32));assertFalse(p.emitOwnGameplayEvent(event));
        stateGrant(true,token.get());assertTrue(p.emitOwnGameplayEvent(event));p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());String id=(String)p.pendingEvents.get(0).get("event_id");
        p.crashSpoolForTest();p=fresh("STANDARD",7);combatGrant(true);p.pumpPublicOutbox();p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());
        stateGrant(true,token.get());p.pumpPublicOutbox();p.awaitSpoolIdleForTest();
        assertEquals(id,p.pendingEvents.get(0).get("event_id"));
        server.enqueue(new MockResponse().setBody(receipt(id)));p.flushEvents();
        JsonObject uploaded=request().getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals("skill_goals",uploaded.get("state_kind").getAsString());done();
        p.pumpPublicOutbox();p.awaitSpoolIdleForTest();assertTrue(p.pendingEvents.isEmpty());
        Map<?,?> health=(Map<?,?>)p.captureHealthSnapshot().get("queue_delivery");
        Map<?,?> counters=(Map<?,?>)((Map<?,?>)health.get("by_family")).get("own_state_skill_goals");
        assertEquals(1L,counters.get("broker_receipts"));assertEquals(0L,counters.get("archive_receipts"));
        assertEquals("restart can acknowledge prior-process rows",0L,counters.get("capture_handoffs"));
    }
    @Test public void stateLosingItsScopeBeforeFinalEmitCannotFallBackToLegacyOrThrow() throws Exception
    {
        stateGrant(true,token.get());Map<String,Object> fields=OwnStateEventTest.goalEvent();fields.remove("type");
        // Reproduce the final emitter boundary after the prior source-level check.
        grant();p.emitEvent("own_state",fields);p.awaitSpoolIdleForTest();
        assertTrue("a scope race must not queue state on the legacy writer",p.pendingEvents.isEmpty());
        assertEquals(1L,p.captureHealthSnapshot().get("events_lost_total"));
        stateGrant(true,token.get());p.emitEvent("own_state",Map.of("state_schema",1));p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());assertEquals(2L,p.captureHealthSnapshot().get("events_lost_total"));
    }
    @Test public void combatRequiresBothGrantsAndSurvivesRestartAndScopeRollbackUntilOwnAck() throws Exception
    {
        Map<String,Object> event=new LinkedHashMap<>(Map.of("type","combat_hit","combat_schema",1,
            "gameplay_session","e".repeat(32),"source_sequence",1,"source_tick",42,
            "observed_at_ms",System.currentTimeMillis(),"source","HitsplatApplied","direction","received","amount",0,"hitsplat_type",16));
        combatGrant(false);assertFalse(p.emitOwnCombat(event));assertTrue(p.pendingEvents.isEmpty());
        combatGrant(true);assertTrue(p.emitOwnCombat(event));p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());String id=(String)p.pendingEvents.get(0).get("event_id");
        p.crashSpoolForTest();p=fresh("STANDARD",7);grant();p.pumpPublicOutbox();p.awaitSpoolIdleForTest();
        assertTrue("narrow scope must hold rather than discard combat",p.pendingEvents.isEmpty());
        combatGrant(true);p.pumpPublicOutbox();p.awaitSpoolIdleForTest();assertEquals(1,p.pendingEvents.size());
        assertEquals(id,p.pendingEvents.get(0).get("event_id"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("fault"));p.flushEvents();
        assertEquals(id,request().getAsJsonArray("events").get(0).getAsJsonObject().get("event_id").getAsString());done();
        assertEquals(1,p.pendingEvents.size());
        assertTrue(p.queueRetryBackoffUntilMs>0);p.flushEvents();assertEquals(1,server.getRequestCount());
        p.queueRetryBackoffUntilMs=0;
        server.enqueue(new MockResponse().setBody(receipt(id)));p.flushEvents();request();done();
        p.pumpPublicOutbox();p.awaitSpoolIdleForTest();assertTrue(p.pendingEvents.isEmpty());
    }
    private void gameplayOnlyGrant(boolean gameplay) throws Exception
    {
        Response r=new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Own-Gameplay",gameplay?"on":"off").build();
        p.applyServerPolicy(r,token.get());p.awaitSpoolIdleForTest();
    }
    @Test public void grantedCombatWithoutQueueScopeUsesLegacyWriterAndStateNeverDoes() throws Exception
    {
        Map<String,Object> hit=new LinkedHashMap<>(Map.of("type","combat_hit","combat_schema",1,
            "gameplay_session","e".repeat(32),"source_sequence",1,"source_tick",42,
            "observed_at_ms",System.currentTimeMillis(),"source","HitsplatApplied","direction","dealt_to_npc","amount",3,"hitsplat_type",16));
        gameplayOnlyGrant(false);
        assertFalse("no grant, nothing on the legacy path",p.emitOwnCombat(hit));p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());
        gameplayOnlyGrant(true);
        assertTrue(p.emitOwnCombat(hit));p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());assertEquals("combat_hit",p.pendingEvents.get(0).get("type"));
        assertNotNull("legacy combat carries an event_id for server dedupe",p.pendingEvents.get(0).get("event_id"));
        p.pendingEvents.clear();
        Map<String,Object> state=OwnStateEventTest.goalEvent();
        assertFalse("own_state never uses the legacy writer",p.emitOwnGameplayEvent(state));
        p.emitEvent("own_state",Map.of("state_schema",1));p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());
        gameplayOnlyGrant(false);
        p.emitEvent("combat_hit",Map.of("combat_schema",1));p.awaitSpoolIdleForTest();
        assertTrue("a revoked grant stops the legacy combat path",p.pendingEvents.isEmpty());
    }
    @Test public void grantedOwnSignalWithoutQueueScopeUsesLegacyWriterOnlyWhenValid() throws Exception
    {
        Map<String,Object> signal=new LinkedHashMap<>(OwnSignalEventTest.event("farming.compost"));
        gameplayOnlyGrant(false);
        assertFalse("no grant, no legacy signal",p.emitOwnGameplayEvent(signal));p.awaitSpoolIdleForTest();
        assertTrue(p.pendingEvents.isEmpty());
        gameplayOnlyGrant(true);
        assertTrue(p.emitOwnGameplayEvent(signal));p.awaitSpoolIdleForTest();
        assertEquals(1,p.pendingEvents.size());assertEquals("own_signal",p.pendingEvents.get(0).get("type"));
        assertNotNull(p.pendingEvents.get(0).get("event_id"));
        p.pendingEvents.clear();
        Map<String,Object> broken=new LinkedHashMap<>(signal);broken.remove("signal_value");
        assertFalse("an invalid signal never reaches the legacy writer",p.emitOwnGameplayEvent(broken));
        p.awaitSpoolIdleForTest();assertTrue(p.pendingEvents.isEmpty());
        gameplayOnlyGrant(false);
        p.emitEvent("own_signal",Map.of("signal_schema",1));p.awaitSpoolIdleForTest();
        assertTrue("a revoked grant stops the legacy signal path",p.pendingEvents.isEmpty());
    }
    @Test public void combatCannotUsePreviousAccountOrWorldProfileBeforeSessionTaskCatchesUp() throws Exception
    {
        combatGrant(true);net.runelite.api.Client client=mock(net.runelite.api.Client.class);
        set(p,"client",client);when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(net.runelite.api.WorldType.class));
        when(client.getAccountHash()).thenReturn(456L);
        assertFalse(p.emitOwnCombat(Map.of("type","combat_hit")));assertTrue(p.pendingEvents.isEmpty());
        when(client.getAccountHash()).thenReturn(123L);
        when(client.getWorldType()).thenReturn(EnumSet.of(net.runelite.api.WorldType.SEASONAL));
        assertFalse(p.emitOwnCombat(Map.of("type","combat_hit")));assertTrue(p.pendingEvents.isEmpty());
    }
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
        Field version = AccountConnectPlugin.class.getDeclaredField("PLUGIN_VERSION"); version.setAccessible(true);
        assertEquals(version.get(null), before.get("plugin_version")); // artifact equality is covered by VersionDriftTest
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
        grant(); emit(); String standardId = (String)p.pendingEvents.get(0).get("event_id");
        set(p, "sessionActive", false); p.flushEvents(); assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        p.crashSpoolForTest(); p = fresh("LEAGUE", 7); grant(); assertTrue(p.pendingEvents.isEmpty());
        emit(); String leagueId = (String)p.pendingEvents.get(0).get("event_id");
        p.crashSpoolForTest(); p = fresh("LEAGUE", 8); grant(); assertTrue(p.pendingEvents.isEmpty());
        p.crashSpoolForTest(); p = fresh("STANDARD", 7); grant();
        assertEquals(1, p.pendingEvents.size()); assertEquals(standardId, p.pendingEvents.get(0).get("event_id"));
        server.enqueue(new MockResponse().setBody(receipt(standardId))); p.flushEvents();
        assertEquals(standardId, request().getAsJsonArray("events").get(0).getAsJsonObject().get("event_id").getAsString()); done();
        p.crashSpoolForTest(); p = fresh("LEAGUE", 7); grant();
        assertEquals(1, p.pendingEvents.size()); assertEquals(leagueId, p.pendingEvents.get(0).get("event_id"));
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
    private void bankGrant(boolean enabled, boolean queue) throws Exception
    {
        Response.Builder r = new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Spool", "off")
            .header("X-Bank-Evidence", enabled ? "on" : "off");
        if (enabled) r.header("X-Bank-Receipt", "event-id-v1");
        if (queue) r.header("X-Event-Queue", "own-history-v1");
        p.applyServerPolicy(r.build(), token.get()); p.awaitSpoolIdleForTest();
    }
    private String emitBank() throws Exception
    {
        p.emitEvent("bank_visit_close", Map.of("visit_id", "a".repeat(32), "closed_at_ms", System.currentTimeMillis(),
            "close_reason", "logout", "manifest_complete", true, "moves_complete", false));
        p.awaitSpoolIdleForTest(); return (String)p.pendingEvents.get(p.pendingEvents.size()-1).get("event_id");
    }
    @Test public void publicBankSurvivesCrashAndOnlyItsOwnLegacyReceiptSettlesIt() throws Exception
    {
        bankGrant(true, false); String id=emitBank();
        assertEquals(1, ((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.crashSpoolForTest(); p=fresh("STANDARD", 7);
        p.pumpPublicOutbox(); p.awaitSpoolIdleForTest(); assertTrue(p.pendingEvents.isEmpty());
        bankGrant(true, false); assertEquals(id,p.pendingEvents.get(0).get("event_id"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"stored\":1}")); p.flushEvents();
        assertFalse("bank replay uses the existing legacy per-ID endpoint", request().has("ack_protocol")); done();
        assertEquals(1,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.eventRetryBackoffUntilMs=0;
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"results\":[{\"event_id\":\""+id+"\",\"status\":\"duplicate\"}]}"));
        p.flushEvents(); request(); done();
        assertEquals(0,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);assertTrue(p.pendingEvents.isEmpty());
    }
    @Test public void publicBankGrantDoesNotJournalUnrelatedTrafficOrWidenQueue()
        throws Exception
    {
        emitBank(); assertEquals(0,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);
        emit();String bankId=emitBank();
        assertEquals(1,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        p.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);
        assertEquals(1,p.pendingEvents.size());assertEquals(bankId,p.pendingEvents.get(0).get("event_id"));
        bankGrant(false,true);p.flushEvents();done();assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
        bankGrant(true,false);server.enqueue(new MockResponse().setBody("{\"ok\":true}"));p.flushEvents();
        assertFalse(request().has("ack_protocol"));done();
    }
    @Test public void publicBankAndQueueUseSeparateBatchesAndReceiptFormats() throws Exception
    {
        bankGrant(true,true);emit();String geId=(String)p.pendingEvents.get(0).get("event_id");String bankId=emitBank();
        server.enqueue(new MockResponse().setBody(receipt(geId)));p.flushEvents();
        JsonObject ge=request();assertEquals("queue-v1",ge.get("ack_protocol").getAsString());
        assertEquals(1,ge.getAsJsonArray("events").size());done();
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"results\":[{\"event_id\":\""+bankId+"\",\"status\":\"stored\"}]}"));
        p.flushEvents();JsonObject bank=request();assertFalse(bank.has("ack_protocol"));assertEquals(1,bank.getAsJsonArray("events").size());done();
        assertEquals(0,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void capturedBankCloseFinishesDiskWriteAfterLogoutButCannotReplayInAnotherMode() throws Exception
    {
        bankGrant(true,false);
        Field f=AccountConnectPlugin.class.getDeclaredField("spoolExec");f.setAccessible(true);
        java.util.concurrent.ExecutorService disk=(java.util.concurrent.ExecutorService)f.get(p);
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        disk.execute(()->{started.countDown();try {release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(started.await(1,TimeUnit.SECONDS));
        try {
            p.emitEvent("bank_visit_close",Map.of("visit_id","a".repeat(32),"closed_at_ms",System.currentTimeMillis(),
                "close_reason","logout","manifest_complete",true,"moves_complete",false));
            set(p,"sessionActive",false);set(p,"activeProfileType",null);
        } finally {release.countDown();}
        p.awaitSpoolIdleForTest();assertEquals(1,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        assertTrue(p.pendingEvents.isEmpty());p.crashSpoolForTest();p=fresh("LEAGUE",7);bankGrant(true,false);
        assertTrue(p.pendingEvents.isEmpty());p.crashSpoolForTest();p=fresh("STANDARD",8);bankGrant(true,false);
        assertTrue(p.pendingEvents.isEmpty());p.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);
        assertEquals(1,p.pendingEvents.size());
    }
    @Test public void publicBankRevocationPurgesBeforeColdRecovery() throws Exception
    {
        bankGrant(true,false);emitBank();server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":\"revoked\"}"));
        p.flushEvents();request();done();p.crashSpoolForTest();p=fresh("STANDARD",7);bankGrant(true,false);
        assertTrue(p.pendingEvents.isEmpty());assertEquals(0,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
    }
    @Test public void publicBankDiskFailureKeepsBoundedLegacyRetryWithoutClaimingDurability() throws Exception
    {
        bankGrant(true,false);Path blocked=data.resolve("osrsbis/public-bank-events-v1");
        Files.createDirectories(blocked.getParent());Files.write(blocked,new byte[]{1});String id=emitBank();
        assertEquals(1,((Number)p.captureHealthSnapshot().get("public_outbox_not_durable_total")).intValue());
        assertEquals(0,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","5"));p.flushEvents();
        assertFalse(request().has("ack_protocol"));done();assertEquals(id,p.pendingEvents.get(0).get("event_id"));
        assertTrue(p.eventRetryBackoffUntilMs>0);assertEquals(0,p.queueRetryBackoffUntilMs);
    }
    @Test public void publicBankJournalIsSeparateFromAnOlderQueueClientRoot() throws Exception
    {
        bankGrant(true,false);String id=emitBank();p.crashSpoolForTest();
        assertTrue(Files.isDirectory(data.resolve("osrsbis/public-bank-events-v1")));
        PublicEventOutbox.Context owner=new PublicEventOutbox.Context(TOKEN,"123","STANDARD","7");
        PublicEventOutbox queueOnly=new PublicEventOutbox(data,new Gson(),System::currentTimeMillis);
        try { assertTrue(queueOnly.pending(owner,"own-history-v1",100).isEmpty()); }
        finally { queueOnly.close(); }
        p=fresh("STANDARD",7);bankGrant(true,false);assertEquals(id,p.pendingEvents.get(0).get("event_id"));
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
