package com.osrsbestinslot.export;

import java.util.*;

/**
 * Public Hub wire contract. The server may grant collection to an exact token and account.
 * A collection grant widens permitted event fields but never grants staff identity or access rights.
 */
final class PublicTransport
{
    private PublicTransport() {}

    private static final Map<String, Set<String>> FIELDS = new HashMap<>();
    private static final Set<String> ENVELOPE = keys("type ts event_id account_hash rsn profile_type world world_members verification_tick verification_revision verification_text");
    private static final Set<String> ITEMS = keys("given received equipped unequipped items items_lost deposited withdrawn worn inventory");
    static
    {
        allow("trade", "given received world world_members");
        allow("ge_offer ge_progress ge_buy ge_sell ge_cancel ge_collect",
            "item qty price gp qty_total slot state side offer_key offer_seen ge_observation world world_members");
        allow("store_buy store_sell", "item qty gp_total coins_unchanged qty_merged qty_executed qty_partial unit_price_gp world loc");
        allow("drop", "item qty location wilderness tile drop_session_id drop_seq");
        allow("ground_removed", "item qty location ticks_on_ground cause recipient drop_session_id drop_seq attribution_uncertain tile");
        allow("pickup alch", "item qty spell gp location");
        allow("login", "wealth world world_members");
        allow("logout", "wealth session_ms reason world world_members");
        allow("world_hop", "from to");
        allow("region", "from to x y");
        allow("death", "location death_kind death_tick items_lost");
        allow("equip_change", "equipped unequipped");
        allow("loot", "source_name source_type npc_id items value");
        allow("kill_count", "source kind count");
        allow("kill_timing", "seq label duration_s new_pb personal_best_s team_size room");
        allow("kill_context", "source count worn inventory levels_boosted_real special_energy world_members toa_raid_level");
        allow("activity_counter", "key from to kind seen");
        allow("slayer_task", "tasks_completed task_target_id");
        allow("progress", "kind subject count tier task_id shipwreck_type quantity source");
        allow("attempt_end", "outcome basis");
        allow("minigame", "minigame result detail count total screen");
        allow("verification_discovery", "activity context_basis surface widget region_id discovery_status");
        allow("verification", "area check tick snapshot_revision plugin_version trigger trigger_key trigger_value world ref part parts total mismatches unknown results source_event_id source_event_type source_event_tick source_event_revision");
        allow("pet", ""); // the event is useful; unstructured chat text is not sent
        allow("level_up", "skill level xp");
        allow("xp_gain", "gains total");
        allow("bank_session", "deposited withdrawn complete truncated");
        allow("combat_hit combat_npc_end", "combat_schema gameplay_session source_sequence source_tick observed_at_ms source direction amount hitsplat_type npc_id target_key worn first_own_hit_tick end kill_credit");
        allow("own_vars", "");
        allow("own_signal", "");
        allow("own_state", ""); // the nested body uses the closed OwnStateEvent projection below
        allow("bank_visit_open", "visit_id opened_at_ms read_at_ms bank_state item_stacks manifest_sha256 plugin_version manifest_in_snapshot");
        allow("bank_visit_close", "visit_id closed_at_ms close_reason manifest_complete moves_complete deposited_sha256 withdrawn_sha256 end_manifest_sha256 end_chunks end_item_stacks manifest_in_snapshot deposited withdrawn deposited_chunks withdrawn_chunks moves_chunks");
        allow("bank_visit_manifest_chunk", "visit_id phase chunk chunks manifest_sha256 items");
        // No fh_batch, store_taken or media manifests: these are observations of others / Ops proof.
    }
    private static Set<String> keys(String s) { return new HashSet<>(Arrays.asList(s.split(" "))); }
    private static void allow(String types, String fields)
    {
        for (String type : types.split(" ")) FIELDS.put(type, keys(fields));
    }
    static boolean allowsEvent(String type) { return FIELDS.containsKey(type); }
    private static final Set<String> STAFF_EVENT_TYPES = keys("store_taken drop_trade_clip drop_trade_clip_final");
    static boolean allowsEvent(String type, boolean verifiedStaff)
    {
        return allowsEvent(type) || (verifiedStaff && STAFF_EVENT_TYPES.contains(type));
    }

    /**
     * The public projection, unless the server has granted staff capture to this exact token and account.
     * A staff row keeps every field the plugin wrote, as staff clients sent before the public boundary
     * (other players' names, counterparty, recipient, media manifests). Local delivery keys ("__...") never leave.
     */
    static Map<String, Object> event(Map<String, Object> input, boolean verifiedStaff)
    {
        Map<String, Object> standard = event(input);
        if (!verifiedStaff || input == null || ("verification".equals(input.get("type")) || "verification_discovery".equals(input.get("type")) || "own_state".equals(input.get("type"))
            || "own_vars".equals(input.get("type")) || "own_signal".equals(input.get("type")))) return standard;
        String type = String.valueOf(input.get("type"));
        if (!allowsEvent(type, true)) return null;
        // A row the public projection refuses stays refused, unless it is one of the staff-only types.
        if (standard == null && !STAFF_EVENT_TYPES.contains(type)) return null;
        Map<String, Object> out = standard == null ? new LinkedHashMap<>() : standard;
        for (Map.Entry<String, Object> e : input.entrySet())
        {
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || key.startsWith("__")) continue;
            if ("observation_context".equals(key)) { out.put(key, EventObservationContext.project(value)); continue; }
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Map || value instanceof List)
                out.put(key, value);
        }
        return out;
    }

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
            if ("verification_text".equals(k))
            {
                if (EventVerification.TYPES.contains(type) && v instanceof String && v.equals(EventVerification.clean((String) v))) out.put(k, v);
            }
            else if ("verification_tick".equals(k) || "verification_revision".equals(k))
            {
                if (EventVerification.TYPES.contains(type) && v instanceof Number) out.put(k, v);
            }
            else if ("observation_context".equals(k)) out.put(k, EventObservationContext.project(v));
            else if (ENVELOPE.contains(k))
            {
                if (v instanceof String || v instanceof Number || v instanceof Boolean) out.put(k,v);
            }
            else if (fields.contains(k))
            {
                if ("results".equals(k) && "verification".equals(type)) out.put(k, VerificationProbe.project(v));
                else if ("ge_observation".equals(k)) out.put(k, geObservation(v));
                else if (ITEMS.contains(k)) out.put(k, itemRows(v));
                else if ("levels_boosted_real".equals(k)) out.put(k, levelPairs(v));
                else if ("screen".equals(k))
                    out.put(k, numericMap(v, MinigameLines.SCREEN_LABELS));	// known labels, numbers only
                else if ("location".equals(k) || "tile".equals(k))
                    out.put(k, numericMap(v, keys("x y plane region_id")));
                else if ("gains".equals(k) && v instanceof List)
                {
                    List<Map<String,Object>> gains = new ArrayList<>();
                    for (Object entry : (List<?>) v) if (entry instanceof Map)
                    {
                        Map<?,?> g = (Map<?,?>) entry;
                        if (g.get("skill") instanceof String && g.get("xp") instanceof Number)
                        {
                            Map<String,Object> gain = new LinkedHashMap<>();
                            gain.put("skill", g.get("skill")); gain.put("xp", g.get("xp")); gains.add(gain);
                        }
                    }
                    out.put(k, gains);
                }
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
        if ("own_signal".equals(type))
        {
            Map<String,Object> signal=OwnSignalEvent.wire(input);if(signal==null)return null;out.putAll(signal);
        }
        if ("own_vars".equals(type))
        {
            Map<String,Object> vars = OwnVarsEvent.wire(input);
            if (vars == null) return null;
            out.putAll(vars);
        }
        if ("own_state".equals(type))
        {
            Map<String,Object> state = OwnStateEvent.wire(input);
            if (state == null) return null;
            out.putAll(state);
        }
        return out;
    }
    private static Map<String,Object> geObservation(Object input)
    {
        Set<String> numbers = keys("schema_v sequence cumulative_qty cumulative_gp delta_from_qty delta_from_gp observed_at_ms counter_observed_at_ms listing_price execution_price tax_gp delta_qty delta_gp interval_start_ms interval_end_ms");
        Set<String> values = keys("runelite_grand_exchange_offer runelite_spent_tax_unknown ambiguous_counter_regression inferred_across_gap inferred_session_slot collector_session account_profile unknown baseline_only observed_counter_difference");
        Set<String> labels = keys("source gp_semantics identity offer_key_scope delta_status fill_time");
        Map<String,Object> out = new LinkedHashMap<>();
        if (input instanceof Map) for (Map.Entry<?,?> e : ((Map<?,?>) input).entrySet())
        {
            Object key = e.getKey(), value = e.getValue();
            if (numbers.contains(key) && (value == null || value instanceof Number)
                || labels.contains(key) && value instanceof String && values.contains(value))
                out.put((String) key, value);
        }
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
    /** Skill name to [boosted, real], combat skills only; anything else is dropped. */
    private static Map<String,Object> levelPairs(Object input)
    {
        Map<String,Object> out = new LinkedHashMap<>();
        if (input instanceof Map) for (Map.Entry<?,?> e : ((Map<?,?>) input).entrySet())
        {
            if (!(e.getKey() instanceof String) || !Arrays.asList(KillContext.SKILLS).contains(e.getKey()) || !(e.getValue() instanceof List)) continue;
            List<?> pair = (List<?>) e.getValue();
            if (pair.size() == 2 && pair.get(0) instanceof Number && pair.get(1) instanceof Number)
                out.put((String) e.getKey(), Arrays.asList(((Number) pair.get(0)).intValue(), ((Number) pair.get(1)).intValue()));
        }
        return out;
    }
    private static List<Object> itemRows(Object input)
    {
        List<Object> out = new ArrayList<>();
        if (input instanceof List) for (Object item : (List<?>) input)
        {
            // Bank manifests use integer pairs, both in-memory long[] and JSON replay Lists.
            if (item instanceof long[] && ((long[])item).length == 2)
            {
                out.add(((long[])item).clone()); continue;
            }
            if (item instanceof List)
            {
                List<?> pair = (List<?>)item;
                if (pair.size() == 2 && pair.get(0) instanceof Number && pair.get(1) instanceof Number)
                    out.add(Arrays.asList(((Number)pair.get(0)).longValue(), ((Number)pair.get(1)).longValue()));
                continue;
            }
            Map<String,Object> row = numericMap(item, keys("id qty slot"));
            if (row.containsKey("id") && row.containsKey("qty")) out.add(row);
        }
        return out;
    }
}
