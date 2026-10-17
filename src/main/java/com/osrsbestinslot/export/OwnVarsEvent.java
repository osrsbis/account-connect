package com.osrsbestinslot.export;

import java.util.*;

/** Closed raw own-player counters/settings; never inferred gains, durations or completion. */
final class OwnVarsEvent
{
    private OwnVarsEvent() {}
    static final Map<String,List<String>> FIELDS;
    static
    {
        Map<String,List<String>> m = new LinkedHashMap<>();
        m.put("effects.surge", List.of("timer_raw"));
        m.put("effects.death_charge", List.of("active_raw", "cooldown_raw"));
        m.put("effects.escape_crystal", List.of("afk_mode_raw", "afk_delay_raw", "ring_of_life_raw"));
        m.put("minigame.arena_state", List.of("entry_paid_raw", "cooldown_raw", "ticket_available_raw"));
        m.put("combat.barrows_state", List.of("ahrim", "dharok", "guthan", "karil", "torag", "verac", "reward_potential_raw", "npcs_slain_raw"));
        m.put("combat.raid_points", List.of("in_raid", "personal_points"));
        m.put("minigame.tears", List.of("countdown_raw", "tears_raw", "collecting_raw"));
        m.put("minigame.brewing", List.of("own_contribution_raw"));
        m.put("minigame.restaurant_order", List.of("started_raw", "customer_id_raw", "order_id_raw", "refused_hard_raw"));
        m.put("minigame.plunder_state", List.of("timer_raw", "room_raw"));
        m.put("sailing.trial_progress", List.of("tempor_tantrum_raw", "jubbly_jive_raw", "gwenith_glide_raw"));
        m.put("combat.special_attack", List.of("energy_raw"));
        FIELDS = Collections.unmodifiableMap(m);
    }
    static boolean supports(Object family) { return FIELDS.containsKey(family); }
    static Map<String,Object> wire(Map<String,Object> input)
    {
        try
        {
            if (number(input.get("vars_schema"),1,1) != 1 || !"own_client_vars".equals(input.get("source"))
                || !Boolean.TRUE.equals(input.get("direct"))) return null;
            Object family = input.get("var_family"), session = input.get("gameplay_session");
            if (!supports(family) || !(session instanceof String) || !((String)session).matches("[a-f0-9]{32}")) return null;
            Object observation = input.get("observation");
            if (!Arrays.asList("baseline","absolute_update").contains(observation)) return null;
            Object raw = input.get("values"); if (!(raw instanceof Map)) return null;
            Map<?,?> values = (Map<?,?>)raw; Map<String,Object> closed = new LinkedHashMap<>();
            for (String key : FIELDS.get(family))
            {
                // The existing Gson wire omits null map values; omitted is unknown, never zero.
                Object value = values.get(key);
                closed.put(key,value == null ? null : number(value,Integer.MIN_VALUE,Integer.MAX_VALUE));
            }
            if ("combat.raid_points".equals(family) && !Long.valueOf(1).equals(closed.get("in_raid"))
                && closed.get("personal_points") != null) return null;
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("vars_schema",1); out.put("var_family",family); out.put("source","own_client_vars");
            out.put("gameplay_session",session); out.put("direct",true); out.put("observation",observation);
            out.put("source_sequence",number(input.get("source_sequence"),1,9007199254740991L));
            out.put("source_tick",number(input.get("source_tick"),0,Integer.MAX_VALUE));
            out.put("observed_at_ms",number(input.get("observed_at_ms"),1000000000000L,9007199254740991L));
            out.put("values",closed); return out;
        }
        catch (IllegalArgumentException invalid) { return null; }
    }
    static Map<String,Object> from(String session, Map<String,Object> row)
    {
        if (!supports(row.get("kind")) || session == null || !session.matches("[a-f0-9-]{36}") || !(row.get("value") instanceof Map)) return null;
        Map<?,?> value = (Map<?,?>)row.get("value"); Map<String,Object> input = new LinkedHashMap<>();
        input.put("vars_schema",1); input.put("var_family",row.get("kind")); input.put("source","own_client_vars");
        input.put("gameplay_session",session.replace("-","")); input.put("direct",row.get("direct"));
        input.put("observation",value.get("observation")); input.put("source_sequence",row.get("sequence"));
        input.put("source_tick",row.get("tick")); input.put("observed_at_ms",row.get("observed_at_ms")); input.put("values",value);
        Map<String,Object> out = wire(input); if (out != null) out.put("type","own_vars"); return out;
    }
    private static long number(Object x,long min,long max)
    {
        if (!(x instanceof Number)) throw new IllegalArgumentException();
        Number n=(Number)x;long v=n.longValue();
        if (!Double.isFinite(n.doubleValue()) || n.doubleValue()!=v || v<min || v>max) throw new IllegalArgumentException();
        return v;
    }
}
