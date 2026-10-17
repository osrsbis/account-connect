package com.osrsbestinslot.export;

import java.util.*;

/** Closed sample coincidences. Never confirmed gameplay, input idleness, effects or profit. */
final class OwnInferredSignal
{
    static final Set<String> KINDS=Set.of("session.stationary_ge","skills.item_transformation","effects.consumption");
    static boolean supports(Object kind) { return kind instanceof String && KINDS.contains(kind); }
    static String source(String kind)
    {
        switch(kind) {
            case "session.stationary_ge":return "own_world_tile;System.nanoTime";
            case "skills.item_transformation":return "GameTick:own_inventory+MagicXP";
            case "effects.consumption":return "MenuOptionClicked:149.0+own_inventory";
            default:return null;
        }
    }
    static Map<String,Object> value(String kind,Map<?,?> p)
    {
        Map<String,Object> out=new LinkedHashMap<>();String inference;
        switch(kind)
        {
            case "session.stationary_ge":
                long start=num(p.get("start_tick"),0,Integer.MAX_VALUE),end=num(p.get("end_tick"),start+1,Integer.MAX_VALUE);
                out.put("start_tick",start);out.put("end_tick",end);
                out.put("monotonic_elapsed_ms",num(p.get("monotonic_elapsed_ms"),0,(end-start)*5000));
                inference="same_tile_at_sample_times_not_game_aura_or_idle_input";break;
            case "skills.item_transformation":
                long before=num(p.get("input_item_id"),1,Integer.MAX_VALUE),after=num(p.get("output_item_id"),1,Integer.MAX_VALUE);
                boolean pair=false;for(int[] ids:OwnActivityMappings.BOLTS)if(ids[0]==before&&ids[1]==after)pair=true;
                if(!pair)throw new IllegalArgumentException();
                out.put("input_item_id",before);out.put("output_item_id",after);
                out.put("matched_quantity",num(p.get("matched_quantity"),1,28L*Integer.MAX_VALUE));
                out.put("magic_xp_change",num(p.get("magic_xp_change"),1,Integer.MAX_VALUE));
                inference="inventory_xp_coincidence_not_confirmed_cast";break;
            case "effects.consumption":
                long id=num(p.get("item_id_before"),1,Integer.MAX_VALUE),quantity=num(p.get("quantity_before"),1,Integer.MAX_VALUE);
                long next=num(p.get("item_id_after"),-1,Integer.MAX_VALUE),remaining=num(p.get("quantity_after"),0,Integer.MAX_VALUE);
                if(next==0||(next==-1?remaining!=0:remaining==0)||(id==next&&remaining>=quantity))throw new IllegalArgumentException();
                Object action=p.get("action");if(!(action instanceof String)||!List.of("eat","drink","chew").contains(action))throw new IllegalArgumentException();
                out.put("item_id_before",id);out.put("quantity_before",quantity);out.put("item_id_after",next);out.put("quantity_after",remaining);
                out.put("action",action);out.put("intent_tick",num(p.get("intent_tick"),0,Integer.MAX_VALUE));
                inference="own_menu_and_slot_change_not_confirmed_effect_or_cooldown";break;
            default:throw new IllegalArgumentException();
        }
        if(!inference.equals(p.get("inference")))throw new IllegalArgumentException();out.put("inference",inference);return out;
    }
    static void checkTick(String kind,Map<String,Object> value,long tick)
    {
        if(kind.equals("session.stationary_ge")&&(Long)value.get("end_tick")+1!=tick)throw new IllegalArgumentException();
        if(kind.equals("effects.consumption")) {
            long age=tick-(Long)value.get("intent_tick");if(age<0||age>2)throw new IllegalArgumentException();
        }
    }
    private static long num(Object x,long min,long max)
    {
        if(!(x instanceof Number))throw new IllegalArgumentException();Number n=(Number)x;long value=n.longValue();
        if(!Double.isFinite(n.doubleValue())||n.doubleValue()!=value||value<min||value>max)throw new IllegalArgumentException();return value;
    }
}
