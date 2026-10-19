package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ActivityRecordTest
{
    static final String SESSION = "0123456789abcdef0123456789abcdef", OTHER = "fedcba9876543210fedcba9876543210";
    static final long START = 1792085400000L, END = 1792085700000L; // 5 minutes, after the price snapshot
    ActivityPrices prices;

    @Before public void prices() throws Exception
    {
        Map<?,?> body;
        try (InputStreamReader in = new InputStreamReader(getClass().getResourceAsStream("/activity/wiki-latest-20261015T1726Z-subset.json"), StandardCharsets.UTF_8))
        { body = new Gson().fromJson(in, Map.class); }
        prices = ActivityPrices.wikiLatest(body, (String)body.get("response_sha256"), ((Number)body.get("available_at")).longValue());
    }

    static Map<String,Object> m(Object... kv)
    {
        Map<String,Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String)kv[i], kv[i+1]);
        return out;
    }

    static Map<String,Object> eat(String id, long at, int item, int qty, int after, int qtyAfter, String action, String session)
    {
        return m("type", "own_signal", "event_id", id, "gameplay_session", session, "signal_kind", "effects.consumption",
            "observed_at_ms", at, "source_sequence", at / 600, "signal_value", m("item_id_before", item, "quantity_before", qty,
            "item_id_after", after, "quantity_after", qtyAfter, "action", action, "intent_tick", 1,
            "inference", "own_menu_and_slot_change_not_confirmed_effect_or_cooldown"));
    }

    static Map<String,Object> pub(String type, String id, long ts, Object... fields)
    {
        Map<String,Object> e = m("type", type, "event_id", id, "ts", ts, "account_hash", "42", "profile_type", "STANDARD");
        e.putAll(m(fields));
        return e;
    }

    static Map<String,Object> encounter()
    {
        return m("encounter_key", SESSION + ":t1", "gameplay_session", SESSION, "start_tick", 100, "end_tick", 600,
            "start_observed_at_ms", START, "end_observed_at_ms", END, "start_provenance", "first_observed_own_hit",
            "outcome", "observed_death", "kill_credit", "unknown");
    }

    static Map<String,Object> scope()
    {
        return m("activity_type", "combat_encounter", "account_hash", "42", "profile_type", "STANDARD",
            "gameplay_session", SESSION, "encounter", encounter());
    }

    /** A representative combat encounter: food, two prayer doses, loot, a dropped vial and noise that must not count. */
    static List<Map<String,Object>> encounterEvents()
    {
        List<Map<String,Object>> e = new ArrayList<>();
        e.add(pub("combat_hit", "h1", START, "gameplay_session", SESSION));
        e.add(eat("c1", START + 40_000, 385, 1, -1, 0, "eat", SESSION));
        e.add(eat("c2", START + 70_000, 2434, 1, 139, 1, "drink", SESSION));
        e.add(eat("c3", START + 110_000, 385, 1, -1, 0, "eat", SESSION));
        e.add(eat("c4", START + 150_000, 139, 1, 141, 1, "drink", SESSION));
        e.add(eat("c5", START + 200_000, 385, 1, -1, 0, "eat", SESSION));
        e.add(pub("drop", "d1", START + 210_000, "item", 229, "qty", 1));
        e.add(pub("loot", "l1", END - 1_000, "source_type", "npc", "items", List.of(m("id", 536, "qty", 2), m("id", 561, "qty", 30)), "value", 1));
        e.add(pub("xp_gain", "x1", END - 500, "gains", List.of(m("skill", "HITPOINTS", "xp", 1200L), m("skill", "SLAYER", "xp", 900L)), "total", 2100L));
        // Noise: a duplicate delivery, another gameplay session, another profile and an event after the encounter.
        e.add(eat("c1", START + 40_000, 385, 1, -1, 0, "eat", SESSION));
        e.add(eat("z1", START + 50_000, 385, 1, -1, 0, "eat", OTHER));
        Map<String,Object> seasonal = pub("loot", "z2", START + 60_000, "source_type", "npc", "items", List.of(m("id", 536, "qty", 99)));
        seasonal.put("profile_type", "SEASONAL"); e.add(seasonal);
        e.add(eat("z3", END + 5_000, 385, 1, -1, 0, "eat", SESSION));
        return e;
    }

    @Test public void encounterJoinsSuppliesLootAndXpIntoQualifiedNet()
    {
        Map<String,Object> r = ActivityRecord.derive(scope(), encounterEvents(), prices);
        Map<?,?> v = (Map<?,?>)r.get("valuation"), res = (Map<?,?>)r.get("result"), q = (Map<?,?>)r.get("quality");
        // 3 sharks at 916 + Prayer potion(4) 9599 + Prayer potion(3) 7194 = 19541.
        assertEquals(19541L, v.get("input_cost_gp"));
        // Prayer potion(3) 7193 + Prayer potion(2) 4771 + 2 dragon bones at 3809 + 30 nature runes at 137 = 23692.
        assertEquals(23692L, v.get("output_value_gp"));
        assertEquals(4151L, res.get("net_gp"));
        assertEquals(49812L, res.get("net_gp_per_elapsed_hour"));
        assertEquals("upper_bound_excludes_unobserved_costs", res.get("net_bound"));
        assertEquals("low", res.get("confidence")); // the 5-minute XP flush is not window-aligned
        assertEquals(SESSION + ":t1", r.get("encounter_key"));
        assertEquals("observed_death", r.get("encounter_outcome"));
        assertEquals(1, q.get("duplicates_dropped"));
        assertEquals(2, q.get("identity_mismatch_excluded"));
        assertEquals(1, q.get("outside_window_excluded"));
        // The dropped vial is a transfer: valued separately, never a cost.
        assertEquals(3L, v.get("transfers_out_gp"));
        assertEquals(2100L, ((Map<?,?>)r.get("xp")).get("total"));
        assertEquals(1.98, res.get("gp_per_xp"));
        Map<?,?> time = (Map<?,?>)r.get("time");
        assertEquals(300_000L, time.get("elapsed_ms"));
        // Hit and supply events 0..210s with no >60s gap, then 89s to loot is idle: active 210s.
        assertEquals(210_000L, time.get("active_ms"));
        assertEquals(List.of("h1", "c1", "c2", "c3", "c4", "c5", "d1", "l1", "x1"), r.get("contributing_event_ids"));
    }

    @Test public void deliveryOrderDoesNotChangeTheRecord()
    {
        List<Map<String,Object>> events = encounterEvents();
        Map<String,Object> expected = ActivityRecord.derive(scope(), events, prices);
        for (long seed = 1; seed <= 5; seed++)
        {
            List<Map<String,Object>> shuffled = new ArrayList<>(events); Collections.shuffle(shuffled, new Random(seed));
            Map<String,Object> got = ActivityRecord.derive(scope(), shuffled, prices);
            assertEquals(expected.get("result"), got.get("result"));
            assertEquals(expected.get("valuation"), got.get("valuation"));
        }
    }

    @Test public void missingPriceIsUnknownNotZero()
    {
        List<Map<String,Object>> events = encounterEvents();
        events.add(pub("loot", "l2", END - 2_000, "source_type", "npc", "items", List.of(m("id", 11286, "qty", 1))));
        Map<?,?> res = (Map<?,?>)ActivityRecord.derive(scope(), events, prices).get("result");
        assertNull(res.get("net_gp")); assertNull(res.get("net_gp_per_elapsed_hour")); assertNull(res.get("gp_per_xp"));
        assertEquals("unavailable", res.get("confidence"));
        assertEquals(List.of("unpriced_flows:1"), res.get("unavailable_reasons"));
    }

    @Test public void deathWithoutTrustedDiffMakesTheNetUnavailable()
    {
        List<Map<String,Object>> events = encounterEvents();
        events.add(pub("death", "k1", END - 3_000, "death_kind", "safe"));
        Map<String,Object> r = ActivityRecord.derive(scope(), events, prices);
        assertEquals(List.of("unknown_loss:1"), ((Map<?,?>)r.get("result")).get("unavailable_reasons"));
        boolean found = false;
        for (Object f : (List<?>)r.get("flows")) if ("lost_death".equals(((Map<?,?>)f).get("flow_class")))
        { found = true; assertEquals("unknown", ((Map<?,?>)f).get("provenance")); assertNull(((Map<?,?>)f).get("qty_delta")); }
        assertTrue(found);
    }

    @Test public void bankTradeAndDropAreTransfersNotConsumption()
    {
        List<Map<String,Object>> events = List.of(
            pub("bank_session", "b1", START + 1_000, "deposited", List.of(m("id", 385, "qty", 20L)), "withdrawn", List.of(m("id", 2434, "qty", 4L)), "complete", true),
            pub("trade", "t1", START + 2_000, "given", List.of(m("id", 1513, "qty", 100)), "received", List.of()),
            pub("drop", "d1", START + 3_000, "item", 385, "qty", 1));
        Map<String,Object> r = ActivityRecord.derive(scope(), events, prices);
        for (Object f : (List<?>)r.get("flows")) assertEquals("transferred", ((Map<?,?>)f).get("flow_class"));
        Map<?,?> v = (Map<?,?>)r.get("valuation");
        assertEquals(0L, v.get("input_cost_gp")); assertEquals(0L, v.get("output_value_gp"));
        assertEquals(0L, ((Map<?,?>)r.get("result")).get("net_gp"));
        assertTrue(((Map<?,?>)r.get("inputs")).containsKey("transferred"));
        assertFalse(((Map<?,?>)r.get("inputs")).containsKey("consumed_inferred"));
    }

    @Test public void boltEnchantingUsesTransformationXpAndNamesUnobservedRunes()
    {
        Map<String,Object> s = m("activity_type", "bolt_enchanting", "account_hash", "42", "profile_type", "STANDARD",
            "gameplay_session", SESSION, "start_ms", START, "end_ms", END);
        List<Map<String,Object>> events = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            events.add(m("type", "own_signal", "event_id", "e" + i, "gameplay_session", SESSION, "signal_kind", "skills.item_transformation",
                "observed_at_ms", START + i * 2_000L, "signal_value", m("input_item_id", 9340, "output_item_id", 9243,
                "matched_quantity", 10L, "magic_xp_change", 67L, "inference", "inventory_xp_coincidence_not_confirmed_cast")));
        Map<String,Object> r = ActivityRecord.derive(s, events, prices);
        Map<?,?> res = (Map<?,?>)r.get("result");
        // 40 Diamond bolts at instant-buy 343 -> 40 Diamond bolts (e) at instant-sell 125.
        assertEquals(40L * 125 - 40L * 343, res.get("net_gp"));
        assertEquals("magic_xp_from_transformations", res.get("gp_per_xp_basis"));
        assertEquals(Math.round((40D * 125 - 40D * 343) * 100 / 268) / 100D, res.get("gp_per_xp"));
        assertEquals(List.of("runes"), res.get("unobserved_cost_classes"));
        assertEquals("medium", res.get("confidence"));
    }

    @Test public void priceSnapshotAfterTheWindowIsNotUsedRetroactively()
    {
        Map<String,Object> s = scope();
        Map<String,Object> early = encounter(); early.put("start_observed_at_ms", 1792084000000L); early.put("end_observed_at_ms", 1792084300000L);
        s.put("encounter", early);
        List<Map<String,Object>> events = List.of(eat("c1", 1792084100000L, 385, 1, -1, 0, "eat", SESSION));
        Map<String,Object> r = ActivityRecord.derive(s, events, prices);
        assertEquals(List.of("unpriced_flows:1"), ((Map<?,?>)r.get("result")).get("unavailable_reasons"));
        assertEquals("snapshot_not_yet_available", ((Map<?,?>)((List<?>)((Map<?,?>)r.get("valuation")).get("quotes")).get(0)).get("freshness"));
    }

    @Test public void openWindowAndOwnEventsWithoutSessionStayUnavailable()
    {
        Map<String,Object> s = m("activity_type", "generic", "account_hash", "42", "profile_type", "STANDARD", "start_ms", START, "end_ms", null);
        Map<String,Object> r = ActivityRecord.derive(s, encounterEvents(), prices);
        Map<?,?> res = (Map<?,?>)r.get("result");
        assertTrue(((List<?>)res.get("unavailable_reasons")).contains("window_open"));
        assertNull(res.get("net_gp"));
        // Without a caller-supplied gameplay session, own_* events cannot be joined by guesswork.
        assertTrue((Integer)((Map<?,?>)r.get("quality")).get("identity_mismatch_excluded") >= 7);
    }

    /** Consumes the real projections, after a JSON round trip, as a server read model would. */
    @Test public void consumesActualProjectedEventShapesAfterJsonReplay()
    {
        String uuid = "01234567-89ab-cdef-0123-456789abcdef";
        Map<String,Object> row = m("sequence", 7L, "kind", "effects.consumption", "source", "MenuOptionClicked:149.0+own_inventory",
            "tick", 101, "observed_at_ms", START + 1_000, "direct", false, "value", m("item_id_before", 385, "quantity_before", 1,
            "item_id_after", -1, "quantity_after", 0, "action", "eat", "intent_tick", 100,
            "inference", "own_menu_and_slot_change_not_confirmed_effect_or_cooldown"));
        Map<String,Object> signal = OwnSignalEvent.from(uuid, row);
        assertNotNull(signal);
        signal.put("event_id", "s1"); // assigned by the shared emitter, never by the domain
        Map<String,Object> loot = PublicTransport.event(pub("loot", "l1", START + 2_000, "source_type", "npc",
            "items", List.of(m("id", 536, "qty", 2)), "value", 7618));
        assertNotNull(loot);
        Gson gson = new Gson(); List<Map<String,Object>> replay = new ArrayList<>();
        for (Map<String,Object> e : List.of(signal, loot)) replay.add(gson.fromJson(gson.toJson(e), Map.class));
        Map<String,Object> s = m("activity_type", "combat_encounter", "account_hash", "42", "profile_type", "STANDARD",
            "gameplay_session", SESSION, "start_ms", START, "end_ms", END);
        Map<String,Object> r = ActivityRecord.derive(s, replay, prices);
        assertEquals(List.of("s1", "l1"), r.get("contributing_event_ids"));
        Map<?,?> v = (Map<?,?>)r.get("valuation");
        assertEquals(916L, v.get("input_cost_gp")); assertEquals(2L * 3809, v.get("output_value_gp"));
    }

    @Test public void replayAcceptsSnapshotRowsAndWireEventsAndSkipsInvalidRows()
    {
        String uuid = "01234567-89ab-cdef-0123-456789abcdef";
        List<String> lines = List.of(
            "{\"collector_session\":\"" + uuid + "\",\"event_id\":\"r1\",\"sequence\":3,\"kind\":\"effects.consumption\",\"source\":\"MenuOptionClicked:149.0+own_inventory\",\"tick\":101,\"observed_at_ms\":" + (START + 1000) + ",\"direct\":false,\"value\":{\"item_id_before\":385,\"quantity_before\":1,\"item_id_after\":-1,\"quantity_after\":0,\"action\":\"eat\",\"intent_tick\":100,\"inference\":\"own_menu_and_slot_change_not_confirmed_effect_or_cooldown\"}}",
            // A claimed consumption whose slot did not change fails the projection and is skipped.
            "{\"collector_session\":\"" + uuid + "\",\"event_id\":\"r2\",\"sequence\":4,\"kind\":\"effects.consumption\",\"source\":\"MenuOptionClicked:149.0+own_inventory\",\"tick\":102,\"observed_at_ms\":" + (START + 2000) + ",\"direct\":false,\"value\":{\"item_id_before\":385,\"quantity_before\":1,\"item_id_after\":385,\"quantity_after\":1,\"action\":\"eat\",\"intent_tick\":101,\"inference\":\"own_menu_and_slot_change_not_confirmed_effect_or_cooldown\"}}",
            "{\"type\":\"loot\",\"event_id\":\"w1\",\"ts\":" + (START + 3000) + ",\"account_hash\":\"42\",\"profile_type\":\"STANDARD\",\"source_type\":\"npc\",\"items\":[{\"id\":536,\"qty\":1}]}");
        List<Map<String,Object>> events = ActivityReplay.read(lines);
        assertEquals(2, events.size());
        Map<String,Object> s = m("activity_type", "combat_encounter", "account_hash", "42", "profile_type", "STANDARD",
            "gameplay_session", SESSION, "start_ms", (double)START, "end_ms", (double)END);
        Map<String,Object> r = ActivityRecord.derive(s, events, prices);
        assertEquals(List.of("r1", "w1"), r.get("contributing_event_ids"));
        assertEquals(3809L - 916L, ((Map<?,?>)r.get("result")).get("net_gp"));
    }

    /** Writes the derived example for the handoff; the build directory is not committed. */
    @Test public void writeDerivedExample() throws Exception
    {
        Map<String,Object> r = ActivityRecord.derive(scope(), encounterEvents(), prices);
        Path out = Paths.get("build", "activity-example", "combat-encounter-record.json");
        Files.createDirectories(out.getParent());
        Files.write(out, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(r).getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.size(out) > 0);
    }
}
