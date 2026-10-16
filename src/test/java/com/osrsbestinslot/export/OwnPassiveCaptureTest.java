package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnPassiveCaptureTest
{
    Client c; Player self; OwnGameplayCapture capture; int tick; long nanos; ItemContainer inv;
    @Before public void setup()
    {
        c=mock(Client.class); self=mock(Player.class); capture=new OwnGameplayCapture(); tick=100; nanos=1_000_000_000L;
        when(c.getLocalPlayer()).thenReturn(self); when(c.getTickCount()).thenAnswer(i->tick); when(c.getWorld()).thenReturn(301);
        inv=mock(ItemContainer.class); when(c.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
        when(inv.getItems()).thenReturn(new Item[0]);
    }
    List<Map<String,Object>> rows() { return (List<Map<String,Object>>)capture.snapshot().get("observations"); }
    Map<?,?> state(String name) { return (Map<?,?>)((Map<?,?>)((Map<?,?>)capture.snapshot().get("states")).get(name)).get("value"); }
    Widget widget(String text)
    {
        Widget w=mock(Widget.class); when(w.getText()).thenReturn(text); when(w.getType()).thenReturn(WidgetType.TEXT); return w;
    }
    void seq() { capture.sequences.poll(c,1000,nanos); tick++; nanos+=600_000_000L; }
    MenuOptionClicked action(int slot,int item,String action)
    {
        MenuOptionClicked e=mock(MenuOptionClicked.class); when(e.getParam0()).thenReturn(slot); when(e.getParam1()).thenReturn(149<<16);
        when(e.getItemId()).thenReturn(item); when(e.getMenuOption()).thenReturn(action); return e;
    }
    @Test public void geTimingExcludesEnableHistoryMovementAndClockJumps()
    {
        when(self.getWorldLocation()).thenReturn(new WorldPoint(3164,3488,0)); seq(); seq(); seq(); assertTrue(rows().isEmpty());
        when(self.getWorldLocation()).thenReturn(new WorldPoint(3165,3488,0)); seq();
        assertEquals(1,rows().size()); assertEquals(false,rows().get(0).get("direct"));
        assertEquals(1200L,((Map<?,?>)rows().get(0).get("value")).get("monotonic_elapsed_ms"));
        nanos+=60_000_000_000L; seq(); // long blocked client interval cannot earn observed time
        when(c.getWorld()).thenReturn(302); seq();
        when(self.getWorldLocation()).thenReturn(new WorldPoint(3100,3400,0)); seq(); assertEquals(1,rows().size());
    }
    @Test public void geIdleBeyondTwoHundredTicksDoesNotRollOverOrChangeUploadHash() throws Exception
    {
        when(self.getWorldLocation()).thenReturn(new WorldPoint(3164,3488,0)); seq(); seq(); seq();
        when(self.getWorldLocation()).thenReturn(new WorldPoint(3165,3488,0)); seq();
        assertEquals(1,rows().size());
        java.lang.reflect.Method hash=AccountConnectPlugin.class.getDeclaredMethod("canonicalHash",Map.class);
        hash.setAccessible(true); AccountConnectPlugin plugin=new AccountConnectPlugin();
        java.lang.reflect.Field gson=AccountConnectPlugin.class.getDeclaredField("gson");
        gson.setAccessible(true); gson.set(plugin,new com.google.gson.Gson());
        Map<String,Object> snapshot=new LinkedHashMap<>(); snapshot.put("own_gameplay",capture.snapshot());
        Object before=hash.invoke(plugin,snapshot);
        for(int i=0;i<250;i++) seq();
        snapshot.put("own_gameplay",capture.snapshot());
        assertEquals(before,hash.invoke(plugin,snapshot)); assertEquals(1,rows().size());
        when(self.getWorldLocation()).thenReturn(new WorldPoint(3166,3488,0)); seq();
        assertEquals(2,rows().size());
        assertEquals(150000L,((Map<?,?>)rows().get(1).get("value")).get("monotonic_elapsed_ms"));
    }
    @Test public void geGapOrWorldChangeCannotCarryAnInterval()
    {
        when(self.getWorldLocation()).thenReturn(new WorldPoint(3164,3488,0)); seq(); seq(); capture.gap();
        when(self.getWorldLocation()).thenReturn(new WorldPoint(3165,3488,0)); seq(); assertTrue(rows().isEmpty());
        assertFalse(OwnSequenceCapture.courtyard(new WorldPoint(3155,3480,0))); assertFalse(OwnSequenceCapture.courtyard(new WorldPoint(3164,3488,1)));
    }
    @Test public void boltPairNeedsLossGainXpAndConsecutiveSampleAndIsNeverAConfirmedCast()
    {
        int[] pair=OwnActivityMappings.BOLTS[0]; when(inv.getItems()).thenReturn(new Item[]{new Item(pair[0],20)});
        when(c.getSkillExperience(Skill.MAGIC)).thenReturn(100); seq();
        when(inv.getItems()).thenReturn(new Item[]{new Item(pair[0],10),new Item(pair[1],10)});
        when(c.getSkillExperience(Skill.MAGIC)).thenReturn(110); seq(); seq();
        assertEquals(1,rows().size()); assertEquals(false,rows().get(0).get("direct"));
        assertEquals(10L,((Map<?,?>)rows().get(0).get("value")).get("matched_quantity"));
        when(inv.getItems()).thenReturn(new Item[]{new Item(pair[1],100)}); seq(); assertEquals(1,rows().size());
        capture.gap(); when(c.getSkillExperience(Skill.MAGIC)).thenReturn(999); seq(); assertEquals(1,rows().size());
    }
    @Test public void missingInventoryOrTickGapIsRebaselineNotTransformation()
    {
        int[] pair=OwnActivityMappings.BOLTS[0]; when(inv.getItems()).thenReturn(new Item[]{new Item(pair[0],20)}); seq();
        when(c.getItemContainer(InventoryID.INVENTORY)).thenReturn(null); seq();
        when(c.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv); when(inv.getItems()).thenReturn(new Item[]{new Item(pair[1],20)});
        when(c.getSkillExperience(Skill.MAGIC)).thenReturn(100); seq(); assertTrue(rows().isEmpty());
    }
    @Test public void mixologyPreservesRepeatedOrdersAndRejectsUnknownFourthOrSubstringText()
    {
        Widget parent=widget(""); when(c.getWidget(882,2)).thenReturn(parent);
        doReturn(new Widget[]{widget("Mixalot"),widget("Mixalot"),widget("Liplack liquor")}).when(parent).getChildren();
        capture.interfaces.poll(c,1000); assertEquals(Arrays.asList("MAL","MAL","LLL"),state("minigame.mixology_orders").get("orders_observed"));
        doReturn(new Widget[]{widget("Mixalot hacked"),widget("Mixalot"),widget("Liplack liquor")}).when(parent).getChildren();
        capture.interfaces.poll(c,1001); assertNull(state("minigame.mixology_orders").get("orders_observed"));
        doReturn(new Widget[]{widget("Mixalot"),widget("Mixalot"),widget("Mixalot"),widget("Mixalot")}).when(parent).getChildren();
        capture.interfaces.poll(c,1002); assertNull(state("minigame.mixology_orders").get("orders_observed"));
    }
    @Test public void fairyFiltersAndInjectedRowsCannotBecomeUnlockedDestinations()
    {
        Widget good=widget("<col=ff0000>ALR</col>"), bad=widget("Other Player");
        when(c.getWidget(net.runelite.api.gameval.InterfaceID.FairyringsLog.FAVE_CODE_1)).thenReturn(good);
        when(c.getWidget(net.runelite.api.gameval.InterfaceID.FairyringsLog.FAVE_CODE_2)).thenReturn(bad);
        capture.interfaces.poll(c,1000); List<?> codes=(List<?>)state("travel.fairy_destinations").get("favourite_codes_visible");
        assertEquals("ALR",codes.get(0)); assertNull(codes.get(1)); when(good.isHidden()).thenReturn(true);
        capture.interfaces.poll(c,1001); assertNull(((List<?>)state("travel.fairy_destinations").get("favourite_codes_visible")).get(0));
        assertFalse(new com.google.gson.Gson().toJson(capture.snapshot()).contains("Other Player"));
    }
    @Test public void ephemeralStorageWidgetNeedsOwnPossessionAndDedupesUntilClosed()
    {
        Widget w=widget("The barrel contains: 2 x Raw salmon, 3 x Raw tuna"); when(c.getWidget(193,2)).thenReturn(w);
        capture.interfaces.clientTick(c,1000); assertTrue(rows().isEmpty());
        when(inv.getItems()).thenReturn(new Item[]{new Item(25582,1)});
        capture.interfaces.clientTick(c,1001); capture.interfaces.clientTick(c,1002); assertEquals(1,rows().size());
        assertEquals(5L,((Map<?,?>)rows().get(0).get("value")).get("count_observed"));
        when(w.isHidden()).thenReturn(true); capture.interfaces.clientTick(c,1003);
        when(w.isHidden()).thenReturn(false); capture.interfaces.clientTick(c,1004); assertEquals(2,rows().size());
        when(w.getText()).thenReturn("The barrel contains: 99 x Raw tuna"); capture.interfaces.clientTick(c,1005); assertEquals(2,rows().size());
    }
    @Test public void coalCheckCanOpenAndCloseWithinOneGameTickAndCannotInferCapeCapacity()
    {
        Widget w=widget("The coal bag contains 54 pieces of coal."); when(c.getWidget(193,2)).thenReturn(w);
        when(inv.getItems()).thenReturn(new Item[]{new Item(12019,1)}); capture.interfaces.clientTick(c,1000);
        when(w.isHidden()).thenReturn(true); capture.interfaces.clientTick(c,1001);
        assertEquals(54L,((Map<?,?>)rows().get(0).get("value")).get("count_observed"));
    }
    @Test public void spiritMenuRequiresExactTitleAndSuppressesUnknownNamesAndHouseSuffix()
    {
        Widget parent=widget(""),title=widget("Spirit Tree Locations"),list=widget("");
        when(c.getWidget(187,0)).thenReturn(parent); when(parent.getChild(1)).thenReturn(title); when(c.getWidget(187,3)).thenReturn(list);
        doReturn(new Widget[]{widget("1: Your house (Rimmington)"),widget("2: OtherPlayer"),widget("3: <col=5f5f5f>Prifddinas")}).when(list).getDynamicChildren();
        capture.interfaces.poll(c,1000); List<?> result=(List<?>)state("travel.spirit_destinations").get("visible_destinations");
        assertEquals(2,result.size()); assertEquals("Your house",((Map<?,?>)result.get(0)).get("destination"));
        assertEquals(true,((Map<?,?>)result.get(1)).get("disabled_style_observed"));
        when(title.getText()).thenReturn("Other menu"); capture.interfaces.poll(c,1001); assertNull(state("travel.spirit_destinations").get("visible_destinations"));
    }
    @Test public void clueTableReadsRequireCurrentInventoryAndNeverInspectAnotherPluginProfile()
    {
        when(c.getItemContainer(InventoryID.INVENTORY)).thenReturn(null); capture.interfaces.poll(c,1000);
        assertNull(state("clues.destination").get("inventory_map_coordinate_clues"));
        when(c.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv); when(inv.getItems()).thenReturn(new Item[]{new Item(2,1)});
        capture.interfaces.poll(c,1001); assertEquals(Collections.emptyMap(),state("clues.destination").get("inventory_map_coordinate_clues"));
        when(inv.getItems()).thenReturn(new Item[]{new Item(12179,1)}); capture.interfaces.poll(c,1002);
        Map<?,?> mapped=(Map<?,?>)((Map<?,?>)state("clues.destination").get("inventory_map_coordinate_clues")).get("12179");
        assertEquals(3300,mapped.get("x")); assertEquals(3291,mapped.get("y"));
        when(inv.getItems()).thenReturn(new Item[0]); capture.interfaces.poll(c,1003);
        assertEquals(Collections.emptyMap(),state("clues.destination").get("inventory_map_coordinate_clues"));
        assertEquals(false,state("clues.destination").get("complete"));
    }
    GameObject object(int id,int x,int y)
    {
        GameObject o=mock(GameObject.class); when(o.getId()).thenReturn(id); when(o.getWorldLocation()).thenReturn(new WorldPoint(x,y,0)); return o;
    }
    @Test public void acidOverlapIsDelayedOwnOnlyAndDespawnIsNotDamage()
    {
        GameObject acid=object(45570,100,100); when(self.getWorldLocation()).thenReturn(new WorldPoint(100,100,0));
        capture.scene.spawned(c,acid,1000); capture.scene.poll(c,1000); assertTrue(rows().isEmpty());
        tick++; capture.scene.poll(c,1001); capture.scene.poll(c,1002); assertEquals(1,rows().size());
        assertEquals("own_tile_overlap_not_proven_damage_or_mistake",((Map<?,?>)rows().get(0).get("value")).get("classification"));
        capture.scene.despawned(acid); tick++; capture.scene.poll(c,1003); assertEquals(1,rows().size()); verify(c,never()).getPlayers();
    }
    @Test public void sceneHopGapWipeAndPreReadDespawnCannotSubmitStaleObjects()
    {
        GameObject circle=object(24988,100,100); capture.scene.spawned(c,circle,1000); when(c.getWorld()).thenReturn(302); tick++;
        capture.scene.poll(c,1001); assertTrue(rows().isEmpty());
        capture.scene.spawned(c,circle,1002); capture.scene.despawned(circle); tick++; capture.scene.poll(c,1003); assertTrue(rows().isEmpty());
        capture.scene.spawned(c,circle,1004); capture.gap(); tick++; capture.scene.poll(c,1005); assertTrue(rows().isEmpty());
        capture.scene.spawned(c,circle,1006); ChatMessage m=new ChatMessage(); m.setType(ChatMessageType.GAMEMESSAGE);m.setMessage("Your party failed to complete the challenge.");
        capture.activities.chat(c,m,1007);tick++;capture.scene.poll(c,1008);assertTrue(rows().isEmpty());
        capture.scene.spawned(c,circle,1009);tick++;capture.scene.poll(c,1010);assertEquals(1,rows().size());
    }
    @Test public void failedConsumptionClickOrObjectActionCannotReportAConsumption()
    {
        when(inv.getItems()).thenReturn(new Item[]{new Item(385,1)}); capture.consumption.menu(c,action(0,385,"Eat"));
        tick+=3;capture.consumption.poll(c,1000);assertTrue(rows().isEmpty());
        MenuOptionClicked object=action(0,385,"Drink");when(object.getParam1()).thenReturn(0);capture.consumption.menu(c,object);
        when(inv.getItems()).thenReturn(new Item[0]);capture.consumption.poll(c,1001);assertTrue(rows().isEmpty());
    }
    @Test public void comboActionsProduceSeparateQualifiedSlotChangesWithoutCooldownClaims()
    {
        when(inv.getItems()).thenReturn(new Item[]{new Item(385,1),new Item(229,1)});
        capture.consumption.menu(c,action(0,385,"Eat"));capture.consumption.menu(c,action(1,229,"Drink"));
        when(inv.getItems()).thenReturn(new Item[0]);tick++;capture.consumption.poll(c,1000);capture.consumption.poll(c,1001);
        assertEquals(2,rows().size());for(Map<String,Object> r:rows()) { assertEquals(false,r.get("direct"));assertFalse(((Map<?,?>)r.get("value")).containsKey("cooldown")); }
    }
}
