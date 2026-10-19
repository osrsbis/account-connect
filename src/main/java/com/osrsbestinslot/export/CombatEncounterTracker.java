package com.osrsbestinslot.export;

import java.util.*;

/**
 * Derived own-combat encounter summaries from the raw own-gameplay rows of ONE gameplay session.
 * Pure and replayable: the same ordered rows give the same result. Raw rows stay unchanged.
 * An encounter is one observed NPC target. Its start is the first OBSERVED own hit, not the true
 * start. NPC death is not kill credit, despawn is not death and received damage is not a mistake.
 * Only the local player's hits are visible, so party damage and own damage share stay unknown.
 */
final class CombatEncounterTracker
{
    static final String MODEL = "combat-encounter-v1";
    // 100 ticks (60 s) without an own hit closes the target as an unobserved end. Inferred, not observed.
    static final int INACTIVE_TICKS = 100;
    // ServerNpcLoot follows the death animation. Loot later than this is never linked.
    static final int LOOT_WINDOW_TICKS = 16;
    static final int MAX_OPEN = 16, MAX_CLOSED = 32, MAX_PHASES = 8, MAX_REVISIONS = 8, MAX_LOOT = 8, MAX_SEQUENCES = 256;
    // RuneLite HitsplatID: own attack splats versus own damage over time.
    private static final Set<Integer> ATTACK = new HashSet<>(Arrays.asList(12, 16, 18, 20, 22, 24, 43, 44, 45, 46, 47, 53, 55));
    private static final Set<Integer> OVER_TIME = new HashSet<>(Arrays.asList(5, 65, 67, 74));

    private final String session;
    private final Map<String,Encounter> open = new LinkedHashMap<>();
    private final Deque<Encounter> closed = new ArrayDeque<>();
    private final List<Map<String,Object>> unlinkedLoot = new ArrayList<>();
    private Map<String,Object> worn; // latest observed worn revision in this session, or null = unknown
    private long receivedNoOpenHits, receivedNoOpenDamage, receivedOverlapHits, receivedOverlapDamage, droppedClosed, droppedLoot;
    private int lastTick = -1;

    CombatEncounterTracker(String gameplaySession) { session = gameplaySession == null ? null : gameplaySession.replace("-", ""); }
    String session() { return session; }

    private final class Encounter
    {
        final String key, targetKey;
        final int startTick; final long startAt;
        int lastHitTick, endTick = -1; long lastHitAt, endAt; String outcome, endSource;
        long attackSplats, landed, zero, attackDamage, overTimeSplats, overTimeDamage, otherSplats, otherDamage, maxHit;
        long receivedHits, receivedDamage;
        final List<Map<String,Object>> phases = new ArrayList<>();
        final List<Map<String,Object>> revisions = new ArrayList<>();
        final List<Map<String,Object>> loot = new ArrayList<>();
        final Set<String> gaps = new LinkedHashSet<>();
        final List<Long> sequences = new ArrayList<>(); boolean sequencesTruncated;
        final Map<String,Object> wornAtStart;

        Encounter(String target, int tick, long at)
        {
            targetKey = target; key = session + ":" + target + ":" + tick; startTick = lastHitTick = tick; startAt = lastHitAt = at;
            wornAtStart = worn;
        }
        void contributes(long sequence)
        {
            if (sequence < 0) return; // local input, no shared source sequence
            if (sequences.size() < MAX_SEQUENCES) sequences.add(sequence); else sequencesTruncated = true;
        }
        void phase(int npcId, int tick)
        {
            Map<String,Object> last = phases.isEmpty() ? null : phases.get(phases.size() - 1);
            if (last != null && Objects.equals(last.get("npc_id"), npcId)) { last.put("last_hit_tick", tick); return; }
            if (phases.size() >= MAX_PHASES) { gaps.add("phase_capacity"); return; }
            Map<String,Object> p = new LinkedHashMap<>();
            p.put("npc_id", npcId); p.put("first_hit_tick", tick); p.put("last_hit_tick", tick);
            phases.add(p);
        }
        boolean hasNpc(int npcId)
        {
            for (Map<String,Object> p : phases) if (Objects.equals(p.get("npc_id"), npcId)) return true;
            return false;
        }
    }

    /** Feed one raw own-gameplay row in sequence order. Unknown kinds are ignored. */
    void accept(Map<String,Object> row)
    {
        if (row == null || !(row.get("value") instanceof Map) || !(row.get("tick") instanceof Number)
            || !(row.get("observed_at_ms") instanceof Number)) return;
        // Shared rows carry source sequence. Local encounter inputs carry local_input_id instead.
        if (!(row.get("sequence") instanceof Number) && !(row.get("local_input_id") instanceof String)) return;
        String kind = String.valueOf(row.get("kind"));
        Map<?,?> v = (Map<?,?>) row.get("value");
        int tick = ((Number) row.get("tick")).intValue();
        long at = ((Number) row.get("observed_at_ms")).longValue();
        long seq = row.get("sequence") instanceof Number ? ((Number) row.get("sequence")).longValue() : -1;
        if (lastTick >= 0 && tick < lastTick) { interrupt("tick_reversal", lastTick); lastTick = -1; }
        expire(tick);
        lastTick = Math.max(lastTick, tick);
        switch (kind)
        {
            case "combat.worn_revision": worn(v, tick, seq); break;
            case "combat.worn_unknown":
                worn = null; // later encounters start with unknown gear
                for (Encounter e : open.values()) e.gaps.add("equipment_unreadable");
                break;
            case "combat.own_hits": hit(v, tick, at, seq); break;
            case "combat.npc_lifecycle": npcEnd(v, tick, at, seq); break;
            case "combat.own_death":
                for (Encounter e : new ArrayList<>(open.values())) { e.contributes(seq); close(e, "own_death", "ActorDeath:self", tick, at); }
                break;
            case "combat.loot_observed": loot(v, tick, at, "loot_input_id", String.valueOf(row.get("local_input_id")), false); break;
            default: break;
        }
    }

    private void worn(Map<?,?> v, int tick, long seq)
    {
        if (!(v.get("slots") instanceof Map) || !(v.get("content_id") instanceof String)) return;
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("content_id", v.get("content_id")); r.put("slots", new LinkedHashMap<>((Map<?,?>) v.get("slots")));
        r.put("observed_tick", tick); r.put("provenance", "worn_container_at_own_hit");
        worn = r;
        for (Encounter e : open.values())
        {
            if (e.revisions.size() < MAX_REVISIONS) e.revisions.add(r); else e.gaps.add("revision_capacity");
            e.contributes(seq);
        }
    }

    private void hit(Map<?,?> v, int tick, long at, long seq)
    {
        if (!(v.get("amount") instanceof Number) || !(v.get("hitsplat_type") instanceof Number)) return;
        long amount = ((Number) v.get("amount")).longValue(); int type = ((Number) v.get("hitsplat_type")).intValue();
        if ("received".equals(v.get("direction")))
        {
            // Without a visible attacker, received damage belongs to an encounter only when exactly one is open.
            if (open.size() == 1)
            {
                Encounter e = open.values().iterator().next();
                e.receivedHits++; e.receivedDamage += amount; e.contributes(seq);
            }
            else if (open.isEmpty()) { receivedNoOpenHits++; receivedNoOpenDamage += amount; }
            else { receivedOverlapHits++; receivedOverlapDamage += amount; }
            return;
        }
        if (!"dealt_to_npc".equals(v.get("direction")) || !(v.get("target_observation_id") instanceof String)
            || !(v.get("npc_id") instanceof Number)) return;
        String target = ((String) v.get("target_observation_id")).replace("-", "");
        Encounter e = open.get(target);
        if (e == null)
        {
            if (open.size() >= MAX_OPEN)
            {
                Encounter oldest = open.values().iterator().next();
                oldest.gaps.add("open_capacity"); close(oldest, "unknown", "tracker_capacity", tick, at);
            }
            e = new Encounter(target, tick, at); open.put(target, e);
            if (open.size() > 1) for (Encounter o : open.values()) o.gaps.add("overlapping_targets");
            if (e.wornAtStart != null) e.revisions.add(e.wornAtStart);
        }
        e.lastHitTick = tick; e.lastHitAt = at; e.contributes(seq); e.phase(((Number) v.get("npc_id")).intValue(), tick);
        if (ATTACK.contains(type))
        {
            e.attackSplats++; e.attackDamage += amount; e.maxHit = Math.max(e.maxHit, amount);
            if (amount > 0) e.landed++; else e.zero++;
        }
        else if (OVER_TIME.contains(type)) { e.overTimeSplats++; e.overTimeDamage += amount; }
        else { e.otherSplats++; e.otherDamage += amount; }
    }

    private void npcEnd(Map<?,?> v, int tick, long at, long seq)
    {
        if (!(v.get("target_observation_id") instanceof String)) return;
        Encounter e = open.get(((String) v.get("target_observation_id")).replace("-", ""));
        if (e == null) return; // Not observed from its first own hit in this tracker.
        e.contributes(seq);
        String end = String.valueOf(v.get("end"));
        close(e, "observed_death".equals(end) ? "observed_death" : "despawn_not_death",
            "observed_death".equals(end) ? "ActorDeath" : "NpcDespawned", tick, at);
    }

    private void loot(Map<?,?> v, int tick, long at, String idField, String id, boolean wallClock)
    {
        Integer npcId = v.get("npc_id") instanceof Number && ((Number) v.get("npc_id")).intValue() >= 0
            ? ((Number) v.get("npc_id")).intValue() : null;
        List<Encounter> candidates = new ArrayList<>();
        for (Encounter e : closed)
            // One ServerNpcLoot per death: an encounter that already holds loot is not a candidate again.
            if ("observed_death".equals(e.outcome) && e.loot.isEmpty() && (npcId == null || e.hasNpc(npcId))
                && (wallClock ? e.endAt >= 0 && at >= e.endAt && at - e.endAt <= LOOT_WINDOW_TICKS * 600L
                    : tick >= e.endTick && tick - e.endTick <= LOOT_WINDOW_TICKS)) candidates.add(e);
        Map<String,Object> link = new LinkedHashMap<>();
        // Client side: local input ID only, the uploaded event_id is generated later by the shared emitter.
        // Event replay: the existing loot event's own event_id.
        link.put(idField, id); link.put("observed_tick", wallClock ? null : tick);
        link.put("observed_at_ms", at); link.put("npc_id", npcId); link.put("items", v.get("items"));
        link.put("link_basis", (npcId == null ? "time_window_only" : "npc_id_and_time_window") + (wallClock ? ";wall_clock_ms" : ";game_ticks"));
        if (candidates.size() == 1)
        {
            Encounter e = candidates.get(0);
            link.put("encounter_key", e.key); link.put("link_provenance", "matched");
            if (e.loot.size() < MAX_LOOT) e.loot.add(link); else e.gaps.add("loot_capacity");
            return;
        }
        link.put("encounter_key", null);
        link.put("link_provenance", candidates.isEmpty() ? "unlinked" : "ambiguous");
        if (!candidates.isEmpty())
        {
            List<String> keys = new ArrayList<>(); for (Encounter e : candidates) keys.add(e.key);
            link.put("candidate_encounter_keys", keys);
        }
        if (unlinkedLoot.size() < MAX_LOOT) unlinkedLoot.add(link); else droppedLoot++;
    }

    private void expire(int tick)
    {
        for (Encounter e : new ArrayList<>(open.values()))
            if (tick - e.lastHitTick > INACTIVE_TICKS) close(e, "unobserved_end_inactivity", "inferred_inactivity", e.lastHitTick, -1);
    }

    private void close(Encounter e, String outcome, String source, int tick, long at)
    {
        open.remove(e.targetKey);
        e.outcome = outcome; e.endSource = source; e.endTick = tick; e.endAt = at;
        closed.addLast(e);
        while (closed.size() > MAX_CLOSED) { closed.removeFirst(); droppedClosed++; }
    }

    /** Scene reload, tick reversal or session end: open targets cannot be followed further. */
    void interrupt(String reason, int tick)
    {
        worn = null; // gear across a gap is not known until it is read again
        // The caller's tick can be older than the last hit (poll tick, or -1 after a gap). Never end before it.
        for (Encounter e : new ArrayList<>(open.values())) { e.gaps.add(reason); close(e, reason, reason, Math.max(tick, e.lastHitTick), -1); }
    }

    /** Read-only: the same accepted rows give the same result however often this is called. */
    Map<String,Object> summary(int asOfTick)
    {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("model", MODEL); out.put("gameplay_session", session); out.put("as_of_tick", asOfTick < 0 ? null : asOfTick);
        out.put("derived", true); out.put("complete", false);
        out.put("scope", "own_hits_on_npcs_only;party_damage_not_observed;kill_credit_unknown");
        List<Map<String,Object>> list = new ArrayList<>();
        for (Encounter e : closed) list.add(describe(e, e.outcome, e.endSource, e.endTick, e.endAt));
        for (Encounter e : open.values())
        {
            // As-of view only. The tracker itself expires targets when a later row is accepted.
            boolean inactive = asOfTick >= 0 && asOfTick - e.lastHitTick > INACTIVE_TICKS;
            list.add(inactive ? describe(e, "unobserved_end_inactivity", "inferred_inactivity", e.lastHitTick, -1) : describe(e, null, null, -1, -1));
        }
        out.put("encounters", list); out.put("closed_dropped", droppedClosed);
        Map<String,Object> received = new LinkedHashMap<>();
        received.put("no_open_encounter_hits", receivedNoOpenHits); received.put("no_open_encounter_damage", receivedNoOpenDamage);
        received.put("overlapping_encounters_hits", receivedOverlapHits); received.put("overlapping_encounters_damage", receivedOverlapDamage);
        out.put("received_unattributed", received);
        out.put("loot_not_matched", new ArrayList<>(unlinkedLoot)); out.put("loot_not_matched_dropped", droppedLoot);
        return out;
    }

    private Map<String,Object> describe(Encounter e, String outcome, String endSource, int endTick, long endAt)
    {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("encounter_key", e.key); m.put("target_key", e.targetKey);
        m.put("status", outcome == null ? "open" : "closed");
        m.put("start_tick", e.startTick); m.put("start_observed_at_ms", e.startAt);
        m.put("start_provenance", "first_observed_own_hit"); m.put("true_start", "unknown");
        m.put("last_own_hit_tick", e.lastHitTick); m.put("last_own_hit_observed_at_ms", e.lastHitAt);
        m.put("end_tick", outcome == null ? null : endTick);
        m.put("end_observed_at_ms", outcome == null || endAt < 0 ? null : endAt);
        m.put("outcome", outcome == null ? "open" : outcome); m.put("end_source", endSource);
        m.put("kill_credit", "unknown");
        Integer elapsed = outcome == null ? null : endTick - e.startTick;
        m.put("elapsed_ticks", elapsed);
        m.put("elapsed_seconds_game", elapsed == null ? null : elapsed * 0.6);
        m.put("elapsed_basis", "first_observed_own_hit_to_end_tick");
        m.put("phases", new ArrayList<>(e.phases));
        Map<String,Object> own = new LinkedHashMap<>();
        own.put("attack_splats", e.attackSplats); own.put("landed_splats", e.landed); own.put("zero_splats", e.zero);
        own.put("attack_damage", e.attackDamage); own.put("max_attack_splat", e.attackSplats == 0 ? null : e.maxHit);
        own.put("over_time_splats", e.overTimeSplats); own.put("over_time_damage", e.overTimeDamage);
        own.put("other_type_splats", e.otherSplats); own.put("other_type_damage", e.otherDamage);
        long total = e.attackDamage + e.overTimeDamage + e.otherDamage;
        own.put("total_own_damage", total);
        own.put("damage_per_tick", elapsed == null || elapsed <= 0 ? null : Math.round(total * 1000.0 / elapsed) / 1000.0);
        own.put("party_damage", "not_observed");
        m.put("own_attacks", own);
        Map<String,Object> recv = new LinkedHashMap<>();
        recv.put("hits", e.receivedHits); recv.put("damage", e.receivedDamage);
        recv.put("attribution", e.gaps.contains("overlapping_targets") ? "single_open_window_partial" : "single_open_window");
        recv.put("interpretation", "received_damage_not_classified_as_mistake");
        m.put("received", recv);
        Map<String,Object> gear = new LinkedHashMap<>();
        gear.put("at_start", e.wornAtStart == null ? "unknown" : e.wornAtStart);
        gear.put("revisions_observed", new ArrayList<>(e.revisions));
        // A change needs two adjacent revisions with different content. Unknown then known, or the same gear
        // re-read as a fresh baseline after an unreadable read, is not a change.
        boolean changed = false;
        for (int i = 1; i < e.revisions.size(); i++)
            changed |= !Objects.equals(e.revisions.get(i - 1).get("content_id"), e.revisions.get(i).get("content_id"));
        gear.put("changed_during", changed);
        gear.put("timing", "observed_at_own_hits_not_exact_change_time");
        m.put("equipment", gear);
        m.put("loot", new ArrayList<>(e.loot));
        m.put("loot_status", !e.loot.isEmpty() ? "matched" : "observed_death".equals(outcome) ? "none_matched" : "not_applicable");
        m.put("gaps", new ArrayList<>(e.gaps));
        m.put("contributing_sequences", new ArrayList<>(e.sequences)); m.put("contributing_truncated", e.sequencesTruncated);
        return m;
    }

    // Same canonical form as LoadoutRevision.contentId (loadout.revision.v1), so one gear set has one ID.
    // Slot indexes 6, 8 and 11 are not worn item slots and are excluded.
    static final String[] SLOT_NAMES = {"head", "cape", "amulet", "weapon", "body", "shield", null, "legs",
        null, "gloves", "boots", null, "ring", "ammo"};

    /** sha256 of "loadout.v1;" plus "name=item_id;" for each named slot in index order. */
    static String contentId(Map<String,Object> slots)
    {
        StringBuilder canonical = new StringBuilder("loadout.v1;");
        for (int i = 0; i < SLOT_NAMES.length; i++)
        {
            Object item = slots.get(SLOT_NAMES[i]);
            if (SLOT_NAMES[i] != null && item != null) canonical.append(SLOT_NAMES[i]).append('=').append(item).append(';');
        }
        return AccountConnectPlugin.sha256Hex(canonical.toString());
    }

    /**
     * Server/offline read model from the EXISTING own-combat-v1 wire events (combat_hit, combat_npc_end) of one
     * gameplay_session. No new wire field is needed. Gear, own death and loot are not in those events, so the
     * result marks them unknown. Delivery can duplicate or reorder: rows are deduplicated by source_sequence
     * and replayed in source order.
     */
    static Map<String,Object> deriveFromEvents(String gameplaySession, List<Map<String,Object>> events, int asOfTick)
    {
        TreeMap<Long,Map<String,Object>> rows = new TreeMap<>();
        for (Map<String,Object> ev : events)
        {
            if (ev == null || !Objects.equals(gameplaySession, ev.get("gameplay_session"))
                || !(ev.get("source_sequence") instanceof Number) || !(ev.get("source_tick") instanceof Number)
                || !(ev.get("observed_at_ms") instanceof Number)) continue;
            Map<String,Object> value = new LinkedHashMap<>();
            Map<String,Object> row = new LinkedHashMap<>();
            if ("combat_hit".equals(ev.get("type")))
            {
                row.put("kind", "combat.own_hits");
                for (String k : Arrays.asList("direction", "amount", "hitsplat_type", "npc_id")) if (ev.containsKey(k)) value.put(k, ev.get(k));
                if (ev.get("target_key") != null) value.put("target_observation_id", ev.get("target_key"));
            }
            else if ("combat_npc_end".equals(ev.get("type")))
            {
                row.put("kind", "combat.npc_lifecycle");
                value.put("target_observation_id", ev.get("target_key")); value.put("end", ev.get("end"));
            }
            else continue;
            row.put("sequence", ((Number) ev.get("source_sequence")).longValue());
            row.put("tick", ((Number) ev.get("source_tick")).intValue());
            row.put("observed_at_ms", ev.get("observed_at_ms")); row.put("value", value);
            rows.putIfAbsent(((Number) ev.get("source_sequence")).longValue(), row);
        }
        // Existing loot events carry an NPC name, not an ID, and wall-clock ts, not a tick. Same account only:
        // the caller supplies events of one account/profile. Linking is a time-window inference. Loot is merged
        // in time order with the combat rows, so it links before older encounters leave the bounded closed list.
        Set<Object> seenLoot = new HashSet<>();
        List<Map<String,Object>> loot = new ArrayList<>();
        for (Map<String,Object> ev : events)
            if (ev != null && "loot".equals(ev.get("type")) && "npc".equals(ev.get("source_type")) && ev.get("ts") instanceof Number
                && ev.get("event_id") instanceof String && seenLoot.add(ev.get("event_id"))) loot.add(ev);
        loot.sort(Comparator.comparingLong(ev -> ((Number) ev.get("ts")).longValue()));
        CombatEncounterTracker t = new CombatEncounterTracker(gameplaySession);
        int next = 0;
        for (Map<String,Object> row : rows.values())
        {
            long at = ((Number) row.get("observed_at_ms")).longValue();
            for (; next < loot.size() && ((Number) loot.get(next).get("ts")).longValue() < at; next++) t.replayLoot(loot.get(next));
            t.accept(row);
        }
        for (; next < loot.size(); next++) t.replayLoot(loot.get(next));
        Map<String,Object> out = t.summary(asOfTick);
        List<Long> gearChanges = new ArrayList<>();
        for (Map<String,Object> ev : events)
            if (ev != null && "equip_change".equals(ev.get("type")) && ev.get("ts") instanceof Number) gearChanges.add(((Number) ev.get("ts")).longValue());
        for (Object o : (List<?>) out.get("encounters"))
        {
            Map<String,Object> e = (Map<String,Object>) o, gear = (Map<String,Object>) e.get("equipment");
            long from = ((Number) e.get("start_observed_at_ms")).longValue();
            // Open: any later change counts. Closed without an end time: bounded by the last own hit only.
            Object to = "open".equals(e.get("status")) ? null
                : e.get("end_observed_at_ms") != null ? e.get("end_observed_at_ms") : e.get("last_own_hit_observed_at_ms");
            boolean changed = false;
            for (long at : gearChanges) changed |= at >= from && (to == null || at <= ((Number) to).longValue());
            gear.put("changed_during", changed ? Boolean.TRUE : "not_observed");
            gear.put("timing", "equip_change_event_wall_clock");
        }
        long expected = rows.isEmpty() ? 0 : rows.firstKey(), missing = 0;
        for (Long s : rows.keySet()) { missing += s - expected; expected = s + 1; }
        out.put("input_scope", "own_combat_v1_events;gear_death_loot_not_in_events");
        out.put("input_sequence_first", rows.isEmpty() ? null : rows.firstKey());
        out.put("input_sequence_missing_between", missing); // other own families share the sequence, so not all are combat
        return out;
    }

    private void replayLoot(Map<String,Object> ev)
    {
        Map<String,Object> v = new LinkedHashMap<>(); v.put("items", ev.get("items"));
        loot(v, -1, ((Number) ev.get("ts")).longValue(), "loot_event_id", (String) ev.get("event_id"), true);
    }

    /** Offline replay of one session's ordered rows, e.g. from a captured own_gameplay snapshot or fixture. */
    static Map<String,Object> derive(String gameplaySession, List<Map<String,Object>> rows, int asOfTick)
    {
        CombatEncounterTracker t = new CombatEncounterTracker(gameplaySession);
        for (Map<String,Object> row : rows) t.accept(row);
        return t.summary(asOfTick);
    }
}
