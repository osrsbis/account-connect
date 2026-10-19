package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, deterministic reducer from ge_* event rows (as emitted by GeObservationTracker and kept by
 * PublicTransport) to one derived record per offer episode. Raw rows are never changed.
 *
 * Contract: offer_key is a session-slot episode, not a game order ID. Only counter differences
 * after an observed baseline are credited. Fill times are observation intervals, never execution
 * times. RuneLite "spent" has unverified tax semantics: tax is a rule-based range or an exact
 * value only when a unique GE history row corroborates the same counters.
 */
final class GeOfferLifecycle
{
    static final int RECORD_SCHEMA_V = 1;
    static final String DERIVATION = "c1_ge_offer_lifecycle_v1";
    static final long TAX_EXEMPT_BELOW_UNIT_GP = 50;
    static final long TAX_CAP_PER_ITEM_GP = 5_000_000;
    private static final Set<String> TYPES = new LinkedHashSet<>(java.util.Arrays.asList(
        "ge_offer", "ge_progress", "ge_buy", "ge_sell", "ge_cancel", "ge_collect"));
    private static final List<String> IDENTITY_RANK = java.util.Arrays.asList(
        "inferred_session_slot", "inferred_across_gap", "ambiguous_counter_regression");

    private GeOfferLifecycle() {}

    private static final class Episode
    {
        final String key;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<Long, String> bySequence = new HashMap<>();
        final Set<String> quarantine = new LinkedHashSet<>();
        Episode(String key) { this.key = key; }
    }

    /**
     * @param events ge_* rows for ONE account (other types are counted and ignored)
     * @param history GeHistoryCapture rows for the same account, or null; corroboration only
     * @param generation caller-supplied account/mode identity; there is no shared public epoch yet
     */
    static Map<String, Object> reduce(List<Map<String, Object>> events, List<Map<String, Object>> history,
        String generation)
    {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        Map<String, Episode> episodes = new LinkedHashMap<>();
        Map<String, String> eventIds = new HashMap<>();
        for (Map<String, Object> e : events == null ? Collections.<Map<String, Object>>emptyList() : events)
        {
            if (e == null || !TYPES.contains(String.valueOf(e.get("type"))))
            {
                count(diagnostics, "non_ge_rows_ignored");
                continue;
            }
            Map<?, ?> o = e.get("ge_observation") instanceof Map ? (Map<?, ?>) e.get("ge_observation") : null;
            Object key = e.get("offer_key");
            Long sequence = o == null ? null : num(o.get("sequence"));
            if (!(key instanceof String) || o == null || sequence == null)
            {
                count(diagnostics, "rows_without_versioned_observation");
                continue;
            }
            String content = canonical(e);
            Object eventId = e.get("event_id");
            if (eventId instanceof String)
            {
                String prior = eventIds.putIfAbsent((String) eventId, content);
                if (prior != null)
                {
                    count(diagnostics, prior.equals(content) ? "duplicate_event_id_dropped" : "conflicting_event_id");
                    if (!prior.equals(content))
                    {
                        episodes.computeIfAbsent((String) key, Episode::new).quarantine.add("conflicting_event_id");
                    }
                    continue;
                }
            }
            Episode ep = episodes.computeIfAbsent((String) key, Episode::new);
            String prior = ep.bySequence.putIfAbsent(sequence, content);
            if (prior != null)
            {
                // A replay with a new event_id but the same observation is not another fill.
                count(diagnostics, prior.equals(content) ? "duplicate_sequence_dropped" : "conflicting_sequence");
                if (!prior.equals(content))
                {
                    ep.quarantine.add("conflicting_sequence");
                }
                continue;
            }
            ep.rows.add(e);
        }
        List<Map<String, Object>> offers = new ArrayList<>();
        for (Episode ep : episodes.values())
        {
            if (ep.rows.isEmpty())
            {
                continue;
            }
            ep.rows.sort((a, b) -> Long.compare(seq(a), seq(b)));
            offers.add(record(ep, diagnostics));
        }
        offers.sort((a, b) -> Long.compare(orZero(a.get("first_seen_at_ms")), orZero(b.get("first_seen_at_ms"))));
        List<Map<String, Object>> unmatched = joinHistory(offers, history, diagnostics);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record_schema_v", RECORD_SCHEMA_V);
        out.put("derivation", DERIVATION);
        out.put("generation", generation);
        out.put("generation_source", "caller_supplied");
        out.put("offers", offers);
        out.put("history_only", unmatched);
        out.put("diagnostics", diagnostics);
        return out;
    }

    static Map<String, Object> reduce(List<Map<String, Object>> events, List<Map<String, Object>> history)
    {
        return reduce(events, history, null);
    }

    private static Map<String, Object> record(Episode ep, Map<String, Object> diagnostics)
    {
        Map<String, Object> first = ep.rows.get(0);
        Map<String, Object> last = ep.rows.get(ep.rows.size() - 1);
        Set<String> gaps = new LinkedHashSet<>();
        Set<String> quarantine = new LinkedHashSet<>(ep.quarantine);
        String identity = "inferred_session_slot";
        List<Map<String, Object>> fills = new ArrayList<>();
        Long placedAt = null, completedAt = null, cancelledAt = null, collectedAt = null, lastCounterAt = null;
        long[] firstFill = null, completion = null, cancel = null;
        String phase = "open";
        long previousSeq = 0, previousQty = -1, previousGp = -1;
        long creditedQty = 0, creditedGp = 0;
        boolean placementObserved = false;
        long baselineQty = 0, baselineGp = 0;
        for (int i = 0; i < ep.rows.size(); i++)
        {
            Map<String, Object> r = ep.rows.get(i);
            Map<?, ?> o = (Map<?, ?>) r.get("ge_observation");
            String type = String.valueOf(r.get("type"));
            long sequence = seq(r);
            long at = orZero(num(o.get("observed_at_ms")));
            if (!sameSignature(first, r))
            {
                quarantine.add("signature_changed_within_offer_key");
            }
            identity = worse(identity, String.valueOf(o.get("identity")));
            if (i == 0)
            {
                // The tracker's first row decides the baseline. delta_from is what the tracker had
                // already accounted for: zero after an observed empty slot or a zero-counter placement.
                Long fromQty = num(o.get("delta_from_qty")), fromGp = num(o.get("delta_from_gp"));
                placementObserved = ("ge_offer".equals(type) || "ge_buy".equals(type) || "ge_sell".equals(type)
                    || "ge_cancel".equals(type)) && Long.valueOf(0).equals(fromQty) && Long.valueOf(0).equals(fromGp)
                    && sequence == 1;
                baselineQty = fromQty == null ? 0 : fromQty;
                baselineGp = fromGp == null ? 0 : fromGp;
                if ("baseline_only".equals(o.get("delta_status")))
                {
                    baselineQty = orZero(num(o.get("cumulative_qty")));
                    baselineGp = orZero(num(o.get("cumulative_gp")));
                    placementObserved = "ge_offer".equals(type) && baselineQty == 0 && baselineGp == 0;
                }
                if (placementObserved && "ge_offer".equals(type))
                {
                    placedAt = at;
                }
                if (!placementObserved)
                {
                    gaps.add("placement_not_observed_earlier_fills_uncredited");
                }
                if (sequence != 1)
                {
                    gaps.add("earlier_rows_missing");
                }
            }
            else if (sequence != previousSeq + 1)
            {
                gaps.add("missing_rows_between_observations");
            }
            if ("unknown".equals(o.get("delta_status")))
            {
                quarantine.add("tracker_delta_unknown");
            }
            Long cq = num(o.get("cumulative_qty")), cg = num(o.get("cumulative_gp"));
            Long fromQty = num(o.get("delta_from_qty")), fromGp = num(o.get("delta_from_gp"));
            if (cq == null || cg == null)
            {
                quarantine.add("missing_counter");
                continue;
            }
            if (previousQty >= 0 && (cq < previousQty || cg < previousGp))
            {
                quarantine.add("counter_regression");
            }
            if (previousQty >= 0 && fromQty != null && fromGp != null && (fromQty != previousQty || fromGp != previousGp))
            {
                // A row was lost: the counter difference is still recoverable, its timing is not.
                gaps.add("missing_rows_between_observations");
            }
            Long dq = num(o.get("delta_qty")), dg = num(o.get("delta_gp"));
            if ("observed_counter_difference".equals(o.get("delta_status")) && dq != null && dg != null && dq > 0)
            {
                Map<String, Object> fill = new LinkedHashMap<>();
                fill.put("sequence", sequence);
                fill.put("qty", dq);
                fill.put("gp", dg);
                fill.put("interval_start_ms", o.get("interval_start_ms"));
                fill.put("interval_end_ms", o.get("interval_end_ms"));
                fill.put("fill_time", "unknown_within_interval");
                fill.put("avg_gp_per_unit_proxy", ratio(dg, dq));
                fills.add(fill);
                creditedQty += dq;
                creditedGp += dg;
                if (firstFill == null)
                {
                    firstFill = interval(o);
                }
            }
            else if ("observed_counter_difference".equals(o.get("delta_status")) && dq != null && dg != null
                && (dq < 0 || dg < 0 || (dq == 0 && dg != 0)))
            {
                quarantine.add("inconsistent_counter_pair");
            }
            previousQty = cq;
            previousGp = cg;
            previousSeq = sequence;
            lastCounterAt = at;
            if ("ge_buy".equals(type) || "ge_sell".equals(type))
            {
                phase = "completed";
                completedAt = at;
                completion = interval(o);
            }
            else if ("ge_cancel".equals(type))
            {
                phase = "cancelled";
                cancelledAt = at;
                cancel = interval(o);
            }
            else if ("ge_collect".equals(type))
            {
                collectedAt = at;
                if ("open".equals(phase))
                {
                    // The collect row carries the slot's last state; a terminal row may have been lost.
                    String state = String.valueOf(r.get("state"));
                    phase = state.startsWith("CANCELLED") ? "cancelled" : "BOUGHT".equals(state) || "SOLD".equals(state)
                        ? "completed" : "unknown";
                    gaps.add("terminal_row_not_observed");
                }
            }
        }
        if ("ambiguous_counter_regression".equals(identity))
        {
            quarantine.add("ambiguous_identity");
        }
        if ("inferred_across_gap".equals(identity))
        {
            gaps.add("observation_gap_logout_or_hop");
        }
        boolean quarantined = !quarantine.isEmpty();
        long totalQty = orZero(num(first.get("qty_total")));
        long price = orZero(num(first.get("price")));
        String side = String.valueOf(first.get("side"));
        long finalQty = previousQty < 0 ? 0 : previousQty, finalGp = previousGp < 0 ? 0 : previousGp;
        Long observedQty = quarantined ? null : finalQty - baselineQty;
        Long observedGp = quarantined ? null : finalGp - baselineGp;
        if (collectedAt == null)
        {
            gaps.add("not_collected_yet_or_collect_not_observed");
        }

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("record_schema_v", RECORD_SCHEMA_V);
        rec.put("derivation", DERIVATION);
        rec.put("offer_key", ep.key);
        rec.put("offer_key_scope", "collector_session");
        rec.put("account_hash", first.get("account_hash"));
        rec.put("profile_type", first.get("profile_type"));
        rec.put("slot", first.get("slot"));
        rec.put("item_id", first.get("item"));
        rec.put("side", side);
        rec.put("listing_price", price);
        rec.put("total_qty", totalQty);
        rec.put("placement", placementObserved ? "observed" : "not_observed");
        rec.put("placed_observed_at_ms", placedAt);
        rec.put("first_seen_at_ms", num(((Map<?, ?>) first.get("ge_observation")).get("observed_at_ms")));
        rec.put("phase", phase);
        rec.put("completed_at_observed_ms", completedAt);
        rec.put("cancelled_at_observed_ms", cancelledAt);
        rec.put("collected", collectedAt != null);
        rec.put("collected_at_observed_ms", collectedAt);
        rec.put("counter_qty", finalQty);
        rec.put("counter_gp", finalGp);
        rec.put("baseline_qty_uncredited", baselineQty);
        rec.put("baseline_gp_uncredited", baselineGp);
        rec.put("filled_qty_observed", observedQty);
        rec.put("filled_gp_observed", observedGp);
        rec.put("fills", quarantined ? Collections.emptyList() : fills);
        rec.put("untimed_fill_qty", quarantined ? null : observedQty - creditedQty);
        rec.put("first_fill_interval_ms", quarantined ? null : list(firstFill));
        rec.put("completion_interval_ms", quarantined || !placementObserved ? null : list(completion));
        rec.put("cancel_interval_ms", quarantined ? null : list(cancel));
        rec.put("fill_time", "unknown_within_intervals");
        rec.put("identity", identity);
        rec.put("quarantined", quarantined);
        rec.put("quarantine_reasons", new ArrayList<>(quarantine));
        rec.put("gaps", new ArrayList<>(gaps));
        rec.put("completeness", quarantined ? "quarantined"
            : placementObserved && collectedAt != null && !gaps.contains("missing_rows_between_observations")
                && !gaps.contains("terminal_row_not_observed") ? "complete_observed_lifecycle" : "partial");
        rec.put("cashflow", cashflow(side, price, totalQty, finalQty, finalGp, observedQty, observedGp, phase, quarantined));
        rec.put("escrow", escrow(side, price, totalQty, finalQty, finalGp, phase, collectedAt, lastCounterAt, quarantined,
            identity));
        rec.put("history_corroboration", null);
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> r : ep.rows)
        {
            ids.add(r.get("event_id"));
        }
        rec.put("contributing_event_ids", ids);
        rec.put("as_of_observed_ms", lastCounterAt);
        return rec;
    }

    /** Realized GP from credited counter differences. A buy refund and sell tax are qualified. */
    private static Map<String, Object> cashflow(String side, long price, long total, long finalQty, long finalGp,
        Long qty, Long gp, String phase, boolean quarantined)
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("gp_counter_semantics", "runelite_spent_tax_unknown");
        if (quarantined)
        {
            c.put("status", "unknown_quarantined");
            return c;
        }
        c.put("status", "credited_counter_difference");
        if ("buy".equals(side))
        {
            c.put("coins_paid_gp", gp);
            c.put("items_bought", qty);
            // Coins put up at the listing price minus coins spent return at collection.
            c.put("overpay_refund_gp_whole_offer", price * finalQty - finalGp);
            c.put("tax_gp", 0L);
            c.put("tax_status", "no_tax_on_buys");
            return c;
        }
        c.put("items_sold", qty);
        c.put("gp_counter", gp);
        Long low = null, high = null;
        String status;
        if (qty == null || gp == null || qty == 0)
        {
            status = qty != null && qty == 0 ? "no_sale" : "unknown";
            if (qty != null && qty == 0)
            {
                low = high = 0L;
            }
        }
        else if (gp > TAX_CAP_PER_ITEM_GP * 50)
        {
            // Some item may be above the 250M price where the per-item cap binds.
            status = "unknown_cap_may_apply";
        }
        else
        {
            // Per-item tax is floor(2% of that item's price). Mixed prices inside one counter change
            // are unknown, so the sum lies in [floor(2% of total) - qty + 1, floor(2% of total)].
            high = gp * 2 / 100;
            low = Math.max(0, high - qty + 1);
            if (gp < TAX_EXEMPT_BELOW_UNIT_GP * qty)
            {
                low = 0L;
            }
            status = low.equals(high) ? "rule_exact_if_gp_is_gross_and_item_not_exempt"
                : "rule_range_if_gp_is_gross_and_item_not_exempt";
        }
        c.put("tax_status", status);
        c.put("tax_gp", null);
        c.put("tax_rule_low_gp", low);
        c.put("tax_rule_high_gp", high);
        c.put("net_gp_rule_low", gp == null || high == null ? null : gp - high);
        c.put("net_gp_rule_high", gp == null || low == null ? null : gp - low);
        c.put("tax_assumption", "spent_is_gross_unverified; item_exemptions_not_checked");
        return c;
    }

    /** Escrow for a wealth ledger from the last observed absolute counters. Partial collection is unobservable. */
    private static Map<String, Object> escrow(String side, long price, long total, long qty, long gp, String phase,
        Long collectedAt, Long asOf, boolean quarantined, String identity)
    {
        Map<String, Object> e = new LinkedHashMap<>();
        boolean open = "open".equals(phase);
        boolean cancelled = "cancelled".equals(phase);
        e.put("as_of_observed_ms", collectedAt != null ? collectedAt : asOf);
        e.put("basis", collectedAt != null ? "slot_emptied_while_logged_in" : "last_observed_counter");
        e.put("escrow_state", quarantined || "unknown".equals(phase) ? "unknown" : "observed");
        e.put("gap_or_reset_seen", !"inferred_session_slot".equals(identity));
        e.put("filled_qty", qty);
        if (quarantined || "unknown".equals(phase))
        {
            return e;
        }
        if (collectedAt != null)
        {
            e.put("locked_coins", 0L);
            e.put("locked_items", 0L);
            e.put("uncollected_items_max", 0L);
            e.put("uncollected_coins_max", 0L);
            e.put("collection_note", "collect_is_not_proof_of_arrival_match_container_change");
            return e;
        }
        if ("buy".equals(side))
        {
            e.put("locked_coins", open ? price * (total - qty) : 0L);
            e.put("locked_items", 0L);
            e.put("uncollected_items_max", qty);
            e.put("uncollected_coins_max", price * qty - gp + (cancelled ? price * (total - qty) : 0L));
        }
        else
        {
            e.put("locked_coins", 0L);
            e.put("locked_items", open ? total - qty : 0L);
            e.put("uncollected_items_max", cancelled ? total - qty : 0L);
            e.put("uncollected_coins_max", gp);
            e.put("coins_semantics", "gp_counter_tax_unknown");
        }
        e.put("uncollected_semantics", "upper_bound_partial_collection_unobserved");
        return e;
    }

    /**
     * GE history rows have no time and no order ID. Join only a one-to-one match on item, side,
     * offer quantity and counters. A match corroborates the counters and, for a sell, gives the
     * displayed tax. Never adds a fill.
     */
    private static List<Map<String, Object>> joinHistory(List<Map<String, Object>> offers,
        List<Map<String, Object>> history, Map<String, Object> diagnostics)
    {
        List<Map<String, Object>> unmatched = new ArrayList<>();
        if (history == null)
        {
            return unmatched;
        }
        Map<Map<String, Object>, List<Map<String, Object>>> rowMatches = new LinkedHashMap<>();
        Map<Map<String, Object>, List<Map<String, Object>>> offerMatches = new HashMap<>();
        for (Map<String, Object> row : history)
        {
            List<Map<String, Object>> found = new ArrayList<>();
            for (Map<String, Object> offer : offers)
            {
                if (Boolean.TRUE.equals(offer.get("quarantined")) || "open".equals(offer.get("phase"))
                    || "unknown".equals(offer.get("phase")))
                {
                    continue;
                }
                if (Objects.equals(num(row.get("item_id")), num(offer.get("item_id")))
                    && Objects.equals(row.get("side"), offer.get("side"))
                    && Objects.equals(num(row.get("quantity")), num(offer.get("counter_qty")))
                    && counterMatch(row, offer) != null)
                {
                    found.add(offer);
                    offerMatches.computeIfAbsent(offer, k -> new ArrayList<>()).add(row);
                }
            }
            rowMatches.put(row, found);
        }
        for (Map.Entry<Map<String, Object>, List<Map<String, Object>>> m : rowMatches.entrySet())
        {
            if (m.getValue().isEmpty())
            {
                unmatched.add(historyOnly(m.getKey()));
                continue;
            }
            if (m.getValue().size() != 1)
            {
                count(diagnostics, "history_row_ambiguous");
                continue;
            }
            Map<String, Object> offer = m.getValue().get(0);
            if (offerMatches.get(offer).size() != 1)
            {
                count(diagnostics, "history_offer_ambiguous");
                continue;
            }
            Map<String, Object> row = m.getKey();
            String basis = counterMatch(row, offer);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("match", "unique_item_side_qty_counter");
            h.put("counter_equals", basis);
            h.put("history_gross_gp", row.get("original_gross_gp"));
            h.put("history_tax_gp", row.get("explicit_tax_gp"));
            h.put("history_net_gp", row.get("displayed_net_gp"));
            h.put("history_execution_time", "unknown");
            h.put("history_verified_with_actual_client", row.get("verified_with_actual_client"));
            offer.put("history_corroboration", h);
            Map<String, Object> c = asMap(offer.get("cashflow"));
            if ("sell".equals(offer.get("side")) && c != null && num(row.get("explicit_tax_gp")) != null
                && Objects.equals(num(c.get("gp_counter")), num(offer.get("counter_gp"))))
            {
                // Exact only when the whole offer was credited; the history total is the whole offer.
                c.put("gp_counter_semantics", "history_gross".equals(basis) ? "matches_history_gross_one_offer"
                    : "matches_history_net_one_offer");
                c.put("tax_gp", num(row.get("explicit_tax_gp")));
                c.put("tax_status", "history_explicit_offer_total");
                c.put("net_gp", num(row.get("displayed_net_gp")));
                Long tax = num(row.get("explicit_tax_gp")), low = num(c.get("tax_rule_low_gp")),
                    high = num(c.get("tax_rule_high_gp"));
                if (tax != null && low != null && high != null && (tax < low || tax > high))
                {
                    // Exemption, cap or a rule change: keep the displayed value and flag the disagreement.
                    c.put("tax_rule_disagrees_with_history", true);
                }
            }
            count(diagnostics, "history_joined");
        }
        return unmatched;
    }

    /**
     * A finished offer seen only in the GE history UI, e.g. made on mobile or with the plugin off.
     * It proves an offer total existed in the recent list. It has no time, no slot, no fills and no
     * order ID, and two equal rows cannot be told apart from one repeated snapshot row.
     */
    private static Map<String, Object> historyOnly(Map<String, Object> row)
    {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("item_id", row.get("item_id"));
        h.put("side", row.get("side"));
        h.put("quantity", row.get("quantity"));
        h.put("gross_gp", row.get("original_gross_gp"));
        h.put("tax_gp", row.get("explicit_tax_gp"));
        h.put("net_gp", row.get("displayed_net_gp"));
        h.put("displayed_total_gp", row.get("displayed_total_gp"));
        h.put("recoverable", "offer_total_only");
        h.put("execution_time", "unknown");
        h.put("placement_and_fills", "unknown");
        h.put("may_overlap_unobserved_or_earlier_session_offers", true);
        return h;
    }

    private static String counterMatch(Map<String, Object> row, Map<String, Object> offer)
    {
        Long gp = num(offer.get("counter_gp"));
        if (gp == null) return null;
        if (gp.equals(num(row.get("original_gross_gp")))) return "history_gross";
        if (gp.equals(num(row.get("displayed_net_gp")))) return "history_net";
        if (num(row.get("original_gross_gp")) == null && gp.equals(num(row.get("displayed_total_gp"))))
            return "history_displayed_total";
        return null;
    }

    private static boolean sameSignature(Map<String, Object> a, Map<String, Object> b)
    {
        return Objects.equals(num(a.get("item")), num(b.get("item"))) && Objects.equals(a.get("side"), b.get("side"))
            && Objects.equals(num(a.get("qty_total")), num(b.get("qty_total")))
            && Objects.equals(num(a.get("price")), num(b.get("price"))) && Objects.equals(a.get("slot"), b.get("slot"))
            && Objects.equals(a.get("account_hash"), b.get("account_hash"));
    }

    private static String worse(String a, String b)
    {
        int ia = IDENTITY_RANK.indexOf(a), ib = IDENTITY_RANK.indexOf(b);
        if (ib < 0) return "ambiguous_counter_regression"; // unknown label is never trusted
        return ib > ia ? b : a;
    }

    private static long[] interval(Map<?, ?> o)
    {
        Long s = num(o.get("interval_start_ms")), e = num(o.get("interval_end_ms"));
        return s == null || e == null ? null : new long[]{s, e};
    }

    private static List<Long> list(long[] v)
    {
        return v == null ? null : java.util.Arrays.asList(v[0], v[1]);
    }

    private static String ratio(long gp, long qty)
    {
        return new java.math.BigDecimal(gp).divide(new java.math.BigDecimal(qty), 4, java.math.RoundingMode.HALF_EVEN)
            .stripTrailingZeros().toPlainString();
    }

    private static long seq(Map<String, Object> e)
    {
        return orZero(num(((Map<?, ?>) e.get("ge_observation")).get("sequence")));
    }

    /** Content identity without transport-only envelope fields. */
    private static String canonical(Map<String, Object> e)
    {
        Map<String, Object> copy = new java.util.TreeMap<>();
        for (Map.Entry<String, Object> x : e.entrySet())
        {
            String k = x.getKey();
            if (!"event_id".equals(k) && !"ts".equals(k) && !"received_at".equals(k) && !"token_fp".equals(k)
                && !k.startsWith("_"))
            {
                copy.put(k, x.getValue() instanceof Map ? new java.util.TreeMap<>((Map<?, ?>) x.getValue()) : x.getValue());
            }
        }
        return copy.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o)
    {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static Long num(Object o)
    {
        if (o instanceof Long || o instanceof Integer || o instanceof Short) return ((Number) o).longValue();
        if (o instanceof Number)
        {
            double d = ((Number) o).doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 9.0e15 ? (long) d : null;
        }
        return null;
    }

    private static long orZero(Object o)
    {
        Long v = o instanceof Long ? (Long) o : num(o);
        return v == null ? 0 : v;
    }

    private static void count(Map<String, Object> d, String key)
    {
        d.put(key, orZero(d.get(key)) + 1);
    }
}
