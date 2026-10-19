package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure, deterministic reducer from ge_* event rows (as emitted by GeObservationTracker and kept by
 * PublicTransport) to one derived record per offer episode. Raw rows are never changed.
 *
 * Contract: offer_key is a session-slot episode, not a game order ID. Only counter differences
 * after an observed baseline are credited. Fill times are observation intervals, never execution
 * times. RuneLite "spent" has unverified tax semantics: tax is a rule-based range or an exact
 * value only when a unique GE history row corroborates the same counters. The result does not
 * depend on input order.
 */
final class GeOfferLifecycle
{
    static final int RECORD_SCHEMA_V = 1;
    static final String DERIVATION = "c1_ge_offer_lifecycle_v2";
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
        Map<String, Object> diagnostics = new TreeMap<>();
        // Pass 1: keep usable rows and find event IDs that carry more than one content.
        Map<String, Map<Long, List<Map<String, Object>>>> grouped = new TreeMap<>();
        Map<String, Set<String>> contentsById = new TreeMap<>();
        Map<String, Set<String>> keysById = new TreeMap<>();
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
            if (e.get("event_id") instanceof String)
            {
                String id = (String) e.get("event_id");
                contentsById.computeIfAbsent(id, k -> new TreeSet<>()).add(canonical(e));
                keysById.computeIfAbsent(id, k -> new TreeSet<>()).add((String) key);
            }
            grouped.computeIfAbsent((String) key, k -> new TreeMap<>())
                .computeIfAbsent(sequence, k -> new ArrayList<>()).add(e);
        }
        Set<String> conflictKeys = new TreeSet<>();
        for (Map.Entry<String, Set<String>> c : contentsById.entrySet())
        {
            if (c.getValue().size() > 1)
            {
                // Every offer any variant names is suspect, whichever variant arrived first.
                count(diagnostics, "conflicting_event_id");
                conflictKeys.addAll(keysById.get(c.getKey()));
            }
        }
        // Pass 2: one row per (offer_key, sequence), chosen without regard to arrival order.
        List<Map<String, Object>> offers = new ArrayList<>();
        for (Map.Entry<String, Map<Long, List<Map<String, Object>>>> g : grouped.entrySet())
        {
            Episode ep = new Episode(g.getKey());
            if (conflictKeys.contains(ep.key))
            {
                ep.quarantine.add("conflicting_event_id");
            }
            for (List<Map<String, Object>> rows : g.getValue().values())
            {
                Set<String> contents = new TreeSet<>();
                Set<String> ids = new TreeSet<>();
                int unnamed = 0;
                for (Map<String, Object> r : rows)
                {
                    contents.add(canonical(r));
                    if (r.get("event_id") instanceof String) ids.add((String) r.get("event_id"));
                    else unnamed++;
                }
                if (contents.size() > 1)
                {
                    count(diagnostics, "conflicting_sequence");
                    ep.quarantine.add("conflicting_sequence");
                }
                // A replay with a new event_id but the same observation is not another fill.
                add(diagnostics, "duplicate_event_id_dropped", rows.size() - ids.size() - unnamed);
                add(diagnostics, "duplicate_sequence_dropped", ids.size() + unnamed - 1);
                rows.sort((a, b) ->
                {
                    String ia = a.get("event_id") instanceof String ? (String) a.get("event_id") : "￿";
                    String ib = b.get("event_id") instanceof String ? (String) b.get("event_id") : "￿";
                    int c = ia.compareTo(ib);
                    return c != 0 ? c : canonical(a).compareTo(canonical(b));
                });
                ep.rows.add(rows.get(0));
            }
            offers.add(record(ep));
        }
        offers.sort((a, b) ->
        {
            Long fa = num(a.get("first_seen_at_ms")), fb = num(b.get("first_seen_at_ms"));
            int c = Long.compare(fa == null ? Long.MAX_VALUE : fa, fb == null ? Long.MAX_VALUE : fb);
            return c != 0 ? c : String.valueOf(a.get("offer_key")).compareTo(String.valueOf(b.get("offer_key")));
        });
        supersede(offers, diagnostics);
        List<Map<String, Object>> unmatched = joinHistory(offers, history, diagnostics);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record_schema_v", RECORD_SCHEMA_V);
        out.put("derivation", DERIVATION);
        out.put("generation", generation);
        out.put("generation_source", "caller_supplied");
        out.put("offers", offers);
        out.put("history_only", unmatched);
        out.put("diagnostics", new LinkedHashMap<>(diagnostics));
        return out;
    }

    static Map<String, Object> reduce(List<Map<String, Object>> events, List<Map<String, Object>> history)
    {
        return reduce(events, history, null);
    }

    private static Map<String, Object> record(Episode ep)
    {
        Map<String, Object> first = ep.rows.get(0);
        Map<?, ?> firstObs = (Map<?, ?>) first.get("ge_observation");
        Set<String> gaps = new LinkedHashSet<>();
        Set<String> quarantine = new LinkedHashSet<>(ep.quarantine);
        String identity = "inferred_session_slot";
        List<Map<String, Object>> fills = new ArrayList<>();
        Long placedAt = null, completedAt = null, cancelledAt = null, collectedAt = null, lastCounterAt = null;
        long[] firstFill = null, completion = null, cancel = null;
        boolean firstFillSeen = false, collected = false;
        String phase = "open";
        long previousSeq = 0, previousQty = -1, previousGp = -1;
        long creditedQty = 0, creditedGp = 0;
        boolean placementObserved = false;
        long baselineQty = 0, baselineGp = 0;

        Long item = num(first.get("item")), totalQty = num(first.get("qty_total")), price = num(first.get("price"));
        String side = first.get("side") instanceof String ? (String) first.get("side") : null;
        if (item == null || item <= 0 || totalQty == null || totalQty <= 0 || price == null || price < 0
            || !("buy".equals(side) || "sell".equals(side)))
        {
            quarantine.add("missing_offer_terms");
        }

        // The tracker's first row decides the baseline. Only a ge_offer the tracker saw being placed
        // proves placement: a terminal first row can follow an unseen zero-counter replay seed.
        String firstStatus = firstObs.get("delta_status") instanceof String ? (String) firstObs.get("delta_status") : null;
        Long firstFromQty = num(firstObs.get("delta_from_qty")), firstFromGp = num(firstObs.get("delta_from_gp"));
        if ("baseline_only".equals(firstStatus))
        {
            Long cq = num(firstObs.get("cumulative_qty")), cg = num(firstObs.get("cumulative_gp"));
            baselineQty = cq == null ? 0 : cq;
            baselineGp = cg == null ? 0 : cg;
            if (cq == null || cg == null) quarantine.add("missing_baseline_fields");
        }
        else if (firstFromQty != null && firstFromGp != null && firstStatus != null)
        {
            baselineQty = firstFromQty;
            baselineGp = firstFromGp;
        }
        else
        {
            quarantine.add("missing_baseline_fields");
        }
        placementObserved = "ge_offer".equals(first.get("type")) && "placed".equals(first.get("offer_seen"))
            && seq(first) == 1 && baselineQty == 0 && baselineGp == 0;
        if (placementObserved)
        {
            placedAt = num(firstObs.get("observed_at_ms"));
        }
        else if (baselineQty == 0 && baselineGp == 0)
        {
            gaps.add("placement_unconfirmed_zero_baseline");
        }
        else
        {
            gaps.add("placement_not_observed_earlier_fills_uncredited");
        }
        if (seq(first) != 1)
        {
            gaps.add("earlier_rows_missing");
        }

        for (int i = 0; i < ep.rows.size(); i++)
        {
            Map<String, Object> r = ep.rows.get(i);
            Map<?, ?> o = (Map<?, ?>) r.get("ge_observation");
            String type = String.valueOf(r.get("type"));
            long sequence = seq(r);
            Long at = num(o.get("observed_at_ms"));
            if (!sameSignature(first, r))
            {
                quarantine.add("signature_changed_within_offer_key");
            }
            identity = worse(identity, o.get("identity"));
            if (i > 0 && sequence != previousSeq + 1)
            {
                gaps.add("missing_rows_between_observations");
            }
            if ("unknown".equals(o.get("delta_status")))
            {
                quarantine.add("tracker_delta_unknown");
            }
            Long cq = num(o.get("cumulative_qty")), cg = num(o.get("cumulative_gp"));
            Long fromQty = num(o.get("delta_from_qty")), fromGp = num(o.get("delta_from_gp"));
            if (cq == null || cg == null || cq < 0 || cg < 0 || (totalQty != null && cq > totalQty))
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
            if ("observed_counter_difference".equals(o.get("delta_status")))
            {
                if (dq == null || dg == null || fromQty == null || fromGp == null
                    || cq - fromQty != dq || cg - fromGp != dg || dq < 0 || dg < 0
                    || (dq == 0) != (dg == 0))
                {
                    quarantine.add("inconsistent_counter_pair");
                }
                else if (dq > 0)
                {
                    Map<String, Object> fill = new LinkedHashMap<>();
                    fill.put("sequence", sequence);
                    fill.put("qty", dq);
                    fill.put("gp", dg);
                    fill.put("interval_start_ms", num(o.get("interval_start_ms")));
                    fill.put("interval_end_ms", num(o.get("interval_end_ms")));
                    fill.put("fill_time", "unknown_within_interval");
                    fill.put("avg_gp_per_unit_proxy", ratio(dg, dq));
                    fills.add(fill);
                    creditedQty += dq;
                    creditedGp += dg;
                    if (!firstFillSeen)
                    {
                        firstFillSeen = true;
                        firstFill = interval(o);
                    }
                }
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
                collected = true;
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
        if (previousQty < 0)
        {
            quarantine.add("missing_counter");
        }
        if (previousQty >= 0 && (previousQty < baselineQty || previousGp < baselineGp))
        {
            quarantine.add("counter_regression");
        }
        boolean quarantined = !quarantine.isEmpty();
        long finalQty = Math.max(previousQty, 0), finalGp = Math.max(previousGp, 0);
        Long observedQty = quarantined ? null : finalQty - baselineQty;
        Long observedGp = quarantined ? null : finalGp - baselineGp;
        if (!collected)
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
        rec.put("slot", num(first.get("slot")));
        rec.put("item_id", item);
        rec.put("side", side);
        rec.put("listing_price", price);
        rec.put("total_qty", totalQty);
        rec.put("placement", placementObserved ? "observed" : "not_observed");
        rec.put("placed_observed_at_ms", placedAt);
        rec.put("first_seen_at_ms", num(firstObs.get("observed_at_ms")));
        rec.put("phase", phase);
        rec.put("completed_at_observed_ms", completedAt);
        rec.put("cancelled_at_observed_ms", cancelledAt);
        rec.put("collected", collected);
        rec.put("collected_at_observed_ms", collectedAt);
        rec.put("superseded_by", null);
        rec.put("counter_qty", previousQty < 0 ? null : finalQty);
        rec.put("counter_gp", previousGp < 0 ? null : finalGp);
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
        rec.put("completeness", completeness(quarantined, placementObserved, collected, gaps));
        rec.put("cashflow", quarantined ? quarantinedCashflow()
            : cashflow(side, price, finalQty, finalGp, observedQty, observedGp));
        rec.put("escrow", quarantined || "unknown".equals(phase)
            ? unknownEscrow(lastCounterAt, identity, quarantined ? "quarantined" : "phase_unknown")
            : escrow(side, price, totalQty, finalQty, finalGp, phase, collected, collectedAt, lastCounterAt, identity));
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

    private static String completeness(boolean quarantined, boolean placement, boolean collected, Set<String> gaps)
    {
        if (quarantined) return "quarantined";
        return placement && collected && !gaps.contains("missing_rows_between_observations")
            && !gaps.contains("terminal_row_not_observed") && !gaps.contains("superseded_by_later_offer_key_same_slot")
            ? "complete_observed_lifecycle" : "partial";
    }

    /**
     * The tracker starts a new offer_key after a restart, account/profile/token change or a
     * re-baseline, even for the same live offer. An earlier uncollected key in the same slot can
     * therefore never stay "open with escrow": it ended unobserved or continues under the later key.
     */
    @SuppressWarnings("unchecked")
    private static void supersede(List<Map<String, Object>> offers, Map<String, Object> diagnostics)
    {
        Map<String, Map<String, Object>> lastInSlot = new TreeMap<>();
        for (Map<String, Object> offer : offers)
        {
            String fence = offer.get("account_hash") + "|" + offer.get("profile_type") + "|" + offer.get("slot");
            Map<String, Object> earlier = lastInSlot.put(fence, offer);
            if (earlier == null || Boolean.TRUE.equals(earlier.get("collected")))
            {
                continue;
            }
            earlier.put("superseded_by", offer.get("offer_key"));
            if ("open".equals(earlier.get("phase")))
            {
                earlier.put("phase", "unknown");
            }
            List<String> gaps = (List<String>) earlier.get("gaps");
            gaps.add("superseded_by_later_offer_key_same_slot");
            if (!Boolean.TRUE.equals(earlier.get("quarantined")))
            {
                earlier.put("completeness", "partial");
            }
            earlier.put("escrow", unknownEscrow(num(earlier.get("as_of_observed_ms")), String.valueOf(earlier.get("identity")),
                "superseded_by_later_offer_key_same_slot"));
            count(diagnostics, "superseded_offer_keys");
        }
    }

    private static Map<String, Object> quarantinedCashflow()
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("gp_counter_semantics", "runelite_spent_tax_unknown");
        c.put("status", "unknown_quarantined");
        return c;
    }

    /** Realized GP from credited counter differences. A buy refund and sell tax are qualified. */
    private static Map<String, Object> cashflow(String side, long price, long finalQty, long finalGp, long qty, long gp)
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("gp_counter_semantics", "runelite_spent_tax_unknown");
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
        c.put("tax_gp", null);
        putTaxRule(c, qty, gp, "spent_is_gross_unverified; item_exemptions_not_checked");
        return c;
    }

    /**
     * Per-item tax is floor(2% of that item's price), items under 50 gp exempt, capped per item.
     * Mixed prices inside one counter change are unknown, so the sum lies in
     * [floor(2% of gross) - qty + 1, floor(2% of gross)].
     */
    private static void putTaxRule(Map<String, Object> c, long qty, long gross, String assumption)
    {
        Long low = null, high = null;
        String status;
        if (qty == 0)
        {
            status = "no_sale";
            low = high = 0L;
        }
        else if (gross > TAX_CAP_PER_ITEM_GP * 50)
        {
            // Some item may be above the 250M price where the per-item cap binds.
            status = "unknown_cap_may_apply";
        }
        else
        {
            high = gross * 2 / 100;
            low = Math.max(0, high - qty + 1);
            if (gross < TAX_EXEMPT_BELOW_UNIT_GP * qty)
            {
                low = 0L;
            }
            status = low.equals(high) ? "rule_exact_if_gp_is_gross_and_item_not_exempt"
                : "rule_range_if_gp_is_gross_and_item_not_exempt";
        }
        c.put("tax_status", status);
        c.put("tax_rule_low_gp", low);
        c.put("tax_rule_high_gp", high);
        c.put("net_gp_rule_low", high == null ? null : gross - high);
        c.put("net_gp_rule_high", low == null ? null : gross - low);
        c.put("tax_assumption", assumption);
    }

    private static Map<String, Object> unknownEscrow(Long asOf, String identity, String basis)
    {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("as_of_observed_ms", asOf);
        e.put("basis", basis);
        e.put("escrow_state", "unknown");
        e.put("gap_or_reset_seen", !"inferred_session_slot".equals(identity));
        return e;
    }

    /** Escrow for a wealth ledger from the last observed absolute counters. Partial collection is unobservable. */
    private static Map<String, Object> escrow(String side, long price, long total, long qty, long gp, String phase,
        boolean collected, Long collectedAt, Long asOf, String identity)
    {
        Map<String, Object> e = new LinkedHashMap<>();
        boolean open = "open".equals(phase);
        boolean cancelled = "cancelled".equals(phase);
        e.put("as_of_observed_ms", collected ? collectedAt : asOf);
        e.put("basis", collected ? "slot_emptied_while_logged_in" : "last_observed_counter");
        e.put("escrow_state", "observed");
        e.put("gap_or_reset_seen", !"inferred_session_slot".equals(identity));
        e.put("filled_qty", qty);
        if (collected)
        {
            e.put("locked_coins", 0L);
            e.put("locked_items", 0L);
            e.put("uncollected_items_max", 0L);
            e.put("uncollected_coins_max", 0L);
            e.put("collection_note", "collect_is_not_proof_of_arrival_match_container_change");
            return e;
        }
        long lockedCoins, lockedItems, items, coins;
        if ("buy".equals(side))
        {
            lockedCoins = open ? price * (total - qty) : 0L;
            lockedItems = 0L;
            items = qty;
            coins = price * qty - gp + (cancelled ? price * (total - qty) : 0L);
        }
        else
        {
            lockedCoins = 0L;
            lockedItems = open ? total - qty : 0L;
            items = cancelled ? total - qty : 0L;
            coins = gp;
            e.put("coins_semantics", "gp_counter_tax_unknown");
        }
        if (lockedCoins < 0 || lockedItems < 0 || items < 0 || coins < 0)
        {
            // A buy can never spend more than its listing price allows; counters disagree with terms.
            return unknownEscrow(asOf, identity, "counters_inconsistent_with_offer_terms");
        }
        e.put("locked_coins", lockedCoins);
        e.put("locked_items", lockedItems);
        e.put("uncollected_items_max", items);
        e.put("uncollected_coins_max", coins);
        e.put("uncollected_semantics", "upper_bound_partial_collection_unobserved");
        return e;
    }

    /**
     * GE history rows have no time and no order ID. Join only a one-to-one match on item, side,
     * offer quantity and counters. A match corroborates the counters and, for a sell, gives the
     * displayed tax. Never adds a fill.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> joinHistory(List<Map<String, Object>> offers,
        List<Map<String, Object>> history, Map<String, Object> diagnostics)
    {
        List<Map<String, Object>> unmatched = new ArrayList<>();
        if (history == null)
        {
            return unmatched;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : history)
        {
            if (row == null) count(diagnostics, "history_row_invalid");
            else rows.add(row);
        }
        List<List<Integer>> rowMatches = new ArrayList<>();
        int[] offerMatchCount = new int[offers.size()];
        for (Map<String, Object> row : rows)
        {
            List<Integer> found = new ArrayList<>();
            for (int i = 0; i < offers.size(); i++)
            {
                Map<String, Object> offer = offers.get(i);
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
                    found.add(i);
                    offerMatchCount[i]++;
                }
            }
            rowMatches.add(found);
        }
        for (int r = 0; r < rows.size(); r++)
        {
            List<Integer> found = rowMatches.get(r);
            Map<String, Object> row = rows.get(r);
            if (found.isEmpty())
            {
                unmatched.add(historyOnly(row));
                continue;
            }
            if (found.size() != 1)
            {
                count(diagnostics, "history_row_ambiguous");
                continue;
            }
            if (offerMatchCount[found.get(0)] != 1)
            {
                count(diagnostics, "history_offer_ambiguous");
                continue;
            }
            Map<String, Object> offer = offers.get(found.get(0));
            String basis = counterMatch(row, offer);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("match", "unique_item_side_qty_counter");
            h.put("counter_equals", basis);
            h.put("history_gross_gp", num(row.get("original_gross_gp")));
            h.put("history_tax_gp", num(row.get("explicit_tax_gp")));
            h.put("history_net_gp", num(row.get("displayed_net_gp")));
            h.put("history_execution_time", "unknown");
            h.put("history_verified_with_actual_client", row.get("verified_with_actual_client"));
            offer.put("history_corroboration", h);
            Map<String, Object> c = (Map<String, Object>) offer.get("cashflow");
            Long tax = num(row.get("explicit_tax_gp")), gross = num(row.get("original_gross_gp")),
                net = num(row.get("displayed_net_gp"));
            if ("sell".equals(offer.get("side")) && tax != null && gross != null && net != null
                && Objects.equals(num(c.get("gp_counter")), num(offer.get("counter_gp"))))
            {
                // Exact only when the whole offer was credited; the history total is the whole offer.
                boolean counterIsGross = "history_gross".equals(basis);
                c.put("gp_counter_semantics", counterIsGross ? "matches_history_gross_one_offer"
                    : "matches_history_net_one_offer");
                // The rule range is always computed on the gross value, never on a net that is already taxed.
                putTaxRule(c, num(offer.get("counter_qty")), gross, "history_gross; item_exemptions_not_checked");
                c.put("tax_gp", tax);
                c.put("net_gp", net);
                Long low = num(c.get("tax_rule_low_gp")), high = num(c.get("tax_rule_high_gp"));
                c.put("tax_status", "history_explicit_offer_total");
                if (low != null && high != null && (tax < low || tax > high))
                {
                    // Exemption, cap or a rule change: keep the displayed value and flag the disagreement.
                    c.put("tax_rule_disagrees_with_history", true);
                }
            }
            count(diagnostics, "history_joined");
        }
        unmatched.sort((a, b) -> canonical(a).compareTo(canonical(b)));
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
        h.put("item_id", num(row.get("item_id")));
        h.put("side", row.get("side") instanceof String ? row.get("side") : null);
        h.put("quantity", num(row.get("quantity")));
        h.put("gross_gp", num(row.get("original_gross_gp")));
        h.put("tax_gp", num(row.get("explicit_tax_gp")));
        h.put("net_gp", num(row.get("displayed_net_gp")));
        h.put("displayed_total_gp", num(row.get("displayed_total_gp")));
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
            && Objects.equals(num(a.get("price")), num(b.get("price")))
            && Objects.equals(num(a.get("slot")), num(b.get("slot")))
            && Objects.equals(a.get("account_hash"), b.get("account_hash"));
    }

    private static String worse(String a, Object b)
    {
        int ia = IDENTITY_RANK.indexOf(a), ib = IDENTITY_RANK.indexOf(b);
        if (ib < 0) return "ambiguous_counter_regression"; // an unknown label is never trusted
        return ib > ia ? (String) b : a;
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
        Long v = num(((Map<?, ?>) e.get("ge_observation")).get("sequence"));
        return v == null ? 0 : v;
    }

    /** Content identity without transport-only envelope fields. Numbers compare by value, not type. */
    private static String canonical(Map<String, Object> e)
    {
        Map<String, Object> copy = new TreeMap<>();
        for (Map.Entry<String, Object> x : e.entrySet())
        {
            String k = x.getKey();
            // Gson omits nulls by default, so an absent field and a null field are the same content.
            if (x.getValue() != null && !"event_id".equals(k) && !"ts".equals(k) && !"received_at".equals(k)
                && !"token_fp".equals(k) && !k.startsWith("_"))
            {
                copy.put(k, normal(x.getValue()));
            }
        }
        return copy.toString();
    }

    private static Object normal(Object v)
    {
        if (v instanceof Number)
        {
            Long n = num(v);
            return n != null ? "n" + n : "d" + ((Number) v).doubleValue();
        }
        if (v instanceof Map)
        {
            Map<String, Object> m = new TreeMap<>();
            for (Map.Entry<?, ?> x : ((Map<?, ?>) v).entrySet())
            {
                if (x.getValue() != null) m.put(String.valueOf(x.getKey()), normal(x.getValue()));
            }
            return m;
        }
        if (v instanceof List)
        {
            List<Object> l = new ArrayList<>();
            for (Object x : (List<?>) v) l.add(normal(x));
            return l;
        }
        return v;
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

    private static void count(Map<String, Object> d, String key)
    {
        add(d, key, 1);
    }

    private static void add(Map<String, Object> d, String key, long n)
    {
        if (n <= 0) return;
        Long v = num(d.get(key));
        d.put(key, (v == null ? 0 : v) + n);
    }
}
