package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnLootKeysCaptureTest
{
    private Client client;private Widget tabs;private OwnGameplayCapture out;private long price=100;
    @Before public void setup()
    {
        client=mock(Client.class);tabs=mock(Widget.class);out=new OwnGameplayCapture(e->false,id->price);
        when(client.getWidget(InterfaceID.WildyLootChest.TABS)).thenReturn(tabs);
    }
    private ItemContainer packet(int id,Item... items)
    {
        ItemContainer c=mock(ItemContainer.class);when(c.getId()).thenReturn(id);when(c.getItems()).thenReturn(items);
        when(client.getItemContainer(id)).thenReturn(c);out.lootKeys.container(client,id,c,1000);return c;
    }
    private Map<?,?> value()
    {
        out.lootKeys.poll(client,1100);
        return (Map<?,?>)((Map<?,?>)((Map<?,?>)out.snapshot().get("states")).get("loot.owned_key_contents")).get("value");
    }
    private Map<?,?> slot(Map<?,?> value,int i) { return (Map<?,?>)((List<?>)value.get("slots")).get(i); }
    @Test public void allFiveSlotsPreserveMissingEmptyDuplicateQuantitiesAndEstimatedValue()
    {
        packet(558,new Item(2,Integer.MAX_VALUE),new Item(2,Integer.MAX_VALUE));packet(559);
        packet(560,new Item(995,100));packet(561,new Item(-1,0));packet(562,new Item(3,1));
        Map<?,?> v=value();assertEquals(5,((List<?>)v.get("slots")).size());assertEquals(false,v.get("complete"));
        assertEquals(false,v.get("new_loot_verified"));assertEquals(4294967294L,((Map<?,?>)((List<?>)slot(v,0).get("items")).get(0)).get("qty"));
        assertEquals(429496729400L,slot(v,0).get("value_gp"));assertEquals(100L,slot(v,2).get("value_gp"));
        assertEquals("observed_empty_cache",slot(v,1).get("status"));assertEquals("observed_empty_cache",slot(v,3).get("status"));
        out.lootKeys.reset();assertEquals("not_observed_in_session",slot(value(),0).get("status"));
        assertNull(slot(value(),0).get("items"));verify(tabs,never()).getText();verify(client,never()).getPlayers();
    }
    @Test public void hiddenUnobservedOrWrongContainersAreNeverReadAndGapsClearPreviousKeys()
    {
        ItemContainer c=mock(ItemContainer.class);when(tabs.isHidden()).thenReturn(true);
        out.lootKeys.container(client,558,c,1000);verify(c,never()).getItems();assertEquals("interface_unavailable",value().get("status"));
        when(tabs.isHidden()).thenReturn(false);out.lootKeys.container(client,90,c,1000);verify(c,never()).getId();
        when(c.getId()).thenReturn(90);out.lootKeys.container(client,558,c,1000);verify(c,never()).getItems();
        assertEquals("not_observed_in_session",slot(value(),0).get("status"));verify(client,never()).getItemContainer(anyInt());
        packet(558,new Item(995,100));assertEquals("cached_items",slot(value(),0).get("status"));
        out.gap();assertEquals("not_observed_in_session",slot(value(),0).get("status"));
    }
    @Test public void missingArraysUnobservedCacheChangesAndUnpricedOrUnsafeValuesStayUnknown()
    {
        ItemContainer c=packet(558,(Item[])null);assertEquals("not_observed_in_session",slot(value(),0).get("status"));
        c=packet(558,new Item(2,2));when(c.getItems()).thenReturn(new Item[]{new Item(2,3)});
        assertEquals("cache_unverified",slot(value(),0).get("status"));assertNull(slot(value(),0).get("value_gp"));
        packet(558,new Item(2,Integer.MAX_VALUE));price=0;assertNull(slot(value(),0).get("value_gp"));
        price=Long.MAX_VALUE;assertNull(slot(value(),0).get("value_gp"));
        price=5000000;assertNull(slot(value(),0).get("value_gp"));
        packet(558,new Item(2,1));price=100;assertEquals(100L,slot(value(),0).get("value_gp"));
        out.reset();assertEquals("not_observed_in_session",slot(value(),0).get("status"));
    }
}
