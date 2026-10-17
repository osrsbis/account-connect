package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.MapClue;
import net.runelite.client.plugins.cluescrolls.clues.CoordinateClue;

/** Own clue observations plus fixed public library facts. No drop owner, actual route or completed timer. */
final class OwnClueProjection
{
    static final Set<String> SIGNALS=Set.of("clues.owned_ground","clues.ground_gap","world.scene_gap");
    static boolean supports(Object kind) { return kind instanceof String&&SIGNALS.contains(kind); }
    static String source(String kind)
    {return kind.equals("clues.owned_ground")?"own_client_ground":"own_client_capacity";}
    static boolean sourceMatches(String kind,Object source,Map<?,?> raw)
    {
        switch(kind) {
            case "clues.owned_ground":return "appeared".equals(raw.get("observation"))
                ?"RuneLite1.13.1:TileItem_ownership_and_server_ticks".equals(source)
                :"left_scene".equals(raw.get("observation"))&&"RuneLite1.13.1:tracked_self_ItemDespawned".equals(source);
            case "clues.ground_gap":return "bounded_self_ground_clue_capacity".equals(source);
            case "world.scene_gap":return "bounded object observation capacity".equals(source);
            default:return false;
        }
    }
    static Map<String,Object> inventory(Map<?,?> p)
    {
        Object raw=p.get("inventory_map_coordinate_clues");Map<String,Object> clues=null;
        if(raw!=null)
        {
            Map<?,?> entries=map(raw);if(entries.size()>28)throw new IllegalArgumentException();clues=new TreeMap<>();
            for(Map.Entry<?,?> entry:entries.entrySet())
            {
                if(!(entry.getKey() instanceof String)||!((String)entry.getKey()).matches("[1-9][0-9]{0,5}"))throw new IllegalArgumentException();
                int id=Integer.parseInt((String)entry.getKey());MapClue mc=MapClue.forItemId(id);CoordinateClue cc=mc==null?CoordinateClue.forItemId(id):null;
                if(mc==null&&cc==null)throw new IllegalArgumentException();WorldPoint point=mc!=null?mc.getLocation(null):cc.getLocation(null);
                Map<?,?> v=map(entry.getValue());String kind=mc!=null?"map":"coordinate";
                if(!kind.equals(v.get("kind"))||!"library_base_location_not_resolved_quest_alternative".equals(v.get("qualification"))
                    ||num(v.get("x"),0,16383)!=point.getX()||num(v.get("y"),0,16383)!=point.getY()||num(v.get("plane"),0,3)!=point.getPlane())throw new IllegalArgumentException();
                clues.put((String)entry.getKey(),Map.of("kind",kind,"x",(long)point.getX(),"y",(long)point.getY(),"plane",(long)point.getPlane(),
                    "qualification","library_base_location_not_resolved_quest_alternative"));
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("inventory_map_coordinate_clues",clues);out.put("complete",false);
        out.put("source_table","runelite_1_13_1_primary");return out;
    }
    static Map<String,Object> signal(String kind,Map<?,?> p)
    {
        if(!kind.equals("clues.owned_ground"))
        {
            String gap=kind.equals("clues.ground_gap")?"observation_capacity":"object_capacity";
            if(!gap.equals(p.get("gap")))throw new IllegalArgumentException();return Map.of("gap",gap);
        }
        Object id=p.get("observation_id");if(!(id instanceof String))throw new IllegalArgumentException();String observationId=((String)id).replace("-","");
        if(!((String)id).matches("[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException();
        int item=(int)num(p.get("item_id"),1,Integer.MAX_VALUE);
        if(item!=ItemID.TRAIL_CLUE_BEGINNER&&item!=ItemID.TRAIL_CLUE_MASTER&&MapClue.forItemId(item)==null&&CoordinateClue.forItemId(item)==null)throw new IllegalArgumentException();
        if(!"self_at_observation".equals(p.get("ownership"))||!"unverified".equals(p.get("drop_origin"))
            ||!"current_client_session_only".equals(p.get("clock_scope")))throw new IllegalArgumentException();
        Object observation=p.get("observation");if(!"appeared".equals(observation)&&!"left_scene".equals(observation))throw new IllegalArgumentException();
        if("appeared".equals(observation)?p.get("outcome")!=null:!"taken_expired_or_scene_change_unknown".equals(p.get("outcome")))throw new IllegalArgumentException();
        Map<String,Object> out=new LinkedHashMap<>();out.put("observation_id",observationId);out.put("item_id",(long)item);
        out.put("quantity",num(p.get("quantity"),1,Integer.MAX_VALUE));out.put("ownership","self_at_observation");out.put("drop_origin","unverified");
        Object tick=p.get("despawn_server_tick");out.put("despawn_server_tick",tick==null?null:num(tick,1,Integer.MAX_VALUE));
        out.put("clock_scope","current_client_session_only");out.put("observation",observation);out.put("outcome",p.get("outcome"));return out;
    }
    private static Map<?,?> map(Object x) {if(!(x instanceof Map))throw new IllegalArgumentException();return (Map<?,?>)x;}
    private static long num(Object x,long min,long max)
    {
        if(!(x instanceof Number))throw new IllegalArgumentException();Number n=(Number)x;long value=n.longValue();
        if(!Double.isFinite(n.doubleValue())||n.doubleValue()!=value||value<min||value>max)throw new IllegalArgumentException();return value;
    }
}
