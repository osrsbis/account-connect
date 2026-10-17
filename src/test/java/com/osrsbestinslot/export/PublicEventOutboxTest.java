package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import static org.junit.Assert.*;

public class PublicEventOutboxTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private Path dir;
    private PublicEventOutbox box;
    private final AtomicLong now = new AtomicLong(1792234800000L);
    private final PublicEventOutbox.Context owner = new PublicEventOutbox.Context(TOKEN, "123", "STANDARD", "profile-a");
    @Before public void setup() throws Exception { dir = Files.createTempDirectory("public-outbox"); box = fresh(); }
    @After public void close() throws Exception
    {
        box.close();
        try (java.util.stream.Stream<Path> paths = Files.walk(dir))
        { for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p); }
    }
    private PublicEventOutbox fresh() { return new PublicEventOutbox(dir, new Gson(), now::get); }
    private Map<String,Object> event(String id)
    {
        Map<String,Object> e = new LinkedHashMap<>(); e.put("event_id", id); e.put("type", "ge_progress");
        e.put("account_hash", "123"); e.put("ts", now.get()); e.put("item", 2); e.put("qty", 1);
        e.put("rsn", "OWN_NAME_NOT_NEEDED_ON_DISK");
        e.put("other_player_name", "must-never-reach-disk"); e.put("__delivery_token_fp", "internal-binding"); return e;
    }
    @Test public void forcedRowsSurviveCrashAndOnlyExactDurableReceiptsRemoveThem() throws Exception
    {
        assertTrue(box.append(owner, "own-history-v1", event(A))); assertTrue(box.append(owner, "own-history-v1", event(B)));
        box.crashForTest(); box = fresh();
        assertTrue(box.pending(null, "own-history-v1", 100).isEmpty());
        List<Map<String,Object>> rows = box.pending(owner, "own-history-v1", 100); assertEquals(2, rows.size());
        box.settle(owner, rows, EventQueueProtocol.receipts("{\"ok\":true,\"stored\":2}", rows));
        assertEquals(2, box.size());
        String receipt = "{\"receipt_version\":1,\"results\":[{\"index\":0,\"event_id\":\""+A+"\",\"status\":\"queued\",\"broker_ack\":true}]}";
        box.settle(owner, rows, EventQueueProtocol.receipts(receipt, rows)); box.crashForTest(); box = fresh();
        rows = box.pending(owner, "own-history-v1", 100); assertEquals(1, rows.size()); assertEquals(B, rows.get(0).get("event_id"));
    }
    @Test public void localDiskContainsOnlyProjectedRowsAndNoToken() throws Exception
    {
        assertTrue(box.append(owner, "ge-v1", event(A)));
        try (java.util.stream.Stream<Path> files = Files.walk(dir))
        {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
            {
                String text = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                for (String forbidden : Arrays.asList(TOKEN, "must-never-reach-disk", "internal-binding", "profile-a", "OWN_NAME_NOT_NEEDED_ON_DISK"))
                    assertFalse(forbidden, text.contains(forbidden));
            }
        }
        Map<String,Object> other = event(B); other.put("account_hash", "456"); assertFalse(box.append(owner, "ge-v1", other));
        other = event(B); other.put("type", "fh_batch"); assertFalse(box.append(owner, "own-history-v1", other));
    }
    @Test public void logoutRetainsButModeAndProfileCannotRecoverPriorRows() throws Exception
    {
        box.append(owner, "ge-v1", event(A)); box.close(); box = fresh();
        assertEquals(1, box.pending(owner, "ge-v1", 100).size());
        PublicEventOutbox.Context league = new PublicEventOutbox.Context(TOKEN, "123", "LEAGUE", "profile-a");
        assertTrue(box.pending(league, "ge-v1", 100).isEmpty());
        Map<String,Object> e = event(B); e.put("profile_type", "LEAGUE"); assertTrue(box.append(league, "ge-v1", e));
        PublicEventOutbox.Context profile = new PublicEventOutbox.Context(TOKEN, "123", "LEAGUE", "profile-b");
        assertTrue(box.pending(profile, "ge-v1", 100).isEmpty());
    }
    @Test public void explicitErasurePreventsRestartReplayButDoesNotMarkAnotherLiveIdentity() throws Exception
    {
        box.append(owner, "ge-v1", event(A));
        PublicEventOutbox sibling = fresh();
        PublicEventOutbox.Context other = new PublicEventOutbox.Context(TOKEN, "456", "STANDARD", "profile-b");
        Map<String,Object> e = event(B); e.put("account_hash", "456");
        try
        {
            sibling.append(other, "ge-v1", e);
            box.settle(owner, Arrays.asList(event(A)), Arrays.asList(EventQueueProtocol.Outcome.ERASED));
            box.crashForTest(); box = fresh();
            assertTrue(box.pending(owner, "ge-v1", 100).isEmpty());
            assertEquals(1, sibling.pending(other, "ge-v1", 100).size());
        }
        finally { sibling.close(); }
    }
    @Test public void boundsHaveExplicitLossCountersAndAgeNeverBecomesServerRetention() throws Exception
    {
        box.close(); box = new PublicEventOutbox(dir, new Gson(), now::get, new EventSpool.Limits(4096, 1, 1000));
        box.append(owner, "ge-v1", event(A)); box.append(owner, "ge-v1", event(B));
        assertEquals(1, box.size()); assertEquals(1, box.counters.dropped.get());
        now.addAndGet(1001); assertTrue(box.pending(owner, "ge-v1", 100).isEmpty()); assertEquals(2, box.counters.dropped.get());
    }
    @Test public void narrowingGrantPausesWithoutAcknowledgingBroaderRows() throws Exception
    {
        Map<String,Object> e = event(A); e.put("type", "xp_gain"); e.put("total", 42);
        assertTrue(box.append(owner, "own-history-v1", e));
        assertTrue(box.pending(owner, "ge-v1", 100).isEmpty()); assertEquals(1, box.size());
        assertEquals(1, box.pending(owner, "own-history-v1", 100).size());
    }
    @Test public void failedCompactionWriterRecoversWithoutRequiringAClientRestart() throws Exception
    {
        box.append(owner, "ge-v1", event(A)); box.append(owner, "ge-v1", event(B));
        java.lang.reflect.Field field = PublicEventOutbox.class.getDeclaredField("journal"); field.setAccessible(true);
        EventSpool journal = (EventSpool)field.get(box);
        journal.ack(Collections.singletonList(A));
        journal.setReopenerForTest(path -> { throw new java.io.IOException("temporary reopen failure"); });
        try { journal.compactNow(); fail("fault must surface"); }
        catch (java.io.IOException expected) { assertTrue(journal.isFailed()); }
        List<Map<String,Object>> recovered = box.pending(owner, "ge-v1", 100);
        assertEquals(1, recovered.size()); assertEquals(B, recovered.get(0).get("event_id"));
        assertTrue(box.append(owner, "ge-v1", event(A))); assertEquals(2, box.size());
    }
    @Test public void observedErasureMarkerDeletesOwnPayloadInsteadOfOnlyPausingIt() throws Exception
    {
        box.append(owner, "ge-v1", event(A));
        Path identity = dir.resolve("osrsbis/public-events-v1").resolve(owner.identity);
        EventSpool.purgeIdentityDir(identity, now::get);
        assertTrue(box.pending(owner, "ge-v1", 100).isEmpty()); assertEquals(0, box.size());
        assertFalse(box.append(owner, "ge-v1", event(B))); // retained marker forbids stale-grant writes
        try (java.util.stream.Stream<Path> paths = Files.walk(identity))
        { assertFalse(paths.anyMatch(path -> path.toString().endsWith(".spl"))); }
    }
    @Test public void coldWithdrawalFindsIdleIdentitiesThatFellOutOfTheBoundedIndex() throws Exception
    {
        List<PublicEventOutbox> writers = new ArrayList<>();
        try
        {
            for (int i = 0; i <= EventSpool.MAX_INDEX; i++)
            {
                PublicEventOutbox writer = fresh(); writers.add(writer);
                PublicEventOutbox.Context context = new PublicEventOutbox.Context(TOKEN, "123", "STANDARD", "profile-" + i);
                assertTrue(writer.append(context, "ge-v1", event(A)));
            }
        }
        finally { for (PublicEventOutbox writer : writers) writer.close(); }
        assertEquals(EventSpool.MAX_INDEX, EventSpool.readIndex(dir.resolve("osrsbis/public-events-v1")).size());
        box.purge();
        try (java.util.stream.Stream<Path> paths = Files.walk(dir))
        { assertFalse(paths.anyMatch(path -> path.toString().endsWith(".spl"))); }
    }
}
