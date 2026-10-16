package com.osrsbestinslot.export;

import java.util.*;

/**
 * Public Hub wire contract. Remote grants, tokens and JVM properties cannot widen it.
 * Private operational capture requires a separately reviewed distribution, not a Hub toggle.
 */
final class PublicTransport
{
    private PublicTransport() {}
    static boolean mediaAllowed() { return false; }
    static boolean firehoseAllowed() { return false; }

    private static final Map<String, Set<String>> FIELDS = new HashMap<>();
    private static final Set<String> ENVELOPE = keys("type ts event_id account_hash rsn profile_type");
    private static final Set<String> ITEMS = keys("given received equipped unequipped items items_lost deposited withdrawn");
    static
    {
        allow("trade", "given received world world_members");
        allow("ge_offer ge_progress ge_buy ge_sell ge_cancel ge_collect",
            "item qty price gp qty_total slot state side offer_key offer_seen world world_members");
        allow("store_buy store_sell", "item qty gp_total qty_merged qty_executed qty_partial unit_price_gp world loc");
        allow("drop", "item qty location wilderness tile drop_session_id drop_seq");
        allow("ground_removed", "item qty location ticks_on_ground cause recipient drop_session_id drop_seq attribution_uncertain tile");
        allow("pickup alch", "item qty spell gp location");
        allow("login", "wealth world world_members");
        allow("logout", "wealth session_ms reason world world_members");
        allow("world_hop", "from to");
        allow("region", "from to x y");
        allow("death", "location death_kind items_lost");
        allow("equip_change", "equipped unequipped");
        allow("loot", "source_name source_type items value");
        allow("kill_count", "source kind count");
        allow("pet", ""); // the event is useful; unstructured chat text is not sent
        allow("level_up", "skill level xp");
        allow("xp_gain", "gains total");
        allow("bank_session", "deposited withdrawn complete truncated");
        allow("bank_visit_open", "visit_id opened_at_ms read_at_ms bank_state item_stacks manifest_sha256 plugin_version");
        allow("bank_visit_close", "visit_id closed_at_ms close_reason manifest_complete moves_complete deposited_sha256 withdrawn_sha256 end_manifest_sha256 end_chunks deposited withdrawn deposited_chunks withdrawn_chunks moves_chunks");
        allow("bank_visit_manifest_chunk", "visit_id phase chunk chunks manifest_sha256 items");
        // No fh_batch, store_taken or media manifests: these are observations of others / Ops proof.
    }
    private static Set<String> keys(String s) { return new HashSet<>(Arrays.asList(s.split(" "))); }
    private static void allow(String types, String fields)
    {
        for (String type : types.split(" ")) FIELDS.put(type, keys(fields));
    }
    static boolean allowsEvent(String type) { return FIELDS.containsKey(type); }

    /** null means local privacy rejection, never an acknowledged server delivery. */
    static Map<String, Object> event(Map<String, Object> input)
    {
        if (input == null) return null;
        String type = String.valueOf(input.get("type"));
        Set<String> fields = FIELDS.get(type);
        if (fields == null) return null;
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : input.entrySet())
        {
            String k = e.getKey(); Object v = e.getValue();
            if (ENVELOPE.contains(k))
            {
                if (v instanceof String || v instanceof Number) out.put(k,v);
            }
            else if (fields.contains(k))
            {
                if (ITEMS.contains(k)) out.put(k, itemRows(v));
                else if ("location".equals(k) || "tile".equals(k))
                    out.put(k, numericMap(v, keys("x y plane region_id")));
                else if ("gains".equals(k)) out.put(k, numericMap(v, null));
                else if ("loc".equals(k) && v instanceof List)
                {
                    List<?> loc = (List<?>) v;
                    if (loc.size() == 3 && loc.stream().allMatch(n -> n instanceof Number))
                        out.put(k, new ArrayList<>(loc));
                }
                else if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String)
                    out.put(k,v);
            }
        }
        // Legacy PvP loot must never bring the victim's name back through disk replay.
        if ("loot".equals(type) && !"npc".equals(input.get("source_type")) && !"event".equals(input.get("source_type")))
            out.remove("source_name");
        if ("ground_removed".equals(type)) out.put("recipient", "UNKNOWN");
        return out;
    }
    private static Map<String,Object> numericMap(Object input, Set<String> allowed)
    {
        Map<String,Object> out = new LinkedHashMap<>();
        if (input instanceof Map) for (Map.Entry<?,?> e : ((Map<?,?>) input).entrySet())
            if (e.getKey() instanceof String && (allowed == null || allowed.contains(e.getKey())) && e.getValue() instanceof Number)
                out.put((String)e.getKey(), e.getValue());
        return out;
    }
    private static List<Map<String,Object>> itemRows(Object input)
    {
        List<Map<String,Object>> out = new ArrayList<>();
        if (input instanceof List) for (Object item : (List<?>) input)
        {
            Map<String,Object> row = numericMap(item, keys("id qty slot"));
            if (row.containsKey("id") && row.containsKey("qty")) out.add(row);
        }
        return out;
    }
}
