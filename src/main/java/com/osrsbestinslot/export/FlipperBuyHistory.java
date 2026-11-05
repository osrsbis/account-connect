package com.osrsbestinslot.export;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/** Session-local purchase receipts. A recorded average is a calculation basis, not inventory accounting. */
final class FlipperBuyHistory
{
    private final Map<Integer, FlipperMath.Cost> purchases = new LinkedHashMap<>();
    private final Slot[] slots = new Slot[8];

    private static final class Slot
    {
        int item;
        int total;
        int filled;
        long price;
        long spent;
        GrandExchangeOfferState state;
        boolean fromStart;
        boolean recorded;
    }

    void clear()
    {
        purchases.clear();
        java.util.Arrays.fill(slots, null);
    }

    void seed(GrandExchangeOffer[] offers)
    {
        if (offers != null)
        {
            for (int i = 0; i < Math.min(offers.length, slots.length); i++)
            {
                observe(i, offers[i], true);
            }
        }
    }

    FlipperMath.Cost cost(int item, int quantity)
    {
        FlipperMath.Cost cost = purchases.get(item);
        return cost != null && quantity > 0 && quantity <= cost.quantity ? cost : null;
    }

    void observe(int index, GrandExchangeOffer offer, boolean baseline)
    {
        if (index < 0 || index >= slots.length || offer == null || offer.getState() == null)
        {
            return;
        }
        GrandExchangeOfferState state = offer.getState();
        Slot old = slots[index];
        if (state == GrandExchangeOfferState.EMPTY)
        {
            Slot empty = new Slot();
            empty.state = state;
            slots[index] = empty;
            return;
        }
        int item = offer.getItemId();
        int total = offer.getTotalQuantity();
        int filled = offer.getQuantitySold();
        long spent = offer.getSpent();
        if (item <= 0 || total <= 0 || filled < 0 || filled > total || spent < 0)
        {
            slots[index] = null;
            return;
        }
        boolean same = old != null && old.item == item && old.total == total
            && old.price == offer.getPrice() && buy(old.state) == buy(state)
            && !(terminal(old.state) && !terminal(state));
        Slot now = same ? old : new Slot();
        if (!same)
        {
            now.fromStart = !baseline && (filled == 0
                || (old != null && old.state == GrandExchangeOfferState.EMPTY));
        }
        else if (filled < old.filled || spent < old.spent)
        {
            now.fromStart = false;
        }
        now.item = item;
        now.total = total;
        now.price = offer.getPrice();
        now.filled = filled;
        now.spent = spent;
        now.state = state;
        slots[index] = now;
        if (!baseline && now.fromStart && !now.recorded && buy(state) && terminal(state) && filled > 0
            && spent > 0)
        {
            purchases.remove(item);
            purchases.put(item, new FlipperMath.Cost(BigInteger.valueOf(spent), filled));
            if (purchases.size() > 128)
            {
                purchases.remove(purchases.keySet().iterator().next());
            }
            now.recorded = true;
        }
    }

    private static boolean buy(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.CANCELLED_BUY;
    }

    private static boolean terminal(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.CANCELLED_BUY
            || state == GrandExchangeOfferState.SOLD || state == GrandExchangeOfferState.CANCELLED_SELL;
    }
}
