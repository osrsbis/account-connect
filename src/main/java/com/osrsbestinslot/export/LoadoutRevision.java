package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * loadout.revision.v1: ordered worn-equipment revisions for ONE caller-supplied generation (account/profile/mode
 * session). Pure and deterministic. The caller (shared adapter) owns the generation key; this class never joins
 * two independently generated session IDs. Agreed with C2 on 2026-10-19.
 *
 * Rules:
 * - No observation yet = no revision. Unknown equipment is never an empty loadout.
 * - The first observation of a generation, and the first after a gap, is a "baseline", never a change.
 * - Only a different slot -> item id map makes a new revision. Quantity changes (ammo, darts) do not.
 * - content_id = sha256 of the canonical slot map, so the same gear joins across generations.
 * - Inventory Setups are not an input: they are global RuneLite config with no account binding.
 */
final class LoadoutRevision
{
    static final String SCHEMA = "loadout.revision.v1";
    /** EquipmentInventorySlot indices 0..13; 8 (HAIR) and 11 (JAW) are body kit, never worn items. */
    private static final String[] SLOT_NAMES = {"head", "cape", "amulet", "weapon", "body", "shield", null, "legs",
        null, "gloves", "boots", null, "ring", "ammo"};
    static final int MAX_REVISIONS = 256;

    private final String generation;
    private final List<Map<String, Object>> revisions = new ArrayList<>();
    private Map<Integer, Integer> current;
    private boolean gap = true;
    private int evicted;
    /** {revision_seq the gap followed, gap tick}: the gap ends that revision's validity. */
    private final List<int[]> gaps = new ArrayList<>();

    LoadoutRevision(String generation)
    {
        if (generation == null || generation.isEmpty()) throw new IllegalArgumentException("generation required");
        this.generation = generation;
    }

    /** The client lost sight of the equipment (logout, hop, region gap): the next observation is a baseline. */
    void gap(int tick)
    {
        if (!gap) gaps.add(new int[]{revisions.size() + evicted, tick});
        gap = true;
        if (gaps.size() > MAX_REVISIONS) gaps.remove(0);
    }

    /**
     * Records one observed worn-equipment state. slotItemIds: equipment slot index -> item id, only occupied slots.
     * Returns the new revision, or null when the item-id map did not change.
     */
    Map<String, Object> observe(Map<Integer, Integer> slotItemIds, int tick, long observedAtMs)
    {
        if (slotItemIds == null) return null; // container unavailable is unknown, not empty
        Map<Integer, Integer> slots = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : slotItemIds.entrySet())
        {
            Integer slot = e.getKey(), item = e.getValue();
            if (slot == null || slot < 0 || slot >= SLOT_NAMES.length || SLOT_NAMES[slot] == null) continue;
            if (item != null && item > 0) slots.put(slot, item);
        }
        boolean baseline = gap || current == null;
        if (!baseline && slots.equals(current)) return null;
        // A baseline that matches the last known map still records a revision: it proves the gear at that time.
        Map<String, Object> rev = new LinkedHashMap<>();
        rev.put("schema", SCHEMA);
        rev.put("generation", generation);
        rev.put("revision_seq", revisions.size() + evicted + 1);
        rev.put("tick", tick);
        rev.put("observed_at_ms", observedAtMs);
        rev.put("provenance", baseline ? "baseline" : "observed_change");
        rev.put("content_id", contentId(slots));
        rev.put("slots", named(slots));
        rev.put("previous_content_id", baseline ? null : contentId(current));
        current = slots;
        gap = false;
        Map<String, Object> frozen = Collections.unmodifiableMap(rev);
        revisions.add(frozen);
        if (revisions.size() > MAX_REVISIONS)
        {
            revisions.remove(0);
            evicted++;
        }
        return frozen;
    }

    /**
     * Revision active at tick (max revision_seq with tick <= at), or null when the gear is unknown at that tick:
     * before the first retained revision, or inside a gap that no later baseline has closed yet.
     */
    Map<String, Object> activeAt(int tick)
    {
        Map<String, Object> best = null;
        for (Map<String, Object> rev : revisions)
        {
            if ((Integer) rev.get("tick") <= tick) best = rev;
        }
        if (best == null) return null;
        int seq = (Integer) best.get("revision_seq");
        for (int[] g : gaps)
        {
            if (g[0] == seq && g[1] <= tick) return null;
        }
        return best;
    }

    List<Map<String, Object>> revisions()
    {
        return Collections.unmodifiableList(revisions);
    }

    int evicted()
    {
        return evicted;
    }

    String generation()
    {
        return generation;
    }

    /**
     * Adapter helper: worn container items (index = EquipmentInventorySlot index) to the observe() input.
     * Returns null for a missing container, so "unavailable" never becomes an empty loadout.
     */
    static Map<Integer, Integer> slotsOf(net.runelite.api.Item[] worn)
    {
        if (worn == null) return null;
        Map<Integer, Integer> out = new TreeMap<>();
        for (int i = 0; i < worn.length && i < SLOT_NAMES.length; i++)
        {
            if (worn[i] != null && worn[i].getId() > 0 && worn[i].getQuantity() > 0) out.put(i, worn[i].getId());
        }
        return out;
    }

    /** Canonical "slot=item;" string over named slots in index order, hashed. Stable across generations. */
    static String contentId(Map<Integer, Integer> slots)
    {
        StringBuilder canon = new StringBuilder("loadout.v1;");
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(slots).entrySet())
        {
            canon.append(SLOT_NAMES[e.getKey()]).append('=').append(e.getValue()).append(';');
        }
        return AccountConnectPlugin.sha256Hex(canon.toString());
    }

    private static Map<String, Object> named(Map<Integer, Integer> slots)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : slots.entrySet()) out.put(SLOT_NAMES[e.getKey()], e.getValue());
        return Collections.unmodifiableMap(out);
    }

    /**
     * Ownership context for one revision from C4 holdings. owns is C4 WealthLedger.owns (item id -> answer map
     * with "answer" yes|no_in_observed_locations|unknown and location lists). Worn at the revision is observed;
     * current holdings come only from C4. A null holdings source or a missing answer stays unknown.
     */
    static Map<String, Object> ownership(Map<String, Object> revision, java.util.function.IntFunction<? extends Map<?, ?>> owns)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<?, ?> slots = (Map<?, ?>) revision.get("slots");
        for (Map.Entry<?, ?> e : slots.entrySet())
        {
            int item = ((Number) e.getValue()).intValue();
            Map<?, ?> answer = owns == null ? null : owns.apply(item);
            Object a = answer == null ? null : answer.get("answer");
            String state = "yes".equals(a) ? "owned_observed"
                : "no_in_observed_locations".equals(a) ? "not_seen_in_observed" : "unknown";
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("item_id", item);
            slot.put("worn_at_revision", true);
            slot.put("current_holdings", state);
            slot.put("unknown_locations", answer == null ? null : answer.get("unknown_locations"));
            out.put((String) e.getKey(), slot);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content_id", revision.get("content_id"));
        result.put("revision_seq", revision.get("revision_seq"));
        result.put("slots", out);
        result.put("holdings_source", owns == null ? "unavailable" : "c4_wealth_ledger_owns");
        return result;
    }
}
