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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
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
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.HotkeyListener;
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
	private static final String PLUGIN_VERSION = "0.7.0";
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
	// Capture-on-open groups: opening either forces an immediate snapshot so the bank / collection log
	// sync the moment they become readable (verified vs runelite-api 1.12.32 gameval enums, javap).
	private static final int BANK_GROUP_ID = net.runelite.api.gameval.InterfaceID.BANKMAIN;					// 12
	private static final int COLLECTION_LOG_GROUP_ID = net.runelite.api.gameval.InterfaceID.COLLECTION;		// 621
	private static final String TRADE_ACCEPTED_MESSAGE = "Accepted trade.";
	private static final String TRADE_DECLINED_MESSAGE = "Other player declined trade.";
	private static final MediaType PNG = MediaType.parse("image/png");
	static final int MAX_SCREENSHOT_UPLOAD_BYTES = 5 * 1024 * 1024;	// enforced client-side and server-side

	// Trade state machine (package-private so unit tests can assert on it without a live client).
	// IDLE -> (container 90) tradeActive -> (334 load) tradeArmed + frame buffered -> commit/discard.
	volatile boolean tradeActive;
	volatile boolean tradeArmed;
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
	private KeyManager keyManager;

	@Inject
	private ClientThread clientThread;

	/**
	 * Manual "Re-sync now" hotkey (FIX: the site tells users to Re-sync but the plugin only auto-sent on a
	 * change + debounce). Pressing the configured key forces one immediate snapshot send. The keypress
	 * arrives on the AWT event thread, so it is marshalled onto the client thread before building the
	 * snapshot (buildSnapshot reads live client containers). NOT_SET until the user assigns a key.
	 */
	private final HotkeyListener resyncHotkeyListener = new HotkeyListener(() -> config.resyncHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invoke(AccountConnectPlugin.this::forceSendSnapshot);
		}
	};

	@Provides
	AccountConnectConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AccountConnectConfig.class);
	}

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(resyncHotkeyListener);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(resyncHotkeyListener);
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
	private volatile boolean sessionActive;
	private volatile String activeRsn;
	private volatile String activeHash;
	private volatile long sessionStartMillis;
	/** Dedup state for the broad activity sweep: emit a level event only on a real level change, a GE event
	 *  only on a state transition (not every partial-fill tick). Client-thread only. */
	private final Map<String, Integer> lastSkillLevel = new java.util.HashMap<>();
	private final Map<Integer, GrandExchangeOfferState> lastGeState = new java.util.HashMap<>();
	/** Shop interface (SHOPMAIN 300) open — so a Buy/Sell menu click is a general-store transaction. */
	private volatile boolean shopOpen;
	/** Trade offer + counterparty captured at the confirm screen, emitted as a "trade" event on accept. */
	private volatile List<Map<String, Object>> pendingTradeGiven;
	private volatile String pendingCounterparty;

	/** Trade-screenshot capture requires the local opt-in, no server force-disable, AND a non-excluded account. */
	boolean screenshotsEnabled()
	{
		// toggle first: when off, short-circuit before touching client (the feature is off regardless).
		return config.uploadTradeScreenshots() && !serverScreenshotsDisabled && !isCurrentAccountExcluded();
	}

	/** True if the logged-in character is on the user's "don't sync these accounts" list (personal opt-out). */
	boolean isCurrentAccountExcluded()
	{
		if (client == null)
		{
			return false;
		}
		net.runelite.api.Player p = client.getLocalPlayer();
		return p != null && isAccountExcluded(p.getName());
	}

	/** Case-insensitive membership test of a character name against the comma-separated excluded list. */
	boolean isAccountExcluded(String playerName)
	{
		if (playerName == null)
		{
			return false;
		}
		String excluded = config.excludedAccounts();
		if (excluded == null || excluded.trim().isEmpty())
		{
			return false;
		}
		String target = playerName.trim().toLowerCase(java.util.Locale.ROOT);
		for (String name : excluded.split(","))
		{
			if (name.trim().toLowerCase(java.util.Locale.ROOT).equals(target))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The activity log is part of core sync — no separate toggle. It's active whenever a valid link
	 * token is set and the logged-in account isn't on the opt-out list: the SAME gate as the snapshot
	 * upload. Disclosed on the link-token config + the osrsbestinslot connect flow.
	 */
	boolean activityLogActive()
	{
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		return token.matches("^[a-f0-9]{32}$") && !isCurrentAccountExcluded();
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
	}

	/**
	 * Detect session start on the client thread (player available): the first logged-in tick, or a hop
	 * into a different account, emits a "login" event and stamps the session. Called from syncTask.
	 */
	void trackSessionStart()
	{
		String rsn = client.getLocalPlayer().getName();
		String hash = Long.toString(client.getAccountHash());
		if (!sessionActive || !hash.equals(activeHash))
		{
			activeRsn = rsn;
			activeHash = hash;
			sessionActive = true;
			sessionStartMillis = System.currentTimeMillis();
			emitEvent("login", null);
		}
		else
		{
			activeRsn = rsn; // keep the display name fresh
		}
	}

	/** Emit a "logout" event (with session duration) for the tracked account, if a session was active. */
	void trackLogout()
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
		emitEvent("logout", fields);
		sessionActive = false;
	}

	/**
	 * POST buffered activity events to /event-ingest, then clear them. Fire-and-forget like the
	 * screenshot upload. Runs on every @Schedule tick (including at the login screen) so a logout event
	 * flushes promptly. Own-account data only; token-gated exactly like the snapshot path.
	 */
	void flushEvents()
	{
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return;
		}
		List<Map<String, Object>> batch;
		synchronized (pendingEvents)
		{
			if (pendingEvents.isEmpty())
			{
				return;
			}
			batch = new ArrayList<>(pendingEvents);
			pendingEvents.clear();
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("token", token);
		body.put("events", batch);
		String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
		Request request = new Request.Builder()
			.url(base + "/event-ingest")
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS BiS event sync failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
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
		if (isCurrentAccountExcluded())
		{
			return; // personal account on the user's opt-out list — never build, track, or send it
		}
		String token = config.linkToken() == null ? "" : config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return; // no / malformed token configured yet
		}
		trackSessionStart(); // core sync: emits a "login" event on the first tick of a session / after a hop
		Map<String, Object> snapshot = buildSnapshot();
		String hash = canonicalHash(snapshot);
		// Track on EVERY tick regardless of the gates below: the logout flush sends this cache.
		lastBuiltSnapshot = snapshot;
		lastBuiltHash = hash;

		long now = System.currentTimeMillis();
		if (now < backoffUntilMillis)
		{
			return; // rate-limited by the server — build + track only, never send
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
	 * the manual "Re-sync now" hotkey and capture-on-open (bank / collection log). It still honors the
	 * guards that make a send valid at all — logged in, token set, account not excluded — and an active
	 * 429 backoff (the server explicitly said stop; a client-side force never overrides that). It
	 * deliberately does NOT touch lastSendMillis: a capture-on-open send can land a tick before the data
	 * finishes populating (the collection log fills as its draw scripts run), and updating the debounce
	 * clock here would hold back the real snapshot the next tick sends. lastUploadedHash is still recorded
	 * on accept (shared postSnapshot callback), so an unchanged follow-up tick won't re-send; the only
	 * cost is at most one duplicate POST if a scheduled tick races the async accept, which the server
	 * dedupes by hash. Must run on the client thread (WidgetLoaded delivery, or ClientThread.invoke from
	 * the hotkey) so reading client containers is safe.
	 */
	void forceSendSnapshot()
	{
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			return;
		}
		if (isCurrentAccountExcluded())
		{
			return; // personal account on the user's opt-out list — never build, track or send it
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
		if (isCurrentAccountExcluded())
		{
			return; // opted-out account — don't cache its post-trade wealth either
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
		switch (event.getGameState())
		{
			case HOPPING:
			case LOGGING_IN:
			case CONNECTION_LOST:
				clogObtained.clear();
				clogSeen = false;
				resetTradeState();	// a pending trade frame must never leak across accounts/sessions
				break;
			case LOGIN_SCREEN:
				// Real logout (HOPPING keeps the session and is handled above, without a flush).
				trackLogout(); // buffer a "logout" event (session duration); flushed by the next syncTask tick
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
	public void onWidgetLoaded(WidgetLoaded event)
	{
		handleTradeWidgetLoaded(event.getGroupId());
		handleActivityWidgetLoaded(event.getGroupId());
		handleCaptureOnOpenWidgetLoaded(event.getGroupId());
	}

	/**
	 * Capture-on-open: opening the bank (BANKMAIN 12) or the collection log (COLLECTION 621) forces an
	 * immediate snapshot so those containers sync the moment they become readable, instead of waiting up
	 * to a full debounce interval for the next scheduled tick (the "opened my bank, still says not synced"
	 * complaint). WidgetLoaded is delivered on the client thread, so forceSendSnapshot reads containers
	 * directly. Its own guards (token / excluded / backoff) still apply — an unlinked or opted-out account
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
	 * be emitted on "Accepted trade.". Own-account items; counterparty forwarded only to a staff backend.
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
		}
		else if (groupId == TRADE_CONFIRM_GROUP_ID)
		{
			pendingTradeGiven = readOwnOffer();
			pendingCounterparty = counterpartyName();
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
		}
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

	/** "Accepted trade." commits the buffered frame; a decline discards it. Tag-tolerant match. */
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
		emitChatActivity(event);
	}

	/**
	 * On "Accepted trade.", emit a structured "trade" event from what was captured at the confirm screen:
	 * the items WE gave (own offer) and the counterparty name (forwarded for ALL users, disclosed). The
	 * received[] side is added in a follow-up (read from the confirm-screen YOU_WILL_RECEIVE widget).
	 */
	void emitTradeEvent()
	{
		List<Map<String, Object>> given = pendingTradeGiven;
		String counterparty = pendingCounterparty;
		pendingTradeGiven = null;
		pendingCounterparty = null;
		if (given == null)
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("given", given);
		// Counterparty RSN forwarded for ALL users (staff-backend gate removed — Lukas 2026-07-14). It is the
		// one other-player field we send, on purpose, disclosed in the config item + Hub warning text.
		if (counterparty != null)
		{
			fields.put("counterparty", counterparty);
		}
		emitEvent("trade", fields);
	}

	/** General-store buy/sell: a Buy/Sell menu click while the shop (SHOPMAIN 300) is open. Own-account. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!shopOpen || !activityLogActive())
		{
			return;
		}
		String opt = event.getMenuOption() == null ? "" : event.getMenuOption();
		String type;
		if (opt.startsWith("Buy"))
		{
			type = "store_buy";
		}
		else if (opt.startsWith("Sell"))
		{
			type = "store_sell";
		}
		else
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("item", event.getItemId());
		fields.put("qty", parseTrailingQty(opt));
		emitEvent(type, fields);
	}

	/** "Buy 10" -> 10, "Sell 1" -> 1, "Buy" -> 1 (default). */
	static int parseTrailingQty(String option)
	{
		String[] parts = option.trim().split("\\s+");
		try
		{
			return Integer.parseInt(parts[parts.length - 1]);
		}
		catch (NumberFormatException e)
		{
			return 1;
		}
	}

	/**
	 * Broad activity sweep — own-account game chat only (GAMEMESSAGE / SPAM). Captures deaths, drops,
	 * pet/untradeable/clue rewards, level-ups, quest completions, GE collect lines, etc. as timestamped
	 * events. Deliberately EXCLUDES player/private/clan/friends chat (other-player content = PII / the
	 * Hub's "crowdsource other players" rejection). Own account, structured, disclosed.
	 */
	void emitChatActivity(ChatMessage event)
	{
		if (event == null)
		{
			return;
		}
		ChatMessageType t = event.getType();
		if (t != ChatMessageType.GAMEMESSAGE && t != ChatMessageType.SPAM)
		{
			return;
		}
		String text = Text.removeTags(event.getMessage() == null ? "" : event.getMessage()).trim();
		if (text.isEmpty())
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("text", text);
		emitEvent("chat", fields);
	}

	/** Own-account death (item-loss context). ActorDeath fires for any nearby actor, so filter to self. */
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() != null && event.getActor() == client.getLocalPlayer())
		{
			emitEvent("death", null);
		}
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
					if (screenshotsEnabled())	// re-check: toggle may flip before the frame lands
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
	 * The whole feature is gated on the opt-in toggle: off means no arming, no frame request, no
	 * buffer, no upload. Also drops any frame buffered before the toggle was switched off mid-trade.
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
		pendingTradeFrame.set(null);
		pendingTradeGiven = null;	// activity-log trade capture — drop on decline/abandon/hop so it never leaks
		pendingCounterparty = null;
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

	/** In-memory PNG encode via JDK ImageIO (same encoder core ImageCapture uses). Null on failure. */
	static byte[] encodePng(BufferedImage image)
	{
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		try
		{
			javax.imageio.ImageIO.write(image, "png", buf);
		}
		catch (IOException e)
		{
			log.debug("OSRS BiS trade screenshot PNG encode failed", e);
			return null;
		}
		return buf.toByteArray();
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
