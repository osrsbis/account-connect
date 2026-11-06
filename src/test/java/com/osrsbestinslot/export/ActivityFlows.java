package com.osrsbestinslot.export;

import java.util.*;

/**
 * Classifies one already-projected event into own item flows (contract c3-activity-flows-v1).
 * Only a classified flow is a cost or a result. An inventory decrease alone is never consumption:
 * bank, trade, drop and pickup are transfers, and a death without a trusted diff is an unknown loss.
 */
final class ActivityFlows
{
    private ActivityFlows() {}
    static final String VERSION = "c3-activity-flows-v1";
    static final List<String> CLASSES = List.of("consumed_observed", "consumed_inferred", "transformed",
        "output_reward", "transferred", "lost_death", "unknown");

    /** Event time in ms: own gameplay projections carry observed_at_ms, the public emitter carries ts. */
    static Long at(Map<String,Object> event)
    {
        Object x = event.get("observed_at_ms") != null ? event.get("observed_at_ms") : event.get("ts");
        return x instanceof Number && ((Number)x).longValue() > 0 ? ((Number)x).longValue() : null;
    }

    static List<Map<String,Object>> classify(Map<String,Object> event)
    {
        List<Map<String,Object>> out = new ArrayList<>();
        String type = String.valueOf(event.get("type")), id = String.valueOf(event.get("event_id"));
        Long at = at(event);
        switch (type)
        {
            case "own_signal":
            {
                Map<?,?> v = event.get("signal_value") instanceof Map ? (Map<?,?>)event.get("signal_value") : Map.of();
                String kind = String.valueOf(event.get("signal_kind"));
                if (kind.equals("effects.consumption"))
                {
                    int before = num(v.get("item_id_before")), after = num(v.get("item_id_after"));
                    long qb = lnum(v.get("quantity_before")), qa = lnum(v.get("quantity_after"));
                    if (before <= 0 || qb <= 0) break;
                    if (after == before) out.add(flow(id, 0, at, before, qa - qb, "consumed_inferred", "inferred", "own_click_slot_change", null));
                    else if (after <= 0) out.add(flow(id, 0, at, before, -qb, "consumed_inferred", "inferred", "own_click_slot_change", null));
                    else
                    {
                        // A dose step or a food stage: the input becomes a cheaper product in the same slot.
                        out.add(flow(id, 0, at, before, -qb, "transformed", "inferred", "own_click_slot_change", id));
                        out.add(flow(id, 1, at, after, qa, "transformed", "inferred", "own_click_slot_change", id));
                    }
                }
                else if (kind.equals("skills.item_transformation"))
                {
                    int in = num(v.get("input_item_id")), product = num(v.get("output_item_id"));
                    long q = lnum(v.get("matched_quantity"));
                    if (in <= 0 || product <= 0 || q <= 0) break;
                    out.add(flow(id, 0, at, in, -q, "transformed", "inferred", "inventory_xp_coincidence", id));
                    out.add(flow(id, 1, at, product, q, "transformed", "inferred", "inventory_xp_coincidence", id));
                }
                break;
            }
            case "loot":
                items(out, id, at, event.get("items"), 1, "output_reward", "observed", "server_loot_" + event.get("source_type"));
                break;
            case "alch":
            {
                int item = num(event.get("item")); long q = lnum(event.get("qty")), gp = lnum(event.get("gp"));
                if (item <= 0 || q <= 0 || gp <= 0) break;
                // Runes spent on the cast are not observed here; the record lists them as an unobserved cost.
                out.add(flow(id, 0, at, item, -q, "transformed", "observed", "alch_coin_gain", id));
                out.add(flow(id, 1, at, ActivityPrices.COINS, gp, "transformed", "observed", "alch_coin_gain", id));
                break;
            }
            case "drop":
                single(out, id, at, event, -1, "own_drop_spawn_confirmed");
                break;
            case "pickup":
                single(out, id, at, event, 1, "own_pickup");
                break;
            case "bank_session":
            {
                int n = items(out, id, at, event.get("deposited"), -1, "transferred", "observed", "bank_deposit");
                items(out, id, at, event.get("withdrawn"), 1, "transferred", "observed", "bank_withdraw", n);
                break;
            }
            case "trade":
            {
                int n = items(out, id, at, event.get("given"), -1, "transferred", "observed", "trade_given");
                items(out, id, at, event.get("received"), 1, "transferred", "observed", "trade_received", n);
                break;
            }
            case "death":
                if (event.get("items_lost") instanceof List)
                    items(out, id, at, event.get("items_lost"), -1, "lost_death", "observed", "death_settled_diff");
                else out.add(flow(id, 0, at, null, null, "lost_death", "unknown", "death_without_trusted_diff", null));
                break;
            default:
                break;
        }
        return out;
    }

    private static void single(List<Map<String,Object>> out, String id, Long at, Map<String,Object> e, int sign, String origin)
    {
        int item = num(e.get("item")); long q = lnum(e.get("qty"));
        if (item > 0 && q > 0) out.add(flow(id, 0, at, item, sign * q, "transferred", "observed", origin, null));
    }

    private static int items(List<Map<String,Object>> out, String id, Long at, Object list, int sign, String cls, String prov, String origin)
    {
        return items(out, id, at, list, sign, cls, prov, origin, 0);
    }

    private static int items(List<Map<String,Object>> out, String id, Long at, Object list, int sign, String cls, String prov, String origin, int first)
    {
        int index = first;
        if (!(list instanceof List)) return index;
        for (Object x : (List<?>)list)
        {
            if (!(x instanceof Map)) continue;
            int item = num(((Map<?,?>)x).get("id")); long q = lnum(((Map<?,?>)x).get("qty"));
            if (item > 0 && q > 0) out.add(flow(id, index++, at, item, sign * q, cls, prov, origin, null));
        }
        return index;
    }

    static Map<String,Object> flow(String eventId, int index, Long at, Integer item, Long delta, String cls, String provenance, String origin, String group)
    {
        Map<String,Object> f = new LinkedHashMap<>();
        f.put("flow_id", eventId + ":" + index); f.put("event_id", eventId);
        f.put("item_id", item); f.put("qty_delta", delta); f.put("observed_at_ms", at);
        f.put("flow_class", cls); f.put("provenance", provenance); f.put("origin", origin);
        f.put("transform_group_id", group);
        return f;
    }

    private static int num(Object x)
    {
        return x instanceof Number && ((Number)x).doubleValue() == ((Number)x).intValue() ? ((Number)x).intValue() : -1;
    }

    private static long lnum(Object x)
    {
        return x instanceof Number && ((Number)x).doubleValue() == ((Number)x).longValue() ? ((Number)x).longValue() : -1;
    }
}
