package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OwnActivityCaptureTest
{
    // The pinned review lists (API 1.13.1). OwnActivityMappings writes them as the ranges they equal.
    /** The reviewed named list the shipped OwnPotionStorageCapture.VARPS runs must equal (RuneLite gameval). */
    private static final int[] NAMED_POTION_VARPS = {
        VarPlayerID.POTIONSTORE_VIALS, VarPlayerID.POTIONSTORE_AGILITY_POTION, VarPlayerID.POTIONSTORE_ANCIENT_BREW,
        VarPlayerID.POTIONSTORE_ANTI_VENOM, VarPlayerID.POTIONSTORE_ANTI_VENOM_P, VarPlayerID.POTIONSTORE_ANTIDOTE_P,
        VarPlayerID.POTIONSTORE_ANTIDOTE_PP, VarPlayerID.POTIONSTORE_ANTIFIRE_POTION,
        VarPlayerID.POTIONSTORE_ANTIPOISON, VarPlayerID.POTIONSTORE_ATTACK_POTION,
        VarPlayerID.POTIONSTORE_BASTION_POTION, VarPlayerID.POTIONSTORE_BATTLEMAGE_POTION,
        VarPlayerID.POTIONSTORE_COMBAT_POTION, VarPlayerID.POTIONSTORE_COMPOST_POTION,
        VarPlayerID.POTIONSTORE_DEFENCE_POTION, VarPlayerID.POTIONSTORE_DIVINE_BASTION_POTION,
        VarPlayerID.POTIONSTORE_DIVINE_BATTLEMAGE_POTION, VarPlayerID.POTIONSTORE_DIVINE_MAGIC_POTION,
        VarPlayerID.POTIONSTORE_DIVINE_RANGING_POTION, VarPlayerID.POTIONSTORE_DIVINE_SUPER_ATTACK_POTION,
        VarPlayerID.POTIONSTORE_DIVINE_SUPER_COMBAT_POTION, VarPlayerID.POTIONSTORE_DIVINE_SUPER_DEFENCE_POTION,
        VarPlayerID.POTIONSTORE_DIVINE_SUPER_STRENGTH_POTION, VarPlayerID.POTIONSTORE_ENERGY_POTION,
        VarPlayerID.POTIONSTORE_EXTENDED_ANTIFIRE_POTION, VarPlayerID.POTIONSTORE_EXTENDED_SUPER_ANTIFIRE_POTION,
        VarPlayerID.POTIONSTORE_FISHING_POTION, VarPlayerID.POTIONSTORE_FORGOTTENBREW_POTION,
        VarPlayerID.POTIONSTORE_GOADING_POTION, VarPlayerID.POTIONSTORE_GUTHIX_BALANCE_POTION,
        VarPlayerID.POTIONSTORE_HUNTER_POTION, VarPlayerID.POTIONSTORE_MAGICESSENCE_POTION,
        VarPlayerID.POTIONSTORE_MAGIC_POTION, VarPlayerID.POTIONSTORE_MENAPHITEREMEDY_POTION,
        VarPlayerID.POTIONSTORE_PRAYER_POTION, VarPlayerID.POTIONSTORE_RANGING_POTION,
        VarPlayerID.POTIONSTORE_RELICYMSBALM_POTION, VarPlayerID.POTIONSTORE_RESTORE_POTION,
        VarPlayerID.POTIONSTORE_SANFEWSERUM_POTION, VarPlayerID.POTIONSTORE_SARADOMINBREW_POTION,
        VarPlayerID.POTIONSTORE_SERUM207_POTION, VarPlayerID.POTIONSTORE_STAMINA_POTION,
        VarPlayerID.POTIONSTORE_STRENGTH_POTION, VarPlayerID.POTIONSTORE_SUPER_ANTIFIRE_POTION,
        VarPlayerID.POTIONSTORE_SUPER_ATTACK_POTION, VarPlayerID.POTIONSTORE_SUPER_COMBAT_POTION,
        VarPlayerID.POTIONSTORE_SUPER_DEFENCE_POTION, VarPlayerID.POTIONSTORE_SUPER_ENERGY_POTION,
        VarPlayerID.POTIONSTORE_SUPER_RESTORE_POTION, VarPlayerID.POTIONSTORE_SUPER_STRENGTH_POTION,
        VarPlayerID.POTIONSTORE_SUPER_ANTIPOISON_POTION, VarPlayerID.POTIONSTORE_WEAPON_POISON_POTION,
        VarPlayerID.POTIONSTORE_WEAPON_POISON_P_POTION, VarPlayerID.POTIONSTORE_WEAPON_POISON_PP_POTION,
        VarPlayerID.POTIONSTORE_ZAMORAK_BREW_POTION, VarPlayerID.POTIONSTORE_PRAYER_REGENERATION_POTION,
        VarPlayerID.POTIONSTORE_ATTACK_MIX, VarPlayerID.POTIONSTORE_ANTIPOISON_MIX,
        VarPlayerID.POTIONSTORE_RELICYMS_MIX, VarPlayerID.POTIONSTORE_STRENGTH_MIX,
        VarPlayerID.POTIONSTORE_RESTORE_MIX, VarPlayerID.POTIONSTORE_ENERGY_MIX, VarPlayerID.POTIONSTORE_DEFENCE_MIX,
        VarPlayerID.POTIONSTORE_AGILITY_MIX, VarPlayerID.POTIONSTORE_COMBAT_MIX, VarPlayerID.POTIONSTORE_PRAYER_MIX,
        VarPlayerID.POTIONSTORE_SUPERATTACK_MIX, VarPlayerID.POTIONSTORE_ANTIPOISON_SUPER_MIX,
        VarPlayerID.POTIONSTORE_FISHING_MIX, VarPlayerID.POTIONSTORE_SUPER_ENERGY_MIX,
        VarPlayerID.POTIONSTORE_HUNTING_MIX, VarPlayerID.POTIONSTORE_SUPER_STRENGTH_MIX,
        VarPlayerID.POTIONSTORE_MAGIC_ESSENCE_MIX, VarPlayerID.POTIONSTORE_SUPER_RESTORE_MIX,
        VarPlayerID.POTIONSTORE_SUPER_DEFENCE_MIX, VarPlayerID.POTIONSTORE_ANTIDOTE_P_MIX,
        VarPlayerID.POTIONSTORE_ANTIFIRE_MIX, VarPlayerID.POTIONSTORE_RANGING_MIX, VarPlayerID.POTIONSTORE_MAGIC_MIX,
        VarPlayerID.POTIONSTORE_ZAMORAK_MIX, VarPlayerID.POTIONSTORE_STAMINA_MIX,
        VarPlayerID.POTIONSTORE_EXTENDED_ANTIFIRE_MIX, VarPlayerID.POTIONSTORE_ANCIENT_MIX,
        VarPlayerID.POTIONSTORE_SUPER_ANTIFIRE_MIX, VarPlayerID.POTIONSTORE_EXTENDED_SUPER_ANTIFIRE_MIX,
        VarPlayerID.POTIONSTORE_GUAMVIAL, VarPlayerID.POTIONSTORE_MARRENTILLVIAL,
        VarPlayerID.POTIONSTORE_TARROMINVIAL, VarPlayerID.POTIONSTORE_HARRALANDERVIAL,
        VarPlayerID.POTIONSTORE_RANARRVIAL, VarPlayerID.POTIONSTORE_TOADFLAXVIAL, VarPlayerID.POTIONSTORE_IRITVIAL,
        VarPlayerID.POTIONSTORE_AVANTOEVIAL, VarPlayerID.POTIONSTORE_KWUARMVIAL,
        VarPlayerID.POTIONSTORE_SNAPDRAGONVIAL, VarPlayerID.POTIONSTORE_CADANTINEVIAL,
        VarPlayerID.POTIONSTORE_LANTADYMEVIAL, VarPlayerID.POTIONSTORE_DWARFWEEDVIAL,
        VarPlayerID.POTIONSTORE_TORSTOLVIAL, VarPlayerID.POTIONSTORE_BLIGHTED_2RESTORE_POTION,
        VarPlayerID.POTIONSTORE_EXTENDED_ANTIVENOMPLUS_POTION, VarPlayerID.POTIONSTORE_CADANTINE_BLOODVIAL,
        VarPlayerID.POTIONSTORE_HUASCAVIAL, VarPlayerID.POTIONSTORE_SURGE_POTION,
        VarPlayerID.POTIONSTORE_ARMADYLBREW_POTION, VarPlayerID.POTIONSTORE_EXTENDED_STAMINA_POTION,
        VarPlayerID.POTIONSTORE_EXTREME_ENERGY_POTION, VarPlayerID.POTIONSTORE_SUPER_FISHING_POTION,
        VarPlayerID.POTIONSTORE_SUPER_HUNTER_POTION, VarPlayerID.POTIONSTORE_ELKHORNVIAL,
        VarPlayerID.POTIONSTORE_PILLARVIAL, VarPlayerID.POTIONSTORE_UMBRALVIAL,
        VarPlayerID.POTIONSTORE_HAEMOSTATIC_POULTICE};
    private static final int[] PINNED_CHARTING = {
        18574, 18575, 18576, 18577, 18578, 18579, 18580, 18581, 18582, 18583, 18584, 18585, 18586, 18587, 18588,
        18589, 18590, 18591, 18592, 18593, 18594, 18595, 18596, 18597, 18598, 18599, 18600, 18601, 18602, 18603,
        18604, 18605, 18606, 18607, 18608, 18609, 18610, 18611, 18612, 18613, 18614, 18615, 18616, 18617, 18618,
        18619, 18620, 18621, 18622, 18623, 18624, 18625, 18626, 18627, 18628, 18629, 18630, 18631, 18632, 18633,
        18634, 18635, 18636, 18637, 18638, 18639, 18640, 18641, 18642, 18643, 18644, 18645, 18646, 18647, 18648,
        18649, 18650, 18651, 18652, 18653, 18654, 18655, 18656, 18657, 18658, 18659, 18660, 18661, 18662, 18663,
        18664, 18665, 18666, 18667, 18668, 18669, 18670, 18671, 18672, 18673, 18674, 18675, 18676, 18677, 18678,
        18679, 18680, 18681, 18682, 18683, 18684, 18685, 18686, 18687, 18688, 18689, 18690, 18691, 18692, 18693,
        18694, 18695, 18696, 18697, 18698, 18699, 18700, 18701, 18702, 18703, 18704, 18705, 18706, 18707, 18708,
        18709, 18710, 18711, 18712, 18713, 18714, 18715, 18716, 18717, 18718, 18719, 18720, 18721, 18722, 18723,
        18724, 18725, 18726, 18727, 18728, 18729, 18730, 18731, 18732, 18733, 18734, 18735, 18736, 18737, 18738,
        18739, 18740, 18741, 18742, 18743, 18744, 18745, 18746, 18747, 18748, 18749, 18750, 18751, 18752, 18753,
        18754, 18755, 18756, 18757, 18758, 18759, 18760, 18761, 18762, 18763, 18764, 18765, 18766, 18767, 18768,
        18769, 18770, 18771, 18772, 18773, 18774, 18775, 18776, 18777, 18778, 18779, 18780, 18781, 18782, 18783,
        18784, 18785, 18786, 18787, 18788, 18789, 18790, 18791, 18792, 18793, 18794, 18795, 18796, 18797, 18798,
        18799, 18800, 18801, 18802, 18803, 18804, 18805, 18806, 18807, 18808, 18809, 18810, 18811, 18812, 18813,
        18814, 18815, 18816, 18817, 18818, 18819, 18820, 18821, 18822, 18823, 18824, 18825, 18826, 18827, 18828,
        18829, 18830, 18831, 18832, 18833, 18834, 18835, 18836, 18837, 18838, 18839, 18840, 18841, 18842, 18843,
        18844, 18845, 18846, 18847, 18848, 18849, 18850, 18851, 18852, 18853, 18854, 18855, 18856, 18857, 18858,
        18859, 18860, 18861, 18862, 18863, 18864, 18865, 18866, 18867, 18868, 18869, 18870, 18871, 18872, 18873,
        18874, 18875, 18876, 18877, 18878, 18879, 18880, 18881, 18882, 18883, 18884, 18885, 18886, 18887, 18888,
        18889, 18890, 18891, 18892, 18893, 18894, 18895, 18896, 18897, 18898, 18899, 18900, 18901, 18902, 18903,
        18904, 18905, 18906, 18907, 18908, 18909, 18910, 18911, 18912, 18913, 18914, 18915, 18916, 18917, 18918,
        18919, 18920, 18921, 18922, 18923, 18924, 18925, 18926, 18927, 18928, 18929, 18930, 18931,
    };
    private static final int[][] PINNED_TOTEMS = {
        {17611, 17612, 17614, 17615, 17616, 17617, 17618, 17619, 17620, 17621, 17622},
        {17629, 17630, 17632, 17633, 17634, 17635, 17636, 17637, 17638, 17639, 17640},
        {17647, 17648, 17650, 17651, 17652, 17653, 17654, 17655, 17656, 17657, 17658},
        {17665, 17666, 17668, 17669, 17670, 17671, 17672, 17673, 17674, 17675, 17676},
        {17683, 17684, 17686, 17687, 17688, 17689, 17690, 17691, 17692, 17693, 17694},
        {17701, 17702, 17704, 17705, 17706, 17707, 17708, 17709, 17710, 17711, 17712},
        {17719, 17720, 17722, 17723, 17724, 17725, 17726, 17727, 17728, 17729, 17730},
        {17737, 17738, 17740, 17741, 17742, 17743, 17744, 17745, 17746, 17747, 17748},
    };
    Client client; Player self; OwnGameplayCapture capture; int[] vars; int tick;
    @Before public void setup()
    {
        client=mock(Client.class); self=mock(Player.class); capture=new OwnGameplayCapture(); vars=new int[6000]; tick=100;
        when(client.getVarps()).thenReturn(vars); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(self); when(client.getAccountHash()).thenReturn(42L);
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class)); when(client.getTickCount()).thenAnswer(i->tick);
        assertFalse(capture.ready(client,"grant")); tick+=2; assertTrue(capture.ready(client,"grant"));
    }
    void bit(int id,int index)
    {
        VarbitComposition d=mock(VarbitComposition.class); when(d.getIndex()).thenReturn(index);
        when(d.getMostSignificantBit()).thenReturn(15); when(client.getVarbit(id)).thenReturn(d);
    }
    Map<?,?> value(String family)
    {
        return (Map<?,?>)((Map<?,?>)((Map<?,?>)capture.snapshot().get("states")).get(family)).get("value");
    }
    List<Map<String,Object>> rows() { return (List<Map<String,Object>>)capture.snapshot().get("observations"); }
    void chat(ChatMessageType type,String text)
    {
        ChatMessage e=new ChatMessage(); e.setType(type); e.setMessage(text); capture.activities.chat(client,e,1000);
    }
    @Test public void whistleObservesKnownTargetAndExplicitAbsenceWithoutInventingTurnIn()
    {
        chat(ChatMessageType.GAMEMESSAGE,"Your current rumour target is a Red chinchompa. You'll need to find it.");
        chat(ChatMessageType.GAMEMESSAGE,"Your current rumour target is a Red chinchompa. You'll need to find it.");
        assertEquals(1,rows().size()); Map<?,?> first=(Map<?,?>)rows().get(0).get("value");
        assertEquals("red_chinchompa",first.get("target_observed"));
        assertEquals("whistle_target",first.get("signal")); assertEquals(false,first.get("turn_in_verified"));
        tick++; chat(ChatMessageType.GAMEMESSAGE,"You do not have an active rumour right now.");
        assertEquals(2,rows().size()); Map<?,?> last=(Map<?,?>)rows().get(1).get("value");
        assertEquals("whistle_no_active",last.get("signal"));assertNull(last.get("target_observed"));
        assertFalse(last.containsKey("completed_total"));
        verify(client,never()).getItemContainer(any(InventoryID.class));verify(client,never()).getPlayers();
    }
    @Test public void whistleDoesNotReadChatNamesOrExportUnknownTextAndDialogue()
    {
        ChatMessage other=mock(ChatMessage.class);when(other.getType()).thenReturn(ChatMessageType.PUBLICCHAT);
        capture.activities.chat(client,other,1000); verify(other,never()).getMessage();verify(other,never()).getName();
        chat(ChatMessageType.SPAM,"Your current rumour target is a Red chinchompa. You'll find it.");
        chat(ChatMessageType.GAMEMESSAGE,"Your current rumour target is a Private Player. You'll find it.");
        chat(ChatMessageType.GAMEMESSAGE,"OtherPlayer: You do not have an active rumour right now.");
        chat(ChatMessageType.DIALOG,"Guild Hunter Wolf|Thanks for that. Red chinchompa.");
        chat(ChatMessageType.GAMEMESSAGE,"Your current rumour target is a red chinchompa. You'll " + String.join("",Collections.nCopies(200,"x")));
        assertTrue(rows().isEmpty());
        chat(ChatMessageType.GAMEMESSAGE,"Your current rumour target is an Embertailed jerboa. You'll find Private Player nearby.");
        assertEquals(1,rows().size());assertEquals("embertailed_jerboa",((Map<?,?>)rows().get(0).get("value")).get("target_observed"));
        assertFalse(rows().toString().contains("Private"));assertFalse(rows().toString().contains("You'll"));
    }
    @Test public void whistleHistoryDoesNotSurviveAccountSwitchOrFabricateMissingActivity()
    {
        chat(ChatMessageType.GAMEMESSAGE,"Your current rumour target is a Sabre-toothed kebbit. You'll need to find it.");
        assertEquals(1,rows().size());when(client.getAccountHash()).thenReturn(99L);assertFalse(capture.ready(client,"grant"));
        assertTrue(rows().isEmpty());tick+=2;assertTrue(capture.ready(client,"grant"));assertTrue(rows().isEmpty());
        chat(ChatMessageType.GAMEMESSAGE,"You do not have an active rumour right now.");assertEquals(1,rows().size());
        assertEquals("whistle_no_active",((Map<?,?>)rows().get(0).get("value")).get("signal"));
    }
    @Test public void potionStorageUsesNamedVarpsAndDistinguishesMissingFromZero()
    {
        assertArrayEquals("the shipped id runs equal the named potion-store varps, in order", NAMED_POTION_VARPS,
            OwnPotionStorageCapture.VARPS);
        assertEquals(113,Arrays.stream(OwnPotionStorageCapture.VARPS).distinct().count());
        assertFalse(Arrays.stream(OwnPotionStorageCapture.VARPS).anyMatch(id->id>=4272&&id<=4285));
        vars[4327]=1234567;vars[4937]=Integer.MAX_VALUE;
        capture.poll(client,1000);Map<?,?> raw=(Map<?,?>)value("storage.potion_store").get("storage_values_raw");
        assertEquals(1234567,raw.get("4327"));assertEquals(Integer.MAX_VALUE,raw.get("4937"));assertEquals(0,raw.get("4286"));
        assertEquals("unverified_not_doses_or_bottles",value("storage.potion_store").get("units"));
        assertNull(value("storage.potion_store").get("unlocked_raw"));
        when(client.getVarps()).thenReturn(new int[4300]);tick++;capture.poll(client,1001);
        raw=(Map<?,?>)value("storage.potion_store").get("storage_values_raw");assertNull(raw.get("4327"));assertEquals(0,raw.get("4286"));
        when(client.getVarps()).thenReturn(null);tick++;capture.poll(client,1002);
        assertTrue(((Map<?,?>)value("storage.potion_store").get("storage_values_raw")).values().stream().allMatch(Objects::isNull));
        verify(client,never()).getVarbit(4327);verify(client,never()).getPlayers();
        verify(client,never()).runScript(anyInt(),any());
    }
    @Test public void potionStorageIdleDeduplicatesChangesAreAbsoluteAndProfileResetClearsValues()
    {
        vars[4327]=50;capture.poll(client,1000);
        long before=rows().stream().filter(r->"storage.potion_store".equals(r.get("kind"))).count();
        tick++;capture.poll(client,1001);assertEquals(before,rows().stream().filter(r->"storage.potion_store".equals(r.get("kind"))).count());
        vars[4327]=0;tick++;capture.poll(client,1002);
        List<Map<String,Object>> potions=new ArrayList<>();for(Map<String,Object> r:rows())if("storage.potion_store".equals(r.get("kind")))potions.add(r);
        assertEquals(2,potions.size());Map<?,?> last=(Map<?,?>)potions.get(1).get("value");assertEquals("absolute_update",last.get("observation"));
        assertEquals(0,((Map<?,?>)last.get("storage_values_raw")).get("4327"));
        when(client.getAccountHash()).thenReturn(99L);assertFalse(capture.ready(client,"grant"));
        assertTrue(((Map<?,?>)capture.snapshot().get("states")).isEmpty());assertTrue(rows().isEmpty());
    }
    @Test public void portTasksUseOwnFixedNamespacesWithoutWidgetTaskOrRewardInference()
    {
        vars[5197]=100;vars[5201]=500;vars[5207]=42;vars[5586]=3;
        bit(19589,100);bit(19591,101);vars[100]=2;vars[101]=7;
        capture.poll(client,1000);Map<?,?> port=value("sailing.port_tasks");
        assertEquals(6,((Map<?,?>)port.get("packed_slots_raw")).size());
        assertEquals(100,((Map<?,?>)port.get("packed_slots_raw")).get("5197"));
        assertEquals(500,((Map<?,?>)port.get("packed_slots_raw")).get("5201"));
        assertEquals(42,port.get("completed_total_raw"));assertEquals(3,port.get("bounty_item_counter_raw"));
        assertEquals(2,port.get("extra_slots_raw"));assertEquals(7,port.get("completed_today_raw"));
        assertEquals("task_identity_and_reward_unverified",port.get("interpretation"));
        assertFalse(port.containsKey("bounty_creature"));assertFalse(port.containsKey("reward"));
        verify(client,never()).getVarbit(5207);verify(client,never()).getWidgetRoots();
    }
    @Test public void portTaskReplacementKeepsMissingDifferentFromEmptyAndDoesNotClaimCompletion()
    {
        vars[5197]=123;capture.poll(client,1000);vars[5197]=0;tick++;capture.poll(client,1001);
        assertEquals(0,((Map<?,?>)value("sailing.port_tasks").get("packed_slots_raw")).get("5197"));
        assertNull(value("sailing.port_tasks").get("extra_slots_raw"));
        when(client.getVarps()).thenReturn(new int[5000]);tick++;capture.poll(client,1002);
        assertTrue(((Map<?,?>)value("sailing.port_tasks").get("packed_slots_raw")).values().stream().allMatch(Objects::isNull));
        assertNull(value("sailing.port_tasks").get("completed_total_raw"));
    }
    @Test public void countersKeepMissingZeroAndCoarseTimerDistinct()
    {
        bit(5099,100); bit(455,101); vars[100]=20; vars[101]=7;
        capture.poll(client,1000); assertEquals(20,value("minigame.tears").get("countdown_raw"));
        assertNull(value("minigame.tears").get("collecting_raw"));
        tick++; capture.poll(client,1001); assertEquals(20,value("minigame.tears").get("countdown_raw"));
        vars[100]=0; tick++; capture.poll(client,1002); assertEquals(0,value("minigame.tears").get("countdown_raw"));
    }
    @Test public void totemVarpOnlyChangeAndEveryAnimalSlotUseCorrectNamespace()
    {
        assertArrayEquals(PINNED_TOTEMS,OwnActivityMappings.TOTEMS);
        for(int site=0;site<8;site++) for(int f=0;f<11;f++)
        { bit(OwnActivityMappings.TOTEMS[site][f],100+site*11+f); vars[100+site*11+f]=site*11+f; }
        vars[4764]=99; capture.poll(client,1000);
        Map<?,?> site8=(Map<?,?>)value("minigame.totem_state").get("site_8");
        assertEquals(83,site8.get("animal_1_raw")); assertEquals(84,site8.get("animal_2_raw")); assertEquals(85,site8.get("animal_3_raw"));
        vars[4764]=100; tick++; capture.poll(client,1001);
        assertEquals(100,value("minigame.totem_state").get("research_points_raw"));
        vars[177]=0; tick++; capture.poll(client,1002);
        assertEquals(0,((Map<?,?>)value("minigame.totem_state").get("site_8")).get("base_raw"));
        verify(client,never()).getVarbit(4764);
    }
    @Test public void chartingHas358UniquePinnedIdsAndNoNearestTaskInference()
    {
        assertArrayEquals(PINNED_CHARTING,OwnActivityMappings.CHARTING);
        assertEquals(358,Arrays.stream(OwnActivityMappings.CHARTING).distinct().count());
        int id=OwnActivityMappings.CHARTING[357]; bit(id,100); vars[100]=1;
        capture.poll(client,1000); assertEquals(358,value("sailing.charting").size());
        assertEquals(1,value("sailing.charting").get(String.valueOf(id)));
        assertNull(value("sailing.charting").get(String.valueOf(OwnActivityMappings.CHARTING[0])));
        vars[100]=0; tick++; capture.poll(client,1001); assertEquals(0,value("sailing.charting").get(String.valueOf(id)));
    }
    @Test public void ownContributionDoesNotReadTeamBottlesOrPlayerSlots()
    {
        bit(2290,100); vars[100]=33; capture.poll(client,1000);
        assertEquals(Collections.singletonMap("own_contribution_raw",33),value("minigame.brewing"));
        verify(client,never()).getVarbit(2286); verify(client,never()).getVarbit(2287);
        for(int i=6442;i<=6446;i++) verify(client,never()).getVarbit(i);
        verify(client,never()).getPlayers();
    }
    @Test public void orderReplacementAndTrialSwitchAreAbsoluteNotCompletion()
    {
        bit(2483,100); bit(2485,101); bit(18427,102); bit(18432,103);
        vars[100]=7; vars[102]=2; capture.poll(client,1000);
        vars[100]=9; vars[101]=1; vars[102]=0; vars[103]=2; tick++; capture.poll(client,1001);
        assertEquals(9,value("minigame.restaurant_order").get("order_id_raw"));
        assertEquals(1,value("minigame.restaurant_order").get("refused_hard_raw"));
        assertEquals(2,value("sailing.trial_progress").get("jubbly_jive_raw"));
        assertFalse(value("sailing.trial_progress").containsKey("completed"));
    }
    @Test public void hiddenMissingUnknownAndLobbyHudClearRoomInsteadOfLeakingText()
    {
        bit(6440,100); vars[100]=2; Widget parent=mock(Widget.class), label=mock(Widget.class);
        when(client.getWidget(28,1)).thenReturn(parent); when(parent.getChild(2)).thenReturn(label);
        when(label.getText()).thenReturn("Sotetseg"); capture.poll(client,1000);
        assertEquals("Sotetseg",value("combat.raid_context").get("room_observed"));
        when(label.getText()).thenReturn("Other Player Private Name"); tick++; capture.poll(client,1001);
        assertNull(value("combat.raid_context").get("room_observed"));
        when(label.getText()).thenReturn("Sotetseg"); when(parent.isHidden()).thenReturn(true); tick++; capture.poll(client,1002);
        assertNull(value("combat.raid_context").get("room_observed"));
        when(parent.isHidden()).thenReturn(false); vars[100]=1; tick++; capture.poll(client,1003);
        assertNull(value("combat.raid_context").get("room_observed"));
        assertFalse(new com.google.gson.Gson().toJson(capture.snapshot()).contains("Private Name"));
    }
    @Test public void deathReadsOnlySelfAndDoesNotCountSpectatorOrDuplicateCallbacks()
    {
        Player other=mock(Player.class); capture.activities.death(client,other,1000); verifyNoInteractions(other); assertTrue(rows().isEmpty());
        bit(6440,100); vars[100]=1; capture.activities.death(client,self,1000);
        assertFalse(rows().stream().anyMatch(r->"combat.tob_room_death".equals(r.get("kind"))));
        vars[100]=2; tick++; capture.activities.death(client,self,1001); capture.activities.death(client,self,1001);
        assertEquals(1,rows().stream().filter(r->"combat.tob_room_death".equals(r.get("kind"))).count());
    }
    @Test public void publicChatCannotSpoofStructuredGameSignals()
    {
        chat(ChatMessageType.PUBLICCHAT,"You scratch a notch on your bow for the chompy bird kill.");
        chat(ChatMessageType.CLAN_CHAT,"You have completed 100 contracts with a total of 1,000 points.");
        assertTrue(rows().isEmpty());
        chat(ChatMessageType.SPAM,"You scratch a notch on your bow for the chompy bird kill.");
        chat(ChatMessageType.SPAM,"You scratch a notch on your bow for the chompy bird kill.");
        assertEquals(1,rows().size()); assertNull(((Map<?,?>)rows().get(0).get("value")).get("total_kills"));
    }
    @Test public void contractTotalsStayAbsoluteAndMalformedCountsAreRejected()
    {
        chat(ChatMessageType.GAMEMESSAGE,"You have completed 1,234 contracts with a total of 12,345 points.");
        assertEquals(1234L,((Map<?,?>)rows().get(0).get("value")).get("contracts_absolute"));
        chat(ChatMessageType.GAMEMESSAGE,"You have completed 1,2 contracts with a total of 12,345 points.");
        chat(ChatMessageType.GAMEMESSAGE,"You have completed 999999999999999999999999 contracts with a total of 1 point.");
        assertEquals(1,rows().size());
    }
    @Test public void storageAndCompostSignalsEmitNoRawNamesNoInventedPatchOrTotals()
    {
        chat(ChatMessageType.GAMEMESSAGE,"The coal bag contains one piece of coal.");
        chat(ChatMessageType.SPAM,"The grape barrel is partially full.");
        chat(ChatMessageType.GAMEMESSAGE,"You treat the allotment with ultracompost.");
        assertEquals(3,rows().size()); assertEquals(1L,((Map<?,?>)rows().get(0).get("value")).get("count_observed"));
        assertEquals("partial",((Map<?,?>)rows().get(1).get("value")).get("fill_observed"));
        assertEquals("unknown",((Map<?,?>)rows().get(2).get("value")).get("patch_identity"));
        chat(ChatMessageType.GAMEMESSAGE,"OtherPlayer received a drop: Secret.");
        assertEquals(3,rows().size()); assertFalse(new com.google.gson.Gson().toJson(capture.snapshot()).contains("allotment"));
    }
    @Test public void gapAllowsNewBaselineAndClearsMessageDedupState()
    {
        chat(ChatMessageType.GAMEMESSAGE,"The coal bag is empty."); capture.gap();
        chat(ChatMessageType.GAMEMESSAGE,"The coal bag is empty."); assertEquals(2,rows().size());
        capture.poll(client,1000); assertEquals("baseline",((Map<?,?>)rows().get(2).get("value")).get("observation"));
        capture.reset(); assertTrue(rows().isEmpty());
    }
}
