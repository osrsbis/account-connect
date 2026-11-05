package com.osrsbestinslot.export;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;
import net.runelite.api.*;
import net.runelite.client.ui.FontManager;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real overlay renderer over a deterministic native-bounds fixture; not a logged-in client capture. */
public class FlipperOverviewRenderTest
{
    static FlipperOverviewOverlay.View fixture()
    {
        FlipperOfferActivity activity = new FlipperOfferActivity();
        GrandExchangeOffer[] baseline = new GrandExchangeOffer[8];
        for(int i=0;i<8;i++) baseline[i]=FlipperBuyHistoryTest.offer(GrandExchangeOfferState.EMPTY,0,0,0,0,0);
        baseline[0]=FlipperBuyHistoryTest.offer(GrandExchangeOfferState.BUYING,100,1000,200,100,20000);
        activity.seed(baseline);
        activity.observe(1,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING,101,6000,4000,181,724000),0);
        activity.observe(2,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.BOUGHT,102,300,300,500,150000),90000);
        activity.observe(3,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.CANCELLED_SELL,103,100,25,10000,250000),100000);
        activity.observe(4,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING,104,500,0,1000,0),115000);
        activity.observe(5,FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SOLD,105,250,250,200,50000),120000);
        Map<Integer,Rectangle> bounds=new LinkedHashMap<>();
        for(int i=0;i<8;i++) bounds.put(i,new Rectangle(22+(i%4)*123,72+(i/4)*115,116,106));
        return new FlipperOverviewOverlay.View("fixture",new Rectangle(8,24,510,292),bounds,activity.snapshot(),125000);
    }
    @Test public void renderMixedOverviewRough() throws Exception
    {
        FlipperOverviewOverlay.View view=fixture();
        BufferedImage image=new BufferedImage(765,503,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setColor(new Color(33,35,30));g.fillRect(0,0,765,503);
        g.setFont(FontManager.getRunescapeSmallFont());g.setColor(Color.LIGHT_GRAY);g.drawString("Native-render fixture · not a logged-in game capture",10,14);
        g.setColor(new Color(64,57,44));g.fill(view.frame);g.setColor(new Color(154,136,99));g.draw(view.frame);
        g.setFont(FontManager.getRunescapeBoldFont());g.setColor(new Color(255,165,40));g.drawString("Grand Exchange",205,46);
        String[] items={"Rune arrows","Zulrah's scales","Coal","Dragon bones","Logs","Air runes","",""};
        for(int i=0;i<8;i++)
        {
            Rectangle b=view.slots.get(i);g.setColor(new Color(76,67,51));g.fill(b);g.setColor(new Color(120,107,80));g.draw(b);
            g.setFont(FontManager.getRunescapeSmallFont());g.setColor(new Color(255,165,40));
            g.drawString(i==0||i==2?"Buy":i<6?"Sell":"Buy / Sell",b.x+6,b.y+15);
            g.setColor(new Color(225,205,110));g.drawString(items[i],b.x+6,b.y+40);
            if(i<6){g.setColor(new Color(34,31,24));g.fillRect(b.x+7,b.y+69,b.width-14,12);g.setColor(i==2||i==5?new Color(70,145,55):new Color(148,99,38));g.fillRect(b.x+7,b.y+69,(b.width-14)*(i==2||i==5?100:40)/100,12);g.setColor(new Color(255,165,40));g.drawString("Native price",b.x+26,b.y+96);}
        }
        Map<Integer,Rectangle> chips=FlipperOverviewOverlay.paint(g,view,765,503);g.dispose();assertEquals(6,chips.size());
        File out=new File("build/reports/flipper-overview");assertTrue(out.isDirectory()||out.mkdirs());ImageIO.write(image,"png",new File(out,"rough-overview-fixed.png"));
        for(Rectangle chip:chips.values())for(Rectangle nativeSlot:view.slots.values())assertFalse(chip.intersects(nativeSlot));
    }
    @Test public void renderBoundsVariantsAndPriorClientContextComposite() throws Exception
    {
        FlipperOverviewOverlay.View fixed=fixture();File out=new File("build/reports/flipper-overview");assertTrue(out.isDirectory()||out.mkdirs());
        for (int variant=0;variant<3;variant++)
        {
            int dx=variant==1?240:0,dy=variant==1?160:0;
            double scale=variant==2?1.5:1.0;int canvasWidth=variant==1?1100:765,canvasHeight=variant==1?800:503;
            Map<Integer,Rectangle> slots=new LinkedHashMap<>();
            for(Map.Entry<Integer,Rectangle> e:fixed.slots.entrySet()){Rectangle b=new Rectangle(e.getValue());b.translate(dx,dy);slots.put(e.getKey(),b);}
            Rectangle frame=new Rectangle(fixed.frame);frame.translate(dx,dy);
            FlipperOverviewOverlay.View view=new FlipperOverviewOverlay.View(fixed.owner,frame,slots,fixed.offers,fixed.now);
            BufferedImage image=new BufferedImage((int)Math.ceil(canvasWidth*scale),(int)Math.ceil(canvasHeight*scale),BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=image.createGraphics();g.scale(scale,scale);g.setColor(new Color(48,44,35));g.fill(frame);
            for(Rectangle b:slots.values()){g.setColor(new Color(128,110,76));g.draw(b);}
            Map<Integer,Rectangle> chips=FlipperOverviewOverlay.paint(g,view,canvasWidth,canvasHeight);g.dispose();assertEquals(6,chips.size());
            for(Rectangle chip:chips.values()){assertTrue(new Rectangle(0,0,canvasWidth,canvasHeight).contains(chip));for(Rectangle nativeSlot:slots.values())assertFalse(chip.intersects(nativeSlot));}
            ImageIO.write(image,"png",new File(out,new String[]{"overview-fixed","overview-resizable","overview-scaled-150"}[variant]+".png"));
        }
        // All eight fields and maximum native quantities exercise fallback labels and exact hover counts.
        FlipperOfferActivity all=new FlipperOfferActivity();GrandExchangeOffer[] empty=new GrandExchangeOffer[8];
        for(int i=0;i<8;i++)empty[i]=FlipperBuyHistoryTest.offer(GrandExchangeOfferState.EMPTY,0,0,0,0,0);all.seed(empty);
        for(int i=0;i<8;i++)all.observe(i,FlipperBuyHistoryTest.offer(i%2==0?GrandExchangeOfferState.CANCELLED_BUY:GrandExchangeOfferState.SELLING,
            100+i,Integer.MAX_VALUE,Integer.MAX_VALUE-1,1,Integer.MAX_VALUE-1L),0);
        FlipperOverviewOverlay.View allView=new FlipperOverviewOverlay.View(fixed.owner,fixed.frame,fixed.slots,all.snapshot(),125000);
        BufferedImage allImage=new BufferedImage(765,503,BufferedImage.TYPE_INT_ARGB);Graphics2D allGraphics=allImage.createGraphics();
        assertEquals(8,FlipperOverviewOverlay.paint(allGraphics,allView,765,503).size());allGraphics.dispose();
        ImageIO.write(allImage,"png",new File(out,"overview-all-eight-long-counts.png"));
        assertTrue(FlipperOverviewOverlay.tooltip(all.snapshot().get(0),125000).contains("2,147,483,646 / 2,147,483,647"));
        String baseline=System.getenv("FLIPPER_OVERVIEW_BASELINE");
        if(baseline!=null)
        {
            BufferedImage original=ImageIO.read(new File(baseline));
            BufferedImage composite=new BufferedImage(original.getWidth(),original.getHeight()+30,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=composite.createGraphics();g.drawImage(original,0,0,null);
            Rectangle frame=new Rectangle(18,20,484,303);Map<Integer,Rectangle> slots=new LinkedHashMap<>();
            for(int i=0;i<8;i++)slots.put(i,new Rectangle(27+(i%4)*117,85+(i/4)*118,115,110));
            FlipperOverviewOverlay.View view=new FlipperOverviewOverlay.View(fixed.owner,frame,slots,fixed.offers,fixed.now);
            FlipperOverviewOverlay.paint(g,view,original.getWidth(),original.getHeight());
            g.setColor(Color.BLACK);g.fillRect(0,original.getHeight(),original.getWidth(),30);g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif",Font.PLAIN,12));g.drawString("COMPOSITE: simulated offer observations and overlay on prior empty-GE client image; not live acceptance.",8,original.getHeight()+19);
            g.dispose();ImageIO.write(composite,"png",new File(out,"overview-prior-client-context-composite.png"));
        }
    }

    @Test public void unsupportedRowsAndAllInvalidSlotsPaintNoPixels()
    {
        FlipperOverviewOverlay.View base=fixture();Map<Integer,Rectangle> threeRows=new LinkedHashMap<>(base.slots);
        threeRows.put(4,new Rectangle(22,250,116,50));
        assertNoPixels(new FlipperOverviewOverlay.View(base.owner,base.frame,threeRows,base.offers,base.now));
        Map<Integer,Rectangle> invalid=new LinkedHashMap<>();
        for(Map.Entry<Integer,Rectangle> entry:base.slots.entrySet())
            invalid.put(entry.getKey(),new Rectangle(-100,entry.getValue().y,70,entry.getValue().height));
        assertNoPixels(new FlipperOverviewOverlay.View(base.owner,base.frame,invalid,base.offers,base.now));
    }

    private static void assertNoPixels(FlipperOverviewOverlay.View view)
    {
        BufferedImage image=new BufferedImage(765,503,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        assertTrue(FlipperOverviewOverlay.paint(g,view,765,503).isEmpty());g.dispose();
        for(int x=0;x<image.getWidth();x++)for(int y=0;y<image.getHeight();y++)assertEquals(0,image.getRGB(x,y));
    }

    @Test public void brandedRailAndLongOwnPriceTooltipFitNativeRenderer() throws Exception
    {
        FlipperOfferActivity tracker=new FlipperOfferActivity();
        tracker.seed(new GrandExchangeOffer[]{FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING,
            100,1,0,1_000_000_000_000L,0)},0);
        String text=FlipperOverviewOverlay.tooltip(tracker.snapshot().get(0),120000);
        assertTrue(text.startsWith("OSRS Best in Slot · Flipper"));
        assertTrue(text.contains("Offer price / item: 1,000,000,000,000 GP"));
        BufferedImage image=new BufferedImage(765,180,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        g.setFont(FontManager.getRunescapeSmallFont());
        assertTrue(g.getFontMetrics().stringWidth(FlipperOverviewOverlay.RAIL_TITLE)<=400-14);
        net.runelite.client.ui.overlay.components.TooltipComponent tooltip=new net.runelite.client.ui.overlay.components.TooltipComponent();
        tooltip.setText(text);tooltip.setBackgroundColor(new Color(26,28,27,245));tooltip.setPreferredLocation(new java.awt.Point(10,10));
        Dimension size=tooltip.render(g);assertTrue(size.width<=745&&size.height<=150);g.dispose();
        File out=new File("build/reports/flipper-overview");assertTrue(out.isDirectory()||out.mkdirs());
        ImageIO.write(image,"png",new File(out,"overview-tooltip-long-price.png"));
    }

}
