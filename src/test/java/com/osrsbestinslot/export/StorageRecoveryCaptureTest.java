package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StorageRecoveryCaptureTest
{
    private Client client;
    private StorageRecoveryCapture capture;
    private int tick;

    @Before public void setup()
    {
        client = mock(Client.class);
        capture = new StorageRecoveryCapture();
        when(client.getAccountHash()).thenReturn(42L);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        when(client.getTickCount()).thenAnswer(i -> tick);
        when(client.getWidget(StorageRecoveryCapture.DEPOSIT_ITEMS)).thenReturn(mock(Widget.class));
    }

    private ItemContainer items(int... pairs)
    {
        ItemContainer c = mock(ItemContainer.class);
        Item[] items = new Item[pairs.length / 2];
        for (int i = 0; i < pairs.length; i += 2) items[i / 2] = new Item(pairs[i], pairs[i + 1]);
        when(c.getItems()).thenReturn(items);
        return c;
    }

    private void bank(int... pairs)
    {
        when(client.getWidget(StorageRecoveryCapture.BANK_WIDGET)).thenReturn(mock(Widget.class));
        capture.container(client, 95, items(pairs), 10 + tick);
    }

    private MenuOptionClicked click(int op, int item)
    {
        MenuOptionClicked event = mock(MenuOptionClicked.class);
        when(event.getMenuAction()).thenReturn(MenuAction.CC_OP);
        when(event.getParam1()).thenReturn(StorageRecoveryCapture.DEPOSIT_ITEMS);
        when(event.getId()).thenReturn(op);
        when(event.getItemId()).thenReturn(item);
        return event;
    }

    private List<Map<String, Object>> rows()
    {
        return (List<Map<String, Object>>) capture.snapshot(client).get("observations");
    }

    private Map<?, ?> block(String key) { return (Map<?, ?>) capture.snapshot(client).get(key); }

    @Test public void bankBaselineAndSignedDifferenceRetainGapAndIdentity()
    {
        bank(100, 10, 101, 5);
        assertEquals("baseline_only", rows().get(0).get("delta_status"));
        assertEquals("42", rows().get(0).get("account_hash"));
        assertEquals("STANDARD", rows().get(0).get("profile_type"));
        tick++;
        bank(100, 7, 102, 2);
        assertEquals(Map.of(100, -3L, 101, -5L, 102, 2L), rows().get(1).get("delta"));
        assertEquals(false, rows().get(1).get("across_gap"));
        capture.gap();
        bank(100, 8);
        assertEquals(true, rows().get(2).get("across_gap"));
        assertEquals("unknown", rows().get(2).get("transfer_attribution"));
    }

    @Test public void closedAndEmptyReadsNeverOverwriteBaseline()
    {
        bank(100, 10);
        when(client.getWidget(StorageRecoveryCapture.BANK_WIDGET)).thenReturn(null);
        capture.container(client, 95, items(100, 999), 20);
        assertEquals(Map.of(100, 10L), block("bank").get("items"));
        assertEquals(true, block("bank").get("uncertain"));
        bank();
        assertEquals(Map.of(100, 10L), block("bank").get("items"));
        assertEquals(1, rows().size());
    }

    @Test public void fixedDepositNeedsCallbackAndNeverMutatesBank()
    {
        bank(100, 10);
        when(client.getWidget(StorageRecoveryCapture.BANK_WIDGET)).thenReturn(null);
        doReturn(items(100, 8)).when(client).getItemContainer(93);
        capture.menu(client, click(3, 100), 20);
        assertEquals(1, rows().size());
        capture.container(client, 93, items(100, 3), 21);
        tick = 2;
        capture.poll(client, 22);
        assertEquals("inferred", rows().get(1).get("delta_status"));
        assertEquals(Map.of(100, -5L), rows().get(1).get("inventory_delta"));
        assertEquals("unknown", rows().get(1).get("bank_delta_status"));
        assertEquals(Map.of(100, 10L), block("bank").get("items"));
        capture.poll(client, 23);
        assertEquals(2, rows().size());
    }

    @Test public void missedCallbackAndClickOnlyStayUncertain()
    {
        doReturn(items(100, 8)).when(client).getItemContainer(93);
        capture.menu(client, click(3, 100), 20);
        tick = 2;
        capture.poll(client, 22);
        assertEquals("uncertain", rows().get(0).get("delta_status"));
        assertNull(rows().get(0).get("inventory_delta"));
        assertNull(block("bank").get("items"));
    }

    @Test public void multipleChangesOrDelayedCallbacksDoNotInfer()
    {
        doReturn(items(100, 8, 200, 1)).when(client).getItemContainer(93);
        capture.menu(client, click(3, 100), 20);
        capture.container(client, 93, items(100, 3), 21);
        tick = 2;
        capture.poll(client, 22);
        assertEquals("uncertain", rows().get(0).get("delta_status"));
        capture.menu(client, click(3, 100), 25);
        capture.container(client, 93, items(100, 3, 200, 1), 26);
        tick = 6;
        capture.poll(client, 27);
        assertEquals("uncertain", rows().get(1).get("delta_status"));
    }

    @Test public void competingClicksCancelAndUnsupportedActionsDoNotArm()
    {
        doReturn(items(100, 8)).when(client).getItemContainer(93);
        capture.menu(client, click(3, 100), 20);
        capture.menu(client, click(3, 100), 21);
        assertEquals("competing_menu_action", rows().get(0).get("reason"));
        for (int op : new int[]{1, 5, 7, 9}) capture.menu(client, click(op, 100), 22);
        MenuOptionClicked consumed = click(3, 100);
        when(consumed.isConsumed()).thenReturn(true);
        capture.menu(client, consumed, 22);
        tick = 3;
        capture.poll(client, 23);
        assertEquals(1, rows().size());
    }

    @Test public void notesRemainRawWithoutInventedBankNormalization()
    {
        doReturn(items(101, 8)).when(client).getItemContainer(93);
        capture.menu(client, click(6, 101), 20);
        capture.container(client, 93, items(), 21);
        tick = 2;
        capture.poll(client, 22);
        assertEquals("inferred", rows().get(0).get("delta_status"));
        assertEquals(101, rows().get(0).get("raw_item_id"));
        assertEquals("unknown", rows().get(0).get("bank_delta_status"));
    }

    @Test public void boundedRingAndResetAreExplicit()
    {
        for (int i = 1; i <= 70; i++) bank(100, i);
        assertEquals(64, rows().size());
        assertEquals(6L, capture.snapshot(client).get("observations_evicted"));
        Object session = capture.snapshot(client).get("collector_session");
        capture.reset();
        assertTrue(rows().isEmpty());
        assertNull(block("bank").get("last_seen_ms"));
        assertNotEquals(session, capture.snapshot(client).get("collector_session"));
    }

    @Test public void missingPlankDefinitionsStayUnknownAndKnownZeroSurvives()
    {
        VarbitComposition def = mock(VarbitComposition.class);
        when(def.getIndex()).thenReturn(1);
        when(def.getLeastSignificantBit()).thenReturn(1);
        when(def.getMostSignificantBit()).thenReturn(8);
        when(client.getVarbit(18171)).thenReturn(def);
        when(client.getVarps()).thenReturn(new int[5]);
        capture.poll(client, 20);
        Map<?, ?> quantities = (Map<?, ?>) block("plank_sack").get("quantities");
        assertEquals(0, quantities.get("plain"));
        assertNull(quantities.get("oak"));
        assertEquals("unknown", block("plank_sack").get("ownership"));
        assertEquals("partial", block("plank_sack").get("full_or_partial"));
    }

    @Test public void identityAndLoadingInvalidatePendingEvidence()
    {
        AccountProgressCapture progress = new AccountProgressCapture();
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(Player.class));
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
        assertFalse(progress.ready(client, "token"));
        tick = 2;
        assertTrue(progress.ready(client, "token"));
        when(client.getWidget(StorageRecoveryCapture.BANK_WIDGET)).thenReturn(mock(Widget.class));
        progress.storageRecovery.container(client, 95, items(100, 8), 10);
        when(client.getGameState()).thenReturn(GameState.LOADING);
        assertFalse(progress.ready(client, "token"));
        assertEquals(true, ((Map<?, ?>) progress.storageRecovery.snapshot(client).get("bank")).get("uncertain"));
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getAccountHash()).thenReturn(43L);
        assertFalse(progress.ready(client, "token"));
        assertNull(((Map<?, ?>) progress.storageRecovery.snapshot(client).get("bank")).get("items"));
        tick = 4;
        assertTrue(progress.ready(client, "token"));
        progress.storageRecovery.container(client, 95, items(100, 8), 20);
        assertFalse(progress.ready(client, "new-token"));
        assertTrue(((List<?>) progress.storageRecovery.snapshot(client).get("observations")).isEmpty());
    }

    @Test public void countsUseLongAndRejectMalformedOrOversizedArrays()
    {
        assertEquals(Long.valueOf(4294967294L), StorageRecoveryCapture.counts(items(100, Integer.MAX_VALUE, 100, Integer.MAX_VALUE)).get(100));
        assertNull(StorageRecoveryCapture.counts(items(100, -1)));
        ItemContainer oversized = mock(ItemContainer.class);
        when(oversized.getItems()).thenReturn(new Item[1501]);
        assertNull(StorageRecoveryCapture.counts(oversized));
    }
}
