package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
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
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.RuneScapeProfileType;

/** The account snapshot: skills, quests, diaries, containers, storage and game vars, built from the live client. */
@Slf4j
@RequiredArgsConstructor
final class SnapshotBuilder
{
	private final AccountConnectPlugin plugin;

	static final int COINS_ID = 995;

	// VarPlayer and varbit ids are gameval ids; SnapshotVarIdsTest pins each one against its named constant.
	static final int VARP_SLAYER_TARGET = 395;    // current task creature id
	static final int VARP_SLAYER_COUNT = 394;     // remaining kills on current task
	static final int VARP_QUEST_POINTS = 101;     // gameval QP
	static final int VARBIT_CA_POINTS = 14815;    // gameval CA_POINTS
	// Raw game-value blocks, in wire order: name and id pairs. A negative id is that varp, any other a varbit.
	static final Object[] CA_TIERS = {"easy", 12863, "medium", 12864, "hard", 12865, "elite", 12866, "master", 12867,
		"grandmaster", 12868};
	// unlocks1/unlocks2 hold the rewards bought after the first varp filled; the bitfields are decoded server-side.
	static final Object[] SLAYER = {"points", 4068, "streak", 4069, "task_target_id", -395, "task_remaining", -394,
		"unlocks_raw", -1076, "blocked_raw", -1096, "unlocks1_raw", -1344, "unlocks2_raw", -5587};
	// Kourend house favour (server divides by 10 for the %).
	static final Object[] KOUREND_FAVOUR = {"arceuus", 4896, "hosidius", 4895, "lovakengj", 4898, "piscarilius", 4899,
		"shayzien", 4894};
	// NMZ and Tithe points, then own milestones the game keeps in vars (gameval ids, runelite-api 1.13.4). LMS has no
	// points var, so LMS points are omitted rather than invented. Boss kill counts and minigame scores are not in vars:
	// the server reads those from the official hiscores.
	static final Object[] MINIGAME_POINTS = {"nmz", 3949, "tithe", 4893,
		"ba_attacker_base", 4759, "ba_collector_base", 4760, "ba_healer_base", 4761, "ba_defender_base", 4762,
		"ba_attacker_extra", 4763, "ba_collector_extra", 4764, "ba_healer_extra", 4765, "ba_defender_extra", 4766,
		"colosseum_highest_wave", 11410, "hallowed_final_chests", 10423, "hallowed_normal_chests", 10446,
		"tempoross_permits", 11936, "soul_wars_zeal", -2871, "giants_foundry", -3436,
		"mixology_lye", -4414, "mixology_aga", -4415, "mixology_mox", -4416};
	// Tool leprechaun storage varbits, raw (the server combines the extra_* parts).
	static final Object[] TOOL_LEPRECHAUN = {"rake", 1435, "dibber", 1436, "spade", 1437, "secateurs", 1438,
		"magic_secateurs", 1848, "watering_can", 1439, "trowel", 1440, "plant_cure", 6268, "buckets", 1441,
		"extra_buckets", 4731, "extra2_buckets", 6265, "compost", 1442, "extra_compost", 6266, "supercompost", 1443,
		"extra_supercompost", 6267, "ultracompost", 5732, "bottomless_bucket_type", 7915,
		"bottomless_bucket_quantity", 7916, "extra_rakes", 8357, "extra_dibbers", 8358, "extra_secateurs", 8359,
		"extra_trowels", 8360, "extra_spades", 8361};
	// Boss kill counts, raid completions and clue completions the game keeps in varps, raw; the gameval names are in
	// docs/ACCOUNT_LISTING.md.
	static final int[] KILL_COUNT_VARPS = {
		1111, 1112, 1116, 1354, 1503, 1504, 1505, 1506, 1507, 1508, 1509, 1510, 1511, 1512, 1513, 1514, 1515, 1516,
		1517, 1518, 1519, 1520, 1521, 1522, 1523, 1524, 1525, 1526, 1527, 1528, 1529, 1532, 1533, 1534, 1585, 1661,
		1669, 1691, 1733, 1735, 1748, 2074, 2075, 2201, 2221, 2233, 2352, 2353, 2354, 2664, 2671, 2934, 3057, 3058,
		3269, 3397, 3645, 3646, 3647, 3752, 3761, 3762, 3763, 3967, 3968, 3969, 3970, 3971, 3972, 3973, 3974, 4079,
		4148, 4149, 4150, 4186, 4187, 4260, 4403, 4404, 4648, 4701, 4825, 4945, 5407, 5408, 5614, 5712, 5894};
	// Rune pouch slots: {rune type varbit, amount varbit}.
	static final int[][] RUNE_POUCH = {{29, 1624}, {1622, 1625}, {1623, 1626}, {14285, 14286}, {15373, 15375}, {15374, 15376}};
	// Off-book containers sent for every linked player, in wire order (gameval ids, so they match
	// ItemContainerChanged.getContainerId()). Bonds and quiver ammo are real wealth a bank, inventory and
	// equipment snapshot cannot see (gap audit 2026-07-18).
	private static final Object[][] CONTAINERS = {
		{"looting_bag", net.runelite.api.gameval.InventoryID.LOOTING_BAG},
		{"seed_vault", net.runelite.api.gameval.InventoryID.SEED_VAULT},
		{"bonds_pouch", net.runelite.api.gameval.InventoryID.BONDS_POUCH},
		{"bonds_escrow", net.runelite.api.gameval.InventoryID.BONDS_ESCROW},
		{"quiver_ammo", net.runelite.api.gameval.InventoryID.DIZANAS_QUIVER_AMMO},
	};
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

	// region -> {easy, medium, hard, elite} achievement-diary completion varbits (the Varbits.DIARY_* ids).
	static final Object[][] DIARIES = {
		{"ardougne", 4458, 4459, 4460, 4461}, {"desert", 4483, 4484, 4485, 4486}, {"falador", 4462, 4463, 4464, 4465},
		{"fremennik", 4491, 4492, 4493, 4494}, {"kandarin", 4475, 4476, 4477, 4478}, {"karamja", 3578, 3599, 3611, 4566},
		{"kourend", 7925, 7926, 7927, 7928}, {"lumbridge", 4495, 4496, 4497, 4498}, {"morytania", 4487, 4488, 4489, 4490},
		{"varrock", 4479, 4480, 4481, 4482}, {"western", 4471, 4472, 4473, 4474}, {"wilderness", 4466, 4467, 4468, 4469},
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
		// Keep freshness on the wire, but a passive poll alone must never trigger an upload; the same bank read with the
		// bank open or closed is no account change; capture health is not account state. Each block is copied, so the
		// snapshot itself keeps these fields.
		for (String[] nested : new String[][]{{"own_gameplay", "last_authoritative_read_ms"}, {"bank", "open_at_capture"}, {"source", "capture_health"}})
		{
			Object block = canonical.get(nested[0]);
			if (!(block instanceof Map)) continue;
			Map<String, Object> stable = new LinkedHashMap<>((Map<String, Object>) block);
			stable.remove(nested[1]);
			canonical.put(nested[0], stable);
		}
		return EventSpool.sha256Hex(plugin.gson.toJson(canonical));
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
	static final List<String> ACCOUNT_TYPES = Collections.unmodifiableList(Arrays.asList("NORMAL", "IRONMAN", "ULTIMATE_IRONMAN",
		"HARDCORE_IRONMAN", "GROUP_IRONMAN", "HARDCORE_GROUP_IRONMAN", "UNRANKED_GROUP_IRONMAN"));

	static String accountTypeName(int varbit)
	{
		return varbit >= 0 && varbit < ACCOUNT_TYPES.size() ? ACCOUNT_TYPES.get(varbit) : "UNKNOWN_" + varbit;
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
		int days = plugin.client.getVarpValue(VarPlayer.MEMBERSHIP_DAYS);
		account.put("members", accountMembers(onMembersWorld, varsSettled, days));
		account.put("membership_days", varsSettled && days >= 0 ? days : null);
		// Launched by the Jagex Launcher (a Jagex Account session): true, else unknown. The name itself is never sent.
		account.put("jagex_launcher", plugin.client.getLauncherDisplayName() != null ? Boolean.TRUE : null);
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
		// read_count: how many quests the client could read, so a quest missing from states is known to be unread, not absent.
		snap.put("quests", Fields.of("states", states, "read_count", states.size(), "known_count", Quest.values().length,
			"quest_points", plugin.client.getVarpValue(VARP_QUEST_POINTS)));

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
		Map<String, Object> ca = vars(CA_TIERS);
		// Every task's completion bit (one bit per task id), so a task-gated item is known, not only the tier.
		List<Integer> caWords = new ArrayList<>();
		for (int varp : AccountProgressCapture.CA_TASK_VARPS)
		{
			caWords.add(plugin.client.getVarpValue(varp));
		}
		ca.put("task_words", caWords);
		ca.put("points", plugin.client.getVarbitValue(VARBIT_CA_POINTS));
		snap.put("combat_achievements_raw", ca);
		snap.put("slayer", vars(SLAYER));

		// ---- rune pouch: raw type-index + amount per slot (server maps type-index -> rune item) ----
		List<Map<String, Object>> runePouch = new ArrayList<>();
		for (int[] slot : RUNE_POUCH)
		{
			int type = plugin.client.getVarbitValue(slot[0]);
			int amt = plugin.client.getVarbitValue(slot[1]);
			if (type > 0 && amt > 0)
			{
				runePouch.add(Fields.of("type_raw", type, "amount", amt));
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
		if (bankSynced)
		{
			// The layout beside the unchanged items: every slot items skips before the unused tail, as [slot, id] for a
			// placeholder and [slot, -1] for an empty one, and the sizes of tabs 1-9.
			List<List<Integer>> placeholders = new ArrayList<>();
			int end = bank.length;
			while (end > 0 && (bank[end - 1] == null || bank[end - 1].getId() <= 0 || bank[end - 1].getQuantity() <= 0)) end--;
			for (int slot = 0; slot < end; slot++)
			{
				Item item = bank[slot];
				if (!realItem(item))
				{
					boolean held = item != null && item.getId() > 0 && item.getQuantity() > 0;
					placeholders.add(Arrays.asList(slot, held ? item.getId() : -1));
				}
			}
			List<Integer> tabs = new ArrayList<>();
			for (int tab = 0; tab < 9; tab++)
			{
				tabs.add(plugin.client.getVarbitValue(VarbitID.BANK_TAB_1 + tab));
			}
			bankBlock.put("tab_sizes", tabs);
			bankBlock.put("placeholders", placeholders);
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
		for (Object[] c : CONTAINERS)
		{
			addContainerSnapshot(snap, (String) c[0], plugin.client.getItemContainer((int) c[1]));
		}
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
			Map<String, Object> house = plugin.house.layout;
			if (house != null) snap.put("poh_layout", house);
		}

		// Group Ironman shared storage is not built into the snapshot until its data policy is approved (0.7.69): a block
		// built here but stripped at send made every sync look changed (audit 0.7.69 P2). The unused builder was removed;
		// re-enabling group storage is a new change, not a switch.

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
		// Ingest rebuilds collection_log with fixed keys, so the page counters are their own block.
		if (collection != null) snap.put("collection_log_pages", collection.pages());

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
		snap.put("spellbook", plugin.client.getVarbitValue(4070));          // 0 standard / 1 ancient / 2 lunar / 3 arceuus
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

		snap.put("kourend_favour", vars(KOUREND_FAVOUR));
		snap.put("minigame_points", vars(MINIGAME_POINTS));
		// Read like account.members: a login read can be zeros still on their way.
		if (varsSettled)
		{
			snap.put("tool_leprechaun_raw", vars(TOOL_LEPRECHAUN));
			Map<String, Object> kills = new LinkedHashMap<>();
			for (int id : KILL_COUNT_VARPS) kills.put(Integer.toString(id), plugin.client.getVarpValue(id));
			snap.put("kill_counts_raw", kills);
		}

		return snap;
	}

	/** One raw block from name and id pairs (a negative id is that varp, any other a varbit), in table order. */
	private Map<String, Object> vars(Object[] pairs)
	{
		Map<String, Object> values = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			int id = (int) pairs[i + 1];
			values.put((String) pairs[i], id < 0 ? plugin.client.getVarpValue(-id) : plugin.client.getVarbitValue(id));
		}
		return values;
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
		return Fields.of("region_id", wp.getRegionID(), "plane", wp.getPlane());
	}

	// ---- container helpers ----

	Map<String, Object> itemMap(int id, int qty)
	{
		return Fields.of("id", id, "qty", qty);
	}

	/** itemMap variant for quantities that can exceed int range (bank diffs, large coin/token stacks). */
	Map<String, Object> itemMapLong(int id, long qty)
	{
		return Fields.of("id", id, "qty", qty);
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
		return Fields.of("items", items, "value_gp", estimate,
			"observed_at", observedAtSeconds(net.runelite.api.gameval.InventoryID.SEED_BOX),
			"basis", "client_cached_seed_box", "complete", false,
			"status", items.isEmpty() ? "observed_empty_cache" : "cached_items", "valuation_basis", "client_item_price_estimate");
	}
}
