package com.osrsbestinslot.export;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;

/** Own-account, session-local observations. No game order ID or execution timestamp is exposed. */
final class GeObservationTracker
{
    private final Map<Integer, Slot> slots = new HashMap<>();
    private final Map<Integer, Long> emptySlots = new HashMap<>();

    private static final class Slot
    {
        String key = EventSpool.newEventId();
        String seen;
        GrandExchangeOfferState state;
        long[] value;
        long emittedQty;
        long emittedGp;
        long emittedAt;
        long observedAt;
        int lastProgressTick;
        boolean uncertain;
        boolean gap;
        long sequence;
    }

    void clear()
    {
        slots.clear();
        emptySlots.clear();
    }

    void gap()
    {
        for (Slot s : slots.values())
        {
            s.gap = true;
        }
    }

    static boolean active(GrandExchangeOfferState s)
    {
        return s == GrandExchangeOfferState.BUYING || s == GrandExchangeOfferState.SELLING;
    }

    private static boolean same(Slot s, GrandExchangeOfferState state, long[] v)
    {
        return s.value[0] == v[0] && s.value[2] == v[2] && s.value[3] == v[3]
            && java.util.Objects.equals(AccountConnectPlugin.geSide(s.state), AccountConnectPlugin.geSide(state));
    }

    /** v = item, cumulative quantity, total quantity, listing price, cumulative RuneLite spent. */
    Map<String, Object> observe(int slot, GrandExchangeOfferState state, long[] v,
        boolean replay, int tick, long observedAt)
    {
        if (slot < 0 || slot >= 8 || state == null || v == null || v.length != 5)
        {
            return null;
        }
        Slot s = slots.get(slot);
        if (state == GrandExchangeOfferState.EMPTY)
        {
            slots.remove(slot);
            emptySlots.put(slot, observedAt);
            return s == null || replay ? null : row(slot, s, "ge_collect", observedAt, false);
        }
        if (v[0] <= 0 || v[1] < 0 || v[2] <= 0 || v[1] > v[2] || v[3] < 0 || v[4] < 0)
        {
            // Do not bridge a malformed counter into an apparently trustworthy delta.
            if (s != null)
            {
                s.uncertain = true;
            }
            return null;
        }
        Long emptyAt = emptySlots.remove(slot);
        boolean afterEmpty = emptyAt != null;
        boolean ambiguousReuse = s != null && same(s, state, v) && !active(s.state) && active(state);
        boolean fresh = s == null || !same(s, state, v) || (!active(s.state) && active(state));
        if (fresh)
        {
            s = new Slot();
            s.uncertain = ambiguousReuse;
            s.state = state;
            s.value = v.clone();
            s.seen = replay ? "baseline" : active(state) && (afterEmpty || v[1] == 0) ? "placed" : "midway";
            // Only an observed empty slot supplies a zero baseline for an instant fill.
            // Otherwise first sight cannot credit historical fills, including restart/login.
            boolean fromEmpty = afterEmpty && !replay;
            s.emittedQty = fromEmpty ? 0 : v[1];
            s.emittedGp = fromEmpty ? 0 : v[4];
            s.emittedAt = fromEmpty ? emptyAt : observedAt;
            s.observedAt = observedAt;
            s.lastProgressTick = replay ? tick - AccountConnectPlugin.GE_PROGRESS_MIN_TICKS : tick;
            slots.put(slot, s);
            if (replay)
            {
                return null;
            }
            return row(slot, s, active(state) ? (afterEmpty || v[1] == 0 ? "ge_offer" : "ge_progress")
                : terminalType(state), observedAt, !fromEmpty);
        }
        if (v[1] < s.value[1] || v[4] < s.value[4])
        {
            // Could be a stale packet, collection-counter reset or unseen identical relist. No oracle
            // distinguishes these. Keep the high-water tuple and quarantine deltas until a new offer.
            s.uncertain = true;
            return null;
        }
        GrandExchangeOfferState previousState = s.state;
        boolean changed = v[1] != s.value[1] || v[4] != s.value[4];
        s.value = v.clone();
        s.state = state;
        s.observedAt = observedAt;
        s.gap |= replay;
        if (replay && !changed && state == previousState)
        {
            return null;
        }
        String type = null;
        if (!active(state) && (state != previousState || changed))
        {
            type = terminalType(state);
        }
        else if (active(state) && (s.value[1] != s.emittedQty || s.value[4] != s.emittedGp)
            && (replay || tick < s.lastProgressTick || tick - s.lastProgressTick >= AccountConnectPlugin.GE_PROGRESS_MIN_TICKS))
        {
            type = "ge_progress";
        }
        if (type == null)
        {
            return null;
        }
        s.lastProgressTick = tick;
        return row(slot, s, type, observedAt, false);
    }

    private static String terminalType(GrandExchangeOfferState state)
    {
        if (state == GrandExchangeOfferState.BOUGHT)
        {
            return "ge_buy";
        }
        if (state == GrandExchangeOfferState.SOLD)
        {
            return "ge_sell";
        }
        return "ge_cancel";
    }

    private static Map<String, Object> row(int slot, Slot s, String type, long at, boolean baseline)
    {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        f.put("item", (int) s.value[0]);
        f.put("qty", (int) s.value[1]);
        f.put("qty_total", (int) s.value[2]);
        f.put("price", s.value[3]);
        f.put("gp", s.value[4]);
        f.put("slot", slot);
        f.put("state", s.state.name());
        f.put("side", AccountConnectPlugin.geSide(s.state));
        f.put("offer_key", s.key);
        f.put("offer_seen", s.seen);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schema_v", 1);
        o.put("sequence", ++s.sequence);
        o.put("cumulative_qty", s.value[1]);
        o.put("cumulative_gp", s.value[4]);
        o.put("delta_from_qty", s.emittedQty);
        o.put("delta_from_gp", s.emittedGp);
        o.put("source", "runelite_grand_exchange_offer");
        o.put("observed_at_ms", at);
        o.put("counter_observed_at_ms", s.observedAt);
        o.put("listing_price", s.value[3]);
        o.put("gp_semantics", "runelite_spent_tax_unknown");
        o.put("execution_price", null);
        o.put("tax_gp", null);
        o.put("identity", s.uncertain ? "ambiguous_counter_regression" : s.gap ? "inferred_across_gap" : "inferred_session_slot");
        o.put("offer_key_scope", "collector_session");
        boolean clockOk = at >= s.emittedAt;
        o.put("delta_status", s.uncertain ? "unknown" : baseline ? "baseline_only" : "observed_counter_difference");
        o.put("delta_qty", s.uncertain ? null : s.value[1] - s.emittedQty);
        o.put("delta_gp", s.uncertain ? null : s.value[4] - s.emittedGp);
        // Bounds are observation bounds, conditional on the inferred identity; never exact fill times.
        o.put("interval_start_ms", !s.uncertain && clockOk ? s.emittedAt : null);
        o.put("interval_end_ms", !s.uncertain && clockOk ? s.observedAt : null);
        o.put("fill_time", "unknown");
        f.put("ge_observation", o);
        s.emittedQty = s.value[1];
        s.emittedGp = s.value[4];
        s.emittedAt = at;
        return f;
    }
}
