package com.osrsbestinslot.export;

import java.util.*;
import java.util.function.IntToLongFunction;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;

/** Personal loot-key chest cache only. No victim identity, key provenance, income or new-loot claim. */
final class OwnLootKeysCapture
{
    private static final int[] IDS={InventoryID.DEADMAN_LOOT_INV0,InventoryID.DEADMAN_LOOT_INV1,
        InventoryID.DEADMAN_LOOT_INV2,InventoryID.DEADMAN_LOOT_INV3,InventoryID.DEADMAN_LOOT_INV4};
    private final OwnGameplayCapture out;
    private final IntToLongFunction prices;
    private final Map<Integer,int[][]> packets=new HashMap<>();
    private final Map<Integer,Long> times=new HashMap<>();
    OwnLootKeysCapture(OwnGameplayCapture out,IntToLongFunction prices) { this.out=out;this.prices=prices; }
    static boolean supports(int id) { for(int known:IDS)if(id==known)return true;return false; }
    void reset() { packets.clear();times.clear(); }
    private static boolean visible(Client client)
    {
        Widget tabs=client.getWidget(InterfaceID.WildyLootChest.TABS);
        return tabs!=null&&!tabs.isHidden(); // Never read its labels, children, names or menu text.
    }
    void container(Client client,int id,ItemContainer container,long at)
    {
        if(!supports(id))return;
        if(!visible(client)){reset();return;}
        int[][] copy=copy(id,container);
        if(copy==null){packets.remove(id);times.remove(id);return;}
        packets.put(id,copy);times.put(id,at);
    }
    private static int[][] copy(int id,ItemContainer container)
    {
        if(container==null||container.getId()!=id)return null;
        Item[] items=container.getItems();
        // Defensive acquisition limit, not a claim about the game's key capacity.
        if(items==null||items.length>128)return null;
        int[][] out=new int[items.length][2];
        for(int i=0;i<items.length;i++)
        {
            Item item=items[i];if(item==null||item.getId()<-1||item.getQuantity()<0
                ||item.getId()<=0&&item.getQuantity()>0)return null;
            out[i][0]=item.getId();out[i][1]=item.getQuantity();
        }
        return out;
    }
    void poll(Client client,long at)
    {
        Map<String,Object> value=new LinkedHashMap<>();value.put("basis","own_visible_key_cache");
        value.put("complete",false);value.put("new_loot_verified",false);
        if(!visible(client)) { reset();value.put("status","interface_unavailable");value.put("slots",null); }
        else
        {
            List<Map<String,Object>> slots=new ArrayList<>();
            for(int slot=0;slot<IDS.length;slot++)
            {
                int id=IDS[slot];Map<String,Object> row=new LinkedHashMap<>();row.put("slot",slot);
                row.put("status","not_observed_in_session");row.put("items",null);row.put("value_gp",null);row.put("observed_at_ms",null);
                int[][] seen=packets.get(id);
                if(seen!=null)
                {
                    int[][] current=copy(id,client.getItemContainer(id));
                    if(current==null||!Arrays.deepEquals(seen,current)||times.get(id)>at)row.put("status","cache_unverified");
                    else
                    {
                        Map<Integer,Long> totals=new TreeMap<>();
                        for(int[] item:current)if(item[0]>0&&item[1]>0)totals.merge(item[0],(long)item[1],Long::sum);
                        List<Map<String,Object>> items=new ArrayList<>();Long estimate=0L;
                        for(Map.Entry<Integer,Long> entry:totals.entrySet())
                        {
                            items.add(Map.of("id",entry.getKey(),"qty",entry.getValue()));
                            if(estimate!=null)try
                            {
                                long price=entry.getKey()==995?1:prices.applyAsLong(entry.getKey());
                                estimate=price<=0?null:Math.addExact(estimate,Math.multiplyExact(price,entry.getValue()));
                                if(estimate!=null&&estimate>9007199254740991L)estimate=null;
                            }
                            catch(RuntimeException unknown){estimate=null;}
                        }
                        row.put("items",items);row.put("value_gp",estimate);row.put("observed_at_ms",times.get(id));
                        row.put("status",items.isEmpty()?"observed_empty_cache":"cached_items");
                    }
                }
                slots.add(row);
            }
            value.put("status","visible_key_slots");value.put("slots",slots);value.put("valuation_basis","client_reference_price_estimate");
        }
        out.state("loot.owned_key_contents","own_key_packet+visible_fixed_chest",value,client.getTickCount(),at);
    }
}
