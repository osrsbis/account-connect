package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import okhttp3.*;
import okio.Buffer;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

public class VerificationDiscoveryWiringTest
{
    private static final String TOKEN="0123456789abcdef0123456789abcdef";
    private static void set(AccountConnectPlugin plugin,String name,Object value)throws Exception
    {Field field=AccountConnectPlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);}
    private static final class Fixture
    {
        final AccountConnectPlugin plugin=new AccountConnectPlugin();final Client client=mock(Client.class);
        final OkHttpClient http=mock(OkHttpClient.class);long account=123;int tick=100;
        Fixture()throws Exception
        {
            set(plugin,"client",client);set(plugin,"activeHash","123");set(plugin,"lastSnapshotCapturedAtMs",1000L);
            set(plugin,"config",new AccountConnectConfig(){public String linkToken(){return TOKEN;}});
            plugin.grants.verificationGrantToken = TOKEN;plugin.grants.verificationFramesGrantToken = TOKEN;
            set(plugin,"gson",new Gson());set(plugin,"executor",mock(ScheduledExecutorService.class));
            set(plugin,"drawManager",new DrawManager());set(plugin,"clientThread",mock(ClientThread.class));set(plugin,"okHttpClient",http);
            when(client.getAccountHash()).thenAnswer(i->account);when(client.getTickCount()).thenAnswer(i->tick);
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);when(client.getWorld()).thenReturn(301);
            when(client.getCanvasWidth()).thenReturn(800);when(client.getCanvasHeight()).thenReturn(600);
            Player player=mock(Player.class);when(player.getWorldLocation()).thenReturn(new WorldPoint(3200,3200,0));
            when(client.getLocalPlayer()).thenReturn(player);
            when(http.newCall(any())).thenReturn(mock(Call.class));
        }
        void chat(ChatMessageType channel,String text)
        {
            ChatMessage event=mock(ChatMessage.class);when(event.getType()).thenReturn(channel);when(event.getMessage()).thenReturn(text);
            plugin.onChatMessage(event);
        }
        void show(int id,String text)
        {
            Widget widget=mock(Widget.class);when(widget.getId()).thenReturn(id);when(widget.getText()).thenReturn(text);
            when(widget.getBounds()).thenReturn(new Rectangle(10,20,400,25));when(client.getWidget(id)).thenReturn(widget);
        }
    }

    @Test public void unparsedMessageGetsRealSourceIdentityAndUsesLegacyEvenWithQueueGrant()throws Exception
    {
        Fixture f=new Fixture();f.plugin.grants.eventQueueGrantToken = TOKEN;f.plugin.grants.eventQueueScope = "own-history-v1";
        f.chat(ChatMessageType.GAMEMESSAGE,"An unknown game completion message.");
        assertEquals(1,f.plugin.events.pendingEvents.size());Map<String,Object> source=f.plugin.events.pendingEvents.get(0);
        assertEquals("verification_discovery",source.get("type"));assertEquals("unknown",source.get("activity"));
        assertTrue(String.valueOf(source.get("event_id")).matches("[a-f0-9]{32}"));
        assertEquals(100,source.get("verification_tick"));assertEquals(1000L,source.get("verification_revision"));
        assertEquals("123",source.get("account_hash"));assertFalse(source.containsKey("_discovery_at"));
        assertFalse(EventQueueProtocol.allows("own-history-v1",source));
        f.plugin.events.flushEvents();ArgumentCaptor<Request> request=ArgumentCaptor.forClass(Request.class);
        verify(f.http).newCall(request.capture());assertTrue(request.getValue().url().toString().endsWith("/event-ingest"));
        Buffer body=new Buffer();request.getValue().body().writeTo(body);String json=body.readUtf8();
        assertTrue(json.contains("verification_discovery"));assertFalse(json.contains("_discovery_at"));
    }

    @Test public void privatePublicChatOffGrantAndUnknownAccountProduceNoDiscovery()throws Exception
    {
        Fixture f=new Fixture();f.chat(ChatMessageType.PUBLICCHAT,"A player's message.");
        f.chat(ChatMessageType.PRIVATECHAT,"A private message.");assertTrue(f.plugin.events.pendingEvents.isEmpty());
        f.plugin.grants.verificationGrantToken = null;f.chat(ChatMessageType.GAMEMESSAGE,"Unknown game result.");
        assertTrue(f.plugin.events.pendingEvents.isEmpty());f.plugin.grants.verificationGrantToken = TOKEN;
        f.account=-1;set(f.plugin,"activeHash","-1");f.chat(ChatMessageType.GAMEMESSAGE,"Unknown game result.");
        assertTrue(f.plugin.events.pendingEvents.isEmpty());
    }

    @Test public void captureGrantCannotWidenDiscoverySourceProjection()throws Exception
    {
        Fixture f=new Fixture();f.plugin.grants.setStaffCapture(TOKEN,"123");assertTrue(f.plugin.grants.captureEnabled());
        f.chat(ChatMessageType.GAMEMESSAGE,"An unknown result under a capture grant.");
        assertEquals(1,f.plugin.events.pendingEvents.size());
        assertEquals("verification_discovery",f.plugin.events.pendingEvents.get(0).get("type"));
        assertFalse(f.plugin.events.pendingEvents.get(0).containsKey("_discovery_at"));
        assertFalse(f.plugin.events.pendingEvents.get(0).containsKey("unapproved_field"));
    }

    @Test public void sharedQueueRefusalDoesNotConsumeNamedResultTransition()throws Exception
    {
        Fixture f=new Fixture();f.show(InterfaceID.ClanwarsGameover.TITLE,"Clan Wars");
        f.show(InterfaceID.ClanwarsGameover.MESSAGE,"An unparsed result.");
        Map<String,Object> event=VerificationProbe.inputs("event_id","aabbccddeeff00112233445566778899aa","type","death");
        for(int i=0;i<EventVerification.MAX_PENDING;i++)
            assertTrue(f.plugin.eventVerification.offer(new EventVerification.Source(event,"different","999",0,100,1000,0,null)));
        f.plugin.onClientTick(null);assertTrue(f.plugin.events.pendingEvents.isEmpty());
        // The old ineligible entries were drained. Both unchanged result fields are still eligible next tick.
        f.plugin.onClientTick(null);
        assertEquals(2,f.plugin.events.pendingEvents.size());
        assertEquals(InterfaceID.ClanwarsGameover.MESSAGE,f.plugin.events.pendingEvents.get(0).get("widget"));
        assertEquals(InterfaceID.ClanwarsGameover.TITLE,f.plugin.events.pendingEvents.get(1).get("widget"));
    }
}
