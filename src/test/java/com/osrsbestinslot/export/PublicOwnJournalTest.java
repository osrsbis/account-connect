package com.osrsbestinslot.export;

import com.google.gson.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * The public own-events journal (X-Event-Receipt: own-p01-v1): the real plugin emitter, journal, restart and
 * event-ingest receipt path against a server that dedupes on event_id; no live game client.
 */
public class PublicOwnJournalTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private final AtomicReference<String> token = new AtomicReference<>(TOKEN);
    private final List<AccountConnectPlugin> plugins = new ArrayList<>();
    private MockWebServer server;
    private OkHttpClient http;
    private Path data;
    private AccountConnectPlugin p;

    /** The server: 429 while throttled, else per-ID results; stored once per event_id, duplicate after. */
    private volatile int status = 200;
    private final List<String> accepted = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> posts200 = new ConcurrentHashMap<>();
    private final List<String> receiptLane = Collections.synchronizedList(new ArrayList<>());

    @Before public void setup() throws Exception
    {
        data = Files.createTempDirectory("public-own"); server = new MockWebServer();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher()
        {
            @Override public MockResponse dispatch(RecordedRequest request)
            {
                if (!request.getPath().endsWith("/event-ingest")) return new MockResponse().setBody("{\"ok\":true}");
                if (status == 429) return new MockResponse().setResponseCode(429).setHeader("Retry-After", "1")
                    .setBodyDelay(30, TimeUnit.MILLISECONDS);
                if (status != 200) return new MockResponse().setResponseCode(status).setBody("{\"error\":\"revoked\"}");
                JsonArray events = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("events");
                StringBuilder results = new StringBuilder();
                for (JsonElement e : events)
                {
                    String id = e.getAsJsonObject().get("event_id").getAsString();
                    boolean first = posts200.merge(id, 1, Integer::sum) == 1;
                    if (first) accepted.add(id);
                    if (results.length() > 0) results.append(',');
                    results.append("{\"event_id\":\"").append(id).append("\",\"status\":\"").append(first ? "stored" : "duplicate").append("\"}");
                }
                return new MockResponse().setBody("{\"ok\":true,\"results\":[" + results + "]}");
            }
        });
        server.start();
        http = new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(); p = fresh();
    }

    @After public void close() throws Exception
    {
        for (AccountConnectPlugin plugin : plugins) SpoolHooks.crash(plugin.spooler);
        server.shutdown(); http.dispatcher().executorService().shutdownNow(); http.connectionPool().evictAll();
        try (java.util.stream.Stream<Path> paths = Files.walk(data))
        { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path); }
    }

    private AccountConnectPlugin fresh() throws Exception
    {
        AccountConnectPlugin plugin = new AccountConnectPlugin(); plugins.add(plugin);
        AccountConnectConfig config = mock(AccountConnectConfig.class);
        when(config.linkToken()).thenAnswer(call -> token.get()); when(config.apiBaseUrl()).thenReturn(server.url("/api").toString());
        ConfigManager manager = mock(ConfigManager.class); ConfigProfile profile = mock(ConfigProfile.class);
        when(profile.getId()).thenReturn(7L); when(manager.getProfile()).thenReturn(profile);
        set(plugin, "config", config); set(plugin, "configManager", manager); set(plugin, "gson", new Gson());
        set(plugin, "okHttpClient", http); set(plugin, "activeHash", "123"); set(plugin, "activeProfileType", "STANDARD");
        plugin.session.sessionActive = true; SpoolHooks.dataRoot(plugin.spooler, data); return plugin;
    }

    private static void set(AccountConnectPlugin plugin, String name, Object value) throws Exception
    { Field f = AccountConnectPlugin.class.getDeclaredField(name); f.setAccessible(true); f.set(plugin, value); }

    /** A snapshot answer: the own-events receipt header (null: absent), optionally the bank receipt or the staff spool. */
    private void policy(int code, String receipt, boolean bank, boolean staffSpool) throws Exception
    {
        Response.Builder r = new Response.Builder().request(new Request.Builder().url(server.url("/api/ingest")).build())
            .protocol(Protocol.HTTP_1_1).code(code).message("x");
        if (receipt != null) r.header("X-Event-Receipt", receipt);
        if (bank) r.header("X-Bank-Evidence", "on").header("X-Bank-Receipt", "event-id-v1");
        if (staffSpool) r.header("X-Event-Spool", "on");
        p.grants.applyServerPolicy(r.build(), token.get()); SpoolHooks.awaitIdle(p.spooler);
    }

    private void grant() throws Exception { policy(200, "own-p01-v1", false, false); }

    private long health(String key) { return ((Number) p.events.captureHealthSnapshot().get(key)).longValue(); }

    private String last() { synchronized (p.events.pendingEvents) { return (String) p.events.pendingEvents.get(p.events.pendingEvents.size() - 1).get("event_id"); } }

    /** Emit one row; return its event_id when it went to the own-events journal, else null. */
    private String emit(String type, Map<String, Object> fields) throws Exception
    {
        long before = health("public_outbox_pending");
        p.events.emitEvent(type, new LinkedHashMap<>(fields)); SpoolHooks.awaitIdle(p.spooler);
        return health("public_outbox_pending") == before ? null : journaledTail(type);
    }

    /** The newest journal row of this type: the journal holds every unsettled row in append order. */
    private String journaledTail(String type) throws Exception
    {
        AtomicReference<String> id = new AtomicReference<>();
        p.spooler.spoolExec.submit(() ->
        {
            for (EventSpool.Record r : p.outbox.publicOwnOutbox.journal.pending()) if (type.equals(r.type)) id.set(r.eventId);
        }).get(5, TimeUnit.SECONDS);
        return id.get();
    }

    private static Map<String, Object> f(Object... kv) { return Fields.of(kv); }

    private void done() throws Exception
    {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (p.events.eventPostInFlight && System.nanoTime() < until) Thread.sleep(5);
        assertFalse(p.events.eventPostInFlight); SpoolHooks.awaitIdle(p.spooler);
    }

    /** One flush with the backoff cleared, as the 5 s tick does once the wait is over. */
    private void tick(boolean pumpWhileInFlight) throws Exception
    {
        p.events.eventRetryBackoffUntilMs = 0; p.events.queueRetryBackoffUntilMs = 0;
        p.outbox.pumpPublicOutbox(); SpoolHooks.awaitIdle(p.spooler);
        p.events.flushEvents();
        if (pumpWhileInFlight) { p.outbox.pumpPublicOutbox(); }
        done();
    }

    private void drain() throws Exception
    {
        for (int i = 0; i < 400 && (health("public_outbox_pending") > 0 || !p.events.pendingEvents.isEmpty()); i++) tick(false);
        assertEquals(0, health("public_outbox_pending"));
    }

    /** A mixed session; journaled ids in emit order. geHeavy picks the GE mix, else the loot mix. */
    private List<String> session(int rows, boolean geHeavy, int start) throws Exception
    {
        return session(rows, geHeavy, start, true);
    }

    private List<String> session(int rows, boolean geHeavy, int start, boolean logout) throws Exception
    {
        List<String> ids = new ArrayList<>();
        ids.add(emit("login", f("world", 301)));
        for (int i = start; i < start + rows; i++)
        {
            int k = i % 10;
            String id;
            if (geHeavy ? k < 7 : k < 2)
                id = emit(k % 2 == 0 ? "ge_progress" : "ge_buy", f("item", 560, "qty", i, "price", 200, "slot", i % 8));
            else if (k < 8)
                id = emit("loot", f("source_name", "Goblin", "source_type", "npc", "npc_id", 3029,
                    "items", Collections.singletonList(f("id", 526, "qty", 1)), "value", 0));
            else if (k == 8) id = emit(i % 20 == 8 ? "pickup" : "kill_count", f("item", 995, "qty", i, "source", "Goblin", "kind", "kill", "count", i));
            else id = emit(i % 30 == 9 ? "trade" : "alch", f("item", 1, "qty", 1, "spell", "High Level Alchemy", "gp", 1));
            assertNotNull("every public P0/P1 row goes to the journal", id);
            ids.add(id);
            if (i % 25 == 0) assertNull("a region crossing stays memory-only", emit("region", f("from", 1, "to", 2, "x", 3, "y", 4)));
            if (i % 50 == 0) ids.add(emit("xp_gain", f("gains", Collections.singletonList(f("skill", "ATTACK", "xp", 40)), "total", 40)));
            if (i % 120 == 0) ids.add(emit("world_hop", f("from", 301, "to", 302)));
            if (status == 429 && i % 40 == 39) tick(true);
        }
        if (logout) ids.add(emit("logout", f("reason", "manual", "session_ms", 1000)));
        assertFalse(ids.contains(null));
        return ids;
    }

    private void assertDeliveredOnceInOrder(List<String> ids)
    {
        Set<String> journaled = new HashSet<>(ids);
        List<String> order = new ArrayList<>();
        synchronized (accepted) { for (String id : accepted) if (journaled.contains(id)) order.add(id); }
        assertEquals("every journaled row was stored, in journal order", ids, order);
        for (String id : ids) assertEquals("posted once with a 200", Integer.valueOf(1), posts200.get(id));
        assertEquals(0, health("events_overflow_p0_total"));
        assertEquals(0, health("events_overflow_p1_total"));
        assertEquals(0, health("public_outbox_dropped_total"));
        assertEquals(0, health("public_outbox_not_durable_total"));
        assertEquals(0, health("public_outbox_oldest_age_ms"));
    }

    private void throttledRunThenRecovery(boolean geHeavy) throws Exception
    {
        grant(); status = 429;
        List<String> ids = new ArrayList<>(session(300, geHeavy, 0));
        ids.addAll(session(300, geHeavy, 300));
        assertTrue("longer than the memory buffer", ids.size() > EventQueue.MAX_PENDING_EVENTS + 100);
        assertEquals(ids.size(), health("public_outbox_pending"));
        assertTrue(p.events.pendingEvents.size() <= EventQueue.MAX_PENDING_EVENTS);
        assertTrue("memory-only P2 rows still count when they go", health("events_overflow_p2_total") > 0);
        assertTrue(accepted.isEmpty());
        status = 200; drain();
        assertDeliveredOnceInOrder(ids);
    }

    @Test public void aGeHeavy429RunLongerThanTheBufferDeliversEveryRowOnceInOrder() throws Exception
    { throttledRunThenRecovery(true); }

    @Test public void aLootHeavy429RunLongerThanTheBufferDeliversEveryRowOnceInOrder() throws Exception
    { throttledRunThenRecovery(false); }

    @Test public void aProcessKillInThe429RunReplaysEveryRowAfterRestartInOrder() throws Exception
    {
        grant(); status = 429;
        List<String> ids = new ArrayList<>(session(300, true, 0));
        ids.addAll(session(150, false, 300, false)); // the kill comes before this session's logout
        SpoolHooks.crash(p.spooler); p = fresh();
        p.outbox.pumpPublicOutbox(); SpoolHooks.awaitIdle(p.spooler);
        assertTrue("no replay before a fresh grant", p.events.pendingEvents.isEmpty());
        grant();
        assertEquals(ids.size(), health("public_outbox_pending"));
        List<String> after = session(200, true, 450);
        ids.addAll(after);
        status = 200; drain();
        assertDeliveredOnceInOrder(ids);
    }

    @Test public void aRealLogoutReplaysAfterItsSessionsRowsAfterACrash() throws Exception
    {
        grant(); status = 429;
        List<String> ids = new ArrayList<>(session(80, false, 0, false));
        p.session.trackLogout("manual"); SpoolHooks.awaitIdle(p.spooler);
        ids.add(journaledTail("logout"));
        tick(false);
        SpoolHooks.crash(p.spooler); p = fresh(); grant();
        status = 200; drain();
        assertDeliveredOnceInOrder(ids);
        assertEquals(ids.get(ids.size() - 1), accepted.get(accepted.size() - 1));
    }

    @Test public void withoutTheGrantOrWithOffRowsKeepThe0770MemoryPath() throws Exception
    {
        assertNull("no header: memory only", emit("loot", f("source_name", "Goblin", "source_type", "npc")));
        policy(200, "off", false, false);
        assertNull(emit("logout", f("reason", "manual")));
        assertNull(p.grants.ownJournalGrantToken);
        assertFalse(Files.exists(data.resolve("osrsbis/public-own-events-v1")));
        p.events.flushEvents(); done();
        assertEquals(2, accepted.size());
        assertEquals(0, health("public_outbox_pending"));
    }

    @Test public void offPausesTheJournalWithoutPurgingAndTheGrantResumesIt() throws Exception
    {
        grant(); String kept = emit("loot", f("source_name", "Goblin", "source_type", "npc"));
        assertNotNull(kept);
        policy(200, "off", false, false);
        assertNull("off: new rows take the memory path", emit("ge_buy", f("item", 2, "qty", 1)));
        p.events.flushEvents(); done();
        assertEquals("only the memory row went", 1, accepted.size()); assertFalse(accepted.contains(kept));
        assertEquals(1, health("public_outbox_pending"));
        policy(200, null, false, false);
        assertNull("a missing header ends the grant too", p.grants.ownJournalGrantToken);
        grant(); drain();
        assertTrue(accepted.contains(kept)); assertEquals(Integer.valueOf(1), posts200.get(kept));
    }

    @Test public void a429Or5xxSnapshotAnswerKeepsTheGrantAndA403EndsIt() throws Exception
    {
        grant(); policy(429, null, false, false); assertEquals(TOKEN, p.grants.ownJournalGrantToken);
        policy(503, null, false, false); assertEquals(TOKEN, p.grants.ownJournalGrantToken);
        policy(403, null, false, false); assertNull(p.grants.ownJournalGrantToken);
    }

    private boolean ownShardsOnDisk() throws Exception
    {
        Path root = data.resolve("osrsbis/public-own-events-v1");
        if (!Files.exists(root)) return false;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) { return paths.anyMatch(path -> path.toString().endsWith(".spl")); }
    }

    @Test public void anEventIngest403PurgesTheJournalBeforeAnyRestartReplay() throws Exception
    {
        grant(); for (int i = 0; i < 3; i++) emit("loot", f("source_name", "Goblin", "source_type", "npc"));
        assertTrue(ownShardsOnDisk());
        status = 403; p.events.flushEvents(); done();
        assertEquals(0, health("public_outbox_pending")); assertNull(p.grants.ownJournalGrantToken);
        SpoolHooks.crash(p.spooler); p = fresh(); status = 200; grant();
        assertTrue(p.events.pendingEvents.isEmpty()); assertFalse(ownShardsOnDisk());
    }

    @Test public void aSnapshotRevocationPurgesTheJournalOnAColdStart() throws Exception
    {
        grant(); emit("death", f("location", f("x", 1, "y", 2, "plane", 0)));
        SpoolHooks.crash(p.spooler); p = fresh(); p.session.sessionActive = false; set(p, "activeHash", null);
        p.spooler.spoolOnRevoked(TOKEN); SpoolHooks.awaitIdle(p.spooler);
        SpoolHooks.crash(p.spooler); p = fresh(); grant();
        assertTrue(p.events.pendingEvents.isEmpty()); assertFalse(ownShardsOnDisk());
    }

    @Test public void aTokenChangePurgesTheJournalAndSendsNothingUnderTheNewToken() throws Exception
    {
        grant(); emit("trade", f("given", Collections.emptyList(), "received", Collections.emptyList()));
        String replacement = "ffffffffffffffffffffffffffffffff"; token.set(replacement);
        ConfigChanged e = mock(ConfigChanged.class);
        Field group = AccountConnectPlugin.class.getDeclaredField("CONFIG_GROUP"); group.setAccessible(true);
        when(e.getGroup()).thenReturn((String) group.get(null)); when(e.getKey()).thenReturn("linkToken");
        when(e.getOldValue()).thenReturn(TOKEN); when(e.getNewValue()).thenReturn(replacement);
        p.onConfigChanged(e); SpoolHooks.awaitIdle(p.spooler);
        assertNull(p.grants.ownJournalGrantToken);
        grant(); p.events.flushEvents(); done();
        assertTrue(p.events.pendingEvents.isEmpty()); assertTrue(accepted.isEmpty()); assertFalse(ownShardsOnDisk());
    }

    @Test public void aStaffSpoolGrantNeverUsesTheOwnJournal() throws Exception
    {
        policy(200, "own-p01-v1", false, true);
        assertNull(p.grants.ownJournalGrantToken);
        assertNull(emit("loot", f("source_name", "Goblin", "source_type", "npc")));
        assertFalse(Files.exists(data.resolve("osrsbis/public-own-events-v1")));
    }

    @Test public void bankRowsKeepTheBankJournalAndAnOlderClientIgnoresTheNewRoot() throws Exception
    {
        policy(200, "own-p01-v1", true, false);
        String loot = emit("loot", f("source_name", "Goblin", "source_type", "npc"));
        p.events.emitEvent("bank_visit_close", Map.of("visit_id", "a".repeat(32), "closed_at_ms", System.currentTimeMillis(),
            "close_reason", "logout", "manifest_complete", true, "moves_complete", false));
        SpoolHooks.awaitIdle(p.spooler); String bank = last();
        assertNotNull(loot); assertEquals(2, health("public_outbox_pending"));
        assertTrue(Files.isDirectory(data.resolve("osrsbis/public-bank-events-v1")));
        // Downgrade: the bank grant alone is the 0.7.70 path; it replays and settles the bank row only.
        SpoolHooks.crash(p.spooler); p = fresh(); policy(200, null, true, false);
        assertEquals(1, p.events.pendingEvents.size()); assertEquals(bank, p.events.pendingEvents.get(0).get("event_id"));
        drain();
        assertEquals(Collections.singletonList(bank), accepted);
        assertTrue("the own root is left as it was", ownShardsOnDisk());
        SpoolHooks.crash(p.spooler); p = fresh(); grant(); drain();
        assertEquals(Arrays.asList(bank, loot), accepted);
    }

    @Test public void theOldestAgeIsZeroWhenEmptyAndGrowsWhileRowsWait() throws Exception
    {
        assertEquals(0, health("public_outbox_oldest_age_ms"));
        grant(); assertEquals(0, health("public_outbox_oldest_age_ms"));
        long now = System.currentTimeMillis(); p.spooler.wallClock = () -> now;
        status = 429; emit("logout", f("reason", "manual"));
        p.spooler.wallClock = () -> now + 60_000;
        long first = health("public_outbox_oldest_age_ms");
        assertTrue(first >= 60_000 && first < 70_000);
        p.spooler.wallClock = () -> now + 120_000;
        assertTrue(health("public_outbox_oldest_age_ms") >= first + 60_000);
        status = 200; drain();
        assertEquals(0, health("public_outbox_oldest_age_ms"));
    }

    @Test public void overflowDropsMemoryP2FirstThenTheNewestJournalRowAndCountsOnlyLoss()
    {
        PublicEventOutbox.Context owner = new PublicEventOutbox.Context(TOKEN, "123", "STANDARD", "7");
        List<Map<String, Object>> buffer = p.events.pendingEvents;
        synchronized (buffer)
        {
            for (int i = 0; i < EventQueue.MAX_PENDING_EVENTS; i++)
                buffer.add(new HashMap<>(Map.of("type", i % 2 == 0 ? "loot" : "xp_gain", "event_id", "j" + i, EventQueue.EVENT_PUBLIC_OUTBOX, owner)));
            buffer.add(0, new HashMap<>(Map.of("type", "region", "event_id", "m0")));
            p.events.trimPending();
            assertFalse("a memory P2 row goes first", buffer.stream().anyMatch(e -> "m0".equals(e.get("event_id"))));
            buffer.add(0, new HashMap<>(Map.of("type", "logout", "event_id", "m1")));
            p.events.trimPending();
            assertEquals("m1", buffer.get(0).get("event_id"));
            assertEquals("then the newest journal row", "j" + (EventQueue.MAX_PENDING_EVENTS - 2), buffer.get(buffer.size() - 1).get("event_id"));
        }
        assertEquals(1, health("events_overflow_p2_total"));
        assertEquals(0, health("events_overflow_p0_total") + health("events_overflow_p1_total"));
        assertEquals(1, health("events_lost_total"));
    }

    @Test public void withoutJournalRowsOverflowPicksTheSameRowAs0770()
    {
        Random random = new Random(7);
        String[] types = {"loot", "ge_buy", "region", "logout", "equip_change", "pickup"};
        List<Map<String, Object>> buffer = p.events.pendingEvents;
        for (int round = 0; round < 200; round++)
        {
            synchronized (buffer)
            {
                buffer.clear();
                for (int i = 0; i <= EventQueue.MAX_PENDING_EVENTS; i++)
                    buffer.add(new HashMap<>(Map.of("type", types[random.nextInt(round % 2 == 0 ? 6 : 2)], "event_id", "r" + i)));
                List<Map<String, Object>> expected = new ArrayList<>(buffer);
                expected.remove(EventPriority.evictIndex(expected));
                p.events.trimPending();
                assertEquals(expected, new ArrayList<>(buffer));
            }
        }
    }
}
