package com.osrsbestinslot.export;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;

/**
 * Minigame results from the game's own chat lines: a game or unit completed, and the points, tokens or rewards it
 * gave. Covers minigames that keep no persistent counter the plugin can read (see KillContext.ACTIVITY_COUNTERS).
 *
 * Pure: one GAMEMESSAGE/SPAM line in, one {@code minigame} event body out, or null. A line that names another player
 * is never matched. Every pattern is anchored to the whole cleaned line, and a number is read only from a group that
 * accepts digits with correct thousands commas, so an unexpected shape gives no event rather than a wrong number.
 * Each LINE has its source (RuneLite plugin file or wiki transcript); none is trusted on the rig yet, so the server
 * treats a row as an observation until the rig confirms that line.
 *
 * Body: {@code minigame} (key), {@code result} ("complete", "win", "loss", "reward", "points", "total", "start",
 * "screen"; "total" only restates a running total, it is never a new unit),
 * optional {@code count} (units or points this line reports), {@code total} (a running total the line states) and
 * {@code detail} (a fixed label from the pattern, never text copied from the line).
 */
final class MinigameLines
{
	private MinigameLines()
	{
	}

	/** Digits with correct thousands commas: "1,250" or "1250", never "1,,250" or "12,50". */
	static final String NUM = "(\\d{1,3}(?:,\\d{3})+|\\d+)";

	/**
	 * {minigame, result, detail or null, pattern, group of count or 0, group of total or 0}. Filled from the source-
	 * quoted research (evidence/minigame-signals-20261104.md); the comment above each entry gives its source.
	 */
	static final Object[][] LINES = {
		// TzHaar-Ket-Rak's Challenges: RuneLite ChatCommandsPlugin KILLCOUNT_PATTERN, test line "Your completion count
		// for TzHaar-Ket-Rak's First Challenge is: 1." (the kill_count gate also sends it; this row names the challenge).
		{"tzhaar_ket_rak", "complete", "first", "^Your completion count for TzHaar-Ket-Rak's First Challenge is: " + NUM + "\\.$", 0, 1},
		{"tzhaar_ket_rak", "complete", "second", "^Your completion count for TzHaar-Ket-Rak's Second Challenge is: " + NUM + "\\.$", 0, 1},
		{"tzhaar_ket_rak", "complete", "third", "^Your completion count for TzHaar-Ket-Rak's Third Challenge is: " + NUM + "\\.$", 0, 1},
		{"tzhaar_ket_rak", "complete", "fourth", "^Your completion count for TzHaar-Ket-Rak's Fourth Challenge is: " + NUM + "\\.$", 0, 1},
		{"tzhaar_ket_rak", "complete", "fifth", "^Your completion count for TzHaar-Ket-Rak's Fifth Challenge is: " + NUM + "\\.$", 0, 1},
		{"tzhaar_ket_rak", "complete", "sixth", "^Your completion count for TzHaar-Ket-Rak's Sixth Challenge is: " + NUM + "\\.$", 0, 1},
		// PvP Arena duels: ChatCommandsPlugin DUEL_ARENA_WINS_PATTERN and ScreenshotPlugin, test "You won! You have now
		// won 1,909 duels." The total is the running count the line states.
		{"pvp_arena", "win", null, "^You won! You have(?: now)? won (" + "\\d{1,3}(?:,\\d{3})+|\\d+|one" + ") duels?\\.$", 0, 1},
		{"pvp_arena", "loss", null, "^You were defeated! You have(?: now)? won (" + "\\d{1,3}(?:,\\d{3})+|\\d+|one" + ") duels?\\.$", 0, 1},
		{"pvp_arena", "total", "lost_total", "^You have(?: now)? lost (" + "\\d{1,3}(?:,\\d{3})+|\\d+|one" + ") duels?\\.$", 0, 1},
		// Brimhaven Agility Arena: KILLCOUNT_PATTERN "Total Ticket" branch, test "Your Agility Arena Total Ticket count
		// is: 206."
		{"brimhaven_agility_arena", "points", "tickets_total", "^Your Agility Arena Total Ticket count is: " + NUM + "\\.$", 0, 1},
		// Hallowed Sepulchre: ChatCommandsPlugin HS_KC_FLOOR_PATTERN / HS_KC_GHC_PATTERN (tests L797, L803).
		{"hallowed_sepulchre", "complete", "floor_1", "^You have completed Floor 1 of the Hallowed Sepulchre! Total completions: " + NUM + "\\.$", 0, 1},
		{"hallowed_sepulchre", "complete", "floor_2", "^You have completed Floor 2 of the Hallowed Sepulchre! Total completions: " + NUM + "\\.$", 0, 1},
		{"hallowed_sepulchre", "complete", "floor_3", "^You have completed Floor 3 of the Hallowed Sepulchre! Total completions: " + NUM + "\\.$", 0, 1},
		{"hallowed_sepulchre", "complete", "floor_4", "^You have completed Floor 4 of the Hallowed Sepulchre! Total completions: " + NUM + "\\.$", 0, 1},
		{"hallowed_sepulchre", "complete", "floor_5", "^You have completed Floor 5 of the Hallowed Sepulchre! Total completions: " + NUM + "\\.$", 0, 1},
		{"hallowed_sepulchre", "complete", "grand_coffin", "^You have opened the Grand Hallowed Coffin " + NUM + " times?!$", 0, 1},
		// Guardians of the Rift: GUARDIANS_OF_THE_RIFT_PATTERN "Amount of Rifts you have closed: N." A reward search is
		// NOT matched here: "You found some loot: " is also Wintertodt's and Tempoross's line, and RewardLootCapture
		// already sends it as a loot row, gated by region (review 2026-11-04).
		{"guardians_of_the_rift", "complete", null, "^Amount of Rifts you have closed: " + NUM + "\\.$", 0, 1},
		// Hunter Rumours: HUNTER_RUMOUR_KC_PATTERN, test "You have completed 77 rumours for the Hunter Guild."
		{"hunter_rumours", "complete", null, "^You have completed " + NUM + " rumours? for the Hunter Guild\\.$", 0, 1},
		// Forestry: WoodcuttingPlugin ANIMA_BARK_PATTERN "You've been awarded N Anima-infused bark."
		{"forestry", "reward", "anima_bark", "^You've been awarded " + NUM + " Anima-infused bark\\.$", 1, 0},
		// Giants' Foundry (wiki, Giants' Foundry page; mes): a finished sword, before the hand-in.
		{"giants_foundry", "complete", "sword_finished", "^You have successfully finished the sword\\. You should give it to Kovac for a reward\\.$", 0, 0},
		{"giants_foundry", "loss", "sword_damaged", "^This sword is too damaged to keep working\\. You should give it to Kovac and try again\\.$", 0, 0},
		// Pete Kayer's Challenges (wiki Transcript; mes): the special-attack challenge completion line.
		{"pete_kayer", "complete", "special_attack", "^You've completed the Special Attack challenge! You can continue to practice against your sparring partner, with or without movement and prayers, if you wish\\.$", 0, 0},
		// Volcanic Mine (wiki Transcript:Petrified Pete; mes): a game is about to start.
		{"volcanic_mine", "start", null, "^The mine is currently dormant, and will awaken within the next 30 seconds\\.$", 0, 0},
		// Barbarian Assault high gamble: LootTrackerPlugin (MESBOX in region 10039) and ScreenshotPlugin
		// "(reward)!<br>High level gamble count: N" (the reward name is not kept).
		{"barbarian_assault", "reward", "high_gamble", "^.{1,80}! High level gamble count: " + NUM + "$", 0, 1},
		// Brimhaven Agility Arena (wiki Transcript:Pirate Jackie the Fruit; mes): tickets exchanged.
		{"brimhaven_agility_arena", "reward", "tickets_exchanged", "^Pirate Jackie the Fruit converts " + NUM + " Agility Arena Tickets? into Agility Arena Vouchers?\\.?$", 1, 0},
		// Chompy bird hunting (wiki Transcript:Rantz; mes): a kill milestone reward.
		{"chompy", "reward", "milestone_xp", "^You've been awarded ranged experience for your relentless pursuit of chompies!$", 0, 0},
		// Forestry partial reward (wiki): the player was not there when the event started.
		{"forestry", "reward", "reduced", "^As you weren't chopping near the event when it started, you aren't eligible for full rewards\\.$", 0, 0},
		// Lines read from real 0.7.67 discovery samples (11-06/07), where the earlier research found no completion signal.
		// Mahogany Homes: a finished contract, before the reward is collected (no number on this line). Independent Hub
		// plugins parse both Mahogany lines as game messages (Mahogany Homes, Dude Where's My Stuff); none generates
		// them.
		{"mahogany_homes", "complete", null, "^[A-Z][a-z]{1,20} seems happy with your work\\. Talk to (?:him|her) for your reward\\.$", 0, 0},
		// Mahogany Homes: the running totals the game states when a reward is collected (total contracts; points not kept).
		{"mahogany_homes", "total", "contracts", "^You have completed " + NUM + " contracts with a total of " + NUM + " points\\.$", 0, 1},
	};

	private static final Pattern[] COMPILED = new Pattern[LINES.length];

	static
	{
		for (int i = 0; i < LINES.length; i++) COMPILED[i] = Pattern.compile((String) LINES[i][3]);
	}

	/**
	 * Dialogue and message boxes (NPC dialogue, the message box, the item box), same shape as LINES. Only fixed
	 * labels and numbers leave the plugin, never the dialogue text, which can contain the player's own name. Sources:
	 * Arceuus Library from RuneLite KourendLibraryPlugin (L349); the rest are wiki transcripts, whose "[number]"
	 * placeholders and tags are the wiki's own, so each needs a rig capture before it is trusted.
	 */
	static final Object[][] DIALOGUE = {
		{"arceuus_library", "reward", "book_delivered", "^.*(?:You can have this other book|please accept a token of my thanks\\.|Thanks, I'll get on with reading it\\.).*$", 0, 0},
		{"giants_foundry", "complete", "sword_handed_in", "^Sword completed in: .{1,20} at quality: " + NUM + ".*Total swords made in the Giants Foundry: " + NUM + "\\.?$", 1, 2},
		{"rogues_den", "complete", null, "^You gained: " + NUM + " Thieving Experience\\.? " + NUM + " Agility Experience\\.?$", 0, 0},
		{"sorceress_garden", "reward", "juice_xp", "^The man who is not Osman imparts some Thieving advice to you \\(" + NUM + " Thieving experience points\\) as a reward for the sq'irk juice\\.$", 1, 0},
		{"warriors_guild", "reward", "keg_balancing", "^The rather drunk Jimmy scribbles the tokens you've earned from Keg Balancing in the Ledger so that you can claim them from an official member of training staff\\.$", 0, 0},
		{"chompy", "total", "milestone", "^\\*\\*\\* Congratulations! " + NUM + " Chompies! \\*\\*\\*$", 0, 1},
		{"mage_arena", "complete", "guthix_cape", "^You kneel and chant to Guthix.*Suddenly a cape appears in your pack\\.$", 0, 0},
		{"mage_arena", "complete", "saradomin_cape", "^You kneel and chant to Saradomin.*Suddenly a cape appears in your pack\\.$", 0, 0},
		{"mage_arena", "complete", "zamorak_cape", "^You kneel and chant to Zamorak.*Suddenly a cape appears in your pack\\.$", 0, 0},
		{"mage_arena", "complete", "mage_arena_2", "^Kolodion takes your cape and imbues it with the power of the gods before handing it back\\.$", 0, 0},
		{"knight_waves", "complete", null, "^You receive access to the prayers Chivalry and Piety and .*$", 0, 0},
		{"pete_kayer", "complete", "challenge", "^Congratulations! You have completed the challenge!$", 0, 0},
		// Pest Control end of game, in the message box (real 0.7.67 discovery samples, dialogue surface; the same text is
		// read from Messagebox.TEXT by the Hub plugin Dude Where's My Stuff). The win states the points awarded for this
		// game (count), not a total.
		{"pest_control", "win", null, "^Congratulations! You managed to destroy all the portals! We've awarded you " + NUM + " Void Knight Commendation points?\\. Please also accept these coins as a reward\\.$", 1, 0},
		{"pest_control", "loss", null, "^The Void Knight was killed, another of our Order has fallen and that Island is lost\\.$", 0, 0},
	};

	/** Packed ids of the dialogue texts read: ChatLeft.TEXT, Messagebox.TEXT, Objectbox.TEXT, ObjectboxDouble.TEXT. */
	static final int[] DIALOGUE_TEXTS = {15138822, 15007747, 12648450, 720898};

	/**
	 * End-of-game and reward screens: {minigame, packed component id, label}. Only components that show a NUMBER are
	 * listed; name and message components (Rat Pits player names, the Clan Wars message) are never read. Ids are
	 * RuneLite gameval InterfaceID 1.13.6; what each number means is from its component name until the rig checks it.
	 */
	static final Object[][] SCREENS = {
		{"barbarian_assault", 32571425, "points_total"}, {"barbarian_assault", 32571439, "individual_points_total"},
		{"barbarian_assault", 32571441, "game_total"}, {"barbarian_assault", 32571443, "game_total_role"},
		{"barbarian_assault", 32571453, "main_points"}, {"barbarian_assault", 32571454, "main_total_points"},
		{"last_man_standing", 25886722, "wins"}, {"last_man_standing", 25886723, "kills"},
		{"last_man_standing", 25886724, "games"},
		{"castle_wars", 3604484, "saradomin_total_score"}, {"castle_wars", 3604486, "zamorak_total_score"},
		{"trouble_brewing", 27131924, "score"}, {"trouble_brewing", 27131925, "pieces_of_eight"},
		{"trouble_brewing", 27131926, "blue_team_final_score"}, {"trouble_brewing", 27131927, "red_team_final_score"},
		{"trouble_brewing", 27131929, "xp_reward"},
		{"soul_wars", 28508177, "players_killed"}, {"soul_wars", 28508181, "areas_captured"},
		{"soul_wars", 28508185, "avatar_damage"}, {"soul_wars", 28508189, "obelisk_sacrifices"},
		{"soul_wars", 28508193, "bones_buried"}, {"soul_wars", 28508197, "zeal_earned"},
		{"soul_wars", 28508201, "lifetime_zeal"}, {"soul_wars", 28508205, "zeal_tokens"},
		{"soul_wars", 28508224, "blue_score"}, {"soul_wars", 28508228, "red_score"},
		{"werewolf_skullball", 24838148, "time"}, {"werewolf_skullball", 24838149, "xp"},
		{"rat_pits", 18415623, "score_p1"}, {"rat_pits", 18415624, "score_p2"},
	};

	/** The interface groups of SCREENS, for the WidgetLoaded check, and the labels the public wire accepts. */
	static final Set<Integer> SCREEN_GROUPS = new HashSet<>();
	static final Set<String> SCREEN_LABELS = new HashSet<>();

	static
	{
		for (Object[] c : SCREENS)
		{
			SCREEN_GROUPS.add(((int) c[1]) >>> 16);
			SCREEN_LABELS.add((String) c[2]);
		}
	}

	/**
	 * One "screen" row per minigame from the texts read off its components (label to text), or null when no
	 * component showed a plain number. Numbers use the strict KillContext.widgetNumber, so an unknown shape is left
	 * out, never guessed.
	 */
	static Map<String, Object> screen(String minigame, Map<String, String> texts)
	{
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : texts.entrySet())
		{
			Integer v = KillContext.widgetNumber(e.getValue());
			if (v != null) values.put(e.getKey(), v);
		}
		if (values.isEmpty()) return null;
		Map<String, Object> out = Fields.of("minigame", minigame, "result", "screen", "screen", values);
		return out;
	}

	private static final Pattern[] DIALOGUE_COMPILED = new Pattern[DIALOGUE.length];

	static
	{
		for (int i = 0; i < DIALOGUE.length; i++) DIALOGUE_COMPILED[i] = Pattern.compile((String) DIALOGUE[i][3]);
	}

	/** The event body for one game line, or null. */
	static Map<String, Object> parse(String message)
	{
		return match(LINES, COMPILED, message);
	}

	/** The event body for one dialogue or message box text, or null. */
	static Map<String, Object> parseDialogue(String text)
	{
		return match(DIALOGUE, DIALOGUE_COMPILED, text);
	}

	private static Map<String, Object> match(Object[][] table, Pattern[] compiled, String message)
	{
		String text = KillTiming.clean(message);
		if (text.isEmpty()) return null;
		for (int i = 0; i < table.length; i++)
		{
			Matcher m = compiled[i].matcher(text);
			if (!m.matches()) continue;
			Map<String, Object> out = Fields.of("minigame", table[i][0], "result", table[i][1]);
			if (table[i][2] != null) out.put("detail", table[i][2]);
			Long count = number(m, (int) table[i][4]);
			Long total = number(m, (int) table[i][5]);
			if ((int) table[i][4] > 0 && count == null) return null;
			if ((int) table[i][5] > 0 && total == null) return null;
			if (count != null) out.put("count", count);
			if (total != null) out.put("total", total);
			return out;
		}
		return null;
	}

	private static Long number(Matcher m, int group)
	{
		if (group <= 0) return null;
		String g = m.group(group);
		if (g == null) return null;
		if (g.equalsIgnoreCase("one")) return 1L;
		try
		{
			long v = Long.parseLong(g.replace(",", ""));
			return v < 0 || v > Integer.MAX_VALUE ? null : v;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
