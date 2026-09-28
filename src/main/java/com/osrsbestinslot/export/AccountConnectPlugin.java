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
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPCComposition;
import net.runelite.api.Prayer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.ui.overlay.infobox.Timer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
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
	// MUST equal build.gradle's version — VersionDriftTest fails the build if the two ever diverge, so
	// every snapshot's source.plugin_version honestly reports which build the account is running.
	private static final String PLUGIN_VERSION = "0.7.15";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int COINS_ID = 995;

	// ---- snapshot upload gating ----
	// The @Schedule tick (every 5s) is only the CHECKER: it always builds + tracks the snapshot, but an
	// HTTP send happens only when the canonical hash changed, the debounce interval has passed and
	// no 429 backoff is active. Reference behavior is Wise Old Man's event/change-driven uploads —
	// never a clock firehose (the server allows 150 req/hr per token).
	private static final long DEFAULT_MIN_UPLOAD_INTERVAL_MILLIS = 120_000L;
	private static final long BACKOFF_START_MILLIS = 60_000L;   // 429 without Retry-After: 60s, doubling
	private static final long BACKOFF_CAP_MILLIS = 900_000L;    // exponential backoff cap
	private static final long RETRY_AFTER_CAP_SECONDS = 3600L;  // never honor a Retry-After beyond 1h

	// Debounce interval is a field (not config) so tests can shrink it via same-package access.
	long minUploadIntervalMillis = DEFAULT_MIN_UPLOAD_INTERVAL_MILLIS;
	// Written on the client thread (tick/flush), read by the OkHttp callback thread and vice
	// versa — volatile keeps the handoff safe without locking.
	volatile Map<String, Object> lastBuiltSnapshot;
	volatile String lastBuiltHash;
	volatile String lastUploadedHash;
	volatile long lastSendMillis;
	volatile long backoffUntilMillis;
	volatile long nextBackoffMillis = BACKOFF_START_MILLIS;

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

	// Trade-screenshot delivery proof (opt-in, see uploadTradeScreenshots): grab one frame when the
	// trade CONFIRM screen (group 334) loads, buffer it, and upload it only on "Accepted trade.".
	// NOTE the legacy net.runelite.api.InventoryID imported above is a DIFFERENT type from these
	// gameval int constants — referenced fully-qualified so the two are never conflated.
	private static final int TRADE_OFFER_CONTAINER_ID = net.runelite.api.gameval.InventoryID.TRADEOFFER;	// 90
	private static final int TRADE_CONFIRM_GROUP_ID = net.runelite.api.gameval.InterfaceID.TRADECONFIRM;	// 334
	private static final int TRADE_MAIN_GROUP_ID = net.runelite.api.gameval.InterfaceID.TRADEMAIN;			// 335
	// Activity-log capture (verified vs runelite-api 1.12.32 gameval enums):
	private static final int SHOP_GROUP_ID = net.runelite.api.gameval.InterfaceID.SHOPMAIN;					// 300
	private static final int TRADE_TITLE_COMPONENT = net.runelite.api.gameval.InterfaceID.Trademain.TITLE;	// 21954591 ("Trading with X")
	// The other player's live offer GRID on the MAIN trade screen (335). This is the only place the received
	// items are readable as item sprites — the confirm screen (334) shows them only as a value-text summary
	// that collapses to "Lots!" on big trades. javap-verified vs runelite-api 1.12.33.
	private static final int TRADE_MAIN_OTHER_OFFER = net.runelite.api.gameval.InterfaceID.Trademain.OTHER_OFFER;	// 21954588
	// WAVE 1b: the confirm screen's "You will receive" column — a value-TEXT summary (not item sprites), kept
	// only as a last-resort fallback. Packed component id = group 334 << 16 | child 24.
	private static final int TRADE_CONFIRM_RECEIVE_COMPONENT = net.runelite.api.gameval.InterfaceID.Tradeconfirm.YOU_WILL_RECEIVE;	// 21889048
	// Capture-on-open groups: opening either forces an immediate snapshot so the bank / collection log
	// sync the moment they become readable (verified vs runelite-api 1.12.32 gameval enums, javap).
	private static final int BANK_GROUP_ID = net.runelite.api.gameval.InterfaceID.BANKMAIN;					// 12
	private static final int COLLECTION_LOG_GROUP_ID = net.runelite.api.gameval.InterfaceID.COLLECTION;		// 621
	// Off-book snapshot containers (verified vs runelite-api 1.12.32 gameval enums, javap). gameval int ids so
	// they match ItemContainerChanged.getContainerId(); Group-Ironman shared storage has no gameval constant, so
	// its snapshot read uses the legacy InventoryID.GROUP_STORAGE overload of client.getItemContainer(...).
	private static final int LOOTING_BAG_CONTAINER_ID = net.runelite.api.gameval.InventoryID.LOOTING_BAG;	// 516
	private static final int SEED_VAULT_CONTAINER_ID = net.runelite.api.gameval.InventoryID.SEED_VAULT;		// 626
	private static final int BONDS_POUCH_CONTAINER_ID = net.runelite.api.gameval.InventoryID.BONDS_POUCH;	// 536
	private static final int BONDS_ESCROW_CONTAINER_ID = net.runelite.api.gameval.InventoryID.BONDS_ESCROW;	// 534
	private static final int QUIVER_AMMO_CONTAINER_ID = net.runelite.api.gameval.InventoryID.DIZANAS_QUIVER_AMMO; // 879

	// ---- chest / reward-interface loot (gap audit 2026-07-18): raids, Barrows, clues, Colosseum, Lunar,
	// wildy loot chest. These NEVER fire LootManager (ServerNpcLoot) — RuneLite itself captures them only in
	// the OPTIONAL LootTrackerPlugin via WidgetLoaded + a reward-container read, which a user can disable.
	// We replicate the same widget-group -> container reads here (ids javap-verified vs 1.12.33 gameval). ----
	private static final int RAIDS_REWARDS_GROUP = net.runelite.api.gameval.InterfaceID.RAIDS_REWARDS;			// 539 CoX
	private static final int RAIDS_REWARDS_CONTAINER = net.runelite.api.gameval.InventoryID.RAIDS_REWARDS;		// 581
	private static final int TOB_CHESTS_GROUP = net.runelite.api.gameval.InterfaceID.TOB_CHESTS;				// 23
	private static final int TOB_CHESTS_CONTAINER = net.runelite.api.gameval.InventoryID.TOB_CHESTS;			// 612
	private static final int TOA_CHESTS_GROUP = net.runelite.api.gameval.InterfaceID.TOA_CHESTS;				// 771
	private static final int TOA_CHESTS_CONTAINER = net.runelite.api.gameval.InventoryID.TOA_CHESTS;			// 811 (personal split)
	private static final int BARROWS_REWARD_GROUP = net.runelite.api.gameval.InterfaceID.BARROWS_REWARD;		// 155
	private static final int TRAIL_REWARDSCREEN_GROUP = net.runelite.api.gameval.InterfaceID.TRAIL_REWARDSCREEN;	// 73 (clue)
	private static final int TRAIL_REWARD_CONTAINER = net.runelite.api.gameval.InventoryID.TRAIL_REWARDINV;		// 141 (Barrows + clues share it)
	private static final int COLOSSEUM_REWARD_GROUP = net.runelite.api.gameval.InterfaceID.COLOSSEUM_REWARD_CHEST_2;	// 864
	private static final int COLOSSEUM_REWARD_CONTAINER = net.runelite.api.gameval.InventoryID.COLOSSEUM_REWARDS;	// 843
	private static final int PMOON_REWARD_GROUP = net.runelite.api.gameval.InterfaceID.PMOON_REWARD;			// 868 Lunar Chest
	private static final int PMOON_REWARD_CONTAINER = net.runelite.api.gameval.InventoryID.PMOON_REWARDINV;		// 847
	private static final int WILDY_LOOT_CHEST_GROUP = net.runelite.api.gameval.InterfaceID.WILDY_LOOT_CHEST;	// 742
	private static final int[] WILDY_LOOT_CONTAINERS = {
		net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV0, net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV1,
		net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV2, net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV3,
		net.runelite.api.gameval.InventoryID.DEADMAN_LOOT_INV4,	// 558-562
	};
	private static final int TOB_REGION = 12867;		// Theatre of Blood — the chest widget only counts inside
	private static final int TOB_LOBBY_REGION = 14642;
	private boolean chestLooted;		// one instanced-chest emit per visit; reset on LOADING (region change)
	private String lastChestEmitKey;	// consecutive-identical belt (same widget + same contents = a re-view, not new loot)
	private static final String TRADE_ACCEPTED_MESSAGE = "Accepted trade.";
	private static final String TRADE_DECLINED_MESSAGE = "Other player declined trade.";
	private static final MediaType PNG = MediaType.parse("image/png");
	private static final MediaType JPEG = MediaType.parse("image/jpeg");	// store delivery-proof burst frames
	static final int MAX_SCREENSHOT_UPLOAD_BYTES = 5 * 1024 * 1024;	// enforced client-side and server-side

	// Trade state machine (package-private so unit tests can assert on it without a live client).
	// IDLE -> (container 90) tradeActive -> (334 load) tradeArmed + frame buffered -> commit/discard.
	volatile boolean tradeActive;
	volatile boolean tradeArmed;
	// MAIN trade screen (335) open: while true, onGameTick polls the other player's offer + name (readable
	// only here — 334 confirm replaces 335 and shows the other side as a "Lots!" text summary with its
	// 335-only title widget already gone). Root cause of the prod 0-counterparty / empty-received[] bug.
	volatile boolean tradeMainOpen;

	// Bank move capture: item-id -> qty of the bank as LAST SEEN in this bank session (the open read, then
	// every BANK container change). Each change is diffed against it and the movement is added to the GROSS
	// per-item totals below, so a deposit and a withdraw of the same item inside one session both survive
	// (a net open-vs-close diff cancelled them out). One bank_session event per session carries the totals.
	// Bank moves are internal (no trade / GE / store), so this is the ONLY event-plane record of them. Own
	// account. null = no bank session with a baseline is running.
	private Map<Integer, Long> bankAtOpen;
	private final Map<Integer, Long> bankGrossDeposited = new LinkedHashMap<>();
	private final Map<Integer, Long> bankGrossWithdrawn = new LinkedHashMap<>();
	// True while the bank is open but its container was empty or unloaded at open (the first open after a
	// login can precede the bank contents). Diffing a real bank against that empty state would report the
	// whole bank as deposited, so no baseline is taken then. The first BANK container change while
	// this is set becomes the baseline, and the close diffs against it.
	private boolean bankBaselinePending;

	// Equipment change capture: last-seen worn-item counts, diffed on each WORN-container change to emit
	// equip_change {equipped[], unequipped[]}. Only a change in the SET of worn item ids emits: a quantity
	// change of an item still worn (ammo spent on every attack) is not an equipment change and emits
	// nothing, else one ranger floods the event ingest. The first change after a login only baselines, so
	// a normal gear load does not emit a spurious full-kit equip. Reset on hop and relog.
	private Map<Integer, Long> equipLast;

	// World-hop capture: the last world we were on, 0 when unknown. WorldChanged after a hop emits
	// world_hop {from,to}. The initial login WorldChanged is suppressed by lastWorld 0. Reset on logout.
	private int lastWorld;

	// XP-gain capture (coalesced): accumulate per-skill xp deltas from StatChanged, which fires on every
	// drop, and flush them as ONE xp_gain event every XP_FLUSH_TICKS (5 minutes) and at logout, world hop
	// and disconnect, so per-action xp does not flood the event plane: at most 12 timed flushes per hour of
	// training plus one at session end, and no accumulated xp is dropped at a session boundary. lastSkillXp = last-seen total per skill (the baseline). Reset per account
	// on hop, relog and logout.
	private final Map<String, Integer> lastSkillXp = new LinkedHashMap<>();
	private final Map<String, Long> xpAccum = new LinkedHashMap<>();
	private int xpFlushTicks;
	static final int XP_FLUSH_TICKS = 500;	// 5 minutes at 0.6s per tick
	// Region capture: emit region {from,to} only when the map region changes, which coalesces naturally.
	private Integer lastRegion;

	// Firehose buffer (server-granted): high-frequency rows batched into ONE fh_batch event so per-tick
	// data never floods the ingest. Cleared on account switch, flushed before logout.
	private final List<Map<String, Object>> firehoseBuffer = new ArrayList<>();
	private int firehoseFlushTicks;
	private static final int FIREHOSE_FLUSH_TICKS = 10;	// ~6s batch window
	private static final int FIREHOSE_MAX = 500;		// hard cap between flushes: drop overflow, never OOM
	final AtomicReference<BufferedImage> pendingTradeFrame = new AtomicReference<>();

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

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private net.runelite.client.ui.overlay.OverlayManager overlayManager;

	@Inject
	private ConfigManager configManager;

	static final String CONFIG_GROUP = "osrsbisexport";

	@Provides
	AccountConnectConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AccountConnectConfig.class);
	}

	/**
	 * Two SEPARATE overlays, deliberately.
	 *
	 * The countdown is one fixed-size row so it never moves; the nearby list changes height as
	 * players walk past. Combined, the list dragged the countdown around the screen on every tick —
	 * the jitter reported from the live test on 2026-09-03.
	 */
	private StoreNearbyOverlay nearbyOverlay;
	private StoreResetOverlay resetOverlay;

	@Override
	protected void startUp()
	{
		removeOrphanedKeys();
		// Started while already in the world: the GE slots were replayed before we were listening, so the
		// next LOGGED_IN (a region load) is not a login and must not open a replay window.
		geAwaitingLogin = client == null || client.getGameState() != GameState.LOGGED_IN;
		if (overlayManager != null)
		{
			resetOverlay = new StoreResetOverlay(this);
			overlayManager.add(resetOverlay);
			nearbyOverlay = new StoreNearbyOverlay(this);
			overlayManager.add(nearbyOverlay);
		}
	}

	/**
	 * A key from a removed feature, still sitting in every upgraded profile.
	 *
	 * The trade-screenshot opt-in became part of core sync in 0.7.6 and its config item was deleted,
	 * but RuneLite never removes a stored value whose item is gone. Nothing reads it, so it is
	 * harmless — but anyone reading a profile file sees a toggle that no longer exists and concludes
	 * the feature is still opt-in. Unset it once so the profile matches the code.
	 */
	static final String ORPHAN_SCREENSHOT_KEY = "uploadTradeScreenshots";

	void removeOrphanedKeys()
	{
		if (configManager == null)
		{
			return;
		}
		configManager.unsetConfiguration(CONFIG_GROUP, ORPHAN_SCREENSHOT_KEY);
	}

	@Override
	protected void shutDown()
	{
		if (overlayManager != null && nearbyOverlay != null)
		{
			overlayManager.remove(nearbyOverlay);	// an overlay outliving the plugin is a leak on screen
		}
		if (overlayManager != null && resetOverlay != null)
		{
			overlayManager.remove(resetOverlay);
		}
		nearbyOverlay = null;
		resetOverlay = null;
		stopStoreClipCapture(false);	// unregister the render listener + drop any buffered frames, no upload
		interruptDropSession();		// a shutdown mid-session still publishes what was captured
		// A countdown that outlives the plugin is a lie left on the user's screen — and RuneLite does
		// not clear a plugin's infoboxes for it.
		storeResetAnchorMs = 0;
		removeResetTimer();
		// A disabled plugin sees no GameStateChanged, so a bank session left here could later be diffed
		// against another account's bank. Discard it without a row.
		bankAtOpen = null;
		bankBaselinePending = false;
		bankGrossDeposited.clear();
		bankGrossWithdrawn.clear();
	}

	/**
	 * Server-dictated policy, read from the ingest response on every accepted upload (see
	 * applyServerPolicy). Volatile: written by the OkHttp callback thread, read by the client thread.
	 * serverScreenshotsDisabled lets osrsbestinslot.com force trade-screenshot capture OFF for a token
	 * remotely (it can never force it ON — that stays a local opt-in for Plugin Hub compliance).
	 */
	volatile boolean serverScreenshotsDisabled;

	/**
	 * Server-dictated STORE TOOLS grant — one half of the gate on the reset countdown and the nearby
	 * panel.
	 *
	 * Defaults OFF and can only ever be turned ON by the backend, for a token whose linked RSN is on
	 * the allowlist. A regular player therefore never sees these overlays, and there is no local
	 * setting that grants them. Read from the X-Store-Tools response header on every accepted upload.
	 */
	volatile boolean serverStoreToolsEnabled;

	/**
	 * Server-dictated FIREHOSE grant. Same shape as the store-tools grant, and for the same reason.
	 *
	 * Defaults OFF and can only ever be turned ON by the backend, for a token on the staff allowlist.
	 * There is NO local setting that grants it, deliberately: this plugin carries no user-facing capture
	 * toggle (operator default 2026-09-19), and the firehose captures other players' names and public chat,
	 * which the hub manifest warning does not describe. A regular player therefore never runs it.
	 * Read from the X-Max-Capture response header on every accepted upload.
	 */
	volatile boolean serverMaxCaptureEnabled;

	/**
	 * LOCAL TEST OVERRIDE for the store-tools gate.
	 *
	 * The grant normally arrives from the backend, so the feature cannot be exercised at all until
	 * that deploy lands. This lets a field test run against production today. It is a JVM system
	 * property, so it can only be set by whoever launches the client with an explicit flag — the
	 * Plugin Hub build launched through RuneLite.exe never has it, and it grants nothing on the
	 * server: no extra data is read, captured or uploaded, only local rendering.
	 */
	static boolean storeToolsDevOverride()
	{
		String v = System.getProperty("osrsbis.storetools");
		return v != null && ("on".equals(v) || "true".equals(v) || "1".equals(v));
	}

	/**
	 * Are the store overlays allowed right now? The server grant (or the local test override) AND
	 * the same upload gate every network send uses, both required.
	 *
	 * The upload half is here on purpose. These overlays are the visible half of a feature whose
	 * data half is uploaded, and the grant that turns them on arrives on an upload response. A user
	 * who has turned the upload switch off has turned the feature off, so drawing the countdown and
	 * the nearby panel at them would show a feature they declined. uploadAllowed() subsumes the
	 * linked-token requirement this gate carried before, so nothing is dropped by folding it in.
	 */
	boolean storeToolsEnabled()
	{
		return (serverStoreToolsEnabled || storeToolsDevOverride()) && uploadAllowed();
	}

	/**
	 * Own-account activity log (syncActivityLog opt-in): buffered structured events — logins/logouts,
	 * and later GE / general-store / trade / item movements — POSTed to /event-ingest. Only the user's
	 * OWN account data; nothing about other players is sent to the default backend. Written on the
	 * client thread, flushed on the @Schedule tick.
	 */
	final java.util.List<Map<String, Object>> pendingEvents =
		java.util.Collections.synchronizedList(new java.util.ArrayList<>());
	private static final int MAX_PENDING_EVENTS = 500;

	/** Event-delivery retry state. Base equals the flush period, so the first retry is the next tick. */
	private static final long EVENT_RETRY_BASE_BACKOFF_MS = 5_000L;
	/** Ceiling on the doubling ladder — 5s, 10s, 20s … capped at 5 minutes. */
	private static final long EVENT_RETRY_MAX_BACKOFF_MS = 300_000L;
	volatile boolean eventPostInFlight;
	volatile long eventRetryBackoffMs;
	volatile long eventRetryBackoffUntilMs;
	private volatile boolean sessionActive;
	private volatile String activeRsn;
	private volatile String activeHash;
	private volatile long sessionStartMillis;
	// WAVE 3: idle counters sampled on the last logged-in tick (syncTask), used to classify the logout reason.
	private volatile int lastKeyboardIdleTicks;
	private volatile int lastMouseIdleTicks;
	// Logout-reason heuristics: a session within this window of 6h is the in-game six-hour cap; both idle
	// counters above this tick count (0.6s/tick, ~5 min) at logout = an idle timeout, else a manual logout.
	private static final long SIX_HOUR_MS = 6L * 60L * 60L * 1000L;
	private static final long SIX_HOUR_SLACK_MS = 3L * 60L * 1000L;
	private static final int IDLE_LOGOUT_TICKS = 500;
	/** Dedup state for the broad activity sweep: emit a level event only on a real level change, a GE event
	 *  only on a state transition (not every partial-fill tick). Client-thread only. */
	private final Map<String, Integer> lastSkillLevel = new java.util.HashMap<>();
	private final Map<Integer, GrandExchangeOfferState> lastGeState = new java.util.HashMap<>();
	/** Last seen offer per GE slot {item, qty_sold, qty_total, price, spent}, so a collect (slot -> EMPTY) still names what it held. */
	private final Map<Integer, long[]> lastGeOffer = new java.util.HashMap<>();
	/** Tick of the last ge_progress row per slot. A big offer fills in many steps; one row per minute per slot is enough. */
	private final Map<Integer, Integer> lastGeProgressTick = new java.util.HashMap<>();
	/** Tick of the last LOGGED_IN. The client replays every slot right after login; that replay is a baseline, not activity. */
	int geLoginTick = Integer.MIN_VALUE / 2;
	/** Set when the account leaves the world (login screen, hop, relog, disconnect); the next LOGGED_IN is a real login. */
	boolean geAwaitingLogin = true;
	/** Account the remembered GE slots belong to. */
	private long geAccountHash = -1L;
	/** Ticks after LOGGED_IN during which GE slot replays only set the baseline (the RuneLite GE plugin uses 2). */
	static final int GE_LOGIN_BURST_TICKS = 2;
	/** Minimum ticks between two ge_progress rows for one slot (100 ticks = 1 minute). */
	static final int GE_PROGRESS_MIN_TICKS = 100;
	/** Shop interface (SHOPMAIN 300) open — so a Buy/Sell menu click is a general-store transaction. */
	private volatile boolean shopOpen;

	/** Is a shop interface open right now? Read by the overlays to decide whether to draw. */
	boolean isShopOpen()
	{
		return shopOpen;
	}

	/**
	 * Where the shop's item grid ENDS on screen, so an overlay can sit directly beneath it.
	 *
	 * RuneLite draws overlays over the interface, never inside it, so "below the items" has to be
	 * computed from the live widget each frame — the shop window moves with the client size and the
	 * fixed/resizable layout. Child 16 is the item grid (the same child the stock reader uses).
	 *
	 * Returns null when the shop is closed or the widget is not laid out yet; the caller then falls
	 * back to its own anchor rather than drawing at a stale position.
	 */
	java.awt.Point shopItemsBottomLeft()
	{
		if (client == null || !shopOpen)
		{
			return null;
		}
		net.runelite.api.widgets.Widget grid = client.getWidget(SHOP_GROUP_ID, 16);
		if (grid == null || grid.isHidden())
		{
			return null;
		}
		net.runelite.api.Point loc = grid.getCanvasLocation();
		if (loc == null || grid.getHeight() <= 0)
		{
			return null;
		}
		// ANCHOR TO THE GRID BOX, NOT THE ITEMS. Measured twice on a live client 2026-09-03:
		//   - grid bottom alone put the panels ON the "Value check" / "Quantity" controls, because
		//     the widget box extends past them;
		//   - lowest-occupied-item put them INSIDE the grid as soon as the shop gained a third row.
		// The grid box is stable whatever the stock count, so use it and clear the control row by a
		// fixed margin measured from that same box.
		int gridBottom = loc.getY() + grid.getHeight();
		// RIGHT EDGE, not a fraction of the width. The caller subtracts its own panel width, so both
		// panels end flush with the shop's right side however wide each one is.
		//
		// Prefer the SHOP WINDOW's right edge over the item grid's: measured on a live client the
		// grid box ends ~58px inside the window, so aligning to it left the panels short of the
		// right side that was asked for. Fall back to the grid when the root widget is unreadable.
		int right = loc.getX() + grid.getWidth();
		net.runelite.api.widgets.Widget root = client.getWidget(SHOP_GROUP_ID, 0);
		if (root != null && !root.isHidden() && root.getCanvasLocation() != null && root.getWidth() > 0)
		{
			right = root.getCanvasLocation().getX() + root.getWidth();
		}
		int gridRight = right - 10;
		// +58 clears the control row entirely. Measured: the grid box bottom sits ~14px above the
		// "Value check" / "Quantity" row, which is ~26px tall, so +30 landed ON it (seen live).
		return new java.awt.Point(gridRight, gridBottom + 58);
	}

	/** Test seam: drive the shop-open gate without a game client. */
	void setShopOpenForTest(boolean open)
	{
		shopOpen = open;
	}

	/** Test seam: grant the server-side store-tools half of the gate. */
	void setStoreToolsForTest(boolean on)
	{
		serverStoreToolsEnabled = on;
	}

	// Store-transfer counterparty inference: while a shop is open, accumulate every nearby player seen
	// (rsn -> [closestTileDist, firstTick, lastTick, combatLevel]). Attached to the store event as `nearby`
	// so an UNTRACKED receiver of the general-store method (staff sells cheap, receiver buys it out) can be
	// inferred from who stood there over the visit — presence + persistence + proximity. Reset on shop open.
	private final Map<String, int[]> shopVisitNearby = new LinkedHashMap<>();
	/**
	 * Item -> quantity in the SHOP's own stock, from the last container change while a shop is open.
	 *
	 * This is what makes the counterparty visible on the general-store method. The staff account sells
	 * an item into the shop and an untracked player buys it out; that second half is invisible in the
	 * staff account's own inventory, so the store event alone can never say who received the value.
	 * The shop container CAN see it: the stock of an item we just sold goes DOWN when somebody takes it.
	 * Pairing that moment with who is standing there names the receiver.
	 */
	private final Map<Integer, Integer> shopStock = new java.util.HashMap<>();
	/** Items this visit's own sells put INTO the shop — only these are watched for a taker. */
	private final java.util.Set<Integer> soldThisVisit = new java.util.LinkedHashSet<>();
	/**
	 * Of those, the items the shop already stocked when we sold (stock > 0 at the click): default stock.
	 * The shop normalises default stock back down one at a time within seconds of a sale (F-A1, rig
	 * 2026-09-27: Pot 6->5 after every sale), which is not a customer.
	 */
	private final java.util.Set<Integer> defaultStockSoldThisVisit = new java.util.HashSet<>();
	/** Client tick of our most recent sell of each item this visit, so a store_taken can say how soon after it came. */
	private final java.util.Map<Integer, Integer> lastSellTickThisVisit = new java.util.HashMap<>();
	/** Name of the NPC we were trading with when the shop opened. A buyer must be trading with the same NPC. */
	String shopkeeperName;
	/** The shopkeeper NPC itself (by index). with_shopkeeper compares identity, never the name: two NPCs can share a name. */
	int shopkeeperIndex = -1;
	/**
	 * Nearby players AT THE MOMENT of the transaction, as opposed to across the whole visit.
	 *
	 * A visit-wide list answers "who was around at some point", which is a weaker claim than the one a
	 * dispute needs. This is sampled when the buy/sell actually fires, so the event can state who was
	 * present AT the transaction and for how long around it.
	 */
	private volatile List<Map<String, Object>> nearbyAtTx;
	// ---- STORE RESET CLOCK ----
	//
	// A general store's stock cycles on a fixed 60s clock. An item put into the shop disappears at the
	// next tick of that clock, NOT 60s after it was sold — so an item sold 1s after a reset is gone in
	// 59s, and one sold at 58s is gone in 2. That difference is the whole risk of the delivery method:
	// a seller currently puts a junk item in and watches it vanish to feel out where the clock is,
	// which is a guess repeated by eye every visit. Measured over 56 real sessions, the gap between the
	// junk probe and the first real item ran 5s to 146s — that spread IS the guesswork.
	//
	// The clock's PHASE is what matters, and one observation fixes it: the moment any tracked item
	// vanishes from the shop, we know a reset just happened, and every reset after that is anchored to
	// it. So a single junk probe pins the phase for the rest of the visit.
	//
	// The anchor is only valid while the shop stays open. On close, hop or logout it is dropped rather
	// than carried, because a stale phase shown as a live countdown is worse than no countdown at all —
	// the user would push a high-value item into a window that has already closed.
	private static final long STORE_RESET_PERIOD_MS = 60_000L;
	private static final int COINS_ITEM_ID = 995;		// infobox icon
	/**
	 * The junk PROBE item — the first thing sold into the shop this visit.
	 *
	 * Its disappearance is the reset (see handleShopStockChanged). Everything sold after it is real
	 * merchandise whose disappearance means a customer, so only this one may move the clock.
	 */
	private int storeProbeItem;
	/** Item id of our most recent own store buy, and when — see the buy-back note in onMenuOptionClicked. */
	private int lastSelfBuyItem;
	private long lastSelfBuyAtMs;
	/** How long a self-buy suppresses the counterparty inference for that item. */
	private static final long SELF_BUY_SUPPRESS_MS = 3_000L;
	/** Wall-clock ms of an OBSERVED reset; 0 = phase unknown, show nothing. */
	private long storeResetAnchorMs;
	/** When the shop container last changed at all. The anchor needs two observations, not one. */
	private long lastStockChangeMs;
	private static final int MAX_NEARBY_TRACKED = 64;	// bound the per-visit map
	private static final int NEARBY_FIELD_CAP = 24;		// bound the emitted nearby[] list
	/** Trade offer + counterparty captured at the confirm screen, emitted as a "trade" event on accept. */
	private volatile List<Map<String, Object>> pendingTradeGiven;
	private volatile String pendingCounterparty;
	// WAVE 1b: the counterparty's side (what WE receive), read from the confirm-screen YOU_WILL_RECEIVE widget
	// at 334-load. pendingTradeReceived = structured [{id,qty}] if the widget exposes item children;
	// pendingReceivedText = the raw "Blood rune x 100 ..." summary as a lossless fallback when it does not.
	private volatile List<Map<String, Object>> pendingTradeReceived;
	private volatile String pendingReceivedText;

	// Store transacted-price capture: a store buy/sell click snapshots the pre-transaction coin count and
	// arms this pending; the next INVENTORY change reads the post count and the |delta| is the exact gp that
	// changed hands (see store-price-feasibility.md). Emit is deferred to that inventory change, not the click.
	private volatile StorePending storePending;
	/** Ticks after which an unresolved store pending is stale (a failed click fires no inventory change). */
	static final int STORE_PENDING_MAX_TICKS = 3;
	/** No item-count baseline was captured, so the executed quantity cannot be measured. */
	static final long UNKNOWN_ITEM_COUNT = -1L;
	/**
	 * Colour/formatting tags in a menu option, e.g. the "&lt;col=ff9040&gt;" the client appends to SELL
	 * options. Stripped before the quantity is read — the tag's own hex digits are not a quantity.
	 */
	private static final Pattern MENU_TAG = Pattern.compile("<[^>]*>");
	/**
	 * The quantity in a store menu option, once tags are stripped: the trailing run of digits.
	 * Capped at 9 digits so a pathological string cannot overflow the parse.
	 */
	private static final Pattern TRAILING_QTY = Pattern.compile("(\\d{1,9})\\s*$");
	/** gameval INVENTORY container id (93) — matches ItemContainerChanged.getContainerId(), not legacy InventoryID. */
	private static final int INVENTORY_CONTAINER_ID = net.runelite.api.gameval.InventoryID.INV;
	/** gameval WORN container id — the equipment container, diffed to emit equip_change. */
	private static final int EQUIP_CONTAINER_ID = net.runelite.api.gameval.InventoryID.WORN;
	/** gameval BANK container id: its first contents after an empty open become the bank-move baseline. */
	private static final int BANK_CONTAINER_ID = net.runelite.api.gameval.InventoryID.BANK;

	/** An armed store buy/sell awaiting its inventory-change resolution. coinsBefore is a long: bank-stack totals overflow int. */
	static final class StorePending
	{
		final String type;			// "store_buy" | "store_sell"
		final int item;
		final int qty;				// the CLICK's quantity (intent) — see qtyExecuted resolution
		final long coinsBefore;
		final long itemBefore;		// item count at arm time, so the EXECUTED quantity can be measured
		final int tick;				// client tick at arm time, for staleness + same-tick-batch detection
		final boolean ambiguous;	// a second click landed on the same tick → delta merges two txns, omit price
		/**
		 * True when {@code qty} is the SUM of several same-tick clicks rather than one click's quantity.
		 * The coin delta is still ground truth, so {@code gp_total} stays exact — but a click can FAIL
		 * (out of stock, full inventory, not enough coins) while still having been counted here, so the
		 * merged quantity is click INTENT, not confirmed executed quantity. Any figure divided by it
		 * (i.e. unit_price_gp) would therefore be wrong — half price if one of two clicks failed — so it
		 * is omitted. Never derive a unit price from an uncertain denominator.
		 */
		final boolean qtyMerged;

		StorePending(String type, int item, int qty, long coinsBefore, int tick, boolean ambiguous)
		{
			this(type, item, qty, coinsBefore, tick, ambiguous, false);
		}

		StorePending(String type, int item, int qty, long coinsBefore, int tick, boolean ambiguous, boolean qtyMerged)
		{
			this(type, item, qty, coinsBefore, UNKNOWN_ITEM_COUNT, tick, ambiguous, qtyMerged);
		}

		StorePending(String type, int item, int qty, long coinsBefore, long itemBefore, int tick,
			boolean ambiguous, boolean qtyMerged)
		{
			this.type = type;
			this.item = item;
			this.qty = qty;
			this.coinsBefore = coinsBefore;
			this.itemBefore = itemBefore;
			this.tick = tick;
			this.ambiguous = ambiguous;
			this.qtyMerged = qtyMerged;
		}
	}

	// ---- off-book value events: drop / pickup / alch. A menu click arms an inventory-delta pending, resolved
	// on the next INVENTORY change from the item-count delta (reuses the store-pending arm/resolve discipline). ----
	static final int INV_DELTA_PENDING_MAX_TICKS = 5;	// pickup: wait a few ticks (a "Take" may land after a walk)
	static final int ALCH_PENDING_MAX_TICKS = 2;		// an alch lands next tick — short window, nothing to wait for
	static final int DROP_PENDING_MAX_TICKS = 16;		// drop: the warning dialog can sit ~10s before the player confirms
	static final int DROP_SPAWN_MAX_DIST = 2;			// own drops land on/adjacent to the player's tile
	static final int DROP_CORROBORATION_MAX_TICKS = 2;	// spawn + inventory loss must land within ~1 tick of each other
	/**
	 * Armed off-book pendings, OLDEST FIRST.
	 *
	 * This was a SINGLE slot, and that silently lost drops. A drop trade is N items dropped in quick
	 * succession, so the second Drop click armed a new pending over the first before its ground spawn had
	 * confirmed it, and the first drop emitted NOTHING — no error, no trace anywhere downstream. Measured
	 * with the real callback order: four rapid drops produced ONE event; three were lost. The inventory
	 * still showed the items gone, so a periodic snapshot looks perfectly consistent while the telemetry is
	 * simply incomplete. That is why a snapshot can confirm final state but can never certify event capture.
	 *
	 * Bounded at INV_PENDING_MAX so a stream of clicks that never land cannot grow without limit; the OLDEST
	 * is evicted first, which is also the one most likely to be stale. Resolution walks the queue and matches
	 * by ITEM ID, so out-of-order ground spawns (packet order is the server's choice) still find their own
	 * pending rather than consuming someone else's.
	 */
	static final int INV_PENDING_MAX = 28;
	/** bank_session: at most this many items per list; the rest are dropped and the row says truncated. */
	static final int BANK_SESSION_ITEM_CAP = 50;
	/** Own dropped piles still believed to be on the ground. Same bound as the inventory that fed them. */
	static final int GROUND_TRACK_MAX = 28;
	/**
	 * How far before its reported despawn tick a removal counts as EARLY rather than the timer expiring.
	 * The client's despawnTime is authoritative for the pile, so this is only slack for tick rounding.
	 */
	static final int GROUND_EARLY_MARGIN_TICKS = 2;
	/**
	 * Below this many ticks on the ground, a removal may NOT be called `despawn_timer`.
	 *
	 * FAIL-CLOSED GUARD, not a model of the game. Its only effect is `despawn_timer` -> `unknown`;
	 * no path here produces a stronger claim than the one it replaces. Measured evidence: the real
	 * despawn is about 300 ticks (live read 2026-09-12, tick=244 against despawn_time=530), and 357
	 * of 358 correct fleet rows read exactly 299. 100 is a loose floor that cannot reject a genuine
	 * full-length expiry. An item with a shorter natural lifetime would report `unknown`, which is
	 * the acceptable direction to be wrong in.
	 */
	static final int GROUND_TIMER_MIN_TICKS = 100;
	/**
	 * How long a removal may wait for its pickup to resolve. INV_DELTA_PENDING_MAX_TICKS is the
	 * window the pickup pending itself lives for, so waiting the same span plus one tick cannot
	 * outlive the evidence it is waiting for: if the pickup has not landed by then it never will.
	 */
	static final int REMOVAL_RESOLVE_MAX_TICKS = INV_DELTA_PENDING_MAX_TICKS + 1;
	private final java.util.Deque<DroppedGroundItem> groundDrops = new java.util.ArrayDeque<>();
	/**
	 * Telekinetic Grab sightings aimed at the tile of a pile we track. Only tracked tiles are kept, one
	 * row per projectile or impact, pruned to the attribution window and capped, so a busy scene of
	 * casters cannot grow it. Read once per removal to flag a grab from range.
	 */
	final java.util.List<DropCandidates.TelegrabSighting> telegrabSightings = new ArrayList<>();
	static final int TELEGRAB_SIGHTING_CAP = 16;
	/** Projectiles already recorded. ProjectileMoved fires every client cycle for one projectile. */
	private final java.util.Set<Object> telegrabProjectilesSeen =
		java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
	/**
	 * Removals seen but NOT yet finalized, because a local Take could still explain them.
	 *
	 * The client fires ItemDespawned the instant a pile leaves the ground, and the inventory change
	 * that proves WE took it arrives after. Publishing the cause at despawn time therefore states a
	 * conclusion before the evidence exists, and a real self-pickup went out as `removed_early`
	 * (measured live 2026-09-12 07:39:40). A removal with a plausible pending Take waits here for a
	 * bounded number of ticks, then finalizes on whatever actually resolved. Exactly one
	 * `ground_removed` is ever emitted per pile lifetime: the entry is removed from this map as it
	 * fires, and the pile is already out of `groundDrops` by then.
	 */
	private final java.util.Map<DroppedGroundItem, Integer> pendingRemovals =
		new java.util.LinkedHashMap<>();
	/**
	 * Set while the scene is reloading or the account is leaving. A scene reload despawns EVERY ground
	 * pile at once for reasons unrelated to anyone taking them, so removals seen in that state are
	 * reported as UNKNOWN, never as early removal.
	 */
	private volatile boolean groundObservationUnreliable;
	// one inventory's worth of clicks; drops cannot exceed it
	private final java.util.Deque<InvDeltaPending> invDeltaPendings = new java.util.ArrayDeque<>();

	// ---- ROUND 8: THE SCENE IS THE SOURCE OF TRUTH ----

	/**
	 * What the GAME'S OWN WORLD STATE says about one item id on one tile.
	 *
	 * ROUND 8, FINDING R4. Seven rounds of this resolver inferred "is our pile still on the ground?"
	 * from our own event bookkeeping, and every round a pile the bookkeeping never knew about broke
	 * it: another player's pile, a pile from a session that ended while the pile was still down, a
	 * pile dropped before the token was linked. The game already knows the answer, and the scene is
	 * where it keeps it.
	 *
	 * THREE ANSWERS, AND THE THIRD IS WHY THIS IS AN ENUM. A tile outside the loaded scene is not an
	 * empty tile. Collapsing UNKNOWN into NONE would turn every world hop, every scene reload and
	 * every walk out of render distance into "your pile is gone", which is a new false negative in
	 * place of the old false positive. UNKNOWN means the scene cannot answer, and every caller here
	 * treats it as "change nothing".
	 */
	enum ScenePileState
	{
		/** The tile is in the loaded scene and holds at least one item of that id. */
		PRESENT,
		/** The tile is in the loaded scene and holds NO item of that id. */
		NONE,
		/** The scene cannot answer: no client, no world view, the tile is out of scene, or it is null. */
		UNKNOWN
	}

	/**
	 * ROUND 9 — WHAT THE SCENE SAYS ABOUT ITEMS OF ONE ID ON ONE TILE.
	 *
	 * Round 8 asked the scene a YES/NO question — "is an item of this id still on this tile?" — and
	 * FINDING R7 is the price of that question: it cannot tell OUR OWN second pile from a
	 * stranger's. Ten of our own piles on one tile made the first nine despawns look exactly like an
	 * untracked rival, so nine of ten evidence rows were never published and the surviving row
	 * described the wrong pile.
	 *
	 * A COUNT can tell them apart. With |S| of our own records still on that tile, a remaining count
	 * BELOW |S| means one of OURS left; a count at or above |S| means a stranger's pile may be the
	 * one that went. The QUANTITIES say WHICH of ours, where the reader can supply them.
	 */
	static final class SceneTileItems
	{
		/** UNKNOWN when the scene could not be read; NONE for a count of zero; PRESENT otherwise. */
		final ScenePileState state;
		/** Piles of that item id on that tile, excluding the one the caller asked to exclude. */
		final int count;
		/** One quantity per counted pile, or null when this reader cannot say. */
		final long[] quantities;

		private SceneTileItems(ScenePileState state, int count, long[] quantities)
		{
			this.state = state;
			this.count = count;
			this.quantities = quantities;
		}

		static SceneTileItems unknown()
		{
			return new SceneTileItems(ScenePileState.UNKNOWN, 0, null);
		}

		/** @param quantities one per pile, or null when the reader cannot expose them. */
		static SceneTileItems of(int count, long[] quantities)
		{
			return new SceneTileItems(
				count <= 0 ? ScenePileState.NONE : ScenePileState.PRESENT,
				Math.max(count, 0),
				quantities);
		}
	}

	/**
	 * Reads the scene, so a test can model a rival pile, our pile going, or a tile out of scene.
	 * The live implementation is {@link #readSceneTileItems}; nothing else in this class reads the
	 * scene.
	 */
	interface SceneGroundReader
	{
		/**
		 * @param excluding a TileItem to IGNORE while counting, or null. See
		 *                  {@link #scenePileState} for why the despawn path passes one.
		 */
		SceneTileItems itemsOnTile(int item, int x, int y, int plane, Object excluding);
	}

	/** Defaults to the live client read; swapped in tests through {@link #setSceneReaderForTest}. */
	private volatile SceneGroundReader sceneReader = this::readSceneTileItems;

	void setSceneReaderForTest(SceneGroundReader r)
	{
		sceneReader = r == null ? this::readSceneTileItems : r;
	}

	/**
	 * The callers' view of the scene. Never throws; answers UNKNOWN when it cannot read.
	 *
	 * WHY {@code excluding} EXISTS. RuneLite fires ItemDespawned from inside the client's own scene
	 * update, and nothing in the 1.12.39 obfuscated client proves whether the TileItem being
	 * despawned has already left the tile's item layer at the moment the event is posted. If it has
	 * not, a scene read at despawn time would still see the very pile that is going and answer
	 * PRESENT for it, which would make EVERY despawn look ambiguous. Excluding that one TileItem by
	 * OBJECT IDENTITY makes the answer the same either way, so this does not depend on an ordering
	 * we cannot verify without a live client. The poll path passes null: nothing is going there.
	 */
	ScenePileState scenePileState(int item, int x, int y, int plane, Object excluding)
	{
		return sceneTileItems(item, x, y, plane, excluding).state;
	}

	/** The counting form of {@link #scenePileState}. Never throws; answers UNKNOWN when it cannot read. */
	SceneTileItems sceneTileItems(int item, int x, int y, int plane, Object excluding)
	{
		SceneGroundReader r = sceneReader;
		if (r == null)
		{
			return SceneTileItems.unknown();
		}
		try
		{
			SceneTileItems s = r.itemsOnTile(item, x, y, plane, excluding);
			return s == null || s.state == null ? SceneTileItems.unknown() : s;
		}
		catch (RuntimeException e)
		{
			// A scene read that threw told us nothing. UNKNOWN is the only honest answer, and it is
			// the one that changes no behaviour.
			return SceneTileItems.unknown();
		}
	}

	/**
	 * READ-ONLY scene lookup, RuneLite 1.12.39 API, CLIENT THREAD ONLY.
	 *
	 * Both callers run on the client thread already: onItemDespawned is an event subscriber and
	 * pollDropSession runs from onGameTick. Nothing here mutates the scene or the client.
	 *
	 * The route is: Client.getTopLevelWorldView() -> WorldView.getScene() ->
	 * Scene.getTiles()[plane][sceneX][sceneY] -> Tile.getGroundItems() -> List&lt;TileItem&gt;, with
	 * the world-to-scene conversion done by LocalPoint.fromWorld(WorldView, x, y), which already
	 * returns null for a point outside the scene (verified in the 1.12.39 runelite-api bytecode:
	 * fromWorld calls WorldPoint.isInScene first and returns null when it is false).
	 *
	 * EVERY failure to read answers UNKNOWN, never NONE. A missing client, a missing world view, a
	 * plane outside the tile array, a null tile and a null ground-item list are all "the scene
	 * cannot tell us", and the difference between that and "the tile is empty" is the entire point
	 * of this method.
	 */
	private SceneTileItems readSceneTileItems(int item, int x, int y, int plane, Object excluding)
	{
		if (client == null)
		{
			return SceneTileItems.unknown();
		}
		net.runelite.api.WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return SceneTileItems.unknown();
		}
		net.runelite.api.Scene scene = wv.getScene();
		if (scene == null)
		{
			return SceneTileItems.unknown();
		}
		net.runelite.api.coords.LocalPoint lp =
			net.runelite.api.coords.LocalPoint.fromWorld(wv, x, y);
		if (lp == null)
		{
			return SceneTileItems.unknown();	// the tile is not in the loaded scene
		}
		net.runelite.api.Tile[][][] tiles = scene.getTiles();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return SceneTileItems.unknown();
		}
		int sx = lp.getSceneX();
		int sy = lp.getSceneY();
		net.runelite.api.Tile[][] atPlane = tiles[plane];
		if (atPlane == null || sx < 0 || sx >= atPlane.length)
		{
			return SceneTileItems.unknown();
		}
		net.runelite.api.Tile[] col = atPlane[sx];
		if (col == null || sy < 0 || sy >= col.length)
		{
			return SceneTileItems.unknown();
		}
		net.runelite.api.Tile tile = col[sy];
		if (tile == null)
		{
			return SceneTileItems.unknown();
		}
		java.util.List<net.runelite.api.TileItem> items = tile.getGroundItems();
		if (items == null)
		{
			// The tile exists but carries no item layer at all. RuneLite's Tile.getGroundItems
			// returns null when there is no item layer, which is a genuinely EMPTY tile, not a
			// failure to read. Verified in the 1.12.39 injected client: the null return happens only
			// on a missing ItemLayer.
			return SceneTileItems.of(0, new long[0]);
		}
		// ROUND 9. COUNT them, do not stop at the first match. The count is what separates our own
		// second pile from a stranger's, and TileItem.getQuantity() is what says WHICH of ours went.
		// Both signatures read from runelite-api-1.12.39 with javap: getId()I and getQuantity()I.
		java.util.List<Long> qty = new ArrayList<>();
		for (net.runelite.api.TileItem it : items)
		{
			if (it != null && it != excluding && it.getId() == item)
			{
				qty.add((long) it.getQuantity());
			}
		}
		long[] q = new long[qty.size()];
		for (int i = 0; i < q.length; i++)
		{
			q[i] = qty.get(i);
		}
		return SceneTileItems.of(q.length, q);
	}

	/**
	 * A ground item WE dropped, tracked from its own-tile spawn until it leaves the ground.
	 *
	 * This exists to answer one question honestly: did the item we dropped stay there, or did it go?
	 * It deliberately CANNOT answer who took it. A ground item's pickup happens in the other player's
	 * client and nothing in our packet stream names the taker, so no field here ever carries a
	 * recipient. What we can separate is EARLY removal from the natural despawn TIMER, and even that
	 * separation is refused whenever the scene reloaded or the tile left render distance, because both
	 * fire ItemDespawned for reasons that have nothing to do with anyone taking the item.
	 */
	static final class DroppedGroundItem
	{
		final int item;
		final long qty;
		final int x;
		final int y;
		final int plane;
		final Map<String, Object> location;	// {region_id, plane} — same shape the drop event carries
		final int dropTick;
		/** Client's own despawn deadline for this pile, in ticks. -1 when the client did not report one. */
		final int despawnTick;
		/** Set when OUR OWN account picked this item back up, so the despawn is explained, not ambiguous. */
		volatile boolean selfPickedUp;
		/**
		 * How many times settlement has considered this parked removal. A frozen tick counter makes
		 * the elapsed-tick test unreachable, so the attempt count is the backstop that guarantees
		 * every parked removal is eventually published.
		 */
		volatile int settleAttempts;
		/**
		 * Set when a local Take of this item could have been aimed at this pile.
		 *
		 * Once set it never clears. A pile a local Take named may have been recovered by us, so
		 * `removed_early` - which reads as somebody else took it - is unsupported, and so is
		 * `despawn_timer`. The pile reports `unknown` unless the pickup is proven exactly.
		 */
		volatile boolean takeArmed;
		/**
		 * Tick a PROVISIONAL self-pickup mark landed, or -1 when no such mark is outstanding.
		 *
		 * A mark set while the pile is STILL ON THE GROUND is a guess: the gain that produced it can
		 * equally have come from a trade, a store sale, or an untracked pile of the same item. A pile
		 * we really recovered leaves the ground at once, so a pile still lying there more than
		 * GROUND_EARLY_MARGIN_TICKS later was not the source of that gain. The mark is then withdrawn
		 * and the row falls to `unknown`, which `takeArmed` already guarantees. Withdrawing can only
		 * WEAKEN a verdict; nothing on this path ever sets `selfPickedUp`.
		 */
		volatile int provisionalMarkTick = -1;
		/**
		 * Set when this pile was PARKED because a local Take of its item was live at the moment the
		 * pile left the ground.
		 *
		 * The live Take is the reason the removal was held, so it is a fact about the REMOVAL and it
		 * must be read at removal time. A Take can go stale, or be pruned by an unrelated inventory
		 * change, while the removal is still parked. Asking `hasArmedPickupFor` later then answers
		 * "no Take" about a pile whose removal a Take really could explain, and the deadline reset
		 * publishes `despawn_timer` - "nobody took it" - for our own recovery after a long walk, or
		 * for a customer collection. This flag remembers the removal-time answer, so the reset
		 * refuses and the row stays `unknown`. Like every other condition on that path it can only
		 * WEAKEN the verdict.
		 */
		volatile boolean parkedForLiveTake;
		/**
		 * Set when this record was minted for a drop the game reported as a MERGE into an existing
		 * stack, so it is a SHADOW of a physical pile rather than a pile of its own.
		 *
		 * ROUND 7, FINDING R2. The merge branch in trackGroundDrop is scoped to a running session,
		 * so with no session a stackable dropped twice onto one tile leaves two records for ONE
		 * physical pile. The single despawn consumes one and the other lingers on a tile that is now
		 * physically empty: the R1 phantom. A record carrying this flag is therefore known NOT to
		 * stand for an independent pile, which is what lets the despawn resolver tell the R1 phantom
		 * (one physical pile, two records) apart from the R2 case (two physical piles of an
		 * unstackable, only one of them ours). Without the distinction, closing R2 re-opens R1.
		 */
		volatile boolean mergeShadow;
		/**
		 * Set at despawn time when an OWNED and an UNOWNED real record both matched this item and
		 * tile, so which physical pile actually went is unknowable.
		 *
		 * ROUND 7, FINDING R2. The row this record publishes then carries NO drop_session_id and no
		 * drop_seq, because a guess about which pile went is a fabricated attribution. Carried on
		 * the record rather than passed as an argument so a PARKED removal, settled ticks later,
		 * publishes the same refusal the despawn decided.
		 */
		volatile boolean attributionAmbiguous;
		/**
		 * ROUND 9. Set when the count proved one of OUR OWN piles left this tile, but two or more of
		 * our own records on it carry the SAME quantity, so which of those equal piles went cannot
		 * be told apart.
		 *
		 * This is WEAKER than {@link #attributionAmbiguous} on purpose, and the difference is the
		 * whole point. The pile that left was certainly OURS and its quantity is certainly this one,
		 * so the row keeps its drop_session_id and its qty and stays honest evidence. What is
		 * uncertain is only WHICH of our equal piles it was, which is drop_seq and therefore
		 * ticks_on_ground. The row says so in `attribution_uncertain`, and the SESSION is not marked
		 * unprovable: two of our own equal piles are not a stranger.
		 */
		volatile boolean qtyTieUncertain;

		DroppedGroundItem(int item, long qty, int x, int y, int plane, Map<String, Object> location,
			int dropTick, int despawnTick)
		{
			this.item = item;
			this.qty = qty;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.location = location;
			this.dropTick = dropTick;
			this.despawnTick = despawnTick;
		}
	}

	static final class InvDeltaPending
	{
		final String base;			// "drop" | "pickup" | "alch"
		final int item;
		final String spell;			// "high" | "low" for alch, else null
		/**
		 * Count of `item` in the inventory at click time. RAISED when another pending consumes an
		 * arrival of the same item, so this pending cannot claim that same arrival. Two Takes of one
		 * item armed on the same tick share a baseline, and a SINGLE arriving item then satisfied
		 * both: two `pickup` rows for one real recovery. Not final for exactly that reason.
		 */
		volatile long beforeCount;
		final long beforeCoins;		// carried coins at click time (alch gp confirmation)
		final Map<String, Object> location;	// {region_id, plane} at click, or null (alch)
		final Boolean wilderness;	// drop only: true if dropped inside the Wilderness (instantly visible)
		final int tick;
		// drop only: tick a matching ground spawn was seen at our tile BEFORE the inventory loss was visible.
		// Makes resolution order-independent — whichever of (spawn, inventory-decrement) the client processes
		// first records itself; the second completes the emit. -1 = no corroboration yet.
		volatile int spawnCorroboratedTick = -1;
		// drop only: the WORLD tile the confirming ground spawn landed on, so the drop row carries the pile's
		// exact tile itself. -1 = the spawn came with no tile (a test overload), and the row then omits it.
		volatile int spawnX = -1;
		volatile int spawnY = -1;
		volatile int spawnPlane = -1;
		/**
		 * pickup only: the WORLD tile of the ground pile this Take was clicked on, or -1 when the
		 * client did not give us one. Item id alone cannot tell two piles of the same item apart,
		 * and a drop trade is exactly two piles of the same item.
		 */
		final int takeX;
		final int takeY;
		final int takePlane;

		/** No tile: an inventory action, or a Take the client gave us no usable coordinates for. */
		InvDeltaPending(String base, int item, String spell, long beforeCount, long beforeCoins,
			Map<String, Object> location, Boolean wilderness, int tick)
		{
			this(base, item, spell, beforeCount, beforeCoins, location, wilderness, tick, -1, -1, -1);
		}

		InvDeltaPending(String base, int item, String spell, long beforeCount, long beforeCoins,
			Map<String, Object> location, Boolean wilderness, int tick,
			int takeX, int takeY, int takePlane)
		{
			this.takeX = takeX;
			this.takeY = takeY;
			this.takePlane = takePlane;
			this.base = base;
			this.item = item;
			this.spell = spell;
			this.beforeCount = beforeCount;
			this.beforeCoins = beforeCoins;
			this.location = location;
			this.wilderness = wilderness;
			this.tick = tick;
		}
	}

	// ---- death items-lost: read pre-death inv+equip live at ActorDeath, resolve the loss diff once the
	// containers have settled (a few ticks later, at syncTask) or on logout — whichever comes first. ----
	static final int DEATH_SETTLE_TICKS = 4;	// item removal follows the death animation by a few ticks
	private volatile DeathPending deathPending;

	static final class DeathPending
	{
		final Map<String, Object> location;	// {region_id, plane} at death, or null
		final String kind;					// "wilderness" | "pvp" | "safe" (only wilderness/pvp is a transfer)
		final Map<Integer, Long> preCounts;	// merged inventory + equipment item -> qty just before death
		final int tick;

		DeathPending(Map<String, Object> location, String kind, Map<Integer, Long> preCounts, int tick)
		{
			this.location = location;
			this.kind = kind;
			this.preCounts = preCounts;
			this.tick = tick;
		}
	}

	// ---- WAVE 2: real-time sync ----
	// On any emitEvent we flush live instead of waiting for the 5s eventFlushTask. A burst (e.g. rapid chat
	// lines) is micro-coalesced into a single ~1s window so it becomes ONE /event-ingest POST, not one HTTP
	// call per line (still sub-second-to-~1s = real-time). The first event opens the window; the rest ride it.
	private static final long EVENT_COALESCE_MILLIS = 1000L;
	long eventCoalesceMillis = EVENT_COALESCE_MILLIS;	// field (not const) so tests can shrink the window
	private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
	// State-changing events whose wealth/state must land immediately also force a snapshot. Chat / level_up
	// are excluded (too frequent / not wealth-moving); login is bound separately by syncTask's new-login send.
	private static final java.util.Set<String> SNAPSHOT_TRIGGER_EVENTS = new java.util.HashSet<>(
		java.util.Arrays.asList("trade", "ge_buy", "ge_sell", "ge_cancel", "store_buy", "store_sell", "death", "drop", "alch"));

	/**
	 * Trade-screenshot capture is part of core sync (2026-09-02): active whenever the activity log is —
	 * i.e. a valid link token is set — with no separate opt-in. osrsbestinslot.com can still force it off
	 * per token via the X-Screenshots response header.
	 */
	boolean screenshotsEnabled()
	{
		return activityLogActive() && !serverScreenshotsDisabled;
	}

	// ---- store delivery-proof: burst frame capture (Task B2) ----
	// While a shop is open, sample the render at a WALL-CLOCK rate into a bounded StoreVisitClip. On
	// shop-close, if the visit had a buy/sell the frames go to the (B3) uploader; otherwise they are
	// dropped. Frames are captured raw here — no video encode in the plugin; the server stitches them.
	// SAMPLE RATE — raised 1 -> 3 on 2026-09-02, with the retained window cut 120s -> 40s so the ring
	// stays at 120 frames. A general-store delivery is EVIDENCE a customer may dispute, and at 1fps the
	// click that moves an item can fall between two frames entirely: the quantity menu, the click and
	// the inventory change all happen inside one second.
	//
	// 30fps — the client's own render rate, so the clip IS the delivery rather than a flipbook of it.
	// 1 -> 3 -> 8 were each rejected on review; at 8fps (125ms) the cursor still steps between positions
	// and the review verdict was "it doesn't feel like a clip, its absolutely lagging". 30fps is 33ms,
	// which is motion. There is no rate above this worth having: the client does not render faster.
	//
	// Paying for it: 30fps x 12s = 360 frames, and 360 frames only fitted the old 12MB burst cap at a smaller
	// frame. Measured on a real captured frame: 768px/q0.70 = 45KB (16.2MB — over), 704px/q0.55 = 30KB
	// (10.8MB — fits). The 704px/q0.55 frame was checked for LEGIBILITY, not just size: shop item text
	// and chat remain readable, which is the property MAX_FRAME_WIDTH exists to protect.
	//
	// 360 frames is the per-VISIT frame budget. It used to be the NEWEST 12 seconds of the visit, and
	// that lost the transaction whenever the visit ran longer than 12 seconds after the last sale
	// (Arengees 2026-09-25: sells 16:37:59-16:39:51, frames 16:39:55-16:40:06, no sale on video).
	// The same budget is now spent by StoreVisitClip on the visit start, a window around every
	// buy / sell / taken moment, and a 1fps baseline between them.
	static final int CLIP_FPS = 30;					// sample rate (constant, NOT a user setting)
	static final int CLIP_SECONDS = 12;				// budget expressed as seconds at CLIP_FPS
	static final int MAX_CLIP_FRAMES = CLIP_FPS * CLIP_SECONDS;	// per-visit frame budget = 360 frames
	// Task-0 legibility verdict (PRD): 768px keeps store text readable at the server stitch size.
	// (Plan body text says 640px; 768 is the ratified Task-0 override.) Lowered 768 -> 704 on 2026-09-02
	// to pay for 30fps: 360 frames only fitted the old 12MB burst cap at ~30KB/frame. 704 was chosen over 640
	// because it was CHECKED for legibility — shop item text and chat still read at 704/q0.55, and store
	// text is the thing being proven. Below 704 that stops being true, so this is a floor, not a knob.
	static final int MAX_FRAME_WIDTH = 704;
	// Server ingest caps (mirror /store-frames-ingest): a JPEG over this is dropped; the visit keeps the
	// newest suffix that fits both the frame-count cap and this total-bytes cap.
	//
	// ⚠ MAX_CLIP_BURST_BYTES bounds the WHOLE VISIT here, not one POST. Since the upload is chunked
	// (CLIP_CHUNK_FRAMES), the server's per-request limit is never the binding constraint — a
	// 40-frame chunk at ~45KB is ~1.8MB. The visit budget is 16 MiB (owner decision PIO-014, 2026-09-27,
	// was 12MB): a 360-frame visit at ~44KB/frame (~15.8MB) now keeps every frame at the current quality.
	// It caps what one shop visit can ever cost in memory and upload.
	static final int MAX_CLIP_FRAME_BYTES = 1_000_000;		// 1MB per frame
	static final int MAX_CLIP_BURST_BYTES = 16 * 1024 * 1024;	// 16 MiB per VISIT (all chunks together)

	/** osrsbestinslot.com can force store-clip capture OFF for a token via the X-Clips response header
	 *  (it can never force it ON — that stays a local opt-in, mirroring serverScreenshotsDisabled). */
	volatile boolean serverClipsDisabled;
	/** Armed between shop-open and shop-close while capture is running. */
	private volatile boolean clipCapturing;
	/** Set the moment a general-store buy/sell fires during the visit — a visit without one is dropped. */
	volatile boolean storeTxThisVisit;
	/** The visit's kept frames (start, every moment, baseline); created per capture, cleared on stop. */
	private volatile StoreVisitClip clipVisit;
	/** Wall time (ms) the frame in flight was sampled at. The frame is kept at THIS time, not its encode time. */
	private volatile long clipFrameSampledAtMillis;
	/** Wall-clock (nanoTime) of the next frame to sample; a render tick before this is skipped. */
	private volatile long nextClipSampleAt;
	/** Set on a sampled tick to request one frame from DrawManager; cleared when that frame arrives. */
	private volatile boolean clipFramePending;
	/** Per-frame render callback, registered with DrawManager only while capturing. */
	private final Runnable clipFrameTick = this::onClipFrameTick;

	// ---- DROP-TRADE PROOF: session lifecycle, segmented capture, pile-centred candidates ----
	//
	// This is a SECOND consumer of the same frame-capture machinery the store path uses, not a rival
	// pipeline. It shares the sampler, the encoder, the downscaler and the multipart upload idiom.
	// What differs is WHAT is retained and WHEN: the store path keeps one 360-frame budget per visit
	// (StoreVisitClip), and a drop trade must keep everything however long the customer takes.

	/** Session state machine. Owns start, the 5-second tail, and which piles are still live. */
	final DropSessionRecorder dropSession = new DropSessionRecorder();
	/** Segmented frame buffer. Bounded to one segment in memory, never the whole session. */
	private volatile DropFrameSegmenter dropSegmenter;
	/** Armed while drop capture is running. Separate from clipCapturing so the two never interfere. */
	private volatile boolean dropCapturing;
	/** Set on a sampled tick to request one frame; cleared when that frame arrives. */
	private volatile boolean dropFramePending;
	/** Wall-clock (nanoTime) of the next drop frame to sample. */
	private volatile long nextDropSampleAt;
	/** Per-frame render callback, registered with DrawManager only while drop capture runs. */
	private final Runnable dropFrameTick = this::onDropFrameTick;
	/**
	 * Adaptive rate controller: 8fps baseline, 30fps around the Drop, the spawn, the removal and a
	 * new drop inside the tail. The sampler still runs at 30fps, because the two seconds BEFORE a
	 * removal cannot be chosen after it happens; this decides which sampled frames survive.
	 */
	private volatile DropCaptureRate dropRate;
	/** Segments uploaded for the CURRENT session, counted so the final manifest can be checked. */
	private final java.util.concurrent.atomic.AtomicInteger dropSegmentsSent =
		new java.util.concurrent.atomic.AtomicInteger();
	/** Segments whose upload FAILED after every retry. A clip missing a segment must say so. */
	private final java.util.concurrent.atomic.AtomicInteger dropSegmentsFailed =
		new java.util.concurrent.atomic.AtomicInteger();
	/**
	 * Live piles of the CURRENT session, keyed exactly as DropSessionRecorder keys them.
	 *
	 * Maps a tracked pile to its session id and drop sequence, which is what lets a removal name the
	 * drop that produced it. Without this a removal can only say "a pile of item X went", and the
	 * manifest could not join one removal to one drop when a session drops the same item twice.
	 */
	private final java.util.Map<DroppedGroundItem, int[]> dropPileSeq =
		java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
	/** Session id each tracked pile belongs to. Parallel to dropPileSeq; separate to keep types simple. */
	private final java.util.Map<DroppedGroundItem, String> dropPileSession =
		java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
	/** Sequence number assigned to the next drop ACTION, read when its pile finally spawns. */
	private volatile int pendingDropSeq;
	/** Session id at the moment of the last drop action, so a late spawn attaches to the right one. */
	private volatile String pendingDropSessionId;
	/**
	 * THE ACCOUNT IDENTITY THIS RECORDING BELONGS TO: the link token as it read when the session
	 * started. Null when no session is bound to an identity.
	 *
	 * WHY IT EXISTS. uploadAllowed() only asks whether SOME valid token is configured. A mid-session
	 * swap from token A to a different valid token B therefore left every gate open, and the frames
	 * captured under A were uploaded with B in the form body. That attributes one account's evidence
	 * to another account, which is an identity-boundary failure, not an accounting untidiness.
	 *
	 * Every drop-proof path now compares the live token against THIS value rather than against the
	 * shape of a token. A mismatch of any kind — a swap, a clear, a malformed value — ends the
	 * session and destroys what it captured.
	 */
	private volatile String dropSessionToken;

	/** Frame rate for drop capture. Same sampler as the store path, so the same constant. */
	static final int DROP_CLIP_FPS = CLIP_FPS;

	/**
	 * ROLLOUT, NOT AUTHORIZATION. Its own per-token policy flag, default OFF.
	 *
	 * Set from the X-Drop-Proof response header, whose ONE source of truth is the `drop_proof` key
	 * in the server's KV policy map. Absent header, absent key, unparseable value and a failed
	 * response all leave this false, so an unknown state fails capture OFF.
	 *
	 * IT AUTHORIZES NOTHING. Whether a `drop_frames` upload is accepted is decided server-side by
	 * the staff check on the token at /store-frames-ingest, which this flag never touches. Rollout
	 * says WHICH granted clients record; authorization says whose recording the server keeps.
	 */
	volatile boolean serverDropProofEnabled;

	/**
	 * Drop-trade capture: the store-tools grant, the drop-proof rollout flag, and clips not forced
	 * off. All three required.
	 *
	 * WHY THE ROLLOUT FLAG IS SEPARATE FROM X-Store-Tools. Before this, X-Store-Tools ALONE turned
	 * on continuous screen recording, while its own documented contract said the grant expanded no
	 * collection. The backend allowlist had no way to grant the shop overlays without also granting
	 * a screen recorder, and the only denial was X-Clips: off, which also killed the store delivery
	 * clips. Now the operator turns drop proof on for one token at a time and nothing else moves.
	 *
	 * Deliberately NOT a config item: this plugin carries no user-facing settings (operator default
	 * 2026-09-19), and a drop clip records other players standing on a tile, which the hub manifest
	 * warning does not describe. Server-granting it keeps it off every ordinary player entirely.
	 */
	boolean dropProofEnabled()
	{
		return storeToolsEnabled()
			&& serverDropProofEnabled
			&& (!serverClipsDisabled || dropProofDevOverride());
	}

	/** Test hook for the rollout flag. The real one is only ever written by applyServerPolicy. */
	void setDropProofRolloutForTest(boolean on)
	{
		serverDropProofEnabled = on;
	}

	/**
	 * LOCAL TEST OVERRIDE for the clips half of the drop-proof gate.
	 *
	 * Exactly the shape of storeToolsDevOverride above, and for the same reason: the grant arrives
	 * from the backend, so the feature cannot be exercised at all on a non-staff token. Without this
	 * the field rig cannot reach the capture path, because the server sends X-Clips: off to every
	 * non-staff token and the rig's account is deliberately not staff.
	 *
	 * It is a JVM system property, so only whoever launches the client with an explicit flag can set
	 * it. The Plugin Hub build launched through RuneLite.exe never has it. It grants NOTHING on the
	 * server: /store-frames-ingest refuses a non-staff token with a benign 200-drop, so a local
	 * override captures and uploads into a refusal. Only the local capture path is reachable.
	 */
	static boolean dropProofDevOverride()
	{
		String v = System.getProperty("osrsbis.dropproof");
		return v != null && ("on".equals(v) || "true".equals(v) || "1".equals(v));
	}

	/** The configured link token exactly as the upload paths read it: trimmed, never null. */
	String currentLinkToken()
	{
		if (config == null || config.linkToken() == null)
		{
			return "";
		}
		return config.linkToken().trim();
	}

	/**
	 * Does the live token still name the account this recording was started for?
	 *
	 * True when no session is bound to an identity, so a caller can ask this unconditionally.
	 */
	boolean dropSessionIdentityIntact()
	{
		String bound = dropSessionToken;
		return bound == null || bound.equals(currentLinkToken());
	}

	/**
	 * THE IDENTITY BOUNDARY. A changed link token ends the running session at once and destroys
	 * everything captured under the old one.
	 *
	 * It covers all three shapes of change with one rule, because they are the same event: a swap to
	 * another VALID token, a clear to empty, and a change to a malformed value. Only the first was
	 * ever missed, and only because the old gate asked whether a token was well-formed rather than
	 * whether it was the SAME token.
	 *
	 * A newly configured token starts nothing here. It can only be the identity of a FUTURE session,
	 * which is what stops a token that comes back from resurrecting the recording it left.
	 */
	void enforceDropSessionTokenIdentity()
	{
		if (dropSessionToken == null || dropSessionIdentityIntact())
		{
			return;
		}
		discardDropSessionOnWithdrawnConsent();
	}

	/**
	 * Any plugin config write. The link token is a config item, so this is the FIRST moment the
	 * client can know it changed.
	 *
	 * Deliberately not filtered by config group or key. The check is idempotent and costs a string
	 * compare, and a filter that names the wrong group would silently restore the defect. Catching
	 * the change here rather than at the next tick is what makes an A -> B -> A swap inside one tick
	 * still end the session.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		enforceDropSessionTokenIdentity();
	}

	/**
	 * A Drop action was clicked. Starts or joins the session and arms capture.
	 *
	 * Called from the menu-click path, NOT from the drop EVENT, and that ordering is the requirement:
	 * the event only exists once the inventory loss and the ground spawn agree, which is ticks after
	 * the click. Starting there would cut the drop itself off the front of the clip.
	 */
	void onDropActionForProof()
	{
		if (!dropProofEnabled())
		{
			return;
		}
		long now = System.currentTimeMillis();
		boolean wasActive = dropSession.active();
		boolean inTail = dropSession.stopPending();
		String newId = wasActive ? null : newDropSessionId();
		pendingDropSeq = dropSession.onDropAction(newId, now);
		pendingDropSessionId = dropSession.sessionId();
		if (!wasActive)
		{
			// Bind the identity BEFORE capture arms, so no frame can exist that is not attributable.
			dropSessionToken = currentLinkToken();
			startDropCapture();
		}
		// A drop inside the tail is its own moment: it cancels the stop, so the clip must show it at
		// full rate rather than at the baseline the tail had settled into.
		markDropMoment(inTail ? DropCaptureRate.Event.TAIL_DROP : DropCaptureRate.Event.DROP_ACTION, now);
	}

	/**
	 * Longest a session id may be on the wire. The server truncates at 24 and the staff-ops stitcher
	 * puts the id inside a D1 `LIKE` pattern, which D1 refuses past 50 characters. Keeping the id
	 * short at the source means no downstream limit has to be widened for it.
	 */
	static final int DROP_SESSION_ID_MAX = 24;

	/**
	 * Session id: time-ordered and unique per client, so two staff clients cannot collide.
	 *
	 * Hyphen-free and lower-case hex, because the id becomes part of an R2 key and the server's
	 * sanitizer drops anything outside [a-z0-9_-]. An id that loses characters to sanitizing would
	 * stop matching the manifest the stitcher joins on.
	 */
	private String newDropSessionId()
	{
		String id = Long.toHexString(System.currentTimeMillis())
			+ Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 24));
		return id.length() > DROP_SESSION_ID_MAX ? id.substring(0, DROP_SESSION_ID_MAX) : id;
	}

	/** Arm frame capture for a session. Idempotent. */
	void startDropCapture()
	{
		if (dropCapturing || !dropProofEnabled())
		{
			return;
		}
		dropSegmenter = new DropFrameSegmenter();
		dropRate = new DropCaptureRate();
		dropSegmentsSent.set(0);
		dropSegmentsFailed.set(0);
		nextDropSampleAt = 0L;		// first render tick samples immediately
		dropFramePending = false;
		dropCapturing = true;
		if (drawManager != null)
		{
			drawManager.registerEveryFrameListener(dropFrameTick);
		}
	}

	/**
	 * Wall-clock decimation for drop frames. Its own gate, so drop capture and store capture running
	 * at the same time cannot steal each other's sample slots.
	 */
	boolean shouldSampleDropFrame(long nowNanos)
	{
		if (nowNanos < nextDropSampleAt)
		{
			return false;
		}
		final long period = 1_000_000_000L / DROP_CLIP_FPS;
		long next = nextDropSampleAt + period;
		nextDropSampleAt = (next <= nowNanos) ? nowNanos + period : next;
		return true;
	}

	/** Render-thread frame hook. Encodes off-thread; at most one frame is ever in flight. */
	void onDropFrameTick()
	{
		if (!dropCapturing || dropFramePending)
		{
			return;
		}
		if (!shouldSampleDropFrame(System.nanoTime()))
		{
			return;
		}
		dropFramePending = true;
		drawManager.requestNextFrameListener(img ->
		{
			if (img == null)
			{
				dropFramePending = false;
				return;
			}
			final BufferedImage scaled = downscaleRgb(toRgbFrame(img), MAX_FRAME_WIDTH);
			if (scaled == null)
			{
				dropFramePending = false;
				return;
			}
			executor.submit(() ->
			{
				try
				{
					acceptDropFrame(encodeJpeg(scaled), System.currentTimeMillis());
				}
				finally
				{
					dropFramePending = false;
				}
			});
		});
	}

	/**
	 * Hand one encoded frame to the segmenter and upload the segment it completes.
	 *
	 * Package-private so the memory bound and the segment ordering can be driven under test with
	 * synthetic bytes, with no client and no render loop.
	 */
	void acceptDropFrame(byte[] encoded, long nowMillis)
	{
		// RE-CHECK THE GATE ON THE FRAME PATH (finding F2). The frame path used to test only that a
		// segmenter existed, so a user who unticked the upload switch mid-session kept being
		// recorded until something else ended the session. A frame taken after consent is withdrawn
		// is never accepted, so it can never be uploaded later.
		if (!dropCapturing || !dropProofEnabled())
		{
			return;
		}
		// AND RE-CHECK THE IDENTITY. This runs on the encode executor, which can deliver a frame
		// after the token already changed. Refusing here does not end the session — the config
		// listener and the tick poll do that, both on the client thread — it only guarantees that
		// no frame captured under the old account is ever buffered.
		if (!dropSessionIdentityIntact())
		{
			return;
		}
		DropFrameSegmenter seg = dropSegmenter;
		if (seg == null)
		{
			return;
		}
		DropCaptureRate rate = dropRate;
		if (rate != null && rate.offer(encoded, nowMillis) != DropCaptureRate.Decision.KEEP)
		{
			return;		// held as pre-roll, or discarded by the baseline cadence
		}
		DropFrameSegmenter.Segment full = seg.add(encoded, nowMillis);
		if (full != null)
		{
			uploadDropSegment(full, dropSession.sessionId());
		}
	}

	/**
	 * A moment worth full detail: start a 30fps burst and flush the held pre-roll into the clip.
	 *
	 * The pre-roll is committed FIRST and in order, so the two seconds leading up to the moment sit
	 * before it in the clip rather than after.
	 */
	void markDropMoment(DropCaptureRate.Event event, long nowMillis)
	{
		DropCaptureRate rate = dropRate;
		DropFrameSegmenter seg = dropSegmenter;
		if (rate == null || seg == null)
		{
			return;
		}
		rate.onEvent(event, nowMillis);
		for (byte[] held : rate.claimPreRoll(nowMillis))
		{
			DropFrameSegmenter.Segment full = seg.add(held, nowMillis);
			if (full != null)
			{
				uploadDropSegment(full, dropSession.sessionId());
			}
		}
	}

	/**
	 * Stop capture, flush the tail segment, and emit the session manifest event.
	 *
	 * The manifest is an EVENT, not a media row, so the join from clip to session to drops to
	 * removals lives in the same event stream everything else does.
	 */
	void stopDropCapture(DropSessionRecorder.Outcome outcome, String sessionId, int dropCount,
		long startedAtMillis, String reason)
	{
		if (!dropCapturing)
		{
			return;
		}
		dropCapturing = false;
		if (drawManager != null)
		{
			drawManager.unregisterEveryFrameListener(dropFrameTick);
		}
		DropFrameSegmenter seg = dropSegmenter;
		dropFramePending = false;
		if (seg == null)
		{
			dropSegmenter = null;
			dropRate = null;
			return;
		}
		// THE MANIFEST IS EMITTED WHATEVER THE TAIL UPLOAD DOES.
		//
		// The manifest is the only record of how many segments a clip should have, so losing it to
		// an upload fault turns a recoverable partial clip into an unreadable one: the stitcher
		// cannot tell a complete clip from one missing its last segment. The upload is therefore
		// wrapped, its failure is counted in segments_failed, and the manifest still goes out.
		try
		{
			DropFrameSegmenter.Segment tail = seg.flushRemainder();
			if (tail != null)
			{
				uploadDropSegment(tail, sessionId);
			}
		}
		catch (RuntimeException e)
		{
			dropSegmentsFailed.incrementAndGet();
			log.debug("OSRS BiS drop tail segment failed to submit", e);
		}
		emitDropSessionManifest(seg, outcome, sessionId, dropCount, startedAtMillis, reason);
		// Cleared AFTER the manifest: emitDropSessionManifest reads the rate controller's counts.
		dropSegmenter = null;
		DropCaptureRate done = dropRate;
		dropRate = null;
		if (done != null)
		{
			done.clear();
		}
	}

	/**
	 * The manifest row: everything needed to join a stitched clip back to what it shows.
	 *
	 * segments is the count the stitcher must find. A clip assembled from fewer is INCOMPLETE and the
	 * surface says so rather than playing a gap as if it were the footage.
	 */
	private void emitDropSessionManifest(DropFrameSegmenter seg, DropSessionRecorder.Outcome outcome,
		String sessionId, int dropCount, long startedAtMillis, String reason)
	{
		if (sessionId == null)
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("drop_session_id", sessionId);
		// COMPLETE IS A CLAIM ABOUT EVIDENCE, so it is only written when the recorder proved it. The
		// recorder refuses COMPLETE for a live pile, an outstanding pending drop, an expired
		// pending, an abandoned pile and any external interrupt — see DropSessionRecorder.finish.
		boolean complete = outcome == DropSessionRecorder.Outcome.COMPLETE;
		fields.put("outcome", complete ? "COMPLETE" : "INTERRUPTED");
		if (!complete)
		{
			// WHY it is not complete, so a reader never has to guess whether a partial clip means a
			// logout or a drop that was never observed landing.
			fields.put("outcome_reason",
				reason == null || reason.isEmpty() ? DropSessionRecorder.REASON_EXTERNAL : reason);
		}
		fields.put("drops", dropCount);
		fields.put("started_at", startedAtMillis);
		fields.put("ended_at", System.currentTimeMillis());
		fields.put("segments", seg.segmentCount());
		// ⚠ THESE TWO COUNTS ARE A SNAPSHOT, NOT A RECONCILIATION, and the manifest says so.
		//
		// Uploads are async: stopDropCapture submits the last segments and emits this row in the
		// same breath, so the in-flight ones have neither succeeded nor failed yet. Measured live
		// 2026-09-20 on a real session: segments 5, uploaded 3, failed 0 — which does not add up
		// and read like two lost segments when both were simply still on the wire.
		//
		// Waiting here is the wrong fix. It would block the client thread on a network round trip,
		// and a client that logs out immediately after a trade would publish nothing at all. So the
		// row states what it knows AT EMIT TIME and names the gap explicitly. `segments` is the
		// declared total and is authoritative; the stitcher counts what actually arrived in R2 and
		// compares against THAT, which is the only count that can be complete.
		fields.put("segments_uploaded_at_emit", dropSegmentsSent.get());
		fields.put("segments_failed_at_emit", dropSegmentsFailed.get());
		int inFlight = seg.segmentCount() - dropSegmentsSent.get() - dropSegmentsFailed.get();
		if (inFlight > 0)
		{
			fields.put("segments_in_flight_at_emit", inFlight);
		}
		fields.put("frames", seg.acceptedFrames());
		fields.put("frames_dropped", seg.rejectedFrames());
		fields.put("bytes", seg.sessionBytes());
		// The memory claim, measured rather than asserted: the most this client ever held at once,
		// and how many segments were still unacknowledged when the manifest was written.
		fields.put("peak_held_bytes", seg.peakHeldBytes());
		fields.put("unsettled_at_emit", seg.unsettledSegments());
		// ADAPTIVE RATE, reported so a viewer knows what they are watching. `fps` is the SAMPLE
		// rate the bursts play at; `baseline_fps` is the continuous rate between them. A stitcher
		// that assumed one flat rate would play the baseline stretches too fast.
		fields.put("fps", DROP_CLIP_FPS);
		fields.put("baseline_fps", DropCaptureRate.BASELINE_FPS);
		DropCaptureRate rate = dropRate;
		if (rate != null)
		{
			fields.put("frames_baseline", rate.keptBaseline());
			fields.put("frames_burst", rate.keptBurst());
			fields.put("bursts", rate.bursts());
		}
		fields.put("media_kind", DROP_MEDIA_KIND);
		if (seg.truncated())
		{
			// FAIL VISIBLY (operator decision 2026-09-20). Truncation is no longer an accepted
			// outcome: the adaptive rate exists so a long session fits. Reaching the budget anyway
			// means the sizing assumption is wrong for this session, so the row says the coverage
			// is INCOMPLETE in as many words, and the stitcher refuses to call the clip complete.
			fields.put("truncated", true);
			fields.put("coverage", "INCOMPLETE_BUDGET_EXCEEDED");
		}
		else
		{
			fields.put("coverage", "FULL");
		}
		emitEvent("drop_trade_clip", fields);
	}

	/**
	 * Media kind for drop footage. DISTINCT from the store path's `store_frames`, deliberately: a
	 * drop clip is not a shop visit, and the collector, the stitcher and the staff surfaces all key
	 * off this string to tell them apart.
	 */
	static final String DROP_MEDIA_KIND = "drop_frames";

	/** Upload retries for one segment. A dropped segment is a hole in the evidence, so it is retried. */
	static final int DROP_SEGMENT_RETRIES = 3;

	/**
	 * POST one segment to the frames ingest, with retries.
	 *
	 * The segment index and the session id travel as form fields, so reassembly is deterministic and
	 * does not infer order from timestamps the way the store path must.
	 */
	/**
	 * The server stored a segment. Release its bytes from the retry buffer.
	 *
	 * Called from the OkHttp callback thread, and the segmenter is synchronized, so this is safe.
	 * It reads the field fresh: a session that has already ended has nulled it, and a late
	 * acknowledgement for a finished session must not resurrect anything.
	 */
	private void acknowledgeDropSegment(int index)
	{
		DropFrameSegmenter seg = dropSegmenter;
		if (seg != null)
		{
			seg.segmentAcknowledged(index);
		}
	}

	/** A segment will never be stored. Release its bytes too, and count it as failed elsewhere. */
	private void abandonDropSegment(int index)
	{
		DropFrameSegmenter seg = dropSegmenter;
		if (seg != null)
		{
			seg.segmentAbandoned(index);
		}
	}

	private void uploadDropSegment(DropFrameSegmenter.Segment segment, String sessionId)
	{
		if (segment == null)
		{
			return;
		}
		// Every early return below strands the segment's bytes in the retry buffer unless they are
		// released. That is the whole failure this budget change could introduce: a session that
		// cannot upload at all would fill the buffer with segments nobody is waiting on and stop
		// capturing, which is exactly the truncation the change exists to remove.
		if (!uploadAllowed() || segment.frames.isEmpty() || sessionId == null)
		{
			abandonDropSegment(segment.index);
			return;
		}
		// AND THE GRANT ITSELF. uploadAllowed() is the user's own switch plus a well-formed token,
		// and neither of those moves when the OPERATOR withdraws drop proof: a server withdrawal
		// only clears serverDropProofEnabled. Without this line the guard set is inconsistent —
		// the user switch and a token swap both stop this call and the operator's own documented
		// "turn it off" does not.
		if (!dropProofEnabled())
		{
			abandonDropSegment(segment.index);
			return;
		}
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			abandonDropSegment(segment.index);
			return;
		}
		final String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
		if (executor == null)
		{
			// No executor means the segment cannot be sent at all. COUNT it rather than throwing:
			// a throw here would propagate out of stopDropCapture and kill the manifest, so an
			// upload fault would also erase the record of what was captured.
			dropSegmentsFailed.incrementAndGet();
			abandonDropSegment(segment.index);
			return;
		}
		executor.submit(() -> postDropSegment(base, token, segment, sessionId, 0));
	}

	/** One attempt, re-enqueueing itself on failure up to DROP_SEGMENT_RETRIES. */
	private void postDropSegment(String base, String token, DropFrameSegmenter.Segment segment,
		String sessionId, int attempt)
	{
		if (!uploadAllowed())
		{
			// The user turned uploads off mid-session. Nothing more will be sent, so release the
			// bytes rather than leaving them held for the rest of the session.
			abandonDropSegment(segment.index);
			return;
		}
		// THE GRANT ARM, and it belongs here rather than only at the entry point. A retry runs this
		// method again minutes later: DROP_SEGMENT_RETRIES attempts at a
		// CLIP_UPLOAD_TIMEOUT_SECONDS call timeout each. discardDropSessionOnWithdrawnConsent
		// cannot reach a Runnable already on the executor — that closure holds its own Segment with
		// the frame bytes inside it, and discardAll() drops the accounting, not those bytes. So the
		// only place a queued retry can be stopped is the attempt itself.
		//
		// The server does not catch it either: /store-frames-ingest authorizes on the staff token
		// and never reads drop_proof, deliberately, because rollout is not authorization. A staff
		// token is still staff after the operator withdraws the rollout flag, so the late POST is
		// stored. Both components are self-consistent and the seam between them leaked.
		if (!dropProofEnabled())
		{
			abandonDropSegment(segment.index);
			return;
		}
		// THE OUTSTANDING RETRY ARM. This segment's bytes were captured while `token` was configured,
		// and `token` is what its form body carries. A retry that fires after the user swapped to a
		// different token would file one account's footage under whoever is linked now, so the
		// attempt is stranded instead. Retries are the only way a segment survives the swap at all:
		// the segmenter refuses to produce a new one.
		if (!token.equals(currentLinkToken()))
		{
			abandonDropSegment(segment.index);
			return;
		}
		Request request = new Request.Builder()
			.url(base + "/store-frames-ingest")
			.post(buildDropSegmentBody(segment, token, sessionId))
			.build();
		OkHttpClient uploadClient = okHttpClient.newBuilder()
			.writeTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
			.readTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
			.callTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
			.build();
		uploadClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				retryOrGiveUp(e == null ? "io" : String.valueOf(e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.isSuccessful())
					{
						// A 200 IS NOT ALWAYS A STORE. /store-frames-ingest answers a deliberate
						// refusal with 200 {ok:true, dropped:'not_staff'} or
						// 200 {ok:true, dropped:'clips_disabled'}, on purpose: a 200 stops a public
						// plugin error-retrying something the server meant to throw away. Counting
						// those as sent made segments_uploaded_at_emit overstate what reached R2,
						// so a manifest could read as a clean success for footage that does not
						// exist. Reachable today the moment a staff token gets clips: 'off' in
						// acct:policy_map, which account-ingest explicitly permits.
						//
						// A refusal is NOT retried. The server meant it, and retrying it would be
						// the error loop the 200 exists to prevent. The bytes are released and the
						// segment is counted as failed, which is what it is.
						if (dropSegmentWasRefused(response))
						{
							dropSegmentsFailed.incrementAndGet();
							abandonDropSegment(segment.index);
							log.debug("OSRS BiS drop segment {} refused by the server with a 200",
								segment.index);
							return;
						}
						dropSegmentsSent.incrementAndGet();
						acknowledgeDropSegment(segment.index);
						return;
					}
					// A 4xx other than 429 will fail identically forever, so it is not retried.
					int code = response.code();
					if (code == 429 || code >= 500)
					{
						retryOrGiveUp("http " + code);
					}
					else
					{
						// FINDING E2. A 403 IS A REVOCATION, NOT AN ORDINARY FAILURE.
						// /store-frames-ingest answers a revoked link with 403, from revocationBlock,
						// which runs before anything is stored. So no data reaches the server — but
						// without this the recorder keeps capturing the rendered screen and keeps
						// posting segments that will all get the same 403, until the player logs out.
						// Recording locally with nowhere to send is exactly what the grant being
						// withdrawn is supposed to stop.
						//
						// It clears the rollout flag rather than tearing the session down here,
						// because this runs on an OkHttp callback thread and the teardown touches
						// the DrawManager and the segmenter, which belong to the client thread.
						// Clearing the flag makes dropProofEnabled() false, and the next
						// pollDropSession — the client thread, next game tick — runs the SAME
						// withdrawal path an X-Drop-Proof: off header runs. One revocation path, not
						// two. A server that has not in fact revoked the link restores the flag on
						// its next policy response.
						//
						// The flag is cleared BEFORE the counters, so an observer that waits on the
						// segment count can never read the flag in its old state.
						if (code == 403)
						{
							serverDropProofEnabled = false;
							log.debug("OSRS BiS drop segment {} got a 403: treating the grant as "
								+ "withdrawn", segment.index);
						}
						dropSegmentsFailed.incrementAndGet();
						abandonDropSegment(segment.index);
						log.debug("OSRS BiS drop segment {} rejected: {}", segment.index, code);
					}
				}
				finally
				{
					response.close();
				}
			}

			private void retryOrGiveUp(String why)
			{
				if (attempt + 1 >= DROP_SEGMENT_RETRIES)
				{
					dropSegmentsFailed.incrementAndGet();
					abandonDropSegment(segment.index);
					log.debug("OSRS BiS drop segment {} lost after {} attempts: {}",
						segment.index, DROP_SEGMENT_RETRIES, why);
					return;
				}
				executor.submit(() -> postDropSegment(base, token, segment, sessionId, attempt + 1));
			}
		});
	}

	/**
	 * Did a 2xx actually REFUSE the segment?
	 *
	 * /store-frames-ingest returns 200 {ok:true, dropped:'<why>'} for a deliberate drop, so the
	 * status code alone cannot distinguish a store from a refusal. The body is peeked with
	 * peekBody, which does NOT consume the response: the caller still closes it in its own finally
	 * block, and a peek that fails for any reason reports "not refused" so an unreadable body can
	 * never turn a real store into a phantom failure.
	 *
	 * The bound is small on purpose. A refusal body is a few dozen bytes; a larger body is a
	 * successful store and there is no reason to hold it in memory.
	 */
	static boolean dropSegmentWasRefused(Response response)
	{
		if (response == null)
		{
			return false;
		}
		try
		{
			String body = response.peekBody(DROP_REFUSAL_PEEK_BYTES).string();
			return body != null && body.contains("\"dropped\"");
		}
		catch (IOException | RuntimeException e)
		{
			return false;	// unreadable body: never invent a failure
		}
	}

	/** Enough of a 2xx body to see a refusal marker. A store's body is larger and is not read. */
	static final long DROP_REFUSAL_PEEK_BYTES = 512L;

	/**
	 * Multipart body for a drop segment.
	 *
	 * Same field names the store path uses for the frames themselves, so the server's existing parser
	 * handles them unchanged. The three EXTRA fields are what make a drop clip reassemble
	 * deterministically: the session id, the segment index and the media kind.
	 */
	static okhttp3.MultipartBody buildDropSegmentBody(DropFrameSegmenter.Segment segment, String token,
		String sessionId)
	{
		MultipartBody.Builder builder = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("token", token)
			.addFormDataPart("captured_at", Long.toString(segment.firstFrameMillis / 1000L))
			.addFormDataPart("fps", Integer.toString(DROP_CLIP_FPS))
			.addFormDataPart("kind", DROP_MEDIA_KIND)
			.addFormDataPart("drop_session_id", sessionId)
			.addFormDataPart("segment_index", Integer.toString(segment.index));
		for (int i = 0; i < segment.frames.size(); i++)
		{
			builder.addFormDataPart("frames[]", String.format("frame-%03d.jpg", i),
				RequestBody.create(JPEG, segment.frames.get(i)));
		}
		builder.addFormDataPart("frame_count", Integer.toString(segment.frames.size()));
		// THE SEGMENT'S OWN SIZE, distinct from this attempt's size. Every attempt at this index
		// carries the same Segment object, so this value never changes between a first attempt and
		// a retry. The server refuses an attempt whose frame count disagrees with it, which is what
		// stops a short retry from silently replacing a complete attempt.
		builder.addFormDataPart("segment_frame_total", Integer.toString(segment.frameCount()));
		return builder.build();
	}

	/**
	 * Register a freshly spawned pile with the running session.
	 *
	 * Called from the ground-spawn path, where the pile first exists. The sequence number comes from
	 * the drop ACTION that armed it, so a removal can name the exact drop even when a session drops
	 * the same item onto the same tile twice.
	 */
	void attachPileToDropSession(DroppedGroundItem g)
	{
		if (g == null || !dropSession.active())
		{
			return;
		}
		String sid = pendingDropSessionId;
		int seq = pendingDropSeq;
		if (sid == null || seq <= 0)
		{
			return;
		}
		dropPileSeq.put(g, new int[]{seq});
		dropPileSession.put(g, sid);
		dropSession.pileActive(DropSessionRecorder.pileKey(g.item, g.x, g.y, g.plane, seq), seq);
		markDropMoment(DropCaptureRate.Event.PILE_SPAWN, System.currentTimeMillis());
	}

	/**
	 * A drop MERGED into a pile that is already tracked on that tile. ADOPT the pile.
	 *
	 * FINDING F1 PATH A, and the reason the recorder used to run forever. Dropping a stackable onto
	 * an existing stack of the same item on the same tile does not spawn a second ground item: the
	 * game merges them and fires exactly ONE ItemDespawned when the merged stack goes. Tracking a
	 * second pile here produced a second session key that no despawn could ever release, so the
	 * tail never armed and only a logout stopped the recording.
	 *
	 * ROUND 5, FINDING B2 — WHY OWNERSHIP IS NOT PART OF THE MERGE DECISION. The caller used to look
	 * for the pile with liveSessionPileAt, which required membership of the CURRENT session. That is
	 * the right rule for ATTRIBUTION and the wrong rule for MERGE DETECTION: a merge is a fact about
	 * the game's ground state, not about who owns the pile. Three ordinary routes put a tracked pile
	 * on the tile that the session does not own, with no session ending at all:
	 *
	 *   1. ground tracking is gated on activityLogActive() and the session on dropProofEnabled(), so
	 *      a pile tracked before the grant arrives is still on the ground, unowned, when it opens;
	 *   2. the same, through the user's own upload switch going off and then back on;
	 *   3. a late pile whose own pending already expired (PENDING_DROP_EXPIRY_MILLIS), which
	 *      attachPileToDropSession refuses because pendingDropSeq is 0 by then.
	 *
	 * In every one of them the ownership test refused the pile, the caller minted a SECOND
	 * DroppedGroundItem for ONE physical pile, the single despawn resolved to the OLDEST match
	 * (the unowned entry), the session's key was never released and the recorder ran until a logout.
	 *
	 * So the pile is found regardless of ownership and ADOPTED into the running session: it takes
	 * this session's id and this drop's sequence number, and the recorder holds exactly one key for
	 * it. The one despawn the game fires then releases that one key. A pile the session ALREADY owns
	 * needs no adoption, only the pending resolved against it, because its key already exists.
	 */
	void adoptMergedPileIntoDropSession(DroppedGroundItem existing)
	{
		if (existing == null || !dropSession.active())
		{
			return;
		}
		String sid = pendingDropSessionId;
		int seq = pendingDropSeq;
		if (sid == null || seq <= 0)
		{
			return;
		}
		if (sid.equals(dropPileSession.get(existing)))
		{
			// Already ours: one pile, one key, and the pending is resolved by the pile already live.
			dropSession.pendingDropResolved(seq);
			markDropMoment(DropCaptureRate.Event.PILE_SPAWN, System.currentTimeMillis());
			return;
		}
		// Not ours yet. Adopt it through the same call the spawn path uses, so the pile gets this
		// session's id, this drop's sequence and exactly ONE recorder key, and the pending is
		// resolved by that key rather than left outstanding.
		attachPileToDropSession(existing);
	}

	/**
	 * The client stopped tracking a pile without seeing it leave the ground.
	 *
	 * FINDING F1 PATH C. trackGroundDrop evicts the oldest pile past GROUND_TRACK_MAX, and nothing
	 * used to tell the session, so a 30-item drop trade stranded keys the recorder waited on
	 * forever. The key is released here so the tail can still arm, and the session is marked
	 * unprovable so the released key is never mistaken for the pile actually going: that session
	 * reports INTERRUPTED, never COMPLETE.
	 */
	void abandonPileFromDropSession(DroppedGroundItem g)
	{
		if (g == null)
		{
			return;
		}
		int seq = dropSeqForPile(g);
		dropPileSeq.remove(g);
		dropPileSession.remove(g);
		if (seq > 0)
		{
			dropSession.pileAbandoned(
				DropSessionRecorder.pileKey(g.item, g.x, g.y, g.plane, seq), System.currentTimeMillis());
		}
	}

	/** The drop sequence a tracked pile belongs to, or -1 when it is not part of a session. */
	int dropSeqForPile(DroppedGroundItem g)
	{
		int[] v = dropPileSeq.get(g);
		return v == null ? -1 : v[0];
	}

	/** The session id a tracked pile belongs to, or null. */
	String dropSessionForPile(DroppedGroundItem g)
	{
		return dropPileSession.get(g);
	}

	/**
	 * Does the ground tracker still hold a pile the CURRENT session owns?
	 *
	 * ROUND 5. This is the caller half of the backstop invariant. The recorder waits on pile KEYS;
	 * this map is where the piles those keys stand for actually live. When the recorder holds a key
	 * and this returns false, nothing can ever release that key, because a removal is only reported
	 * for a pile that is tracked here.
	 */
	boolean dropSessionHoldsOwnedPile()
	{
		String sid = dropSession.sessionId();
		if (sid == null)
		{
			return false;
		}
		synchronized (groundDrops)
		{
			for (DroppedGroundItem g : groundDrops)
			{
				if (sid.equals(dropPileSession.get(g)))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A tracked pile left the ground. Tells the session, which arms the 5-second tail when it was
	 * the last one.
	 */
	void releasePileFromDropSession(DroppedGroundItem g)
	{
		if (g == null)
		{
			return;
		}
		int seq = dropSeqForPile(g);
		dropPileSeq.remove(g);
		dropPileSession.remove(g);
		if (seq > 0)
		{
			// Mark the moment FIRST. The pre-roll it flushes is the two seconds leading up to the
			// pile vanishing, which is the single most important stretch in the whole clip: it is
			// where a player walks onto the tile.
			markDropMoment(DropCaptureRate.Event.PILE_REMOVED, System.currentTimeMillis());
			dropSession.pileRemoved(
				DropSessionRecorder.pileKey(g.item, g.x, g.y, g.plane, seq), System.currentTimeMillis());
		}
	}

	/**
	 * THE ONE TEARDOWN. Every path that ends a drop session calls exactly this, and nothing else
	 * clears drop-session state.
	 *
	 * WHY IT IS ONE METHOD. This teardown used to be five copied lines at three call sites, with a
	 * fourth half of it (clearGroundDrops) written out separately at the four GameState arms. The
	 * withdrawal path got the five lines and not the ground half, and that single omission is
	 * finding F1 path A coming back through a different door:
	 *
	 *   1. the maps are cleared while the pile is still physically on the ground and still tracked;
	 *   2. the player re-drops the same stackable onto the same tile, the game MERGES it into that
	 *      pile and will fire exactly ONE ItemDespawned for the result;
	 *   3. liveSessionPileAt refuses the stale pile, because dropPileSession no longer names it, so
	 *      trackGroundDrop mints a SECOND DroppedGroundItem for ONE physical pile;
	 *   4. the one despawn resolves to the oldest match, which is the stale entry, and the new
	 *      session's key is never released. The tail can never arm and the recorder never stops.
	 *
	 * So untracking the session's piles is part of ENDING a session, not a courtesy the GameState
	 * arms happen to perform first. Only piles this session owned are untracked: groundDrops also
	 * holds ordinary piles that have nothing to do with a drop session, and clearing those would
	 * silently drop their ground_removed events. The GameState arms keep their own broader
	 * clearGroundDrops() call, which is about the scene and the world, not about this session.
	 */
	private void endDropSessionTracking()
	{
		synchronized (groundDrops)
		{
			// Read the ownership BEFORE the maps are cleared, or every pile looks unowned.
			java.util.List<DroppedGroundItem> owned = new ArrayList<>();
			for (DroppedGroundItem g : groundDrops)
			{
				if (dropPileSession.get(g) != null || dropPileSeq.get(g) != null)
				{
					owned.add(g);
				}
			}
			groundDrops.removeAll(owned);
		}
		dropPileSeq.clear();
		dropPileSession.clear();
		pendingDropSessionId = null;
		pendingDropSeq = 0;
		dropSessionToken = null;	// the next session binds its own identity; this one cannot return
	}

	/**
	 * Per-tick session poll: stop when the tail has elapsed.
	 *
	 * Reads the session's identity BEFORE finishing it, because finish() clears the state the
	 * manifest needs.
	 */
	void pollDropSession()
	{
		if (!dropSession.active())
		{
			return;
		}
		long now = System.currentTimeMillis();
		// CHECK THE GATE EVERY TICK, not only when capture starts. The user can untick the upload
		// switch, clear the token, or have the grant withdrawn while a session runs. Before this,
		// dropCapturing was only ever cleared by a stop, so capture continued through a withdrawal
		// and the frames taken during it uploaded as soon as the switch went back on (finding F2).
		if (!dropProofEnabled())
		{
			discardDropSessionOnWithdrawnConsent();
			return;
		}
		// AND CHECK THE IDENTITY EVERY TICK. The config listener is the immediate path, but it is an
		// event from the client and this poll must not depend on one arriving. Both call the same
		// method, so the two paths cannot disagree about what a changed token means.
		if (!dropSessionIdentityIntact())
		{
			enforceDropSessionTokenIdentity();
			return;
		}
		// EXPIRE PENDING DROPS FIRST. A Drop click that never produces a pile leaves nothing for a
		// removal to release, so without this the tail can never arm and the session runs until a
		// logout (finding F1 path B).
		for (Integer expiredSeq : dropSession.settle(now))
		{
			if (expiredSeq != null && expiredSeq == pendingDropSeq)
			{
				pendingDropSeq = 0;
				pendingDropSessionId = null;
			}
		}
		// THE PHYSICAL CHECK (round 8). Ask the game, every poll, whether the piles this session
		// still holds keys for are actually on the ground. Runs BEFORE the backstops, so a key this
		// releases is counted by the invariant on this same poll.
		releaseSessionPilesGoneFromScene(now);
		// THE STRUCTURAL BACKSTOP (round 5). Two bounds that do not care how the state went wrong:
		// the recorder waiting on a key no tracked pile of this session can release, and a hard
		// maximum session duration. Both end the session as INTERRUPTED and name the bound in
		// outcome_reason. This runs AFTER settle, so a pending that expires on this very poll is
		// counted before the invariant reads it, and BEFORE shouldStop, so a bound that fires ends
		// the session on this same poll rather than a tick later.
		dropSession.enforceBackstops(now, dropSessionHoldsOwnedPile());
		if (!dropSession.shouldStop(now))
		{
			return;
		}
		String sid = dropSession.sessionId();
		int drops = dropSession.dropCount();
		long started = dropSession.startedAtMillis();
		String reason = dropSession.reason();		// read BEFORE finish(), which clears it
		DropSessionRecorder.Outcome outcome = dropSession.finish();
		endDropSessionTracking();
		stopDropCapture(outcome, sid, drops, started, reason);
	}

	/**
	 * ROUND 8, FINDING R5 — RELEASE A KEY WHOSE PILE THE SCENE SAYS IS GONE.
	 *
	 * The disclosure promises the recording stops five seconds after the final dropped pile
	 * disappears. Round 7 made that true in the EARLY direction and false in the LATE one: on the
	 * ambiguous route the despawn we saw was credited to the rival's record, so our own record
	 * lingers for a pile that is physically gone. dropSessionHoldsOwnedPile then keeps returning
	 * true, the 30-second orphaned-state invariant can never accumulate its grace, and only the
	 * 30-minute hard cap ends the session. That is up to 30 minutes of recording past the moment the
	 * disclosure names, and every one of those frames uploads.
	 *
	 * The game knows the pile is gone. This asks it once per poll: for every pile this session still
	 * holds a key for, is an item of that id still on that tile? NONE means the pile physically
	 * left, so the key is released and the tail arms normally, which is exactly the five seconds the
	 * disclosure promises. UNKNOWN — the tile is out of scene, or the scene could not be read —
	 * changes nothing, and the orphaned-state invariant and the hard cap remain the backstops for
	 * that case.
	 *
	 * THE SESSION IS MARKED UNPROVABLE by the release, so this can never manufacture a COMPLETE. See
	 * DropSessionRecorder.pileGoneFromScene.
	 */
	/**
	 * ROUND 9 — WHICH of our own piles on one tile are the ones that left.
	 *
	 * {@code release} is already decided by the count. This only chooses WHICH records it names.
	 * Every quantity the scene still shows is matched against one of our records, oldest first; a
	 * record that survives the matching is still physically down. What is left over went, and the
	 * OLDEST {@code release} of those are released. When the reader cannot expose quantities, the
	 * oldest {@code release} records are released, which is the order they were dropped in.
	 *
	 * A scene quantity that matches NONE of our records is a stranger's pile. It is simply not
	 * matched, and because the return is capped at {@code release} it can never cause an extra key
	 * to be released.
	 */
	private static List<DroppedGroundItem> pilesGoneByQuantity(List<DroppedGroundItem> own,
		SceneTileItems scene, int release)
	{
		List<DroppedGroundItem> candidates = new ArrayList<>(own);
		if (scene.quantities != null)
		{
			for (long q : scene.quantities)
			{
				for (java.util.Iterator<DroppedGroundItem> it = candidates.iterator(); it.hasNext(); )
				{
					if (it.next().qty == q)
					{
						it.remove();	// this record is still physically down
						break;
					}
				}
			}
		}
		List<DroppedGroundItem> out = new ArrayList<>();
		for (DroppedGroundItem g : candidates)
		{
			if (out.size() >= release)
			{
				break;
			}
			out.add(g);
		}
		return out;
	}

	void releaseSessionPilesGoneFromScene(long nowMillis)
	{
		String sid = dropSession.sessionId();
		if (sid == null)
		{
			return;
		}
		List<DroppedGroundItem> gone = null;
		synchronized (groundDrops)
		{
			// ROUND 9, FINDING R8 — COUNT PER (ITEM, TILE), NOT PRESENCE.
			//
			// Round 8 asked the tile a yes/no question once per RECORD, so two of our records for
			// one item on one tile asked the SAME question and got the SAME answer: released
			// together or kept together. The count says how many of ours went. We own |S| records
			// there and the scene shows C piles of that id, so |S| - C of ours have left. Release
			// exactly that many and no more, choosing by QUANTITY where the reader exposes it, so
			// the key released stands for a pile that is really gone. When the scene cannot say
			// which quantities remain, the OLDEST are released, which is the order they were
			// dropped in.
			//
			// CONSERVATIVE ON ANY DOUBT. UNKNOWN releases nothing. A count at or above ours releases
			// nothing. Keeping a key too long is bounded by the invariant and the hard cap;
			// releasing one too early would shorten a recording the disclosure promised.
			Map<String, List<DroppedGroundItem>> byTile = new LinkedHashMap<>();
			for (DroppedGroundItem g : groundDrops)
			{
				if (!sid.equals(dropPileSession.get(g)))
				{
					continue;
				}
				// The ITEM ID is part of the key. A tile emptied of some other item says nothing
				// about our pile, and reading "the tile is clear" off the wrong id would release a
				// key while our pile is still lying there.
				byTile.computeIfAbsent(g.item + ":" + g.x + ":" + g.y + ":" + g.plane,
					k -> new ArrayList<>()).add(g);
			}
			for (List<DroppedGroundItem> own : byTile.values())
			{
				DroppedGroundItem first = own.get(0);
				SceneTileItems scene = sceneTileItems(first.item, first.x, first.y, first.plane, null);
				if (scene.state == ScenePileState.UNKNOWN)
				{
					continue;
				}
				int release = own.size() - scene.count;
				if (release <= 0)
				{
					continue;
				}
				for (DroppedGroundItem g : pilesGoneByQuantity(own, scene, release))
				{
					if (gone == null)
					{
						gone = new ArrayList<>();
					}
					gone.add(g);
				}
			}
			if (gone != null)
			{
				groundDrops.removeAll(gone);
			}
		}
		if (gone == null)
		{
			return;
		}
		for (DroppedGroundItem g : gone)
		{
			int seq = dropSeqForPile(g);
			dropPileSeq.remove(g);
			dropPileSession.remove(g);
			if (seq > 0)
			{
				dropSession.pileGoneFromScene(
					DropSessionRecorder.pileKey(g.item, g.x, g.y, g.plane, seq), nowMillis);
			}
		}
	}

	/**
	 * A hop, logout, disconnect or scene reload happened mid-session.
	 *
	 * The footage is uploaded and the manifest says INTERRUPTED. Discarding it would delete the
	 * evidence for exactly the sessions somebody will need to look at.
	 */
	void interruptDropSession()
	{
		if (!dropSession.active())
		{
			return;
		}
		String sid = dropSession.sessionId();
		int drops = dropSession.dropCount();
		long started = dropSession.startedAtMillis();
		dropSession.interrupt();
		String reason = dropSession.reason();		// read BEFORE finish(), which clears it
		DropSessionRecorder.Outcome outcome = dropSession.finish();
		endDropSessionTracking();
		stopDropCapture(outcome, sid, drops, started, reason);
	}

	/**
	 * CONSENT WAS WITHDRAWN MID-SESSION. End the session and DESTROY what was captured.
	 *
	 * This is the one path where footage is deliberately thrown away, and it is the opposite of
	 * interruptDropSession on purpose. A hop or a logout is an accident of the session and the
	 * user still consents, so that footage is evidence and it uploads. Unticking the upload switch,
	 * clearing the token or losing the grant is the user or the operator saying stop, so the frames
	 * already in the buffer must not survive to be uploaded when the switch goes back on. Finding
	 * F2 measured exactly that leak: OFF-window frames reached the uploader after a re-enable.
	 *
	 * No manifest is emitted either. A manifest is an upload about a recording the user withdrew
	 * consent for, so publishing one would be the same disclosure defect in a smaller shape.
	 */
	void discardDropSessionOnWithdrawnConsent()
	{
		if (dropSession.active())
		{
			dropSession.interrupt();
			dropSession.finish();
		}
		endDropSessionTracking();
		dropCapturing = false;
		dropFramePending = false;
		if (drawManager != null)
		{
			drawManager.unregisterEveryFrameListener(dropFrameTick);
		}
		DropFrameSegmenter seg = dropSegmenter;
		dropSegmenter = null;
		if (seg != null)
		{
			// discardAll, NOT clear. clear() empties only the segment being filled and leaves the
			// RETRY BUFFER accounted, so a segment already handed to the uploader would still be
			// retried under whatever token is configured by then. discardAll drops that accounting
			// and latches the segmenter closed, so this object can never yield another segment even
			// to a caller still holding a reference to it.
			seg.discardAll();
		}
		DropCaptureRate rate = dropRate;
		dropRate = null;
		if (rate != null)
		{
			rate.clear();		// the held pre-roll dies too
		}
	}

	/**
	 * The drop-proof capability may have just changed. Called after every policy application.
	 *
	 * A capability that has gone away must stop a running recorder now rather than at the next tick.
	 */
	void onDropProofCapabilityChanged()
	{
		if (!dropProofEnabled())
		{
			if (dropSession.active() || dropCapturing)
			{
				discardDropSessionOnWithdrawnConsent();
			}
		}
	}

	/**
	 * Players observed right now, as plain values, for pile-centred candidate resolution.
	 *
	 * Separate from nearbyPlayersSnapshot, which measures from OUR character. In a drop trade the
	 * staff member routinely walks away before the customer arrives, so a distance measured from us
	 * says nothing about who was on the pile.
	 */
	java.util.List<DropCandidates.Observed> observedPlayers()
	{
		java.util.List<DropCandidates.Observed> out = new ArrayList<>();
		if (client == null)
		{
			return out;
		}
		Player self = client.getLocalPlayer();
		java.util.List<Player> players = client.getPlayers();
		if (players == null)
		{
			return out;
		}
		for (Player p : players)
		{
			if (p == null || p == self || p.getName() == null || p.getName().isEmpty()
				|| p.getWorldLocation() == null)
			{
				continue;
			}
			WorldPoint loc = p.getWorldLocation();
			out.add(new DropCandidates.Observed(Text.removeTags(p.getName()),
				loc.getX(), loc.getY(), loc.getPlane(), p.getCombatLevel(), p.getAnimation()));
		}
		return out;
	}

	/** Store-clip capture requires a linked token AND no server force-disable (both read live per call). */
	boolean storeClipsEnabled()
	{
		return activityLogActive() && !serverClipsDisabled;
	}

	/**
	 * Arm capture for a shop visit: fresh ring, reset the decimation clock + tx flag, and start listening
	 * to the render loop. Idempotent — a second shop-open while already capturing is ignored.
	 */
	void startStoreClipCapture()
	{
		if (clipCapturing || !storeClipsEnabled())
		{
			return;
		}
		clipVisit = new StoreVisitClip(MAX_CLIP_FRAMES, MAX_CLIP_BURST_BYTES, MAX_CLIP_FRAME_BYTES);
		storeTxThisVisit = false;
		nextClipSampleAt = 0L;			// 0 => the first render tick samples immediately
		clipFramePending = false;
		clipCapturing = true;
		drawManager.registerEveryFrameListener(clipFrameTick);
	}

	/**
	 * Wall-clock 1-in-time decimation: returns true at most once per (1/CLIP_FPS) second regardless of
	 * render fps (render fluctuates ~20-50fps, so a frame-count divisor would drift). Advances the gate
	 * on every hit so it is a steady rate, not a one-shot.
	 */
	boolean shouldSampleClipFrame(long nowNanos)
	{
		if (nowNanos < nextClipSampleAt)
		{
			return false;
		}
		final long period = 1_000_000_000L / CLIP_FPS;
		// Advance from the SCHEDULED boundary, not from the observed tick.
		//
		// `nextClipSampleAt = nowNanos + period` looks equivalent and is not: a render tick lands on its
		// own grid (~20ms at 50fps), so each sample fires slightly AFTER its boundary and that remainder
		// is then baked into the next deadline. The error compounds. At 1fps it hid — 20ms against a
		// 1000ms period — but at 8fps it is 20ms against 125ms and it cost a real frame every two
		// seconds: measured 15 samples where 16 were due, ~6% of a 25-second clip silently missing.
		// Advancing the deadline by whole periods keeps the rate exact over any span.
		//
		// After a long gap (a hitch, or a client that was not rendering) the deadline can sit far in the
		// past; walking it forward in whole periods would then fire a burst of catch-up samples on
		// consecutive frames. Re-base to `nowNanos` in that case: a gap loses frames either way, and
		// duplicating the same instant several times is not evidence.
		if (nextClipSampleAt == 0L || nowNanos - nextClipSampleAt >= period)
		{
			nextClipSampleAt = nowNanos + period;
		}
		else
		{
			nextClipSampleAt += period;
		}
		return true;
	}

	/**
	 * Render-loop callback (runs every frame while capturing). Decimates by wall clock, then asks
	 * DrawManager for the next composited frame; that frame arrives on the consumer, is converted to an
	 * RGB (no-alpha) buffer, downscaled, JPEG-ENCODED, and the BYTES are stored.
	 *
	 * ⚠ Encoding happens HERE, per frame, not at upload time — the ring must never hold decoded images
	 * (see ClipRingBuffer's header: 360 raw frames is ~400MB and freezes the client). The encode runs on
	 * the executor so the render thread is not blocked by ImageIO, and clipFramePending is cleared only
	 * once that encode has finished, so at most ONE frame is ever in flight and the ring stays in
	 * chronological order even though the executor is a shared pool.
	 */
	void onClipFrameTick()
	{
		if (!clipCapturing || clipFramePending)
		{
			return;
		}
		if (!shouldSampleClipFrame(System.nanoTime()))
		{
			return;
		}
		clipFramePending = true;
		final long sampledAt = System.currentTimeMillis();
		clipFrameSampledAtMillis = sampledAt;
		drawManager.requestNextFrameListener(img ->
		{
			if (img == null)
			{
				clipFramePending = false;
				return;
			}
			// Copy + downscale on this thread: `img` is DrawManager's buffer and is not ours to keep.
			final BufferedImage scaled = downscaleRgb(toRgbFrame(img), MAX_FRAME_WIDTH);
			if (scaled == null)
			{
				clipFramePending = false;
				return;
			}
			executor.submit(() ->
			{
				try
				{
					StoreVisitClip visit = clipVisit;
					if (visit != null)
					{
						visit.offer(encodeJpeg(scaled), sampledAt);	// null/empty is ignored
					}
				}
				finally
				{
					clipFramePending = false;
				}
			});
		});
	}

	/**
	 * Stop capturing: unregister the render listener, snapshot the ring, and — only when upload is
	 * requested AND the visit had a buy/sell — hand the frames to the uploader. Always clears state so a
	 * dropped visit leaks nothing. Idempotent.
	 */
	void stopStoreClipCapture(boolean upload)
	{
		if (!clipCapturing)
		{
			return;
		}
		clipCapturing = false;
		drawManager.unregisterEveryFrameListener(clipFrameTick);
		StoreVisitClip visit = clipVisit;
		clipVisit = null;
		clipFramePending = false;
		boolean hadTx = storeTxThisVisit;
		storeTxThisVisit = false;
		if (visit == null)
		{
			return;
		}
		if (!upload || !hadTx)
		{
			visit.clear();		// no purchase, or shutdown/hop drop — discard without uploading
			return;
		}
		StoreVisitClip.Snapshot snap = visit.snapshot();	// already JPEG-encoded at capture time
		visit.clear();
		if (!snap.frames.isEmpty())
		{
			submitStoreClipUpload(snap.frames, snap.capturedAtMillis);
		}
	}

	/**
	 * A buy / sell click or a store_taken during a capturing visit. The clip keeps the seconds before
	 * and after it at the full rate. No-op when no visit is capturing.
	 */
	void markStoreClipMoment()
	{
		StoreVisitClip visit = clipVisit;
		if (clipCapturing && visit != null)
		{
			visit.onMoment(System.currentTimeMillis());
		}
	}

	/**
	 * Copy any rendered Image into a TYPE_INT_RGB (no-alpha) BufferedImage. The JDK JPEG writer corrupts
	 * ARGB rasters, so store-clip frames are always RGB. This is deliberately SEPARATE from the trade
	 * path's ARGB toBufferedImage — never reuse that here.
	 */
	static BufferedImage toRgbFrame(java.awt.Image img)
	{
		if (img == null)
		{
			return null;
		}
		int w = img.getWidth(null);
		int h = img.getHeight(null);
		if (w <= 0 || h <= 0)
		{
			return null;
		}
		BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = rgb.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return rgb;
	}

	/**
	 * Downscale to at most maxWidth (aspect preserved) into a fresh TYPE_INT_RGB buffer. Frames already
	 * within maxWidth are returned unchanged. Keeps store text legible at the server stitch size (Task-0).
	 */
	static BufferedImage downscaleRgb(BufferedImage src, int maxWidth)
	{
		if (src == null || maxWidth <= 0 || src.getWidth() <= maxWidth)
		{
			return src;
		}
		int w = maxWidth;
		int h = Math.max(1, (int) Math.round(src.getHeight() * (maxWidth / (double) src.getWidth())));
		BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = dst.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
			java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return dst;
	}

	/**
	 * Encode the visit's frames to JPEG on the background executor, keep the newest suffix that fits the
	 * server caps, and POST them as ONE multipart burst to /store-frames-ingest. Mirrors the trade-upload
	 * okhttp idioms: token guard, apiBaseUrl trim, RequestBody.create(MediaType, bytes), async enqueue.
	 * Encoding + upload run off the render thread; a bad token or an empty burst is a silent no-op.
	 */
	/**
	 * Max frames in ONE POST. This is NOT the clip length — a visit's frames are split across as many
	 * chunked bursts as it takes, and the collector reassembles them into one video.
	 *
	 * Why chunk at all: the ingest endpoint REJECTS a burst over its frame cap with a 400 rather than
	 * trimming it, and the deployed cap is 120. A 30fps clip is 360 frames. Chunking means the clip
	 * length stops being hostage to the server cap: 30fps works against the CURRENTLY DEPLOYED server,
	 * today, and a longer clip later costs another chunk rather than another deploy.
	 *
	 * 100, not 120, on purpose — the plugin cannot see the server's cap, so it sits below the smallest
	 * one that has ever been deployed rather than exactly at the current one.
	 */
	// SUBREQUEST BUDGET, not a size preference (lowered 100 -> 40 on 2026-09-03). The server writes one
	// R2 object per frame inside ONE Worker invocation, and a Worker has a hard subrequest ceiling: a
	// 100-frame chunk killed it mid-loop, leaving orphaned frames, no manifest and no database row, and
	// the plugin saw a dead socket rather than an error. Measured on production: real chunks died at 49,
	// 45 and 48 frames written. 40 keeps the server inside its budget. This value is HALF of a contract
	// with the server's MEDIA.FRAMES_PER_REQUEST_MAX — raising it here alone silently loses every clip.
	static final int CLIP_CHUNK_FRAMES = 40;

	/**
	 * Upload timeout for ONE clip chunk. Sized to the payload, not to a page fetch.
	 * A 40-frame chunk at the measured ~37KB/frame is ~1.5MB; the same shape took ~6s in a production
	 * probe and a 100-frame chunk took 13.9-16.6s. 90s leaves room for a slow domestic uplink without
	 * hanging a staff client forever. The shared RuneLite client's 10s default is what silently
	 * destroyed every clip before 2026-09-03 (see postStoreClipChunk).
	 */
	static final int CLIP_UPLOAD_TIMEOUT_SECONDS = 90;

	/**
	 * Encode the visit's frames to JPEG on the background executor and POST them as one or more chunked
	 * bursts to /store-frames-ingest. Mirrors the trade-upload okhttp idioms: token guard, apiBaseUrl
	 * trim, RequestBody.create(MediaType, bytes), async enqueue. Encoding + upload run off the render
	 * thread; a bad token or an empty burst is a silent no-op.
	 *
	 * REASSEMBLY CONTRACT — the collector joins chunks on `captured_at`, so each chunk carries the wall
	 * time of ITS OWN first frame, read from the frame's real capture time, not the visit's. That gives
	 * the collector the ORDER of the chunks. Since StoreVisitClip the frames are NOT one continuous
	 * span: moments are at the full rate and the gaps between them at 1fps. Every chunk still declares
	 * CLIP_FPS, because the collector refuses to join chunks whose rates differ; the 1fps stretches
	 * therefore play faster than real time, and the moments play at real time.
	 */
	void submitStoreClipUpload(java.util.List<byte[]> frames, java.util.List<Long> capturedAtMillis)
	{
		if (!uploadAllowed())
		{
			return;		// upload switch off — send nothing
		}
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$") || frames == null || frames.isEmpty()
			|| capturedAtMillis == null || capturedAtMillis.size() != frames.size())
		{
			return;	// same guard as the trade path — no / malformed token, or nothing to send
		}
		final java.util.List<byte[]> allFrames = new java.util.ArrayList<>(frames);
		final java.util.List<Long> allTimes = new java.util.ArrayList<>(capturedAtMillis);
		executor.submit(() ->
		{
			// Frames arrive ALREADY ENCODED and already inside the visit budget (StoreVisitClip).
			// selectStoreClipFrames still filters null / oversized entries as a second line.
			java.util.List<Integer> kept = selectStoreClipFrameIndexes(
				allFrames, MAX_CLIP_FRAMES, MAX_CLIP_FRAME_BYTES, MAX_CLIP_BURST_BYTES);
			if (kept.isEmpty())
			{
				return;
			}
			String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
			for (int off = 0; off < kept.size(); off += CLIP_CHUNK_FRAMES)
			{
				java.util.List<Integer> idx = kept.subList(off, Math.min(off + CLIP_CHUNK_FRAMES, kept.size()));
				java.util.List<byte[]> chunk = new java.util.ArrayList<>(idx.size());
				for (int i : idx)
				{
					chunk.add(allFrames.get(i));
				}
				// Each chunk carries the REAL capture time of its own first frame. The visit is not one
				// continuous span any more (start, moments, baseline), so a time derived from the frame
				// count would be wrong; the collector orders chunks on this value.
				long chunkAtMillis = allTimes.get(idx.get(0));
				postStoreClipChunk(base, token, chunk, chunkAtMillis / 1000L);
			}
		});
	}

	/**
	 * POST one chunk of a (possibly multi-chunk) store-clip burst.
	 *
	 * ⚠ THE TIMEOUT IS THE WHOLE POINT OF THIS METHOD'S CLIENT OVERRIDE (2026-09-03).
	 * RuneLite's injected OkHttpClient keeps OkHttp's DEFAULT 10s read/write timeouts, and a real
	 * frame chunk does not upload in 10s on an ordinary connection. The client abandoned the request
	 * mid-body, the server had already written part of the burst to storage, and because the request
	 * was CANCELLED rather than failed, the server's own cleanup never ran either: orphaned frames,
	 * no manifest, no database row, and nothing logged above debug. Every delivery clip from the
	 * chunked-upload release until this fix was destroyed exactly this way.
	 *
	 * Reproduced on production 2026-09-03, one chunk, two arms, identical bytes:
	 *   10s cap -> 49 objects stored, NO manifest (the exact production signature)
	 *   60s cap -> 101 objects stored, manifest written, 200 OK
	 *
	 * So the upload gets its own client with a timeout sized to the payload, not to a page fetch.
	 * Do NOT drop this back to the shared client.
	 */
	private void postStoreClipChunk(String base, String token, java.util.List<byte[]> chunk, long capturedAt)
	{
		postStoreClipChunk(base, token, chunk, capturedAt, 0, false);
	}

	/**
	 * Attempts for ONE store-clip chunk, the first included (finding F-A3, 2026-09-25).
	 *
	 * Before this, one failed POST lost the whole chunk silently: a 500/503 from a loaded server or
	 * a dropped connection, logged only at debug. The rig lost three sell visits exactly this way
	 * while the activity events of the same minutes were retried and arrived. So a chunk is retried
	 * on a network failure, a 5xx or a 429, and never on another 4xx or a 200 refusal.
	 */
	static final int CLIP_CHUNK_ATTEMPTS = 5;

	/**
	 * Wait before retry 1..4. Sum 7 minutes; with the 90s call timeout on every attempt the worst
	 * case window is about 14 minutes, and the chunk bytes are released at its end either way.
	 * The first retry is short on purpose: the media collector joins a chunk into its visit only
	 * when the server stores it within 120s of the visit's other chunks, so an early success keeps
	 * the video in one piece. A later success is still stored, as its own short clip.
	 */
	static final long[] CLIP_CHUNK_RETRY_DELAYS_MS = {15_000L, 45_000L, 120_000L, 240_000L};

	/**
	 * A 429 Retry-After longer than this is not waited for: the chunk is given up instead. Retrying
	 * early would ignore what the server asked for, and holding the bytes for up to an hour would
	 * break the bounded retry window.
	 */
	static final long CLIP_RETRY_AFTER_MAX_MS = 240_000L;

	/**
	 * Total bytes of chunks waiting for a retry, across all visits. Two full visits (2 x 16 MiB = 32 MiB). A chunk that
	 * would push the total over this is given up rather than held, so a long server outage cannot
	 * grow memory without bound. Same idea as the drop path's unacked byte budget.
	 */
	static final long CLIP_RETRY_BYTE_BUDGET = 2L * MAX_CLIP_BURST_BYTES;

	/** Bytes currently held for a pending store-clip retry. Never above CLIP_RETRY_BYTE_BUDGET. */
	final java.util.concurrent.atomic.AtomicLong clipRetryHeldBytes = new java.util.concurrent.atomic.AtomicLong();
	/** Chunks the server stored. */
	final java.util.concurrent.atomic.AtomicInteger clipChunksSent = new java.util.concurrent.atomic.AtomicInteger();
	/** Chunks that will never be stored: attempts spent, not retryable, or no room to hold them. */
	final java.util.concurrent.atomic.AtomicInteger clipChunksLost = new java.util.concurrent.atomic.AtomicInteger();

	/** Run a store-clip retry after delayMs. A seam so tests can drive the ladder without waiting. */
	void scheduleClipRetry(Runnable retry, long delayMs)
	{
		executor.schedule(retry, delayMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * One attempt for one chunk. `held` is true when this chunk's bytes are already counted in
	 * clipRetryHeldBytes (every retry), so the count is released exactly once when the chunk ends.
	 *
	 * A RETRY RESENDS THE SAME BODY: the same frames and the same captured_at. The server does not
	 * dedupe: every stored attempt is a new random directory and a new account_media row. That is
	 * harmless for a real failure (a 5xx, a 429 or a refused connection stores no row), but when the
	 * server stored the chunk and the reply was lost (a timeout after the insert), the retry stores a
	 * second row. That second row carries the same (link_token, captured_at, frame_count, bytes), so
	 * a consumer can recognise it. The plugin cannot tell the two cases apart from its side.
	 */
	private void postStoreClipChunk(String base, String token, java.util.List<byte[]> chunk, long capturedAt,
		int attempt, boolean held)
	{
		if (!uploadAllowed() || (attempt > 0 && !token.equals(currentLinkToken())))
		{
			// Uploads switched off, or a retry after the user linked a different token: this chunk
			// was captured under the old token and must not be filed under the new one.
			endStoreClipChunk(chunk, held, false, "upload no longer allowed for this token", attempt);
			return;
		}
		Request request = new Request.Builder()
			.url(base + "/store-frames-ingest")
			.post(buildStoreClipBody(chunk, token, capturedAt, CLIP_FPS))
			.build();

		OkHttpClient uploadClient = okHttpClient.newBuilder()
			.writeTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
			.readTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
			.callTimeout(CLIP_UPLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
			.build();

		uploadClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				retryOrGiveUp(e == null ? "io" : String.valueOf(e.getMessage()), 0L);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					int code = response.code();
					if (response.isSuccessful())
					{
						// A 200 {"dropped":...} is a deliberate refusal (not staff, clips off).
						// Retrying it is the error loop that 200 exists to prevent.
						if (dropSegmentWasRefused(response))
						{
							endStoreClipChunk(chunk, held, false, null, attempt);
							log.debug("OSRS BiS store-clip chunk refused by the server with a 200");
							return;
						}
						endStoreClipChunk(chunk, held, true, null, attempt);
						return;
					}
					if (code == 429)
					{
						Long ra = parseRetryAfterSeconds(response.header("Retry-After"));
						retryOrGiveUp("http 429", ra == null ? 0L : ra * 1000L);
					}
					else if (code >= 500)
					{
						retryOrGiveUp("http " + code, 0L);
					}
					else
					{
						// Any other 4xx fails identically on every attempt.
						endStoreClipChunk(chunk, held, false, "http " + code, attempt);
					}
				}
				finally
				{
					response.close();
				}
			}

			private void retryOrGiveUp(String why, long retryAfterMs)
			{
				if (attempt + 1 >= CLIP_CHUNK_ATTEMPTS)
				{
					endStoreClipChunk(chunk, held, false, why, attempt);
					return;
				}
				if (retryAfterMs > CLIP_RETRY_AFTER_MAX_MS)
				{
					endStoreClipChunk(chunk, held, false, why + ", Retry-After " + (retryAfterMs / 1000L)
						+ "s is longer than the retry window", attempt);
					return;
				}
				if (!held && !reserveClipRetryBytes(chunkBytes(chunk)))
				{
					endStoreClipChunk(chunk, false, false, why + ", retry memory budget full", attempt);
					return;
				}
				long delay = Math.max(CLIP_CHUNK_RETRY_DELAYS_MS[attempt], retryAfterMs);
				scheduleClipRetry(() -> postStoreClipChunk(base, token, chunk, capturedAt, attempt + 1, true),
					delay);
			}
		});
	}

	/** Add bytes to the retry total if they fit the budget. False (and nothing added) if not. */
	boolean reserveClipRetryBytes(long bytes)
	{
		while (true)
		{
			long cur = clipRetryHeldBytes.get();
			if (cur + bytes > CLIP_RETRY_BYTE_BUDGET)
			{
				return false;
			}
			if (clipRetryHeldBytes.compareAndSet(cur, cur + bytes))
			{
				return true;
			}
		}
	}

	static long chunkBytes(java.util.List<byte[]> chunk)
	{
		long n = 0;
		for (byte[] b : chunk)
		{
			n += b == null ? 0 : b.length;
		}
		return n;
	}

	/**
	 * The chunk is finished: stored, refused or lost. Releases its retry bytes (when held) and
	 * counts it. `lossReason` non-null means lost, and that is logged at WARN: log only, never
	 * chat text. A null reason with stored=false is a deliberate server refusal, not a loss.
	 */
	private void endStoreClipChunk(java.util.List<byte[]> chunk, boolean held, boolean stored,
		String lossReason, int attempt)
	{
		if (held)
		{
			clipRetryHeldBytes.addAndGet(-chunkBytes(chunk));
		}
		if (stored)
		{
			clipChunksSent.incrementAndGet();
			return;
		}
		if (lossReason != null)
		{
			clipChunksLost.incrementAndGet();
			log.warn("OSRS BiS store-clip chunk ({} frames) lost after {} attempt(s): {}",
				chunk.size(), attempt + 1, lossReason);
		}
	}

	/**
	 * Choose which encoded frames go in the burst. Pass 1: drop any frame that failed to encode or exceeds
	 * the per-frame cap. Pass 2: keep the NEWEST suffix that fits both maxFrames and maxBurstBytes, walking
	 * from the END — the shop-close / sale frames are the actual delivery evidence, so an oversized burst
	 * must drop the OLDEST frames, never the newest. Chronological order within the kept suffix is preserved.
	 */
	static java.util.List<byte[]> selectStoreClipFrames(java.util.List<byte[]> encoded, int maxFrames, int maxFrameBytes, int maxBurstBytes)
	{
		java.util.List<byte[]> out = new java.util.ArrayList<>();
		for (int i : selectStoreClipFrameIndexes(encoded, maxFrames, maxFrameBytes, maxBurstBytes))
		{
			out.add(encoded.get(i));
		}
		return out;
	}

	/** selectStoreClipFrames as indexes into `encoded`, so a caller can keep each frame's capture time. */
	static java.util.List<Integer> selectStoreClipFrameIndexes(java.util.List<byte[]> encoded, int maxFrames, int maxFrameBytes, int maxBurstBytes)
	{
		java.util.List<Integer> valid = new java.util.ArrayList<>(encoded.size());
		for (int i = 0; i < encoded.size(); i++)
		{
			byte[] b = encoded.get(i);
			if (b != null && b.length > 0 && b.length <= maxFrameBytes)
			{
				valid.add(i);
			}
		}
		int start = valid.size();
		long total = 0;
		int kept = 0;
		while (start > 0 && kept < maxFrames && total + encoded.get(valid.get(start - 1)).length <= maxBurstBytes)
		{
			total += encoded.get(valid.get(start - 1)).length;
			start--;
			kept++;
		}
		return new java.util.ArrayList<>(valid.subList(start, valid.size()));
	}

	/**
	 * Build the one multipart burst body: token, captured_at, fps, then the kept frames[] parts, then
	 * frame_count LAST so it always equals the number of parts actually attached (the server 400s on a
	 * mismatch). Frames are JPEG, named frame-000.jpg upward in chronological order.
	 */
	static okhttp3.MultipartBody buildStoreClipBody(java.util.List<byte[]> kept, String token, long capturedAt, int fps)
	{
		MultipartBody.Builder builder = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("token", token)
			.addFormDataPart("captured_at", Long.toString(capturedAt))
			.addFormDataPart("fps", Integer.toString(fps));
		for (int i = 0; i < kept.size(); i++)
		{
			builder.addFormDataPart("frames[]", String.format("frame-%03d.jpg", i),
				RequestBody.create(JPEG, kept.get(i)));
		}
		builder.addFormDataPart("frame_count", Integer.toString(kept.size()));
		return builder.build();
	}

	/**
	 * The activity log is part of core sync — no separate toggle. It's active whenever a valid link
	 * token is set: the SAME gate as the snapshot upload. Disclosed on the link-token config + the
	 * osrsbestinslot connect flow.
	 */
	boolean activityLogActive()
	{
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		return token.matches("^[a-f0-9]{32}$");
	}

	/**
	 * THE ONE UPLOAD GATE. Every OkHttp call site in this plugin calls it and returns early when it
	 * is false: the account snapshot, the activity events, the trade screenshot and the shop clip.
	 *
	 * A valid link token is the gate: the server has nowhere to file an upload without one.
	 */
	boolean uploadAllowed()
	{
		return activityLogActive();
	}

	/**
	 * Buffer one own-account activity event. No-op unless the activity log is active (token linked +
	 * account not opted out). account_hash/rsn are the currently-tracked session's, stamped at emit time
	 * so a logout (player already null) still self-describes. Bounded to MAX_PENDING_EVENTS (drop oldest).
	 */
	void emitEvent(String type, Map<String, Object> fields)
	{
		if (!activityLogActive())
		{
			return;
		}
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("type", type);
		ev.put("ts", System.currentTimeMillis());
		if (activeHash != null)
		{
			ev.put("account_hash", activeHash);
		}
		if (activeRsn != null)
		{
			ev.put("rsn", activeRsn);
		}
		if (fields != null)
		{
			ev.putAll(fields);
		}
		synchronized (pendingEvents)
		{
			pendingEvents.add(ev);
			while (pendingEvents.size() > MAX_PENDING_EVENTS)
			{
				pendingEvents.remove(0);
			}
		}
		// WAVE 2: real-time. A state-changing event forces a fresh snapshot so wealth/state lands now; every
		// event schedules a coalesced flush so it ships in ~1s instead of on the next 5s tick.
		maybeForceSnapshotForEvent(type);
		scheduleCoalescedFlush();
	}

	/**
	 * State-changing events (trade / ge_* / store_* / death) push a fresh snapshot immediately so post-event
	 * wealth lands live. forceSendSnapshot honors all the usual guards (logged in, token, backoff)
	 * and is async. client is null only in buffer-only unit tests — real runtime always has it injected.
	 */
	private void maybeForceSnapshotForEvent(String type)
	{
		if (client != null && SNAPSHOT_TRIGGER_EVENTS.contains(type))
		{
			forceSendSnapshot();
		}
	}

	/**
	 * Micro-coalesce the event flush: the first event schedules a one-shot flush on the background executor;
	 * further events within the window coalesce into it (compareAndSet keeps it to a single scheduled task),
	 * so a burst becomes ONE POST. The flag is cleared before flushing so events arriving during the flush
	 * open a fresh window (never lost). executor is null only in buffer-only unit tests; the 5s eventFlushTask
	 * remains as a safety net. Fire-and-forget — never blocks the client thread.
	 */
	void scheduleCoalescedFlush()
	{
		if (executor == null)
		{
			return;
		}
		if (flushScheduled.compareAndSet(false, true))
		{
			executor.schedule(() ->
			{
				flushScheduled.set(false);
				flushEvents();
			}, eventCoalesceMillis, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Detect session start on the client thread (player available): the first logged-in tick, or a hop
	 * into a different account, emits a "login" event and stamps the session. Called from syncTask.
	 */
	boolean trackSessionStart()
	{
		String rsn = client.getLocalPlayer().getName();
		String hash = Long.toString(client.getAccountHash());
		if (!sessionActive || !hash.equals(activeHash))
		{
			activeRsn = rsn;
			activeHash = hash;
			sessionActive = true;
			sessionStartMillis = System.currentTimeMillis();
			emitEvent("login", loginFields());
			return true;
		}
		activeRsn = rsn; // keep the display name fresh
		return false;
	}

	/** Extra fields carried on the login event. WAVE 3: net worth at login (from the just-built snapshot). */
	private Map<String, Object> loginFields()
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		Long netWorth = lastKnownNetWorth();
		if (netWorth != null)
		{
			fields.put("wealth", netWorth);
		}
		return fields;
	}

	/**
	 * Net worth (gp) from the most recently built snapshot's wealth block, for the login/logout events. Prefers
	 * net_worth_gp (inventory + equipment + bank, populated once the bank has been opened); before then falls
	 * back to the carried inventory + worn equipment value. null only if no snapshot has been built yet.
	 */
	Long lastKnownNetWorth()
	{
		Map<String, Object> snap = lastBuiltSnapshot;
		if (snap == null)
		{
			return null;
		}
		Object w = snap.get("wealth");
		if (!(w instanceof Map))
		{
			return null;
		}
		Map<?, ?> wealth = (Map<?, ?>) w;
		Object net = wealth.get("net_worth_gp");
		if (net instanceof Number)
		{
			return ((Number) net).longValue();
		}
		long inv = wealth.get("inventory_gp") instanceof Number ? ((Number) wealth.get("inventory_gp")).longValue() : 0L;
		long eqp = wealth.get("equipment_gp") instanceof Number ? ((Number) wealth.get("equipment_gp")).longValue() : 0L;
		return inv + eqp;
	}

	/** Emit a "logout" event (duration + classified reason) for the tracked account, if a session was active. */
	void trackLogout()
	{
		trackLogout(classifyLogoutReason());
	}

	/**
	 * WAVE 3: emit a "logout" with an explicit reason (idle / manual / six_hour_cap / connection_lost) plus
	 * session duration and the account's net worth at logout. reason=connection_lost is passed by the
	 * CONNECTION_LOST game-state (which previously emitted nothing).
	 */
	void trackLogout(String reason)
	{
		if (!sessionActive)
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		if (sessionStartMillis > 0)
		{
			fields.put("session_ms", System.currentTimeMillis() - sessionStartMillis);
		}
		if (reason != null)
		{
			fields.put("reason", reason);
		}
		Long netWorth = lastKnownNetWorth();
		if (netWorth != null)
		{
			fields.put("wealth", netWorth);
		}
		emitEvent("logout", fields);
		sessionActive = false;
	}

	/**
	 * Best-effort logout classification from the last logged-in tick's idle counters + session length. A
	 * session length within {@link #SIX_HOUR_SLACK_MS} of the 6h cap is the in-game six-hour logout; both
	 * idle counters high = an idle timeout; otherwise a manual logout. Heuristic (no client API says WHY the
	 * client logged out) — connection loss is signalled explicitly via {@link #trackLogout(String)} instead.
	 */
	String classifyLogoutReason()
	{
		long sessionMs = sessionStartMillis > 0 ? System.currentTimeMillis() - sessionStartMillis : 0L;
		if (sessionMs >= SIX_HOUR_MS - SIX_HOUR_SLACK_MS)
		{
			return "six_hour_cap";
		}
		if (Math.min(lastKeyboardIdleTicks, lastMouseIdleTicks) >= IDLE_LOGOUT_TICKS)
		{
			return "idle";
		}
		return "manual";
	}

	/**
	 * POST buffered activity events to /event-ingest, then clear them. Runs on every @Schedule tick
	 * (including at the login screen) so a logout event flushes promptly. Own-account data only;
	 * token-gated exactly like the snapshot path.
	 *
	 * <p>Delivery is RETRIED, not fire-and-forget. The batch is removed from {@link #pendingEvents}
	 * before the POST (so a slow request cannot double-send it), and on any network failure or non-2xx
	 * response it is put BACK at the FRONT of the buffer, preserving order, for the next 5s tick to
	 * resend. Previously the buffer was cleared before sending and {@code onFailure} only logged at
	 * debug, so every 429/5xx/timeout silently destroyed that batch — the loss correlating exactly with
	 * the periods of highest event volume, when the shared ingest ceiling is being hit.
	 *
	 * <p>Bounded three ways, because an unbounded retry against a rate-limited server is worse than the
	 * data loss it replaces: (1) requeue reuses the existing {@link #MAX_PENDING_EVENTS} cap, so a
	 * server that is down for a long time drops the OLDEST events rather than growing memory without
	 * limit; (2) {@link #eventRetryBackoffUntilMs} makes the next attempt wait, doubling from 5s to a
	 * {@link #EVENT_RETRY_MAX_BACKOFF_MS} ceiling, so we back off instead of hammering; (3) a single
	 * in-flight POST at a time ({@link #eventPostInFlight}), so ticks cannot pile concurrent retries of
	 * the same batch on top of each other. A 429 carrying Retry-After is honoured up to the ceiling.
	 *
	 * <p><b>SCOPE LIMIT — retry is IN-MEMORY ONLY and does not survive a client restart.</b> There is no
	 * on-disk spool: {@link #pendingEvents} is a plain in-memory list, nothing writes it to config or to
	 * a file, and {@code shutDown()} does not flush it. So events buffered or awaiting retry when
	 * RuneLite closes (or crashes, or the plugin is disabled) are LOST — unchanged from previous
	 * versions. What this release fixes is the far more common case: a transient 429/5xx/timeout while
	 * the client keeps running, which previously destroyed the batch instantly and now recovers. A
	 * durable spool would be the next step if restart-loss ever proves material; it is deliberately NOT
	 * in 0.7.4, because writing game-activity data to disk is a larger design and privacy question than
	 * this release should decide.
	 */
	void flushEvents()
	{
		if (!uploadAllowed())
		{
			return;		// upload switch off — send nothing
		}
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return;
		}
		if (nowMs() < eventRetryBackoffUntilMs)
		{
			return;	// backing off after a failure; the events stay buffered
		}
		List<Map<String, Object>> batch;
		// The in-flight claim and the buffer drain happen under ONE lock. Tested separately they are
		// check-then-act: the coalesced-flush executor and the 5s scheduler can both pass the guard
		// before either sets the flag, which allows two concurrent POSTs (violating the stated
		// one-in-flight invariant) and, on double failure, an order inversion where the later requeue
		// lands in front of the older batch. No interleaving was found that sends the same event twice —
		// the drain itself was already atomic — but claiming the flag here costs one line and makes the
		// invariant true rather than nearly true.
		synchronized (pendingEvents)
		{
			if (eventPostInFlight)
			{
				return;	// one batch in flight at a time — a retry must not race its own predecessor
			}
			if (pendingEvents.isEmpty())
			{
				return;
			}
			batch = new ArrayList<>(pendingEvents);
			pendingEvents.clear();
			eventPostInFlight = true;
		}
		// Everything from here to enqueue() must be guarded. The batch has already left the buffer and
		// the in-flight flag is set, so ANY throw in this window loses the batch AND wedges delivery
		// permanently: flushEvents would early-return on eventPostInFlight forever after, with no further
		// log lines, and events would pile up to the cap and rot until the plugin restarts.
		//
		// This is reachable from ordinary user input, not just from an internal bug: apiBaseUrl is a
		// user-editable config field and Request.Builder.url() throws IllegalArgumentException on a
		// malformed value — a pasted non-URL or a leading space is enough. gson.toJson,
		// RequestBody.create and enqueue (RejectedExecutionException from a shut-down dispatcher) share
		// the same window. 0.7.3 also delivered nothing with a bad URL, but it recovered the moment the
		// user fixed the typo; without this guard 0.7.4 would stay silently dead until restart.
		try
		{
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("token", token);
			body.put("events", batch);
			String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
			Request request = new Request.Builder()
				.url(base + "/event-ingest")
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
			okHttpClient.newCall(request).enqueue(buildEventCallback(batch));
		}
		catch (Throwable t)
		{
			// requeueEvents restores the batch, arms the backoff and clears the in-flight flag, so a
			// corrected config recovers on the next tick exactly as it did before 0.7.4.
			log.debug("OSRS BiS event sync could not be dispatched — requeueing {} events", batch.size(), t);
			requeueEvents(batch, 0);
		}
	}

	/** The delivery callback, extracted so the dispatch window above can be guarded as one block. */
	private Callback buildEventCallback(final List<Map<String, Object>> batch)
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS BiS event sync failed — requeueing {} events", batch.size(), e);
				requeueEvents(batch, 0);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.isSuccessful())
					{
						eventPostInFlight = false;
						eventRetryBackoffMs = 0;	// delivered — reset the ladder
						eventRetryBackoffUntilMs = 0L;
						return;
					}
					// Non-2xx: the server did NOT take these events. A 4xx other than 429 is not
					// retryable (bad token / malformed body would fail identically forever), so only
					// 429 and 5xx are requeued; anything else is dropped deliberately and logged.
					int code = response.code();
					if (code == 429 || code >= 500)
					{
						long retryAfterMs = 0L;
						String h = response.header("Retry-After");
						if (h != null)
						{
							try
							{
								retryAfterMs = Long.parseLong(h.trim()) * 1000L;
							}
							catch (NumberFormatException ignored)
							{
								// non-numeric (HTTP-date form) — fall back to the backoff ladder
							}
						}
						log.debug("OSRS BiS event sync HTTP {} — requeueing {} events", code, batch.size());
						requeueEvents(batch, retryAfterMs);
					}
					else
					{
						log.debug("OSRS BiS event sync HTTP {} — dropping {} events (not retryable)",
							code, batch.size());
						eventPostInFlight = false;
					}
				}
				finally
				{
					response.close();
				}
			}
		};
	}

	/**
	 * Put a failed batch back at the FRONT of the pending buffer (preserving chronological order with
	 * anything logged while the POST was in flight) and arm the backoff for the next attempt.
	 *
	 * <p>Re-applies {@link #MAX_PENDING_EVENTS} by dropping the OLDEST events, matching what
	 * {@code logEvent} does. So a long outage degrades to "keep the most recent 500" rather than
	 * unbounded growth — the requeue can never make the plugin a memory problem.
	 *
	 * @param retryAfterMs server-requested delay (0 when none); capped by EVENT_RETRY_MAX_BACKOFF_MS
	 */
	void requeueEvents(List<Map<String, Object>> batch, long retryAfterMs)
	{
		synchronized (pendingEvents)
		{
			pendingEvents.addAll(0, batch);
			while (pendingEvents.size() > MAX_PENDING_EVENTS)
			{
				pendingEvents.remove(0);
			}
		}
		long next = eventRetryBackoffMs <= 0
			? EVENT_RETRY_BASE_BACKOFF_MS
			: Math.min(eventRetryBackoffMs * 2, EVENT_RETRY_MAX_BACKOFF_MS);
		if (retryAfterMs > 0)
		{
			next = Math.max(next, Math.min(retryAfterMs, EVENT_RETRY_MAX_BACKOFF_MS));
		}
		eventRetryBackoffMs = next;
		eventRetryBackoffUntilMs = nowMs() + next;
		eventPostInFlight = false;
	}

	/** Wall clock, isolated so tests can drive the backoff without sleeping. */
	long nowMs()
	{
		return System.currentTimeMillis();
	}

	/**
	 * Longest a client holding the drop-proof grant may go without asking the server whether it
	 * still holds it.
	 *
	 * This is the bound on revocation latency for an IDLE client, and it is the number
	 * ops/ACTIVATION.md must quote. Fifteen minutes, chosen against the two costs it sits between:
	 * the server allows 150 requests per hour per token and this spends 4 of them, and a screen
	 * recorder that can start under a grant the operator already withdrew should not be able to do
	 * so for a whole session.
	 *
	 * It bounds the IDLE case only. A client that is actually drop-trading refreshes its policy far
	 * sooner, because "drop" is in SNAPSHOT_TRIGGER_EVENTS and maybeForceSnapshotForEvent bypasses
	 * the hash gate entirely.
	 */
	static final long DROP_PROOF_POLICY_HEARTBEAT_MILLIS = 900_000L;

	/**
	 * Non-async @Schedule runs on the client thread, so reading client state below is safe.
	 * The network POST itself is async (OkHttp enqueue), so it never blocks the game.
	 */
	/**
	 * Activity-log flush runs on its own schedule (not piggybacked on syncTask) so it fires even at the
	 * login screen — a "logout" event reaches the server promptly — and stays independent of the
	 * snapshot change-gate. Self-gates on the link token; no-op when nothing is buffered.
	 */
	@Schedule(period = 5, unit = ChronoUnit.SECONDS)
	public void eventFlushTask()
	{
		flushEvents();
	}

	@Schedule(period = 5, unit = ChronoUnit.SECONDS)
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
		// WAVE 3: sample idle counters on the last logged-in tick so a later logout can be classified idle/manual.
		lastKeyboardIdleTicks = client.getKeyboardIdleTicks();
		lastMouseIdleTicks = client.getMouseIdleTicks();
		// Resolve a deferred death once the containers have settled (a few ticks after ActorDeath). If a syncTask
		// fires too soon after death, wait for the next one — items may not be removed yet.
		if (deathPending != null && client.getTickCount() - deathPending.tick >= DEATH_SETTLE_TICKS)
		{
			resolveDeathPendingViaLiveContainers();
		}
		// Build + cache the snapshot FIRST so trackSessionStart's login event can carry wealth-at-login (WAVE 3)
		// and so the new-login force-send below ships this exact snapshot.
		Map<String, Object> snapshot = buildSnapshot();
		String hash = canonicalHash(snapshot);
		// Track on EVERY tick regardless of the gates below: the logout flush sends this cache.
		lastBuiltSnapshot = snapshot;
		lastBuiltHash = hash;

		boolean newLogin = trackSessionStart(); // emits a "login" event on the first tick of a session / after a hop

		long now = System.currentTimeMillis();
		if (now < backoffUntilMillis)
		{
			return; // rate-limited by the server — build + track only, never send
		}
		if (newLogin)
		{
			// WAVE 2: bind the login server-side immediately. Send this snapshot now, bypassing the
			// unchanged-hash gate AND the debounce, so login (and wealth-at-login) lands live even on a
			// re-link to the same state — the "login drops on a fresh link" race. Still honors the 429 backoff.
			lastSendMillis = now;
			postSnapshot(token, snapshot, hash);
			return;
		}
		if (hash.equals(lastUploadedHash))
		{
			// THE POLICY HEARTBEAT. applyServerPolicy has exactly one caller, inside the
			// postSnapshot response callback, so a client that sends nothing has NO policy channel
			// at all and keeps whatever grant it last read. For an idle logged-in player the
			// canonical hash is stable, so this return used to make the revocation latency
			// unbounded, and ops/ACTIVATION.md A0d promised the opposite.
			//
			// SCOPED TO CLIENTS THAT HOLD THE GRANT, on purpose. A client with no drop-proof grant
			// has nothing to revoke and gets no extra traffic at all, so the server's 150 req/hr
			// per token budget is untouched for every ordinary user. A granted client costs at most
			// one extra request per DROP_PROOF_POLICY_HEARTBEAT_MILLIS, which is 4 per hour.
			//
			// It deliberately does NOT force a send for a client whose grant is off. That direction
			// is a GRANT arriving, not a revocation, and failing to hear about it only delays a
			// feature. Failing to hear about a revocation keeps a screen recorder alive.
			if (!dropProofEnabled()
				|| now - lastSendMillis < DROP_PROOF_POLICY_HEARTBEAT_MILLIS)
			{
				return; // nothing meaningfully changed since the last accepted upload
			}
			lastSendMillis = now;
			postSnapshot(token, snapshot, hash);
			return;
		}
		if (now - lastSendMillis < minUploadIntervalMillis)
		{
			return; // state keeps changing (e.g. xp grinding) — debounce scheduled sends
		}
		lastSendMillis = now;
		postSnapshot(token, snapshot, hash);
	}

	/**
	 * Force one snapshot send NOW, bypassing the unchanged-hash gate AND the debounce interval. Shared by
	 * capture-on-open (bank / collection log) and the snapshot-trigger events. It still honors the
	 * guards that make a send valid at all — logged in, token set — and an active
	 * 429 backoff (the server explicitly said stop; a client-side force never overrides that). It
	 * deliberately does NOT touch lastSendMillis: a capture-on-open send can land a tick before the data
	 * finishes populating (the collection log fills as its draw scripts run), and updating the debounce
	 * clock here would hold back the real snapshot the next tick sends. lastUploadedHash is still recorded
	 * on accept (shared postSnapshot callback), so an unchanged follow-up tick won't re-send; the only
	 * cost is at most one duplicate POST if a scheduled tick races the async accept, which the server
	 * dedupes by hash. Must run on the client thread (WidgetLoaded delivery, or ClientThread.invoke) so
	 * reading client containers is safe.
	 */
	void forceSendSnapshot()
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
		if (System.currentTimeMillis() < backoffUntilMillis)
		{
			return; // server-imposed 429 backoff still wins — a manual force never overrides it
		}
		Map<String, Object> snapshot = buildSnapshot();
		String hash = canonicalHash(snapshot);
		lastBuiltSnapshot = snapshot;	// keep the logout-flush cache coherent, exactly like syncTask
		lastBuiltHash = hash;
		postSnapshot(token, snapshot, hash);
	}

	/**
	 * Change-detection hash: SHA-256 of the snapshot JSON minus the volatile fields. captured_at
	 * moves every build, and the wealth block derives from GE prices, which jitter without any
	 * real account change — the underlying items are still hashed via the container blocks.
	 */
	String canonicalHash(Map<String, Object> snapshot)
	{
		Map<String, Object> canonical = new LinkedHashMap<>(snapshot);
		canonical.remove("captured_at");
		canonical.remove("wealth");
		return sha256Hex(gson.toJson(canonical));
	}

	static String sha256Hex(String s)
	{
		try
		{
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
				.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		}
		catch (java.security.NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e); // JVM-mandatory algorithm
		}
	}

	/**
	 * Logout flush: if the newest built snapshot never got uploaded (hash gate or debounce held it
	 * back), send the CACHED copy so the final session state (e.g. a bank opened late) always
	 * lands. Bypasses the debounce; an active 429 backoff still wins — the server said stop.
	 * Reads the cache, never live client containers — those are mid-teardown during the logout
	 * transition.
	 */
	void flushPendingSnapshot()
	{
		Map<String, Object> snapshot = lastBuiltSnapshot;
		String hash = lastBuiltHash;
		if (snapshot == null || hash == null || hash.equals(lastUploadedHash))
		{
			return;
		}
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return;
		}
		if (System.currentTimeMillis() < backoffUntilMillis)
		{
			return;
		}
		lastSendMillis = System.currentTimeMillis();
		postSnapshot(token, snapshot, hash);
	}

	/**
	 * A completed trade changes wealth. Rebuild the snapshot cache immediately (client thread,
	 * containers valid right after "Accepted trade.") so the logout flush carries POST-trade wealth
	 * even when no periodic tick ran between the trade and logout. Independent of the trade-screenshot
	 * opt-in — wealth tracking is separate from delivery-proof capture. If the player stays online,
	 * the next syncTask still sends it through the change gate.
	 */
	private void refreshSnapshotCacheAfterTrade()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return;
		}
		Map<String, Object> snapshot = buildSnapshot();
		lastBuiltSnapshot = snapshot;
		lastBuiltHash = canonicalHash(snapshot);
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

	/** Clog + trade state are per-account — clear them on hop / relog so we never mix two accounts. */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		// A store-clip visit must not survive a hop / logout / disconnect. End it here; frames are still
		// valid delivery proof if the visit had a buy/sell (upload=true only uploads when it did), else dropped.
		if (clipCapturing
			&& (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST))
		{
			stopStoreClipCapture(true);
		}
		// A hop or a disconnect ends the shop session, so the observed phase no longer describes the
		// shop in front of us. Drop it: an anchor that survives a hop is a confidently wrong countdown.
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			storeResetAnchorMs = 0;
			removeResetTimer();
		}
		switch (event.getGameState())
		{
			case LOADING:
				// Region transition — a NEW instanced chest may lie ahead; the belt keys are per-visit.
				chestLooted = false;
				lastChestEmitKey = null;
				// Scene reload replays existing ground piles as fresh ItemSpawned events (Codex consult
				// 2026-07-18) — a surviving pending could pair a replayed same-id pile with an unrelated
				// inventory loss. A drop clicked right before a region boundary is lost; miss beats fabricate.
				clearInvDeltaPendings();
				// A scene reload despawns EVERY pile in the old scene at once. Those removals say nothing
				// about anyone taking the item, so stop trusting observation and discard what we tracked
				// rather than emit a wave of "removed_early" that means nothing.
				groundObservationUnreliable = true;
				clearGroundDrops();
				clearPendingRemovals();
				interruptDropSession();	// a scene reload ends the session as INTERRUPTED; footage still uploads
				break;
			case HOPPING:
			case LOGGING_IN:
				clogObtained.clear();
				clogSeen = false;
				geAwaitingLogin = true;	// the next LOGGED_IN replays the GE slots
				resetTradeState();	// a pending trade frame must never leak across accounts/sessions
				clearInvDeltaPendings();	// an armed drop/pickup/alch must never resolve across a hop/relog
				endBankSession(false);	// a bank left open across a hop/relog: incomplete, never diffed against another account
				equipLast = null;	// re-baseline equipment on the next change so a relog emits no full-kit diff
				flushXpGain();		// a hop ends the xp window: emit what accumulated, never drop it
				xpFlushTicks = 0;
				lastSkillXp.clear();	// xp deltas re-baseline per account
				xpAccum.clear();
				lastRegion = null;
				clearFirehose();	// buffered firehose rows must not carry across accounts
				groundObservationUnreliable = true;
				clearGroundDrops();
				clearPendingRemovals();		// ground state is per world AND per account
				interruptDropSession();	// a hop ends the session as INTERRUPTED; footage still uploads
				shopVisitNearby.clear();	// nearby-candidate set must not carry rsns across accounts
				shopStock.clear();		// stock/sold/at-tx state is per visit AND per account
				storeResetAnchorMs = 0;	// phase is per-visit: never carry it across accounts
				lastStockChangeMs = 0;
				storeProbeItem = 0;
				soldThisVisit.clear();
				defaultStockSoldThisVisit.clear();
				lastSellTickThisVisit.clear();
				shopkeeperName = null;
				shopkeeperIndex = -1;
				nearbyAtTx = null;
				chestLooted = false;
				lastChestEmitKey = null;
				break;
			case CONNECTION_LOST:
				clogObtained.clear();
				clogSeen = false;
				geAwaitingLogin = true;
				resetTradeState();
				clearInvDeltaPendings();	// an armed drop/pickup/alch must never survive a disconnect
				endBankSession(false);	// a bank open at disconnect: incomplete, never diffed against the next session
				equipLast = null;	// re-baseline equipment on reconnect
				flushXpGain();		// a disconnect ends the xp window: emit what accumulated, never drop it
				xpFlushTicks = 0;
				lastSkillXp.clear();
				xpAccum.clear();
				lastRegion = null;
				clearFirehose();
				groundObservationUnreliable = true;
				clearGroundDrops();
				clearPendingRemovals();
				interruptDropSession();	// a disconnect ends the session as INTERRUPTED; footage still uploads
				// a feed-death then instant disconnect: record the death, but the containers here are null or
				// not-yet-settled, so OMIT items_lost (computeLoss=false) rather than emit a wrong diff.
				resolveDeathPending(null, false);
				// WAVE 3: a dropped connection previously emitted NOTHING. Emit an explicit logout so a crash /
				// disconnect is distinguishable from a clean logout. Ends the session; a reconnect re-logs in.
				trackLogout("connection_lost");
				break;
			case LOGIN_SCREEN:
				// Real logout (HOPPING keeps the session and is handled above, without a flush).
				geAwaitingLogin = true;
				clearInvDeltaPendings();	// an armed drop/pickup/alch must never survive a logout
				endBankSession(false);	// a bank open at logout: incomplete, never diffed against the next session
				lastWorld = 0;	// the next login's WorldChanged must not be read as a hop
				flushXpGain();		// flush accumulated xp before the session ends
				xpFlushTicks = 0;
				flushFirehose();	// flush batched firehose rows before the session ends
				lastSkillXp.clear();
				xpAccum.clear();
				lastRegion = null;
				clearFirehose();
				groundObservationUnreliable = true;
				clearGroundDrops();
				clearPendingRemovals();
				interruptDropSession();	// a logout ends the session as INTERRUPTED; footage still uploads
				resolveDeathPending(null, false);	// feed-death then logout: record death, omit untrustworthy items_lost
				trackLogout(); // buffer a "logout" event (duration + reason); flushed live (WAVE 2) / next tick
				flushPendingSnapshot();
				break;
			case LOGGED_IN:
				// Scene is settled again. Nothing tracked survives from before, so observation is trustworthy
				// for piles dropped from here on.
				groundObservationUnreliable = false;
				// A login to the world the login screen already had fires no WorldChanged, so lastWorld stayed 0
				// and the FIRST hop of the session was read as the login and emitted nothing (F-H1). Seed it
				// here. Only when unset: after a hop lastWorld already holds the world and must not be touched.
				if (lastWorld == 0 && client != null)
				{
					lastWorld = client.getWorld();
				}
				// Only a real login or hop replays the GE slots. LOGGED_IN also follows every scene LOADING, and a
				// fill landing right after a region change must not be swallowed as a replay.
				if (client != null && geAwaitingLogin)
				{
					geLoginTick = client.getTickCount();
				}
				geAwaitingLogin = false;
				break;
			default:
				break;
		}
	}

	// ---- trade screenshot: state machine (IDLE -> tradeActive -> ARMED frame -> commit/discard) ----

	/** Our trade-offer container (gameval id 90) changing marks a trade as genuinely in progress. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		handleTradeContainerChanged(event.getContainerId());
		if (shopOpen && event.getContainerId() != INVENTORY_CONTAINER_ID)
		{
			handleShopStockChanged(event.getItemContainer());
		}
		// WAVE 6 (store price): resolve a store buy/sell against the live inventory coin count. Read the coins
		// from the changed container itself (final post-transaction state) — independent of the trade gate above.
		if (event.getContainerId() == INVENTORY_CONTAINER_ID)
		{
			ItemContainer inv = event.getItemContainer();
			long coinsAfter = countItem(inv, COINS_ID);
			int tick = client == null ? 0 : client.getTickCount();
			// The transacted item's post count too: its distance from the pending's baseline is the
			// quantity the server ACTUALLY moved, which is what licenses a derived unit price.
			StorePending sp = storePending;
			long itemAfter = (sp == null || inv == null) ? UNKNOWN_ITEM_COUNT : countItem(inv, sp.item);
			resolveStorePendingOnInventoryChange(coinsAfter, itemAfter, tick);
			// Off-book drop/pickup/alch resolve on the same INVENTORY change from the item-count delta.
			// EVERY armed pending is offered this change, each against its own item count — see
			// resolveInvDeltaPendings: a stale drop at the head must never hide a real pickup behind it.
			resolveInvDeltaPendings(inv, coinsAfter, tick);
		}
		if (event.getContainerId() == EQUIP_CONTAINER_ID)
		{
			handleEquipmentChanged(event.getItemContainer());
		}
		if (event.getContainerId() == BANK_CONTAINER_ID)
		{
			handleBankContainerChanged(event.getItemContainer());
		}
	}

	/**
	 * Emit equip_change from the diff of the worn-equipment container against its last-seen state. The
	 * first change after a login only baselines, so a normal gear load does not emit a spurious full-kit
	 * equip. Own account, and the same data class as the equipment snapshot.
	 */
	void handleEquipmentChanged(ItemContainer worn)
	{
		if (worn == null)
		{
			return;
		}
		Map<Integer, Long> now = new LinkedHashMap<>();
		addContainerCounts(now, worn);
		Map<Integer, Long> before = equipLast;
		equipLast = now;
		if (before == null || !activityLogActive())
		{
			return;		// first observation this session is a baseline, no event
		}
		// Diff the id SETS only. A quantity change of an id worn before and after (ammo shot, a dart
		// thrown, a stack topped up) is not an equip/unequip and emits nothing.
		List<Map<String, Object>> equipped = new ArrayList<>();
		List<Map<String, Object>> unequipped = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : now.entrySet())
		{
			if (!before.containsKey(e.getKey()))
			{
				equipped.add(itemMapLong(e.getKey(), e.getValue()));
			}
		}
		for (Map.Entry<Integer, Long> e : before.entrySet())
		{
			if (!now.containsKey(e.getKey()))
			{
				unequipped.add(itemMapLong(e.getKey(), e.getValue()));
			}
		}
		if (equipped.isEmpty() && unequipped.isEmpty())
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		if (!equipped.isEmpty())
		{
			fields.put("equipped", equipped);
		}
		if (!unequipped.isEmpty())
		{
			fields.put("unequipped", unequipped);
		}
		emitEvent("equip_change", fields);
	}

	/** World hop: emit world_hop {from,to} when the world changes. The login's first change is suppressed. */
	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		if (client == null)
		{
			return;
		}
		int w = client.getWorld();
		int prev = lastWorld;
		lastWorld = w;
		if (prev != 0 && w != prev && activityLogActive())
		{
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("from", prev);
			fields.put("to", w);
			emitEvent("world_hop", fields);
		}
	}

	void handleTradeContainerChanged(int containerId)
	{
		if (tradeScreenshotsDisabled())
		{
			return;
		}
		if (containerId == TRADE_OFFER_CONTAINER_ID)
		{
			tradeActive = true;
		}
	}

	/**
	 * Confirm screen (group 334) loading is the frame-grab moment: the "You will give / You will
	 * receive" window is the strongest proof frame and it is still on-screen. Buffer only — the
	 * upload commits on "Accepted trade." (capturing on the chat line instead would grab the NEXT
	 * frame, after the window already closed; uploading here would ship trades that get declined).
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Poll the other player's trade offer + name while the MAIN screen (335) is open — the only window
		// they are readable (see captureOtherOfferWhileMainOpen). Tight-gated: the body runs only during an
		// active trade with activity logging on; a cheap boolean check otherwise.
		if (tradeMainOpen && activityLogActive())
		{
			captureOtherOfferWhileMainOpen();
		}
		if (shopOpen && activityLogActive())
		{
			accumulateShopNearby();		// build the receiver-candidate set across the whole shop visit
		}
		if (activityLogActive())
		{
			if (++xpFlushTicks >= XP_FLUSH_TICKS)
			{
				xpFlushTicks = 0;
				flushXpGain();		// coalesced xp_gain every 5 minutes
			}
			checkRegionChange();	// region {from,to} on map-region change
			if (maxCapture())
			{
				capturePosition();	// per-tick position trail (firehose)
				if (++firehoseFlushTicks >= FIREHOSE_FLUSH_TICKS)
				{
					firehoseFlushTicks = 0;
					flushFirehose();
				}
			}
		}
		// RuneLite's Timer infobox removes ITSELF when it reaches zero, so a countdown armed at one
		// observed reset covers exactly one 60s cycle and then vanishes. The phase is still known,
		// so re-arm for the next cycle. This extrapolates from a REAL observed anchor, never a
		// guess, and the next observed reset re-anchors it.
		if (shopOpen && resetTimer == null && resetPhaseKnown(storeResetAnchorMs))
		{
			reArmAttempts++;
			showResetTimer();
		}
		if (client != null)
		{
			settlePendingRemovals(client.getTickCount(), true, false);
			pollDropSession();	// the 5-second tail after the last pile, checked once per tick
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		handleTradeWidgetLoaded(event.getGroupId());
		handleActivityWidgetLoaded(event.getGroupId());
		handleCaptureOnOpenWidgetLoaded(event.getGroupId());
		handleChestLootWidgetLoaded(event.getGroupId());
	}

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
		if (!activityLogActive() || client == null)
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
				Map<String, Object> loc = currentLocation();
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
			ItemContainer c = client.getItemContainer(containerId);
			if (c == null)
			{
				continue;
			}
			for (Item item : c.getItems())
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
	 * Capture-on-open: opening the bank (BANKMAIN 12) or the collection log (COLLECTION 621) forces an
	 * immediate snapshot so those containers sync the moment they become readable, instead of waiting up
	 * to a full debounce interval for the next scheduled tick (the "opened my bank, still says not synced"
	 * complaint). WidgetLoaded is delivered on the client thread, so forceSendSnapshot reads containers
	 * directly. Its own guards (token / backoff) still apply — an unlinked account
	 * opening its bank sends nothing.
	 */
	void handleCaptureOnOpenWidgetLoaded(int groupId)
	{
		if (groupId == BANK_GROUP_ID || groupId == COLLECTION_LOG_GROUP_ID)
		{
			forceSendSnapshot();
		}
		if (groupId == BANK_GROUP_ID && activityLogActive())
		{
			// Snapshot the bank the moment it opens; handleBankWidgetClosed diffs against this on close.
			// The bank is readable here — the same read forceSendSnapshot just used to set bank_synced.
			Map<Integer, Long> b = new LinkedHashMap<>();
			addContainerCounts(b, client.getItemContainer(InventoryID.BANK));
			// A session still running here was never closed: fold in this read (moves since the last change)
			// and end it as incomplete before starting fresh.
			advanceBankSession(client.getItemContainer(InventoryID.BANK));
			endBankSession(false);
			// An empty or unloaded container is not a baseline: wait for the first bank contents instead.
			bankAtOpen = b.isEmpty() ? null : b;
			bankBaselinePending = b.isEmpty();
		}
	}

	/**
	 * The bank contents arrived after an open whose container was empty or unloaded: take this first
	 * populated state as the baseline. No event is emitted here. The close diffs against this baseline.
	 */
	void handleBankContainerChanged(ItemContainer bank)
	{
		if (bankBaselinePending)
		{
			Map<Integer, Long> b = new LinkedHashMap<>();
			addContainerCounts(b, bank);
			if (!b.isEmpty())
			{
				bankAtOpen = b;
				bankBaselinePending = false;
			}
			return;
		}
		advanceBankSession(bank);
	}

	/**
	 * Add the movement from the last-seen bank to {@code bank} to the session's GROSS totals and make it
	 * the last-seen state. A null or EMPTY read is skipped, never read as "everything was withdrawn": an
	 * empty container is not evidence (the first-open rule above), and one transient empty read would
	 * otherwise fabricate a whole-bank withdraw and a whole-bank deposit. Cost: emptying the bank of its
	 * very last stack is not seen. Miss beats fabricate.
	 */
	private void advanceBankSession(ItemContainer bank)
	{
		Map<Integer, Long> before = bankAtOpen;
		if (before == null || bank == null)
		{
			return;
		}
		Map<Integer, Long> now = new LinkedHashMap<>();
		addContainerCounts(now, bank);
		if (now.isEmpty())
		{
			return;
		}
		for (Map.Entry<Integer, Long> e : before.entrySet())
		{
			long delta = now.getOrDefault(e.getKey(), 0L) - e.getValue();
			if (delta < 0)
			{
				bankGrossWithdrawn.merge(e.getKey(), -delta, Long::sum);
			}
			else if (delta > 0)
			{
				bankGrossDeposited.merge(e.getKey(), delta, Long::sum);
			}
		}
		for (Map.Entry<Integer, Long> e : now.entrySet())
		{
			if (!before.containsKey(e.getKey()))
			{
				bankGrossDeposited.merge(e.getKey(), e.getValue(), Long::sum);
			}
		}
		bankAtOpen = now;
	}

	/**
	 * Bank close: fold in the final bank read, then emit ONE bank_session for the session. Bank transfers
	 * fire no trade / GE / store event, so this is the only event-plane record of what left or entered the
	 * bank. Own account; same data class as the bank snapshot, which the hub already approved.
	 */
	void handleBankWidgetClosed(int groupId)
	{
		handleBankWidgetClosed(groupId, false);
	}

	/**
	 * True when a bank close happens because the client is leaving the world (hop, logout, disconnect)
	 * rather than the player closing it. NOT WidgetClosed.isUnload(): the rig (2026-09-27, 0.7.15 @ 1af4d7f)
	 * showed an ordinary X-button bank close arrives with unload=true, which marked every visit incomplete.
	 */
	private boolean bankCloseIsTeardown()
	{
		GameState s = client == null ? null : client.getGameState();
		return s == GameState.HOPPING || s == GameState.LOGIN_SCREEN || s == GameState.CONNECTION_LOST
			|| s == GameState.LOGGING_IN;
	}

	/**
	 * @param unload true when the client tore the bank down for a hop or logout rather than the player
	 *               closing it: the session is incomplete and the bank read during teardown is not used.
	 */
	void handleBankWidgetClosed(int groupId, boolean unload)
	{
		if (groupId != BANK_GROUP_ID)
		{
			return;
		}
		if (unload)
		{
			endBankSession(false);
			return;
		}
		if (client != null)
		{
			advanceBankSession(client.getItemContainer(InventoryID.BANK));
		}
		endBankSession(true);
	}

	/**
	 * End the running bank session and emit its bank_session summary: GROSS deposited[] and withdrawn[]
	 * per item, each capped at BANK_SESSION_ITEM_CAP (truncated:true when cut), and complete:false when
	 * the session ended without a bank close (hop, logout, disconnect, a re-open). An incomplete row
	 * carries only moves actually observed; nothing is inferred for the part we did not see. No row when
	 * nothing moved, so an idle bank visit costs no event.
	 */
	private void endBankSession(boolean complete)
	{
		bankAtOpen = null;
		bankBaselinePending = false;
		if ((bankGrossDeposited.isEmpty() && bankGrossWithdrawn.isEmpty()) || !activityLogActive())
		{
			bankGrossDeposited.clear();
			bankGrossWithdrawn.clear();
			return;
		}
		boolean[] truncated = {false};
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("deposited", cappedItemList(bankGrossDeposited, truncated));
		f.put("withdrawn", cappedItemList(bankGrossWithdrawn, truncated));
		f.put("complete", complete);
		if (truncated[0])
		{
			f.put("truncated", true);
		}
		bankGrossDeposited.clear();
		bankGrossWithdrawn.clear();
		emitEvent("bank_session", f);
	}

	private List<Map<String, Object>> cappedItemList(Map<Integer, Long> totals, boolean[] truncated)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : totals.entrySet())
		{
			if (out.size() >= BANK_SESSION_ITEM_CAP)
			{
				truncated[0] = true;
				break;
			}
			out.add(itemMapLong(e.getKey(), e.getValue()));
		}
		return out;
	}

	/**
	 * Activity log (independent of the screenshot opt-in): track the general-store window opening, and at
	 * the trade confirm screen snapshot our own offer + the partner name so a structured "trade" event can
	 * be emitted on "Accepted trade.". Own-account items; counterparty forwarded for ALL users (disclosed).
	 */
	void handleActivityWidgetLoaded(int groupId)
	{
		if (!activityLogActive())
		{
			return;
		}
		if (groupId == SHOP_GROUP_ID)
		{
			shopOpen = true;
			// Re-place the overlays under THIS shop's item grid. A user drag still wins: the
			// overlays only apply a default when RuneLite has no stored location for them.
			if (resetOverlay != null)
			{
				resetOverlay.resetAnchorForVisit();
			}
			if (nearbyOverlay != null)
			{
				nearbyOverlay.resetAnchorForVisit();
			}
			shopVisitNearby.clear();	// fresh receiver-candidate set for this shop visit
			shopStock.clear();		// baseline is taken from this visit's first container change
			storeResetAnchorMs = 0;		// a new visit starts with the phase UNKNOWN
			lastStockChangeMs = 0;
			storeProbeItem = 0;		// and with no probe until the first item is sold
			soldThisVisit.clear();
			defaultStockSoldThisVisit.clear();
			lastSellTickThisVisit.clear();
			shopkeeperName = currentShopkeeperName();
			shopkeeperIndex = currentShopkeeperIndex();
			nearbyAtTx = null;
			accumulateShopNearby();		// seed with whoever is already standing here at open
			startStoreClipCapture();	// arm burst capture for this visit (no-op unless opt-in + server-allowed)
		}
		else if (groupId == TRADE_MAIN_GROUP_ID)
		{
			// A 335-load ALWAYS begins a fresh trade, so clear the other-player pendings first.
			//
			// Without this, a trade that ends WITHOUT acceptance leaks into the next one. A decline routes
			// through handleTradeChat, which early-returns when screenshots are off (the DEFAULT), so
			// resetTradeState() never runs; and handleTradeWidgetClosed only resets when tradeArmed, which
			// is set at confirm-load with screenshots on — so a trade abandoned at the first screen never
			// arms either. Combined with 0.7.4's last-non-empty-wins poll and its "only fill if empty"
			// confirm guards, the stale values then SURVIVE a next trade whose partner offers nothing —
			// the classic gold-delivery shape. Demonstrated: Bob declines offering a Bandos chestplate,
			// the player then gives an item to Alice who offers nothing, and the emitted event reads
			// counterparty=Alice received=[Bandos chestplate].
			//
			// That is worse than the bug 0.7.4 fixes: 0.7.3 emitted EMPTY fields, this would write
			// confidently WRONG trade rows on staff delivery accounts, and attribute a third player's RSN
			// to a trade he was never in. Clearing at 335-open closes it at the only point that is
			// guaranteed to run for every trade.
			pendingCounterparty = null;
			pendingTradeReceived = null;
			pendingReceivedText = null;
			// Main trade screen open: begin polling the other player's side (readable here, gone by confirm).
			// onGameTick keeps the buffer current; the last read before the 334 transition = the final offer.
			tradeMainOpen = true;
			captureOtherOfferWhileMainOpen();	// initial read (offer may still be empty; the poll catches it)
		}
		else if (groupId == TRADE_CONFIRM_GROUP_ID)
		{
			tradeMainOpen = false;	// moved to confirm — stop polling; the last 335 poll holds the final offer
			pendingTradeGiven = readOwnOffer();	// own offer container (90) persists across the transition
			// Counterparty + received[] come from the 335 poll above (the only place they are readable). Fall
			// back to the confirm screen ONLY when the poll captured nothing — counterpartyName() is usually
			// null here (335 title gone) and readReceivedOffer() reads the value-text column.
			if (pendingCounterparty == null || pendingCounterparty.isEmpty())
			{
				pendingCounterparty = counterpartyName();
			}
			if (pendingTradeReceived == null || pendingTradeReceived.isEmpty())
			{
				pendingTradeReceived = readReceivedOffer();
			}
			// Lossless text fallback only when we still have no structured items (e.g. big "Lots!" trade the
			// poll missed) — keeps the existing "no received_text when items exist" contract.
			if ((pendingTradeReceived == null || pendingTradeReceived.isEmpty())
				&& (pendingReceivedText == null || pendingReceivedText.isEmpty()))
			{
				pendingReceivedText = readReceivedText();
			}
		}
	}

	/**
	 * While the MAIN trade screen (335) is open, snapshot the other player's offer + name — the only place
	 * they are readable as item sprites (the confirm screen 334 replaces 335 and shows the other side as a
	 * value-text summary that collapses to "Lots!"; its 335-only title widget is gone by 334-load). Last
	 * non-empty read wins, so the buffer holds the FINAL offer at the moment we transition to confirm.
	 * Null-safe; runs on the client thread from onGameTick / WidgetLoaded.
	 */
	void captureOtherOfferWhileMainOpen()
	{
		if (client == null)
		{
			return;
		}
		List<Map<String, Object>> other = new ArrayList<>();
		collectReceivedItems(client.getWidget(TRADE_MAIN_OTHER_OFFER), other);
		if (!other.isEmpty())
		{
			pendingTradeReceived = other;
		}
		String cp = counterpartyName();
		if (cp != null && !cp.isEmpty())
		{
			pendingCounterparty = cp;
		}
	}

	/** Snapshot the items in our own trade offer (container 90) as [{id, qty}]. */
	private List<Map<String, Object>> readOwnOffer()
	{
		ItemContainer c = client.getItemContainer(TRADE_OFFER_CONTAINER_ID);
		List<Map<String, Object>> items = new ArrayList<>();
		if (c != null)
		{
			for (Item it : c.getItems())
			{
				if (it != null && it.getId() >= 0 && it.getQuantity() > 0)
				{
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", it.getId());
					m.put("qty", it.getQuantity());
					items.add(m);
				}
			}
		}
		return items;
	}

	/**
	 * WAVE 1b — read the counterparty's side (what WE receive) from the confirm-screen "You will receive"
	 * widget (component {@link #TRADE_CONFIRM_RECEIVE_COMPONENT}). There is NO counterparty ItemContainer, so
	 * this widget is the only source. Tries item-bearing children first → [{id, qty}]; returns an EMPTY list if
	 * none are present (the caller then falls back to {@link #readReceivedText}). Null-safe throughout: the
	 * widget, its child arrays and per-child item fields may all be absent.
	 *
	 * ⚠ NOT unit-verifiable against real runtime. Two things need one in-game two-sided trade to confirm:
	 * (1) whether this widget exposes item CHILDREN at all (it is likely a text summary — item sprites may
	 * instead live under Tradeconfirm.OTHER_OFFER / 21889053), and (2) whether it is even populated at
	 * WidgetLoaded time (the confirm-screen CS2 scripts may run a tick later). Written defensively so an empty
	 * read never throws and never blocks the text fallback.
	 */
	List<Map<String, Object>> readReceivedOffer()
	{
		List<Map<String, Object>> items = new ArrayList<>();
		if (client == null)
		{
			return items;
		}
		collectReceivedItems(client.getWidget(TRADE_CONFIRM_RECEIVE_COMPONENT), items);
		return items;
	}

	/** Collect item-bearing widgets (the widget itself + its children) as [{id, qty}]. Null-safe. */
	private void collectReceivedItems(Widget w, List<Map<String, Object>> out)
	{
		if (w == null)
		{
			return;
		}
		addReceivedItem(w, out);
		Widget[] kids = w.getChildren();
		if (kids == null || kids.length == 0)
		{
			kids = w.getDynamicChildren();	// some interfaces expose item slots only via the dynamic-child array
		}
		if (kids != null)
		{
			for (Widget k : kids)
			{
				addReceivedItem(k, out);
			}
		}
	}

	/** Append {id, qty} if this widget carries a real item (id/qty > 0). */
	private void addReceivedItem(Widget w, List<Map<String, Object>> out)
	{
		if (w == null)
		{
			return;
		}
		int id = w.getItemId();
		int qty = w.getItemQuantity();
		if (id > 0 && qty > 0)
		{
			out.add(itemMap(id, qty));
		}
	}

	/**
	 * WAVE 1b fallback — the "You will receive" column is likely a TEXT summary ("Blood rune x 100<br>Coins
	 * x 5,000"), not item sprites. When no item children are found, capture that RAW text (tags/&lt;br&gt;
	 * intact so nothing is lost, child lines joined) as received_text. Returns null only when there is no
	 * actual content (emptiness is tested tag-stripped). Null-safe.
	 */
	String readReceivedText()
	{
		if (client == null)
		{
			return null;
		}
		Widget w = client.getWidget(TRADE_CONFIRM_RECEIVE_COMPONENT);
		if (w == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		appendReceivedText(w, sb);
		Widget[] kids = w.getChildren();
		if (kids == null || kids.length == 0)
		{
			kids = w.getDynamicChildren();
		}
		if (kids != null)
		{
			for (Widget k : kids)
			{
				appendReceivedText(k, sb);
			}
		}
		String raw = sb.toString().trim();
		return Text.removeTags(raw).trim().isEmpty() ? null : raw;
	}

	/** Append a widget's non-empty raw text as its own line. */
	private void appendReceivedText(Widget w, StringBuilder sb)
	{
		if (w == null)
		{
			return;
		}
		String t = w.getText();
		if (t != null && !t.isEmpty())
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append(t);
		}
	}

	/** Trade partner name from the trade window title ("Trading With: Name"), or null. */
	private String counterpartyName()
	{
		Widget w = client.getWidget(TRADE_TITLE_COMPONENT);
		if (w == null)
		{
			return null;
		}
		String t = Text.removeTags(w.getText() == null ? "" : w.getText()).trim();
		int idx = t.toLowerCase(java.util.Locale.ROOT).indexOf("with");
		if (idx >= 0)
		{
			String name = t.substring(idx + 4).replaceFirst("^[:\\s]+", "").trim();
			return name.isEmpty() ? null : name;
		}
		return t.isEmpty() ? null : t;
	}

	void handleTradeWidgetLoaded(int groupId)
	{
		if (tradeScreenshotsDisabled())
		{
			return;
		}
		// Receive-side fix: the first trade window (335) opening marks the trade active regardless of
		// which side put items up. Previously tradeActive was set only when OUR offer container (90)
		// changed, so a receive-only trade (we add nothing) never armed the confirm capture.
		if (groupId == TRADE_MAIN_GROUP_ID)
		{
			tradeActive = true;
		}
		if (groupId == TRADE_CONFIRM_GROUP_ID && tradeActive)
		{
			tradeArmed = true;
			drawManager.requestNextFrameListener(image ->
			{
				if (screenshotsEnabled())	// re-check: toggle may flip before the frame lands
				{
					pendingTradeFrame.set(toBufferedImage(image));
				}
			});
		}
	}

	/**
	 * Trade window closed while ARMED (confirm screen reached) but before "Accepted trade." =
	 * declined/abandoned — discard, upload nothing. Gated on tradeArmed on purpose: the first trade
	 * screen (group 335) closes during the normal 335 -> 334 confirm transition, and reacting to that
	 * close while merely tradeActive would wipe the state before the confirm screen ever arms.
	 */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		handleTradeWidgetClosed(event.getGroupId());
		handleBankWidgetClosed(event.getGroupId(), bankCloseIsTeardown());
		if (event.getGroupId() == SHOP_GROUP_ID)
		{
			shopOpen = false;
			flushStorePendingOnShopClose();
			stopStoreClipCapture(true);	// end the visit; upload only if it had a buy/sell, else drop
			// The reset phase is only valid while the shop is open — drop it rather than show a stale
			// countdown on the next visit. Re-probing costs one junk item; a wrong number costs the item.
			storeResetAnchorMs = 0;
			removeResetTimer();
		}
	}

	/**
	 * Shop closed with a click still awaiting its inventory change (buy-then-close is a common sequence).
	 * The coin delta can no longer be attributed, but the transaction itself did happen — emit the degraded
	 * {item, qty} form rather than losing the event, which is what the pre-price behaviour reported anyway.
	 * Package-private seam so the close path is unit testable without a live client.
	 *
	 * Carried forward from 0.7.1 (the build currently on the Hub). Dropping the pending here, as this
	 * branch previously did, silently loses every buy-then-close transaction.
	 */
	void flushStorePendingOnShopClose()
	{
		StorePending p = storePending;
		storePending = null;
		if (p == null || !activityLogActive())
		{
			return;
		}
		// coinsAfter == coinsBefore -> zero delta -> fails the sign check -> {item, qty} only, never a guess.
		emitEvent(p.type, buildStoreTxFields(p.type, p.item, p.qty, p.coinsBefore, p.coinsBefore, p.ambiguous, p.qtyMerged));
	}

	void handleTradeWidgetClosed(int groupId)
	{
		if (tradeScreenshotsDisabled())
		{
			return;
		}
		if (tradeArmed && (groupId == TRADE_CONFIRM_GROUP_ID || groupId == TRADE_MAIN_GROUP_ID))
		{
			resetTradeState();
		}
	}

	/**
	 * TRADE messages drive the trade-completion path ("Accepted trade." commits the buffered frame;
	 * a decline discards it — tag-tolerant match). Nothing else is read from chat.
	 *
	 * The broad game-chat sweep that used to run here for every message was REMOVED in 0.7.3. It
	 * emitted one event per GAMEMESSAGE/SPAM line, which on the live release meant 121,565 events from
	 * 166 accounts in seven hours — enough to exhaust the server's shared hourly ingest budget and drop
	 * unrelated events for 11-16 minutes of every hour. Nothing consumed those rows, and every signal
	 * they carried (level-ups, deaths, drops, loot, GE completions) is already emitted as its own
	 * structured event by this plugin, so the sweep was pure duplication. It also captured other
	 * players' names via Jagex drop broadcasts, which this plugin's disclosure does not cover.
	 *
	 * Do not reintroduce it, under this or any other event name. If a future feature needs a specific
	 * chat line, match THAT line and emit a structured event for it.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Two SPECIFIC system lines are matched and emitted as their own structured events: a kill count
		// and a pet drop. This is the narrow form the comment above allows. It is NOT the broad sweep:
		// no raw chat line is emitted, no other player's name is read, and no other channel is examined.
		if (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM)
		{
			emitParsedMilestones(Text.removeTags(event.getMessage() == null ? "" : event.getMessage()).trim());
			return;
		}
		// Nearby public chat, firehose grant only. This is the ONE place another player's words are read,
		// it is off for every ordinary player, and it is batched into fh_batch like every other firehose row.
		if (maxCapture()
			&& (event.getType() == ChatMessageType.PUBLICCHAT || event.getType() == ChatMessageType.MODCHAT))
		{
			String text = Text.removeTags(event.getMessage() == null ? "" : event.getMessage()).trim();
			if (!text.isEmpty())
			{
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("text", text);
				String name = Text.removeTags(event.getName() == null ? "" : event.getName()).trim();
				if (!name.isEmpty())
				{
					d.put("rsn", name);
				}
				firehose("public_chat", d);
			}
			return;
		}
		if (event.getType() != ChatMessageType.TRADE)
		{
			return;
		}
		// A completed trade changes wealth: refresh the snapshot cache NOW (independent of the
		// screenshot opt-in) so the logout flush carries post-trade wealth even if no tick has run
		// since the trade. Client thread; containers are valid right after "Accepted trade.".
		if (TRADE_ACCEPTED_MESSAGE.equalsIgnoreCase(
			Text.removeTags(event.getMessage() == null ? "" : event.getMessage()).trim()))
		{
			refreshSnapshotCacheAfterTrade();
			emitTradeEvent();
		}
		handleTradeChat(event.getMessage());
	}

	// ---- Firehose (server-granted): batched high-frequency capture ----

	/**
	 * True when the backend has granted the firehose for this token AND uploading is allowed.
	 *
	 * NO CONFIG ITEM, deliberately. The operator default is that this plugin carries no user-facing
	 * settings, and the firehose captures other players' names and public chat, which the hub manifest
	 * warning does not describe. Making it server-granted keeps it off every ordinary player's client
	 * with no way for them to turn it on.
	 */
	boolean maxCapture()
	{
		return serverMaxCaptureEnabled && uploadAllowed();
	}

	/** Buffer one firehose row. No-op unless the grant is live. Batched, never emitted per event. */
	void firehose(String kind, Map<String, Object> data)
	{
		if (!maxCapture())
		{
			return;
		}
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("k", kind);
		row.put("tick", client == null ? 0 : client.getTickCount());
		if (data != null)
		{
			row.putAll(data);
		}
		synchronized (firehoseBuffer)
		{
			if (firehoseBuffer.size() >= FIREHOSE_MAX)
			{
				return;		// hard cap between flushes: drop the overflow rather than grow without bound
			}
			firehoseBuffer.add(row);
		}
	}

	/** Emit the buffered firehose rows as ONE fh_batch event. */
	void flushFirehose()
	{
		List<Map<String, Object>> batch;
		synchronized (firehoseBuffer)
		{
			if (firehoseBuffer.isEmpty())
			{
				return;
			}
			batch = new ArrayList<>(firehoseBuffer);
			firehoseBuffer.clear();
		}
		if (!activityLogActive())
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("events", batch);
		emitEvent("fh_batch", fields);
	}

	/** Drop buffered firehose rows on an account switch, so stale rows never mis-stamp the next account. */
	private void clearFirehose()
	{
		synchronized (firehoseBuffer)
		{
			firehoseBuffer.clear();
		}
		firehoseFlushTicks = 0;
	}

	/** Per-tick position trail (firehose). */
	private void capturePosition()
	{
		if (client == null)
		{
			return;
		}
		Player self = client.getLocalPlayer();
		WorldPoint wp = self == null ? null : self.getWorldLocation();
		if (wp == null)
		{
			return;
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("x", wp.getX());
		d.put("y", wp.getY());
		d.put("p", wp.getPlane());
		firehose("pos", d);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!maxCapture() || client == null)
		{
			return;
		}
		Actor self = client.getLocalPlayer();
		Actor actor = event.getActor();
		Actor target = self == null ? null : self.getInteracting();
		if (actor != self && actor != target)
		{
			return;		// only hits on us and on our current target
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("on", actor == self ? "self" : "target");
		d.put("amount", event.getHitsplat() == null ? 0 : event.getHitsplat().getAmount());
		firehose("hitsplat", d);
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (!maxCapture() || client == null || event.getActor() != client.getLocalPlayer())
		{
			return;
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("id", event.getActor().getAnimation());
		firehose("anim", d);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!maxCapture() || client == null || event.getSource() != client.getLocalPlayer())
		{
			return;
		}
		Actor t = event.getTarget();
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("target", t == null || t.getName() == null ? null : Text.removeTags(t.getName()));
		firehose("interact", d);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!maxCapture())
		{
			return;
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("varbit", event.getVarbitId());
		d.put("varp", event.getIndex());
		d.put("val", event.getValue());
		firehose("varbit", d);
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		nearbyPlayerEvent(event.getPlayer(), "spawn");
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		nearbyPlayerEvent(event.getPlayer(), "despawn");
	}

	private void nearbyPlayerEvent(Player p, String kind)
	{
		if (!maxCapture() || p == null || (client != null && p == client.getLocalPlayer()))
		{
			return;
		}
		if (p.getName() == null || p.getName().isEmpty())
		{
			return;
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("rsn", Text.removeTags(p.getName()));
		d.put("event", kind);
		firehose("nearby", d);
	}

	private static final java.util.regex.Pattern KILL_COUNT_RE = java.util.regex.Pattern.compile(
		"(?:kill|completion|chest|success|harvest) count is:? ?([\\d,]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Emit the two structured milestones parsed from a system chat line: kill_count and pet.
	 *
	 * Own-account only. A system line names no other player, so nothing here captures a bystander.
	 * The raw line rides along as `text` because the count alone does not say WHAT was killed.
	 */
	void emitParsedMilestones(String text)
	{
		if (text == null || text.isEmpty() || !activityLogActive())
		{
			return;
		}
		java.util.regex.Matcher m = KILL_COUNT_RE.matcher(text);
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
			emitEvent("kill_count", f);
		}
		String low = text.toLowerCase(java.util.Locale.ROOT);
		if (low.contains("funny feeling like you") && low.contains("followed"))
		{
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("text", text);
			emitEvent("pet", f);
		}
	}

	/**
	 * On "Accepted trade.", emit a structured "trade" event from what was captured at the confirm screen:
	 * the items WE gave (own offer), the counterparty name (forwarded for ALL users, disclosed), and the items
	 * WE receive (WAVE 1b — structured [{id,qty}] from the YOU_WILL_RECEIVE widget, or a raw received_text
	 * summary fallback when that widget carries text rather than item sprites).
	 */
	void emitTradeEvent()
	{
		List<Map<String, Object>> given = pendingTradeGiven;
		String counterparty = pendingCounterparty;
		List<Map<String, Object>> received = pendingTradeReceived;
		String receivedText = pendingReceivedText;
		pendingTradeGiven = null;
		pendingCounterparty = null;
		pendingTradeReceived = null;
		pendingReceivedText = null;
		if (given == null)
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("given", given);
		// Counterparty RSN forwarded for ALL users (staff-backend gate removed — product decision 2026-07-14). It is the
		// one other-player field we send, on purpose, disclosed in the config item + Hub warning text.
		if (counterparty != null)
		{
			fields.put("counterparty", counterparty);
		}
		// WAVE 1b: the received side. Always carry received[] (may be empty if the widget was text-only or not
		// yet populated); attach received_text only when the structured read came back empty and text was found.
		fields.put("received", received == null ? new ArrayList<>() : received);
		if (receivedText != null && !receivedText.isEmpty())
		{
			fields.put("received_text", receivedText);
		}
		emitEvent("trade", fields);
	}

	/** General-store buy/sell: a Buy/Sell menu click while the shop (SHOPMAIN 300) is open. Own-account. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (maxCapture() && event != null)
		{
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("opt", event.getMenuOption());
			d.put("target", Text.removeTags(event.getMenuTarget() == null ? "" : event.getMenuTarget()));
			firehose("menu", d);
		}
		// Delivery-proof tx flag — hoisted ABOVE the activity-log gate (grill F5): a clip's buy/sell must
		// not be dropped just because activity logging is off. Only relevant while a clip is capturing.
		if (clipCapturing && shopOpen && isStoreBuyOrSell(event))
		{
			storeTxThisVisit = true;
			markStoreClipMoment();
		}
		// Off-book value events (drop / pickup / alch): own gate (not shop-scoped) — arm an inventory-delta
		// pending resolved on the next INVENTORY change. BEFORE the store gate so the shop-open early-return
		// below never swallows it.
		maybeArmInvDeltaPending(event);
		if (!shopOpen || !activityLogActive())
		{
			return;
		}
		String type = storeTxType(event);
		if (type == null)
		{
			return;
		}
		if ("store_buy".equals(type))
		{
			// OUR OWN BUY-BACK. The seller's own procedure has an explicit emergency step — if the
			// buyer cannot take the item in time, buy it back — so our own buy of an item we just
			// sold is a NORMAL part of the method, not a customer taking it. Without this the fall
			// that our own purchase causes is reported as a counterparty: measured on live events
			// 3914798/3914799, where a buy-back and a store_taken landed in the SAME SECOND and the
			// plugin still named a candidate. Record the item so handleShopStockChanged can suppress
			// the inference.
			lastSelfBuyItem = event.getItemId();
			lastSelfBuyAtMs = System.currentTimeMillis();
		}
		int item = event.getItemId();
		if (item <= 0)
		{
			return;	// no resolvable item id — same guard the off-book path uses; skip rather than emit -1
		}
		String opt = event.getMenuOption();	// non-null here: storeTxType only matches a "Buy"/"Sell" prefix
		// WAVE 6 (store price): don't emit here — arm a pending and let the next INVENTORY change resolve the
		// exact transacted gp from the coin-count delta (store-price-feasibility.md option B). Snapshot coins
		// BEFORE the transaction. Last-click-wins: a second click on the same tick marks the pending ambiguous
		// (its delta would merge two txns), so the resolver omits gp_total rather than misattribute it.
		ItemContainer invNow = client == null ? null : client.getItemContainer(InventoryID.INVENTORY);
		long coinsBefore = countItem(invNow, COINS_ID);
		long itemBefore = invNow == null ? UNKNOWN_ITEM_COUNT : countItem(invNow, item);
		int tick = client == null ? 0 : client.getTickCount();
		int qty = parseTrailingQty(opt);
		// Snapshot who is standing here AT THE CLICK. The visit-wide accumulator answers a weaker
		// question ("who was around at some point"); a dispute is about the moment value moved.
		nearbyAtTx = nearbyPlayersSnapshot(NEARBY_FIELD_CAP);
		if ("store_sell".equals(type))
		{
			// Default stock is judged ONCE, at our FIRST sell of this item this visit: after that the shop
			// stock includes units we put in ourselves, which must never make our delivery look native.
			if (soldThisVisit.add(item) && shopStock.getOrDefault(item, 0) > 0)
			{
				defaultStockSoldThisVisit.add(item);	// the shop already stocked it before we sold: default stock
			}
			lastSellTickThisVisit.put(item, tick);
			if (storeProbeItem == 0)
			{
				// FIRST sell of the visit = the junk probe. Only its disappearance moves the reset
				// clock; everything after it is merchandise whose disappearance means a customer.
				storeProbeItem = item;
			}
		}
		StorePending prev = storePending;
		storePending = mergeStorePending(prev, type, item, qty, coinsBefore, itemBefore, tick);
	}

	/**
	 * Fold a new store click into any pending one still inside the resolution window.
	 *
	 * <p>Production evidence (2026-08-15 audit): on the newest clients, {@code gp_total} was missing from
	 * <b>37.1% of multi-quantity store buys</b> (140 of 377 on 2026-08-15) against 8.0% at qty=1. Cause:
	 * a player spam-clicking "Buy 10" lands two clicks in quick succession, which the previous code
	 * flagged {@code ambiguous} and then dropped the gold for — deliberately, "rather than misattribute
	 * it". General-store selling is how gold is delivered, so that was the delivery path going dark.
	 *
	 * <p>The flag was over-cautious for the common case. If {@code prev} is still armed then it was never
	 * resolved, and a pending is only consumed by an inventory change — so no inventory change has
	 * happened yet, so {@code prev.coinsBefore} still predates BOTH transactions. When the two clicks are
	 * the same type on the same item, the eventual coin delta therefore covers exactly their combined
	 * quantity: keep the ORIGINAL {@code coinsBefore} and SUM the quantities, and {@code gp_total} comes
	 * out exact rather than absent.
	 *
	 * <p><b>The window is UNRESOLVED-ness, not same-tick.</b> Field-observed 2026-08-17: two "Buy 10"
	 * clicks a single tick apart produced {@code qty:10, gp_total:11325, unit_price_gp:1132} — one
	 * click's quantity against both clicks' gold, with no {@code qty_merged} label, so a fabricated unit
	 * price (true ~755) was indistinguishable downstream from a correct single-click row. That is the
	 * exact half-price failure the conservative rule exists to prevent, arriving through the door the
	 * {@code prev.tick == tick} equality left open. What makes {@code prev.coinsBefore} usable is that
	 * nothing has consumed the pending, so the merge now spans the same {@link #STORE_PENDING_MAX_TICKS}
	 * window the resolver already uses to decide a pending is still live. Beyond it the resolver would
	 * discard the pending anyway, so the two agree by construction.
	 *
	 * <p>Genuine ambiguity is still respected. Clicks on a DIFFERENT item, or in a different direction,
	 * cannot be split out of one delta, so those stay {@code ambiguous} and still degrade to
	 * {item, qty} — the audit's rule that we never emit a guessed number is unchanged.
	 */
	static StorePending mergeStorePending(StorePending prev, String type, int item, int qty, long coinsBefore, int tick)
	{
		return mergeStorePending(prev, type, item, qty, coinsBefore, UNKNOWN_ITEM_COUNT, tick);
	}

	static StorePending mergeStorePending(StorePending prev, String type, int item, int qty, long coinsBefore,
		long itemBefore, int tick)
	{
		if (prev != null && tick - prev.tick >= 0 && tick - prev.tick <= STORE_PENDING_MAX_TICKS)
		{
			if (prev.item == item && prev.type.equals(type) && !prev.ambiguous)
			{
				// Same item, same direction, prev never resolved → one delta covers both. Both baselines
				// stay the ORIGINAL pre-transaction readings, for the same reason: nothing has consumed
				// this pending, so no inventory change has been applied to either count yet.
				return new StorePending(type, item, prev.qty + qty, prev.coinsBefore, prev.itemBefore,
					tick, false, true);
			}
			// Different item or direction inside one window — the delta merges unrelated transactions.
			return new StorePending(type, item, qty, coinsBefore, itemBefore, tick, true, false);
		}
		return new StorePending(type, item, qty, coinsBefore, itemBefore, tick, false, false);
	}

	/**
	 * Resolve an armed store pending against the post-transaction coin count. Consumes the pending (one
	 * inventory change resolves at most one click) and emits the store event, with the exact-gp price when it
	 * can be cleanly attributed. Stale pendings (a failed click that never moved the inventory, then some
	 * unrelated later change) are dropped rather than paired. Package-private seam so the async path is unit
	 * testable without a live client.
	 */
	void resolveStorePendingOnInventoryChange(long coinsAfter, int currentTick)
	{
		resolveStorePendingOnInventoryChange(coinsAfter, UNKNOWN_ITEM_COUNT, currentTick);
	}

	/**
	 * @param itemAfter post-transaction count of the transacted item, or {@link #UNKNOWN_ITEM_COUNT} when it
	 *                  could not be read. Its distance from the pending's baseline is the quantity the
	 *                  server ACTUALLY moved, which is what makes a unit price safe to derive — see
	 *                  {@link #buildStoreTxFields}.
	 */
	void resolveStorePendingOnInventoryChange(long coinsAfter, long itemAfter, int currentTick)
	{
		StorePending p = storePending;
		if (p == null)
		{
			return;
		}
		if (currentTick - p.tick > STORE_PENDING_MAX_TICKS)
		{
			storePending = null;	// stale: a failed click's pending paired with an unrelated later change
			return;
		}
		storePending = null;		// consume — last-click-wins already collapsed repeats to this one
		if (!activityLogActive())
		{
			return;
		}
		long executed = executedQty(p, itemAfter);
		Map<String, Object> fields = buildStoreTxFields(p.type, p.item, p.qty, p.coinsBefore, coinsAfter,
			p.ambiguous, p.qtyMerged, executed);
		addStoreContextFields(fields);	// nearby[] + world + loc — the candidate receivers of a store transfer
		emitEvent(p.type, fields);
	}

	/**
	 * The quantity the server actually moved, from the item-count delta, or {@link #UNKNOWN_ITEM_COUNT} when
	 * either baseline is missing. Sign is normalised: a buy adds items, a sell removes them, so the
	 * magnitude is what matters and a delta pointing the wrong way is not this transaction's.
	 */
	static long executedQty(StorePending p, long itemAfter)
	{
		if (p.itemBefore == UNKNOWN_ITEM_COUNT || itemAfter == UNKNOWN_ITEM_COUNT)
		{
			return UNKNOWN_ITEM_COUNT;
		}
		long delta = "store_buy".equals(p.type) ? itemAfter - p.itemBefore : p.itemBefore - itemAfter;
		return delta < 0 ? UNKNOWN_ITEM_COUNT : delta;
	}

	/**
	 * Attach store-transfer context to a store event: `nearby` (players seen over the shop visit — the
	 * candidate receivers of the general-store method: staff sells cheap, an untracked player buys it out),
	 * `world`, `loc`. Instance method (needs the live client). nearby[] comes from the visit accumulator; a
	 * live snapshot backfills if the accumulator is empty (event fired before a tick ran).
	 */
	void addStoreContextFields(Map<String, Object> fields)
	{
		if (client == null)
		{
			return;
		}
		List<Map<String, Object>> nearby = nearbyFromVisit();
		if (nearby.isEmpty())
		{
			nearby = nearbyPlayersSnapshot(NEARBY_FIELD_CAP);
		}
		if (!nearby.isEmpty())
		{
			fields.put("nearby", nearby);		// everyone seen across the whole shop visit
		}
		// AND, separately, who was standing there at the CLICK. The two answer different questions and
		// a dispute is about the second: "nobody was around at some point in the visit" is far weaker
		// than "nobody was within N tiles when the item moved". Captured at the click rather than here
		// because this runs on the inventory change, which is one or more ticks later.
		List<Map<String, Object>> atTx = nearbyAtTx;
		if (atTx != null && !atTx.isEmpty())
		{
			fields.put("nearby_at_tx", atTx);
		}
		nearbyAtTx = null;
		fields.put("world", client.getWorld());
		Player self = client.getLocalPlayer();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me != null)
		{
			fields.put("loc", java.util.Arrays.asList(me.getX(), me.getY(), me.getPlane()));
		}
	}

	/** Snapshot up to {@code cap} nearest OTHER players right now: {rsn, dx, dy, dist, cb}, nearest first. */
	List<Map<String, Object>> nearbyPlayersSnapshot(int cap)
	{
		return nearbyPlayersSnapshot(cap, false);
	}

	/**
	 * The name of the NPC our own character is interacting with, or null. Read when the shop opens: the
	 * Trade click faces the shopkeeper, so this names the shop's owner for the buyer check.
	 */
	String currentShopkeeperName()
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return null;
		}
		Actor t = client.getLocalPlayer().getInteracting();
		return t instanceof net.runelite.api.NPC && t.getName() != null ? Text.removeTags(t.getName()) : null;
	}

	/** Index of the NPC our character faces when the shop opens, or -1. */
	int currentShopkeeperIndex()
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return -1;
		}
		Actor t = client.getLocalPlayer().getInteracting();
		return t instanceof net.runelite.api.NPC ? ((net.runelite.api.NPC) t).getIndex() : -1;
	}

	/**
	 * Same snapshot, and with {@code activity} each row also says what the player was doing: {@code anim}
	 * (animation id, -1 idle) and {@code interacting_npc} when they face an NPC, plus {@code with_shopkeeper}
	 * when that NPC is the shopkeeper we are trading with. Another player's shop screen is never visible,
	 * so this is the closest mechanical sign that they are trading with the same shop. Player targets are
	 * not named.
	 */
	List<Map<String, Object>> nearbyPlayersSnapshot(int cap, boolean activity)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		if (client == null)
		{
			return out;
		}
		Player self = client.getLocalPlayer();
		List<Player> players = client.getPlayers();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me == null || players == null)
		{
			return out;
		}
		List<Player> others = new ArrayList<>();
		for (Player p : players)
		{
			if (p == null || p == self || p.getName() == null || p.getName().isEmpty() || p.getWorldLocation() == null)
			{
				continue;
			}
			others.add(p);
		}
		others.sort((a, b) -> Integer.compare(a.getWorldLocation().distanceTo(me), b.getWorldLocation().distanceTo(me)));
		for (Player p : others)
		{
			if (out.size() >= cap)
			{
				break;
			}
			WorldPoint loc = p.getWorldLocation();
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("rsn", Text.removeTags(p.getName()));
			m.put("dx", loc.getX() - me.getX());
			m.put("dy", loc.getY() - me.getY());
			m.put("dist", loc.distanceTo(me));
			m.put("cb", p.getCombatLevel());
			if (activity)
			{
				m.put("anim", p.getAnimation());
				Actor t = p.getInteracting();
				if (t instanceof net.runelite.api.NPC && t.getName() != null)
				{
					String npc = Text.removeTags(t.getName());
					m.put("interacting_npc", npc);
					// The SAME NPC, by index. A second trader of the same shop (a shop assistant) reads false here
					// but still shows its name in interacting_npc; a same-named NPC elsewhere never reads true.
					m.put("with_shopkeeper", shopkeeperIndex >= 0
						&& ((net.runelite.api.NPC) t).getIndex() == shopkeeperIndex);
				}
			}
			out.add(m);
		}
		return out;
	}

	/**
	 * Milliseconds until the next store reset, given an observed anchor and the current time.
	 *
	 * Returns 0 when the phase is unknown (no reset observed yet this visit) — the caller shows nothing
	 * rather than a guess. Pure and static so the phase arithmetic is testable without a game client.
	 */
	static long msUntilNextReset(long anchorMs, long nowMs)
	{
		if (anchorMs <= 0 || nowMs < anchorMs)
		{
			return 0;			// no anchor, or a clock that went backwards — refuse to guess
		}
		long since = nowMs - anchorMs;
		long remainder = since % STORE_RESET_PERIOD_MS;
		// Land exactly ON a tick => a full period remains, never 0 (0 is reserved for "unknown").
		return STORE_RESET_PERIOD_MS - remainder;
	}

	/** True when the phase is known and a countdown may be shown. */
	static boolean resetPhaseKnown(long anchorMs)
	{
		return anchorMs > 0;
	}

	/** The live countdown infobox, or null when none is shown. Client thread only. */
	private Timer resetTimer;

	/**
	 * Show (or re-point) the countdown for the next reset.
	 *
	 * Called once per observed reset, so the box is replaced rather than accumulated — a leaked infobox
	 * is the obvious failure of this feature and the tests assert against it explicitly.
	 */
	private void showResetTimer()
	{
		if (infoBoxManager == null || !storeToolsEnabled() || !resetPhaseKnown(storeResetAnchorMs))
		{
			return;
		}
		removeResetTimer();
		long remainMs = msUntilNextReset(storeResetAnchorMs, System.currentTimeMillis());
		if (remainMs <= 0)
		{
			return;
		}
		java.awt.image.BufferedImage icon = itemManager != null ? itemManager.getImage(COINS_ITEM_ID) : null;
		Timer t = new Timer(remainMs, java.time.temporal.ChronoUnit.MILLIS, icon, this);
		t.setTooltip("General store resets");
		resetTimer = t;
		infoBoxManager.addInfoBox(t);
	}

	/** Counts tick-driven re-arms. Test seam: the re-arm decision is otherwise invisible. */
	private int reArmAttempts;

	int reArmAttemptsForTest()
	{
		return reArmAttempts;
	}

	boolean resetPhaseKnownForTest()
	{
		return resetPhaseKnown(storeResetAnchorMs);
	}

	/** Milliseconds until the next reset, or 0 when the phase has not been observed yet. */
	long msUntilNextResetForDisplay()
	{
		return msUntilNextReset(storeResetAnchorMs, System.currentTimeMillis());
	}

	/**
	 * DIAGNOSTIC ONLY — writes what the shop container actually does, to a LOCAL file.
	 *
	 * The reset model is not yet confirmed against a real client: a full store session produced no
	 * anchor on 2026-09-03 while the operator saw several resets happen. Rather than guess at a
	 * second model, record the ground truth. Nothing here is uploaded and nothing about another
	 * player is written — only item ids and quantities from the shop's own container.
	 *
	 * Enabled only by the system property, so an ordinary build writes nothing at all.
	 */
	private void traceLine(String text)
	{
		String path = System.getProperty("osrsbis.shoptrace");
		if (path == null || path.isEmpty())
		{
			return;
		}
		try
		{
			java.nio.file.Files.write(java.nio.file.Paths.get(path),
				(System.currentTimeMillis() + " " + text + "\n")
					.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
		}
		catch (Exception ignored)
		{
			// a diagnostic must never affect the plugin
		}
	}

	private void traceShopStock(Map<Integer, Integer> now)
	{
		String path = System.getProperty("osrsbis.shoptrace");
		if (path == null || path.isEmpty())
		{
			return;
		}
		try
		{
			StringBuilder sb = new StringBuilder();
			sb.append(System.currentTimeMillis()).append(' ');
			sb.append("gate=").append(storeToolsEnabled()).append(' ');
			sb.append("probe=").append(storeProbeItem).append(' ');
			sb.append("anchor=").append(storeResetAnchorMs).append(' ');
			sb.append("delta=");
			boolean any = false;
			java.util.Set<Integer> keys = new java.util.TreeSet<>();
			keys.addAll(shopStock.keySet());
			keys.addAll(now.keySet());
			for (Integer k : keys)
			{
				int a = shopStock.getOrDefault(k, 0);
				int b = now.getOrDefault(k, 0);
				if (a != b)
				{
					sb.append(k).append(':').append(a).append("->").append(b).append(' ');
					any = true;
				}
			}
			if (!any)
			{
				sb.append("(none)");
			}
			sb.append('\n');
			java.nio.file.Files.write(java.nio.file.Paths.get(path),
				sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
		}
		catch (Exception ignored)
		{
			// a diagnostic must never affect the plugin
		}
	}

	/**
	 * Milliseconds from {@code nowMs} to the NEAREST shop cycle tick of an observed anchor, or null when no
	 * phase is known (or the clock went backwards). Pure and static so it is testable without a client.
	 */
	static Long msFromResetCycle(long anchorMs, long nowMs)
	{
		if (anchorMs <= 0 || nowMs < anchorMs)
		{
			return null;
		}
		long r = (nowMs - anchorMs) % STORE_RESET_PERIOD_MS;
		return Math.min(r, STORE_RESET_PERIOD_MS - r);
	}

	/** How far a gap may sit from one full period and still count as the cycle. */
	static final long PERIOD_TOLERANCE_MS = 3_000L;

	/**
	 * Do two consecutive container changes sit exactly one store period apart?
	 *
	 * This is the whole anchor rule. It is deliberately conservative: a shop where nothing else
	 * happens produces a clean 60s chain, while a busy shop full of customers produces changes at
	 * arbitrary times that mostly will NOT be 60s apart, so the clock simply stays unknown rather
	 * than locking onto a coincidence. Showing nothing beats showing a wrong countdown.
	 *
	 * Pure and static so the rule is testable without a client.
	 */
	static boolean isPeriodAgreement(long previousChangeMs, long nowMs)
	{
		if (previousChangeMs <= 0 || nowMs <= previousChangeMs)
		{
			return false;		// nothing to compare against, or a clock that went backwards
		}
		long gap = nowMs - previousChangeMs;
		return Math.abs(gap - STORE_RESET_PERIOD_MS) <= PERIOD_TOLERANCE_MS;
	}

	/** Remove the countdown. Idempotent — every teardown path calls it. */
	private void removeResetTimer()
	{
		Timer t = resetTimer;
		resetTimer = null;
		if (t != null && infoBoxManager != null)
		{
			infoBoxManager.removeInfoBox(t);
		}
	}

	/**
	 * Watch the SHOP's stock for someone taking an item we just sold into it — the general-store
	 * counterparty, which no other signal can see.
	 *
	 * The method works because the shop is a dead drop: staff sells low, an untracked player buys it
	 * out. The staff account's own inventory shows only the first half. The shop container shows the
	 * second: stock of a sold item DROPS when a player takes it. So a drop, paired with who is standing
	 * there at that moment, names the likely receiver.
	 *
	 * ⚠ This is an INFERENCE and is emitted as one (`taken_by_candidates`, never `counterparty`). Stock
	 * also falls when the shop's own restock timer runs, and any player in the shop could be the taker,
	 * so the candidate list is the players present — evidence to weigh, not an accusation. Only items
	 * this visit actually SOLD are watched, so a staff buy that lowers stock is never mistaken for it.
	 */
	void handleShopStockChanged(ItemContainer shop)
	{
		if (shop == null)
		{
			return;
		}
		Map<Integer, Integer> now = new java.util.HashMap<>();
		Item[] items = shop.getItems();
		if (items != null)
		{
			for (Item it : items)
			{
				if (it != null && it.getId() > 0)
				{
					now.merge(it.getId(), it.getQuantity(), Integer::sum);
				}
			}
		}
		traceShopStock(now);
		long nowMs = System.currentTimeMillis();
		// The shop cycle phase as it was BEFORE this change. Player-added stock decays on the cycle tick, so a
		// store_taken that lands on it may be the shop, not a buyer. Read before the anchors below move it.
		long cycleAnchorBefore = storeResetAnchorMs;

		// ANCHOR 1 — THE ITEM WE SOLD VANISHING. This is the event the user actually watches, and it
		// is the strongest signal available: a junk item the shop does NOT natively stock is sold in,
		// and it is removed on the next tick.
		//
		// The discriminator is ZERO, measured live 2026-09-03. Player-added stock decays 1 per tick
		// until it reaches 0 and leaves the shop entirely (item 1191: 5->4->3->…->0, each step exactly
		// 60.0s apart; item 1159: 1->0). Shop DEFAULT stock behaves completely differently — selling a
		// Pot into Varrock GS raised it 5->6 and the shop normalised it back to 5 in 1.2-5.4 SECONDS,
		// never touching the cycle. So a sold item falling to a NON-ZERO number is the shop
		// normalising its own goods and means nothing; falling to ZERO is the tick.
		// ONLY THE PROBE, never the merchandise. Reported from the field by Snaauz on 2026-09-19:
		// delivering more than one item made the timer reset as soon as the first item was bought.
		// A delivery visit sells the junk probe and then the goods, so soldThisVisit holds both. A
		// customer buying the GOODS out takes their stock to zero at an arbitrary moment in the cycle,
		// which is a purchase and not a tick. Anchoring there threw the phase away and every number
		// after it was wrong. storeProbeItem already records the first item sold, which is the junk
		// whose decay IS the cycle, so read that one item and no other.
		if (storeProbeItem != 0)
		{
			int had = shopStock.getOrDefault(storeProbeItem, 0);
			int has = now.getOrDefault(storeProbeItem, 0);
			if (had > 0 && has == 0)
			{
				storeResetAnchorMs = nowMs;
				traceLine("ANCHOR probe-vanished item=" + storeProbeItem + " " + had + "->0");
				showResetTimer();
			}
		}

		// ANCHOR 2 — PERIODICITY, for a visit where nothing of ours has vanished yet. Two consecutive
		// container changes exactly one period apart can only be the cycle. Confirmed live: two
		// restock ticks 60.015s apart anchored, while two buys 15.0s apart were correctly refused.
		//
		// Two earlier models were refuted by measurement, both by watching a real shop. "the junk item
		// we sold vanishing IS the reset" gave 72s cycles drifting 36s/24s/12s, because a shop's
		// DEFAULT stock normalises on its own schedule. "a tick is a rise and a fall together" refused
		// all five ticks of a five-minute observation, because a full shop has nothing to restock. What
		// survived every observation is the PERIOD: ticks landed at 60.0s with no deviation, twice.
		if (!now.equals(shopStock) && !shopStock.isEmpty())
		{
			if (storeResetAnchorMs == 0 && isPeriodAgreement(lastStockChangeMs, nowMs))
			{
				storeResetAnchorMs = nowMs;
				traceLine("ANCHOR period gap=" + (nowMs - lastStockChangeMs));
				showResetTimer();
			}
			lastStockChangeMs = nowMs;
		}
		for (Integer item : soldThisVisit)
		{
			int before = shopStock.getOrDefault(item, -1);
			int after = now.getOrDefault(item, 0);
			if (before < 0 || after >= before)
			{
				continue;		// no baseline yet, or stock did not fall
			}
			if (item != null && item == lastSelfBuyItem
				&& System.currentTimeMillis() - lastSelfBuyAtMs <= SELF_BUY_SUPPRESS_MS)
			{
				continue;	// WE bought it back — not a customer. See onMenuOptionClicked.
			}
			if (after > 0 && before - after == 1 && defaultStockSoldThisVisit.contains(item))
			{
				continue;	// F-A1: the shop normalising its own default stock, not a customer
			}
			if (after == 0)
			{
				defaultStockSoldThisVisit.remove(item);	// native stock is gone: anything sold in later is ours
			}
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("item", item);
			f.put("qty", before - after);
			f.put("stock_before", before);
			f.put("stock_after", after);
			// Every other player in the shop area, each with what they were doing, so the reader can keep
			// only the mechanically plausible buyers (facing the same shopkeeper) and name one if one remains.
			List<Map<String, Object>> present = nearbyPlayersSnapshot(NEARBY_FIELD_CAP, true);
			if (!present.isEmpty())
			{
				f.put("taken_by_candidates", present);
			}
			if (shopkeeperName != null)
			{
				f.put("shopkeeper", shopkeeperName);
			}
			// Distance in ms to the nearest shop cycle tick, when the phase was known before this change. Near 0
			// = the fall landed on the cycle, where player-added stock decays by itself (measured live
			// 2026-09-27: a sold log fell to 0 exactly 60.0 s after the previous one with no buyer).
			// Absent = phase unknown, so the reader cannot tell a decay from a buy by timing.
			Long msFromCycle = msFromResetCycle(cycleAnchorBefore, nowMs);
			if (msFromCycle != null)
			{
				f.put("ms_from_reset_cycle", msFromCycle);
			}
			if (client != null)
			{
				f.put("world", client.getWorld());
				Integer soldAt = lastSellTickThisVisit.get(item);
				if (soldAt != null && client.getTickCount() >= soldAt)
				{
					f.put("ticks_since_sell", client.getTickCount() - soldAt);
				}
			}
			emitEvent("store_taken", f);
			markStoreClipMoment();
		}
		shopStock.clear();
		shopStock.putAll(now);
	}

	/** Merge the current nearby players into the shop-visit accumulator (called each tick while shop open). */
	void accumulateShopNearby()
	{
		if (client == null)
		{
			return;
		}
		Player self = client.getLocalPlayer();
		List<Player> players = client.getPlayers();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me == null || players == null)
		{
			return;
		}
		int tick = client.getTickCount();
		for (Player p : players)
		{
			if (p == null || p == self || p.getName() == null || p.getName().isEmpty() || p.getWorldLocation() == null)
			{
				continue;
			}
			int dist = p.getWorldLocation().distanceTo(me);
			String rsn = Text.removeTags(p.getName());
			int[] rec = shopVisitNearby.get(rsn);
			if (rec == null)
			{
				if (shopVisitNearby.size() >= MAX_NEARBY_TRACKED)
				{
					continue;	// map full — don't grow unbounded on a crowded world
				}
				shopVisitNearby.put(rsn, new int[]{dist, tick, tick, p.getCombatLevel()});
			}
			else
			{
				if (dist < rec[0])
				{
					rec[0] = dist;	// closest approach this visit
				}
				rec[2] = tick;		// last seen
			}
		}
	}

	/** Emit nearby[] from the visit accumulator, nearest-first: {rsn, dist, first_tick, last_tick, cb}. */
	private List<Map<String, Object>> nearbyFromVisit()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		List<Map.Entry<String, int[]>> entries = new ArrayList<>(shopVisitNearby.entrySet());
		entries.sort((a, b) -> Integer.compare(a.getValue()[0], b.getValue()[0]));
		for (Map.Entry<String, int[]> e : entries)
		{
			if (out.size() >= NEARBY_FIELD_CAP)
			{
				break;
			}
			int[] r = e.getValue();
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("rsn", e.getKey());
			m.put("dist", r[0]);
			m.put("first_tick", r[1]);
			m.put("last_tick", r[2]);
			m.put("cb", r[3]);
			out.add(m);
		}
		return out;
	}

	/**
	 * Build the store event fields. {item, qty} always; gp_total = the exact coins that moved, added ONLY
	 * when the delta is cleanly attributable — right sign for the direction (buy → coins fell, sell → rose)
	 * and not a same-tick batch. unit_price_gp is a LABELLED average (gp_total/qty), only meaningful for
	 * qty>1; per-item price scales mid-batch as stock moves, so it is never a flat rate. When the delta can't
	 * be trusted, degrade to {item, qty} only (option D) rather than emit a guess. Pure/static: no client.
	 */
	static Map<String, Object> buildStoreTxFields(String type, int item, int qty, long coinsBefore, long coinsAfter, boolean ambiguous)
	{
		return buildStoreTxFields(type, item, qty, coinsBefore, coinsAfter, ambiguous, false);
	}

	/**
	 * @param qtyMerged qty is the SUM of same-tick clicks (intent), not a confirmed executed quantity —
	 *                  gp_total stays exact (it is the measured coin delta) but unit_price_gp is OMITTED,
	 *                  because dividing an exact total by an unconfirmed denominator yields a wrong unit
	 *                  price (half price when one of two merged clicks failed). An absent field is
	 *                  recoverable; a plausible wrong number is not.
	 */
	static Map<String, Object> buildStoreTxFields(String type, int item, int qty, long coinsBefore, long coinsAfter,
		boolean ambiguous, boolean qtyMerged)
	{
		return buildStoreTxFields(type, item, qty, coinsBefore, coinsAfter, ambiguous, qtyMerged,
			UNKNOWN_ITEM_COUNT);
	}

	/**
	 * @param executedQty the quantity the server ACTUALLY moved, measured from the item-count delta, or
	 *                    {@link #UNKNOWN_ITEM_COUNT} when it could not be measured.
	 *
	 *                    <p>Field-observed 2026-08-17: a SINGLE "Buy 50" click on a shop holding 5 emitted
	 *                    {@code qty:50, gp_total:3925, unit_price_gp:78} against a true unit of ~785, and a
	 *                    single "Sell 50" of 27 items emitted {@code unit_price_gp:43} against ~80. A
	 *                    single click's quantity is click INTENT too — the shop's stock, the inventory's
	 *                    free space and the player's coins all cap what actually executes — so "one click
	 *                    means a confirmed denominator" was simply false, and both rows were 10x wrong
	 *                    while looking exact.
	 *
	 *                    <p>So a unit price is derived only when the measured executed quantity CONFIRMS
	 *                    the click: it is then a real per-item rate over a real denominator. When the two
	 *                    disagree the row carries {@code qty_partial: true} and the executed quantity in
	 *                    {@code qty_executed}, and no unit price — the same "label it, never guess it"
	 *                    rule the merged case already follows. When nothing could be measured, there is no
	 *                    evidence either way, so no unit price either.
	 */
	static Map<String, Object> buildStoreTxFields(String type, int item, int qty, long coinsBefore, long coinsAfter,
		boolean ambiguous, boolean qtyMerged, long executedQty)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("item", item);
		fields.put("qty", qty);
		long delta = coinsAfter - coinsBefore;
		boolean signOk = "store_buy".equals(type) ? delta < 0 : delta > 0;
		if (!ambiguous && signOk)
		{
			long gp = Math.abs(delta);
			fields.put("gp_total", gp);
			if (qtyMerged)
			{
				// Label the residual uncertainty rather than let a bare exact-looking number imply a
				// pairing we cannot guarantee. gp_total is always the coins that really moved in this
				// resolution window. qty is the SUM of same-tick click intents, and two things can make
				// it overstate what those coins bought: a click that failed (out of stock, full
				// inventory, insufficient coins), or the server settling the two clicks on DIFFERENT
				// ticks, in which case the first ItemContainerChanged carries only the first
				// transaction's delta while qty already counts both.
				//
				// Neither is detectable client-side — the plugin sees one inventory update and cannot
				// ask what the server did — so this is honest labelling, not a guess. A consumer must
				// treat qty as an UPPER BOUND on a merged row and must never divide gp_total by it.
				// unit_price_gp is omitted for exactly that reason.
				fields.put("qty_merged", true);
				if (executedQty != UNKNOWN_ITEM_COUNT)
				{
					// The measured quantity is not uncertain even when the click intent was — publish it
					// so a consumer has a real denominator instead of only an upper bound.
					fields.put("qty_executed", executedQty);
				}
			}
			else if (executedQty != UNKNOWN_ITEM_COUNT && executedQty != qty)
			{
				// The server moved a different quantity than the click asked for — partial fill (stock ran
				// out, inventory filled, coins ran short) or nothing at all. qty stays the click's own
				// number for continuity with every historical row; the truth sits beside it, labelled.
				fields.put("qty_partial", true);
				fields.put("qty_executed", executedQty);
			}
			else if (executedQty == qty && qty > 1)
			{
				// The measured executed quantity CONFIRMS the click, so gp_total/qty is a real per-item
				// rate over a real denominator. An unmeasurable quantity does not reach here: no evidence
				// is not the same as confirmation, and this release exists to stop plausible wrong numbers.
				fields.put("unit_price_gp", gp / qty);	// average — see note above
			}
		}
		return fields;
	}

	/**
	 * The general-store transaction type for a menu click — "store_buy", "store_sell", or null if it is
	 * neither. SINGLE source of truth for what counts as a store buy/sell: the emit path derives its event
	 * type from this and isStoreBuyOrSell delegates to it, so the two can never drift (grill F5).
	 */
	static String storeTxType(MenuOptionClicked event)
	{
		String opt = event.getMenuOption() == null ? "" : event.getMenuOption();
		if (opt.startsWith("Buy"))
		{
			return "store_buy";
		}
		if (opt.startsWith("Sell"))
		{
			return "store_sell";
		}
		return null;
	}

	/** True when a menu click is a general-store buy/sell (delegates to storeTxType — never a second match). */
	static boolean isStoreBuyOrSell(MenuOptionClicked event)
	{
		return storeTxType(event) != null;
	}

	/**
	 * "Buy 10" -&gt; 10, "Sell 10&lt;col=ff9040&gt;" -&gt; 10, "Buy" -&gt; 1 (default).
	 *
	 * <p>Colour tags are stripped first, then the trailing digits are read. Field-observed 2026-08-17: the
	 * client's SELL options carry a trailing colour tag that BUY options do not ("Sell 10&lt;col=ff9040&gt;"),
	 * so token-parsing threw and silently defaulted every sell to qty 1 while gp_total stayed correct — a
	 * 10x-wrong implied unit price on the gold-delivery path. Stripping matters rather than just scanning
	 * for digits: "ff9040" inside the tag would otherwise read as the quantity.
	 */
	static int parseTrailingQty(String option)
	{
		Matcher m = TRAILING_QTY.matcher(MENU_TAG.matcher(option).replaceAll(""));
		if (!m.find())
		{
			return 1;
		}
		try
		{
			return Integer.parseInt(m.group(1));
		}
		catch (NumberFormatException e)
		{
			return 1;	// absurdly long digit run — treat as unparseable rather than overflow
		}
	}

	/**
	 * Off-book value events. A "Drop", ground-item "Take", or High/Low-Alchemy cast arms an inventory-delta
	 * pending, resolved on the next INVENTORY change from the item-count delta (alch also confirms the gp gained
	 * from the coin delta). Own gate on the activity log; not shop-scoped. Last-click-wins like the store pending
	 * (a fresh click supersedes an unresolved one — rapid drop-all logs the last drop; the wealth snapshot nets
	 * the rest). getItemId() carries the item for Drop/Take (same accessor the store path uses); for alch it is
	 * [verify in-client] (a spell-on-item click may report -1), so an unresolvable item id is skipped, not guessed.
	 */
	void maybeArmInvDeltaPending(MenuOptionClicked event)
	{
		if (!activityLogActive())
		{
			return;
		}
		String action = offBookMenuAction(event);
		if (action == null)
		{
			return;
		}
		int item = offBookItemId(event, action);
		if (item <= 0)
		{
			return;	// no resolvable item id (e.g. alch spell-on-item may report -1) — skip rather than guess
		}
		String base = action.startsWith("alch") ? "alch" : action;
		if ("drop".equals(base))
		{
			// START THE CLIP AT THE CLICK, not at the drop EVENT. The event only exists once the
			// inventory loss and the ground spawn agree, which is ticks later, so starting there
			// would cut the drop itself off the front of the evidence.
			onDropActionForProof();
		}
		String spell = "alch_high".equals(action) ? "high" : ("alch_low".equals(action) ? "low" : null);
		ItemContainer inv = client == null ? null : client.getItemContainer(InventoryID.INVENTORY);
		long beforeCount = countItem(inv, item);
		long beforeCoins = countItem(inv, COINS_ID);
		int tick = client == null ? 0 : client.getTickCount();
		Map<String, Object> location = "alch".equals(base) ? null : currentLocation();
		Boolean wilderness = "drop".equals(base)
			? (client != null && client.getVarbitValue(Varbits.IN_WILDERNESS) > 0)
			: null;
		// A ground Take carries the pile's tile; anything else has none, which reads as (-1,-1,-1).
		int[] tile = "pickup".equals(base) ? groundTakeTile(event) : new int[]{-1, -1, -1};
		InvDeltaPending armed = new InvDeltaPending(base, item, spell, beforeCount, beforeCoins,
			location, wilderness, tick, tile[0], tile[1], tile[2]);
		synchronized (invDeltaPendings)
		{
			// A second Drop before the first lands is the NORMAL shape of a drop trade, never a mistake.
			// Append; never overwrite.
			while (invDeltaPendings.size() >= INV_PENDING_MAX)
			{
				invDeltaPendings.pollFirst();	// evict the oldest — also the most likely to be stale
			}
			invDeltaPendings.addLast(armed);
		}
		if ("pickup".equals(base))
		{
			// Flag every pile this Take could have been aimed at, NOW, while they are all still
			// tracked. From here on none of them may be published as `removed_early` (somebody else
			// took it) or `despawn_timer` (nobody took it): we clicked Take on one of them, so both
			// are claims the client cannot support. Only a proven, unambiguous recovery upgrades a
			// flagged pile back to `self_pickup`.
			flagTakeArmedPiles(armed);
		}
	}

	/**
	 * WORLD tile of the ground pile a Take was clicked on, or null when it cannot be established.
	 *
	 * A ground-item menu entry carries the pile's SCENE coordinates in param0/param1 (live capture
	 * 2026-09-12: `option=Take id=1931 itemId=-1 param0=49 param1=54`). Scene coordinates are
	 * relative to the loaded region, so they are converted to world coordinates here, which is the
	 * space `DroppedGroundItem` stores and what makes the two comparable.
	 *
	 * Returns null rather than a guess whenever the opcode is not a ground-item option, the client
	 * is unavailable, or the conversion fails.
	 */
	int[] groundTakeTile(MenuOptionClicked event)
	{
		int[] none = {-1, -1, -1};
		if (client == null || event == null || !isGroundItemOpcode(event.getMenuAction()))
		{
			return none;
		}
		try
		{
			net.runelite.api.coords.WorldPoint w = net.runelite.api.coords.WorldPoint.fromScene(
				client, event.getParam0(), event.getParam1(), client.getPlane());
			return w == null ? none : new int[]{w.getX(), w.getY(), w.getPlane()};
		}
		catch (RuntimeException e)
		{
			return none;	// no scene loaded / coordinates out of range — degrade, never guess
		}
	}

	/** True for the ground-item menu opcodes, the only ones whose identifier is an item id. */
	static boolean isGroundItemOpcode(net.runelite.api.MenuAction a)
	{
		return a == net.runelite.api.MenuAction.GROUND_ITEM_FIRST_OPTION
			|| a == net.runelite.api.MenuAction.GROUND_ITEM_SECOND_OPTION
			|| a == net.runelite.api.MenuAction.GROUND_ITEM_THIRD_OPTION
			|| a == net.runelite.api.MenuAction.GROUND_ITEM_FOURTH_OPTION
			|| a == net.runelite.api.MenuAction.GROUND_ITEM_FIFTH_OPTION;
	}

	/**
	 * The item id for an off-book action, from whichever field the client actually populates.
	 *
	 * `getItemId()` is the right source for an INVENTORY action (Drop, alch): the entry is a widget
	 * op on a slot that holds the item. A GROUND-item Take is a different opcode entirely, and the
	 * client leaves `getItemId()` at -1 while putting the item id in the entry IDENTIFIER.
	 *
	 * Measured on the live client 2026-09-12, dropping and re-taking a Pot on world 308 - the real
	 * MenuOptionClicked the plugin received:
	 *
	 *     option=Take target=&lt;col=ff9040&gt;Pot id=1931 itemId=-1 param0=49 param1=54
	 *     type=GROUND_ITEM_THIRD_OPTION
	 *
	 * against the inventory Drop of the same item moments earlier:
	 *
	 *     option=Drop target=&lt;col=ff9040&gt;Pot id=7 itemId=1931 param0=1 param1=9764864
	 *     type=CC_OP_LOW_PRIORITY
	 *
	 * So a self-pickup could never arm a pending, no `pickup` was ever emitted for this opcode, and
	 * the item's later removal fell through to the despawn comparison and was labelled as if a
	 * stranger or the timer had taken it. Both symptoms, one cause.
	 *
	 * The fallback is deliberately narrow: pickup only, only when `getItemId()` is unusable, AND
	 * only on a GROUND_ITEM_* opcode. `identifier` on an inventory entry is the OPTION index (7 for
	 * Drop above), never an item, so it must not be consulted anywhere else. Matching the option
	 * word "Take" alone would adopt the identifier of any future non-ground entry labelled Take; the
	 * opcode is what makes that identifier an item id.
	 */
	static int offBookItemId(MenuOptionClicked event, String action)
	{
		int item = event.getItemId();
		if (item > 0 || !"pickup".equals(action) || !isGroundItemOpcode(event.getMenuAction()))
		{
			return item;
		}
		return event.getId();	// ground Take: the item id lives in the entry identifier
	}

	/**
	 * Ground-spawn confirmation for drops (the M3 redesign). A "drop" pending resolves ONLY when BOTH signals
	 * co-occur: the armed item APPEARS ON THE GROUND on/adjacent to the player's tile, AND the player's
	 * inventory holds fewer of it than at click time. An inventory removal alone (equip / eat / bank deposit /
	 * destroy — including via widget buttons that carry no item id) spawns no ground item, so it can never
	 * fabricate a drop; a ground item alone (another player's drop becoming visible) shows no inventory loss,
	 * so it never resolves either. Package-private + primitives so it is unit-testable without a client.
	 */
	/** Backwards-compatible overload — a plain ItemSpawned is always a fresh (grown) ground item. */
	void resolveDropPendingOnGroundSpawn(int spawnedItemId, int dist, long invCountAfter, int currentTick)
	{
		resolveDropPendingOnGroundSpawn(spawnedItemId, dist, invCountAfter, currentTick, true);
	}

	void resolveDropPendingOnGroundSpawn(int spawnedItemId, int dist, long invCountAfter, int currentTick, boolean stackGrew)
	{
		resolveDropPendingOnGroundSpawn(spawnedItemId, dist, invCountAfter, currentTick, stackGrew, -1, -1, -1);
	}

	/** @param tileX tileY tilePlane the spawn's world tile, recorded on the drop row; -1 when unknown. */
	void resolveDropPendingOnGroundSpawn(int spawnedItemId, int dist, long invCountAfter, int currentTick, boolean stackGrew,
		int tileX, int tileY, int tilePlane)
	{
		if (dist > DROP_SPAWN_MAX_DIST || !stackGrew)
		{
			return;	// !stackGrew: a SHRINKING nearby stack is another player looting it — never our drop landing
		}
		if (deathPending != null)
		{
			// Death belt (backs the onActorDeath disarm): mid-death ground spawns + inventory wipe are the
			// death's items, never a player-initiated drop — refuse all corroboration while a death settles.
			clearInvDeltaPendings();
			return;
		}
		// Match by ITEM ID against the oldest matching armed drop. Packet order is the server's choice, so a
		// spawn may arrive for the second click before the first; matching on the item rather than on "the
		// one pending" is what lets each drop find its own.
		InvDeltaPending p;
		synchronized (invDeltaPendings)
		{
			p = null;
			java.util.Iterator<InvDeltaPending> it = invDeltaPendings.iterator();
			while (it.hasNext())
			{
				InvDeltaPending c = it.next();
				if (!"drop".equals(c.base) || c.item != spawnedItemId)
				{
					continue;
				}
				if (currentTick - c.tick > DROP_PENDING_MAX_TICKS)
				{
					// Stale — the click this pending belonged to is long over. Discard it and keep looking:
					// returning here left a NEWER armed drop of the same item without its spawn, while the
					// pile was already tracked, so a ground_removed row appeared with no drop row (theft
					// investigation M5, event 40325).
					it.remove();
					continue;
				}
				p = c;
				break;
			}
			if (p == null)
			{
				return;
			}
			// The tile follows the spawn that corroborates (overwritten with spawnCorroboratedTick) or completes
			// the drop, so the row names the pile that actually confirmed it, never an earlier stray spawn.
			if (tileX >= 0 && tileY >= 0 && tilePlane >= 0)
			{
				p.spawnX = tileX;
				p.spawnY = tileY;
				p.spawnPlane = tilePlane;
			}
			long staleDelta = p.beforeCount - invCountAfter;
			if (staleDelta <= 0)
			{
				// The spawn was processed BEFORE the inventory decrement was visible (intra-tick order is a
				// server packet detail we don't control). Record the corroboration; the inventory-change path
				// completes the emit when the loss lands. Without this, spawn-first ordering misses EVERY drop.
				p.spawnCorroboratedTick = currentTick;
				return;
			}
			invDeltaPendings.remove(p);	// consume — both signals confirmed
		}
		emitDropEvent(p, p.beforeCount - invCountAfter);
	}

	/** Drop every armed pending — used by the logout / hop / disconnect / death disarms. */
	private void clearInvDeltaPendings()
	{
		synchronized (invDeltaPendings)
		{
			invDeltaPendings.clear();
		}
	}

	/** Remove exactly one resolved/expired pending. */
	private void removeInvDeltaPending(InvDeltaPending p)
	{
		synchronized (invDeltaPendings)
		{
			invDeltaPendings.remove(p);
		}
	}

	/** The oldest armed pending, or null. Kept so the single-pending call sites read unchanged. */
	private InvDeltaPending peekInvDeltaPending()
	{
		synchronized (invDeltaPendings)
		{
			return invDeltaPendings.peekFirst();
		}
	}

	/** Shared drop emit — reached from either signal order. */
	private void emitDropEvent(InvDeltaPending p, long qty)
	{
		if (!activityLogActive())
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("item", p.item);
		fields.put("qty", qty);
		if (p.location != null)
		{
			fields.put("location", p.location);
		}
		fields.put("wilderness", p.wilderness != null && p.wilderness);
		// The pile's exact tile and the world, on the drop row itself, in the same {x,y,plane} shape the
		// ground_removed row uses. Before this only the removal row carried the tile, so a drop whose
		// removal was never seen (out of scene, logout) could not be located at all.
		if (p.spawnX >= 0)
		{
			Map<String, Object> tile = new LinkedHashMap<>();
			tile.put("x", p.spawnX);
			tile.put("y", p.spawnY);
			tile.put("plane", p.spawnPlane);
			fields.put("tile", tile);
		}
		int world = client == null ? 0 : client.getWorld();
		if (world > 0)
		{
			fields.put("world", world);
		}
		// SESSION LINKAGE on the drop row itself. drop_seq is what lets the manifest join one drop
		// to one removal when a session drops the same item, same quantity, onto the same tile twice
		// — which is the ordinary shape of a drop trade, not an edge case.
		if (dropSession.active() && pendingDropSessionId != null)
		{
			fields.put("drop_session_id", pendingDropSessionId);
			fields.put("drop_seq", pendingDropSeq);
		}
		emitEvent("drop", fields);
	}

	/** Live wrapper for the ground-spawn resolvers: distance from the local player + current inventory count. */
	private void handleGroundItemForDropPending(net.runelite.api.TileItem it, net.runelite.api.Tile tile,
		boolean stackGrew, boolean quantityMerge)
	{
		InvDeltaPending p = peekInvDeltaPending();
		if (p == null || client == null || it == null || tile == null)
		{
			return;	// cheap early-out — ItemSpawned fires constantly for scenery/other players' items
		}
		// Ownership discriminator (Codex consult 2026-07-18): the client tags ground items with ownership,
		// and a fresh OWN drop is always OWNERSHIP_SELF. Anything else (another player's pile, an NPC drop,
		// an ownerless world spawn, a GIM partner's item) can never corroborate OUR drop — this closes the
		// whole coincidental-foreign-source fabrication class in one check. [verify in-client]
		if (it.getOwnership() != net.runelite.api.TileItem.OWNERSHIP_SELF)
		{
			return;
		}
		net.runelite.api.Player me = client.getLocalPlayer();
		net.runelite.api.coords.WorldPoint pw = me == null ? null : me.getWorldLocation();
		net.runelite.api.coords.WorldPoint tw = tile.getWorldLocation();
		if (pw == null || tw == null || pw.getPlane() != tw.getPlane())
		{
			return;
		}
		int dist = Math.abs(pw.getX() - tw.getX()) + Math.abs(pw.getY() - tw.getY());
		long invCount = countItem(client.getItemContainer(InventoryID.INVENTORY), it.getId());
		int tick = client.getTickCount();
		// Track the pile BEFORE resolving: resolution may consume the pending, and the pile is ours either
		// way (OWNERSHIP_SELF, our own tile). Only track when an armed drop of this item is waiting, so a
		// pile we did not drop this session is never adopted.
		if (dist <= DROP_SPAWN_MAX_DIST && stackGrew && hasArmedDropFor(it.getId()))
		{
			trackGroundDrop(it.getId(), it.getQuantity(), tw.getX(), tw.getY(), tw.getPlane(),
				currentLocation(), tick, it.getDespawnTime(), quantityMerge);
		}
		resolveDropPendingOnGroundSpawn(it.getId(), dist, invCount, tick, stackGrew,
			tw.getX(), tw.getY(), tw.getPlane());
	}

	/** A fresh ground item at our tile — the primary own-drop confirmation signal. */
	@Subscribe
	public void onItemSpawned(net.runelite.api.events.ItemSpawned event)
	{
		// A genuine NEW ground item, never a merge into an existing stack.
		handleGroundItemForDropPending(event.getItem(), event.getTile(), true, false);
	}

	/**
	 * Dropping a stackable onto an existing ground stack merges instead of spawning — same confirmation, but
	 * ONLY when the stack GREW (a shrinking stack is another player looting it, never our drop landing).
	 */
	@Subscribe
	public void onItemQuantityChanged(net.runelite.api.events.ItemQuantityChanged event)
	{
		// A GROWN stack is a merge: the game holds ONE pile and will fire ONE ItemDespawned for it.
		handleGroundItemForDropPending(event.getItem(), event.getTile(),
			event.getNewQuantity() > event.getOldQuantity(), true);
	}

	/** True when a "drop" pending for this item is armed — i.e. this ground pile is one we just dropped. */
	boolean hasArmedDropFor(int item)
	{
		synchronized (invDeltaPendings)
		{
			for (InvDeltaPending p : invDeltaPendings)
			{
				if ("drop".equals(p.base) && p.item == item)
				{
					return true;
				}
			}
		}
		return false;
	}

	// ---- ground-item lifecycle: our own dropped piles, tracked from spawn to removal ----

	/**
	 * Record a pile WE dropped, so a later ItemDespawned on the same tile can be attributed to it.
	 * Called only from the own-tile OWNERSHIP_SELF spawn path, so a foreign pile is never tracked.
	 */
	void trackGroundDrop(int item, long qty, int x, int y, int plane, Map<String, Object> location,
		int dropTick, int despawnTick)
	{
		trackGroundDrop(item, qty, x, y, plane, location, dropTick, despawnTick, false);
	}

	/**
	 * @param quantityMerge true when this arrived as an ItemQuantityChanged growth, meaning the game
	 *                      merged the drop into an existing stack instead of spawning a new pile.
	 */
	void trackGroundDrop(int item, long qty, int x, int y, int plane, Map<String, Object> location,
		int dropTick, int despawnTick, boolean quantityMerge)
	{
		synchronized (groundDrops)
		{
			// A MERGED STACK IS ONE PILE. Dropping a stackable onto a live pile of the same item on
			// the same tile merges it in the game, and the client will fire exactly one
			// ItemDespawned for the result. Tracking a second DroppedGroundItem here made a second
			// session key that no despawn could release (finding F1 path A).
			//
			// FINDING B2: the lookup is ownership-AGNOSTIC on purpose, and it is findGroundDrop —
			// the SAME resolver onItemDespawned uses. So the entry adopted here is exactly the entry
			// that despawn will resolve to. Requiring session membership here is what let one
			// physical pile become two tracked entries whenever the pile predated the session.
			//
			// STILL SCOPED TO A RUNNING SESSION. With no session there are no keys to strand, and
			// two tracked records for one stack is the pre-existing behaviour the ground-removal
			// arms pin: it makes the removal report cause `unknown` rather than fabricate an
			// attribution. Widening the merge branch to the no-session case would change that, and
			// it has nothing to do with this defect.
			DroppedGroundItem merged =
				quantityMerge && dropSession.active() ? findGroundDrop(item, x, y, plane) : null;
			if (merged != null)
			{
				adoptMergedPileIntoDropSession(merged);
				return;
			}
			while (groundDrops.size() >= GROUND_TRACK_MAX)
			{
				// An evicted pile is one we will never see leave the ground. Tell the session, or
				// its key is stranded and the recorder waits on it forever (finding F1 path C).
				abandonPileFromDropSession(groundDrops.pollFirst());
			}
			DroppedGroundItem g =
				new DroppedGroundItem(item, qty, x, y, plane, location, dropTick, despawnTick);
			// The game called this drop a MERGE and the merge branch above did not take it, so this
			// record shadows a pile already on the tile rather than standing for one of its own.
			// FINDING R2 needs that distinction; see DroppedGroundItem.mergeShadow.
			g.mergeShadow = quantityMerge;
			groundDrops.addLast(g);
			attachPileToDropSession(g);
		}
	}

	/**
	 * Resolve a removal or a merge at this item and tile: the RUNNING SESSION'S OWN record first,
	 * then the oldest match.
	 *
	 * ROUND 6, FINDING R1 — WHY OWNERSHIP IS PART OF THE RESOLUTION ORDER. The merge branch above is
	 * scoped to a running session, so with no session a stackable dropped twice onto one tile is
	 * still TWO records for ONE physical pile. The single despawn consumed the OLDEST of them and
	 * left the other on a tile that is now physically empty: a PHANTOM. It is unowned, so no
	 * teardown removes it, and no despawn will ever come for it.
	 *
	 * A later session then dropped on that same tile and the ONE despawn it gets resolved to the
	 * phantom again, because the phantom is older. The session's own key was never released, the
	 * tail never armed, and only MAX_SESSION_MILLIS ended the recording — 30 minutes rather than the
	 * five seconds after the final pile both user-facing strings promise. The invariant backstop
	 * cannot see it either: dropSessionHoldsOwnedPile asks whether the session owns ANY tracked
	 * record, and it still owns the record whose physical pile is gone. The disagreement is between
	 * the tracked RECORD and the PHYSICAL pile, which neither half of that invariant can observe.
	 *
	 * So when a session is running its own record wins. A despawn on a tile the session dropped on
	 * is the session's pile far more often than it is a stale record, and the session's record is
	 * the only one whose resolution can release a recorder key.
	 *
	 * NO-SESSION BEHAVIOUR IS UNCHANGED, deliberately. With no session there is no owner, the loop
	 * falls straight through to the oldest match, and the two-records-for-one-stack attribution the
	 * ground-removal arms pin is exactly as it was. That behaviour makes a removal report `unknown`
	 * rather than fabricate an attribution, and this change must not touch it.
	 *
	 * ONE RESOLVER, BOTH CALLERS. trackGroundDrop's merge lookup and onItemDespawned use this same
	 * method, so the record a merge ADOPTS is exactly the record the despawn will later resolve to.
	 * Finding B2 was caused by those two asking different questions.
	 */
	private DroppedGroundItem findGroundDrop(int item, int x, int y, int plane)
	{
		String sid = dropSession.sessionId();
		synchronized (groundDrops)
		{
			if (sid != null)
			{
				for (DroppedGroundItem g : groundDrops)
				{
					if (g.item == item && g.x == x && g.y == y && g.plane == plane
						&& sid.equals(dropPileSession.get(g)))
					{
						return g;
					}
				}
			}
			for (DroppedGroundItem g : groundDrops)
			{
				if (g.item == item && g.x == x && g.y == y && g.plane == plane)
				{
					return g;
				}
			}
		}
		return null;
	}

	/**
	 * Resolve the ONE ItemDespawned the game fires at this item and tile, and say whether the
	 * choice was a GUESS.
	 *
	 * ROUND 7, FINDING R2 — WHY THE DESPAWN PATH NEEDS ITS OWN RESOLVER. Round 6 made
	 * findGroundDrop prefer the running session's own record, which closed R1. On a tile holding
	 * TWO REAL PILES of the same UNSTACKABLE, only one of them the session's, that preference took
	 * the despawn of the OTHER pile: the session's key was released while its pile was still on the
	 * ground, the recording stopped early, and the manifest claimed COMPLETE on a clip missing the
	 * collection. The row published for the other pile carried our session id, our sequence, our
	 * quantity and our time on ground.
	 *
	 * WHICH PILE WENT IS UNKNOWABLE. The game tells us an item of that id left that tile. It does
	 * not tell us which of two identical piles it was. Round 6 guessed "ours" and round 5 guessed
	 * "the oldest", and both guesses are wrong half the time. So this method REFUSES: it reports the
	 * choice as ambiguous and the callers then decline to claim anything.
	 *
	 *   ATTRIBUTION goes back to the pre-round-6 answer — the oldest UNOWNED record. That is the
	 *   other pile's own record, so the published row is the honest one it always was, with no
	 *   session linkage on it (see attributionAmbiguous).
	 *
	 *   THE RECORDER keeps its key. Our pile may still be lying there, so releasing the key would be
	 *   the early stop all over again. The session is marked unprovable instead, which puts COMPLETE
	 *   permanently out of reach, and it still ends: by the later despawn that is no longer
	 *   ambiguous, by the orphaned-state invariant, or by the 30-minute cap.
	 *
	 * A MERGE SHADOW IS NOT A SECOND PILE, and that is what keeps R1 closed. The R1 phantom is one
	 * physical pile tracked twice, because the merge branch is scoped to a running session: the
	 * second record was minted from a quantityMerge and stands for nothing of its own. Treating it
	 * as a rival pile here would make every R1 route ambiguous again and strand the recorder exactly
	 * as round 3 measured. So only a NON-shadow unowned record can make a despawn ambiguous.
	 */
	static final class GroundDespawnMatch
	{
		final DroppedGroundItem record;
		final boolean ambiguous;

		GroundDespawnMatch(DroppedGroundItem record, boolean ambiguous)
		{
			this.record = record;
			this.ambiguous = ambiguous;
		}
	}

	/** Pure: picks the record and classifies the choice. Changes no state. */
	GroundDespawnMatch matchDespawnedGroundDrop(int item, int x, int y, int plane)
	{
		String sid = dropSession.sessionId();
		if (sid != null)
		{
			DroppedGroundItem owned = null;
			DroppedGroundItem unownedReal = null;
			synchronized (groundDrops)
			{
				// groundDrops is append-ordered, so the first match in each class is the oldest.
				for (DroppedGroundItem g : groundDrops)
				{
					if (g.item != item || g.x != x || g.y != y || g.plane != plane)
					{
						continue;
					}
					if (sid.equals(dropPileSession.get(g)))
					{
						if (owned == null)
						{
							owned = g;
						}
					}
					else if (!g.mergeShadow && unownedReal == null)
					{
						unownedReal = g;
					}
				}
			}
			if (owned != null && unownedReal != null)
			{
				return new GroundDespawnMatch(unownedReal, true);
			}
			if (owned != null)
			{
				return new GroundDespawnMatch(owned, false);
			}
		}
		return new GroundDespawnMatch(findGroundDrop(item, x, y, plane), false);
	}

	/**
	 * Resolve a despawn and APPLY the refusal when the choice was a guess. The one entry point the
	 * despawn path uses, so nothing can resolve a despawn and forget to record the ambiguity.
	 */
	DroppedGroundItem resolveDespawnedGroundDrop(int item, int x, int y, int plane)
	{
		return resolveDespawnedGroundDrop(item, x, y, plane, null);
	}

	/**
	 * Every record THIS SESSION owns for that item on that tile, oldest first.
	 *
	 * ROUND 9. The count of these is |S| in the despawn rule below. groundDrops is append-ordered,
	 * so the returned order is the order the piles were dropped.
	 */
	List<DroppedGroundItem> sessionOwnedPilesAt(int item, int x, int y, int plane)
	{
		List<DroppedGroundItem> out = new ArrayList<>();
		String sid = dropSession.sessionId();
		if (sid == null)
		{
			return out;
		}
		synchronized (groundDrops)
		{
			for (DroppedGroundItem g : groundDrops)
			{
				if (g.item == item && g.x == x && g.y == y && g.plane == plane
					&& sid.equals(dropPileSession.get(g)))
				{
					out.add(g);
				}
			}
		}
		return out;
	}

	/**
	 * A despawning pile that can state its own quantity.
	 *
	 * The live path passes a {@code net.runelite.api.TileItem}, whose {@code getQuantity()} was read
	 * from the resolved runelite-api-1.12.39 jar with javap. The test harness models a physical pile
	 * with its own type, so this interface is the seam that lets both answer the same question
	 * without the harness having to implement the whole TileItem surface.
	 */
	interface DespawnQuantity
	{
		long despawnedQuantity();
	}

	/** The quantity of the pile that is going, or null when nothing can say. */
	static Long despawningQuantity(Object despawnedItem)
	{
		if (despawnedItem instanceof net.runelite.api.TileItem)
		{
			return (long) ((net.runelite.api.TileItem) despawnedItem).getQuantity();
		}
		if (despawnedItem instanceof DespawnQuantity)
		{
			return ((DespawnQuantity) despawnedItem).despawnedQuantity();
		}
		return null;
	}

	/**
	 * ROUND 8, FINDING R4 — THE SCENE DECIDES WHETHER A DESPAWN IS AMBIGUOUS, NOT OUR BOOKKEEPING.
	 *
	 * Round 7 asked "do I hold TWO matching RECORDS?". That question can only see a rival the plugin
	 * TRACKED, and groundDrops only ever holds OWNERSHIP_SELF piles that had an armed drop pending.
	 * Three real piles are invisible to it: another player's pile, our own pile from a session that
	 * ended while the pile was still down (every end path runs endDropSessionTracking, which removes
	 * the records and leaves the PILES), and a pile dropped before the token was linked. When one of
	 * those despawns, round 7 saw exactly one matching record — OURS — called it unambiguous,
	 * released the key, stopped recording five seconds later with our pile still on the ground, and
	 * published COMPLETE with our session id on a row describing somebody else's pile.
	 *
	 * SO THE QUESTION CHANGES to one the game can answer: after this despawn, is there still an item
	 * of this id on this tile? If there is, the despawn is AMBIGUOUS whatever we tracked, because
	 * our pile may be the one still lying there. If there is not, it is unambiguous: nothing of that
	 * kind is left, so our pile went.
	 *
	 * THE SCENE CAN ONLY MAKE A DESPAWN MORE AMBIGUOUS, NEVER LESS. A round-7 ambiguity stands
	 * whatever the scene says. UNKNOWN changes nothing, because a tile out of scene is not an empty
	 * tile and this must not invent certainty from a failed read.
	 *
	 * @param despawnedItem the TileItem that is going, excluded from the scene count by identity, or
	 *                      null. See {@link #scenePileState}.
	 */
	DroppedGroundItem resolveDespawnedGroundDrop(int item, int x, int y, int plane,
		Object despawnedItem)
	{
		GroundDespawnMatch m = matchDespawnedGroundDrop(item, x, y, plane);
		if (m.record != null && m.ambiguous)
		{
			// ROUND 7. Two records matched and one of them is a real rival pile, so the OTHER pile's
			// own record answers the despawn and the flag rides on it. Carried on the RECORD, because
			// a parked removal publishes its row ticks later and must publish the same refusal this
			// despawn decided. Our own record stays tracked and our key stays held.
			m.record.attributionAmbiguous = true;
			dropSession.resolutionAmbiguous(System.currentTimeMillis());
			return m.record;
		}
		// ROUND 9, FINDING R7 — COUNT THE TILE, AND USE THE QUANTITY. DO NOT ASK PRESENCE.
		//
		// Round 8 asked "is an item of this id still on this tile?". That question cannot tell our
		// OWN second pile from a stranger's, so a session that dropped ten piles of one item on one
		// tile had the first nine despawns refused, published one of its ten evidence rows, and that
		// row described the WRONG pile. An ordinary store delivery is exactly that shape.
		//
		// The counting question CAN tell them apart. |S| is how many records this session still owns
		// for that item on that tile, INCLUDING the one this despawn is about to credit. C is how
		// many piles of that id the scene still shows, with the despawning TileItem excluded by
		// identity. After this removal the scene holds C piles while we still claim |S|, so:
		//
		//   C >= |S|  AMBIGUOUS. At least as many piles are left as we own, so at least one of them
		//             is not ours, and the one that left may equally have been that stranger's.
		//   C <  |S|  UNAMBIGUOUS. There are fewer piles left than we own, so one of OURS went.
		//
		// ONLY a despawn about to be credited to our own pile is tested. A record that is not ours
		// releases no key and claims no session linkage, so there is nothing here for the scene to
		// protect. UNKNOWN keeps round-8 behaviour: no refusal is ever inferred from a failed read,
		// and the invariant and the hard cap remain the backstops.
		if (m.record != null
			&& dropSession.sessionId() != null
			&& dropSession.sessionId().equals(dropSessionForPile(m.record)))
		{
			SceneTileItems scene = sceneTileItems(item, x, y, plane, despawnedItem);
			if (scene.state != ScenePileState.UNKNOWN)
			{
				List<DroppedGroundItem> own = sessionOwnedPilesAt(item, x, y, plane);
				if (scene.count >= own.size())
				{
					// THE DESPAWN IS REFUSED WHOLE, which is the round-7 handling with no rival
					// record to hand it to. Returning null leaves our record TRACKED and our key
					// HELD, so the tail cannot arm while our pile may still be down, and publishes
					// NO row: a row built from our record would describe a pile that did not
					// necessarily go, which is the fabricated attribution round 7 exists to refuse.
					//
					// attributionAmbiguous is deliberately NOT set on our record. Our pile is still
					// down and its own later removal may be perfectly attributable; the flag never
					// clears, so setting it here would refuse an honest linkage we have not yet
					// lost. COMPLETE is already out of reach through the recorder, which is the
					// property that matters.
					dropSession.resolutionAmbiguous(System.currentTimeMillis());
					return null;
				}
				if (own.size() > 1)
				{
					return chooseOwnPileByQuantity(own, despawnedItem);
				}
			}
		}
		return m.record;
	}

	/**
	 * ROUND 9 — WHICH of our own piles just left, when several of ours are on that tile.
	 *
	 * The count already proved one of OURS went. The QUANTITY says which one.
	 * {@code TileItem.getQuantity()} was read from runelite-api-1.12.39 with javap.
	 *
	 * <ul>
	 * <li>EXACTLY ONE of our records carries that quantity: that is the pile, with its own
	 *     drop_seq, its own ticks_on_ground and its own qty. This is the ordinary delivery.</li>
	 * <li>SEVERAL of our records carry it: they are indistinguishable BY QUANTITY, and nothing else
	 *     on the tile can separate them. The oldest is released so its row is published, and ONLY
	 *     that row's attribution is marked uncertain. The session is NOT marked unprovable: every
	 *     candidate is ours, every quantity is the same, and the row's own qty is right. Refusing
	 *     the whole session over two of our own equal piles would be FINDING R7 again.</li>
	 * <li>NONE of our records carries it, or nothing could state the despawning quantity: a pile of
	 *     a size we never dropped left that tile, so it was a stranger's. Refuse.</li>
	 * </ul>
	 */
	private DroppedGroundItem chooseOwnPileByQuantity(List<DroppedGroundItem> own,
		Object despawnedItem)
	{
		Long q = despawningQuantity(despawnedItem);
		List<DroppedGroundItem> matches = new ArrayList<>();
		if (q != null)
		{
			for (DroppedGroundItem g : own)
			{
				if (g.qty == q)
				{
					matches.add(g);
				}
			}
		}
		if (matches.isEmpty())
		{
			dropSession.resolutionAmbiguous(System.currentTimeMillis());
			return null;
		}
		DroppedGroundItem chosen = matches.get(0);	// oldest, groundDrops is append-ordered
		if (matches.size() > 1)
		{
			chosen.qtyTieUncertain = true;
		}
		return chosen;
	}

	void clearGroundDrops()
	{
		synchronized (groundDrops)
		{
			groundDrops.clear();
		}
		synchronized (telegrabSightings)
		{
			telegrabSightings.clear();
		}
	}

	/** True when a pile we track (live or parked for a Take) lies on this tile. */
	boolean isTrackedPileTile(int x, int y, int plane)
	{
		synchronized (groundDrops)
		{
			for (DroppedGroundItem g : groundDrops)
			{
				if (g.x == x && g.y == y && g.plane == plane)
				{
					return true;
				}
			}
		}
		synchronized (pendingRemovals)
		{
			for (DroppedGroundItem g : pendingRemovals.keySet())
			{
				if (g.x == x && g.y == y && g.plane == plane)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Record one Telekinetic Grab sighting when it targets a tracked pile's tile. Prunes rows older than
	 * the attribution window and keeps at most TELEGRAB_SIGHTING_CAP (oldest dropped).
	 */
	void recordTelegrabSighting(DropCandidates.TelegrabSighting s)
	{
		if (s == null || !isTrackedPileTile(s.x, s.y, s.plane))
		{
			return;
		}
		synchronized (telegrabSightings)
		{
			telegrabSightings.removeIf(o -> s.tick - o.tick > DropCandidates.TELEGRAB_WINDOW_TICKS || o.tick > s.tick);
			telegrabSightings.add(s);
			while (telegrabSightings.size() > TELEGRAB_SIGHTING_CAP)
			{
				telegrabSightings.remove(0);
			}
		}
	}

	/**
	 * A Telekinetic Grab projectile in flight. Recorded once per projectile, with its caster, when it
	 * targets the tile of a pile we track. Other projectiles return on the first id compare.
	 */
	@Subscribe
	public void onProjectileMoved(net.runelite.api.events.ProjectileMoved event)
	{
		if (client == null || event == null || event.getProjectile() == null
			|| event.getProjectile().getId() != DropCandidates.TELEGRAB_PROJECTILE)
		{
			return;
		}
		net.runelite.api.Projectile pr = event.getProjectile();
		if (!telegrabProjectilesSeen.add(pr))
		{
			return;
		}
		WorldPoint target = pr.getTargetPoint();
		if (target == null && event.getPosition() != null)
		{
			target = WorldPoint.fromLocal(client, event.getPosition());
		}
		if (target == null)
		{
			return;
		}
		Actor src = pr.getSourceActor();
		boolean self = src != null && src == client.getLocalPlayer();
		String caster = src == null || src.getName() == null ? null : Text.removeTags(src.getName());
		WorldPoint from = src != null && src.getWorldLocation() != null ? src.getWorldLocation() : pr.getSourcePoint();
		recordTelegrabSighting(new DropCandidates.TelegrabSighting(target.getX(), target.getY(), target.getPlane(),
			client.getTickCount(), "projectile", self ? null : caster, self,
			from == null ? target.getX() : from.getX(), from == null ? target.getY() : from.getY()));
	}

	/** A Telekinetic Grab impact graphic. It carries no caster, only the tile. */
	@Subscribe
	public void onGraphicsObjectCreated(net.runelite.api.events.GraphicsObjectCreated event)
	{
		if (client == null || event == null || event.getGraphicsObject() == null
			|| event.getGraphicsObject().getId() != DropCandidates.TELEGRAB_IMPACT
			|| event.getGraphicsObject().getLocation() == null)
		{
			return;
		}
		net.runelite.api.GraphicsObject go = event.getGraphicsObject();
		WorldPoint wp = WorldPoint.fromLocal(client, go.getLocation());
		if (wp == null)
		{
			return;
		}
		recordTelegrabSighting(new DropCandidates.TelegrabSighting(wp.getX(), wp.getY(), go.getLevel(),
			client.getTickCount(), "impact", null, false, wp.getX(), wp.getY()));
	}

	int groundDropCount()
	{
		synchronized (groundDrops)
		{
			return groundDrops.size();
		}
	}

	/**
	 * Could this Take have been aimed at this pile?
	 *
	 * Item, and the tile whenever the client gave us one. A Take the client gave NO tile for could
	 * have been aimed at any pile of its item, so it matches all of them here.
	 *
	 * This is the ONLY match rule in the narrow candidate, and it is used in both directions. To
	 * WEAKEN a verdict, one match is enough: the pile may have been ours. To CLAIM a pile, the
	 * caller additionally requires that it be the ONLY match, so a no-tile Take against two piles
	 * claims neither, and a tiled Take against two piles on that tile claims neither. Identity comes
	 * from the uniqueness, not from the tile alone, and the two directions can never disagree
	 * because there is only one predicate.
	 */
	static boolean takeCouldName(InvDeltaPending take, DroppedGroundItem g)
	{
		return g.item == take.item && (take.takeX < 0
			|| (g.x == take.takeX && g.y == take.takeY && g.plane == take.takePlane));
	}

	/**
	 * Every pile we still hold state for: on the ground, and parked awaiting a verdict.
	 *
	 * One snapshot, because a pile the Take named may already have despawned - which is the ORDINARY
	 * case for a completed pickup, since the despawn callback runs before the inventory change.
	 * Taken under both locks, then released, so callers iterate a copy and never hold two at once.
	 */
	private List<DroppedGroundItem> trackedPiles()
	{
		List<DroppedGroundItem> all = new ArrayList<>();
		synchronized (pendingRemovals)
		{
			all.addAll(pendingRemovals.keySet());
		}
		synchronized (groundDrops)
		{
			all.addAll(groundDrops);
		}
		return all;
	}

	/**
	 * Mark every tracked pile a Take on this item and tile could have been aimed at.
	 *
	 * Run at CLICK time, not at resolution time. A Take is pruned by any unrelated inventory change,
	 * and a store account's inventory changes constantly, so waiting until the arrival lands loses
	 * the flag exactly when the walk was long enough to matter - and the pile then published
	 * `removed_early` over our own recovery.
	 */
	private void flagTakeArmedPiles(InvDeltaPending take)
	{
		for (DroppedGroundItem g : trackedPiles())
		{
			g.takeArmed = g.takeArmed || takeCouldName(take, g);
		}
	}

	/**
	 * True when a local Take that could explain THIS pile is still in flight.
	 *
	 * Used only to DEFER a removal, never to attribute one. Matching on item id alone made a Take on
	 * a stranger's pile ten tiles away defer our pile's removal, which delayed a verdict that was
	 * already knowable.
	 */
	boolean hasArmedPickupFor(DroppedGroundItem g)
	{
		synchronized (invDeltaPendings)
		{
			for (InvDeltaPending p : invDeltaPendings)
			{
				if ("pickup".equals(p.base) && takeCouldName(p, g))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The ONE tracked pile this Take could have been aimed at, or null when there is not exactly one.
	 *
	 * Two candidates are indistinguishable to the client, so nothing is claimed and both report
	 * `unknown`. That covers two piles on one tile and a no-tile Take against two piles of its item
	 * with the same rule. Looks in both collections because the pile may already have despawned and
	 * be parked awaiting its verdict, which is the ordinary case for a completed pickup.
	 */
	private DroppedGroundItem soleClaimablePile(InvDeltaPending take)
	{
		DroppedGroundItem found = null;
		for (DroppedGroundItem g : trackedPiles())
		{
			if (!g.selfPickedUp && takeCouldName(take, g))
			{
				if (found != null)
				{
					return null;	// two candidates: indistinguishable, so claim neither
				}
				found = g;
			}
		}
		return found;
	}

	/**
	 * Attribute a recovered pile to a Take, but ONLY when the evidence is unambiguous.
	 *
	 * Every condition below is necessary and none is negotiable, because the failure this guards
	 * against is naming a staff member as the taker of a pile a CUSTOMER collected:
	 *
	 *   * exactly ONE armed Take of the item in this pass - two Takes and the client cannot say
	 *     which one the server actually ran, and OSRS runs the LAST click of a burst while the
	 *     queue hands the arrival to the FIRST pending;
	 *   * exactly ONE tracked pile that Take could have been aimed at - two piles on the tile, or a
	 *     no-tile Take against two piles of the item, name nothing;
	 *   * the measured gain equals that pile EXACTLY - a ground pile is recovered whole, so a
	 *     1,500-coin store sale is not a 1,200-coin pile.
	 *
	 * The Take's own freshness is enforced earlier, in `resolveOnePending`: a Take past its window
	 * is discarded before it can produce a `pickup` row at all, so it cannot reach this method.
	 *
	 * Anything else marks nothing. The pile keeps its `takeArmed` flag and reports `unknown`, which
	 * is the truthful answer: we may well have taken it, so `removed_early` would be as wrong as
	 * claiming the pickup.
	 *
	 * @return true when a pile was marked, so the caller can publish it at once.
	 */
	private boolean markGroundDropSelfPickedUp(InvDeltaPending take, long rawGain,
		List<InvDeltaPending> snapshot, int currentTick)
	{
		if (take == null)
		{
			return false;
		}
		for (InvDeltaPending other : snapshot)
		{
			if (other != take && "pickup".equals(other.base) && other.item == take.item)
			{
				return false;	// a second armed Take: nothing says which click the server ran
			}
		}
		DroppedGroundItem g = soleClaimablePile(take);
		if (g == null || rawGain != g.qty)
		{
			return false;
		}
		g.selfPickedUp = true;
		// A pile still lying on the ground has NOT been recovered yet, whatever the inventory did.
		// Record the tick, so the removal can check the pile really left. See `provisionalMarkTick`.
		if (stillOnGround(g))
		{
			g.provisionalMarkTick = currentTick;
		}
		return true;
	}

	/** True while the pile is still lying on the ground, so its removal has not been observed yet. */
	private boolean stillOnGround(DroppedGroundItem g)
	{
		synchronized (groundDrops)
		{
			return groundDrops.contains(g);
		}
	}

	/**
	 * Finalize removals whose resolution window has closed, or whose pickup has landed.
	 *
	 * A pile marked `selfPickedUp` finalizes at once - the evidence is in. One that ran out of
	 * window finalizes on what can actually be proven, which is never `self_pickup`. Called from the
	 * tick hook and again whenever a pickup resolves, so the common case publishes immediately
	 * rather than waiting out the window.
	 */
	/**
	 * @param tickPass true only for the once-per-tick call. The budget counts TICKS, not calls: a
	 * pickup resolving re-enters this method, so counting every call let ten pickups landing in one
	 * tick spend the whole budget of every other parked pile. The re-entrant call exists to PUBLISH
	 * a pile whose answer just arrived, never to age one.
	 */
	/**
	 * @param unreliable true for the lifecycle disarms, which take EVERY parked entry and publish it
	 * as `unknown`. Observation is genuinely unreliable across a hop, a logout or a region boundary,
	 * so no stronger cause is supportable there.
	 * @param tickPass true only for the once-per-tick call. The attempt budget counts TICKS, not
	 * calls: a pickup resolving re-enters this method, so counting every call let ten pickups landing
	 * in one tick spend the whole budget of every other parked pile. The re-entrant call exists to
	 * PUBLISH a pile whose answer just arrived, never to age one.
	 */
	void settlePendingRemovals(int currentTick, boolean tickPass, boolean unreliable)
	{
		Map<DroppedGroundItem, Integer> due = new LinkedHashMap<>();
		synchronized (pendingRemovals)
		{
			java.util.Iterator<java.util.Map.Entry<DroppedGroundItem, Integer>> it =
				pendingRemovals.entrySet().iterator();
			while (it.hasNext())
			{
				java.util.Map.Entry<DroppedGroundItem, Integer> e = it.next();
				int waited = currentTick - e.getValue();
				// `waited < 0` means the tick counter moved BACKWARDS under us; `settleAttempts`
				// counts attempts instead of trusting the clock, because a counter that is FROZEN
				// never lets the difference reach the window either. Without both, the entry strands
				// in the map forever and its event is lost. Finalize: a late verdict beats none.
				if (tickPass)
				{
					e.getKey().settleAttempts++;
				}
				if (unreliable || e.getKey().selfPickedUp || waited >= REMOVAL_RESOLVE_MAX_TICKS
					|| waited < 0 || e.getKey().settleAttempts > REMOVAL_RESOLVE_MAX_TICKS)
				{
					due.put(e.getKey(), e.getValue());
					it.remove();		// removed as it fires - never two finals for one lifecycle
				}
			}
		}
		for (Map.Entry<DroppedGroundItem, Integer> e : due.entrySet())
		{
			// The tick the pile ACTUALLY left the ground, not the tick we got around to concluding.
			// Passing `currentTick` here let up to REMOVAL_RESOLVE_MAX_TICKS of drift cross the
			// despawn deadline, silently turning a genuine `removed_early` into `despawn_timer` -
			// a STRONGER claim than the evidence supports, and the opposite of failing closed.
			emitGroundRemoval(e.getKey(), e.getValue(), unreliable || groundObservationUnreliable);
		}
	}

	/**
	 * Discard the parked removals - but PUBLISH them first, as `unknown`.
	 *
	 * A region boundary, a hop or a logout can land inside the resolution window. Clearing the map
	 * silently made the pile's removal vanish from the record entirely, which is worse than the
	 * weaker verdict the same event got before it was ever deferred: a delivery simply disappeared.
	 * Observation is genuinely unreliable across these transitions, so the honest cause is `unknown`
	 * and never `despawn_timer` or `removed_early`. Each entry is emitted at the tick it was
	 * actually removed, and removed from the map as it fires, so this cannot double-emit.
	 */
	void clearPendingRemovals()
	{
		settlePendingRemovals(client == null ? 0 : client.getTickCount(), false, true);
	}

	/**
	 * Our dropped pile left the ground. Emit what we can HONESTLY say, and nothing more.
	 *
	 * `cause` is the whole point of this event and has exactly three values:
	 *   "self_pickup"   — our own account took it back; we saw the inventory rise, so this is certain.
	 *   "despawn_timer" — removal happened at or after the client's own reported despawn deadline.
	 *                     Nobody took it; it timed out.
	 *   "removed_early" — the pile went BEFORE its deadline while we were watching normally.
	 *
	 * "removed_early" means SOMETHING removed it early. It does NOT mean a customer collected it,
	 * and it names nobody. A different player picking it up and an unobserved client-side quirk are
	 * indistinguishable here, which is why the field says removed, not delivered. Any consumer that
	 * renders this as a delivery is reading it wrong.
	 *
	 * When observation was unreliable (scene reload, hop, logout, render-distance loss) the cause is
	 * "unknown" — the ambiguity is preserved explicitly rather than resolved by guessing.
	 */
	/**
	 * May a pile a Take once named still report `despawn_timer`? Only at its OWN deadline.
	 *
	 * `takeArmed` is set at click time and never clears, so a Take the client gave no tile for costs
	 * every same-item pile its timer evidence for the whole pile lifetime. This buys that evidence
	 * back in the one case where the Take cannot explain anything, and every condition is required:
	 *
	 *   * observation was reliable, and the client did report a deadline for this pile;
	 *   * the pile left AT that deadline, inside the margin on BOTH sides - so this can only ever
	 *     produce `despawn_timer`, never `removed_early`, which is the label the old
	 *     withholding-expiry rule got wrong (round 8). The upper bound matters as much as the lower
	 *     one: a removal a hundred ticks PAST a deadline means the record does not describe the pile
	 *     that just left, which is the merged-stack double-tracking residual, and a stale record
	 *     must never be read as a timer expiry;
	 *   * NO Take of this item is still live, so nothing in flight could still explain the removal,
	 *     and no Take was live when the pile actually LEFT the ground either - a Take that expired
	 *     while the removal sat parked still explains that removal, so the pile stays ambiguous;
	 *   * no gain was ever claimed for this pile, provisionally or otherwise - a claim that was made
	 *     and then withdrawn leaves the pile ambiguous, and ambiguous stays `unknown`.
	 *
	 * This method does NOT repeat the GROUND_TIMER_MIN_TICKS floor. Passing here only clears the way
	 * to the deadline branch below, which applies that floor itself, so a pile removed too fast for a
	 * real timer still reads `unknown`. `theTimerFloorStillRefusesAnImplausiblyFastExpiry` proves it.
	 *
	 * The reset is bound to the pile's own deadline and to nothing else. Resetting on TAKE EXPIRY
	 * instead is round 8 again: a walk longer than the Take window would publish our own recovery as
	 * `removed_early`. `aTakeThatExpiredWithThePileStillOnTheGroundStaysUnknown` holds that line.
	 */
	private boolean timerExpiryIsStillProvable(DroppedGroundItem g, int currentTick,
		boolean observationUnreliable)
	{
		return !observationUnreliable
			&& g.despawnTick >= 0
			&& currentTick >= g.despawnTick - GROUND_EARLY_MARGIN_TICKS
			&& currentTick <= g.despawnTick + GROUND_EARLY_MARGIN_TICKS
			&& g.provisionalMarkTick < 0
			&& !g.parkedForLiveTake
			&& !hasArmedPickupFor(g);
	}

	void emitGroundRemoval(DroppedGroundItem g, int currentTick, boolean observationUnreliable)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("item", g.item);
		fields.put("qty", g.qty);
		if (g.location != null)
		{
			fields.put("location", g.location);
		}
		fields.put("ticks_on_ground", Math.max(0, currentTick - g.dropTick));
		// Reconcile a PROVISIONAL mark against what the pile actually did. A pile we truly recovered
		// leaves the ground in the same tick, or in the next tick or two. One still lying there long
		// after the gain was never the source of that gain, so the mark is withdrawn and `takeArmed`
		// carries the row to `unknown`. This branch only WEAKENS: it never sets `selfPickedUp`.
		//
		// A NEGATIVE wait means the tick counter moved BACKWARDS under us, so the removal cannot be
		// timed against the mark at all. A bare `> margin` test reads every negative difference as
		// "inside the margin" and keeps the claim, which is the strongest reading of the weakest
		// evidence. `settlePendingRemovals` already treats `waited < 0` as a reason to stop trusting
		// the clock; the provisional path does the same and withdraws.
		int sinceMark = currentTick - g.provisionalMarkTick;
		if (g.selfPickedUp && g.provisionalMarkTick >= 0
			&& (sinceMark < 0 || sinceMark > GROUND_EARLY_MARGIN_TICKS))
		{
			g.selfPickedUp = false;
		}
		String cause;
		if (g.selfPickedUp)
		{
			cause = "self_pickup";
		}
		else if (g.takeArmed && !timerExpiryIsStillProvable(g, currentTick, observationUnreliable))
		{
			// A local Take named this pile and the recovery was not proven exactly. `removed_early`
			// would read as somebody else taking it and `despawn_timer` as nobody taking it, and we
			// can support neither. Refusing to attribute must not become a different wrong answer.
			cause = "unknown";
		}
		else if (observationUnreliable)
		{
			cause = "unknown";
		}
		else if (g.despawnTick < 0)
		{
			cause = "unknown";	// the client never reported a deadline — we cannot call early vs timer
		}
		else if (currentTick >= g.despawnTick - GROUND_EARLY_MARGIN_TICKS)
		{
			// FAIL CLOSED. `despawnTick` is an ABSOLUTE future tick (measured live 2026-09-12:
			// tick=244 while despawn_time=530 on a freshly dropped pile), so a pile that genuinely
			// reaches its deadline has been on the ground for the full timer. A removal that
			// satisfies the comparison after only a handful of ticks means the deadline we stored
			// does not describe this pile - a stale baseline, a re-tracked tile, a client whose tick
			// counter moved under us. Saying "nobody took it" there is an assertion we cannot
			// support. Unknown is the honest answer; a wrong cause is worse than no cause.
			cause = (currentTick - g.dropTick) < GROUND_TIMER_MIN_TICKS ? "unknown" : "despawn_timer";
		}
		else
		{
			cause = "removed_early";
		}
		fields.put("cause", cause);
		// Stated on every row so no downstream reader has to know this rule: the taker is never observable.
		fields.put("recipient", "UNKNOWN");
		// DROP-SESSION LINKAGE. Written on every removal of a session pile, whatever the cause, so a
		// self-pickup and a despawn join the manifest exactly like an early removal does. A removal
		// that carries no session id simply predates the feature or happened outside a session.
		// FINDING R2. An AMBIGUOUS despawn resolved between two real piles of one item on one tile
		// and could not know which went. Naming our session on that row would be a fabricated
		// attribution, so the row carries no linkage at all. `cause` is left to the ordinary rules,
		// which already answer `unknown` when the evidence does not support a claim.
		String dropSid = g.attributionAmbiguous ? null : dropSessionForPile(g);
		int dropSeq = dropSeqForPile(g);
		if (dropSid != null)
		{
			fields.put("drop_session_id", dropSid);
			fields.put("drop_seq", dropSeq);
			// ROUND 9. Two or more of OUR OWN piles of this item on this tile carried the same
			// quantity, so which of them left cannot be told apart. The linkage and the qty are
			// still ours and still right; only drop_seq and ticks_on_ground name one of several
			// equal candidates. Said on the row rather than hidden, so a reader is never misled.
			if (g.qtyTieUncertain)
			{
				fields.put("attribution_uncertain", "equal_quantity_own_piles");
			}
		}
		// The pile's own tile, on every row. A reader must be able to locate the pile without
		// knowing where our character stood, and the candidate offsets below are relative to THIS.
		Map<String, Object> tile = new LinkedHashMap<>();
		tile.put("x", g.x);
		tile.put("y", g.y);
		tile.put("plane", g.plane);
		fields.put("tile", tile);
		// A Telekinetic Grab aimed at THIS tile just before it went. A grab takes the pile from range, so
		// a player standing on the tile may be a bystander; the reader must know. Absent when none seen.
		// Not on a self-pickup: our own Take already explains the removal.
		if (!"self_pickup".equals(cause))
		{
			Map<String, Object> grab;
			synchronized (telegrabSightings)
			{
				grab = DropCandidates.telegrabAt(telegrabSightings, g.x, g.y, g.plane, currentTick);
			}
			if (grab != null)
			{
				fields.put("telegrab", grab);
			}
		}
		// Who was standing here when an early removal happened. The pickup itself runs in another
		// player's client, so this is EVIDENCE, never an answer: it goes out under the same
		// `taken_by_candidates` inference field the store handoff uses, for the server-side resolver
		// (one candidate at dist 0 -> counterparty, anything else stays UNKNOWN). Only `removed_early`
		// carries it: presence at a timer expiry or a self-pickup says nothing about a taker.
		// The key is absent when nobody is around, matching the store path, so readers keep one rule.
		if ("removed_early".equals(cause))
		{
			// PILE-CENTRED, not player-centred. The old snapshot measured from OUR character, which
			// answers the wrong question: in a drop trade the staff member walks off before the
			// customer arrives, so a player at distance 0 from us is not on the pile and a player on
			// the pile can be many tiles away. Measured from the pile's own tile instead.
			List<Map<String, Object>> present =
				DropCandidates.candidatesAt(observedPlayers(), g.x, g.y, g.plane);
			if (!present.isEmpty())
			{
				fields.put("taken_by_candidates", present);
			}
			// Resolve ONLY on exactly one player standing on the pile's tile. Two on it is
			// AMBIGUOUS, an empty tile is UNKNOWN, and neither ever names anybody. The Take itself
			// happens in the other client and nothing in our stream proves it.
			String resolved = fields.containsKey("telegrab") ? null : DropCandidates.resolveCounterparty(present);
			String status = DropCandidates.statusFor(present, resolved);
			fields.put("counterparty_status", status);
			if (resolved != null)
			{
				// `counterparty_inferred`, never `counterparty`. The trade path's `counterparty` is
				// read from the trade window and IS the other party; this one is an inference from
				// where somebody stood, and the field name has to keep those apart.
				fields.put("counterparty_inferred", resolved);
			}
		}
		else
		{
			// Self-pickup, despawn timer and unknown all carry the status too, so a reader never has
			// to infer "no status means nobody asked" from an absent key.
			fields.put("counterparty_status", DropCandidates.STATUS_UNKNOWN);
		}
		emitEvent("ground_removed", fields);
		// Tell the session this pile is gone. When it is the last one, the 5-second tail arms.
		releasePileFromDropSession(g);
	}

	/**
	 * RuneLite fires ItemDespawned for a pile leaving the ground — taken, timed out, or simply
	 * out of scene. Only piles WE dropped and are still tracking reach an emit.
	 */
	@Subscribe
	public void onItemDespawned(net.runelite.api.events.ItemDespawned event)
	{
		if (client == null || event == null || event.getItem() == null || event.getTile() == null)
		{
			return;
		}
		net.runelite.api.coords.WorldPoint tw = event.getTile().getWorldLocation();
		if (tw == null)
		{
			return;
		}
		DroppedGroundItem g = resolveDespawnedGroundDrop(
			event.getItem().getId(), tw.getX(), tw.getY(), tw.getPlane(), event.getItem());
		if (g == null)
		{
			// Not one of ours — every other pile on the map despawns constantly — OR the scene
			// refused this despawn because an item of that id is still on the tile (round 8).
			return;
		}
		synchronized (groundDrops)
		{
			groundDrops.remove(g);
		}
		int now = client.getTickCount();
		// Do NOT conclude yet if a local Take for this item is still in flight: the inventory gain
		// that proves it arrives AFTER this callback. Hold the removal for a bounded window and let
		// whatever actually resolves decide the cause. Piles with no pending Take finalize
		// immediately, so ordinary despawns are not delayed at all.
		if (!g.selfPickedUp && hasArmedPickupFor(g))
		{
			g.takeArmed = true;
			// Recorded HERE, at removal time, and never re-derived at settlement: the Take that
			// justified parking this pile can expire or be pruned before the row is published.
			g.parkedForLiveTake = true;
			synchronized (pendingRemovals)
			{
				pendingRemovals.put(g, now);
			}
			return;
		}
		emitGroundRemoval(g, now, groundObservationUnreliable);
	}

	/**
	 * Classify a menu click as an off-book value action: "drop" (inventory Drop), "pickup" (ground Take), or
	 * "alch_high"/"alch_low" (High/Low Level Alchemy cast). Alch menu shape varies — option "Cast High Level
	 * Alchemy", or option "Cast" with the spell name in the target — so it matches the spell name across the
	 * combined option+target text. Returns null for anything else. Static + pure (no client) so it is unit-testable.
	 */
	static String offBookMenuAction(MenuOptionClicked event)
	{
		String opt = event.getMenuOption() == null ? "" : event.getMenuOption();
		if (opt.equals("Drop"))
		{
			return "drop";
		}
		if (opt.equals("Take"))
		{
			return "pickup";
		}
		if (opt.startsWith("Cast"))
		{
			String combined = opt + " " + (event.getMenuTarget() == null ? "" : event.getMenuTarget());
			if (combined.contains("High Level Alchemy"))
			{
				return "alch_high";
			}
			if (combined.contains("Low Level Alchemy"))
			{
				return "alch_low";
			}
		}
		return null;
	}

	/**
	 * Resolve an armed pickup/alch pending against the post-change inventory. pickup fires when the item count
	 * ROSE (an inventory removal can't fake a rise); alch fires only when the item fell AND coins rose in the
	 * same change (an alch always yields coins — an item removal without a coin gain is an equip/bank/destroy,
	 * never an alch). "drop" pendings NEVER resolve here: an inventory removal alone could be a bank deposit /
	 * destroy / equip, so drops resolve exclusively via ground-spawn confirmation
	 * (resolveDropPendingOnGroundSpawn) — this path only expires a stale drop pending. Unrelated inventory
	 * changes are ignored and the pending kept until it lands or its tick window expires (so a "Take" that
	 * completes after a short walk still logs). Package-private + primitive args so it is unit-testable.
	 */
	void resolveInvDeltaPending(long itemCountAfter, long coinsAfter, int currentTick)
	{
		// Head-only entry point, kept for the single-pending call shape. The live client path uses
		// resolveInvDeltaPendings(ItemContainer, ...) instead, which walks EVERY armed pending: with a queue,
		// an unconfirmed drop sits at the head and would otherwise hide a later pickup behind it forever.
		InvDeltaPending p = peekInvDeltaPending();
		if (p == null)
		{
			return;
		}
		resolveOnePending(p, itemCountAfter, coinsAfter, currentTick,
			java.util.Collections.singletonList(p));
	}

	/**
	 * Resolve EVERY armed pending against one inventory change, each against ITS OWN item count.
	 *
	 * A single pending slot could rely on the head being the only candidate. A queue cannot: a Drop whose
	 * ground spawn never arrives stays armed for DROP_PENDING_MAX_TICKS, and while it sits at the head a
	 * real "Take" armed behind it resolves against nothing and is lost. The pre-queue build hid this because
	 * a fresh click overwrote the stale pending. Walking the queue is what keeps a self-pickup observable.
	 */
	void resolveInvDeltaPendings(ItemContainer inv, long coinsAfter, int currentTick)
	{
		List<InvDeltaPending> snapshot;
		synchronized (invDeltaPendings)
		{
			if (invDeltaPendings.isEmpty())
			{
				return;
			}
			snapshot = new ArrayList<>(invDeltaPendings);
		}
		// ONE unit of evidence resolves ONE pending. Every pending is measured against the same
		// container, so two Takes of the same item armed on the same tick share a `beforeCount` and a
		// SINGLE arriving item satisfied both: two `pickup` rows and doubled quantities for one real
		// recovery. A resolved pickup therefore raises every other still-armed pickup's baseline by
		// what it consumed.
		for (InvDeltaPending p : snapshot)
		{
			long used = resolveOnePending(p, countItem(inv, p.item), coinsAfter, currentTick, snapshot);
			if (used > 0 && "pickup".equals(p.base))
			{
				for (InvDeltaPending other : snapshot)
				{
					if (other != p && "pickup".equals(other.base) && other.item == p.item)
					{
						other.beforeCount += used;
					}
				}
			}
		}
	}

	/**
	 * Resolve exactly ONE armed pending. Removes it only when its own signals landed or its window
	 * expired. Returns the quantity it CONSUMED, so the caller can withhold that evidence from the
	 * remaining pendings; 0 when nothing landed.
	 */
	private long resolveOnePending(InvDeltaPending p, long itemCountAfter, long coinsAfter,
		int currentTick, List<InvDeltaPending> snapshot)
	{
		// Resolution removes ONE pending — the one whose signals landed. The lifecycle disarms (logout, hop,
		// disconnect, death) are the only places that clear them all; a single item's loss must never discard
		// another item's armed drop, which is the whole point of the queue.
		if ("drop".equals(p.base))
		{
			// Order-independence: if a matching ground spawn already corroborated (spawn processed before the
			// inventory decrement) and the loss now lands within the corroboration window, complete the emit.
			long dropDelta = p.beforeCount - itemCountAfter;
			int spawnTick = p.spawnCorroboratedTick;
			if (dropDelta > 0 && spawnTick >= 0 && currentTick - spawnTick <= DROP_CORROBORATION_MAX_TICKS)
			{
				removeInvDeltaPending(p);
				emitDropEvent(p, dropDelta);
				return dropDelta;
			}
			if (currentTick - p.tick > DROP_PENDING_MAX_TICKS)
			{
				removeInvDeltaPending(p);	// no ground spawn confirmed it — expire silently, emit nothing
			}
			return 0;
		}
		if ("pickup".equals(p.base) && currentTick - p.tick > INV_DELTA_PENDING_MAX_TICKS)
		{
			// A Take past its OWN window is no longer evidence about anything, whether or not an
			// item arrived. The window used to be checked only when nothing landed, so a Take
			// clicked but never served stayed armed forever and the next same-item gain revived it:
			// a customer's collection published as ours, and a `pickup` row for a recovery that
			// never happened. Expire it silently and emit nothing.
			removeInvDeltaPending(p);
			return 0;
		}
		long delta = "pickup".equals(p.base) ? (itemCountAfter - p.beforeCount) : (p.beforeCount - itemCountAfter);
		long gp = coinsAfter - p.beforeCoins;
		boolean landed = delta > 0 && (!"alch".equals(p.base) || gp > 0);
		if (!landed)
		{
			int window = "pickup".equals(p.base) ? INV_DELTA_PENDING_MAX_TICKS : ALCH_PENDING_MAX_TICKS;
			if (currentTick - p.tick > window)
			{
				removeInvDeltaPending(p);
			}
			return 0;
		}
		removeInvDeltaPending(p);	// consume — the expected delta landed
		if (!activityLogActive())
		{
			return delta;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("item", p.item);
		fields.put("qty", delta);
		if ("alch".equals(p.base))
		{
			fields.put("spell", p.spell);
			fields.put("gp", gp);	// always present now — the coin gain is the alch confirmation itself
			emitEvent("alch", fields);
			return delta;
		}
		if (p.location != null)
		{
			fields.put("location", p.location);
		}
		// If this recovers a pile WE dropped, say so on the pile: its later removal is then explained
		// as self_pickup rather than counted as an ambiguous early removal. The rule is deliberately
		// strict — see markGroundDropSelfPickedUp. Anything it refuses leaves the pile `unknown`.
		if (markGroundDropSelfPickedUp(p, delta, snapshot, currentTick))
		{
			// The pile may already be waiting on this answer; publish now rather than idle out the
			// window. settlePendingRemovals removes the entry as it fires, so this cannot double-emit.
			settlePendingRemovals(currentTick, false, false);
		}
		emitEvent("pickup", fields);	// only pickup reaches here — drop is spawn-confirmed, alch returned above
		return delta;
	}

	/** Own-account death (item-loss context). ActorDeath fires for any nearby actor, so filter to self. */
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() != null && event.getActor() == client.getLocalPlayer())
		{
			// A death spawns the player's items on the ground at their tile WITH an inventory loss — exactly the
			// drop pending's dual confirmation signal. Kill any armed drop/pickup/alch pending: the loss belongs
			// to the death event's items_lost, never to a fabricated player-initiated drop.
			clearInvDeltaPendings();
			// Capture the pre-death inventory + equipment LIVE now: OSRS removes items a few ticks AFTER the death
			// animation, so the containers are still intact at ActorDeath ([verify in-client] — the standard
			// RuneLite death-tracking timing assumption). Defer the emit; items_lost is the pre/post diff resolved
			// once the containers settle (at syncTask, DEATH_SETTLE_TICKS later) or on logout — whichever first.
			deathPending = new DeathPending(currentLocation(), deathKind(),
				mergedInvEquipCounts(), client == null ? 0 : client.getTickCount());
		}
	}

	/**
	 * death_kind: "wilderness" (inside the Wilderness — items drop to the killer), "pvp" (a PvP / DMM / high-risk
	 * world), or "safe" (PvM / minigame — a gp-sink item-retrieval reclaim, NOT a transfer). Only wilderness/pvp is
	 * an inter-account transfer. IN_WILDERNESS is the standard client wilderness signal.
	 */
	private String deathKind()
	{
		if (client == null)
		{
			return "safe";
		}
		if (client.getVarbitValue(Varbits.IN_WILDERNESS) > 0)
		{
			return "wilderness";
		}
		java.util.Set<WorldType> wt = client.getWorldType();
		if (wt != null && (wt.contains(WorldType.PVP) || wt.contains(WorldType.DEADMAN) || wt.contains(WorldType.HIGH_RISK)))
		{
			return "pvp";
		}
		return "safe";
	}

	/** Merged inventory + equipment item -> total qty, read live. Null-safe (empty when a container is absent). */
	private Map<Integer, Long> mergedInvEquipCounts()
	{
		Map<Integer, Long> counts = new LinkedHashMap<>();
		if (client == null)
		{
			return counts;
		}
		addContainerCounts(counts, client.getItemContainer(InventoryID.INVENTORY));
		addContainerCounts(counts, client.getItemContainer(InventoryID.EQUIPMENT));
		return counts;
	}

	private void addContainerCounts(Map<Integer, Long> counts, ItemContainer c)
	{
		if (c == null)
		{
			return;
		}
		for (Item item : c.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				counts.merge(item.getId(), (long) item.getQuantity(), Long::sum);
			}
		}
	}

	/** Resolve a deferred death from the settle-window path: containers are live AND settled, so the loss diff
	 * is trustworthy (computeLoss=true). The logout/disconnect path calls resolveDeathPending(null, false). */
	private void resolveDeathPendingViaLiveContainers()
	{
		if (deathPending != null)
		{
			resolveDeathPending(mergedInvEquipCounts(), true);
		}
	}

	/**
	 * Emit the deferred death event: {location?, death_kind, items_lost[]?}. death + kind + location are always
	 * recorded. items_lost is emitted ONLY when it can be trusted: (a) computeLoss — the settle-window path where
	 * the containers are live AND settled (the logout/disconnect path passes false, where the containers are null
	 * or not-yet-settled and a diff would over- or under-report), AND (b) the death is a real inter-account
	 * transfer (wilderness/pvp). A safe death is a gp-sink reclaim, not a transfer, and its settle-window diff is
	 * polluted by post-death eating/drinking — so it never carries items_lost. A wrong items_lost on a monitoring
	 * feed is worse than an absent one; the server wealth-reconciliation still catches the delta either way.
	 * Package-private + map arg so it is unit-testable without a client.
	 */
	void resolveDeathPending(Map<Integer, Long> postCounts, boolean computeLoss)
	{
		DeathPending p = deathPending;
		if (p == null)
		{
			return;
		}
		deathPending = null;
		Map<String, Object> fields = new LinkedHashMap<>();
		if (p.location != null)
		{
			fields.put("location", p.location);
		}
		fields.put("death_kind", p.kind);
		if (computeLoss && ("wilderness".equals(p.kind) || "pvp".equals(p.kind)))
		{
			List<Map<String, Object>> lost = new ArrayList<>();
			for (Map.Entry<Integer, Long> e : p.preCounts.entrySet())
			{
				long before = e.getValue();
				long after = postCounts == null ? 0L : postCounts.getOrDefault(e.getKey(), 0L);
				long d = before - after;
				if (d > 0)
				{
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", e.getKey());
					m.put("qty", d);
					lost.add(m);
				}
			}
			fields.put("items_lost", lost);
		}
		emitEvent("death", fields);
	}

	/** Level-ups: StatChanged fires on every xp drop, so emit only when the real level increases. */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == null)
		{
			return;
		}
		String skill = event.getSkill().getName();
		int level = event.getLevel();
		Integer prev = lastSkillLevel.put(skill, level);
		if (prev != null && level > prev && activityLogActive())
		{
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("skill", skill);
			fields.put("level", level);
			if (client != null)
			{
				fields.put("xp", client.getSkillExperience(event.getSkill()));	// WAVE 3: total xp at level-up
			}
			emitEvent("level_up", fields);
		}
		// XP gain (coalesced): accumulate the per-skill delta from the event's total xp. The tick timer
		// flushes it as one xp_gain event. First observation per skill only baselines (prevXp null).
		int nowXp = event.getXp();
		Integer prevXp = lastSkillXp.put(skill, nowXp);
		if (prevXp != null && nowXp > prevXp)
		{
			xpAccum.merge(skill, (long) (nowXp - prevXp), Long::sum);
		}
	}

	/** Emit one coalesced xp_gain event for all per-skill xp accumulated since the last flush. */
	void flushXpGain()
	{
		if (xpAccum.isEmpty() || !activityLogActive())
		{
			return;
		}
		List<Map<String, Object>> gains = new ArrayList<>();
		long total = 0;
		for (Map.Entry<String, Long> e : xpAccum.entrySet())
		{
			Map<String, Object> g = new LinkedHashMap<>();
			g.put("skill", e.getKey());
			g.put("xp", e.getValue());
			gains.add(g);
			total += e.getValue();
		}
		xpAccum.clear();
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("gains", gains);
		fields.put("total", total);
		emitEvent("xp_gain", fields);
	}

	/** Emit region {from,to,x,y} when the map region changes. The first observation only baselines. */
	void checkRegionChange()
	{
		if (client == null)
		{
			return;
		}
		Player self = client.getLocalPlayer();
		WorldPoint wp = self == null ? null : self.getWorldLocation();
		if (wp == null)
		{
			return;
		}
		int region = wp.getRegionID();
		Integer prev = lastRegion;
		lastRegion = region;
		if (prev != null && prev != region)
		{
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("from", prev);
			fields.put("to", region);
			fields.put("x", wp.getX());
			fields.put("y", wp.getY());
			emitEvent("region", fields);
		}
	}

	/**
	 * GE offer lifecycle, own account only, one row per real state step:
	 * ge_offer (a new offer placed), ge_progress (a partial fill, at most one per slot per minute),
	 * ge_buy / ge_sell / ge_cancel (terminal, as before) and ge_collect (the slot emptied, with what it held).
	 * The client replays every slot right after login; that replay only sets the baseline, so an old
	 * uncollected offer is never reported again as new activity.
	 */
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		GrandExchangeOffer offer = event.getOffer();
		if (offer == null)
		{
			return;
		}
		int slot = event.getSlot();
		GrandExchangeOfferState state = offer.getState();
		// The client clears every slot to EMPTY while hopping, logging in and at the login screen. That
		// clear is not a collect and must not overwrite what the slot held (the RuneLite GE plugin skips
		// it the same way). Only an EMPTY seen while logged in is a real collect.
		if (state == GrandExchangeOfferState.EMPTY && client != null
			&& client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		int tick = client == null ? 0 : client.getTickCount();
		long acct = client == null ? -1L : client.getAccountHash();
		if (acct != geAccountHash)
		{
			// GE slots belong to one account. Another account's remembered slot must never make this one's
			// replay look like an offer that finished while away.
			lastGeState.clear();
			lastGeOffer.clear();
			lastGeProgressTick.clear();
			geAccountHash = acct;
		}
		boolean loginReplay = client != null && tick - geLoginTick <= GE_LOGIN_BURST_TICKS;
		GrandExchangeOfferState prev = lastGeState.put(slot, state);
		long[] prevOffer = lastGeOffer.get(slot);
		long[] nowOffer = {offer.getItemId(), offer.getQuantitySold(), offer.getTotalQuantity(),
			offer.getPrice(), offer.getSpent()};
		if (state == GrandExchangeOfferState.EMPTY)
		{
			lastGeOffer.remove(slot);
			lastGeProgressTick.remove(slot);
		}
		else
		{
			lastGeOffer.put(slot, nowOffer);
		}
		if (state == null)
		{
			return;
		}
		if (loginReplay)
		{
			// Baseline only, with one exception: an offer we saw active before the hop/relog that finished
			// while we were away (same item, now BOUGHT/SOLD/CANCELLED) is a real completion and still reported.
			boolean finishedWhileAway = prevOffer != null && prevOffer[0] == nowOffer[0]
				&& (prev == GrandExchangeOfferState.BUYING || prev == GrandExchangeOfferState.SELLING)
				&& state != GrandExchangeOfferState.BUYING && state != GrandExchangeOfferState.SELLING
				&& state != GrandExchangeOfferState.EMPTY;
			if (!finishedWhileAway)
			{
				return;
			}
		}
		String type = null;
		switch (state)
		{
			case BOUGHT:
				type = state == prev ? null : "ge_buy";
				break;
			case SOLD:
				type = state == prev ? null : "ge_sell";
				break;
			case CANCELLED_BUY:
			case CANCELLED_SELL:
				type = state == prev ? null : "ge_cancel";
				break;
			case BUYING:
			case SELLING:
				if (prev != state && (prev == GrandExchangeOfferState.EMPTY || (prev == null && nowOffer[1] == 0)
					|| prev == GrandExchangeOfferState.BOUGHT || prev == GrandExchangeOfferState.SOLD
					|| prev == GrandExchangeOfferState.CANCELLED_BUY || prev == GrandExchangeOfferState.CANCELLED_SELL))
				{
					// EMPTY -> an active offer: just placed. A finished slot that turns active again was
					// collected unseen and re-used, so this is a new offer too.
					type = "ge_offer";
					lastGeProgressTick.put(slot, tick);
				}
				else if (prev != state)
				{
					// An offer first seen part-filled (the plugin started mid-offer): report the fill, not a new offer.
					type = "ge_progress";
					lastGeProgressTick.put(slot, tick);
				}
				else if (prevOffer != null && nowOffer[1] > prevOffer[1])
				{
					Integer last = lastGeProgressTick.get(slot);
					if (last == null || tick - last >= GE_PROGRESS_MIN_TICKS || tick < last)
					{
						type = "ge_progress";
						lastGeProgressTick.put(slot, tick);
					}
					else
					{
						// Rate-limited: keep the older fill as the baseline so the next row still shows the change.
						lastGeOffer.put(slot, prevOffer);
					}
				}
				break;
			case EMPTY:
				if (prev != null && prev != GrandExchangeOfferState.EMPTY && prevOffer != null)
				{
					type = "ge_collect";	// the slot was cleared: the items or coins were collected
				}
				break;
			default:
				break;
		}
		if (type == null)
		{
			return;
		}
		long[] o = "ge_collect".equals(type) ? prevOffer : nowOffer;
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("item", (int) o[0]);
		fields.put("qty", (int) o[1]);
		fields.put("price", o[3]);
		fields.put("gp", o[4]);
		fields.put("qty_total", (int) o[2]);
		fields.put("slot", slot);
		fields.put("state", "ge_collect".equals(type) ? String.valueOf(prev) : state.name());
		fields.put("side", geSide("ge_collect".equals(type) ? prev : state));
		emitEvent(type, fields);
	}

	/** "buy" or "sell" for an offer state; null for EMPTY. */
	static String geSide(GrandExchangeOfferState s)
	{
		if (s == null)
		{
			return null;
		}
		switch (s)
		{
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				return "buy";
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				return "sell";
			default:
				return null;
		}
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
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		NPCComposition comp = event.getComposition();
		emitLoot(comp == null ? null : comp.getName(), "npc", event.getItems());
	}

	/**
	 * WAVE 4 — PvP player loot (e.g. a Wilderness kill). Capture the ITEMS only. The victim's name is available
	 * (event.getPlayer().getName()) but is deliberately NOT sent: forwarding a killed player's name on the
	 * public path is a compliance decision for the product owner, not an auto-send.
	 * TODO: decide whether to attach the victim RSN as loot source_name — left OUT until then.
	 * javap-verified vs client 1.12.32 (PlayerLootReceived.getItems).
	 */
	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
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
			items.add(itemMap(st.getId(), st.getQuantity()));
			if (itemManager != null)
			{
				value += (long) itemManager.getItemPrice(st.getId()) * st.getQuantity();
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
		fields.put("items", items);
		fields.put("value", value);
		emitEvent("loot", fields);
	}

	void handleTradeChat(String message)
	{
		if (tradeScreenshotsDisabled())
		{
			return;
		}
		String text = Text.removeTags(message == null ? "" : message).trim();
		if (TRADE_ACCEPTED_MESSAGE.equalsIgnoreCase(text))
		{
			BufferedImage frame = pendingTradeFrame.getAndSet(null);
			resetTradeState();
			if (frame != null)
			{
				submitTradeScreenshotUpload(frame, "confirm");
			}
			// Second proof frame: the trade just completed and the window closed, so the NEXT rendered
			// frame shows the "Accepted trade." chat confirmation. Delivery proof needs BOTH — the
			// confirm screen (items + partner) and the completion frame (proof it actually went through).
			// Only capture when a valid token is configured: nothing to upload otherwise, and this also
			// keeps the no-token path from touching drawManager.
			String completedToken = config.linkToken() == null ? "" : config.linkToken().trim();
			if (completedToken.matches("^[a-f0-9]{32}$"))
			{
				drawManager.requestNextFrameListener(image ->
				{
					if (screenshotsEnabled())	// re-check: the gate may close before the frame lands
					{
						submitTradeScreenshotUpload(toBufferedImage(image), "completed");
					}
				});
			}
		}
		else if (TRADE_DECLINED_MESSAGE.equalsIgnoreCase(text))
		{
			resetTradeState();
		}
	}

	/**
	 * The whole feature is gated on screenshotsEnabled(): no link token (or a server force-disable)
	 * means no arming, no frame request, no buffer, no upload. Also drops any frame buffered before the
	 * token was cleared or the server disabled it mid-trade.
	 */
	private boolean tradeScreenshotsDisabled()
	{
		if (screenshotsEnabled())
		{
			return false;
		}
		if (tradeActive || tradeArmed || pendingTradeFrame.get() != null)
		{
			resetTradeState();
		}
		return true;
	}

	private void resetTradeState()
	{
		tradeActive = false;
		tradeArmed = false;
		tradeMainOpen = false;
		pendingTradeFrame.set(null);
		pendingTradeGiven = null;	// activity-log trade capture — drop on decline/abandon/hop so it never leaks
		pendingCounterparty = null;
		pendingTradeReceived = null;	// WAVE 1b: received side — drop with the rest so it never leaks across trades
		pendingReceivedText = null;
	}

	// ---- trade screenshot: capture + upload ----

	/** Safe Image -> BufferedImage copy (the frame-listener contract only guarantees Image). */
	static BufferedImage toBufferedImage(java.awt.Image img)
	{
		if (img instanceof BufferedImage)
		{
			return (BufferedImage) img;
		}
		BufferedImage bi = new BufferedImage(
			img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = bi.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return bi;
	}

	/** In-memory encode via JDK ImageIO (same encoder core ImageCapture uses). fmt e.g. "png". Null on failure. */
	static byte[] encodeImage(BufferedImage image, String fmt)
	{
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		try
		{
			javax.imageio.ImageIO.write(image, fmt, buf);
		}
		catch (IOException e)
		{
			log.debug("OSRS BiS image encode ({}) failed", fmt, e);
			return null;
		}
		return buf.toByteArray();
	}

	/** In-memory PNG encode (trade path). Delegates to the generalized encoder. */
	static byte[] encodePng(BufferedImage image)
	{
		return encodeImage(image, "png");
	}

	/**
	 * In-memory JPEG encode at an explicit quality (store delivery-proof frames). The default ImageIO.write
	 * path can't set quality, so drive the writer directly with ImageWriteParam. Input must be TYPE_INT_RGB
	 * (the JDK JPEG writer corrupts ARGB rasters). Null on failure.
	 */
	static byte[] encodeJpeg(BufferedImage image)
	{
		javax.imageio.ImageWriter writer = null;
		try (java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(buf))
		{
			writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
			writer.setOutput(ios);
			javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
			// 0.55, lowered from 0.70 on 2026-09-02 to fit 360 frames (30fps x 12s) in the then 12MB burst
			// cap: 30KB/frame at 704px vs 45KB at 768px/0.70. Legibility was re-checked at this setting,
			// not assumed — a clip nobody can read is not evidence however smooth it is.
			param.setCompressionQuality(0.55f);
			writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
			ios.flush();
			return buf.toByteArray();
		}
		catch (Exception e)
		{
			log.debug("OSRS BiS store-clip JPEG encode failed", e);
			return null;
		}
		finally
		{
			if (writer != null)
			{
				writer.dispose();
			}
		}
	}

	/**
	 * PNG-encoding a full frame can hitch the render loop, so encode + upload run on the injected
	 * background executor; the OkHttp call itself is async (enqueue), same as postSnapshot.
	 */
	private void submitTradeScreenshotUpload(BufferedImage frame, String phase)
	{
		if (!uploadAllowed())
		{
			return;		// upload switch off — send nothing
		}
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return;	// same guard as syncTask: no / malformed token configured yet — never POST
		}
		executor.submit(() ->
		{
			byte[] bytes = encodePng(frame);
			if (bytes == null || bytes.length == 0)
			{
				return;
			}
			if (bytes.length > MAX_SCREENSHOT_UPLOAD_BYTES)
			{
				log.debug("OSRS BiS trade screenshot dropped: {} bytes exceeds {} byte cap",
					bytes.length, MAX_SCREENSHOT_UPLOAD_BYTES);
				return;
			}
			uploadTradeScreenshot(token, bytes, phase);
		});
	}

	private void uploadTradeScreenshot(String token, byte[] pngBytes, String phase)
	{
		if (!uploadAllowed())
		{
			return;		// upload switch off — send nothing
		}
		long capturedAt = System.currentTimeMillis() / 1000L;
		RequestBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("token", token)
			.addFormDataPart("kind", "trade")
			.addFormDataPart("phase", phase)	// "confirm" = trade window; "completed" = post-accept chat frame
			.addFormDataPart("captured_at", Long.toString(capturedAt))
			.addFormDataPart("file", "trade-" + phase + "-" + capturedAt + ".png", RequestBody.create(PNG, pngBytes))
			.build();

		String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
		Request request = new Request.Builder()
			.url(base + "/screenshot-ingest")
			.post(body)
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS BiS trade screenshot upload failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					log.debug("OSRS BiS trade screenshot upload response: {}", response.code());
				}
				finally
				{
					response.close();
				}
			}
		});
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
		long geEscrowGp = 0L;	// wealth locked in GE offers (see the per-offer accumulation below)
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
					if (remaining > 0 && itemManager != null)
					{
						geEscrowGp += remaining * itemManager.getItemPrice(o.getItemId());
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
		Map<String, Object> bankBlock = new LinkedHashMap<>();
		bankBlock.put("items", bankSynced ? itemList(bank) : new ArrayList<>());
		bankBlock.put("value_gp", bankSynced ? containerValue(bank) : 0L);
		snap.put("bank", bankBlock);

		// ---- off-book / niche containers (verified gameval ids; legacy enum for GIM shared storage). Included
		// only when non-null = opened at least once this session, so a normal snapshot never carries empty blocks.
		// group_storage is a GENUINE cross-account container (shared between Group-Ironman members) — disclosed. ----
		addContainerSnapshot(snap, "looting_bag", client.getItemContainer(LOOTING_BAG_CONTAINER_ID));
		addContainerSnapshot(snap, "seed_vault", client.getItemContainer(SEED_VAULT_CONTAINER_ID));
		addContainerSnapshot(snap, "group_storage", client.getItemContainer(InventoryID.GROUP_STORAGE));
		// Storage-visibility (gap audit 2026-07-18): real wealth a bank+inv+equip snapshot can't see — a bond
		// is ~30M+ parked invisibly; quiver ammo can be a large stacked value. False-residual sources closed.
		addContainerSnapshot(snap, "bonds_pouch", client.getItemContainer(BONDS_POUCH_CONTAINER_ID));
		addContainerSnapshot(snap, "bonds_escrow", client.getItemContainer(BONDS_ESCROW_CONTAINER_ID));
		addContainerSnapshot(snap, "quiver_ammo", client.getItemContainer(QUIVER_AMMO_CONTAINER_ID));

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
		wealth.put("ge_escrow_gp", geEscrowGp);       // wealth locked in GE offers — invisible to bank+inv+equip
		wealth.put("net_worth_gp", bankSynced ? (invValue + eqpValue + bankValue + geEscrowGp) : null); // null until bank opened
		snap.put("wealth", wealth);

		// ---- WAVE 5: live state (all ids javap-verified vs runelite-api 1.12.32; raw values, decoded server-side) ----
		snap.put("spellbook", client.getVarbitValue(Varbits.SPELLBOOK));          // 0 standard / 1 ancient / 2 lunar / 3 arceuus
		snap.put("attack_style", client.getVarpValue(VarPlayer.ATTACK_STYLE));    // 0..3 = the selected style slot
		snap.put("world", client.getWorld());
		snap.put("location", currentLocation());                                  // {region_id, plane} or null if unreadable

		List<String> activePrayers = new ArrayList<>();
		for (Prayer prayer : Prayer.values())
		{
			if (client.isPrayerActive(prayer))
			{
				activePrayers.add(prayer.name());
			}
		}
		snap.put("prayer_active", activePrayers);

		// Kourend house favour — 5 raw varbits (server divides by 10 for the %). Spelled FAVOR in the api enum.
		Map<String, Object> favour = new LinkedHashMap<>();
		favour.put("arceuus", client.getVarbitValue(Varbits.KOUREND_FAVOR_ARCEUUS));
		favour.put("hosidius", client.getVarbitValue(Varbits.KOUREND_FAVOR_HOSIDIUS));
		favour.put("lovakengj", client.getVarbitValue(Varbits.KOUREND_FAVOR_LOVAKENGJ));
		favour.put("piscarilius", client.getVarbitValue(Varbits.KOUREND_FAVOR_PISCARILIUS));
		favour.put("shayzien", client.getVarbitValue(Varbits.KOUREND_FAVOR_SHAYZIEN));
		snap.put("kourend_favour", favour);

		// Minigame points. NMZ + Tithe have clean point varbits; LMS has none in this jar (only IN_LMS state) so
		// LMS points are omitted rather than invented.
		Map<String, Object> minigames = new LinkedHashMap<>();
		minigames.put("nmz", client.getVarbitValue(Varbits.NMZ_POINTS));
		minigames.put("tithe", client.getVarbitValue(Varbits.TITHE_FARM_POINTS));
		snap.put("minigame_points", minigames);

		return snap;
	}

	/** {region_id, plane} of the local player's world location, or null if it isn't currently readable. */
	private Map<String, Object> currentLocation()
	{
		net.runelite.api.Player p = client.getLocalPlayer();
		if (p == null)
		{
			return null;
		}
		net.runelite.api.coords.WorldPoint wp = p.getWorldLocation();
		if (wp == null)
		{
			return null;
		}
		Map<String, Object> loc = new LinkedHashMap<>();
		loc.put("region_id", wp.getRegionID());
		loc.put("plane", wp.getPlane());
		return loc;
	}

	// ---- container helpers ----

	private Map<String, Object> itemMap(int id, int qty)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("qty", qty);
		return m;
	}

	/** itemMap variant for quantities that can exceed int range (bank diffs, large coin/token stacks). */
	private Map<String, Object> itemMapLong(int id, long qty)
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

	/** Add a {items, value_gp} block for a container, but only when it is present (opened this session). */
	void addContainerSnapshot(Map<String, Object> snap, String key, ItemContainer c)
	{
		if (c == null)
		{
			return;
		}
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("items", itemList(c));
		block.put("value_gp", containerValue(c));
		snap.put(key, block);
	}

	/**
	 * Async snapshot POST. The response drives the upload gate's memory: a 2xx accept records the
	 * uploaded hash (so unchanged ticks stop re-sending) and clears any backoff; a 429 arms the
	 * backoff window from Retry-After (or exponential). hash is the CANONICAL hash of this snapshot
	 * — recorded on accept, never the raw JSON hash.
	 */
	private void postSnapshot(String token, Map<String, Object> snapshot, String hash)
	{
		if (!uploadAllowed())
		{
			return;		// upload switch off — send nothing
		}
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
					int code = response.code();
					applyServerPolicy(response);	// server dictates cadence / screenshot-disable per token
					if (response.isSuccessful())
					{
						onUploadAccepted(hash);
					}
					else if (code == 429)
					{
						applyRateLimitBackoff(response.header("Retry-After"));
					}
					// Other non-2xx (transient 5xx, auth): no bookkeeping — the unchanged hash gate
					// leaves the snapshot pending and a later tick retries it, no backoff imposed.
					log.debug("OSRS BiS sync response: {}", code);
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	/** A 2xx accept: this canonical hash is now the server's known state, and any 429 backoff clears. */
	private void onUploadAccepted(String hash)
	{
		lastUploadedHash = hash;
		backoffUntilMillis = 0L;
		nextBackoffMillis = BACKOFF_START_MILLIS;
	}

	/**
	 * Apply the per-token policy osrsbestinslot.com returns on the ingest response. All directives are
	 * "narrow/control" only (Hub-safe): the server can set the sync cadence, force trade screenshots
	 * OFF, and soft-pause a token — it can never force screenshots ON or expand what's collected.
	 *   X-Sync-Interval    seconds, clamped [5, 600] — the minimum gap between sends.
	 *   X-Screenshots      "off"/"disabled"/"false" force-disables trade-screenshot capture.
	 *   X-Clips            "off"/"disabled"/"false" force-disables store delivery-clip capture.
	 *   X-Store-Tools      "on"/"enabled"/"true"/"1" grants the shop overlays for this token. The
	 *                      overlays draw only and send nothing, so the grant expands no collection.
	 *                      It does NOT grant drop-trade recording; that is X-Drop-Proof below, and
	 *                      keeping them apart is the whole reason that header exists.
	 *   X-Drop-Proof       "on"/"enabled"/"true"/"1" ROLLS OUT drop-trade screen recording to this
	 *                      token. Absent, false or unparseable = OFF, and a response that never
	 *                      arrives leaves it OFF, so an unknown state never records. It is a
	 *                      rollout control, not an authorization: the server still decides on its
	 *                      own staff check whether to keep a drop_frames upload.
	 *   X-Uploads-Enabled  "false"/"0" soft-pauses the token: cadence drops to the 600s max so the
	 *                      policy channel stays open (the hard data-stop is enforced server-side by
	 *                      dropping the token's snapshots — the client never goes dark, so it can be
	 *                      re-enabled on the next poll). Any header absent = that directive unchanged.
	 */
	void applyServerPolicy(Response response)
	{
		String screenshots = response.header("X-Screenshots");
		if (screenshots != null)
		{
			String v = screenshots.trim().toLowerCase(java.util.Locale.ROOT);
			serverScreenshotsDisabled = "off".equals(v) || "disabled".equals(v) || "false".equals(v);
		}

		String storeTools = response.header("X-Store-Tools");
		if (storeTools != null)
		{
			String v = storeTools.trim().toLowerCase(java.util.Locale.ROOT);
			boolean on = "on".equals(v) || "enabled".equals(v) || "true".equals(v) || "1".equals(v);
			serverStoreToolsEnabled = on;
			if (!on)
			{
				removeResetTimer();	// a revoked grant must clear what is already on screen
			}
		}

		String maxCap = response.header("X-Max-Capture");
		if (maxCap != null)
		{
			String v = maxCap.trim().toLowerCase(java.util.Locale.ROOT);
			boolean on = "on".equals(v) || "enabled".equals(v) || "true".equals(v) || "1".equals(v);
			serverMaxCaptureEnabled = on;
			if (!on)
			{
				clearFirehose();	// a revoked grant drops whatever is still buffered
			}
		}

		String clips = response.header("X-Clips");
		if (clips != null)
		{
			String v = clips.trim().toLowerCase(java.util.Locale.ROOT);
			serverClipsDisabled = "off".equals(v) || "disabled".equals(v) || "false".equals(v);
		}

		// THE ROLLOUT FLAG. Absent header leaves it unchanged, exactly like every other directive,
		// because a single malformed response must not silently revoke a live rollout. It starts
		// false and only an explicit on-value ever sets it, so the fail-closed default holds.
		String dropProof = response.header("X-Drop-Proof");
		if (dropProof != null)
		{
			String v = dropProof.trim().toLowerCase(java.util.Locale.ROOT);
			serverDropProofEnabled = "on".equals(v) || "enabled".equals(v)
				|| "true".equals(v) || "1".equals(v);
		}
		// A capability that has just become reachable owes the user the notice, and a capability
		// that has just gone away must not leave a recorder running.
		onDropProofCapabilityChanged();

		boolean paused = false;
		String uploadsEnabled = response.header("X-Uploads-Enabled");
		if (uploadsEnabled != null)
		{
			String v = uploadsEnabled.trim().toLowerCase(java.util.Locale.ROOT);
			paused = "false".equals(v) || "0".equals(v) || "off".equals(v);
		}

		String interval = response.header("X-Sync-Interval");
		if (paused)
		{
			minUploadIntervalMillis = 600_000L;	// soft-pause: max cadence, still polls for re-enable
		}
		else if (interval != null)
		{
			try
			{
				long s = Long.parseLong(interval.trim());
				minUploadIntervalMillis = Math.max(5L, Math.min(s, 600L)) * 1000L;
			}
			catch (NumberFormatException ignored)
			{
				// malformed directive — keep the current cadence
			}
		}
	}

	/**
	 * 429 rate-limited: honor Retry-After (seconds) when present, capped at 1h; otherwise exponential
	 * backoff from 60s, doubling to a 15m cap. While backed off, ticks build + track the snapshot but
	 * never send (see syncTask); the next accepted upload resets the schedule.
	 */
	private void applyRateLimitBackoff(String retryAfterHeader)
	{
		long now = System.currentTimeMillis();
		Long retryAfterSeconds = parseRetryAfterSeconds(retryAfterHeader);
		if (retryAfterSeconds != null)
		{
			long capped = Math.min(retryAfterSeconds, RETRY_AFTER_CAP_SECONDS);
			backoffUntilMillis = now + capped * 1000L;
			return;
		}
		long wait = Math.min(nextBackoffMillis, BACKOFF_CAP_MILLIS);
		backoffUntilMillis = now + wait;
		nextBackoffMillis = Math.min(nextBackoffMillis * 2, BACKOFF_CAP_MILLIS);
	}

	/** Retry-After as integer seconds; null if absent/blank/negative/non-integer (HTTP-date unsupported). */
	static Long parseRetryAfterSeconds(String header)
	{
		if (header == null)
		{
			return null;
		}
		String trimmed = header.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}
		try
		{
			long seconds = Long.parseLong(trimmed);
			return seconds < 0 ? null : seconds;
		}
		catch (NumberFormatException e)
		{
			return null; // HTTP-date form not handled — fall back to exponential
		}
	}
}
