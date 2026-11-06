package com.osrsbestinslot.export;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
        /** Restored from disk after a restart: the offer_key outlived the collector session. */
        boolean restored;
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

    boolean isEmpty()
    {
        return slots.isEmpty();
    }

    /** The slots as plain values, for the restart file. Empty-slot times are not kept: they only date a new offer. */
    List<Map<String, Object>> export()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Integer, Slot> e : new TreeMap<>(slots).entrySet())
        {
            Slot s = e.getValue();
            Map<String, Object> m = Fields.of(
                "slot", e.getKey(), "key", s.key, "seen", s.seen, "state", s.state.name(),
                "value", Arrays.stream(s.value).boxed().collect(Collectors.toList()), "emitted_qty", s.emittedQty,
                "emitted_gp", s.emittedGp, "emitted_at", s.emittedAt, "observed_at", s.observedAt,
                "uncertain", s.uncertain, "sequence", s.sequence);
            out.add(m);
        }
        return out;
    }

    /**
     * Take back the slots of the same account, profile and token after a restart. Every restored slot is marked
     * as crossing a gap: the next observation reports the counter difference since the last row sent, with the
     * interval from that row to now and no fill time. A malformed entry is skipped, never guessed.
     */
    void restore(List<?> saved)
    {
        slots.clear();
        emptySlots.clear();
        if (saved == null)
        {
            return;
        }
        for (Object o : saved)
        {
            try
            {
                Map<?, ?> m = (Map<?, ?>) o;
                List<?> v = (List<?>) m.get("value");
                String key = (String) m.get("key");
                if (v == null || v.size() != 5 || key == null || !key.matches("^[0-9a-f]{32}$"))
                {
                    continue;
                }
                int slot = ((Number) m.get("slot")).intValue();
                if (slot < 0 || slot >= GeSlots.SLOT_COUNT)
                {
                    continue;
                }
                Slot s = new Slot();
                s.key = key;
                s.seen = (String) m.get("seen");
                s.state = GrandExchangeOfferState.valueOf((String) m.get("state"));
                s.value = new long[5];
                for (int i = 0; i < 5; i++)
                {
                    s.value[i] = ((Number) v.get(i)).longValue();
                }
                s.emittedQty = ((Number) m.get("emitted_qty")).longValue();
                s.emittedGp = ((Number) m.get("emitted_gp")).longValue();
                s.emittedAt = ((Number) m.get("emitted_at")).longValue();
                s.observedAt = ((Number) m.get("observed_at")).longValue();
                s.uncertain = Boolean.TRUE.equals(m.get("uncertain"));
                s.sequence = ((Number) m.get("sequence")).longValue();
                s.lastProgressTick = Integer.MIN_VALUE / 2;	// the tick count restarted with the client
                s.gap = true;
                s.restored = true;
                if (s.state == GrandExchangeOfferState.EMPTY || s.seen == null)
                {
                    continue;
                }
                slots.put(slot, s);
            }
            catch (RuntimeException e)
            {
                // skip this entry
            }
        }
    }

    static boolean active(GrandExchangeOfferState s)
    {
        return s == GrandExchangeOfferState.BUYING || s == GrandExchangeOfferState.SELLING;
    }

    private static boolean same(Slot s, GrandExchangeOfferState state, long[] v)
    {
        return s.value[0] == v[0] && s.value[2] == v[2] && s.value[3] == v[3]
            && Objects.equals(GeSlots.side(s.state), GeSlots.side(state));
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
            s.seen = replay ? "midway" : active(state) && (afterEmpty || v[1] == 0) ? "placed" : "midway";
            // Only an observed empty slot supplies a zero baseline for an instant fill.
            // Otherwise first sight cannot credit historical fills, including restart/login.
            boolean fromEmpty = afterEmpty && !replay;
            s.emittedQty = fromEmpty ? 0 : v[1];
            s.emittedGp = fromEmpty ? 0 : v[4];
            s.emittedAt = fromEmpty ? emptyAt : observedAt;
            s.observedAt = observedAt;
            s.lastProgressTick = replay ? tick - GeSlots.PROGRESS_MIN_TICKS : tick;
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
            && (replay || tick < s.lastProgressTick || tick - s.lastProgressTick >= GeSlots.PROGRESS_MIN_TICKS))
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
        Map<String, Object> f = Fields.of(
            "type", type, "item", (int) s.value[0], "qty", (int) s.value[1], "qty_total", (int) s.value[2],
            "price", s.value[3], "gp", s.value[4], "slot", slot, "state", s.state.name(),
            "side", GeSlots.side(s.state), "offer_key", s.key, "offer_seen", s.seen);
        Map<String, Object> o = Fields.of(
            "schema_v", 1, "sequence", ++s.sequence, "cumulative_qty", s.value[1], "cumulative_gp", s.value[4],
            "delta_from_qty", s.emittedQty, "delta_from_gp", s.emittedGp, "source", "runelite_grand_exchange_offer",
            "observed_at_ms", at, "counter_observed_at_ms", s.observedAt, "listing_price", s.value[3],
            "gp_semantics", "runelite_spent_tax_unknown", "execution_price", null, "tax_gp", null,
            "identity", s.uncertain ? "ambiguous_counter_regression" : s.gap ? "inferred_across_gap" : "inferred_session_slot",
            "offer_key_scope", s.restored ? "account_profile" : "collector_session");
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
