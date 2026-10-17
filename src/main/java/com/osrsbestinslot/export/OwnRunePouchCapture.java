package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;

/** Own carried-pouch cache observations, never a bank/wealth balance or consumption ledger. */
final class OwnRunePouchCapture
{
    private OwnRunePouchCapture() {}
    // RuneLite1.13.1 RunepouchOverlay recognizes these four unnoted items and six cache slots.
    private static final Set<Integer> POUCHES = new HashSet<>(Arrays.asList(
        ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER,
        ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER));
    static final int[][] SLOTS = {
        {Varbits.RUNE_POUCH_RUNE1,Varbits.RUNE_POUCH_AMOUNT1},
        {Varbits.RUNE_POUCH_RUNE2,Varbits.RUNE_POUCH_AMOUNT2},
        {Varbits.RUNE_POUCH_RUNE3,Varbits.RUNE_POUCH_AMOUNT3},
        {Varbits.RUNE_POUCH_RUNE4,Varbits.RUNE_POUCH_AMOUNT4},
        {Varbits.RUNE_POUCH_RUNE5,Varbits.RUNE_POUCH_AMOUNT5},
        {Varbits.RUNE_POUCH_RUNE6,Varbits.RUNE_POUCH_AMOUNT6}
    };
    static Map<String,Object> read(Client client, int[] vars)
    {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("decoder_version", "own-carried-rune-pouch-v1");
        out.put("contents_semantics", "client_cached_slots");out.put("complete", false);
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        Item[] carried = inventory == null ? null : inventory.getItems();
        if (carried == null) { out.put("possession", "inventory_unavailable");return out; }
        List<Integer> pouchIds = new ArrayList<>();
        for (Item item : carried) if (item != null && item.getQuantity()>0 && POUCHES.contains(item.getId()))
            if (!pouchIds.contains(item.getId())) pouchIds.add(item.getId());
        if (pouchIds.isEmpty()) { out.put("possession", "not_observed_carried");return out; }
        Collections.sort(pouchIds);out.put("possession", "carried");out.put("pouch_item_ids", pouchIds);
        // Enumerate exact keys instead of accepting getIntValue's default for an unknown rune type.
        EnumComposition mapping = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        int[] keys = mapping == null ? null : mapping.getKeys(), values = mapping == null ? null : mapping.getIntVals();
        Map<Integer,Integer> runes = new HashMap<>();
        if (keys != null && values != null && keys.length == values.length && keys.length<=4096)
            for (int i=0;i<keys.length;i++) if(keys[i]>0&&values[i]>0) runes.put(keys[i],values[i]);
        List<Map<String,Object>> slots = new ArrayList<>();
        Map<Integer,Long> totals = new TreeMap<>();boolean fullyDecoded = true;
        for(int i=0;i<SLOTS.length;i++)
        {
            Integer type=AccountProgressCapture.bit(client,vars,SLOTS[i][0]);
            Integer amount=AccountProgressCapture.bit(client,vars,SLOTS[i][1]);
            Map<String,Object> slot=new LinkedHashMap<>();slot.put("slot",i);slot.put("type_raw",type);slot.put("amount",amount);
            String status;
            if(type==null||amount==null)status="unavailable";
            else if(type<0||amount<0||type==0&&amount>0)status="inconsistent_cache";
            else if(amount==0)status="observed_empty";
            else if(!runes.containsKey(type))status="mapping_unknown";
            else
            {
                status="observed_cached";int item=runes.get(type);slot.put("item",item);
                totals.put(item,totals.getOrDefault(item,0L)+(long)amount);
            }
            if(!status.equals("observed_cached")&&!status.equals("observed_empty"))fullyDecoded=false;
            slot.put("status",status);slots.add(slot);
        }
        List<Map<String,Object>> items = new ArrayList<>();
        for(Map.Entry<Integer,Long> entry:totals.entrySet())
        {
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",entry.getKey());item.put("qty",entry.getValue());items.add(item);
        }
        out.put("slots",slots);out.put("observed_items",items);out.put("all_slots_decoded",fullyDecoded);
        return out;
    }
}
