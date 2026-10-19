package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static net.runelite.api.GrandExchangeOfferState.*;
import static org.junit.Assert.*;

/**
 * Lifecycle reducer over rows produced by the real GeObservationTracker and projected by
 * PublicTransport, so the fixture is the wire shape, not a hand-written guess of it.
 */
public class GeOfferLifecycleTest
{
    private final GeObservationTracker tracker = new GeObservationTracker();
    private final List<Map<String, Object>> wire = new ArrayList<>();
    private int ids;

    /** Observe one slot change; wrap and project an emitted row like emitEvent + PublicTransport. */
    private Map<String, Object> obs(int slot, GrandExchangeOfferState s, int item, long qty, long total, long price,
        long gp, int tick)
    {
        Map<String, Object> f = tracker.observe(slot, s, new long[]{item, qty, total, price, gp}, false, tick,
            1_000_000L + tick * 600L);
        if (f == null)
        {
            return null;
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", f.remove("type"));
        ev.put("ts", 2_000_000L + tick);
        ev.put("event_id", String.format("%032x", ++ids));
        ev.put("account_hash", "42");
        ev.put("profile_type", "STANDARD");
        ev.putAll(f);
        Map<String, Object> projected = PublicTransport.event(ev);
        assertNotNull(projected);
        wire.add(projected);
        return projected;
    }

    private Map<String, Object> empty(int slot, int tick)
    {
        return obs(slot, EMPTY, 0, 0, 0, 0, 0, tick);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> offers(Map<String, Object> out)
    {
        return (List<Map<String, Object>>) out.get("offers");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o)
    {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> copyWithId(Map<String, Object> row, String id)
    {
        Map<String, Object> c = new LinkedHashMap<>(row);
        c.put("event_id", id);
        return c;
    }

    @Test
    public void partialFillCancelCollectThenSlotReuseWithReconnectDuplicates()
    {
        empty(2, 1);
        obs(2, BUYING, 560, 0, 1000, 250, 0, 2);
        obs(2, BUYING, 560, 400, 1000, 250, 98_000, 110);
        Map<String, Object> cancel = obs(2, CANCELLED_BUY, 560, 400, 1000, 250, 98_000, 140);
        Map<String, Object> collect = empty(2, 150);
        Map<String, Object> reuse = obs(2, BUYING, 560, 0, 1000, 250, 0, 151);
        obs(2, BOUGHT, 560, 1000, 1000, 250, 240_000, 300);
        // Spool replay re-sends a row with the same id, and a reconnect re-sends one under a new id.
        wire.add(new LinkedHashMap<>(cancel));
        wire.add(copyWithId(collect, "ffffffffffffffffffffffffffffffff"));

        Map<String, Object> out = GeOfferLifecycle.reduce(wire, null);
        List<Map<String, Object>> offers = offers(out);
        assertEquals("slot reuse is a second offer, duplicates add none", 2, offers.size());
        Map<String, Object> first = offers.get(0), second = offers.get(1);
        assertNotEquals(first.get("offer_key"), second.get("offer_key"));
        assertEquals(cancel.get("offer_key"), first.get("offer_key"));
        assertEquals(reuse.get("offer_key"), second.get("offer_key"));

        assertEquals("cancelled", first.get("phase"));
        assertEquals("observed", first.get("placement"));
        assertEquals(400L, first.get("filled_qty_observed"));
        assertEquals(98_000L, first.get("filled_gp_observed"));
        assertEquals(true, first.get("collected"));
        assertEquals("complete_observed_lifecycle", first.get("completeness"));
        assertEquals(1, ((List<?>) first.get("fills")).size());
        assertEquals("245", m(((List<?>) first.get("fills")).get(0)).get("avg_gp_per_unit_proxy"));
        assertEquals(Arrays.asList(1_001_200L, 1_066_000L), first.get("first_fill_interval_ms"));
        assertNull("a cancelled offer has no completion interval", first.get("completion_interval_ms"));
        Map<String, Object> cash = m(first.get("cashflow"));
        assertEquals(98_000L, cash.get("coins_paid_gp"));
        assertEquals(400L, cash.get("items_bought"));
        assertEquals("listing 250 x 400 minus 98,000 spent", 2_000L, cash.get("overpay_refund_gp_whole_offer"));
        assertEquals(0L, cash.get("tax_gp"));
        assertEquals("collected escrow is zero, arrival needs a container match", 0L,
            m(first.get("escrow")).get("locked_coins"));

        assertEquals("completed", second.get("phase"));
        assertEquals(1000L, second.get("filled_qty_observed"));
        assertEquals(240_000L, second.get("filled_gp_observed"));
        assertEquals(false, second.get("collected"));
        assertEquals("partial", second.get("completeness"));
        Map<String, Object> esc = m(second.get("escrow"));
        assertEquals("observed", esc.get("escrow_state"));
        assertEquals(0L, esc.get("locked_coins"));
        assertEquals(1000L, esc.get("uncollected_items_max"));
        assertEquals(10_000L, esc.get("uncollected_coins_max"));

        Map<String, Object> d = m(out.get("diagnostics"));
        assertEquals(1L, d.get("duplicate_event_id_dropped"));
        assertEquals(1L, d.get("duplicate_sequence_dropped"));
    }

    /** Writes the demonstrated derived result for the handoff; the assertions above pin its values. */
    @Test
    public void writesTheExampleTimeline() throws Exception
    {
        empty(2, 1);
        obs(2, BUYING, 560, 0, 1000, 250, 0, 2);
        obs(2, BUYING, 560, 400, 1000, 250, 98_000, 110);
        obs(2, CANCELLED_BUY, 560, 400, 1000, 250, 98_000, 140);
        empty(2, 150);
        obs(2, SELLING, 560, 0, 400, 260, 0, 151);
        obs(2, SELLING, 560, 150, 400, 260, 39_150, 260);
        obs(2, SOLD, 560, 400, 400, 260, 104_400, 400);
        empty(2, 420);
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("item_id", 560);
        history.put("quantity", 400);
        history.put("side", "sell");
        history.put("original_gross_gp", 104_400L);
        history.put("explicit_tax_gp", 2_000L);
        history.put("displayed_net_gp", 102_400L);
        history.put("verified_with_actual_client", false);
        Map<String, Object> mobile = new LinkedHashMap<>();
        mobile.put("item_id", 11802);
        mobile.put("quantity", 1);
        mobile.put("side", "sell");
        mobile.put("original_gross_gp", 20_000_000L);
        mobile.put("explicit_tax_gp", 400_000L);
        mobile.put("displayed_net_gp", 19_600_000L);
        Map<String, Object> out = GeOfferLifecycle.reduce(wire, Arrays.asList(history, mobile), "caller-generation-1");
        Map<String, Object> sell = offers(out).get(1);
        assertEquals(2_000L, m(sell.get("cashflow")).get("tax_gp"));
        assertEquals(1, ((List<?>) out.get("history_only")).size());
        java.nio.file.Path dir = java.nio.file.Paths.get("build", "ge-lifecycle");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve("example-input.json"), new com.google.gson.GsonBuilder()
            .setPrettyPrinting().serializeNulls().create().toJson(wire).getBytes("UTF-8"));
        java.nio.file.Files.write(dir.resolve("example-output.json"), new com.google.gson.GsonBuilder()
            .setPrettyPrinting().serializeNulls().create().toJson(out).getBytes("UTF-8"));
    }

    @Test
    public void openBuyEscrowLocksTheUnfilledRemainder()
    {
        empty(0, 1);
        obs(0, BUYING, 2, 0, 100, 300, 0, 2);
        obs(0, BUYING, 2, 30, 100, 300, 8_700, 200);
        Map<String, Object> esc = m(offers(GeOfferLifecycle.reduce(wire, null)).get(0).get("escrow"));
        assertEquals(21_000L, esc.get("locked_coins"));
        assertEquals(30L, esc.get("uncollected_items_max"));
        assertEquals(300L, esc.get("uncollected_coins_max"));
        assertEquals(30L, esc.get("filled_qty"));
    }

    @Test
    public void loginBaselineNeverCreditsEarlierFills()
    {
        // First sight of a part-filled offer after plugin start: earlier fills are not ours to credit.
        obs(5, SELLING, 4151, 3, 10, 1_500_000, 4_480_000, 10);
        obs(5, SOLD, 4151, 10, 10, 1_500_000, 14_950_000, 300);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals("not_observed", o.get("placement"));
        assertEquals(3L, o.get("baseline_qty_uncredited"));
        assertEquals(7L, o.get("filled_qty_observed"));
        assertEquals(10_470_000L, o.get("filled_gp_observed"));
        assertNull("no placement, no completion duration", o.get("completion_interval_ms"));
        assertTrue(((List<?>) o.get("gaps")).contains("placement_not_observed_earlier_fills_uncredited"));
        assertEquals("partial", o.get("completeness"));
    }

    @Test
    public void sellTaxIsARuleRangeAndHistoryGivesTheExactValueWithoutSecondDeduction()
    {
        empty(1, 1);
        obs(1, SELLING, 4151, 0, 3, 1_000_000, 0, 2);
        obs(1, SOLD, 4151, 3, 3, 1_000_000, 3_060_000, 120);
        empty(1, 130);
        Map<String, Object> cash = m(offers(GeOfferLifecycle.reduce(wire, null)).get(0).get("cashflow"));
        assertNull("tax is never asserted from the counter alone", cash.get("tax_gp"));
        assertEquals(61_198L, cash.get("tax_rule_low_gp"));
        assertEquals(61_200L, cash.get("tax_rule_high_gp"));
        assertEquals(2_998_800L, cash.get("net_gp_rule_low"));
        assertEquals(2_998_802L, cash.get("net_gp_rule_high"));

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("item_id", 4151);
        history.put("quantity", 3);
        history.put("side", "sell");
        history.put("original_gross_gp", 3_060_000L);
        history.put("explicit_tax_gp", 61_200L);
        history.put("displayed_net_gp", 2_998_800L);
        history.put("verified_with_actual_client", false);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, Arrays.asList(history))).get(0);
        cash = m(o.get("cashflow"));
        assertEquals(61_200L, cash.get("tax_gp"));
        assertEquals("history net is used as-is, never taxed again", 2_998_800L, cash.get("net_gp"));
        assertEquals("matches_history_gross_one_offer", cash.get("gp_counter_semantics"));
        assertEquals("history_gross", m(o.get("history_corroboration")).get("counter_equals"));
        assertEquals("history adds no fill", 3L, o.get("filled_qty_observed"));
    }

    @Test
    public void historyTaxOutsideTheRuleIsKeptAndFlagged()
    {
        // e.g. an exempt item: the displayed zero tax wins, the rule range is shown as disagreeing.
        empty(1, 1);
        obs(1, SELLING, 1755, 0, 10, 100, 0, 2);
        obs(1, SOLD, 1755, 10, 10, 100, 1_000, 3);
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("item_id", 1755);
        history.put("quantity", 10);
        history.put("side", "sell");
        history.put("original_gross_gp", 1_000L);
        history.put("explicit_tax_gp", 0L);
        history.put("displayed_net_gp", 1_000L);
        Map<String, Object> cash = m(offers(GeOfferLifecycle.reduce(wire, Arrays.asList(history))).get(0).get("cashflow"));
        assertEquals(0L, cash.get("tax_gp"));
        assertEquals(1_000L, cash.get("net_gp"));
        assertEquals(true, cash.get("tax_rule_disagrees_with_history"));
    }

    @Test
    public void twoEqualOffersMakeHistoryAmbiguous()
    {
        empty(1, 1);
        obs(1, SELLING, 4151, 0, 1, 1_000_000, 0, 2);
        obs(1, SOLD, 4151, 1, 1, 1_000_000, 1_000_000, 3);
        empty(1, 4);
        empty(4, 5);
        obs(4, SELLING, 4151, 0, 1, 1_000_000, 0, 6);
        obs(4, SOLD, 4151, 1, 1, 1_000_000, 1_000_000, 7);
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("item_id", 4151);
        history.put("quantity", 1);
        history.put("side", "sell");
        history.put("original_gross_gp", 1_000_000L);
        history.put("explicit_tax_gp", 20_000L);
        history.put("displayed_net_gp", 980_000L);
        Map<String, Object> out = GeOfferLifecycle.reduce(wire, Arrays.asList(history));
        for (Map<String, Object> o : offers(out))
        {
            assertNull(o.get("history_corroboration"));
            assertNull(m(o.get("cashflow")).get("tax_gp"));
        }
        assertEquals(1L, m(out.get("diagnostics")).get("history_row_ambiguous"));
    }

    @Test
    public void mobileTradeSeenOnlyInHistoryIsAnUntimedTotalNotAnOffer()
    {
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("item_id", 11802);
        history.put("quantity", 1);
        history.put("side", "sell");
        history.put("original_gross_gp", 20_000_000L);
        history.put("explicit_tax_gp", 400_000L);
        history.put("displayed_net_gp", 19_600_000L);
        Map<String, Object> out = GeOfferLifecycle.reduce(wire, Arrays.asList(history), "gen-a");
        assertTrue(offers(out).isEmpty());
        assertEquals("gen-a", out.get("generation"));
        List<?> only = (List<?>) out.get("history_only");
        assertEquals(1, only.size());
        assertEquals("offer_total_only", m(only.get(0)).get("recoverable"));
        assertEquals("unknown", m(only.get(0)).get("execution_time"));
        assertEquals(19_600_000L, m(only.get(0)).get("net_gp"));
    }

    @Test
    public void recordListsContributingEventIdsAndAsOf()
    {
        empty(0, 1);
        Map<String, Object> placed = obs(0, BUYING, 560, 0, 10, 250, 0, 2);
        Map<String, Object> done = obs(0, BOUGHT, 560, 10, 10, 250, 2_400, 50);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals(Arrays.asList(placed.get("event_id"), done.get("event_id")), o.get("contributing_event_ids"));
        assertEquals(1_030_000L, o.get("as_of_observed_ms"));
    }

    @Test
    public void instantFillIsCreditedButPlacementStaysUnconfirmed()
    {
        // On the wire an instant fill after an empty slot looks the same as a fill after an unseen
        // zero-counter replay seed, so the fill counts but placement and duration are not claimed.
        empty(3, 1);
        obs(3, BOUGHT, 1511, 100, 100, 5, 400, 2);
        empty(3, 3);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals("not_observed", o.get("placement"));
        assertEquals("completed", o.get("phase"));
        assertEquals(100L, o.get("filled_qty_observed"));
        assertNull(o.get("completion_interval_ms"));
        assertTrue(((List<?>) o.get("gaps")).contains("placement_unconfirmed_zero_baseline"));
        assertEquals("partial", o.get("completeness"));
    }

    @Test
    public void replaySeedThenLiveFillIsNotAnObservedPlacement()
    {
        tracker.observe(3, BUYING, new long[]{1511, 0, 100, 5, 0}, true, 1, 1_000_000L);
        obs(3, BOUGHT, 1511, 100, 100, 5, 400, 200);
        empty(3, 201);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals("not_observed", o.get("placement"));
        assertEquals("partial", o.get("completeness"));
    }

    @Test
    public void restartRekeyNeverLeavesTheOldKeyHoldingEscrow()
    {
        empty(3, 1);
        obs(3, BUYING, 560, 0, 100, 300, 0, 2);
        obs(3, BUYING, 560, 40, 100, 300, 12_000, 110);
        Map<String, Object> oldKey = wire.get(wire.size() - 1);
        tracker.clear();	// plugin restart or profile change; the same live offer gets a new key
        tracker.observe(3, BUYING, new long[]{560, 40, 100, 300, 12_000}, true, 300, 1_180_000L);
        Map<String, Object> newKey = obs(3, BOUGHT, 560, 100, 100, 300, 30_000, 400);
        List<Map<String, Object>> offers = offers(GeOfferLifecycle.reduce(wire, null));
        assertEquals(2, offers.size());
        Map<String, Object> first = offers.get(0), second = offers.get(1);
        assertEquals(oldKey.get("offer_key"), first.get("offer_key"));
        assertEquals(newKey.get("offer_key"), first.get("superseded_by"));
        assertEquals("unknown", first.get("phase"));
        assertEquals("unknown", m(first.get("escrow")).get("escrow_state"));
        assertNull(m(first.get("escrow")).get("locked_coins"));
        assertEquals("fills before the restart stay credited once", 40L, first.get("filled_qty_observed"));
        assertEquals("the new key credits only what came after", 60L, second.get("filled_qty_observed"));
        assertEquals(40L, second.get("baseline_qty_uncredited"));
        assertEquals("observed", m(second.get("escrow")).get("escrow_state"));
    }

    @Test
    public void clockStepBackAcrossRestartMakesBothKeysUnknownNotTheLiveOneAlone()
    {
        empty(3, 1);
        obs(3, BUYING, 560, 0, 100, 300, 0, 2);
        obs(3, BUYING, 560, 40, 100, 300, 12_000, 110);
        tracker.clear();
        // The restarted client's clock is behind: the live key's times precede the stale key's.
        Map<String, Object> f = tracker.observe(3, BUYING, new long[]{560, 40, 100, 300, 12_000}, true, 300, 900_000L);
        assertNull(f);
        f = tracker.observe(3, BUYING, new long[]{560, 60, 100, 300, 18_000}, false, 500, 950_000L);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", f.remove("type"));
        ev.put("event_id", String.format("%032x", ++ids));
        ev.put("account_hash", "42");
        ev.put("profile_type", "STANDARD");
        ev.putAll(f);
        wire.add(PublicTransport.event(ev));
        for (Map<String, Object> o : offers(GeOfferLifecycle.reduce(wire, null)))
        {
            assertEquals(o.toString(), "unknown", m(o.get("escrow")).get("escrow_state"));
            assertTrue(o.toString(), ((List<?>) o.get("gaps")).contains("slot_order_ambiguous"));
        }
    }

    @Test
    public void unusableCopyUnderAUsedEventIdStillQuarantines()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 10, 250, 0, 2);
        Map<String, Object> done = obs(0, BOUGHT, 560, 10, 10, 250, 2_400, 50);
        Map<String, Object> stripped = new LinkedHashMap<>(done);
        stripped.remove("ge_observation");
        wire.add(0, stripped);
        assertEquals(true, offers(GeOfferLifecycle.reduce(wire, null)).get(0).get("quarantined"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void rawPollutedInputIsSkipped()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 10, 250, 0, 2);
        List raw = new ArrayList(wire);
        Map bad = new LinkedHashMap();
        bad.put(5, "x");
        raw.add(bad);
        raw.add("not a row");
        List history = new ArrayList();
        history.add(7);
        Map<String, Object> out = GeOfferLifecycle.reduce(raw, history);
        assertEquals(1, offers(out).size());
        assertEquals(1L, m(out.get("diagnostics")).get("history_row_invalid"));
        assertEquals(2L, m(out.get("diagnostics")).get("non_ge_rows_ignored"));
    }

    @Test
    public void missingSlotQuarantines()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 10, 250, 0, 2);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : wire)
        {
            Map<String, Object> c = new LinkedHashMap<>(r);
            c.remove("slot");
            rows.add(c);
        }
        assertEquals(true, offers(GeOfferLifecycle.reduce(rows, null)).get(0).get("quarantined"));
    }

    @Test
    public void historyMatchOnNetNeverTaxesTheNetAgain()
    {
        empty(1, 1);
        obs(1, SELLING, 4151, 0, 3, 1_000_000, 0, 2);
        obs(1, SOLD, 4151, 3, 3, 1_000_000, 2_998_800, 120);
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("item_id", 4151);
        history.put("quantity", 3);
        history.put("side", "sell");
        history.put("original_gross_gp", 3_060_000L);
        history.put("explicit_tax_gp", 61_200L);
        history.put("displayed_net_gp", 2_998_800L);
        Map<String, Object> cash = m(offers(GeOfferLifecycle.reduce(wire, Arrays.asList(history))).get(0).get("cashflow"));
        assertEquals("matches_history_net_one_offer", cash.get("gp_counter_semantics"));
        assertEquals(61_200L, cash.get("tax_gp"));
        assertEquals(2_998_800L, cash.get("net_gp"));
        assertEquals(2_998_800L, cash.get("net_gp_rule_low"));
        assertEquals(2_998_802L, cash.get("net_gp_rule_high"));
        assertNull(cash.get("tax_rule_disagrees_with_history"));
    }

    @Test
    public void shuffledDuplicatedInputGivesTheIdenticalResult()
    {
        empty(2, 1);
        obs(2, BUYING, 560, 0, 1000, 250, 0, 2);
        obs(2, BUYING, 560, 400, 1000, 250, 98_000, 110);
        obs(2, CANCELLED_BUY, 560, 400, 1000, 250, 98_000, 140);
        empty(2, 150);
        empty(5, 150);
        obs(5, SELLING, 4151, 0, 2, 1_000_000, 0, 150);	// same first_seen as nothing else, tie on time below
        obs(2, BUYING, 560, 0, 1000, 250, 0, 151);
        wire.add(copyWithId(wire.get(2), "ffffffffffffffffffffffffffffffff"));
        wire.add(new LinkedHashMap<>(wire.get(3)));
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("item_id", 1);
        history.put("side", "buy");
        Map<String, Object> history2 = new LinkedHashMap<>(history);
        history2.put("item_id", 2);
        Map<String, Object> expected = GeOfferLifecycle.reduce(wire, Arrays.asList(history, history2));
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 20; i++)
        {
            List<Map<String, Object>> shuffled = new ArrayList<>(wire);
            java.util.Collections.shuffle(shuffled, random);
            List<Map<String, Object>> hs = new ArrayList<>(Arrays.asList(history, history2));
            java.util.Collections.shuffle(hs, random);
            assertEquals(expected, GeOfferLifecycle.reduce(shuffled, hs));
        }
    }

    @Test
    public void conflictQuarantinesEveryKeyTheIdNamesWhicheverArrivesFirst()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 10, 250, 0, 2);
        Map<String, Object> real = obs(0, BOUGHT, 560, 10, 10, 250, 2_400, 50);
        Map<String, Object> variant = new LinkedHashMap<>(real);
        variant.put("offer_key", "other-key");
        for (boolean variantFirst : new boolean[]{false, true})
        {
            List<Map<String, Object>> rows = new ArrayList<>(wire);
            rows.add(variantFirst ? 0 : rows.size(), variant);
            for (Map<String, Object> o : offers(GeOfferLifecycle.reduce(rows, null)))
            {
                assertEquals(o.get("offer_key") + " variantFirst=" + variantFirst, true, o.get("quarantined"));
            }
        }
    }

    @Test
    public void missingOfferTermsQuarantineInsteadOfBecomingZero()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 100, 300, 0, 2);
        obs(0, BUYING, 560, 30, 100, 300, 8_700, 200);
        for (String field : new String[]{"side", "price", "qty_total", "item"})
        {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : wire)
            {
                Map<String, Object> c = new LinkedHashMap<>(r);
                c.remove(field);
                rows.add(c);
            }
            Map<String, Object> o = offers(GeOfferLifecycle.reduce(rows, null)).get(0);
            assertEquals(field, true, o.get("quarantined"));
            assertEquals(field, "unknown", m(o.get("escrow")).get("escrow_state"));
        }
    }

    @Test
    public void gsonRoundTripOfTheSameRowIsADuplicateNotAConflict()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 10, 250, 0, 2);
        Map<String, Object> done = obs(0, BOUGHT, 560, 10, 10, 250, 2_400, 50);
        com.google.gson.Gson gson = new com.google.gson.Gson();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = gson.fromJson(gson.toJson(done), Map.class);
        wire.add(parsed);
        Map<String, Object> out = GeOfferLifecycle.reduce(wire, null);
        assertEquals(false, offers(out).get(0).get("quarantined"));
        assertEquals(1L, m(out.get("diagnostics")).get("duplicate_event_id_dropped"));
    }

    @Test
    public void malformedRowsAreSkippedNotFatal()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 10, 250, 0, 2);
        Map<String, Object> bad = new LinkedHashMap<>(wire.get(0));
        Map<Object, Object> weird = new LinkedHashMap<>(m(bad.get("ge_observation")));
        weird.put(7, "x");
        bad.put("ge_observation", weird);
        bad.put("event_id", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        wire.add(bad);
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(null);
        Map<String, Object> out = GeOfferLifecycle.reduce(wire, history);
        assertEquals(1L, m(out.get("diagnostics")).get("history_row_invalid"));
    }

    @Test
    public void negativeOrInconsistentDeltaIsNeverAFill()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 10, 250, 0, 2);
        Map<String, Object> fill = obs(0, BUYING, 560, 5, 10, 250, 1_200, 200);
        Map<String, Object> obs = new LinkedHashMap<>(m(fill.get("ge_observation")));
        obs.put("delta_gp", -1_500L);
        fill.put("ge_observation", obs);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals(true, o.get("quarantined"));
        assertTrue(((List<?>) o.get("quarantine_reasons")).contains("inconsistent_counter_pair"));
    }

    @Test
    public void strippedFirstRowCreditsNothingAndUnknownTimesStayNull()
    {
        obs(5, SELLING, 4151, 3, 10, 1_500_000, 4_480_000, 10);
        Map<String, Object> obs = new LinkedHashMap<>(m(wire.get(0).get("ge_observation")));
        obs.remove("delta_from_qty");
        obs.remove("delta_from_gp");
        obs.remove("delta_status");
        obs.remove("observed_at_ms");
        wire.get(0).put("ge_observation", obs);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals(true, o.get("quarantined"));
        assertNull(o.get("filled_qty_observed"));
        assertNull(o.get("first_seen_at_ms"));
    }

    @Test
    public void counterRegressionQuarantinesTheWholeOffer()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 100, 300, 0, 2);
        obs(0, BUYING, 560, 50, 100, 300, 15_000, 110);
        assertNull(obs(0, BUYING, 560, 20, 100, 300, 6_000, 111));
        obs(0, BOUGHT, 560, 100, 100, 300, 30_000, 220);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals(true, o.get("quarantined"));
        assertNull(o.get("filled_qty_observed"));
        assertTrue(((List<?>) o.get("fills")).isEmpty());
        assertEquals("unknown", m(o.get("escrow")).get("escrow_state"));
        assertEquals("unknown_quarantined", m(o.get("cashflow")).get("status"));
    }

    @Test
    public void hopGapStaysCreditedButFlagged()
    {
        empty(6, 1);
        obs(6, BUYING, 560, 0, 10, 250, 0, 2);
        tracker.gap();
        obs(6, BOUGHT, 560, 10, 10, 250, 2_400, 500);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals("inferred_across_gap", o.get("identity"));
        assertEquals(10L, o.get("filled_qty_observed"));
        assertTrue(((List<?>) o.get("gaps")).contains("observation_gap_logout_or_hop"));
        assertEquals(true, m(o.get("escrow")).get("gap_or_reset_seen"));
    }

    @Test
    public void lostMiddleRowKeepsTheTotalButNotItsTiming()
    {
        empty(7, 1);
        obs(7, BUYING, 560, 0, 100, 300, 0, 2);
        Map<String, Object> lost = obs(7, BUYING, 560, 40, 100, 300, 12_000, 110);
        obs(7, BOUGHT, 560, 100, 100, 300, 30_000, 220);
        wire.remove(lost);
        Map<String, Object> o = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        assertEquals("the terminal row's own delta covers only 60", 100L, o.get("filled_qty_observed"));
        assertEquals(40L, o.get("untimed_fill_qty"));
        assertTrue(((List<?>) o.get("gaps")).contains("missing_rows_between_observations"));
        assertEquals("partial", o.get("completeness"));
    }

    @Test
    public void conflictingContentUnderOneEventIdQuarantines()
    {
        empty(0, 1);
        obs(0, BUYING, 560, 0, 100, 300, 0, 2);
        Map<String, Object> real = obs(0, BUYING, 560, 50, 100, 300, 15_000, 110);
        Map<String, Object> forged = new LinkedHashMap<>(real);
        forged.put("gp", 1L);
        wire.add(forged);
        Map<String, Object> out = GeOfferLifecycle.reduce(wire, null);
        assertEquals(true, offers(out).get(0).get("quarantined"));
        assertEquals(1L, m(out.get("diagnostics")).get("conflicting_event_id"));
    }

    @Test
    public void reorderedDeliveryGivesTheSameRecord()
    {
        empty(2, 1);
        obs(2, SELLING, 4151, 0, 2, 1_000_000, 0, 2);
        obs(2, SELLING, 4151, 1, 2, 1_000_000, 1_000_000, 110);
        obs(2, CANCELLED_SELL, 4151, 1, 2, 1_000_000, 1_000_000, 120);
        empty(2, 130);
        Map<String, Object> inOrder = offers(GeOfferLifecycle.reduce(wire, null)).get(0);
        List<Map<String, Object>> reversed = new ArrayList<>(wire);
        java.util.Collections.reverse(reversed);
        assertEquals(inOrder, offers(GeOfferLifecycle.reduce(reversed, null)).get(0));
        Map<String, Object> esc = m(inOrder.get("escrow"));
        assertEquals(0L, esc.get("uncollected_items_max"));
        assertEquals(1L, m(inOrder.get("cashflow")).get("items_sold"));
    }

    @Test
    public void cancelledSellBeforeCollectReturnsUnsoldItemsToEscrow()
    {
        empty(2, 1);
        obs(2, SELLING, 4151, 0, 2, 1_000_000, 0, 2);
        obs(2, SELLING, 4151, 1, 2, 1_000_000, 1_000_000, 110);
        obs(2, CANCELLED_SELL, 4151, 1, 2, 1_000_000, 1_000_000, 120);
        Map<String, Object> esc = m(offers(GeOfferLifecycle.reduce(wire, null)).get(0).get("escrow"));
        assertEquals(0L, esc.get("locked_items"));
        assertEquals(1L, esc.get("uncollected_items_max"));
        assertEquals(1_000_000L, esc.get("uncollected_coins_max"));
    }
}
