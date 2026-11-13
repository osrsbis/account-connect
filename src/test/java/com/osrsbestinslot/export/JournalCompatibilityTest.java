package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * Journal directories exactly as 0.7.69 (c66fb61) code leaves them, in src/test/resources/spool-c66fb61, load under
 * this code and replay the same pending rows c66fb61 itself replayed from them (expected.json), with nothing acked
 * or quarantined coming back. The fixture holds an active.json index with index.lock in the staff and public roots,
 * quarantine.jsonl files, a .corrupt shard, an unopened shard with middle corruption, an end-recorded .next beside its
 * .spl, and a lone end-recorded .next left by a load that died between deleting the .spl and moving the .next.
 */
public class JournalCompatibilityTest
{
    private static final long REPLAY_MS = 1792234800000L + 2000;
    private Path data;
    private JsonObject expected;

    @Before public void copyFixture() throws Exception
    {
        Path fixture = Paths.get(getClass().getResource("/spool-c66fb61").toURI());
        expected = new JsonParser().parse(new String(Files.readAllBytes(fixture.resolve("expected.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        data = Files.createTempDirectory("journal-compat");
        copyTree(fixture.resolve("data"), data);
    }

    private static void copyTree(Path from, Path into) throws Exception
    {
        try (java.util.stream.Stream<Path> paths = Files.walk(from))
        {
            for (Path p : (Iterable<Path>) paths::iterator)
            {
                Path to = into.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to); else Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @After public void deleteCopy() throws Exception
    {
        try (java.util.stream.Stream<Path> paths = Files.walk(data))
        { for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p); }
    }

    private Path staffRoot() { return data.resolve("osrsbis/spool"); }
    private Path publicRoot() { return data.resolve("osrsbis/public-events-v1"); }
    private String text(String key) { return expected.get(key).getAsString(); }

    private Set<String> expectedRows(String key)
    {
        Set<String> out = new TreeSet<>();
        for (JsonElement e : expected.getAsJsonArray(key))
        {
            JsonObject r = e.getAsJsonObject();
            out.add(r.get("id").getAsString() + "|" + r.get("type").getAsString() + "|" + r.get("created_ms").getAsLong() + "|" + r.get("event").getAsString());
        }
        return out;
    }

    private Set<String> staffReplay(String identity, EventSpool.Counters counters) throws Exception
    {
        PublicEventOutbox staff = new PublicEventOutbox(data, () -> REPLAY_MS, counters);
        try
        {
            assertTrue(staff.openStaff(identity));
            Set<String> out = new TreeSet<>();
            for (EventSpool.Record r : staff.journal.pending()) out.add(r.eventId + "|" + r.type + "|" + r.createdMs + "|" + r.eventJson);
            return out;
        }
        finally { staff.close(); }
    }

    private Set<String> publicReplay() throws Exception
    {
        List<String> o = new ArrayList<>();
        for (JsonElement e : expected.getAsJsonArray("public_owner")) o.add(e.getAsString());
        PublicEventOutbox box = new PublicEventOutbox(data, new Gson(), () -> REPLAY_MS);
        try
        {
            Set<String> out = new TreeSet<>();
            for (Map<String, Object> e : box.pending(new PublicEventOutbox.Context(o.get(0), o.get(1), o.get(2), o.get(3)), "ge-v1", 100))
                out.add((String) e.get("event_id"));
            return out;
        }
        finally { box.close(); }
    }

    private Set<String> ids(Set<String> rows)
    {
        Set<String> out = new TreeSet<>();
        for (String r : rows) out.add(r.substring(0, r.indexOf('|')));
        return out;
    }

    private void assertNothingAckedReturns(Set<String> replayedIds)
    {
        for (JsonElement e : expected.getAsJsonArray("acked_or_quarantined")) assertFalse(e.getAsString(), replayedIds.contains(e.getAsString()));
    }

    /**
     * DEL-5: the identity directories replace the active.json index; a leftover index is deleted (its lock file stays)
     * and no row is lost.
     */
    @Test public void anIndexedC66fb61DirectoryReplaysTheSameRowsAndOnlyItsIndexIsDeleted() throws Exception
    {
        for (Path root : Arrays.asList(staffRoot(), publicRoot()))
            assertTrue(Files.exists(root.resolve("active.json")) && Files.exists(root.resolve("index.lock")));
        assertEquals(new HashSet<>(Arrays.asList(text("staff_a_identity"), text("staff_b_identity"))), new HashSet<>(EventSpool.identities(staffRoot())));

        Set<String> a = staffReplay(text("staff_a_identity"), new EventSpool.Counters());
        Set<String> b = staffReplay(text("staff_b_identity"), new EventSpool.Counters());
        Set<String> pub = publicReplay();
        assertEquals(expectedRows("staff_a"), a);
        assertEquals(expectedRows("staff_b"), b);
        Set<String> expectedPublic = new TreeSet<>();
        for (JsonElement e : expected.getAsJsonArray("public")) expectedPublic.add(e.getAsString());
        assertEquals(expectedPublic, pub);
        assertNothingAckedReturns(ids(a)); assertNothingAckedReturns(ids(b)); assertNothingAckedReturns(pub);

        for (Path root : Arrays.asList(staffRoot(), publicRoot()))
        {
            assertFalse(root + "/active.json", Files.exists(root.resolve("active.json")));
            assertFalse(Files.exists(root.resolve("active.json.next")));
            assertTrue("index.lock keeps its inode for an older client", Files.exists(root.resolve("index.lock")));
        }
        assertEquals(new HashSet<>(Arrays.asList(text("staff_a_identity"), text("staff_b_identity"))), new HashSet<>(EventSpool.identities(staffRoot())));
        // A second load of the same directory, now without an index, still replays the same rows.
        assertEquals(expectedRows("staff_a"), staffReplay(text("staff_a_identity"), new EventSpool.Counters()));
        assertEquals(expectedPublic, publicReplay());
    }

    private List<String> files(String suffix) throws Exception
    {
        try (java.util.stream.Stream<Path> paths = Files.walk(data))
        {
            List<String> out = new ArrayList<>();
            for (Path p : (Iterable<Path>) paths::iterator) if (p.getFileName().toString().endsWith(suffix)) out.add(data.relativize(p).toString());
            return out;
        }
    }

    /**
     * DEL-6: quarantine.jsonl and the .corrupt shard are deleted, the .next beside its .spl is discarded, the lone
     * end-recorded .next becomes the shard and replays, the unopened corrupt shard is counted, and the old end records
     * are skipped. The pending rows are the ones c66fb61 replayed.
     */
    @Test public void forensicFilesAndCompactionLeftoversOfC66fb61ReplayTheSameRowsAndAreCleanedUp() throws Exception
    {
        Path b = staffRoot().resolve(text("staff_b_identity"));
        assertEquals(2, files("quarantine.jsonl").size());
        assertEquals(1, files(".corrupt").size());
        assertEquals("one .next beside its .spl, one alone", 2, files(".spl.next").size());
        try (java.util.stream.Stream<Path> inB = Files.list(b))
        {
            assertFalse("B holds only its lone .next", inB.anyMatch(p -> p.getFileName().toString().endsWith(".spl")));
        }

        EventSpool.Counters ca = new EventSpool.Counters(), cb = new EventSpool.Counters();
        Set<String> a = staffReplay(text("staff_a_identity"), ca);
        assertEquals(expectedRows("staff_a"), a);
        assertEquals("the unopened shard with middle corruption is counted", 1L, ca.corrupt.get());
        Set<String> bRows = staffReplay(text("staff_b_identity"), cb);
        assertEquals("the lone end-recorded .next is replayed", expectedRows("staff_b"), bRows);
        assertEquals(0L, cb.corrupt.get());
        Set<String> expectedPublic = new TreeSet<>();
        for (JsonElement e : expected.getAsJsonArray("public")) expectedPublic.add(e.getAsString());
        Set<String> pub = publicReplay();
        assertEquals(expectedPublic, pub);
        assertNothingAckedReturns(ids(a)); assertNothingAckedReturns(ids(bRows)); assertNothingAckedReturns(pub);

        assertEquals(Collections.emptyList(), files("quarantine.jsonl"));
        assertEquals(Collections.emptyList(), files("quarantine.jsonl.next"));
        assertEquals(Collections.emptyList(), files(".corrupt"));
        assertEquals(Collections.emptyList(), files(".spl.next"));
        // Loaded again with nothing left over, the same rows replay and nothing more is counted.
        EventSpool.Counters again = new EventSpool.Counters();
        assertEquals(expectedRows("staff_a"), staffReplay(text("staff_a_identity"), again));
        assertEquals(expectedRows("staff_b"), staffReplay(text("staff_b_identity"), again));
        assertEquals(0L, again.corrupt.get());
    }

    /**
     * DEL-6: c66fb61 code died between forcing a compaction's end-recorded .next and moving it, and the .spl was
     * then damaged mid-file. The clean .next replays, exactly as c66fb61 replayed it, and the shard is counted.
     */
    @Test public void aCorruptSplBesideACleanEndRecordedNextReplaysTheNext() throws Exception
    {
        Path fixture = Paths.get(getClass().getResource("/spool-c66fb61-corrupt-spl").toURI());
        expected = new JsonParser().parse(new String(Files.readAllBytes(fixture.resolve("expected.json")), StandardCharsets.UTF_8)).getAsJsonObject();
        deleteCopy();	// this case has its own fixture
        data = Files.createTempDirectory("journal-compat");
        copyTree(fixture.resolve("data"), data);
        Path c = staffRoot().resolve(text("staff_c_identity"));
        try (java.util.stream.Stream<Path> files = Files.list(c))
        {
            for (Path p : (Iterable<Path>) files::iterator)
            {
                if (p.toString().endsWith(".spl")) assertEquals(EventSpool.ScanStatus.CORRUPT, EventSpool.scan(p).status);
                if (p.toString().endsWith(".spl.next")) assertEquals(EventSpool.ScanStatus.CLEAN, EventSpool.scan(p).status);
            }
        }

        EventSpool.Counters counters = new EventSpool.Counters();
        PublicEventOutbox staff = new PublicEventOutbox(data, () -> REPLAY_MS, counters);
        Set<String> rows = new TreeSet<>();
        try
        {
            assertTrue(staff.openStaff(text("staff_c_identity")));
            for (EventSpool.Record r : staff.journal.pending()) rows.add(r.eventId + "|" + r.type + "|" + r.createdMs + "|" + r.eventJson);
        }
        finally { staff.close(); }
        assertEquals(expectedRows("staff_c"), rows);
        assertNothingAckedReturns(ids(rows));
        assertEquals("the corrupt shard is still counted", 1L, counters.corrupt.get());
        try (java.util.stream.Stream<Path> files = Files.list(c))
        {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".spl.next") || p.toString().endsWith(".corrupt")));
        }
        assertEquals(expectedRows("staff_c"), staffReplay(text("staff_c_identity"), counters));
        assertEquals("counted once", 1L, counters.corrupt.get());
    }
}
