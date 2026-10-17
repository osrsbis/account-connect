package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnSignalEventTest
{
    static final long AT=1792254000000L;
    static Map<String,Map<String,Object>> examples()
    {
        Map<String,Map<String,Object>> x=new LinkedHashMap<>();
        x.put("storage.coal_bag",Map.of("count_observed",0));
        x.put("storage.fish_barrel",Map.of("count_observed",28));
        x.put("storage.grape_barrel",Map.of("fill_observed","partial"));
        Map<String,Object> notch=new LinkedHashMap<>();notch.put("signal","notch_observed");notch.put("total_kills",null);x.put("combat.chompy_kc",notch);
        x.put("minigame.construction_contract",Map.of("contracts_absolute",0,"points_absolute",999999999999L));
        x.put("farming.compost",Map.of("compost_type","ultra","patch_identity","unknown"));
        x.put("hunter.rumour",Map.of("signal","whistle_target","target_observed","red_chinchompa","turn_in_verified",false));return x;
    }
    static Map<String,Object> event(String kind)
    {
        return OwnSignalEvent.from("12345678-1234-1234-1234-123456789abc",Map.of("kind",kind,"source",kind.equals("storage.fish_barrel")
            ?"ClientTick:widget:193.2;own_inventory_possession":"ChatMessage:GAMEMESSAGE:exact_english_projection",
            "sequence",1,"tick",42,"observed_at_ms",AT,"direct",true,"value",examples().get(kind)));
    }
    @Test public void sevenClosedSignalProjectionsSurviveJsonAndStripPrivateTextWithoutInventingDeltas()
    {
        assertEquals(7,examples().size());Gson gson=new Gson();
        for(String kind:examples().keySet())
        {
            Map<String,Object> e=event(kind);assertNotNull(e);e.put("event_id","a".repeat(32));e.put("ts",AT);
            e.put("raw_chat","PRIVATE");((Map<String,Object>)e.get("signal_value")).put("player_name","PRIVATE");
            Map<String,Object> projected=PublicTransport.event(e);assertNotNull(projected);assertFalse(gson.toJson(projected).contains("PRIVATE"));
            assertEquals(OwnSignalEvent.wire(projected),OwnSignalEvent.wire(PublicTransport.event(gson.fromJson(gson.toJson(projected),Map.class))));
            assertTrue(EventQueueProtocol.allows("own-signals-v1",projected));
            for(String scope:List.of("own-vars-v1","own-state-v1","own-combat-v1","own-history-v1","ge-v1"))assertFalse(EventQueueProtocol.allows(scope,projected));
            assertEquals("observed_signal",projected.get("observation"));
        }
        assertNull(((Map<?,?>)event("combat.chompy_kc").get("signal_value")).get("total_kills"));
    }
    @Test public void invalidSourcesCountsTargetsAndSyntheticCompletionAreRejected()
    {
        Map<String,Object> e=event("hunter.rumour");((Map<String,Object>)e.get("signal_value")).put("target_observed","PRIVATE_PLAYER");assertNull(OwnSignalEvent.wire(e));
        e=event("hunter.rumour");((Map<String,Object>)e.get("signal_value")).put("turn_in_verified",true);assertNull(OwnSignalEvent.wire(e));
        e=event("storage.coal_bag");e.put("signal_value",Map.of("count_observed",55));assertNull(OwnSignalEvent.wire(e));
        e=event("storage.fish_barrel");e.put("source","own_client_system");assertNull(OwnSignalEvent.wire(e));
        e=event("combat.chompy_kc");e.put("signal_value",Map.of("signal","notch_observed","total_kills",1));assertNull(OwnSignalEvent.wire(e));
        e=event("farming.compost");e.put("signal_value",Map.of("compost_type","super","patch_identity","private"));assertNull(OwnSignalEvent.wire(e));
        e=event("storage.grape_barrel");e.put("signal_kind",null);assertNull(OwnSignalEvent.wire(e));
        e=event("storage.grape_barrel");e.put("direct",false);assertNull(OwnSignalEvent.wire(e));
        assertNull(OwnSignalEvent.from("12345678-1234-1234-1234-123456789abc",Map.of("kind","hunter.rumour","source","PUBLICCHAT","value",examples().get("hunter.rumour"))));
    }
    @Test public void rumourVariantsRemainSeparateAndMissingNotchTotalStaysUnknown()
    {
        assertEquals(Map.of("whistle_charges_observed",0L),OwnSignalEvent.value("hunter.rumour",Map.of("whistle_charges_observed",0)));
        Map<String,Object> none=OwnSignalEvent.value("hunter.rumour",Map.of("signal","whistle_no_active","turn_in_verified",false));
        assertTrue(none.containsKey("target_observed"));assertNull(none.get("target_observed"));
        assertNull(OwnSignalEvent.value("hunter.rumour",Map.of("whistle_charges_observed",1,"signal","whistle_target","target_observed","red_chinchompa")));
        assertEquals(9000L,OwnSignalEvent.value("combat.chompy_kc",Map.of("signal","absolute_check","total_kills",9000)).get("total_kills"));
    }
}
