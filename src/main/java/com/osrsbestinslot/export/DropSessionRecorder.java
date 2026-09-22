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
 * THE RULES IT ENFORCES, each of which was asked for explicitly:
 *   1. Recording starts at the FIRST Drop ACTION, not at the drop event, so the drop itself is on
 *      screen. The action fires on the click; the event only exists once the inventory and the
 *      ground spawn agree, which is ticks later.
 *   2. Recording continues while ANY pile from this session is still on the ground.
 *   3. When the last pile goes, recording runs on for exactly TAIL_MILLIS more.
 *   4. A new drop inside that tail CANCELS the stop and continues the SAME session. It never
 *      starts a second one.
 *   5. Two overlapping sessions can never exist: there is one session field, and a drop either
 *      starts it or joins it.
 */
package com.osrsbestinslot.export;

import java.util.LinkedHashSet;
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

	/** No stop is pending. Deliberately not 0, which is a legal clock value under test. */
	private static final long NO_STOP = -1L;

	/** How a session ended. The clip is uploaded either way — INTERRUPTED is evidence, not litter. */
	public enum Outcome
	{
		/** Every pile resolved and the tail elapsed. The clip covers the whole trade. */
		COMPLETE,
		/**
		 * A hop, a logout, a disconnect or a scene reload ended the session while piles were still
		 * live. What was captured still uploads, labelled so nobody reads a partial clip as a whole
		 * one.
		 */
		INTERRUPTED
	}

	private String sessionId;
	private int nextSeq;
	private long startedAtMillis;
	private long stopAtMillis = NO_STOP;
	private boolean interrupted;
	/** Pile keys still on the ground for THIS session. A LinkedHashSet so ordering is reproducible. */
	private final Set<String> activePiles = new LinkedHashSet<>();
	/** Drops recorded this session. Kept so a session that never spawned a pile still reports its size. */
	private int dropCount;

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

	/** True while a stop is armed and has not yet fired. */
	public boolean stopPending()
	{
		return stopAtMillis != NO_STOP;
	}

	/**
	 * A Drop action happened. Starts a session when idle, and otherwise JOINS the running one —
	 * cancelling any armed stop, which is rule 4.
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
			activePiles.clear();
			dropCount = 0;
		}
		// Rule 4: a drop inside the tail cancels the stop. Unconditional on purpose — cancelling a
		// stop that is not armed is a no-op, and making it conditional invites a caller to forget.
		stopAtMillis = NO_STOP;
		dropCount++;
		return ++nextSeq;
	}

	/**
	 * A pile from this session is now on the ground.
	 *
	 * Also cancels a pending stop. A pile can spawn a tick or two AFTER the drop action, so a fast
	 * session could otherwise arm its stop between the action and the spawn.
	 */
	public void pileActive(String pileKey)
	{
		if (sessionId == null || pileKey == null)
		{
			return;
		}
		activePiles.add(pileKey);
		stopAtMillis = NO_STOP;
	}

	/**
	 * A pile from this session left the ground. When it was the last one, arm the 5-second tail.
	 *
	 * Arming on the LAST pile only is rule 2: a session with three piles keeps recording while any
	 * of the three is still there, however long the customer takes.
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
		if (activePiles.isEmpty())
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
	 * A hop, logout, disconnect or scene reload happened. The session ends as INTERRUPTED.
	 *
	 * The captured frames are NOT discarded: an interrupted drop trade is exactly the case somebody
	 * will need to look at, and throwing the footage away because the session did not end tidily is
	 * how evidence disappears. The flag only changes the LABEL the clip carries.
	 */
	public void interrupt()
	{
		if (sessionId == null)
		{
			return;
		}
		interrupted = true;
		stopAtMillis = 0L;	// due immediately — the caller's next poll stops it
	}

	/**
	 * End the session and return how it ended. Idempotent: a second call on an idle recorder
	 * returns COMPLETE and changes nothing.
	 *
	 * A session still holding live piles at finish time is INTERRUPTED even without an explicit
	 * interrupt() — the only way to reach here with piles outstanding is an abnormal end.
	 */
	public Outcome finish()
	{
		Outcome outcome = (interrupted || !activePiles.isEmpty()) ? Outcome.INTERRUPTED : Outcome.COMPLETE;
		sessionId = null;
		stopAtMillis = NO_STOP;
		interrupted = false;
		activePiles.clear();
		nextSeq = 0;
		dropCount = 0;
		startedAtMillis = 0L;
		return outcome;
	}

	/**
	 * The stable key for one pile: item, tile and the sequence number of the drop that made it.
	 *
	 * The sequence number is what makes two piles of the SAME item on the SAME tile distinguishable,
	 * which is the normal shape of a drop trade — drop 50 coins twice on one tile and a key built
	 * from item and tile alone collapses them into one, so the second removal finds nothing and the
	 * session's stop arms early.
	 */
	public static String pileKey(int item, int x, int y, int plane, int seq)
	{
		return item + ":" + x + ":" + y + ":" + plane + ":" + seq;
	}
}
