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
            p.verify.frameHttp = http;
            p.verify.frameHttpBase = http;
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
        Widget line;
        /** The kill-count line as a child of the chat scroll area, where the game keeps each line's text. */
        void show()
        {
            line=mock(Widget.class);when(line.getText()).thenReturn("Your Vorkath kill count is: 42.");
            when(line.getId()).thenReturn(InterfaceID.Chatbox.SCROLLAREA);when(line.getBounds()).thenReturn(new Rectangle(1,1,400,20));
            Widget scroll=mock(Widget.class);when(scroll.getDynamicChildren()).thenReturn(new Widget[]{line});
            when(client.getWidget(InterfaceID.Chatbox.SCROLLAREA)).thenReturn(scroll);
        }
    }
    @Test public void emittedEventIdentityDrivesImmediateCaptureAndSurvivesProjection() throws Exception
    {
        Fixture f=new Fixture();assertTrue(f.p.verificationProbe.takeEvent(System.currentTimeMillis()));
        f.emit();f.show();String id=String.valueOf(f.p.events.pendingEvents.get(0).get("event_id"));
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
        Fixture f=new Fixture();f.emit();f.show();
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
        Fixture f=new Fixture();f.emit();f.show();
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
        f.emit(); f.show();
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
        Fixture f=new Fixture();f.emit();f.show();
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
        Fixture f=new Fixture();f.emit();f.show();
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
        Fixture f=new Fixture();f.emit();f.show();
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
    @Test public void aSampleFrameOnTheSampleTickIsAlsoRecheckedAgainstTheSampledValues() throws Exception
    {
        // Run energy or hitpoints can change within the tick: the frame must still show what the rows expect.
        Fixture f=new Fixture();
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        when(orb.getText()).thenReturn("41");
        f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        if (f.finish.get()!=null) f.finish.get().run();
        assertNull("nothing is encoded",f.encode.get());
        assertEquals("screen_changed_before_frame",observed(lastCapture(f)).get("frame_skipped"));
        // Moved, same value: also changed.
        Fixture g=new Fixture();
        Widget orb2=mock(Widget.class);when(orb2.getText()).thenReturn("42");when(orb2.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb2);
        g.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        when(orb2.getBounds()).thenReturn(new Rectangle(500,60,20,12));
        g.draws.processDrawComplete(EventVerificationWiringTest::scene);
        if (g.finish.get()!=null) g.finish.get().run();
        assertNull(g.encode.get());
        // Unchanged on the sample tick: taken.
        Fixture h=new Fixture();
        Widget orb3=mock(Widget.class);when(orb3.getText()).thenReturn("42");when(orb3.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(h.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb3);
        h.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        h.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull(h.encode.get());
    }
    @Test public void aDrawnFrameOfAnotherSizeThanTheCanvasIsNeverUsed() throws Exception
    {
        // Rig, resize (11-07): the drawn frame lagged the canvas, so regions scaled by the size ratio point at the wrong pixels.
        Fixture f=new Fixture();f.emit();f.show();
        f.p.onClientTick(null);
        f.draws.processDrawComplete(()->{BufferedImage i=new BufferedImage(760,600,BufferedImage.TYPE_INT_RGB);i.setRGB(5,5,0x445566);i.setRGB(300,200,0x112233);return i;});
        f.finish.get().run();
        assertNull("no pixels are taken from a frame of another size", f.encode.get());
        // Stretched mode: the drawn frame must be the stretched size.
        Fixture g=new Fixture();g.emit();g.show();
        when(g.client.isStretchedEnabled()).thenReturn(true);when(g.client.getStretchedDimensions()).thenReturn(new java.awt.Dimension(1200,900));
        when(g.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        g.p.onClientTick(null);
        g.draws.processDrawComplete(()->{BufferedImage i=new BufferedImage(1200,900,BufferedImage.TYPE_INT_RGB);for(int y=0;y<900;y+=9)for(int x=0;x<1200;x+=7)i.setRGB(x,y,0x203040+x);return i;});
        assertNotNull("a stretched frame of the stretched size is used", g.encode.get());
    }
    @Test public void aDisplayScaledFrameOfTheCanvasIsUsed() throws Exception
    {
        // Windows display scaling at 125%: RuneLite draws the frame at canvas x 1.25 (CPU rounds, GPU truncates).
        Fixture f=new Fixture();f.emit();f.show();
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
        f.emit();f.show();f.p.onClientTick(null);
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
    @Test public void captureRowsSayHowTheFrameWasDrawn() throws Exception
    {
        // Stretched to 1200x900 at 125% display scaling: the drawn frame is 1500x1125.
        Fixture f=sizedEventFrame(800,600,new java.awt.Dimension(1200,900),1.25,1500,1125);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.encode.get().run();f.finish.get().run();
        Map<String,Object> capture=observed(f.p.events.pendingEvents.get(1));
        assertEquals("regions",capture.get("scope"));
        assertEquals(1.25,capture.get("display_scale_x"));assertEquals(1.25,capture.get("display_scale_y"));
        assertEquals(Boolean.TRUE,capture.get("stretched"));
        assertEquals(1200,capture.get("stretched_width"));assertEquals(900,capture.get("stretched_height"));
        assertEquals(Boolean.FALSE,capture.get("gpu"));
        assertEquals(800,capture.get("canvas_width"));assertEquals(600,capture.get("canvas_height"));
        assertEquals(1500,capture.get("drawn_width"));assertEquals(1125,capture.get("drawn_height"));
        // Only numbers and booleans, and they survive the public projection.
        Map<String,Object> wire=PublicTransport.event(f.p.events.pendingEvents.get(1),false);
        Map<String,Object> projected=observed(wire);
        for (String k:new String[]{"display_scale_x","stretched","stretched_width","gpu","canvas_width","drawn_width","drawn_height"})
            assertEquals(k,capture.get(k),projected.get(k));
        // Unknown is null, never a made-up 1.
        Fixture g=new Fixture();g.emit();g.show();
        when(g.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        g.p.onClientTick(null);g.draws.processDrawComplete(EventVerificationWiringTest::scene);g.encode.get().run();g.finish.get().run();
        Map<String,Object> plain=observed(g.p.events.pendingEvents.get(1));
        assertNull(plain.get("display_scale_x"));assertEquals(Boolean.FALSE,plain.get("stretched"));assertNull(plain.get("stretched_width"));
        assertEquals(800,plain.get("drawn_width"));
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
        assertEquals("the skip row says what size was drawn",799,observed(lastCapture(f)).get("drawn_width"));
        assertEquals(800,observed(lastCapture(f)).get("canvas_width"));
        verify(f.http,never()).newCall(any());
    }
    /** The client's own pixel buffer at the canvas size, with something drawn everywhere. */
    private static void clientBuffer(Fixture f)
    {
        int[] pixels=new int[800*600];
        for (int i=0;i<pixels.length;i++) pixels[i]=0x203040+(i%251);
        net.runelite.api.BufferProvider buffer=mock(net.runelite.api.BufferProvider.class);
        when(buffer.getPixels()).thenReturn(pixels);when(buffer.getWidth()).thenReturn(800);when(buffer.getHeight()).thenReturn(600);
        when(f.client.getBufferProvider()).thenReturn(buffer);
    }
    private static BufferedImage oddSizedScene()
    {
        BufferedImage i=new BufferedImage(1001,749,BufferedImage.TYPE_INT_RGB);
        for (int y=0;y<749;y+=7) for (int x=0;x<1001;x+=9) i.setRGB(x,y,0x203040+x);
        return i;
    }
    @Test public void anEventFrameFromTheClientBufferIsTakenWhateverSizeTheDrawnFrameHas() throws Exception
    {
        // A display scale or renderer whose drawn size neither rounding rule predicts: the native pixels are still exact.
        Fixture f=new Fixture();f.emit();f.show();clientBuffer(f);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::oddSizedScene);
        assertNotNull("the region frame is taken",f.encode.get());f.encode.get().run();f.finish.get().run();
        assertEquals("client_buffer",observed(f.p.events.pendingEvents.get(1)).get("pixel_source"));
        // The full window would be the drawn frame itself, whose size does not fit the regions: it is not taken.
        assertEquals("one upload: the region frame",1,uploadedImages(f).size());
        assertNull(lastFullWindow(f));
        // Without a client buffer the drawn frame's size still decides (unchanged).
        Fixture g=new Fixture();g.emit();g.show();
        g.p.onClientTick(null);g.draws.processDrawComplete(EventVerificationWiringTest::oddSizedScene);
        assertNull(g.encode.get());
    }
    @Test public void aSampleFrameFromTheClientBufferIsTakenWhateverSizeTheDrawnFrameHas() throws Exception
    {
        Fixture f=new Fixture();clientBuffer(f);
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,true);
        f.draws.processDrawComplete(EventVerificationWiringTest::oddSizedScene);
        assertNotNull("the region frame is taken",f.encode.get());f.encode.get().run();f.finish.get().run();
        assertEquals("client_buffer",observed(lastCapture(f)).get("pixel_source"));
        assertEquals("regions",observed(lastCapture(f)).get("scope"));
        assertEquals("no full window from a drawn frame of another size",1,uploadedImages(f).size());
    }
    private static Map<String,Object> lastFullWindow(Fixture f)
    {
        for (Map<String,Object> r:f.p.events.pendingEvents)
            if ("capture".equals(r.get("check"))&&"full_window".equals(observed(r).get("scope"))) return r;
        return null;
    }
    @Test public void fullWindowsHaveTheirOwnSixAnHourAndNeverTakeARegionFrameSlot()
    {
        VerificationProbe probe=new VerificationProbe(n->1);long now=1_000_000L;
        for (int i=0;i<FullWindowFrame.MAX_PER_HOUR;i++) assertTrue(probe.takeFullWindow(now));
        assertFalse(probe.takeFullWindow(now));
        assertEquals("every region frame slot is still free",VerificationProbe.MAX_EVENT_FRAMES_PER_HOUR,budgetRoom(probe,true,now));
        assertTrue("after an hour the budget is back",probe.takeFullWindow(now+VerificationProbe.HOUR_MS));
        // Region frames spent do not stop the optional full window either: the two budgets are separate.
        VerificationProbe full=new VerificationProbe(n->1);
        for (int i=0;i<VerificationProbe.MAX_EVENT_FRAMES_PER_HOUR;i++) assertTrue(full.takeFrame(true,now));
        assertTrue(full.fullWindowRoom(now));
    }
    private static int budgetRoom(VerificationProbe probe,boolean event,long now)
    {
        int n=0;while (probe.takeFrame(event,now)) n++;return n;
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
        // The source is refused and says so; the hourly admission is not spent on it.
        assertEquals(1,unknownRows(f,"pending_capacity"));
        long now=System.currentTimeMillis();int admitted=0;
        while (f.p.verificationProbe.takeEvent(now)) admitted++;
        assertEquals(VerificationProbe.MAX_EVENTS_PER_HOUR,admitted);
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
        Fixture f=new Fixture();f.emit();f.show();
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        f.encode.get().run();f.p.grants.clearVerificationGrant();f.finish.get().run();
        verify(f.http,never()).newCall(any());
        assertEquals(VerificationProbe.MAX_EVENT_FRAMES_PER_HOUR,budgetRoom(f.p.verificationProbe,true,System.currentTimeMillis()));
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
            assertEquals(VerificationProbe.MAX_FRAMES_PER_HOUR,budgetRoom(g.p.verificationProbe,false,System.currentTimeMillis()));
        }
    }
    @Test public void aTradeOrPinScreenOpenedAfterSelectionUploadsNothing() throws Exception
    {
        for (int blocker:Verification.VERIFY_FRAME_BLOCKERS)
        {
            // Event: the screen opens while the frame is encoded.
            Fixture f=new Fixture();f.emit();f.show();
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
        f.emit();f.show();
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
        Fixture f=new Fixture();f.emit();f.show();
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
        Fixture f=new Fixture();f.emit();f.show();
        f.p.onClientTick(null);f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        f.finish.get().run();
        assertEquals(VerificationProbe.MAX_EVENT_FRAMES_PER_HOUR,budgetRoom(f.p.verificationProbe,true,System.currentTimeMillis()));
        // Sample: a frame drawn two ticks late is skipped and takes no frame slot.
        Fixture g=new Fixture();
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        g.p.verify.requestVerificationFrame("fedcba9876543210",100,1000L,999L,false);
        g.tick=102;g.draws.processDrawComplete(EventVerificationWiringTest::scene);
        if (g.finish.get()!=null) g.finish.get().run();
        assertEquals("frame_on_later_tick",observed(lastCapture(g)).get("frame_skipped"));
        assertEquals(VerificationProbe.MAX_FRAMES_PER_HOUR,budgetRoom(g.p.verificationProbe,false,System.currentTimeMillis()));
        // An uploaded frame keeps its slot.
        Fixture h=new Fixture();h.emit();h.show();
        when(h.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        h.p.onClientTick(null);h.draws.processDrawComplete(EventVerificationWiringTest::scene);
        h.encode.get().run();h.finish.get().run();
        assertEquals("the region frame takes one region slot; the full window takes none",VerificationProbe.MAX_EVENT_FRAMES_PER_HOUR-1,
            budgetRoom(h.p.verificationProbe,true,System.currentTimeMillis()));
        assertFalse("the full window took one of its own",fullWindowsLeft(h.p.verificationProbe)==FullWindowFrame.MAX_PER_HOUR);
    }
    private static Map<String,Object> observed(Map<String,Object> capture)
    {
        Map<String,Object> out=new HashMap<>();
        for (Object o:(List<?>)capture.get("results")) { Map<?,?> r=(Map<?,?>)o; out.putIfAbsent((String)r.get("key"),r.get("observed")); }
        return out;
    }

    @Test public void frameRowsKeepTheSourceEventsProfileAfterAWorldChange() throws Exception
    {
        Fixture f=new Fixture();set(f.p,"activeProfileType","STANDARD");
        f.emit();f.show();
        assertEquals("STANDARD",f.p.events.pendingEvents.get(0).get("profile_type"));
        // The player is on another world type by the time the frame is drawn and its rows are written.
        set(f.p,"activeProfileType","SEASONAL");
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        f.encode.get().run();f.finish.get().run();
        assertTrue(f.p.events.pendingEvents.size()>2);
        for (Map<String,Object> row:f.p.events.pendingEvents)
            assertEquals(row.get("area")+"/"+row.get("check"),"STANDARD",row.get("profile_type"));
        // An event made with no known profile carries none on its rows, rather than the current one.
        Fixture g=new Fixture();g.emit();g.show();set(g.p,"activeProfileType","SEASONAL");
        g.tick=104;g.p.onClientTick(null);
        assertNull(g.p.events.pendingEvents.get(1).get("profile_type"));
    }
    @Test public void eventsThatCanNeverBeOnScreenTakeNoAdmissionAndLeaveNoScreenRow() throws Exception
    {
        Fixture f=new Fixture();
        Map<String,Object> drop=new LinkedHashMap<>();drop.put("id",995);drop.put("qty",100);
        f.p.events.emitEvent("loot",VerificationProbe.inputs("source_name","Goblin","source_type","npc","items",Arrays.asList(drop)));
        f.p.events.emitEvent("death",VerificationProbe.inputs("death_kind","normal"));
        f.p.events.emitEvent("slayer_task",VerificationProbe.inputs("tasks_completed",12));
        assertEquals("the three events are sent",3,f.p.events.pendingEvents.size());
        for (Map<String,Object> row:f.p.events.pendingEvents)
            assertEquals("each still names its verification tick",100,row.get("verification_tick"));
        assertTrue("no source waits for a frame",f.p.eventVerification.drain().isEmpty());
        long now=System.currentTimeMillis();int admitted=0;
        while (f.p.verificationProbe.takeEvent(now)) admitted++;
        assertEquals("no admission was spent",VerificationProbe.MAX_EVENTS_PER_HOUR,admitted);
        f.tick=104;f.p.onClientTick(null);
        assertEquals("no UNKNOWN screen rows",3,f.p.events.pendingEvents.size());
        // Reward-panel loot, a kill-count line and a level-up still can.
        Fixture g=new Fixture();
        g.p.events.emitEvent("loot",VerificationProbe.inputs("source_name","Barrows","source_type","event","items",Arrays.asList(drop)));
        g.emit();
        g.p.events.emitEvent("level_up",VerificationProbe.inputs("skill","Mining","level",9));
        assertEquals(3,g.p.eventVerification.drain().size());
    }
    @Test public void spentSampleFramesDoNotStopAnEventFrameAndSpentEventFramesDo() throws Exception
    {
        Fixture f=new Fixture();long now=System.currentTimeMillis();
        while (f.p.verificationProbe.takeFrame(false,now)) { }
        f.emit();f.show();f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull("the event still has its own frame slots",f.encode.get());
        Fixture g=new Fixture();
        while (g.p.verificationProbe.takeFrame(true,now)) { }
        g.emit();g.show();g.p.onClientTick(null);
        assertEquals(1,unknownRows(g,"frame_budget"));
    }
    @Test public void aLineAlreadyInTheChatWhenTheEventIsMadeIsNotItsEvidence() throws Exception
    {
        // The same text from an earlier event is on screen and the event's own line never arrives.
        Fixture f=new Fixture();f.show();f.emit();
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNull("no frame of the older line",f.encode.get());
        f.tick=104;f.p.onClientTick(null);
        assertEquals(1,unknownRows(f,"older_or_repeated_line"));
        verify(f.http,never()).newCall(any());
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
        Fixture f=new Fixture();f.emit();f.show();f.p.onClientTick(null);f.account=456;
        f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        assertNull(f.encode.get());verify(f.http,never()).newCall(any());
    }
    @Test public void screenThatChangesBeforeTheFrameIsLookedAtAgainWithinTheSourceDelay() throws Exception
    {
        Fixture f=new Fixture();f.emit();f.show();String id=String.valueOf(f.p.events.pendingEvents.get(0).get("event_id"));
        Widget w=f.line;
        // The line moves before the frame: the source goes back on the queue, no unknown row yet.
        f.p.onClientTick(null);when(w.getBounds()).thenReturn(new Rectangle(1,30,400,20));
        f.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        f.finish.get().run();
        assertNull(f.encode.get());assertEquals(1,f.p.events.pendingEvents.size());
        // Next tick, inside the delay: the settled line is selected again and photographed.
        f.tick=101;f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull(f.encode.get());
        // One budget charge for both looks.
        assertTrue(f.p.verificationProbe.takeFrame(true,System.currentTimeMillis()));
        // A change on the last allowed tick is reported, bound to the source.
        Fixture g=new Fixture();g.emit();g.show();Widget v=g.line;
        g.tick=103;g.p.onClientTick(null);when(v.getBounds()).thenReturn(new Rectangle(1,30,400,20));g.tick=104;
        g.draws.processDrawComplete(()->new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
        g.finish.get().run();
        assertNull(g.encode.get());
        Map<?,?> result=(Map<?,?>)((List<?>)g.p.events.pendingEvents.get(1).get("results")).get(0);
        assertEquals("screen_changed_before_frame",((Map<?,?>)result.get("inputs")).get("reason"));
        assertEquals(id.length(),String.valueOf(g.p.events.pendingEvents.get(1).get("source_event_id")).length());
    }
    private static boolean frameBound(Map<String,Object> row)
    {
        return Boolean.TRUE.equals(row.get(EventPriority.FRAME_BOUND))&&"verification".equals(row.get("type"));
    }
    @Test public void anUploadedFramesRowsOutlastA429HoldWhileUnframedVerificationRowsGoFirst() throws Exception
    {
        Fixture f=new Fixture();f.emit();f.show();
        okhttp3.Call call=mock(okhttp3.Call.class);AtomicReference<okhttp3.Callback> upload=new AtomicReference<>();
        doAnswer(i->{upload.set(i.getArgument(0));return null;}).when(call).enqueue(any());
        when(f.http.newCall(any())).thenReturn(call);
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        f.encode.get().run();f.finish.get().run();
        // The upload's answer is lost: its outcome row is written under the frame's ref.
        upload.get().onFailure(call,new java.io.IOException("lost"));f.finish.get().run();
        // Rows with no frame: an event that is never on screen, and a sample frame that is not taken.
        Map<String,Object> other=VerificationProbe.inputs("event_id","aabbccddeeff00112233445566778899aa","type","kill_count");
        f.p.verify.eventEvidenceUnknown(new EventVerification.Source(other,TOKEN,"123",0,100,999,0,null),"source_not_visible");
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        if (f.finish.get()!=null) f.finish.get().run();
        List<Map<String,Object>> framed=new ArrayList<>();
        Set<String> checks=new HashSet<>();
        for (Map<String,Object> row:f.p.events.pendingEvents) if (frameBound(row)) { framed.add(row);checks.add(row.get("area")+"/"+row.get("check")); }
        assertEquals("region and full window: capture and screen rows, and the upload row",5,framed.size());
        assertEquals(new HashSet<>(Arrays.asList("frame/capture","screen/ocr_event","frame/upload")),checks);
        assertEquals(1,unknownRows(f,"source_not_visible"));
        assertEquals("no_numeric_region",observed(lastCapture(f)).get("frame_skipped"));
        for (Map<String,Object> row:f.p.events.pendingEvents)
            if ("verification".equals(row.get("type"))&&!framed.contains(row)) assertEquals(EventPriority.P2,EventPriority.rowClass(row));
        // The marker is local: the wire row is the same as before.
        for (Map<String,Object> row:framed) assertFalse(PublicTransport.event(row,false).containsKey(EventPriority.FRAME_BOUND));
        // A 429: the batch goes back to the front and the lane waits, while the player goes on playing.
        List<Map<String,Object>> batch;
        synchronized (f.p.events.pendingEvents) { batch=new ArrayList<>(f.p.events.pendingEvents);f.p.events.pendingEvents.clear(); }
        f.p.events.requeueEvents(batch,60_000L);
        assertTrue(f.p.events.eventRetryBackoffUntilMs>System.currentTimeMillis());
        for (int i=0;i<EventQueue.MAX_PENDING_EVENTS+100;i++) f.p.events.emitEvent("region",VerificationProbe.inputs("from",1,"to",2));
        assertEquals(EventQueue.MAX_PENDING_EVENTS,f.p.events.pendingEvents.size());
        int framedLeft=0;
        for (Map<String,Object> row:f.p.events.pendingEvents) if (framed.contains(row)) framedLeft++;
        assertEquals("every row of the uploaded frame survives",framed.size(),framedLeft);
        assertEquals("kill_count",f.p.events.pendingEvents.get(0).get("type"));
        assertEquals("the unframed rows were evicted",0,unknownRows(f,"source_not_visible"));
        for (Map<String,Object> row:f.p.events.pendingEvents)
            if ("capture".equals(row.get("check"))) assertNull("the skip row was evicted",observed(row).get("frame_skipped"));
        assertEquals(0L,f.p.events.captureHealthSnapshot().get("events_overflow_p1_total"));
    }
    @Test public void aFrameBoundRowStaysP2AndGoesLastWithinP2ButBeforeAnyGameEvent()
    {
        Map<String,Object> row=VerificationProbe.inputs("type","verification","area","screen","ref","0123456789abcdef");
        row.put(EventPriority.FRAME_BOUND,true);
        assertEquals("the marker never raises a row's class",EventPriority.P2,EventPriority.rowClass(row));
        assertEquals(EventPriority.P0,EventPriority.rowClass(VerificationProbe.inputs("type","ge_buy")));
        assertEquals(EventPriority.P1,EventPriority.rowClass(VerificationProbe.inputs("type","loot")));
        Map<String,Object> diag=VerificationProbe.inputs("type","verification","area","screen");
        // Within P2 every other row goes before a frame's row, older or not.
        List<Map<String,Object>> q=new ArrayList<>(Arrays.asList(row,VerificationProbe.inputs("type","loot"),diag,VerificationProbe.inputs("type","region")));
        assertEquals(2,EventPriority.evictIndex(q));
        q.remove(2);
        assertEquals(2,EventPriority.evictIndex(q));
        q.remove(2);
        // Only P1 game rows and frame rows left: the frame's row goes, never the loot.
        assertEquals(0,EventPriority.evictIndex(q));
        List<Map<String,Object>> p1=new ArrayList<>(Arrays.asList(VerificationProbe.inputs("type","kill_count"),VerificationProbe.inputs("type","level_up"),row));
        assertEquals("a buffer of P1 events and one frame row drops the frame row",2,EventPriority.evictIndex(p1));
        p1.remove(2);
        assertEquals("then the oldest P1, as before",0,EventPriority.evictIndex(p1));
    }
    private static Fixture sampleFixture() throws Exception
    {
        Fixture f=new Fixture();
        when(f.client.getLocalPlayer()).thenReturn(mock(net.runelite.api.Player.class));
        return f;
    }
    private static void sample(Fixture f) throws Exception
    {
        java.lang.reflect.Method m=Verification.class.getDeclaredMethod("verificationSample",VerificationProbe.Sample.class);
        m.setAccessible(true);m.invoke(f.p.verify,new VerificationProbe.Sample("random",null,null));
    }
    private static Set<String> sampleChecks(Fixture f)
    {
        Set<String> out=new HashSet<>();
        for (Map<String,Object> row:f.p.events.pendingEvents) if ("verification".equals(row.get("type"))) out.add(row.get("area")+"/"+row.get("check"));
        f.p.events.pendingEvents.clear();
        return out;
    }
    @Test public void zeroYieldChecksRunOnTheFirstSampleAfterALoginThenOneInTen() throws Exception
    {
        Fixture f=sampleFixture();
        List<String> thinned=Arrays.asList("skills/xp_to_level","skills/derived_levels","counters/server_vs_client","account/server_vs_client");
        f.p.worldLoginTick=50;
        sample(f);
        assertTrue(sampleChecks(f).containsAll(thinned));
        for (int i=1;i<VerificationProbe.ZERO_YIELD_ONE_IN;i++)
        {
            sample(f);Set<String> checks=sampleChecks(f);
            for (String c:thinned) assertFalse(c,checks.contains(c));
            assertTrue("the other checks run on every sample",checks.contains("skills/boosted_vs_orbs")&&checks.contains("inventory/container_vs_widget"));
        }
        sample(f);assertTrue("the tenth sample after the first",sampleChecks(f).containsAll(thinned));
        sample(f);assertFalse(sampleChecks(f).contains("skills/xp_to_level"));
        // A new login starts the count again.
        f.p.worldLoginTick=900;
        sample(f);assertTrue(sampleChecks(f).containsAll(thinned));
        sample(f);assertFalse(sampleChecks(f).contains("skills/xp_to_level"));
    }
    @Test public void sampleOrbRegionsExpectTheClientValueNotTheWidgetsOwnText() throws Exception
    {
        Fixture f=new Fixture();
        // The orb shows 42 while the client's boosted hitpoints are 40: the frame arbitrates.
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        when(f.client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS)).thenReturn(40);
        Widget run=mock(Widget.class);when(run.getText()).thenReturn("73");when(run.getBounds()).thenReturn(new Rectangle(500,80,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.RUNENERGY_TEXT)).thenReturn(run);
        when(f.client.getEnergy()).thenReturn(7_399);
        Widget spec=mock(Widget.class);when(spec.getText()).thenReturn("100");when(spec.getBounds()).thenReturn(new Rectangle(500,110,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.SPECENERGY_TEXT)).thenReturn(spec);
        when(f.client.getVarpValue(net.runelite.api.gameval.VarPlayerID.SA_ENERGY)).thenReturn(1_000);
        okhttp3.Call call=mock(okhttp3.Call.class);
        org.mockito.ArgumentCaptor<okhttp3.Request> request=org.mockito.ArgumentCaptor.forClass(okhttp3.Request.class);
        when(f.http.newCall(request.capture())).thenReturn(call);
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull("the widget still shows its number, so the frame is taken",f.encode.get());f.encode.get().run();f.finish.get().run();
        Map<String,Map<?,?>> results=new HashMap<>();
        for (Map<String,Object> row:f.p.events.pendingEvents)
            if ("ocr_numeric".equals(row.get("check"))) for (Object o:(List<?>)row.get("results")) results.put((String)((Map<?,?>)o).get("key"),(Map<?,?>)o);
        assertEquals(40,results.get("hitpoints_orb").get("expected"));
        assertEquals(42,((Map<?,?>)results.get("hitpoints_orb").get("inputs")).get("widget_value"));
        assertEquals(73,results.get("run_energy_orb").get("expected"));
        assertEquals(100,results.get("special_energy_orb").get("expected"));
        // The public projection keeps both numbers.
        Map<String,Object> wire=null;
        for (Map<String,Object> row:f.p.events.pendingEvents) if ("ocr_numeric".equals(row.get("check"))) wire=PublicTransport.event(row,false);
        Map<?,?> hp=null;
        for (Object o:(List<?>)wire.get("results")) if ("hitpoints_orb".equals(((Map<?,?>)o).get("key"))) hp=(Map<?,?>)o;
        assertEquals(40,hp.get("expected"));assertEquals(42,((Map<?,?>)hp.get("inputs")).get("widget_value"));
        // The upload's own pairing copy carries the client value as expected.
        String pairing=null;
        for (okhttp3.MultipartBody.Part part:((okhttp3.MultipartBody)request.getValue().body()).parts())
            if (String.valueOf(part.headers().get("Content-Disposition")).contains("name=\"pairing\"")) { okio.Buffer b=new okio.Buffer();part.body().writeTo(b);pairing=b.readUtf8(); }
        // ...and the widget's own number, so a misread and a screen that differs from the client stay apart.
        assertTrue(pairing,pairing.contains("\"widget_value\":42,\"key\":\"hitpoints_orb\",\"expected\":40"));
    }
    private static net.runelite.api.Item item(int id,int qty)
    {
        return new net.runelite.api.Item(id,qty);
    }
    @Test public void theBankCountRegionExpectsItsOwnNumberWithPlaceholdersAndFillersInTheBank() throws Exception
    {
        // Coins, a placeholder (quantity 0) and a bank filler: how the game counts the last two is not established.
        Fixture f=new Fixture();
        net.runelite.api.ItemContainer bank=mock(net.runelite.api.ItemContainer.class);
        when(bank.getItems()).thenReturn(new net.runelite.api.Item[]{item(995,1000),item(1052,0),item(20594,1)});
        when(f.client.getItemContainer(net.runelite.api.InventoryID.BANK)).thenReturn(bank);
        Widget used=mock(Widget.class);when(used.getText()).thenReturn("2");when(used.getBounds()).thenReturn(new Rectangle(30,10,24,12));
        when(f.client.getWidget(InterfaceID.Bankmain.OCCUPIEDSLOTS)).thenReturn(used);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        f.draws.processDrawComplete(EventVerificationWiringTest::scene);
        assertNotNull(f.encode.get());f.encode.get().run();f.finish.get().run();
        Map<?,?> slots=null;
        for (Map<String,Object> row:f.p.events.pendingEvents)
            if ("ocr_numeric".equals(row.get("check"))) for (Object o:(List<?>)row.get("results"))
                if ("occupied_slots".equals(((Map<?,?>)o).get("key"))) slots=(Map<?,?>)o;
        assertNotNull(slots);
        assertEquals("the number on screen, never a client count of 3 or 2 by some rule",2,slots.get("expected"));
        assertEquals(2,((Map<?,?>)slots.get("inputs")).get("widget_value"));
        // The same with a widget showing 3: still its own number.
        when(used.getText()).thenReturn("3");
        Fixture g=new Fixture();
        when(g.client.getItemContainer(net.runelite.api.InventoryID.BANK)).thenReturn(bank);
        when(g.client.getWidget(InterfaceID.Bankmain.OCCUPIEDSLOTS)).thenReturn(used);
        when(g.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        g.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        g.draws.processDrawComplete(EventVerificationWiringTest::scene);
        g.encode.get().run();g.finish.get().run();
        int found=0;
        for (Map<String,Object> row:g.p.events.pendingEvents)
            if ("ocr_numeric".equals(row.get("check"))) for (Object o:(List<?>)row.get("results"))
                if ("occupied_slots".equals(((Map<?,?>)o).get("key"))) { assertEquals(3,((Map<?,?>)o).get("expected"));found++; }
        assertEquals(1,found);
    }
    @Test public void anUndrawnOrbRightAfterLoginIsNotARegion() throws Exception
    {
        // The orb still reads 0 ten ticks after the login while the client says 99: no region, so no false lead.
        Fixture f=new Fixture();f.p.worldLoginTick=90;
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("0");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(f.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        when(f.client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS)).thenReturn(99);
        f.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
        if (f.finish.get()!=null) f.finish.get().run();
        assertEquals("no_numeric_region",observed(lastCapture(f)).get("frame_skipped"));
    }
    private static final String[] DRAWN_KEYS={"display_scale_x","display_scale_y","stretched","stretched_width","stretched_height",
        "gpu","canvas_width","canvas_height","drawn_width","drawn_height"};
    /** The capture rows of an event frame, a sample frame and a sample's frame_size_mismatch skip row. */
    private static List<Map<String,Object>> threeCaptureRows(boolean staff) throws Exception
    {
        List<Map<String,Object>> out=new ArrayList<>();
        Fixture f=new Fixture();if (!staff) f.p.grants.clearStaffCaptureGrant();
        f.emit();f.show();when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::scene);f.encode.get().run();f.finish.get().run();
        out.add(f.p.events.pendingEvents.get(1));
        for (int size:new int[]{800,799})
        {
            Fixture g=new Fixture();if (!staff) g.p.grants.clearStaffCaptureGrant();
            Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
            when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
            when(g.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
            g.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,false);
            g.draws.processDrawComplete(()->{BufferedImage i=scene();return size==800?i:i.getSubimage(0,0,799,600);});
            if (g.encode.get()!=null) g.encode.get().run();
            g.finish.get().run();
            out.add(lastCapture(g));
        }
        assertEquals("regions",observed(out.get(0)).get("scope"));
        assertEquals("regions",observed(out.get(1)).get("scope"));
        assertEquals("frame_size_mismatch",observed(out.get(2)).get("frame_skipped"));
        return out;
    }
    @Test public void howTheFrameWasDrawnIsRecordedOnlyUnderTheStaffGrant() throws Exception
    {
        for (Map<String,Object> row:threeCaptureRows(true))
            for (String k:DRAWN_KEYS) assertTrue(k,observed(row).containsKey(k));
        // A public link: the rows are as they were before these fields, nothing that describes the device.
        for (Map<String,Object> row:threeCaptureRows(false))
            for (String k:DRAWN_KEYS) assertFalse(k,observed(row).containsKey(k));
    }
    @Test public void aFullWindowLeftOutForItsDrawnSizeIsNamedOnTheCaptureRow() throws Exception
    {
        // Event: regions from the client buffer, a drawn frame of another size, a kill count that wants a full window.
        Fixture f=new Fixture();f.emit();f.show();clientBuffer(f);
        when(f.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        f.p.onClientTick(null);f.draws.processDrawComplete(EventVerificationWiringTest::oddSizedScene);
        f.encode.get().run();f.finish.get().run();
        Map<String,Object> event=observed(f.p.events.pendingEvents.get(1));
        assertEquals("frame_size_mismatch",event.get("full_window_skipped"));assertNull(event.get("paired_ref"));
        // Sample that found a discrepancy: the same.
        Fixture g=new Fixture();clientBuffer(g);
        Widget orb=mock(Widget.class);when(orb.getText()).thenReturn("42");when(orb.getBounds()).thenReturn(new Rectangle(500,50,20,12));
        when(g.client.getWidget(InterfaceID.Orbs.HEALTH_TEXT)).thenReturn(orb);
        when(g.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        g.p.verify.requestVerificationFrame("0123456789abcdef",100,1000L,999L,true);
        g.draws.processDrawComplete(EventVerificationWiringTest::oddSizedScene);
        g.encode.get().run();g.finish.get().run();
        assertEquals("frame_size_mismatch",observed(lastCapture(g)).get("full_window_skipped"));
        // A drawn frame that fits gives the full window and no reason; no full window wanted gives no reason either.
        Fixture h=new Fixture();h.emit();h.show();clientBuffer(h);
        when(h.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        h.p.onClientTick(null);h.draws.processDrawComplete(EventVerificationWiringTest::scene);
        h.encode.get().run();h.finish.get().run();
        Map<String,Object> fits=observed(h.p.events.pendingEvents.get(1));
        assertFalse(fits.containsKey("full_window_skipped"));assertNotNull(fits.get("paired_ref"));
        Fixture k=new Fixture();k.p.grants.clearStaffCaptureGrant();k.emit();k.show();clientBuffer(k);
        when(k.http.newCall(any())).thenReturn(mock(okhttp3.Call.class));
        k.p.onClientTick(null);k.draws.processDrawComplete(EventVerificationWiringTest::oddSizedScene);
        k.encode.get().run();k.finish.get().run();
        assertFalse(observed(k.p.events.pendingEvents.get(1)).containsKey("full_window_skipped"));
    }
    @Test public void canvasResizedBeforeTheFrameIsAChangedScreenNotACrop() throws Exception
    {
        Fixture f=new Fixture();f.emit();f.show();
        f.p.onClientTick(null);
        when(f.client.getCanvasWidth()).thenReturn(1000);
        f.draws.processDrawComplete(()->new BufferedImage(1000,600,BufferedImage.TYPE_INT_RGB));
        f.finish.get().run();
        assertNull("no pixels are taken with bounds from another canvas size", f.encode.get());
        assertEquals("the source waits for a retry inside its delay", 1, f.p.events.pendingEvents.size());
    }
}
