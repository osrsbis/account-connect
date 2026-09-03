package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The nearby panel's row selection. Covers what a wrong overlay would actually get wrong:
 * showing a player across the map, drawing a row with no name, or growing without bound.
 */
public class StoreNearbyOverlayTest
{
	private static Map<String, Object> player(String rsn, int dist, Integer cb)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("rsn", rsn);
		m.put("dist", dist);
		if (cb != null)
		{
			m.put("cb", cb);
		}
		return m;
	}

	@Test
	public void formatsNameDistanceAndCombat()
	{
		List<Map<String, Object>> in = new ArrayList<>();
		in.add(player("Light Work G", 1, 114));
		List<String[]> rows = StoreNearbyOverlay.visibleRows(in);
		assertEquals(1, rows.size());
		assertEquals("Light Work G", rows.get(0)[0]);
		assertEquals("beside you  lvl 114", rows.get(0)[1]);
		assertEquals("1", rows.get(0)[2]);		// adjacent => highlighted gold
	}

	@Test
	public void pluralisesBeyondOneTile()
	{
		List<Map<String, Object>> in = new ArrayList<>();
		in.add(player("Zezima", 4, 126));
		assertEquals("4 tiles  lvl 126", StoreNearbyOverlay.visibleRows(in).get(0)[1]);
		assertEquals("4", StoreNearbyOverlay.visibleRows(in).get(0)[2]);
	}

	@Test
	public void dropsPlayersBeyondDisplayRange()
	{
		List<Map<String, Object>> in = new ArrayList<>();
		in.add(player("Close", StoreNearbyOverlay.DISPLAY_RANGE_TILES, 3));
		in.add(player("Far", StoreNearbyOverlay.DISPLAY_RANGE_TILES + 1, 3));
		List<String[]> rows = StoreNearbyOverlay.visibleRows(in);
		assertEquals(1, rows.size());
		assertEquals("Close", rows.get(0)[0]);
	}

	@Test
	public void skipsEntriesMissingNameOrDistance()
	{
		List<Map<String, Object>> in = new ArrayList<>();
		Map<String, Object> noName = new LinkedHashMap<>();
		noName.put("dist", 2);
		in.add(noName);
		Map<String, Object> noDist = new LinkedHashMap<>();
		noDist.put("rsn", "Ghost");
		in.add(noDist);
		in.add(player("Real", 2, 50));
		List<String[]> rows = StoreNearbyOverlay.visibleRows(in);
		assertEquals(1, rows.size());
		assertEquals("Real", rows.get(0)[0]);
	}

	@Test
	public void omitsCombatLevelWhenAbsent()
	{
		List<Map<String, Object>> in = new ArrayList<>();
		in.add(player("NoCb", 3, null));
		assertEquals("3 tiles", StoreNearbyOverlay.visibleRows(in).get(0)[1]);
	}

	@Test
	public void neverDrawsMoreRowsThanTheDisplayCap()
	{
		List<Map<String, Object>> in = new ArrayList<>();
		for (int i = 0; i < StoreNearbyOverlay.DISPLAY_CAP + 10; i++)
		{
			in.add(player("P" + i, 2, 3));
		}
		assertEquals(StoreNearbyOverlay.DISPLAY_CAP, StoreNearbyOverlay.visibleRows(in).size());
	}



	@Test
	public void theAdjacentPlayerIsColouredDifferentlyFromTheRest()
	{
		java.awt.Color adjacent = StoreNearbyOverlay.distanceColor(1);
		java.awt.Color near = StoreNearbyOverlay.distanceColor(3);
		java.awt.Color far = StoreNearbyOverlay.distanceColor(12);
		org.junit.Assert.assertNotEquals("the adjacent player must stand out", adjacent, near);
		org.junit.Assert.assertNotEquals("far players must be dimmer", near, far);
	}

	/**
	 * A LONG RSN MUST NOT WRAP. Seen on the rig: `96pure2382` wrapped and its "9 tiles lvl 22" landed
	 * on the next line, so every row below it read against the wrong player. A truncated name is
	 * recoverable; a misaligned list is actively misleading about who is standing next to you.
	 */
	@Test
	public void longNamesAreTruncatedRatherThanAllowedToWrap()
	{
		String longest = "AAAAAAAAAAAAA";		// 13 chars — longer than any real RSN
		assertTrue(longest.length() > StoreNearbyOverlay.RSN_MAX_CHARS);
		String out = StoreNearbyOverlay.shortenRsn(longest);
		assertEquals(StoreNearbyOverlay.RSN_MAX_CHARS, out.length());
		assertTrue("truncation must be visible", out.endsWith("\u2026"));
	}

	/** Every REAL RSN fits: OSRS caps display names at 12 characters. */
	@Test
	public void everyRealRsnLengthSurvivesUntouched()
	{
		assertTrue("the cap must cover a full-length OSRS name",
			StoreNearbyOverlay.RSN_MAX_CHARS >= 12);
		assertEquals("Light Work G", StoreNearbyOverlay.shortenRsn("Light Work G"));
		assertEquals("96pure2382", StoreNearbyOverlay.shortenRsn("96pure2382"));
	}

	@Test
	public void namesThatFitAreLeftExactlyAsTheyAre()
	{
		// An RSN is an identity. Trimming one that fits would name the wrong player.
		String exact = "AAAAAAAAAAAA";		// exactly RSN_MAX_CHARS, and a real 12-char RSN
		assertEquals(StoreNearbyOverlay.RSN_MAX_CHARS, exact.length());
		assertEquals(exact, StoreNearbyOverlay.shortenRsn(exact));
		assertEquals("Zezima", StoreNearbyOverlay.shortenRsn("Zezima"));
		assertEquals("", StoreNearbyOverlay.shortenRsn(null));
	}

	@Test
	public void theRowBuilderAppliesTheTruncation()
	{
		java.util.List<java.util.Map<String, Object>> in = new ArrayList<>();
		in.add(player("ThisNameIsWayTooLong", 3, 50));
		String shown = StoreNearbyOverlay.visibleRows(in).get(0)[0];
		assertTrue("rows must carry the shortened name", shown.length() <= StoreNearbyOverlay.RSN_MAX_CHARS);
	}

	@Test
	public void emptyAndNullInputProduceNoRows()
	{
		assertTrue(StoreNearbyOverlay.visibleRows(new ArrayList<>()).isEmpty());
		assertTrue(StoreNearbyOverlay.visibleRows(null).isEmpty());
	}

	@Test
	public void preservesTheOrderItIsGiven()
	{
		// nearbyPlayersSnapshot sorts nearest-first; the panel must not reshuffle that.
		List<Map<String, Object>> in = new ArrayList<>();
		in.add(player("A", 1, 3));
		in.add(player("B", 5, 3));
		in.add(player("C", 9, 3));
		List<String[]> rows = StoreNearbyOverlay.visibleRows(in);
		assertEquals("A", rows.get(0)[0]);
		assertEquals("B", rows.get(1)[0]);
		assertEquals("C", rows.get(2)[0]);
	}
}
