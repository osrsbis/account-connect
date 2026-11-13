package com.osrsbestinslot.export;

import java.util.stream.IntStream;

/**
 * Factual API 1.13.1 IDs from the pinned charting/totem acquisition reviews. The reviewed lists happen to be
 * contiguous, so they are written as the exact ranges they equal; the pinned lists themselves live in
 * OwnActivityCaptureTest, which checks these arrays element for element. Do not widen a range by inference.
 */
final class OwnActivityMappings
{
    private OwnActivityMappings() {}
    // sea-charting-quest-helper pinned source table task IDs 0..357; protocol keys are varbit IDs 18574..18931.
    static final int[] CHARTING = IntStream.rangeClosed(18574, 18931).toArray();
    static final String[] TOTEM_FIELDS = {"base_raw", "base_carved_raw", "low_raw", "mid_raw", "top_raw", "decorations_raw", "animal_1_raw", "animal_2_raw", "animal_3_raw", "decay_raw", "points_raw"};
    // Site s (0..7) uses varbits 17611 + 18*s + offset; offset 2 is not a totem field.
    static final int[][] TOTEMS = new int[8][];
    static
    {
        for (int site = 0; site < TOTEMS.length; site++)
        {
            int base = 17611 + 18 * site;
            TOTEMS[site] = IntStream.of(0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11).map(o -> base + o).toArray();
        }
    }
    /**
     * The plain varbit families, each {family, snapshot source or null for "varbits:" and the id list, then key and
     * varbit id pairs in value order}. Poll order: row 0 (then raid points), rows 1-4 (then goals, rune pouch, potion
     * store and port tasks), rows 5-10. OwnEvent builds the own_vars wire keys from the same rows.
     */
    static final Object[][] VARBITS = {
        {"combat.barrows_state", "varbits:457-464", "ahrim", 457, "dharok", 458, "guthan", 459, "karil", 460, "torag", 461,
            "verac", 462, "reward_potential_raw", 463, "npcs_slain_raw", 464},
        {"effects.surge", "varbit:16270", "timer_raw", 16270},
        {"effects.death_charge", "varbits:12411,12138", "active_raw", 12411, "cooldown_raw", 12138},
        // Settings are not possession, server idle time, or a promise that teleport will occur.
        {"effects.escape_crystal", "varbits:14838,14849,14857", "afk_mode_raw", 14838, "afk_delay_raw", 14849, "ring_of_life_raw", 14857},
        // Ticket availability is not lifetime currency and no path/action is inferred.
        {"minigame.arena_state", "varbits:5964,10732,5962", "entry_paid_raw", 5964, "cooldown_raw", 10732, "ticket_available_raw", 5962},
        {"minigame.tears", null, "countdown_raw", 5099, "tears_raw", 455, "collecting_raw", 453},
        // Only the local player's contribution, never team bottles or other players' scoreboards.
        {"minigame.brewing", null, "own_contribution_raw", 2290},
        {"minigame.restaurant_order", null, "started_raw", 2478, "customer_id_raw", 2484, "order_id_raw", 2483, "refused_hard_raw", 2485},
        {"minigame.plunder_state", null, "timer_raw", 2375, "room_raw", 2377},
        {"sailing.trial_progress", null, "tempor_tantrum_raw", 18427, "jubbly_jive_raw", 18432, "gwenith_glide_raw", 18437},
        {"storage.bank_tabs", null, "tab_1_slots", 4171, "tab_2_slots", 4172, "tab_3_slots", 4173, "tab_4_slots", 4174,
            "tab_5_slots", 4175, "tab_6_slots", 4176, "tab_7_slots", 4177, "tab_8_slots", 4178, "tab_9_slots", 4179},
    };
    static final int[][] BOLTS = {
        {879, 9236},
        {9337, 9240},
        {9335, 9237},
        {880, 9238},
        {9338, 9241},
        {9336, 9239},
        {9339, 9242},
        {9340, 9243},
        {9341, 9244},
        {9342, 9245},
        {21955, 21932},
        {21963, 21940},
        {21957, 21934},
        {21959, 21936},
        {21965, 21942},
        {21961, 21938},
        {21967, 21944},
        {21969, 21946},
        {21971, 21948},
        {21973, 21950},
    };
}
