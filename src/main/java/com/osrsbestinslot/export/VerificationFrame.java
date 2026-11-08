package com.osrsbestinslot.export;

import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.widgets.Widget;

/** Numeric screenshot evidence. Pixels outside the selected widget interiors are never retained. */
final class VerificationFrame
{
    static final class Region
    {
        final String key;
        final int widget;
        final Rectangle bounds;
        final Object value;
        final Map<String, Object> inputs;
        final String text;
        Widget sourceWidget;
        int sourceItemQuantity;

        Region(String key, int widget, Rectangle bounds, int value)
        {
            this(key, widget, bounds, value, null, null);
        }

        Region(String key, int widget, Rectangle bounds, Object value, Map<String, Object> inputs, String text)
        {
            this.key = key;
            this.widget = widget;
            this.bounds = new Rectangle(bounds);
            this.value = value;
            this.inputs = inputs;
            this.text = text;
        }
    }

    /**
     * True when every selected region is a single colour in this image: nothing was drawn there yet (the rig's
     * sample right after login, 11-08, was all black). Such a frame holds no evidence and is not uploaded.
     */
    static boolean regionsBlank(BufferedImage image, List<Region> regions, int canvasWidth, int canvasHeight)
    {
        for (Region r : regions)
        {
            Rectangle p = pixels(r, image.getWidth(), image.getHeight(), canvasWidth, canvasHeight);
            if (p.isEmpty()) continue;
            int first = image.getRGB(p.x, p.y) & 0xFFFFFF;
            for (int y = p.y; y < p.y + p.height; y++)
                for (int x = p.x; x < p.x + p.width; x++)
                    if ((image.getRGB(x, y) & 0xFFFFFF) != first) return false;
        }
        return true;
    }

    static Rectangle pixels(Region region, int width, int height, int canvasWidth, int canvasHeight)
    {
        // Round inward: never include pixels outside the selected widget.
        Rectangle b = region.bounds;
        int x = (int) Math.ceil(b.x * (double) width / canvasWidth);
        int y = (int) Math.ceil(b.y * (double) height / canvasHeight);
        int right = (int) Math.floor((b.x + b.width) * (double) width / canvasWidth);
        int bottom = (int) Math.floor((b.y + b.height) * (double) height / canvasHeight);
        return new Rectangle(x, y, Math.max(0, right - x), Math.max(0, bottom - y))
            .intersection(new Rectangle(0, 0, width, height));
    }

    /**
     * The selected regions at the client's native resolution, read from the client's own pixel buffer: the game draws
     * its interface there before any stretched-mode or display (DPI) scaling, so text keeps its exact font pixels. Null
     * when the buffer does not match the canvas the widget bounds refer to; the caller then uses the drawn frame.
     */
    static BufferedImage nativeRegions(int[] buffer, int bufferWidth, int bufferHeight, List<Region> regions,
        int canvasWidth, int canvasHeight)
    {
        if (buffer == null || bufferWidth != canvasWidth || bufferHeight != canvasHeight
            || bufferWidth <= 0 || bufferHeight <= 0 || buffer.length < bufferWidth * bufferHeight) return null;
        BufferedImage out = new BufferedImage(bufferWidth, bufferHeight, BufferedImage.TYPE_INT_RGB);
        for (Region r : regions)
        {
            Rectangle p = pixels(r, bufferWidth, bufferHeight, canvasWidth, canvasHeight);
            for (int y = p.y; y < p.y + p.height; y++)
                for (int x = p.x; x < p.x + p.width; x++)
                    out.setRGB(x, y, buffer[y * bufferWidth + x] & 0xFFFFFF);
        }
        return out;
    }

    static BufferedImage retainRegions(BufferedImage source, List<Region> regions, int canvasWidth, int canvasHeight)
    {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try
        {
            for (Region r : regions)
            {
                Rectangle p = pixels(r, out.getWidth(), out.getHeight(), canvasWidth, canvasHeight);
                if (p.isEmpty()) continue;
                g.drawImage(source, p.x, p.y, p.x + p.width, p.y + p.height,
                    p.x, p.y, p.x + p.width, p.y + p.height, null);
            }
        }
        finally { g.dispose(); }
        return out;
    }

    static List<Map<String, Object>> results(List<Region> regions, int width, int height, int canvasWidth, int canvasHeight)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Region r : regions)
        {
            Rectangle p = pixels(r, width, height, canvasWidth, canvasHeight);
            if (p.isEmpty()) continue;
            Map<String, Object> inputs = VerificationProbe.inputs("widget", r.widget,
                "x", p.x, "y", p.y, "width", p.width, "height", p.height);
            if (r.inputs != null) inputs.putAll(r.inputs);
            out.add(VerificationProbe.result(r.key, inputs, r.value, null));
        }
        return out;
    }
}
