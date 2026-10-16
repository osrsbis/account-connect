package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.widgets.Widget;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnActivityCaptureTest
{
    Client client; Player self; OwnGameplayCapture capture; int[] vars; int tick;
    @Before public void setup()
    {
        client=mock(Client.class); self=mock(Player.class); capture=new OwnGameplayCapture(); vars=new int[6000]; tick=100;
        when(client.getVarps()).thenReturn(vars); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(self); when(client.getAccountHash()).thenReturn(42L);
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class)); when(client.getTickCount()).thenAnswer(i->tick);
        assertFalse(capture.ready(client,"grant")); tick+=2; assertTrue(capture.ready(client,"grant"));
    }
    void bit(int id,int index)
    {
        VarbitComposition d=mock(VarbitComposition.class); when(d.getIndex()).thenReturn(index);
        when(d.getMostSignificantBit()).thenReturn(15); when(client.getVarbit(id)).thenReturn(d);
    }
    Map<?,?> value(String family)
    {
        return (Map<?,?>)((Map<?,?>)((Map<?,?>)capture.snapshot().get("states")).get(family)).get("value");
    }
    List<Map<String,Object>> rows() { return (List<Map<String,Object>>)capture.snapshot().get("observations"); }
    void chat(ChatMessageType type,String text)
    {
        ChatMessage e=new ChatMessage(); e.setType(type); e.setMessage(text); capture.activities.chat(client,e,1000);
    }
    @Test public void countersKeepMissingZeroAndCoarseTimerDistinct()
    {
        bit(5099,100); bit(455,101); vars[100]=20; vars[101]=7;
        capture.poll(client,1000); assertEquals(20,value("minigame.tears").get("countdown_raw"));
        assertNull(value("minigame.tears").get("collecting_raw"));
        tick++; capture.poll(client,1001); assertEquals(20,value("minigame.tears").get("countdown_raw"));
        vars[100]=0; tick++; capture.poll(client,1002); assertEquals(0,value("minigame.tears").get("countdown_raw"));
    }
    @Test public void totemVarpOnlyChangeAndEveryAnimalSlotUseCorrectNamespace()
    {
        for(int site=0;site<8;site++) for(int f=0;f<11;f++)
        { bit(OwnActivityMappings.TOTEMS[site][f],100+site*11+f); vars[100+site*11+f]=site*11+f; }
        vars[4764]=99; capture.poll(client,1000);
        Map<?,?> site8=(Map<?,?>)value("minigame.totem_state").get("site_8");
        assertEquals(83,site8.get("animal_1_raw")); assertEquals(84,site8.get("animal_2_raw")); assertEquals(85,site8.get("animal_3_raw"));
        vars[4764]=100; tick++; capture.poll(client,1001);
        assertEquals(100,value("minigame.totem_state").get("research_points_raw"));
        vars[177]=0; tick++; capture.poll(client,1002);
        assertEquals(0,((Map<?,?>)value("minigame.totem_state").get("site_8")).get("base_raw"));
        verify(client,never()).getVarbit(4764);
    }
    @Test public void chartingHas358UniquePinnedIdsAndNoNearestTaskInference()
    {
        assertEquals(358,Arrays.stream(OwnActivityMappings.CHARTING).distinct().count());
        int id=OwnActivityMappings.CHARTING[357]; bit(id,100); vars[100]=1;
        capture.poll(client,1000); assertEquals(358,value("sailing.charting").size());
        assertEquals(1,value("sailing.charting").get(String.valueOf(id)));
        assertNull(value("sailing.charting").get(String.valueOf(OwnActivityMappings.CHARTING[0])));
        vars[100]=0; tick++; capture.poll(client,1001); assertEquals(0,value("sailing.charting").get(String.valueOf(id)));
    }
    @Test public void ownContributionDoesNotReadTeamBottlesOrPlayerSlots()
    {
        bit(2290,100); vars[100]=33; capture.poll(client,1000);
        assertEquals(Collections.singletonMap("own_contribution_raw",33),value("minigame.brewing"));
        verify(client,never()).getVarbit(2286); verify(client,never()).getVarbit(2287);
        for(int i=6442;i<=6446;i++) verify(client,never()).getVarbit(i);
        verify(client,never()).getPlayers();
    }
    @Test public void orderReplacementAndTrialSwitchAreAbsoluteNotCompletion()
    {
        bit(2483,100); bit(2485,101); bit(18427,102); bit(18432,103);
        vars[100]=7; vars[102]=2; capture.poll(client,1000);
        vars[100]=9; vars[101]=1; vars[102]=0; vars[103]=2; tick++; capture.poll(client,1001);
        assertEquals(9,value("minigame.restaurant_order").get("order_id_raw"));
        assertEquals(1,value("minigame.restaurant_order").get("refused_hard_raw"));
        assertEquals(2,value("sailing.trial_progress").get("jubbly_jive_raw"));
        assertFalse(value("sailing.trial_progress").containsKey("completed"));
    }
    @Test public void hiddenMissingUnknownAndLobbyHudClearRoomInsteadOfLeakingText()
    {
        bit(6440,100); vars[100]=2; Widget parent=mock(Widget.class), label=mock(Widget.class);
        when(client.getWidget(28,1)).thenReturn(parent); when(parent.getChild(2)).thenReturn(label);
        when(label.getText()).thenReturn("Sotetseg"); capture.poll(client,1000);
        assertEquals("Sotetseg",value("combat.raid_context").get("room_observed"));
        when(label.getText()).thenReturn("Other Player Private Name"); tick++; capture.poll(client,1001);
        assertNull(value("combat.raid_context").get("room_observed"));
        when(label.getText()).thenReturn("Sotetseg"); when(parent.isHidden()).thenReturn(true); tick++; capture.poll(client,1002);
        assertNull(value("combat.raid_context").get("room_observed"));
        when(parent.isHidden()).thenReturn(false); vars[100]=1; tick++; capture.poll(client,1003);
        assertNull(value("combat.raid_context").get("room_observed"));
        assertFalse(new com.google.gson.Gson().toJson(capture.snapshot()).contains("Private Name"));
    }
    @Test public void deathReadsOnlySelfAndDoesNotCountSpectatorOrDuplicateCallbacks()
    {
        Player other=mock(Player.class); capture.activities.death(client,other,1000); verifyNoInteractions(other); assertTrue(rows().isEmpty());
        bit(6440,100); vars[100]=1; capture.activities.death(client,self,1000);
        assertFalse(rows().stream().anyMatch(r->"combat.tob_room_death".equals(r.get("kind"))));
        vars[100]=2; tick++; capture.activities.death(client,self,1001); capture.activities.death(client,self,1001);
        assertEquals(1,rows().stream().filter(r->"combat.tob_room_death".equals(r.get("kind"))).count());
    }
    @Test public void publicChatCannotSpoofStructuredGameSignals()
    {
        chat(ChatMessageType.PUBLICCHAT,"You scratch a notch on your bow for the chompy bird kill.");
        chat(ChatMessageType.CLAN_CHAT,"You have completed 100 contracts with a total of 1,000 points.");
        assertTrue(rows().isEmpty());
        chat(ChatMessageType.SPAM,"You scratch a notch on your bow for the chompy bird kill.");
        chat(ChatMessageType.SPAM,"You scratch a notch on your bow for the chompy bird kill.");
        assertEquals(1,rows().size()); assertNull(((Map<?,?>)rows().get(0).get("value")).get("total_kills"));
    }
    @Test public void contractTotalsStayAbsoluteAndMalformedCountsAreRejected()
    {
        chat(ChatMessageType.GAMEMESSAGE,"You have completed 1,234 contracts with a total of 12,345 points.");
        assertEquals(1234L,((Map<?,?>)rows().get(0).get("value")).get("contracts_absolute"));
        chat(ChatMessageType.GAMEMESSAGE,"You have completed 1,2 contracts with a total of 12,345 points.");
        chat(ChatMessageType.GAMEMESSAGE,"You have completed 999999999999999999999999 contracts with a total of 1 point.");
        assertEquals(1,rows().size());
    }
    @Test public void storageAndCompostSignalsEmitNoRawNamesNoInventedPatchOrTotals()
    {
        chat(ChatMessageType.GAMEMESSAGE,"The coal bag contains one piece of coal.");
        chat(ChatMessageType.SPAM,"The grape barrel is partially full.");
        chat(ChatMessageType.GAMEMESSAGE,"You treat the allotment with ultracompost.");
        assertEquals(3,rows().size()); assertEquals(1L,((Map<?,?>)rows().get(0).get("value")).get("count_observed"));
        assertEquals("partial",((Map<?,?>)rows().get(1).get("value")).get("fill_observed"));
        assertEquals("unknown",((Map<?,?>)rows().get(2).get("value")).get("patch_identity"));
        chat(ChatMessageType.GAMEMESSAGE,"OtherPlayer received a drop: Secret.");
        assertEquals(3,rows().size()); assertFalse(new com.google.gson.Gson().toJson(capture.snapshot()).contains("allotment"));
    }
    @Test public void gapAllowsNewBaselineAndClearsMessageDedupState()
    {
        chat(ChatMessageType.GAMEMESSAGE,"The coal bag is empty."); capture.gap();
        chat(ChatMessageType.GAMEMESSAGE,"The coal bag is empty."); assertEquals(2,rows().size());
        capture.poll(client,1000); assertEquals("baseline",((Map<?,?>)rows().get(2).get("value")).get("observation"));
        capture.reset(); assertTrue(rows().isEmpty());
    }
}
