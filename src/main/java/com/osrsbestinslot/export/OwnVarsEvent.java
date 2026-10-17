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
        List<String> chart=new ArrayList<>();
        for(int id:OwnActivityMappings.CHARTING)chart.add(String.valueOf(id));
        m.put("sailing.charting",Collections.unmodifiableList(chart));
        List<String> potions=new ArrayList<>();
        for(int id:OwnPotionStorageCapture.VARPS)potions.add(String.valueOf(id));
        potions.add("unlocked_raw");m.put("storage.potion_store",Collections.unmodifiableList(potions));
        List<String> totems=new ArrayList<>();totems.add("research_points_raw");
        for(int site=1;site<=OwnActivityMappings.TOTEMS.length;site++)
            for(String field:OwnActivityMappings.TOTEM_FIELDS)totems.add("site_"+site+"."+field);
        m.put("minigame.totem_state",Collections.unmodifiableList(totems));
        m.put("sailing.port_tasks",List.of("5197","5198","5199","5200","5201","5202",
            "completed_total_raw","bounty_item_counter_raw","extra_slots_raw","completed_today_raw"));
        m.put("storage.bank_tabs",List.of("tab_1_slots","tab_2_slots","tab_3_slots","tab_4_slots","tab_5_slots",
            "tab_6_slots","tab_7_slots","tab_8_slots","tab_9_slots"));
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
        input.put("source_tick",row.get("tick")); input.put("observed_at_ms",row.get("observed_at_ms"));
        Map<String,Object> flat=new LinkedHashMap<>();
        for(String key:FIELDS.get(row.get("kind")))
        {
            Map<?,?> source=value;
            if("storage.potion_store".equals(row.get("kind"))&&!key.equals("unlocked_raw"))
                source=value.get("storage_values_raw") instanceof Map?(Map<?,?>)value.get("storage_values_raw"):Collections.emptyMap();
            if("sailing.port_tasks".equals(row.get("kind"))&&key.matches("[0-9]+"))
                source=value.get("packed_slots_raw") instanceof Map?(Map<?,?>)value.get("packed_slots_raw"):Collections.emptyMap();
            String field=key;
            if("minigame.totem_state".equals(row.get("kind"))&&key.contains("."))
            {
                String[] path=key.split("\\.",2);field=path[1];
                source=value.get(path[0]) instanceof Map?(Map<?,?>)value.get(path[0]):Collections.emptyMap();
            }
            flat.put(key,source.get(field));
        }
        input.put("values",flat);
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
