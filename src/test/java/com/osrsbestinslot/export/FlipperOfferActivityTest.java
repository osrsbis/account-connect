package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FlipperOfferActivityTest
{
    private GrandExchangeOffer offer(GrandExchangeOfferState state,int total,int filled)
    { return FlipperBuyHistoryTest.offer(state,100,total,filled,200,filled*200L); }
    @Test public void existingOfferHasUnknownTimingUntilObservedChange()
    {
        FlipperOfferActivity tracker=new FlipperOfferActivity();GrandExchangeOffer old=offer(GrandExchangeOfferState.BUYING,100,20);
        tracker.seed(new GrandExchangeOffer[]{old});assertNull(tracker.snapshot().get(0).elapsed(1000));
        assertEquals("Watching just now",tracker.snapshot().get(0).timing(1000));
        assertEquals("Watching 2m",tracker.snapshot().get(0).timing(120000));
        tracker.observe(0,old,2000);assertNull(tracker.snapshot().get(0).elapsed(2000));
        tracker.observe(0,offer(GrandExchangeOfferState.BUYING,100,21),3000);
        assertEquals(Long.valueOf(60000),tracker.snapshot().get(0).elapsed(63000));
        assertEquals(Long.valueOf(63000),tracker.snapshot().get(0).watched(63000));
    }
    @Test public void sameNativeObjectMutationIsObservedAndDuplicateDoesNotReset()
    {
        FlipperOfferActivity tracker=new FlipperOfferActivity();GrandExchangeOffer nativeOffer=offer(GrandExchangeOfferState.SELLING,100,20);
        tracker.seed(new GrandExchangeOffer[]{nativeOffer});when(nativeOffer.getQuantitySold()).thenReturn(25);
        tracker.observe(0,nativeOffer,1000);assertEquals(25,tracker.snapshot().get(0).filled);
        tracker.observe(0,nativeOffer,2000);assertEquals(Long.valueOf(1000),tracker.snapshot().get(0).elapsed(2000));
    }
    @Test public void newReplacementAndCollectDoNotInheritTiming()
    {
        FlipperOfferActivity tracker=new FlipperOfferActivity();GrandExchangeOffer empty=offer(GrandExchangeOfferState.EMPTY,0,0);
        tracker.seed(new GrandExchangeOffer[]{empty});tracker.observe(0,offer(GrandExchangeOfferState.SELLING,100,0),1000);
        assertEquals("Updated just now",tracker.snapshot().get(0).timing(1000));
        tracker.observe(0,offer(GrandExchangeOfferState.SOLD,100,100),2000);assertTrue(tracker.snapshot().get(0).complete());
        tracker.observe(0,empty,3000);assertTrue(tracker.snapshot().isEmpty());
        tracker.observe(0,offer(GrandExchangeOfferState.BUYING,10,0),4000);assertEquals(10,tracker.snapshot().get(0).total);
        assertEquals(Long.valueOf(0),tracker.snapshot().get(0).elapsed(4000));
        tracker.clear();tracker.observe(0,offer(GrandExchangeOfferState.BUYING,10,0),5000);assertNull(tracker.snapshot().get(0).elapsed(5000));
    }
    @Test public void nativeCancelledStateIsNotCompletedAndClockRollbackIsUnknown()
    {
        FlipperOfferActivity tracker=new FlipperOfferActivity();tracker.seed(new GrandExchangeOffer[]{offer(GrandExchangeOfferState.EMPTY,0,0)});
        tracker.observe(0,offer(GrandExchangeOfferState.CANCELLED_BUY,100,20),1000);
        FlipperOfferActivity.Slot slot=tracker.snapshot().get(0);assertEquals("Cancelled",slot.status());assertEquals(80,slot.remaining());
        assertFalse(slot.complete());assertNull(slot.elapsed(999));assertEquals("Timing unknown",slot.timing(999));
        assertTrue(FlipperOverviewOverlay.tooltip(slot,2000).contains("Filled 20 / 100"));
        assertFalse(FlipperOverviewOverlay.tooltip(slot,2000).contains("profit"));
    }
    @Test public void invalidUnknownAndRegressedSlotsCannotKeepStaleDecoration()
    {
        FlipperOfferActivity tracker=new FlipperOfferActivity();tracker.seed(new GrandExchangeOffer[]{offer(GrandExchangeOfferState.BUYING,100,50)});
        tracker.observe(0,offer(GrandExchangeOfferState.BUYING,100,10),1000);
        assertEquals(10,tracker.snapshot().get(0).filled);assertEquals(Long.valueOf(0),tracker.snapshot().get(0).elapsed(1000));
        tracker.observe(0,offer(GrandExchangeOfferState.BUYING,100,101),2000);assertTrue(tracker.snapshot().isEmpty());
        tracker.observe(0,offer(GrandExchangeOfferState.BUYING,100,10),3000);assertNull(tracker.snapshot().get(0).elapsed(3000));
        tracker.observe(0,null,4000);assertTrue(tracker.snapshot().isEmpty());
    }
}
