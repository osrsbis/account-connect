package com.osrsbestinslot.export;

import com.google.gson.GsonBuilder;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnVarsEventTest
{
    static Map<String,Object> event()
    {
        Map<String,Object> v=new LinkedHashMap<>();v.put("timer_raw",null);v.put("observation","baseline");
        return OwnEvent.from("12345678-1234-1234-1234-123456789abc",Map.of("kind","effects.surge","value",v,
            "direct",true,"sequence",1,"tick",42,"observed_at_ms",1792240000000L));
    }
    @Test public void rawValuesKeepNullZeroAndSignedUnknownsAndDropAllPrivateExtras()
    {
        Map<String,Object> event=event();assertNotNull(event);event.put("event_id","a".repeat(32));event.put("ts",1792240000000L);
        event.put("other_player","PRIVATE");((Map<String,Object>)event.get("values")).put("name","PRIVATE");
        Map<String,Object> projected=PublicTransport.event(event);assertNotNull(projected);
        assertTrue(((Map<?,?>)projected.get("values")).containsKey("timer_raw"));
        assertNull(((Map<?,?>)projected.get("values")).get("timer_raw"));
        com.google.gson.Gson gson=new GsonBuilder().create();
        assertFalse(gson.toJson(projected).contains("PRIVATE"));
        assertEquals(projected.get("values"),PublicTransport.event(gson.fromJson(gson.toJson(projected),Map.class)).get("values"));
        for(String scope:List.of("own-history-v1","ge-v1"))assertFalse(EventQueueProtocol.allows(scope,projected));
        ((Map<String,Object>)event.get("values")).put("timer_raw",0);assertEquals(0L,((Map<?,?>)OwnEvent.wire(event).get("values")).get("timer_raw"));
        ((Map<String,Object>)event.get("values")).put("timer_raw",-1);assertEquals(-1L,((Map<?,?>)OwnEvent.wire(event).get("values")).get("timer_raw"));
        ((Map<String,Object>)event.get("values")).remove("timer_raw");assertNull(((Map<?,?>)OwnEvent.wire(event).get("values")).get("timer_raw"));
    }
    @Test public void invalidFieldsCannotAcquireTheRawProjectionOrInventRaidPoints()
    {
        for(Object bad:List.of(0.5,Double.NaN,2147483648L,"PRIVATE"))
        {Map<String,Object> e=event();((Map<String,Object>)e.get("values")).put("timer_raw",bad);assertNull(OwnEvent.wire(e));}
        Map<String,Object> e=event();e.put("var_family","crew.points");assertNull(OwnEvent.wire(e));
        e=event();e.put("direct",false);assertNull(OwnEvent.wire(e));
        e=event();e.put("var_family","combat.raid_points");e.put("values",Map.of("in_raid",0,"personal_points",100));assertNull(OwnEvent.wire(e));
    }
    @Test public void plainVarbitFamiliesKeepTheirSourcesWireKeysAndPollOrder()
    {
        List<Map<String,Object>> events=new ArrayList<>();OwnGameplayCapture c=new OwnGameplayCapture(e->{events.add(e);return true;});
        net.runelite.api.Client client=org.mockito.Mockito.mock(net.runelite.api.Client.class);int[] tick={40};
        org.mockito.Mockito.when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        org.mockito.Mockito.when(client.getLocalPlayer()).thenReturn(org.mockito.Mockito.mock(net.runelite.api.Player.class));
        org.mockito.Mockito.when(client.getAccountHash()).thenReturn(123L);
        org.mockito.Mockito.when(client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS)).thenReturn(10);
        org.mockito.Mockito.when(client.getWorldType()).thenReturn(EnumSet.noneOf(net.runelite.api.WorldType.class));
        org.mockito.Mockito.when(client.getTickCount()).thenAnswer(i->tick[0]);org.mockito.Mockito.when(client.getVarps()).thenReturn(new int[6000]);
        assertFalse(c.ready(client,"grant"));tick[0]+=2;assertTrue(c.ready(client,"grant"));c.poll(client,1792240000000L);
        List<Object> kinds=new ArrayList<>();Map<Object,Object> sources=new HashMap<>();
        for(Object row:(List<?>)c.snapshot().get("observations")){kinds.add(((Map<?,?>)row).get("kind"));sources.put(((Map<?,?>)row).get("kind"),((Map<?,?>)row).get("source"));}
        assertEquals(List.of("combat.barrows_state","combat.raid_points","effects.surge","effects.death_charge","effects.escape_crystal",
            "minigame.arena_state","skills.goal_xp","storage.rune_pouch","storage.potion_store","sailing.port_tasks","minigame.tears",
            "minigame.brewing","minigame.restaurant_order","minigame.plunder_state","sailing.trial_progress","storage.bank_tabs",
            "minigame.totem_state","sailing.charting","combat.special_attack","combat.raid_context"),kinds.subList(0,20));
        assertEquals(Map.of("combat.barrows_state","varbits:457-464","effects.surge","varbit:16270","effects.death_charge","varbits:12411,12138",
            "effects.escape_crystal","varbits:14838,14849,14857","minigame.arena_state","varbits:5964,10732,5962","minigame.tears","varbits:[5099, 455, 453]",
            "minigame.brewing","varbits:[2290]","minigame.restaurant_order","varbits:[2478, 2484, 2483, 2485]","minigame.plunder_state","varbits:[2375, 2377]",
            "sailing.trial_progress","varbits:[18427, 18432, 18437]"),Map.of("combat.barrows_state",sources.get("combat.barrows_state"),
            "effects.surge",sources.get("effects.surge"),"effects.death_charge",sources.get("effects.death_charge"),"effects.escape_crystal",
            sources.get("effects.escape_crystal"),"minigame.arena_state",sources.get("minigame.arena_state"),"minigame.tears",sources.get("minigame.tears"),
            "minigame.brewing",sources.get("minigame.brewing"),"minigame.restaurant_order",sources.get("minigame.restaurant_order"),
            "minigame.plunder_state",sources.get("minigame.plunder_state"),"sailing.trial_progress",sources.get("sailing.trial_progress")));
        assertEquals("varbits:[4171, 4172, 4173, 4174, 4175, 4176, 4177, 4178, 4179]",sources.get("storage.bank_tabs"));
        // Each family's wire keys are exactly its table keys, in table order, and only the table's families leave.
        Map<Object,List<Object>> keys=new HashMap<>();
        for(Map<String,Object> e:events)if("own_vars".equals(e.get("type")))keys.put(e.get("var_family"),new ArrayList<>(((Map<?,?>)e.get("values")).keySet()));
        assertEquals(17,keys.size());
        for(Object[] row:OwnActivityMappings.VARBITS){List<Object> expected=new ArrayList<>();for(int i=2;i<row.length;i+=2)expected.add(row[i]);assertEquals(expected,keys.get(row[0]));}
        assertEquals(List.of("ahrim","dharok","guthan","karil","torag","verac","reward_potential_raw","npcs_slain_raw"),keys.get("combat.barrows_state"));
    }
    @Test public void baselineChangesAndGapsAreAbsoluteAndOnlySupportedFamiliesAreHandedOff()
    {
        List<Map<String,Object>> events=new ArrayList<>();OwnGameplayCapture c=new OwnGameplayCapture(e->{events.add(e);return true;});
        // settle a synthetic own session through the normal gate, never a controlled game account
        net.runelite.api.Client client=org.mockito.Mockito.mock(net.runelite.api.Client.class);
        org.mockito.Mockito.when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        org.mockito.Mockito.when(client.getLocalPlayer()).thenReturn(org.mockito.Mockito.mock(net.runelite.api.Player.class));
        org.mockito.Mockito.when(client.getAccountHash()).thenReturn(123L);
        org.mockito.Mockito.when(client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS)).thenReturn(10);
        org.mockito.Mockito.when(client.getWorldType()).thenReturn(EnumSet.noneOf(net.runelite.api.WorldType.class));
        org.mockito.Mockito.when(client.getTickCount()).thenReturn(40,42);assertFalse(c.ready(client,"grant"));assertTrue(c.ready(client,"grant"));
        c.state("effects.surge","varbit:16270",Map.of("timer_raw",5),42,1792240000000L);
        c.state("effects.surge","varbit:16270",Map.of("timer_raw",5),43,1792240000600L);assertEquals(1,events.size());
        c.state("effects.surge","varbit:16270",Map.of("timer_raw",0),44,1792240001200L);assertEquals("absolute_update",events.get(1).get("observation"));
        assertEquals(0L,((Map<?,?>)events.get(1).get("values")).get("timer_raw"));
        c.gap();c.state("effects.surge","varbit:16270",Map.of("timer_raw",0),45,1792240001800L);
        assertEquals("baseline",events.get(2).get("observation"));
        c.state("unreviewed.private","private",Map.of("name","PRIVATE"),46,1792240002400L);assertEquals(3,events.size());
        assertEquals(3L,c.snapshot().get("vars_events_handed_off"));c.reset();assertEquals(0L,c.snapshot().get("vars_events_handed_off"));
    }
}
