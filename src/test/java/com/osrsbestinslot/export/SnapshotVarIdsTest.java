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
}
