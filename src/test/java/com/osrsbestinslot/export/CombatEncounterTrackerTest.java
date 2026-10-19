package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.client.game.ItemStack;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CombatEncounterTrackerTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final long AT = 1_760_000_000_000L;
    private Client client;
    private Player self;
    private int tick;
    private Item[] worn;
    private OwnGameplayCapture capture;
    private final List<Map<String,Object>> handed = new ArrayList<>();

    @Before public void setup()
    {
        client = mock(Client.class); self = mock(Player.class);
        capture = new OwnGameplayCapture(e -> handed.add(e));
        tick = 100;
        when(client.getVarps()).thenReturn(new int[5000]); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(self); when(client.getAccountHash()).thenReturn(42L);
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(70);
        when(client.getTickCount()).thenAnswer(i -> tick);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        // Slot 3 weapon, slot 13 ammunition. Quantity changes of worn ammunition are not gear revisions.
        wear(new Item(-1,0), new Item(-1,0), new Item(-1,0), new Item(861,1), new Item(-1,0), new Item(-1,0),
            new Item(-1,0), new Item(-1,0), new Item(-1,0), new Item(-1,0), new Item(-1,0), new Item(-1,0),
            new Item(-1,0), new Item(892,500));
        assertFalse(capture.ready(client, TOKEN)); tick += 2; assertTrue(capture.ready(client, TOKEN));
    }

    private void wear(Item... items)
    {
        worn = items; ItemContainer c = mock(ItemContainer.class); when(c.getItems()).thenAnswer(i -> worn);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(c);
    }
    private Hitsplat splat(int amount, int type, boolean mine)
    {
        Hitsplat s = mock(Hitsplat.class);
        when(s.getAmount()).thenReturn(amount); when(s.getHitsplatType()).thenReturn(type); when(s.isMine()).thenReturn(mine);
        return s;
    }
    private NPC npc(int id) { NPC n = mock(NPC.class); when(n.getId()).thenReturn(id); return n; }
    private void dealt(NPC n, int amount) { capture.hit(client, n, splat(amount, HitsplatID.DAMAGE_ME, true), AT + tick * 600L); }
    private void received(int amount) { capture.hit(client, self, splat(amount, amount == 0 ? HitsplatID.BLOCK_ME : HitsplatID.DAMAGE_ME, false), AT + tick * 600L); }
    private List<Map<String,Object>> encounters(Map<String,Object> summary) { return (List<Map<String,Object>>) summary.get("encounters"); }
    private Map<?,?> m(Object o) { return (Map<?,?>) o; }

    /** Representative supported encounter: one transforming NPC, own hits, received damage, death and loot. */
    @Test public void representativeEncounterJoinsHitsGearElapsedTimeDeathAndLoot()
    {
        NPC boss = npc(2042); // one NPC object whose ID changes is one encounter with two phases
        tick = 110; dealt(boss, 0); received(12);
        tick = 114; dealt(boss, 23); received(0);
        when(boss.getId()).thenReturn(2043);
        tick = 118; dealt(boss, 31);
        tick = 122; capture.hit(client, boss, splat(6, HitsplatID.VENOM, true), AT + tick * 600L);
        tick = 125; capture.endNpc(client, boss, true, AT + tick * 600L);
        tick = 129; capture.loot(client, 2043, Arrays.asList(new ItemStack(12934, 120), new ItemStack(12934, 30), new ItemStack(995, 5000)), AT + tick * 600L);

        Map<String,Object> summary = capture.encounterSummary(tick);
        assertEquals("combat-encounter-v1", summary.get("model")); assertEquals(false, summary.get("complete"));
        List<Map<String,Object>> list = encounters(summary); assertEquals(1, list.size());
        Map<String,Object> e = list.get(0);
        assertEquals("observed_death", e.get("outcome")); assertEquals("unknown", e.get("kill_credit"));
        assertEquals("first_observed_own_hit", e.get("start_provenance")); assertEquals("unknown", e.get("true_start"));
        assertEquals(110, e.get("start_tick")); assertEquals(125, e.get("end_tick")); assertEquals(15, e.get("elapsed_ticks"));
        assertEquals(2, ((List<?>) e.get("phases")).size());
        Map<?,?> own = m(e.get("own_attacks"));
        assertEquals(3L, own.get("attack_splats")); assertEquals(2L, own.get("landed_splats")); assertEquals(1L, own.get("zero_splats"));
        assertEquals(54L, own.get("attack_damage")); assertEquals(31L, own.get("max_attack_splat"));
        assertEquals(1L, own.get("over_time_splats")); assertEquals(6L, own.get("over_time_damage"));
        assertEquals(60L, own.get("total_own_damage")); assertEquals("not_observed", own.get("party_damage"));
        Map<?,?> recv = m(e.get("received"));
        assertEquals(2L, recv.get("hits")); assertEquals(12L, recv.get("damage"));
        assertEquals("received_damage_not_classified_as_mistake", recv.get("interpretation"));
        Map<?,?> gear = m(e.get("equipment"));
        Map<?,?> start = m(gear.get("at_start"));
        assertEquals(861, m(start.get("slots")).get("weapon")); assertEquals(892, m(start.get("slots")).get("ammo"));
        assertEquals(false, gear.get("changed_during"));
        List<?> loot = (List<?>) e.get("loot"); assertEquals(1, loot.size());
        assertEquals("matched", m(loot.get(0)).get("link_provenance")); assertEquals(e.get("encounter_key"), m(loot.get(0)).get("encounter_key"));
        List<?> items = (List<?>) m(loot.get(0)).get("items"); assertEquals(2, items.size()); // sorted by item ID
        assertEquals(995, m(items.get(0)).get("id")); assertEquals(12934, m(items.get(1)).get("id"));
        assertEquals(150L, m(items.get(1)).get("qty")); // duplicate stacks merged as long
        assertTrue(((List<?>) summary.get("loot_not_matched")).isEmpty());
        // Contributing rows are the shared source sequences that the existing combat events carry.
        Set<Object> wire = new HashSet<>(); for (Map<String,Object> h : handed) if (h.get("type").toString().startsWith("combat_")) wire.add(h.get("source_sequence"));
        assertTrue(wire.containsAll((List<?>) e.get("contributing_sequences")));
        String out = System.getenv("C2_EXAMPLE_OUT");
        if (out != null)
        {
            try
            {
                java.nio.file.Files.createDirectories(java.nio.file.Paths.get(out));
                java.nio.file.Files.write(java.nio.file.Paths.get(out, "client-derived-representative.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(summary).getBytes("UTF-8"));
            }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }
    }

    @Test public void despawnIsNotDeathAndLaterLootStaysUnlinked()
    {
        NPC n = npc(3000);
        tick = 110; dealt(n, 5); tick = 112; capture.endNpc(client, n, false, AT);
        tick = 113; capture.loot(client, 3000, Collections.singletonList(new ItemStack(526, 1)), AT);
        Map<String,Object> s = capture.encounterSummary(tick); Map<String,Object> e = encounters(s).get(0);
        assertEquals("despawn_not_death", e.get("outcome")); assertEquals("not_applicable", e.get("loot_status"));
        Map<?,?> loot = m(((List<?>) s.get("loot_not_matched")).get(0));
        assertEquals("unlinked", loot.get("link_provenance")); assertNull(loot.get("encounter_key"));
    }

    @Test public void overlappingTargetsLeaveReceivedDamageAndSameIdLootAmbiguous()
    {
        NPC a = npc(3000), b = npc(3000);
        tick = 110; dealt(a, 4); dealt(b, 4); received(7);
        tick = 112; capture.endNpc(client, a, true, AT); capture.endNpc(client, b, true, AT);
        tick = 115; capture.loot(client, 3000, Collections.singletonList(new ItemStack(526, 1)), AT);
        Map<String,Object> s = capture.encounterSummary(tick);
        for (Map<String,Object> e : encounters(s))
        {
            assertTrue(((List<?>) e.get("gaps")).contains("overlapping_targets")); assertEquals(0L, m(e.get("received")).get("hits"));
            assertEquals("none_matched", e.get("loot_status"));
        }
        assertEquals(7L, m(s.get("received_unattributed")).get("overlapping_encounters_damage"));
        Map<?,?> loot = m(((List<?>) s.get("loot_not_matched")).get(0));
        assertEquals("ambiguous", loot.get("link_provenance")); assertEquals(2, ((List<?>) loot.get("candidate_encounter_keys")).size());
    }

    @Test public void lootForOtherNpcOrOutsideWindowIsNotLinked()
    {
        NPC n = npc(3000);
        tick = 110; dealt(n, 4); tick = 111; capture.endNpc(client, n, true, AT);
        tick = 112; capture.loot(client, 9999, Collections.singletonList(new ItemStack(526, 1)), AT);
        tick = 111 + CombatEncounterTracker.LOOT_WINDOW_TICKS + 1; capture.loot(client, 3000, Collections.singletonList(new ItemStack(526, 1)), AT);
        Map<String,Object> s = capture.encounterSummary(tick);
        assertEquals("none_matched", encounters(s).get(0).get("loot_status"));
        assertEquals(2, ((List<?>) s.get("loot_not_matched")).size());
    }

    @Test public void ownDeathEndsOpenEncounterWithoutKillOrMistakeClaims()
    {
        NPC n = npc(3000); tick = 110; dealt(n, 4); received(40);
        tick = 111; capture.ownDeath(client, mock(Player.class), AT); // another player's death is ignored
        tick = 112; capture.ownDeath(client, self, AT);
        Map<String,Object> e = encounters(capture.encounterSummary(tick)).get(0);
        assertEquals("own_death", e.get("outcome")); assertEquals("unknown", e.get("kill_credit"));
        assertEquals(40L, m(e.get("received")).get("damage"));
        tick = 113; capture.endNpc(client, n, true, AT); // a later NPC end cannot rewrite a closed encounter
        assertEquals("own_death", encounters(capture.encounterSummary(tick)).get(0).get("outcome"));
    }

    @Test public void gearChangeDuringEncounterIsRevisionButAmmunitionUseIsNot()
    {
        NPC n = npc(3000);
        tick = 110; dealt(n, 4);
        worn[13] = new Item(892, 480); tick = 112; dealt(n, 4);
        worn[3] = new Item(11235, 1); tick = 114; dealt(n, 4);
        Map<String,Object> e = encounters(capture.encounterSummary(tick)).get(0);
        Map<?,?> gear = m(e.get("equipment"));
        assertEquals(true, gear.get("changed_during")); assertEquals(2, ((List<?>) gear.get("revisions_observed")).size());
        assertEquals("open", e.get("outcome")); assertNull(e.get("elapsed_ticks"));
    }

    @Test public void unreadableEquipmentIsUnknownNotEmpty()
    {
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(null);
        tick = 110; dealt(npc(3000), 4);
        assertEquals("unknown", m(encounters(capture.encounterSummary(tick)).get(0).get("equipment")).get("at_start"));
    }

    @Test public void inactivityIsInferredEndAndReturningStartsNewEncounter()
    {
        NPC n = npc(3000); tick = 110; dealt(n, 4);
        tick = 110 + CombatEncounterTracker.INACTIVE_TICKS + 1; dealt(n, 4);
        List<Map<String,Object>> list = encounters(capture.encounterSummary(tick));
        assertEquals(2, list.size());
        assertEquals("unobserved_end_inactivity", list.get(0).get("outcome")); assertNull(list.get(0).get("end_observed_at_ms"));
        assertNotEquals(list.get(0).get("encounter_key"), list.get(1).get("encounter_key"));
        assertEquals(list.get(0).get("target_key"), list.get(1).get("target_key"));
    }

    @Test public void sceneGapClosesOpenEncounterAsGap()
    {
        tick = 110; dealt(npc(3000), 4); capture.gap();
        Map<String,Object> e = encounters(capture.encounterSummary(-1)).get(0);
        assertEquals("scene_gap", e.get("outcome")); assertTrue(((List<?>) e.get("gaps")).contains("scene_gap"));
    }

    @Test public void encounterInputsStayLocalAndSharedSequenceStaysContiguous()
    {
        NPC n = npc(3000); tick = 110; dealt(n, 4); worn[3] = new Item(11235, 1); tick = 111; dealt(n, 5);
        tick = 112; capture.endNpc(client, n, true, AT); capture.ownDeath(client, self, AT);
        capture.loot(client, 3000, Collections.singletonList(new ItemStack(526, 1)), AT);
        for (Map<String,Object> row : (List<Map<String,Object>>) capture.snapshot().get("observations"))
            assertFalse(String.valueOf(row.get("kind")), Arrays.asList("combat.worn_revision", "combat.own_death", "combat.loot_observed").contains(row.get("kind")));
        long expected = 1;
        for (Map<String,Object> h : handed)
        {
            assertTrue(Arrays.asList("combat_hit", "combat_npc_end").contains(h.get("type")) || !h.get("type").toString().startsWith("combat"));
            Map<String,Object> projected = PublicTransport.event(withEnvelope(h));
            assertEquals("no new wire field", withEnvelope(h).keySet(), projected.keySet());
            assertEquals(expected++, ((Number) h.get("source_sequence")).longValue());
        }
        assertEquals(3, handed.size());
    }

    private Map<String,Object> withEnvelope(Map<String,Object> h)
    {
        Map<String,Object> ev = new LinkedHashMap<>(h); ev.put("ts", AT); ev.put("event_id", "0123456789abcdef0123456789abcdef");
        return ev;
    }

    @Test public void accountSwitchStartsNewSessionAndForgetsEncounters()
    {
        tick = 110; dealt(npc(3000), 4);
        String before = (String) capture.encounterSummary(tick).get("gameplay_session");
        when(client.getAccountHash()).thenReturn(43L); assertFalse(capture.ready(client, TOKEN)); tick += 2; assertTrue(capture.ready(client, TOKEN));
        assertTrue(encounters(capture.encounterSummary(tick)).isEmpty());
        dealt(npc(3000), 4);
        Map<String,Object> after = capture.encounterSummary(tick);
        assertNotEquals(before, after.get("gameplay_session"));
        assertTrue(m(encounters(after).get(0).get("equipment")).get("at_start") instanceof Map); // fresh baseline in the new session
    }

    @Test public void replayOfSameRowsIsDeterministic()
    {
        List<Map<String,Object>> rows = new ArrayList<>();
        rows.add(row(1, "combat.own_hits", 10, "dealt_to_npc", 7, 16, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 3000));
        rows.add(row(2, "combat.own_hits", 12, "received", 3, 16, null, -1));
        Map<String,Object> end = new LinkedHashMap<>(); end.put("target_observation_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"); end.put("end", "observed_death");
        Map<String,Object> r = new LinkedHashMap<>(); r.put("sequence", 3L); r.put("kind", "combat.npc_lifecycle"); r.put("tick", 14); r.put("observed_at_ms", AT); r.put("value", end);
        rows.add(r);
        Map<String,Object> a = CombatEncounterTracker.derive("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", rows, 20);
        Map<String,Object> b = CombatEncounterTracker.derive("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", rows, 20);
        assertEquals(a, b); assertEquals(4, encounters(a).get(0).get("elapsed_ticks"));
        assertEquals(Arrays.asList(1L, 2L, 3L), encounters(a).get(0).get("contributing_sequences"));
    }

    private Map<String,Object> row(long seq, String kind, int tick, String direction, int amount, int type, String target, int npcId)
    {
        Map<String,Object> v = new LinkedHashMap<>(); v.put("direction", direction); v.put("amount", amount); v.put("hitsplat_type", type);
        if (target != null) { v.put("target_observation_id", target); v.put("npc_id", npcId); }
        Map<String,Object> r = new LinkedHashMap<>(); r.put("sequence", seq); r.put("kind", kind); r.put("tick", tick); r.put("observed_at_ms", AT); r.put("value", v);
        return r;
    }

    @Test public void existingWireEventsReplayToSameCombatResultDespiteDuplicateAndReorder() throws Exception
    {
        NPC boss = npc(2042);
        tick = 110; dealt(boss, 0); received(12); tick = 114; dealt(boss, 23); tick = 125; capture.endNpc(client, boss, true, AT + tick * 600L);
        Map<String,Object> local = encounters(capture.encounterSummary(tick)).get(0);
        List<Map<String,Object>> wire = new ArrayList<>();
        for (Map<String,Object> h : handed) wire.add(PublicTransport.event(withEnvelope(h)));
        List<Map<String,Object>> delivered = new ArrayList<>(wire); Collections.reverse(delivered); delivered.add(wire.get(1));
        // Existing public loot and equip_change events from the same account, as the legacy emitter shapes them.
        delivered.add(legacy("loot", AT + 128 * 600L, "11111111111111111111111111111111", "source_type", "npc", "source_name", "Example boss",
            "items", Collections.singletonList(item(526, 1))));
        delivered.add(legacy("loot", AT + 128 * 600L, "11111111111111111111111111111111", "source_type", "npc", "items", Collections.emptyList())); // duplicate delivery
        delivered.add(legacy("loot", AT + 200 * 600L, "22222222222222222222222222222222", "source_type", "npc", "items", Collections.singletonList(item(526, 1))));
        delivered.add(legacy("equip_change", AT + 112 * 600L, "33333333333333333333333333333333", "equipped", Collections.singletonList(item(11235, 1))));
        String session = (String) wire.get(0).get("gameplay_session");
        Map<String,Object> server = CombatEncounterTracker.deriveFromEvents(session, delivered, tick);
        Map<String,Object> e = encounters(server).get(0);
        for (String k : Arrays.asList("start_tick", "end_tick", "elapsed_ticks", "outcome", "own_attacks", "received", "phases", "contributing_sequences"))
            assertEquals(k, local.get(k), e.get(k));
        assertEquals("unknown", m(e.get("equipment")).get("at_start")); // gear is not in the existing events
        assertEquals(true, m(e.get("equipment")).get("changed_during"));
        List<?> loot = (List<?>) e.get("loot"); assertEquals(1, loot.size());
        assertEquals("11111111111111111111111111111111", m(loot.get(0)).get("loot_event_id"));
        assertEquals("time_window_only;wall_clock_ms", m(loot.get(0)).get("link_basis"));
        assertEquals("unlinked", m(((List<?>) server.get("loot_not_matched")).get(0)).get("link_provenance"));
        assertEquals(0L, server.get("input_sequence_missing_between"));
        assertTrue(encounters(CombatEncounterTracker.deriveFromEvents("other", delivered, tick)).isEmpty());
        String out = System.getenv("C2_EXAMPLE_OUT");
        if (out != null)
        {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(out));
            com.google.gson.Gson g = new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create();
            java.nio.file.Files.write(java.nio.file.Paths.get(out, "wire-events.json"), g.toJson(wire).getBytes("UTF-8"));
            java.nio.file.Files.write(java.nio.file.Paths.get(out, "server-derived-from-wire.json"), g.toJson(server).getBytes("UTF-8"));
        }
    }

    private Map<String,Object> item(int id, int qty) { Map<String,Object> i = new LinkedHashMap<>(); i.put("id", id); i.put("qty", qty); return i; }
    private Map<String,Object> legacy(String type, long ts, String id, Object... kv)
    {
        Map<String,Object> ev = new LinkedHashMap<>(); ev.put("type", type); ev.put("ts", ts); ev.put("event_id", id);
        for (int i = 0; i < kv.length; i += 2) ev.put((String) kv[i], kv[i + 1]);
        Map<String,Object> projected = PublicTransport.event(ev); assertNotNull(projected); return projected;
    }

    // Review r1 F1: one loot per death, so back-to-back kills of the same NPC type each keep their loot.
    @Test public void backToBackSameNpcKillsEachLinkTheirOwnLoot()
    {
        NPC a = npc(3000), b = npc(3000);
        tick = 100; dealt(a, 4); tick = 102; capture.endNpc(client, a, true, AT);
        tick = 104; capture.loot(client, 3000, Collections.singletonList(new ItemStack(526, 1)), AT);
        tick = 105; dealt(b, 4); tick = 110; capture.endNpc(client, b, true, AT);
        tick = 112; capture.loot(client, 3000, Collections.singletonList(new ItemStack(526, 1)), AT);
        Map<String,Object> s = capture.encounterSummary(tick);
        for (Map<String,Object> e : encounters(s)) assertEquals("matched", e.get("loot_status"));
        assertTrue(((List<?>) s.get("loot_not_matched")).isEmpty());
    }

    // Review r1 F2: replay links loot in time order before old encounters leave the bounded list; drops are counted.
    @Test public void longReplayLinksEveryLootAndCountsDroppedUnlinked()
    {
        List<Map<String,Object>> events = new ArrayList<>();
        String session = "cccccccccccccccccccccccccccccccc";
        long seq = 1;
        for (int i = 0; i < 40; i++)
        {
            String target = String.format("%032x", i + 1);
            int t = 1000 + i * 20; long at = AT + t * 600L;
            Map<String,Object> hit = new LinkedHashMap<>(); hit.put("type", "combat_hit"); hit.put("gameplay_session", session);
            hit.put("source_sequence", seq++); hit.put("source_tick", t); hit.put("observed_at_ms", at); hit.put("direction", "dealt_to_npc");
            hit.put("amount", 3); hit.put("hitsplat_type", 16); hit.put("npc_id", 3000); hit.put("target_key", target);
            Map<String,Object> end = new LinkedHashMap<>(); end.put("type", "combat_npc_end"); end.put("gameplay_session", session);
            end.put("source_sequence", seq++); end.put("source_tick", t + 3); end.put("observed_at_ms", at + 1800); end.put("target_key", target);
            end.put("end", "observed_death"); end.put("first_own_hit_tick", t);
            events.add(hit); events.add(end);
            events.add(legacy("loot", at + 3000, String.format("%032x", 1000 + i), "source_type", "npc", "items", Collections.singletonList(item(526, 1))));
        }
        for (int i = 0; i < 10; i++) events.add(legacy("loot", AT + 900_000L + i, String.format("%032x", 5000 + i), "source_type", "npc", "items", Collections.singletonList(item(526, 1))));
        Collections.shuffle(events, new Random(7));
        Map<String,Object> s = CombatEncounterTracker.deriveFromEvents(session, events, -1);
        assertEquals(32, encounters(s).size()); assertEquals(8L, s.get("closed_dropped"));
        for (Map<String,Object> e : encounters(s)) assertEquals("matched", e.get("loot_status"));
        assertEquals(8, ((List<?>) s.get("loot_not_matched")).size()); assertEquals(2L, s.get("loot_not_matched_dropped"));
        for (Object o : (List<?>) s.get("loot_not_matched")) assertEquals("unlinked", m(o).get("link_provenance"));
    }

    // Review r1 F3: an inferred end is bounded by the last own hit, not treated as still open.
    @Test public void replayGearChangeAfterInferredEndIsNotDuringEncounter()
    {
        NPC n = npc(3000); tick = 110; dealt(n, 4); tick = 112; dealt(n, 4);
        tick = 112 + CombatEncounterTracker.INACTIVE_TICKS + 1; dealt(npc(3001), 4);
        List<Map<String,Object>> wire = new ArrayList<>();
        for (Map<String,Object> h : handed) wire.add(PublicTransport.event(withEnvelope(h)));
        wire.add(legacy("equip_change", AT + 400 * 600L, "33333333333333333333333333333333", "equipped", Collections.singletonList(item(11235, 1))));
        Map<String,Object> s = CombatEncounterTracker.deriveFromEvents((String) wire.get(0).get("gameplay_session"), wire, tick);
        Map<String,Object> first = encounters(s).get(0);
        assertEquals("unobserved_end_inactivity", first.get("outcome"));
        assertEquals("not_observed", m(first.get("equipment")).get("changed_during"));
        assertEquals(true, m(encounters(s).get(1).get("equipment")).get("changed_during")); // still open
    }

    // Review r1 F4: an interrupt never ends an encounter before its last own hit.
    @Test public void sceneGapAfterHitBeforePollNeverGivesNegativeElapsed()
    {
        tick = 200; dealt(npc(3000), 4); capture.gap();
        Map<String,Object> e = encounters(capture.encounterSummary(-1)).get(0);
        assertEquals(200, e.get("end_tick")); assertEquals(0, e.get("elapsed_ticks"));
        tick = 300; dealt(npc(3001), 4); capture.gap(); capture.gap();
        assertEquals(300, encounters(capture.encounterSummary(-1)).get(1).get("end_tick"));
    }

    // Review r1 F5: unknown gear at start, then readable gear, is a baseline, not a change.
    @Test public void firstReadableGearMidEncounterIsNotAChange()
    {
        ItemContainer known = client.getItemContainer(InventoryID.EQUIPMENT);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(null);
        NPC n = npc(3000); tick = 110; dealt(n, 4);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(known); tick = 112; dealt(n, 4);
        Map<?,?> gear = m(encounters(capture.encounterSummary(tick)).get(0).get("equipment"));
        assertEquals("unknown", gear.get("at_start")); assertEquals(1, ((List<?>) gear.get("revisions_observed")).size());
        assertEquals(false, gear.get("changed_during"));
    }

    // Review r1 F6: reading the summary does not change later results.
    @Test public void summaryIsReadOnly()
    {
        NPC n = npc(3000); tick = 110; dealt(n, 4);
        Map<String,Object> peek = capture.encounterSummary(110 + CombatEncounterTracker.INACTIVE_TICKS + 5);
        assertEquals("unobserved_end_inactivity", encounters(peek).get(0).get("outcome"));
        tick = 150; dealt(n, 4); // the tracker never expired it, so this is the same encounter
        List<Map<String,Object>> list = encounters(capture.encounterSummary(tick));
        assertEquals(1, list.size()); assertEquals("open", list.get(0).get("outcome"));
    }

    // Integrator reproduction on 95462e6: worn tick 10, scene gap tick 11, own hit tick 12 with no readable worn.
    @Test public void sceneGapForgetsGearSoUnreadableNextEncounterIsUnknown()
    {
        CombatEncounterTracker t = new CombatEncounterTracker("dddddddd-dddd-dddd-dddd-dddddddddddd");
        Map<String,Object> slots = new LinkedHashMap<>(); slots.put("weapon", 861);
        Map<String,Object> wv = new LinkedHashMap<>(); wv.put("content_id", CombatEncounterTracker.contentId(slots)); wv.put("slots", slots);
        Map<String,Object> w = new LinkedHashMap<>(); w.put("local_input_id", "local:1"); w.put("kind", "combat.worn_revision");
        w.put("tick", 10); w.put("observed_at_ms", AT); w.put("value", wv);
        t.accept(w); t.interrupt("scene_gap", 11);
        t.accept(row(1, "combat.own_hits", 12, "dealt_to_npc", 3, 16, "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", 3000));
        assertEquals("unknown", m(encounters(t.summary(12)).get(0).get("equipment")).get("at_start"));
    }

    @Test public void captureGapThenUnreadableContainerGivesUnknownGear()
    {
        tick = 110; dealt(npc(3000), 4); capture.gap();
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(null);
        tick = 112; dealt(npc(3001), 4);
        assertEquals("unknown", m(encounters(capture.encounterSummary(tick)).get(1).get("equipment")).get("at_start"));
    }

    @Test public void captureGapThenSameReadableGearIsFreshBaseline()
    {
        tick = 110; dealt(npc(3000), 4); capture.gap();
        tick = 112; dealt(npc(3001), 4); // unchanged gear must still be re-observed after the gap
        Map<?,?> gear = m(encounters(capture.encounterSummary(tick)).get(1).get("equipment"));
        assertEquals(861, m(m(gear.get("at_start")).get("slots")).get("weapon")); assertEquals(112, m(gear.get("at_start")).get("observed_tick"));
    }

    @Test public void unreadableContainerMidSessionInvalidatesOldGearForLaterEncounters()
    {
        NPC a = npc(3000); tick = 110; dealt(a, 4); tick = 111; capture.endNpc(client, a, true, AT);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(null);
        NPC b = npc(3001); tick = 113; dealt(b, 4);
        assertEquals("unknown", m(encounters(capture.encounterSummary(tick)).get(1).get("equipment")).get("at_start"));
        wear(worn); tick = 115; dealt(npc(3002), 4); // same gear readable again: fresh baseline, not unknown forever
        Map<?,?> start = m(m(encounters(capture.encounterSummary(tick)).get(2).get("equipment")).get("at_start"));
        assertEquals(861, m(start.get("slots")).get("weapon")); assertEquals(115, start.get("observed_tick"));
    }

    // Review r2 F1: unreadable between two equal readable reads is a gap, not a gear change.
    @Test public void unreadableBetweenEqualReadsIsGapNotChange()
    {
        ItemContainer known = client.getItemContainer(InventoryID.EQUIPMENT);
        NPC n = npc(3000); tick = 110; dealt(n, 4);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(null); tick = 112; dealt(n, 4);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(known); tick = 114; dealt(n, 4);
        Map<String,Object> e = encounters(capture.encounterSummary(tick)).get(0);
        assertEquals(false, m(e.get("equipment")).get("changed_during"));
        assertTrue(((List<?>) e.get("gaps")).contains("equipment_unreadable"));
        worn[3] = new Item(11235, 1); tick = 116; dealt(n, 4);
        assertEquals(true, m(encounters(capture.encounterSummary(tick)).get(0).get("equipment")).get("changed_during"));
    }

    @Test public void nullItemArrayIsUnknownNotEmptyLoadout()
    {
        ItemContainer c = mock(ItemContainer.class); when(c.getItems()).thenReturn(null);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(c);
        tick = 110; dealt(npc(3000), 4);
        assertEquals("unknown", m(encounters(capture.encounterSummary(tick)).get(0).get("equipment")).get("at_start"));
    }

    @Test public void gearBecomingUnreadableDuringEncounterKeepsStartAndFlagsGap()
    {
        NPC n = npc(3000); tick = 110; dealt(n, 4);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(null); tick = 112; dealt(n, 4);
        Map<String,Object> e = encounters(capture.encounterSummary(tick)).get(0);
        assertTrue(m(e.get("equipment")).get("at_start") instanceof Map);
        assertTrue(((List<?>) e.get("gaps")).contains("equipment_unreadable"));
    }

    @Test public void contentIdMatchesLoadoutRevisionFixture()
    {
        // loadout.revision.v1 fixture loadout-revisions-example.json (commit 6fd299f), revisions 1 and 2.
        Map<String,Object> a = new LinkedHashMap<>(); a.put("ammo", 11212); a.put("body", 10551); a.put("weapon", 4151); a.put("head", 10828);
        assertEquals("6cd9c6ed233e4fea4ca79ab44714cbf1389faf07bb25df876fe77dd08b32976d", CombatEncounterTracker.contentId(a));
        a.put("weapon", 12006);
        assertEquals("bee9e86817610841690a5a491249bf4e3447d3acdecda497e7da931ae2cdb74e", CombatEncounterTracker.contentId(a));
    }
}
