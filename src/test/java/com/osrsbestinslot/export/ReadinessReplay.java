package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline replay for actual-client QA (test classpath only, not in the plugin JAR). Reads snapshot bodies as the
 * client sent them ({token, snapshot} or a bare snapshot) and prints progress.readiness.v1 for each, plus the
 * worn-equipment loadout revisions across them in order. No token or account name is printed.
 *
 * Usage: java ... ReadinessReplay SNAPSHOT.json [SNAPSHOT2.json ...]
 */
public final class ReadinessReplay
{
    public static void main(String[] args) throws Exception
    {
        if (args.length == 0)
        {
            System.err.println("usage: ReadinessReplay SNAPSHOT.json [SNAPSHOT2.json ...]");
            System.exit(2);
        }
        System.out.println(new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(replay(args)));
    }

    static Map<String, Object> replay(String... paths) throws Exception
    {
        List<Object> results = new ArrayList<>();
        LoadoutRevision loadouts = new LoadoutRevision("replay");
        int index = 0;
        for (String path : paths)
        {
            Map<?, ?> snap = load(path);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("file_index", index++);
            one.put("captured_at", snap.get("captured_at"));
            Object progress = snap.get("account_progress");
            one.put("account_progress_present", progress instanceof Map);
            one.put("readiness", progress instanceof Map ? AccountReadiness.derive((Map<?, ?>) progress) : null);
            Object equipment = snap.get("equipment");
            if (equipment instanceof Map)
            {
                Number at = snap.get("captured_at") instanceof Number ? (Number) snap.get("captured_at") : 0;
                loadouts.observe(slots((Map<?, ?>) equipment), index, at.longValue() * 1000L);
            }
            else
            {
                loadouts.gap(index);
            }
            results.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshots", results);
        out.put("loadout_revisions", loadouts.revisions());
        return out;
    }

    /** Snapshot "equipment" is {slot_name: {id, qty}}; map names back to EquipmentInventorySlot indices. */
    static Map<Integer, Integer> slots(Map<?, ?> equipment)
    {
        String[] names = {"head", "cape", "amulet", "weapon", "body", "shield", "arms", "legs", "hair", "gloves",
            "boots", "jaw", "ring", "ammo"};
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++)
        {
            Object item = equipment.get(names[i]);
            if (item instanceof Map && ((Map<?, ?>) item).get("id") instanceof Number)
            {
                out.put(i, ((Number) ((Map<?, ?>) item).get("id")).intValue());
            }
        }
        return out;
    }

    static Map<?, ?> load(String path) throws Exception
    {
        try (Reader r = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8))
        {
            Map<?, ?> body = new Gson().fromJson(r, Map.class);
            return body.get("snapshot") instanceof Map ? (Map<?, ?>) body.get("snapshot") : body;
        }
    }
}
