package com.osrsbestinslot.export;

import static org.junit.Assert.assertArrayEquals;

import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/**
 * The snapshot's raw var tables hold ids as numbers (a negative id is that varp). Each number equals the named
 * constant the builder read before 0.7.72, in the same wire order, so the snapshot keys and values are unchanged.
 */
@SuppressWarnings("deprecation")
public class SnapshotVarIdsTest
{
	@Test
	public void combatAchievementTiersAreTheNamedVarbits()
	{
		assertArrayEquals(new Object[]{
			"easy", Varbits.COMBAT_ACHIEVEMENT_TIER_EASY, "medium", Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM,
			"hard", Varbits.COMBAT_ACHIEVEMENT_TIER_HARD, "elite", Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE,
			"master", Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER, "grandmaster", Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER,
		}, SnapshotBuilder.CA_TIERS);
	}

	@Test
	public void slayerIsTheNamedVarbitsAndVarps()
	{
		assertArrayEquals(new Object[]{
			"points", Varbits.SLAYER_POINTS, "streak", Varbits.SLAYER_TASK_STREAK,
			"task_target_id", -VarPlayerID.SLAYER_TARGET, "task_remaining", -VarPlayerID.SLAYER_COUNT,
			"unlocks_raw", -VarPlayerID.SLAYER_REWARDS_UNLOCKS, "blocked_raw", -VarPlayerID.SLAYER_REWARDS_BLOCKED,
			"unlocks1_raw", -VarPlayerID.SLAYER_REWARDS_UNLOCKS1, "unlocks2_raw", -VarPlayerID.SLAYER_REWARDS_UNLOCKS2,
		}, SnapshotBuilder.SLAYER);
	}

	@Test
	public void kourendFavourIsTheNamedVarbits()
	{
		assertArrayEquals(new Object[]{
			"arceuus", Varbits.KOUREND_FAVOR_ARCEUUS, "hosidius", Varbits.KOUREND_FAVOR_HOSIDIUS,
			"lovakengj", Varbits.KOUREND_FAVOR_LOVAKENGJ, "piscarilius", Varbits.KOUREND_FAVOR_PISCARILIUS,
			"shayzien", Varbits.KOUREND_FAVOR_SHAYZIEN,
		}, SnapshotBuilder.KOUREND_FAVOUR);
	}

	@Test
	public void minigamePointsAreTheNamedVarbitsAndVarps()
	{
		assertArrayEquals(new Object[]{
			"nmz", Varbits.NMZ_POINTS, "tithe", Varbits.TITHE_FARM_POINTS,
			"ba_attacker_base", VarbitID.BARBASSAULT_POINTS_ATTACKER_BASE,
			"ba_collector_base", VarbitID.BARBASSAULT_POINTS_COLLECTOR_BASE,
			"ba_healer_base", VarbitID.BARBASSAULT_POINTS_HEALER_BASE,
			"ba_defender_base", VarbitID.BARBASSAULT_POINTS_DEFENDER_BASE,
			"ba_attacker_extra", VarbitID.BARBASSAULT_POINTS_ATTACKER_EXTRA,
			"ba_collector_extra", VarbitID.BARBASSAULT_POINTS_COLLECTOR_EXTRA,
			"ba_healer_extra", VarbitID.BARBASSAULT_POINTS_HEALER_EXTRA,
			"ba_defender_extra", VarbitID.BARBASSAULT_POINTS_DEFENDER_EXTRA,
			"colosseum_highest_wave", VarbitID.COLOSSEUM_HIGHEST_WAVE,
			"hallowed_final_chests", VarbitID.HALLOWED_FINAL_CHEST_COUNT,
			"hallowed_normal_chests", VarbitID.HALLOWED_NORMAL_CHEST_COUNT,
			"tempoross_permits", VarbitID.TEMPOROSS_REWARDPERMITS,
			"soul_wars_zeal", -VarPlayerID.SOUL_WARS_ZEAL,
			"giants_foundry", -VarPlayerID.GIANTS_FOUNDRY_REWARD_SHOP_POINTS,
			"mixology_lye", -VarPlayerID.MIXOLOGY_LYE_POINTS,
			"mixology_aga", -VarPlayerID.MIXOLOGY_AGA_POINTS,
			"mixology_mox", -VarPlayerID.MIXOLOGY_MOX_POINTS,
		}, SnapshotBuilder.MINIGAME_POINTS);
	}

	@Test
	public void runePouchSlotsAreTheNamedVarbits()
	{
		assertArrayEquals(new int[][]{
			{Varbits.RUNE_POUCH_RUNE1, Varbits.RUNE_POUCH_AMOUNT1}, {Varbits.RUNE_POUCH_RUNE2, Varbits.RUNE_POUCH_AMOUNT2},
			{Varbits.RUNE_POUCH_RUNE3, Varbits.RUNE_POUCH_AMOUNT3}, {Varbits.RUNE_POUCH_RUNE4, Varbits.RUNE_POUCH_AMOUNT4},
			{Varbits.RUNE_POUCH_RUNE5, Varbits.RUNE_POUCH_AMOUNT5}, {Varbits.RUNE_POUCH_RUNE6, Varbits.RUNE_POUCH_AMOUNT6},
		}, SnapshotBuilder.RUNE_POUCH);
	}

	@Test
	public void diariesAreTheNamedVarbits()
	{
		assertArrayEquals(new Object[][]{
			{"ardougne", Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE},
			{"desert", Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE},
			{"falador", Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE},
			{"fremennik", Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE},
			{"kandarin", Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE},
			{"karamja", Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE},
			{"kourend", Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE},
			{"lumbridge", Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE},
			{"morytania", Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE},
			{"varrock", Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE},
			{"western", Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE},
			{"wilderness", Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE},
		}, SnapshotBuilder.DIARIES);
	}

	@Test
	public void toolLeprechaunIsTheNamedVarbits()
	{
		assertArrayEquals(new Object[]{
			"rake", VarbitID.FARMING_TOOLS_RAKE, "dibber", VarbitID.FARMING_TOOLS_DIBBER,
			"spade", VarbitID.FARMING_TOOLS_SPADE, "secateurs", VarbitID.FARMING_TOOLS_SECATEURS,
			"magic_secateurs", VarbitID.FARMING_TOOLS_FAIRYSECATEURS, "watering_can", VarbitID.FARMING_TOOLS_WATERINGCAN,
			"trowel", VarbitID.FARMING_TOOLS_TROWEL, "plant_cure", VarbitID.FARMING_TOOLS_PLANTCURE,
			"buckets", VarbitID.FARMING_TOOLS_BUCKETS, "extra_buckets", VarbitID.FARMING_TOOLS_EXTRABUCKETS,
			"extra2_buckets", VarbitID.FARMING_TOOLS_EXTRA2BUCKETS, "compost", VarbitID.FARMING_TOOLS_COMPOST,
			"extra_compost", VarbitID.FARMING_TOOLS_EXTRACOMPOST, "supercompost", VarbitID.FARMING_TOOLS_SUPERCOMPOST,
			"extra_supercompost", VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST, "ultracompost", VarbitID.FARMING_TOOLS_ULTRACOMPOST,
			"bottomless_bucket_type", VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE,
			"bottomless_bucket_quantity", VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_QUANTITY,
			"extra_rakes", VarbitID.FARMING_TOOLS_EXTRARAKES, "extra_dibbers", VarbitID.FARMING_TOOLS_EXTRADIBBERS,
			"extra_secateurs", VarbitID.FARMING_TOOLS_EXTRASECATEURS, "extra_trowels", VarbitID.FARMING_TOOLS_EXTRATROWELS,
			"extra_spades", VarbitID.FARMING_TOOLS_EXTRASPADES,
		}, SnapshotBuilder.TOOL_LEPRECHAUN);
	}

	@Test
	public void membershipDaysIsTheAccountCreditVarp()
	{
		org.junit.Assert.assertEquals(VarPlayerID.ACCOUNT_CREDIT, net.runelite.api.VarPlayer.MEMBERSHIP_DAYS);
	}

	@Test
	public void killCountsAreTheNamedVarps()
	{
		assertArrayEquals(new int[]{
			VarPlayerID.COMPLETED_CLUES, VarPlayerID.COMPLETED_CLUES1, VarPlayerID.COMPLETED_CLUES_TOTAL,
			VarPlayerID.COMPLETED_CLUES2, VarPlayerID.TOTAL_ARMADYL_KILLS, VarPlayerID.TOTAL_BANDOS_KILLS,
			VarPlayerID.TOTAL_SARADOMIN_KILLS, VarPlayerID.TOTAL_ZAMORAK_KILLS, VarPlayerID.TOTAL_PRIME_KILLS,
			VarPlayerID.TOTAL_REX_KILLS, VarPlayerID.TOTAL_SUPREME_KILLS, VarPlayerID.TOTAL_CALLISTO_KILLS,
			VarPlayerID.TOTAL_VENENATIS_KILLS, VarPlayerID.TOTAL_VETION_KILLS, VarPlayerID.TOTAL_CHAOSELE_KILLS,
			VarPlayerID.TOTAL_KBD_KILLS, VarPlayerID.TOTAL_MOLE_KILLS, VarPlayerID.TOTAL_KALPHITE_KILLS,
			VarPlayerID.TOTAL_CORP_KILLS, VarPlayerID.TOTAL_SNAKEBOSS_KILLS, VarPlayerID.TOTAL_CHAOSFANATIC_KILLS,
			VarPlayerID.TOTAL_SCORPIA_KILLS, VarPlayerID.TOTAL_CRAZYARCHAEOLOGIST_KILLS, VarPlayerID.TOTAL_JAD_KILLS,
			VarPlayerID.TOTAL_KRAKEN_BOSS_KILLS, VarPlayerID.TOTAL_THERMY_KILLS, VarPlayerID.TOTAL_CERBERUS_KILLS,
			VarPlayerID.TOTAL_ABYSSALSIRE_KILLS, VarPlayerID.TOTAL_CATA_BOSS_KILLS, VarPlayerID.TOTAL_WINTERTODT_KILLS,
			VarPlayerID.TOTAL_HILLGIANT_BOSS_KILLS, VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS, VarPlayerID.COMPLETED_CLUES3,
			VarPlayerID.COMPLETED_CLUES4, VarPlayerID.TOTAL_ZUK_KILLS, VarPlayerID.TOTAL_DERANGEDARCHAEOLOGIST_KILLS,
			VarPlayerID.TOTAL_GARGBOSS_KILLS, VarPlayerID.TOTAL_VORKATH_KILLS, VarPlayerID.TOTAL_BRYOPHYTA_KILLS,
			VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS_CHALLENGE, VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD, VarPlayerID.TOTAL_HYDRABOSS_KILLS,
			VarPlayerID.TOTAL_HESPORI_KILLS, VarPlayerID.COMPLETED_CLUES5, VarPlayerID.TOTAL_MIMIC_KILLS,
			VarPlayerID.TOTAL_SARACHNIS_KILLS, VarPlayerID.TOTAL_ZALCANO_KILLS, VarPlayerID.TOTAL_COMPLETED_GAUNTLET,
			VarPlayerID.TOTAL_COMPLETED_GAUNTLET_HM, VarPlayerID.TOTAL_NIGHTMARE_KILLS, VarPlayerID.TOTAL_NIGHTMARE_CHALLENGE_KILLS,
			VarPlayerID.TOTAL_TEMPOROSS_KILLS, VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_HARD, VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_STORY,
			VarPlayerID.TOTAL_NEX_KILLS, VarPlayerID.TOTAL_GOTR_KILLS, VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_ENTRY,
			VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT, VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_EXPERT, VarPlayerID.TOTAL_MUSPAH_KILLS,
			VarPlayerID.TOTAL_ARTIO_KILLS, VarPlayerID.TOTAL_SPINDEL_KILLS, VarPlayerID.TOTAL_CALVARION_KILLS,
			VarPlayerID.TOTAL_DUKE_SUCELLUS_KILLS, VarPlayerID.TOTAL_LEVIATHAN_KILLS, VarPlayerID.TOTAL_WHISPERER_KILLS,
			VarPlayerID.TOTAL_VARDORVIS_KILLS, VarPlayerID.TOTAL_VARDORVIS_AWAKENED_KILLS, VarPlayerID.TOTAL_WHISPERER_AWAKENED_KILLS,
			VarPlayerID.TOTAL_LEVIATHAN_AWAKENED_KILLS, VarPlayerID.TOTAL_DUKE_SUCELLUS_AWAKENED_KILLS, VarPlayerID.TOTAL_RAT_BOSS_KILLS, VarPlayerID.PMOON_ECLIPSE_KILLS, VarPlayerID.PMOON_BLUE_KILLS, VarPlayerID.PMOON_BLOOD_KILLS,
			VarPlayerID.TOTAL_PMOON_CHESTS, VarPlayerID.TOTAL_SOL_KILLS, VarPlayerID.TOTAL_ARAXXOR_KILLS,
			VarPlayerID.TOTAL_AMOXLIATL_KILLS, VarPlayerID.TOTAL_HUEY_KILLS, VarPlayerID.TOTAL_ROYAL_TITAN_KILLS,
			VarPlayerID.TOTAL_YAMA_KILLS, VarPlayerID.TOTAL_GEMSTONE_CRAB_KILLS, VarPlayerID.TOTAL_GRYPHON_BOSS_KILLS,
			VarPlayerID.TOTAL_COWBOSS_KILLS, VarPlayerID.TOTAL_COWBOSS_HARDMODE_KILLS, VarPlayerID.TOTAL_MAGGOT_KING_KILLS,
			VarPlayerID.TOTAL_MAD_ANGEL_KILLS, VarPlayerID.TOTAL_COMPLETED_THEFRACTUREDARCHIVE,
		}, SnapshotBuilder.KILL_COUNT_VARPS);
	}
}
