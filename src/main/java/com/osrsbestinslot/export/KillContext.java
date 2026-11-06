package com.osrsbestinslot.export;

import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the player had and was boosted to at the moment the game counted a kill, and the progression and failure lines
 * around it. Pure: the caller reads the client, this class only shapes and compares.
 *
 * Signal groups, all own-account:
 * - charges, boosts and restrictions: worn items with quantities, boosted and real combat levels, prayer points,
 *   special attack energy and the world type. An item id gives the variant and ammo its count; charges stored inside
 *   an item are not read, so remaining charges are not established;
 * - supplies versus rewards: the inventory at each kill. The difference between two kills is not the supplies used:
 *   loot, pickups, banking, trades, drops and dose or form changes also move items. Nothing is inferred here;
 * - progression: a slayer task completion, read from the game's own completed-tasks counter going up by one, and the
 *   clue, collection-log and combat-task lines the account-progress parser already reads;
 * - failed runs: the game's party-failure line. A death is already its own row. Nothing here marks the start of an
 *   attempt, so attempts that end without a count, death or failure line are not delimited.
 */
final class KillContext
{
	private KillContext() {}

	/** Combat skills in a fixed order: boosted and real levels for each. */
	static final String[] SKILLS = {"attack", "strength", "defence", "ranged", "magic", "hitpoints", "prayer"};
	static final String PARTY_FAILED = "Your party failed to complete the challenge";

	/**
	 * kill_context body. worn and inventory are [item id, quantity] pairs; boosted and real are in SKILLS order.
	 * Returns null when a required read is missing, so a half-read context is never sent.
	 */
	static Map<String, Object> build(List<long[]> worn, List<long[]> inventory, int[] boosted, int[] real,
		Integer specialEnergy, Boolean membersWorld)
	{
		return build(worn, inventory, boosted, real, specialEnergy, membersWorld, null);
	}

	/** As above, plus the Tombs of Amascut raid level (varbit TOA_CLIENT_RAID_LEVEL) when the kill is a ToA one. */
	static Map<String, Object> build(List<long[]> worn, List<long[]> inventory, int[] boosted, int[] real,
		Integer specialEnergy, Boolean membersWorld, Integer toaRaidLevel)
	{
		if (worn == null || inventory == null || boosted == null || real == null
			|| boosted.length != SKILLS.length || real.length != SKILLS.length) return null;
		Map<String, Object> out = Fields.of("worn", pairs(worn), "inventory", pairs(inventory));
		Map<String, Object> levels = new LinkedHashMap<>();
		for (int i = 0; i < SKILLS.length; i++)
		{
			List<Integer> pair = new ArrayList<>();
			pair.add(boosted[i]);
			pair.add(real[i]);
			levels.put(SKILLS[i], pair);
		}
		out.put("levels_boosted_real", levels);
		// Varp 300 counts tenths of a percent (1000 = 100%).
		out.put("special_energy", specialEnergy == null ? null : specialEnergy / 10);
		out.put("world_members", membersWorld);
		if (toaRaidLevel != null) out.put("toa_raid_level", toaRaidLevel);
		return out;
	}

	private static List<List<Long>> pairs(List<long[]> items)
	{
		List<List<Long>> out = new ArrayList<>();
		for (long[] it : items)
		{
			if (it == null || it.length != 2 || it[0] <= 0 || it[1] <= 0) continue;
			List<Long> p = new ArrayList<>();
			p.add(it[0]);
			p.add(it[1]);
			out.add(p);
		}
		return out;
	}

	/** True for the game's party-failure line, with or without its trailing text. */
	static boolean partyFailed(String cleanText)
	{
		return cleanText != null && cleanText.startsWith(PARTY_FAILED);
	}

	/**
	 * Service units the game keeps in vars, by key: {key, var kind ("varp" or "varbit"), gameval id}. Ids from
	 * RuneLite gameval (runelite-api 1.13.4). These are the units customers order that have no kill count line:
	 * Doom of Mokhaiotl clears per delve level, Colosseum waves, Tempoross permits, Barbarian Assault and Tithe Farm
	 * points; then the game's own totals for the most-ordered services (below).
	 */
	static final Object[][] ACTIVITY_COUNTERS = {
		{"doom_level_1_completions", "varp", 4808}, {"doom_level_2_completions", "varp", 4809},
		{"doom_level_3_completions", "varp", 4810}, {"doom_level_4_completions", "varp", 4811},
		{"doom_level_5_completions", "varp", 4812}, {"doom_level_6_completions", "varp", 4813},
		{"doom_level_7_completions", "varp", 4814}, {"doom_level_8_completions", "varp", 4815},
		{"doom_level_8_plus_completions", "varp", 4816}, {"doom_deepest_level", "varp", 4806},
		{"colosseum_waves_completed", "varp", 4131}, {"colosseum_highest_wave", "varbit", 11410},
		{"tempoross_permits", "varbit", 11936}, {"tithe_points", "varbit", 4893},
		{"ba_attacker_base", "varbit", 4759}, {"ba_collector_base", "varbit", 4760},
		{"ba_healer_base", "varbit", 4761}, {"ba_defender_base", "varbit", 4762},
		// The game's own completion totals for the most-ordered services (In-Game Services ranking 2026-11-04).
		// They count a unit even when the chat line is missed, unverified (Wintertodt, TFA, Zuk) or ambiguous by
		// mode (raid loot), and they split each raid by mode. Named gameval ids, so a renamed var breaks the build.
		{"jad_kills", "varp", VarPlayerID.TOTAL_JAD_KILLS}, {"zuk_kills", "varp", VarPlayerID.TOTAL_ZUK_KILLS},
		{"sol_heredit_kills", "varp", VarPlayerID.TOTAL_SOL_KILLS}, {"doom_levels_total", "varp", VarPlayerID.TOTAL_DOM_LEVELS},
		{"gauntlet_completions", "varp", VarPlayerID.TOTAL_COMPLETED_GAUNTLET},
		{"corrupted_gauntlet_completions", "varp", VarPlayerID.TOTAL_COMPLETED_GAUNTLET_HM},
		{"fractured_archive_completions", "varp", VarPlayerID.TOTAL_COMPLETED_THEFRACTUREDARCHIVE},
		{"wintertodt_subdued", "varp", VarPlayerID.TOTAL_WINTERTODT_KILLS}, {"tempoross_kills", "varp", VarPlayerID.TOTAL_TEMPOROSS_KILLS},
		{"gotr_total", "varp", VarPlayerID.TOTAL_GOTR_KILLS},
		{"cox_completions", "varp", VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS},
		{"cox_cm_completions", "varp", VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS_CHALLENGE},
		{"tob_completions", "varp", VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD},
		{"tob_hard_completions", "varp", VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_HARD},
		{"tob_entry_completions", "varp", VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD_STORY},
		{"toa_entry_completions", "varp", VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_ENTRY},
		{"toa_completions", "varp", VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT},
		{"toa_expert_completions", "varp", VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT_EXPERT},
		{"nightmare_kills", "varp", VarPlayerID.TOTAL_NIGHTMARE_KILLS},
		{"phosani_kills", "varp", VarPlayerID.TOTAL_NIGHTMARE_CHALLENGE_KILLS},
		{"nex_kills", "varp", VarPlayerID.TOTAL_NEX_KILLS}, {"zalcano_kills", "varp", VarPlayerID.TOTAL_ZALCANO_KILLS},
		{"moons_chests", "varp", VarPlayerID.TOTAL_PMOON_CHESTS}, {"yama_kills", "varp", VarPlayerID.TOTAL_YAMA_KILLS},
		{"maggot_king_kills", "varp", VarPlayerID.TOTAL_MAGGOT_KING_KILLS},
		{"soul_wars_zeal", "varp", VarPlayerID.SOUL_WARS_ZEAL}, {"soul_wars_games", "varp", VarPlayerID.SOUL_WARS_TOTAL_GAMES},
		{"giants_foundry_points", "varp", VarPlayerID.GIANTS_FOUNDRY_REWARD_SHOP_POINTS},
		{"mixology_orders", "varp", VarPlayerID.TOTAL_MIXOLOGY_ORDERS},
		{"mixology_mox_points", "varp", VarPlayerID.MIXOLOGY_MOX_POINTS},
		{"mixology_aga_points", "varp", VarPlayerID.MIXOLOGY_AGA_POINTS},
		{"mixology_lye_points", "varp", VarPlayerID.MIXOLOGY_LYE_POINTS},
		// Every other minigame on the wiki list with a persistent var whose name says what it counts (gameval
		// 1.13.6). Meanings are from the names only until the rig confirms each one.
		{"castle_wars_wins", "varp", VarPlayerID.CASTLEWARS_WINS},
		{"castle_wars_games", "varp", VarPlayerID.CASTLEWARS_GAMES_PLAYED},
		{"soul_wars_wins", "varp", VarPlayerID.SOUL_WARS_TOTAL_WINS},
		{"soul_wars_kills", "varp", VarPlayerID.SOUL_WARS_TOTAL_KILLS},
		{"soul_wars_zeal_tokens", "varp", VarPlayerID.SOUL_WARS_ZEAL_TOKENS},
		{"ba_high_gambles", "varbit", VarbitID.BARBASSAULT_GAMBLECOUNT},
		{"ba_queen_kills", "varbit", VarbitID.BARBASSAULT_QUEENKILLS_EXTRA},
		{"nmz_reward_points", "varp", VarPlayerID.NZONE_REWARDPOINTS},
		{"lms_rank", "varp", VarPlayerID.BR_RANK},
		{"bounty_hunter_points", "varp", VarPlayerID.BH_2023_POINTS},
		{"bounty_hunter_target_kills", "varp", VarPlayerID.BH_2023_TARGET_KILL},
		{"bounty_hunter_rogue_kills", "varp", VarPlayerID.BH_2023_ROGUE_KILL},
		{"pvp_arena_rank_points", "varbit", VarbitID.PVPA_RANK_POINTS},
		{"pvp_arena_reward_points", "varbit", VarbitID.PVPA_POINTS_CURRENCY},
		{"vale_totems_research_points", "varp", VarPlayerID.ENT_TOTEMS_RESEARCH_POINTS},
		{"golem_crafting_count", "varbit", VarbitID.GOLEM_CRAFTING_COUNT},
		{"warriors_guild_games", "varp", VarPlayerID.WARGUILD_GAMES},
		// Signal research 2026-11-04 (evidence/minigame-signals-20261104.md): every remaining minigame var whose name
		// says it counts or tracks a result. All arrive "unverified" until the rig confirms each meaning.
		{"tzhaar_ket_rak_1_completions", "varp", VarPlayerID.JAD_CHALLENGE_1_COMPLETIONS},
		{"tzhaar_ket_rak_2_completions", "varp", VarPlayerID.JAD_CHALLENGE_2_COMPLETIONS},
		{"tzhaar_ket_rak_3_completions", "varp", VarPlayerID.JAD_CHALLENGE_3_COMPLETIONS},
		{"tzhaar_ket_rak_4_completions", "varp", VarPlayerID.JAD_CHALLENGE_4_COMPLETIONS},
		{"tzhaar_ket_rak_5_completions", "varp", VarPlayerID.JAD_CHALLENGE_5_COMPLETIONS},
		{"tzhaar_ket_rak_6_completions", "varp", VarPlayerID.JAD_CHALLENGE_6_COMPLETIONS},
		{"castle_wars_losses", "varp", VarPlayerID.CASTLEWARS_LOSS},
		{"castle_wars_game_kills", "varbit", VarbitID.CASTLEWARS_KILLS},
		{"castle_wars_game_caps", "varbit", VarbitID.CASTLEWARS_CAPS},
		{"lms_game_kills", "varbit", VarbitID.BR_KILLCOUNT},
		{"pest_control_activity", "varbit", VarbitID.PEST_ACTIVITY_COUNTER},
		{"gotr_elemental_this_game", "varbit", VarbitID.GOTR_ELEMENTAL_EARNED_THIS_GAME},
		{"gotr_catalytic_this_game", "varbit", VarbitID.GOTR_CATALYTIC_EARNED_THIS_GAME},
		{"gotr_finished_game", "varbit", VarbitID.GOTR_PLAYER_HAS_FINISHED_GAME},
		{"trawler_contribution", "varbit", VarbitID.TRAWLER_CONTRIBUTION},
		{"tzhaar_fight_pit_remaining", "varp", VarPlayerID.TZHAAR_FIGHTPIT_REMAINING},
		{"archery_target_count", "varp", VarPlayerID.TARGETCOUNT},
		{"archery_target_score", "varp", VarPlayerID.TARGETSCORE},
		{"barracuda_tempor_tantrum_completed", "varp", VarPlayerID.SAILING_BT_TRIAL_TEMPOR_TANTRUM_COMPLETED},
		{"barracuda_jubbly_jive_completed", "varp", VarPlayerID.SAILING_BT_TRIAL_JUBBLY_JIVE_COMPLETED},
		{"barracuda_gwenith_glide_completed", "varp", VarPlayerID.SAILING_BT_TRIAL_GWENITH_GLIDE_COMPLETED},
		{"gnome_restaurant_credits", "varbit", VarbitID.ALUFT_CREDITS},
		{"pyramid_plunder_room", "varbit", VarbitID.NTK_ROOM_NUMBER},
		{"trouble_brewing_pieces", "varp", VarPlayerID.BREW_PIECES},
		{"tai_bwo_cleanup_progress", "varbit", VarbitID.CLEANUP_PROGRESS},
		{"tai_bwo_cleanup", "varp", VarPlayerID.TAI_BWO_CLEANUP},
		{"tears_of_guthix_tears", "varbit", VarbitID.TOG_TEARS_COLLECTED},
		{"stealing_artefacts_completed", "varbit", VarbitID.PISCARILIUS_BURGLARY_COMPLETED},
		{"stealing_valuables_lifetime", "varp", VarPlayerID.VARLAMORE_THIEVING_HOUSE_VALUABLES_LIFETIME},
		{"wise_old_man_task_count", "varbit", VarbitID.WOM_NUMBER},
		{"games_room_draughts_rank", "varp", VarPlayerID.BOARDGAMES_DRAUGHTS_RANK},
		{"games_room_runelink_rank", "varp", VarPlayerID.BOARDGAMES_RUNELINK_RANK},
		{"games_room_runesquares_rank", "varp", VarPlayerID.BOARDGAMES_RUNESQUARES_RANK},
		{"games_room_runeversi_rank", "varp", VarPlayerID.BOARDGAMES_RUNEVERSI_RANK},
		{"shades_temple_sanctity", "varp", VarPlayerID.TEMPLE_SANCTITY_P},
		{"miscellania_coffers", "varbit", VarbitID.MISC_COFFERS},
		{"miscellania_approval", "varbit", VarbitID.MISC_APPROVAL},
		{"blast_furnace_bronze_bars", "varbit", VarbitID.BLAST_FURNACE_BRONZE_BARS},
		{"blast_furnace_iron_bars", "varbit", VarbitID.BLAST_FURNACE_IRON_BARS},
		{"blast_furnace_steel_bars", "varbit", VarbitID.BLAST_FURNACE_STEEL_BARS},
		{"blast_furnace_mithril_bars", "varbit", VarbitID.BLAST_FURNACE_MITHRIL_BARS},
		{"blast_furnace_adamantite_bars", "varbit", VarbitID.BLAST_FURNACE_ADAMANTITE_BARS},
		{"blast_furnace_runite_bars", "varbit", VarbitID.BLAST_FURNACE_RUNITE_BARS},
		{"blast_furnace_gold_bars", "varbit", VarbitID.BLAST_FURNACE_GOLD_BARS},
		{"blast_furnace_silver_bars", "varbit", VarbitID.BLAST_FURNACE_SILVER_BARS},
		{"blast_mine_coal", "varbit", VarbitID.LOVAKENGJ_ORE_COAL_BIGGER},
		{"blast_mine_gold", "varbit", VarbitID.LOVAKENGJ_ORE_GOLD_BIGGER},
		{"blast_mine_mithril", "varbit", VarbitID.LOVAKENGJ_ORE_MITHRIL_BIGGER},
		{"blast_mine_adamantite", "varbit", VarbitID.LOVAKENGJ_ORE_ADAMANTITE_BIGGER},
		{"blast_mine_runite", "varbit", VarbitID.LOVAKENGJ_ORE_RUNITE_BIGGER},
		{"mage_arena_state", "varp", VarPlayerID.MAGEARENA},
		{"mage_arena_2_progress", "varbit", VarbitID.MA2_PROGRESS},
		{"knight_waves_state", "varbit", VarbitID.KR_KNIGHTWAVES_STATE},
		{"pete_kayer_prayer", "varbit", VarbitID.PVPTUT_PRAYER_COMPLETE},
		{"pete_kayer_freeze", "varbit", VarbitID.PVPTUT_FREEZE_COMPLETE},
		{"pete_kayer_special_attack", "varbit", VarbitID.PVPTUT_SPECIAL_ATTACK_COMPLETE},
		{"pete_kayer_gear_switch", "varbit", VarbitID.PVPTUT_GEAR_SWITCH_COMPLETE},
		{"pete_kayer_combo_eating", "varbit", VarbitID.PVPTUT_COMBO_EATING_COMPLETE},
		{"pete_kayer_boss_medium", "varbit", VarbitID.PVPTUT_BOSS_MEDIUM_COMPLETE},
		{"pete_kayer_boss_expert", "varbit", VarbitID.PVPTUT_BOSS_EXPERT_COMPLETE},
		{"gnome_ball_score", "varbit", VarbitID.GNOMEBALL_SCORE},
	};

	/**
	 * What a counter's change means. Every key is in exactly one set; a key in none is "unverified".
	 * - TOTALS: completion totals that only rise with play (the game's own TOTAL_* counters, Doom clears per level,
	 *   Colosseum waves). Each rise is units played.
	 * - BALANCES: points or permits the player also spends, or a rating. A fall is a purchase or a loss, a rise is
	 *   points earned; never a unit count by itself.
	 * - Anything else, "unverified": the var's meaning comes from its gameval name only (for example Warriors' Guild
	 *   WARGUILD_GAMES is not the token count, tokens are item 8851; BARBASSAULT_QUEENKILLS_EXTRA may not be the whole
	 *   queen count). Its changes are sent for the rig and analysis but never counted as units (review 2026-11-04).
	 */
	static final Set<String> TOTALS = new HashSet<>(Arrays.asList(
		"doom_level_1_completions", "doom_level_2_completions", "doom_level_3_completions", "doom_level_4_completions",
		"doom_level_5_completions", "doom_level_6_completions", "doom_level_7_completions", "doom_level_8_completions",
		"doom_level_8_plus_completions", "colosseum_waves_completed", "jad_kills", "zuk_kills", "sol_heredit_kills",
		"doom_levels_total", "gauntlet_completions", "corrupted_gauntlet_completions", "fractured_archive_completions",
		"wintertodt_subdued", "tempoross_kills", "gotr_total", "cox_completions", "cox_cm_completions",
		"tob_completions", "tob_hard_completions", "tob_entry_completions", "toa_entry_completions",
		"toa_completions", "toa_expert_completions", "nightmare_kills", "phosani_kills", "nex_kills", "zalcano_kills",
		"moons_chests", "yama_kills", "maggot_king_kills", "mixology_orders"));

	static final Set<String> BALANCES = new HashSet<>(Arrays.asList(
		"tempoross_permits", "tithe_points", "ba_attacker_base", "ba_collector_base", "ba_healer_base",
		"ba_defender_base", "soul_wars_zeal", "soul_wars_zeal_tokens", "giants_foundry_points", "mixology_mox_points",
		"mixology_aga_points", "mixology_lye_points", "nmz_reward_points", "bounty_hunter_points",
		"pvp_arena_reward_points", "vale_totems_research_points", "lms_rank", "pvp_arena_rank_points",
		"mta_telekinetic_points", "mta_alchemist_points", "mta_enchantment_points", "mta_graveyard_points",
		"pest_control_points", "lms_points", "volcanic_mine_points", "mahogany_homes_points", "pvp_arena_shop_points"));

	static String kindOf(String key)
	{
		return TOTALS.contains(key) ? "total" : BALANCES.contains(key) ? "balance" : "unverified";
	}

	/**
	 * Balances the game shows only in an interface, never in a var: {key, packed widget id}. Read only while that
	 * widget is on screen; the first sight after a reset is the baseline, and a later sight that differs is one
	 * change. Ids from RuneLite gameval InterfaceID (1.13.6); which screen shows which balance needs the rig.
	 * Magic Training Arena's four room totals are the same widgets RuneLite's own overlay reads.
	 */
	static final Object[][] WIDGET_COUNTERS = {
		{"mta_telekinetic_points", 36241418}, {"mta_alchemist_points", 36241419},
		{"mta_enchantment_points", 36241420}, {"mta_graveyard_points", 36241421},
		{"pest_control_points", 26673157},	// PestLanderOverlay.PEST_LANDER_OVER_POINTS (the lander)
		{"pest_control_points", 15925256},	// PestRewardshop.TOTAL (the reward shop)
		{"lms_points", 42270728},			// BrRewardShop.TOTAL
		{"volcanic_mine_points", 40108037},	// FossilVolcanicShop.POINTS
		{"mahogany_homes_points", 44105736},	// ConstructionContractShop.TOTAL
		{"pvp_arena_shop_points", 50397190},	// PvpArenaRewards.TOTAL
		{"volcanic_mine_game_points", 40042505},	// FossilVolcanicMine.POINTS (in a game; resets each game)
	};

	static final Set<String> WIDGET_KEYS = new HashSet<>();
	static
	{
		for (Object[] c : WIDGET_COUNTERS) WIDGET_KEYS.add((String) c[0]);
	}

	/** A widget's whole text: an optional word label and colon, then one plain whole number with correct thousands commas. */
	private static final Pattern WIDGET_NUMBER =
		Pattern.compile("^(?:[A-Za-z][A-Za-z ']{0,40}:\\s*)?(\\d{1,3}(?:,\\d{3})*|\\d+)$");

	/**
	 * The number a balance widget shows ("Points: 1,250" or "1250"), or null. Any other shape stays unknown: a sign,
	 * a decimal or suffix ("1.5K"), a fraction ("1 / 500"), stray commas or extra text never give a number (review
	 * 2026-11-04: a lenient first-number parse turned those into plausible wrong balances).
	 */
	static Integer widgetNumber(String text)
	{
		if (text == null) return null;
		Matcher m = WIDGET_NUMBER.matcher(text.replaceAll("<[^>]*>", "").trim());
		if (!m.matches()) return null;
		try
		{
			long v = Long.parseLong(m.group(1).replace(",", ""));
			return v > Integer.MAX_VALUE ? null : (int) v;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}


	/**
	 * Follows ACTIVITY_COUNTERS and reports each change once, as {key, from, to}. The caller resets it on every
	 * logout, hop, disconnect and grant change, so two accounts are never compared. After a reset the reads of the
	 * first SETTLE_TICKS ticks are only the baseline. That is a heuristic against vars still arriving at login, not
	 * proof that they have all loaded: a var that arrives later than that shows as one change. So a change is an
	 * observation of the server-set value at that tick, not proof that the unit was played then; the rig has not yet
	 * verified login synchronization. After that, every
	 * change is reported, from 0 too (a first Doom clear). The server treats a change as a unit only together with
	 * the account, time and the rest of the order evidence.
	 */
	static final class ActivityCounters
	{
		static final int SETTLE_TICKS = 2;
		private final Map<String, Integer> last = new LinkedHashMap<>();
		private int ticks;

		void reset()
		{
			last.clear();
			ticks = 0;
		}

		List<Map<String, Object>> tick(Map<String, Integer> now)
		{
			List<Map<String, Object>> out = new ArrayList<>();
			if (now == null) return out;
			boolean settling = ticks++ < SETTLE_TICKS;
			for (Map.Entry<String, Integer> e : now.entrySet())
			{
				Integer v = e.getValue();
				if (v == null) continue;
				Integer prev = last.put(e.getKey(), v);
				if (prev == null && WIDGET_KEYS.contains(e.getKey()))
				{
					// A shop may be opened once only: its first sight is sent as an observation of the balance,
					// with no "from", so it is never read as points earned or spent (review 2026-11-04).
					Map<String, Object> row = Fields.of(
						"key", e.getKey(), "to", v, "kind", kindOf(e.getKey()), "seen", "first");
					out.add(row);
					continue;
				}
				if (settling || prev == null || prev.intValue() == v.intValue()) continue;
				Map<String, Object> row = Fields.of(
					"key", e.getKey(), "from", prev, "to", v, "kind", kindOf(e.getKey()));
				out.add(row);
			}
			return out;
		}
	}

	/** Follows the game's completed slayer task counters and reports each completion once. */
	static final class SlayerTasks
	{
		private Integer lastStreak;
		private Integer lastTarget;
		private Integer lastRemaining;

		void reset()
		{
			lastStreak = null;
			lastTarget = null;
			lastRemaining = null;
		}

		/**
		 * One tick's reads: the completed-tasks counter (varbit 4069), the task target (varp 395) and the kills left
		 * (varp 394). Returns a slayer_task body when the counter went up by exactly one since the last read; a first
		 * read, a jump or a drop (another account, a reset) only sets the baseline.
		 */
		Map<String, Object> tick(Integer streak, Integer target, Integer remaining)
		{
			Map<String, Object> out = null;
			if (streak != null && lastStreak != null && streak == lastStreak + 1)
			{
				out = new LinkedHashMap<>();
				out.put("tasks_completed", streak);
				out.put("task_target_id", lastTarget != null && lastTarget > 0 ? lastTarget : null);
			}
			if (streak != null) lastStreak = streak;
			// Keep the last real task: the game clears the target when the task ends, on the same tick or the next.
			if (target != null && target > 0)
			{
				lastTarget = target;
				lastRemaining = remaining;
			}
			return out;
		}

		Integer lastRemaining()
		{
			return lastRemaining;
		}
	}
}
