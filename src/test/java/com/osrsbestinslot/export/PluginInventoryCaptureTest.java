package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PluginInventoryCaptureTest
{
    static class ExamplePlugin extends Plugin {}

    @Test public void readsOnlyIdentifiersAndIndependentEnabledActiveStates()
    {
        PluginManager manager = mock(PluginManager.class);
        Plugin plugin = new ExamplePlugin();
        when(manager.getPlugins()).thenReturn(List.of(plugin));
        when(manager.isPluginEnabled(plugin)).thenReturn(true);
        when(manager.isPluginActive(plugin)).thenReturn(false);
        Map<String, Object> snapshot = PluginInventoryCapture.read(manager, 123L);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) snapshot.get("plugins")).get(0);
        assertEquals(Set.of("id", "enabled", "active"), row.keySet());
        assertEquals(ExamplePlugin.class.getName(), row.get("id"));
        assertEquals(true, row.get("enabled")); assertEquals(false, row.get("active"));
        assertEquals(123L, snapshot.get("observed_at_ms"));
        verify(manager).getPlugins(); verify(manager).isPluginEnabled(plugin); verify(manager).isPluginActive(plugin);
        verifyNoMoreInteractions(manager);
        assertNull(PluginInventoryCapture.read(null, 123L));
    }
}
