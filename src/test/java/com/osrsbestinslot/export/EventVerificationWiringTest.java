package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

public class EventVerificationWiringTest
{
    private static final String TOKEN="0123456789abcdef0123456789abcdef";
    private static void set(AccountConnectPlugin p,String name,Object value) throws Exception
    { Field f=AccountConnectPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(p,value); }
    private static final class Fixture
    {
        final AccountConnectPlugin p=new AccountConnectPlugin();
        final Client client=mock(Client.class);
        final DrawManager draws=new DrawManager();
        final OkHttpClient http=mock(OkHttpClient.class);
        final AtomicReference<Runnable> encode=new AtomicReference<>(), finish=new AtomicReference<>();
        int tick=100; long account=123; String token=TOKEN;
        Fixture() throws Exception
        {
            set(p,"config",new AccountConnectConfig(){public String linkToken(){return token;}});
            set(p,"client",client);set(p,"activeHash","123");set(p,"verificationGrantToken",TOKEN);
            set(p,"verificationFramesGrantToken",TOKEN);set(p,"gson",new Gson());
            set(p,"drawManager",draws);set(p,"okHttpClient",http);p.lastSnapshotCapturedAtMs=999;
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getTickCount()).thenAnswer(i->tick);when(client.getAccountHash()).thenAnswer(i->account);
            when(client.getCanvasWidth()).thenReturn(800);when(client.getCanvasHeight()).thenReturn(600);
            ScheduledExecutorService executor=mock(ScheduledExecutorService.class);
            doAnswer(i->{encode.set(i.getArgument(0));return null;}).when(executor).submit(any(Runnable.class));set(p,"executor",executor);
            ClientThread thread=mock(ClientThread.class);
            doAnswer(i->{finish.set(i.getArgument(0));return null;}).when(thread).invoke(any(Runnable.class));set(p,"clientThread",thread);
        }
        void emit() throws Exception
        {
            String text="Your Vorkath kill count is: 42.";
            set(p,"verificationMessage",text);
            p.emitEvent("kill_count",VerificationProbe.inputs("source","Vorkath","kind","kill","count",42));
            set(p,"verificationMessage",null);
        }
        void show()
        {
            Widget w=mock(Widget.class);when(w.getText()).thenReturn("Your Vorkath kill count is: 42.");
            when(w.getId()).thenReturn(InterfaceID.Chatbox.LINE0);when(w.getBounds()).thenReturn(new Rectangle(1,1,400,20));
            when(client.getWidget(InterfaceID.Chatbox.LINE0)).thenReturn(w);
        }
    }
    @Test public void emittedEventIdentityDrivesImmediateCaptureAndSurvivesProjection() throws Exception
    {
        Fixture f=new Fixture();f.show();assertTrue(f.p.verificationProbe.takeEvent(System.currentTimeMillis()));
        f.emit();String id=String.valueOf(f.p.pendingEvents.get(0).get("event_id"));
        f.p.onClientTick(null);
        f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        assertNotNull(f.encode.get());f.encode.get().run();assertNotNull(f.finish.get());
        // Stop before network: revoke at the encoding completion boundary.
        f.token="abcdef0123456789abcdef0123456789";f.finish.get().run();
        verify(f.http,never()).newCall(any());
        assertEquals(id,f.p.pendingEvents.get(0).get("event_id"));
        assertEquals(100,f.p.pendingEvents.get(0).get("verification_tick"));
        assertEquals(999L,f.p.pendingEvents.get(0).get("verification_revision"));
    }
    @Test public void completedFrameAndScreenRowsKeepActualProjectedEventIdentity() throws Exception
    {
        Fixture f=new Fixture();f.show();f.emit();
        String id=String.valueOf(f.p.pendingEvents.get(0).get("event_id"));
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        f.encode.get().run();f.finish.get().run();
        assertEquals(3,f.p.pendingEvents.size());
        for (int i=1;i<3;i++)
        {
            Map<String,Object> row=f.p.pendingEvents.get(i);
            assertEquals(id,row.get("source_event_id"));assertEquals("kill_count",row.get("source_event_type"));
            assertEquals(100,row.get("source_event_tick"));assertEquals(999L,row.get("source_event_revision"));
            assertEquals("123",row.get("account_hash"));
            assertTrue(new Gson().toJson(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<8192);
        }
        Map<?,?> result=(Map<?,?>)((List<?>)f.p.pendingEvents.get(2).get("results")).get(0);
        assertEquals(42,result.get("expected"));assertNull(result.get("match"));
        verify(f.http).newCall(any());
    }

    @Test public void missingWidgetExpiresAsSourceBoundUnknownInsteadOfLaterOrbCapture() throws Exception
    {
        Fixture f=new Fixture();f.emit();String id=String.valueOf(f.p.pendingEvents.get(0).get("event_id"));
        f.p.onClientTick(null);assertEquals(1,f.p.pendingEvents.size());
        f.tick=104;f.p.onClientTick(null);
        Map<String,Object> row=f.p.pendingEvents.get(1);
        assertEquals("ocr_event",row.get("check"));assertEquals(id,row.get("source_event_id"));
        assertEquals(100,row.get("source_event_tick"));assertEquals(999L,row.get("snapshot_revision"));
        Map<?,?> result=(Map<?,?>)((List<?>)row.get("results")).get(0);
        assertEquals("source_not_visible",((Map<?,?>)result.get("inputs")).get("reason"));assertNull(result.get("match"));
        assertNull(f.encode.get());
    }
    @Test public void accountSwitchBeforeDrawDropsFrameAndDoesNotAttributeItToNewAccount() throws Exception
    {
        Fixture f=new Fixture();f.show();f.emit();f.p.onClientTick(null);f.account=456;
        f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        assertNull(f.encode.get());verify(f.http,never()).newCall(any());
    }
}
