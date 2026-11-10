package com.osrsbestinslot.export;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import okhttp3.MultipartBody;
import okhttp3.Response;

/** Server-dictated policy: every grant bound to the token (and account) the server named, the off switches, the rollout flags and the checks that read them. */
@RequiredArgsConstructor
final class ServerGrants
{
	private final AccountConnectPlugin plugin;

	volatile String pluginInventoryGrantToken;
	volatile String collectionGrantAccount;
	volatile boolean collectionAccountRequired;

	boolean collectionAccountMatches()
	{
		if (!collectionAccountRequired) return true; // compatibility with earlier servers, not new grants
		return collectionGrantAccount != null && captureAccountMatches(collectionGrantAccount);
	}

	private long lastPluginInventoryMs;
	static final long PLUGIN_INVENTORY_INTERVAL_MS = 15 * 60 * 1000L;

	Map<String, Object> pluginInventoryBlock(long now)
	{
		if (!collectionAccountRequired || !collectionAccountMatches() || !plugin.currentLinkToken().equals(pluginInventoryGrantToken) || plugin.pluginManager == null
			|| now - lastPluginInventoryMs < PLUGIN_INVENTORY_INTERVAL_MS) return null;
		Map<String, Object> result = PluginInventoryCapture.read(plugin.pluginManager, now);
		if (result != null) lastPluginInventoryMs = now;
		return result;
	}

	volatile String gameplayGrantToken;
	volatile long gameplayGrantEpoch;

	/** The current link token is a well-formed token and is the one this grant was answered for. */
	private boolean granted(String grantToken)
	{
		String token = plugin.currentLinkToken();
		return token != null && token.matches("[a-f0-9]{32}") && token.equals(grantToken);
	}

	boolean gameplayGranted()
	{
		return granted(gameplayGrantToken) && collectionAccountMatches();
	}

	volatile String progressGrantToken;
	volatile long progressGrantEpoch;

	boolean progressGranted()
	{
		return granted(progressGrantToken) && collectionAccountMatches();
	}

	/**
	 * Kill detail: fight/raid/lap timings (kill_timing), the Hallowed Sepulchre and Guardians of the Rift count lines,
	 * and reward-pool loot (Tempoross, Wintertodt, Guardians of the Rift, Herbiboar). Off until the server answers this
	 * exact token with X-Kill-Detail: on, so a release can carry the code before ingest stores the rows. A refused,
	 * failed or missing answer turns it off.
	 */
	private volatile String killDetailGrantToken;

	boolean killDetailGranted()
	{
		return granted(killDetailGrantToken) && collectionAccountMatches();
	}

	/**
	 * Kill context: worn items, inventory, boosted levels and special energy at each kill count, slayer task
	 * completions, the account-progress lines as events, and raid failures. Off until the server answers this exact
	 * token with X-Kill-Context: on. It is a separate grant from X-Kill-Detail because it says more about the player
	 * (supplies and gear at every kill), so the server can grant it to some links and not others.
	 */
	private volatile String killContextGrantToken;

	boolean killContextGranted()
	{
		return granted(killContextGrantToken) && collectionAccountMatches() && plugin.activityLogActive();
	}

	/**
	 * Verification (VerificationProbe): cross-check rows and verification frames, on occasions and at random moments.
	 * Off until the server answers this exact token with X-Verification: on, a separate collection grant.
	 * A refused, failed or missing answer turns it off; it grants no staff access.
	 */
	volatile String verificationGrantToken;
	volatile String verificationFramesGrantToken;

	boolean verificationGranted()
	{
		return granted(verificationGrantToken) && plugin.activityLogActive();
	}

	boolean verificationFramesEnabled()
	{
		return verificationGranted() && plugin.currentLinkToken().equals(verificationFramesGrantToken);
	}

	/** Unmasked whole-canvas uploads are restricted to the account-bound staff grant; public links keep region evidence. */
	boolean fullWindowCaptureAllowed()
	{
		return verificationFramesEnabled() && staffCaptureEnabled();
	}

	void clearVerificationGrant()
	{
		verificationGrantToken = null;
		verificationFramesGrantToken = null;
		plugin.verificationEpoch.incrementAndGet();
		plugin.verificationProbe.reset();
		plugin.eventVerification.reset();
		plugin.verificationDiscovery.reset();
		plugin.lastSnapshotCapturedAtMs = 0;
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
	 * BROAD CAPTURE (batch 1). Server-granted by X-Broad-Capture: on, default OFF, no config item. It turns on
	 * game_vars (below) and the own-storage container blocks (OWN_STORAGE_CONTAINERS).
	 * When on, the snapshot carries the player's own varp array (GameVarsCapture), from which the server decodes
	 * quests, diaries, combat achievement tasks, Slayer, prayers, Sailing, farming and every other varbit. It is
	 * controlled by its separate account-scoped grant and explicit off switches.
	 */
	volatile boolean serverGameVarsEnabled;
	volatile String broadCaptureToken;

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
		return devOverride("osrsbis.storetools");
	}

	private static boolean devOverride(String property)
	{
		String v = System.getProperty(property);
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
		return (serverStoreToolsEnabled || storeToolsDevOverride()) && plugin.uploadAllowed();
	}

	/**
	 * STAFF CAPTURE: trade screenshots, store and drop clips, and other players near a shop or a drop.
	 * One plugin for everyone (2026-10-28): there is no separate staff build.
	 * Off unless the server answered this exact link token with X-Staff-Capture: on and named the account
	 * in X-Staff-Account, and that account is the one logged in now. The server sends that only for an
	 * approved staff token. No setting, system property or other header can turn it on.
	 *
	 * ONLY osrsbestinslot.com CAN GRANT IT. "API base URL" is an ordinary setting, so any server a player
	 * points it at could otherwise answer X-Staff-Capture: on and switch on the other-player readers and the
	 * recorders. The grant is taken only from a response to the official endpoint, and it holds only while
	 * the setting still names that endpoint.
	 */
	static final String OFFICIAL_API_BASE = "https://www.osrsbestinslot.com/wp-json/osrsbis/v1";
	/** The only endpoint whose answer can grant staff capture. Tests point it at their own server. */
	String staffApiBase = OFFICIAL_API_BASE;
	private volatile String staffCaptureGrantToken;
	private volatile String staffCaptureGrantAccount;

	private boolean onStaffApi(String url)
	{
		String base = staffApiBase.replaceAll("/+$", "");
		String u = url == null ? "" : url.trim().replaceAll("/+$", "");
		return u.equals(base) || u.startsWith(base + "/");
	}

	private boolean configuredForStaffApi()
	{
		return plugin.config != null && onStaffApi(plugin.config.apiBaseUrl());
	}

	void clearStaffCaptureGrant()
	{
		staffCaptureGrantToken = null;
		staffCaptureGrantAccount = null;
	}

	/** Test seam: the server's X-Staff-Capture grant for this token and account, as applyServerPolicy stores it. */
	void setStaffCapture(String token, String account)
	{
		staffCaptureGrantToken = token;
		staffCaptureGrantAccount = account;
	}

	boolean staffCaptureEnabled()
	{
		return accountGrant(staffCaptureGrantToken, staffCaptureGrantAccount);
	}

	/**
	 * An account-bound grant: this token, uploads allowed, the staff API, and the grant's account both as the session's
	 * account and as the live client's (activeHash follows a relog only at the next session start; the client knows at once).
	 */
	private boolean accountGrant(String token, String account)
	{
		return token != null && account != null && plugin.uploadAllowed() && configuredForStaffApi()
			&& token.equals(plugin.currentLinkToken()) && captureAccountMatches(account);
	}


	private volatile String deliveryCaptureToken;
	private volatile String deliveryCaptureAccount;
	private volatile boolean deliveryScreenshotsAllowed;
	private volatile boolean deliveryClipsAllowed;
	private volatile boolean deliveryDropProofAllowed;

	/** Collection permission only: never grants staff identity, overlays, API access or quota exemptions. */
	boolean deliveryCaptureEnabled()
	{
		return accountGrant(deliveryCaptureToken, deliveryCaptureAccount);
	}

	boolean captureEnabled()
	{
		return staffCaptureEnabled() || deliveryCaptureEnabled();
	}

	void clearDeliveryCaptureGrant()
	{
		deliveryCaptureToken = null;
		deliveryCaptureAccount = null;
		deliveryScreenshotsAllowed = false;
		deliveryClipsAllowed = false;
		deliveryDropProofAllowed = false;
	}


	boolean captureAccountMatches(String account)
	{
		if (!Objects.equals(account, plugin.activeHash)) return false;
		long live = plugin.client == null ? -1L : plugin.client.getAccountHash();
		return live == -1L || Objects.equals(account, Long.toString(live));
	}

	static MultipartBody withMediaAccount(MultipartBody original, String account)
	{
		MultipartBody.Builder builder = new MultipartBody.Builder().setType(original.type());
		for (MultipartBody.Part part : original.parts()) builder.addPart(part);
		builder.addFormDataPart("account_hash", account == null ? "" : account);
		return builder.build();
	}
	volatile String eventQueueGrantToken;
	volatile String eventQueueScope;
	volatile String bankJournalGrantToken;

	/**
	 * Trade screenshots require an account-bound staff or delivery collection grant to
	 * this token and the logged-in account (staffCaptureEnabled), including before the first server response.
	 */
	boolean screenshotsEnabled()
	{
		return (staffCaptureEnabled() && !serverScreenshotsDisabled)
			|| (deliveryCaptureEnabled() && deliveryScreenshotsAllowed);
	}

	/** osrsbestinslot.com can force store-clip capture OFF for a token via the X-Clips response header
	 *  (it can never force it ON — that stays a local opt-in, mirroring serverScreenshotsDisabled). */
	volatile boolean serverClipsDisabled;

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
	 *
	 * ⚠ EXCEPT FOR THE WILDERNESS SURROUNDING SNAPSHOTS. dropProofEnabled() is ALSO the only gate on
	 * `surrounding_at_drop` and `surrounding_at_trade`, and /event-ingest does NOT re-check staff for
	 * event fields. For those two fields this flag IS the authorization, and it holds only because
	 * the server sends X-Store-Tools and X-Drop-Proof to staff tokens alone. An operator `X-Clips: off`
	 * on a staff token also switches both snapshots off.
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
		return (staffCaptureEnabled() && storeToolsEnabled() && serverDropProofEnabled
			&& (!serverClipsDisabled || dropProofDevOverride()))
			|| (deliveryCaptureEnabled() && deliveryClipsAllowed && deliveryDropProofAllowed);
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
		return devOverride("osrsbis.dropproof");
	}

	/** Store-clip capture requires a linked token AND no server force-disable (both read live per call). */
	boolean storeClipsEnabled()
	{
		return (staffCaptureEnabled() && !serverClipsDisabled)
			|| (deliveryCaptureEnabled() && deliveryClipsAllowed);
	}

	boolean broadCaptureEnabled()
	{
		return serverGameVarsEnabled && broadCaptureToken != null
			&& broadCaptureToken.equals(plugin.currentLinkToken()) && collectionAccountMatches();
	}

	void setBroadCaptureGrant(String token, boolean enabled)
	{
		broadCaptureToken = enabled ? token : null;
		serverGameVarsEnabled = enabled;
		if (!enabled)
		{
			plugin.snapshots.lastGameVarsSentMs = 0;
			Map<String, Object> cached = plugin.uploader.lastBuiltSnapshot;
			if (cached != null)
			{
				Map<String, Object> clean = new LinkedHashMap<>(cached);
				SnapshotUploader.stripBroadCapture(clean);
				plugin.uploader.lastBuiltSnapshot = clean;
				plugin.uploader.lastBuiltHash = plugin.snapshots.canonicalHash(clean);
			}
		}
	}

	/**
	 * Apply the per-token policy osrsbestinslot.com returns on the ingest response. All directives are
	 * controls for declared collection scopes: cadence, collection grants, off switches and upload pause.
	 * Collection grants never assign staff identity or change user-facing configuration text.
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
		applyServerPolicy(response, plugin.currentLinkToken());
	}

	/** A policy header's switch: TRUE for on/enabled/true/1, FALSE for off/disabled/false/0, null if absent or other. */
	static Boolean policyFlag(Response response, String name)
	{
		String v = response.header(name);
		v = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
		if (v.equals("on") || v.equals("enabled") || v.equals("true") || v.equals("1")) return Boolean.TRUE;
		if (v.equals("off") || v.equals("disabled") || v.equals("false") || v.equals("0")) return Boolean.FALSE;
		return null;
	}

	/** X-Screenshots / X-Clips: off, disabled or false switches the capture off ("0" does not, as before). */
	static boolean offValue(String v)
	{
		v = v.trim().toLowerCase(Locale.ROOT);
		return v.equals("off") || v.equals("disabled") || v.equals("false");
	}

	/** The token a grant header binds to: the sent token on a successful response whose header is exactly "on". */
	static String grantFor(Response response, String name, String sentToken)
	{
		String v = response.header(name);
		return response.isSuccessful() && v != null && "on".equalsIgnoreCase(v.trim()) ? sentToken : null;
	}

	/**
	 * sentToken is the token the request carried. The spool grant is applied only when it is still the
	 * configured token, so a late response for an old token can never switch the new identity on.
	 *   X-Event-Spool      "on"/"enabled"/"true"/"1" turns the durable event spool on for this token;
	 *                      "off"/"disabled"/"false"/"0" turns it off and purges this identity's spool.
	 *                      Absent or unparseable = unchanged; the default is off.
	 */
	void applyServerPolicy(Response response, String sentToken)
	{
		if (sentToken == null || !sentToken.equals(plugin.currentLinkToken())) return;
		String collectionAccount = response.header("X-Collection-Account");
		if (collectionAccount != null) collectionAccountRequired = true;
		if (collectionAccountRequired) collectionGrantAccount = response.isSuccessful()
			&& collectionAccount != null && collectionAccount.matches("-?[0-9]{1,19}") ? collectionAccount : null;
		String queueScope = response.header("X-Event-Queue");
		if (response.isSuccessful() && ("ge-v1".equals(queueScope) || "own-history-v1".equals(queueScope)))
		{
			eventQueueScope = queueScope;
			eventQueueGrantToken = sentToken;
		}
		else { eventQueueGrantToken = null; eventQueueScope = null; }

		// No existing broad/progress grant widens the capture scope. Missing/failed response revokes.
		String gameplayGrant = grantFor(response, "X-Own-Gameplay", sentToken);
		if (!Objects.equals(gameplayGrant, gameplayGrantToken)) gameplayGrantEpoch++;
		gameplayGrantToken = gameplayGrant;

		// Separate batch2 disclosure gate: batch1's X-Broad-Capture does not grant these fields.
		String progress = response.header("X-Account-Progress");
		if (progress != null && sentToken != null && sentToken.equals(plugin.currentLinkToken()))
		{
			String grant = "on".equalsIgnoreCase(progress.trim()) && response.isSuccessful() ? sentToken : null;
			if (!Objects.equals(grant, progressGrantToken))
			{
				progressGrantEpoch++;
			}
			progressGrantToken = grant;
		}
		if (response.code() == 403 && sentToken != null && sentToken.equals(progressGrantToken))
		{
			progressGrantToken = null;
			progressGrantEpoch++;
			gameplayGrantToken = null;
			gameplayGrantEpoch++;
		}
		String contextGrant = grantFor(response, "X-Kill-Context", sentToken);
		if (!Objects.equals(contextGrant, killContextGrantToken))
		{
			plugin.slayerTasks.reset();
			plugin.activityCounters.reset();
		}
		killContextGrantToken = contextGrant;
		String verificationGrant = grantFor(response, "X-Verification", sentToken);
		if (!Objects.equals(verificationGrant, verificationGrantToken))
		{
			plugin.verificationEpoch.incrementAndGet();
			plugin.verificationProbe.reset();
			if (verificationGrant != null) plugin.verificationProbe.occasion("verification_ready", null);
		}
		verificationGrantToken = verificationGrant;
		String frameGrant = verificationGrant != null && "on".equalsIgnoreCase(response.header("X-Verification-Frames", "").trim())
			? sentToken : null;
		if (!Objects.equals(frameGrant, verificationFramesGrantToken)) plugin.verificationEpoch.incrementAndGet();
		verificationFramesGrantToken = frameGrant;
		killDetailGrantToken = grantFor(response, "X-Kill-Detail", sentToken);
		Boolean eventSpool = policyFlag(response, "X-Event-Spool");
		if (eventSpool != null) plugin.spooler.setSpoolGrant(sentToken, eventSpool);

		// X-Bank-Evidence: on grants bank-visit evidence to THIS token. Missing or anything else = off, so a
		// grant lasts only while the newest snapshot response keeps sending it.
		plugin.bankVisit.bankEvidenceToken = Boolean.TRUE.equals(policyFlag(response, "X-Bank-Evidence")) ? sentToken : null;
		// Distinct public capability: old servers and staff grants never enable this journal.
		bankJournalGrantToken = response.isSuccessful() && sentToken.equals(plugin.bankVisit.bankEvidenceToken)
			&& !sentToken.equals(plugin.spooler.spoolGrantToken) && "event-id-v1".equals(response.header("X-Bank-Receipt"))
			? sentToken : null;


		// The server grant binds this exact account and link token to staff media/evidence.
		// A late response for a previous account never opens a new account's recorder.
		// An accepted upload without the grant ends it, and so does any refusal (4xx). Only a 429 or a 5xx
		// says nothing about staff, so only those keep it and leave a running store clip alone.
		boolean previouslyGranted = captureEnabled();
		if (!configuredForStaffApi() || !onStaffApi(response.request().url().toString()))
		{
			clearStaffCaptureGrant();
		}
		else if (response.isSuccessful())
		{
			String staffCapture = response.header("X-Staff-Capture");
			String staffAccount = response.header("X-Staff-Account");
			if ("on".equalsIgnoreCase(staffCapture == null ? "" : staffCapture.trim())
				&& staffAccount != null && staffAccount.trim().matches("-?[0-9]{1,19}"))
			{
				setStaffCapture(sentToken, staffAccount.trim());
			}
			else
			{
				clearStaffCaptureGrant();
			}
		}
		else if (response.code() >= 400 && response.code() < 500 && response.code() != 429)
		{
			clearStaffCaptureGrant();
		}
		// Independent delivery capture grant; the legacy staff grant and all staff access stay unchanged.
		String deliveryAccount = response.header("X-Delivery-Account");
		if (response.isSuccessful() && configuredForStaffApi() && onStaffApi(response.request().url().toString())
			&& "on".equalsIgnoreCase(response.header("X-Delivery-Capture", ""))
			&& deliveryAccount != null && deliveryAccount.matches("-?[0-9]{1,19}"))
		{
			deliveryCaptureAccount = deliveryAccount;
			deliveryCaptureToken = sentToken;
			deliveryScreenshotsAllowed = "allow".equalsIgnoreCase(response.header("X-Delivery-Screenshots", ""));
			deliveryClipsAllowed = "allow".equalsIgnoreCase(response.header("X-Delivery-Clips", ""));
			deliveryDropProofAllowed = "on".equalsIgnoreCase(response.header("X-Delivery-Drop-Proof", ""));
		}
		else clearDeliveryCaptureGrant();
		if (response.header("X-Screenshots") != null)
		{
			serverScreenshotsDisabled = offValue(response.header("X-Screenshots"));
		}
		if (response.header("X-Store-Tools") != null)
		{
			serverStoreToolsEnabled = Boolean.TRUE.equals(policyFlag(response, "X-Store-Tools"));
			if (!serverStoreToolsEnabled) plugin.store.removeResetTimer();	// a revoked grant must clear what is already on screen
		}
		// Either header can end staff capture. A store clip recording when it ends is dropped, not sent.
		if (previouslyGranted && !captureEnabled())
		{
			plugin.clips.stopStoreClipCapture(false);
			plugin.store.removeResetTimer();
		}


		String inventoryGrant = grantFor(response, "X-Plugin-Inventory", sentToken);
		if (!Objects.equals(pluginInventoryGrantToken, inventoryGrant)) lastPluginInventoryMs = 0;
		pluginInventoryGrantToken = inventoryGrant;
		setBroadCaptureGrant(sentToken, response.isSuccessful() && Boolean.TRUE.equals(policyFlag(response, "X-Broad-Capture")));
		if (response.header("X-Clips") != null)
		{
			serverClipsDisabled = offValue(response.header("X-Clips"));
		}

		// THE ROLLOUT FLAG. Absent header leaves it unchanged, exactly like every other directive,
		// because a single malformed response must not silently revoke a live rollout. It starts
		// false and only an explicit on-value ever sets it, so the fail-closed default holds.
		if (response.header("X-Drop-Proof") != null)
		{
			serverDropProofEnabled = Boolean.TRUE.equals(policyFlag(response, "X-Drop-Proof"));
		}
		// A withdrawn capability must not leave a recorder running.
		plugin.dropProof.onDropProofCapabilityChanged();

		boolean paused = false;
		String uploadsEnabled = response.header("X-Uploads-Enabled");
		if (uploadsEnabled != null)
		{
			String v = uploadsEnabled.trim().toLowerCase(Locale.ROOT);
			paused = "false".equals(v) || "0".equals(v) || "off".equals(v);
			// An operator pause holds spool REPLAY only. Writes continue within bounds; nothing is purged.
			plugin.spooler.spoolReplayPaused = paused;
		}

		String interval = response.header("X-Sync-Interval");
		if (paused)
		{
			plugin.uploader.minUploadIntervalMillis = 600_000L;	// soft-pause: max cadence, still polls for re-enable
		}
		else if (interval != null)
		{
			try
			{
				long s = Long.parseLong(interval.trim());
				plugin.uploader.minUploadIntervalMillis = Math.max(5L, Math.min(s, 600L)) * 1000L;
			}
			catch (NumberFormatException ignored)
			{
				// malformed directive — keep the current cadence
			}
		}
		plugin.outbox.pumpPublicOutbox();
	}
}
