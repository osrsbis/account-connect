package com.osrsbestinslot.export;
import java.util.*;
import net.runelite.api.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class OwnRunePouchCaptureTest
{
    Client client;int[] vars;ItemContainer inventory;EnumComposition mapping;
    @Before public void setup()
    {
        client=mock(Client.class);inventory=mock(ItemContainer.class);mapping=mock(EnumComposition.class);vars=new int[13];
        when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventory);
        when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.RUNE_POUCH,1)});
        when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(mapping);
        when(mapping.getKeys()).thenReturn(new int[]{1,2});when(mapping.getIntVals()).thenReturn(new int[]{556,555});
        for(int i=0;i<6;i++)for(int j=0;j<2;j++)
        {
            VarbitComposition bit=mock(VarbitComposition.class);when(bit.getIndex()).thenReturn(i*2+j+1);
            when(bit.getLeastSignificantBit()).thenReturn(0);when(bit.getMostSignificantBit()).thenReturn(30);
            when(client.getVarbit(OwnRunePouchCapture.SLOTS[i][j])).thenReturn(bit);
        }
    }
    Map<String,Object> read(){return OwnRunePouchCapture.read(client,vars);}
    @Test public void absentOrNotedPouchDoesNotReadStaleQuantities()
    {
        vars[1]=1;vars[2]=9000;
        for(Item[] items:new Item[][]{new Item[0],{new Item(ItemID.RUNE_POUCH_NOTE,1)},{new Item(ItemID.RUNE_POUCH,0)}})
        {when(inventory.getItems()).thenReturn(items);Map<String,Object> out=read();assertEquals("not_observed_carried",out.get("possession"));assertFalse(out.containsKey("observed_items"));}
        verify(client,never()).getEnum(anyInt());verify(client,never()).getVarbit(anyInt());
    }
    @Test public void unavailableInventoryIsUnknownNotEmpty()
    {when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(null);assertEquals("inventory_unavailable",read().get("possession"));}
    @Test public void unknownMappingNeverUsesDefaultAndMissingVarbitIsNotZero()
    {
        vars[1]=99;vars[2]=7;when(mapping.getIntValue(99)).thenReturn(556);
        when(client.getVarbit(OwnRunePouchCapture.SLOTS[1][0])).thenReturn(null);
        Map<String,Object> out=read();List<Map<String,Object>> slots=(List<Map<String,Object>>)out.get("slots");
        assertEquals("mapping_unknown",slots.get(0).get("status"));assertEquals("unavailable",slots.get(1).get("status"));
        assertEquals(false,out.get("all_slots_decoded"));assertTrue(((List<?>)out.get("observed_items")).isEmpty());
        verify(mapping,never()).getIntValue(anyInt());
    }
    @Test public void duplicateRuneSlotsUseLongAndDoNotMultiplyByPouchItems()
    {
        vars[1]=vars[3]=1;vars[2]=vars[4]=Integer.MAX_VALUE;
        when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.RUNE_POUCH,1),new Item(ItemID.DIVINE_RUNE_POUCH,1)});
        Map<String,Object> out=read();List<Map<String,Object>> items=(List<Map<String,Object>>)out.get("observed_items");
        assertEquals(1,items.size());assertEquals(4294967294L,items.get(0).get("qty"));assertEquals(false,out.get("complete"));
    }
    @Test public void sixEmptySlotsAreObservedEmptyButNoConsumptionOrWealthInferred()
    {
        Map<String,Object> out=read();assertEquals(true,out.get("all_slots_decoded"));assertTrue(((List<?>)out.get("observed_items")).isEmpty());
        assertEquals("client_cached_slots",out.get("contents_semantics"));assertFalse(out.containsKey("delta"));assertFalse(out.containsKey("value_gp"));
    }
}
