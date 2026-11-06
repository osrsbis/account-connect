package com.osrsbestinslot.export;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import okhttp3.Request;
import okhttp3.RequestBody;

/** The account snapshot upload: change and debounce gates, forced and logout sends, the policy heartbeat, grant stripping at send, bank records and the 429 backoff. */
@Slf4j
final class SnapshotUploader
{
	private final AccountConnectPlugin plugin;

	SnapshotUploader(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

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
	/**
	 * Every buildSnapshot counts here. A row recorded at its item change with the count of that moment
	 * names a forced build as its trigger only while the count is unchanged: that build is then the first
	 * snapshot holding the change. A second row of the same change finds the count moved and names none.
	 */
	long snapshotBuildSeq;
	/** The build count when the inventory change being handled arrived; -1 outside that handler. */
	long triggerChangeBuildSeq = -1;
	volatile String lastUploadedHash;
	volatile long lastSendMillis;
	volatile long backoffUntilMillis;
	volatile long nextBackoffMillis = BACKOFF_START_MILLIS;
	// The bank block of the newest snapshot queued for upload: its token and account, its manifest hash and whether
	// its read had a known time. Cleared when that upload fails.
	volatile String queuedBankToken;
	volatile String queuedBankAccount;
	volatile String queuedBankSha;
	volatile boolean queuedBankTimed;
	// The same for the newest snapshot the server ACCEPTED (2xx). A close is flagged only against this.
	volatile String acceptedBankToken;
	volatile String acceptedBankAccount;
	volatile String acceptedBankSha;
	volatile boolean acceptedBankTimed;
	// A snapshot forced by an event inside a widget-close script waits for the next game tick (a build runs scripts).
	boolean widgetClosing;
	boolean deferredForce;
	String deferredForceTrigger;
	long deferredForceBuildSeq;
	volatile boolean snapshotRetryPending;
	// State-changing events whose wealth/state must land immediately also force a snapshot. Chat / level_up
	// are excluded (too frequent / not wealth-moving); login is bound separately by syncTask's new-login send.
	private static final Set<String> SNAPSHOT_TRIGGER_EVENTS = new HashSet<>(
		Arrays.asList("trade", "ge_buy", "ge_sell", "ge_cancel", "store_buy", "store_sell", "death", "drop", "alch"));

	/**
	 * State-changing events (trade / ge_* / store_* / death) push a fresh snapshot immediately so post-event
	 * wealth lands live. forceSendSnapshot honors all the usual guards (logged in, token, backoff)
	 * and is async. client is null only in buffer-only unit tests — real runtime always has it injected.
	 */
	void maybeForceSnapshotForEvent(String type, String eventId)
	{
		if (plugin.client != null && SNAPSHOT_TRIGGER_EVENTS.contains(type))
		{
			// trigger_event_id: this row's item change came with no build since, so the forced build is the
			// first snapshot holding it. Readers place the row inside that snapshot even in the same ms.
			long changeSeq = triggerChangeBuildSeq;
			String trigger = changeSeq >= 0 && changeSeq == snapshotBuildSeq ? eventId : null;
			if (widgetClosing)
			{
				// Inside a widget-close script (a shop closed with a buy still pending): build on the next tick.
				if (!deferredForce || deferredForceTrigger == null)
				{
					deferredForceTrigger = trigger;
					deferredForceBuildSeq = snapshotBuildSeq;
				}
				deferredForce = true;
				return;
			}
			forceSendSnapshot(trigger);
		}
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
		forceSendSnapshot(null);
	}

	/** {@code triggerEventId}: the row whose item change this build is the first to hold, or null. */
	void forceSendSnapshot(String triggerEventId)
	{
		if (plugin.clientClosing || plugin.client.getGameState() != GameState.LOGGED_IN || plugin.client.getLocalPlayer() == null)
		{
			return;
		}
		String token = plugin.config.linkToken() == null ? "" : plugin.config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return; // no / malformed token configured yet
		}
		if (System.currentTimeMillis() < backoffUntilMillis)
		{
			return; // server-imposed 429 backoff still wins — a manual force never overrides it
		}
		if (!plugin.loginSettled())
		{
			return;
		}
		Map<String, Object> snapshot = plugin.snapshots.buildSnapshot();
		if (triggerEventId != null && triggerEventId.matches("^[0-9a-f]{32}$"))
		{
			snapshot.put("trigger_event_id", triggerEventId);
		}
		String hash = plugin.snapshots.canonicalHash(snapshot);
		lastBuiltSnapshot = snapshot;	// keep the logout-flush cache coherent, exactly like syncTask
		lastBuiltHash = hash;
		postSnapshot(token, snapshot, hash);
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
		String token = plugin.config.linkToken() == null ? "" : plugin.config.linkToken().trim();
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
	void refreshSnapshotCacheAfterTrade()
	{
		if (plugin.client.getGameState() != GameState.LOGGED_IN || plugin.client.getLocalPlayer() == null)
		{
			return;
		}
		String token = plugin.config.linkToken() == null ? "" : plugin.config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return;
		}
		Map<String, Object> snapshot = plugin.snapshots.buildSnapshot();
		lastBuiltSnapshot = snapshot;
		lastBuiltHash = plugin.snapshots.canonicalHash(snapshot);
	}

	static void stripBroadCapture(Map<String, Object> snapshot)
	{
		snapshot.remove("game_vars");
		snapshot.remove("group_storage");
		for (String key : SnapshotBuilder.OWN_STORAGE_CONTAINERS.keySet()) snapshot.remove(key);
	}

	/**
	 * Async snapshot POST. The response drives the upload gate's memory: a 2xx accept records the
	 * uploaded hash (so unchanged ticks stop re-sending) and clears any backoff; a 429 arms the
	 * backoff window from Retry-After (or exponential). hash is the CANONICAL hash of this snapshot
	 * — recorded on accept, never the raw JSON hash.
	 */
	void postSnapshot(String token, Map<String, Object> snapshot, String hash)
	{
		if (!plugin.uploadAllowed() || !token.equals(plugin.currentLinkToken()))
		{
			return;		// upload switch off — send nothing
		}
		final Map<String, Object> sentSnapshot = new LinkedHashMap<>(snapshot);
		// Shared group storage follows the same explicit broad-capture grant, not staff identity.
		if (!plugin.grants.broadCaptureEnabled()) stripBroadCapture(sentSnapshot);
		// Recheck separate grants at serialization, including cached logout snapshots.
		if (!plugin.grants.progressGranted() || !token.equals(plugin.grants.progressGrantToken)) sentSnapshot.remove("account_progress");
		if (!plugin.grants.gameplayGranted() || !token.equals(plugin.grants.gameplayGrantToken)) sentSnapshot.remove("own_gameplay");
		if (!plugin.grants.collectionAccountRequired || !plugin.grants.collectionAccountMatches() || !token.equals(plugin.grants.pluginInventoryGrantToken)) sentSnapshot.remove("plugin_inventory");
		final String sentHash = plugin.snapshots.canonicalHash(sentSnapshot);
		Object sentBank = sentSnapshot.get("bank");
		Object sentBankSha = sentBank instanceof Map ? ((Map<?, ?>) sentBank).get("manifest_sha256") : null;
		Object sentAccount = sentSnapshot.get("account");
		Object sentHashValue = sentAccount instanceof Map ? ((Map<?, ?>) sentAccount).get("account_hash") : null;
		final String bankAccount = sentHashValue instanceof String ? (String) sentHashValue : null;
		final String bankSha = sentBankSha instanceof String ? (String) sentBankSha : null;
		final boolean bankTimed = sentBank instanceof Map && ((Map<?, ?>) sentBank).get("observed_at") != null;
		queuedBankToken = token;
		queuedBankAccount = bankAccount;
		queuedBankSha = bankSha;
		queuedBankTimed = bankTimed;
		Map<String, Object> body = Fields.of("token", token, "snapshot", sentSnapshot);

		String base = plugin.config.apiBaseUrl() == null ? "" : plugin.config.apiBaseUrl().replaceAll("/+$", "");
		Request request = new Request.Builder()
			.url(base + "/account-ingest")
			.post(RequestBody.create(AccountConnectPlugin.JSON, plugin.gson.toJson(body)))
			.build();

		plugin.okHttpClient.newCall(request).enqueue(EventQueue.answered(e ->
		{
			if (token.equals(plugin.currentLinkToken()))
			{
				plugin.grants.clearVerificationGrant();
				plugin.grants.clearDeliveryCaptureGrant();
				plugin.grants.pluginInventoryGrantToken = null;
				if (plugin.grants.collectionAccountRequired) plugin.grants.collectionGrantAccount = null;
				snapshotRetryPending = true;
				applyRateLimitBackoff(null);
			}
			forgetQueuedBank(bankSha);
			log.debug("OSRS BiS sync failed", e);
		}, response ->
		{
			if (!token.equals(plugin.currentLinkToken())) return; // stale identity cannot grant or ACK this cache
			int code = response.code();
			plugin.grants.applyServerPolicy(response, token);	// server dictates cadence / screenshot-disable per token
			if (code == 403)
			{
				plugin.spooler.spoolOnRevoked(token);	// a revoked link: its durable spool is purged
			}
			if (response.isSuccessful())
			{
				acceptedBankToken = token;
				acceptedBankAccount = bankAccount;
				acceptedBankSha = bankSha;
				acceptedBankTimed = bankTimed;
				onUploadAccepted(sentHash);
				Object gv = sentSnapshot.get("game_vars");
				if (gv instanceof Map)
				{
					plugin.snapshots.lastGameVarsSentMs = System.currentTimeMillis();
				}
			}
			else
			{
				snapshotRetryPending = true;
				applyRateLimitBackoff(response.header("Retry-After"));
				forgetQueuedBank(bankSha);
			}
			// All failed snapshot requests remain pending behind bounded retry backoff.
			log.debug("OSRS BiS sync response: {}", code);
		}));
	}

	/** A failed upload: a newer one may have replaced the queued record, so only the same bank is forgotten. */
	private void forgetQueuedBank(String sha)
	{
		if (sha != null && sha.equals(queuedBankSha))
		{
			queuedBankSha = null;
		}
	}

	/** A 2xx accept: this canonical hash is now the server's known state, and any 429 backoff clears. */
	private void onUploadAccepted(String hash)
	{
		snapshotRetryPending = false;
		lastUploadedHash = hash;
		plugin.events.healthLastSnapshotUploadMs.set(System.currentTimeMillis());
		backoffUntilMillis = 0L;
		nextBackoffMillis = BACKOFF_START_MILLIS;
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

	void syncTask()
	{
		if (plugin.clientClosing || plugin.client.getGameState() != GameState.LOGGED_IN || plugin.client.getLocalPlayer() == null)
		{
			return;
		}
		String token = plugin.config.linkToken() == null ? "" : plugin.config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return; // no / malformed token configured yet
		}
		// WAVE 3: sample idle counters on the last logged-in tick so a later logout can be classified idle/manual.
		plugin.session.lastKeyboardIdleTicks = plugin.client.getKeyboardIdleTicks();
		plugin.session.lastMouseIdleTicks = plugin.client.getMouseIdleTicks();
		// Resolve a deferred death once the containers have settled (a few ticks after ActorDeath). If a syncTask
		// fires too soon after death, wait for the next one — items may not be removed yet.
		if (plugin.death.deathPending != null && plugin.client.getTickCount() - plugin.death.deathPending.tick >= Death.DEATH_SETTLE_TICKS)
		{
			plugin.death.resolveDeathPendingViaLiveContainers();
		}
		if (!plugin.loginSettled())
		{
			return;	// the skills still hold the previous account's values until the login stat burst lands
		}
		plugin.refreshProfileType();
		// Build + cache the snapshot FIRST so trackSessionStart's login event can carry wealth-at-login (WAVE 3)
		// and so the new-login force-send below ships this exact snapshot.
		Map<String, Object> snapshot = plugin.snapshots.buildSnapshot();
		String hash = plugin.snapshots.canonicalHash(snapshot);
		// Track on EVERY tick regardless of the gates below: the logout flush sends this cache.
		lastBuiltSnapshot = snapshot;
		lastBuiltHash = hash;

		boolean newLogin = plugin.session.trackSessionStart(); // emits a "login" event on the first tick of a session / after a hop

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
		if (hash.equals(lastUploadedHash) && (snapshot.containsKey("game_vars") || snapshotRetryPending))
		{
			// game_vars is outside the change hash. Failures remain pending but obey the same
			// debounce and error backoff as changed state; they cannot create a five-second loop.
			if (now - lastSendMillis < minUploadIntervalMillis) return;
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
			// The staff-capture grant (screenshots, clips, other-player readers) gets the same heartbeat.
			// It deliberately does NOT force a send for a client whose grant is off. That direction
			// is a GRANT arriving, not a revocation, and failing to hear about it only delays a
			// feature. Failing to hear about a revocation keeps a screen recorder alive.
			if (!plugin.grants.dropProofEnabled() && !plugin.grants.captureEnabled()
				|| now - lastSendMillis < SnapshotUploader.DROP_PROOF_POLICY_HEARTBEAT_MILLIS)
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
}
