package com.osrsbestinslot.export;

import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        net.runelite.api.widgets.Widget sourceWidget;
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
