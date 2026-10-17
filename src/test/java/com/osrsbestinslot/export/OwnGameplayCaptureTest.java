package com.osrsbestinslot.export;

import java.lang.reflect.*;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import okhttp3.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnGameplayCaptureTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private Client client;
    private Player self;
    private int[] vars;
    private int tick;
    private OwnGameplayCapture capture;

    @Before public void setup()
    {
        client = mock(Client.class); self = mock(Player.class); capture = new OwnGameplayCapture();
        vars = new int[5000]; tick = 100;
        when(client.getVarps()).thenReturn(vars); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(self); when(client.getAccountHash()).thenReturn(42L);
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
        when(client.getTickCount()).thenAnswer(i -> tick);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
    }
    private void bit(int id, int index)
    {
        VarbitComposition d = mock(VarbitComposition.class);
        when(d.getIndex()).thenReturn(index); when(d.getMostSignificantBit()).thenReturn(15);
        when(client.getVarbit(id)).thenReturn(d);
    }
    private void settle()
    {
        assertFalse(capture.ready(client, TOKEN)); tick += 2; assertTrue(capture.ready(client, TOKEN));
    }
    private List<Map<String,Object>> rows()
    {
        return (List<Map<String,Object>>) capture.snapshot().get("observations");
    }
    private Map<?,?> state(String key)
    {
        return (Map<?,?>) ((Map<?,?>)((Map<?,?>)capture.snapshot().get("states")).get(key)).get("value");
    }
    private Hitsplat splat(int amount, boolean mine)
    {
        Hitsplat s = mock(Hitsplat.class);
        when(s.getAmount()).thenReturn(amount); when(s.getHitsplatType()).thenReturn(16);
        when(s.isMine()).thenReturn(mine); return s;
    }
    private NPC npc(int id)
    {
        NPC n = mock(NPC.class); when(n.getId()).thenReturn(id); return n;
    }

    @Test public void carriedPouchStateChangesRemainAbsoluteAndResetAfterSceneGap()
    {
        settle();ItemContainer inventory=mock(ItemContainer.class);
        when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventory);
        when(inventory.getItems()).thenReturn(new Item[]{new Item(ItemID.RUNE_POUCH,1)});
        capture.poll(client,1000);tick++;capture.poll(client,1600);
        List<Map<String,Object>> pouch=new ArrayList<>();
        for(Map<String,Object> r:rows())if("storage.rune_pouch".equals(r.get("kind")))pouch.add(r);
        assertEquals(1,pouch.size());assertEquals("baseline",((Map<?,?>)pouch.get(0).get("value")).get("observation"));
        when(inventory.getItems()).thenReturn(new Item[0]);tick++;capture.poll(client,2200);
        assertEquals("not_observed_carried",state("storage.rune_pouch").get("possession"));
        Map<?,?> last=rows().get(rows().size()-1); // Find the pouch update independently of other activity decoders.
        for(Map<String,Object> r:rows())if("storage.rune_pouch".equals(r.get("kind")))last=r;
        assertEquals("absolute_update",((Map<?,?>)last.get("value")).get("observation"));
        capture.gap();tick++;capture.poll(client,2800);
        for(Map<String,Object> r:rows())if("storage.rune_pouch".equals(r.get("kind")))last=r;
        assertEquals("baseline",((Map<?,?>)last.get("value")).get("observation"));
    }

    @Test public void rejectsOtherPlayersAndOtherPlayersNpcHitsBeforeReadingTheirFields()
    {
        settle(); Player other = mock(Player.class); NPC n = npc(1);
        capture.hit(client, other, splat(9, true), 1000);
        capture.hit(client, n, splat(9, false), 1000);
        assertTrue(rows().isEmpty()); verifyNoInteractions(other);
        verify(n, never()).getName(); verify(n, never()).getId();
        capture.hit(client, self, splat(0, false), 1000);
        capture.hit(client, n, splat(9, true), 1000);
        assertEquals(2, rows().size()); // own zero-damage splats are observations, not missing
        verify(n, never()).getName(); verify(self, never()).getInteracting();
    }

    @Test public void duplicateCallbackIsNotSecondHitButEqualDistinctHitsSurvive()
    {
        settle(); Hitsplat hit = splat(5, false);
        capture.hit(client, self, hit, 1000); capture.hit(client, self, hit, 1000);
        capture.hit(client, self, splat(5, false), 1000);
        assertEquals(2, rows().size());
        assertNotEquals(rows().get(0).get("event_id"), rows().get(1).get("event_id"));
    }

    @Test public void onlyOwnCombatHandsOffTypedEventsWithExplicitNonAckCounters()
    {
        List<Map<String,Object>> handed = new ArrayList<>();
        capture = new OwnGameplayCapture(row -> { handed.add(row); return handed.size() == 1; });
        settle(); Player other=mock(Player.class); NPC n=npc(42);
        capture.hit(client,other,splat(8,true),1000);capture.hit(client,n,splat(8,false),1000);
        assertTrue(handed.isEmpty());
        capture.hit(client,self,splat(0,false),1000);capture.hit(client,n,splat(8,true),1001);
        capture.endNpc(client,n,false,1002);
        assertEquals(3,handed.size());assertEquals("combat_hit",handed.get(0).get("type"));
        assertEquals("received",handed.get(0).get("direction"));assertFalse(handed.get(0).containsKey("npc_id"));
        assertEquals(0,handed.get(0).get("amount"));
        assertEquals(handed.get(1).get("target_key"),handed.get(2).get("target_key"));
        assertEquals("despawn_not_death",handed.get(2).get("end"));assertEquals("unknown",handed.get(2).get("kill_credit"));
        assertEquals(3L,capture.snapshot().get("combat_events_observed"));
        assertEquals(1L,capture.snapshot().get("combat_events_handed_off"));
        assertEquals(2L,capture.snapshot().get("combat_events_not_handed_off"));
        verifyNoInteractions(other);verify(n,never()).getName();
        capture.reset();assertEquals(0L,capture.snapshot().get("combat_events_observed"));
    }

    @Test public void despawnIsNotKillAndReusedNpcIndexGetsNewIdentity()
    {
        settle(); NPC first = npc(1), second = npc(1);
        when(first.getIndex()).thenReturn(7); when(second.getIndex()).thenReturn(7);
        capture.hit(client, first, splat(3,true), 1000);
        Object key = ((Map<?,?>)rows().get(0).get("value")).get("target_observation_id");
        capture.endNpc(client, first, false, 1001);
        Map<?,?> end = (Map<?,?>)rows().get(1).get("value");
        assertEquals("despawn_not_death", end.get("end")); assertEquals("unknown", end.get("kill_credit"));
        capture.endNpc(client, first, true, 1002); assertEquals(2, rows().size());
        capture.hit(client, second, splat(3,true), 1003);
        assertNotEquals(key, ((Map<?,?>)rows().get(2).get("value")).get("target_observation_id"));
        capture.endNpc(client, second, true, 1004);
        assertEquals("observed_death", ((Map<?,?>)rows().get(3).get("value")).get("end"));
    }

    @Test public void sceneGapBreaksTargetContinuityAndStartsAbsoluteBaselines()
    {
        settle(); NPC n = npc(1); capture.poll(client,1000); capture.hit(client,n,splat(3,true),1000);
        int before = rows().size(); capture.gap(); capture.endNpc(client,n,true,1001);
        assertEquals(before, rows().size()); tick++; capture.poll(client,1002);
        assertEquals("baseline", ((Map<?,?>)rows().get(before).get("value")).get("observation"));
        assertEquals("scene_gap",capture.snapshot().get("gap_reason"));
    }

    @Test public void tokenAccountModeAndIronTypeChangesClearHistoryAndRequireSettlement()
    {
        settle(); capture.hit(client,self,splat(3,false),1000);
        String session=(String)capture.snapshot().get("collector_session");
        when(client.getAccountHash()).thenReturn(43L); assertFalse(capture.ready(client,TOKEN));
        tick+=2; assertTrue(capture.ready(client,TOKEN)); assertTrue(rows().isEmpty());
        assertNotEquals(session,capture.snapshot().get("collector_session"));
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.SEASONAL));
        assertFalse(capture.ready(client,TOKEN)); tick+=2; assertTrue(capture.ready(client,TOKEN));
        assertNotEquals("STANDARD",capture.snapshot().get("profile_type"));
        bit(1777,200); vars[200]=1; assertFalse(capture.ready(client,TOKEN));
        tick+=2; assertTrue(capture.ready(client,TOKEN));
        assertFalse(capture.ready(client,"new-grant-epoch")); assertTrue(rows().isEmpty());
    }

    @Test public void invalidSessionAndClockResetCannotReuseObservations()
    {
        settle(); capture.hit(client,self,splat(3,false),1000);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        assertFalse(capture.ready(client,TOKEN)); assertTrue(rows().isEmpty());
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN); settle(); tick=0;
        assertFalse(capture.ready(client,TOKEN)); assertNull(capture.snapshot().get("collector_session"));
    }

    @Test public void missingVarDefinitionsAreUnknownAndBarrowsStaysRaw()
    {
        settle(); capture.poll(client,1000);
        assertNull(state("combat.barrows_state").get("ahrim"));
        for(int i=0;i<8;i++) bit(457+i,200+i);
        vars[200]=1; vars[203]=1; vars[206]=80; vars[207]=4;
        tick++; capture.poll(client,1001);
        Map<?,?> b=state("combat.barrows_state");
        assertEquals(1,b.get("ahrim")); assertEquals(0,b.get("dharok")); assertEquals(1,b.get("karil"));
        assertEquals(80,b.get("reward_potential_raw")); // never double-add brother bonuses
        assertEquals(4,b.get("npcs_slain_raw"));
    }

    @Test public void raidPointsRequireInRaidAndReadPersonalVarpNotPartyVarbit()
    {
        settle(); bit(5432,210); vars[4609]=1234;
        capture.poll(client,1000); assertNull(state("combat.raid_points").get("personal_points"));
        vars[210]=1; tick++; capture.poll(client,1001);
        assertEquals(1234,state("combat.raid_points").get("personal_points"));
        assertFalse(state("combat.raid_points").containsKey("total_points"));
        vars[210]=0; tick++; capture.poll(client,1002);
        assertNull(state("combat.raid_points").get("personal_points"));
    }

    @Test public void effectTransitionsStayObservedRawWithoutPredictedExpiryOrSpoofableChat()
    {
        settle(); bit(16270,220); bit(12411,221); bit(12138,222);
        vars[220]=10; vars[221]=1; vars[222]=50; capture.poll(client,1000);
        assertEquals(10,state("effects.surge").get("timer_raw"));
        int before=rows().size(); tick++; capture.poll(client,999); // wall clock rollback does not change value
        assertEquals(before,rows().size());
        vars[220]=0; vars[221]=0; tick++; capture.poll(client,1002);
        assertEquals(0,state("effects.surge").get("timer_raw"));
        assertEquals(0,state("effects.death_charge").get("active_raw"));
        assertEquals(before+2,rows().size());
    }

    @Test public void goalEditsMissingAndInvalidRangesRemainDistinct()
    {
        settle(); vars[1229]=100; vars[1253]=100; capture.poll(client,1000);
        assertEquals("invalid",((Map<?,?>)state("skills.goal_xp").get("ATTACK")).get("status"));
        vars[1253]=200000000; tick++; capture.poll(client,1001);
        assertEquals("configured",((Map<?,?>)state("skills.goal_xp").get("ATTACK")).get("status"));
        vars[1253]=0; tick++; capture.poll(client,1002);
        assertEquals("unset",((Map<?,?>)state("skills.goal_xp").get("ATTACK")).get("status"));
        when(client.getVarps()).thenReturn(new int[100]); tick++; capture.poll(client,1003);
        assertEquals("unknown",((Map<?,?>)state("skills.goal_xp").get("ATTACK")).get("status"));
        assertEquals(23,state("skills.goal_xp").size()); // Sailing goals not established by this source
    }

    @Test public void bufferIsBoundedWithExplicitLossAndStableOldSnapshots()
    {
        settle(); capture.poll(client,1000); Map<String,Object> old=capture.snapshot(); int baseline=rows().size();
        for(int i=0;i<300;i++) { tick++; capture.hit(client,self,splat(1,false),1000+i); }
        assertEquals(OwnGameplayCapture.MAX_OBSERVATIONS,rows().size());
        assertEquals(300+baseline-OwnGameplayCapture.MAX_OBSERVATIONS,capture.snapshot().get("evicted")); assertEquals("buffer_eviction",capture.snapshot().get("gap_reason"));
        assertEquals(false,capture.snapshot().get("complete"));
        assertEquals(baseline,((List<?>)old.get("observations")).size());
    }

    @Test public void escapeSettingsAndArenaAvailabilityAreNotPossessionIdleOrCurrency()
    {
        settle(); bit(14838,230); bit(14849,231); bit(14857,232);
        bit(5964,233); bit(10732,234); bit(5962,235);
        vars[230]=1; vars[231]=20; vars[232]=1; vars[233]=1; vars[235]=1;
        capture.poll(client,1000);
        assertEquals(20,state("effects.escape_crystal").get("afk_delay_raw"));
        assertEquals(3,state("effects.escape_crystal").size()); // no invented idle/possession fields
        assertEquals(1,state("minigame.arena_state").get("ticket_available_raw"));
        vars[235]=0; tick++; capture.poll(client,1001);
        assertEquals(0,state("minigame.arena_state").get("ticket_available_raw"));
        assertEquals(3,state("minigame.arena_state").size());
    }

    @Test public void wideGoalUpdatesCannotOverflowTheSnapshotRequestBudget()
    {
        settle();
        for(int i=0;i<300;i++) { vars[1253]=1000+i; tick++; capture.poll(client,1000+i); }
        int bytes = new com.google.gson.Gson().toJson(capture.snapshot()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue("recent block leaves room in the existing 512KiB snapshot contract", bytes < 128 * 1024);
        assertTrue((Integer)capture.snapshot().get("evicted") > 0);
        assertTrue(rows().size() < OwnGameplayCapture.MAX_OBSERVATIONS);
        System.out.println("OWN_GAMEPLAY_WIDE_FIXTURE_BYTES="+bytes+" observations="+rows().size());
    }

    private static void inject(Object target,String key,Object value) throws Exception
    {
        Field f=target.getClass().getDeclaredField(key); f.setAccessible(true); f.set(target,value);
    }

    private TileItem groundClue(int owner,int id,int expiry)
    {
        TileItem item=mock(TileItem.class); when(item.getOwnership()).thenReturn(owner);
        when(item.getId()).thenReturn(id); when(item.getQuantity()).thenReturn(1); when(item.getDespawnTime()).thenReturn(expiry);
        return item;
    }

    @Test public void groundClueOwnershipPrecedesIdentityAndNoOtherPlayerFieldsAreRead()
    {
        settle();
        for(int owner:new int[]{TileItem.OWNERSHIP_NONE,TileItem.OWNERSHIP_OTHER,TileItem.OWNERSHIP_GROUP,99})
        {
            TileItem other=groundClue(owner,23182,500);capture.groundClues.spawned(client,other,1000);
            verify(other,never()).getId();verify(other,never()).getQuantity();verify(other,never()).getDespawnTime();
        }
        assertTrue(rows().isEmpty());
        TileItem own=groundClue(TileItem.OWNERSHIP_SELF,23182,500);
        capture.groundClues.spawned(client,own,1000);capture.groundClues.spawned(client,own,1001);
        assertEquals(1,rows().size());Map<?,?> value=(Map<?,?>)rows().get(0).get("value");
        assertEquals(500,value.get("despawn_server_tick"));assertEquals("unverified",value.get("drop_origin"));
        assertEquals("current_client_session_only",value.get("clock_scope"));
        assertFalse(value.containsKey("x"));assertFalse(value.containsKey("player"));verify(client,never()).getPlayers();
    }

    @Test public void groundClueMissingExpirySceneGapAndCapacityNeverClaimACompletedTimer()
    {
        settle();TileItem own=groundClue(TileItem.OWNERSHIP_SELF,19835,0);
        capture.groundClues.spawned(client,own,1000);assertNull(((Map<?,?>)rows().get(0).get("value")).get("despawn_server_tick"));
        capture.groundClues.despawned(client,own,1001);assertEquals("taken_expired_or_scene_change_unknown",((Map<?,?>)rows().get(1).get("value")).get("outcome"));
        capture.groundClues.spawned(client,own,1002);capture.gap();int before=rows().size();
        capture.groundClues.despawned(client,own,1003);assertEquals(before,rows().size());
        for(int i=0;i<65;i++)capture.groundClues.spawned(client,groundClue(TileItem.OWNERSHIP_SELF,23182,600+i),2000+i);
        assertEquals(1,rows().stream().filter(r->"clues.ground_gap".equals(r.get("kind"))).count());
        when(client.getAccountHash()).thenReturn(99L);assertFalse(capture.ready(client,TOKEN));assertTrue(rows().isEmpty());
        capture.groundClues.despawned(client,own,3000);assertTrue(rows().isEmpty());
    }

    @Test public void groundClueActualSubscriberRequiresCurrentGrantAndRevocationClearsTracking() throws Exception
    {
        AccountConnectPlugin p=plugin();TileItem own=groundClue(TileItem.OWNERSHIP_SELF,23182,500);
        ItemSpawned spawn=new ItemSpawned(null,own);p.onItemSpawned(spawn);assertNull(p.gameplayBlock());
        verify(own,never()).getOwnership();
        p.applyServerPolicy(policy("X-Own-Gameplay","on",200),TOKEN);p.gameplayBlock();tick+=2;p.onItemSpawned(spawn);
        assertEquals(1,((List<?>)p.gameplayBlock().get("observations")).size());
        p.applyServerPolicy(policy("X-Own-Gameplay","off",200),TOKEN);p.onItemDespawned(new ItemDespawned(null,own));assertNull(p.gameplayBlock());
        p.applyServerPolicy(policy("X-Own-Gameplay","on",200),TOKEN);p.gameplayBlock();tick+=2;
        p.onItemDespawned(new ItemDespawned(null,own));assertTrue(((List<?>)p.gameplayBlock().get("observations")).isEmpty());
    }
    private AccountConnectPlugin plugin() throws Exception
    {
        AccountConnectPlugin p=new AccountConnectPlugin();
        AccountConnectConfig config=mock(AccountConnectConfig.class);
        when(config.linkToken()).thenReturn(TOKEN); when(config.apiBaseUrl()).thenReturn("http://localhost");
        inject(p,"config",config); inject(p,"client",client); inject(p,"gson",new com.google.gson.Gson());
        return p;
    }
    private Response policy(String key,String value,int status)
    {
        return new Response.Builder().request(new Request.Builder().url("http://localhost").build())
            .protocol(Protocol.HTTP_1_1).code(status).message("fixture").header(key,value).build();
    }

    @Test public void unchangedPollDoesNotTriggerUploadButRealStateAndHitsStillDo() throws Exception
    {
        AccountConnectPlugin p=plugin(); settle(); capture.poll(client,1000);
        Map<String,Object> snapshot=new LinkedHashMap<>(); snapshot.put("own_gameplay",capture.snapshot());
        String before=p.canonicalHash(snapshot);
        tick++; capture.poll(client,2000); snapshot.put("own_gameplay",capture.snapshot());
        assertEquals(before,p.canonicalHash(snapshot));
        assertEquals(2000L,((Map<?,?>)snapshot.get("own_gameplay")).get("last_authoritative_read_ms"));
        vars[1253]=100000; tick++; capture.poll(client,3000); snapshot.put("own_gameplay",capture.snapshot());
        String changed=p.canonicalHash(snapshot); assertNotEquals(before,changed);
        capture.hit(client,self,splat(7,false),3001); snapshot.put("own_gameplay",capture.snapshot());
        assertNotEquals(changed,p.canonicalHash(snapshot));
    }

    @Test public void grantDefaultsOffAndCannotComeFromBroadProgressOldTokenOrFailedResponse() throws Exception
    {
        AccountConnectPlugin p=plugin(); assertNull(p.gameplayBlock());
        p.applyServerPolicy(policy("X-Broad-Capture","on",200),TOKEN);
        p.applyServerPolicy(policy("X-Account-Progress","on",200),TOKEN); assertFalse(p.gameplayGranted());
        p.applyServerPolicy(policy("X-Own-Gameplay","on",200),"old-token"); assertFalse(p.gameplayGranted());
        p.applyServerPolicy(policy("X-Own-Gameplay","on",500),TOKEN); assertFalse(p.gameplayGranted());
        p.applyServerPolicy(policy("X-Own-Gameplay","on",200),TOKEN);
        assertNull(p.gameplayBlock()); tick+=2; assertNotNull(p.gameplayBlock());
        p.applyServerPolicy(policy("X-Other","on",200),TOKEN); assertNull(p.gameplayBlock());
    }

    @Test public void actualHandlersCaptureOwnHitsOnlyAndRevocationStripsCachedWirePayload() throws Exception
    {
        AccountConnectPlugin p=plugin(); p.applyServerPolicy(policy("X-Own-Gameplay","on",200),TOKEN);
        p.gameplayBlock(); tick+=2;
        HitsplatApplied e=new HitsplatApplied(); e.setActor(self); e.setHitsplat(splat(7,false));
        p.onHitsplatApplied(e);
        assertEquals(1,((List<?>)p.gameplayBlock().get("observations")).size());
        Map<String,Object> cached=new LinkedHashMap<>(); cached.put("own_gameplay",p.gameplayBlock());
        OkHttpClient http=mock(OkHttpClient.class); when(http.newCall(any(Request.class))).thenReturn(mock(Call.class));
        inject(p,"okHttpClient",http);
        Method send=AccountConnectPlugin.class.getDeclaredMethod("postSnapshot",String.class,Map.class,String.class);
        send.setAccessible(true); send.invoke(p,TOKEN,cached,"hash");
        p.applyServerPolicy(policy("X-Own-Gameplay","off",200),TOKEN); send.invoke(p,TOKEN,cached,"hash");
        org.mockito.ArgumentCaptor<Request> requests=org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(http,times(2)).newCall(requests.capture());
        okio.Buffer first=new okio.Buffer(), second=new okio.Buffer();
        requests.getAllValues().get(0).body().writeTo(first); requests.getAllValues().get(1).body().writeTo(second);
        assertTrue(first.readUtf8().contains("own_gameplay")); assertFalse(second.readUtf8().contains("own_gameplay"));
        assertTrue(cached.containsKey("own_gameplay"));
        assertNull(p.gameplayBlock());
    }

    @Test public void newSystemAndClientTickHandlersRequireSeparateGrantAndRevocationClearsThem() throws Exception
    {
        AccountConnectPlugin p=plugin();
        ChatMessage msg=new ChatMessage(); msg.setType(ChatMessageType.GAMEMESSAGE);
        msg.setMessage("Your quetzal whistle has 42 charges remaining.");
        p.onChatMessage(msg); p.onOwnActivityClientTick(new ClientTick());
        verify(client,never()).getWidget(193,2); assertNull(p.gameplayBlock());
        p.applyServerPolicy(policy("X-Own-Gameplay","on",200),TOKEN); p.gameplayBlock(); tick+=2;
        p.onChatMessage(msg);
        assertEquals(1,((List<?>)p.gameplayBlock().get("observations")).size());
        p.applyServerPolicy(policy("X-Own-Gameplay","off",200),TOKEN); assertNull(p.gameplayBlock());
        p.onChatMessage(msg); assertNull(p.gameplayBlock());
    }
}
