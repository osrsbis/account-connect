/*
 * OSRS Best in Slot — account-connect export plugin.
 *
 * Reads the logged-in account's game state and POSTs a versioned JSON snapshot to the
 * osrsbestinslot.com ingest endpoint, keyed by a link token the player pastes from the site.
 * The calculators read the snapshot back (by the same token) to auto-fill inputs/settings.
 *
 * Plumbing idioms (injected Client/Gson/OkHttpClient/ItemManager, @Schedule on the client thread,
 * async OkHttp enqueue) follow the open-source WikiSync plugin by andmcadams (BSD-2-Clause) as the
 * reference implementation — credited. The data model is original to the osrsbestinslot contract.
 *
 * schema_v stays 1: the live endpoint accepts schema_v<=1 and stores extra fields opaquely, so
 * adding fields here is plugin-only (no server change). Do NOT bump schema_v without also raising
 * the endpoint cap in the same change.
 *
 * Captured this pass (all via named RuneLite constants / clean APIs — no guessed ids):
 *   skills (xp/level/boosted), quests (Quest.getState), worn equipment + inventory, account meta,
 *   achievement diaries (48 Varbits.DIARY_*), combat-achievement tiers (6), slayer (points/streak/
 *   current task), bank + bank value (ItemManager prices, gated on the bank being opened), wealth.
 * Deferred (flagged false): collection log (cache walk), boss KC (no clean client field).
 * Diary/CA values are captured RAW (lossless ints) — the raw->done decode is done server-side once
 * the values are confirmed against a real account.
 */
package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
	name = "OSRS Best in Slot",
	description = "Connect your account to auto-fill the osrsbestinslot.com calculators (skills, quests, gear).",
	tags = {"osrs", "bis", "best in slot", "calculator", "gear"}
)
public class AccountConnectPlugin extends Plugin
{
	private static final int SCHEMA_V = 1;
	private static final String PLUGIN_VERSION = "0.4.0";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int COINS_ID = 995;

	// VarPlayer ids verified against RuneLite gameval.VarPlayerID (no named legacy constant).
	private static final int VARP_SLAYER_TARGET = 395;    // current task creature id
	private static final int VARP_SLAYER_COUNT = 394;     // remaining kills on current task
	private static final int VARP_SLAYER_UNLOCKS = 1076;  // bitfield (decode server-side)
	private static final int VARP_SLAYER_BLOCKED = 1096;  // bitfield (decode server-side)

	// Collection-log: the client fires script 4100 once per OBTAINED slot as the clog UI renders.
	// We accumulate those item ids; capture is partial until the player opens the relevant clog tabs.
	private static final int SCRIPT_CLOG_DRAW = 4100;
	private final java.util.Set<Integer> clogObtained = new java.util.HashSet<>();
	private boolean clogSeen = false;

	// region -> {easy, medium, hard, elite} achievement-diary completion varbits (Varbits.DIARY_*).
	private static final Object[][] DIARIES = {
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

	@Inject
	private Client client;

	@Inject
	private AccountConnectConfig config;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ItemManager itemManager;

	@Provides
	AccountConnectConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AccountConnectConfig.class);
	}

	/**
	 * Non-async @Schedule runs on the client thread, so reading client state below is safe.
	 * The network POST itself is async (OkHttp enqueue), so it never blocks the game.
	 */
	@Schedule(period = 30, unit = ChronoUnit.SECONDS)
	public void syncTask()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return; // no / malformed token configured yet
		}
		postSnapshot(token, buildSnapshot());
	}

	/** Each obtained collection-log slot fires script 4100 (arg[1] = item id) as the clog UI renders. */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != SCRIPT_CLOG_DRAW)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args != null && args.length > 1 && args[1] instanceof Integer)
		{
			clogObtained.add((Integer) args[1]);
			clogSeen = true;
		}
	}

	/** Clog state is per-account — clear it on hop / relog so we never mix two accounts. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case HOPPING:
			case LOGGING_IN:
			case CONNECTION_LOST:
				clogObtained.clear();
				clogSeen = false;
				break;
			default:
				break;
		}
	}

	private Map<String, Object> buildSnapshot()
	{
		Map<String, Object> snap = new LinkedHashMap<>();
		snap.put("schema_v", SCHEMA_V);
		snap.put("captured_at", System.currentTimeMillis() / 1000L);

		Map<String, Object> source = new LinkedHashMap<>();
		source.put("plugin", "osrsbis-export");
		source.put("plugin_version", PLUGIN_VERSION);
		source.put("client", "runelite");
		snap.put("source", source);

		ItemContainer eqp = client.getItemContainer(InventoryID.EQUIPMENT);
		ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		boolean bankSynced = bank != null;

		// ---- account meta ----
		Map<String, Object> account = new LinkedHashMap<>();
		account.put("rsn", client.getLocalPlayer().getName());
		account.put("account_hash", Long.toString(client.getAccountHash()));
		account.put("account_type", RuneScapeProfileType.getCurrent(client).name());
		// Membership is NOT readable client-side on an f2p world (no varbit/varp exists for the
		// account's subscription). Being on a members world DOES prove an active sub. So: true on a
		// members world, null (unknown — never false) otherwise. current_world_members is the honest
		// world-type signal; account membership can be inferred server-side from members content.
		boolean onMembersWorld = client.getWorldType().contains(WorldType.MEMBERS);
		account.put("current_world_members", onMembersWorld);
		account.put("members", onMembersWorld ? Boolean.TRUE : null);
		snap.put("account", account);

		// ---- capture completeness ----
		Map<String, Object> flags = new LinkedHashMap<>();
		flags.put("equipment_synced", eqp != null);
		flags.put("inventory_synced", inv != null);
		flags.put("bank_synced", bankSynced);   // bank container is null until the bank is opened in-session
		flags.put("collog_synced", clogSeen);     // true once the player has opened the clog UI this session
		flags.put("boss_kc_source", "none");      // deferred (no clean client field)
		snap.put("capture_flags", flags);

		// ---- skills (real skills only; OVERALL excluded) ----
		Map<String, Object> skills = new LinkedHashMap<>();
		for (Skill s : Skill.values())
		{
			if ("Overall".equalsIgnoreCase(s.getName()))
			{
				continue;
			}
			Map<String, Object> sk = new LinkedHashMap<>();
			sk.put("xp", client.getSkillExperience(s));
			sk.put("level", client.getRealSkillLevel(s));
			sk.put("boosted", client.getBoostedSkillLevel(s));
			skills.put(s.getName().toLowerCase(), sk);
		}
		snap.put("skills", skills);
		snap.put("total_level", client.getTotalLevel());
		snap.put("combat_level", client.getLocalPlayer().getCombatLevel());

		// ---- quests: per-quest state ----
		Map<String, Object> states = new LinkedHashMap<>();
		for (Quest q : Quest.values())
		{
			try
			{
				states.put(q.name(), q.getState(client).name());
			}
			catch (Exception e)
			{
				log.debug("Unable to read quest state {}: {}", q.name(), e.toString());
			}
		}
		Map<String, Object> quests = new LinkedHashMap<>();
		quests.put("states", states);
		snap.put("quests", quests);

		// ---- achievement diaries: raw completion varbit per region/tier ----
		Map<String, Object> diaries = new LinkedHashMap<>();
		for (Object[] d : DIARIES)
		{
			Map<String, Object> tiers = new LinkedHashMap<>();
			tiers.put("easy", client.getVarbitValue((int) d[1]));
			tiers.put("medium", client.getVarbitValue((int) d[2]));
			tiers.put("hard", client.getVarbitValue((int) d[3]));
			tiers.put("elite", client.getVarbitValue((int) d[4]));
			diaries.put((String) d[0], tiers);
		}
		snap.put("diaries_raw", diaries);

		// ---- combat achievements: raw per-tier varbit ----
		Map<String, Object> ca = new LinkedHashMap<>();
		ca.put("easy", client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_EASY));
		ca.put("medium", client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM));
		ca.put("hard", client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_HARD));
		ca.put("elite", client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE));
		ca.put("master", client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER));
		ca.put("grandmaster", client.getVarbitValue(Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER));
		snap.put("combat_achievements_raw", ca);

		// ---- slayer ----
		Map<String, Object> slayer = new LinkedHashMap<>();
		slayer.put("points", client.getVarbitValue(Varbits.SLAYER_POINTS));
		slayer.put("streak", client.getVarbitValue(Varbits.SLAYER_TASK_STREAK));
		slayer.put("task_target_id", client.getVarpValue(VARP_SLAYER_TARGET));
		slayer.put("task_remaining", client.getVarpValue(VARP_SLAYER_COUNT));
		slayer.put("unlocks_raw", client.getVarpValue(VARP_SLAYER_UNLOCKS));
		slayer.put("blocked_raw", client.getVarpValue(VARP_SLAYER_BLOCKED));
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
			int type = client.getVarbitValue(s[0]);
			int amt = client.getVarbitValue(s[1]);
			if (type > 0 && amt > 0)
			{
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("type_raw", type);
				m.put("amount", amt);
				runePouch.add(m);
			}
		}
		snap.put("rune_pouch_raw", runePouch);

		// ---- grand exchange: active buy/sell offers ----
		List<Map<String, Object>> geOffers = new ArrayList<>();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			for (int i = 0; i < offers.length; i++)
			{
				GrandExchangeOffer o = offers[i];
				if (o == null || o.getState() == GrandExchangeOfferState.EMPTY)
				{
					continue;
				}
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("slot", i);
				m.put("item_id", o.getItemId());
				m.put("state", o.getState().name());
				m.put("total_qty", o.getTotalQuantity());
				m.put("transferred_qty", o.getQuantitySold());
				m.put("price_per_item", o.getPrice());
				m.put("spent", o.getSpent());
				geOffers.add(m);
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
		Map<String, Object> bankBlock = new LinkedHashMap<>();
		bankBlock.put("items", bankSynced ? itemList(bank) : new ArrayList<>());
		bankBlock.put("value_gp", bankSynced ? containerValue(bank) : 0L);
		snap.put("bank", bankBlock);

		// (looting bag / seed vault deferred: InventoryID member names need verification vs this client)

		// ---- collection log: obtained item ids (partial until the player opens the clog tabs) ----
		Map<String, Object> collog = new LinkedHashMap<>();
		List<Integer> obtained = new ArrayList<>(clogObtained);
		java.util.Collections.sort(obtained);
		collog.put("synced", clogSeen);
		collog.put("obtained_count", obtained.size());
		collog.put("obtained_item_ids", obtained);
		snap.put("collection_log", collog);

		// ---- wealth (broken out: carried coins, inventory, worn gear, bank, total) ----
		long invValue = containerValue(inv);
		long eqpValue = containerValue(eqp);
		long bankValue = bankSynced ? containerValue(bank) : 0L;
		long coins = countItem(inv, COINS_ID) + countItem(eqp, COINS_ID);
		Map<String, Object> wealth = new LinkedHashMap<>();
		wealth.put("coins_carried", coins);          // coins (id 995) on hand
		wealth.put("inventory_gp", invValue);         // total GE value of carried inventory (incl. coins)
		wealth.put("equipment_gp", eqpValue);         // GE value of worn / on-character gear
		wealth.put("bank_gp", bankSynced ? bankValue : null);
		wealth.put("net_worth_gp", bankSynced ? (invValue + eqpValue + bankValue) : null); // null until bank opened
		snap.put("wealth", wealth);

		return snap;
	}

	// ---- container helpers ----

	private Map<String, Object> itemMap(int id, int qty)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("qty", qty);
		return m;
	}

	private List<Map<String, Object>> itemList(ItemContainer c)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		if (c == null)
		{
			return out;
		}
		for (Item item : c.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				out.add(itemMap(item.getId(), item.getQuantity()));
			}
		}
		return out;
	}

	private long countItem(ItemContainer c, int id)
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
		if (c == null)
		{
			return 0L;
		}
		long v = 0L;
		for (Item item : c.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				v += (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
			}
		}
		return v;
	}

	private void postSnapshot(String token, Map<String, Object> snapshot)
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("token", token);
		body.put("snapshot", snapshot);

		String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
		Request request = new Request.Builder()
			.url(base + "/account-ingest")
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS BiS sync failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					log.debug("OSRS BiS sync response: {}", response.code());
				}
				finally
				{
					response.close();
				}
			}
		});
	}
}
