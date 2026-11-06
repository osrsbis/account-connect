package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemStack;

/** Own kills and activities: loot, reward chests, kill counts, milestones, minigame screens and slayer tasks. */
@Slf4j
final class KillActivity
{
	private final AccountConnectPlugin plugin;

	KillActivity(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	// ---- chest / reward-interface loot (gap audit 2026-07-18): raids, Barrows, clues, Colosseum, Lunar,
	// wildy loot chest. These NEVER fire LootManager (ServerNpcLoot) — RuneLite itself captures them only in
	// the OPTIONAL LootTrackerPlugin via WidgetLoaded + a reward-container read, which a user can disable.
	// We replicate the same widget-group -> container reads here (ids javap-verified vs 1.12.33 gameval). ----
	private static final int RAIDS_REWARDS_GROUP = InterfaceID.RAIDS_REWARDS;			// 539 CoX
	private static final int RAIDS_REWARDS_CONTAINER = net.runelite.api.gameval.InventoryID.RAIDS_REWARDS;		// 581
	private static final int TOB_CHESTS_GROUP = InterfaceID.TOB_CHESTS;				// 23
	private static final int TOB_CHESTS_CONTAINER = net.runelite.api.gameval.InventoryID.TOB_CHESTS;			// 612
	private static final int TOA_CHESTS_GROUP = InterfaceID.TOA_CHESTS;				// 771
	private static final int TOA_CHESTS_CONTAINER = net.runelite.api.gameval.InventoryID.TOA_CHESTS;			// 811 (personal split)
	private static final int BARROWS_REWARD_GROUP = InterfaceID.BARROWS_REWARD;		// 155
	private static final int TRAIL_REWARDSCREEN_GROUP = InterfaceID.TRAIL_REWARDSCREEN;	// 73 (clue)
	private static final int TRAIL_REWARD_CONTAINER = net.runelite.api.gameval.InventoryID.TRAIL_REWARDINV;		// 141 (Barrows + clues share it)
	private static final int COLOSSEUM_REWARD_GROUP = InterfaceID.COLOSSEUM_REWARD_CHEST_2;	// 864
	private static final int COLOSSEUM_REWARD_CONTAINER = net.runelite.api.gameval.InventoryID.COLOSSEUM_REWARDS;	// 843
	private static final int PMOON_REWARD_GROUP = InterfaceID.PMOON_REWARD;			// 868 Lunar Chest
	private static final int PMOON_REWARD_CONTAINER = net.runelite.api.gameval.InventoryID.PMOON_REWARDINV;		// 847
	private static final int WILDY_LOOT_CHEST_GROUP = InterfaceID.WILDY_LOOT_CHEST;	// 742
	private static final int[] WILDY_LOOT_CONTAINERS = {
		net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV0, net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV1,
		net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV2, net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV3,
		net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV4,	// 558-562
	};
	private static final int TOB_REGION = 12867;		// Theatre of Blood — the chest widget only counts inside
	private static final int TOB_LOBBY_REGION = 14642;
	boolean chestLooted;		// one instanced-chest emit per visit; reset on LOADING (region change)
	String lastChestEmitKey;	// consecutive-identical belt (same widget + same contents = a re-view, not new loot)

	/**
	 * Chest / reward-interface loot (the raid-income blind spot — gap audit 2026-07-18). Mirrors RuneLite's
	 * own LootTrackerPlugin widget handlers so the capture works even with LootTracker disabled. Emits through
	 * the existing emitLoot as source_type "event". Dedup is two-belt: instanced chests (raids / Colosseum /
	 * Lunar) emit once per visit via chestLooted (reset on LOADING = region transition); ALL groups also skip a
	 * consecutive re-emit of identical contents (a re-viewed chest is the same loot, not new income). An
	 * unreadable/empty container emits nothing — no fabrication. ToA's container already holds only this
	 * player's split; ToB is region-gated like LootTracker. Package-private for unit tests.
	 */
	void handleChestLootWidgetLoaded(int groupId)
	{
		if (!plugin.activityLogActive() || plugin.client == null)
		{
			return;
		}
		String source;
		int[] containers;
		boolean instanced = false;
		switch (groupId)
		{
			case RAIDS_REWARDS_GROUP:
				source = "Chambers of Xeric";
				containers = new int[]{RAIDS_REWARDS_CONTAINER};
				instanced = true;
				break;
			case TOB_CHESTS_GROUP:
			{
				Map<String, Object> loc = plugin.snapshots.currentLocation();
				int region = loc == null ? -1 : (Integer) loc.get("region_id");
				if (region != TOB_REGION && region != TOB_LOBBY_REGION)
				{
					return;	// the group can load outside the chest room — only count in ToB itself
				}
				source = "Theatre of Blood";
				containers = new int[]{TOB_CHESTS_CONTAINER};
				instanced = true;
				break;
			}
			case TOA_CHESTS_GROUP:
				source = "Tombs of Amascut";
				containers = new int[]{TOA_CHESTS_CONTAINER};
				instanced = true;
				break;
			case BARROWS_REWARD_GROUP:
				source = "Barrows";
				containers = new int[]{TRAIL_REWARD_CONTAINER};
				break;
			case TRAIL_REWARDSCREEN_GROUP:
				source = "Clue Scroll";
				containers = new int[]{TRAIL_REWARD_CONTAINER};
				break;
			case COLOSSEUM_REWARD_GROUP:
				source = "Fortis Colosseum";
				containers = new int[]{COLOSSEUM_REWARD_CONTAINER};
				instanced = true;
				break;
			case PMOON_REWARD_GROUP:
				source = "Lunar Chest";
				containers = new int[]{PMOON_REWARD_CONTAINER};
				instanced = true;
				break;
			case WILDY_LOOT_CHEST_GROUP:
				source = "Loot Chest";
				containers = WILDY_LOOT_CONTAINERS;
				break;
			default:
				return;
		}
		if (instanced && chestLooted)
		{
			return;	// this instance visit's chest is already counted
		}
		List<ItemStack> stacks = new ArrayList<>();
		StringBuilder key = new StringBuilder().append(groupId);
		for (int containerId : containers)
		{
			ItemContainer c = plugin.client.getItemContainer(containerId);
			if (c == null)
			{
				continue;
			}
			Item[] rewardItems = c.getItems();
			if (rewardItems == null) continue; // An unloaded reward is unknown; do not latch chestLooted.
			for (Item item : rewardItems)
			{
				if (item != null && item.getId() > 0 && item.getQuantity() > 0)
				{
					stacks.add(new ItemStack(item.getId(), item.getQuantity()));
					key.append(':').append(item.getId()).append('x').append(item.getQuantity());
				}
			}
		}
		if (stacks.isEmpty())
		{
			return;	// unreadable or empty reward — emit nothing rather than fabricate
		}
		String k = key.toString();
		if (k.equals(lastChestEmitKey))
		{
			return;	// same widget re-showing the same contents = a re-view, not new loot
		}
		lastChestEmitKey = k;
		if (instanced)
		{
			chestLooted = true;
		}
		emitLoot(source, "event", stacks);
	}

	/**
	 * The gate for a count line. Live 0.7.61/0.7.62 (10-27..30) sent no raid, Wintertodt or agility count: "Your
	 * completed Tombs of Amascut count is", "Your subdued Wintertodt count is", "Your completion count for the Theatre of
	 * Blood is" and "Your ... lap count is" did not match.
	 * A lap line goes out as kind "count" with source "<course> lap": the ingest kinds have no "lap".
	 */
	private static final Pattern KILL_COUNT_RE = Pattern.compile(
		"(?:(?:kill|completion|chest|success|harvest|lap) count|Your (?:completed|subdued) .+? count|Your completion count for .+?) is:? ?([\\d,]+)",
		Pattern.CASE_INSENSITIVE);
	/**
	 * Structured source of a count line: "Your [completion count for |subdued |completed ]<source>
	 * [kill|harvest|completion|success|Total Ticket ][count ]is: N". The shape follows the parser in the
	 * BSD-2-Clause plugin "Collection Log Popup Enhanced" (Copyright (c) 2026, SnakeSteak), written
	 * independently here; only the line shape is shared. The colour markup is stripped first, both forms.
	 */
	static final Pattern KC_SOURCE_RE = Pattern.compile(
		"Your (completion count for |subdued |completed )?(.+?) ((?:kill|harvest|completion|success|Total Ticket) )?(?:count )?is: ?([0-9,]+)",
		Pattern.CASE_INSENSITIVE);
	static final Pattern CHAT_MARKUP_RE = Pattern.compile("<[^<>]*>|@[a-zA-Z0-9_]+@");

	/** {source, kind, count} for a count line, or null. kind is the verb ("kill", "completion", ...) or "count". */
	static Map<String, Object> parseKillCount(String text)
	{
		if (text == null)
		{
			return null;
		}
		String clean = CHAT_MARKUP_RE.matcher(text).replaceAll("");
		Matcher m = KC_SOURCE_RE.matcher(clean);
		if (!m.find())
		{
			return null;
		}
		long count;
		try
		{
			count = Long.parseLong(m.group(4).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
		String source = m.group(2).trim();
		if (source.toLowerCase(Locale.ROOT).startsWith("the "))
		{
			source = source.substring(4);
		}
		String kind = m.group(3) != null ? m.group(3).trim().toLowerCase(Locale.ROOT)
			: m.group(1) != null ? m.group(1).trim().toLowerCase(Locale.ROOT) : "count";
		Map<String, Object> out = Fields.of("source", source, "kind", kind, "count", count);
		return out;
	}

	/**
	 * Emit the two structured milestones parsed from a system chat line: kill_count and pet.
	 *
	 * Own-account only. A system line names no other player, so nothing here captures a bystander.
	 * The raw line rides along as `text` because the count alone does not say WHAT was killed.
	 */
	void emitParsedMilestones(String text)
	{
		if (text == null || text.isEmpty() || !plugin.activityLogActive())
		{
			return;
		}
		Matcher m = KILL_COUNT_RE.matcher(text);
		if (m.find())
		{
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("text", text);
			try
			{
				f.put("count", Long.parseLong(m.group(1).replace(",", "")));
			}
			catch (NumberFormatException ignored)
			{
				// leave count off when it does not parse; the text still carries the milestone
			}
			Map<String, Object> parsed = parseKillCount(text);
			if (parsed != null)
			{
				f.put("source", parsed.get("source"));	// structured boss / activity name; text kept as the raw line
				f.put("kind", parsed.get("kind"));
			}
			plugin.events.emitEvent("kill_count", f);
			emitKillContext(f.get("source"), f.get("count"));
		}
		String low = text.toLowerCase(Locale.ROOT);
		if (low.contains("funny feeling like you") && low.contains("followed"))
		{
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("text", text);
			plugin.events.emitEvent("pet", f);
		}
	}

	/**
	 * Kill detail from one own GAMEMESSAGE/SPAM line (raw, with game markup), only under the X-Kill-Detail grant.
	 * kill_timing: one row per time the line prints (a ToA line prints the room and the whole raid). kill_count: the
	 * Sepulchre and Guardians of the Rift count lines. A reward line arms the reward-loot capture.
	 */
	void emitKillDetail(String message)
	{
		if (message == null || !plugin.activityLogActive() || !plugin.grants.killDetailGranted())
		{
			return;
		}
		Map<String, Object> timing = KillTiming.parse(message);
		if (timing != null)
		{
			int seq = 0;
			for (Object t : (List<?>) timing.get("timings"))
			{
				@SuppressWarnings("unchecked") Map<String, Object> f = new LinkedHashMap<>((Map<String, Object>) t);
				f.put("seq", seq++);
				if (timing.get("team_size") != null) f.put("team_size", timing.get("team_size"));
				if (timing.get("room") != null) f.put("room", timing.get("room"));
				plugin.events.emitEvent("kill_timing", f);
			}
		}
		Map<String, Object> count = KillTiming.parseActivityCount(message);
		if (count != null)
		{
			plugin.events.emitEvent("kill_count", count);
		}
		String text = KillTiming.clean(message);
		String source = RewardLootCapture.source(text, regionNow());
		if (source != null && plugin.client != null)
		{
			plugin.rewardLoot.arm(source, plugin.client.getTickCount(), inventoryCounts(plugin.client.getItemContainer(AccountConnectPlugin.INVENTORY_CONTAINER_ID)));
		}
	}

	/** kill_context for the kill count just sent, only under the X-Kill-Context grant and while logged in. */
	void emitKillContext(Object source, Object count)
	{
		if (!plugin.grants.killContextGranted() || plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN) return;
		int[] boosted = new int[KillContext.SKILLS.length], real = new int[KillContext.SKILLS.length];
		for (int i = 0; i < KillContext.SKILLS.length; i++)
		{
			Skill skill = Skill.valueOf(KillContext.SKILLS[i].toUpperCase(Locale.ROOT));
			boosted[i] = plugin.client.getBoostedSkillLevel(skill);
			real[i] = plugin.client.getRealSkillLevel(skill);
		}
		Set<WorldType> types = plugin.client.getWorldType();
		// The raid level is read only for a Tombs of Amascut count (its own and Entry/Expert lines).
		Integer toaLevel = String.valueOf(source).startsWith("Tombs of Amascut")
			? plugin.client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL) : null;
		Map<String, Object> f = KillContext.build(itemPairs(plugin.client.getItemContainer(AccountConnectPlugin.EQUIP_CONTAINER_ID)),
			itemPairs(plugin.client.getItemContainer(AccountConnectPlugin.INVENTORY_CONTAINER_ID)), boosted, real,
			plugin.client.getVarpValue(VarPlayerID.SA_ENERGY), types == null ? null : types.contains(WorldType.MEMBERS),
			toaLevel);
		if (f == null) return;
		Map<String, Object> row = Fields.of("source", source, "count", count);
		row.putAll(f);
		plugin.events.emitEvent("kill_context", row);
	}

	/** Changes of the service-unit counters (KillContext.ACTIVITY_COUNTERS), only under the X-Kill-Context grant. */
	void trackActivityCounters()
	{
		if (!plugin.grants.killContextGranted() || plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			plugin.activityCounters.reset();
			return;
		}
		Map<String, Integer> now = new LinkedHashMap<>();
		for (Object[] c : KillContext.ACTIVITY_COUNTERS)
		{
			int id = (int) c[2];
			// The last value the server set, never the client's own prediction (Client.getServerVarpValue).
			now.put((String) c[0], "varp".equals(c[1]) ? plugin.client.getServerVarpValue(id) : plugin.client.getServerVarbitValue(id));
		}
		for (Object[] c : KillContext.WIDGET_COUNTERS)
		{
			Widget w = plugin.client.getWidget((int) c[1]);
			Integer v = w == null || w.isHidden() ? null : KillContext.widgetNumber(w.getText());
			// Two screens can show one balance; the one on screen wins, an absent one leaves the last value.
			if (v != null) now.put((String) c[0], v);
			else now.putIfAbsent((String) c[0], null);
		}
		for (Map<String, Object> row : plugin.activityCounters.tick(now)) plugin.events.emitEvent("activity_counter", row);
	}

	/** The last dialogue text seen per box, so one dialogue page gives one row however long it stays open. */
	final String[] lastMinigameDialogue = new String[MinigameLines.DIALOGUE_TEXTS.length];

	/** Minigame results shown in a dialogue or message box (MinigameLines.DIALOGUE), only under the X-Kill-Context grant. */
	void readMinigameDialogue()
	{
		if (plugin.client == null || !plugin.grants.killContextGranted() || plugin.client.getGameState() != GameState.LOGGED_IN) return;
		for (int i = 0; i < MinigameLines.DIALOGUE_TEXTS.length; i++)
		{
			Widget w = plugin.client.getWidget(MinigameLines.DIALOGUE_TEXTS[i]);
			String text = w == null || w.isHidden() ? null : w.getText();
			if (text == null || text.equals(lastMinigameDialogue[i]))
			{
				lastMinigameDialogue[i] = text;
				continue;
			}
			lastMinigameDialogue[i] = text;
			Map<String, Object> row = MinigameLines.parseDialogue(text);
			if (row != null)
			{
				plugin.verificationMessage = EventVerification.clean(text);
				try { plugin.events.emitEvent("minigame", row); }
				finally { plugin.verificationMessage = null; }
			}
		}
	}

	/** Result screens loaded and waiting one tick for their text: group id to the tick it loaded. */
	final Map<Integer, Integer> pendingMinigameScreens = new HashMap<>();

	/**
	 * A minigame result screen's numbers (MinigameLines.SCREENS), read on the first tick after it loaded, because the
	 * game fills the text after the load. One row per screen load, only under the X-Kill-Context grant.
	 */
	void readMinigameScreens()
	{
		if (pendingMinigameScreens.isEmpty() || plugin.client == null) return;
		int tick = plugin.client.getTickCount();
		Iterator<Map.Entry<Integer, Integer>> it = pendingMinigameScreens.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, Integer> p = it.next();
			if (tick <= p.getValue()) continue;
			it.remove();
			if (!plugin.grants.killContextGranted() || plugin.client.getGameState() != GameState.LOGGED_IN) continue;
			Map<String, Map<String, String>> byGame = new LinkedHashMap<>();
			for (Object[] c : MinigameLines.SCREENS)
			{
				if (((int) c[1]) >>> 16 != p.getKey()) continue;
				Widget w = plugin.client.getWidget((int) c[1]);
				if (w == null || w.isHidden()) continue;
				byGame.computeIfAbsent((String) c[0], k -> new LinkedHashMap<>()).put((String) c[2], w.getText());
			}
			for (Map.Entry<String, Map<String, String>> g : byGame.entrySet())
			{
				Map<String, Object> row = MinigameLines.screen(g.getKey(), g.getValue());
				if (row != null) plugin.events.emitEvent("minigame", row);
			}
		}
	}

	/** Slayer task completion from the game's completed-tasks counter, only under the X-Kill-Context grant. */
	void trackSlayerTask()
	{
		if (!plugin.grants.killContextGranted() || plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			plugin.slayerTasks.reset();
			return;
		}
		Map<String, Object> done = plugin.slayerTasks.tick(plugin.client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED),
			plugin.client.getVarpValue(VarPlayerID.SLAYER_TARGET),
			plugin.client.getVarpValue(VarPlayerID.SLAYER_COUNT));
		if (done != null) plugin.events.emitEvent("slayer_task", done);
	}

	/**
	 * Under the X-Kill-Context grant: the clue, collection-log, combat-task and salvage lines the account-progress
	 * parser reads, as timed progress rows, and the game's party-failure line as attempt_end.
	 */
	void emitProgressAndFailure(String message)
	{
		if (message == null || !plugin.grants.killContextGranted()) return;
		Map<String, Object> progress = AccountProgressCapture.parseMessage(message);
		if (progress != null) plugin.events.emitEvent("progress", progress);
		Map<String, Object> minigame = MinigameLines.parse(message);
		if (minigame != null) plugin.events.emitEvent("minigame", minigame);
		if (KillContext.partyFailed(KillTiming.clean(message)))
		{
			Map<String, Object> f = Fields.of("outcome", "failed", "basis", "party_failure_line");
			plugin.events.emitEvent("attempt_end", f);
		}
	}

	/** [item id, quantity] for each real item, or null for an unreadable container. */
	static List<long[]> itemPairs(ItemContainer c)
	{
		Item[] items = c == null ? null : c.getItems();
		if (items == null) return null;
		List<long[]> out = new ArrayList<>();
		for (Item it : items) if (it != null && it.getId() > 0 && it.getQuantity() > 0) out.add(new long[]{it.getId(), it.getQuantity()});
		return out;
	}

	/** The reward of an armed line, as a loot row with source_type "event", only under the X-Kill-Detail grant. */
	void emitRewardLoot(int tick, Map<Integer, Long> inventory)
	{
		Map<String, Object> reward = plugin.rewardLoot.inventoryChanged(tick, inventory);
		if (reward == null || !plugin.activityLogActive() || !plugin.grants.killDetailGranted())
		{
			return;
		}
		List<ItemStack> stacks = new ArrayList<>();
		for (Object o : (List<?>) reward.get("items"))
		{
			long[] it = (long[]) o;
			stacks.add(new ItemStack((int) it[0], (int) Math.min(Integer.MAX_VALUE, it[1])));
		}
		emitLoot((String) reward.get("source"), "event", stacks);
	}

	private int regionNow()
	{
		Player me = plugin.client == null ? null : plugin.client.getLocalPlayer();
		WorldPoint wp = me == null ? null : me.getWorldLocation();
		return wp == null ? -1 : wp.getRegionID();
	}

	/** Item id to total quantity, or null for an unreadable container. */
	static Map<Integer, Long> inventoryCounts(ItemContainer c)
	{
		Item[] items = c == null ? null : c.getItems();
		if (items == null) return null;
		Map<Integer, Long> out = new HashMap<>();
		for (Item it : items)
		{
			if (it != null && it.getId() > 0 && it.getQuantity() > 0) out.merge(it.getId(), (long) it.getQuantity(), Long::sum);
		}
		return out;
	}

	/**
	 * WAVE 4 — structured NPC loot. The whole drop for one kill arrives as a single Collection&lt;ItemStack&gt;,
	 * so one kill = one "loot" event (already coalesced by the API — no time window needed). Fired by
	 * LootManager, a core RuneLite service that is ALWAYS running (NOT the optional loot-tracker plugin), for
	 * the local player's kills — so this is own-account by construction.
	 *
	 * We subscribe to ServerNpcLoot ONLY. LootManager fires BOTH ServerNpcLoot and NpcLootReceived for the same
	 * server-authoritative kill (verified in its bytecode), so taking both would double-count; this mirrors
	 * RuneLite's own maintained LootTrackerPlugin (onServerNpcLoot + onPlayerLootReceived). Trade-off: kills
	 * that ONLY fire via LootManager's older ground-detection path (NpcLootReceived alone) are not captured —
	 * a rare edge case in modern OSRS; adding it would require subscribing to both with dedup state.
	 * javap-verified vs client 1.12.32 (ServerNpcLoot.getComposition/getItems, NPCComposition.getName).
	 */
	void onServerNpcLoot(ServerNpcLoot event)
	{
		NPCComposition comp = event.getComposition();
		emitLoot(comp == null ? null : comp.getName(), "npc", event.getItems(), comp == null ? -1 : comp.getId());
	}

	/**
	 * WAVE 4 — PvP player loot (e.g. a Wilderness kill). Capture the ITEMS only. The victim's name is available
	 * (event.getPlayer().getName()) but is deliberately NOT sent: forwarding a killed player's name on the
	 * public path is a compliance decision for the product owner, not an auto-send.
	 * TODO: decide whether to attach the victim RSN as loot source_name — left OUT until then.
	 * javap-verified vs client 1.12.32 (PlayerLootReceived.getItems).
	 */
	void onPlayerLootReceived(PlayerLootReceived event)
	{
		emitLoot(null, "player", event.getItems());
	}

	/**
	 * Build + buffer a structured "loot" event: {source_name?, source_type, items:[{id,qty}], value}. value is
	 * the summed GE price via ItemManager when reachable (0 when itemManager is absent, e.g. buffer-only tests).
	 * No-op on an empty/blank drop. Gated inside emitEvent exactly like every other event (token set + account
	 * not excluded). Deliberately NOT a SNAPSHOT_TRIGGER_EVENT: loot fires many times a minute at a boss/slayer
	 * task — forcing a snapshot per kill would be a firehose; the wealth/inventory change lands on the next
	 * syncTask through the normal change-gate, while the loot event itself still ships in real time (WAVE 2).
	 */
	void emitLoot(String sourceName, String sourceType, Collection<ItemStack> stacks)
	{
		emitLoot(sourceName, sourceType, stacks, -1);
	}

	void emitLoot(String sourceName, String sourceType, Collection<ItemStack> stacks, int npcId)
	{
		if (stacks == null || stacks.isEmpty())
		{
			return;
		}
		List<Map<String, Object>> items = new ArrayList<>();
		long value = 0L;
		for (ItemStack st : stacks)
		{
			if (st == null || st.getId() <= 0 || st.getQuantity() <= 0)
			{
				continue;
			}
			items.add(plugin.snapshots.itemMap(st.getId(), st.getQuantity()));
			if (plugin.itemManager != null)
			{
				value += (long) plugin.itemManager.getItemPrice(st.getId()) * st.getQuantity();
			}
		}
		if (items.isEmpty())
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		if (sourceName != null && !sourceName.isEmpty())
		{
			fields.put("source_name", sourceName);
		}
		fields.put("source_type", sourceType);
		if (npcId >= 0)
		{
			fields.put("npc_id", npcId);
		}
		fields.put("items", items);
		fields.put("value", value);
		plugin.events.emitEvent("loot", fields);
	}
}
