package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.TileItem;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.MapClue;
import net.runelite.client.plugins.cluescrolls.clues.CoordinateClue;

/** Current-session self-owned clue observations, never imported or inferred across a scene gap. */
final class OwnGroundClueCapture
{
    private final OwnGameplayCapture out;
    private final Map<TileItem,Map<String,Object>> seen = new IdentityHashMap<>();
    OwnGroundClueCapture(OwnGameplayCapture out) { this.out=out; }
    void reset() { seen.clear(); }

    void spawned(Client client, TileItem item, long at)
    {
        // Check ownership before reading item identity, quantity or expiry. Group/unknown are excluded.
        if (item==null || item.getOwnership()!=TileItem.OWNERSHIP_SELF || seen.containsKey(item)) return;
        int id=item.getId();
        if (id!=ItemID.TRAIL_CLUE_BEGINNER && id!=ItemID.TRAIL_CLUE_MASTER
            && MapClue.forItemId(id)==null && CoordinateClue.forItemId(id)==null) return;
        int quantity=item.getQuantity(); if(quantity<=0) return;
        if(seen.size()>=64)
        {
            seen.clear();
            out.append("clues.ground_gap","bounded_self_ground_clue_capacity",client.getTickCount(),at,
                Collections.singletonMap("gap","observation_capacity"));
        }
        int despawn=item.getDespawnTime();
        Map<String,Object> row=Fields.of("observation_id",UUID.randomUUID().toString(),"item_id",id,"quantity",quantity,
            "ownership","self_at_observation","drop_origin","unverified","despawn_server_tick",despawn>0?despawn:null,
            "clock_scope","current_client_session_only","observation","appeared");
        seen.put(item,row);
        out.append("clues.owned_ground","RuneLite1.13.1:TileItem_ownership_and_server_ticks",client.getTickCount(),at,row);
    }

    void despawned(Client client, TileItem item, long at)
    {
        Map<String,Object> prior=seen.remove(item); if(prior==null) return;
        Map<String,Object> row=new LinkedHashMap<>(prior); row.put("observation","left_scene");
        row.put("outcome","taken_expired_or_scene_change_unknown");
        out.append("clues.owned_ground","RuneLite1.13.1:tracked_self_ItemDespawned",client.getTickCount(),at,row);
    }
}
