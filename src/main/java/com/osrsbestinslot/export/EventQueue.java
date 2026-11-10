package com.osrsbestinslot.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.events.ClientShutdown;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** The memory event queue and its sender: emit, priority-bounded buffer, coalesced flush, retry ladders, the exit file, the close flush and capture health. */
@Slf4j
@RequiredArgsConstructor
final class EventQueue
{
	private final AccountConnectPlugin plugin;

	/**
	 * Own-account activity log (syncActivityLog opt-in): buffered structured events — logins/logouts,
	 * and later GE / general-store / trade / item movements — POSTed to /event-ingest. Only the user's
	 * OWN account data; nothing about other players is sent to the default backend. Written on the
	 * client thread, flushed on the @Schedule tick.
	 */
	final List<Map<String, Object>> pendingEvents =
		Collections.synchronizedList(new ArrayList<>());
	static final int MAX_PENDING_EVENTS = 500;
	/** Internal-only queue binding. Removed before POST; never sent to the server. */
	static final String EVENT_TOKEN_FP = "__delivery_token_fp";
	static final String EVENT_QUEUE = "__delivery_queue_v1";
	static final String EVENT_PUBLIC_BANK = "__public_bank_receipt";
	static final String EVENT_PUBLIC_OUTBOX = "__public_outbox_context";
	static final String EVENT_PUBLIC_VOLATILE = "__public_outbox_volatile";

	/** Server ingest limits: at most 100 events and under 256 KiB of UTF-8 per request. */
	static final int EVENT_BATCH_MAX_EVENTS = 100;
	static final long EVENT_BATCH_MAX_BYTES = 256L * 1024L;
	// Cumulative capture-health counters for this plugin process. They intentionally live outside
	// pendingEvents so the health record can ride in ordinary snapshots without recursively generating
	// another event. Public journal gaps/bounds are separate counters; other public families remain volatile.
	private final long healthProcessStartedAtMs = System.currentTimeMillis();
	private final AtomicLong healthEventsQueued = new AtomicLong();
	private final AtomicLong healthEventsSent = new AtomicLong();
	final AtomicLong healthEventsLost = new AtomicLong();
	final QueueDeliveryHealth queueDeliveryHealth = new QueueDeliveryHealth();
	// Memory overflow by priority class. Journal-backed evictions remain replayable, not lost.
	private final AtomicLongArray healthOverflowByClass = new AtomicLongArray(3);
	final AtomicLong healthDropSegmentsSent = new AtomicLong();
	final AtomicLong healthDropSegmentsRefused = new AtomicLong();
	final AtomicLong healthDropSegmentsLost = new AtomicLong();
	final AtomicLong healthDropFramesRejected = new AtomicLong();
	final AtomicLong healthLastEventUploadMs = new AtomicLong();
	final AtomicLong healthLastMediaUploadMs = new AtomicLong();
	final AtomicLong healthLastSnapshotUploadMs = new AtomicLong();

	/** Event-delivery retry state. Base equals the flush period, so the first retry is the next tick. */
	static final long EVENT_RETRY_BASE_BACKOFF_MS = 5_000L;
	/** Ceiling on the doubling ladder — 5s, 10s, 20s … capped at 5 minutes. */
	static final long EVENT_RETRY_MAX_BACKOFF_MS = 300_000L;
	volatile boolean eventPostInFlight;
	volatile long eventRetryBackoffMs;
	volatile long queueRetryBackoffMs;
	volatile long queueRetryBackoffUntilMs;
	volatile long eventRetryBackoffUntilMs;

	// ---- WAVE 2: real-time sync ----
	// On any emitEvent we flush live instead of waiting for the 5s eventFlushTask. A burst (e.g. rapid chat
	// lines) is micro-coalesced into a single ~1s window so it becomes ONE /event-ingest POST, not one HTTP
	// call per line (still sub-second-to-~1s = real-time). The first event opens the window; the rest ride it.
	private static final long EVENT_COALESCE_MILLIS = 1000L;
	long eventCoalesceMillis = EVENT_COALESCE_MILLIS;	// field (not const) so tests can shrink the window
	private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

	String currentEventTokenFingerprint()
	{
		String token = plugin.currentLinkToken();
		return token.matches("^[a-f0-9]{32}$") ? EventSpool.sha256Hex("event-token:" + token) : null;
	}

	/**
	 * Buffer one own-account activity event. No-op unless the activity log is active (token linked +
	 * account not opted out). account_hash/rsn are the currently-tracked session's, stamped at emit time
	 * so a logout (player already null) still self-describes. Bounded to MAX_PENDING_EVENTS (drop oldest).
	 */
	/**
	 * Overflow of the pending buffer: drop the oldest row of the LOWEST priority class present, not the oldest
	 * row overall, so a long 429 run keeps GE, trade, store, death and session rows (EventPriority). Caller holds
	 * the pendingEvents lock.
	 */
	void trimPending()
	{
		while (pendingEvents.size() > MAX_PENDING_EVENTS) evictOneForOverflow();
	}

	void evictOneForOverflow()
	{
		int i = EventPriority.evictIndex(pendingEvents);
		Map<String, Object> gone = pendingEvents.remove(i < 0 ? 0 : i);
		Object t = gone.get("type");
		healthOverflowByClass.incrementAndGet(EventPriority.classOf(t == null ? null : String.valueOf(t)));
		plugin.spooler.spoolReleaseFromMemory(gone);
		if (!gone.containsKey(EVENT_PUBLIC_OUTBOX) || Boolean.TRUE.equals(gone.get(EVENT_PUBLIC_VOLATILE)))
			healthEventsLost.incrementAndGet();
	}

	/**
	 * True when the client is in the world as another account than the row names. The hash decides; while
	 * it still reads -1 just after a login, the player name decides (it cannot change inside a session).
	 */
	private boolean stampedForAnotherAccount(Object stampedHash, Object stampedRsn)
	{
		if (stampedHash == null || plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		long live = plugin.client.getAccountHash();
		if (live != -1L)
		{
			return !String.valueOf(stampedHash).equals(Long.toString(live));
		}
		Player self = plugin.client.getLocalPlayer();
		String name = self == null ? null : self.getName();
		return name != null && stampedRsn != null && !name.equals(stampedRsn);
	}

	void emitEvent(String type, Map<String, Object> fields)
	{
		emitEventBound(type, fields, currentEventTokenFingerprint());
		if (plugin.grants.verificationGranted() && !EventVerification.TYPES.contains(type) && !"kill_context".equals(type))
			plugin.verificationProbe.occasion(type, fields);
	}

	void emitEventBound(String type, Map<String, Object> fields, String tokenFingerprint)
	{
		if (("verification".equals(type) || "verification_discovery".equals(type)) && !plugin.grants.verificationGranted()) return;
		if (!PublicTransport.allowsEvent(type, plugin.grants.captureEnabled())) return;
		String liveFingerprint = currentEventTokenFingerprint();
		if (tokenFingerprint == null || !tokenFingerprint.equals(liveFingerprint))
		{
			if (tokenFingerprint != null)
			{
				healthEventsLost.incrementAndGet();
			}
			return;
		}
		Map<String, Object> ev = Fields.of("type", type, "ts", System.currentTimeMillis());
		ev.put(EVENT_TOKEN_FP, tokenFingerprint);
		if (plugin.activeHash != null)
		{
			ev.put("account_hash", plugin.activeHash);
		}
		if (plugin.activeRsn != null)
		{
			ev.put("rsn", plugin.activeRsn);
		}
		String profile = plugin.activeProfileType;
		if (profile != null)
		{
			ev.put("profile_type", profile);
		}
		if (fields != null)
		{
			ev.putAll(fields);
		}
		// After a logout and a login to another account, activeHash names the old account until the next
		// syncTask starts the new session. A row stamped with it would file this account's activity under the
		// other one, so it is dropped. Rows that carry their own account (GE) are kept when it is the live one.
		if (stampedForAnotherAccount(ev.get("account_hash"), ev.get("rsn")))
		{
			healthEventsLost.incrementAndGet();
			return;
		}
		// Generated ONCE, before either queue sees the event, so a retry and a replay carry the same id.
		String eventId = EventSpool.newEventId();
		ev.put("event_id", eventId);
		// Generated once before durable append; caller fields cannot forge collector context.
		ev.remove("observation_context");
		String contextOwner = Objects.equals(plugin.activeHash, ev.get("account_hash"))
			&& Objects.equals(plugin.activeProfileType, ev.get("profile_type")) ? observationOwner() : null;
		Map<String,Object> context = plugin.eventObservationContext.event(contextOwner, AccountConnectPlugin.PLUGIN_VERSION,
			((Number)ev.get("ts")).longValue());
		if (context != null) ev.put("observation_context", context);
		boolean verifyAccount = plugin.grants.verificationGranted() && plugin.client != null && plugin.activeHash != null && plugin.client.getAccountHash() != -1L
			&& plugin.activeHash.equals(Long.toString(plugin.client.getAccountHash()));
		if (verifyAccount)
		{
			plugin.verificationDiscovery.bind(plugin.currentLinkToken(), plugin.activeHash, plugin.verificationEpoch.get());
			if ("activity_counter".equals(type)) plugin.verificationDiscovery.counter(ev, plugin.client.getTickCount());
		}
		boolean eventEvidence = verifyAccount && EventVerification.TYPES.contains(type);
		if (eventEvidence)
		{
			if (plugin.verificationMessage != null && !"verification_discovery".equals(type)) plugin.verificationMessageHandled = true;
			ev.put("verification_tick", plugin.client.getTickCount());
			ev.put("verification_revision", plugin.lastSnapshotCapturedAtMs > 0 ? plugin.lastSnapshotCapturedAtMs : null);
			String text = EventVerification.clean(plugin.verificationMessage);
			if (text != null) ev.put("verification_text", text);
		}
		ev = PublicTransport.event(ev, plugin.grants.captureEnabled());
		if (ev == null) { healthEventsLost.incrementAndGet(); return; }
		if (eventEvidence)
		{
			EventVerification.Source source = new EventVerification.Source(ev, plugin.currentLinkToken(), plugin.activeHash,
				plugin.verificationEpoch.get(), plugin.client.getTickCount(), plugin.lastSnapshotCapturedAtMs, System.currentTimeMillis(), plugin.verificationMessage);
			// The hourly admission is charged only for a source the pending queue accepts (client thread: room, then offer).
			boolean queued = false;
			plugin.verify.evictExpiredSources();
			if (!plugin.eventVerification.room()) plugin.verify.eventEvidenceUnknown(source, "pending_capacity");
			else if (!plugin.verificationProbe.takeEvent(System.currentTimeMillis()))
			{
				if (plugin.verificationProbe.reportEventBudget()) plugin.verify.eventEvidenceUnknown(source, "event_budget");
			}
			else if (!(queued = plugin.eventVerification.offer(source))) plugin.verify.eventEvidenceUnknown(source, "pending_capacity");
			if ("verification_discovery".equals(type)) plugin.discoveryEvidenceQueued = queued;
		}
		ev.put(EVENT_TOKEN_FP, tokenFingerprint);
		healthEventsQueued.incrementAndGet();
		if (plugin.outbox.capturePublicOutbox(ev))
		{
			plugin.uploader.maybeForceSnapshotForEvent(type, eventId);
			return;
		}
		if (("combat_hit".equals(type) || "combat_npc_end".equals(type) || "own_signal".equals(type)
			|| "own_state".equals(type) || "own_vars".equals(type)) && !plugin.legacyOwnCombatAllowed(type))
		{
			// A grant/identity race cannot send own-player events after permission is withdrawn.
			healthEventsLost.incrementAndGet(); return;
		}
		plugin.spooler.spoolOnEmit(type, ev, eventId);
		synchronized (pendingEvents)
		{
			pendingEvents.add(ev);
			trimPending();
		}
		// WAVE 2: real-time. A state-changing event forces a fresh snapshot so wealth/state lands now; every
		// event schedules a coalesced flush so it ships in ~1s instead of on the next 5s tick.
		plugin.uploader.maybeForceSnapshotForEvent(type, eventId);
		scheduleCoalescedFlush();
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
		if (plugin.executor == null)
		{
			return;
		}
		if (flushScheduled.compareAndSet(false, true))
		{
			plugin.executor.schedule(() ->
			{
				flushScheduled.set(false);
				flushEvents();
			}, eventCoalesceMillis, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * CLIENT CLOSE. Closing RuneLite while logged in used to lose the session end and every buffered event:
	 * no logout row was emitted and nothing was flushed. Now the close emits a logout with reason
	 * "client_close" and sends ONE final batch, and RuneLite waits for that request (ClientShutdown.waitFor,
	 * which RuneLite bounds for every plugin) so the client does not exit before the POST is handed over.
	 * Only the in-memory buffer is sent; the durable staff spool keeps its own rows for the next start.
	 */
	void onClientShutdown(ClientShutdown event)
	{
		plugin.clientClosing = true;
		CompletableFuture<Void> done = new CompletableFuture<>();
		finalFlushDone = done;
		finalFlushBatchesLeft.set(FINAL_FLUSH_EXTRA_BATCHES);
		// ClientShutdown is posted on the UI thread. The bank state belongs to the client thread, so the
		// teardown runs there, and the logout and the final flush follow it even if the teardown fails.
		Runnable closeSession = () ->
		{
			if (plugin.client != null && plugin.client.getGameState() == GameState.LOGGED_IN)
			{
				try
				{
					// A bank open, or a close waiting for its snapshot, would otherwise never be sent:
					// end it as a teardown, as a logout does.
					plugin.bankVisit.finishPublicBankClose(true);
					plugin.bankVisit.closeBankVisit("logout", null);
					plugin.bankVisit.endBankSession(false);
				}
				catch (RuntimeException failure)
				{
					log.debug("OSRS BiS bank teardown at client close failed", failure);
				}
				plugin.session.trackLogout("client_close");
			}
			boolean sent = flushEventsNow();
			if (!sent && !eventPostInFlight)
			{
				completeFinalFlush();
			}
		};
		plugin.onClientThread(closeSession);
		// What the final batch could not deliver (a 429 when the global hour is full, a 5xx, no network, or
		// more than one batch) goes to disk, and the next start sends it. RuneLite waits at most 10 s for all
		// plugins, so the write does not wait longer than EXIT_FLUSH_WAIT_MS for the POST.
		event.waitFor(done.handle((v, t) -> null).completeOnTimeout(null, EXIT_FLUSH_WAIT_MS, TimeUnit.MILLISECONDS)
			.thenRun(this::persistPendingEvents));
	}

	// ---- EXIT PERSISTENCE of the memory buffer ----
	//
	// pendingEvents lives in memory. A client closed while the server answered 429 (global event cap full)
	// lost every queued event: 11 events of one session were never sent (2026-10-11). Now the close and a
	// plugin stop write the rows the memory path owns to one file, and the next startUp puts them back at
	// the front of the buffer. Rows already on disk elsewhere (the public outbox journal, the staff spool)
	// are not written twice.
	//
	// IDENTITY. A row is restored only under the token it was queued for (its token fingerprint, never the
	// token). A start with another token or none deletes the file unread. Rows older than EventSpool.MAX_AGE_MS
	// are dropped. At most MAX_PENDING_EVENTS rows. The file is deleted when it is read.

	static final String EXIT_PENDING_FILE = "pending-events.json";
	/** Longest wait for the final POST before the buffer is written anyway. A batch still in flight then is not written. */
	static final long EXIT_FLUSH_WAIT_MS = 7_000L;

	private Path exitPendingPath()
	{
		return plugin.spooler.spoolDataRoot().resolve("osrsbis").resolve(EXIT_PENDING_FILE);
	}

	/**
	 * Move the memory-owned rows of pendingEvents to the exit file, after any rows already in it. The rows leave
	 * the buffer under its lock, so a flush still running cannot also send them. A failed write puts them back.
	 */
	void persistPendingEvents()
	{
		List<Map<String, Object>> taken = new ArrayList<>();
		synchronized (pendingEvents)
		{
			Iterator<Map<String, Object>> it = pendingEvents.iterator();
			while (it.hasNext())
			{
				Map<String, Object> ev = it.next();
				Object id = ev.get("event_id");
				if (ev.containsKey(EVENT_PUBLIC_OUTBOX) || !(ev.get(EVENT_TOKEN_FP) instanceof String)
					|| id != null && plugin.spooler.spoolMemoryOwned.containsKey(String.valueOf(id)))
				{
					continue;
				}
				taken.add(ev);
				it.remove();
			}
		}
		if (taken.isEmpty())
		{
			return;
		}
		try
		{
			List<Map<String, Object>> all = readExitPending();
			for (Map<String, Object> ev : taken)
			{
				Map<String, Object> row = new LinkedHashMap<>(ev);
				row.remove(EVENT_QUEUE);	// the writer is chosen again at the next start
				all.add(row);
			}
			while (all.size() > MAX_PENDING_EVENTS)
			{
				all.remove(0);
				healthEventsLost.incrementAndGet();
			}
			Path file = exitPendingPath();
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(EXIT_PENDING_FILE + ".tmp");
			Files.write(tmp, plugin.gson.toJson(all).getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		}
		catch (Throwable t)
		{
			log.debug("OSRS BiS pending events could not be written at exit", t);
			synchronized (pendingEvents)
			{
				pendingEvents.addAll(0, taken);
			}
		}
	}

	/** Put the exit file's rows for the current token back at the front of pendingEvents, and delete the file. */
	void restorePendingEvents()
	{
		try
		{
			String fp = currentEventTokenFingerprint();
			List<Map<String, Object>> all = readExitPending();
			Files.deleteIfExists(exitPendingPath());
			long now = System.currentTimeMillis();
			List<Map<String, Object>> keep = new ArrayList<>();
			for (Map<String, Object> ev : all)
			{
				Object ts = ev.get("ts");
				if (fp != null && fp.equals(ev.get(EVENT_TOKEN_FP)) && ts instanceof Number
					&& now - ((Number) ts).longValue() <= EventSpool.MAX_AGE_MS)
				{
					keep.add(ev);
				}
			}
			if (keep.isEmpty())
			{
				return;
			}
			synchronized (pendingEvents)
			{
				pendingEvents.addAll(0, keep);
				trimPending();
			}
			scheduleCoalescedFlush();
		}
		catch (Throwable t)
		{
			log.debug("OSRS BiS pending events could not be restored", t);
		}
	}

	private List<Map<String, Object>> readExitPending() throws IOException
	{
		Path file = exitPendingPath();
		List<Map<String, Object>> rows = new ArrayList<>();
		if (!Files.exists(file))
		{
			return rows;
		}
		try
		{
			Object parsed = plugin.gson.fromJson(new String(Files.readAllBytes(file),
				StandardCharsets.UTF_8), Object.class);
			if (parsed instanceof List)
			{
				for (Object row : (List<?>) parsed)
				{
					if (row instanceof Map)
					{
						@SuppressWarnings("unchecked")
						Map<String, Object> ev = (Map<String, Object>) wholeNumbers(row);
						rows.add(ev);
					}
				}
			}
		}
		catch (RuntimeException corrupt)
		{
			log.debug("OSRS BiS pending events file is unreadable and is dropped", corrupt);
		}
		return rows;
	}

	/** Gson reads every JSON number as a Double. Give whole numbers back their Long form, as emitted. */
	static Object wholeNumbers(Object v)
	{
		if (v instanceof Double)
		{
			double d = (Double) v;
			return d == Math.rint(d) && Math.abs(d) <= 9_007_199_254_740_992d ? (Object) (long) d : v;
		}
		if (v instanceof Map)
		{
			Map<String, Object> out = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet())
			{
				out.put(String.valueOf(e.getKey()), wholeNumbers(e.getValue()));
			}
			return out;
		}
		if (v instanceof List)
		{
			List<Object> out = new ArrayList<>();
			for (Object o : (List<?>) v)
			{
				out.add(wholeNumbers(o));
			}
			return out;
		}
		return v;
	}

	void completeFinalFlush()
	{
		CompletableFuture<Void> f = finalFlushDone;
		if (f == null || f.isDone())
		{
			return;
		}
		// One request carries one kind of row. A public row (a bank close) reaches the buffer after its journal
		// write on the disk thread, so the close sends a few more batches from there, after those writes.
		if (plugin.clientClosing && finalFlushBatchesLeft.getAndDecrement() > 0 && plugin.spooler.onSpoolThread(() ->
		{
			// Honours a backoff the last answer set: a refused close is not sent again, its rows stay saved.
			boolean before = eventPostInFlight;
			flushEvents();
			if (before || !eventPostInFlight)
			{
				completeFinalFlush();
			}
		}))
		{
			return;
		}
		f.complete(null);
	}

	/** Batches the client close sends after the first one, so a bank close journaled at the close goes too. */
	static final int FINAL_FLUSH_EXTRA_BATCHES = 3;
	private final AtomicInteger finalFlushBatchesLeft = new AtomicInteger();

	/** Completed by the event callback of the final close flush; null when no close is in progress. */
	volatile CompletableFuture<Void> finalFlushDone;

	/** Close-time flush: ignores the retry backoff once, so a close during a 429 wait still tries to send. */
	boolean flushEventsNow()
	{
		eventRetryBackoffUntilMs = 0L;
		queueRetryBackoffUntilMs = 0L;
		boolean before = eventPostInFlight;
		flushEvents();
		return !before && eventPostInFlight;
	}

	/** Internal identity only; no raw token/profile identifier is serialized. */
	private String observationOwner()
	{
		String fp = currentEventTokenFingerprint();
		if (!plugin.session.sessionActive || fp == null || plugin.activeHash == null || "-1".equals(plugin.activeHash)
			|| plugin.activeProfileType == null || "UNKNOWN".equals(plugin.activeProfileType)
			|| plugin.configManager == null || plugin.configManager.getProfile() == null) return null;
		return fp + ":" + plugin.activeHash + ":" + plugin.activeProfileType + ":" + plugin.session.sessionStartMillis
			+ ":" + plugin.configManager.getProfile().getId();
	}

	/** Client-thread tick only. A missing definition/varp is unknown, never normal mode. */
	void refreshEventObservationContext()
	{
		String owner = observationOwner();
		if (owner == null || plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN
			|| plugin.client.getLocalPlayer() == null || !Long.toString(plugin.client.getAccountHash()).equals(plugin.activeHash))
		{
			plugin.eventObservationContext.clear(); return;
		}
		Integer raw = AccountProgressCapture.bit(plugin.client, plugin.client.getVarps(), SnapshotBuilder.ACCOUNT_TYPE_VARBIT);
		plugin.eventObservationContext.observe(owner, raw == null ? null : SnapshotBuilder.accountTypeName(raw),
			plugin.client.getTickCount(), System.currentTimeMillis());
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
	 * <p>Public queue-v1 rows captured under a settled identity and a current Queue grant are forced to
	 * a separate bounded journal before memory staging. Other public event families and snapshots keep
	 * their existing memory-only delivery. The staff journal remains under its separate grant.
	 */
	void flushEvents()
	{
		if (!plugin.uploadAllowed())
		{
			return;		// upload switch off — send nothing
		}
		String token = plugin.currentLinkToken();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return;
		}
		String tokenFingerprint = EventSpool.sha256Hex("event-token:" + token);
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
			batch = new ArrayList<>();
			Iterator<Map<String, Object>> it = pendingEvents.iterator();
			while (it.hasNext() && batch.size() < EVENT_BATCH_MAX_EVENTS)
			{
				Map<String, Object> ev = it.next();
				Object eid = ev.get("event_id");
				if (eid != null && !plugin.spooler.durabilityGateOpen(String.valueOf(eid)))
				{
					// Not durable yet: it stays in place and keeps its order. Nothing behind it may
					// overtake it either, so the batch stops here.
					break;
				}
				if (!PublicTransport.allowsEvent(String.valueOf(ev.get("type")), plugin.grants.captureEnabled()))
				{
					// Legacy memory rows are dropped locally; durable rows are quarantined on replay.
					healthEventsLost.incrementAndGet();
					plugin.spooler.spoolReleaseFromMemory(ev);
					it.remove();
					continue;
				}
				Object durableOwner = ev.get(EVENT_PUBLIC_OUTBOX);
				if (durableOwner instanceof PublicEventOutbox.Context)
				{
					PublicEventOutbox.Context current = plugin.outbox.publicSendContext();
					if (current == null) continue; // no owner (another login pending, unknown mode) pauses replay
					if (plugin.outbox.stagedPublicScope(ev) == null) continue; // another public capability cannot resume this path
					if (!current.identity.equals(((PublicEventOutbox.Context) durableOwner).identity))
					{
						if (Boolean.TRUE.equals(ev.get(EVENT_PUBLIC_VOLATILE))) healthEventsLost.incrementAndGet();
						plugin.spooler.spoolReleaseFromMemory(ev);
						it.remove();
						continue; // never send a previous account/profile under the new one
					}
				}
				Object bound = ev.get(EVENT_TOKEN_FP);
				if (bound == null || tokenFingerprint.equals(bound))
				{
					// Pin the first selected writer across retries, including a later grant withdrawal.
					// Staff durable rows retain their existing receipt/replay contract.
					if (!ev.containsKey(EVENT_QUEUE)) ev.put(EVENT_QUEUE,
						token.equals(plugin.grants.eventQueueGrantToken) && !token.equals(plugin.spooler.spoolGrantToken)
						&& !plugin.spooler.spoolMemoryOwned.containsKey(String.valueOf(eid))
						&& EventQueueProtocol.allows(plugin.grants.eventQueueScope, ev));
					boolean queue = Boolean.TRUE.equals(ev.get(EVENT_QUEUE));
					if (plugin.nowMs() < (queue ? queueRetryBackoffUntilMs : eventRetryBackoffUntilMs)) continue;
					if (!batch.isEmpty() && !Objects.equals(batch.get(0).get(EVENT_QUEUE), ev.get(EVENT_QUEUE))) break;
					if (!batch.isEmpty() && !Objects.equals(PublicOutboxDriver.publicOutboxIdentity(batch.get(0)), PublicOutboxDriver.publicOutboxIdentity(ev))) break;
					if (!batch.isEmpty() && !Objects.equals(batch.get(0).get(EVENT_PUBLIC_VOLATILE), ev.get(EVENT_PUBLIC_VOLATILE))) break;
					batch.add(ev);
				}
				else
				{
					healthEventsLost.incrementAndGet();
					plugin.spooler.spoolReleaseFromMemory(ev);
				}
				it.remove();
			}
			if (batch.isEmpty())
			{
				return;
			}
			eventPostInFlight = true;
		}
		if (PublicOutboxDriver.publicOutboxIdentity(batch.get(0)) != null && !Boolean.TRUE.equals(batch.get(0).get(EVENT_PUBLIC_VOLATILE)))
		{
			final List<Map<String, Object>> selected = batch;
			if (!plugin.spooler.onSpoolThread(() -> plugin.outbox.dispatchPublicOutbox(selected, token))) requeueEvents(selected, 0);
			return;
		}
		dispatchEventBatch(batch, token);
	}

	void dispatchEventBatch(List<Map<String, Object>> batch, String token)
	{
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
			// Encode and MEASURE before sending: the server refuses a body of 256 KiB or more, so the
			// batch is cut at the last event that keeps it under the limit, and the rest goes back to
			// the FRONT of the buffer for the next flush, in order. A single event that is too large
			// on its own is still sent alone, exactly as before.
			List<String> encoded = new ArrayList<>(batch.size());
			for (Map<String, Object> queued : batch)
			{
				Map<String, Object> wire = PublicTransport.event(queued, plugin.grants.captureEnabled());
				encoded.add(plugin.gson.toJson(wire));
			}
			boolean queue = Boolean.TRUE.equals(batch.get(0).get(EVENT_QUEUE));
			int kept = fitEventBatch(plugin.gson.toJson(token), encoded, queue);
			if (kept < batch.size())
			{
				List<Map<String, Object>> overflow = new ArrayList<>(batch.subList(kept, batch.size()));
				batch = new ArrayList<>(batch.subList(0, kept));
				synchronized (pendingEvents)
				{
					pendingEvents.addAll(0, overflow);
				}
			}
			Request request = eventRequest(plugin.gson.toJson(token), encoded.subList(0, kept), queue, null);
			String outboxIdentity = PublicOutboxDriver.publicOutboxIdentity(batch.get(0));
			PublicEventOutbox.Context liveOwner = outboxIdentity == null ? null : plugin.outbox.publicSendContext();
			if (outboxIdentity != null && (liveOwner == null || !outboxIdentity.equals(liveOwner.identity)
				|| plugin.outbox.stagedPublicScope(batch.get(0)) == null))
			{
				requeueEvents(batch, 0);
				return;
			}
			final List<Map<String, Object>> sent = batch;
			plugin.okHttpClient.newCall(request).enqueue(answered(e ->
			{
				log.debug("OSRS BiS event sync failed — requeueing {} events", sent.size(), e);
				requeueEvents(sent, 0);
			}, response -> eventAnswer(sent, token, response)));
		}
		catch (Throwable t)
		{
			// requeueEvents restores the batch, arms the backoff and clears the in-flight flag, so a
			// corrected config recovers on the next tick exactly as it did before 0.7.4.
			log.debug("OSRS BiS event sync could not be dispatched — requeueing {} events", batch.size(), t);
			requeueEvents(batch, 0);
		}
	}

	/** The shared sender: one {token, events} request to /event-ingest, with the queue-v1 envelope when asked. */
	Request eventRequest(String tokenJson, List<String> encoded, boolean queue, SpoolDriver.SpoolReplayAge age)
	{
		String base = plugin.apiBase();
		Request.Builder request = new Request.Builder().url(base + "/event-ingest")
			.post(RequestBody.create(AccountConnectPlugin.JSON, eventBatchBody(tokenJson, encoded, queue)));
		return (age == null ? request : request.tag(SpoolDriver.SpoolReplayAge.class, age)).build();
	}

	/** The shared answer path: a network failure, or a response that is always closed after its lane read it. */
	static Callback answered(Consumer<IOException> failed, Consumer<Response> answer)
	{
		return new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				failed.accept(e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					answer.accept(response);
				}
				finally
				{
					response.close();
				}
			}
		};
	}

	/** The memory and public-journal lane's answer. */
	private void eventAnswer(List<Map<String, Object>> batch, String sentToken, Response response)
	{
		boolean bankReceipt = Boolean.TRUE.equals(batch.get(0).get(EVENT_PUBLIC_BANK));
		if ((Boolean.TRUE.equals(batch.get(0).get(EVENT_QUEUE)) || bankReceipt)
			&& response.code() != 403)
		{
			String queueBody = readBody(response);
			EventQueueProtocol.ReceiptSet parsed = EventQueueProtocol.terminalError(response.code(), queueBody)
				? EventQueueProtocol.ReceiptSet.rejected(batch.size()) : bankReceipt
					? PublicBankProtocol.receipts(response.code(), queueBody, batch) : EventQueueProtocol.parseReceipts(queueBody, batch);
			List<EventQueueProtocol.Outcome> receipts = parsed.outcomes;
			queueDeliveryHealth.response(batch, parsed);
			plugin.outbox.settlePublicOutbox(batch, receipts, sentToken);
			List<Map<String, Object>> retry = new ArrayList<>();
			for (int i = 0; i < batch.size(); i++)
			{
				if (receipts.get(i) == EventQueueProtocol.Outcome.RETRY) retry.add(batch.get(i));
				else if (receipts.get(i) == EventQueueProtocol.Outcome.DURABLE) healthEventsSent.incrementAndGet();
				else healthEventsLost.incrementAndGet();
			}
			if (receipts.contains(EventQueueProtocol.Outcome.DURABLE)) healthLastEventUploadMs.set(System.currentTimeMillis());
			if (!retry.isEmpty())
			{
				requeueEvents(retry, retryAfterMillis(response));
			}
			else
			{
				eventPostInFlight = false;
				completeFinalFlush();
				if (bankReceipt) { eventRetryBackoffMs = 0; eventRetryBackoffUntilMs = 0L; }
				else { queueRetryBackoffMs = 0; queueRetryBackoffUntilMs = 0L; }
				if (!pendingEvents.isEmpty()) scheduleCoalescedFlush();
			}
			return;
		}
		if (response.isSuccessful())
		{
			// Durable rows are settled ONLY by their own event_id in `results`. The memory
			// copy is done either way, exactly as before; an unacked durable row stays on
			// disk and becomes eligible for the paced replay.
			plugin.spooler.spoolOnMemoryBatchDone(batch, sentToken, readBody(response), response.code());
			healthEventsSent.addAndGet(batch.size());
			healthLastEventUploadMs.set(System.currentTimeMillis());
			eventPostInFlight = false;
			completeFinalFlush();
			eventRetryBackoffMs = 0;	// delivered — reset the ladder
			eventRetryBackoffUntilMs = 0L;
			if (!pendingEvents.isEmpty())
			{
				scheduleCoalescedFlush();	// the rest of a split batch goes now, not at the next tick
			}
			return;
		}
		// Provider route failures and request timeouts remain retryable, as in queue-v1.
		// Revoked links and permanent application rejections retain their terminal handling.
		int code = response.code();
		if (code == 404 || code == 408 || code == 429 || code >= 500)
		{
			long retryAfterMs = retryAfterMillis(response);
			log.debug("OSRS BiS event sync HTTP {} — requeueing {} events", code, batch.size());
			requeueEvents(batch, retryAfterMs);
		}
		else
		{
			log.debug("OSRS BiS event sync HTTP {} — dropping {} events (not retryable)",
				code, batch.size());
			healthEventsLost.addAndGet(batch.size());
			// 403 = the link is revoked: the identity's spool is purged. Any other 4xx:
			// the durable copies go to terminal quarantine, metadata only, never retried.
			plugin.spooler.spoolOnMemoryBatchDone(batch, sentToken, null, code);
			eventPostInFlight = false;
			completeFinalFlush();
		}
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
			trimPending();
		}
		boolean queue = !batch.isEmpty() && Boolean.TRUE.equals(batch.get(0).get(EVENT_QUEUE));
		long prior = queue ? queueRetryBackoffMs : eventRetryBackoffMs;
		long next = nextBackoff(prior, retryAfterMs);
		if (queue) { queueRetryBackoffMs = next; queueRetryBackoffUntilMs = plugin.nowMs() + next; }
		else { eventRetryBackoffMs = next; eventRetryBackoffUntilMs = plugin.nowMs() + next; }
		eventPostInFlight = false;
		completeFinalFlush();
	}

	/** The doubling retry ladder from the base to the cap, never shorter than a server Retry-After (itself capped). */
	static long nextBackoff(long prior, long retryAfterMs)
	{
		long next = prior <= 0 ? EVENT_RETRY_BASE_BACKOFF_MS : Math.min(prior * 2, EVENT_RETRY_MAX_BACKOFF_MS);
		return retryAfterMs > 0 ? Math.max(next, Math.min(retryAfterMs, EVENT_RETRY_MAX_BACKOFF_MS)) : next;
	}

	/** Integer Retry-After, capped before conversion so a large header cannot overflow. */
	static long retryAfterMillis(Response response)
	{
		Long seconds = SnapshotUploader.parseRetryAfterSeconds(response.header("Retry-After"));
		return seconds == null ? 0L
			: Math.min(seconds, EVENT_RETRY_MAX_BACKOFF_MS / 1000L) * 1000L;
	}

	/** Read a response body for the ack, or null. Never throws. */
	static String readBody(Response response)
	{
		try
		{
			return response.body() == null ? null : response.body().string();
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	/** How many of these encoded events fit in one request: at most 100 and under 256 KiB, at least one. */
	static int fitEventBatch(String tokenJson, List<String> encoded, boolean queue)
	{
		long bytes = eventBatchBody(tokenJson, Collections.emptyList(), queue).getBytes(StandardCharsets.UTF_8).length;
		int n = 0;
		for (String e : encoded)
		{
			if (n >= EVENT_BATCH_MAX_EVENTS)
			{
				break;
			}
			long add = e.getBytes(StandardCharsets.UTF_8).length + (n > 0 ? 1 : 0);
			if (n > 0 && bytes + add >= EVENT_BATCH_MAX_BYTES)
			{
				break;
			}
			bytes += add;
			n++;
		}
		return Math.max(n, Math.min(1, encoded.size()));
	}

	/** The request body, byte-identical to gson's encoding of {token, events}. */
	static String eventBatchBody(String tokenJson, List<String> encoded, boolean queue)
	{
		StringBuilder sb = new StringBuilder("{\"token\":").append(tokenJson);
		if (queue) sb.append(",\"ack_protocol\":\"queue-v1\",\"schema_version\":1");
		return sb.append(",\"events\":[").append(String.join(",", encoded)).append("]}").toString();
	}

	Map<String, Object> captureHealthSnapshot()
	{
		Map<String, Object> h = Fields.of(
			"process_started_at", healthProcessStartedAtMs / 1000L, "events_queued_total", healthEventsQueued.get(),
			"events_sent_total", healthEventsSent.get(), "events_lost_total", healthEventsLost.get(),
			"events_sent_semantics", "legacy_success_or_queue_ownership_not_archive_total",
			"queue_delivery", queueDeliveryHealth.snapshot(),
			"events_overflow_p0_total", healthOverflowByClass.get(EventPriority.P0),
			"events_overflow_p1_total", healthOverflowByClass.get(EventPriority.P1),
			"events_overflow_p2_total", healthOverflowByClass.get(EventPriority.P2));
		synchronized (pendingEvents)
		{
			h.put("events_pending", pendingEvents.size());
		}
		h.put("event_post_in_flight", eventPostInFlight);
		h.put("drop_segments_uploaded_total", healthDropSegmentsSent.get());
		h.put("drop_segments_refused_total", healthDropSegmentsRefused.get());
		h.put("drop_segments_lost_total", healthDropSegmentsLost.get());
		h.put("drop_frames_rejected_total", healthDropFramesRejected.get());
		h.put("store_clip_chunks_uploaded_total", plugin.clips.clipChunksSent.get());
		h.put("store_clip_chunks_lost_total", plugin.clips.clipChunksLost.get());
		long eventAt = healthLastEventUploadMs.get();
		long mediaAt = healthLastMediaUploadMs.get();
		long snapshotAt = healthLastSnapshotUploadMs.get();
		if (eventAt > 0) h.put("last_event_upload_at", eventAt / 1000L);
		if (mediaAt > 0) h.put("last_media_upload_at", mediaAt / 1000L);
		if (snapshotAt > 0) h.put("last_snapshot_upload_at", snapshotAt / 1000L);
		h.put("public_outbox_pending", plugin.outbox.publicOutboxPending);
		h.put("public_outbox_bytes", plugin.outbox.publicOutboxBytes);
		h.put("public_outbox_dropped_total", plugin.outbox.publicOutboxDropped);
		h.put("public_outbox_retired_identities_total", plugin.outbox.publicOutboxPurged);
		h.put("public_outbox_not_durable_total", plugin.outbox.publicOutboxUndurable.get());
		h.put("public_outbox_append_pending", plugin.outbox.publicOutboxWrites.get());
		plugin.spooler.addSpoolHealth(h);
		return h;
	}
}
