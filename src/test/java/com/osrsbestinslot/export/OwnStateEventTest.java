package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnStateEventTest
{
    static final long AT = 1792240000000L;
    static Map<String,Object> goalEvent()
    {
        Map<String,Object> goals = new LinkedHashMap<>();
        for (String skill : OwnStateEvent.SKILLS)
            goals.put(skill, new LinkedHashMap<>(Map.of("start_xp", 0, "end_xp", 100, "status", "configured")));
        return from("skills.goal_xp", goals);
    }
    static Map<String,Object> from(String kind, Map<String,Object> values)
    {
        Map<String,Object> v = new LinkedHashMap<>(values); v.put("observation", "baseline");
        return OwnStateEvent.from("12345678-1234-1234-1234-123456789abc",
            Map.of("kind",kind,"value",v,"sequence",1,"tick",42,"observed_at_ms",AT));
    }
    static Map<String,Object> bankEvent()
    {
        List<Map<String,Object>> tabs = new ArrayList<>();
        for (int i = 0; i < 9; i++) tabs.add(new LinkedHashMap<>(Map.of("tab",i+1,"start_slot",i==0?0:2,
            "slot_count",i==0?2:0,"coins",i==0?100:0,"platinum_tokens",i==0?2:0,"currency_gp_equivalent",i==0?2100:0)));
        return from("storage.bank_tab_currency", new LinkedHashMap<>(Map.of("status","observed_cache_ranges",
            "container_observed_at_ms",AT-600,"tabs",tabs,"untabbed",Map.of("start_slot",2,"slot_count",0,
                "coins",0,"platinum_tokens",0,"currency_gp_equivalent",0))));
    }
    static Map<String,Object> pouchEvent()
    {
        List<Map<String,Object>> slots = new ArrayList<>();
        for (int i = 0; i < 6; i++) slots.add(new LinkedHashMap<>(Map.of("slot",i,"type_raw",i<2?1:0,
            "amount",i<2?Integer.MAX_VALUE:0,"status",i<2?"observed_cached":"observed_empty")));
        slots.get(0).put("item",556); slots.get(1).put("item",556);
        return from("storage.rune_pouch", new LinkedHashMap<>(Map.of("possession","carried",
            "pouch_item_ids",List.of(12791),"slots",slots,"observed_items",List.of(Map.of("id",999,"qty",999)))));
    }

    @Test public void threeTypedFamiliesSurviveJsonReplayWithoutPrivateExtrasOrFalseGains()
    {
        Gson gson = new Gson();
        for (Map<String,Object> event : List.of(goalEvent(),pouchEvent(),bankEvent()))
        {
            assertNotNull(event); event.put("event_id","a".repeat(32)); event.put("account_hash","123"); event.put("ts",AT);
            event.put("other_player","NEVER_SEND"); ((Map<String,Object>)event.get("state_value")).put("raw_text","NEVER_SEND");
            Map<String,Object> projected = PublicTransport.event(event); assertNotNull(projected);
            assertFalse(gson.toJson(projected).contains("NEVER_SEND"));
            Map<String,Object> replay = PublicTransport.event(gson.fromJson(gson.toJson(projected),Map.class));
            assertNotNull(replay); assertEquals(projected.get("state_value"),replay.get("state_value"));
            assertTrue(EventQueueProtocol.allows("own-state-v1",projected));
            assertFalse(EventQueueProtocol.allows("own-combat-v1",projected));
            assertFalse(EventQueueProtocol.allows("own-history-v1",projected));
        }
        Map<?,?> pouch = (Map<?,?>)pouchEvent().get("state_value");
        assertEquals(List.of(Map.of("id",556L,"qty",Integer.MAX_VALUE*2L)),pouch.get("observed_items"));
        assertEquals(false,bankEvent().get("direct")); assertEquals("baseline",bankEvent().get("observation"));
    }

    @Test public void contradictoryOrUnknownSourcesNeverBecomeAcceptedStatePayloads()
    {
        Map<String,Object> goal = goalEvent(); ((Map<?,?>)((List<?>)((Map<?,?>)goal.get("state_value")).get("goals")).get(0)).clear();
        assertNull(OwnStateEvent.wire(goal));
        Map<String,Object> bank = bankEvent(); bank.put("direct",true); assertNull(OwnStateEvent.wire(bank));
        bank = bankEvent(); ((Map<String,Object>)bank.get("state_value")).put("container_observed_at_ms",AT+1);
        assertNull(OwnStateEvent.wire(bank));
        Map<String,Object> pouch = pouchEvent(); ((Map<String,Object>)pouch.get("state_value")).put("pouch_item_ids",List.of(995));
        assertNull(OwnStateEvent.wire(pouch));
        goal = goalEvent(); goal.put("source","NEVER_SEND"); assertNull(PublicTransport.event(goal));
        assertNull(from("unreviewed.family",Map.of()));
    }

    @Test public void missingValuesStayUnknownAndNarrowerGrantsCannotDiscardTheirDurableRows() throws Exception
    {
        Map<String,Object> e = from("storage.rune_pouch",Map.of("possession","inventory_unavailable"));
        assertEquals(Map.of("contents_semantics","client_cached_slots","complete",false,"possession","inventory_unavailable"),e.get("state_value"));
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("own-state-journal");
        PublicEventOutbox box = new PublicEventOutbox(dir,new Gson(),()->AT);
        PublicEventOutbox.Context owner = new PublicEventOutbox.Context("a".repeat(32),"123","STANDARD","profile");
        try
        {
            e.put("event_id","b".repeat(32)); e.put("account_hash","123"); e.put("ts",AT);
            assertFalse(box.append(owner,"own-history-v1",e)); assertTrue(box.append(owner,"own-state-v1",e));
            box.crashForTest(); box = new PublicEventOutbox(dir,new Gson(),()->AT);
            assertTrue(box.pending(owner,"own-combat-v1",10).isEmpty()); assertEquals(1,box.size());
            assertEquals(1,box.pending(owner,"own-state-v1",10).size()); box.erase(owner);
            assertTrue(box.pending(owner,"own-state-v1",10).isEmpty());
        }
        finally
        {
            box.close(); try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(dir))
            { for (java.nio.file.Path p : (Iterable<java.nio.file.Path>)paths.sorted(Comparator.reverseOrder())::iterator) java.nio.file.Files.deleteIfExists(p); }
        }
    }
}
