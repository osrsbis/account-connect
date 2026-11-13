package com.osrsbestinslot.export;

import java.util.*;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.CoordinateClue;
import net.runelite.client.plugins.cluescrolls.clues.MapClue;

import static com.osrsbestinslot.export.EventValues.*;
import static com.osrsbestinslot.export.Shape.*;

/**
 * The closed own-account events own_state, own_signal and own_vars: one envelope, one table of kinds and one value
 * shape per kind. Only declared keys leave, as bounded whole numbers, booleans and allowlisted strings, and a check
 * per kind drops a value the capture could never produce. Unknown is never an empty balance or gain. A signal is not
 * a balance delta or proof of activity during a gap; an inferred signal is a sample coincidence, never confirmed
 * gameplay, input idleness, an effect or profit. A clue row adds fixed public library facts, never a drop owner,
 * route or timer; a loot-key row has no victim, provenance, reference price or income.
 */
final class OwnEvent
{
    private OwnEvent() {}
    static final List<String> SKILLS = Arrays.asList("ATTACK", "STRENGTH", "RANGED", "MAGIC", "DEFENCE",
        "HITPOINTS", "PRAYER", "AGILITY", "HERBLORE", "THIEVING", "CRAFTING", "RUNECRAFT", "MINING",
        "SMITHING", "FISHING", "COOKING", "FIREMAKING", "WOODCUTTING", "FLETCHING", "SLAYER", "FARMING",
        "CONSTRUCTION", "HUNTER", "SAILING");
    private static final long SAFE = 9007199254740991L, MAX = Integer.MAX_VALUE;
    private static final long[] INT = num(Integer.MIN_VALUE, MAX), ID = num(1, MAX), NAT = num(0, MAX),
        TIME = num(1000000000000L, SAFE), GP = num(0, SAFE), TOTAL = num(0, 999999999999L);
    private static final Predicate<Object> ROOM = OwnActivityCapture::raidRoom;
    private static final String SYSTEM = "own_client_system", SEQUENCE = "own_client_sequence",
        GAME = "ChatMessage:GAMEMESSAGE:exact_english_projection", SPAM = "ChatMessage:SPAM:exact_english_projection",
        WIDGET = "ClientTick:widget:193.2;own_inventory_possession";
    private static final Map<String,String> CHAT = Map.of(GAME, SYSTEM, SPAM, SYSTEM);
    private static final Map<String,Object> RANGE = obj("start_slot", num(0, 4096), "slot_count", num(0, 4096),
        "coins", GP, "platinum_tokens", GP, "currency_gp_equivalent", GP);

    /** A check beyond the shape on the raw value and its projection; it may complete the projection. */
    private interface Check { void test(Map<?,?> raw, Map<String,Object> value); }

    private static final class Kind
    {
        final String type, kind, wire;
        // Snapshot row source to wire source, for signals; state and vars have one source per type.
        final Map<String,String> sources;
        final List<String> wireSources;
        final boolean direct;
        final Map<String,Object> shape;
        final Check check;

        Kind(String type, String kind, String wire, Map<String,String> sources, Map<String,Object> shape, Check check)
        {
            this.type = type; this.kind = kind; this.wire = wire == null ? kind : wire; this.sources = sources;
            this.shape = shape; this.check = check;
            wireSources = sources == null ? List.of(NAMES.get(type)[3]) : new ArrayList<>(sources.values());
            // Inferred signals and the bank tab ranges (inferred from tab counts) are not direct observations.
            direct = !"bank_tabs".equals(this.wire) && !wireSources.contains(SEQUENCE);
        }
    }

    /** Per event type: schema, kind and value field names, and the wire source (null: per kind). */
    private static final Map<String,String[]> NAMES = Map.of(
        "own_state", new String[]{"state_schema", "state_kind", "state_value", "own_client_state"},
        "own_signal", new String[]{"signal_schema", "signal_kind", "signal_value", null},
        "own_vars", new String[]{"vars_schema", "var_family", "values", "own_client_vars"});
    private static final Map<Object,Kind> BY_KIND = new HashMap<>();
    private static final Map<Object,Map<Object,Kind>> BY_WIRE = new HashMap<>();

    private static void add(String type, String kind, String wire, Map<String,String> sources, Map<String,Object> shape, Check check)
    {
        Kind k = new Kind(type, kind, wire, sources, shape, check);
        BY_KIND.put(kind, k);
        BY_WIRE.computeIfAbsent(type, t -> new HashMap<>()).put(k.wire, k);
    }

    private static void vars(String family, List<String> keys, Check check)
    {
        Map<String,Object> shape = new LinkedHashMap<>();
        for (String key : keys) shape.put(key + "?", INT);
        add("own_vars", family, null, null, shape, check);
    }

    static
    {
        String state = "own_state", signal = "own_signal";
        add(state, "skills.goal_xp", "skill_goals", null, obj("goals", list(24, 24, obj("skill", SKILLS, "start_xp?", INT,
            "end_xp?", INT, "status", List.of("unknown", "unset", "invalid", "configured")))), (p, v) -> {
                Set<Object> seen = new HashSet<>();
                for (Object goal : (List<?>) v.get("goals"))
                {
                    Map<?,?> g = (Map<?,?>) goal;
                    if (!seen.add(g.get("skill")) || !g.get("status").equals(goalStatus((Long) g.get("start_xp"), (Long) g.get("end_xp"))))
                        throw bad();
                }
            });
        add(state, "storage.rune_pouch", "rune_pouch", null, obj("contents_semantics", "client_cached_slots", "complete", false,
            "possession", List.of("inventory_unavailable", "not_observed_carried", "carried")), OwnEvent::pouch);
        add(state, "storage.bank_tab_currency", "bank_tabs", null, obj("basis", "inferred_raw_tab_ranges_same_session_cached_bank",
            "complete", false, "status", List.of("own_bank_packet_unavailable", "clock_or_tick_gap", "ordinary_bank_ui_unavailable",
                "bank_cache_changed_without_packet", "tab_counts_unknown", "tab_counts_exceed_container", "observed_cache_ranges"),
            "container_observed_at_ms?", num(1, SAFE), "tabs", null, "untabbed", null), OwnEvent::tabs);
        add(state, "travel.fairy_destinations", "fairy_favourites", null, obj("favourite_codes_visible", list(10, 10,
            is(c -> c == null || c instanceof String && ((String) c).matches("[ABCD][IJKL][PQRS]"))),
            "authority", "visible_ui_not_unlock_proof"), null);
        add(state, "minigame.mixology_orders", "mixology_orders", null, obj("orders_observed?", list(3, 3,
            is(OwnInterfaceCapture::mixologyCode)), "authority", "visible_ui_not_completion"), null);
        add(state, "travel.spirit_destinations", "spirit_destinations", null, obj("visible_destinations?", list(0, 32,
            obj("destination", is(OwnInterfaceCapture::spiritDestination), "disabled_style_observed", is(b -> b instanceof Boolean))),
            "authority", "classic_ui_not_unlock_proof"), null);
        add(state, "combat.raid_context", "raid_context", null, obj("party_status_raw?", INT, "room_observed?", ROOM), (p, v) -> {
            if (v.get("room_observed") != null && !Long.valueOf(2).equals(v.get("party_status_raw"))) throw bad();
        });
        add(state, "clues.destination", "clue_inventory", null, obj("inventory_map_coordinate_clues", null, "complete", false,
            "source_table", "runelite_1_13_1_primary"), OwnEvent::clues);
        add(state, "loot.key_interface", "loot_key_interface", null, obj("basis", "own_visible_key_cache", "complete", false,
            "new_loot_verified", false, "status", List.of("interface_unavailable", "visible_key_slots")), null);
        add(state, "loot.key_slot", "loot_key_slot", null, obj("basis", "own_visible_key_cache", "complete", false,
            "new_loot_verified", false, "slot", num(0, 4), "status", List.of("not_observed_in_session", "cache_unverified",
                "observed_empty_cache", "cached_items"), "items?", list(0, 128, obj("id", ID, "qty", num(1, 128 * MAX))),
            "container_observed_at_ms?", TIME), OwnEvent::keySlot);

        add(signal, "storage.coal_bag", null, Map.of(GAME, SYSTEM, SPAM, SYSTEM, WIDGET, "own_client_dialog"),
            obj("count_observed", num(0, 54)), null);
        add(signal, "storage.fish_barrel", null, Map.of(WIDGET, "own_client_dialog"), obj("count_observed", num(0, 28)), null);
        add(signal, "storage.grape_barrel", null, CHAT, obj("fill_observed", List.of("empty", "partial", "full")), null);
        add(signal, "combat.chompy_kc", null, CHAT, obj("signal", List.of("notch_observed", "absolute_check"), "total_kills?", TOTAL),
            (p, v) -> { if ("notch_observed".equals(v.get("signal")) != (v.get("total_kills") == null)) throw bad(); });
        add(signal, "minigame.construction_contract", null, CHAT, obj("contracts_absolute", TOTAL, "points_absolute", TOTAL), null);
        add(signal, "farming.compost", null, CHAT, obj("compost_type", List.of("normal", "super", "ultra"),
            "patch_identity", List.of("unknown")), null);
        add(signal, "hunter.rumour", null, CHAT, obj(), OwnEvent::rumour);
        add(signal, "combat.tob_room_death", null, Map.of("ActorDeath:self", "own_client_actor"), obj("party_status_raw", num(2, 2),
            "room_observed?", ROOM, "actor", List.of("self"), "classification", List.of("observed_own_death_not_team_wipe")), null);
        add(signal, "combat.toa_hazards", null, Map.of("own_tile+observed_acid_object", "own_client_scene"), obj("object_id",
            num(45570, 45576), "spawn_tick", NAT, "classification", List.of("own_tile_overlap_not_proven_damage_or_mistake")), null);
        add(signal, "session.stationary_ge", null, Map.of("own_world_tile;System.nanoTime", SEQUENCE), obj("start_tick", NAT,
            "end_tick", NAT, "monotonic_elapsed_ms", num(0, Long.MAX_VALUE), "inference",
            List.of("same_tile_at_sample_times_not_game_aura_or_idle_input")), (p, v) -> {
                long start = (Long) v.get("start_tick"), end = (Long) v.get("end_tick");
                if (end <= start || (Long) v.get("monotonic_elapsed_ms") > (end - start) * 5000) throw bad();
            });
        add(signal, "skills.item_transformation", null, Map.of("GameTick:own_inventory+MagicXP", SEQUENCE), obj("input_item_id", ID,
            "output_item_id", ID, "matched_quantity", num(1, 28 * MAX), "magic_xp_change", ID, "inference",
            List.of("inventory_xp_coincidence_not_confirmed_cast")), (p, v) -> {
                for (int[] pair : OwnActivityMappings.BOLTS)
                    if (pair[0] == (Long) v.get("input_item_id") && pair[1] == (Long) v.get("output_item_id")) return;
                throw bad();
            });
        add(signal, "effects.consumption", null, Map.of("MenuOptionClicked:149.0+own_inventory", SEQUENCE), obj("item_id_before", ID,
            "quantity_before", ID, "item_id_after", num(-1, MAX), "quantity_after", NAT, "action", List.of("eat", "drink", "chew"),
            "intent_tick", NAT, "inference", List.of("own_menu_and_slot_change_not_confirmed_effect_or_cooldown")), (p, v) -> {
                long id = (Long) v.get("item_id_before"), next = (Long) v.get("item_id_after"), left = (Long) v.get("quantity_after");
                if (next == 0 || (next == -1 ? left != 0 : left == 0) || id == next && left >= (Long) v.get("quantity_before")) throw bad();
            });
        add(signal, "clues.owned_ground", null, Map.of("appeared:RuneLite1.13.1:TileItem_ownership_and_server_ticks", "own_client_ground",
            "left_scene:RuneLite1.13.1:tracked_self_ItemDespawned", "own_client_ground"), obj("observation_id", is(id -> id instanceof String
                && ((String) id).matches("[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")),
            "item_id", ID, "quantity", ID, "ownership", List.of("self_at_observation"), "drop_origin", List.of("unverified"),
            "despawn_server_tick?", ID, "clock_scope", List.of("current_client_session_only"), "observation",
            List.of("appeared", "left_scene"), "outcome?", List.of("taken_expired_or_scene_change_unknown")), (p, v) -> {
                int item = (int) (long) (Long) v.get("item_id");
                if ("appeared".equals(v.get("observation")) != (v.get("outcome") == null) || item != ItemID.TRAIL_CLUE_BEGINNER
                    && item != ItemID.TRAIL_CLUE_MASTER && MapClue.forItemId(item) == null && CoordinateClue.forItemId(item) == null)
                    throw bad();
                v.put("observation_id", ((String) v.get("observation_id")).replace("-", ""));
            });
        add(signal, "clues.ground_gap", null, Map.of("bounded_self_ground_clue_capacity", "own_client_capacity"),
            obj("gap", List.of("observation_capacity")), null);
        add(signal, "world.scene_gap", null, Map.of("bounded object observation capacity", "own_client_capacity"),
            obj("gap", List.of("object_capacity")), null);

        // Raw own-player counters and settings; never inferred gains, durations or completion.
        for (Object[] f : OwnActivityMappings.VARBITS)
        {
            List<String> keys = new ArrayList<>();
            for (int i = 2; i < f.length; i += 2) keys.add((String) f[i]);
            vars((String) f[0], keys, null);
        }
        vars("combat.raid_points", List.of("in_raid", "personal_points"), (p, v) -> {
            if (!Long.valueOf(1).equals(v.get("in_raid")) && v.get("personal_points") != null) throw bad();
        });
        vars("combat.special_attack", List.of("energy_raw"), null);
        List<String> chart = new ArrayList<>(), potions = new ArrayList<>(), totems = new ArrayList<>();
        for (int id : OwnActivityMappings.CHARTING) chart.add(String.valueOf(id));
        vars("sailing.charting", chart, null);
        for (int id : OwnPotionStorageCapture.VARPS) potions.add(String.valueOf(id));
        potions.add("unlocked_raw");
        vars("storage.potion_store", potions, null);
        totems.add("research_points_raw");
        for (int site = 1; site <= OwnActivityMappings.TOTEMS.length; site++)
            for (String field : OwnActivityMappings.TOTEM_FIELDS) totems.add("site_" + site + "." + field);
        vars("minigame.totem_state", totems, null);
        vars("sailing.port_tasks", List.of("5197", "5198", "5199", "5200", "5201", "5202", "completed_total_raw",
            "bounty_item_counter_raw", "extra_slots_raw", "completed_today_raw"), null);
    }

    private static IllegalArgumentException bad() { return new IllegalArgumentException(); }

    /** The same rule the capture uses for a goal's start and end experience. */
    static String goalStatus(Number start, Number end)
    {
        if (start == null || end == null) return "unknown";
        long s = start.longValue(), e = end.longValue();
        return e == 0 ? "unset" : s < 0 || e <= s || e > 200000000 ? "invalid" : "configured";
    }

    /** Event stream of a snapshot kind for the hand-off counters: 1 state, 2 signal, 3 vars, 0 none. */
    static int stream(Object kind)
    {
        Kind k = BY_KIND.get(kind);
        return k == null ? 0 : List.of("own_state", "own_signal", "own_vars").indexOf(k.type) + 1;
    }

    static boolean handles(String type) { return NAMES.containsKey(type); }

    /** The delivery health family of an event of the three types ("own_vars_" and its family), or null. */
    static String family(Map<String,Object> event)
    {
        Object type = event.get("type");
        Map<Object,Kind> kinds = BY_WIRE.get(type);
        Object kind = kinds == null ? null : event.get(NAMES.get(type)[1]);
        return kinds != null && kinds.containsKey(kind) ? type + "_" + kind : null;
    }

    /** The closed value of one wire kind of one event type, or null. */
    static Map<String,Object> value(String type, Object kind, Object input)
    {
        Map<Object,Kind> kinds = BY_WIRE.get(type);
        Kind k = kinds == null ? null : kinds.get(kind);
        return k == null ? null : value(k, input);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> value(Kind k, Object input)
    {
        try
        {
            Map<String,Object> value = (Map<String,Object>) project(k.shape, input);
            if (k.check != null) k.check.test((Map<?,?>) input, value);
            return value;
        }
        catch (IllegalArgumentException invalid) { return null; }
    }

    /** The closed wire body of one event of the three types, without its "type"; null when anything does not fit. */
    static Map<String,Object> wire(Map<String,Object> in)
    {
        String type = String.valueOf(in.get("type"));
        String[] n = NAMES.get(type);
        Kind k = n == null ? null : BY_WIRE.get(type).get(in.get(n[1]));
        if (k == null) return null;
        try
        {
            boolean signal = n[3] == null;
            Object session = in.get("gameplay_session"), observation = choice(in.get("observation"), signal
                ? List.of(k.direct ? "observed_signal" : "inferred_observation") : List.of("baseline", "absolute_update"));
            if (number(in.get(n[0]), 1, 1) != 1 || !Boolean.valueOf(k.direct).equals(in.get("direct"))
                || !(session instanceof String) || !((String) session).matches("[a-f0-9]{32}")) return null;
            Map<String,Object> value = value(k, in.get(n[2]));
            if (value == null) return null;
            Map<String,Object> out = Fields.of(n[0], 1, n[1], k.wire, "source", choice(in.get("source"), k.wireSources));
            out.putAll(signal ? Fields.of("direct", k.direct, "observation", observation, "gameplay_session", session)
                : Fields.of("gameplay_session", session, "direct", k.direct, "observation", observation));
            long tick = number(in.get("source_tick"), 0, MAX), at = number(in.get("observed_at_ms"), 1000000000000L, SAFE);
            out.putAll(Fields.of("source_sequence", number(in.get("source_sequence"), 1, SAFE), "source_tick", tick,
                "observed_at_ms", at, n[2], value));
            // A time inside a value is checked against the event's own tick and time wherever it appears.
            Long seen = (Long) value.get("container_observed_at_ms"), spawn = (Long) value.get("spawn_tick"),
                end = (Long) value.get("end_tick"), intent = (Long) value.get("intent_tick");
            if (seen != null && seen > at || spawn != null && spawn >= tick || end != null && end + 1 != tick
                || intent != null && (tick < intent || tick - intent > 2)) return null;
            return out;
        }
        catch (IllegalArgumentException invalid) { return null; }
    }

    /** The event for one snapshot row, with its "type"; null when the row has no event or does not fit. */
    static Map<String,Object> from(String session, Map<String,Object> row)
    {
        Kind k = BY_KIND.get(row.get("kind"));
        if (k == null || session == null || !session.matches("[a-f0-9-]{36}") || !(row.get("value") instanceof Map)) return null;
        String[] n = NAMES.get(k.type);
        Map<?,?> value = (Map<?,?>) row.get("value");
        Object shaped = value, source = n[3], direct = "own_state".equals(k.type) ? k.direct : row.get("direct");
        Object rowSource = row.get("source");
        if (source == null && rowSource instanceof String)	// Map.of rejects a null key: a row without a source is dropped
            source = k.sources.get(k.kind.equals("clues.owned_ground") ? value.get("observation") + ":" + rowSource : rowSource);
        if (source == null) return null;
        try
        {
            if ("skill_goals".equals(k.wire))
            {
                List<Object> goals = new ArrayList<>();
                for (String skill : SKILLS)
                {
                    Map<Object,Object> goal = new LinkedHashMap<>(map(value.get(skill)));
                    goal.put("skill", skill);
                    goals.add(goal);
                }
                shaped = Map.of("goals", goals);
            }
            else if ("own_vars".equals(k.type))
            {
                // The wire is flat: a numeric potion or port key comes from its raw group, a totem site key from its site.
                String group = k.kind.equals("storage.potion_store") ? "storage_values_raw"
                    : k.kind.equals("sailing.port_tasks") ? "packed_slots_raw" : null;
                Map<String,Object> flat = new LinkedHashMap<>();
                for (String key : k.shape.keySet())
                {
                    String name = key.substring(0, key.length() - 1), field = name;
                    int dot = name.indexOf('.');
                    Map<?,?> from = value;
                    if (dot > 0) { from = group(value, name.substring(0, dot)); field = name.substring(dot + 1); }
                    else if (group != null && name.matches("[0-9]+")) from = group(value, group);
                    flat.put(name, from.get(field));
                }
                shaped = flat;
            }
        }
        catch (IllegalArgumentException invalid) { return null; }
        Map<String,Object> out = wire(Fields.of("type", k.type, n[0], 1, n[1], k.wire, "source", source,
            "gameplay_session", session.replace("-", ""), "direct", direct, "observation", n[3] != null ? value.get("observation")
                : k.direct ? "observed_signal" : "inferred_observation", "source_sequence", row.get("sequence"),
            "source_tick", row.get("tick"), "observed_at_ms", row.get("observed_at_ms"), n[2], shaped));
        if (out != null) out.put("type", k.type);
        return out;
    }

    private static Map<?,?> group(Map<?,?> value, String key)
    {
        return value.get(key) instanceof Map ? (Map<?,?>) value.get(key) : Collections.emptyMap();
    }

    private static void pouch(Map<?,?> p, Map<String,Object> v)
    {
        if (!"carried".equals(v.get("possession"))) return;
        List<?> ids = (List<?>) project(list(1, 4, ID), p.get("pouch_item_ids"));
        if (!List.of(12791L, 24416L, 27281L, 27509L).containsAll(ids) || new HashSet<>(ids).size() != ids.size()) throw bad();
        List<?> slots = (List<?>) project(list(6, 6, obj("slot", num(0, 5), "type_raw?", INT, "amount?", INT, "status",
            List.of("unavailable", "inconsistent_cache", "observed_empty", "mapping_unknown", "observed_cached"))), p.get("slots"));
        List<?> raw = (List<?>) p.get("slots");
        Map<Long,Long> totals = new TreeMap<>();
        boolean decoded = true;
        for (int i = 0; i < 6; i++)
        {
            @SuppressWarnings("unchecked") Map<String,Object> s = (Map<String,Object>) slots.get(i);
            Object item = ((Map<?,?>) raw.get(i)).get("item");
            Long type = (Long) s.get("type_raw"), amount = (Long) s.get("amount");
            String status = type == null || amount == null ? "unavailable"
                : type < 0 || amount < 0 || type == 0 && amount > 0 ? "inconsistent_cache"
                : amount == 0 ? "observed_empty" : item == null ? "mapping_unknown" : "observed_cached";
            if (!Long.valueOf(i).equals(s.get("slot")) || !status.equals(s.get("status"))) throw bad();
            if (status.equals("observed_cached"))
            {
                long id = number(item, 1, MAX);
                s.put("item", id);
                totals.merge(id, amount, Long::sum);
            }
            else if (!status.equals("observed_empty")) decoded = false;
        }
        List<Map<String,Object>> items = new ArrayList<>();
        totals.forEach((id, qty) -> items.add(Map.of("id", id, "qty", qty)));
        v.put("pouch_item_ids", ids); v.put("slots", slots); v.put("observed_items", items); v.put("all_slots_decoded", decoded);
    }

    private static void tabs(Map<?,?> p, Map<String,Object> v)
    {
        if (!"observed_cache_ranges".equals(v.get("status"))) return;
        Object raw = p.get("tabs");
        if (v.get("container_observed_at_ms") == null || !(raw instanceof List) || ((List<?>) raw).size() != 9) throw bad();
        List<Map<String,Object>> tabs = new ArrayList<>();
        long start = 0;
        for (int i = 0; i < 9; i++)
        {
            Map<?,?> tab = map(((List<?>) raw).get(i));
            if (number(tab.get("tab"), 1, 9) != i + 1) throw bad();
            Map<String,Object> range = range(tab, start);
            start += (Long) range.get("slot_count");
            range.put("tab", i + 1);
            tabs.add(range);
        }
        v.put("tabs", tabs); v.put("untabbed", range(p.get("untabbed"), start));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> range(Object raw, long start)
    {
        Map<String,Object> r = (Map<String,Object>) project(RANGE, raw);
        long count = (Long) r.get("slot_count"), coins = (Long) r.get("coins"), platinum = (Long) r.get("platinum_tokens");
        if ((Long) r.get("start_slot") != start || count > 4096 - start || coins > count * MAX || platinum > count * MAX
            || coins + platinum > count * MAX || (Long) r.get("currency_gp_equivalent") != coins + platinum * 1000) throw bad();
        return r;
    }

    private static void clues(Map<?,?> p, Map<String,Object> v)
    {
        Object raw = p.get("inventory_map_coordinate_clues");
        if (raw == null) return;
        Map<?,?> entries = map(raw);
        if (entries.size() > 28) throw bad();
        Map<String,Object> clues = new TreeMap<>();
        for (Map.Entry<?,?> e : entries.entrySet())
        {
            if (!(e.getKey() instanceof String) || !((String) e.getKey()).matches("[1-9][0-9]{0,5}")) throw bad();
            int id = Integer.parseInt((String) e.getKey());
            MapClue map = MapClue.forItemId(id);
            CoordinateClue coordinate = map == null ? CoordinateClue.forItemId(id) : null;
            if (map == null && coordinate == null) throw bad();
            WorldPoint point = map != null ? map.getLocation(null) : coordinate.getLocation(null);
            String q = "library_base_location_not_resolved_quest_alternative";
            Map<String,Object> clue = Map.of("kind", map != null ? "map" : "coordinate", "x", (long) point.getX(),
                "y", (long) point.getY(), "plane", (long) point.getPlane(), "qualification", q);
            if (!clue.equals(project(obj("kind", List.of("map", "coordinate"), "x", num(0, 16383), "y", num(0, 16383),
                "plane", num(0, 3), "qualification", List.of(q)), e.getValue()))) throw bad();
            clues.put((String) e.getKey(), clue);
        }
        v.put("inventory_map_coordinate_clues", clues);
    }

    private static void keySlot(Map<?,?> p, Map<String,Object> v)
    {
        Object status = v.get("status"), items = v.get("items"), at = v.get("container_observed_at_ms");
        if ("not_observed_in_session".equals(status) || "cache_unverified".equals(status))
        {
            if (items != null || at != null) throw bad();
            return;
        }
        if (items == null || at == null || ((List<?>) items).isEmpty() != "observed_empty_cache".equals(status)) throw bad();
        long previous = 0, total = 0;
        for (Object item : (List<?>) items)
        {
            long id = (Long) ((Map<?,?>) item).get("id");
            total += (Long) ((Map<?,?>) item).get("qty");
            if (id <= previous || total > 128 * MAX) throw bad();
            previous = id;
        }
    }

    private static void rumour(Map<?,?> p, Map<String,Object> v)
    {
        if (p.containsKey("whistle_charges_observed"))
        {
            if (p.get("signal") != null || p.get("target_observed") != null) throw bad();
            v.put("whistle_charges_observed", number(p.get("whistle_charges_observed"), 0, 999999999999L));
            return;
        }
        String signal = choice(p.get("signal"), List.of("whistle_target", "whistle_no_active"));
        Object target = p.get("target_observed");
        if (!Boolean.FALSE.equals(p.get("turn_in_verified"))
            || (signal.equals("whistle_target") ? !OwnActivityCapture.rumourTargetCode(target) : target != null)) throw bad();
        v.putAll(Fields.of("signal", signal, "target_observed", target, "turn_in_verified", false));
    }
}
