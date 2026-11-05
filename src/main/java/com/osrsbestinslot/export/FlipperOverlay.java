package com.osrsbestinslot.export;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

final class FlipperOverlay extends OverlayPanel
{
    // Four native text rows plus borders render at84px; reserve a small placement margin.
    static final int HEIGHT = 90;
    static final int WIDTH = 290;
    private static final Color GOLD = new Color(213, 181, 104);
    private final FlipperController controller;
    private Point automaticLocation;

    FlipperOverlay(Plugin plugin, FlipperController controller)
    {
        super(plugin);
        this.controller = controller;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setMovable(true);
        panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
        panelComponent.setBackgroundColor(new Color(26, 28, 27, 240));
        panelComponent.setBorder(new java.awt.Rectangle(7, 5, 7, 5));
        panelComponent.setGap(new Point(0, 2));
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        FlipperView view = controller.view();
        if (!controller.showOverlay() || view.result == null || view.anchor == null)
        {
            return null;
        }
        // Follow the GE window until the player chooses a position with RuneLite's overlay drag.
        if (getPreferredLocation() == null || getPreferredLocation().equals(automaticLocation))
        {
            automaticLocation = new Point(view.anchor);
            setPreferredLocation(automaticLocation);
        }
        panelComponent.getChildren().add(LineComponent.builder()
            .left("OSRS Best in Slot").leftColor(GOLD).right("Flipper").rightColor(GOLD).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Profit if sold")
            .right(FlipperView.compactProfit(view.result.profit))
            .rightColor(view.result.profit != null && view.result.profit.signum() < 0
                ? new Color(236, 123, 117) : GOLD).build());
        panelComponent.getChildren().add(LineComponent.builder().left("Tax / item")
            .right(FlipperView.number(view.result.taxPerItem) + " GP").build());
        panelComponent.getChildren().add(LineComponent.builder().left("Break-even / item")
            .right(view.result.breakEven == null ? (view.cost == null ? "Cost needed" : "Above offer limit")
                : FlipperView.compactGp(view.result.breakEven)).build());
        return super.render(graphics);
    }
}
