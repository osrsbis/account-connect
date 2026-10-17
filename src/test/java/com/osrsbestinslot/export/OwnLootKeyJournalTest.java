package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnLootKeyJournalTest
{
    static final List<String> FAMILIES=List.of("interface","slot0","slot1","slot2","slot3","slot4");
    static Map<String,Object> event(String family)
    {
        boolean visibility=family.equals("interface");Map<String,Object> value=new LinkedHashMap<>();value.put("observation","baseline");
        if(visibility)value.put("status","visible_key_slots");
        else {
            value.put("slot",Integer.parseInt(family.substring(4)));value.put("status","cached_items");value.put("container_observed_at_ms",1792254000000L);
            List<Map<String,Object>> items=new ArrayList<>();for(int i=0;i<128;i++)items.add(Map.of("id",2147483500+i,"qty",Integer.MAX_VALUE));value.put("items",items);
        }
        return OwnStateEvent.from("12345678-1234-1234-1234-123456789abc",Map.of("kind",visibility?"loot.key_interface":"loot.key_slot",
            "source","own_key_packet+visible_fixed_chest","sequence",1,"tick",42,"observed_at_ms",1792254000001L,"direct",true,"value",value));
    }
    @Test public void projectedSlotsRejectOversizeContradictionsAndNeverCarryValuationOrVictimFields()
    {
        com.google.gson.Gson gson=new com.google.gson.Gson();
        for(String family:FAMILIES) {
            Map<String,Object> e=event(family);assertNotNull(e);e.put("event_id","a".repeat(32));e.put("ts",1792254000001L);
            Map<String,Object> value=(Map<String,Object>)e.get("state_value");value.put("value_gp",999999);value.put("victim","PRIVATE");
            Map<String,Object> wire=PublicTransport.event(e);assertNotNull(wire);assertFalse(gson.toJson(wire).contains("PRIVATE"));assertFalse(gson.toJson(wire).contains("value_gp"));
            assertNotNull(PublicTransport.event(gson.fromJson(gson.toJson(wire),Map.class)));assertTrue(EventQueueProtocol.allows("own-state-v1",wire));
        }
        Map<String,Object> e=event("slot0"),value=(Map<String,Object>)e.get("state_value");value.put("container_observed_at_ms",1792254000002L);assertNull(OwnStateEvent.wire(e));
        e=event("slot0");value=(Map<String,Object>)e.get("state_value");value.put("slot",5);assertNull(OwnStateEvent.wire(e));
        e=event("slot0");value=(Map<String,Object>)e.get("state_value");((List<Object>)value.get("items")).add(Map.of("id",2147483640,"qty",1));assertNull(OwnStateEvent.wire(e));
        e=event("slot0");value=(Map<String,Object>)e.get("state_value");value.put("status","observed_empty_cache");assertNull(OwnStateEvent.wire(e));
        Map<String,Object> unknown=new LinkedHashMap<>();unknown.put("slot",0);unknown.put("status","cache_unverified");unknown.put("items",null);unknown.put("container_observed_at_ms",null);
        assertNotNull(OwnStateEvent.projectValue("loot_key_slot",unknown));unknown.put("items",List.of());assertNull(OwnStateEvent.projectValue("loot_key_slot",unknown));
        Map<String,Object> merged=Map.of("slot",0,"status","cached_items","items",List.of(Map.of("id",2,"qty",4294967294L)),"container_observed_at_ms",1792254000000L);
        assertNotNull(OwnStateEvent.projectValue("loot_key_slot",merged));
    }
    @Test public void largeFiveSlotCacheUsesBoundedChangedSlotEventsWithoutPriceOrSnapshotDuplication()
    {
        List<Map<String,Object>> sent=new ArrayList<>();long[] price={100};int[] tick={100};long at=1792254000000L;
        OwnGameplayCapture out=new OwnGameplayCapture(e->{sent.add(e);return !Long.valueOf(4).equals(((Map<?,?>)e.get("state_value")).get("slot"));},id->price[0]);
        Client c=mock(Client.class);Player self=mock(Player.class);Widget tabs=mock(Widget.class);
        when(c.getLocalPlayer()).thenReturn(self);when(c.getGameState()).thenReturn(GameState.LOGGED_IN);when(c.getAccountHash()).thenReturn(123L);
        when(c.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);when(c.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        when(c.getTickCount()).thenAnswer(i->tick[0]);when(c.getWidget(InterfaceID.WildyLootChest.TABS)).thenReturn(tabs);
        assertFalse(out.ready(c,"synthetic"));tick[0]+=2;assertTrue(out.ready(c,"synthetic"));
        ItemContainer[] containers=new ItemContainer[5];
        for(int slot=0;slot<5;slot++) {
            Item[] items=new Item[128];for(int j=0;j<128;j++)items[j]=new Item(2147483500+j,Integer.MAX_VALUE);
            containers[slot]=mock(ItemContainer.class);when(containers[slot].getId()).thenReturn(558+slot);when(containers[slot].getItems()).thenReturn(items);
            when(c.getItemContainer(558+slot)).thenReturn(containers[slot]);out.lootKeys.container(c,558+slot,containers[slot],at);
        }
        out.lootKeys.poll(c,at+1);
        assertEquals("one visibility observation plus five bounded slots",6,sent.size());
        assertEquals(6L,out.snapshot().get("state_events_observed"));assertEquals(5L,out.snapshot().get("state_events_handed_off"));assertEquals(1L,out.snapshot().get("state_events_not_handed_off"));
        com.google.gson.Gson gson=new com.google.gson.Gson();int maximum=0;
        for(Map<String,Object> e:sent) {
            assertNotNull(OwnStateEvent.wire(e));int bytes=gson.toJson(e).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;maximum=Math.max(maximum,bytes);assertTrue(bytes<8192);
            assertFalse(gson.toJson(e).contains("value_gp"));
        }
        Map<?,?> full=(Map<?,?>)((Map<?,?>)((Map<?,?>)out.snapshot().get("states")).get("loot.owned_key_contents")).get("value");
        int fullBytes=gson.toJson(full).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;assertTrue(fullBytes>8192);
        System.out.println("five-slot source bytes="+fullBytes+", maximum projected event bytes="+maximum);
        assertEquals(1,((List<?>)out.snapshot().get("observations")).size());
        out.lootKeys.poll(c,at+2);price[0]=200;out.lootKeys.poll(c,at+3);assertEquals(6,sent.size());
        out.lootKeys.container(c,560,containers[2],at+4);out.lootKeys.poll(c,at+5);assertEquals(7,sent.size());
        assertEquals(2L,((Map<?,?>)sent.get(6).get("state_value")).get("slot"));
        when(tabs.isHidden()).thenReturn(true);out.lootKeys.poll(c,at+6);out.lootKeys.poll(c,at+7);assertEquals(8,sent.size());
        when(tabs.isHidden()).thenReturn(false);out.lootKeys.poll(c,at+8);assertEquals(14,sent.size());
        assertEquals("not_observed_in_session",((Map<?,?>)sent.get(9).get("state_value")).get("status"));
        verify(tabs,never()).getText();verify(c,never()).getPlayers();
    }
}
