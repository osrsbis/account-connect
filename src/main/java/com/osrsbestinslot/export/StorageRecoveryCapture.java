package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.RuneScapeProfileType;

/** Bounded evidence, never a writer to legacy bank state. All calls use the progress grant/identity gate. */
final class StorageRecoveryCapture
{
    static final int BANK = 95, INVENTORY = 93, BANK_WIDGET = 786433, DEPOSIT_ITEMS = 12582936;
    private Map<Integer, Long> bank, inventory;
    private Long bankSeen, inventorySeen;
    private boolean gap = true, inventoryUncertain = true;
    private String session = UUID.randomUUID().toString();
    private long sequence, evicted, inventoryVersion;
    private final Deque<Map<String, Object>> observations = new ArrayDeque<>();
    private Pending pending;
    private Map<String, Object> plankSack;
    private int nextStorageTick;

    private static final class Pending
    {
        int item, tick, op;
        long quantity, at, inventoryVersion;
        Map<Integer, Long> before;
    }

    void reset()
    {
        bank = inventory = null;
        plankSack = null;
        nextStorageTick = 0;
        bankSeen = inventorySeen = null;
        gap = inventoryUncertain = true;
        pending = null;
        observations.clear();
        sequence = evicted = inventoryVersion = 0;
        session = UUID.randomUUID().toString();
    }

    static boolean bankVisible(Client client)
    {
        Widget widget = client.getWidget(BANK_WIDGET);
        return widget != null && !widget.isHidden();
    }

    void gap()
    {
        gap = inventoryUncertain = true;
        pending = null;
    }

    void container(Client client, int id, ItemContainer container, long now)
    {
        if (id != BANK && id != INVENTORY) return;
        Map<Integer, Long> counts = counts(container);
        if (counts == null) { gap(); return; }
        if (id == INVENTORY)
        {
            inventoryUncertain = false;
            inventoryVersion++;
            inventory = counts;
            inventorySeen = now;
            return;
        }
        // Cached bank containers outside the interface are not a fresh authoritative bank read.
        if (!bankVisible(client)) { gap = true; return; }
        // Empty bank transmissions can be clears during teardown; never fabricate a full withdrawal.
        if (counts.isEmpty()) { gap = true; return; }
        if (!gap && counts.equals(bank)) { bankSeen = now; return; }
        Map<String, Object> row = row(client, now, "item_container_changed_95");
        row.put("kind", bank == null ? "bank_baseline" : "bank_observed_delta");
        row.put("delta_status", bank == null ? "baseline_only" : "authoritative_container_difference");
        row.put("transfer_attribution", "unknown");
        row.put("across_gap", gap);
        row.put("from_observed_at_ms", bankSeen);
        if (bank != null) row.put("delta", difference(bank, counts));
        append(row);
        bank = counts;
        bankSeen = now;
        gap = false;
    }

    void menu(Client client, MenuOptionClicked event, long now)
    {
        // Competing actions invalidate attribution, including a second deposit before settlement.
        if (pending != null)
        {
            unresolved(client, now, "competing_menu_action");
            return;
        }
        if (event == null || event.isConsumed() || event.getParam1() != DEPOSIT_ITEMS
            || (event.getMenuAction() != MenuAction.CC_OP && event.getMenuAction() != MenuAction.CC_OP_LOW_PRIORITY)
            || bankVisible(client)) return;
        Widget widget = client.getWidget(DEPOSIT_ITEMS);
        if (widget == null || widget.isHidden()) return;
        int op = event.getId();
        // Only fixed 1/5/10 and All. Default and X need additional modal/settings evidence.
        if (op != 2 && op != 3 && op != 4 && op != 6) return;
        Map<Integer, Long> before = counts(client.getItemContainer(INVENTORY));
        if (before == null || event.getItemId() <= 0) return;
        long available = before.getOrDefault(event.getItemId(), 0L);
        if (available <= 0) return;
        pending = new Pending();
        pending.item = event.getItemId();
        pending.op = op;
        pending.tick = client.getTickCount();
        pending.at = now;
        pending.before = before;
        pending.inventoryVersion = inventoryVersion;
        pending.quantity = Math.min(available, op == 2 ? 1 : op == 3 ? 5 : op == 4 ? 10 : available);
    }

    void poll(Client client, long now)
    {
        if (plankSack == null || client.getTickCount() >= nextStorageTick)
        {
            samplePlanks(client, now);
            nextStorageTick = client.getTickCount() + 100;
        }
        if (!bankVisible(client)) gap = true;
        if (pending == null || client.getTickCount() - pending.tick < 2) return;
        Map<Integer, Long> delta = inventory == null ? null : difference(pending.before, inventory);
        boolean matched = inventoryVersion > pending.inventoryVersion && inventorySeen != null && inventorySeen >= pending.at && delta != null
            && delta.size() == 1 && Objects.equals(delta.get(pending.item), -pending.quantity)
            && !bankVisible(client) && client.getTickCount() - pending.tick <= 3;
        Map<String, Object> row = row(client, now, "deposit_box_menu_and_inventory_callback");
        row.put("kind", "deposit_box_recovery");
        row.put("delta_status", matched ? "inferred" : "uncertain");
        row.put("reason", matched ? "single_item_loss_matches_fixed_action" : "no_unambiguous_inventory_match");
        row.put("clicked_at_ms", pending.at);
        row.put("menu_op", pending.op);
        row.put("raw_item_id", pending.item);
        row.put("requested_quantity", pending.quantity);
        row.put("inventory_observed_at_ms", inventorySeen);
        if (matched) row.put("inventory_delta", delta);
        // Notes, item transformations and concurrent unseen actions prevent a bank-item/quantity assertion.
        row.put("bank_delta_status", "unknown");
        row.put("authoritative_baseline_mutated", false);
        append(row);
        pending = null;
    }

    private void unresolved(Client client, long now, String reason)
    {
        Map<String, Object> row = row(client, now, "deposit_box_menu");
        row.put("kind", "deposit_box_recovery");
        row.put("delta_status", "uncertain");
        row.put("reason", reason);
        row.put("clicked_at_ms", pending.at);
        append(row);
        pending = null;
    }

    private Map<String, Object> row(Client client, long now, String source)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequence", ++sequence);
        row.put("observed_at_ms", now);
        row.put("source", source);
        row.put("account_hash", Long.toString(client.getAccountHash()));
        row.put("profile_type", RuneScapeProfileType.getCurrent(client).name());
        row.put("world", client.getWorld());
        return row;
    }

    private void append(Map<String, Object> row)
    {
        if (observations.size() == 64) { observations.removeFirst(); evicted++; }
        observations.addLast(Collections.unmodifiableMap(row));
    }

    Map<String, Object> snapshot(Client client)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_v", 1);
        out.put("plank_sack", plankSack == null ? null : new LinkedHashMap<>(plankSack));
        out.put("collector_session", session);
        out.put("complete", false);
        out.put("observations_evicted", evicted);
        out.put("observations", new ArrayList<>(observations));
        out.put("bank", containerBlock(bank, bankSeen, gap || !bankVisible(client)));
        out.put("inventory", containerBlock(inventory, inventorySeen, inventoryUncertain));
        return out;
    }

    private void samplePlanks(Client client, long now)
    {
        int[] live = client.getVarps();
        int[] vars = live == null ? null : live.clone();
        Map<String, Object> values = new LinkedHashMap<>();
        String[] names = {"plain", "oak", "teak", "mahogany", "camphor", "ironwood", "rosewood"};
        // RuneLite gameval 1.13.1 PLANK_SACK_*; no runtime linkage to newer constants.
        for (int i = 0; i < names.length; i++)
            values.put(names[i], AccountProgressCapture.bit(client, vars, 18171 + i));
        plankSack = new LinkedHashMap<>();
        plankSack.put("quantities", values);
        plankSack.put("source", "sampled_varbits_18171_18177");
        plankSack.put("last_seen_ms", now);
        plankSack.put("full_or_partial", "partial");
        plankSack.put("authority", "client_varbit_values");
        plankSack.put("server_freshness", "unknown");
        plankSack.put("ownership", "unknown"); // zero counts do not prove possession of a sack
    }

    private static Map<String, Object> containerBlock(Map<Integer, Long> items, Long seen, boolean uncertain)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items == null ? null : new TreeMap<>(items));
        out.put("last_seen_ms", seen);
        out.put("full_or_partial", "partial"); // all observed slots, no protocol completeness proof
        out.put("source", "item_container_changed");
        out.put("uncertain", uncertain);
        return out;
    }

    static Map<Integer, Long> counts(ItemContainer container)
    {
        if (container == null || container.getItems() == null || container.getItems().length > 1500) return null;
        Map<Integer, Long> out = new TreeMap<>();
        for (Item item : container.getItems())
        {
            if (item == null || item.getQuantity() < 0) return null;
            if (item.getId() > 0 && item.getQuantity() > 0)
                out.merge(item.getId(), (long) item.getQuantity(), Long::sum);
        }
        return out;
    }

    private static Map<Integer, Long> difference(Map<Integer, Long> before, Map<Integer, Long> after)
    {
        Map<Integer, Long> delta = new TreeMap<>(after);
        before.forEach((id, quantity) -> delta.merge(id, -quantity, Long::sum));
        delta.values().removeIf(value -> value == 0);
        return delta;
    }
}
