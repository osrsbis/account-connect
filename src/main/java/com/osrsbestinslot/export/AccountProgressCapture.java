package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.StructComposition;
import net.runelite.api.Skill;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.VarbitComposition;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.util.Text;

/** Own-account observations only. No persistence or third-party plugin dependencies. Only the permitted collection Search action. */
final class AccountProgressCapture
{
    // Cache-generated gameval 1.13.1 IDs; numeric IDs avoid linking newer enum fields on older clients.
    private static final Pattern CLUE = Pattern.compile("^You have completed ([0-9,]+) (beginner|easy|medium|hard|elite|master) Treasure Trails?\\.$");
    private static final Pattern COLLECTION = Pattern.compile("^New item added to your collection log: (.{1,160})$");
    private static final Pattern CA = Pattern.compile("^(?:CA_ID:([0-9]+)\\|)?Congratulations, you've completed an? (easy|medium|hard|elite|master|grandmaster) combat task: (.{1,160})\\.$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SALVAGE = Pattern.compile("^You sort through the (Small|Fishy|Barracuda|Large|Plundered|Martial|Fremennik|Opulent) salvage and find: ([0-9,]+) x (.{1,160})\\.$", Pattern.CASE_INSENSITIVE);
    private String identity;
    private int settleTick;
    private final Map<Integer, Integer> collectionItems = new TreeMap<>();
    private Long collectionObservedAt;
    private final Map<String, Map<String, Object>> signals = new LinkedHashMap<>();
    private int evicted;
    private final GeHistoryCapture geHistory = new GeHistoryCapture();
    final StorageRecoveryCapture storageRecovery = new StorageRecoveryCapture();
    private boolean scanActive, scanComplete, scanAttempted;
    private int scanStartTick, lastSlotTick, nextScanTick;
    private Integer scanObtained, scanTotal;
    private Long scanStartedAt, scanVerifiedAt;
    private String scanStatus = "not_scanned";
    // Static game-cache CA catalogue (task id -> tier), loaded on the client thread. Not account data.
    private Map<Integer, String> caCatalog;
    private int caCatalogNextTick;

    void reset()
    {
        identity = null;
        collectionItems.clear();
        collectionObservedAt = null;
        signals.clear();
        evicted = 0;
        geHistory.reset();
        storageRecovery.reset();
        scanActive = scanComplete = scanAttempted = false;
        nextScanTick = 0;
        scanObtained = scanTotal = null;
        scanStartedAt = scanVerifiedAt = null;
        scanStatus = "not_scanned";
    }

    boolean ready(Client client, String token)
    {
        // Region loads are not account transitions. Retain last-seen observations, but do not read.
        if (client != null && client.getGameState() == GameState.LOADING)
        {
            storageRecovery.gap();
            return false;
        }
        if (client == null || client.getGameState() != GameState.LOGGED_IN
            || client.getLocalPlayer() == null || (client.getAccountHash() == -1L || client.getAccountHash() == 0L)
            || client.getRealSkillLevel(Skill.HITPOINTS) <= 0)
        {
            reset();
            return false;
        }
        String key = token + ":" + client.getAccountHash() + ":" + RuneScapeProfileType.getCurrent(client).name();
        if (!Objects.equals(identity, key))
        {
            reset();
            identity = key;
            settleTick = client.getTickCount();
            return false;
        }
        return client.getTickCount() - settleTick >= 2;
    }

    void collectionSlot(Client client, Object[] args, long now)
    {
        // Missing discriminator is unknown, never permission to read a house host's log.
        Integer host = bit(client, client.getVarps(), 12061);
        if (!Integer.valueOf(0).equals(host) || args == null || args.length < 3
            || !(args[1] instanceof Integer) || !(args[2] instanceof Integer))
        {
            return;
        }
        int id = (Integer) args[1];
        int quantity = (Integer) args[2];
        if (id <= 0 || (quantity < 0 || (quantity == 0 && !scanActive)) || (collectionItems.size() >= 4096 && !collectionItems.containsKey(id)))
        {
            return;
        }
        lastSlotTick = client.getTickCount();
        if (!Objects.equals(collectionItems.get(id), quantity))
        {
            scanComplete = false;
            if (!scanActive && scanVerifiedAt != null) scanStatus = "slots_changed";
        }
        collectionItems.put(id, quantity); // absolute count, repeated draws never increment
        collectionObservedAt = now;
    }

    /** Called only after the existing grant and account-settlement gate, on the client thread. */
    void collectionSetup(Client client, long now)
    {
        if (scanAttempted || client.getTickCount() < nextScanTick || !ownLog(client)) return;
        Widget search = client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE);
        if (search == null || search.isHidden()) return;
        scanAttempted = true; // set before synchronous scripts can re-enter
        nextScanTick = client.getTickCount() + 100;
        scanActive = true;
        scanComplete = false;
        scanStatus = "scanning";
        scanStartTick = lastSlotTick = client.getTickCount();
        scanStartedAt = now;
        scanVerifiedAt = null;
        scanObtained = varp(client.getVarps(), 2943);
        scanTotal = varp(client.getVarps(), 2944);
        collectionItems.clear();
        collectionObservedAt = null;
        try
        {
            // RuneLite's sole documented menuAction exception. Never enable packet-sending scripts.
            client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
            client.runScript(2240);
        }
        catch (RuntimeException | LinkageError ex)
        {
            scanActive = false;
            scanStatus = "unsupported_client"; // passive script observations remain usable
        }
    }

    void collectionClosed()
    {
        scanAttempted = false;
        if (scanActive)
        {
            scanActive = false;
            scanStatus = "closed_before_verification";
        }
    }

    private boolean ownLog(Client client)
    {
        return Integer.valueOf(0).equals(bit(client, client.getVarps(), 12061));
    }

    void poll(Client client, long now)
    {
        if (caCatalog == null && client.getTickCount() >= caCatalogNextTick)
        {
            caCatalog = loadCaCatalog(client);
            if (caCatalog == null) caCatalogNextTick = client.getTickCount() + 100;
        }
        geHistory.poll(client, now);
        storageRecovery.poll(client, now);
        if (!ownLog(client))
        {
            scanActive = scanComplete = false;
            scanStatus = "own_log_unverified";
            return;
        }
        if ((scanComplete || scanActive) && (!Objects.equals(scanObtained, varp(client.getVarps(), 2943))
            || !Objects.equals(scanTotal, varp(client.getVarps(), 2944))))
        {
            scanActive = scanComplete = false;
            scanStatus = "counts_changed";
        }
        if (!scanActive) return;
        int tick = client.getTickCount();
        if (tick - scanStartTick > 20)
        {
            scanActive = false;
            scanStatus = "timeout";
            return;
        }
        if (tick - lastSlotTick < 3) return;
        Integer obtained = varp(client.getVarps(), 2943), total = varp(client.getVarps(), 2944);
        if (total != null && total > 0 && obtained != null && obtained >= 0 && obtained <= total
            && Objects.equals(scanTotal, total) && Objects.equals(scanObtained, obtained)
            && positiveCollectionCount() == obtained)
        {
            scanActive = false;
            scanComplete = true;
            scanStatus = "count_verified";
            scanVerifiedAt = now;
            if (collectionObservedAt == null) collectionObservedAt = now / 1000L;
        }
        // Mismatch is not completion; leave time for delayed transmissions until the timeout.
    }

    private long positiveCollectionCount()
    {
        return collectionItems.values().stream().filter(quantity -> quantity > 0).count();
    }

    void chat(String message, long now)
    {
        Map<String, Object> signal = parseMessage(message);
        if (signal == null)
        {
            return;
        }
        String key = signal.get("kind") + ":" + signal.get("subject");
        Map<String, Object> old = signals.get(key);
        // Identical chat/SPAM callbacks must not churn timestamps or create duplicate observations.
        if (old != null)
        {
            Map<String, Object> value = new LinkedHashMap<>(old);
            value.remove("observed_at");
            if (value.equals(signal))
            {
                return;
            }
        }
        signal.put("observed_at", now);
        signals.remove(key);
        signals.put(key, signal);
        if (signals.size() > 128)
        {
            signals.remove(signals.keySet().iterator().next());
            evicted++;
        }
    }

    static Map<String, Object> parseMessage(String message)
    {
        if (message == null || message.length() > 512)
        {
            return null;
        }
        String text = Text.removeTags(message).replaceAll("@\\w+@", "").replace('\u00a0', ' ').trim();
        Map<String, Object> out = new LinkedHashMap<>();
        Matcher m = CLUE.matcher(text);
        try
        {
            if (m.matches())
            {
                out.put("kind", "clue_complete");
                out.put("subject", m.group(2));
                out.put("count", positiveCount(m.group(1)));
            }
            else if ((m = COLLECTION.matcher(text)).matches())
            {
                out.put("kind", "collection_log_add");
                out.put("subject", m.group(1)); // name only: never invent an item id
            }
            else if ((m = CA.matcher(text)).matches())
            {
                out.put("kind", "combat_achievement");
                out.put("subject", m.group(3));
                out.put("tier", m.group(2).toLowerCase(java.util.Locale.ROOT));
                out.put("task_id", m.group(1) == null ? null : Long.parseLong(m.group(1)));
            }
            else if ((m = SALVAGE.matcher(text)).matches())
            {
                out.put("kind", "salvage_sort");
                out.put("subject", m.group(3));
                out.put("shipwreck_type", m.group(1).toLowerCase(java.util.Locale.ROOT));
                out.put("quantity", positiveCount(m.group(2)));
            }
            else
            {
                return null;
            }
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
        out.put("source", "game_message");
        return out;
    }

    private static long positiveCount(String value)
    {
        if (!value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)"))
        {
            throw new NumberFormatException();
        }
        long count = Long.parseLong(value.replace(",", ""));
        if (count <= 0)
        {
            throw new NumberFormatException();
        }
        return count;
    }

    Map<String, Object> snapshot(Client client)
    {
        int[] live = client.getVarps();
        int[] vars = live == null ? null : live.clone();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "runelite_account_progress");
        Map<String, Object> slayer = new LinkedHashMap<>();
        Integer target = varp(vars, 395);
        Integer remaining = varp(vars, 394);
        boolean active = remaining != null && remaining > 0;
        slayer.put("task_target_id", target);
        slayer.put("task_remaining", remaining);
        slayer.put("boss_id", active && Integer.valueOf(98).equals(target) ? bit(client, vars, 4723) : null);
        slayer.put("area_id", active ? varp(vars, 2096) : null);
        slayer.put("task_original", active ? varp(vars, 4258) : null);
        out.put("slayer", slayer);
        Map<String, Object> prayers = new LinkedHashMap<>();
        String[] names = {"rigour", "augury", "preserve", "deadeye", "mystic_vigour"};
        int[] ids = {5451, 5452, 5453, 16097, 16098};
        for (int i = 0; i < ids.length; i++)
        {
            Integer value = bit(client, vars, ids[i]);
            prayers.put(names[i], value == null ? null : value == 1);
        }
        // Keep raw quest state: the threshold for chivalry/piety is not runtime-verified here.
        prayers.put("knight_waves_state", bit(client, vars, 3909));
        out.put("prayer_unlocks", prayers);
        Map<String, Object> watson = new LinkedHashMap<>();
        String[] tiers = {"easy", "medium", "hard", "elite"};
        for (int i = 0; i < tiers.length; i++)
        {
            Integer value = bit(client, vars, 5186 + i);
            watson.put(tiers[i], value == null ? null : value == 1);
        }
        out.put("watson_clues", watson);
        out.put("combat_achievement_points", bit(client, vars, 14815));
        out.put("combat_achievement_tasks", caTasks(vars, caCatalog));
        Map<String, Object> log = new LinkedHashMap<>();
        boolean own = Integer.valueOf(0).equals(bit(client, vars, 12061));
        Integer total = own ? varp(vars, 2944) : null;
        Integer obtained = own ? varp(vars, 2943) : null;
        boolean countsKnown = total != null && total > 0 && obtained != null && obtained >= 0 && obtained <= total;
        log.put("game_obtained_count", countsKnown ? obtained : null);
        log.put("game_total_count", countsKnown ? total : null);
        log.put("notification_setting", bit(client, vars, 11959));
        log.put("observed_at", collectionObservedAt);
        log.put("observed_item_count", collectionObservedAt == null ? null : collectionItems.size());
        log.put("items", new TreeMap<>(collectionItems));
        boolean verified = scanComplete && own && countsKnown
            && Objects.equals(scanObtained, obtained) && Objects.equals(scanTotal, total)
            && positiveCollectionCount() == obtained;
        log.put("complete", verified);
        log.put("completeness_basis", verified ? "own_search_scan_unique_count_matches_game" : "unverified");
        log.put("scan_status", scanComplete && !verified ? "counts_changed" : scanStatus);
        log.put("scan_expected_obtained_count", scanObtained);
        log.put("scan_expected_total_count", scanTotal);
        log.put("observed_positive_item_count", collectionObservedAt == null ? null : positiveCollectionCount());
        log.put("scan_started_at_ms", scanStartedAt);
        log.put("scan_verified_at_ms", scanVerifiedAt);
        log.put("source", "script_4100_observed_slots");
        out.put("collection_log", log);
        out.put("ge_history", geHistory.snapshot());
        out.put("storage_recovery", storageRecovery.snapshot(client));
        out.put("recent_signals", new ArrayList<>(signals.values()));
        out.put("signals_evicted", evicted);
        return out;
    }

    /**
     * Raw CA completion words plus game-cache catalogue metadata. completed_by_tier lists only completed ids whose
     * tier the cache catalogue states; the bit position alone is the task id. Unknown word = whole list null.
     */
    static Map<String, Object> caTasks(int[] vars, Map<Integer, String> catalog)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.List<Integer> words = new ArrayList<>();
        boolean known = vars != null;
        for (int id : AccountReadiness.CA_TASK_VARPS)
        {
            Integer w = varp(vars, id);
            known &= w != null;
            words.add(w);
        }
        out.put("words", known ? words : null);
        out.put("source", "varp:CA_TASK_COMPLETED_0..20");
        out.put("catalog_status", catalog == null ? "unavailable" : "loaded");
        if (catalog == null || !known)
        {
            out.put("completed_by_tier", null);
            out.put("catalog_task_totals", null);
            return out;
        }
        Map<String, Object> byTier = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        StringBuilder canon = new StringBuilder();
        for (String tier : AccountReadiness.CA_TIERS)
        {
            byTier.put(tier, new ArrayList<Integer>());
            totals.put(tier, 0);
        }
        for (Map.Entry<Integer, String> e : catalog.entrySet())
        {
            totals.put(e.getValue(), totals.get(e.getValue()) + 1);
            canon.append(e.getKey()).append('=').append(e.getValue()).append(';');
            int w = e.getKey() / 32, b = e.getKey() % 32;
            if ((words.get(w) & (1 << b)) != 0) ((java.util.List<Integer>) byTier.get(e.getValue())).add(e.getKey());
        }
        out.put("catalog_source", "enum:3981..3986,param:1306");
        out.put("catalog_sha256", AccountConnectPlugin.sha256Hex(canon.toString()));
        out.put("completed_by_tier", byTier);
        out.put("catalog_task_totals", totals);
        return out;
    }

    /** Client thread only. Enums 3981..3986 list CA task structs by tier; struct param 1306 is the task id. */
    static Map<Integer, String> loadCaCatalog(Client client)
    {
        try
        {
            Map<Integer, String> catalog = new TreeMap<>();
            for (int t = 0; t < AccountReadiness.CA_TIERS.size(); t++)
            {
                EnumComposition tier = client.getEnum(3981 + t);
                int[] structs = tier == null ? null : tier.getIntVals();
                if (structs == null || structs.length == 0 || structs.length > 4096) return null;
                for (int structId : structs)
                {
                    StructComposition struct = client.getStructComposition(structId);
                    if (struct == null) return null;
                    int task = struct.getIntValue(1306);
                    // A duplicate or out-of-range id means the cache layout changed: refuse the whole catalogue.
                    if (task < 0 || task >= 32 * AccountReadiness.CA_TASK_VARPS.length || catalog.containsKey(task)) return null;
                    catalog.put(task, AccountReadiness.CA_TIERS.get(t));
                }
            }
            return catalog;
        }
        catch (RuntimeException | LinkageError ex)
        {
            return null;
        }
    }

    static Integer varp(int[] vars, int id)
    {
        return vars == null || id < 0 || id >= vars.length ? null : vars[id];
    }

    static Integer bit(Client client, int[] vars, int id)
    {
        try
        {
            VarbitComposition def = client.getVarbit(id);
            // All listed sources use a positive varp index. A missing cache file can return
            // a default 0/0/0 composition instead of null on mixed client versions.
            if (def == null || def.getIndex() <= 0 || varp(vars, def.getIndex()) == null
                || def.getLeastSignificantBit() < 0 || def.getMostSignificantBit() > 31
                || def.getMostSignificantBit() < def.getLeastSignificantBit())
            {
                return null;
            }
            return GameVarsCapture.varbit(vars[def.getIndex()], def.getLeastSignificantBit(), def.getMostSignificantBit());
        }
        catch (RuntimeException ex)
        {
            return null; // missing cache definition on an older client is unknown, never locked/zero
        }
    }
}
