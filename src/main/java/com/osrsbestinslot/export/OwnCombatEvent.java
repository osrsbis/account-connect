package com.osrsbestinslot.export;

import java.util.*;

/** Narrow event projection of the already captured own hits and observed NPC ends. */
final class OwnCombatEvent
{
    private OwnCombatEvent() {}
    static Map<String,Object> from(String session, Map<String,Object> row)
    {
        String kind = String.valueOf(row.get("kind"));
        boolean hit = "combat.own_hits".equals(kind);
        if (!hit && !"combat.npc_lifecycle".equals(kind)) return null;
        if (session == null || !session.matches("[a-f0-9-]{36}") || !Boolean.TRUE.equals(row.get("direct"))
            || !(row.get("value") instanceof Map)) return null;
        Map<?,?> value = (Map<?,?>) row.get("value");
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("type", hit ? "combat_hit" : "combat_npc_end");
        out.put("combat_schema", 1); out.put("gameplay_session", session.replace("-", ""));
        for (String key : Arrays.asList("sequence", "tick", "observed_at_ms"))
        {
            Object v = row.get(key);
            if (!(v instanceof Number) || ((Number)v).longValue() < ("sequence".equals(key) ? 1 : 0)) return null;
            out.put("sequence".equals(key) ? "source_sequence" : "tick".equals(key) ? "source_tick" : key, v);
        }
        out.put("source", row.get("source"));
        if (hit)
        {
            if (!"HitsplatApplied".equals(row.get("source"))) return null;
            Object direction=value.get("direction");
            if (!"received".equals(direction) && !"dealt_to_npc".equals(direction)) return null;
            out.put("direction", direction);
            for(String key : Arrays.asList("amount", "hitsplat_type"))
            {
                Object v=value.get(key); if(!(v instanceof Number)||((Number)v).longValue()<0)return null;
                out.put(key,v);
            }
            if ("received".equals(direction)) return out; // Never record an attacker or another player.
        }
        Object npc=value.get("npc_id"), target=value.get("target_observation_id");
        if(!(npc instanceof Number)||((Number)npc).longValue()<0||!(target instanceof String)
            || !((String)target).matches("[a-f0-9-]{36}"))return null;
        out.put("npc_id",npc);out.put("target_key",((String)target).replace("-",""));
        if (!hit)
        {
            Object end=value.get("end"),first=value.get("first_own_hit_tick");
            if(!("observed_death".equals(end)&&"ActorDeath".equals(row.get("source"))
                || "despawn_not_death".equals(end)&&"NpcDespawned".equals(row.get("source")))
                || !(first instanceof Number)||((Number)first).longValue()<0
                || ((Number)first).longValue()>((Number)row.get("tick")).longValue())return null;
            out.put("first_own_hit_tick",first);out.put("end",end);out.put("kill_credit","unknown");
        }
        return out;
    }
}
