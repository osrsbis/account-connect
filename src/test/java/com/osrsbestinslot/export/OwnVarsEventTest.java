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
        return OwnVarsEvent.from("12345678-1234-1234-1234-123456789abc",Map.of("kind","effects.surge","value",v,
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
        assertTrue(EventQueueProtocol.allows("own-vars-v1",projected));
        for(String scope:List.of("own-state-v1","own-combat-v1","own-history-v1","ge-v1"))assertFalse(EventQueueProtocol.allows(scope,projected));
        ((Map<String,Object>)event.get("values")).put("timer_raw",0);assertEquals(0L,((Map<?,?>)OwnVarsEvent.wire(event).get("values")).get("timer_raw"));
        ((Map<String,Object>)event.get("values")).put("timer_raw",-1);assertEquals(-1L,((Map<?,?>)OwnVarsEvent.wire(event).get("values")).get("timer_raw"));
        ((Map<String,Object>)event.get("values")).remove("timer_raw");assertNull(((Map<?,?>)OwnVarsEvent.wire(event).get("values")).get("timer_raw"));
    }
    @Test public void invalidFieldsCannotAcquireTheRawProjectionOrInventRaidPoints()
    {
        for(Object bad:List.of(0.5,Double.NaN,2147483648L,"PRIVATE"))
        {Map<String,Object> e=event();((Map<String,Object>)e.get("values")).put("timer_raw",bad);assertNull(OwnVarsEvent.wire(e));}
        Map<String,Object> e=event();e.put("var_family","crew.points");assertNull(OwnVarsEvent.wire(e));
        e=event();e.put("direct",false);assertNull(OwnVarsEvent.wire(e));
        e=event();e.put("var_family","combat.raid_points");e.put("values",Map.of("in_raid",0,"personal_points",100));assertNull(OwnVarsEvent.wire(e));
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
