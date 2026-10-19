package com.osrsbestinslot.export;

import java.util.*;

/**
 * Deterministic derived activity record (schema osrsbis.activity-record.v1). Pure: the caller supplies
 * the already-projected events, the scope (identity, window, optional C2 encounter) and one dated price
 * snapshot. Raw events are never changed. A rate is published only when every value-bearing flow is
 * priced and no loss is unknown; otherwise it is null with the reasons. Costs this capture cannot see
 * (runes, ammunition, charges) are named, so a published net is an upper bound when that list is not empty.
 */
final class ActivityRecord
{
    private ActivityRecord() {}
    static final String SCHEMA = "osrsbis.activity-record.v1", INTERPRETATION = "c3-activity-v1";
    static final long IDLE_GAP_MS = 60_000;
    static final int MAX_CONTRIBUTING = 512;
    static final Map<String,List<String>> UNOBSERVED_COSTS = Map.of(
        "combat_encounter", List.of("ammunition", "runes", "weapon_charges", "cannonballs", "prayer_points_without_potions"),
        "bolt_enchanting", List.of("runes"),
        "alching", List.of("runes"),
        "generic", List.of("supplies_used_without_eat_drink_chew_click"));
    private static final Set<String> SESSION_BOUNDARIES = Set.of("login", "logout", "world_hop");

    /**
     * scope: activity_type, account_hash, profile_type, start_ms, end_ms (null = open), optional gameplay_session
     * (32 hex, filters own_* events) and optional encounter (C2 record, copied verbatim).
     */
    static Map<String,Object> derive(Map<String,Object> scope, List<Map<String,Object>> events, ActivityPrices prices)
    {
        String type = UNOBSERVED_COSTS.containsKey(scope.get("activity_type")) ? (String)scope.get("activity_type") : "generic";
        Long start = asLong(scope.get("start_ms")), end = asLong(scope.get("end_ms"));
        Map<?,?> encounter = scope.get("encounter") instanceof Map ? (Map<?,?>)scope.get("encounter") : null;
        if (encounter != null)
        {
            start = asLong(encounter.get("start_observed_at_ms")); end = asLong(encounter.get("end_observed_at_ms"));
        }
        String session = scope.get("gameplay_session") instanceof String ? (String)scope.get("gameplay_session") : null;
        List<String> gaps = new ArrayList<>();
        int duplicates = 0, identityMismatch = 0, outside = 0;

        // Queue delivery can duplicate and reorder; dedupe on event_id, then order by observed time.
        Map<String,Map<String,Object>> unique = new LinkedHashMap<>();
        for (Map<String,Object> e : events)
        {
            Object id = e.get("event_id"); Long at = ActivityFlows.at(e);
            if (!(id instanceof String) || at == null) { gaps.add("event_without_id_or_time"); continue; }
            if (!identityMatches(scope, e, session)) { identityMismatch++; continue; }
            if (start == null || at < start || (end != null && at > end)) { outside++; continue; }
            if (unique.putIfAbsent((String)id, e) != null) duplicates++;
        }
        List<Map<String,Object>> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparingLong((Map<String,Object> e) -> ActivityFlows.at(e))
            .thenComparingLong(e -> asLong(e.get("source_sequence")) == null ? 0 : asLong(e.get("source_sequence"))));

        List<Map<String,Object>> flows = new ArrayList<>();
        Map<String,Long> xp = new TreeMap<>();
        long transformationXp = 0; boolean xpFromFlush = false, boundary = false;
        List<Long> activityTimes = new ArrayList<>();
        List<String> contributing = new ArrayList<>();
        for (Map<String,Object> e : ordered)
        {
            String t = String.valueOf(e.get("type")); long at = ActivityFlows.at(e);
            List<Map<String,Object>> f = ActivityFlows.classify(e);
            boolean used = !f.isEmpty();
            flows.addAll(f);
            if (t.equals("xp_gain") && e.get("gains") instanceof List)
            {
                for (Object g : (List<?>)e.get("gains")) if (g instanceof Map)
                {
                    Long amount = asLong(((Map<?,?>)g).get("xp")); Object skill = ((Map<?,?>)g).get("skill");
                    if (amount != null && amount > 0 && skill instanceof String) xp.merge((String)skill, amount, Long::sum);
                }
                xpFromFlush = used = true;
            }
            if (t.equals("own_signal") && "skills.item_transformation".equals(e.get("signal_kind")) && e.get("signal_value") instanceof Map)
            {
                Long m = asLong(((Map<?,?>)e.get("signal_value")).get("magic_xp_change")); if (m != null) transformationXp += m;
            }
            if (SESSION_BOUNDARIES.contains(t)) { boundary = true; used = true; }
            if (t.equals("combat_hit")) used = true;
            if (used && !t.equals("xp_gain") && !SESSION_BOUNDARIES.contains(t)) activityTimes.add(at);
            if (used && contributing.size() < MAX_CONTRIBUTING) contributing.add((String)e.get("event_id"));
        }
        if (contributing.size() >= MAX_CONTRIBUTING && unique.size() > MAX_CONTRIBUTING) gaps.add("contributing_ids_truncated");

        // Valuation: inputs at the Wiki instant-buy (replacement) price, outputs at instant-sell, before GE tax.
        long asOf = end == null ? 0 : (end + 999) / 1000;
        long inputs = 0, outputs = 0, transfersOut = 0, transfersIn = 0;
        int unpriced = 0, stale = 0, unknownLoss = 0;
        Map<Integer,Map<String,Object>> quotes = new TreeMap<>();
        for (Map<String,Object> f : flows)
        {
            String cls = (String)f.get("flow_class");
            Integer item = (Integer)f.get("item_id"); Long delta = (Long)f.get("qty_delta");
            if (item == null || delta == null) { unknownLoss++; f.put("unit_gp", null); continue; }
            String side = delta < 0 ? "instant_buy" : "instant_sell";
            Map<String,Object> q = end == null ? null : prices.quote(item, side, asOf);
            Long unit = q == null ? null : (Long)q.get("unit_gp");
            if (q != null) quotes.putIfAbsent(item * 2 + (delta < 0 ? 0 : 1), q);
            f.put("unit_gp", unit); f.put("price_side", side);
            if (cls.equals("transferred"))
            {
                if (unit != null) { if (delta < 0) transfersOut += -delta * unit; else transfersIn += delta * unit; }
                continue;
            }
            if (unit == null) { unpriced++; continue; }
            if ("stale".equals(q.get("freshness"))) stale++;
            if (delta < 0) inputs += -delta * unit; else outputs += delta * unit;
        }

        Map<String,Object> r = new LinkedHashMap<>();
        r.put("schema", SCHEMA); r.put("interpretation_version", INTERPRETATION); r.put("flows_version", ActivityFlows.VERSION);
        r.put("activity_type", type);
        r.put("activity_key", session == null || start == null ? null : session + ":" + start + ":" + type);
        r.put("encounter_key", encounter == null ? null : encounter.get("encounter_key"));
        r.put("encounter_outcome", encounter == null ? null : encounter.get("outcome"));
        r.put("join_basis", session == null ? "account_profile_time_window" : "account_profile_time_window+gameplay_session_for_own_events");

        Map<String,Object> time = new LinkedHashMap<>();
        time.put("start_ms", start); time.put("end_ms", end);
        time.put("elapsed_ms", start == null || end == null ? null : end - start);
        long active = 0; for (int i = 1; i < activityTimes.size(); i++)
        {
            long gap = activityTimes.get(i) - activityTimes.get(i-1); if (gap <= IDLE_GAP_MS) active += gap;
        }
        time.put("active_ms", end == null || activityTimes.size() < 2 ? null : active);
        time.put("active_basis", "sum_of_gaps_le_60s_between_classified_events_not_input_activity");
        time.put("session_boundary_inside", boundary);
        r.put("time", time);

        r.put("flows", flows);
        r.put("inputs", summary(flows, true)); r.put("outputs", summary(flows, false));
        Map<String,Object> xpOut = new LinkedHashMap<>();
        long totalXp = 0; for (long v : xp.values()) totalXp += v;
        xpOut.put("by_skill", xp); xpOut.put("total", xpFromFlush ? totalXp : null);
        xpOut.put("source", xpFromFlush ? "xp_gain_5min_flush" : "not_observed");
        xpOut.put("alignment", xpFromFlush ? "flush_may_include_xp_from_before_window" : null);
        xpOut.put("magic_xp_from_transformations", transformationXp);
        r.put("xp", xpOut);

        Map<String,Object> v = new LinkedHashMap<>();
        v.put("source", prices.source); v.put("source_sha256", prices.sourceSha256);
        v.put("snapshot_available_at", prices.availableAt); v.put("as_of", end == null ? null : asOf);
        v.put("basis", "inputs_instant_buy_outputs_instant_sell_before_ge_tax");
        v.put("quotes", new ArrayList<>(quotes.values()));
        v.put("input_cost_gp", inputs); v.put("output_value_gp", outputs);
        v.put("transfers_out_gp", transfersOut); v.put("transfers_in_gp", transfersIn);
        v.put("transfers_in_net", false);
        r.put("valuation", v);

        List<String> reasons = new ArrayList<>();
        if (end == null) reasons.add("window_open");
        if (unpriced > 0) reasons.add("unpriced_flows:" + unpriced);
        if (unknownLoss > 0) reasons.add("unknown_loss:" + unknownLoss);
        if (flows.isEmpty()) reasons.add("no_classified_flows");
        Map<String,Object> result = new LinkedHashMap<>();
        boolean ok = reasons.isEmpty();
        Long net = ok ? outputs - inputs : null;
        Long elapsed = (Long)time.get("elapsed_ms"), activeMs = (Long)time.get("active_ms");
        result.put("net_gp", net);
        result.put("net_gp_per_elapsed_hour", ok && elapsed != null && elapsed > 0 ? Math.round(net * 3_600_000D / elapsed) : null);
        result.put("net_gp_per_active_hour", ok && activeMs != null && activeMs > 0 ? Math.round(net * 3_600_000D / activeMs) : null);
        Long gpXpBase = xpFromFlush ? Long.valueOf(totalXp) : transformationXp > 0 ? Long.valueOf(transformationXp) : null;
        result.put("gp_per_xp", ok && gpXpBase != null && gpXpBase > 0 ? Math.round(net * 100D / gpXpBase) / 100D : null);
        result.put("gp_per_xp_basis", gpXpBase == null ? null : xpFromFlush ? "xp_gain_total" : "magic_xp_from_transformations");
        result.put("unobserved_cost_classes", UNOBSERVED_COSTS.get(type));
        result.put("net_bound", !ok ? null : UNOBSERVED_COSTS.get(type).isEmpty() ? "point" : "upper_bound_excludes_unobserved_costs");
        result.put("confidence", !ok ? "unavailable" : stale > 0 || boundary || xpFromFlush ? "low" : "medium");
        result.put("unavailable_reasons", reasons);
        r.put("result", result);

        Map<String,Object> quality = new LinkedHashMap<>();
        quality.put("events_received", events.size()); quality.put("events_used", contributing.size());
        quality.put("duplicates_dropped", duplicates); quality.put("identity_mismatch_excluded", identityMismatch);
        quality.put("outside_window_excluded", outside); quality.put("stale_prices_used", stale);
        quality.put("gaps", gaps);
        r.put("quality", quality);
        r.put("contributing_event_ids", contributing);
        return r;
    }

    private static Map<String,Object> summary(List<Map<String,Object>> flows, boolean inputs)
    {
        Map<String,Map<Integer,Long>> byClass = new TreeMap<>();
        for (Map<String,Object> f : flows)
        {
            Long d = (Long)f.get("qty_delta"); Integer item = (Integer)f.get("item_id");
            if (d == null || item == null || (inputs ? d >= 0 : d <= 0)) continue;
            byClass.computeIfAbsent((String)f.get("flow_class"), k -> new TreeMap<>()).merge(item, Math.abs(d), Long::sum);
        }
        return new LinkedHashMap<>(byClass);
    }

    private static boolean identityMatches(Map<String,Object> scope, Map<String,Object> e, String session)
    {
        if (String.valueOf(e.get("type")).startsWith("own_") || e.get("gameplay_session") != null)
            return session != null && session.equals(e.get("gameplay_session"));
        return Objects.equals(scope.get("account_hash"), e.get("account_hash"))
            && Objects.equals(scope.get("profile_type"), e.get("profile_type"));
    }

    private static Long asLong(Object x)
    {
        return x instanceof Number && ((Number)x).doubleValue() == ((Number)x).longValue() ? ((Number)x).longValue() : null;
    }
}
