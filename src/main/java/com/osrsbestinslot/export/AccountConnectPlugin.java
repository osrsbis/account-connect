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
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
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
	private static final String PLUGIN_VERSION = "0.7.7";
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

	@Provides
	AccountConnectConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AccountConnectConfig.class);
	}

	@Override
	protected void startUp()
	{
	}

	@Override
	protected void shutDown()
	{
		stopStoreClipCapture(false);	// unregister the render listener + drop any buffered frames, no upload
	}

	/**
	 * Server-dictated policy, read from the ingest response on every accepted upload (see
	 * applyServerPolicy). Volatile: written by the OkHttp callback thread, read by the client thread.
	 * serverScreenshotsDisabled lets osrsbestinslot.com force trade-screenshot capture OFF for a token
	 * remotely (it can never force it ON — that stays a local opt-in for Plugin Hub compliance).
	 */
	volatile boolean serverScreenshotsDisabled;

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
	/** Shop interface (SHOPMAIN 300) open — so a Buy/Sell menu click is a general-store transaction. */
	private volatile boolean shopOpen;

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
	 * Nearby players AT THE MOMENT of the transaction, as opposed to across the whole visit.
	 *
	 * A visit-wide list answers "who was around at some point", which is a weaker claim than the one a
	 * dispute needs. This is sampled when the buy/sell actually fires, so the event can state who was
	 * present AT the transaction and for how long around it.
	 */
	private volatile List<Map<String, Object>> nearbyAtTx;
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
	private volatile InvDeltaPending invDeltaPending;

	static final class InvDeltaPending
	{
		final String base;			// "drop" | "pickup" | "alch"
		final int item;
		final String spell;			// "high" | "low" for alch, else null
		final long beforeCount;		// count of `item` in the inventory at click time
		final long beforeCoins;		// carried coins at click time (alch gp confirmation)
		final Map<String, Object> location;	// {region_id, plane} at click, or null (alch)
		final Boolean wilderness;	// drop only: true if dropped inside the Wilderness (instantly visible)
		final int tick;
		// drop only: tick a matching ground spawn was seen at our tile BEFORE the inventory loss was visible.
		// Makes resolution order-independent — whichever of (spawn, inventory-decrement) the client processes
		// first records itself; the second completes the emit. -1 = no corroboration yet.
		volatile int spawnCorroboratedTick = -1;

		InvDeltaPending(String base, int item, String spell, long beforeCount, long beforeCoins,
			Map<String, Object> location, Boolean wilderness, int tick)
		{
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
	// While a shop is open, sample the render at a low WALL-CLOCK rate into a bounded ring buffer. On
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
	// Paying for it: 30fps x 12s = 360 frames, and 360 frames only fits the 12MB burst cap at a smaller
	// frame. Measured on a real captured frame: 768px/q0.70 = 45KB (16.2MB — over), 704px/q0.55 = 30KB
	// (10.8MB — fits). The 704px/q0.55 frame was checked for LEGIBILITY, not just size: shop item text
	// and chat remain readable, which is the property MAX_FRAME_WIDTH exists to protect.
	//
	// 12 seconds is the deliberate trade. A store visit's evidence is the transaction and the seconds
	// around it, and the ring keeps the NEWEST frames, so a longer visit loses its opening and never its
	// transaction. Twelve seconds at 30fps shows the approach, the click and the result.
	static final int CLIP_FPS = 30;					// sample rate (constant, NOT a user setting)
	static final int CLIP_SECONDS = 12;				// max clip length retained (was 120 @ 1fps)
	static final int MAX_CLIP_FRAMES = CLIP_FPS * CLIP_SECONDS;	// ring capacity = 120 frames
	// Task-0 legibility verdict (PRD): 768px keeps store text readable at the server stitch size.
	// (Plan body text says 640px; 768 is the ratified Task-0 override.) Lowered 768 -> 704 on 2026-09-02
	// to pay for 30fps: 360 frames only fits the 12MB burst cap at ~30KB/frame. 704 was chosen over 640
	// because it was CHECKED for legibility — shop item text and chat still read at 704/q0.55, and store
	// text is the thing being proven. Below 704 that stops being true, so this is a floor, not a knob.
	static final int MAX_FRAME_WIDTH = 704;
	// Server ingest caps (mirror /store-frames-ingest): a JPEG over this is dropped; the visit keeps the
	// newest suffix that fits both the frame-count cap and this total-bytes cap.
	//
	// ⚠ MAX_CLIP_BURST_BYTES bounds the WHOLE VISIT here, not one POST. Since the upload is chunked
	// (CLIP_CHUNK_FRAMES), the server's 12MB per-request limit is never the binding constraint — a
	// 100-frame chunk at ~30KB is ~3MB. This stays at 12MB as the visit budget: it caps what one shop
	// visit can ever cost in memory and upload, and is what MAX_CLIP_FRAMES is sized against.
	static final int MAX_CLIP_FRAME_BYTES = 1_000_000;		// 1MB per frame
	static final int MAX_CLIP_BURST_BYTES = 12_000_000;		// 12MB per VISIT (all chunks together)

	/** osrsbestinslot.com can force store-clip capture OFF for a token via the X-Clips response header
	 *  (it can never force it ON — that stays a local opt-in, mirroring serverScreenshotsDisabled). */
	volatile boolean serverClipsDisabled;
	/** Armed between shop-open and shop-close while capture is running. */
	private volatile boolean clipCapturing;
	/** Set the moment a general-store buy/sell fires during the visit — a visit without one is dropped. */
	volatile boolean storeTxThisVisit;
	/** Bounded FIFO of sampled frames; created per capture, snapshotted + cleared on stop. */
	private volatile ClipRingBuffer clipRing;
	/** Wall-clock (nanoTime) of the next frame to sample; a render tick before this is skipped. */
	private volatile long nextClipSampleAt;
	/** Set on a sampled tick to request one frame from DrawManager; cleared when that frame arrives. */
	private volatile boolean clipFramePending;
	/** Per-frame render callback, registered with DrawManager only while capturing. */
	private final Runnable clipFrameTick = this::onClipFrameTick;

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
		clipRing = new ClipRingBuffer(MAX_CLIP_FRAMES);
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
					ClipRingBuffer ring = clipRing;
					if (ring != null)
					{
						ring.add(encodeJpeg(scaled));	// null/empty is ignored by the ring
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
		ClipRingBuffer ring = clipRing;
		clipRing = null;
		clipFramePending = false;
		boolean hadTx = storeTxThisVisit;
		storeTxThisVisit = false;
		if (ring == null)
		{
			return;
		}
		if (!upload || !hadTx)
		{
			ring.clear();		// no purchase, or shutdown/hop drop — discard without uploading
			return;
		}
		List<byte[]> frames = ring.snapshot();	// already JPEG-encoded at capture time
		ring.clear();
		if (!frames.isEmpty())
		{
			submitStoreClipUpload(frames);
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
	 * time of ITS OWN first frame, not the visit's. That gives the collector two things it cannot get
	 * any other way: the ORDER of the chunks, and the REAL frame rate (a chunk's frame count divided by
	 * the gap to the next chunk's captured_at). The real rate matters because the server clamps the
	 * DECLARED fps to a range that predates 30fps capture — so the declared value can be wrong, while a
	 * rate derived from two timestamps cannot be.
	 */
	void submitStoreClipUpload(java.util.List<byte[]> frames)
	{
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$") || frames == null || frames.isEmpty())
		{
			return;	// same guard as the trade path — no / malformed token, or nothing to send
		}
		// The visit ENDED now, so the first frame is (frames-1)/fps seconds ago. Anchoring on the end
		// and working backwards keeps each chunk's captured_at on the same real timeline even though
		// all of them are uploaded at once, after the fact.
		final long endMillis = System.currentTimeMillis();
		final long startMillis = endMillis - (long) (frames.size() - 1) * 1000L / CLIP_FPS;
		executor.submit(() ->
		{
			// Frames arrive ALREADY ENCODED (onClipFrameTick encodes each one as it is captured), so
			// there is no second decoded copy here — that copy is what put ~400MB on the heap and froze
			// the client. selectStoreClipFrames still filters null / oversized entries.
			java.util.List<byte[]> kept = selectStoreClipFrames(
				frames, MAX_CLIP_FRAMES, MAX_CLIP_FRAME_BYTES, MAX_CLIP_BURST_BYTES);
			if (kept.isEmpty())
			{
				return;
			}
			// selectStoreClipFrames keeps the NEWEST suffix, so a trimmed burst starts later than the
			// visit did. Re-anchor on the END, which is the frame that is never dropped.
			long firstKeptMillis = endMillis - (long) (kept.size() - 1) * 1000L / CLIP_FPS;
			String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
			for (int off = 0; off < kept.size(); off += CLIP_CHUNK_FRAMES)
			{
				java.util.List<byte[]> chunk =
					kept.subList(off, Math.min(off + CLIP_CHUNK_FRAMES, kept.size()));
				long chunkAtMillis = firstKeptMillis + (long) off * 1000L / CLIP_FPS;
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
					log.debug("OSRS BiS store-clip burst upload failed", e);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					try
					{
						log.debug("OSRS BiS store-clip burst upload response: {}", response.code());
					}
					finally
					{
						response.close();
					}
				}
			});
	}

	/**
	 * Choose which encoded frames go in the burst. Pass 1: drop any frame that failed to encode or exceeds
	 * the per-frame cap. Pass 2: keep the NEWEST suffix that fits both maxFrames and maxBurstBytes, walking
	 * from the END — the shop-close / sale frames are the actual delivery evidence, so an oversized burst
	 * must drop the OLDEST frames, never the newest. Chronological order within the kept suffix is preserved.
	 */
	static java.util.List<byte[]> selectStoreClipFrames(java.util.List<byte[]> encoded, int maxFrames, int maxFrameBytes, int maxBurstBytes)
	{
		java.util.List<byte[]> valid = new java.util.ArrayList<>(encoded.size());
		for (byte[] b : encoded)
		{
			if (b != null && b.length > 0 && b.length <= maxFrameBytes)
			{
				valid.add(b);
			}
		}
		int start = valid.size();
		long total = 0;
		int kept = 0;
		while (start > 0 && kept < maxFrames && total + valid.get(start - 1).length <= maxBurstBytes)
		{
			total += valid.get(start - 1).length;
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
			return; // nothing meaningfully changed since the last accepted upload
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
		switch (event.getGameState())
		{
			case LOADING:
				// Region transition — a NEW instanced chest may lie ahead; the belt keys are per-visit.
				chestLooted = false;
				lastChestEmitKey = null;
				// Scene reload replays existing ground piles as fresh ItemSpawned events (Codex consult
				// 2026-07-18) — a surviving pending could pair a replayed same-id pile with an unrelated
				// inventory loss. A drop clicked right before a region boundary is lost; miss beats fabricate.
				invDeltaPending = null;
				break;
			case HOPPING:
			case LOGGING_IN:
				clogObtained.clear();
				clogSeen = false;
				resetTradeState();	// a pending trade frame must never leak across accounts/sessions
				invDeltaPending = null;	// an armed drop/pickup/alch must never resolve across a hop/relog
				shopVisitNearby.clear();	// nearby-candidate set must not carry rsns across accounts
				shopStock.clear();		// stock/sold/at-tx state is per visit AND per account
				soldThisVisit.clear();
				nearbyAtTx = null;
				chestLooted = false;
				lastChestEmitKey = null;
				break;
			case CONNECTION_LOST:
				clogObtained.clear();
				clogSeen = false;
				resetTradeState();
				invDeltaPending = null;	// an armed drop/pickup/alch must never survive a disconnect
				// a feed-death then instant disconnect: record the death, but the containers here are null or
				// not-yet-settled, so OMIT items_lost (computeLoss=false) rather than emit a wrong diff.
				resolveDeathPending(null, false);
				// WAVE 3: a dropped connection previously emitted NOTHING. Emit an explicit logout so a crash /
				// disconnect is distinguishable from a clean logout. Ends the session; a reconnect re-logs in.
				trackLogout("connection_lost");
				break;
			case LOGIN_SCREEN:
				// Real logout (HOPPING keeps the session and is handled above, without a flush).
				invDeltaPending = null;	// an armed drop/pickup/alch must never survive a logout
				resolveDeathPending(null, false);	// feed-death then logout: record death, omit untrustworthy items_lost
				trackLogout(); // buffer a "logout" event (duration + reason); flushed live (WAVE 2) / next tick
				flushPendingSnapshot();
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
			InvDeltaPending idp = invDeltaPending;
			if (idp != null)
			{
				resolveInvDeltaPending(countItem(inv, idp.item), coinsAfter, tick);
			}
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
			shopVisitNearby.clear();	// fresh receiver-candidate set for this shop visit
			shopStock.clear();		// baseline is taken from this visit's first container change
			soldThisVisit.clear();
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
		if (event.getGroupId() == SHOP_GROUP_ID)
		{
			shopOpen = false;
			flushStorePendingOnShopClose();
			stopStoreClipCapture(true);	// end the visit; upload only if it had a buy/sell, else drop
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
		// Delivery-proof tx flag — hoisted ABOVE the activity-log gate (grill F5): a clip's buy/sell must
		// not be dropped just because activity logging is off. Only relevant while a clip is capturing.
		if (clipCapturing && shopOpen && isStoreBuyOrSell(event))
		{
			storeTxThisVisit = true;
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
			soldThisVisit.add(item);	// watch this item's shop stock for a taker
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
			out.add(m);
		}
		return out;
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
		for (Integer item : soldThisVisit)
		{
			int before = shopStock.getOrDefault(item, -1);
			int after = now.getOrDefault(item, 0);
			if (before < 0 || after >= before)
			{
				continue;		// no baseline yet, or stock did not fall
			}
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("item", item);
			f.put("qty", before - after);
			f.put("stock_before", before);
			f.put("stock_after", after);
			List<Map<String, Object>> present = nearbyPlayersSnapshot(NEARBY_FIELD_CAP);
			if (!present.isEmpty())
			{
				f.put("taken_by_candidates", present);
			}
			if (client != null)
			{
				f.put("world", client.getWorld());
			}
			emitEvent("store_taken", f);
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
		int item = event.getItemId();
		if (item <= 0)
		{
			return;	// no resolvable item id (e.g. alch spell-on-item may report -1) — skip rather than guess
		}
		String base = action.startsWith("alch") ? "alch" : action;
		String spell = "alch_high".equals(action) ? "high" : ("alch_low".equals(action) ? "low" : null);
		ItemContainer inv = client == null ? null : client.getItemContainer(InventoryID.INVENTORY);
		long beforeCount = countItem(inv, item);
		long beforeCoins = countItem(inv, COINS_ID);
		int tick = client == null ? 0 : client.getTickCount();
		Map<String, Object> location = "alch".equals(base) ? null : currentLocation();
		Boolean wilderness = "drop".equals(base)
			? (client != null && client.getVarbitValue(Varbits.IN_WILDERNESS) > 0)
			: null;
		invDeltaPending = new InvDeltaPending(base, item, spell, beforeCount, beforeCoins, location, wilderness, tick);
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
		InvDeltaPending p = invDeltaPending;
		if (p == null || !"drop".equals(p.base) || spawnedItemId != p.item || dist > DROP_SPAWN_MAX_DIST || !stackGrew)
		{
			return;	// !stackGrew: a SHRINKING nearby stack is another player looting it — never our drop landing
		}
		if (deathPending != null)
		{
			// Death belt (backs the onActorDeath disarm): mid-death ground spawns + inventory wipe are the
			// death's items, never a player-initiated drop — refuse all corroboration while a death settles.
			invDeltaPending = null;
			return;
		}
		if (currentTick - p.tick > DROP_PENDING_MAX_TICKS)
		{
			invDeltaPending = null;	// stale — the click this pending belonged to is long over
			return;
		}
		long delta = p.beforeCount - invCountAfter;
		if (delta <= 0)
		{
			// The spawn was processed BEFORE the inventory decrement was visible (intra-tick order is a server
			// packet detail we don't control). Record the corroboration; the inventory-change path completes
			// the emit when the loss lands. Without this, spawn-first ordering would miss EVERY drop.
			p.spawnCorroboratedTick = currentTick;
			return;
		}
		invDeltaPending = null;	// consume — both signals confirmed
		emitDropEvent(p, delta);
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
		emitEvent("drop", fields);
	}

	/** Live wrapper for the ground-spawn resolvers: distance from the local player + current inventory count. */
	private void handleGroundItemForDropPending(net.runelite.api.TileItem it, net.runelite.api.Tile tile, boolean stackGrew)
	{
		InvDeltaPending p = invDeltaPending;
		if (p == null || !"drop".equals(p.base) || client == null || it == null || tile == null)
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
		resolveDropPendingOnGroundSpawn(it.getId(), dist, invCount, client.getTickCount(), stackGrew);
	}

	/** A fresh ground item at our tile — the primary own-drop confirmation signal. */
	@Subscribe
	public void onItemSpawned(net.runelite.api.events.ItemSpawned event)
	{
		handleGroundItemForDropPending(event.getItem(), event.getTile(), true);
	}

	/**
	 * Dropping a stackable onto an existing ground stack merges instead of spawning — same confirmation, but
	 * ONLY when the stack GREW (a shrinking stack is another player looting it, never our drop landing).
	 */
	@Subscribe
	public void onItemQuantityChanged(net.runelite.api.events.ItemQuantityChanged event)
	{
		handleGroundItemForDropPending(event.getItem(), event.getTile(),
			event.getNewQuantity() > event.getOldQuantity());
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
		InvDeltaPending p = invDeltaPending;
		if (p == null)
		{
			return;
		}
		if ("drop".equals(p.base))
		{
			// Order-independence: if a matching ground spawn already corroborated (spawn processed before the
			// inventory decrement) and the loss now lands within the corroboration window, complete the emit.
			long dropDelta = p.beforeCount - itemCountAfter;
			int spawnTick = p.spawnCorroboratedTick;
			if (dropDelta > 0 && spawnTick >= 0 && currentTick - spawnTick <= DROP_CORROBORATION_MAX_TICKS)
			{
				invDeltaPending = null;
				emitDropEvent(p, dropDelta);
				return;
			}
			if (currentTick - p.tick > DROP_PENDING_MAX_TICKS)
			{
				invDeltaPending = null;	// no ground spawn ever confirmed it — expire silently, emit nothing
			}
			return;
		}
		long delta = "pickup".equals(p.base) ? (itemCountAfter - p.beforeCount) : (p.beforeCount - itemCountAfter);
		long gp = coinsAfter - p.beforeCoins;
		boolean landed = delta > 0 && (!"alch".equals(p.base) || gp > 0);
		if (!landed)
		{
			int window = "pickup".equals(p.base) ? INV_DELTA_PENDING_MAX_TICKS : ALCH_PENDING_MAX_TICKS;
			if (currentTick - p.tick > window)
			{
				invDeltaPending = null;
			}
			return;
		}
		invDeltaPending = null;	// consume — the expected delta landed
		if (!activityLogActive())
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("item", p.item);
		fields.put("qty", delta);
		if ("alch".equals(p.base))
		{
			fields.put("spell", p.spell);
			fields.put("gp", gp);	// always present now — the coin gain is the alch confirmation itself
			emitEvent("alch", fields);
			return;
		}
		if (p.location != null)
		{
			fields.put("location", p.location);
		}
		emitEvent("pickup", fields);	// only pickup reaches here — drop is spawn-confirmed, alch returned above
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
			invDeltaPending = null;
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
	}

	/**
	 * GE buys/sells as discrete events — emit once per terminal-state transition (BOUGHT / SOLD /
	 * CANCELLED), not on every partial-fill tick. Own-account GE only.
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
		GrandExchangeOfferState prev = lastGeState.put(slot, state);
		if (state == prev)
		{
			return;
		}
		String type;
		switch (state)
		{
			case BOUGHT:
				type = "ge_buy";
				break;
			case SOLD:
				type = "ge_sell";
				break;
			case CANCELLED_BUY:
			case CANCELLED_SELL:
				type = "ge_cancel";
				break;
			default:
				return; // BUYING / SELLING / EMPTY — not a terminal event
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("item", offer.getItemId());
		fields.put("qty", offer.getQuantitySold());
		fields.put("price", offer.getPrice());
		fields.put("gp", offer.getSpent());
		emitEvent(type, fields);
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
			// 0.55, lowered from 0.70 on 2026-09-02 to fit 360 frames (30fps x 12s) in the 12MB burst
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

		String clips = response.header("X-Clips");
		if (clips != null)
		{
			String v = clips.trim().toLowerCase(java.util.Locale.ROOT);
			serverClipsDisabled = "off".equals(v) || "disabled".equals(v) || "false".equals(v);
		}

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
