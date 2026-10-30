package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DropEvidenceLinkageTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private static AccountConnectPlugin plugin(ItemContainer inventory) throws Exception
    {
        AccountConnectPlugin p = new AccountConnectPlugin();
        inject(p, "config", new AccountConnectConfig()
        {
            @Override public String linkToken() { return TOKEN; }
        });
        Client c = mock(Client.class);
        Player me = mock(Player.class);
        when(c.getAccountHash()).thenReturn(12345L);
        when(c.getTickCount()).thenReturn(100);
        when(c.getLocalPlayer()).thenReturn(me);
        when(me.getWorldLocation()).thenReturn(new WorldPoint(3200, 3400, 0));
        when(c.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventory);
        inject(p, "client", c);
        inject(p, "activeHash", "12345");
        p.staffApiBase = AccountConnectPlugin.OFFICIAL_API_BASE;
        p.setStaffCaptureForTest(TOKEN, "12345");
        p.setStoreToolsForTest(true);
        p.setDropProofRolloutForTest(true);
        assertTrue("fixture has a staff grant", p.staffCaptureEnabled());
        assertTrue("fixture has the media grant", p.dropProofEnabled());
        return p;
    }

    private static void inject(Object o, String name, Object value) throws Exception
    {
        Field f = AccountConnectPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static Object field(AccountConnectPlugin p, String name) throws Exception
    {
        Field f = AccountConnectPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(p);
    }

    private static ItemContainer inventory(int... ids)
    {
        ItemContainer inv = mock(ItemContainer.class);
        Item[] items = new Item[ids.length];
        for (int i = 0; i < ids.length; i++) items[i] = new Item(ids[i], 1);
        when(inv.getItems()).thenReturn(items);
        return inv;
    }

    private static void click(AccountConnectPlugin p, int item)
    {
        MenuOptionClicked event = mock(MenuOptionClicked.class);
        when(event.getMenuOption()).thenReturn("Drop");
        when(event.getMenuTarget()).thenReturn("");
        when(event.getItemId()).thenReturn(item);
        p.onMenuOptionClicked(event);
        assertTrue("fixture has a granted active drop session", p.dropSession.active());
    }

    private static void spawn(AccountConnectPlugin p, int item)
    {
        Tile tile = mock(Tile.class);
        TileItem ground = mock(TileItem.class);
        when(tile.getWorldLocation()).thenReturn(new WorldPoint(3200, 3400, 0));
        when(ground.getId()).thenReturn(item);
        when(ground.getQuantity()).thenReturn(1);
        when(ground.getOwnership()).thenReturn(TileItem.OWNERSHIP_SELF);
        when(ground.getDespawnTime()).thenReturn(400);
        p.onItemSpawned(new ItemSpawned(tile, ground));
    }

    private static List<Map<String, Object>> drops(AccountConnectPlugin p)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : p.pendingEvents)
            if ("drop".equals(row.get("type"))) rows.add(row);
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static List<AccountConnectPlugin.DroppedGroundItem> piles(AccountConnectPlugin p) throws Exception
    {
        return new ArrayList<>((Deque<AccountConnectPlugin.DroppedGroundItem>) field(p, "groundDrops"));
    }

    private static void assertCompletedWithoutExpiredClicks(AccountConnectPlugin p) throws Exception
    {
        assertEquals("every observed drop resolves its own pending click", 0, p.dropSession.pendingDropCount());
        p.dropSession.settle(System.currentTimeMillis() + DropSessionRecorder.PENDING_DROP_EXPIRY_MILLIS + 1);
        assertFalse("observed drops must not expire under a later click's identity", p.dropSession.unprovable());
        for (AccountConnectPlugin.DroppedGroundItem g : piles(p)) p.emitGroundRemoval(g, 400, false);
        assertEquals(0, p.dropSession.activePileCount());
        assertEquals(DropSessionRecorder.Outcome.COMPLETE, p.dropSession.finish());
    }

    @Test
    public void rapidMixedItemsKeepClickIdentityWhenSpawnsArriveReversed() throws Exception
    {
        AccountConnectPlugin p = plugin(inventory(1511, 1513, 1521));
        click(p, 1511); click(p, 1513); click(p, 1521);
        String session = p.dropSession.sessionId();
        ItemContainer empty = inventory();
        when(((Client) field(p, "client")).getItemContainer(InventoryID.INVENTORY)).thenReturn(empty);
        spawn(p, 1521); spawn(p, 1513); spawn(p, 1511);
        List<Map<String, Object>> rows = drops(p);
        assertEquals(3, rows.size());
        for (int i = 0; i < rows.size(); i++)
        {
            assertEquals(session, rows.get(i).get("drop_session_id"));
            assertEquals(3 - i, rows.get(i).get("drop_seq"));
            assertEquals(3 - i, p.dropSeqForPile(piles(p).get(i)));
        }
        assertCompletedWithoutExpiredClicks(p);
    }

    @Test
    public void repeatedItemSpawnsBeforeOneInventoryUpdateStaySeparate() throws Exception
    {
        AccountConnectPlugin p = plugin(inventory(229, 229, 229));
        click(p, 229); click(p, 229); click(p, 229);
        spawn(p, 229); spawn(p, 229); spawn(p, 229);
        assertTrue(drops(p).isEmpty());
        p.resolveInvDeltaPendings(inventory(), 0, 100);
        List<Map<String, Object>> rows = drops(p);
        assertEquals("one event per actual pile", 3, rows.size());
        for (int i = 0; i < rows.size(); i++)
        {
            assertEquals(i + 1, rows.get(i).get("drop_seq"));
            assertEquals("the same inventory loss cannot be counted repeatedly", 1L, rows.get(i).get("qty"));
            assertEquals(i + 1, p.dropSeqForPile(piles(p).get(i)));
        }
        assertCompletedWithoutExpiredClicks(p);
    }

    @Test
    public void repeatedItemInventoryLossBeforeSpawnsIsConsumedOnce() throws Exception
    {
        AccountConnectPlugin p = plugin(inventory(229, 229, 229));
        click(p, 229); click(p, 229); click(p, 229);
        ItemContainer empty = inventory();
        when(((Client) field(p, "client")).getItemContainer(InventoryID.INVENTORY)).thenReturn(empty);
        p.resolveInvDeltaPendings(inventory(), 0, 100);
        spawn(p, 229); spawn(p, 229); spawn(p, 229);
        List<Map<String, Object>> rows = drops(p);
        assertEquals(3, rows.size());
        for (int i = 0; i < rows.size(); i++)
        {
            assertEquals(i + 1, rows.get(i).get("drop_seq"));
            assertEquals(1L, rows.get(i).get("qty"));
        }
        assertCompletedWithoutExpiredClicks(p);
    }
    @Test
    public void anOlderUnconfirmedSpawnCannotStealTheNextClicksPile() throws Exception
    {
        AccountConnectPlugin p = plugin(inventory(229, 229));
        click(p, 229); click(p, 229);
        spawn(p, 229);
        when(((Client) field(p, "client")).getTickCount()).thenReturn(104);
        spawn(p, 229);
        List<AccountConnectPlugin.DroppedGroundItem> ground = piles(p);
        assertEquals(2, ground.size());
        assertEquals(1, p.dropSeqForPile(ground.get(0)));
        assertEquals(2, p.dropSeqForPile(ground.get(1)));
        assertEquals(0, p.dropSession.pendingDropCount());
    }

}
