package com.osrsbestinslot.export;

import java.awt.*;
import java.awt.image.BufferedImage;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FlipperOverviewControllerTest
{
    static void overview(FlipperControllerTest.Fixture f)
    {
        when(f.setup.isHidden()).thenReturn(true);when(f.details.isHidden()).thenReturn(true);
        Widget index=mock(Widget.class);when(f.client.getWidget(InterfaceID.GeOffers.INDEX)).thenReturn(index);
        for(int i=0;i<8;i++)
        {
            final int indexNumber=i;
            Widget slot=mock(Widget.class);Rectangle bounds=new Rectangle(22+(i%4)*123,55+(i/4)*115,116,106);
            when(slot.getBounds()).thenReturn(bounds);
            Widget item=mock(Widget.class);when(item.getBounds()).thenReturn(new Rectangle(bounds.x+6,bounds.y+25,32,32));
            when(item.getItemId()).thenAnswer(call->f.offers[indexNumber].getItemId());
            when(slot.getChildren()).thenReturn(new Widget[]{item});
            when(f.client.getWidget(FlipperOverviewOverlay.SLOT_WIDGETS[i])).thenReturn(slot);
        }
        f.tick();
    }
    static int paint(FlipperControllerTest.Fixture f)
    {
        BufferedImage image=new BufferedImage(765,503,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        f.overlays.get(1).render(g);g.dispose();int count=0;
        for(int x=0;x<image.getWidth();x++)for(int y=0;y<image.getHeight();y++)if((image.getRGB(x,y)>>>24)!=0)count++;
        return count;
    }
    @Test public void nativeOverviewTracksLocalOfferAndAddsHoverWithoutChangingGameActions() throws Exception
    {
        try(FlipperControllerTest.Fixture f=new FlipperControllerTest.Fixture())
        {
            overview(f);f.offer(0,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING,100,100,25,200,5000));f.tick();
            when(f.client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(30,60));
            assertTrue(paint(f)>0);assertEquals(1,f.tooltips.getTooltips().size());
            String text=f.tooltips.getTooltips().get(0).getText();assertTrue(text.contains("Filled 25 / 100"));assertTrue(text.contains("Remaining 75"));
            f.tooltips.clear();f.offer(0,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.EMPTY,0,0,0,0,0));
            assertEquals(0,paint(f));assertTrue(f.tooltips.getTooltips().isEmpty());
            f.tick();assertEquals(0,paint(f));
        }
    }
    @Test public void inputMenuHiddenBoundsAndAccountSwitchHideImmediately() throws Exception
    {
        try(FlipperControllerTest.Fixture f=new FlipperControllerTest.Fixture())
        {
            overview(f);f.offer(0,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.BUYING,100,100,0,200,0));f.tick();
            assertTrue(paint(f)>0);
            when(f.client.isMenuOpen()).thenReturn(true);assertEquals(0,paint(f));when(f.client.isMenuOpen()).thenReturn(false);
            when(f.client.getVarcIntValue(VarClientInt.INPUT_TYPE)).thenReturn(7);assertEquals(0,paint(f));
            when(f.client.getVarcIntValue(VarClientInt.INPUT_TYPE)).thenReturn(0);
            when(f.client.getWidget(InterfaceID.GeOffers.INDEX).isHidden()).thenReturn(true);assertEquals(0,paint(f));
            when(f.client.getWidget(InterfaceID.GeOffers.INDEX).isHidden()).thenReturn(false);
            when(f.frame.getBounds()).thenReturn(new Rectangle(100,2,510,334));assertEquals(0,paint(f));
            when(f.frame.getBounds()).thenReturn(new Rectangle(8,2,510,334));
            when(f.setup.isHidden()).thenReturn(false);assertEquals(0,paint(f));when(f.setup.isHidden()).thenReturn(true);
            Widget item=f.client.getWidget(FlipperOverviewOverlay.SLOT_WIDGETS[0]).getChildren()[0];
            when(item.getItemId()).thenReturn(999);assertEquals(0,paint(f));when(item.getItemId()).thenReturn(100);
            when(f.client.getWorldType()).thenReturn(java.util.EnumSet.of(WorldType.DEADMAN));assertEquals(0,paint(f));
            when(f.client.getWorldType()).thenReturn(java.util.EnumSet.noneOf(WorldType.class));
            when(f.client.getAccountHash()).thenReturn(11L);assertEquals(0,paint(f));
        }
    }
    @Test public void toggleAndLifecycleRemoveOverviewWithoutInventingBaselineAge() throws Exception
    {
        try(FlipperControllerTest.Fixture f=new FlipperControllerTest.Fixture())
        {
            overview(f);f.offer(0,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.BUYING,100,100,5,200,1000));f.tick();
            when(f.client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(30,60));assertTrue(paint(f)>0);
            f.enabled.set(false);assertEquals(0,paint(f));f.tick();f.enabled.set(true);f.tick();
            f.tooltips.clear();assertTrue(paint(f)>0);assertTrue(f.tooltips.getTooltips().get(0).getText().contains("Prior start / last fill: unknown"));
            f.state(GameState.HOPPING);assertEquals(0,paint(f));f.state(GameState.LOGGED_IN);f.tick();
            f.tooltips.clear();assertTrue(paint(f)>0);assertTrue(f.tooltips.getTooltips().get(0).getText().contains("Prior start / last fill: unknown"));
        }
    }
    @Test public void missingSlotGeometrySuppressesThatOfferWithoutException() throws Exception
    {
        try(FlipperControllerTest.Fixture f=new FlipperControllerTest.Fixture())
        {
            overview(f);f.offer(0,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.BUYING,100,100,0,200,0));f.tick();
            Widget slot=f.client.getWidget(FlipperOverviewOverlay.SLOT_WIDGETS[0]);when(slot.getBounds()).thenReturn(null);
            assertEquals(0,paint(f));f.tick();assertEquals(0,paint(f));assertTrue(f.tooltips.getTooltips().isEmpty());
            when(slot.getBounds()).thenReturn(new Rectangle(20,30,0,0));f.tick();assertEquals(0,paint(f));
        }
    }

    @Test public void renderIntegratedHoverAndSuppressedStates() throws Exception
    {
        try(FlipperControllerTest.Fixture f=new FlipperControllerTest.Fixture())
        {
            overview(f);f.offer(0,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING,100,6000,4000,181,724000));
            f.offer(1,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.BOUGHT,101,250,250,100,25000));
            f.offer(2,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.CANCELLED_BUY,102,100,20,500,10000));f.tick();
            when(f.client.getMouseCanvasPosition()).thenReturn(new net.runelite.api.Point(35,100));
            net.runelite.client.config.RuneLiteConfig config=mock(net.runelite.client.config.RuneLiteConfig.class);
            when(config.overlayBackgroundColor()).thenReturn(new Color(26,28,27,245));
            java.lang.reflect.Constructor<net.runelite.client.ui.overlay.tooltip.TooltipOverlay> constructor=
                net.runelite.client.ui.overlay.tooltip.TooltipOverlay.class.getDeclaredConstructor(Client.class,
                    net.runelite.client.ui.overlay.tooltip.TooltipManager.class,net.runelite.client.config.RuneLiteConfig.class);
            constructor.setAccessible(true);
            net.runelite.client.ui.overlay.tooltip.TooltipOverlay tooltips=constructor.newInstance(f.client,f.tooltips,config);
            java.io.File output=new java.io.File("build/reports/flipper-overview");assertTrue(output.isDirectory()||output.mkdirs());
            String[] states={"hover","input","menu","off"};
            for(String state:states)
            {
                when(f.client.isMenuOpen()).thenReturn("menu".equals(state));
                when(f.client.getVarcIntValue(VarClientInt.INPUT_TYPE)).thenReturn("input".equals(state)?7:0);
                f.enabled.set(!"off".equals(state));
                BufferedImage image=new BufferedImage(765,503,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
                g.setColor(new Color(33,35,30));g.fillRect(0,0,765,503);g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
                g.setColor(Color.LIGHT_GRAY);g.drawString("Integrated native-render fixture: "+state+" (not logged-in capture)",10,15);
                Rectangle frame=f.frame.getBounds();g.setColor(new Color(64,57,44));g.fill(frame);
                for(int i=0;i<8;i++)
                {
                    Rectangle slot=f.client.getWidget(FlipperOverviewOverlay.SLOT_WIDGETS[i]).getBounds();
                    g.setColor(new Color(76,67,51));g.fill(slot);g.setColor(new Color(140,120,80));g.draw(slot);
                    GrandExchangeOffer offer=f.offers[i];g.setColor(new Color(220,180,100));
                    g.drawString(offer.getState().name(),slot.x+4,slot.y+15);
                    if(offer.getItemId()>0){g.drawString("Item "+offer.getItemId(),slot.x+4,slot.y+40);g.drawString(offer.getQuantitySold()+" / "+offer.getTotalQuantity(),slot.x+4,slot.y+70);}
                }
                f.tooltips.clear();f.overlays.get(1).render(g);
                if("hover".equals(state)){assertEquals(1,f.tooltips.getTooltips().size());tooltips.render(g);assertTrue(f.tooltips.getTooltips().isEmpty());}
                else assertTrue(f.tooltips.getTooltips().isEmpty());
                g.setColor(Color.LIGHT_GRAY);g.drawString("Integrated native-render fixture: "+state+" (not logged-in capture)",10,490);
                g.dispose();javax.imageio.ImageIO.write(image,"png",new java.io.File(output,"overview-integrated-"+state+".png"));
            }
        }
    }

}
