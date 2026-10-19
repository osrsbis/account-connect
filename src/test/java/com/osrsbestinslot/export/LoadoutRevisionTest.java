package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import net.runelite.api.Item;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class LoadoutRevisionTest
{
    private static final int HEAD = 0, WEAPON = 3, BODY = 4, HAIR = 8, AMMO = 13;

    private static Map<Integer, Integer> gear(int... slotItem)
    {
        Map<Integer, Integer> m = new TreeMap<>();
        for (int i = 0; i < slotItem.length; i += 2) m.put(slotItem[i], slotItem[i + 1]);
        return m;
    }

    /** Shape of C4 WealthLedger.owns at da11975: answer plus location lists. Absent item = unknown answer. */
    private static java.util.function.IntFunction<Map<String, Object>> c4(Map<Integer, String> answers)
    {
        return id -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item_id", id);
            m.put("answer", answers.getOrDefault(id, "unknown"));
            m.put("unknown_locations", java.util.Collections.singletonList("bank"));
            return m;
        };
    }

    @Test
    public void firstObservationIsBaselineAndUnknownIsNotEmpty()
    {
        LoadoutRevision l = new LoadoutRevision("gen-a");
        assertNull(l.activeAt(100));
        assertNull(l.observe(null, 100, 1L)); // container unavailable: no revision, still unknown
        assertNull(l.activeAt(100));
        Map<String, Object> r = l.observe(gear(WEAPON, 4151, BODY, 10551), 101, 2L);
        assertEquals("baseline", r.get("provenance"));
        assertEquals(1, r.get("revision_seq"));
        assertEquals("loadout.revision.v1", r.get("schema"));
        assertEquals(4151, ((Map<?, ?>) r.get("slots")).get("weapon"));
        assertNull(r.get("previous_content_id"));
    }

    @Test
    public void onlyItemIdChangesMakeRevisionsAndOrderIsMonotonic()
    {
        LoadoutRevision l = new LoadoutRevision("gen-a");
        Map<String, Object> a = l.observe(gear(WEAPON, 4151), 10, 1L);
        assertNull(l.observe(gear(WEAPON, 4151), 11, 2L)); // same map, quantity-only change is invisible here
        Map<String, Object> b = l.observe(gear(WEAPON, 12006), 20, 3L);
        assertEquals("observed_change", b.get("provenance"));
        assertEquals(2, b.get("revision_seq"));
        assertEquals(a.get("content_id"), b.get("previous_content_id"));
        assertSame(a, l.activeAt(15));
        assertSame(b, l.activeAt(20));
        assertNull(l.activeAt(9));
    }

    @Test
    public void contentIdIsStableAcrossGenerationsAndIgnoresBodyKitAndEmpties()
    {
        Map<String, Object> a = new LoadoutRevision("gen-a").observe(gear(HEAD, 10828, WEAPON, 4151), 1, 1L);
        Map<String, Object> b = new LoadoutRevision("gen-b").observe(gear(WEAPON, 4151, HEAD, 10828, HAIR, 3, AMMO, -1), 99, 9L);
        assertEquals(a.get("content_id"), b.get("content_id"));
        assertNotEquals(a.get("generation"), b.get("generation"));
        Map<String, Object> c = new LoadoutRevision("gen-c").observe(gear(HEAD, 4151, WEAPON, 10828), 1, 1L);
        assertNotEquals(a.get("content_id"), c.get("content_id")); // same items in other slots is other gear
    }

    @Test
    public void gapMakesGearUnknownUntilTheNextBaseline()
    {
        LoadoutRevision l = new LoadoutRevision("gen-a");
        Map<String, Object> a = l.observe(gear(WEAPON, 4151), 10, 1L);
        l.gap(30);
        assertSame(a, l.activeAt(29));
        assertNull(l.activeAt(31)); // gear during a gap is not assumed unchanged
        Map<String, Object> b = l.observe(gear(WEAPON, 4151), 40, 2L);
        assertEquals("baseline", b.get("provenance")); // same gear after a gap is re-proved, not a change
        assertEquals(a.get("content_id"), b.get("content_id"));
        assertNull(l.activeAt(35));
        assertSame(b, l.activeAt(41));
    }

    @Test
    public void evictionKeepsSequenceAndOldTicksBecomeUnknown()
    {
        LoadoutRevision l = new LoadoutRevision("gen-a");
        for (int i = 0; i < LoadoutRevision.MAX_REVISIONS + 5; i++) l.observe(gear(WEAPON, 1000 + i), i, i);
        assertEquals(LoadoutRevision.MAX_REVISIONS, l.revisions().size());
        assertEquals(5, l.evicted());
        assertEquals(6, l.revisions().get(0).get("revision_seq"));
        assertNull(l.activeAt(2));
    }

    @Test
    public void ownershipUsesC4AnswersAndNeverTurnsUnknownIntoNotOwned()
    {
        Map<String, Object> r = new LoadoutRevision("gen-a").observe(gear(WEAPON, 4151, BODY, 10551, HEAD, 10828), 1, 1L);
        Map<Integer, String> answers = new HashMap<>();
        answers.put(4151, "yes");
        answers.put(10551, "no_in_observed_locations");
        Map<String, Object> o = LoadoutRevision.ownership(r, c4(answers));
        Map<?, ?> slots = (Map<?, ?>) o.get("slots");
        assertEquals("owned_observed", ((Map<?, ?>) slots.get("weapon")).get("current_holdings"));
        assertEquals("not_seen_in_observed", ((Map<?, ?>) slots.get("body")).get("current_holdings"));
        assertEquals("unknown", ((Map<?, ?>) slots.get("head")).get("current_holdings"));
        assertEquals("unavailable", LoadoutRevision.ownership(r, null).get("holdings_source"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void generationIsRequired()
    {
        new LoadoutRevision("");
    }

    @Test
    public void slotsOfKeepsUnavailableDistinctFromEmpty()
    {
        assertNull(LoadoutRevision.slotsOf(null));
        assertTrue(LoadoutRevision.slotsOf(new Item[14]).isEmpty());
        Item[] worn = new Item[14];
        worn[WEAPON] = new Item(4151, 1);
        worn[AMMO] = new Item(11212, 0); // zero quantity is not worn
        worn[HAIR] = new Item(-1, 0);
        assertEquals(gear(WEAPON, 4151), LoadoutRevision.slotsOf(worn));
    }

    /**
     * C2 fixture: baseline -> weapon swap -> logout gap -> same gear re-proved by baseline. Encounter queries
     * at ticks before, inside and after the gap show known versus unknown equipment.
     */
    @Test
    public void representativeSessionMatchesCommittedFixture() throws Exception
    {
        LoadoutRevision l = new LoadoutRevision("caller-generation-1");
        l.observe(gear(HEAD, 10828, BODY, 10551, WEAPON, 4151, AMMO, 11212), 1000, 1760880000000L);
        l.observe(gear(HEAD, 10828, BODY, 10551, WEAPON, 12006, AMMO, 11212), 1010, 1760880006000L);
        l.gap(1100);
        l.observe(gear(HEAD, 10828, BODY, 10551, WEAPON, 12006, AMMO, 11212), 1300, 1760880180000L);
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("revisions", l.revisions());
        Map<String, Object> queries = new LinkedHashMap<>();
        for (int t : new int[]{999, 1005, 1050, 1200, 1300})
        {
            Map<String, Object> rev = l.activeAt(t);
            queries.put("tick_" + t, rev == null ? "unknown" : rev.get("revision_seq"));
        }
        example.put("active_at", queries);
        Map<Integer, String> answers = new HashMap<>();
        answers.put(12006, "yes");
        answers.put(10551, "no_in_observed_locations");
        example.put("ownership_of_latest", LoadoutRevision.ownership(l.revisions().get(2), c4(answers)));
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        String actual = gson.toJson(example) + "\n";
        Path fixture = Paths.get("src/test/resources/progress/loadout-revisions-example.json");
        if ("1".equals(System.getenv("C5_WRITE_FIXTURES")))
        {
            Files.createDirectories(fixture.getParent());
            Files.write(fixture, actual.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8), actual);
    }
}
