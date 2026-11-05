package com.osrsbestinslot.export;

import java.awt.Rectangle;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class VerificationDiscoveryTest
{
    private static final class Fixture
    {
        final Client client = mock(Client.class);
        final VerificationDiscovery discovery = new VerificationDiscovery();
        int tick = 100;
        Fixture()
        {
            when(client.getCanvasWidth()).thenReturn(800); when(client.getCanvasHeight()).thenReturn(600);
            when(client.getTickCount()).thenAnswer(i -> tick);
            Player player = mock(Player.class); when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
            when(client.getLocalPlayer()).thenReturn(player);
            discovery.bind("token", "123", 1);
        }
        Widget show(int id, String text)
        {
            Widget widget = mock(Widget.class);
            when(widget.getText()).thenReturn(text); when(widget.getId()).thenReturn(id);
            when(widget.getBounds()).thenReturn(new Rectangle(10, 20, 400, 30));
            when(client.getWidget(id)).thenReturn(widget); return widget;
        }
        Map<String,Object> message(String text, long at)
        { return discovery.message(client, ChatMessageType.GAMEMESSAGE, text, at); }
    }

    @Test public void unknownActivityIsSampledWithoutAnySuccessfulParserOrCounter()
    {
        Fixture f = new Fixture();
        String unknown = "A previously unrecognised task result appears.";
        assertNull(MinigameLines.parse(unknown)); assertNull(AccountProgressCapture.parseMessage(unknown));
        Map<String,Object> source = f.message(unknown, 0);
        assertEquals("unknown", source.get("activity"));
        assertEquals("bounded_unknown_context", source.get("context_basis"));
        assertEquals("unparsed", source.get("discovery_status"));
        assertEquals(unknown, source.get("verification_text"));
        assertEquals(new WorldPoint(3200, 3200, 0).getRegionID(), source.get("region_id"));
    }

    @Test public void playerAndPrivateChatNeverReachDiscoveryEvenInActivity()
    {
        Fixture f = new Fixture(); f.show(InterfaceID.PestStatusOverlay.PEST_STATUS_PORT2, "Portal");
        for (ChatMessageType channel : new ChatMessageType[]{ChatMessageType.PUBLICCHAT, ChatMessageType.PRIVATECHAT})
            assertNull(f.discovery.message(f.client, channel, "You won a game.", 0));
        assertNull(f.message(String.join("", Collections.nCopies(385, "x")), 0));
        assertNotNull(f.message("An unparsed game result.", 0));
    }

    @Test public void fallbackQuotaAndDedupeStayBoundedAcrossSessionReset()
    {
        Fixture f = new Fixture();
        for (int i = 0; i < 4; i++)
        {
            assertNotNull(f.message("Unknown result " + i, i * VerificationDiscovery.UNKNOWN_GAP_MS));
            assertNull(f.message("Unknown result " + i, i * VerificationDiscovery.UNKNOWN_GAP_MS + 1));
        }
        f.discovery.bind("other", "456", 2);
        assertNull(f.message("Another result", 3_599_999L));
        assertNotNull(f.message("Another result", 3_600_000L));
    }

    @Test public void contextualCounterNeedsRealChangeAndExpires()
    {
        Fixture f = new Fixture(); String key = (String) KillContext.ACTIVITY_COUNTERS[0][0];
        f.discovery.counter(VerificationProbe.inputs("key", key, "to", 7, "seen", "first"), f.tick);
        assertEquals("unknown", f.message("Unknown first result", 0).get("activity"));
        f.discovery.counter(VerificationProbe.inputs("key", key, "from", 7, "to", 8), f.tick);
        assertEquals(key, f.message("Changed activity result", 1).get("activity"));
        f.tick += VerificationDiscovery.CONTEXT_TICKS + 1;
        Map<String,Object> later = f.message("Later unrelated game message", VerificationDiscovery.UNKNOWN_GAP_MS);
        assertEquals("unknown", later.get("activity"));
    }

    @Test public void contextualHourlyQuotaSurvivesRelogAndToggleReset()
    {
        Fixture f=new Fixture();f.show(InterfaceID.PestStatusOverlay.PEST_STATUS_PORT2,"Portal");
        for(int i=0;i<VerificationDiscovery.MAX_CONTEXT_HOUR;i++)
            assertNotNull(f.message("Unparsed contextual result "+i,i*VerificationDiscovery.CONTEXT_GAP_MS));
        f.discovery.reset();f.discovery.bind("other","456",2);
        assertNull(f.message("Another contextual result",3_599_999L));
        assertNotNull(f.message("Another contextual result",3_600_000L));
    }

    @Test public void resultAndTitleAndDialogueAreAdmittedInOneBoundedOpportunity()
    {
        Fixture f = new Fixture();
        f.show(InterfaceID.ClanwarsGameover.TITLE, "Clan Wars");
        f.show(InterfaceID.ClanwarsGameover.MESSAGE, "A new result format to investigate.");
        f.show(MinigameLines.DIALOGUE_TEXTS[0], "An unknown reward dialogue.");
        f.show(MinigameLines.DIALOGUE_TEXTS[1], "A second unknown reward field.");
        List<Map<String,Object>> rows = f.discovery.poll(f.client, 0);
        assertEquals(4, rows.size());
        assertEquals(InterfaceID.ClanwarsGameover.MESSAGE, rows.get(0).get("widget"));
        Set<Object> widgets = new HashSet<>(); for (Map<String,Object> row : rows) widgets.add(row.get("widget"));
        assertTrue(widgets.contains(InterfaceID.ClanwarsGameover.TITLE));
        assertTrue(widgets.contains(MinigameLines.DIALOGUE_TEXTS[0]));
        assertTrue(f.discovery.poll(f.client, 1).isEmpty());
    }

    @Test public void unchangedThrottledResultAndRejectedOuterAdmissionRemainRetryable()
    {
        Fixture f = new Fixture(); Widget w = f.show(InterfaceID.ClanwarsGameover.MESSAGE, "First result.");
        assertEquals(1, f.discovery.poll(f.client, 0).size());
        when(w.getText()).thenReturn("Next result.");
        assertTrue(f.discovery.poll(f.client, 1).isEmpty());
        List<Map<String,Object>> pending = f.discovery.poll(f.client, 30_000);
        assertEquals(1, pending.size()); assertEquals("Next result.", pending.get(0).get("verification_text"));
        f.discovery.rejected(pending.get(0));
        assertEquals(1, f.discovery.poll(f.client, 30_001).size());
    }

    @Test public void differentNamedFieldsWithEqualTextAreNotMerged()
    {
        Fixture f=new Fixture();
        f.show(InterfaceID.ClanwarsGameover.TITLE,"The round has ended.");
        f.show(InterfaceID.ClanwarsGameover.MESSAGE,"The round has ended.");
        List<Map<String,Object>> fields=f.discovery.poll(f.client,0);
        assertEquals(2,fields.size());
        assertNotEquals(fields.get(0).get("widget"),fields.get(1).get("widget"));
    }

    @Test public void discoveryPixelsUseExactNamedSurfaceAndNeverBecomeSemanticMatch()
    {
        Fixture f = new Fixture(); String message = "An unexplained result.";
        f.show(InterfaceID.ClanwarsGameover.MESSAGE, message);
        Map<String,Object> event = f.discovery.poll(f.client, 0).get(0);
        event.put("type", "verification_discovery"); event.put("event_id", "aabbccddeeff00112233445566778899aa");
        Map<String,Object> wire = PublicTransport.event(event, false);
        assertFalse(wire.containsKey("_discovery_at"));
        event.put("unapproved_field","not part of the discovery wire");
        for(boolean capture:new boolean[]{false,true})
        {
            Map<String,Object> projected=PublicTransport.event(event,capture);
            assertFalse(projected.containsKey("_discovery_at"));
            assertFalse(projected.containsKey("unapproved_field"));
            assertEquals(message,projected.get("verification_text"));
        }
        EventVerification.Source source = new EventVerification.Source(wire, "token", "123", 1, 100, 1, 0, message);
        List<VerificationFrame.Region> regions = EventVerificationScreen.regions(f.client, source);
        assertEquals(1, regions.size()); assertEquals("discovery_text", regions.get(0).inputs.get("reader"));
        assertEquals(message, regions.get(0).value);
        Map<String,Object> result = VerificationFrame.results(regions, 800, 600, 800, 600).get(0);
        assertNull(result.get("match"));
        when(f.client.getWidget(InterfaceID.ClanwarsGameover.MESSAGE).getText()).thenReturn("Different result.");
        assertFalse(EventVerificationScreen.unchanged(f.client, regions));
    }
}
