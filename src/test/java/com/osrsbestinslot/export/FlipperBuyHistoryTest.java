package com.osrsbestinslot.export;

import java.math.BigDecimal;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FlipperBuyHistoryTest
{
    static GrandExchangeOffer offer(GrandExchangeOfferState state, int item, int total, int filled, long price,
        long spent)
    {
        GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
        when(offer.getState()).thenReturn(state);
        when(offer.getItemId()).thenReturn(item);
        when(offer.getTotalQuantity()).thenReturn(total);
        when(offer.getQuantitySold()).thenReturn(filled);
        when(offer.getPrice()).thenReturn(price);
        when(offer.getSpent()).thenReturn(spent);
        return offer;
    }

    @Test
    public void completedPurchaseUsesActualSpentNotListingPrice()
    {
        FlipperBuyHistory history = new FlipperBuyHistory();
        history.observe(0, offer(GrandExchangeOfferState.BUYING, 100, 100, 0, 200, 0), false);
        history.observe(0, offer(GrandExchangeOfferState.BOUGHT, 100, 100, 100, 200, 17_500), false);
        assertEquals(0, new BigDecimal("175").compareTo(history.cost(100, 100).average()));
        assertNull(history.cost(100, 101));
    }

    @Test
    public void loginReplayAndUnseenStartDoNotInventPurchaseHistory()
    {
        FlipperBuyHistory history = new FlipperBuyHistory();
        GrandExchangeOffer done = offer(GrandExchangeOfferState.BOUGHT, 100, 100, 100, 200, 17_500);
        history.seed(new GrandExchangeOffer[]{done});
        history.observe(0, done, false);
        assertNull(history.cost(100, 100));
        history.clear();
        history.observe(0, done, false);
        assertNull(history.cost(100, 100));
    }

    @Test
    public void observedEmptyAllowsAnInstantCompletedBuy()
    {
        FlipperBuyHistory history = new FlipperBuyHistory();
        history.seed(new GrandExchangeOffer[]{offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0)});
        history.observe(0, offer(GrandExchangeOfferState.BOUGHT, 100, 100, 100, 200, 17_500), false);
        assertNotNull(history.cost(100, 100));
        history.clear();
        assertNull(history.cost(100, 1));
    }

    @Test
    public void partialCancelledBuyOnlyCoversItsPurchasedQuantity()
    {
        FlipperBuyHistory history = new FlipperBuyHistory();
        history.observe(0, offer(GrandExchangeOfferState.BUYING, 100, 100, 0, 200, 0), false);
        history.observe(0, offer(GrandExchangeOfferState.CANCELLED_BUY, 100, 100, 30, 200, 5_100), false);
        assertEquals(0, new BigDecimal("170").compareTo(history.cost(100, 30).average()));
        assertNull(history.cost(100, 31));
    }

    @Test
    public void regressingCountersAreNotTrustedAndSellsDoNotSupplyPurchaseCost()
    {
        FlipperBuyHistory history = new FlipperBuyHistory();
        history.observe(0, offer(GrandExchangeOfferState.BUYING, 100, 100, 0, 200, 0), false);
        history.observe(0, offer(GrandExchangeOfferState.BUYING, 100, 100, 50, 200, 10_000), false);
        history.observe(0, offer(GrandExchangeOfferState.CANCELLED_BUY, 100, 100, 30, 200, 6_000), false);
        history.observe(1, offer(GrandExchangeOfferState.SOLD, 101, 100, 100, 200, 20_000), false);
        assertNull(history.cost(100, 1));
        assertNull(history.cost(101, 1));
    }
}
