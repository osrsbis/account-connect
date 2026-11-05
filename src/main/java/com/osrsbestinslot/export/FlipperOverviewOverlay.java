package com.osrsbestinslot.export;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.VarClientInt;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/** Non-interactive overview rail. Native offer rectangles are used only for alignment and hover. */
final class FlipperOverviewOverlay extends Overlay
{
    static final int HEIGHT = 78;
    static final int[] SLOT_WIDGETS = {InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1,
        InterfaceID.GeOffers.INDEX_2, InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4,
        InterfaceID.GeOffers.INDEX_5, InterfaceID.GeOffers.INDEX_6, InterfaceID.GeOffers.INDEX_7};
    private static final Color GOLD = new Color(213, 181, 104);
    private static final Color MUTED = new Color(177, 177, 165);
    private static final Color COMPLETE = new Color(143, 205, 146);
    private static final Color CANCELLED = new Color(225, 168, 108);
    static final class View
    {
        final String owner;
        final Rectangle frame;
        final Map<Integer, Rectangle> slots;
        final List<FlipperOfferActivity.Slot> offers;
        final long now;
        View(String owner, Rectangle frame, Map<Integer, Rectangle> slots, List<FlipperOfferActivity.Slot> offers, long now)
        {
            this.owner = owner; this.frame = new Rectangle(frame); this.now = now;
            Map<Integer, Rectangle> copy = new LinkedHashMap<>();
            for (Map.Entry<Integer, Rectangle> entry : slots.entrySet()) copy.put(entry.getKey(), new Rectangle(entry.getValue()));
            this.slots = Collections.unmodifiableMap(copy);
            List<FlipperOfferActivity.Slot> visible = new ArrayList<>();
            for (FlipperOfferActivity.Slot offer : offers) if (copy.containsKey(offer.index)) visible.add(offer);
            this.offers = Collections.unmodifiableList(visible);
        }
    }
    private final Client client;
    private final TooltipManager tooltips;
    private final Supplier<View> view;
    private final BooleanSupplier enabled;

    FlipperOverviewOverlay(Plugin plugin, Client client, TooltipManager tooltips, Supplier<View> view, BooleanSupplier enabled)
    {
        super(plugin); this.client = client; this.tooltips = tooltips; this.view = view; this.enabled = enabled;
        setPosition(OverlayPosition.DYNAMIC); setLayer(OverlayLayer.ABOVE_WIDGETS);
        setMovable(false); setResizable(false);
    }

    @Override public Dimension render(Graphics2D graphics)
    {
        View current = view.get();
        if (!enabled.getAsBoolean() || current == null || client.getGameState() != GameState.LOGGED_IN
            || client.isMenuOpen() || client.getVarcIntValue(VarClientInt.INPUT_TYPE) != 0
            || client.getAccountHash() == -1L
            || !current.owner.equals(client.getAccountHash() + "|" + RuneScapeProfileType.getCurrent(client).name())
            || !nativeMatches(current)) return null;
        Map<Integer, Rectangle> chips = paint(graphics, current, client.getCanvasWidth(), client.getCanvasHeight());
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        if (mouse != null)
            for (FlipperOfferActivity.Slot slot : current.offers)
            {
                Rectangle nativeBounds = current.slots.get(slot.index), chip = chips.get(slot.index);
                if ((nativeBounds != null && nativeBounds.contains(mouse.getX(), mouse.getY()))
                    || (chip != null && chip.contains(mouse.getX(), mouse.getY())))
                { tooltips.add(new Tooltip(tooltip(slot, current.now))); break; }
            }
        return null; // Nothing intercepts clicks or advertises a draggable overlay rectangle.
    }

    private boolean nativeMatches(View current)
    {
        Widget index = client.getWidget(InterfaceID.GeOffers.INDEX), frame = client.getWidget(InterfaceID.GeOffers.FRAME);
        Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP), details = client.getWidget(InterfaceID.GeOffers.DETAILS);
        if (index == null || index.isHidden() || frame == null || frame.isHidden() || !current.frame.equals(frame.getBounds())
            || current.frame.width <= 0 || current.frame.height <= 0
            || (setup != null && !setup.isHidden()) || (details != null && !details.isHidden())) return false;
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) return false;
        for (Map.Entry<Integer, Rectangle> slot : current.slots.entrySet())
        {
            Widget widget = client.getWidget(SLOT_WIDGETS[slot.getKey()]);
            if (widget == null || widget.isHidden() || !slot.getValue().equals(widget.getBounds())) return false;
        }
        for (FlipperOfferActivity.Slot slot : current.offers)
            if (slot.index >= offers.length || offers[slot.index] == null || !slot.same(offers[slot.index])
                || !visibleItem(client.getWidget(SLOT_WIDGETS[slot.index]), slot.item, current.slots.get(slot.index),
                    Collections.newSetFromMap(new IdentityHashMap<>()), 0)) return false;
        return true;
    }

    /** Bound to the current native slot tree, without assuming a dynamic child index. */
    private static boolean visibleItem(Widget widget, int item, Rectangle slotBounds, Set<Widget> seen, int depth)
    {
        if (slotBounds == null || slotBounds.isEmpty() || widget == null || widget.isHidden() || depth > 4 || seen.size() >= 64 || !seen.add(widget)) return false;
        Rectangle bounds = widget.getBounds();
        if (widget.getItemId() == item && bounds != null && bounds.width > 0 && bounds.height > 0
            && slotBounds.contains(bounds)) return true;
        for (Widget[] children : new Widget[][]{widget.getChildren(), widget.getDynamicChildren(), widget.getStaticChildren()})
            if (children != null) for (Widget child : children)
                if (visibleItem(child, item, slotBounds, seen, depth + 1)) return true;
        return false;
    }

    static Map<Integer, Rectangle> paint(Graphics2D graphics, View current, int canvasWidth, int canvasHeight)
    {
        Map<Integer, Rectangle> chips = new LinkedHashMap<>();
        Rectangle frame = current.frame;
        int y = frame.y + frame.height + 4;
        if (current.offers.isEmpty() || frame.height <= 0 || frame.y < 0 || frame.width < 400 || frame.x < 0 || frame.x + frame.width > canvasWidth
            || y < 0 || y + HEIGHT > canvasHeight) return chips;
        List<Integer> rowPositions = new ArrayList<>();
        for (Rectangle bounds : current.slots.values()) if (!rowPositions.contains(bounds.y)) rowPositions.add(bounds.y);
        Collections.sort(rowPositions);
        if (rowPositions.isEmpty() || rowPositions.size() > 2) return chips;
        for (FlipperOfferActivity.Slot slot : current.offers)
        {
            Rectangle nativeBounds = current.slots.get(slot.index);
            if (nativeBounds == null || nativeBounds.width < 85 || !frame.contains(nativeBounds)) continue;
            int row = rowPositions.indexOf(nativeBounds.y);
            Rectangle chip = new Rectangle(nativeBounds.x, y + 18 + row * 28, nativeBounds.width, 25);
            if (chip.x >= frame.x && chip.x + chip.width <= frame.x + frame.width) chips.put(slot.index, chip);
        }
        if (chips.isEmpty()) return chips;
        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            g.setColor(new Color(25, 28, 27, 238));g.fillRect(frame.x, y, frame.width, HEIGHT);
            g.setFont(FontManager.getRunescapeSmallFont());g.setColor(GOLD);
            g.drawString("Flipper · locally observed activity", frame.x + 7, y + 12);
            for (FlipperOfferActivity.Slot slot : current.offers)
            {
                Rectangle chip = chips.get(slot.index);
                if (chip == null) continue;
                Color color = slot.complete() ? COMPLETE : slot.cancelled() ? CANCELLED : GOLD;
                g.setColor(new Color(255, 255, 255, 20));g.fillRect(chip.x, chip.y, chip.width, chip.height);
                g.setColor(color);g.fillRect(chip.x, chip.y, 2, chip.height);
                g.drawString((slot.index + 1) + " " + slot.status(), chip.x + 6, chip.y + 10);
                String line = slot.complete() ? "Ready to collect" : slot.cancelled()
                    ? FlipperView.number(slot.filled) + " / " + FlipperView.number(slot.total) + " filled" : slot.timing(current.now);
                if (g.getFontMetrics().stringWidth(line) > chip.width - 10) line = slot.cancelled() ? "Inspect remaining" : "Timing unknown";
                g.setColor(MUTED);g.drawString(line, chip.x + 6, chip.y + 21);chips.put(slot.index, chip);
            }
        }
        finally { g.dispose(); }
        return chips;
    }

    static String tooltip(FlipperOfferActivity.Slot slot, long now)
    {
        Long age = slot.elapsed(now), watched = slot.watched(now);
        return "Flipper · Slot " + (slot.index + 1) + " · " + (slot.buy() ? "Buy" : "Sell") + " · " + slot.status()
            + "<br>Filled " + FlipperView.number(slot.filled) + " / " + FlipperView.number(slot.total)
            + " · Remaining " + FlipperView.number(slot.remaining())
            + "<br>" + (watched == null ? "Watch duration unknown" : "Watching locally: " + FlipperOfferActivity.duration(watched))
            + "<br>" + (age == null ? "Prior start / last fill: unknown" : "Last observed change: " + FlipperOfferActivity.duration(age) + "; true start unknown");
    }
}
