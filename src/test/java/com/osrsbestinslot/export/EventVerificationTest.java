package com.osrsbestinslot.export;

import java.awt.Rectangle;
import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class EventVerificationTest
{
    private Map<String, Object> event(String type)
    {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type); e.put("event_id", "abcdef0123456789abcdef0123456789");
        e.put("count", 42); e.put("account_hash", "123"); return e;
    }
    private EventVerification.Source source(Map<String, Object> e, String text)
    { return new EventVerification.Source(e, "token", "123", 4, 100, 123456, 1000, text); }
    private Client client()
    {
        Client c = mock(Client.class); when(c.getCanvasWidth()).thenReturn(800); when(c.getCanvasHeight()).thenReturn(600); return c;
    }
    private Widget text(String value, int id)
    {
        Widget w = mock(Widget.class); when(w.getText()).thenReturn(value); when(w.getId()).thenReturn(id);
        when(w.getBounds()).thenReturn(new Rectangle(10, 20, 400, 30)); return w;
    }

    @Test public void eventInsideRandomGapIsImmediateAndSharesHourlyBudgets()
    {
        VerificationProbe p = new VerificationProbe(n -> 0);
        assertNotNull(p.due(0));
        assertTrue(p.takeEvent(10));
        assertTrue(p.takeEvent(10));
        assertNull(p.due(11));
        for (int i = 3; i < 60; i++) assertTrue(p.takeEvent(i));
        p.reset();
        assertFalse(p.takeEvent(200));
        assertTrue(p.takeEvent(3600000));
    }
    @Test public void sourceRetainsDistinctIdsExpectedValuesAndRevisionAcrossLaterMutations()
    {
        EventVerification q = new EventVerification();
        Map<String,Object> e = event("loot"); Map<String,Object> item = new LinkedHashMap<>(); item.put("id", 1); item.put("qty", 2);
        e.put("items", Arrays.asList(item));
        EventVerification.Source a = source(e, null); assertTrue(q.offer(a));
        e.put("event_id", "second"); item.put("qty", 99);
        assertTrue(q.offer(source(e, null)));
        List<EventVerification.Source> got = q.drain();
        assertNotEquals(got.get(0).id, got.get(1).id);
        assertEquals(2, ((Map<?,?>)((List<?>)a.fields.get("items")).get(0)).get("qty"));
        assertEquals(123456L, a.common(103).get("snapshot_revision"));
        assertFalse(a.expired(103)); assertTrue(a.expired(104));
        assertFalse(a.matches("other", "123", 4)); assertFalse(a.matches("token", "456", 4)); assertFalse(a.matches("token", "123", 5));
    }
    @Test public void pendingQueueIsBoundedAndResetDropsOldSession()
    {
        EventVerification q = new EventVerification();
        for (int i = 0; i < EventVerification.MAX_PENDING; i++) assertTrue(q.offer(source(event("death"), null)));
        assertFalse(q.offer(source(event("death"), null)));q.reset();assertTrue(q.drain().isEmpty());
    }
    @Test public void bossEvidenceSelectsOnlyMatchingSystemLineAndRejectsDuplicatesOrChangedPixels()
    {
        Client c = client(); String message = "Your Vorkath kill count is: 42.";
        Widget w = text("<col=ff0000>"+message+"</col>", InterfaceID.Chatbox.LINE0);
        when(c.getWidget(InterfaceID.Chatbox.LINE0)).thenReturn(w);
        Widget bystander = text("Someone: Hi", InterfaceID.Chatbox.LINE1);when(c.getWidget(InterfaceID.Chatbox.LINE1)).thenReturn(bystander);
        List<VerificationFrame.Region> regions = EventVerificationScreen.regions(c, source(event("kill_count"), message));
        assertEquals(1,regions.size());assertEquals(42,regions.get(0).value);
        assertEquals("kill_count",regions.get(0).inputs.get("reader"));assertTrue(EventVerificationScreen.unchanged(c,regions));
        when(w.getText()).thenReturn("Someone: Hi");assertFalse(EventVerificationScreen.unchanged(c,regions));
        when(w.getText()).thenReturn(message);when(bystander.getText()).thenReturn(message);
        assertTrue(EventVerificationScreen.regions(c,source(event("kill_count"),message)).isEmpty());
    }
    @Test public void levelDialogueMayMaterializeLaterButUnrelatedOrbsAreNeverEvidence()
    {
        Client c=client();Map<String,Object> e=event("level_up");e.put("skill","Mining");e.put("level",9);
        EventVerification.Source source=source(e,null);
        assertTrue(EventVerificationScreen.regions(c,source).isEmpty());
        Widget w=text("Your Mining level is now 9.", InterfaceID.LevelupDisplay.TEXT2);
        when(c.getWidget(InterfaceID.LevelupDisplay.TEXT2)).thenReturn(w);
        List<VerificationFrame.Region> regions=EventVerificationScreen.regions(c,source);
        assertEquals(1,regions.size());assertEquals(9,regions.get(0).value);
        when(w.isHidden()).thenReturn(true);assertTrue(EventVerificationScreen.regions(c,source).isEmpty());
    }
    @Test public void minigameScreenExpectedComesFromEventNotWidget()
    {
        Client c=client();Object[] mapping=MinigameLines.SCREENS[0];
        Widget w=text("99",(int)mapping[1]);when(c.getWidget((int)mapping[1])).thenReturn(w);
        Map<String,Object> e=event("minigame");e.put("minigame",mapping[0]);e.put("screen",Collections.singletonMap(mapping[2],42));
        List<VerificationFrame.Region> regions=EventVerificationScreen.regions(c,source(e,null));
        assertEquals(1,regions.size());assertEquals(42,regions.get(0).value);
        assertEquals("screen."+mapping[2],regions.get(0).inputs.get("field"));
    }
    @Test public void hiddenClippedAndUnrelatedLootEvidenceStayMissing()
    {
        Client c=client();Widget w=text("Your Mining level is now 9.",1);Widget parent=text("",2);
        when(parent.getBounds()).thenReturn(new Rectangle(0,0,20,20));when(w.getParent()).thenReturn(parent);
        assertFalse(EventVerificationScreen.visible(c,w));
        Map<String,Object> e=event("loot");e.put("source_name","Unmapped monster");e.put("items",Arrays.asList(Collections.singletonMap("id",1)));
        assertTrue(EventVerificationScreen.regions(c,source(e,null)).isEmpty());
    }
    @Test public void eventMetadataAndBoundedTextSurviveProjectionWithoutClaimedPass()
    {
        Map<String,Object> e=event("verification");e.putAll(source(event("kill_count"),null).common(102));
        String text="You have completed the challenge!";
        e.put("results",Arrays.asList(VerificationProbe.result("message",VerificationProbe.inputs("reader","text","field","verification_text"),text,null)));
        Map<String,Object> wire=PublicTransport.event(e,false);
        assertEquals(100,wire.get("source_event_tick"));assertEquals(123456L,wire.get("source_event_revision"));
        Map<?,?> result=(Map<?,?>)((List<?>)wire.get("results")).get(0);
        assertEquals(text,result.get("expected"));assertNull(result.get("match"));
        assertNull(EventVerification.clean(String.join("",Collections.nCopies(385,"x"))));
    }
    @Test public void rewardQuantityAndWidgetAttachmentAreRecheckedBeforeDrawing()
    {
        Client c=client();int group=InterfaceID.RAIDS_REWARDS;int id=(group<<16)|1;
        Widget w=text("",id);when(w.getItemId()).thenReturn(1001);when(w.getItemQuantity()).thenReturn(2);
        when(c.getWidget(group,1)).thenReturn(w);when(c.getWidget(id)).thenReturn(w);
        Map<String,Object> e=event("loot");e.put("source_name","Chambers of Xeric");
        e.put("items",Arrays.asList(VerificationProbe.inputs("id",1001,"qty",2)));
        List<VerificationFrame.Region> regions=EventVerificationScreen.regions(c,source(e,null));
        assertEquals(1,regions.size());assertTrue(EventVerificationScreen.unchanged(c,regions));
        when(w.getItemQuantity()).thenReturn(3);assertFalse(EventVerificationScreen.unchanged(c,regions));
        when(w.getItemQuantity()).thenReturn(2);Widget replacement=text("",id);when(c.getWidget(id)).thenReturn(replacement);
        assertFalse(EventVerificationScreen.unchanged(c,regions));
    }
    @Test public void grantClearCannotRaceQueueCopyIntoNullSources() throws Exception
    {
        EventVerification q=new EventVerification();
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try
        {
            java.util.concurrent.Future<?> a=workers.submit(()->{for(int i=0;i<1000;i++){q.offer(source(event("death"),null));q.reset();}});
            java.util.concurrent.Future<?> b=workers.submit(()->{for(int i=0;i<1000;i++)for(EventVerification.Source source:q.drain())assertNotNull(source);});
            a.get(5,java.util.concurrent.TimeUnit.SECONDS);b.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        finally { workers.shutdownNow(); }
    }
    @Test public void maximumTextPartsRemainBelowReceiverFieldLimit()
    {
        String text=String.join("",Collections.nCopies(384,"x"));
        List<Map<String,Object>> results=new ArrayList<>();
        for(int i=0;i<4;i++)results.add(VerificationProbe.result("message_"+i,
            VerificationProbe.inputs("reader","text","field","verification_text","x",1,"y",1,"width",500,"height",40),text,null));
        Map<String,Object> e=event("verification");e.putAll(source(event("progress"),null).common(100));e.put("results",results);
        Map<String,Object> wire=PublicTransport.event(e,false);
        assertEquals(4,((List<?>)wire.get("results")).size());
        assertTrue(new com.google.gson.Gson().toJson(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<8192);
    }

}
