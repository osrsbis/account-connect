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
            set(p,"client",client);set(p,"activeHash","123");p.grants.verificationGrantToken = TOKEN;
            p.grants.verificationFramesGrantToken = TOKEN;p.grants.setStaffCapture(TOKEN, "123");set(p,"gson",new Gson());
            set(p,"drawManager",draws);set(p,"okHttpClient",http);p.lastSnapshotCapturedAtMs=999;
            // Frame uploads use a derived client (no connection-failure retry); the test seam points it at the mock.
            p.verify.frameHttpForTest(http);
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
            p.events.emitEvent("kill_count",VerificationProbe.inputs("source","Vorkath","kind","kill","count",42));
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
        f.emit();String id=String.valueOf(f.p.events.pendingEvents.get(0).get("event_id"));
        f.p.onClientTick(null);
        f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull(f.encode.get());f.encode.get().run();assertNotNull(f.finish.get());
        // Stop before network: revoke at the encoding completion boundary.
        f.token="abcdef0123456789abcdef0123456789";f.finish.get().run();
        verify(f.http,never()).newCall(any());
        assertEquals(id,f.p.events.pendingEvents.get(0).get("event_id"));
        assertEquals(100,f.p.events.pendingEvents.get(0).get("verification_tick"));
        assertEquals(999L,f.p.events.pendingEvents.get(0).get("verification_revision"));
    }
    /** A drawn frame with a scene in it (a solid-black frame is blank and not uploaded as a full window). */
    private static BufferedImage scene()
    {
        BufferedImage img=new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB);
        for (int y=0;y<600;y+=7) for (int x=0;x<800;x+=9) img.setRGB(x,y,0x203040+((x*y)&0xFF));
        return img;
    }
    @Test public void completedFrameAndScreenRowsKeepActualProjectedEventIdentity() throws Exception
    {
        Fixture f=new Fixture();f.show();f.emit();
        String id=String.valueOf(f.p.events.pendingEvents.get(0).get("event_id"));
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        f.encode.get().run();f.finish.get().run();
        // Region frame: capture + screen rows; a kill count also takes the full window: capture + screen rows.
        assertEquals(5,f.p.events.pendingEvents.size());
        for (int i=1;i<5;i++)
        {
            Map<String,Object> row=f.p.events.pendingEvents.get(i);
            assertEquals(id,row.get("source_event_id"));assertEquals("kill_count",row.get("source_event_type"));
            assertEquals(100,row.get("source_event_tick"));assertEquals(999L,row.get("source_event_revision"));
            assertEquals("123",row.get("account_hash"));
            assertTrue(new Gson().toJson(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<8192);
        }
        Map<?,?> result=(Map<?,?>)((List<?>)f.p.events.pendingEvents.get(2).get("results")).get(0);
        assertEquals(42,result.get("expected"));assertNull(result.get("match"));
        verify(f.http,times(2)).newCall(any());
    }
    /** The PNG of every frame POSTed, in order, decoded. */
    private static List<BufferedImage> uploadedImages(Fixture f) throws Exception
    {
        org.mockito.ArgumentCaptor<okhttp3.Request> requests=org.mockito.ArgumentCaptor.forClass(okhttp3.Request.class);
        verify(f.http,atLeastOnce()).newCall(requests.capture());
        List<BufferedImage> out=new ArrayList<>();
        for (okhttp3.Request r:requests.getAllValues())
            for (okhttp3.MultipartBody.Part part:((okhttp3.MultipartBody)r.body()).parts())
            {
                if (part.headers()==null||!String.valueOf(part.headers().get("Content-Disposition")).contains("name=\"file\"")) continue;
                okio.Buffer bytes=new okio.Buffer();part.body().writeTo(bytes);
                out.add(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes.readByteArray())));
            }
        return out;
    }
    @Test public void fullWindowIsTheWholeDrawnFrameWithNothingBlackedOut() throws Exception
    {
        // The full window is the complete game canvas with no blackout masks, even with chat on screen.
        Fixture f=new Fixture();f.show();f.emit();
        Widget chat=mock(Widget.class);when(chat.getBounds()).thenReturn(new Rectangle(0,450,520,150));
        when(f.client.getWidget(InterfaceID.Chatbox.UNIVERSE)).thenReturn(chat);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        f.encode.get().run();f.finish.get().run();
        List<BufferedImage> images=uploadedImages(f);
        assertEquals("region frame and full window",2,images.size());
        BufferedImage drawn=scene(), full=images.get(1);
        assertEquals(800,full.getWidth());assertEquals(600,full.getHeight());
        for (int y=0;y<600;y++) for (int x=0;x<800;x++)
            assertEquals("pixel "+x+","+y,drawn.getRGB(x,y)&0xFFFFFF,full.getRGB(x,y)&0xFFFFFF);
        Map<String,Object> fullCapture=f.p.events.pendingEvents.get(3);
        assertEquals("full_window",observed(fullCapture).get("scope"));
        for (Object o:(List<?>)fullCapture.get("results"))
            assertNotEquals("no mask entries","mask",((Map<?,?>)o).get("key"));
        // The region frame keeps only the evidence line: outside it is black.
        assertEquals(0,images.get(0).getRGB(700,500)&0xFFFFFF);
    }
    @Test public void publicVerificationGetsOnlyTheSmallRegionAndNeverAnUnmaskedWindow() throws Exception
    {
        Fixture f = new Fixture();
        f.p.grants.clearStaffCaptureGrant();
        f.show(); f.emit();
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null); f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull(f.encode.get()); f.encode.get().run(); f.finish.get().run();
        List<BufferedImage> frames = uploadedImages(f);
        assertEquals("public links keep their OCR region evidence", 1, frames.size());
        assertEquals(3, f.p.events.pendingEvents.size());
        assertEquals("regions", observed(f.p.events.pendingEvents.get(1)).get("scope"));
        assertEquals(0, frames.get(0).getRGB(700, 500) & 0xFFFFFF);
    }

    @Test public void fullWindowFramePairsWithTheRegionFrame() throws Exception
    {
        Fixture f=new Fixture();f.show();f.emit();
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        f.encode.get().run();f.finish.get().run();
        Map<String,Object> regionCapture=f.p.events.pendingEvents.get(1), fullCapture=f.p.events.pendingEvents.get(3);
        Map<String,Object> region=observed(regionCapture), full=observed(fullCapture);
        assertEquals("regions",region.get("scope"));assertEquals("full_window",full.get("scope"));
        assertEquals(fullCapture.get("ref"),region.get("paired_ref"));assertEquals(regionCapture.get("ref"),full.get("paired_ref"));
        assertEquals(800,full.get("width"));assertEquals(600,full.get("height"));assertEquals("drawn_frame",full.get("pixel_source"));
        assertEquals(Boolean.FALSE,full.get("gpu"));assertEquals(Boolean.TRUE,full.get("encoded"));
        assertEquals(Boolean.FALSE,full.get("drawn_blank"));
        assertEquals(regionCapture.get("source_event_id"),fullCapture.get("source_event_id"));
        assertEquals(regionCapture.get("tick"),fullCapture.get("tick"));
        assertEquals(regionCapture.get("plugin_version"),fullCapture.get("plugin_version"));
        // The full window's screen row points the same check at the full image.
        assertEquals("ocr_event",f.p.events.pendingEvents.get(4).get("check"));
        assertEquals(fullCapture.get("ref"),f.p.events.pendingEvents.get(4).get("ref"));
    }
    @Test public void aBlankDrawnFrameIsUnknownDrawnBlankAndNothingIsUploaded() throws Exception
    {
        // A software GPU renderer can hand back solid-black drawn frames: neither mode uploads a black image.
        Fixture f=new Fixture();f.show();f.emit();
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        assertNull("nothing is encoded",f.encode.get());assertNotNull(f.finish.get());f.finish.get().run();
        Map<?,?> result=(Map<?,?>)((List<?>)f.p.events.pendingEvents.get(1).get("results")).get(0);
        assertEquals("drawn_blank",((Map<?,?>)result.get("inputs")).get("reason"));
        verify(f.http,never()).newCall(any());
    }
    @Test public void aBlankDrawnFrameWithAClientBufferUploadsTheRegionsButNotTheFullWindow() throws Exception
    {
        // GPU on a software renderer: the drawn frame is black, the client buffer still holds the interface.
        Fixture f=new Fixture();f.show();f.emit();
        int[] pixels=new int[800*600];java.util.Arrays.fill(pixels,0x405060);
        for (int x=2;x<400;x+=3) pixels[10*800+x]=0xFFFF00;	// the chat line's text
        net.runelite.api.BufferProvider buffer=mock(net.runelite.api.BufferProvider.class);
        when(buffer.getPixels()).thenReturn(pixels);when(buffer.getWidth()).thenReturn(800);when(buffer.getHeight()).thenReturn(600);
        when(f.client.getBufferProvider()).thenReturn(buffer);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        f.encode.get().run();f.finish.get().run();
        Map<String,Object> region=observed(f.p.events.pendingEvents.get(1)), full=observed(f.p.events.pendingEvents.get(3));
        assertEquals("client_buffer",region.get("pixel_source"));
        assertEquals("full_window",full.get("scope"));assertEquals(Boolean.TRUE,full.get("drawn_blank"));
        assertEquals(Boolean.FALSE,full.get("uploaded"));
        verify(f.http,times(1)).newCall(any());
    }
    private static Map<String,Object> lastCapture(Fixture f)
    {
        for (int i=f.p.events.pendingEvents.size()-1;i>=0;i--)
        {
            Map<String,Object> r=f.p.events.pendingEvents.get(i);
            if ("frame".equals(r.get("area"))&&"capture".equals(r.get("check"))) return r;
        }
        return null;
    }
    @Test public void aSampleFrameThatIsNotTakenLeavesACaptureRowWithItsReason() throws Exception
    {
        // No numeric widget on screen: the ref already sent with the sample rows is explained.
        Fixture f=new Fixture();
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        if (f.finish.get()!=null) f.finish.get().run();
        Map<String,Object> row=lastCapture(f);
        assertNotNull(row);assertEquals("0123456789abcdef",row.get("ref"));
        assertEquals("no_numeric_region",observed(row).get("frame_skipped"));assertEquals(Boolean.FALSE,observed(row).get("uploaded"));
        // An orb is on screen and the frame is drawn two ticks later: the reason says so.
        Fixture g=new Fixture();
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        g.p.verify.requestVerificationFrame("fedcba9876543210",100,1000L,999L,false);
        g.tick=102;g.draws.processDrawComplete(EventVerificationWiringTest::scene);
        if (g.finish.get()!=null) g.finish.get().run();
        Map<String,Object> later=lastCapture(g);
        assertNotNull(later);assertEquals("frame_on_later_tick",observed(later).get("frame_skipped"));
        assertNull("nothing is encoded",g.encode.get());
    }
    @Test public void aSampleFrameDrawnOnTheNextTickIsUsedOnlyWhenTheWidgetsAreUnchanged() throws Exception
    {
        // The rig drew every sample's frame one tick after the sample (11-07).
        Fixture f=new Fixture();
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        f.tick=101;f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull("the frame is taken",f.encode.get());f.encode.get().run();f.finish.get().run();
        Map<String,Object> capture=lastCapture(f);
        assertEquals(101,capture.get("tick"));assertEquals(101,observed(capture).get("frame_tick"));
        assertEquals(100,observed(capture).get("sample_tick"));assertEquals("regions",observed(capture).get("scope"));
        // The value changed on that next tick: no frame, a reason row instead.
        Fixture g=new Fixture();
        Widget orb2=mock(Widget.class);when(orb2.getText()).thenReturn("42");when(orb2.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb2);
        g.p.verify.requestVerificationFrame("fedcba9876543210",100,1000L,999L,false);
        when(orb2.getText()).thenReturn("41");
        g.tick=101;g.draws.processDrawComplete(EventVerificationWiringTest::scene);
        if (g.finish.get()!=null) g.finish.get().run();
        assertNull(g.encode.get());
        assertEquals("screen_changed_before_frame",observed(lastCapture(g)).get("frame_skipped"));
    }
    @Test public void aDrawnFrameOfAnotherSizeThanTheCanvasIsNeverUsed() throws Exception
    {
        // Rig, resize (11-07): the drawn frame lagged the canvas, so regions scaled by the size ratio point at the wrong pixels.
        Fixture f=new Fixture();f.show();f.emit();
        f.p.onClientTick(null);
        f.draws.processDrawComplete(()->{BufferedImage i=new BufferedImage(760,600,BufferedImage.TYPE_INT_RGB);i.setRGB(5,5,0x445566);i.setRGB(300,200,0x112233);return i;});
        f.finish.get().run();
        assertNull("no pixels are taken from a frame of another size", f.encode.get());
        // Stretched mode: the drawn frame must be the stretched size.
        Fixture g=new Fixture();g.show();g.emit();
        when(g.client.isStretchedEnabled()).thenReturn(true);when(g.client.getStretchedDimensions()).thenReturn(new java.awt.Dimension(1200,900));
        when(g.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        g.p.onClientTick(null);
        g.draws.processDrawComplete(()->{BufferedImage i=new BufferedImage(1200,900,BufferedImage.TYPE_INT_RGB);for(int y=0;y<900;y+=9)for(int x=0;x<1200;x+=7)i.setRGB(x,y,0x203040+x);return i;});
        assertNotNull("a stretched frame of the stretched size is used", g.encode.get());
    }
    @Test public void aDisplayScaledFrameOfTheCanvasIsUsed() throws Exception
    {
        // Windows display scaling at 125%: RuneLite draws the frame at canvas x 1.25 (CPU rounds, GPU truncates).
        Fixture f=new Fixture();f.show();f.emit();
        java.awt.Canvas canvas=mock(java.awt.Canvas.class);java.awt.GraphicsConfiguration gc=mock(java.awt.GraphicsConfiguration.class);
        when(gc.getDefaultTransform()).thenReturn(java.awt.geom.AffineTransform.getScaleInstance(1.25,1.25));
        when(canvas.getGraphicsConfiguration()).thenReturn(gc);when(f.client.getCanvas()).thenReturn(canvas);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);
        f.draws.processDrawComplete(()->{BufferedImage i=new BufferedImage(1000,750,BufferedImage.TYPE_INT_RGB);for(int y=0;y<750;y+=9)for(int x=0;x<1000;x+=7)i.setRGB(x,y,0x203040+x);return i;});
        assertNotNull("a 1.25x frame of the 800x600 canvas is used", f.encode.get());
    }
    private static Fixture sizedEventFrame(int canvasW,int canvasH,java.awt.Dimension stretched,double scale,int imageW,int imageH) throws Exception
    {
        Fixture f=new Fixture();
        when(f.client.getCanvasWidth()).thenReturn(canvasW);when(f.client.getCanvasHeight()).thenReturn(canvasH);
        if (stretched!=null)
        {
            when(f.client.isStretchedEnabled()).thenReturn(true);when(f.client.getStretchedDimensions()).thenReturn(stretched);
        }
        java.awt.Canvas canvas=mock(java.awt.Canvas.class);java.awt.GraphicsConfiguration gc=mock(java.awt.GraphicsConfiguration.class);
        when(gc.getDefaultTransform()).thenReturn(java.awt.geom.AffineTransform.getScaleInstance(scale,scale));
        when(canvas.getGraphicsConfiguration()).thenReturn(gc);when(f.client.getCanvas()).thenReturn(canvas);
        f.show();f.emit();f.p.onClientTick(null);
        f.draws.processDrawComplete(()->{
            BufferedImage image=new BufferedImage(imageW,imageH,BufferedImage.TYPE_INT_RGB);
            image.setRGB(0,0,0x445566);image.setRGB(300,200,0x112233);
            for (int y=0;y<Math.min(40,imageH);y+=2) for (int x=0;x<Math.min(520,imageW);x+=3) image.setRGB(x,y,0xFFFF00);	// the chat line's text
            return image;
        });
        return f;
    }
    private static void assertDimensionRejected(Fixture f)
    {
        assertNull("a wrong-size frame never reaches encoding",f.encode.get());
        assertNotNull("the source can retry within its original window",f.finish.get());f.finish.get().run();
        assertEquals("the original source remains pending",1,f.p.events.pendingEvents.size());
        verify(f.http,never()).newCall(any());
    }
    @Test public void adjacentUnscaledEventFrameDimensionsAreRejected() throws Exception
    {
        // At 1x both renderers produce exactly the canvas dimensions. A one-pixel resize is not rounding.
        for (int[] size:new int[][]{{799,600},{801,600},{800,599},{800,601}})
            assertDimensionRejected(sizedEventFrame(800,600,null,1.0,size[0],size[1]));
    }
    @Test public void fractionalCpuAndGpuDimensionPairsAreBothAccepted() throws Exception
    {
        // 802x603 at 125%: CPU rounds both axes, GPU truncates both axes.
        for (int[] size:new int[][]{{1003,754},{1002,753}})
            assertNotNull("a real renderer dimension pair reaches encoding",
                sizedEventFrame(802,603,null,1.25,size[0],size[1]).encode.get());
        // Stretched dimensions are the logical image size before the same display transform.
        for (int[] size:new int[][]{{1503,1129},{1502,1128}})
            assertNotNull("a stretched renderer dimension pair reaches encoding",
                sizedEventFrame(800,600,new java.awt.Dimension(1202,903),1.25,size[0],size[1]).encode.get());
    }
    @Test public void mixedRoundingAndUnnecessaryCeilingDimensionsAreRejected() throws Exception
    {
        // No supported renderer rounds one axis and truncates the other.
        for (int[] size:new int[][]{{1003,753},{1002,754}})
            assertDimensionRejected(sizedEventFrame(802,603,null,1.25,size[0],size[1]));
        // A .25 fraction rounds down on the CPU too; the ceiling is not a valid renderer size.
        assertDimensionRejected(sizedEventFrame(801,601,null,1.25,1002,752));
        // Exact display-scaled dimensions need no extra pixel, including in stretched mode.
        assertDimensionRejected(sizedEventFrame(800,600,new java.awt.Dimension(1200,900),1.25,1501,1125));
    }
    @Test public void adjacentSampleFrameDimensionsAreExplainedWithoutEncoding() throws Exception
    {
        Fixture f=new Fixture();
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");
        when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        f.draws.processDrawComplete(()->{
            BufferedImage image=new BufferedImage(799,600,BufferedImage.TYPE_INT_RGB);
            image.setRGB(0,0,0x445566);image.setRGB(300,200,0x112233);return image;
        });
        assertNull("a wrong-size sample never reaches encoding",f.encode.get());
        assertNotNull(f.finish.get());f.finish.get().run();
        assertEquals("frame_size_mismatch",observed(lastCapture(f)).get("frame_skipped"));
        verify(f.http,never()).newCall(any());
    }
    @Test public void fullWindowsHaveTheirOwnSixAnHourAndNeverTakeARegionFrameSlot()
    {
        VerificationProbe probe=new VerificationProbe(n->1);long now=1_000_000L;
        for (int i=0;i<FullWindowFrame.MAX_PER_HOUR;i++) assertTrue(probe.takeFullWindow(now));
        assertFalse(probe.takeFullWindow(now));
        assertEquals("every region frame slot is still free",VerificationProbe.MAX_FRAMES_PER_HOUR,budgetRoom(probe,now));
        assertTrue("after an hour the budget is back",probe.takeFullWindow(now+VerificationProbe.HOUR_MS));
        // Region frames spent do not stop the optional full window either: the two budgets are separate.
        VerificationProbe full=new VerificationProbe(n->1);
        for (int i=0;i<VerificationProbe.MAX_FRAMES_PER_HOUR;i++) assertTrue(full.takeFrame(now));
        assertTrue(full.fullWindowRoom(now));
    }
    private static int budgetRoom(VerificationProbe probe,long now)
    {
        int n=0;while (probe.takeFrame(now)) n++;return n;
    }
    private static int fullWindowsLeft(VerificationProbe probe)
    {
        int n=0;long now=System.currentTimeMillis();while (probe.takeFullWindow(now)) n++;return n;
    }
    @Test public void aFullPendingQueueRefusesTheSourceWithoutChargingTheHourlyAdmission() throws Exception
    {
        Fixture f=new Fixture();f.show();
        Map<String,Object> other=VerificationProbe.inputs("event_id","aabbccddeeff00112233445566778899aa","type","death");
        for (int i=0;i<EventVerification.MAX_PENDING;i++)
            assertTrue(f.p.eventVerification.offer(new EventVerification.Source(other,TOKEN,"123",0,100,999,0,null)));
        f.emit();
        // The source is refused and says so; the 60-an-hour admission is not spent on it.
        assertEquals(1,unknownRows(f,"pending_capacity"));
        long now=System.currentTimeMillis();int admitted=0;
        while (f.p.verificationProbe.takeEvent(now)) admitted++;
        assertEquals(VerificationProbe.MAX_SAMPLES_PER_HOUR,admitted);
    }
    @Test public void anExhaustedEventAdmissionIsReportedOncePerGap() throws Exception
    {
        Fixture f=new Fixture();f.show();
        long now=System.currentTimeMillis();
        while (f.p.verificationProbe.takeEvent(now)) { }
        f.emit();f.emit();
        assertEquals("the first refused source is on record, not every one",1,unknownRows(f,"event_budget"));
        assertTrue(f.p.eventVerification.drain().isEmpty());
    }
    /** Screen rows whose results are UNKNOWN for this reason. */
    private static int unknownRows(Fixture f,String reason)
    {
        int n=0;
        for (Map<String,Object> row:f.p.events.pendingEvents)
        {
            if (!(row.get("results") instanceof List)) continue;
            for (Object o:(List<?>)row.get("results"))
            {
                Object inputs=((Map<?,?>)o).get("inputs");
                if (inputs instanceof Map&&reason.equals(((Map<?,?>)inputs).get("reason"))) { n++; break; }
            }
        }
        return n;
    }
    @Test public void aGrantClearedBeforeOrAfterEncodingTakesNoFrameSlot() throws Exception
    {
        // Event, cleared while the frame is encoded.
        Fixture f=new Fixture();f.show();f.emit();
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        f.encode.get().run();f.p.grants.clearVerificationGrant();f.finish.get().run();
        verify(f.http,never()).newCall(any());
        assertEquals(VerificationProbe.MAX_FRAMES_PER_HOUR,budgetRoom(f.p.verificationProbe,System.currentTimeMillis()));
        // Sample, cleared before the draw and after encoding.
        for (boolean afterEncoding:new boolean[]{false,true})
        {
            Fixture g=new Fixture();
            Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
            when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
            g.p.verify.requestVerificationFrame("0123456789abcdef",100,System.currentTimeMillis(),999L,false);
            if (!afterEncoding) g.p.grants.clearVerificationGrant();
            g.draws.processDrawComplete(EventVerificationWiringTest::scene);
            if (afterEncoding) { g.encode.get().run();g.p.grants.clearVerificationGrant();g.finish.get().run(); }
            verify(g.http,never()).newCall(any());
            assertEquals(VerificationProbe.MAX_FRAMES_PER_HOUR,budgetRoom(g.p.verificationProbe,System.currentTimeMillis()));
        }
    }
    @Test public void aTradeOrPinScreenOpenedAfterSelectionUploadsNothing() throws Exception
    {
        for (int blocker:Verification.VERIFY_FRAME_BLOCKERS)
        {
            // Event: the screen opens while the frame is encoded.
            Fixture f=new Fixture();f.show();f.emit();
            f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
            Widget open=mock(Widget.class);when(f.client.getWidget(blocker,0)).thenReturn(open);
            f.encode.get().run();f.finish.get().run();
            verify(f.http,never()).newCall(any());
            assertEquals(1,unknownRows(f,"frame_blocked"));
            // Sample: the screen opens between selection and the draw.
            Fixture g=new Fixture();
            Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
            when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
            g.p.verify.requestVerificationFrame("0123456789abcdef",100,System.currentTimeMillis(),999L,true);
            Widget open2=mock(Widget.class);when(g.client.getWidget(blocker,0)).thenReturn(open2);
            g.tick=101;g.draws.processDrawComplete(EventVerificationWiringTest::scene);
            if (g.finish.get()!=null) g.finish.get().run();
            assertNull("nothing is encoded",g.encode.get());
            assertEquals("frame_blocked",observed(lastCapture(g)).get("frame_skipped"));
            verify(g.http,never()).newCall(any());
        }
    }
    @Test public void expiredPendingSourcesMakeRoomForAFreshEventAndAreReported() throws Exception
    {
        Fixture f=new Fixture();
        for (int i=0;i<EventVerification.MAX_PENDING;i++)
        {
            Map<String,Object> old=VerificationProbe.inputs("event_id",String.format("%032x",i+1),"type","death");
            assertTrue(f.p.eventVerification.offer(new EventVerification.Source(old,TOKEN,"123",f.p.verificationEpoch.get(),96,999L,0,null)));
        }
        f.show();f.emit();
        String fresh=null;
        for (Map<String,Object> row:f.p.events.pendingEvents) if ("kill_count".equals(row.get("type"))) fresh=String.valueOf(row.get("event_id"));
        boolean queued=false;
        for (EventVerification.Source s:f.p.eventVerification.drain()) queued|=s.id.equals(fresh);
        assertTrue("the fresh source is admitted",queued);
        assertEquals("pending_capacity is not reported",0,unknownRows(f,"pending_capacity"));
    }
    @Test public void aSampleWhoseRegionsAreStillBlankUploadsNothing() throws Exception
    {
        // Rig 11-08: the sample right after login was uploaded all black (the orbs were not drawn yet).
        Fixture f=new Fixture();
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        f.tick=101;
        // A drawn scene, but the orb's own area is still one colour.
        f.draws.processDrawComplete(()->{BufferedImage i=scene();for(int y=50;y<62;y++)for(int x=500;x<520;x++)i.setRGB(x,y,0);return i;});
        if (f.finish.get()!=null) f.finish.get().run();
        assertNull("nothing is encoded",f.encode.get());
        assertEquals("regions_blank",observed(lastCapture(f)).get("frame_skipped"));
        verify(f.http,never()).newCall(any());
    }
    @Test public void anEventWhoseRegionsStayBlankEndsUnknownRegionsBlank() throws Exception
    {
        // Review 8443a04 P3-b: the retry reason must survive to expiry, not become source_not_visible.
        Fixture f=new Fixture();f.show();f.emit();
        for (int t=100;t<=104;t++)
        {
            f.tick=t;f.finish.set(null);
            f.p.onClientTick(null);
            // A drawn scene; only the chat line's own area (1,1,400,20) is still one colour.
            f.draws.processDrawComplete(()->{BufferedImage i=scene();for(int y=0;y<24;y++)for(int x=0;x<404;x++)i.setRGB(x,y,0);return i;});
            if (f.finish.get()!=null) f.finish.get().run();
        }
        assertEquals(1,unknownRows(f,"regions_blank"));
        assertEquals(0,unknownRows(f,"source_not_visible"));
        verify(f.http,never()).newCall(any());
    }
    @Test public void onlyAnUploadedFrameTakesAFrameSlot() throws Exception
    {
        // Event: a blank drawn frame with no client buffer is UNKNOWN drawn_blank and takes no frame slot.
        Fixture f=new Fixture();f.show();f.emit();
        f.p.onClientTick(null);f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        f.finish.get().run();
        assertEquals(VerificationProbe.MAX_FRAMES_PER_HOUR,budgetRoom(f.p.verificationProbe,System.currentTimeMillis()));
        // Sample: a frame drawn two ticks late is skipped and takes no frame slot.
        Fixture g=new Fixture();
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        g.p.verify.requestVerificationFrame("fedcba9876543210",100,1000L,999L,false);
        g.tick=102;g.draws.processDrawComplete(EventVerificationWiringTest::scene);
        if (g.finish.get()!=null) g.finish.get().run();
        assertEquals("frame_on_later_tick",observed(lastCapture(g)).get("frame_skipped"));
        assertEquals(VerificationProbe.MAX_FRAMES_PER_HOUR,budgetRoom(g.p.verificationProbe,System.currentTimeMillis()));
        // An uploaded frame keeps its slot.
        Fixture h=new Fixture();h.show();h.emit();
        when(h.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        h.p.onClientTick(null);h.draws.processDrawComplete(EventVerificationWiringTest::scene);
        h.encode.get().run();h.finish.get().run();
        assertEquals("the region frame takes one region slot; the full window takes none",VerificationProbe.MAX_FRAMES_PER_HOUR-1,
            budgetRoom(h.p.verificationProbe,System.currentTimeMillis()));
        assertFalse("the full window took one of its own",fullWindowsLeft(h.p.verificationProbe)==FullWindowFrame.MAX_PER_HOUR);
    }
    private static Map<String,Object> observed(Map<String,Object> capture)
    {
        Map<String,Object> out=new HashMap<>();
        for (Object o:(List<?>)capture.get("results")) { Map<?,?> r=(Map<?,?>)o; out.putIfAbsent((String)r.get("key"),r.get("observed")); }
        return out;
    }

    @Test public void missingWidgetExpiresAsSourceBoundUnknownInsteadOfLaterOrbCapture() throws Exception
    {
        Fixture f=new Fixture();f.emit();String id=String.valueOf(f.p.events.pendingEvents.get(0).get("event_id"));
        f.p.onClientTick(null);assertEquals(1,f.p.events.pendingEvents.size());
        f.tick=104;f.p.onClientTick(null);
        Map<String,Object> row=f.p.events.pendingEvents.get(1);
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
    @Test public void screenThatChangesBeforeTheFrameIsLookedAtAgainWithinTheSourceDelay() throws Exception
    {
        Fixture f=new Fixture();f.show();f.emit();String id=String.valueOf(f.p.events.pendingEvents.get(0).get("event_id"));
        Widget w=f.client.getWidget(InterfaceID.Chatbox.LINE0);
        // The line moves before the frame: the source goes back on the queue, no unknown row yet.
        f.p.onClientTick(null);when(w.getBounds()).thenReturn(new Rectangle(1,30,400,20));
        f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        f.finish.get().run();
        assertNull(f.encode.get());assertEquals(1,f.p.events.pendingEvents.size());
        // Next tick, inside the delay: the settled line is selected again and photographed.
        f.tick=101;f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull(f.encode.get());
        // One budget charge for both looks.
        assertTrue(f.p.verificationProbe.takeFrame(System.currentTimeMillis()));
        // A change on the last allowed tick is reported, bound to the source.
        Fixture g=new Fixture();g.show();g.emit();Widget v=g.client.getWidget(InterfaceID.Chatbox.LINE0);
        g.tick=103;g.p.onClientTick(null);when(v.getBounds()).thenReturn(new Rectangle(1,30,400,20));g.tick=104;
        g.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        g.finish.get().run();
        assertNull(g.encode.get());
        Map<?,?> result=(Map<?,?>)((List<?>)g.p.events.pendingEvents.get(1).get("results")).get(0);
        assertEquals("screen_changed_before_frame",((Map<?,?>)result.get("inputs")).get("reason"));
        assertEquals(id.length(),String.valueOf(g.p.events.pendingEvents.get(1).get("source_event_id")).length());
    }
    @Test public void canvasResizedBeforeTheFrameIsAChangedScreenNotACrop() throws Exception
    {
        Fixture f=new Fixture();f.show();f.emit();
        f.p.onClientTick(null);
        when(f.client.getCanvasWidth()).thenReturn(1000);
        f.draws.processDrawComplete(()->new BufferedImage(1000,600,BufferedImage.TYPE_INT_RGB));
        f.finish.get().run();
        assertNull("no pixels are taken with bounds from another canvas size", f.encode.get());
        assertEquals("the source waits for a retry inside its delay", 1, f.p.events.pendingEvents.size());
    }
}
