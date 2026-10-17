package com.osrsbestinslot.export;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class OwnClueEventTest {
 static final long AT=1792254000000L;
 static final List<String> FAMILIES=List.of("clues.owned_ground","clues.ground_gap","world.scene_gap","clues.destination");
 static Map<String,Object> inventory() {return Map.of("inventory_map_coordinate_clues",Map.of("12179",Map.of("kind","map","x",3300,"y",3291,"plane",0,"qualification","library_base_location_not_resolved_quest_alternative")),"observation","baseline");}
 static Map<String,Object> largestInventory() {
  try {Map<String,Object> clues=new LinkedHashMap<>();
   for(Map.Entry<String,List<Object>> entry:OwnClueTableTest.table().entrySet()) {List<Object> a=entry.getValue();clues.put(entry.getKey(),Map.of("kind",a.get(0),"x",a.get(1),"y",a.get(2),"plane",a.get(3),"qualification","library_base_location_not_resolved_quest_alternative"));if(clues.size()==28)break;}
   return Map.of("inventory_map_coordinate_clues",clues,"observation","baseline");
  }catch(Exception failure){throw new IllegalStateException(failure);}
 }
 static Map<String,Object> ground() {
  Map<String,Object> v=new LinkedHashMap<>();v.put("observation_id","11111111-2222-3333-4444-555555555555");v.put("item_id",23182);v.put("quantity",1);
  v.put("ownership","self_at_observation");v.put("drop_origin","unverified");v.put("despawn_server_tick",null);v.put("clock_scope","current_client_session_only");v.put("observation","appeared");return v;
 }
 static Map<String,Object> event(String kind) {
  Object value=kind.equals("clues.destination")?largestInventory():kind.equals("clues.owned_ground")?ground():Map.of("gap",kind.equals("clues.ground_gap")?"observation_capacity":"object_capacity");
  String source=kind.equals("clues.destination")?"own_inventory+RuneLite1.13.1_map_coordinate_tables":kind.equals("clues.owned_ground")?"RuneLite1.13.1:TileItem_ownership_and_server_ticks":kind.equals("clues.ground_gap")?"bounded_self_ground_clue_capacity":"bounded object observation capacity";
  Map<String,Object> row=Map.of("kind",kind,"source",source,"sequence",1,"tick",42,"observed_at_ms",AT,"direct",true,"value",value);
  return kind.equals("clues.destination")?OwnStateEvent.from("12345678-1234-1234-1234-123456789abc",row):OwnSignalEvent.from("12345678-1234-1234-1234-123456789abc",row);
 }
 @Test public void fixedClueProjectionsPreserveUnknownsAndRejectPrivateOrInventedMeaning() {
  com.google.gson.Gson gson=new com.google.gson.Gson();
  for(String family:FAMILIES) {
   Map<String,Object> e=event(family);assertNotNull(e);e.put("event_id","a".repeat(32));e.put("ts",AT);e.put("player_name","PRIVATE");
   Map<String,Object> wire=PublicTransport.event(e);assertNotNull(wire);assertFalse(gson.toJson(wire).contains("PRIVATE"));
   if(family.equals("clues.destination")) {assertEquals(28,((Map<?,?>)((Map<?,?>)wire.get("state_value")).get("inventory_map_coordinate_clues")).size());int bytes=gson.toJson(wire).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;assertTrue(bytes<8192);System.out.println("28-clue projected payload bytes="+bytes);}
   assertNotNull(PublicTransport.event(gson.fromJson(gson.toJson(wire),Map.class)));assertTrue(EventQueueProtocol.allows("own-signals-v1",wire));
  }
  Map<String,Object> unknown=new HashMap<>();unknown.put("inventory_map_coordinate_clues",null);
  assertNull(OwnStateEvent.projectValue("clue_inventory",unknown).get("inventory_map_coordinate_clues"));
  assertEquals(Map.of(),OwnStateEvent.projectValue("clue_inventory",Map.of("inventory_map_coordinate_clues",Map.of())).get("inventory_map_coordinate_clues"));
  Map<String,Object> bad=new HashMap<>((Map<String,Object>)((Map<?,?>)inventory().get("inventory_map_coordinate_clues")).get("12179"));bad.put("x",999);
  assertNull(OwnStateEvent.projectValue("clue_inventory",Map.of("inventory_map_coordinate_clues",Map.of("12179",bad))));
  Map<String,Object> v=ground();v.put("ownership","other");assertNull(OwnSignalEvent.value("clues.owned_ground",v));
  v=ground();v.put("observation","left_scene");v.put("outcome","picked_up");assertNull(OwnSignalEvent.value("clues.owned_ground",v));
  v=ground();v.put("item_id",2);assertNull(OwnSignalEvent.value("clues.owned_ground",v));
  v=ground();v.put("observation","left_scene");v.put("outcome","taken_expired_or_scene_change_unknown");assertNotNull(OwnSignalEvent.value("clues.owned_ground",v));
  assertNull(OwnSignalEvent.value("clues.ground_gap",Map.of("gap","PRIVATE")));
 }
 @Test public void actualOwnInventoryAndGroundCallbacksKeepOwnershipGapsAndRemovalCauseVisible() {
  List<Map<String,Object>> sent=new ArrayList<>();OwnGameplayCapture capture=new OwnGameplayCapture(e->{if("clue_inventory".equals(e.get("state_kind"))||OwnClueProjection.supports(e.get("signal_kind")))sent.add(e);return true;});
  Client c=mock(Client.class);Player self=mock(Player.class);int[] tick={100};
  when(c.getGameState()).thenReturn(GameState.LOGGED_IN);when(c.getLocalPlayer()).thenReturn(self);when(c.getAccountHash()).thenReturn(123L);
  when(c.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);when(c.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));when(c.getWorld()).thenReturn(301);when(c.getTickCount()).thenAnswer(i->tick[0]);
  assertFalse(capture.ready(c,"synthetic"));tick[0]+=2;assertTrue(capture.ready(c,"synthetic"));
  capture.interfaces.poll(c,AT);assertEquals(1,sent.size());assertNull(((Map<?,?>)sent.get(0).get("state_value")).get("inventory_map_coordinate_clues"));
  ItemContainer inv=mock(ItemContainer.class);when(c.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);when(inv.getItems()).thenReturn(new Item[0]);capture.interfaces.poll(c,AT+1);
  when(inv.getItems()).thenReturn(new Item[]{new Item(12179,1)});capture.interfaces.poll(c,AT+2);capture.interfaces.poll(c,AT+3);assertEquals(3,sent.size());
  for(int owner:new int[]{TileItem.OWNERSHIP_NONE,TileItem.OWNERSHIP_OTHER,TileItem.OWNERSHIP_GROUP,99}) {TileItem other=mock(TileItem.class);when(other.getOwnership()).thenReturn(owner);capture.groundClues.spawned(c,other,AT);verify(other,never()).getId();verify(other,never()).getDespawnTime();}
  TileItem own=mock(TileItem.class);when(own.getOwnership()).thenReturn(TileItem.OWNERSHIP_SELF);when(own.getId()).thenReturn(23182);when(own.getQuantity()).thenReturn(1);
  capture.groundClues.spawned(c,own,AT);capture.groundClues.spawned(c,own,AT);capture.groundClues.despawned(c,own,AT+1);assertEquals(5,sent.size());
  Map<?,?> first=(Map<?,?>)sent.get(3).get("signal_value"),last=(Map<?,?>)sent.get(4).get("signal_value");assertEquals(first.get("observation_id"),last.get("observation_id"));assertNull(first.get("despawn_server_tick"));assertEquals("taken_expired_or_scene_change_unknown",last.get("outcome"));
  for(int i=0;i<65;i++){TileItem item=mock(TileItem.class);when(item.getOwnership()).thenReturn(TileItem.OWNERSHIP_SELF);when(item.getId()).thenReturn(23182);when(item.getQuantity()).thenReturn(1);capture.groundClues.spawned(c,item,AT+2+i);}
  assertEquals(1,sent.stream().filter(e->"clues.ground_gap".equals(e.get("signal_kind"))).count());
  for(int i=0;i<257;i++){GameObject object=mock(GameObject.class);when(object.getId()).thenReturn(45570);when(object.getWorldLocation()).thenReturn(new WorldPoint(100,100,0));capture.scene.spawned(c,object,AT+i);}
  assertEquals(1,sent.stream().filter(e->"world.scene_gap".equals(e.get("signal_kind"))).count());
  for(Map<String,Object> e:sent)assertNotNull("own_state".equals(e.get("type"))?OwnStateEvent.wire(e):OwnSignalEvent.wire(e));
  verify(c,never()).getPlayers();verify(self,never()).getName();
 }
}
