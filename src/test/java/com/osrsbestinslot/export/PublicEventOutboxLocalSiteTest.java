package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;
import static org.mockito.Mockito.*;

/** Opt-in loopback integration only: real uploader/journal, synthetic identity, no game client. */
public class PublicEventOutboxLocalSiteTest
{
    private static final String TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final Gson gson = new Gson();
    private String base;
    private Path data;
    private OkHttpClient http;
    private final List<AccountConnectPlugin> instances = new ArrayList<>();
    private final boolean combat = "1".equals(System.getenv("OSRSBIS_CHECK_COMBAT"));
    private final boolean vars = "1".equals(System.getenv("OSRSBIS_CHECK_VARS"));
    private final boolean states = "1".equals(System.getenv("OSRSBIS_CHECK_STATE"));

    private static void set(AccountConnectPlugin p, String name, Object value) throws Exception
    { Field f = AccountConnectPlugin.class.getDeclaredField(name); f.setAccessible(true); f.set(p, value); }

    private AccountConnectPlugin fresh() throws Exception
    {
        AccountConnectPlugin p = new AccountConnectPlugin(); instances.add(p);
        AccountConnectConfig config = mock(AccountConnectConfig.class);
        when(config.linkToken()).thenReturn(TOKEN); when(config.apiBaseUrl()).thenReturn(base);
        ConfigManager manager = mock(ConfigManager.class); ConfigProfile profile = mock(ConfigProfile.class);
        when(manager.getProfile()).thenReturn(profile); when(profile.getId()).thenReturn(7L);
        set(p,"config",config); set(p,"configManager",manager); set(p,"gson",gson); set(p,"okHttpClient",http);
        set(p,"activeHash","own-a"); set(p,"activeProfileType","STANDARD"); set(p,"sessionActive",true);
        p.setSpoolDataRootForTest(data);
        // Synthetic policy receipt is scoped to this local test identity, never a live grant.
        Response policy = new Response.Builder().request(new Request.Builder().url(base).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Event-Queue",vars?"own-vars-v1":states?"own-state-v1":combat?"own-combat-v1":"own-history-v1")
            .header("X-Own-Gameplay",combat||states||vars?"on":"off").build();
        p.applyServerPolicy(policy,TOKEN); p.awaitSpoolIdleForTest(); return p;
    }

    private void command(String path) throws Exception
    {
        try (Response r = http.newCall(new Request.Builder().url(base+path)
            .post(RequestBody.create(MediaType.get("application/json"),"{}")).build()).execute())
        { assertEquals(r.body().string(),200,r.code()); }
    }

    private void done(AccountConnectPlugin p) throws Exception
    {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(p.eventPostInFlight && System.nanoTime()<end) Thread.sleep(10);
        assertFalse("uploader did not settle",p.eventPostInFlight); p.awaitSpoolIdleForTest();
    }

    @Test public void actualSiteReceiptSettlesOnlyItsRowsAfterLostResponseAndRestart() throws Exception
    {
        base=System.getenv("OSRSBIS_LOCAL_SITE_URL"); assumeNotNull(base);
        assertTrue("integration is loopback only",base.matches("http://127\\.0\\.0\\.1:[0-9]+"));
        data=Files.createTempDirectory("public-local-site-");
        http=new OkHttpClient.Builder().readTimeout(20,TimeUnit.SECONDS).build();
        try
        {
            AccountConnectPlugin p=fresh();
            if(vars)assertTrue(p.emitOwnGameplayEvent(OwnVarsEventTest.event()));
            else if(states)assertTrue(p.emitOwnGameplayEvent(OwnStateEventTest.goalEvent()));
            else if(combat)assertTrue(p.emitOwnCombat(new LinkedHashMap<>(Map.of("type","combat_hit","combat_schema",1,
                "gameplay_session","e".repeat(32),"source_sequence",1,"source_tick",42,
                "observed_at_ms",System.currentTimeMillis(),"source","HitsplatApplied","direction","received","amount",0,"hitsplat_type",16))));
            else p.emitEvent("ge_progress",new LinkedHashMap<>(Map.of("item",2,"qty",1,"state","BUYING")));
            p.emitEvent("ge_progress",new LinkedHashMap<>(Map.of("item",2,"qty",1,"state","INVALID")));
            List<Map<String,Object>> rows=new ArrayList<>();
            for(int i=0;i<90;i++) rows.add(Map.of("id",1000+i,"qty",2000000000));
            if(vars) {
                for(Map.Entry<String,List<String>> entry:OwnVarsEvent.FIELDS.entrySet()) {
                    if(entry.getKey().equals("effects.surge"))continue;
                    Map<String,Object> event=OwnVarsEventTest.event();event.put("var_family",entry.getKey());
                    Map<String,Object> values=new LinkedHashMap<>();for(String key:entry.getValue())values.put(key,null);
                    if(!entry.getKey().equals("combat.raid_points"))values.put(entry.getValue().get(0),0);
                    event.put("values",values);assertTrue(p.emitOwnGameplayEvent(event));
                }
            }
            else if(states) { assertTrue(p.emitOwnGameplayEvent(OwnStateEventTest.pouchEvent()));
                assertTrue(p.emitOwnGameplayEvent(OwnStateEventTest.bankEvent())); }
            else p.emitEvent("bank_session",new LinkedHashMap<>(Map.of("deposited",rows,"withdrawn",rows,"complete",true)));
            int valid=vars?12:states?3:2,total=valid+1;
            p.awaitSpoolIdleForTest(); assertEquals(total,p.pendingEvents.size());
            List<String> ids=new ArrayList<>();
            for(Map<String,Object> event:p.pendingEvents) ids.add((String)event.get("event_id"));
            command("/test/lose-next"); p.flushEvents(); done(p);
            assertEquals(total,p.pendingEvents.size());
            assertEquals(total,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
            command("/test/drain-two");
            p.crashSpoolForTest(); p=fresh(); assertEquals(total,p.pendingEvents.size());
            p.flushEvents(); done(p);
            assertEquals(0,p.pendingEvents.size());
            assertEquals(0,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
            assertEquals((long)valid,p.captureHealthSnapshot().get("events_sent_total"));
            assertEquals(1L,p.captureHealthSnapshot().get("events_lost_total"));
            if(states)
            {
                Map<?,?> health=(Map<?,?>)p.captureHealthSnapshot().get("queue_delivery");
                for(String kind:Arrays.asList("skill_goals","rune_pouch","bank_tabs"))
                {
                    Map<?,?> counts=(Map<?,?>)((Map<?,?>)health.get("by_family")).get("own_state_"+kind);
                    assertEquals(1L,counts.get("archive_receipts"));assertEquals(0L,counts.get("broker_receipts"));
                    assertEquals(0L,counts.get("capture_handoffs"));
                }
            }
            if(vars) {
                Map<?,?> families=(Map<?,?>)((Map<?,?>)p.captureHealthSnapshot().get("queue_delivery")).get("by_family");
                for(String family:OwnVarsEvent.FIELDS.keySet()) {
                    Map<?,?> counts=(Map<?,?>)families.get("own_vars_"+family);
                    assertEquals(1L,counts.get("archive_receipts"));assertEquals(0L,counts.get("capture_handoffs"));
                }
            }
            command("/test/drain-two");
            command("/test/erase");
            if(vars)assertTrue(p.emitOwnGameplayEvent(OwnVarsEventTest.event()));
            else p.emitEvent("pickup",new LinkedHashMap<>(Map.of("item",2,"qty",1)));
            p.awaitSpoolIdleForTest(); p.flushEvents(); done(p);
            assertEquals(0,((Number)p.captureHealthSnapshot().get("public_outbox_pending")).intValue());
            assertEquals((long)valid,p.captureHealthSnapshot().get("events_sent_total"));
            assertEquals(2L,p.captureHealthSnapshot().get("events_lost_total"));
            Path receipt=Path.of(Objects.requireNonNull(System.getenv("OSRSBIS_LOCAL_SITE_RECEIPT")));
            Files.writeString(receipt,gson.toJson(Map.of("locale","LOCAL_JAVA_WORKERD","event_ids",ids,
                "durable",valid,"rejected",1,"late_erased",1,"actual_gameplay_qa",false)));
        }
        finally
        {
            for(AccountConnectPlugin p:instances) p.crashSpoolForTest();
            http.dispatcher().executorService().shutdownNow(); http.connectionPool().evictAll();
            try(java.util.stream.Stream<Path> paths=Files.walk(data))
            { for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
        }
    }
}
