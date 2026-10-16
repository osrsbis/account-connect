package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;

/** Qualified own inventory evidence. A click or seasonal no-loss message alone is not consumption. */
final class OwnConsumptionCapture
{
    private final OwnGameplayCapture out;
    private final Map<Integer,Pending> pending=new LinkedHashMap<>();
    private static final class Pending
    {
        final int item,tick,quantity; final String action;
        Pending(Item item,int tick,String action) { this.item=item.getId(); quantity=item.getQuantity(); this.tick=tick; this.action=action; }
    }
    OwnConsumptionCapture(OwnGameplayCapture out) { this.out=out; }
    void reset() { pending.clear(); }
    void menu(Client c,MenuOptionClicked event)
    {
        String action=event.getMenuOption();
        if(!"Eat".equals(action) && !"Drink".equals(action) && !"Chew".equals(action)) return;
        // Own inventory only. A POH pool/other object's Drink action cannot enter this path.
        if(event.getParam1()!=(149<<16) || event.getParam0()<0 || event.getParam0()>=28) return;
        ItemContainer inv=c.getItemContainer(InventoryID.INVENTORY);
        if(inv==null || inv.getItems()==null || event.getParam0()>=inv.getItems().length) return;
        Item item=inv.getItems()[event.getParam0()];
        if(item==null || item.getId()<=0 || item.getQuantity()<=0 || event.getItemId()!=item.getId()) return;
        pending.put(event.getParam0(),new Pending(item,c.getTickCount(),action)); // at most 28 own slots
    }
    void poll(Client c,long at)
    {
        ItemContainer inv=c.getItemContainer(InventoryID.INVENTORY);
        if(inv==null || inv.getItems()==null || inv.getItems().length>28) { reset(); return; }
        for(Iterator<Map.Entry<Integer,Pending>> i=pending.entrySet().iterator();i.hasNext();)
        {
            Map.Entry<Integer,Pending> entry=i.next(); Pending p=entry.getValue(); int age=c.getTickCount()-p.tick;
            if(age<0 || age>2) { i.remove(); continue; }
            int slot=entry.getKey(); Item after=slot<inv.getItems().length?inv.getItems()[slot]:null;
            int afterId=after==null?-1:after.getId(), afterQty=after==null?0:after.getQuantity();
            if(afterId==p.item && afterQty>=p.quantity) continue;
            Map<String,Object> value=new LinkedHashMap<>(); value.put("item_id_before",p.item);
            value.put("quantity_before",p.quantity); value.put("item_id_after",afterId); value.put("quantity_after",afterQty);
            value.put("action",p.action.toLowerCase(Locale.ROOT)); value.put("intent_tick",p.tick);
            value.put("inference","own_menu_and_slot_change_not_confirmed_effect_or_cooldown");
            out.append("effects.consumption","MenuOptionClicked:149.0+own_inventory",c.getTickCount(),at,value,false); i.remove();
        }
    }
}
