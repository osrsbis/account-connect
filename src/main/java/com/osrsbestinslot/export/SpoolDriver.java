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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** The staff durable event spool: grant and identity, force-before-network gate, purge triggers, paced replay and settlement, and the disk thread every journal uses. */
@Slf4j
@RequiredArgsConstructor
final class SpoolDriver
{
	final AccountConnectPlugin plugin;

	// =====================================================================================================
	// DURABLE EVENT SPOOL (staff only). See EventSpool for the file format.
	//
	// GATE. Active only when the server sent X-Event-Spool: on for the CURRENT token, uploadAllowed() holds
	// and the account hash is real. Otherwise every event takes exactly the memory-only path above, and no
	// file or directory is ever created. A public client never receives the header, so it never writes.
	//
	// SERVER ACK CONTRACT ASSUMED (the server side is built separately):
	//   request : unchanged body {token, events:[...]}, every event carries a top-level "event_id"
	//             (32 lower-case hex, generated once at emit). At most 100 events and < 256 KiB per request.
	//   2xx     : {"ok":true,"results":[{"event_id":"<hex>","status":"stored"|"duplicate"|"filtered"},...]}
	//             A durable row is deleted ONLY when its own event_id is listed with one of those statuses.
	//             No `results` array (today's {"ok":true,"stored":N}), an unknown status, a malformed entry
	//             or an id missing from the list deletes NOTHING: the row stays and replay backs off.
	//             "filtered" is terminal and counted as a loss (spool_filtered_total).
	//   404/408/429/5xx/network : keep, back off; replay stops on the first 429.
	//   403     : the link is revoked; this identity's spool is purged.
	//   other 4xx: the batch's rows go to terminal quarantine as metadata only and are never retried.
	//
	// PURGE TRIGGERS, exactly: token clear or change, account change, 403 revocation, X-Event-Spool: off.
	// An operator pause (X-Uploads-Enabled: false) only holds replay; nothing is purged.
	//
	// THREADING. Every file operation runs on ONE dedicated single-thread executor, never the client
	// thread and never the shared upload executor. An event_id is owned by the memory path while it sits
	// in pendingEvents or in a memory POST; only after that is it eligible for the paced replay, so the
	// same id is never in flight twice in one process.
	// =====================================================================================================

	/** Exact event types that may reach disk. Everything else stays memory-only. */
	static final Set<String> SPOOL_ALLOWLIST = Collections.unmodifiableSet(new HashSet<>(
		Arrays.asList("trade", "ge_offer", "ge_progress", "ge_buy", "ge_sell", "ge_cancel", "ge_collect",
			"store_buy", "store_sell", "store_taken", "drop", "pickup", "ground_removed", "bank_session", "login",
			"logout", "world_hop", "drop_trade_clip", "drop_trade_clip_final", "death",
			"bank_visit_open", "bank_visit_manifest_chunk", "bank_visit_close")));
	static final long SPOOL_REPLAY_MIN_GAP_MS = 5_000L;

	private final Object spoolStateLock = new Object();
	/** The token the server granted the spool for; null = off. */
	volatile String spoolGrantToken;
	/** The identity key that is active right now, or null. */
	private volatile String spoolIdentity;
	/** The last valid (token, account) this process saw, so a change can purge what it left behind. */
	private String spoolLastToken;
	private String spoolLastAccount;
	volatile boolean spoolReplayPaused;
	volatile long spoolReplayNotBeforeMs;
	volatile long spoolReplayBackoffUntilMs;
	volatile long spoolReplayBackoffMs;
	volatile boolean spoolReplayInFlight;
	volatile Path spoolDataRootOverride;
	volatile ExecutorService spoolExec;
	/**
	 * Which identity GENERATION a spooled event belongs to. The generation counts every change of the active
	 * identity, so X -> Y -> X gives X a new generation: a purge decided for the old X releases only gates and
	 * memory ownership of generations up to the one it was decided in, never the re-activated X's.
	 */
	@RequiredArgsConstructor
	private static final class SpoolTag
	{
		final String identity;
		final long generation;
		final long sinceNanos = System.nanoTime();
		/** The durability gate timed out: the memory POST went out before the row was durable. */
		volatile boolean gateTimedOut;

		boolean coveredBy(String id, long maxGeneration)
		{
			return identity.equals(id) && generation <= maxGeneration;
		}
	}

	/** event_id -> tag, for spooled events the memory path still owns. */
	final Map<String, SpoolTag> spoolMemoryOwned = new ConcurrentHashMap<>();
	/** event_id -> tag, for allowlisted events whose durable append has not been forced yet. */
	final Map<String, SpoolTag> spoolDurabilityPending = new ConcurrentHashMap<>();
	/** Incremented under spoolStateLock whenever spoolIdentity changes value. */
	private long spoolGeneration;
	/**
	 * THE HUNG-DISK BOUND. A durability gate older than this is released: the event goes out memory-only
	 * (spool_gate_timeout_total), in order, with the SAME event_id. If the append completes later, the row is
	 * not settled by that memory POST; it replays and the server's per-id "duplicate" deletes it. Normal writes
	 * (force well under this bound) keep force-before-network exactly.
	 */
	static final long DURABILITY_GATE_MAX_MS = 3_000L;
	/**
	 * THE HARD REPLAY BOUND, together with EventSpool.MAX_AGE_MS. A replay call is refused at DISPATCH time
	 * (when OkHttp starts executing it, after any dispatcher queueing) if its oldest row is then older than
	 * MAX_AGE_MS, and the whole call (connect, write, read) is cut off after this timeout. So no replay request
	 * can still be in transit later than MAX_AGE_MS + SPOOL_REPLAY_CALL_TIMEOUT_MS after the created_ms (which
	 * is never later than the event's ts) of any row it carries, measured on this client's wall clock.
	 */
	static final long SPOOL_REPLAY_CALL_TIMEOUT_MS = 30_000L;

	/** Request tag: the oldest created_ms in a replay batch. */
	@RequiredArgsConstructor
	static final class SpoolReplayAge
	{
		final long oldestCreatedMs;
	}

	private volatile OkHttpClient spoolReplayHttp;

	/** The shared client plus the replay call timeout and the dispatch-time age check (same pool and dispatcher). */
	private OkHttpClient spoolReplayHttp()
	{
		OkHttpClient c = spoolReplayHttp;
		if (c == null)
		{
			c = plugin.okHttpClient.newBuilder()
				.callTimeout(SPOOL_REPLAY_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
				.addInterceptor(chain ->
				{
					SpoolReplayAge age = chain.request().tag(SpoolReplayAge.class);
					if (age != null && wallClock.getAsLong() - age.oldestCreatedMs > EventSpool.MAX_AGE_MS)
					{
						spoolCounters.replayAgedAtDispatch.incrementAndGet();
						throw new IOException("spool replay batch passed the age bound before dispatch");
					}
					return chain.proceed(chain.request());
				})
				.build();
			spoolReplayHttp = c;
		}
		return c;
	}
	volatile long durabilityGateMaxMs = DURABILITY_GATE_MAX_MS;
	/** The spool's wall clock (created_ms, 48 h age). A field so tests can move time. */
	volatile LongSupplier wallClock = System::currentTimeMillis;
	// Test-only hooks around the durable append, run on the spool thread. Always null in production.
	volatile Runnable spoolBeforeAppendHookForTest;
	volatile Runnable spoolAfterAppendHookForTest;
	volatile Runnable spoolReplayBeforeDispatchHookForTest;
	private final EventSpool.Counters spoolCounters = new EventSpool.Counters();
	private volatile int spoolPendingSnapshot;
	private volatile long spoolBytesSnapshot;
	private volatile long spoolOldestSnapshotMs = -1L;

	// Writer-thread state. Touched only on spoolExec.
	PublicEventOutbox staffJournal;
	boolean spoolDead;
	private final Set<String> spoolReplayIds = new HashSet<>();

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
	/** Under spoolStateLock: set the active identity; a change of value starts a new generation. */
	private void setSpoolIdentityLocked(String identity)
	{
		if (!Objects.equals(identity, spoolIdentity))
		{
			spoolGeneration++;
		}
		spoolIdentity = identity;
	}

	void syncSpoolState()
	{
		String token = plugin.uploadAllowed() ? plugin.currentLinkToken() : null;
		String account = plugin.activeHash;
		String purge = null;
		long purgeGeneration = 0L;
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
					purgeGeneration = spoolGeneration;
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
			setSpoolIdentityLocked(now);
		}
		if (purge != null)
		{
			final String id = purge;
			final long gen = purgeGeneration;
			onSpoolThread(() -> purgeIdentityOnSpoolThread(id, false, gen));
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
	 * change made at the login screen, before any account was seen, knows no identity. The pointer names
	 * the last granted identity on this OS user; under the changed token it can never be the current one,
	 * so it is purged now rather than left for a grant that may never come. Creates nothing.
	 */
	void spoolOnTokenChangedWithoutAccount()
	{
		final Path root = spoolRoot();
		if (!Files.isDirectory(root))
		{
			return;
		}
		onSpoolThread(() -> purgeIdleIndexedIdentitiesExcept(null));
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
		long gen;
		synchronized (spoolStateLock)
		{
			spoolGrantToken = null;
			gen = spoolGeneration;
			setSpoolIdentityLocked(null);
		}
		if (token != null && validSpoolAccount(account))
		{
			final String id = EventSpool.identityKey(token, account);
			onSpoolThread(() -> purgeIdentityOnSpoolThread(id, true, gen));
		}
	}

	/**
	 * Writer thread. Make `id` the open identity. At the first grant for an identity: purge every OTHER indexed
	 * identity that has no live writer, BEFORE anything of this identity is replayed; age out foreign shards by
	 * record time and finish marked directories; recover this identity's abandoned shards; then record it in
	 * the index.
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
		// replayed, but ONLY when no live writer holds any of its shards. A second client running another
		// identity on this OS user is never marked or purged; its entry stays in the index and is retried at
		// the next grant or sweep, once that client is gone.
		purgeIdleIndexedIdentitiesExcept(id);
		// False while a withdrawal of this identity is still being finished by a live writer.
		return staff().openStaff(id);
	}

	/** Writer thread. Purge every indexed identity other than keep (may be null) and the active one with no live writer. */
	private void purgeIdleIndexedIdentitiesExcept(String keep)
	{
		Path root = spoolRoot();
		String current;
		long gen;
		synchronized (spoolStateLock)
		{
			current = spoolIdentity;
			gen = spoolGeneration;
		}
		for (String other : EventSpool.readIndex(root))
		{
			if (!other.equals(keep) && !other.equals(current))
			{
				purgeIdentityOnSpoolThread(other, false, gen);
			}
		}
	}

	/**
	 * Writer thread. Close our own instance if it is this identity (its shard is deleted), then purge the
	 * directory. withdrawn=true is a server-side withdrawal of THIS identity (X-Event-Spool off, 403): a shard
	 * another live process still holds gets a purge marker, so that writer stops too. withdrawn=false (a token
	 * or account change, another identity becoming active) never marks or deletes a live identity: the
	 * directory is purged only when every shard is free, and otherwise kept in the index for a later retry.
	 */
	private void purgeIdentityOnSpoolThread(String id, boolean withdrawn, long maxGeneration)
	{
		try
		{
			if (id.equals(staff().openId))
			{
				staffJournal.closeOwn();
			}
			spoolMemoryOwned.values().removeIf(t -> t.coveredBy(id, maxGeneration));
			releaseDurabilityGatesOf(id, maxGeneration);
			// A marked directory is finished by its writer or by the sweep; it need not stay indexed.
			boolean purged = staff().drop(id, withdrawn);
			if (purged)
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
		long gen;
		synchronized (spoolStateLock)
		{
			gen = spoolGeneration;
			if (id.equals(spoolIdentity))
			{
				spoolGrantToken = null;
				setSpoolIdentityLocked(null);
			}
		}
		spoolMemoryOwned.values().removeIf(t -> t.coveredBy(id, gen));
		releaseDurabilityGatesOf(id, gen);
		EventSpool.purgeIfIdle(dir);
		EventSpool.deletePointerIf(spoolRoot(), id);
		return true;
	}

	/** emitEventBound hook: durably append an allowlisted event while the spool is active. */
	void spoolOnEmit(String type, Map<String, Object> ev, String eventId)
	{
		if (!SPOOL_ALLOWLIST.contains(type) || spoolGrantToken == null)
		{
			return;
		}
		syncSpoolState();
		final String id;
		final SpoolTag tag;
		synchronized (spoolStateLock)
		{
			id = spoolIdentity;
			tag = id == null ? null : new SpoolTag(id, spoolGeneration);
		}
		if (id == null)
		{
			return;
		}
		Map<String, Object> wire = new LinkedHashMap<>(ev);
		wire.remove(EventQueue.EVENT_TOKEN_FP);	// identity is the shard, never a field
		final String json = plugin.gson.toJson(wire);
		long nowMs = wallClock.getAsLong();
		Object ts = ev.get("ts");
		final long created = ts instanceof Number ? Math.min(((Number) ts).longValue(), nowMs) : nowMs;
		spoolMemoryOwned.put(eventId, tag);
		// FORCE BEFORE NETWORK. Until the append below has been forced, flushEvents leaves this event in
		// pendingEvents (in place, in order) instead of POSTing it. Nothing here blocks the calling thread.
		// The gate is bounded by durabilityGateMaxMs, so a hung disk cannot stall delivery.
		spoolDurabilityPending.put(eventId, tag);
		if (plugin.executor != null)
		{
			plugin.executor.schedule(plugin.events::flushEvents, durabilityGateMaxMs + 50L, TimeUnit.MILLISECONDS);
		}
		boolean queued = onSpoolThread(() ->
		{
			boolean durable = false;
			try
			{
				if (ensureSpoolInstance(id) && !retireIfMarked())
				{
					// POINTER BEFORE SENSITIVE DATA: the hashed pointer (file and directory forced) names this
					// identity before its first row exists, so a crash right after the append still lets the
					// next process purge it without the token.
					if (!id.equals(EventSpool.readPointer(spoolRoot())))
					{
						EventSpool.writePointer(spoolDataRoot(), spoolRoot(), id, wallClock.getAsLong());
					}
					Runnable before = spoolBeforeAppendHookForTest;
					if (before != null)
					{
						before.run();
					}
					durable = spool().append(eventId, type, created, json);	// forced before it returns true
					Runnable after = spoolAfterAppendHookForTest;
					if (after != null)
					{
						after.run();
					}
				}
			}
			catch (IOException | RuntimeException e)
			{
				log.debug("OSRS BiS event spool append failed", e);
			}
			finally
			{
				releaseDurabilityGate(eventId, durable);
			}
		});
		if (!queued)
		{
			releaseDurabilityGate(eventId, false);
		}
	}

	/**
	 * The durable append for this event finished. durable=false (failure, no instance, a purge marker, a
	 * retired identity) falls back to today's memory-only behaviour and is counted. Either way the event is
	 * flushable again, and a flush is scheduled so it is not left waiting for the next tick.
	 */
	private void releaseDurabilityGate(String eventId, boolean durable)
	{
		if (spoolDurabilityPending.remove(eventId) == null)
		{
			return;	// already released (memory cap, identity change, purge)
		}
		if (!durable)
		{
			spoolMemoryOwned.remove(eventId);
			spoolCounters.appendFailed.incrementAndGet();
		}
		plugin.events.scheduleCoalescedFlush();
	}

	/**
	 * A purged or retired identity never leaves an event waiting on the durability gate. Scoped to the
	 * generations the purge was decided for: a re-activated identity's newer gates are not touched.
	 */
	private void releaseDurabilityGatesOf(String identity, long maxGeneration)
	{
		if (spoolDurabilityPending.values().removeIf(t -> t.coveredBy(identity, maxGeneration)))
		{
			plugin.events.scheduleCoalescedFlush();
		}
	}

	/**
	 * flushEvents: may this event go out now? False while its durable append is pending and younger than the
	 * bound. Past the bound the gate is released (counted, memory-only for this send, same event_id).
	 */
	boolean durabilityGateOpen(String eventId)
	{
		SpoolTag t = spoolDurabilityPending.get(eventId);
		if (t == null)
		{
			return true;
		}
		if (System.nanoTime() - t.sinceNanos < durabilityGateMaxMs * 1_000_000L)
		{
			return false;
		}
		if (spoolDurabilityPending.remove(eventId, t))
		{
			t.gateTimedOut = true;
			spoolCounters.gateTimeout.incrementAndGet();
		}
		return true;
	}

	/** The memory path dropped this event (cap or identity change). Its durable row, if any, can replay. */
	void spoolReleaseFromMemory(Map<String, Object> ev)
	{
		if (ev != null && ev.containsKey(EventQueue.EVENT_PUBLIC_OUTBOX))
			plugin.outbox.publicOutboxMemoryIds.remove(String.valueOf(ev.get("event_id")));
		Object id = ev == null ? null : ev.get("event_id");
		if (id != null)
		{
			spoolMemoryOwned.remove(String.valueOf(id));
			spoolDurabilityPending.remove(String.valueOf(id));	// a dropped event never holds the gate
		}
	}

	/** A memory POST finished with a final answer (not 429/5xx/network, which requeue in memory). */
	void spoolOnMemoryBatchDone(List<Map<String, Object>> batch, String sentToken, String body, int code)
	{
		final Map<String, String> spooled = new LinkedHashMap<>();
		final List<String> owned = new ArrayList<>();
		for (Map<String, Object> ev : batch)
		{
			Object raw = ev.get("event_id");
			String eid = raw == null ? null : String.valueOf(raw);
			SpoolTag owner = eid == null ? null : spoolMemoryOwned.get(eid);
			if (owner != null)
			{
				owned.add(eid);
				if (!owner.gateTimedOut)
				{
					spooled.put(eid, owner.identity);
				}
				// A timed-out id was sent BEFORE its row was durable, so this answer cannot settle that row:
				// once durable it replays, and the server's per-id "duplicate" deletes it.
			}
		}
		if (code == 403)
		{
			spoolMemoryOwned.keySet().removeAll(owned);
			spoolOnRevoked(sentToken);
			return;
		}
		if (owned.isEmpty())
		{
			return;
		}
		onSpoolThread(() ->
		{
			spoolMemoryOwned.keySet().removeAll(owned);
			if (!spooled.isEmpty())
			{
				settleOnSpoolThread(spooled, code, body, -1L);
			}
		});
	}

	/**
	 * Writer thread. Settle the durable copies of one sent batch (ids -> identity they were written under).
	 * Rows of an identity that is no longer open are not touched here; the purge already removed them.
	 */
	private void settleOnSpoolThread(Map<String, String> sent, int code, String body, long retryAfterMs)
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
					armSpoolReplayBackoff(0L);
				}
				else
				{
					spoolReplayBackoffMs = 0L;	// every row of this batch is settled: reset the ladder
					spoolReplayBackoffUntilMs = 0L;
				}
				spool.maybeCompact();
			}
			else if (code == 404 || code == 408 || code == 429 || code >= 500 || code < 0)
			{
				armSpoolReplayBackoff(retryAfterMs);
			}
			else if (code >= 400 && code != 403)
			{
				spool.quarantine(mine, code);
			}
		}
		catch (IOException e)
		{
			log.debug("OSRS BiS event spool settle failed", e);
		}
	}

	private void armSpoolReplayBackoff(long retryAfterMs)
	{
		long next = EventQueue.nextBackoff(spoolReplayBackoffMs, retryAfterMs);
		spoolReplayBackoffMs = next;
		spoolReplayBackoffUntilMs = plugin.nowMs() + next;
	}

	/** Writer thread. Replay candidates: oldest first, recovered rows first, never an id something else owns. */
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
		// and no recovery still never replays a row older than 48 h.
		try
		{
			spool.enforceBoundsNow();
		}
		catch (IOException e)
		{
			log.debug("OSRS BiS event spool age-out failed", e);
			return out;	// fail closed: nothing replays if the age cannot be enforced
		}
		for (EventSpool.Record r : spool.pending())
		{
			if (!spoolMemoryOwned.containsKey(r.eventId) && !spoolReplayIds.contains(r.eventId))
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * The paced replay, on the 5 s schedule. At most one request per SPOOL_REPLAY_MIN_GAP_MS, at most 100
	 * events and under 256 KiB, none while a pause, a backoff or a previous replay is open.
	 */
	void replaySpoolTick()
	{
		if (spoolGrantToken == null)
		{
			return;
		}
		syncSpoolState();
		final String id = spoolIdentity;
		final String token = plugin.currentLinkToken();
		if (id == null || spoolReplayPaused || spoolReplayInFlight)
		{
			return;
		}
		long now = plugin.nowMs();
		if (now < spoolReplayNotBeforeMs || now < spoolReplayBackoffUntilMs)
		{
			return;
		}
		spoolReplayInFlight = true;
		onSpoolThread(() ->
		{
			boolean sent = false;
			try
			{
				sent = sendSpoolReplay(id, token);
			}
			finally
			{
				if (!sent)
				{
					spoolReplayInFlight = false;
				}
			}
		});
	}

	/** Is the identity a replay batch was built for still the live one (token fingerprint and account)? */
	private boolean replayIdentityStillCurrent(String id, String token)
	{
		String live = plugin.currentLinkToken();
		String account = plugin.activeHash;
		return token.equals(live) && EventSpool.sha256Hex("event-token:" + token).equals(plugin.events.currentEventTokenFingerprint())
			&& validSpoolAccount(account) && id.equals(EventSpool.identityKey(live, account))
			&& id.equals(spoolIdentity);
	}

	/** Writer thread. Build and enqueue one replay request. True when a request is in flight. */
	private boolean sendSpoolReplay(String id, String token)
	{
		try
		{
			if (!ensureSpoolInstance(id) || retireIfMarked())
			{
				return false;
			}
			EventSpool spool = spool();
			spool.maybeCompact();
			if (!id.equals(spoolIdentity) || !token.equals(plugin.currentLinkToken()) || spoolReplayPaused)
			{
				return false;
			}
			List<EventSpool.Record> candidates = spoolReplayCandidates();
			if (candidates.isEmpty())
			{
				return false;
			}
			List<String> encoded = new ArrayList<>();
			List<EventSpool.Record> eligible = new ArrayList<>();
			List<String> privacyRejected = new ArrayList<>();
			for (EventSpool.Record candidate : candidates)
			{
				Map<String, Object> wire = PublicTransport.event(plugin.gson.fromJson(candidate.eventJson, Map.class), plugin.grants.captureEnabled());
				if (wire == null)
				{
					privacyRejected.add(candidate.eventId);
					continue;
				}
				eligible.add(candidate);
				encoded.add(plugin.gson.toJson(wire));
				if (eligible.size() == EventQueue.EVENT_BATCH_MAX_EVENTS) break;
			}
			// -1 is a local privacy rejection, not an HTTP ACK. No payload enters quarantine.
			spool.quarantine(privacyRejected, -1);
			candidates = eligible;
			if (candidates.isEmpty()) return false;
			String tokenJson = plugin.gson.toJson(token);
			int kept = EventQueue.fitEventBatch(tokenJson, encoded, false);
			final Map<String, String> sentIds = new LinkedHashMap<>();
			long oldestCreated = Long.MAX_VALUE;
			for (int i = 0; i < kept; i++)
			{
				sentIds.put(candidates.get(i).eventId, id);
				oldestCreated = Math.min(oldestCreated, candidates.get(i).createdMs);
			}
			Request request = plugin.events.eventRequest(tokenJson, encoded.subList(0, kept), false, new SpoolReplayAge(oldestCreated));
			Runnable beforeDispatch = spoolReplayBeforeDispatchHookForTest;
			if (beforeDispatch != null)
			{
				beforeDispatch.run();
			}
			// FINAL IDENTITY CHECK, immediately before dispatch: a batch built under one token or account is
			// never sent once either has changed, whatever happened while it was being built.
			if (!replayIdentityStillCurrent(id, token))
			{
				return false;
			}
			spoolReplayIds.addAll(sentIds.keySet());
			spoolReplayNotBeforeMs = plugin.nowMs() + SPOOL_REPLAY_MIN_GAP_MS;
			spoolCounters.replayed.addAndGet(kept);
			try
			{
				spoolReplayHttp().newCall(request).enqueue(EventQueue.answered(e -> finishReplay(sentIds, -1, null, 0L),
					response -> replayAnswer(sentIds, token, response)));
			}
			catch (RuntimeException e)
			{
				spoolReplayIds.removeAll(sentIds.keySet());
				armSpoolReplayBackoff(0L);
				return false;
			}
			return true;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("OSRS BiS event spool replay could not be built", e);
			return false;
		}
	}

	/** The staff replay lane's answer: 403 revokes; retries retain their bounded server delay. */
	private void replayAnswer(Map<String, String> sentIds, String sentToken, Response response)
	{
		int code = response.code();
		if (code == 403)
		{
			spoolOnRevoked(sentToken);
			finishReplay(sentIds, code, null, 0L);
			return;
		}
		long retryAfterMs = EventQueue.retryAfterMillis(response);
		finishReplay(sentIds, code, response.isSuccessful() ? EventQueue.readBody(response) : null, retryAfterMs);
	}

	private void finishReplay(Map<String, String> sentIds, int code, String body, long retryAfterMs)
	{
		if (code == 429)
		{
			// Stop replay on the FIRST 429, before the writer thread even runs: the next tick must not send.
			armSpoolReplayBackoff(retryAfterMs);
		}
		onSpoolThread(() ->
		{
			try
			{
				spoolReplayIds.removeAll(sentIds.keySet());
				if (code != 429)
				{
					settleOnSpoolThread(sentIds, code, body, retryAfterMs);
				}
				if (code >= 200 && code < 300)
				{
					plugin.events.healthLastEventUploadMs.set(System.currentTimeMillis());
				}
			}
			finally
			{
				spoolReplayInFlight = false;
			}
		});
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
		plugin.grants.bankJournalGrantToken = null;
		// A re-enable is treated like a cold start: nothing is written or replayed until the server
		// sends X-Event-Spool again for the configured token.
		synchronized (spoolStateLock)
		{
			spoolGrantToken = null;
			plugin.grants.eventQueueGrantToken = null;
			plugin.grants.eventQueueScope = null;
			setSpoolIdentityLocked(null);
		}
		if (spoolExec == null)
		{
			return;
		}
		onSpoolThread(() ->
		{
			if (plugin.outbox.publicOutbox != null) plugin.outbox.publicOutbox.close();
			if (plugin.outbox.publicBankOutbox != null) plugin.outbox.publicBankOutbox.close();
			if (staffJournal != null) staffJournal.close();
		});
	}
}
