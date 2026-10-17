package com.osrsbestinslot.export;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnCombatSignalTest {
 static final long AT=1792254000000L;
 static Map<String,Map<String,Object>> examples() {
  Map<String,Map<String,Object>> out=new LinkedHashMap<>();
  Map<String,Object> death=new LinkedHashMap<>();death.put("party_status_raw",2);death.put("room_observed",null);death.put("actor","self");death.put("classification","observed_own_death_not_team_wipe");
  out.put("combat.tob_room_death",death);out.put("combat.toa_hazards",Map.of("object_id",45570,"spawn_tick",41,"classification","own_tile_overlap_not_proven_damage_or_mistake"));return out;
 }
 static Map<String,Object> event(String kind) {
  return OwnSignalEvent.from("12345678-1234-1234-1234-123456789abc",Map.of("kind",kind,"source",kind.equals("combat.tob_room_death")?"ActorDeath:self":"own_tile+observed_acid_object",
   "sequence",1,"tick",42,"observed_at_ms",AT,"direct",true,"value",examples().get(kind)));
 }
 @Test public void onlyClosedOwnActorAndSceneEvidenceCanEnterPublicWire() {
  com.google.gson.Gson gson=new com.google.gson.Gson();
  for(String kind:examples().keySet()) {
   Map<String,Object> e=event(kind);assertNotNull(e);e.put("event_id","a".repeat(32));e.put("ts",AT);
   ((Map<String,Object>)e.get("signal_value")).put("player_name","PRIVATE");e.put("location","PRIVATE");
   Map<String,Object> wire=PublicTransport.event(e);assertNotNull(wire);assertFalse(gson.toJson(wire).contains("PRIVATE"));
   assertEquals(OwnSignalEvent.wire(wire),OwnSignalEvent.wire(PublicTransport.event(gson.fromJson(gson.toJson(wire),Map.class))));
   assertTrue(EventQueueProtocol.allows("own-signals-v1",wire));assertFalse(EventQueueProtocol.allows("own-vars-v1",wire));
   e.put("source","own_client_system");assertNull(OwnSignalEvent.wire(e));
  }
  Map<String,Object> e=event("combat.tob_room_death");((Map<String,Object>)e.get("signal_value")).put("room_observed","PRIVATE");assertNull(OwnSignalEvent.wire(e));
  e=event("combat.tob_room_death");((Map<String,Object>)e.get("signal_value")).put("party_status_raw",1);assertNull(OwnSignalEvent.wire(e));
  e=event("combat.tob_room_death");((Map<String,Object>)e.get("signal_value")).put("actor","other");assertNull(OwnSignalEvent.wire(e));
  e=event("combat.toa_hazards");e.put("source_tick",41);assertNull(OwnSignalEvent.wire(e));
  e=event("combat.toa_hazards");((Map<String,Object>)e.get("signal_value")).put("classification","confirmed_damage");assertNull(OwnSignalEvent.wire(e));
  e=OwnSignalEventTest.event("storage.coal_bag");e.put("source","own_client_actor");assertNull(OwnSignalEvent.wire(e));
 }
 @Test public void actualOwnCallbacksHandoffOnceWithoutReadingAnotherActorOrInventingDamage() {
  List<Map<String,Object>> sent=new ArrayList<>();OwnGameplayCapture capture=new OwnGameplayCapture(e->{if("own_signal".equals(e.get("type")))sent.add(e);return true;});
  Client c=mock(Client.class);Player self=mock(Player.class),other=mock(Player.class);int[] tick={100},vars=new int[6000];
  when(c.getGameState()).thenReturn(GameState.LOGGED_IN);when(c.getLocalPlayer()).thenReturn(self);when(c.getAccountHash()).thenReturn(123L);
  when(c.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);when(c.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));when(c.getWorld()).thenReturn(301);
  when(c.getTickCount()).thenAnswer(i->tick[0]);when(c.getVarps()).thenReturn(vars);VarbitComposition bit=mock(VarbitComposition.class);
  when(bit.getIndex()).thenReturn(100);when(bit.getMostSignificantBit()).thenReturn(15);when(c.getVarbit(6440)).thenReturn(bit);
  assertFalse(capture.ready(c,"synthetic"));tick[0]+=2;assertTrue(capture.ready(c,"synthetic"));
  capture.activities.death(c,other,AT);verifyNoInteractions(other);assertTrue(sent.isEmpty());
  vars[100]=1;capture.activities.death(c,self,AT);assertTrue(sent.isEmpty());
  vars[100]=2;tick[0]++;capture.activities.death(c,self,AT+600);capture.activities.death(c,self,AT+601);assertEquals(1,sent.size());
  assertNull(((Map<?,?>)sent.get(0).get("signal_value")).get("room_observed"));
  GameObject acid=mock(GameObject.class);when(acid.getId()).thenReturn(45570);when(acid.getWorldLocation()).thenReturn(new WorldPoint(100,100,0));
  when(self.getWorldLocation()).thenReturn(new WorldPoint(100,100,0));capture.scene.spawned(c,acid,AT+602);capture.scene.poll(c,AT+603);assertEquals(1,sent.size());
  tick[0]++;capture.scene.poll(c,AT+1200);capture.scene.poll(c,AT+1201);assertEquals(2,sent.size());
  capture.scene.despawned(acid);tick[0]++;capture.scene.poll(c,AT+1800);assertEquals(2,sent.size());
  assertEquals(2L,capture.snapshot().get("signal_events_handed_off"));for(Map<String,Object> e:sent)assertNotNull(OwnSignalEvent.wire(e));
  verify(c,never()).getPlayers();verify(self,never()).getName();
 }
}
