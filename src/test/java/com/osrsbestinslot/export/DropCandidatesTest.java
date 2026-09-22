package com.osrsbestinslot.export;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pile-centred candidates and the one rule that turns them into a name.
 *
 * Every arm here is about REFUSING to name somebody. The Take happens in the other player's client
 * and nothing in our stream proves who made it, so the only case worth naming is the one a human
 * would accept on sight: exactly one player standing on the pile's own tile.
 */
public class DropCandidatesTest
{
	private static final int PX = 3200;
	private static final int PY = 3400;

	private static DropCandidates.Observed at(String rsn, int x, int y)
	{
		return new DropCandidates.Observed(rsn, x, y, 0, 100);
	}

	private static DropCandidates.Observed atPlane(String rsn, int x, int y, int plane)
	{
		return new DropCandidates.Observed(rsn, x, y, plane, 100);
	}

	private static List<Map<String, Object>> candidates(DropCandidates.Observed... players)
	{
		return DropCandidates.candidatesAt(Arrays.asList(players), PX, PY, 0);
	}

	// ---- the resolve rule ----

	@Test
	public void exactlyOnePlayerOnTheTileResolves()
	{
		List<Map<String, Object>> c = candidates(at("Customer", PX, PY));
		assertEquals("Customer", DropCandidates.resolveCounterparty(c));
		assertEquals(DropCandidates.STATUS_RESOLVED,
			DropCandidates.statusFor(c, DropCandidates.resolveCounterparty(c)));
	}

	/** Two on the tile is AMBIGUOUS. Somebody took it and we cannot say which, so we name nobody. */
	@Test
	public void twoPlayersOnTheTileResolveToNobody()
	{
		List<Map<String, Object>> c = candidates(at("Customer", PX, PY), at("Bystander", PX, PY));
		assertNull("never a coin toss between two people", DropCandidates.resolveCounterparty(c));
		assertEquals(DropCandidates.STATUS_AMBIGUOUS, DropCandidates.statusFor(c, null));
	}

	/**
	 * A player ONE STEP AWAY is recorded as evidence but never resolves. This is the difference
	 * between an answer and a coincidence: standing next to a pile is not taking it.
	 */
	@Test
	public void aPlayerOneTileAwayIsEvidenceButNotAnAnswer()
	{
		List<Map<String, Object>> c = candidates(at("Nearby", PX + 1, PY));
		assertEquals("recorded as a candidate", 1, c.size());
		assertEquals(1, c.get(0).get("dist"));
		assertNull(DropCandidates.resolveCounterparty(c));
		assertEquals("an empty TILE is unknown, not ambiguous",
			DropCandidates.STATUS_UNKNOWN, DropCandidates.statusFor(c, null));
	}

	@Test
	public void anEmptyTileWithNobodyAroundIsUnknown()
	{
		List<Map<String, Object>> c = candidates();
		assertTrue(c.isEmpty());
		assertNull(DropCandidates.resolveCounterparty(c));
		assertEquals(DropCandidates.STATUS_UNKNOWN, DropCandidates.statusFor(c, null));
	}

	@Test
	public void nullInputsAreHandledAsUnknown()
	{
		assertTrue(DropCandidates.candidatesAt(null, PX, PY, 0).isEmpty());
		assertNull(DropCandidates.resolveCounterparty(null));
		assertEquals(DropCandidates.STATUS_UNKNOWN, DropCandidates.statusFor(null, null));
	}

	/**
	 * One on the tile plus others nearby still resolves. The rule is about the TILE, not about being
	 * alone in the area — a busy bank would otherwise never resolve anything.
	 */
	@Test
	public void oneOnTheTileResolvesEvenInACrowd()
	{
		List<Map<String, Object>> c = candidates(
			at("Customer", PX, PY),
			at("Passer1", PX + 1, PY + 1),
			at("Passer2", PX - 2, PY),
			at("Passer3", PX, PY + 3));
		assertEquals("Customer", DropCandidates.resolveCounterparty(c));
		assertEquals(4, c.size());
	}

	// ---- geometry ----

	@Test
	public void distanceIsChebyshevTheWayTheGameMeasuresIt()
	{
		// A diagonal step is distance 1 in OSRS, not 1.41. A Euclidean measure would make a diagonal
		// neighbour look further away than an orthogonal one when the game treats them the same.
		assertEquals(1, DropCandidates.tileDistance(0, 0, 1, 1));
		assertEquals(2, DropCandidates.tileDistance(0, 0, 2, 1));
		assertEquals(0, DropCandidates.tileDistance(7, 7, 7, 7));
	}

	@Test
	public void offsetsAreRelativeToThePileNotToUs()
	{
		List<Map<String, Object>> c = candidates(at("Customer", PX + 2, PY - 1));
		assertEquals(2, c.get(0).get("dx"));
		assertEquals(-1, c.get(0).get("dy"));
	}

	@Test
	public void candidatesAreSortedNearestFirst()
	{
		List<Map<String, Object>> c = candidates(
			at("Far", PX + 3, PY), at("OnTile", PX, PY), at("Near", PX + 1, PY));
		assertEquals("OnTile", c.get(0).get("rsn"));
		assertEquals("Near", c.get(1).get("rsn"));
		assertEquals("Far", c.get(2).get("rsn"));
	}

	@Test
	public void playersBeyondTheCandidateRangeAreNotRecorded()
	{
		List<Map<String, Object>> c = candidates(at("TooFar", PX + 4, PY));
		assertTrue("4 tiles is past the 3-tile candidate range", c.isEmpty());
	}

	/** A player on another floor cannot have taken the pile, whatever its x and y. */
	@Test
	public void anotherPlaneIsNeverACandidate()
	{
		List<Map<String, Object>> c = DropCandidates.candidatesAt(
			Arrays.asList(atPlane("Upstairs", PX, PY, 1)), PX, PY, 0);
		assertTrue("directly above the pile is still not on it", c.isEmpty());
		assertEquals(DropCandidates.STATUS_UNKNOWN, DropCandidates.statusFor(c, null));
	}

	@Test
	public void unnamedPlayersAreSkipped()
	{
		List<Map<String, Object>> c = candidates(at(null, PX, PY), at("", PX, PY));
		assertTrue(c.isEmpty());
	}

	@Test
	public void theCandidateListIsCapped()
	{
		DropCandidates.Observed[] crowd = new DropCandidates.Observed[40];
		for (int i = 0; i < crowd.length; i++)
		{
			crowd[i] = at("P" + i, PX, PY);
		}
		List<Map<String, Object>> c = candidates(crowd);
		assertEquals("a crowded tile cannot make the row unbounded",
			DropCandidates.CANDIDATE_CAP, c.size());
		assertNull("and forty people on a tile is still ambiguous", DropCandidates.resolveCounterparty(c));
	}

	@Test
	public void aMalformedCandidateRowNeverResolves()
	{
		// Defensive: a row whose rsn is not a string, or whose dist is missing, must not name anyone.
		java.util.Map<String, Object> bad = new java.util.LinkedHashMap<>();
		bad.put("rsn", 12345);
		bad.put("dist", 0);
		assertNull(DropCandidates.resolveCounterparty(Arrays.asList(bad)));

		java.util.Map<String, Object> noDist = new java.util.LinkedHashMap<>();
		noDist.put("rsn", "Someone");
		assertNull(DropCandidates.resolveCounterparty(Arrays.asList(noDist)));
	}
}
