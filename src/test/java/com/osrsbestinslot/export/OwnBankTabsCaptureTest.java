package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnBankTabsCaptureTest
{
    private Client client;
    private OwnGameplayCapture out;
    private ItemContainer bank;
    private int[] vars;
    private int tick = 100;
    @Before public void setup()
    {
        client = mock(Client.class); bank = mock(ItemContainer.class); out = new OwnGameplayCapture();
        vars = new int[20]; when(client.getVarps()).thenReturn(vars);
        when(client.getTickCount()).thenAnswer(i -> tick);
        when(client.getItemContainer(95)).thenReturn(bank);
        Widget widget = mock(Widget.class); when(client.getWidget(InterfaceID.Bankmain.ITEMS)).thenReturn(widget);
        bit(4150, 1); for (int i = 0; i < 9; i++) bit(4171 + i, 2 + i);
    }
    private void bit(int id, int index)
    {
        VarbitComposition def = mock(VarbitComposition.class); when(def.getIndex()).thenReturn(index);
        when(def.getMostSignificantBit()).thenReturn(15); when(client.getVarbit(id)).thenReturn(def);
    }
    private void packet(Item... items)
    {
        when(bank.getItems()).thenReturn(items); out.bankTabs.container(95, bank, tick, 1000);
    }
    private Map<?,?> build()
    {
        out.bankTabs.built(client, 1100);
        return (Map<?,?>)((Map<?,?>)((Map<?,?>)out.snapshot().get("states")).get("storage.bank_tab_currency")).get("value");
    }
    private Map<?,?> tab(Map<?,?> value, int index) { return (Map<?,?>)((List<?>)value.get("tabs")).get(index); }

    @Test public void missingOrSharedContainerNeverBecomesAnEmptyOwnBank()
    {
        when(bank.getItems()).thenReturn(new Item[]{new Item(995, 100)});
        out.bankTabs.container(9999, bank, tick, 1000);
        assertEquals("own_bank_packet_unavailable", build().get("status"));
        assertNull(build().get("tabs")); verify(client, never()).getItemContainer(anyInt());
        verify(bank, never()).getItems(); verify(client, never()).getWidgetRoots();
        verify(client, never()).runScript(anyInt(), any());
    }

    @Test public void emptyTabsAndPlaceholderCurrenciesRemainObservedZero()
    {
        packet(); Map<?,?> empty = build(); assertEquals(9, ((List<?>)empty.get("tabs")).size());
        assertEquals(0L, tab(empty, 0).get("currency_gp_equivalent"));
        packet(new Item(995, 0), new Item(-1, 0)); vars[2] = 1;
        Map<?,?> placeholders = build(); assertEquals(0L, tab(placeholders, 0).get("coins"));
        assertEquals(1, ((Map<?,?>)placeholders.get("untabbed")).get("slot_count"));
        assertFalse((Boolean)placeholders.get("complete"));
    }

    @Test public void reorderKeepsRangesAndLongCurrenciesWithoutInventingAGainOrInventoryWealth()
    {
        packet(new Item(995, Integer.MAX_VALUE), new Item(13204, Integer.MAX_VALUE), new Item(2, 1));
        vars[2] = 1; vars[3] = 1; Map<?,?> before = build();
        assertEquals((long)Integer.MAX_VALUE, tab(before, 0).get("coins"));
        assertEquals(Integer.MAX_VALUE * 1000L, tab(before, 1).get("currency_gp_equivalent"));
        packet(new Item(13204, Integer.MAX_VALUE), new Item(995, Integer.MAX_VALUE), new Item(2, 1));
        tick++; Map<?,?> after = build();
        assertEquals(Integer.MAX_VALUE * 1000L, tab(after, 0).get("currency_gp_equivalent"));
        assertEquals((long)Integer.MAX_VALUE, tab(after, 1).get("coins"));
        List<?> observations = (List<?>)out.snapshot().get("observations");
        Map<?,?> last = (Map<?,?>)observations.get(observations.size() - 1);
        assertEquals(false, last.get("direct"));
        assertEquals("absolute_update", ((Map<?,?>)last.get("value")).get("observation"));
        assertFalse(after.containsKey("gain")); verify(client, never()).getItemContainer(93);
    }

    @Test public void missingCountsOversizeRangesAndUnobservedCacheChangesAreUnknown()
    {
        packet(new Item(995, 100)); vars[2] = 2;
        assertEquals("tab_counts_exceed_container", build().get("status")); assertNull(build().get("tabs"));
        vars[2] = 1; when(client.getVarbit(4172)).thenReturn(null);
        assertEquals("tab_counts_unknown", build().get("status"));
        bit(4172, 3); when(bank.getItems()).thenReturn(new Item[]{new Item(995, 200)});
        assertEquals("bank_cache_changed_without_packet", build().get("status"));
        packet(new Item[OwnBankTabsCapture.MAX_SLOTS + 1]);
        assertEquals("own_bank_packet_unavailable", build().get("status"));
    }

    @Test public void potionTabAndSceneGapCannotReuseAnOldBankPacket()
    {
        packet(new Item(995, 100)); vars[2] = 1; vars[1] = 15;
        assertEquals("ordinary_bank_ui_unavailable", build().get("status"));
        vars[1] = 0; assertEquals("observed_cache_ranges", build().get("status"));
        out.gap(); assertEquals("own_bank_packet_unavailable", build().get("status"));
        packet(new Item(995, 100)); tick = 99;
        assertEquals("clock_or_tick_gap", build().get("status"));
        out.reset(); assertEquals("own_bank_packet_unavailable", build().get("status"));
    }
}
