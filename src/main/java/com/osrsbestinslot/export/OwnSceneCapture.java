package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/** Observed world objects and own tile overlap only. No actor/player enumeration. */
final class OwnSceneCapture
{
    private final OwnGameplayCapture out;
    private final Map<GameObject,Seen> objects=new IdentityHashMap<>();
    private static final class Seen
    {
        final int id,tick,world; final WorldPoint point;
        boolean reported,overlapping;
        Seen(GameObject object,Client client) { id=object.getId(); tick=client.getTickCount(); world=client.getWorld(); point=object.getWorldLocation(); }
    }
    OwnSceneCapture(OwnGameplayCapture out) { this.out=out; }
    void reset() { objects.clear(); }
    void spawned(Client c,GameObject object,long at)
    {
        if(object==null) return;
        int id=object.getId();
        if(id!=24988 && (id<45570 || id>45576)) return;
        if(objects.containsKey(object)) return;
        if(objects.size()>=256)
        {
            objects.clear();
            out.append("world.scene_gap","bounded object observation capacity",c.getTickCount(),at,Collections.singletonMap("gap","object_capacity"));
        }
        Seen seen=new Seen(object,c); if(seen.point!=null) objects.put(object,seen);
    }
    void despawned(GameObject object) { objects.remove(object); }
    void poll(Client c,long at)
    {
        WorldPoint self=c.getLocalPlayer()==null?null:c.getLocalPlayer().getWorldLocation();
        for(Iterator<Seen> i=objects.values().iterator();i.hasNext();)
        {
            Seen s=i.next(); int tick=c.getTickCount();
            if(s.world!=c.getWorld() || tick<s.tick) { i.remove(); continue; }
            if(tick<=s.tick) continue; // newly spawned acid does not become an immediate hit
            if(s.id==24988)
            {
                if(!s.reported)
                {
                    Map<String,Object> value=new LinkedHashMap<>(); value.put("world",s.world);
                    value.put("object_id",s.id); value.put("x",s.point.getX()); value.put("y",s.point.getY()); value.put("plane",s.point.getPlane());
                    value.put("scope","personally_observed_object_not_worldwide_absence");
                    out.append("world.crop_circle","GameObjectSpawned;next_tick_no_despawn",tick,at,value); s.reported=true;
                }
            }
            else
            {
                boolean overlap=s.point.equals(self);
                if(overlap && !s.overlapping)
                {
                    Map<String,Object> value=new LinkedHashMap<>(); value.put("object_id",s.id); value.put("spawn_tick",s.tick);
                    value.put("classification","own_tile_overlap_not_proven_damage_or_mistake");
                    out.append("combat.toa_hazards","own_tile+observed_acid_object",tick,at,value);
                }
                s.overlapping=overlap;
            }
        }
    }
}
