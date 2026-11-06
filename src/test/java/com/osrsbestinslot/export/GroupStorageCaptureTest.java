package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.runelite.api.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GroupStorageCaptureTest
{
    private static void put(AccountConnectPlugin p, String name, Object value) throws Exception
    { Field f = AccountConnectPlugin.class.getDeclaredField(name); f.setAccessible(true); f.set(p, value); }

    @Test public void onlyGrantedGimAndFreshVisibleContainerProduceSharedContents() throws Exception
    {
        AccountConnectPlugin p = new AccountConnectPlugin();
        AccountConnectConfig config = mock(AccountConnectConfig.class);
        String token = "a".repeat(32); when(config.linkToken()).thenReturn(token); put(p, "config", config);
        Client client = mock(Client.class); put(p, "client", client);
        when(client.getVarbitValue(SnapshotBuilder.ACCOUNT_TYPE_VARBIT)).thenReturn(4);
        ItemContainer container = mock(ItemContainer.class); when(container.getId()).thenReturn(777);
        when(container.getItems()).thenReturn(new Item[]{new Item(995, 10)});
        when(client.getItemContainer(InventoryID.GROUP_STORAGE)).thenReturn(container);
        assertNull(p.snapshots.groupStorageBlock());
        Method grant = ServerGrants.class.getDeclaredMethod("setBroadCaptureGrant", String.class, boolean.class);
        grant.setAccessible(true); grant.invoke(p.grants, token, true);
        assertNull("cached container without this-session observation is unknown", p.snapshots.groupStorageBlock());
        Field times = SnapshotBuilder.class.getDeclaredField("containerObservedAtMs"); times.setAccessible(true);
        ((Map<Integer, Long>) times.get(p.snapshots)).put(777, 123000L);
        Map<String, Object> data = p.snapshots.groupStorageBlock();
        assertNotNull(data); assertEquals("shared_visible_contents", data.get("ownership"));
        assertFalse(data.containsKey("value_gp"));
        when(client.getVarbitValue(SnapshotBuilder.ACCOUNT_TYPE_VARBIT)).thenReturn(0);
        assertNull("ordinary accounts cannot read a stale GIM container", p.snapshots.groupStorageBlock());
        when(client.getVarbitValue(SnapshotBuilder.ACCOUNT_TYPE_VARBIT)).thenReturn(4);
        grant.invoke(p.grants, token, false); assertNull(p.snapshots.groupStorageBlock());
    }
}
