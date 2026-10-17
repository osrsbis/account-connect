package com.osrsbestinslot.export;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventObservationContextTest
{
    @Test public void missingAndStaleContextNeverBecomeNormalMode()
    {
        EventObservationContext c = new EventObservationContext();
        assertNull(c.event(null,"0.7.17",1000));
        Map<String,Object> unknown=c.event("private-owner","0.7.17",1000);
        assertNull(unknown.get("account_mode"));
        c.observe("private-owner",null,4,1000);
        assertNull(c.event("private-owner","0.7.17",1100).get("account_mode"));
        c.observe("private-owner","NORMAL",5,2000);
        assertEquals("NORMAL",c.event("private-owner","0.7.17",2001).get("account_mode"));
        assertNull(c.event("private-owner","0.7.17",1999).get("account_mode"));
        assertNull(c.event("private-owner","0.7.17",12001).get("account_mode"));
    }
    @Test public void identityChangesAndResetRotateSessionAndDoNotCarryMode()
    {
        EventObservationContext c=new EventObservationContext();c.observe("a","IRONMAN",2,1000);
        Map<String,Object> first=c.event("a","0.7.17",1000),next=c.event("a","0.7.17",1001);
        assertEquals(first.get("collector_session"),next.get("collector_session"));
        assertEquals(2L,next.get("sequence"));
        Map<String,Object> changed=c.event("b","0.7.17",1002);
        assertNotEquals(first.get("collector_session"),changed.get("collector_session"));
        assertEquals(1L,changed.get("sequence"));assertNull(changed.get("account_mode"));
        c.clear();assertNotEquals(changed.get("collector_session"),c.event("b","0.7.17",1003).get("collector_session"));
    }
    @Test public void nestedProjectionDropsNamesCredentialsAndUnknownLabels()
    {
        Map<String,Object> input=new LinkedHashMap<>();input.put("other_player","PRIVATE_CANARY");
        input.put("collector_session","PRIVATE_CANARY");input.put("account_mode","PRIVATE_CANARY");
        input.put("plugin_version","PRIVATE_CANARY");input.put("source","PRIVATE_CANARY");
        input.put("context_status","PRIVATE_CANARY");input.put("sequence",Double.NaN);
        input.put("context_tick",-1);input.put("schema_v",1);input.put("account_mode_source","PRIVATE_CANARY");
        Map<String,Object> clean=EventObservationContext.project(input);
        assertFalse(clean.toString().contains("PRIVATE_CANARY"));assertFalse(clean.containsKey("sequence"));
        assertFalse(clean.containsKey("context_tick"));assertEquals(1,clean.get("schema_v"));
    }
}
