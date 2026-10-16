package com.osrsbestinslot.export;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class EventQueueDeliveryTest
{
    private static final String TOKEN="0123456789abcdef0123456789abcdef";
    private static final String A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private AccountConnectPlugin plugin;
    private MockWebServer server;
    private OkHttpClient http;
    @Before public void setup() throws Exception
    {
        server=new MockWebServer();server.start();plugin=new AccountConnectPlugin();
        http=new OkHttpClient.Builder().readTimeout(2,TimeUnit.SECONDS).build();
        set("gson",new Gson());set("okHttpClient",http);
        AccountConnectConfig config=mock(AccountConnectConfig.class);
        when(config.linkToken()).thenReturn(TOKEN);when(config.apiBaseUrl()).thenReturn(server.url("/api").toString());set("config",config);
    }
    @After public void close() throws Exception { server.shutdown();http.dispatcher().executorService().shutdownNow();http.connectionPool().evictAll(); }
    private void set(String name,Object value) throws Exception { Field f=AccountConnectPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(plugin,value); }
    private void policy(String scope,String token)
    {
        Response.Builder b=new Response.Builder().request(new Request.Builder().url(server.url("/ingest")).build()).protocol(Protocol.HTTP_1_1).code(200).message("OK");
        if(scope!=null)b.header("X-Event-Queue",scope);
        plugin.applyServerPolicy(b.build(),token);
    }
    private Map<String,Object> event(String id,String type)
    {
        Map<String,Object> e=new LinkedHashMap<>();e.put("event_id",id);e.put("account_hash","synthetic");e.put("ts",1700000000000L);e.put("type",type);e.put("item",2);e.put("qty",1);return e;
    }
    private JsonObject sent() throws Exception { RecordedRequest r=server.takeRequest(3,TimeUnit.SECONDS);assertNotNull(r);return new JsonParser().parse(r.getBody().readUtf8()).getAsJsonObject(); }
    private void done() throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(plugin.eventPostInFlight&&System.nanoTime()<end)Thread.sleep(5);assertFalse(plugin.eventPostInFlight); }
    private String row(int index,String id,String status,String extra) {return "{\"index\":"+index+",\"event_id\":\""+id+"\",\"status\":\""+status+"\""+extra+"}";}
    private String receipt(String...rows){return "{\"receipt_version\":1,\"results\":["+String.join(",",rows)+"]}";}
    @Test public void partial503RetainsOnlyUnacknowledgedIdsAndHonorsBackoff() throws Exception
    {
        policy("ge-v1",TOKEN);plugin.pendingEvents.add(event(A,"ge_progress"));plugin.pendingEvents.add(event(B,"ge_progress"));
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After","20").setBody(receipt(row(0,A,"queued",",\"broker_ack\":true"),row(1,B,"retry",""))));
        plugin.flushEvents();JsonObject body=sent();assertEquals("queue-v1",body.get("ack_protocol").getAsString());assertFalse(body.toString().contains("__delivery"));done();
        assertEquals(1,plugin.pendingEvents.size());assertEquals(B,plugin.pendingEvents.get(0).get("event_id"));assertEquals(20000,plugin.eventRetryBackoffMs);
        assertEquals(1L,plugin.captureHealthSnapshot().get("events_sent_total"));
        policy(null,TOKEN);plugin.eventRetryBackoffUntilMs=0;
        // Matched site rollback refuses queue-pinned intake; it never runs the legacy writer.
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"error\":\"queue_paused\"}"));
        plugin.flushEvents();assertEquals("queue-v1",sent().get("ack_protocol").getAsString());done();
        assertEquals(1,plugin.pendingEvents.size());assertEquals(B,plugin.pendingEvents.get(0).get("event_id"));
        assertEquals(1L,plugin.captureHealthSnapshot().get("events_sent_total"));
        plugin.eventRetryBackoffUntilMs=0;
        server.enqueue(new MockResponse().setBody(receipt(row(0,B,"stored",",\"archive_receipt\":true,\"d1_receipt\":true"))));
        plugin.flushEvents();assertEquals("queue-v1",sent().get("ack_protocol").getAsString());done();assertTrue(plugin.pendingEvents.isEmpty());
    }
    @Test public void generic200AndHtmlNeverAcknowledgeQueueMemory() throws Exception
    {
        policy("ge-v1",TOKEN);plugin.pendingEvents.add(event(A,"ge_progress"));
        for(String response:Arrays.asList("{\"ok\":true,\"stored\":1}","<html>temporary failure</html>"))
        {
            server.enqueue(new MockResponse().setBody(response));plugin.eventRetryBackoffUntilMs=0;plugin.flushEvents();sent();done();assertEquals(1,plugin.pendingEvents.size());
        }
        assertEquals(0L,plugin.captureHealthSnapshot().get("events_sent_total"));
    }
    @Test public void oldTokenResponseCannotGrantAndMixedFamiliesAreSeparated() throws Exception
    {
        policy("ge-v1","ffffffffffffffffffffffffffffffff");plugin.pendingEvents.add(event(A,"ge_progress"));server.enqueue(new MockResponse().setBody("{}"));
        plugin.flushEvents();assertFalse(sent().has("ack_protocol"));done();
        policy("ge-v1",TOKEN);plugin.pendingEvents.add(event(A,"ge_progress"));plugin.pendingEvents.add(event(B,"level_up"));
        server.enqueue(new MockResponse().setBody(receipt(row(0,A,"queued",",\"broker_ack\":true"))));plugin.flushEvents();assertEquals(1,sent().getAsJsonArray("events").size());done();
        server.enqueue(new MockResponse().setBody("{}"));plugin.flushEvents();assertFalse(sent().has("ack_protocol"));done();
    }
    @Test public void staffSpoolGrantKeepsLegacyContract() throws Exception
    {
        policy("own-history-v1",TOKEN);set("spoolGrantToken",TOKEN);plugin.pendingEvents.add(event(A,"ge_progress"));server.enqueue(new MockResponse().setBody("{}"));
        plugin.flushEvents();assertFalse(sent().has("ack_protocol"));done();
    }
    @Test public void receiptRequiresMatchingIndexIdStrictBooleansAndBothDurableStores()
    {
        List<Map<String,Object>> batch=Arrays.asList(event(A,"ge_progress"),event(B,"ge_progress"));
        List<EventQueueProtocol.Outcome> retry=Arrays.asList(EventQueueProtocol.Outcome.RETRY,EventQueueProtocol.Outcome.RETRY);
        assertEquals(retry,EventQueueProtocol.receipts(receipt(row(0,B,"queued",",\"broker_ack\":true")),batch));
        assertEquals(retry,EventQueueProtocol.receipts(receipt(row(0,A,"queued",",\"broker_ack\":\"true\"")),batch));
        assertEquals(retry,EventQueueProtocol.receipts(receipt(row(0,A,"stored",",\"d1_receipt\":true")),batch));
        assertEquals(retry,EventQueueProtocol.receipts(receipt(row(0,A,"queued",",\"broker_ack\":true"),row(0,A,"retry","")),batch));
        assertEquals(Arrays.asList(EventQueueProtocol.Outcome.ERASED,EventQueueProtocol.Outcome.RETRY),EventQueueProtocol.receipts(receipt(row(0,A,"rejected",",\"reason\":\"erased\"")),batch));
    }
    @Test public void queueEnvelopeOverheadIsIncludedAtByteBoundary()
    {
        String token="\""+TOKEN+"\"";String huge="\""+String.join("",Collections.nCopies(262060,"x"))+"\"";
        List<String> rows=Arrays.asList(huge,"{}");
        int kept=AccountConnectPlugin.fitEventBatch(token,rows,true);
        assertEquals(1,kept);assertTrue(AccountConnectPlugin.eventBatchBody(token,rows.subList(0,kept),true).contains("queue-v1"));
        assertFalse(AccountConnectPlugin.eventBatchBody(token,rows.subList(0,1)).contains("queue-v1"));
    }
}
