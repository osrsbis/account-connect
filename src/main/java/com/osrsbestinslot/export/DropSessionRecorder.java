/*
 * Drop-trade session lifecycle — the state machine behind one continuous proof clip.
 *
 * A staff drop trade is several Drop actions, several piles on the ground, and several removals,
 * spread over as long as the customer takes to arrive. The evidence that proves it is ONE clip
 * covering all of that, not one clip per drop.
 *
 * This class owns WHEN that clip starts, when it may stop, and which drops belong to it. It is
 * deliberately pure: no RuneLite, no AWT, no clock of its own — every method takes the caller's
 * millisecond clock. That is what lets the whole lifecycle be driven under test, including the
 * races that only appear at real timing (a second drop landing inside the stop tail).
 *
 * THE CONTRACT, restated after the 0.7.14 privacy review found it unmet:
 *
 *   FIRST Drop action -> one continuous session
 *     -> every live pile AND every pending drop that has not yet produced a pile is represented
 *     -> no stop while ANY of those remain
 *     -> the final pile disappears, or the final pending drop definitively expires
 *     -> exactly TAIL_MILLIS more -> stop.
 *
 * WHAT WAS WRONG BEFORE. The stop was armed from pileRemoved ALONE. Three ordinary cases never
 * reached it, and each left a recorder that ran until the player logged out:
 *
 *   A. A stackable dropped twice onto one tile merges into ONE physical pile, and the client fires
 *      ONE ItemDespawned for it. The old code made two session keys, so the second was orphaned.
 *      Fixed at the caller: a merged drop resolves its pending against the pile already live and
 *      never mints a second key.
 *   B. A Drop click that never produces a tracked pile (the destroy dialog answered No, a pile
 *      landing out of range, a region boundary) left activePiles empty with nothing to remove.
 *      Fixed here: every Drop action registers a PENDING drop with a deadline, and an expired
 *      pending releases the session exactly as a removed pile does.
 *   C. Eviction past the caller's tracking cap orphaned a key nobody could ever release.
 *      Fixed here: pileAbandoned releases the key and marks the session unprovable.
 *
 * AND THE RULE THAT FOLLOWS FROM ALL THREE. A session whose coverage cannot be PROVEN complete
 * never reports COMPLETE. An expired pending drop, an abandoned pile and a live pile at finish
 * time are all INTERRUPTED, because in each case something the clip was supposed to show was not
 * observed end to end.
 *
 * THE RULES IT ENFORCES, each of which was asked for explicitly:
 *   1. Recording starts at the FIRST Drop ACTION, not at the drop event, so the drop itself is on
 *      screen. The action fires on the click; the event only exists once the inventory and the
 *      ground spawn agree, which is ticks later.
 *   2. Recording continues while ANY pile or pending drop from this session is outstanding.
 *   3. When the last of those goes, recording runs on for exactly TAIL_MILLIS more.
 *   4. A new drop inside that tail CANCELS the stop and continues the SAME session. It never
 *      starts a second one.
 *   5. Two overlapping sessions can never exist: there is one session field, and a drop either
 *      starts it or joins it.
 */
package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One drop-trade recording session. Not thread-safe by itself: every caller in the plugin is the
 * client thread, and the one background reader (the flusher) takes the plugin's own lock.
 */
public class DropSessionRecorder
{
	/**
	 * How long recording continues after the LAST pile leaves the ground.
	 *
	 * Five seconds, as specified. It is long enough to show the tile empty and whoever is standing
	 * on it walking away, and short enough that an idle staff client is not recording for free.
	 */
	public static final long TAIL_MILLIS = 5_000L;

	/**
	 * How long a Drop ACTION may wait for its ground pile before the session gives up on it.
	 *
	 * This is the bound that makes rule 3 reachable at all. The click arms a pending; the pile
	 * normally spawns within a tick or two. Ten seconds covers the slowest real path — the
	 * untradeable destroy dialog, which the caller already sizes at DROP_PENDING_MAX_TICKS (16
	 * ticks, about 9.6 seconds) — and then declares the drop unobserved rather than waiting
	 * forever. A pending that expires is NOT evidence of a pile, so the session it belongs to can
	 * never call itself COMPLETE.
	 */
	public static final long PENDING_DROP_EXPIRY_MILLIS = 10_000L;

	/** No stop is pending. Deliberately not 0, which is a legal clock value under test. */
	private static final long NO_STOP = -1L;

	/** How a session ended. The clip is uploaded either way — INTERRUPTED is evidence, not litter. */
	public enum Outcome
	{
		/** Every drop produced a pile, every pile resolved, and the tail elapsed. */
		COMPLETE,
		/**
		 * Coverage could not be proven whole. A hop, a logout, a disconnect, a scene reload, a
		 * withdrawn grant, a drop that never produced an observable pile, or a pile the client
		 * stopped tracking. What was captured still uploads, labelled so nobody reads a partial
		 * clip as a whole one.
		 */
		INTERRUPTED
	}

	/** Why a session could not report COMPLETE. Reporting only; never used to decide the outcome. */
	public static final String REASON_NONE = "none";
	public static final String REASON_EXTERNAL = "external_interrupt";
	public static final String REASON_LIVE_PILES = "piles_still_live";
	public static final String REASON_PENDING_DROPS = "pending_drops_outstanding";
	public static final String REASON_PENDING_EXPIRED = "pending_drop_expired";
	public static final String REASON_PILE_ABANDONED = "pile_abandoned";

	private String sessionId;
	private int nextSeq;
	private long startedAtMillis;
	private long stopAtMillis = NO_STOP;
	private boolean interrupted;
	/** Pile keys still on the ground for THIS session. A LinkedHashSet so ordering is reproducible. */
	private final Set<String> activePiles = new LinkedHashSet<>();
	/**
	 * Drop actions that have not yet produced an observable pile, mapped to the instant they give up.
	 *
	 * This is the half the old code was missing. Without it a Drop click that never spawns a pile
	 * leaves NOTHING outstanding, the tail arms off a pile removal that can never come, and the
	 * recorder runs until an external interrupt. A LinkedHashMap so expiry order is reproducible.
	 */
	private final Map<Integer, Long> pendingDrops = new LinkedHashMap<>();
	/** Drops recorded this session. Kept so a session that never spawned a pile still reports its size. */
	private int dropCount;
	/** Set the moment anything happens that makes full coverage unprovable. Never cleared mid-session. */
	private boolean unprovable;
	private String reason = REASON_NONE;

	/** The current session id, or null when idle. */
	public String sessionId()
	{
		return sessionId;
	}

	public boolean active()
	{
		return sessionId != null;
	}

	public long startedAtMillis()
	{
		return startedAtMillis;
	}

	public int dropCount()
	{
		return dropCount;
	}

	public int activePileCount()
	{
		return activePiles.size();
	}

	/** Drop actions still waiting for a pile. Zero live piles AND zero of these means the tail may arm. */
	public int pendingDropCount()
	{
		return pendingDrops.size();
	}

	/** True when this session can no longer prove it covered every drop end to end. */
	public boolean unprovable()
	{
		return unprovable;
	}

	/** Why the session cannot report COMPLETE, or REASON_NONE. Reporting only. */
	public String reason()
	{
		if (interrupted)
		{
			return REASON_EXTERNAL;
		}
		if (!reason.equals(REASON_NONE))
		{
			return reason;
		}
		if (!activePiles.isEmpty())
		{
			return REASON_LIVE_PILES;
		}
		if (!pendingDrops.isEmpty())
		{
			return REASON_PENDING_DROPS;
		}
		return REASON_NONE;
	}

	/** True while a stop is armed and has not yet fired. */
	public boolean stopPending()
	{
		return stopAtMillis != NO_STOP;
	}

	/**
	 * A Drop action happened. Starts a session when idle, and otherwise JOINS the running one —
	 * cancelling any armed stop, which is rule 4.
	 *
	 * The returned sequence number is ALSO registered as a pending drop. The caller must later
	 * resolve it, with pileActive when a pile spawns for it or with pendingDropResolved when the
	 * drop merged into a pile that is already live. An unresolved pending expires on its own.
	 *
	 * @param sessionIdIfNew id to adopt when this starts a new session. The caller mints it so the id
	 *                       generator stays out of this class and the tests can pin it.
	 * @return the sequence number for this drop, starting at 1 within a session.
	 */
	public int onDropAction(String sessionIdIfNew, long nowMillis)
	{
		if (sessionId == null)
		{
			sessionId = sessionIdIfNew;
			startedAtMillis = nowMillis;
			nextSeq = 0;
			interrupted = false;
			unprovable = false;
			reason = REASON_NONE;
			activePiles.clear();
			pendingDrops.clear();
			dropCount = 0;
		}
		// Rule 4: a drop inside the tail cancels the stop. Unconditional on purpose — cancelling a
		// stop that is not armed is a no-op, and making it conditional invites a caller to forget.
		stopAtMillis = NO_STOP;
		dropCount++;
		int seq = ++nextSeq;
		pendingDrops.put(seq, nowMillis + PENDING_DROP_EXPIRY_MILLIS);
		return seq;
	}

	/**
	 * A pile from this session is now on the ground, produced by the drop numbered {@code seq}.
	 *
	 * Resolves that drop's pending, so the pair (action, pile) is now represented by the pile alone.
	 * Also cancels a pending stop: a pile can spawn a tick or two AFTER the drop action, so a fast
	 * session could otherwise arm its stop between the action and the spawn.
	 */
	public void pileActive(String pileKey, int seq)
	{
		if (sessionId == null || pileKey == null)
		{
			return;
		}
		pendingDrops.remove(seq);
		activePiles.add(pileKey);
		stopAtMillis = NO_STOP;
	}

	/**
	 * The drop numbered {@code seq} merged into a pile that is ALREADY live in this session.
	 *
	 * This is finding F1 path A. Dropping a stackable onto an existing ground stack of the same item
	 * merges it: the game has ONE pile and fires ONE ItemDespawned, so minting a second key here
	 * would orphan it forever. The pending is resolved against the existing pile instead, and no new
	 * key is created.
	 *
	 * Does NOT cancel an armed stop on its own, because the pile it merged into is live and a live
	 * pile means no stop can be armed in the first place.
	 */
	public void pendingDropResolved(int seq)
	{
		if (sessionId == null)
		{
			return;
		}
		pendingDrops.remove(seq);
	}

	/**
	 * A pile from this session left the ground. When nothing is outstanding, arm the TAIL_MILLIS tail.
	 *
	 * Arming only when the LAST outstanding thing goes is rule 2: a session with three piles keeps
	 * recording while any of the three is still there, however long the customer takes.
	 */
	public void pileRemoved(String pileKey, long nowMillis)
	{
		if (sessionId == null || pileKey == null)
		{
			return;
		}
		if (!activePiles.remove(pileKey))
		{
			return;		// not ours, or already counted — never arm a stop off an unknown pile
		}
		maybeArmStop(nowMillis);
	}

	/**
	 * The client stopped tracking a pile without ever observing it leave the ground.
	 *
	 * Finding F1 path C: the caller's ground-item tracker is bounded, and an eviction past that
	 * bound used to strand the key here with nothing able to release it. Releasing it is what stops
	 * the recorder; marking the session unprovable is what stops that release from being mistaken
	 * for the pile actually going.
	 */
	public void pileAbandoned(String pileKey, long nowMillis)
	{
		if (sessionId == null || pileKey == null)
		{
			return;
		}
		if (!activePiles.remove(pileKey))
		{
			return;
		}
		unprovable = true;
		if (reason.equals(REASON_NONE))
		{
			reason = REASON_PILE_ABANDONED;
		}
		maybeArmStop(nowMillis);
	}

	/**
	 * Advance the clock: expire any pending drop past its deadline, then arm the tail if nothing is
	 * left outstanding.
	 *
	 * THE CALLER MUST RUN THIS ON A TIMER, once per tick. It is the only thing that can end a
	 * session whose drops never produced a pile, and without it case 13 — recording with zero piles
	 * and zero pendings — is reachable again.
	 *
	 * @return the sequence numbers that expired on this call, so the caller can drop their bookkeeping.
	 */
	public List<Integer> settle(long nowMillis)
	{
		List<Integer> expired = new ArrayList<>();
		if (sessionId == null)
		{
			return expired;
		}
		Iterator<Map.Entry<Integer, Long>> it = pendingDrops.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, Long> e = it.next();
			if (nowMillis >= e.getValue())
			{
				expired.add(e.getKey());
				it.remove();
			}
		}
		if (!expired.isEmpty())
		{
			// An expired pending is a drop nobody ever saw land. The clip may still show it, but the
			// session cannot PROVE it did, so COMPLETE is off the table from here.
			unprovable = true;
			if (reason.equals(REASON_NONE))
			{
				reason = REASON_PENDING_EXPIRED;
			}
		}
		maybeArmStop(nowMillis);
		return expired;
	}

	/**
	 * Arm the tail when, and only when, nothing is outstanding and no stop is armed already.
	 *
	 * Guarding on {@code stopAtMillis == NO_STOP} keeps an already-armed deadline from sliding
	 * forward every tick, which would be a second way to record forever.
	 */
	private void maybeArmStop(long nowMillis)
	{
		if (sessionId == null || stopAtMillis != NO_STOP)
		{
			return;
		}
		if (activePiles.isEmpty() && pendingDrops.isEmpty())
		{
			stopAtMillis = nowMillis + TAIL_MILLIS;
		}
	}

	/**
	 * Has the armed tail elapsed?
	 *
	 * Note the {@code >=}: a caller polling on a tick boundary can land exactly on the deadline, and
	 * a strict {@code >} there would wait a whole extra tick for no reason.
	 */
	public boolean shouldStop(long nowMillis)
	{
		return stopAtMillis != NO_STOP && nowMillis >= stopAtMillis;
	}

	/**
	 * Milliseconds left on the tail, or -1 when no stop is armed. Reporting only.
	 *
	 * Clamped at 0 so an overdue stop reads as "due now" rather than as a negative duration.
	 */
	public long tailRemainingMillis(long nowMillis)
	{
		if (stopAtMillis == NO_STOP)
		{
			return -1L;
		}
		return Math.max(0L, stopAtMillis - nowMillis);
	}

	/**
	 * A hop, logout, disconnect, scene reload or withdrawn grant happened. The session ends as
	 * INTERRUPTED.
	 *
	 * The captured frames are NOT discarded by this call: an interrupted drop trade is exactly the
	 * case somebody will need to look at, and throwing the footage away because the session did not
	 * end tidily is how evidence disappears. The flag only changes the LABEL the clip carries.
	 * Whether the frames are uploaded is the caller's decision, and a withdrawn upload consent is
	 * the one case where the caller discards them.
	 */
	public void interrupt()
	{
		if (sessionId == null)
		{
			return;
		}
		interrupted = true;
		unprovable = true;
		stopAtMillis = 0L;	// due immediately — the caller's next poll stops it
	}

	/**
	 * End the session and return how it ended. Idempotent: a second call on an idle recorder
	 * returns COMPLETE and changes nothing.
	 *
	 * COMPLETE REQUIRES PROOF, and there are four ways to lose it: an explicit interrupt, a pile
	 * still live, a drop still pending, and the unprovable flag an expired pending or an abandoned
	 * pile sets. Any of them means the clip is not known to cover the whole trade, and a manifest
	 * that said COMPLETE anyway would be a false statement about evidence.
	 */
	public Outcome finish()
	{
		boolean proven = !interrupted
			&& !unprovable
			&& activePiles.isEmpty()
			&& pendingDrops.isEmpty();
		Outcome outcome = proven ? Outcome.COMPLETE : Outcome.INTERRUPTED;
		sessionId = null;
		stopAtMillis = NO_STOP;
		interrupted = false;
		unprovable = false;
		reason = REASON_NONE;
		activePiles.clear();
		pendingDrops.clear();
		nextSeq = 0;
		dropCount = 0;
		startedAtMillis = 0L;
		return outcome;
	}

	/**
	 * The stable key for one pile: item, tile and the sequence number of the drop that made it.
	 *
	 * The sequence number is what makes two piles of the SAME item on the SAME tile distinguishable
	 * when the game really does hold two piles. When the game MERGES them into one — dropping a
	 * stackable onto an existing stack — the caller must not mint a second key at all; see
	 * {@link #pendingDropResolved(int)}.
	 */
	public static String pileKey(int item, int x, int y, int plane, int seq)
	{
		return item + ":" + x + ":" + y + ":" + plane + ":" + seq;
	}
}
