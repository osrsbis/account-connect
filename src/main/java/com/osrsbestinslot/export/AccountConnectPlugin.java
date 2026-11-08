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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import java.awt.Graphics2D;
import java.awt.Image;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "OSRS Best in Slot",
	description = "Connect your account to auto-fill the osrsbestinslot.com calculators (skills, quests, gear).",
	tags = {"osrs", "bis", "best in slot", "calculator", "gear"}
)
public class AccountConnectPlugin extends Plugin
{
	final ServerGrants grants = new ServerGrants(this);
	final SnapshotUploader uploader = new SnapshotUploader(this);
	final EventQueue events = new EventQueue(this);
	final PublicOutboxDriver outbox = new PublicOutboxDriver(this);
	final SpoolDriver spooler = new SpoolDriver(this);
	final SnapshotBuilder snapshots = new SnapshotBuilder(this);
	final DropProof dropProof = new DropProof(this);
	final OffBookPendings offBook = new OffBookPendings(this);
	final OwnPiles piles = new OwnPiles(this);
	final StoreTools store = new StoreTools(this);
	final SessionEvents session = new SessionEvents(this);
	final BankVisit bankVisit = new BankVisit(this);
	final Verification verify = new Verification(this);
	final KillActivity kills = new KillActivity(this);
	final TradeCapture trade = new TradeCapture(this);
	final StoreClips clips = new StoreClips(this);
	final WildernessPlayers wilderness = new WildernessPlayers(this);
	final Death death = new Death(this);
	static final int SCHEMA_V = 1;
	// MUST equal build.gradle's version — VersionDriftTest fails the build if the two ever diverge, so
	// every snapshot's source.plugin_version honestly reports which build the account is running.
	static final String PLUGIN_VERSION = "0.7.68";
	static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	// Collection-log: the client fires script 4100 once per OBTAINED slot as the clog UI renders.
	// We accumulate those item ids; capture is partial until the player opens the relevant clog tabs.
	private static final int SCRIPT_CLOG_DRAW = 4100;
	final Set<Integer> clogObtained = new HashSet<>();
	boolean clogSeen = false;
	final AccountProgressCapture accountProgress = new AccountProgressCapture();
	// The collection-log search scan for a token without the account-progress grant. Only its collection part is
	// used: it makes the snapshot's collection_log whole and says so. Nothing else of account progress is read.
	final AccountProgressCapture collectionScan = new AccountProgressCapture();
	// The collection log finished its setup script: start the scan on the next game tick.
	boolean collectionSetupPending;
	// Tick the player's collection log interface opened, or -1 while it is closed. Live 0.7.62 run: the setup
	// script event was not seen on three opens, so an open log is also checked on each tick, from its second tick.
	int collectionLogOpenTick = -1;
	final OwnGameplayCapture ownGameplay = new OwnGameplayCapture(this::emitOwnGameplayEvent, id -> this.itemManager == null ? -1 : this.itemManager.getItemPrice(id));
	@Inject
	PluginManager pluginManager;

	boolean emitOwnCombat(Map<String,Object> observation)
	{
		if (!"combat_hit".equals(observation.get("type")) && !"combat_npc_end".equals(observation.get("type"))) return false;
		return emitOwnGameplayEvent(observation);
	}

	boolean emitOwnGameplayEvent(Map<String,Object> observation)
	{
		// Existing gameplay permission. Closed own-player events use the granted, capped legacy receiver.
		// This preserves collection but does not claim a durable public journal across restart.
		if (!grants.gameplayGranted()) return false;
		String legacyType = String.valueOf(observation.get("type"));
		if (!legacyOwnCombatAllowed(legacyType)) return false;
		if ("own_signal".equals(legacyType) && OwnSignalEvent.wire(observation) == null) return false;
		if ("own_state".equals(legacyType) && OwnStateEvent.wire(observation) == null) return false;
		if ("own_vars".equals(legacyType) && OwnVarsEvent.wire(observation) == null) return false;
		Map<String,Object> fields = new LinkedHashMap<>(observation); fields.remove("type");
		events.emitEvent(String.valueOf(observation.get("type")), fields); return true;
	}

	/**
	 * Own combat and own signals on the legacy event writer: only combat_hit / combat_npc_end / own_signal, only
	 * with the own-gameplay grant for the current token, only when no Queue scope is active and the live client
	 * still matches the active account and profile. State and vars must pass their closed wire projection.
	 */
	boolean legacyOwnCombatAllowed(String type)
	{
		if (!"combat_hit".equals(type) && !"combat_npc_end".equals(type) && !"own_signal".equals(type)
			&& !"own_state".equals(type) && !"own_vars".equals(type)) return false;
		if (!grants.gameplayGranted() || grants.eventQueueScope != null) return false;
		return client == null || (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null
			&& Long.toString(client.getAccountHash()).equals(activeHash)
			&& RuneScapeProfileType.getCurrent(client).name().equals(activeProfileType));
	}

	boolean gameplayReady()
	{
		if (!grants.gameplayGranted()) { ownGameplay.reset(); return false; }
		// A new token or grant epoch needs a fresh absolute baseline.
		return ownGameplay.ready(client, currentLinkToken() + ":" + grants.gameplayGrantEpoch);
	}

	Map<String,Object> gameplayBlock()
	{
		return gameplayReady() ? ownGameplay.snapshot() : null;
	}

	final RewardLootCapture rewardLoot = new RewardLootCapture();

	final KillContext.SlayerTasks slayerTasks = new KillContext.SlayerTasks();
	final KillContext.ActivityCounters activityCounters = new KillContext.ActivityCounters();
	final AtomicLong verificationEpoch = new AtomicLong();
	final VerificationProbe verificationProbe = new VerificationProbe();
	final EventVerification eventVerification = new EventVerification();
	final VerificationDiscovery verificationDiscovery = new VerificationDiscovery();
	String verificationMessage;
	boolean verificationMessageHandled;
	boolean discoveryEvidenceQueued;
	/** captured_at_ms of the last snapshot this client built: the revision a verification row was read against. */
	volatile long lastSnapshotCapturedAtMs;

	boolean progressReady()
	{
		if (!grants.progressGranted())
		{
			accountProgress.reset();
			return false;
		}
		return accountProgress.ready(client, currentLinkToken() + ":" + grants.progressGrantEpoch);
	}

	/** The collection scan for every linked player. A grant holder uses the account-progress capture instead. */
	boolean collectionScanReady()
	{
		if (grants.progressGranted() || !uploadAllowed())
		{
			collectionScan.reset();
			return false;
		}
		return collectionScan.ready(client, currentLinkToken() + ":collection");
	}

	/** The capture that scans this player's collection log now, or null. */
	AccountProgressCapture activeCollection()
	{
		return progressReady() ? accountProgress : collectionScanReady() ? collectionScan : null;
	}

	Map<String, Object> progressBlock()
	{
		return progressReady() ? accountProgress.snapshot(client) : null;
	}
	// Activity-log capture (verified vs runelite-api 1.12.32 gameval enums):
	static final int SHOP_GROUP_ID = InterfaceID.SHOPMAIN;					// 300
	static final MediaType JPEG = MediaType.parse("image/jpeg");	// store delivery-proof burst frames
	final GeSlots ge = new GeSlots(this);

	@Inject
	Client client;

	@Inject
	AccountConnectConfig config;

	@Inject
	Gson gson;

	@Inject
	OkHttpClient okHttpClient;

	@Inject
	ItemManager itemManager;

	@Inject
	DrawManager drawManager;

	@Inject
	ScheduledExecutorService executor;

	@Inject
	InfoBoxManager infoBoxManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	ConfigManager configManager;

	@Inject
	private FlipperController flipper;

	@Inject
	ClientThread clientThread;

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
		if (flipper != null)
		{
			flipper.startUp(this);
		}
		outbox.publicOutboxPaused = false;
		clientClosing = false;
		removeOrphanedKeys();
		events.restorePendingEvents();	// what the last close or stop could not deliver
		// Started while already in the world: the GE slots were replayed before we were listening, so the
		// next LOGGED_IN (a region load) is not a login and must not open a replay window.
		ge.awaitingLogin = client == null || client.getGameState() != GameState.LOGGED_IN;
		if (!ge.awaitingLogin)
		{
			// Enabled while in the world: no replay will come, so take the slots as they stand now as the
			// baseline. The first real change is then judged against the truth, and the seed itself emits
			// nothing. The GE maps are client-thread state, so seed there.
			if (clientThread != null)
			{
				clientThread.invoke(ge::seed);
			}
			else
			{
				ge.seed();
			}
		}
		if (overlayManager != null)
		{
			resetOverlay = new StoreResetOverlay(this);
			overlayManager.add(resetOverlay);
			nearbyOverlay = new StoreNearbyOverlay(this);
			overlayManager.add(nearbyOverlay);
		}
	}

	/**
	 * shutDown's clear of client-thread state: the phase history, and GE events held at hash -1. A held event
	 * that survived a disable could become the next account's baseline after a re-enable at the login screen.
	 */
	void clearClientThreadStateOnShutdown()
	{
		grants.clearDeliveryCaptureGrant();
		grants.collectionGrantAccount = null;
		grants.pluginInventoryGrantToken = null;
		grants.clearVerificationGrant();
		store.shopOpen = false; // re-enable must wait for a new shop-open event
		ge.reset();
		session.lastSkillLevel.clear();		// re-enabled on another account: its levels are a baseline, not level-ups
		session.lastSkillXp.clear();
		accountProgress.reset();	// disabled plugins miss hop/relog callbacks
		collectionScan.reset();
		ownGameplay.reset();
		store.clearResetPhaseEvidence();
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
		if (flipper != null)
		{
			flipper.shutDown();
		}
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
		clips.stopStoreClipCapture(false);	// unregister the render listener + drop any buffered frames, no upload
		dropProof.interruptDropSession();		// a shutdown mid-session still publishes what was captured
		// A countdown that outlives the plugin is a lie left on the user's screen — and RuneLite does
		// not clear a plugin's infoboxes for it.
		store.storeResetAnchorMs = 0;
		// The phase history is client-thread state that a running shop handler may be iterating, and shutDown
		// runs on the Swing thread. Clear it on the client thread, like the GE seed.
		if (clientThread != null)
		{
			clientThread.invoke(this::clearClientThreadStateOnShutdown);
		}
		else
		{
			clearClientThreadStateOnShutdown();
		}
		store.removeResetTimer();
		// A disabled plugin sees no GameStateChanged, so a bank session left here could later be diffed
		// against another account's bank. Discard it without a row.
		bankVisit.bankAtOpen = null;
		bankVisit.bankBaselinePending = false;
		bankVisit.bankGrossDeposited.clear();
		bankVisit.bankGrossWithdrawn.clear();
		bankVisit.dropBankVisit();
		events.persistPendingEvents();	// queued events survive a plugin stop; startUp sends them again
		spooler.closeSpool();	// rows stay on disk; the next process recovers them under the same identity
	}
	volatile boolean clientClosing;
	volatile String activeRsn;
	// RuneScapeProfileType of the session (STANDARD / BETA / SEASONAL / DEADMAN / ...), refreshed on every
	// logged-in syncTask tick. Events from a non-STANDARD profile carry it, so League, beta and Deadman rows
	// never mix with the main game's GE, loot or bank history under the same account hash.
	volatile String activeProfileType;
	volatile String activeHash;
	final EventObservationContext eventObservationContext = new EventObservationContext();
	/** Tick of the last real login or hop (not a scene LOADING). The account varps arrive just after it. */
	int worldLoginTick = Integer.MIN_VALUE / 2;
	/**
	 * Ticks after a login or hop before a snapshot is built. The client keeps the previous account's skills
	 * after a logout and updates them one StatChanged at a time at login (live 2026-10-23: a snapshot 0.4 s
	 * after a switch carried four skills of the account that had just logged out).
	 */
	static final int LOGIN_SETTLE_TICKS = 5;

	boolean loginSettled()
	{
		return client.getTickCount() - worldLoginTick >= LOGIN_SETTLE_TICKS;
	}
	/** Ticks after a login before a zero membership-days varp is read as "no membership" (3 s). */
	static final int MEMBERS_SETTLE_TICKS = 5;
	/**
	 * Colour/formatting tags in a menu option, e.g. the "&lt;col=ff9040&gt;" the client appends to SELL
	 * options. Stripped before the quantity is read — the tag's own hex digits are not a quantity.
	 */
	static final Pattern MENU_TAG = Pattern.compile("<[^>]*>");
	/** gameval INVENTORY container id (93) — matches ItemContainerChanged.getContainerId(), not legacy InventoryID. */
	static final int INVENTORY_CONTAINER_ID = net.runelite.api.gameval.InventoryID.INV;
	/** gameval WORN container id — the equipment container, diffed to emit equip_change. */
	static final int EQUIP_CONTAINER_ID = net.runelite.api.gameval.InventoryID.WORN;
	/** gameval BANK container id: its first contents after an empty open become the bank-move baseline. */
	static final int BANK_CONTAINER_ID = net.runelite.api.gameval.InventoryID.BANK;

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
		dropProof.enforceDropSessionTokenIdentity();
		if (grants.broadCaptureToken != null && !grants.broadCaptureToken.equals(currentLinkToken()))
		{
			grants.setBroadCaptureGrant(null, false);
		}
		spooler.syncSpoolState();	// a cleared or changed token purges the old identity's durable spool
		if (event != null && CONFIG_GROUP.equals(event.getGroup()) && "linkToken".equals(event.getKey())
			&& !trimmed(event.getOldValue()).equals(trimmed(event.getNewValue())))
		{
			grants.clearVerificationGrant();
			grants.clearDeliveryCaptureGrant();
			grants.collectionGrantAccount = null;
			grants.pluginInventoryGrantToken = null;
			spooler.spoolOnTokenChangedWithoutAccount();
			outbox.revokePublicOutbox(null);
			grants.eventQueueGrantToken = null;
			grants.eventQueueScope = null;
			grants.progressGrantToken = null;
			grants.progressGrantEpoch++;
			grants.gameplayGrantToken = null;
			grants.gameplayGrantEpoch++;
			grants.setBroadCaptureGrant(null, false);
			grants.clearStaffCaptureGrant();
			uploader.snapshotRetryPending = false;
			// A visit belongs to the token that was granted at its open: never finish it under another one.
			bankVisit.bankEvidenceToken = null;
			bankVisit.dropBankVisit();
		}
	}

	private static String trimmed(String s)
	{
		return s == null ? "" : s.trim();
	}

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
	void submitStoreClipUpload(List<byte[]> frames, List<Long> capturedAtMillis)
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
		final String captureAccount = activeHash;
		final List<byte[]> allFrames = new ArrayList<>(frames);
		final List<Long> allTimes = new ArrayList<>(capturedAtMillis);
		executor.submit(() ->
		{
			// Frames arrive ALREADY ENCODED and already inside the visit budget (StoreVisitClip).
			// selectStoreClipFrames still filters null / oversized entries as a second line.
			List<Integer> kept = StoreClips.selectStoreClipFrameIndexes(
				allFrames, StoreClips.MAX_CLIP_FRAMES, StoreClips.MAX_CLIP_FRAME_BYTES, StoreClips.MAX_CLIP_BURST_BYTES);
			if (kept.isEmpty())
			{
				return;
			}
			String base = config.apiBaseUrl() == null ? "" : config.apiBaseUrl().replaceAll("/+$", "");
			for (int off = 0; off < kept.size(); off += StoreClips.CLIP_CHUNK_FRAMES)
			{
				List<Integer> idx = kept.subList(off, Math.min(off + StoreClips.CLIP_CHUNK_FRAMES, kept.size()));
				List<byte[]> chunk = new ArrayList<>(idx.size());
				for (int i : idx)
				{
					chunk.add(allFrames.get(i));
				}
				// Each chunk carries the REAL capture time of its own first frame. The visit is not one
				// continuous span any more (start, moments, baseline), so a time derived from the frame
				// count would be wrong; the collector orders chunks on this value.
				long chunkAtMillis = allTimes.get(idx.get(0));
				clips.postStoreClipChunk(base, token, chunk, chunkAtMillis / 1000L, 0, false, captureAccount);
			}
		});
	}

	/** Run a store-clip retry after delayMs. A seam so tests can drive the ladder without waiting. */
	void scheduleClipRetry(Runnable retry, long delayMs)
	{
		executor.schedule(retry, delayMs, TimeUnit.MILLISECONDS);
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

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		events.onClientShutdown(event);
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
		outbox.pumpPublicOutbox();
		events.flushEvents();
		spooler.replaySpoolTick();
	}

	@Schedule(period = 5, unit = ChronoUnit.SECONDS)
	public void syncTask()
	{
		uploader.syncTask();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_BUILD && gameplayReady())
			ownGameplay.bankTabs.built(client, System.currentTimeMillis());
		if (event.getScriptId() == 7797 && activeCollection() != null)
		{
			collectionSetupPending = true;
		}
	}

	/** Each obtained collection-log slot fires script 4100 (arg[1] = item id) as the clog UI renders. */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != SCRIPT_CLOG_DRAW)
		{
			return;
		}
		// NOT ANOTHER PLAYER'S LOG. In a player-owned house the adventure log shows the HOST's collection log
		// through the same script, and the game sets COLLECTION_POH_HOST_BOOK_OPEN while it is open. Those
		// items belong to someone else, so they are never added to this account (the guard every audited
		// collection-log uploader uses: WikiSync, RuneProfile, DropTracker).
		if (client == null || !Integer.valueOf(0).equals(AccountProgressCapture.bit(client, client.getVarps(), 12061)))
		{
			return;
		}
		Object[] args = event.getScriptEvent() == null ? null : event.getScriptEvent().getArguments();
		AccountProgressCapture collection = activeCollection();
		if (collection != null)
		{
			collection.collectionSlot(client, args, System.currentTimeMillis() / 1000L);
		}
		if (args != null && args.length > 2 && args[1] instanceof Integer && (Integer) args[1] > 0
			&& args[2] instanceof Integer && (Integer) args[2] > 0)
		{
			clogObtained.add((Integer) args[1]);
			clogSeen = true;
		}
	}

	void refreshProfileType()
	{
		activeProfileType = client == null || client.getWorldType() == null ? "UNKNOWN"
			: RuneScapeProfileType.getCurrent(client).name();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		session.onGameStateChanged(event);
	}

	// ---- trade screenshot: state machine (IDLE -> tradeActive -> ARMED frame -> commit/discard) ----

	/** Our trade-offer container (gameval id 90) changing marks a trade as genuinely in progress. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (OwnLootKeysCapture.supports(event.getContainerId()) && gameplayReady())
			ownGameplay.lootKeys.container(client, event.getContainerId(), event.getItemContainer(), System.currentTimeMillis());
		if (event.getContainerId() == BANK_CONTAINER_ID && gameplayReady())
			ownGameplay.bankTabs.container(event.getContainerId(), event.getItemContainer(), client.getTickCount(), System.currentTimeMillis());
		if (progressReady()) accountProgress.storageRecovery.container(client, event.getContainerId(),
			event.getItemContainer(), System.currentTimeMillis());
		snapshots.containerObservedAtMs.put(event.getContainerId(), System.currentTimeMillis());
		trade.handleTradeContainerChanged(event.getContainerId());
		if (store.shopOpen && event.getContainerId() != INVENTORY_CONTAINER_ID)
		{
			store.handleShopStockChanged(event.getItemContainer());
		}
		// WAVE 6 (store price): resolve a store buy/sell against the live inventory coin count. Read the coins
		// from the changed container itself (final post-transaction state) — independent of the trade gate above.
		if (event.getContainerId() == INVENTORY_CONTAINER_ID)
		{
			ItemContainer inv = event.getItemContainer();
			long coinsAfter = snapshots.countItem(inv, SnapshotBuilder.COINS_ID);
			int tick = client == null ? 0 : client.getTickCount();
			// The transacted item's post count too: its distance from the pending's baseline is the
			// quantity the server ACTUALLY moved, which is what licenses a derived unit price.
			// Rows resolved here report this change: a forced build names one only if no build came first.
			uploader.triggerChangeBuildSeq = uploader.snapshotBuildSeq;
			try
			{
				store.resolveStorePendingsOnInventoryChange(coinsAfter, inv == null ? null : id -> snapshots.countItem(inv, id), tick);
				// Off-book drop/pickup/alch resolve on the same INVENTORY change from the item-count delta.
				// EVERY armed pending is offered this change, each against its own item count — see
				// resolveInvDeltaPendings: a stale drop at the head must never hide a real pickup behind it.
				offBook.resolveInvDeltaPendings(inv, coinsAfter, tick);
			}
			finally
			{
				uploader.triggerChangeBuildSeq = -1;
			}
		}
		if (event.getContainerId() == INVENTORY_CONTAINER_ID && rewardLoot.armed())
		{
			kills.emitRewardLoot(client == null ? 0 : client.getTickCount(), KillActivity.inventoryCounts(event.getItemContainer()));
		}
		if (event.getContainerId() == EQUIP_CONTAINER_ID)
		{
			session.handleEquipmentChanged(event.getItemContainer());
		}
		if (event.getContainerId() == BANK_CONTAINER_ID)
		{
			snapshots.rememberBank(event.getItemContainer());
			Item[] changed = event.getItemContainer() == null ? null : event.getItemContainer().getItems();
			if (client != null && changed != null && Arrays.stream(changed).anyMatch(snapshots::realItem))
			{
				bankVisit.bankChangeTick = client.getTickCount();
			}
			bankVisit.handleBankContainerChanged(event.getItemContainer());
		}
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		session.onWorldChanged(event);
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
		kills.trackSlayerTask();
		kills.trackActivityCounters();
		kills.readMinigameScreens();
		kills.readMinigameDialogue();
		verify.runVerification();
		events.refreshEventObservationContext();
		session.finishPendingWorldHop();
		bankVisit.finishPendingBankClose();
		bankVisit.finishPublicBankClose(false);
		if (uploader.deferredForce)
		{
			// A trigger name stays valid only if no snapshot was built since the event asked for one.
			uploader.deferredForce = false;
			uploader.forceSendSnapshot(uploader.deferredForceBuildSeq == uploader.snapshotBuildSeq ? uploader.deferredForceTrigger : null);
		}
		if (collectionSetupPending || collectionLogOpenTick >= 0 && client.getTickCount() - collectionLogOpenTick >= 2)
		{
			// Run here, not inside the setup script: the scan runs a client script of its own. collectionSetup starts
			// at most one scan per open, and only once the Search toggle is on screen.
			collectionSetupPending = false;
			AccountProgressCapture collection = activeCollection();
			if (collection != null)
			{
				collection.collectionSetup(client, System.currentTimeMillis());
			}
		}
		if (gameplayReady()) ownGameplay.poll(client, System.currentTimeMillis());
		if (progressReady())
		{
			accountProgress.poll(client, System.currentTimeMillis());
		}
		else if (collectionScanReady())
		{
			collectionScan.pollCollection(client, System.currentTimeMillis());
		}
		// Poll the other player's trade offer + name while the MAIN screen (335) is open — the only window
		// they are readable (see captureOtherOfferWhileMainOpen). Tight-gated: the body runs only during an
		// active trade with activity logging on; a cheap boolean check otherwise.
		if (trade.tradeMainOpen && activityLogActive())
		{
			trade.captureOtherOfferWhileMainOpen();
		}
		if (store.shopOpen && activityLogActive())
		{
			store.accumulateShopNearby();		// build the receiver-candidate set across the whole shop visit
		}
		if (!ge.unkeyed.isEmpty() && client != null && client.getGameState() == GameState.LOGGED_IN)
		{
			ge.resolveUnkeyed(client.getAccountHash());	// GE events held at hash -1: judge once the account is known
		}
		ge.flushDue();	// partial fills held by the per-minute throttle, sent once their window passes
		if (activityLogActive())
		{
			if (++session.xpFlushTicks >= SessionEvents.XP_FLUSH_TICKS)
			{
				session.xpFlushTicks = 0;
				session.flushXpGain();		// coalesced xp_gain every 5 minutes
			}
			session.checkRegionChange();	// region {from,to} on map-region change
		}
		// RuneLite's Timer infobox removes ITSELF when it reaches zero, so a countdown armed at one
		// observed reset covers exactly one 60s cycle and then vanishes. The phase is still known,
		// so re-arm for the next cycle. This extrapolates from a REAL observed anchor, never a
		// guess, and the next observed reset re-anchors it.
		if (store.shopOpen && store.resetTimer == null && StoreTools.resetPhaseKnown(store.storeResetAnchorMs))
		{
			store.reArmAttempts++;
			store.showResetTimer();
		}
		if (client != null)
		{
			piles.settlePendingRemovals(client.getTickCount(), true, false);
			dropProof.pollDropSession();	// the 5-second tail after the last pile, checked once per tick
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		trade.handleTradeWidgetLoaded(event.getGroupId());
		handleActivityWidgetLoaded(event.getGroupId());
		bankVisit.handleCaptureOnOpenWidgetLoaded(event.getGroupId());
		kills.handleChestLootWidgetLoaded(event.getGroupId());
		if (MinigameLines.SCREEN_GROUPS.contains(event.getGroupId()) && client != null)
		{
			kills.pendingMinigameScreens.put(event.getGroupId(), client.getTickCount());
		}
		if (event.getGroupId() == BankVisit.COLLECTION_LOG_GROUP_ID && client != null)
		{
			collectionLogOpenTick = client.getTickCount();
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
			store.shopOpen = true;
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
			store.shopVisitNearby.clear();	// fresh receiver-candidate set for this shop visit
			store.shopStock.clear();		// baseline is taken from this visit's first container change
			store.storeResetAnchorMs = 0;		// a new visit starts with the phase UNKNOWN
			store.lastStockChangeMs = 0;
			store.clearResetPhaseEvidence();
			store.storeProbeItem = 0;		// and with no probe until the first item is sold
			store.soldThisVisit.clear();
			store.defaultStockSoldThisVisit.clear();
			store.lastSellTickThisVisit.clear();
			store.lastOwnStoreClickMs = 0;
			store.shopkeeperName = store.currentShopkeeperName();
			store.shopkeeperIndex = store.currentShopkeeperIndex();
			store.nearbyAtTx = null;
			store.accumulateShopNearby();		// seed with whoever is already standing here at open
			clips.startStoreClipCapture();	// arm burst capture for this visit (no-op unless opt-in + server-allowed)
		}
		else if (groupId == TradeCapture.TRADE_MAIN_GROUP_ID)
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
			trade.pendingCounterparty = null;
			trade.pendingCounterpartyCombatLevel = null;
			trade.pendingTradeReceived = null;
			trade.pendingReceivedText = null;
			// Main trade screen open: begin polling the other player's side (readable here, gone by confirm).
			// onGameTick keeps the buffer current; the last read before the 334 transition = the final offer.
			trade.tradeMainOpen = true;
			trade.captureOtherOfferWhileMainOpen();	// initial read (offer may still be empty; the poll catches it)
		}
		else if (groupId == TradeCapture.TRADE_CONFIRM_GROUP_ID)
		{
			trade.tradeMainOpen = false;	// moved to confirm — stop polling; the last 335 poll holds the final offer
			trade.pendingTradeGiven = trade.readOwnOffer();	// own offer container (90) persists across the transition
			// Counterparty + received[] come from the 335 poll above (the only place they are readable). Fall
			// back to the confirm screen ONLY when the poll captured nothing — counterpartyName() is usually
			// null here (335 title gone) and readReceivedOffer() reads the value-text column.
			if (!grants.captureEnabled())
			{
				trade.pendingCounterparty = null;
				trade.pendingCounterpartyCombatLevel = null;
			}
			else if (trade.pendingCounterparty == null || trade.pendingCounterparty.isEmpty())
			{
				trade.pendingCounterparty = trade.counterpartyName();
				trade.pendingCounterpartyCombatLevel = trade.visibleCounterpartyCombatLevel(trade.pendingCounterparty);
			}
			if (trade.pendingTradeReceived == null || trade.pendingTradeReceived.isEmpty())
			{
				trade.pendingTradeReceived = trade.readReceivedOffer();
			}
			// Lossless text fallback only when we still have no structured items (e.g. big "Lots!" trade the
			// poll missed) — keeps the existing "no received_text when items exist" contract. Staff only:
			// the text is the other player's offer as the game wrote it.
			if (grants.captureEnabled() && (trade.pendingTradeReceived == null || trade.pendingTradeReceived.isEmpty())
				&& (trade.pendingReceivedText == null || trade.pendingReceivedText.isEmpty()))
			{
				trade.pendingReceivedText = trade.readReceivedText();
			}
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
		uploader.widgetClosing = true;
		try
		{
			widgetClosed(event);
		}
		finally
		{
			uploader.widgetClosing = false;
		}
	}

	private void widgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == BankVisit.COLLECTION_LOG_GROUP_ID)
		{
			collectionSetupPending = false;
			collectionLogOpenTick = -1;
			accountProgress.collectionClosed();
			collectionScan.collectionClosed();
		}
		if (event.getGroupId() == BankVisit.BANK_GROUP_ID) accountProgress.storageRecovery.gap();
		trade.handleTradeWidgetClosed(event.getGroupId());
		bankVisit.handleBankWidgetClosed(event.getGroupId(), bankVisit.bankCloseIsTeardown());
		if (event.getGroupId() == SHOP_GROUP_ID)
		{
			store.shopOpen = false;
			store.flushStorePendingOnShopClose();
			clips.stopStoreClipCapture(true);	// end the visit; upload only if it had a buy/sell, else drop
			// The reset phase is only valid while the shop is open — drop it rather than show a stale
			// countdown on the next visit. Re-probing costs one junk item; a wrong number costs the item.
			store.storeResetAnchorMs = 0;
			store.clearResetPhaseEvidence();
			store.removeResetTimer();
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
		// Specific system lines supply milestone events and, when separately granted,
		// bounded own-account progress observations. This is the narrow form the comment above allows. It is NOT the broad sweep:
		// no raw chat line is emitted, no other player's name is read, and no other channel is examined.
		if (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM)
		{
			if (gameplayReady()) ownGameplay.activities.chat(client, event, System.currentTimeMillis());
			if (progressReady())
			{
				accountProgress.chat(event.getMessage(), System.currentTimeMillis() / 1000L);
			}
			verificationMessage = EventVerification.clean(event.getMessage());
			verificationMessageHandled = false;
			try
			{
				kills.emitParsedMilestones(Text.removeTags(event.getMessage() == null ? "" : event.getMessage()).trim());
				kills.emitKillDetail(event.getMessage());
				kills.emitProgressAndFailure(event.getMessage());
				if (!verificationMessageHandled && verify.discoveryReady())
					verify.emitDiscovery(verificationDiscovery.message(client, event.getType(), event.getMessage(), System.currentTimeMillis()));
			}
			finally { verificationMessage = null; verificationMessageHandled = false; }
			return;
		}
		if (event.getType() != ChatMessageType.TRADE)
		{
			return;
		}
		// A completed trade changes wealth: refresh the snapshot cache NOW (independent of the
		// screenshot opt-in) so the logout flush carries post-trade wealth even if no tick has run
		// since the trade. Client thread; containers are valid right after "Accepted trade.".
		if (TradeCapture.TRADE_ACCEPTED_MESSAGE.equalsIgnoreCase(
			Text.removeTags(event.getMessage() == null ? "" : event.getMessage()).trim()))
		{
			uploader.refreshSnapshotCacheAfterTrade();
			trade.emitTradeEvent();
		}
		trade.handleTradeChat(event.getMessage());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (gameplayReady()) ownGameplay.endNpc(client, event.getNpc(), false, System.currentTimeMillis());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (gameplayReady()) ownGameplay.hit(client, event.getActor(), event.getHitsplat(), System.currentTimeMillis());
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (verify.discoveryReady())
			for (Map<String, Object> source : verificationDiscovery.poll(client, System.currentTimeMillis())) verify.emitDiscovery(source);
		verify.pollEventVerification();
		if (gameplayReady()) ownGameplay.interfaces.clientTick(client, System.currentTimeMillis());
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (gameplayReady()) ownGameplay.scene.spawned(client, event.getGameObject(), System.currentTimeMillis());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		if (gameplayReady()) ownGameplay.scene.despawned(event.getGameObject());
	}

	/**
	 * On "Accepted trade.", emit a structured "trade" event from what was captured at the confirm screen:
	 * the items WE gave and received. PublicTransport removes legacy partner/context/text fields
	 * before either memory or disk enqueue, and again before transmission of old queued rows.
	 */
	void putWorldContext(Map<String, Object> fields)
	{
		if (fields == null || client == null)
		{
			return;
		}
		int world = client.getWorld();
		if (world > 0)
		{
			fields.put("world", world);
		}
		Set<WorldType> types = client.getWorldType();
		if (types != null)
		{
			fields.put("world_members", types.contains(WorldType.MEMBERS));
		}
	}

	/** General-store buy/sell: a Buy/Sell menu click while the shop (SHOPMAIN 300) is open. Own-account. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (gameplayReady()) ownGameplay.consumption.menu(client, event);
		if (progressReady()) accountProgress.storageRecovery.menu(client, event, System.currentTimeMillis());
		// Delivery-proof tx flag — hoisted ABOVE the activity-log gate (grill F5): a clip's buy/sell must
		// not be dropped just because activity logging is off. Only relevant while a clip is capturing.
		if (clips.clipCapturing && store.shopOpen && StoreTools.isStoreBuyOrSell(event))
		{
			clips.storeTxThisVisit = true;
			clips.markStoreClipMoment();
		}
		// Off-book value events (drop / pickup / alch): own gate (not shop-scoped) — arm an inventory-delta
		// pending resolved on the next INVENTORY change. BEFORE the store gate so the shop-open early-return
		// below never swallows it.
		offBook.maybeArmInvDeltaPending(event);
		if (!store.shopOpen || !activityLogActive())
		{
			return;
		}
		String type = StoreTools.storeTxType(event);
		if (type == null)
		{
			return;
		}
		store.lastOwnStoreClickMs = nowMs();	// the shop changes that follow our own click never support the cycle phase
		if ("store_buy".equals(type))
		{
			// OUR OWN BUY-BACK. The seller's own procedure has an explicit emergency step — if the
			// buyer cannot take the item in time, buy it back — so our own buy of an item we just
			// sold is a NORMAL part of the method, not a customer taking it. Without this the fall
			// that our own purchase causes is reported as a counterparty: measured on live events
			// 3914798/3914799, where a buy-back and a store_taken landed in the SAME SECOND and the
			// plugin still named a candidate. Record the item so handleShopStockChanged can suppress
			// the inference.
			store.lastSelfBuyItem = event.getItemId();
			store.lastSelfBuyAtMs = System.currentTimeMillis();
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
		long coinsBefore = snapshots.countItem(invNow, SnapshotBuilder.COINS_ID);
		long itemBefore = invNow == null ? StoreTools.UNKNOWN_ITEM_COUNT : snapshots.countItem(invNow, item);
		int tick = client == null ? 0 : client.getTickCount();
		int qty = OffBookPendings.parseTrailingQty(opt);
		// Snapshot who is standing here AT THE CLICK. The visit-wide accumulator answers a weaker
		// question ("who was around at some point"); a dispute is about the moment value moved.
		store.nearbyAtTx = store.nearbyPlayersSnapshot(StoreTools.NEARBY_FIELD_CAP);
		if ("store_sell".equals(type))
		{
			// Default stock is judged ONCE, at our FIRST sell of this item this visit: after that the shop
			// stock includes units we put in ourselves, which must never make our delivery look native.
			if (store.soldThisVisit.add(item) && store.shopStock.getOrDefault(item, 0) > 0)
			{
				store.defaultStockSoldThisVisit.add(item);	// the shop already stocked it before we sold: default stock
			}
			store.lastSellTickThisVisit.put(item, tick);
			if (store.storeProbeItem == 0)
			{
				// FIRST sell of the visit = the junk probe. Only its disappearance moves the reset
				// clock; everything after it is merchandise whose disappearance means a customer.
				store.storeProbeItem = item;
			}
		}
		store.storePendings.values().removeIf(p -> tick - p.tick > StoreTools.STORE_PENDING_MAX_TICKS);	// failed clicks
		// Removed and put back so a repeat click moves the item to the end: click order is emit order.
		StoreTools.StorePending prev = store.storePendings.remove(item);
		store.storePendings.put(item, StoreTools.mergeStorePending(prev, type, item, qty, coinsBefore, itemBefore, tick));
	}
	/** Stand-in for the event_id that emitEventBound adds later: same length (32 hex), so it is measured. */
	static final String EVENT_ID_PLACEHOLDER = "00000000000000000000000000000000";

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		offBook.onItemSpawned(event);
	}

	@Subscribe
	public void onItemQuantityChanged(ItemQuantityChanged event)
	{
		offBook.onItemQuantityChanged(event);
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		piles.onProjectileMoved(event);
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		piles.onGraphicsObjectCreated(event);
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		piles.onItemDespawned(event);
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		death.onActorDeath(event);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		session.onStatChanged(event);
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		ge.offerChanged(event);
	}

	@Subscribe
	public void onVarbitChanged(net.runelite.api.events.VarbitChanged event)
	{
		ge.taxVarChanged(event);
		ge.minuteVarChanged(event);
	}

	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		kills.onServerNpcLoot(event);
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		kills.onPlayerLootReceived(event);
	}

	// ---- trade screenshot: capture + upload ----

	/** Safe Image -> BufferedImage copy (the frame-listener contract only guarantees Image). */
	static BufferedImage toBufferedImage(Image img)
	{
		if (img instanceof BufferedImage)
		{
			return (BufferedImage) img;
		}
		BufferedImage bi = new BufferedImage(
			img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = bi.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return bi;
	}

	/** In-memory encode via JDK ImageIO (same encoder core ImageCapture uses). fmt e.g. "png". Null on failure. */
	static byte[] encodeImage(BufferedImage image, String fmt)
	{
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try
		{
			ImageIO.write(image, fmt, buf);
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
		ImageWriter writer = null;
		try (ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ImageOutputStream ios = ImageIO.createImageOutputStream(buf))
		{
			writer = ImageIO.getImageWritersByFormatName("jpg").next();
			writer.setOutput(ios);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			// 0.55, lowered from 0.70 on 2026-09-02 to fit 360 frames (30fps x 12s) in the then 12MB burst
			// cap: 30KB/frame at 704px vs 45KB at 768px/0.70. Legibility was re-checked at this setting,
			// not assumed — a clip nobody can read is not evidence however smooth it is.
			param.setCompressionQuality(0.55f);
			writer.write(null, new IIOImage(image, null, null), param);
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
}
