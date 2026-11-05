package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/** Session-local observations of the player's own GE slots, never inferred order age or profitability. */
final class FlipperOfferActivity
{
    static final class Slot
    {
        final int index, item, total, filled;
        final long price, spent;
        final GrandExchangeOfferState state;
        final Long changedAt;
        final long watchedAt;

        Slot(int index, GrandExchangeOffer offer, Long changedAt, long watchedAt)
        {
            this.index = index; item = offer.getItemId(); total = offer.getTotalQuantity();
            filled = offer.getQuantitySold(); price = offer.getPrice(); spent = offer.getSpent();
            state = offer.getState(); this.changedAt = changedAt; this.watchedAt = watchedAt;
        }
        boolean same(GrandExchangeOffer offer)
        {
            return item == offer.getItemId() && total == offer.getTotalQuantity() && filled == offer.getQuantitySold()
                && price == offer.getPrice() && spent == offer.getSpent() && state == offer.getState();
        }
        boolean buy()
        { return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.CANCELLED_BUY; }
        boolean complete() { return state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD; }
        boolean cancelled() { return state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL; }
        String status() { return complete() ? "Complete" : cancelled() ? "Cancelled" : buy() ? "Buy" : "Sell"; }
        int remaining() { return total - filled; }
        Long elapsed(long now) { return changedAt == null || now < changedAt ? null : now - changedAt; }
        Long watched(long now) { return now < watchedAt ? null : now - watchedAt; }
        String timing(long now)
        {
            Long elapsed = elapsed(now), watched = watched(now);
            return elapsed != null ? "Updated " + duration(elapsed)
                : watched != null ? "Watching " + duration(watched) : "Timing unknown";
        }
        boolean sameOffer(GrandExchangeOffer offer)
        {
            GrandExchangeOfferState next = offer.getState();
            boolean nextBuy = next == GrandExchangeOfferState.BUYING || next == GrandExchangeOfferState.BOUGHT
                || next == GrandExchangeOfferState.CANCELLED_BUY;
            boolean nextActive = next == GrandExchangeOfferState.BUYING || next == GrandExchangeOfferState.SELLING;
            return item == offer.getItemId() && total == offer.getTotalQuantity() && price == offer.getPrice()
                && buy() == nextBuy && filled <= offer.getQuantitySold() && spent <= offer.getSpent()
                && !((complete() || cancelled()) && nextActive);
        }
    }

    private final Slot[] slots = new Slot[8];
    private final boolean[] observed = new boolean[8];
    void clear() { Arrays.fill(slots, null); Arrays.fill(observed, false); }
    void seed(GrandExchangeOffer[] offers) { seed(offers, 0); }
    void seed(GrandExchangeOffer[] offers, long now)
    {
        clear();
        if (offers != null) for (int i = 0; i < Math.min(slots.length, offers.length); i++) observe(i, offers[i], now, true);
    }
    void observe(int index, GrandExchangeOffer offer, long now) { observe(index, offer, now, false); }
    private void observe(int index, GrandExchangeOffer offer, long now, boolean baseline)
    {
        if (index < 0 || index >= slots.length) return;
        if (offer == null || offer.getState() == null) { slots[index] = null; observed[index] = false; return; }
        if (offer.getState() == GrandExchangeOfferState.EMPTY) { slots[index] = null; observed[index] = true; return; }
        GrandExchangeOfferState state = offer.getState();
        if (state != GrandExchangeOfferState.BUYING && state != GrandExchangeOfferState.BOUGHT
            && state != GrandExchangeOfferState.SELLING && state != GrandExchangeOfferState.SOLD
            && state != GrandExchangeOfferState.CANCELLED_BUY && state != GrandExchangeOfferState.CANCELLED_SELL)
        { slots[index] = null; observed[index] = false; return; }
        if (offer.getItemId() <= 0 || offer.getTotalQuantity() <= 0 || offer.getQuantitySold() < 0
            || offer.getQuantitySold() > offer.getTotalQuantity() || offer.getPrice() <= 0 || offer.getSpent() < 0)
        { slots[index] = null; observed[index] = false; return; }
        Slot old = slots[index];
        if (old != null && old.same(offer)) return;
        Long changed = baseline || !observed[index] ? null : now;
        long watched = old != null && old.sameOffer(offer) ? old.watchedAt : now;
        slots[index] = new Slot(index, offer, changed, watched); observed[index] = true;
    }
    List<Slot> snapshot()
    {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : slots) if (slot != null) out.add(slot);
        return Collections.unmodifiableList(out);
    }
    static String duration(long elapsedMs)
    {
        long seconds = Math.max(0, elapsedMs) / 1000;
        if (seconds < 5) return "just now";
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + minutes % 60 + "m";
        return hours / 24 + "d " + hours % 24 + "h";
    }
}
