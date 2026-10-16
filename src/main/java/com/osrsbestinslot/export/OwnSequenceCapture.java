package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/** Inferences from consecutive own samples only; no input-device tracking or automated actions. */
final class OwnSequenceCapture
{
    private final OwnGameplayCapture out;
    private WorldPoint anchor;
    private long anchorNanos, previousNanos;
    private int startTick, previousTick=-1, world=-1;
    private Map<Integer,Long> inventory;
    private int magicXp;
    OwnSequenceCapture(OwnGameplayCapture out) { this.out=out; }
    void reset() { anchor=null; inventory=null; previousTick=-1; world=-1; }

    static boolean courtyard(WorldPoint point)
    {
        if(point==null || point.getPlane()!=0) return false;
        int x=point.getX(),y=point.getY();
        return x>=3155 && x<=3174 && y>=3480 && y<=3499 && x+y>=6641 && x+y<=6667 && x-y>=-338 && x-y<=-312;
    }

    void poll(Client c,long at,long nanos)
    {
        int tick=c.getTickCount();
        if(tick==previousTick) return;
        boolean contiguous=previousTick>=0 && tick==previousTick+1 && world==c.getWorld()
            && nanos>=previousNanos && nanos-previousNanos<=5_000_000_000L;
        if(!contiguous) { anchor=null; inventory=null; }
        WorldPoint point=c.getLocalPlayer()==null?null:c.getLocalPlayer().getWorldLocation();
        boolean inGe=courtyard(point);
        if(anchor!=null && !anchor.equals(point))
        {
            // Only movement closes an interval. Periodic rollover would upload indefinitely while idle.
            // End at the last stationary sample, not at the new moving sample.
            if(previousTick>startTick)
            {
                Map<String,Object> value=new LinkedHashMap<>();
                value.put("start_tick",startTick); value.put("end_tick",previousTick);
                value.put("monotonic_elapsed_ms",(previousNanos-anchorNanos)/1_000_000L);
                value.put("inference","same_tile_at_sample_times_not_game_aura_or_idle_input");
                out.append("session.stationary_ge","own_world_tile;System.nanoTime",tick,at,value,false);
            }
            anchor=null;
        }
        if(inGe && anchor==null) { anchor=point; startTick=tick; anchorNanos=nanos; }
        if(!inGe) anchor=null;

        ItemContainer inv=c.getItemContainer(InventoryID.INVENTORY);
        Map<Integer,Long> current=quantities(inv);
        int xp=c.getSkillExperience(Skill.MAGIC);
        // A withdrawal alone cannot be an enchantment: require paired loss/gain AND observed Magic XP.
        if(contiguous && inventory!=null && current!=null && xp>magicXp)
        {
            for(int[] pair:OwnActivityMappings.BOLTS)
            {
                long loss=inventory.getOrDefault(pair[0],0L)-current.getOrDefault(pair[0],0L);
                long gain=current.getOrDefault(pair[1],0L)-inventory.getOrDefault(pair[1],0L);
                if(loss<=0 || loss!=gain) continue;
                Map<String,Object> value=new LinkedHashMap<>();
                value.put("input_item_id",pair[0]); value.put("output_item_id",pair[1]);
                value.put("matched_quantity",gain); value.put("magic_xp_change",xp-magicXp);
                value.put("inference","inventory_xp_coincidence_not_confirmed_cast");
                out.append("skills.item_transformation","GameTick:own_inventory+MagicXP",tick,at,value,false);
            }
        }
        inventory=current; magicXp=xp; previousTick=tick; previousNanos=nanos; world=c.getWorld();
    }

    private static Map<Integer,Long> quantities(ItemContainer container)
    {
        if(container==null || container.getItems()==null || container.getItems().length>28) return null;
        Map<Integer,Long> result=new HashMap<>();
        for(Item item:container.getItems()) if(item!=null && item.getId()>0 && item.getQuantity()>0)
            result.merge(item.getId(),(long)item.getQuantity(),Long::sum);
        return result;
    }
}
