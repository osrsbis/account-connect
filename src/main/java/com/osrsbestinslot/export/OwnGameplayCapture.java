package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.RuneScapeProfileType;

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
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();
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
    private final java.util.function.Predicate<Map<String,Object>> eventSink;
    private long combatObserved, combatHandedOff, combatNotHandedOff;
    private long stateObserved, stateHandedOff, stateNotHandedOff;
    private long varsObserved, varsHandedOff, varsNotHandedOff;
    private long signalsObserved,signalsHandedOff,signalsNotHandedOff;
    OwnGameplayCapture() { this(row -> false); }
    OwnGameplayCapture(java.util.function.Predicate<Map<String,Object>> sink) { this(sink,id -> -1); }
    OwnGameplayCapture(java.util.function.Predicate<Map<String,Object>> sink,java.util.function.IntToLongFunction prices)
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
    /**
     * Worn-equipment revisions for this capture session (loadout.revision.v1), read in-process by encounter code.
     * Null until the first settled poll. Generation = session plus a restart count for a tick-counter reset.
     */
    LoadoutRevision loadouts;
    private int loadoutRestarts;
    // Keep this across scene gaps: gap() clears lastTick before a resumed poll can detect a reset.
    private int loadoutLastTick = -1;
    private final Map<NPC,Target> targets = new IdentityHashMap<>();
    private final Set<Hitsplat> splats = Collections.newSetFromMap(new IdentityHashMap<>());
    // NPCs whose ActorDeath was observed on deathTick. In the real client the killing splat can arrive in the
    // same tick AFTER ActorDeath; that hit belongs to the dying target, not to a new target.
    private final Map<NPC,Target> deaths = new IdentityHashMap<>();
    private int deathTick = -1;
    private CombatEncounterTracker encounters = new CombatEncounterTracker(null);

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
        loadouts = null; loadoutRestarts = 0; loadoutLastTick = -1;
        observations.clear(); observationBytes.clear(); bufferedBytes = 0;
        states.clear(); targets.clear(); splats.clear(); deaths.clear(); deathTick = -1; activities.reset(); interfaces.reset(); sequences.reset(); scene.reset(); consumption.reset(); groundClues.reset(); bankTabs.reset(); lootKeys.reset();
        lastTick = splatTick = -1; sequence = lastReadAt = 0; evicted = 0;
        combatObserved = combatHandedOff = combatNotHandedOff = 0;
        stateObserved = stateHandedOff = stateNotHandedOff = 0;
        varsObserved = varsHandedOff = varsNotHandedOff = 0;
        signalsObserved=signalsHandedOff=signalsNotHandedOff=0;
        gapReason = "not_observed"; encounters = new CombatEncounterTracker(null); wornSession = null; wornSlots = null; localInputs = 0;
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
        encounters.interrupt("scene_gap", lastTick); wornSlots = null; wornSession = null;
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

    private void append(String kind,String source,int tick,long at,Map<String,Object> value,boolean direct,boolean keepSnapshot)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sequence", ++sequence); row.put("event_id", session + ":" + sequence);
        row.put("kind", kind); row.put("source", source); row.put("tick", tick);
        row.put("observed_at_ms", at); row.put("direct", direct); row.put("value", value);
        int bytes = JSON.toJson(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
        if (bytes > MAX_OBSERVATION_BYTES) { evicted++; return; }
        if(keepSnapshot)
        {
            while (observations.size() >= MAX_OBSERVATIONS || bufferedBytes + bytes > MAX_OBSERVATION_BYTES)
            {
                observations.removeFirst(); bufferedBytes -= observationBytes.removeFirst(); evicted++;
            }
            observations.addLast(row); observationBytes.addLast(bytes); bufferedBytes += bytes;
        }
        if (session != null) track(row);
        Map<String,Object> combat = OwnCombatEvent.from(session, row);
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
        if (loadoutLastTick >= 0 && tick < loadoutLastTick) { loadouts = null; loadoutRestarts++; }
        loadoutLastTick = tick;
        lastTick = tick; lastReadAt = at;
        // A scene reload keeps the worn container, so gap() does not end gear knowledge. Unchanged gear is a no-op.
        String generation = session + "/" + loadoutRestarts;
        if (loadouts == null || !loadouts.generation().equals(generation)) loadouts = new LoadoutRevision(generation);
        net.runelite.api.ItemContainer worn = client.getItemContainer(net.runelite.api.gameval.InventoryID.WORN);
        Map<Integer, Integer> wornSlots = worn == null ? null : LoadoutRevision.slotsOf(worn.getItems());
        if (wornSlots == null) loadouts.gap(tick);
        else loadouts.observe(wornSlots, tick, at);
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
        Map<String,Object> charge = new LinkedHashMap<>();
        charge.put("active_raw", AccountProgressCapture.bit(client, vars, 12411));
        charge.put("cooldown_raw", AccountProgressCapture.bit(client, vars, 12138));
        state("effects.death_charge", "varbits:12411,12138", charge, tick, at);

        Map<String,Object> escape = new LinkedHashMap<>();
        escape.put("afk_mode_raw", AccountProgressCapture.bit(client, vars, 14838));
        escape.put("afk_delay_raw", AccountProgressCapture.bit(client, vars, 14849));
        escape.put("ring_of_life_raw", AccountProgressCapture.bit(client, vars, 14857));
        // Settings are not possession, server idle time, or a promise that teleport will occur.
        state("effects.escape_crystal", "varbits:14838,14849,14857", escape, tick, at);

        Map<String,Object> arena = new LinkedHashMap<>();
        arena.put("entry_paid_raw", AccountProgressCapture.bit(client, vars, 5964));
        arena.put("cooldown_raw", AccountProgressCapture.bit(client, vars, 10732));
        arena.put("ticket_available_raw", AccountProgressCapture.bit(client, vars, 5962));
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
            if (target == null)
            {
                if (!dying && targets.size() >= MAX_TARGETS) { targets.clear(); gapReason = "target_capacity_gap"; }
                target = new Target(npc, tick);
                if (dying) deaths.put(npc, target); else targets.put(npc, target);
            }
            value.put("npc_id", npc.getId()); value.put("target_observation_id", target.id);
            worn(client, tick, at);
            append("combat.own_hits", "HitsplatApplied", tick, at, value);
            // The NPC died this tick before our only observed hit: record its death for that new target.
            if (deathWithoutEarlierHit) npcEnd(target, true, "actor_death_before_own_hit_same_tick", tick, at);
            return;
        }
        append("combat.own_hits", "HitsplatApplied", tick, at, value);
    }

    // Own worn item IDs only, read at an own hit and recorded when the ID set changes. Quantities
    // (ammunition) are excluded. A new session, a scene gap or an unreadable read starts a new baseline.
    private String wornSession;
    private Map<String,Object> wornSlots;
    private void worn(Client client, int tick, long at)
    {
        ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
        Item[] items = container == null ? null : container.getItems();
        if (items == null)
        {
            // Unknown is not an empty loadout, and earlier gear is no longer known to be worn.
            if (wornSlots == null && session != null && session.equals(wornSession)) return;
            wornSession = session; wornSlots = null;
            Map<String,Object> value = new LinkedHashMap<>(); value.put("observation", "unreadable");
            combatOnly("combat.worn_unknown", "own_worn_container_at_own_hit", tick, at, value);
            return;
        }
        Map<String,Object> slots = new LinkedHashMap<>();
        for (int i = 0; i < items.length && i < CombatEncounterTracker.SLOT_NAMES.length; i++)
            if (CombatEncounterTracker.SLOT_NAMES[i] != null && items[i] != null && items[i].getId() > 0 && items[i].getQuantity() > 0)
                slots.put(CombatEncounterTracker.SLOT_NAMES[i], items[i].getId());
        boolean sameSession = session != null && session.equals(wornSession);
        if (sameSession && slots.equals(wornSlots)) return;
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("observation", sameSession && wornSlots != null ? "observed_change" : "baseline");
        value.put("content_id", CombatEncounterTracker.contentId(slots)); value.put("slots", slots);
        wornSession = session; wornSlots = slots;
        combatOnly("combat.worn_revision", "own_worn_container_at_own_hit", tick, at, value);
    }

    /** Own death only. Another actor's death never produces this row. */
    void ownDeath(Client client, Actor actor, long at)
    {
        Player self = client.getLocalPlayer();
        if (self == null || actor != self) return;
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("actor", "self"); value.put("classification", "observed_own_death");
        combatOnly("combat.own_death", "ActorDeath:self", client.getTickCount(), at, value);
    }

    /** Own NPC loot from ServerNpcLoot. A reward observation, not kill credit. No NPC or item names. */
    void loot(Client client, int npcId, Collection<net.runelite.client.game.ItemStack> stacks, long at)
    {
        if (stacks == null || stacks.isEmpty()) return;
        Map<Integer,Long> merged = new TreeMap<>();
        for (net.runelite.client.game.ItemStack s : stacks)
        {
            if (s == null || s.getId() <= 0 || s.getQuantity() <= 0) continue;
            if (!merged.containsKey(s.getId()) && merged.size() >= 28) continue;
            merged.merge(s.getId(), (long) s.getQuantity(), Long::sum);
        }
        if (merged.isEmpty()) return;
        List<Map<String,Object>> items = new ArrayList<>();
        for (Map.Entry<Integer,Long> e : merged.entrySet())
        {
            Map<String,Object> item = new LinkedHashMap<>(); item.put("id", e.getKey()); item.put("qty", e.getValue()); items.add(item);
        }
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("npc_id", npcId >= 0 ? npcId : null); value.put("items", items);
        value.put("classification", "reward_observation_not_kill_credit");
        combatOnly("combat.loot_observed", "ServerNpcLoot", client.getTickCount(), at, value);
    }

    // New encounter inputs stay out of the uploaded snapshot, out of every transport projection and
    // out of the shared sequence, so source_sequence gaps never appear. Only the local tracker reads them.
    private long localInputs;
    private void combatOnly(String kind, String source, int tick, long at, Map<String,Object> value)
    {
        if (session == null) return;
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("local_input_id", "local:" + (++localInputs)); row.put("after_sequence", sequence);
        row.put("kind", kind); row.put("source", source); row.put("tick", tick);
        row.put("observed_at_ms", at); row.put("direct", true); row.put("value", value);
        track(row);
    }

    private void track(Map<String,Object> row)
    {
        try
        {
            if (!session.replace("-", "").equals(encounters.session())) encounters = new CombatEncounterTracker(session);
            encounters.accept(row);
        }
        catch (RuntimeException failure) { /* A derived-model failure must not block raw capture or handoff. */ }
    }

    /** Derived encounter summary for this gameplay session. Not a complete combat history. */
    Map<String,Object> encounterSummary(int asOfTick)
    {
        Map<String,Object> out = encounters.summary(asOfTick);
        out.put("input_gap_reason", gapReason);
        return out;
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
