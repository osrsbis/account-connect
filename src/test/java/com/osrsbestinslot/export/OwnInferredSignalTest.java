package com.osrsbestinslot.export;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnInferredSignalTest
{
 static final long AT=1792254000000L;
 static Map<String,Map<String,Object>> examples() {
  Map<String,Map<String,Object>> out=new LinkedHashMap<>();
  out.put("session.stationary_ge",Map.of("start_tick",39,"end_tick",41,"monotonic_elapsed_ms",1200,"inference","same_tile_at_sample_times_not_game_aura_or_idle_input"));
  out.put("skills.item_transformation",Map.of("input_item_id",879,"output_item_id",9236,"matched_quantity",10,"magic_xp_change",10,"inference","inventory_xp_coincidence_not_confirmed_cast"));
  out.put("effects.consumption",Map.of("item_id_before",385,"quantity_before",1,"item_id_after",-1,"quantity_after",0,"action","eat","intent_tick",40,"inference","own_menu_and_slot_change_not_confirmed_effect_or_cooldown"));
  return out;
 }
 static Map<String,Object> event(String kind) {
  return OwnSignalEvent.from("12345678-1234-1234-1234-123456789abc",Map.of("kind",kind,"source",OwnInferredSignal.source(kind),
   "sequence",1,"tick",42,"observed_at_ms",AT,"direct",false,"value",examples().get(kind)));
 }
 @Test public void closedInferencesSurviveJsonAndNeverBecomeConfirmedSignalsOrPrivateText() {
  com.google.gson.Gson gson=new com.google.gson.Gson();
  for(String kind:examples().keySet()) {
   Map<String,Object> e=event(kind);assertNotNull(e);e.put("event_id","a".repeat(32));e.put("ts",AT);
   e.put("player_name","PRIVATE");((Map<String,Object>)e.get("signal_value")).put("coordinates","PRIVATE");
   Map<String,Object> wire=PublicTransport.event(e);assertNotNull(wire);assertFalse(gson.toJson(wire).contains("PRIVATE"));
   assertEquals(OwnSignalEvent.wire(wire),OwnSignalEvent.wire(PublicTransport.event(gson.fromJson(gson.toJson(wire),Map.class))));
   assertEquals(false,wire.get("direct"));assertEquals("inferred_observation",wire.get("observation"));
   assertTrue(EventQueueProtocol.allows("own-signals-v1",wire));assertFalse(EventQueueProtocol.allows("own-vars-v1",wire));
  }
 }
 @Test public void inferenceLabelsSourcesPairsAndTimeContradictionsFailClosed() {
  for(String kind:examples().keySet())for(String key:List.of("direct","observation","source")) {
   Map<String,Object> e=event(kind);e.put(key,key.equals("direct")?true:key.equals("source")?"own_client_system":"observed_signal");assertNull(OwnSignalEvent.wire(e));
  }
  Map<String,Object> e=event("session.stationary_ge");e.put("source_tick",45);assertNull(OwnSignalEvent.wire(e));
  e=event("effects.consumption");e.put("source_tick",43);assertNull(OwnSignalEvent.wire(e));
  e=event("skills.item_transformation");((Map<String,Object>)e.get("signal_value")).put("output_item_id",2);assertNull(OwnSignalEvent.wire(e));
  e=event("effects.consumption");((Map<String,Object>)e.get("signal_value")).put("action",null);assertNull(OwnSignalEvent.wire(e));
  e=event("effects.consumption");((Map<String,Object>)e.get("signal_value")).put("inference","confirmed_effect");assertNull(OwnSignalEvent.wire(e));
  for(int[] pair:OwnActivityMappings.BOLTS) {
   Map<String,Object> v=new HashMap<>(examples().get("skills.item_transformation"));v.put("input_item_id",pair[0]);v.put("output_item_id",pair[1]);v.put("matched_quantity",28L*Integer.MAX_VALUE);
   assertNotNull(OwnSignalEvent.value("skills.item_transformation",v));
  }
 }
 @Test public void auditedOwnSourcesHandoffOnlyCompletedSampleEvidenceAndExposeSinkFailures() {
  List<Map<String,Object>> sent=new ArrayList<>();OwnGameplayCapture capture=new OwnGameplayCapture(e->{sent.add(e);return !"effects.consumption".equals(e.get("signal_kind"));});
  Client c=mock(Client.class);Player self=mock(Player.class);ItemContainer inv=mock(ItemContainer.class);int[] tick={100};
  when(c.getGameState()).thenReturn(GameState.LOGGED_IN);when(c.getLocalPlayer()).thenReturn(self);when(c.getAccountHash()).thenReturn(123L);
  when(c.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);when(c.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
  when(c.getTickCount()).thenAnswer(i->tick[0]);when(c.getWorld()).thenReturn(301);when(c.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
  assertFalse(capture.ready(c,"synthetic"));tick[0]+=2;assertTrue(capture.ready(c,"synthetic"));
  when(self.getWorldLocation()).thenReturn(new WorldPoint(3164,3488,0));when(inv.getItems()).thenReturn(new Item[]{new Item(879,20)});
  when(c.getSkillExperience(Skill.MAGIC)).thenReturn(100);capture.sequences.poll(c,AT,1000000000L);
  tick[0]++;capture.sequences.poll(c,AT+600,1600000000L);assertTrue(sent.isEmpty());
  tick[0]++;when(self.getWorldLocation()).thenReturn(new WorldPoint(3165,3488,0));when(inv.getItems()).thenReturn(new Item[]{new Item(879,10),new Item(9236,10)});
  when(c.getSkillExperience(Skill.MAGIC)).thenReturn(110);capture.sequences.poll(c,AT+1200,2200000000L);assertEquals(2,sent.size());
  when(inv.getItems()).thenReturn(new Item[]{new Item(385,1)});MenuOptionClicked menu=mock(MenuOptionClicked.class);
  when(menu.getMenuOption()).thenReturn("Eat");when(menu.getParam1()).thenReturn(149<<16);when(menu.getParam0()).thenReturn(0);when(menu.getItemId()).thenReturn(385);
  capture.consumption.menu(c,menu);when(inv.getItems()).thenReturn(new Item[0]);tick[0]++;capture.consumption.poll(c,AT+1800);capture.consumption.poll(c,AT+1801);
  assertEquals(3,sent.size());for(Map<String,Object> e:sent)assertNotNull(OwnSignalEvent.wire(e));
  Map<?,?> health=capture.snapshot();assertEquals(3L,health.get("signal_events_observed"));assertEquals(2L,health.get("signal_events_handed_off"));assertEquals(1L,health.get("signal_events_not_handed_off"));
  verify(c,never()).getPlayers();verify(menu,never()).getMenuTarget();
 }
}
