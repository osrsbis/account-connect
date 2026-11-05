package com.osrsbestinslot.export;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class FlipperRenderTest
{
    @Test
    public void nativePanelPaintsKnownUnknownLossAndLargeTotalsAtSidebarWidth() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
                FlipperPanel panel = new FlipperPanel(FlipperController.logo(), value -> {}, (key, value) -> {});
                FlipperView[] views = {
                    new FlipperView(true, 1, 12934, "Zulrah's scales", "Offer not placed.", 6_000, 0,
                        181, 20, "Recorded buy average", "", FlipperMath.Cost.perItem(170), new Point(8, 338)),
                    new FlipperView(true, 2, 12934, "Zulrah's scales", "Offer not placed.", 6_000, 0,
                        181, 20, "Purchase cost unknown", "", null, new Point(8, 338)),
                    new FlipperView(true, 3, 12934, "Zulrah's scales", "Offer not placed.", 6_000, 0,
                        160, 20, "Manual purchase cost", "170", FlipperMath.Cost.perItem(170), new Point(8, 338)),
                    new FlipperView(true, 4, 100, "High-value item", "Offer not placed.", 1_000, 0,
                        2_000_000_000L, 20, "Manual purchase cost", "0", FlipperMath.Cost.perItem(0),
                        new Point(8, 338)),
                    new FlipperView(true, 5, 100, "High-value item", "Offer not placed.", 1_000, 0,
                        2_000_000_000L, 20, "Manual purchase cost", Long.toString(Long.MAX_VALUE),
                        FlipperMath.Cost.perItem(Long.MAX_VALUE), new Point(8, 338)),
                    FlipperView.empty(false, 6, "Enable Flipper to calculate a sell offer."),
                    FlipperView.empty(true, 7, "Waiting for the Grand Exchange offer to update."),
                    new FlipperView(true, 8, 12934, "Zulrah's scales", "Finish entering the Grand Exchange value.",
                        6_000, 0, 181, -1, "Manual purchase cost", "170", FlipperMath.Cost.perItem(170), null),
                    new FlipperView(true, 9, 12934, "Zulrah's scales", "Estimate for the remaining items only.",
                        2_000, 4_000, 181, 20, "Manual purchase cost", "170", FlipperMath.Cost.perItem(170), new Point(8, 338))
                };
                String[] names = {"known", "unknown", "loss", "large-profit", "large-loss", "off", "waiting", "native-input", "partial-sell"};
                File output = new File("build/reports/flipper-render");
                assertTrue(output.isDirectory() || output.mkdirs());
                for (int i = 0; i < views.length; i++)
                {
                    panel.showView(views[i]);
                    panel.setSize(225, 650);
                    layout(panel);
                    BufferedImage image = new BufferedImage(225, 650, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = image.createGraphics();
                    panel.paint(graphics);
                    graphics.dispose();
                    ImageIO.write(image, "png", new File(output, names[i] + ".png"));
                    assertLabelsFit(panel);
                }
            }
            catch (Exception error)
            {
                throw new AssertionError(error);
            }
        });
    }

    @Test
    public void nativeOverlayRendersAndDisappearsDuringInputAndWhenDisabled() throws Exception
    {
        try (FlipperControllerTest.Fixture f = new FlipperControllerTest.Fixture())
        {
            f.cost("10000");
            net.runelite.client.ui.overlay.Overlay overlay = f.overlays.get(0);
            File output = new File("build/reports/flipper-render");
            assertTrue(output.isDirectory() || output.mkdirs());
            BufferedImage image = new BufferedImage(330, 100, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            // PanelComponent returns previous-frame dimensions; warm layout before checking footprint.
            overlay.render(graphics);
            java.awt.Dimension size = overlay.render(graphics);
            graphics.dispose();
            assertNotNull(size); assertTrue("actual overlay size: " + size, size.width <= 330 && size.height <= FlipperOverlay.HEIGHT);
            ImageIO.write(image, "png", new File(output, "overlay-known.png"));
            f.draft(100, 1, 1_000_000_000_000L, 20, "999,995,000,000 coins");
            f.tick();f.cost("999000000000");
            BufferedImage large = new BufferedImage(330, 100, BufferedImage.TYPE_INT_ARGB);
            Graphics2D largeGraphics = large.createGraphics();
            overlay.render(largeGraphics);
            java.awt.Dimension largeSize = overlay.render(largeGraphics);
            largeGraphics.dispose();
            ImageIO.write(large, "png", new File(output, "overlay-large-break-even.png"));
            assertNotNull(largeSize);assertTrue("large overlay size: " + largeSize, largeSize.width <= 330 && largeSize.height <= FlipperOverlay.HEIGHT);
            assertNotNull(f.controller.view().result.breakEven);
            org.mockito.Mockito.when(f.client.getVarcIntValue(net.runelite.api.VarClientInt.INPUT_TYPE)).thenReturn(7);
            f.tick();
            BufferedImage hidden = new BufferedImage(330, 100, BufferedImage.TYPE_INT_ARGB);
            Graphics2D hiddenGraphics = hidden.createGraphics();
            assertNull(overlay.render(hiddenGraphics));
            ImageIO.write(hidden, "png", new File(output, "overlay-native-input.png"));
            f.enabled.set(false);f.tick();assertNull(overlay.render(hiddenGraphics));
            hiddenGraphics.dispose();
            ImageIO.write(hidden, "png", new File(output, "overlay-off.png"));
        }
    }

    private static void layout(Container parent)
    {
        parent.invalidate();
        parent.doLayout();
        for (Component child : parent.getComponents())
        {
            if (child instanceof Container)
            {
                layout((Container) child);
            }
        }
    }

    private static void assertLabelsFit(Container parent)
    {
        for (Component child : parent.getComponents())
        {
            if (child instanceof JLabel && child.isVisible())
            {
                JLabel label = (JLabel) child;
                if (label.getText() != null && !label.getText().startsWith("<html>"))
                {
                    assertTrue("Clipped label: " + label.getText(),
                        label.getFontMetrics(label.getFont()).stringWidth(label.getText()) <= label.getWidth());
                }
            }
            if (child instanceof Container)
            {
                assertLabelsFit((Container) child);
            }
        }
    }
}
