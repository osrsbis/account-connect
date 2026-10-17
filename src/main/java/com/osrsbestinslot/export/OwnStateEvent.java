package com.osrsbestinslot.export;

import java.util.*;

/** Closed projections of audited own-state families. Unknown is never an empty balance or gain. */
final class OwnStateEvent
{
    private OwnStateEvent() {}
    static final List<String> SKILLS = Arrays.asList("ATTACK", "STRENGTH", "RANGED", "MAGIC", "DEFENCE",
        "HITPOINTS", "PRAYER", "AGILITY", "HERBLORE", "THIEVING", "CRAFTING", "RUNECRAFT", "MINING",
        "SMITHING", "FISHING", "COOKING", "FIREMAKING", "WOODCUTTING", "FLETCHING", "SLAYER", "FARMING",
        "CONSTRUCTION", "HUNTER", "SAILING");
    private static final Map<String,String> KINDS = Map.of("skills.goal_xp", "skill_goals",
        "storage.rune_pouch", "rune_pouch", "storage.bank_tab_currency", "bank_tabs",
        "travel.fairy_destinations","fairy_favourites","minigame.mixology_orders","mixology_orders",
        "travel.spirit_destinations","spirit_destinations","combat.raid_context","raid_context","clues.destination","clue_inventory",
        "loot.key_interface","loot_key_interface","loot.key_slot","loot_key_slot");
    static boolean supportsWireKind(Object kind) { return kind instanceof String && KINDS.containsValue(kind); }
    static boolean supports(Object kind) { return kind instanceof String && KINDS.containsKey(kind); }
    static Map<String,Object> wire(Map<String,Object> input)
    {
        try
        {
            if (number(input.get("state_schema"), 1, 1) != 1 || !"own_client_state".equals(input.get("source"))) return null;
            String kind = choice(input.get("state_kind"), new ArrayList<>(KINDS.values()));
            Object session = input.get("gameplay_session");
            if (!(session instanceof String) || !((String)session).matches("[a-f0-9]{32}")) return null;
            boolean direct = !"bank_tabs".equals(kind);
            if (!Boolean.valueOf(direct).equals(input.get("direct"))) return null;
            Map<String,Object> value = projectValue(kind, input.get("state_value")); if (value == null) return null;
            Map<String,Object> out = new LinkedHashMap<>(); out.put("state_schema", 1); out.put("state_kind", kind);
            out.put("source", "own_client_state"); out.put("gameplay_session", session); out.put("direct", direct);
            out.put("observation", choice(input.get("observation"), Arrays.asList("baseline", "absolute_update")));
            out.put("source_sequence", number(input.get("source_sequence"), 1, 9007199254740991L));
            out.put("source_tick", number(input.get("source_tick"), 0, Integer.MAX_VALUE));
            long at = number(input.get("observed_at_ms"), 1000000000000L, 9007199254740991L);
            out.put("observed_at_ms", at); out.put("state_value", value);
            if (("bank_tabs".equals(kind)||"loot_key_slot".equals(kind)) && value.get("container_observed_at_ms") != null
                && (Long)value.get("container_observed_at_ms") > at) return null;
            return out;
        }
        catch (IllegalArgumentException invalid) { return null; }
    }
    static Map<String,Object> from(String session, Map<String,Object> row)
    {
        if (!supports(row.get("kind")) || session == null || !session.matches("[a-f0-9-]{36}")) return null;
        try
        {
            String kind = KINDS.get(row.get("kind")); Map<?,?> value = map(row.get("value"));
            Object observation = value.get("observation");
            if (!Arrays.asList("baseline", "absolute_update").contains(observation)) return null;
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("type", "own_state"); out.put("state_schema", 1); out.put("state_kind", kind);
            out.put("gameplay_session", session.replace("-", "")); out.put("source", "own_client_state");
            out.put("source_sequence", number(row.get("sequence"), 1, Long.MAX_VALUE));
            out.put("source_tick", number(row.get("tick"), 0, Integer.MAX_VALUE));
            out.put("observed_at_ms", number(row.get("observed_at_ms"), 1, 9007199254740991L));
            out.put("observation", observation); out.put("direct", !"bank_tabs".equals(kind));
            Object shaped = value;
            if ("skill_goals".equals(kind))
            {
                List<Map<String,Object>> goals = new ArrayList<>();
                for (String skill : SKILLS)
                {
                    Map<String,Object> goal = new LinkedHashMap<>();
                    map(value.get(skill)).forEach((k,v) -> { if (k instanceof String) goal.put((String)k, v); });
                    goal.put("skill", skill); goals.add(goal);
                }
                shaped = Map.of("goals", goals);
            }
            Map<String,Object> projected = projectValue(kind, shaped);
            if (projected == null) return null;
            out.put("state_value", projected);
            Map<String,Object> checked = wire(out);
            if (checked != null) checked.put("type", "own_state");
            return checked;
        }
        catch (IllegalArgumentException invalid) { return null; }
    }

    static Map<String,Object> projectValue(Object kind, Object input)
    {
        try
        {
            Map<?,?> p = map(input); Map<String,Object> out = new LinkedHashMap<>();
            if("loot_key_interface".equals(kind)||"loot_key_slot".equals(kind))return OwnStorageProjection.value((String)kind,p);
            if ("skill_goals".equals(kind))
            {
                List<?> values = list(p.get("goals"), 24); if (values.size() != 24) throw new IllegalArgumentException();
                Set<String> seen = new HashSet<>(); List<Map<String,Object>> goals = new ArrayList<>();
                for (Object value : values)
                {
                    Map<?,?> g = map(value); String skill = choice(g.get("skill"), SKILLS);
                    if (!seen.add(skill)) throw new IllegalArgumentException();
                    Long start = nullable(g.get("start_xp")), end = nullable(g.get("end_xp"));
                    String status = start == null || end == null ? "unknown" : end == 0 ? "unset"
                        : start < 0 || end <= start || end > 200000000 ? "invalid" : "configured";
                    if (!status.equals(g.get("status"))) throw new IllegalArgumentException();
                    Map<String,Object> goal = new LinkedHashMap<>(); goal.put("skill", skill);
                    goal.put("start_xp", start); goal.put("end_xp", end); goal.put("status", status); goals.add(goal);
                }
                out.put("goals", goals);
            }
            else if ("rune_pouch".equals(kind))
            {
                out.put("contents_semantics", "client_cached_slots"); out.put("complete", false);
                String possession = choice(p.get("possession"), Arrays.asList("inventory_unavailable", "not_observed_carried", "carried"));
                out.put("possession", possession);
                if (!"carried".equals(possession)) return out;
                List<Long> ids = new ArrayList<>();
                for (Object id : list(p.get("pouch_item_ids"), 4))
                {
                    long v = number(id, 1, Integer.MAX_VALUE);
                    if (!Arrays.asList(12791L, 24416L, 27281L, 27509L).contains(v)) throw new IllegalArgumentException();
                    ids.add(v);
                }
                if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException();
                out.put("pouch_item_ids", ids); List<?> values = list(p.get("slots"), 6);
                if (values.size() != 6) throw new IllegalArgumentException();
                List<Map<String,Object>> slots = new ArrayList<>(); Map<Long,Long> totals = new TreeMap<>(); boolean decoded = true;
                for (int i = 0; i < values.size(); i++)
                {
                    Map<?,?> s = map(values.get(i)); if (number(s.get("slot"), 0, 5) != i) throw new IllegalArgumentException();
                    Long type = nullable(s.get("type_raw")), amount = nullable(s.get("amount"));
                    String status = choice(s.get("status"), Arrays.asList("unavailable", "inconsistent_cache", "observed_empty", "mapping_unknown", "observed_cached"));
                    String expected = type == null || amount == null ? "unavailable"
                        : type < 0 || amount < 0 || type == 0 && amount > 0 ? "inconsistent_cache"
                        : amount == 0 ? "observed_empty" : s.get("item") == null ? "mapping_unknown" : "observed_cached";
                    if (!expected.equals(status)) throw new IllegalArgumentException();
                    Map<String,Object> slot = new LinkedHashMap<>(); slot.put("slot", i); slot.put("type_raw", type);
                    slot.put("amount", amount); slot.put("status", status);
                    if ("observed_cached".equals(status))
                    {
                        long item = number(s.get("item"), 1, Integer.MAX_VALUE); slot.put("item", item);
                        totals.put(item, totals.getOrDefault(item, 0L) + amount);
                    }
                    else if (!"observed_empty".equals(status)) decoded = false;
                    slots.add(slot);
                }
                List<Map<String,Object>> items = new ArrayList<>();
                totals.forEach((id,qty) -> items.add(Map.of("id", id, "qty", qty)));
                out.put("slots", slots); out.put("observed_items", items); out.put("all_slots_decoded", decoded);
            }
            else if ("bank_tabs".equals(kind))
            {
                out.put("basis", "inferred_raw_tab_ranges_same_session_cached_bank"); out.put("complete", false);
                String status = choice(p.get("status"), Arrays.asList("own_bank_packet_unavailable", "clock_or_tick_gap",
                    "ordinary_bank_ui_unavailable", "bank_cache_changed_without_packet", "tab_counts_unknown",
                    "tab_counts_exceed_container", "observed_cache_ranges")); out.put("status", status);
                Object at = p.get("container_observed_at_ms");
                out.put("container_observed_at_ms", at == null ? null : number(at, 1, 9007199254740991L));
                out.put("tabs", null); out.put("untabbed", null);
                if (!"observed_cache_ranges".equals(status)) return out;
                if (at == null) throw new IllegalArgumentException();
                List<?> values = list(p.get("tabs"), 9); if (values.size() != 9) throw new IllegalArgumentException();
                List<Map<String,Object>> tabs = new ArrayList<>(); long start = 0;
                for (int i = 0; i < 9; i++)
                {
                    Map<?,?> raw = map(values.get(i)); if (number(raw.get("tab"), 1, 9) != i + 1) throw new IllegalArgumentException();
                    Map<String,Object> range = range(raw, start); start += (Long)range.get("slot_count");
                    range.put("tab", i + 1); tabs.add(range);
                }
                out.put("tabs", tabs); out.put("untabbed", range(map(p.get("untabbed")), start));
            }
            else if("clue_inventory".equals(kind))return OwnClueProjection.inventory(p);
            else if("fairy_favourites".equals(kind))
            {
                List<?> raw=list(p.get("favourite_codes_visible"),10);if(raw.size()!=10)throw new IllegalArgumentException();
                List<Object> codes=new ArrayList<>();for(Object code:raw)
                {if(code!=null&&(!(code instanceof String)||!((String)code).matches("[ABCD][IJKL][PQRS]")))throw new IllegalArgumentException();codes.add(code);}
                out.put("favourite_codes_visible",codes);out.put("authority","visible_ui_not_unlock_proof");
            }
            else if("mixology_orders".equals(kind))
            {
                List<Object> codes=null;
                if(p.get("orders_observed")!=null)
                {
                    List<?> raw=list(p.get("orders_observed"),3);if(raw.size()!=3)throw new IllegalArgumentException();
                    codes=new ArrayList<>();for(Object code:raw){if(!OwnInterfaceCapture.mixologyCode(code))throw new IllegalArgumentException();codes.add(code);}
                }
                out.put("orders_observed",codes);out.put("authority","visible_ui_not_completion");
            }
            else if("spirit_destinations".equals(kind))
            {
                List<Map<String,Object>> destinations=null;
                if(p.get("visible_destinations")!=null)
                {
                    destinations=new ArrayList<>();for(Object row:list(p.get("visible_destinations"),32))
                    {
                        Map<?,?> r=map(row);Object name=r.get("destination"),disabled=r.get("disabled_style_observed");
                        if(!OwnInterfaceCapture.spiritDestination(name)||!(disabled instanceof Boolean))throw new IllegalArgumentException();
                        destinations.add(Map.of("destination",name,"disabled_style_observed",disabled));
                    }
                }
                out.put("visible_destinations",destinations);out.put("authority","classic_ui_not_unlock_proof");
            }
            else if("raid_context".equals(kind))
            {
                Long status=nullable(p.get("party_status_raw"));Object room=p.get("room_observed");
                if(room!=null&&(!Long.valueOf(2).equals(status)||!OwnActivityCapture.raidRoom(room)))throw new IllegalArgumentException();
                out.put("party_status_raw",status);out.put("room_observed",room);
            }
            else throw new IllegalArgumentException();
            return out;
        }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private static Map<String,Object> range(Map<?,?> p, long start)
    {
        if (number(p.get("start_slot"), 0, 4096) != start) throw new IllegalArgumentException();
        long count = number(p.get("slot_count"), 0, 4096 - start), coins = number(p.get("coins"), 0, 9007199254740991L),
            platinum = number(p.get("platinum_tokens"), 0, 9007199254740991L);
        if (coins > count * Integer.MAX_VALUE || platinum > count * Integer.MAX_VALUE
            || coins + platinum > count * Integer.MAX_VALUE) throw new IllegalArgumentException();
        long equivalent = coins + platinum * 1000;
        if (number(p.get("currency_gp_equivalent"), 0, 9007199254740991L) != equivalent) throw new IllegalArgumentException();
        return new LinkedHashMap<>(Map.of("start_slot", start, "slot_count", count, "coins", coins,
            "platinum_tokens", platinum, "currency_gp_equivalent", equivalent));
    }
    private static Map<?,?> map(Object x) { if (!(x instanceof Map)) throw new IllegalArgumentException(); return (Map<?,?>)x; }
    private static List<?> list(Object x, int max) { if (!(x instanceof List) || ((List<?>)x).size() > max) throw new IllegalArgumentException(); return (List<?>)x; }
    private static String choice(Object x, List<String> allowed) { if (!(x instanceof String) || !allowed.contains(x)) throw new IllegalArgumentException(); return (String)x; }
    private static Long nullable(Object x) { return x == null ? null : number(x, Integer.MIN_VALUE, Integer.MAX_VALUE); }
    private static long number(Object x, long min, long max)
    {
        if (!(x instanceof Number)) throw new IllegalArgumentException();
        Number n = (Number)x; long v = n.longValue();
        if (!Double.isFinite(n.doubleValue()) || n.doubleValue() != v || v < min || v > max) throw new IllegalArgumentException();
        return v;
    }
}
