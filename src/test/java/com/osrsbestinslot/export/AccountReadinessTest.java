package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AccountReadinessTest
{
    private Client client;
    private AccountProgressCapture capture;
    private int[] vars;
    private int tick;

    @Before
    public void setup()
    {
        client = mock(Client.class);
        capture = new AccountProgressCapture();
        vars = new int[6000];
        when(client.getVarps()).thenReturn(vars);
        when(client.getTickCount()).thenAnswer(i -> tick);
        bit(12061, 2049, 11, 11);
    }

    private void bit(int id, int index, int low, int high)
    {
        VarbitComposition def = mock(VarbitComposition.class);
        when(def.getIndex()).thenReturn(index);
        when(def.getLeastSignificantBit()).thenReturn(low);
        when(def.getMostSignificantBit()).thenReturn(high);
        when(client.getVarbit(id)).thenReturn(def);
    }

    /** Six tier enums; task ids 0..5 are easy..grandmaster, 40 is elite, 64 is hard. */
    private void catalog()
    {
        int[][] tiers = {{0}, {1}, {2, 64}, {3, 40}, {4}, {5}};
        for (int t = 0; t < tiers.length; t++)
        {
            EnumComposition e = mock(EnumComposition.class);
            int[] structs = new int[tiers[t].length];
            for (int i = 0; i < structs.length; i++)
            {
                structs[i] = 9000 + tiers[t][i];
                StructComposition s = mock(StructComposition.class);
                when(s.getIntValue(1306)).thenReturn(tiers[t][i]);
                when(client.getStructComposition(structs[i])).thenReturn(s);
            }
            when(e.getIntVals()).thenReturn(structs);
            when(client.getEnum(3981 + t)).thenReturn(e);
        }
    }

    private Map<String, Object> readiness()
    {
        return AccountReadiness.derive(capture.snapshot(client));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<?, ?> m, String... path)
    {
        Object o = m;
        for (String k : path) o = ((Map<?, ?>) o).get(k);
        return (Map<String, Object>) o;
    }

    @Test
    public void missingVarDefinitionIsUnknownNeverLocked()
    {
        Map<String, Object> r = readiness();
        assertEquals("progress.readiness.v1", r.get("schema"));
        assertEquals("unknown", at(r, "unlocks", "prayer.rigour").get("state"));
        assertEquals("var_unavailable", at(r, "unlocks", "prayer.rigour").get("basis"));
        assertEquals("unknown", at(r, "tasks", "clue.watson_easy_handed_in").get("state"));
        // Loaded varps at 0 are the game's "no task done" value, not unknown; a short array is tested below.
        assertEquals("observed", at(r, "combat_achievements").get("state"));
        assertEquals(Collections.emptyList(), at(r, "combat_achievements").get("completed_task_ids"));
        Map<String, Object> req = AccountReadiness.requirement(r, "rigour_setup", Arrays.asList("prayer.rigour"));
        assertEquals("unknown", req.get("state"));
        assertEquals(Collections.singletonList("prayer.rigour"), req.get("unknown"));
    }

    @Test
    public void observedVarsBecomeNamedUnlocksAndReadiness()
    {
        bit(5451, 1429, 1, 1); bit(5452, 1429, 2, 2); bit(3909, 1050, 1, 4);
        vars[1429] = 2; vars[1050] = 16;
        Map<String, Object> r = readiness();
        assertEquals("unlocked", at(r, "unlocks", "prayer.rigour").get("state"));
        assertEquals("locked", at(r, "unlocks", "prayer.augury").get("state"));
        assertEquals("varbit:5452", at(r, "unlocks", "prayer.augury").get("source"));
        // Knight Waves raw 8 is kept, but piety stays unknown until the threshold is runtime-verified.
        assertEquals("unknown", at(r, "unlocks", "prayer.piety").get("state"));
        assertEquals(8, at(r, "unlocks", "prayer.piety").get("raw"));
        assertEquals("ready", AccountReadiness.requirement(r, "ranged_max", Arrays.asList("prayer.rigour")).get("state"));
        Map<String, Object> mixed = AccountReadiness.requirement(r, "hybrid",
            Arrays.asList("prayer.rigour", "prayer.augury", "prayer.preserve"));
        assertEquals("not_ready", mixed.get("state"));
        assertEquals(Collections.singletonList("prayer.augury"), mixed.get("missing"));
        assertEquals(Collections.singletonList("prayer.preserve"), mixed.get("unknown"));
    }

    @Test
    public void slayerTaskNoneActiveAndUnknownAreDistinct()
    {
        assertEquals("none", at(readiness(), "slayer_task").get("state"));
        vars[395] = 12; vars[394] = 40; vars[4258] = 120;
        Map<String, Object> s = at(readiness(), "slayer_task");
        assertEquals("active", s.get("state"));
        assertEquals(40, s.get("remaining"));
        assertEquals(12, s.get("target_id"));
        when(client.getVarps()).thenReturn(new int[100]);
        assertEquals("unknown", at(readiness(), "slayer_task").get("state"));
        assertEquals("unknown", at(AccountReadiness.derive(Collections.emptyMap()), "slayer_task").get("state"));
    }

    @Test
    public void individualCombatAchievementsComeFromBitsWithCatalogTier()
    {
        catalog();
        vars[3116] = (1 << 3) | 1; // tasks 0 (easy) and 3 (elite)
        vars[3117] = 1 << 8;       // task 40 (elite)
        vars[3118] = 1 << 7;       // task 71: completed bit with no catalogue row
        capture.poll(client, 0L);
        Map<String, Object> ca = at(readiness(), "combat_achievements");
        assertEquals("observed", ca.get("state"));
        assertEquals(Arrays.asList(0, 3, 40, 71), ca.get("completed_task_ids"));
        Map<?, ?> byTier = (Map<?, ?>) ca.get("completed_by_tier");
        assertEquals(Arrays.asList(3, 40), byTier.get("elite"));
        assertEquals(Collections.singletonList(0), byTier.get("easy"));
        assertEquals(Collections.emptyList(), byTier.get("hard")); // 64 is in the catalogue but not completed
        assertEquals(Collections.singletonList(71), byTier.get("uncatalogued"));
        assertEquals(2, ((Map<?, ?>) ca.get("catalog_task_totals")).get("hard"));
    }

    @Test
    public void caBitsWithoutCatalogStayIdsOnlyAndTierIsNeverGuessed()
    {
        vars[3116] = 1 << 3;
        Map<String, Object> ca = at(readiness(), "combat_achievements");
        assertEquals("observed", ca.get("state"));
        assertEquals(Collections.singletonList(3), ca.get("completed_task_ids"));
        assertEquals("unavailable", ca.get("catalog"));
        assertNull(ca.get("completed_by_tier"));
    }

    @Test
    public void duplicateCatalogTaskIdRefusesWholeCatalog()
    {
        catalog();
        StructComposition dup = mock(StructComposition.class);
        when(dup.getIntValue(1306)).thenReturn(0);
        when(client.getStructComposition(9001)).thenReturn(dup);
        assertNull(AccountProgressCapture.loadCaCatalog(client));
        when(client.getEnum(3983)).thenThrow(new IllegalStateException());
        assertNull(AccountProgressCapture.loadCaCatalog(client));
    }

    @Test
    public void shortVarpArrayMakesEveryCaTaskUnknown()
    {
        when(client.getVarps()).thenReturn(new int[5000]); // CA_TASK_COMPLETED_20 = 5673 is missing
        Map<String, Object> ca = at(readiness(), "combat_achievements");
        assertEquals("unknown", ca.get("state"));
        assertNull(ca.get("completed_task_ids"));
    }

    @Test
    public void savedSetupsAreExplicitlyUnsupportedOwnership()
    {
        Map<String, Object> s = at(readiness(), "saved_setups");
        assertEquals("unsupported_ownership", s.get("state"));
    }

    @Test
    public void deriveIsDeterministicForTheSameRawBlock()
    {
        bit(5451, 1429, 1, 1);
        vars[1429] = 2;
        Map<String, Object> raw = capture.snapshot(client);
        assertEquals(AccountReadiness.derive(raw), AccountReadiness.derive(raw));
        assertNull(AccountReadiness.derive(null));
        List<String> none = Collections.emptyList();
        assertEquals("ready", AccountReadiness.requirement(AccountReadiness.derive(raw), "nothing", none).get("state"));
    }

    /**
     * Representative account: Rigour unlocked, Augury locked, Preserve definition missing, Knight Waves raw 8,
     * active Slayer task, three completed CA tasks (one not in the catalogue). The raw block goes through a JSON
     * round trip, as the server would read it, before derivation. Compared with a committed fixture.
     */
    @Test
    public void representativeAccountMatchesCommittedReadinessFixture() throws Exception
    {
        catalog();
        bit(5451, 1429, 1, 1); bit(5452, 1429, 2, 2); bit(3909, 1050, 1, 4);
        bit(5186, 1353, 8, 8); bit(5187, 1353, 9, 9); bit(5188, 1353, 10, 10); bit(5189, 1353, 11, 11);
        bit(14815, 3791, 0, 15);
        vars[1429] = 2; vars[1050] = 16; vars[1353] = 1 << 8; vars[3791] = 115;
        vars[395] = 12; vars[394] = 40; vars[4258] = 120; vars[2096] = 0;
        vars[3116] = (1 << 3) | 1; vars[3117] = 1 << 8; vars[3118] = 1 << 7;
        capture.poll(client, 0L);
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        Map<?, ?> wire = gson.fromJson(gson.toJson(capture.snapshot(client)), Map.class);
        Map<String, Object> readiness = AccountReadiness.derive(wire);
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("readiness", readiness);
        example.put("advice_ranged_prayer", AccountReadiness.requirement(readiness, "ranged_prayer_rigour",
            Arrays.asList("prayer.rigour")));
        example.put("advice_hybrid_prayers", AccountReadiness.requirement(readiness, "hybrid_rigour_augury_piety",
            Arrays.asList("prayer.rigour", "prayer.augury", "prayer.piety")));
        String actual = gson.toJson(gson.fromJson(gson.toJson(example), Map.class)) + "\n";
        Path fixture = Paths.get("src/test/resources/progress/readiness-example.json");
        if ("1".equals(System.getenv("C5_WRITE_FIXTURES")))
        {
            Files.createDirectories(fixture.getParent());
            Files.write(fixture, actual.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8), actual);
    }
}
