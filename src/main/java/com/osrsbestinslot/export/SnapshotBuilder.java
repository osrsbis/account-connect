package com.osrsbestinslot.export;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.RuneScapeProfileType;

/** The account snapshot: skills, quests, diaries, containers, storage and game vars, built from the live client. */
@Slf4j
final class SnapshotBuilder
{
	private final AccountConnectPlugin plugin;

	SnapshotBuilder(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	static final int COINS_ID = 995;

	// VarPlayer ids verified against RuneLite gameval.VarPlayerID (no named legacy constant).
	static final int VARP_SLAYER_TARGET = 395;    // current task creature id
	static final int VARP_SLAYER_COUNT = 394;     // remaining kills on current task
	private static final int VARP_SLAYER_UNLOCKS = 1076;  // bitfield (decode server-side)
	private static final int VARP_SLAYER_BLOCKED = 1096;  // bitfield (decode server-side)
	private static final int VARP_SLAYER_UNLOCKS1 = 1344; // gameval SLAYER_REWARDS_UNLOCKS1, bitfield
	private static final int VARP_SLAYER_UNLOCKS2 = 5587; // gameval SLAYER_REWARDS_UNLOCKS2, bitfield
	static final int VARP_QUEST_POINTS = 101;     // gameval QP
	static final int VARBIT_CA_POINTS = 14815;    // gameval CA_POINTS
	// {snapshot key, gameval VarbitID}
	private static final Object[][] MINIGAME_VARBITS = {
		{"ba_attacker_base", 4759}, {"ba_collector_base", 4760}, {"ba_healer_base", 4761}, {"ba_defender_base", 4762},
		{"ba_attacker_extra", 4763}, {"ba_collector_extra", 4764}, {"ba_healer_extra", 4765}, {"ba_defender_extra", 4766},
		{"colosseum_highest_wave", 11410}, {"hallowed_final_chests", 10423}, {"hallowed_normal_chests", 10446},
		{"tempoross_permits", 11936},
	};
	// {snapshot key, gameval VarPlayerID}
	private static final Object[][] MINIGAME_VARPS = {
		{"soul_wars_zeal", 2871}, {"giants_foundry", 3436},
		{"mixology_lye", 4414}, {"mixology_aga", 4415}, {"mixology_mox", 4416},
	};
	// Off-book snapshot containers (verified vs runelite-api 1.12.32 gameval enums, javap). gameval int ids so
	// they match ItemContainerChanged.getContainerId(); Group-Ironman shared storage has no gameval constant, so
	// its snapshot read uses the legacy InventoryID.GROUP_STORAGE overload of client.getItemContainer(...).
	private static final int LOOTING_BAG_CONTAINER_ID = net.runelite.api.gameval.InventoryID.LOOTING_BAG;	// 516
	private static final int SEED_VAULT_CONTAINER_ID = net.runelite.api.gameval.InventoryID.SEED_VAULT;		// 626
	private static final int BONDS_POUCH_CONTAINER_ID = net.runelite.api.gameval.InventoryID.BONDS_POUCH;	// 536
	private static final int BONDS_ESCROW_CONTAINER_ID = net.runelite.api.gameval.InventoryID.BONDS_ESCROW;	// 534
	private static final int QUIVER_AMMO_CONTAINER_ID = net.runelite.api.gameval.InventoryID.DIZANAS_QUIVER_AMMO; // 879
	/** Snapshot key -> container id for the player's own storage (broad capture batch 1). Order is the wire order. */
	static final Map<String, Integer> OWN_STORAGE_CONTAINERS;
	static
	{
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put("seed_box", net.runelite.api.gameval.InventoryID.SEED_BOX);					// 573
		m.put("tackle_box", net.runelite.api.gameval.InventoryID.TACKLE_BOX);				// 651
		m.put("forestry_kit", net.runelite.api.gameval.InventoryID.FORESTRY_KIT);			// 814
		m.put("huntsmans_kit", net.runelite.api.gameval.InventoryID.HUNTSMANS_KIT);			// 855
		m.put("barbarian_knapsack", net.runelite.api.gameval.InventoryID.BARBARIAN_KNAPSACK);	// 1012
		m.put("death_storage", net.runelite.api.gameval.InventoryID.DEATH_PERMANENT);		// 636
		m.put("gravestone", net.runelite.api.gameval.InventoryID.GRAVESTONE);				// 525
		m.put("poh_costume_room", net.runelite.api.gameval.InventoryID.POH_COSTUMES);		// 637
		m.put("poh_menagerie", net.runelite.api.gameval.InventoryID.POH_MENAGERIE_PETS);	// 517
		m.put("potion_storage_device", net.runelite.api.gameval.InventoryID.PREPOT_DEVICE_INV);	// 911
		// Boat cargo/net can be crew-visible. Do not read them until own-boat provenance is verified.
		OWN_STORAGE_CONTAINERS = Collections.unmodifiableMap(m);
	}

	// region -> {easy, medium, hard, elite} achievement-diary completion varbits (Varbits.DIARY_*).
	static final Object[][] DIARIES = {
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
	};
	/**
	 * FRESHNESS. When the client last saw each item container change (ItemContainerChanged), in ms. A container
	 * the game loaded but never changed this session has no entry, and its block then says observed_at null,
	 * never a made-up time. Lets the server tell a bank read this minute from one carried for hours.
	 */
	final Map<Integer, Long> containerObservedAtMs = new ConcurrentHashMap<>();
	/**
	 * The last populated bank read of this login, kept for the snapshot after the bank closes. The client
	 * drops the bank container on close, so the first upload after a visit sent no bank and the server
	 * carried the bank from before the visit: a noted withdraw then showed in the bank and the inventory at
	 * once (live run 2026-10-22). Cleared with containerObservedAtMs, so a hop or relog never reuses it.
	 */
	volatile Item[] lastBankItems;
	/** Minimum seconds between two snapshots that carry game_vars (the block is excluded from the change hash). */
	static final long GAME_VARS_MIN_INTERVAL_MS = 15L * 60L * 1000L;
	volatile long lastGameVarsSentMs;

	/**
	 * Change-detection hash: SHA-256 of the snapshot JSON minus the volatile fields. captured_at
	 * moves every build, and the wealth block derives from GE prices, which jitter without any
	 * real account change — the underlying items are still hashed via the container blocks.
	 */
	String canonicalHash(Map<String, Object> snapshot)
	{
		Map<String, Object> canonical = new LinkedHashMap<>(snapshot);
		canonical.remove("captured_at");
		canonical.remove("captured_at_ms");
		canonical.remove("trigger_event_id");	// names the build's cause, not account state
		canonical.remove("wealth");
		canonical.remove("game_vars");	// varps change constantly; game_vars rides on its own interval (gameVarsBlock)
		Object gameplay = canonical.get("own_gameplay");
		if (gameplay instanceof Map)
		{
			Map<String,Object> stableGameplay = new LinkedHashMap<>((Map<String,Object>) gameplay);
			// Keep freshness on the wire, but a passive poll alone must never trigger an upload.
			stableGameplay.remove("last_authoritative_read_ms");
			canonical.put("own_gameplay", stableGameplay);
		}
		Object bankBlock = canonical.get("bank");
		if (bankBlock instanceof Map && ((Map<?, ?>) bankBlock).containsKey("open_at_capture"))
		{
			// The same bank read with the bank open or closed is no account change.
			Map<String, Object> stableBank = new LinkedHashMap<>((Map<String, Object>) bankBlock);
			stableBank.remove("open_at_capture");
			canonical.put("bank", stableBank);
		}
		Object src = canonical.get("source");
		if (src instanceof Map)
		{
			Map<String, Object> stableSource = new LinkedHashMap<>((Map<String, Object>) src);
			stableSource.remove("capture_health");
			canonical.put("source", stableSource);
		}
		return sha256Hex(plugin.gson.toJson(canonical));
	}

	static String sha256Hex(String s)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e); // JVM-mandatory algorithm
		}
	}

	/** Keep a populated bank read. An empty read is not evidence (see advanceBankSession) and keeps the last one. */
	void rememberBank(ItemContainer bank)
	{
		Item[] items = bank == null ? null : bank.getItems();
		if (items != null && Arrays.stream(items).anyMatch(this::realItem))
		{
			lastBankItems = items.clone();
		}
	}

	void addContainerCounts(Map<Integer, Long> counts, ItemContainer c)
	{
		if (c == null)
		{
			return;
		}
		addItemCounts(counts, c.getItems());
	}

	private void addItemCounts(Map<Integer, Long> counts, Item[] items)
	{
		if (items == null)
		{
			return;
		}
		for (Item item : items)
		{
			if (realItem(item))
			{
				counts.merge(item.getId(), (long) item.getQuantity(), Long::sum);
			}
		}
	}

	private Map<Integer, Long> itemCounts(Item[] items)
	{
		Map<Integer, Long> counts = new LinkedHashMap<>();
		addItemCounts(counts, items);
		return counts;
	}

	/** The account-type varbit (gameval VarbitID.IRONMAN, legacy Varbits.ACCOUNT_TYPE). */
	static final int ACCOUNT_TYPE_VARBIT = VarbitID.IRONMAN;	// 1777

	/**
	 * Explicit map from the ACCOUNT_TYPE varbit to a stable name. Values 0-5 match the names of RuneLite's
	 * net.runelite.api.vars.AccountType (deprecated, so it is not referenced at runtime; AccountTypeTest pins
	 * the parity). 6 is unranked group ironman, which that enum lacks, so it gets its own name rather than
	 * being folded into GROUP_IRONMAN. Any other value is UNKNOWN_<n>: never guessed, never NORMAL.
	 */
	static String accountTypeName(int varbit)
	{
		switch (varbit)
		{
			case 0:
				return "NORMAL";
			case 1:
				return "IRONMAN";
			case 2:
				return "ULTIMATE_IRONMAN";
			case 3:
				return "HARDCORE_IRONMAN";
			case 4:
				return "GROUP_IRONMAN";
			case 5:
				return "HARDCORE_GROUP_IRONMAN";
			case 6:
				return "UNRANKED_GROUP_IRONMAN";
			default:
				return "UNKNOWN_" + varbit;
		}
	}

	/**
	 * account.members: TRUE on a members world. Elsewhere the membership-days varp decides, but only once the
	 * player's variables have settled after login: TRUE with days left, FALSE with none, null before that.
	 * An early read can be a zero still on its way, or a value left from the account logged in before.
	 */
	static Boolean accountMembers(boolean onMembersWorld, boolean varsSettled, int membershipDays)
	{
		if (onMembersWorld)
		{
			return Boolean.TRUE;
		}
		if (!varsSettled || membershipDays < 0)
		{
			return null;
		}
		return membershipDays > 0;
	}

	/**
	 * The game_vars block for this snapshot, or null. Null when not granted, before the player's skills have
	 * loaded (a login snapshot can read all zeros: 6 of 3,003 production snapshots on 2026-10-15 had every skill
	 * at level 0), or inside GAME_VARS_MIN_INTERVAL_MS of the last accepted block. The cadence applies
	 * to changed and unchanged arrays alike; failed requests obey snapshot retry backoff.
	 */
	Map<String, Object> gameVarsBlock(long nowMs)
	{
		if (!plugin.grants.broadCaptureEnabled() || plugin.client == null || plugin.client.getRealSkillLevel(Skill.HITPOINTS) <= 0)
		{
			return null;
		}
		if (lastGameVarsSentMs > 0 && nowMs - lastGameVarsSentMs < GAME_VARS_MIN_INTERVAL_MS)
		{
			return null;	// the interval runs from the last ACCEPTED snapshot that carried the block
		}
		Map<String, Object> gv = GameVarsCapture.encode(plugin.client.getVarps());
		if (gv == null)
		{
			return null;
		}
		gv.put("observed_at", nowMs / 1000L);
		gv.put("api", "runelite-varps");
		return gv;
	}

	Map<String, Object> buildSnapshot()
	{
		plugin.uploader.snapshotBuildSeq++;
		Map<String, Object> snap = new LinkedHashMap<>();
		snap.put("schema_v", AccountConnectPlugin.SCHEMA_V);
		// One clock read for both: captured_at_ms orders a snapshot against a same-second event, and
		// captured_at stays its whole second.
		long capturedAtMs = System.currentTimeMillis();
		plugin.lastSnapshotCapturedAtMs = capturedAtMs;
		snap.put("captured_at", capturedAtMs / 1000L);
		snap.put("captured_at_ms", capturedAtMs);

		Map<String, Object> source = Fields.of(
			"plugin", "osrsbis-export", "plugin_version", AccountConnectPlugin.PLUGIN_VERSION, "client", "runelite",
			"capture_health", plugin.events.captureHealthSnapshot());
		snap.put("source", source);
		Map<String, Object> plugins = plugin.grants.pluginInventoryBlock(capturedAtMs);
		if (plugins != null) snap.put("plugin_inventory", plugins);

		ItemContainer eqp = plugin.client.getItemContainer(InventoryID.EQUIPMENT);
		ItemContainer inv = plugin.client.getItemContainer(InventoryID.INVENTORY);
		ItemContainer liveBank = plugin.client.getItemContainer(InventoryID.BANK);
		// After a close the client has no bank container: report the last read of this login, timestamped by
		// the bank's last change, instead of nothing (which let the server carry an older bank forward).
		Item[] bank = liveBank != null ? liveBank.getItems() : lastBankItems;
		boolean bankSynced = bank != null;

		// ---- account meta ----
		Map<String, Object> account = Fields.of(
			"rsn", plugin.client.getLocalPlayer().getName(), "account_hash", Long.toString(plugin.client.getAccountHash()));
		// account_type is the IRON MODE (0.7.17). It used to be RuneScapeProfileType, which has no ironman
		// variants, so every main-game account shipped STANDARD and the site's ironman arm never fired.
		// profile_type keeps that old value (STANDARD/BETA/LEAGUE/...) under its own name.
		account.put("account_type", accountTypeName(plugin.client.getVarbitValue(ACCOUNT_TYPE_VARBIT)));
		account.put("profile_type", RuneScapeProfileType.getCurrent(plugin.client).name());
		// A members world proves an active sub. An f2p world proves nothing (members play there too), so the
		// account's own membership-days varp decides there, the same varp the RuneLite friends list uses for
		// its 400/200 limit. current_world_members stays the plain world-type signal.
		boolean onMembersWorld = plugin.client.getWorldType().contains(WorldType.MEMBERS);
		account.put("current_world_members", onMembersWorld);
		boolean varsSettled = plugin.client.getRealSkillLevel(Skill.HITPOINTS) > 0
			&& plugin.client.getTickCount() - plugin.worldLoginTick >= AccountConnectPlugin.MEMBERS_SETTLE_TICKS;
		account.put("members", accountMembers(onMembersWorld, varsSettled, plugin.client.getVarpValue(VarPlayer.MEMBERSHIP_DAYS)));
		snap.put("account", account);

		// ---- capture completeness ----
		Map<String, Object> flags = Fields.of(
			"equipment_synced", eqp != null, "inventory_synced", inv != null, "bank_synced", bankSynced,
			"collog_synced", plugin.clogSeen, "boss_kc_source", "none");
		snap.put("capture_flags", flags);

		// ---- game variables (server-granted, off by default) ----
		Map<String, Object> gv = gameVarsBlock(System.currentTimeMillis());
		if (gv != null)
		{
			snap.put("game_vars", gv);
		}

		Map<String,Object> gameplay = plugin.gameplayBlock();
		if (gameplay != null) snap.put("own_gameplay", gameplay);
		Map<String, Object> progress = plugin.progressBlock();
		if (progress != null)
		{
			snap.put("account_progress", progress);
		}

		// ---- skills (real skills only; OVERALL excluded) ----
		Map<String, Object> skills = new LinkedHashMap<>();
		for (Skill s : Skill.values())
		{
			if ("Overall".equalsIgnoreCase(s.getName()))
			{
				continue;
			}
			Map<String, Object> sk = Fields.of(
				"xp", plugin.client.getSkillExperience(s), "level", plugin.client.getRealSkillLevel(s),
				"boosted", plugin.client.getBoostedSkillLevel(s));
			skills.put(s.getName().toLowerCase(), sk);
		}
		snap.put("skills", skills);
		snap.put("total_level", plugin.client.getTotalLevel());
		snap.put("combat_level", plugin.client.getLocalPlayer().getCombatLevel());

		// ---- quests: per-quest state ----
		Map<String, Object> states = new LinkedHashMap<>();
		for (Quest q : Quest.values())
		{
			try
			{
				states.put(q.name(), q.getState(plugin.client).name());
			}
			catch (Exception e)
			{
				log.debug("Unable to read quest state {}: {}", q.name(), e.toString());
			}
		}
		Map<String, Object> quests = new LinkedHashMap<>();
		quests.put("states", states);
		// How many quests the client could read, so a quest missing from states is known to be unread, not absent.
		quests.put("read_count", states.size());
		quests.put("known_count", Quest.values().length);
		quests.put("quest_points", plugin.client.getVarpValue(VARP_QUEST_POINTS));
		snap.put("quests", quests);

		// ---- achievement diaries: raw completion varbit per region/tier ----
		Map<String, Object> diaries = new LinkedHashMap<>();
		for (Object[] d : DIARIES)
		{
			Map<String, Object> tiers = Fields.of(
				"easy", plugin.client.getVarbitValue((int) d[1]), "medium", plugin.client.getVarbitValue((int) d[2]),
				"hard", plugin.client.getVarbitValue((int) d[3]), "elite", plugin.client.getVarbitValue((int) d[4]));
			diaries.put((String) d[0], tiers);
		}
		snap.put("diaries_raw", diaries);

		// ---- combat achievements: raw per-tier varbit ----
		Map<String, Object> ca = Fields.of(
			"easy", plugin.client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_EASY),
			"medium", plugin.client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM),
			"hard", plugin.client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_HARD),
			"elite", plugin.client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE),
			"master", plugin.client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER),
			"grandmaster", plugin.client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER));
		// Every task's completion bit (one bit per task id), so a task-gated item is known, not only the tier.
		List<Integer> caWords = new ArrayList<>();
		for (int varp : AccountProgressCapture.CA_TASK_VARPS)
		{
			caWords.add(plugin.client.getVarpValue(varp));
		}
		ca.put("task_words", caWords);
		ca.put("points", plugin.client.getVarbitValue(VARBIT_CA_POINTS));
		snap.put("combat_achievements_raw", ca);

		// ---- slayer ----
		Map<String, Object> slayer = Fields.of(
			"points", plugin.client.getVarbitValue(Varbits.SLAYER_POINTS),
			"streak", plugin.client.getVarbitValue(Varbits.SLAYER_TASK_STREAK),
			"task_target_id", plugin.client.getVarpValue(VARP_SLAYER_TARGET),
			"task_remaining", plugin.client.getVarpValue(VARP_SLAYER_COUNT),
			"unlocks_raw", plugin.client.getVarpValue(VARP_SLAYER_UNLOCKS),
			"blocked_raw", plugin.client.getVarpValue(VARP_SLAYER_BLOCKED));
		// The later reward words: unlocks1/unlocks2 hold the rewards bought after the first varp filled.
		slayer.put("unlocks1_raw", plugin.client.getVarpValue(VARP_SLAYER_UNLOCKS1));
		slayer.put("unlocks2_raw", plugin.client.getVarpValue(VARP_SLAYER_UNLOCKS2));
		snap.put("slayer", slayer);

		// ---- rune pouch: raw type-index + amount per slot (server maps type-index -> rune item) ----
		int[][] rpSlots = {
			{Varbits.RUNE_POUCH_RUNE1, Varbits.RUNE_POUCH_AMOUNT1},
			{Varbits.RUNE_POUCH_RUNE2, Varbits.RUNE_POUCH_AMOUNT2},
			{Varbits.RUNE_POUCH_RUNE3, Varbits.RUNE_POUCH_AMOUNT3},
			{Varbits.RUNE_POUCH_RUNE4, Varbits.RUNE_POUCH_AMOUNT4},
			{Varbits.RUNE_POUCH_RUNE5, Varbits.RUNE_POUCH_AMOUNT5},
			{Varbits.RUNE_POUCH_RUNE6, Varbits.RUNE_POUCH_AMOUNT6},
		};
		List<Map<String, Object>> runePouch = new ArrayList<>();
		for (int[] s : rpSlots)
		{
			int type = plugin.client.getVarbitValue(s[0]);
			int amt = plugin.client.getVarbitValue(s[1]);
			if (type > 0 && amt > 0)
			{
				Map<String, Object> m = Fields.of("type_raw", type, "amount", amt);
				runePouch.add(m);
			}
		}
		snap.put("rune_pouch_raw", runePouch);

		// ---- grand exchange: active buy/sell offers ----
		List<Map<String, Object>> geOffers = new ArrayList<>();
		long geEscrowGp = 0L;	// wealth locked in GE offers (see the per-offer accumulation below)
		GrandExchangeOffer[] offers = plugin.client.getGrandExchangeOffers();
		if (offers != null)
		{
			for (int i = 0; i < offers.length; i++)
			{
				GrandExchangeOffer o = offers[i];
				if (o == null || o.getState() == GrandExchangeOfferState.EMPTY)
				{
					continue;
				}
				Map<String, Object> m = Fields.of(
					"slot", i, "item_id", o.getItemId(), "state", o.getState().name(),
					"total_qty", o.getTotalQuantity(), "transferred_qty", o.getQuantitySold(),
					"price_per_item", o.getPrice(), "spent", o.getSpent());
				geOffers.add(m);
				// GE escrow (gap audit 2026-07-18): wealth locked in the GE that bank+inv can't see — a placed
				// buy removes gp, an in-flight sell removes items, and either reads as a FALSE leak without this.
				// Buy-side: the full committed gp (bought-but-uncollected items stand in for the spent part).
				// Sell-side: remaining items at GE value + uncollected proceeds. Approximation is deliberate —
				// partial mid-offer collection can briefly double-count, and the reconcile engine already
				// downgrades confidence on GE-active windows.
				GrandExchangeOfferState st = o.getState();
				boolean buySide = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
					|| st == GrandExchangeOfferState.CANCELLED_BUY;
				if (buySide)
				{
					geEscrowGp += (long) o.getTotalQuantity() * o.getPrice();
				}
				else
				{
					long remaining = (long) o.getTotalQuantity() - o.getQuantitySold();
					if (remaining > 0 && plugin.itemManager != null)
					{
						geEscrowGp += remaining * plugin.itemManager.getItemPrice(o.getItemId());
					}
					geEscrowGp += o.getSpent();	// proceeds not yet collected
				}
			}
		}
		snap.put("ge_offers", geOffers);

		// ---- worn equipment: slot -> {id, qty} ----
		Map<String, Object> equipment = new LinkedHashMap<>();
		if (eqp != null)
		{
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				Item item = eqp.getItem(slot.getSlotIdx());
				if (item != null && item.getId() > 0)
				{
					equipment.put(slot.name().toLowerCase(), itemMap(item.getId(), item.getQuantity()));
				}
			}
		}
		snap.put("equipment", equipment);

		// ---- inventory + bank ----
		snap.put("inventory", itemList(inv));
		Map<String, Object> bankBlock = Fields.of(
			"items", bankSynced ? itemList(bank) : new ArrayList<>(),
			"value_gp", bankSynced ? containerValue(bank) : 0L);
		List<long[]> manifest = bankSynced ? BankVisitManifest.canonical(itemCounts(bank)) : null;
		if (manifest != null && manifest.size() == ((List<?>) bankBlock.get("items")).size())
		{
			// The same canonical list and hash as a bank visit, so the server can match this bank to a visit and
			// check the list is whole. A bank with one item in two slots would not match its items, so it has neither.
			bankBlock.put("manifest_sha256", BankVisitManifest.sha256(manifest));
			bankBlock.put("item_stacks", manifest.size());
		}
		// An open bank's live read that no change of this open has confirmed yet has no known time: the last
		// change's time predates the open, and the bank may have changed while it was closed.
		bankBlock.put("observed_at", bankSynced && !plugin.bankVisit.bankReadUnconfirmed(liveBank) ? observedAtSeconds(AccountConnectPlugin.BANK_CONTAINER_ID) : null);
		if (plugin.bankVisit.bankOpenAtCapture(liveBank))
		{
			bankBlock.put("open_at_capture", true);
		}
		snap.put("bank", bankBlock);

		// ---- off-book / niche containers (verified gameval ids; legacy enum for GIM shared storage). Included
		// only when non-null = opened at least once this session, so a normal snapshot never carries empty blocks.
		// Shared Group Ironman storage is deliberately excluded from this public artifact. ----
		addContainerSnapshot(snap, "looting_bag", plugin.client.getItemContainer(LOOTING_BAG_CONTAINER_ID));
		addContainerSnapshot(snap, "seed_vault", plugin.client.getItemContainer(SEED_VAULT_CONTAINER_ID));
		// Shared group storage is outside the public own-player transport contract.
		// Storage-visibility (gap audit 2026-07-18): real wealth a bank+inv+equip snapshot can't see — a bond
		// is ~30M+ parked invisibly; quiver ammo can be a large stacked value. False-residual sources closed.
		addContainerSnapshot(snap, "bonds_pouch", plugin.client.getItemContainer(BONDS_POUCH_CONTAINER_ID));
		addContainerSnapshot(snap, "bonds_escrow", plugin.client.getItemContainer(BONDS_ESCROW_CONTAINER_ID));
		addContainerSnapshot(snap, "quiver_ammo", plugin.client.getItemContainer(QUIVER_AMMO_CONTAINER_ID));
		// Broad capture batch 1: the player's OWN storage containers that the game sends once opened or
		// checked (ids from gameval, matched against runelite-api 1.13.1). Each block is present only once the
		// game has sent that container this session, and carries observed_at. Trade, duel, Deadman loot
		// and reward-chest containers are absent here; shared group storage is labelled separately below,
		// which the loot events already cover.
		if (plugin.grants.broadCaptureEnabled())
		{
			for (Map.Entry<String, Integer> c : OWN_STORAGE_CONTAINERS.entrySet())
			{
				addContainerSnapshot(snap, c.getKey(), plugin.client.getItemContainer(c.getValue()));
			}
		}

		Map<String, Object> groupStorage = groupStorageBlock();
		if (groupStorage != null) snap.put("group_storage", groupStorage);

		// ---- collection log: obtained item ids (partial until the player opens the clog tabs) ----
		Map<String, Object> collog = new LinkedHashMap<>();
		List<Integer> obtained = new ArrayList<>(plugin.clogObtained);
		Collections.sort(obtained);
		AccountProgressCapture collection = plugin.activeCollection();
		Map<String, Object> verified = collection == null ? null : collection.verifiedCollection(plugin.client);
		if (verified != null)
		{
			// A verified scan saw every obtained slot: send that whole list, so the server can keep only this one.
			obtained = new ArrayList<>();
			for (Object id : (List<?>) verified.get("obtained_item_ids"))
			{
				obtained.add((Integer) id);
			}
		}
		collog.put("synced", plugin.clogSeen || verified != null);
		if (collection != null)
		{
			collog.put("scan_status", collection.collectionScanStatus());
		}
		collog.put("obtained_count", obtained.size());
		collog.put("obtained_item_ids", obtained);
		if (verified != null)
		{
			collog.put("complete", true);
			collog.put("game_obtained_count", verified.get("game_obtained_count"));
			collog.put("game_total_count", verified.get("game_total_count"));
			collog.put("verified_at_ms", verified.get("verified_at_ms"));
		}
		snap.put("collection_log", collog);

		// ---- wealth (broken out: carried coins, inventory, worn gear, bank, total) ----
		long invValue = containerValue(inv);
		long eqpValue = containerValue(eqp);
		long bankValue = bankSynced ? containerValue(bank) : 0L;
		long coins = countItem(inv, COINS_ID) + countItem(eqp, COINS_ID);
		Map<String, Object> wealth = Fields.of(
			"coins_carried", coins, "inventory_gp", invValue, "equipment_gp", eqpValue,
			"bank_gp", bankSynced ? bankValue : null, "ge_escrow_gp", geEscrowGp,
			"net_worth_gp", bankSynced ? (invValue + eqpValue + bankValue + geEscrowGp) : null);
		snap.put("wealth", wealth);

		// ---- WAVE 5: live state (all ids javap-verified vs runelite-api 1.12.32; raw values, decoded server-side) ----
		snap.put("spellbook", plugin.client.getVarbitValue(Varbits.SPELLBOOK));          // 0 standard / 1 ancient / 2 lunar / 3 arceuus
		snap.put("attack_style", plugin.client.getVarpValue(VarPlayer.ATTACK_STYLE));    // 0..3 = the selected style slot
		snap.put("world", plugin.client.getWorld());
		snap.put("location", currentLocation());                                  // {region_id, plane} or null if unreadable

		List<String> activePrayers = new ArrayList<>();
		for (Prayer prayer : Prayer.values())
		{
			if (plugin.client.isPrayerActive(prayer))
			{
				activePrayers.add(prayer.name());
			}
		}
		snap.put("prayer_active", activePrayers);

		// Kourend house favour — 5 raw varbits (server divides by 10 for the %). Spelled FAVOR in the api enum.
		Map<String, Object> favour = Fields.of(
			"arceuus", plugin.client.getVarbitValue(Varbits.KOUREND_FAVOR_ARCEUUS),
			"hosidius", plugin.client.getVarbitValue(Varbits.KOUREND_FAVOR_HOSIDIUS),
			"lovakengj", plugin.client.getVarbitValue(Varbits.KOUREND_FAVOR_LOVAKENGJ),
			"piscarilius", plugin.client.getVarbitValue(Varbits.KOUREND_FAVOR_PISCARILIUS),
			"shayzien", plugin.client.getVarbitValue(Varbits.KOUREND_FAVOR_SHAYZIEN));
		snap.put("kourend_favour", favour);

		// Minigame points. NMZ + Tithe have clean point varbits; LMS has none in this jar (only IN_LMS state) so
		// LMS points are omitted rather than invented.
		Map<String, Object> minigames = Fields.of(
			"nmz", plugin.client.getVarbitValue(Varbits.NMZ_POINTS),
			"tithe", plugin.client.getVarbitValue(Varbits.TITHE_FARM_POINTS));
		// Own milestones the game keeps in vars (gameval ids, runelite-api 1.13.4), raw. Boss kill counts and
		// minigame scores are not in vars: the server reads those from the official hiscores.
		for (Object[] m : MINIGAME_VARBITS)
		{
			minigames.put((String) m[0], plugin.client.getVarbitValue((int) m[1]));
		}
		for (Object[] m : MINIGAME_VARPS)
		{
			minigames.put((String) m[0], plugin.client.getVarpValue((int) m[1]));
		}
		snap.put("minigame_points", minigames);

		return snap;
	}

	/** {region_id, plane} of the local player's world location, or null if it isn't currently readable. */
	Map<String, Object> currentLocation()
	{
		Player p = plugin.client.getLocalPlayer();
		if (p == null)
		{
			return null;
		}
		WorldPoint wp = p.getWorldLocation();
		if (wp == null)
		{
			return null;
		}
		Map<String, Object> loc = Fields.of("region_id", wp.getRegionID(), "plane", wp.getPlane());
		return loc;
	}

	// ---- container helpers ----

	Map<String, Object> itemMap(int id, int qty)
	{
		Map<String, Object> m = Fields.of("id", id, "qty", qty);
		return m;
	}

	/** itemMap variant for quantities that can exceed int range (bank diffs, large coin/token stacks). */
	Map<String, Object> itemMapLong(int id, long qty)
	{
		Map<String, Object> m = Fields.of("id", id, "qty", qty);
		return m;
	}

	/**
	 * A stack that is really held. A bank placeholder is its own item id with quantity 1 in the container (live run
	 * 2026-10-22: Bat bones 530 left placeholder 19269 x1), so it was sent as an item and moved in and out of the
	 * bank as a phantom deposit and withdrawal. Its composition names a placeholder template; nothing else does.
	 * Must run on the client thread (ItemManager composition). Without an ItemManager (tests) nothing is skipped.
	 */
	boolean realItem(Item item)
	{
		if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
		{
			return false;
		}
		ItemComposition c = plugin.itemManager == null ? null : plugin.itemManager.getItemComposition(item.getId());
		return c == null || c.getPlaceholderTemplateId() == -1;
	}

	/** Shared visible contents, never attributed to personal ownership or added to personal wealth. */
	Map<String, Object> groupStorageBlock()
	{
		if (!plugin.grants.broadCaptureEnabled() || plugin.client == null) return null;
		int mode = plugin.client.getVarbitValue(ACCOUNT_TYPE_VARBIT);
		if (mode < 4 || mode > 6) return null;
		ItemContainer container = plugin.client.getItemContainer(InventoryID.GROUP_STORAGE);
		if (container == null || observedAtSeconds(container.getId()) == null) return null;
		Map<String, Object> block = Fields.of(
			"items", itemList(container), "observed_at", observedAtSeconds(container.getId()),
			"ownership", "shared_visible_contents");
		return block;
	}

	private List<Map<String, Object>> itemList(ItemContainer c)
	{
		return itemList(c == null ? null : c.getItems());
	}

	private List<Map<String, Object>> itemList(Item[] items)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		if (items == null)
		{
			return out;
		}
		for (Item item : items)
		{
			if (realItem(item))
			{
				out.add(itemMap(item.getId(), item.getQuantity()));
			}
		}
		return out;
	}

	long countItem(ItemContainer c, int id)
	{
		if (c == null)
		{
			return 0L;
		}
		long n = 0L;
		for (Item item : c.getItems())
		{
			if (item != null && item.getId() == id)
			{
				n += item.getQuantity();
			}
		}
		return n;
	}

	private long containerValue(ItemContainer c)
	{
		return containerValue(c == null ? null : c.getItems());
	}

	private long containerValue(Item[] items)
	{
		if (items == null)
		{
			return 0L;
		}
		long v = 0L;
		for (Item item : items)
		{
			if (realItem(item))
			{
				v += (long) plugin.itemManager.getItemPrice(item.getId()) * item.getQuantity();
			}
		}
		return v;
	}

	/** Seconds of the last ItemContainerChanged for this container id, or null when none was seen. */
	Long observedAtSeconds(int containerId)
	{
		Long ms = containerObservedAtMs.get(containerId);
		return ms == null ? null : ms / 1000L;
	}

	/** Add a {items, value_gp} block for a container, but only when it is present (opened this session). */
	void addContainerSnapshot(Map<String, Object> snap, String key, ItemContainer c)
	{
		if (c == null)
		{
			return;
		}
		if (observedAtSeconds(c.getId()) == null)
		{
			// Present but no change seen since this login's start (observation times clear on every hop and
			// relog), so this is not a reading of this login: live 0.7.55 sent 3 of 6 looting bags and 4 of 12
			// seed vaults with observed_at null. If the client kept it from before, it may even be another
			// account's. No block: the server keeps this account's last dated reading as carried.
			return;
		}
		if ("seed_box".equals(key))
		{
			Map<String,Object> seed = seedBoxSnapshot(c);
			if (seed != null) snap.put(key, seed);
			return;
		}
		Map<String, Object> block = Fields.of(
			"items", itemList(c), "value_gp", containerValue(c), "observed_at", observedAtSeconds(c.getId()));
		snap.put(key, block);
	}

	/** Own fixed container only. Unknown or malformed cache never becomes an empty box. */
	private Map<String,Object> seedBoxSnapshot(ItemContainer c)
	{
		if (c.getId() != net.runelite.api.gameval.InventoryID.SEED_BOX) return null;
		Item[] raw = c.getItems();
		if (raw == null || raw.length > 4096) return null;
		Map<Integer,Long> totals = new TreeMap<>();
		for (Item item : raw)
		{
			if (item == null || item.getId() < -1 || item.getQuantity() < 0
				|| item.getId() <= 0 && item.getQuantity() > 0) return null;
			if (item.getId() > 0 && item.getQuantity() > 0)
				totals.merge(item.getId(), (long)item.getQuantity(), Long::sum);
		}
		List<Map<String,Object>> items = new ArrayList<>();
		Long estimate = 0L;
		for (Map.Entry<Integer,Long> entry : totals.entrySet())
		{
			items.add(itemMapLong(entry.getKey(), entry.getValue()));
			if (estimate != null)
			{
				long price = plugin.itemManager == null ? -1 : plugin.itemManager.getItemPrice(entry.getKey());
				if (price <= 0) estimate = null;
				else try { estimate = Math.addExact(estimate, Math.multiplyExact(price, entry.getValue())); }
				catch (ArithmeticException overflow) { estimate = null; }
				if (estimate != null && estimate > 9007199254740991L) estimate = null; // exact JSON integer range
			}
		}
		Map<String,Object> block = new LinkedHashMap<>(); block.put("items", items); block.put("value_gp", estimate);
		block.put("observed_at", observedAtSeconds(net.runelite.api.gameval.InventoryID.SEED_BOX));
		block.put("basis", "client_cached_seed_box"); block.put("complete", false);
		block.put("status", items.isEmpty() ? "observed_empty_cache" : "cached_items");
		block.put("valuation_basis", "client_item_price_estimate"); return block;
	}
}
