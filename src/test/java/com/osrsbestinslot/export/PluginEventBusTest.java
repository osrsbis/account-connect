package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.eventbus.EventBus;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise RuneLite registration, which direct handler calls do not validate. */
public class PluginEventBusTest
{
    @Test public void realRuneLiteEventBusRegistersAllSubscribersAndKeepsUnlinkedCaptureDormant()
    {
        List<Throwable> failures=new ArrayList<>();
        EventBus bus=new EventBus(failures::add);
        AccountConnectPlugin plugin=new AccountConnectPlugin();
        bus.register(plugin);
        bus.post(new ClientTick());
        bus.post(new GameObjectSpawned());
        bus.post(new GameObjectDespawned());
        assertTrue(failures.toString(),failures.isEmpty());
        assertNull(plugin.gameplayBlock());
        bus.unregister(plugin);
        bus.post(new ClientTick());
        assertTrue(failures.toString(),failures.isEmpty());
    }
}
