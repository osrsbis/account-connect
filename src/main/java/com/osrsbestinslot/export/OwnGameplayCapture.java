package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.RuneScapeProfileType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntToLongFunction;
import java.util.function.Predicate;

/**
 * Bounded, passive first-party observations. No scene sweep, player target reads, raw chat, actions,
 * plugin configuration or peer messages. The caller must hold the separate current-token grant.
 * This recent-observation snapshot is not a durable event ledger or proof of a complete encounter.
 */
final class OwnGameplayCapture
{
    // Extra block bounded below the existing 512KiB complete snapshot request ceiling.
    static final int MAX_OBSERVATIONS = 256;
    static final int MAX_OBSERVATION_BYTES = 96 * 1024;
    private static final int MAX_TARGETS = 16;
    // RuneLite API 1.13.1 constants, checked against the audited acquisition sources.
    private static final Skill[] GOAL_SKILLS = {Skill.ATTACK, Skill.STRENGTH, Skill.RANGED,
        Skill.MAGIC, Skill.DEFENCE, Skill.HITPOINTS, Skill.PRAYER, Skill.AGILITY, Skill.HERBLORE,
        Skill.THIEVING, Skill.CRAFTING, Skill.RUNECRAFT, Skill.MINING, Skill.SMITHING, Skill.FISHING,
        Skill.COOKING, Skill.FIREMAKING, Skill.WOODCUTTING, Skill.FLETCHING, Skill.SLAYER,
        Skill.FARMING, Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING};
    private String identity, session, profile;
    private Integer accountType;
    private int settledAt, lastTick = -1, splatTick = -1, evicted;
    private long sequence, lastReadAt;
    private String gapReason = "not_observed";
    private final Deque<Map<String,Object>> observations = new ArrayDeque<>();
    private final Deque<Integer> observationBytes = new ArrayDeque<>();
    private int bufferedBytes;
    private final Predicate<Map<String,Object>> eventSink;
    private long combatObserved, combatHandedOff, combatNotHandedOff;
    private long stateObserved, stateHandedOff, stateNotHandedOff;
    private long varsObserved, varsHandedOff, varsNotHandedOff;
    private long signalsObserved,signalsHandedOff,signalsNotHandedOff;
    OwnGameplayCapture() { this(row -> false); }
    OwnGameplayCapture(Predicate<Map<String,Object>> sink) { this(sink,id -> -1); }
    OwnGameplayCapture(Predicate<Map<String,Object>> sink,IntToLongFunction prices)
    { eventSink = sink;lootKeys=new OwnLootKeysCapture(this,prices); }
    private final Map<String,Map<String,Object>> states = new LinkedHashMap<>();
    final OwnInterfaceCapture interfaces = new OwnInterfaceCapture(this);
    final OwnSequenceCapture sequences = new OwnSequenceCapture(this);
    final OwnSceneCapture scene = new OwnSceneCapture(this);
    final OwnConsumptionCapture consumption = new OwnConsumptionCapture(this);
    final OwnActivityCapture activities = new OwnActivityCapture(this);
    final OwnGroundClueCapture groundClues = new OwnGroundClueCapture(this);
    final OwnLootKeysCapture lootKeys;
    final OwnBankTabsCapture bankTabs = new OwnBankTabsCapture(this);
    private final Map<NPC,Target> targets = new IdentityHashMap<>();
    private final Set<Hitsplat> splats = Collections.newSetFromMap(new IdentityHashMap<>());
    // NPCs whose ActorDeath was observed on deathTick. In the real client the killing splat can arrive in the
    // same tick AFTER ActorDeath; that hit belongs to the dying target, not to a new target.
    private final Map<NPC,Target> deaths = new IdentityHashMap<>();
    private int deathTick = -1;
    private List<long[]> wornAtHit;

    private static final class Target
    {
        final String id = UUID.randomUUID().toString();
        final int npcId, firstTick;
        Target(NPC npc, int tick) { npcId = npc.getId(); firstTick = tick; }
    }

    void reset()
    {
        identity = session = profile = null;
        accountType = null;
        observations.clear(); observationBytes.clear(); bufferedBytes = 0;
        states.clear(); targets.clear(); splats.clear(); deaths.clear(); deathTick = -1; activities.reset(); interfaces.reset(); sequences.reset(); scene.reset(); consumption.reset(); groundClues.reset(); bankTabs.reset(); lootKeys.reset();
        lastTick = splatTick = -1; sequence = lastReadAt = 0; evicted = 0;
        combatObserved = combatHandedOff = combatNotHandedOff = 0;
        stateObserved = stateHandedOff = stateNotHandedOff = 0;
        varsObserved = varsHandedOff = varsNotHandedOff = 0;
        signalsObserved=signalsHandedOff=signalsNotHandedOff=0;
        gapReason = "not_observed";
    }

    boolean ready(Client client, String grantIdentity)
    {
        if (client != null && client.getGameState() == GameState.LOADING)
        {
            gap(); return false;
        }
        if (client == null || client.getGameState() != GameState.LOGGED_IN
            || client.getLocalPlayer() == null || client.getAccountHash() == 0
            || client.getAccountHash() == -1 || client.getRealSkillLevel(Skill.HITPOINTS) <= 0)
        {
            reset(); return false;
        }
        String nextProfile = RuneScapeProfileType.getCurrent(client).name();
        Integer nextType = AccountProgressCapture.bit(client, client.getVarps(), 1777);
        String next = grantIdentity + ":" + client.getAccountHash() + ":" + nextProfile + ":" + nextType;
        if (!next.equals(identity))
        {
            reset(); identity = next; session = UUID.randomUUID().toString(); profile = nextProfile;
            accountType = nextType; settledAt = client.getTickCount();
            gapReason = "session_baseline";
            return false;
        }
        if (client.getTickCount() < settledAt) { reset(); return false; }
        return client.getTickCount() - settledAt >= 2;
    }

    void gap()
    {
        // A scene reload cannot connect an NPC index or an absolute state to the previous scene.
        targets.clear(); splats.clear(); deaths.clear(); deathTick = -1; states.clear(); activities.reset(); interfaces.reset(); sequences.reset(); scene.reset(); consumption.reset(); groundClues.reset(); bankTabs.reset(); lootKeys.reset();
        gapReason = "scene_gap"; lastTick = splatTick = -1;
    }

    void append(String kind, String source, int tick, long at, Map<String,Object> value)
    {
        append(kind,source,tick,at,value,true);
    }

    void append(String kind, String source, int tick, long at, Map<String,Object> value, boolean direct)
    {
        append(kind,source,tick,at,value,direct,true);
    }

    // A large source snapshot already retains these values. Its smaller journal parts must not
    // duplicate the same arrays in snapshot history or expose handoff as an archival receipt.
    void journalState(String kind,Map<String,Object> value,String observation,int tick,long at)
    {
        Map<String,Object> change=new LinkedHashMap<>(value);change.put("observation",observation);
        append(kind,"own_key_packet+visible_fixed_chest",tick,at,change,true,false);
    }

    /**
     * UTF-8 byte length of the JSON the snapshot would carry for this value, without creating a Gson instance (the
     * Plugin Hub refuses fresh Gson). Matches Gson's default output for the shapes a row holds: maps with string keys,
     * lists, strings, numbers, booleans and nulls (null map values are omitted, as Gson does).
     */
    static int jsonBytes(Object v)
    {
        if (v == null) return 4;
        if (v instanceof Boolean) return ((Boolean) v) ? 4 : 5;
        if (v instanceof Number) return numberText((Number) v).length();
        if (v instanceof CharSequence || v instanceof Character) return stringBytes(v.toString());
        if (v instanceof Map)
        {
            int n = 2, members = 0;
            for (Map.Entry<?,?> e : ((Map<?,?>) v).entrySet())
            {
                if (e.getValue() == null) continue;
                n += stringBytes(String.valueOf(e.getKey())) + 1 + jsonBytes(e.getValue());
                members++;
            }
            return n + Math.max(0, members - 1);
        }
        if (v instanceof Iterable)
        {
            int n = 2, members = 0;
            for (Object o : (Iterable<?>) v) { n += jsonBytes(o); members++; }
            return n + Math.max(0, members - 1);
        }
        if (v instanceof int[]) { List<Integer> l = new ArrayList<>(); for (int i : (int[]) v) l.add(i); return jsonBytes(l); }
        if (v instanceof long[]) { List<Long> l = new ArrayList<>(); for (long i : (long[]) v) l.add(i); return jsonBytes(l); }
        if (v instanceof Object[]) return jsonBytes(Arrays.asList((Object[]) v));
        if (v instanceof Enum) return stringBytes(((Enum<?>) v).name());
        return stringBytes(v.toString());
    }

    private static String numberText(Number n)
    {
        if (n instanceof Double || n instanceof Float)
        {
            double d = n.doubleValue();
            if (n instanceof Float) return Float.toString(n.floatValue());
            return Double.toString(d);
        }
        return n.toString();
    }

    /** Bytes of a Gson-escaped JSON string, quotes included. Gson escapes <, >, &, = and ' as a six-character escape by default. */
    private static int stringBytes(String s)
    {
        int n = 2;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '"': case '\\': case '\n': case '\r': case '\t': case '\b': case '\f': n += 2; break;
                case '<': case '>': case '&': case '=': case '\'': n += 6; break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') n += 6;
                    else if (c < 0x80) n += 1;
                    else if (c < 0x800) n += 2;
                    else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) { n += 4; i++; }
                    else n += 3;
            }
        }
        return n;
    }

    private void append(String kind,String source,int tick,long at,Map<String,Object> value,boolean direct,boolean keepSnapshot)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sequence", ++sequence); row.put("event_id", session + ":" + sequence);
        row.put("kind", kind); row.put("source", source); row.put("tick", tick);
        row.put("observed_at_ms", at); row.put("direct", direct); row.put("value", value);
        int bytes = jsonBytes(row) + 1;
        if (bytes > MAX_OBSERVATION_BYTES) { evicted++; return; }
        if(keepSnapshot)
        {
            while (observations.size() >= MAX_OBSERVATIONS || bufferedBytes + bytes > MAX_OBSERVATION_BYTES)
            {
                observations.removeFirst(); bufferedBytes -= observationBytes.removeFirst(); evicted++;
            }
            observations.addLast(row); observationBytes.addLast(bytes); bufferedBytes += bytes;
        }
        Map<String,Object> combat = OwnCombatEvent.from(session, row);
        if (combat != null && wornAtHit != null && "combat_hit".equals(combat.get("type"))) combat.put("worn", wornAtHit);
        if (combat != null)
        {
            combatObserved++;
            boolean handedOff = false;
            try { handedOff = eventSink.test(combat); }
            catch (RuntimeException failure) { /* The snapshot still contains the observation. */ }
            if (handedOff) combatHandedOff++; else combatNotHandedOff++;
        }
        if (OwnStateEvent.supports(kind))
        {
            stateObserved++; boolean handedOff = false;
            Map<String,Object> event = OwnStateEvent.from(session, row);
            try { handedOff = event != null && eventSink.test(event); }
            catch (RuntimeException failure) { /* snapshot remains; a handoff is not a durable ACK */ }
            if (handedOff) stateHandedOff++; else stateNotHandedOff++;
        }
        if(OwnSignalEvent.supports(kind))
        {
            signalsObserved++;boolean handedOff=false;Map<String,Object> event=OwnSignalEvent.from(session,row);
            try { handedOff=event!=null&&eventSink.test(event); }
            catch(RuntimeException failure) { /* Bounded snapshot remains; no durable ACK claim. */ }
            if(handedOff)signalsHandedOff++;else signalsNotHandedOff++;
        }
        if (OwnVarsEvent.supports(kind))
        {
            varsObserved++; boolean handedOff = false;
            Map<String,Object> event = OwnVarsEvent.from(session,row);
            try { handedOff = event != null && eventSink.test(event); }
            catch (RuntimeException failure) { /* Retain the snapshot and count the missing handoff. */ }
            if (handedOff) varsHandedOff++; else varsNotHandedOff++;
        }
    }

    void state(String kind, String source, Map<String,Object> value, int tick, long at)
    {
        state(kind, source, value, tick, at, true);
    }

    void state(String kind, String source, Map<String,Object> value, int tick, long at, boolean direct)
    {
        Map<String,Object> previous = states.get(kind);
        if (previous == null || !Objects.equals(previous.get("value"), value))
        {
            Map<String,Object> sample = new LinkedHashMap<>();
            sample.put("value", value); sample.put("observed_at_ms", at);
            sample.put("source", source); sample.put("tick", tick);
            states.put(kind, sample);
            Map<String,Object> change = new LinkedHashMap<>(value);
            change.put("observation", previous == null ? "baseline" : "absolute_update");
            append(kind, source, tick, at, change, direct);
        }
    }

    void poll(Client client, long at)
    {
        int tick = client.getTickCount();
        if (tick == lastTick) return;
        if (lastTick >= 0 && tick < lastTick) gap();
        lastTick = tick; lastReadAt = at;
        int[] live = client.getVarps(), vars = live == null ? null : live.clone();
        Map<String,Object> barrows = new LinkedHashMap<>();
        String[] brothers = {"ahrim", "dharok", "guthan", "karil", "torag", "verac"};
        for (int i = 0; i < brothers.length; i++) barrows.put(brothers[i], AccountProgressCapture.bit(client, vars, 457+i));
        barrows.put("reward_potential_raw", AccountProgressCapture.bit(client, vars, 463));
        barrows.put("npcs_slain_raw", AccountProgressCapture.bit(client, vars, 464));
        state("combat.barrows_state", "varbits:457-464", barrows, tick, at);

        Map<String,Object> raids = new LinkedHashMap<>();
        Integer inRaid = AccountProgressCapture.bit(client, vars, 5432);
        raids.put("in_raid", inRaid);
        raids.put("personal_points", Integer.valueOf(1).equals(inRaid) ? AccountProgressCapture.varp(vars, 4609) : null);
        state("combat.raid_points", "varbit:5432,varp:4609", raids, tick, at);

        Map<String,Object> surge = new LinkedHashMap<>();
        surge.put("timer_raw", AccountProgressCapture.bit(client, vars, 16270));
        state("effects.surge", "varbit:16270", surge, tick, at);
        Map<String,Object> charge = Fields.of(
            "active_raw", AccountProgressCapture.bit(client, vars, 12411),
            "cooldown_raw", AccountProgressCapture.bit(client, vars, 12138));
        state("effects.death_charge", "varbits:12411,12138", charge, tick, at);

        Map<String,Object> escape = Fields.of(
            "afk_mode_raw", AccountProgressCapture.bit(client, vars, 14838),
            "afk_delay_raw", AccountProgressCapture.bit(client, vars, 14849),
            "ring_of_life_raw", AccountProgressCapture.bit(client, vars, 14857));
        // Settings are not possession, server idle time, or a promise that teleport will occur.
        state("effects.escape_crystal", "varbits:14838,14849,14857", escape, tick, at);

        Map<String,Object> arena = Fields.of(
            "entry_paid_raw", AccountProgressCapture.bit(client, vars, 5964),
            "cooldown_raw", AccountProgressCapture.bit(client, vars, 10732),
            "ticket_available_raw", AccountProgressCapture.bit(client, vars, 5962));
        // Ticket availability is not lifetime currency and no path/action is inferred.
        state("minigame.arena_state", "varbits:5964,10732,5962", arena, tick, at);

        Map<String,Object> goals = new LinkedHashMap<>();
        for (int i = 0; i < GOAL_SKILLS.length; i++)
        {
            // Sailing is outside the original contiguous 23-skill ranges. Pinned1.13.1 XpTracker uses these named varps.
            Integer start = AccountProgressCapture.varp(vars, GOAL_SKILLS[i] == Skill.SAILING ? VarPlayerID.XPDROPS_SAILING_START : 1229+i);
            Integer end = AccountProgressCapture.varp(vars, GOAL_SKILLS[i] == Skill.SAILING ? VarPlayerID.XPDROPS_SAILING_END : 1253+i);
            Map<String,Object> goal = new LinkedHashMap<>();
            goal.put("start_xp", start); goal.put("end_xp", end);
            // Preserve raw values; no division, fictional gain, or inferred completion.
            goal.put("status", start == null || end == null ? "unknown" : end == 0 ? "unset"
                : start < 0 || end <= start || end > 200000000 ? "invalid" : "configured");
            goals.put(GOAL_SKILLS[i].name(), goal);
        }
        state("skills.goal_xp", "varps:1229-1251,1253-1275,4964,4965", goals, tick, at);
        state("storage.rune_pouch", "own_inventory,varbits,enum:982", OwnRunePouchCapture.read(client,vars), tick, at);
        activities.poll(client, vars, at);
        lootKeys.poll(client,at);
        interfaces.poll(client, at);
        sequences.poll(client, at, System.nanoTime());
        scene.poll(client, at);
        consumption.poll(client, at);
    }

    void hit(Client client, Actor actor, Hitsplat splat, long at)
    {
        Player self = client.getLocalPlayer();
        if (self == null || actor == null || splat == null || splat.getAmount() < 0) return;
        boolean incoming = actor == self;
        // Never inspect another player or attribute another player's hit to us.
        if (!incoming && (!(actor instanceof NPC) || !splat.isMine())) return;
        int tick = client.getTickCount();
        if (tick != splatTick) { splats.clear(); splatTick = tick; }
        if (splats.size() >= MAX_OBSERVATIONS) { evicted++; return; }
        if (!splats.add(splat)) return; // identical callback object only; equal amounts can be two hits
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("direction", incoming ? "received" : "dealt_to_npc");
        value.put("amount", splat.getAmount()); value.put("hitsplat_type", splat.getHitsplatType());
        if (!incoming)
        {
            NPC npc = (NPC) actor;
            if (tick != deathTick) deaths.clear();
            boolean dying = deathTick == tick && deaths.containsKey(npc);
            Target target = dying ? deaths.get(npc) : targets.get(npc);
            boolean deathWithoutEarlierHit = dying && target == null;
            boolean fresh = target == null;
            if (fresh)
            {
                if (!dying && targets.size() >= MAX_TARGETS) { targets.clear(); gapReason = "target_capacity_gap"; }
                target = new Target(npc, tick);
                if (dying) deaths.put(npc, target); else targets.put(npc, target);
            }
            value.put("npc_id", npc.getId()); value.put("target_observation_id", target.id);
            // Worn item IDs and quantities at the first own hit on a new target, for the combat_hit event only;
            // the snapshot row stays as it was. Absent when unreadable.
            wornAtHit = fresh ? KillActivity.itemPairs(client.getItemContainer(InventoryID.EQUIPMENT)) : null;
            try { append("combat.own_hits", "HitsplatApplied", tick, at, value); }
            finally { wornAtHit = null; }
            // The NPC died this tick before our only observed hit: record its death for that new target.
            if (deathWithoutEarlierHit) npcEnd(target, true, "actor_death_before_own_hit_same_tick", tick, at);
            return;
        }
        append("combat.own_hits", "HitsplatApplied", tick, at, value);
    }

    void endNpc(Client client, NPC npc, boolean death, long at)
    {
        int tick = client.getTickCount();
        Target target = targets.remove(npc);
        if (death)
        {
            // Remember the death for the rest of this tick, so a late killing splat joins this target.
            if (tick != deathTick) { deaths.clear(); deathTick = tick; }
            if (deaths.size() < MAX_TARGETS) deaths.put(npc, target);
        }
        else deaths.remove(npc);
        if (target == null) return;
        npcEnd(target, death, null, tick, at);
    }

    private void npcEnd(Target target, boolean death, String order, int tick, long at)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("npc_id", target.npcId); value.put("target_observation_id", target.id);
        value.put("first_own_hit_tick", target.firstTick);
        value.put("end", death ? "observed_death" : "despawn_not_death");
        value.put("kill_credit", "unknown");
        if (order != null) value.put("event_order", order);
        // Observation interval only: spawn, encounter start, ownership and kill time are not implied.
        append("combat.npc_lifecycle", death ? "ActorDeath" : "NpcDespawned", tick, at, value);
    }

    Map<String,Object> snapshot()
    {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("schema_v", 1); out.put("decoder_version", "own-gameplay-v2");
        out.put("collector_session", session); out.put("profile_type", profile); out.put("account_type_raw", accountType);
        out.put("last_authoritative_read_ms", lastReadAt == 0 ? null : lastReadAt);
        out.put("completeness_scope", "bounded_recent_observations"); out.put("complete", false);
        out.put("gap_reason", evicted > 0 ? "buffer_eviction" : gapReason); out.put("evicted", evicted);
        out.put("buffered_bytes", bufferedBytes);
        // Handoff is not disk durability or server ACK. Scope is this capture session only.
        out.put("combat_events_observed", combatObserved);out.put("combat_events_handed_off", combatHandedOff);
        out.put("combat_events_not_handed_off", combatNotHandedOff);
        out.put("state_events_observed", stateObserved); out.put("state_events_handed_off", stateHandedOff);
        out.put("state_events_not_handed_off", stateNotHandedOff);
        out.put("signal_events_observed",signalsObserved);out.put("signal_events_handed_off",signalsHandedOff);
        out.put("signal_events_not_handed_off",signalsNotHandedOff);
        out.put("vars_events_observed",varsObserved); out.put("vars_events_handed_off",varsHandedOff);
        out.put("vars_events_not_handed_off",varsNotHandedOff);
        out.put("states", new LinkedHashMap<>(states)); out.put("observations", new ArrayList<>(observations));
        return out;
    }
}
