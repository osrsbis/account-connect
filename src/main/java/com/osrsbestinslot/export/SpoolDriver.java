package com.osrsbestinslot.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/** The staff durable event journal: grant and identity, append then stage into the one sender, purge triggers, the recovery pump and settlement, and the disk thread every journal uses. */
@Slf4j
@RequiredArgsConstructor
final class SpoolDriver
{
	final AccountConnectPlugin plugin;

	// =====================================================================================================
	// DURABLE EVENT SPOOL (staff only). See EventSpool for the file format.
	//
	// GATE. Active only when the server sent X-Event-Spool: on for the CURRENT token, uploadAllowed() holds
	// and the account hash is real. Otherwise every event takes exactly the memory-only path, and no file or
	// directory is ever created. A public client never receives the header, so it never writes.
	//
	// MODEL (the public journals' model). While the spool is active every event is handed to the disk thread in
	// emit order. An allowlisted event is appended and forced there, and only then staged into the one memory
	// sender; so nothing is POSTed before it is durable. The pump re-stages, on the 5 s tick, the durable rows the
	// sender does not hold: rows a killed process left, rows the memory cap evicted, rows a 2xx did not settle.
	//
	// SERVER ACK CONTRACT (unchanged):
	//   2xx     : {"ok":true,"results":[{"event_id":"<hex>","status":"stored"|"duplicate"|"filtered"},...]}
	//             A durable row is deleted ONLY when its own event_id is listed with one of those statuses.
	//             Anything else deletes NOTHING: the row stays and the pump backs off before re-staging it.
	//             "filtered" is terminal and counted as a loss (spool_filtered_total).
	//   404/408/429/5xx/network : the sender keeps the rows in memory and retries on its ladder.
	//   403     : the link is revoked; this identity's spool is purged.
	//   other 4xx: the batch's durable rows are tombstoned (quarantined) and never retried.
	//
	// PURGE TRIGGERS, exactly: token clear or change, account change, 403 revocation, X-Event-Spool: off.
	// An operator pause (X-Uploads-Enabled: false) only holds re-staged rows; nothing is purged.
	//
	// THREADING. Every file operation runs on ONE dedicated single-thread executor, never the client thread and
	// never the shared upload executor. An event_id is owned by the sender from staging until its answer is
	// settled on that thread, so the same id is never staged or in flight twice in one process.
	// =====================================================================================================

	/** Exact event types that may reach disk. Everything else stays memory-only. */
	static final Set<String> SPOOL_ALLOWLIST = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList("trade", "ge_offer", "ge_progress", "ge_buy", "ge_sell", "ge_cancel", "ge_collect",
			"store_buy", "store_sell", "store_taken", "drop", "pickup", "ground_removed", "bank_session", "login",
			"logout", "world_hop", "drop_trade_clip", "drop_trade_clip_final", "death",
			"bank_visit_open", "bank_visit_manifest_chunk", "bank_visit_close")));
	/** Internal key of a row the pump staged from disk: its identity and created_ms. Never sent. */
	static final String EVENT_STAFF_SPOOL = "__staff_spool";

	@RequiredArgsConstructor
	static final class Restaged
	{
		final String identity;
		final long createdMs;
	}

	private final Object spoolStateLock = new Object();
	/** The token the server granted the spool for; null = off. */
	volatile String spoolGrantToken;
	/** The identity key that is active right now, or null. */
	private volatile String spoolIdentity;
	/** The last valid (token, account) this process saw, so a change can purge what it left behind. */
	private String spoolLastToken;
	private String spoolLastAccount;
	volatile boolean spoolReplayPaused;
	volatile long spoolReplayBackoffUntilMs;
	volatile long spoolReplayBackoffMs;
	private final AtomicBoolean spoolPumpQueued = new AtomicBoolean();
	volatile Path spoolDataRootOverride;
	volatile ExecutorService spoolExec;
	/** event_id -> identity, for durable rows the sender holds (staged, or in flight until the answer is settled). */
	final Map<String, String> spoolMemoryOwned = new ConcurrentHashMap<>();
	/** The spool's wall clock (created_ms, 48 h age). A field so tests can move time. */
	volatile LongSupplier wallClock = System::currentTimeMillis;
	private final EventSpool.Counters spoolCounters = new EventSpool.Counters();
	private volatile int spoolPendingSnapshot;
	private volatile long spoolBytesSnapshot;
	private volatile long spoolOldestSnapshotMs = -1L;

	// Writer-thread state. Touched only on spoolExec.
	PublicEventOutbox staffJournal;
	boolean spoolDead;

	Path spoolDataRoot()
	{
		Path o = spoolDataRootOverride;
		return o != null ? o : RuneLite.PLUGIN_DATA;
	}

	/** Writer thread: the staff journal adapter, created at first use. */
	private PublicEventOutbox staff()
	{
		if (staffJournal == null) staffJournal = new PublicEventOutbox(spoolDataRoot(), () -> wallClock.getAsLong(), spoolCounters);
		return staffJournal;
	}

	/** Writer thread: the open staff journal, or null. */
	EventSpool spool()
	{
		PublicEventOutbox s = staffJournal;
		return s == null ? null : s.journal;
	}

	private Path spoolRoot()
	{
		return spoolDataRoot().resolve("osrsbis").resolve("spool");
	}

	private static boolean validSpoolAccount(String account)
	{
		return account != null && !account.isEmpty() && !"-1".equals(account);
	}

	private ExecutorService spoolExecutor()
	{
		ExecutorService e = spoolExec;
		if (e == null)
		{
			synchronized (spoolStateLock)
			{
				if (spoolExec == null)
				{
					spoolExec = Executors.newSingleThreadExecutor(r ->
					{
						Thread t = new Thread(r, "osrsbis-event-spool");
						t.setDaemon(true);
						return t;
					});
				}
				e = spoolExec;
			}
		}
		return e;
	}

	/** Run one spool operation on the writer thread. A failure is logged and never escapes. */
	boolean onSpoolThread(Runnable op)
	{
		try
		{
			spoolExecutor().execute(() ->
			{
				if (spoolDead)
				{
					return;
				}
				try
				{
					op.run();
				}
				catch (Throwable t)
				{
					log.debug("OSRS BiS event spool operation failed", t);
				}
				finally
				{
					refreshSpoolSnapshot();
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			log.debug("OSRS BiS event spool executor is closed", e);
			return false;
		}
		return true;
	}

	/**
	 * Recompute which identity is active and purge whatever a token or account change left behind.
	 * Safe from any thread. Never creates a file by itself.
	 */
	void syncSpoolState()
	{
		String token = plugin.uploadAllowed() ? plugin.currentLinkToken() : null;
		String account = plugin.activeHash;
		String purge = null;
		String activate = null;
		synchronized (spoolStateLock)
		{
			if (spoolGrantToken != null && !spoolGrantToken.equals(token))
			{
				spoolGrantToken = null;	// a grant belongs to one token only
			}
			if (spoolLastToken != null)
			{
				boolean tokenChanged = !spoolLastToken.equals(token);
				boolean accountChanged = validSpoolAccount(account) && !spoolLastAccount.equals(account);
				if (tokenChanged || accountChanged)
				{
					purge = EventSpool.identityKey(spoolLastToken, spoolLastAccount);
					spoolLastToken = null;
					spoolLastAccount = null;
				}
			}
			if (token != null && validSpoolAccount(account))
			{
				spoolLastToken = token;
				spoolLastAccount = account;
			}
			String now = token != null && token.equals(spoolGrantToken) && validSpoolAccount(account)
				? EventSpool.identityKey(token, account) : null;
			if (now != null && !now.equals(spoolIdentity))
			{
				activate = now;
			}
			spoolIdentity = now;
		}
		if (purge != null)
		{
			final String id = purge;
			onSpoolThread(() -> purgeIdentityOnSpoolThread(id, false));
		}
		if (activate != null)
		{
			final String id = activate;
			onSpoolThread(() ->
			{
				try
				{
					ensureSpoolInstance(id);
				}
				catch (IOException e)
				{
					log.debug("OSRS BiS event spool could not be opened", e);
				}
			});
		}
	}

	/**
	 * The link token changed or was cleared. syncSpoolState purges the identity this process knew, but a
	 * change made at the login screen, before any account was seen, knows no identity. The identity
	 * directories name every identity granted on this OS user; under the changed token none of them can be
	 * the current one, so the idle ones are purged now rather than left for a grant that may never come.
	 * Creates nothing.
	 */
	void spoolOnTokenChangedWithoutAccount()
	{
		final Path root = spoolRoot();
		if (!Files.isDirectory(root))
		{
			return;
		}
		onSpoolThread(() -> purgeIdleIdentitiesExcept(null));
	}

	/** X-Event-Spool for sentToken. On grants the spool; off revokes it and purges this identity. */
	void setSpoolGrant(String sentToken, boolean on)
	{
		if (on)
		{
			synchronized (spoolStateLock)
			{
				spoolGrantToken = sentToken;
			}
			syncSpoolState();
			return;
		}
		spoolRevoke(sentToken);
	}

	/** The server answered 403 for this token: the link is revoked. */
	void spoolOnRevoked(String sentToken)
	{
		if (sentToken != null && sentToken.equals(plugin.currentLinkToken()))
		{
			spoolRevoke(sentToken);
			plugin.outbox.revokePublicOutbox(sentToken);
		}
	}

	private void spoolRevoke(String token)
	{
		String account = plugin.activeHash;
		synchronized (spoolStateLock)
		{
			spoolGrantToken = null;
			spoolIdentity = null;
		}
		if (token != null && validSpoolAccount(account))
		{
			final String id = EventSpool.identityKey(token, account);
			onSpoolThread(() -> purgeIdentityOnSpoolThread(id, true));
		}
	}

	/**
	 * Writer thread. Make `id` the open identity. At the first grant for an identity: purge every OTHER
	 * identity directory that has no live writer, BEFORE anything of this identity is staged; age out foreign
	 * shards by record time and finish marked directories; then recover this identity's abandoned shards.
	 */
	private boolean ensureSpoolInstance(String id) throws IOException
	{
		if (!id.equals(spoolIdentity))
		{
			return false;
		}
		if (staff().isOpen(id))
		{
			return true;
		}
		staff().close();
		// Every OTHER identity this install has used is purged now, BEFORE anything of this identity is
		// staged, but ONLY when no live writer holds any of its shards. A second client running another
		// identity on this OS user is never marked or purged; its directory stays and is retried at the next
		// grant or sweep, once that client is gone.
		purgeIdleIdentitiesExcept(id);
		// False while a withdrawal of this identity is still being finished by a live writer.
		return staff().openStaff(id);
	}

	/** Writer thread. Purge every identity directory other than keep (may be null) and the active one with no live writer. */
	private void purgeIdleIdentitiesExcept(String keep)
	{
		String current = spoolIdentity;
		for (String other : EventSpool.identities(spoolRoot()))
		{
			if (!other.equals(keep) && !other.equals(current))
			{
				purgeIdentityOnSpoolThread(other, false);
			}
		}
	}

	/**
	 * Writer thread. The sender lets go of an identity's rows: ownership of its durable rows ends, and the rows
	 * the pump staged from disk for it leave memory (rows emitted live stay, memory-only, as before). Everything
	 * staged after this point runs later on this thread, so a re-activated identity's new rows are not touched.
	 */
	private void forget(String id)
	{
		spoolMemoryOwned.values().removeIf(id::equals);
		synchronized (plugin.events.pendingEvents)
		{
			plugin.events.pendingEvents.removeIf(ev ->
				ev.get(EVENT_STAFF_SPOOL) instanceof Restaged && id.equals(((Restaged) ev.get(EVENT_STAFF_SPOOL)).identity));
		}
	}

	/**
	 * Writer thread. Close our own instance if it is this identity (its shard is deleted), then purge the
	 * directory. withdrawn=true is a server-side withdrawal of THIS identity (X-Event-Spool off, 403): a shard
	 * another live process still holds gets a purge marker, so that writer stops too. withdrawn=false (a token
	 * or account change, another identity becoming active) never marks or deletes a live identity: the
	 * directory is purged only when every shard is free, and otherwise kept for a later retry.
	 */
	private void purgeIdentityOnSpoolThread(String id, boolean withdrawn)
	{
		try
		{
			if (id.equals(staff().openId))
			{
				staffJournal.closeOwn();
			}
			forget(id);
			// A marked directory is finished by its writer or by the sweep.
			if (staff().drop(id, withdrawn))
			{
				spoolCounters.purged.incrementAndGet();
			}
		}
		catch (IOException e)
		{
			log.debug("OSRS BiS event spool purge failed", e);
		}
	}

	/**
	 * Writer thread. Our identity directory carries a purge marker: the identity was withdrawn (X-Event-Spool
	 * off or a 403) in another process while we held the shard. Stop, delete our own shard, finish the purge,
	 * and drop our grant: nothing more is written until the server grants the spool again.
	 */
	private boolean retireIfMarked() throws IOException
	{
		EventSpool spool = spool();
		if (spool == null || !spool.purgeRequested())
		{
			return false;
		}
		String id = staffJournal.openId;
		Path dir = spool.dir();
		staffJournal.closeOwn();
		synchronized (spoolStateLock)
		{
			if (id.equals(spoolIdentity))
			{
				spoolGrantToken = null;
				spoolIdentity = null;
			}
		}
		forget(id);
		EventSpool.purgeIfIdle(dir);
		return true;
	}

	/**
	 * THE HUNG-DISK BOUND. An event the disk thread has not handed to the sender within this time is handed over
	 * anyway (spool_gate_timeout_total for an allowlisted one), in order, with the SAME event_id. If its append
	 * completes later, the answer to that send settles it, or the pump stages it again and the server's per-id
	 * "duplicate" deletes it.
	 */
	static final long DURABILITY_GATE_MAX_MS = 3_000L;
	volatile long durabilityGateMaxMs = DURABILITY_GATE_MAX_MS;

	/**
	 * emitEventBound hook. While the spool is active the event goes to the sender through the disk thread, in emit
	 * order; an allowlisted one is appended and forced first. False when the spool is not active: the caller stages it.
	 */
	boolean spoolCapture(String type, Map<String, Object> ev, String eventId)
	{
		if (spoolGrantToken == null)
		{
			return false;
		}
		syncSpoolState();
		final String id = spoolIdentity;
		if (id == null)
		{
			return false;
		}
		final boolean durable = SPOOL_ALLOWLIST.contains(type);
		Map<String, Object> wire = new LinkedHashMap<>(ev);
		wire.remove(EventQueue.EVENT_TOKEN_FP);	// identity is the shard, never a field
		final String json = plugin.gson.toJson(wire);
		long nowMs = wallClock.getAsLong();
		Object ts = ev.get("ts");
		final long created = ts instanceof Number ? Math.min(((Number) ts).longValue(), nowMs) : nowMs;
		final AtomicBoolean handed = new AtomicBoolean();
		if (plugin.executor != null)
		{
			plugin.executor.schedule(() ->
			{
				if (handed.compareAndSet(false, true))
				{
					if (durable) spoolCounters.gateTimeout.incrementAndGet();
					stage(ev, eventId, durable ? id : null);
				}
			}, durabilityGateMaxMs, TimeUnit.MILLISECONDS);
		}
		boolean queued = onSpoolThread(() ->
		{
			boolean forced = false;
			try
			{
				// DIRECTORY BEFORE SENSITIVE DATA: the shard's first open forces the hashed identity directory
				// into the root before its first row exists, so a crash right after the append still lets the
				// next process purge it without the token.
				forced = durable && ensureSpoolInstance(id) && !retireIfMarked() && spool().append(eventId, type, created, json);
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("OSRS BiS event spool append failed", e);
			}
			if (handed.compareAndSet(false, true))
			{
				if (durable && !forced) spoolCounters.appendFailed.incrementAndGet();	// it still goes out, memory-only
				stage(ev, eventId, forced ? id : null);
			}
		});
		if (!queued && handed.compareAndSet(false, true))
		{
			if (durable) spoolCounters.appendFailed.incrementAndGet();
			stage(ev, eventId, null);
		}
		return true;
	}

	/** Hand an event to the sender; owner is the identity of its durable row, null when it is memory-only. */
	private void stage(Map<String, Object> ev, String eventId, String owner)
	{
		if (owner != null)
		{
			spoolMemoryOwned.put(eventId, owner);
		}
		synchronized (plugin.events.pendingEvents)
		{
			plugin.events.pendingEvents.add(ev);
			plugin.events.trimPending();
		}
		plugin.events.scheduleCoalescedFlush();
	}

	/** The memory path dropped this event (cap or identity change). Its durable row, if any, can be staged again. */
	void spoolReleaseFromMemory(Map<String, Object> ev)
	{
		if (ev != null && ev.containsKey(EventQueue.EVENT_PUBLIC_OUTBOX))
			plugin.outbox.publicOutboxMemoryIds.remove(String.valueOf(ev.get("event_id")));
		Object id = ev == null ? null : ev.get("event_id");
		if (id != null)
		{
			spoolMemoryOwned.remove(String.valueOf(id));
		}
	}

	/**
	 * flushEvents, for a row the pump staged: 0 sends it, 1 holds it in place (its identity is not the live
	 * one, or the operator paused), 2 drops it from memory because it has passed 48 h (the pump tombstones it),
	 * 3 drops it because the spool no longer owns it for that identity: a purge or withdrawal let it go while it
	 * was in a batch in flight, and a requeue put it back. Its disk copy is gone or settled, so it is never sent.
	 */
	int restaged(Map<String, Object> ev)
	{
		Object tag = ev.get(EVENT_STAFF_SPOOL);
		if (!(tag instanceof Restaged))
		{
			return 0;
		}
		Restaged r = (Restaged) tag;
		if (!r.identity.equals(spoolMemoryOwned.get(String.valueOf(ev.get("event_id")))))
		{
			return 3;
		}
		if (wallClock.getAsLong() - r.createdMs > EventSpool.MAX_AGE_MS)
		{
			spoolCounters.replayAgedAtDispatch.incrementAndGet();
			return 2;
		}
		String account = plugin.activeHash;
		return spoolReplayPaused || !r.identity.equals(spoolIdentity) || !validSpoolAccount(account)
			|| !r.identity.equals(EventSpool.identityKey(plugin.currentLinkToken(), account)) ? 1 : 0;
	}

	/** A memory POST finished with a final answer (not 429/5xx/network, which requeue in memory). */
	void spoolOnMemoryBatchDone(List<Map<String, Object>> batch, String sentToken, String body, int code)
	{
		final Map<String, String> spooled = new LinkedHashMap<>();
		for (Map<String, Object> ev : batch)
		{
			Object raw = ev.get("event_id");
			String owner = raw == null ? null : spoolMemoryOwned.get(String.valueOf(raw));
			if (owner != null)
			{
				spooled.put(String.valueOf(raw), owner);
			}
		}
		if (code == 403)
		{
			spoolMemoryOwned.keySet().removeAll(spooled.keySet());
			spoolOnRevoked(sentToken);
			return;
		}
		if (spooled.isEmpty())
		{
			return;
		}
		onSpoolThread(() ->
		{
			spoolMemoryOwned.keySet().removeAll(spooled.keySet());
			settleOnSpoolThread(spooled, code, body);
		});
	}

	/**
	 * Writer thread. Settle the durable copies of one sent batch (ids -> identity they were written under).
	 * Rows of an identity that is no longer open are not touched here; the purge already removed them.
	 */
	private void settleOnSpoolThread(Map<String, String> sent, int code, String body)
	{
		EventSpool spool = spool();
		if (spool == null)
		{
			return;
		}
		List<String> mine = new ArrayList<>();
		for (Map.Entry<String, String> e : sent.entrySet())
		{
			if (e.getValue().equals(staffJournal.openId) && spool.get(e.getKey()) != null)
			{
				mine.add(e.getKey());
			}
		}
		if (mine.isEmpty())
		{
			return;
		}
		try
		{
			if (code >= 200 && code < 300)
			{
				Map<String, String> results = PublicBankProtocol.results(body, false);
				List<String> ack = new ArrayList<>();
				int filtered = 0;
				if (results != null)
				{
					for (String eid : mine)
					{
						String status = results.get(eid);
						if (status != null)
						{
							ack.add(eid);
							if ("filtered".equals(status))
							{
								filtered++;
							}
						}
					}
				}
				int done = spool.ack(ack);
				spoolCounters.acked.addAndGet(done);
				spoolCounters.filtered.addAndGet(filtered);
				if (done > 0)
				{
					spoolCounters.lastAckMs.set(System.currentTimeMillis());
				}
				int unacked = mine.size() - ack.size();
				if (unacked > 0)
				{
					spoolCounters.unacked2xx.addAndGet(unacked);
					long next = EventQueue.nextBackoff(spoolReplayBackoffMs, 0L);	// the pump waits before staging them again
					spoolReplayBackoffMs = next;
					spoolReplayBackoffUntilMs = plugin.nowMs() + next;
				}
				else
				{
					spoolReplayBackoffMs = 0L;	// every row of this batch is settled: reset the ladder
					spoolReplayBackoffUntilMs = 0L;
				}
				spool.maybeCompact();
			}
			else if (code >= 400 && code != 403)
			{
				spool.quarantine(mine);
			}
		}
		catch (IOException e)
		{
			log.debug("OSRS BiS event spool settle failed", e);
		}
	}

	/** Writer thread. Rows to stage again: oldest first, recovered rows first, never an id the sender holds. */
	List<EventSpool.Record> spoolReplayCandidates()
	{
		List<EventSpool.Record> out = new ArrayList<>();
		EventSpool spool = spool();
		if (spool == null)
		{
			return out;
		}
		// THE HARD AGE BOUND. Rows older than MAX_AGE_MS are tombstoned (durably, counted as dropped) HERE,
		// on the writer thread, immediately before any candidate is exposed, so a quiet client with no append
		// and no recovery still never stages a row older than 48 h.
		try
		{
			spool.enforceBoundsNow();
		}
		catch (IOException e)
		{
			log.debug("OSRS BiS event spool age-out failed", e);
			return out;	// fail closed: nothing is staged if the age cannot be enforced
		}
		for (EventSpool.Record r : spool.pending())
		{
			if (!spoolMemoryOwned.containsKey(r.eventId))
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * The 5 s tick: one coalesced disk read that stages at most one request's worth (100 rows) of durable rows the
	 * sender does not hold, into the room the memory buffer has, never while a pause or the 2xx backoff is open.
	 */
	void pumpSpool()
	{
		if (spoolGrantToken == null)
		{
			return;
		}
		syncSpoolState();
		final String id = spoolIdentity;
		if (id == null || spoolReplayPaused || plugin.nowMs() < spoolReplayBackoffUntilMs || !spoolPumpQueued.compareAndSet(false, true))
		{
			return;
		}
		if (!onSpoolThread(() ->
		{
			try
			{
				stageFromDisk(id);
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("OSRS BiS event spool recovery failed", e);
			}
			finally
			{
				spoolPumpQueued.set(false);
			}
		}))
		{
			spoolPumpQueued.set(false);
		}
	}

	/** Writer thread. Stage rows of identity id, projected as on every send; a row the projection refuses is tombstoned. */
	private void stageFromDisk(String id) throws IOException
	{
		if (!ensureSpoolInstance(id) || retireIfMarked())
		{
			return;
		}
		spool().maybeCompact();
		String fp = plugin.events.currentEventTokenFingerprint();
		if (fp == null || spoolReplayPaused || !id.equals(spoolIdentity))
		{
			return;
		}
		int room;
		synchronized (plugin.events.pendingEvents)
		{
			room = Math.min(EventQueue.EVENT_BATCH_MAX_EVENTS, EventQueue.MAX_PENDING_EVENTS - plugin.events.pendingEvents.size());
		}
		List<String> privacyRejected = new ArrayList<>();
		List<Map<String, Object>> staged = new ArrayList<>();
		for (EventSpool.Record r : spoolReplayCandidates())
		{
			if (staged.size() >= room) break;
			Map<String, Object> row = PublicTransport.event(plugin.gson.fromJson(r.eventJson, Map.class), plugin.grants.captureEnabled());
			if (row == null)
			{
				privacyRejected.add(r.eventId);
				continue;
			}
			row.put(EventQueue.EVENT_TOKEN_FP, fp);
			row.put(EVENT_STAFF_SPOOL, new Restaged(id, r.createdMs));
			spoolMemoryOwned.put(r.eventId, id);
			staged.add(row);
		}
		// A local privacy rejection, not an HTTP ACK: the rows are tombstoned and counted, never sent.
		spool().quarantine(privacyRejected);
		if (staged.isEmpty())
		{
			return;
		}
		spoolCounters.replayed.addAndGet(staged.size());
		synchronized (plugin.events.pendingEvents)
		{
			plugin.events.pendingEvents.addAll(staged);
			plugin.events.trimPending();
		}
		plugin.events.scheduleCoalescedFlush();
	}

	/** Writer thread: publish content-free numbers for the health block. */
	private void refreshSpoolSnapshot()
	{
		EventSpool s = spool();
		spoolPendingSnapshot = s == null ? 0 : s.pendingCount();
		spoolBytesSnapshot = s == null ? 0L : s.pendingPayloadBytes();
		spoolOldestSnapshotMs = s == null ? -1L : s.oldestCreatedMs();
	}

	void addSpoolHealth(Map<String, Object> h)
	{
		long oldest = spoolOldestSnapshotMs;
		EventSpool.Counters c = spoolCounters;
		h.putAll(Fields.of("spool_active", spoolIdentity != null, "spool_records_pending", spoolPendingSnapshot,
			"spool_bytes", spoolBytesSnapshot,
			"spool_oldest_age_s", oldest > 0 ? Math.max(0L, (wallClock.getAsLong() - oldest) / 1000L) : 0L,
			"spool_replayed_total", c.replayed.get(), "spool_acked_total", c.acked.get(),
			"spool_dropped_total", c.dropped.get(), "spool_corrupt_total", c.corrupt.get(),
			"spool_quarantined_total", c.quarantined.get(), "spool_filtered_total", c.filtered.get(),
			"spool_unacked_2xx_total", c.unacked2xx.get(), "spool_purged_total", c.purged.get(),
			"spool_append_failed_total", c.appendFailed.get(), "spool_reopen_failed_total", c.reopenFailed.get(),
			"spool_gate_timeout_total", c.gateTimeout.get(), "spool_replay_aged_at_dispatch_total", c.replayAgedAtDispatch.get()));
		long ack = c.lastAckMs.get();
		if (ack > 0)
		{
			h.put("last_spool_ack_at", ack / 1000L);
		}
	}

	/** shutDown: close the shard. Its rows stay on disk for the next process of the same identity. */
	void closeSpool()
	{
		plugin.outbox.publicOutboxPaused = true;
		plugin.outbox.endedPublicOwner = null;
		// A re-enable is treated like a cold start: nothing is written or staged until the server
		// sends X-Event-Spool again for the configured token.
		synchronized (spoolStateLock)
		{
			spoolGrantToken = null;
			plugin.outbox.clearJournalGrants();
			spoolIdentity = null;
		}
		if (spoolExec == null)
		{
			return;
		}
		onSpoolThread(() ->
		{
			for (PublicEventOutbox o : new PublicEventOutbox[]{plugin.outbox.publicOutbox, plugin.outbox.publicBankOutbox, plugin.outbox.publicOwnOutbox})
				if (o != null) o.close();
			if (staffJournal != null) staffJournal.close();
		});
	}
}
