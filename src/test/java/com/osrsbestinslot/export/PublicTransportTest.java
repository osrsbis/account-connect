package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Public Hub wire boundary, including payloads persisted by older clients. */
public class PublicTransportTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final Gson gson = new Gson();
    private MockWebServer server;
    private AccountConnectPlugin plugin;
    private Path data;

    @Before public void setup() throws Exception
    {
        server = new MockWebServer(); server.start();
        data = Files.createTempDirectory("public-transport");
        plugin = new AccountConnectPlugin();
        AccountConnectConfig config = mock(AccountConnectConfig.class);
        when(config.linkToken()).thenReturn(TOKEN);
        when(config.apiBaseUrl()).thenReturn(server.url("/api").toString());
        put("config", config); put("gson", gson); put("okHttpClient", new OkHttpClient());
        put("activeHash", "12345"); put("activeRsn", "OwnPlayer");
        plugin.setSpoolDataRootForTest(data);
    }
    @After public void teardown() throws Exception
    {
        plugin.crashSpoolForTest(); server.shutdown(); EventSpoolCodecTest.deleteTree(data);
    }
    private void put(String key, Object value) throws Exception
    {
        Field f = AccountConnectPlugin.class.getDeclaredField(key); f.setAccessible(true); f.set(plugin, value);
    }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception
    {
        Method m = AccountConnectPlugin.class.getDeclaredMethod(name, types); m.setAccessible(true);
        return m.invoke(plugin, args);
    }
    private Response grant()
    {
        return new Response.Builder().request(new Request.Builder().url(server.url("/")).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("X-Screenshots", "allow").header("X-Clips", "allow")
            .header("X-Store-Tools", "on").header("X-Drop-Proof", "on")
            .header("X-Max-Capture", "on").header("X-Event-Spool", "on").build();
    }
    private Map<String,Object> legacyTrade()
    {
        Map<String,Object> e = new LinkedHashMap<>();
        e.put("type", "trade"); e.put("event_id", ID); e.put("ts", System.currentTimeMillis());
        e.put("account_hash", "12345"); e.put("rsn", "OwnPlayer");
        e.put("given", Arrays.asList(Map.of("id", 995, "qty", 50)));
        e.put("received", Arrays.asList(Map.of("id", 4151, "qty", 1, "name", "OTHER_CANARY")));
        e.put("counterparty", "OTHER_CANARY"); e.put("counterparty_combat_level", 99);
        e.put("surrounding_at_trade", Map.of("players", Arrays.asList(Map.of("rsn", "OTHER_CANARY"))));
        e.put("received_text", "OTHER_CANARY"); return e;
    }
    private void assertOwnTrade(RecordedRequest r)
    {
        assertNotNull(r); String body = r.getBody().readUtf8();
        assertFalse(body, body.contains("OTHER_CANARY"));
        assertFalse(body, body.contains("counterparty"));
        assertTrue(body, body.contains(ID)); assertTrue(body, body.contains("OwnPlayer"));
        assertTrue(body, body.contains("4151")); assertTrue(body, body.contains("995"));
    }
    @Test public void qualifiedGeCountersSurviveProjectionWithoutUnknownNestedFields()
    {
        Map<String,Object> observation = new LinkedHashMap<>();
        observation.put("delta_qty", 3L); observation.put("delta_gp", null);
        observation.put("source", "runelite_grand_exchange_offer");
        observation.put("identity", "inferred_across_gap");
        observation.put("other_player", "never upload");
        observation.put("fill_time", "invented player name");
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("type", "ge_progress"); event.put("ge_observation", observation);
        Map<?,?> result = (Map<?,?>) PublicTransport.event(event).get("ge_observation");
        assertEquals(3L, result.get("delta_qty")); assertTrue(result.containsKey("delta_gp"));
        assertNull(result.get("delta_gp")); assertEquals("inferred_across_gap", result.get("identity"));
        assertFalse(result.containsKey("other_player")); assertFalse(result.containsKey("fill_time"));
    }
    @Test public void staleMemoryTradeIsProjectedAtDispatch() throws Exception
    {
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        plugin.pendingEvents.add(legacyTrade()); plugin.flushEvents();
        assertOwnTrade(server.takeRequest(3, TimeUnit.SECONDS));
    }
    @Test public void oldSpoolTradeIsProjectedAndOnlyExactAckSettlesIt() throws Exception
    {
        Path dir = data.resolve("osrsbis/spool").resolve(EventSpool.identityKey(TOKEN, "12345"));
        EventSpool old = new EventSpool(data, dir, System::currentTimeMillis,
            EventSpool.ATOMIC, EventSpool.Limits.DEFAULT, new EventSpool.Counters());
        assertTrue(old.append(ID, "trade", System.currentTimeMillis(), gson.toJson(legacyTrade())));
        old.crashForTest();
        plugin.applyServerPolicy(grant()); plugin.awaitSpoolIdleForTest();
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"stored\":1}"));
        plugin.replaySpoolTick(); assertOwnTrade(server.takeRequest(3, TimeUnit.SECONDS));
        for (int i=0; i<100 && plugin.spoolReplayInFlight; i++) Thread.sleep(10);
        plugin.awaitSpoolIdleForTest();
        assertEquals(1, plugin.spoolPendingIdsForTest().size());
        plugin.spoolReplayNotBeforeMs=0; plugin.spoolReplayBackoffUntilMs=0;
        server.enqueue(new MockResponse().setBody("{\"results\":[{\"event_id\":\""+ID+"\",\"status\":\"stored\"}]}"));
        plugin.replaySpoolTick(); assertOwnTrade(server.takeRequest(3, TimeUnit.SECONDS));
        for (int i=0; i<100 && plugin.spoolReplayInFlight; i++) Thread.sleep(10);
        plugin.awaitSpoolIdleForTest(); assertEquals(0, plugin.spoolPendingIdsForTest().size());
    }
    @Test public void remoteGrantsCannotEnablePublicMediaOrFirehose() throws Exception
    {
        assertFalse(plugin.screenshotsEnabled()); assertFalse(plugin.storeClipsEnabled());
        plugin.applyServerPolicy(grant());
        assertFalse(plugin.screenshotsEnabled()); assertFalse(plugin.storeClipsEnabled());
        assertFalse(plugin.dropProofEnabled()); assertFalse(plugin.maxCapture());
        call("uploadTradeScreenshot", new Class<?>[]{String.class, byte[].class, String.class}, TOKEN, new byte[]{1,2}, "confirm");
        call("postStoreClipChunk", new Class<?>[]{String.class,String.class,List.class,long.class},
            server.url("/api").toString(),TOKEN,Arrays.asList(new byte[]{1,2}),1L);
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
    }
    @Test public void cachedSnapshotDoesNotUploadSharedStorage() throws Exception
    {
        Map<String,Object> snap = new LinkedHashMap<>();
        snap.put("account", Map.of("rsn", "OwnPlayer"));
        snap.put("group_storage", Map.of("items", "OTHER_CANARY"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        call("postSnapshot", new Class<?>[]{String.class,Map.class,String.class}, TOKEN,snap,"old-hash");
        RecordedRequest r=server.takeRequest(3,TimeUnit.SECONDS); assertNotNull(r);
        String body=r.getBody().readUtf8(); assertFalse(body,body.contains("OTHER_CANARY"));
        assertTrue(body,body.contains("OwnPlayer"));
    }
    @Test public void otherPlayerContextsAreRemovedWhileOwnGeFactsSurvive() throws Exception
    {
        Map<String,Object> own = new LinkedHashMap<>(Map.of("type", "ge_sell", "event_id", ID,
            "item", 4151, "qty", 2, "gp", 2000000L, "offer_key", "own-offer", "profile_type", "LEAGUE"));
        for (String type : Arrays.asList("trade", "store_sell", "drop", "ground_removed", "loot"))
        {
            Map<String,Object> e = new LinkedHashMap<>(own); e.put("type", type);
            for (String key : Arrays.asList("counterparty", "counterparty_inferred", "nearby", "nearby_at_tx",
                "surrounding_at_drop", "surrounding_at_trade", "taken_by_candidates", "telegrab", "received_text"))
                e.put(key, Map.of("rsn", "OTHER_CANARY"));
            e.put("source_type", "player"); e.put("source_name", "OTHER_CANARY");
            plugin.pendingEvents.add(e);
        }
        plugin.pendingEvents.add(own);
        plugin.pendingEvents.add(Map.of("type", "fh_batch", "events", "OTHER_CANARY"));
        plugin.pendingEvents.add(Map.of("type", "store_taken", "taken_by_candidates", "OTHER_CANARY"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true}")); plugin.flushEvents();
        RecordedRequest r=server.takeRequest(3,TimeUnit.SECONDS); assertNotNull(r);
        String wire=r.getBody().readUtf8(); assertFalse(wire, wire.contains("OTHER_CANARY"));
        Map<?,?> body=gson.fromJson(wire,Map.class); List<?> events=(List<?>)body.get("events");
        assertEquals(6, events.size());
        Map<?,?> ge=(Map<?,?>)events.get(5);
        assertEquals("own-offer", ge.get("offer_key")); assertEquals("LEAGUE", ge.get("profile_type"));
        assertEquals(2000000.0, ge.get("gp")); assertEquals(2.0, ge.get("qty"));
    }
    @Test public void prohibitedLegacySpoolRowIsQuarantinedLocallyWithoutFakeAck() throws Exception
    {
        Path dir=data.resolve("osrsbis/spool").resolve(EventSpool.identityKey(TOKEN,"12345"));
        EventSpool old=new EventSpool(data,dir,System::currentTimeMillis,
            EventSpool.ATOMIC,EventSpool.Limits.DEFAULT,new EventSpool.Counters());
        Map<String,Object> e=legacyTrade(); e.put("type","store_taken");
        assertTrue(old.append(ID,"store_taken",System.currentTimeMillis(),gson.toJson(e))); old.crashForTest();
        plugin.applyServerPolicy(grant()); plugin.awaitSpoolIdleForTest();
        plugin.replaySpoolTick(); plugin.awaitSpoolIdleForTest();
        assertNull(server.takeRequest(200,TimeUnit.MILLISECONDS));
        assertEquals(0,plugin.spoolPendingIdsForTest().size());
        try (java.util.stream.Stream<Path> files=Files.walk(dir))
        {
            String q=files.filter(f -> f.getFileName().toString().contains("quarantine"))
                .map(f -> {try {return Files.readString(f);} catch(Exception ex) {throw new RuntimeException(ex);}})
                .collect(java.util.stream.Collectors.joining());
            assertTrue(q,q.contains(ID)); assertTrue(q,q.contains("\"status\":-1"));
            assertFalse(q,q.contains("OTHER_CANARY"));
        }
    }

    @Test public void bankPairsKeepTheirExactManifestOnMemoryAndLegacyReplay() throws Exception
    {
        List<long[]> pairs=Arrays.asList(new long[]{995,5000}, new long[]{4151,1});
        String expected=BankVisitManifest.json(pairs);
        String hash=BankVisitManifest.sha256(pairs);
        Map<String,Object> bank=new LinkedHashMap<>(Map.of("type","bank_visit_manifest_chunk",
            "event_id",ID,"account_hash","12345","items",pairs,"manifest_sha256",hash));
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        plugin.pendingEvents.add(bank); plugin.flushEvents();
        RecordedRequest memory=server.takeRequest(3,TimeUnit.SECONDS); assertNotNull(memory);
        com.google.gson.JsonObject sent=new com.google.gson.JsonParser().parse(memory.getBody().readUtf8())
            .getAsJsonObject().getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals(expected,sent.get("items").toString()); assertEquals(hash,sent.get("manifest_sha256").getAsString());
        Path dir=data.resolve("osrsbis/spool").resolve(EventSpool.identityKey(TOKEN,"12345"));
        EventSpool old=new EventSpool(data,dir,System::currentTimeMillis,
            EventSpool.ATOMIC,EventSpool.Limits.DEFAULT,new EventSpool.Counters());
        assertTrue(old.append(ID,"bank_visit_manifest_chunk",System.currentTimeMillis(),gson.toJson(bank)));old.crashForTest();
        plugin.applyServerPolicy(grant());plugin.awaitSpoolIdleForTest();
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));plugin.replaySpoolTick();
        RecordedRequest replay=server.takeRequest(3,TimeUnit.SECONDS); assertNotNull(replay);
        sent=new com.google.gson.JsonParser().parse(replay.getBody().readUtf8())
            .getAsJsonObject().getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals(expected,sent.get("items").toString());assertEquals(hash,sent.get("manifest_sha256").getAsString());
    }

    /** The X-Staff-Capture answer through the same policy path; staffAccount null omits the account header. */
    private Response staffAnswer(String staffCapture, String staffAccount)
    {
        plugin.staffApiBase = server.url("/api").toString();	// this rig's stand-in for osrsbestinslot.com
        Response.Builder b = grant().newBuilder().request(new Request.Builder().url(server.url("/api/account-ingest")).build());
        if (staffCapture != null) b.header("X-Staff-Capture", staffCapture);
        if (staffAccount != null) b.header("X-Staff-Account", staffAccount);
        return b.build();
    }
    private void assertNoStaffTool(String why)
    {
        assertFalse(why, plugin.staffCaptureEnabled());
        assertFalse(why, plugin.screenshotsEnabled()); assertFalse(why, plugin.storeClipsEnabled());
        assertFalse(why, plugin.dropProofEnabled()); assertFalse(why, plugin.maxCapture());
    }

    /**
     * One plugin for everyone (2026-10-28). A player's client gets the staff tools only when the server
     * names its logged-in account in a staff-capture grant for its token. Nothing short of that opens them.
     */
    /** A client with the fixture account logged in. */
    private net.runelite.api.Client loggedIn()
    {
        net.runelite.api.Client client = mock(net.runelite.api.Client.class);
        when(client.getAccountHash()).thenReturn(12345L);
        return client;
    }

    @Test public void onlyTheServerStaffGrantForThisAccountOpensStaffTools() throws Exception
    {
        put("client", loggedIn());
        plugin.applyServerPolicy(staffAnswer("on", null), TOKEN);
        assertNoStaffTool("a grant that names no account");
        plugin.applyServerPolicy(staffAnswer("on", "99999"), TOKEN);
        assertNoStaffTool("a grant for another account");
        plugin.applyServerPolicy(staffAnswer("on", "12345"), "fedcba9876543210fedcba9876543210");
        assertNoStaffTool("a late answer for a token this client no longer holds");
        plugin.applyServerPolicy(staffAnswer("yes", "12345"), TOKEN);
        assertNoStaffTool("any value but on");

        plugin.applyServerPolicy(staffAnswer("on", "12345"), TOKEN);
        assertTrue(plugin.staffCaptureEnabled());
        assertTrue(plugin.screenshotsEnabled()); assertTrue(plugin.storeClipsEnabled()); assertTrue(plugin.dropProofEnabled());
        assertFalse("the firehose stays off for staff too", plugin.maxCapture());

        put("activeHash", "99999");
        assertNoStaffTool("another account logged in on the same client");
        put("activeHash", "12345");
        assertTrue(plugin.staffCaptureEnabled());
        plugin.applyServerPolicy(new Response.Builder().request(new Request.Builder().url(server.url("/api/account-ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(503).message("busy").build(), TOKEN);
        assertTrue("a server error says nothing about staff", plugin.staffCaptureEnabled());
        plugin.applyServerPolicy(grant(), TOKEN);
        assertNoStaffTool("the next accepted upload without the grant ends it");
        plugin.applyServerPolicy(staffAnswer("on", "12345"), TOKEN);
        plugin.applyServerPolicy(new Response.Builder().request(new Request.Builder().url(server.url("/api/account-ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(403).message("revoked").build(), TOKEN);
        assertNoStaffTool("a revoked token");
        plugin.applyServerPolicy(staffAnswer("on", "12345"), TOKEN);
        plugin.applyServerPolicy(new Response.Builder().request(new Request.Builder().url(server.url("/api/account-ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(413).message("too large").build(), TOKEN);
        assertNoStaffTool("any other refusal");
    }

    /**
     * "API base URL" is a setting any player can change. A server the player points it at must not be able to
     * switch on the staff tools, whatever it answers, through the real upload path.
     */
    @Test public void onlyTheOfficialServerCanGrantStaffCapture() throws Exception
    {
        assertEquals("https://www.osrsbestinslot.com/wp-json/osrsbis/v1", AccountConnectPlugin.OFFICIAL_API_BASE);
        assertEquals("the default endpoint is the official one", AccountConnectPlugin.OFFICIAL_API_BASE, plugin.staffApiBase);
        put("client", loggedIn());
        server.enqueue(new MockResponse().setBody("{\"ok\":true}")
            .setHeader("X-Staff-Capture", "on").setHeader("X-Staff-Account", "12345")
            .setHeader("X-Store-Tools", "on").setHeader("X-Drop-Proof", "on"));
        Map<String,Object> snap = new LinkedHashMap<>(); snap.put("account", Map.of("rsn", "OwnPlayer"));
        call("postSnapshot", new Class<?>[]{String.class,Map.class,String.class}, TOKEN, snap, "h");
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
        for (int i = 0; i < 100 && !plugin.storeToolsEnabled(); i++) Thread.sleep(10);
        assertTrue("the answer was applied", plugin.storeToolsEnabled());
        assertNoStaffTool("a player's own server granted staff capture");

        // Granted by the official endpoint, the grant ends the moment the setting names another server.
        plugin.applyServerPolicy(staffAnswer("on", "12345"), TOKEN);
        assertTrue(plugin.staffCaptureEnabled());
        AccountConnectConfig moved = mock(AccountConnectConfig.class);
        when(moved.linkToken()).thenReturn(TOKEN); when(moved.apiBaseUrl()).thenReturn("https://example.invalid/api");
        put("config", moved);
        assertNoStaffTool("the setting now names another server");
    }

    /** A relog to another account on a staff token: the client knows at once, before the next session start. */
    @Test public void aRelogToAnotherAccountEndsStaffCaptureAtOnce() throws Exception
    {
        net.runelite.api.Client client = mock(net.runelite.api.Client.class); put("client", client);
        when(client.getAccountHash()).thenReturn(12345L);
        plugin.applyServerPolicy(staffAnswer("on", "12345"), TOKEN);
        assertTrue(plugin.staffCaptureEnabled());
        when(client.getAccountHash()).thenReturn(67890L);
        assertNoStaffTool("another account is logged in, activeHash not yet updated");
    }

    /** A staff row keeps what the plugin wrote; the same row from a player goes through the public projection. */
    @Test public void onlyAStaffRowCarriesOtherPlayers()
    {
        Map<String,Object> trade = legacyTrade();
        trade.put("__delivery_token_fp", "local");
        Map<String,Object> pub = PublicTransport.event(trade, false);
        assertFalse(pub.toString(), pub.toString().contains("OTHER_CANARY"));
        Map<String,Object> staff = PublicTransport.event(trade, true);
        assertEquals("OTHER_CANARY", staff.get("counterparty"));
        assertEquals("OTHER_CANARY", staff.get("received_text"));
        assertFalse("local delivery keys never leave", staff.containsKey("__delivery_token_fp"));
        Map<String,Object> taken = new LinkedHashMap<>();
        taken.put("type", "store_taken"); taken.put("event_id", ID); taken.put("item", 1);
        taken.put("taken_by_candidates", Arrays.asList(Map.of("rsn", "OTHER_CANARY")));
        assertNull("a player never sends store_taken", PublicTransport.event(taken, false));
        assertNotNull(PublicTransport.event(taken, true));
    }

    /** The plugin's own emit path: a player's queued rows hold no other player, a staff client's keep them. */
    @Test public void emittedRowsCarryOtherPlayersOnlyUnderTheStaffGrant() throws Exception
    {
        Map<String,Object> fields = legacyTrade();
        for (String k : Arrays.asList("type", "event_id", "ts", "account_hash", "rsn")) fields.remove(k);
        Map<String,Object> taken = new LinkedHashMap<>();
        taken.put("item", 1); taken.put("taken_by_candidates", Arrays.asList(Map.of("rsn", "OTHER_CANARY")));

        plugin.emitEvent("trade", new LinkedHashMap<>(fields));
        plugin.emitEvent("store_taken", new LinkedHashMap<>(taken));
        assertEquals("a player's store_taken is never queued", 1, plugin.pendingEvents.size());
        assertFalse(plugin.pendingEvents.toString(), plugin.pendingEvents.toString().contains("OTHER_CANARY"));

        plugin.pendingEvents.clear();
        plugin.applyServerPolicy(staffAnswer("on", "12345"), TOKEN);
        plugin.emitEvent("trade", new LinkedHashMap<>(fields));
        plugin.emitEvent("store_taken", new LinkedHashMap<>(taken));
        assertEquals(2, plugin.pendingEvents.size());
        assertEquals("OTHER_CANARY", plugin.pendingEvents.get(0).get("counterparty"));
        assertEquals("store_taken", plugin.pendingEvents.get(1).get("type"));
    }

    @Test public void publicCollectorsNeverReadOtherPlayerIdentitiesEvenWithGrants() throws Exception
    {
        net.runelite.api.Client client=mock(net.runelite.api.Client.class); put("client",client);
        plugin.applyServerPolicy(grant());
        assertTrue(plugin.observedPlayers().isEmpty());
        assertTrue(plugin.nearbyPlayersSnapshot(64,true).isEmpty());
        assertTrue(plugin.surroundingPlayersNow().isEmpty());
        plugin.accumulateShopNearby();
        assertNull(plugin.visibleCounterpartyCombatLevel("OTHER_CANARY"));
        plugin.captureOtherOfferWhileMainOpen();
        verify(client,never()).getPlayers();
        verify(client,never()).getWidget((335 << 16) | 31); // legacy partner title
        net.runelite.api.events.ProjectileMoved projectile=mock(net.runelite.api.events.ProjectileMoved.class);
        plugin.onProjectileMoved(projectile); verifyNoInteractions(projectile);
    }

}
