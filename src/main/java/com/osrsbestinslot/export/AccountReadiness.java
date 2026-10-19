package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * progress.readiness.v1: named, versioned unlock/task state derived ONLY from the raw account_progress block.
 * Pure and deterministic: the same raw block gives the same result, so it is safe inside the change hash.
 * A missing raw value is "unknown", never "locked". No wall-clock time is added here; the caller owns as-of.
 */
final class AccountReadiness
{
    static final String SCHEMA = "progress.readiness.v1";
    // Cache-generated gameval 1.13.1 names next to their IDs, the same numbers AccountProgressCapture reads.
    private static final String[][] PRAYERS = {
        {"prayer.rigour", "varbit:5451"},          // PRAYER_RIGOUR_UNLOCKED
        {"prayer.augury", "varbit:5452"},          // PRAYER_AUGURY_UNLOCKED
        {"prayer.preserve", "varbit:5453"},        // PRAYER_PRESERVE_UNLOCKED
        {"prayer.deadeye", "varbit:16097"},        // PRAYER_DEADEYE_UNLOCKED
        {"prayer.mystic_vigour", "varbit:16098"},  // PRAYER_MYSTIC_VIGOUR_UNLOCKED
    };
    private static final String[] PRAYER_RAW = {"rigour", "augury", "preserve", "deadeye", "mystic_vigour"};
    private static final String[] WATSON = {"easy", "medium", "hard", "elite"}; // TRAIL_WATSON_* 5186..5189
    /** CA_TASK_COMPLETED_0..20. Task id = 32 * word + bit (cs2 proc script4834, pinned exporter sources). */
    static final int[] CA_TASK_VARPS = {3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128,
        3387, 3718, 3773, 3774, 4204, 4496, 4721, 5673};
    static final List<String> CA_TIERS = Collections.unmodifiableList(Arrays.asList(
        "easy", "medium", "hard", "elite", "master", "grandmaster"));

    private AccountReadiness()
    {
    }

    /** Derives readiness from a raw account_progress map, or returns null when there is no raw block. */
    static Map<String, Object> derive(Map<?, ?> raw)
    {
        if (raw == null)
        {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", SCHEMA);
        out.put("derived_from", "account_progress");
        Map<String, Object> unlocks = new TreeMap<>();
        Map<?, ?> prayers = map(raw.get("prayer_unlocks"));
        for (int i = 0; i < PRAYERS.length; i++)
        {
            Object value = prayers == null ? null : prayers.get(PRAYER_RAW[i]);
            String state = Boolean.TRUE.equals(value) ? "unlocked" : Boolean.FALSE.equals(value) ? "locked" : "unknown";
            unlocks.put(PRAYERS[i][0], entry(state, PRAYERS[i][1], value == null ? "var_unavailable" : "observed_var"));
        }
        // Knight Waves state 8 is widely used as "finished", but no runtime check proves the exact
        // threshold here. Keep chivalry/piety unknown with the raw value rather than guess either way.
        Object waves = prayers == null ? null : prayers.get("knight_waves_state");
        for (String name : new String[]{"prayer.chivalry", "prayer.piety"})
        {
            Map<String, Object> e = entry("unknown", "varbit:3909", "threshold_unverified");
            e.put("raw", waves instanceof Number ? waves : null);
            unlocks.put(name, e);
        }
        out.put("unlocks", unlocks);

        Map<String, Object> tasks = new TreeMap<>();
        Map<?, ?> watson = map(raw.get("watson_clues"));
        for (int i = 0; i < WATSON.length; i++)
        {
            Object value = watson == null ? null : watson.get(WATSON[i]);
            String state = Boolean.TRUE.equals(value) ? "done" : Boolean.FALSE.equals(value) ? "not_done" : "unknown";
            // The game clears these after the master clue is handed out, so not_done is current state, not history.
            tasks.put("clue.watson_" + WATSON[i] + "_handed_in", entry(state, "varbit:" + (5186 + i),
                value == null ? "var_unavailable" : "observed_var_current"));
        }
        out.put("tasks", tasks);
        out.put("slayer_task", slayer(map(raw.get("slayer"))));
        out.put("combat_achievements", combatAchievements(raw));

        // Inventory Setups stores setups in GLOBAL RuneLite config (getConfiguration, not the RS profile).
        // Nothing binds a saved setup to this account, so it is never offered as owned or account gear.
        Map<String, Object> setups = new LinkedHashMap<>();
        setups.put("state", "unsupported_ownership");
        setups.put("reason", "inventory_setups_global_config_no_account_binding");
        out.put("saved_setups", setups);
        return out;
    }

    /**
     * Read-model helper for advice/calculators: are the named unlocks all unlocked? "unknown" when any required
     * unlock is unknown and none is locked. A locked requirement wins, because it is observed.
     */
    static Map<String, Object> requirement(Map<?, ?> readiness, String goal, List<String> required)
    {
        Map<?, ?> unlocks = readiness == null ? null : map(readiness.get("unlocks"));
        List<String> missing = new ArrayList<>(), unknown = new ArrayList<>();
        for (String name : required)
        {
            Map<?, ?> e = unlocks == null ? null : map(unlocks.get(name));
            Object state = e == null ? null : e.get("state");
            if ("locked".equals(state)) missing.add(name);
            else if (!"unlocked".equals(state)) unknown.add(name);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goal", goal);
        out.put("state", !missing.isEmpty() ? "not_ready" : !unknown.isEmpty() ? "unknown" : "ready");
        out.put("missing", missing);
        out.put("unknown", unknown);
        out.put("schema", readiness == null ? null : readiness.get("schema"));
        return out;
    }

    private static Map<String, Object> slayer(Map<?, ?> s)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Integer remaining = s == null ? null : wholeInt(s.get("task_remaining"));
        String state = remaining == null ? "unknown" : remaining > 0 ? "active" : "none";
        out.put("state", state);
        out.put("source", "varp:394,395");
        boolean active = "active".equals(state);
        out.put("target_id", active ? s.get("task_target_id") : null); // raw game id; no name decode in v1
        out.put("remaining", active ? remaining : null);
        out.put("original", active ? s.get("task_original") : null);
        out.put("boss_id", active ? s.get("boss_id") : null);
        out.put("area_id", active ? s.get("area_id") : null);
        return out;
    }

    /**
     * Individual tasks come from the completion bitfield, never from tier totals or chat words. A tier label
     * is attached only from the game's own task catalogue (enums 3981..3986, struct param 1306 = task id).
     */
    static Map<String, Object> combatAchievements(Map<?, ?> raw)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Object points = raw.get("combat_achievement_points");
        out.put("points", points instanceof Number ? points : null);
        out.put("points_source", "varbit:14815");
        out.put("task_source", "varp CA_TASK_COMPLETED_0..20, task_id = 32*word + bit");
        Map<?, ?> ca = map(raw.get("combat_achievement_tasks"));
        List<?> words = ca == null || !(ca.get("words") instanceof List) ? null : (List<?>) ca.get("words");
        if (words == null || words.size() != CA_TASK_VARPS.length || !words.stream().allMatch(w -> wholeInt(w) != null))
        {
            // Unknown words make every task unknown, including "not completed".
            out.put("state", "unknown");
            out.put("completed_task_ids", null);
            out.put("catalog", "unavailable");
            out.put("completed_by_tier", null);
            return out;
        }
        List<Integer> completed = new ArrayList<>();
        for (int w = 0; w < words.size(); w++)
        {
            int value = wholeInt(words.get(w));
            for (int b = 0; b < 32; b++)
            {
                if ((value & (1 << b)) != 0) completed.add(32 * w + b);
            }
        }
        out.put("state", "observed");
        out.put("completed_task_ids", completed);
        Map<?, ?> tiers = map(ca.get("completed_by_tier"));
        if (tiers == null || !"loaded".equals(ca.get("catalog_status")))
        {
            out.put("catalog", "unavailable");
            out.put("completed_by_tier", null);
            return out;
        }
        Map<String, Object> byTier = new LinkedHashMap<>();
        java.util.Set<Integer> catalogued = new java.util.HashSet<>();
        for (String tier : CA_TIERS)
        {
            List<Integer> ids = new ArrayList<>();
            Object listed = tiers.get(tier);
            if (listed instanceof List)
            {
                for (Object id : (List<?>) listed)
                {
                    // Only a completed bit can carry a tier: a catalogue id without its bit is not a completion.
                    Integer task = wholeInt(id);
                    if (task != null && completed.contains(task) && catalogued.add(task)) ids.add(task);
                }
            }
            byTier.put(tier, ids);
        }
        List<Integer> uncatalogued = new ArrayList<>();
        for (Integer id : completed) if (!catalogued.contains(id)) uncatalogued.add(id); // reported, never guessed
        byTier.put("uncatalogued", uncatalogued);
        Map<String, Object> totals = new LinkedHashMap<>();
        Map<?, ?> rawTotals = map(ca.get("catalog_task_totals"));
        for (String tier : CA_TIERS)
        {
            Object n = rawTotals == null ? null : rawTotals.get(tier);
            totals.put(tier, wholeInt(n));
        }
        out.put("catalog", "game_cache_enums_3981_3986");
        out.put("catalog_sha256", ca.get("catalog_sha256"));
        out.put("completed_by_tier", byTier);
        out.put("catalog_task_totals", totals);
        return out;
    }

    /**
     * A JSON number is an int only when finite, whole and inside the int range. Lenient JSON parsing accepts NaN
     * and huge values; they must stay unknown instead of becoming 0 or Integer.MAX_VALUE.
     */
    static Integer wholeInt(Object o)
    {
        if (!(o instanceof Number)) return null;
        double d = ((Number) o).doubleValue();
        if (Double.isNaN(d) || d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) return null;
        return (int) d;
    }

    private static Map<String, Object> entry(String state, String source, String basis)
    {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("state", state);
        e.put("source", source);
        e.put("basis", basis);
        return e;
    }

    private static Map<?, ?> map(Object o)
    {
        return o instanceof Map ? (Map<?, ?>) o : null;
    }
}
