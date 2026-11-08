package com.osrsbestinslot.export;

import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static net.runelite.api.GrandExchangeOfferState.*;
import static org.junit.Assert.*;

public class GeObservationTrackerTest
{
    private final GeObservationTracker tracker = new GeObservationTracker();

    private Map<String, Object> at(GrandExchangeOfferState state, long qty, long gp, int tick)
    {
        return tracker.observe(0, state, new long[]{560, qty, 100, 300, gp}, false, tick, 100000L + tick);
    }

    private Map<?, ?> meta(Map<String, Object> row)
    {
        assertNotNull(row);
        return (Map<?, ?>) row.get("ge_observation");
    }

    @Test
    public void coalescedDeltasTerminalAndCollectNeverDoubleCredit()
    {
        Map<String, Object> start = at(BUYING, 0, 0, 1);
        assertNull(at(BUYING, 10, 2400, 2));
        Map<String, Object> progress = at(BUYING, 40, 9000, 101);
        assertEquals(40L, meta(progress).get("delta_qty"));
        assertEquals(9000L, meta(progress).get("delta_gp"));
        assertEquals(100001L, meta(progress).get("interval_start_ms"));
        assertEquals(100101L, meta(progress).get("interval_end_ms"));
        assertEquals(300L, meta(progress).get("listing_price"));
        assertNull(meta(progress).get("execution_price"));
        assertNull(meta(progress).get("tax_gp"));
        Map<String, Object> end = at(BOUGHT, 100, 21000, 102);
        assertEquals(60L, meta(end).get("delta_qty"));
        assertEquals(12000L, meta(end).get("delta_gp"));
        assertNull(at(BOUGHT, 100, 21000, 103));
        Map<String, Object> collect = at(EMPTY, 0, 0, 104);
        assertEquals(0L, meta(collect).get("delta_qty"));
        assertEquals(0L, meta(collect).get("delta_gp"));
        assertEquals(start.get("offer_key"), collect.get("offer_key"));
        assertNotEquals(start.get("offer_key"), at(BUYING, 0, 0, 105).get("offer_key"));
    }

    @Test
    public void regressionQuarantinesEvenWhenCountersRecover()
    {
        at(BUYING, 0, 0, 1);
        assertEquals(50L, meta(at(BUYING, 50, 10000, 101)).get("delta_qty"));
        assertNull(at(BUYING, 20, 4000, 102));
        Map<?, ?> end = meta(at(BOUGHT, 100, 20000, 201));
        assertNull(end.get("delta_qty"));
        assertNull(end.get("delta_gp"));
        assertNull(end.get("interval_start_ms"));
        assertEquals("ambiguous_counter_regression", end.get("identity"));
    }

    @Test
    public void gpOnlyRegressionAndMalformedCountsCannotBeCredited()
    {
        at(BUYING, 10, 2000, 1);
        assertNull(at(BUYING, 20, 1000, 101));
        assertNull(meta(at(BOUGHT, 100, 20000, 201)).get("delta_gp"));
        tracker.clear();
        at(BUYING, 0, 0, 1);
        assertNull(at(BUYING, 101, 1000, 101));
        assertNull(meta(at(BOUGHT, 100, 20000, 201)).get("delta_qty"));
    }

    @Test
    public void identicalUnseenRelistOrStaleActiveAfterTerminalIsAmbiguous()
    {
        at(BUYING, 0, 0, 1);
        at(BOUGHT, 100, 20000, 101);
        at(BUYING, 0, 0, 102);
        assertNull(meta(at(BOUGHT, 100, 20000, 103)).get("delta_qty"));
    }

    @Test
    public void pendingThrottledFillIsCarriedByCollect()
    {
        at(BUYING, 0, 0, 1);
        assertNull(at(BUYING, 20, 4000, 2));
        Map<?, ?> collect = meta(at(EMPTY, 0, 0, 3));
        assertEquals(20L, collect.get("delta_qty"));
        assertEquals(100002L, collect.get("interval_end_ms"));
        assertEquals(100003L, collect.get("observed_at_ms"));
    }

    @Test
    public void partialReplayPreservesKeyAndOnlyCreditsTheDifference()
    {
        Map<String, Object> first = at(BUYING, 20, 4000, 1);
        tracker.gap();
        Map<String, Object> replay = tracker.observe(0, BUYING, new long[]{560, 40, 100, 300, 8000}, true, 2, 100002L);
        assertEquals(first.get("offer_key"), replay.get("offer_key"));
        assertEquals(20L, meta(replay).get("delta_qty"));
        assertEquals("inferred_across_gap", meta(replay).get("identity"));
        assertNull(tracker.observe(0, BUYING, new long[]{560, 40, 100, 300, 8000}, true, 3, 100003L));
        assertEquals(60L, meta(at(BOUGHT, 100, 20000, 4)).get("delta_qty"));
    }

    @Test
    public void restartAndOtherCollectorDoNotCreditHistoricalCumulativeTotals()
    {
        Map<String, Object> old = at(BUYING, 50, 10000, 1);
        tracker.clear();
        assertNull(tracker.observe(0, BUYING, new long[]{560, 80, 100, 300, 16000}, true, 2, 100002L));
        Map<String, Object> end = at(BOUGHT, 100, 20000, 3);
        assertNotEquals(old.get("offer_key"), end.get("offer_key"));
        assertEquals(20L, meta(end).get("delta_qty"));
        GeObservationTracker other = new GeObservationTracker();
        Map<String, Object> otherEnd = other.observe(0, BOUGHT, new long[]{560, 100, 100, 300, 20000}, false, 4, 100004L);
        assertEquals(0L, meta(otherEnd).get("delta_qty"));
        assertNotEquals(end.get("offer_key"), otherEnd.get("offer_key"));
    }

    @Test
    public void changedSignatureRotatesEvenWithoutQuantityIncrease()
    {
        Map<String, Object> first = at(BUYING, 40, 8000, 1);
        Map<String, Object> replaced = tracker.observe(0, SELLING, new long[]{560, 10, 100, 301, 3000}, false, 2, 100002L);
        assertNotEquals(first.get("offer_key"), replaced.get("offer_key"));
        assertEquals(0L, meta(replaced).get("delta_qty"));
        assertEquals("sell", replaced.get("side"));
    }

    @Test
    public void longGpAndClockRollbackAreHandledWithoutInventedBounds()
    {
        at(SELLING, 0, 0, 100);
        Map<?, ?> m = meta(at(SOLD, 100, 5000000000L, 90));
        assertEquals(5000000000L, m.get("delta_gp"));
        assertNull(m.get("interval_start_ms"));
        assertNull(m.get("interval_end_ms"));
        assertNull(tracker.observe(8, BUYING, new long[]{560, 0, 100, 300, 0}, false, 1, 1));
        assertNull(tracker.observe(-1, BUYING, new long[]{560, 0, 100, 300, 0}, false, 1, 1));
    }
    @Test
    public void instantFillAfterObservedEmptyHasZeroBaselineAndObservationBounds()
    {
        at(EMPTY, 0, 0, 1);
        Map<?, ?> instant = meta(at(BOUGHT, 100, 21000, 2));
        assertEquals(100L, instant.get("delta_qty"));
        assertEquals(21000L, instant.get("delta_gp"));
        assertEquals(100001L, instant.get("interval_start_ms"));
        assertEquals(100002L, instant.get("interval_end_ms"));
        assertEquals("observed_counter_difference", instant.get("delta_status"));
        assertEquals(0L, meta(at(EMPTY, 0, 0, 3)).get("delta_qty"));
    }

    @Test
    public void aQuietPartialFillIsSentOnceItsWindowPassesWithNoFurtherCallback()
    {
        // Released 0.7.67: BUYING 0 at tick 1, a partial 10 at tick 2 (throttled), then no more callbacks.
        at(BUYING, 0, 0, 1);
        assertNull(at(BUYING, 10, 2400, 2));
        assertTrue("still inside the minute", tracker.due(50).isEmpty());
        java.util.List<Map.Entry<Integer, Map<String, Object>>> due = tracker.due(101);
        assertEquals(1, due.size());
        Map<String, Object> row = due.get(0).getValue();
        assertEquals("ge_progress", row.get("type"));
        assertEquals(10L, meta(row).get("delta_qty"));
        assertEquals(2400L, meta(row).get("delta_gp"));
        assertEquals("dated by the counter observation, not by now", 100002L, meta(row).get("observed_at_ms"));
        assertTrue("sent once", tracker.due(500).isEmpty());
        // The collect afterwards credits nothing twice.
        assertEquals(0L, meta(at(EMPTY, 0, 0, 600)).get("delta_qty"));
    }

    @Test
    public void aPendingPartialFillCarriedAcrossARestartIsSentWithoutAnotherCallback()
    {
        at(BUYING, 0, 0, 1);
        assertNull(at(BUYING, 10, 2400, 2));
        java.util.List<Map<String, Object>> saved = tracker.export();
        GeObservationTracker restarted = new GeObservationTracker();
        restarted.restore(saved);
        // The unchanged login replay of the same offer emits nothing by itself.
        assertNull(restarted.observe(0, BUYING, new long[]{560, 10, 100, 300, 2400}, true, 5, 200000L));
        java.util.List<Map.Entry<Integer, Map<String, Object>>> due = restarted.due(6);
        assertEquals(1, due.size());
        Map<?, ?> o = meta(due.get(0).getValue());
        assertEquals(10L, o.get("delta_qty"));
        assertEquals("inferred_across_gap", o.get("identity"));
        assertTrue(restarted.due(500).isEmpty());
    }

    @Test
    public void twoSeparateFillsInOneThrottleWindowKeepTheirOwnPricesAndTimes()
    {
        // Reported 11-08 on released code: 200 GP and 300 GP one-item fills merged into one 250 average, and a later
        // identical callback replaced their original observation time.
        GeObservationTracker t = new GeObservationTracker();
        t.observe(0, EMPTY, new long[]{0, 0, 0, 0, 0}, false, 0, 100000L);
        t.observe(0, BUYING, new long[]{560, 0, 10, 300, 0}, false, 1, 100600L);
        assertNull(t.observe(0, BUYING, new long[]{560, 1, 10, 300, 200}, false, 2, 101200L));
        assertNull(t.observe(0, BUYING, new long[]{560, 2, 10, 300, 500}, false, 3, 101800L));
        Map<String, Object> row = t.observe(0, BUYING, new long[]{560, 2, 10, 300, 500}, false, 101, 160600L);
        Map<?, ?> o = meta(row);
        assertEquals(2L, o.get("delta_qty"));
        assertEquals(500L, o.get("delta_gp"));
        assertEquals("the identical repeat keeps the time the counters changed", 101800L, o.get("counter_observed_at_ms"));
        assertEquals(101800L, o.get("interval_end_ms"));
        java.util.List<?> steps = (java.util.List<?>) o.get("steps");
        assertEquals(2, steps.size());
        Map<?, ?> a = (Map<?, ?>) steps.get(0), b = (Map<?, ?>) steps.get(1);
        assertEquals(101200L, a.get("at_ms")); assertEquals(1L, a.get("cumulative_qty")); assertEquals(200L, a.get("cumulative_gp"));
        assertEquals(101800L, b.get("at_ms")); assertEquals(2L, b.get("cumulative_qty")); assertEquals(500L, b.get("cumulative_gp"));
        assertEquals("client_callback_receipt", o.get("step_time"));
        assertEquals(0, o.get("steps_dropped"));
    }

    @Test
    public void aTaxChangeIsAStepWithClientAndServerValuesAndASingleFillCarriesNoSteps()
    {
        GeObservationTracker t = new GeObservationTracker();
        t.observe(1, EMPTY, new long[]{0, 0, 0, 0, 0}, false, 0, 1L);
        t.observe(1, SELLING, new long[]{4151, 0, 1, 1_500_000, 0}, false, 1, 2L);
        t.taxChanged(1, 30_000L, 30_000L, 5, 5L);
        Map<?, ?> o = meta(t.observe(1, SOLD, new long[]{4151, 1, 1, 1_500_000, 1_500_000}, false, 6, 6L));
        java.util.List<?> steps = (java.util.List<?>) o.get("steps");
        assertEquals("the tax update came before the offer update", "tax", ((Map<?, ?>) steps.get(0)).get("kind"));
        assertEquals(30_000L, ((Map<?, ?>) steps.get(0)).get("native_tax_gp"));
        assertEquals(30_000L, ((Map<?, ?>) steps.get(0)).get("native_tax_server_gp"));
        assertEquals("counter", ((Map<?, ?>) steps.get(1)).get("kind"));
        // One counter change and no tax step: no steps list (the row's own fields say it all).
        GeObservationTracker u = new GeObservationTracker();
        u.observe(2, EMPTY, new long[]{0, 0, 0, 0, 0}, false, 0, 1L);
        u.observe(2, BUYING, new long[]{560, 0, 10, 300, 0}, false, 1, 2L);
        assertNull(meta(u.observe(2, BOUGHT, new long[]{560, 10, 10, 300, 3000}, false, 2, 3L)).get("steps"));
    }

    @Test
    public void stepsAreBoundedAndTheOverflowIsCounted()
    {
        GeObservationTracker t = new GeObservationTracker();
        t.observe(3, EMPTY, new long[]{0, 0, 0, 0, 0}, false, 0, 1L);
        t.observe(3, BUYING, new long[]{560, 0, 100, 300, 0}, false, 1, 2L);
        for (int i = 1; i <= GeObservationTracker.MAX_STEPS + 4; i++)
            t.observe(3, BUYING, new long[]{560, i, 100, 300, 300L * i}, false, 1 + i, 2L + i);
        Map<?, ?> o = meta(t.due(500).get(0).getValue());
        assertEquals(GeObservationTracker.MAX_STEPS, ((java.util.List<?>) o.get("steps")).size());
        assertEquals(4, o.get("steps_dropped"));
        assertEquals("the totals still cover every fill", (long) (GeObservationTracker.MAX_STEPS + 4), o.get("delta_qty"));
    }

    @Test
    public void aClockRollbackNeverGivesReversedIntervalBounds()
    {
        // Independent audit 11-08: placement at 1000 ms, throttled partial at 900 ms (clock stepped back), collect at 1100.
        GeObservationTracker t = new GeObservationTracker();
        t.observe(2, EMPTY, new long[]{0, 0, 0, 0, 0}, false, 1, 990L);
        t.observe(2, BUYING, new long[]{560, 0, 100, 300, 0}, false, 2, 1000L);
        assertNull(t.observe(2, BUYING, new long[]{560, 20, 100, 300, 4000}, false, 3, 900L));
        Map<?, ?> o = meta(t.observe(2, EMPTY, new long[]{0, 0, 0, 0, 0}, false, 4, 1100L));
        assertEquals("the counter difference is kept", 20L, o.get("delta_qty"));
        assertEquals(4000L, o.get("delta_gp"));
        assertNull(o.get("interval_start_ms"));
        assertNull(o.get("interval_end_ms"));
        // Normal clock control: [1000, 1050].
        GeObservationTracker c = new GeObservationTracker();
        c.observe(2, EMPTY, new long[]{0, 0, 0, 0, 0}, false, 1, 990L);
        c.observe(2, BUYING, new long[]{560, 0, 100, 300, 0}, false, 2, 1000L);
        c.observe(2, BUYING, new long[]{560, 20, 100, 300, 4000}, false, 3, 1050L);
        Map<?, ?> n = meta(c.observe(2, EMPTY, new long[]{0, 0, 0, 0, 0}, false, 4, 1100L));
        assertEquals(1000L, n.get("interval_start_ms"));
        assertEquals(1050L, n.get("interval_end_ms"));
    }

    @Test
    public void uncertainCountersAndTerminalSlotsAreNeverFlushed()
    {
        at(BUYING, 10, 2400, 1);
        assertNull(at(BUYING, 5, 1200, 2));	// regression: quarantined
        assertTrue(tracker.due(500).isEmpty());
        GeObservationTracker t = new GeObservationTracker();
        t.observe(1, BUYING, new long[]{560, 0, 100, 300, 0}, false, 1, 1L);
        t.observe(1, BOUGHT, new long[]{560, 100, 100, 300, 30000}, false, 2, 2L);	// terminal row sent at once
        assertTrue(t.due(500).isEmpty());
    }
}
