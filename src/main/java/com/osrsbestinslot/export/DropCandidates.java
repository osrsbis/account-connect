/*
 * Pile-centred counterparty candidates, and the one rule that turns them into a name.
 *
 * ⚠ THE HONESTY CONSTRAINT, AND IT IS THE WHOLE POINT OF THIS FILE.
 * The Take happens in the OTHER player's client. Nothing in our packet stream names the taker, so
 * no amount of geometry can prove who picked a pile up. What this class produces is EVIDENCE with a
 * stated strength, never a fact. The one case worth naming is the case a human would accept on
 * sight: exactly one other player standing ON the pile's own tile at the moment it vanished.
 * Everything else — two people on the tile, nobody on it, somebody one step away — resolves to
 * AMBIGUOUS or UNKNOWN and names nobody.
 *
 * WHY PILE-CENTRED AND NOT PLAYER-CENTRED. The existing nearby snapshot measures from OUR
 * character. In a drop trade the staff member routinely walks off before the customer arrives, so a
 * player at distance 0 from US is not on the pile, and a player on the pile can be many tiles from
 * us. Measuring from the pile's own tile is the difference between an answer and a coincidence.
 *
 * Pure: JDK only, no RuneLite types, so every branch is testable without a client.
 */
package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DropCandidates
{
	private DropCandidates()
	{
	}

	/** How the counterparty question was answered for one pile. Written onto every removal row. */
	public static final String STATUS_RESOLVED = "RESOLVED";
	/** More than one player stood on the pile's tile. Somebody took it; we cannot say which. */
	public static final String STATUS_AMBIGUOUS = "AMBIGUOUS";
	/** Nobody was on the tile, or the cause was not an early removal. Nothing to say. */
	public static final String STATUS_UNKNOWN = "UNKNOWN";

	/**
	 * How far from the pile a player is still recorded as a candidate.
	 *
	 * Wider than the resolve rule on purpose. Only distance 0 ever RESOLVES, but a reviewer looking
	 * at an ambiguous pile wants to see who else was around, and the server-side resolver may later
	 * weigh persistence across ticks. Recording more than we resolve on costs a few bytes and keeps
	 * the evidence; resolving on more than distance 0 would be a guess.
	 */
	public static final int CANDIDATE_RANGE_TILES = 3;

	/**
	 * Cap on recorded candidates for one pile. A crowded tile cannot make the row unbounded. Nearest
	 * first, so the players that matter are never the ones cut.
	 */
	public static final int CANDIDATE_CAP = 8;

	/** Animation id when the client reports none (the game's own idle value). */
	public static final int NO_ANIMATION = -1;

	/** One observed player, in world tiles. Deliberately a plain value type, not a RuneLite Player. */
	public static final class Observed
	{
		public final String rsn;
		public final int x;
		public final int y;
		public final int plane;
		public final int combatLevel;
		/** The animation the player was playing at the moment of observation, or NO_ANIMATION. */
		public final int animation;

		public Observed(String rsn, int x, int y, int plane, int combatLevel)
		{
			this(rsn, x, y, plane, combatLevel, NO_ANIMATION);
		}

		public Observed(String rsn, int x, int y, int plane, int combatLevel, int animation)
		{
			this.rsn = rsn;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.combatLevel = combatLevel;
			this.animation = animation;
		}
	}

	/**
	 * One sighting of a Telekinetic Grab aimed at a tile: the travelling projectile (which names its
	 * caster) or the impact graphic on the tile (which does not). A grab takes a pile from up to ten
	 * tiles away, so a player standing on the tile when it vanished may be an innocent bystander.
	 */
	public static final class TelegrabSighting
	{
		public final int x;
		public final int y;
		public final int plane;
		public final int tick;
		/** "projectile" or "impact". */
		public final String via;
		/** Caster name, only known from a projectile. Null otherwise. */
		public final String caster;
		/** True when the caster is our own character. */
		public final boolean casterSelf;
		public final int casterX;
		public final int casterY;

		public TelegrabSighting(int x, int y, int plane, int tick, String via,
			String caster, boolean casterSelf, int casterX, int casterY)
		{
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.tick = tick;
			this.via = via;
			this.caster = caster;
			this.casterSelf = casterSelf;
			this.casterX = casterX;
			this.casterY = casterY;
		}
	}

	/**
	 * How many ticks before a pile vanished a grab aimed at its tile still counts. The projectile flies
	 * for a few ticks before the item leaves the ground; ten covers the longest cast range with margin
	 * and keeps an old, unrelated grab on the same tile out.
	 */
	public static final int TELEGRAB_WINDOW_TICKS = 10;

	/** Telekinetic Grab reaches about ten tiles; a caster further out cannot have taken the pile. */
	public static final int TELEGRAB_RANGE_TILES = 10;

	/** The player animation of a Telekinetic Grab cast (gameval AnimationID.HUMAN_CASTTELEGRAB). */
	public static final int TELEGRAB_CAST_ANIMATION = 723;

	/** The travelling grab projectile (gameval SpotanimID.TELEGRAB_TRAVEL). */
	public static final int TELEGRAB_PROJECTILE = 143;

	/** The grab's impact graphic on the item's tile (gameval SpotanimID.TELEGRAB_IMPACT). */
	public static final int TELEGRAB_IMPACT = 144;

	/**
	 * The Telekinetic Grab evidence for one pile, or null when no grab aimed at this exact tile was seen
	 * in the window before the removal. A named caster comes from the most recent projectile.
	 */
	public static Map<String, Object> telegrabAt(List<TelegrabSighting> sightings,
		int pileX, int pileY, int pilePlane, int removalTick)
	{
		if (sightings == null)
		{
			return null;
		}
		TelegrabSighting latest = null;
		TelegrabSighting named = null;
		java.util.Set<String> via = new java.util.TreeSet<>();
		for (TelegrabSighting s : sightings)
		{
			if (s == null || s.x != pileX || s.y != pileY || s.plane != pilePlane)
			{
				continue;
			}
			int age = removalTick - s.tick;
			if (age < 0 || age > TELEGRAB_WINDOW_TICKS)
			{
				continue;
			}
			via.add(s.via);
			if (latest == null || s.tick >= latest.tick)
			{
				latest = s;
			}
			if ((s.caster != null || s.casterSelf) && (named == null || s.tick >= named.tick))
			{
				named = s;
			}
		}
		if (latest == null)
		{
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("via", new ArrayList<>(via));
		m.put("ticks_before", removalTick - latest.tick);
		if (named != null)
		{
			if (named.casterSelf)
			{
				m.put("caster_self", true);
			}
			else
			{
				m.put("caster", named.caster);
				m.put("caster_dx", named.casterX - pileX);
				m.put("caster_dy", named.casterY - pileY);
				m.put("caster_dist", tileDistance(named.casterX, named.casterY, pileX, pileY));
			}
		}
		return m;
	}

	/**
	 * Chebyshev distance, which is how OSRS measures tiles: one diagonal step is distance 1, not
	 * 1.41. A Euclidean measure here would make a diagonal neighbour look further away than an
	 * orthogonal one when the game treats them identically.
	 */
	public static int tileDistance(int ax, int ay, int bx, int by)
	{
		return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
	}

	/**
	 * Candidates for one pile, nearest first, each carrying its offset FROM THE PILE.
	 *
	 * A player on another plane is never a candidate whatever its x/y: planes are separate floors and
	 * a player directly above the pile cannot have taken it.
	 */
	public static List<Map<String, Object>> candidatesAt(List<Observed> players,
		int pileX, int pileY, int pilePlane)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		if (players == null)
		{
			return out;
		}
		List<Observed> inRange = new ArrayList<>();
		for (Observed p : players)
		{
			if (p == null || p.rsn == null || p.rsn.isEmpty() || p.plane != pilePlane)
			{
				continue;
			}
			int d = tileDistance(p.x, p.y, pileX, pileY);
			// The mechanically relevant area is not one fixed ring: a player further out who is casting
			// Telekinetic Grab can take the pile from range, so that player is a candidate too.
			if (d <= CANDIDATE_RANGE_TILES
				|| (d <= TELEGRAB_RANGE_TILES && p.animation == TELEGRAB_CAST_ANIMATION))
			{
				inRange.add(p);
			}
		}
		Collections.sort(inRange, (a, b) -> Integer.compare(
			tileDistance(a.x, a.y, pileX, pileY), tileDistance(b.x, b.y, pileX, pileY)));
		for (Observed p : inRange)
		{
			if (out.size() >= CANDIDATE_CAP)
			{
				break;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("rsn", p.rsn);
			// Offsets FROM THE PILE, not from us. A reader must never have to know where we stood.
			m.put("dx", p.x - pileX);
			m.put("dy", p.y - pileY);
			m.put("dist", tileDistance(p.x, p.y, pileX, pileY));
			m.put("cb", p.combatLevel);
			// What the player was DOING when the pile vanished. A floor pickup plays one animation and a
			// grab cast another, so a reader can rule out a bystander who was doing something else.
			m.put("anim", p.animation);
			out.add(m);
		}
		return out;
	}

	/**
	 * The resolve rule: exactly ONE candidate standing on the pile's own tile.
	 *
	 * @return the RSN when that holds, otherwise null. The caller pairs this with
	 *         {@link #statusFor} so the row always states which of the three answers it got.
	 */
	public static String resolveCounterparty(List<Map<String, Object>> candidates)
	{
		if (candidates == null || candidates.isEmpty())
		{
			return null;
		}
		String only = null;
		for (Map<String, Object> c : candidates)
		{
			Object d = c.get("dist");
			if (!(d instanceof Number) || ((Number) d).intValue() != 0)
			{
				continue;
			}
			if (only != null)
			{
				return null;		// two on the tile — ambiguous, and never a coin toss
			}
			Object rsn = c.get("rsn");
			if (!(rsn instanceof String) || ((String) rsn).isEmpty())
			{
				return null;
			}
			only = (String) rsn;
		}
		return only;
	}

	/**
	 * The status that goes on the row.
	 *
	 * AMBIGUOUS is reserved for the case where somebody WAS on the tile and we still cannot name
	 * them. An empty tile is UNKNOWN, not ambiguous: there is no competing explanation to choose
	 * between, only an absence of evidence, and conflating the two would make "we saw two people"
	 * indistinguishable from "we saw nobody".
	 */
	public static String statusFor(List<Map<String, Object>> candidates, String resolved)
	{
		if (resolved != null)
		{
			return STATUS_RESOLVED;
		}
		if (candidates != null)
		{
			for (Map<String, Object> c : candidates)
			{
				Object d = c.get("dist");
				if (d instanceof Number && ((Number) d).intValue() == 0)
				{
					return STATUS_AMBIGUOUS;
				}
			}
		}
		return STATUS_UNKNOWN;
	}
}
