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

}
