package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/** Passive, own-bank cache interpretation. No widget items, inventory sum, prices or game actions. */
final class OwnBankTabsCapture
{
    static final int MAX_SLOTS = 4096;
    private final OwnGameplayCapture out;
    private int[][] observed;
    private int packetTick;
    private long packetAt;
    OwnBankTabsCapture(OwnGameplayCapture out) { this.out = out; }
    void reset() { observed = null; packetTick = -1; packetAt = 0; }

    void container(int id, ItemContainer container, int tick, long at)
    {
        if (id != InventoryID.BANK) return;
        observed = copy(container); packetTick = tick; packetAt = at;
    }

    private static int[][] copy(ItemContainer container)
    {
        Item[] items = container == null ? null : container.getItems();
        if (items == null || items.length > MAX_SLOTS) return null;
        int[][] result = new int[items.length][2];
        for (int i = 0; i < items.length; i++)
        {
            Item item = items[i];
            if (item == null || item.getQuantity() < 0 || item.getId() < -1
                || item.getId() <= 0 && item.getQuantity() > 0) return null;
            result[i][0] = item.getId(); result[i][1] = item.getQuantity();
        }
        return result;
    }

    void built(Client client, long at)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("basis", "inferred_raw_tab_ranges_same_session_cached_bank");
        value.put("complete", false);
        value.put("container_observed_at_ms", observed == null ? null : packetAt);
        value.put("tabs", null); value.put("untabbed", null);
        String status = "own_bank_packet_unavailable";
        if (observed != null)
        {
            int[] vars = client.getVarps();
            Integer tab = AccountProgressCapture.bit(client, vars, VarbitID.BANK_CURRENTTAB);
            Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
            if (at < packetAt || client.getTickCount() < packetTick) status = "clock_or_tick_gap";
            else if (bank == null || bank.isHidden() || tab == null || tab < 0 || tab > 9)
                status = "ordinary_bank_ui_unavailable"; // excludes potion storage's synthetic tab15
            else if (!Arrays.deepEquals(observed, copy(client.getItemContainer(InventoryID.BANK))))
                status = "bank_cache_changed_without_packet";
            else
            {
                Integer[] counts = new Integer[9]; long total = 0; boolean valid = true;
                for (int i = 0; i < counts.length; i++)
                {
                    counts[i] = AccountProgressCapture.bit(client, vars, 4171 + i);
                    if (counts[i] == null || counts[i] < 0) valid = false;
                    else total += counts[i];
                }
                if (!valid) status = "tab_counts_unknown";
                else if (total > observed.length) status = "tab_counts_exceed_container";
                else
                {
                    List<Map<String,Object>> tabs = new ArrayList<>(); int start = 0;
                    for (int i = 0; i < counts.length; i++)
                    {
                        Map<String,Object> range = range(start, counts[i]); range.put("tab", i + 1);
                        tabs.add(range); start += counts[i];
                    }
                    value.put("tabs", tabs);
                    // Tab0 in the UI displays all items, not just this remainder.
                    value.put("untabbed", range(start, observed.length - start));
                    status = "observed_cache_ranges";
                }
            }
        }
        value.put("status", status);
        out.state("storage.bank_tab_currency", "own_bank_packet+bankmain_build+tab_counts:4171-4179",
            value, client.getTickCount(), at, false);
    }

    private Map<String,Object> range(int start, int count)
    {
        long coins = 0, platinum = 0;
        for (int i = start; i < start + count; i++)
        {
            if (observed[i][0] == ItemID.COINS) coins += observed[i][1];
            if (observed[i][0] == ItemID.PLATINUM) platinum += observed[i][1];
        }
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("start_slot", start); row.put("slot_count", count);
        row.put("coins", coins); row.put("platinum_tokens", platinum);
        row.put("currency_gp_equivalent", coins + platinum * 1000L);
        return row;
    }
}
