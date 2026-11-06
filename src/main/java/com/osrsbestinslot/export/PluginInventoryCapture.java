package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/** Local plugin identifiers and enabled/active state only. Never reads another plugin's configuration. */
final class PluginInventoryCapture
{
    static final int MAX_PLUGINS = 256;

    static Map<String, Object> read(PluginManager manager, long now)
    {
        if (manager == null) return null;
        List<Plugin> plugins = new ArrayList<>(manager.getPlugins());
        plugins.sort(Comparator.comparing(p -> p.getClass().getName()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Plugin plugin : plugins)
        {
            if (rows.size() == MAX_PLUGINS) break;
            Map<String, Object> row = Fields.of(
                "id", plugin.getClass().getName(), "enabled", manager.isPluginEnabled(plugin),
                "active", manager.isPluginActive(plugin));
            rows.add(row);
        }
        Map<String, Object> out = Fields.of(
            "schema_v", 1, "observed_at_ms", now, "plugins", rows, "truncated", plugins.size() > MAX_PLUGINS);
        return out;
    }
}
