package com.osrsbestinslot.export;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The staff Wilderness panel through the REAL RuneLite OverlayPanel render path: both gates, every
 * loaded player reachable, next-frame updates, and rows that never break onto a second line.
 */
public class WildernessPlayersOverlayTest
{
	private static Graphics2D graphics()
	{
		Graphics2D g = new BufferedImage(900, 900, BufferedImage.TYPE_INT_ARGB).createGraphics();
		g.setFont(FontManager.getRunescapeFont());
		return g;
	}

	private static SurroundingPlayersTest.Rig rig(boolean staff, boolean wild, int crowd) throws Exception
	{
		SurroundingPlayersTest.Rig r = new SurroundingPlayersTest.Rig(staff, wild);
		for (int i = 0; i < crowd; i++)
		{
			r.players.add(SurroundingPlayersTest.player(String.format("P%03d", i), 3201 + (i % 50), 3700 + i / 50, 3));
		}
		return r;
	}

	private static MouseWheelEvent wheel(int x, int y, int rotation)
	{
		return new MouseWheelEvent(new javax.swing.JPanel(), MouseWheelEvent.MOUSE_WHEEL, 0L, 0, x, y, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation);
	}

	// ---- gates ----

	@Test
	public void staffInTheWildernessSeesThePanel() throws Exception
	{
		WildernessPlayersOverlay o = new WildernessPlayersOverlay(rig(true, true, 3).plugin);
		assertNotNull(o.render(graphics()));
		assertEquals("title + 3 players", 4, o.builtRowCountForTest());
	}

	@Test
	public void staffOutsideTheWildernessSeesNothing() throws Exception
	{
		assertNull(new WildernessPlayersOverlay(rig(true, false, 3).plugin).render(graphics()));
	}

	@Test
	public void aPublicUserInTheWildernessSeesNothing() throws Exception
	{
		assertNull(new WildernessPlayersOverlay(rig(false, true, 3).plugin).render(graphics()));
	}

	@Test
	public void noShopIsNeeded() throws Exception
	{
		SurroundingPlayersTest.Rig r = rig(true, true, 1);
		r.plugin.setShopOpenForTest(false);
		assertNotNull("the Wilderness panel has no store dependency", new WildernessPlayersOverlay(r.plugin).render(graphics()));
	}

	@Test
	public void crossingTheDitchShowsAndHidesItOnTheNextFrame() throws Exception
	{
		SurroundingPlayersTest.Rig r = rig(true, false, 2);
		WildernessPlayersOverlay o = new WildernessPlayersOverlay(r.plugin);
		assertNull(o.render(graphics()));
		r.wild = 1;
		assertNotNull(o.render(graphics()));
		r.wild = 0;
		assertNull(o.render(graphics()));
	}

	// ---- live population ----

	@Test
	public void aSpawnOrDespawnShowsOnTheNextFrame() throws Exception
	{
		SurroundingPlayersTest.Rig r = rig(true, true, 1);
		WildernessPlayersOverlay o = new WildernessPlayersOverlay(r.plugin);
		o.render(graphics());
		assertEquals(2, o.builtRowCountForTest());
		r.players.add(SurroundingPlayersTest.player("Arrived", 3290, 3700, 90));
		o.render(graphics());
		assertEquals("spawn shows next frame", 3, o.builtRowCountForTest());
		r.players.subList(1, r.players.size()).clear();
		o.render(graphics());
		assertEquals("despawn shows next frame: title only", 1, o.builtRowCountForTest());
	}

	/** 140 players: one window at a time, the wheel reaches every one, and nothing is skipped. */
	@Test
	public void everyOneOf140PlayersIsReachableByScrolling() throws Exception
	{
		SurroundingPlayersTest.Rig r = rig(true, true, 140);
		WildernessPlayersOverlay o = new WildernessPlayersOverlay(r.plugin);
		o.render(graphics());
		assertEquals("a full window", WildernessPlayersOverlay.PAGE_ROWS + 1, o.builtRowCountForTest());
		o.getBounds().setBounds(0, 0, 600, 500);	// OverlayRenderer sets this each frame
		java.util.Set<Integer> seen = new java.util.TreeSet<>();
		for (int step = 0; step < 20; step++)
		{
			int first = o.offsetForTest();
			int last = Math.min(140, first + WildernessPlayersOverlay.PAGE_ROWS);
			for (int i = first; i < last; i++)
			{
				seen.add(i);
			}
			MouseWheelEvent e = wheel(100, 100, 1);
			o.mouseWheelMoved(e);
			assertTrue("the wheel over the panel is consumed", e.isConsumed());
			o.render(graphics());
		}
		assertEquals("every player row reached", 140, seen.size());
		assertEquals("the last window is full, never an empty page",
			140 - WildernessPlayersOverlay.PAGE_ROWS, o.offsetForTest());
		for (int step = 0; step < 20; step++)
		{
			o.mouseWheelMoved(wheel(100, 100, -1));
		}
		assertEquals(0, o.offsetForTest());
	}

	@Test
	public void theWheelOutsideThePanelOrWithEveryoneShownIsLeftForTheGame() throws Exception
	{
		SurroundingPlayersTest.Rig r = rig(true, true, 140);
		WildernessPlayersOverlay o = new WildernessPlayersOverlay(r.plugin);
		o.render(graphics());
		o.getBounds().setBounds(0, 0, 600, 500);
		MouseWheelEvent outside = wheel(700, 700, 1);
		assertSame(outside, o.mouseWheelMoved(outside));
		assertFalse("the camera zoom keeps the wheel outside the panel", outside.isConsumed());
		assertEquals(0, o.offsetForTest());

		SurroundingPlayersTest.Rig few = rig(true, true, 5);
		WildernessPlayersOverlay small = new WildernessPlayersOverlay(few.plugin);
		small.render(graphics());
		small.getBounds().setBounds(0, 0, 600, 500);
		MouseWheelEvent inside = wheel(100, 100, 1);
		small.mouseWheelMoved(inside);
		assertFalse("nothing to scroll: the wheel stays the game's", inside.isConsumed());
	}

	@Test
	public void aShrinkingCrowdClampsTheWindow() throws Exception
	{
		SurroundingPlayersTest.Rig r = rig(true, true, 140);
		WildernessPlayersOverlay o = new WildernessPlayersOverlay(r.plugin);
		o.render(graphics());
		o.getBounds().setBounds(0, 0, 600, 500);
		for (int i = 0; i < 10; i++)
		{
			o.mouseWheelMoved(wheel(100, 100, 1));
		}
		r.players.subList(11, r.players.size()).clear();	// 10 players left
		o.render(graphics());
		assertEquals(0, o.offsetForTest());
		assertEquals("title + all 10", 11, o.builtRowCountForTest());
	}

	@Test
	public void theTitleSaysWhichPlayersAreShownOutOfHowMany()
	{
		assertEquals("No players loaded", WildernessPlayersOverlay.title(0, 0, 0));
		assertEquals("Wilderness players (12)", WildernessPlayersOverlay.title(12, 0, 12));
		assertEquals("Wilderness players 21-59 of 140", WildernessPlayersOverlay.title(140, 20, 59));
	}

	@Test
	public void clampKeepsTheWindowFull()
	{
		int page = WildernessPlayersOverlay.PAGE_ROWS;
		assertEquals(0, WildernessPlayersOverlay.clampOffset(-5, 140));
		assertEquals(140 - page, WildernessPlayersOverlay.clampOffset(999, 140));
		assertEquals(0, WildernessPlayersOverlay.clampOffset(20, page));
		assertEquals(20, WildernessPlayersOverlay.clampOffset(20, 140));
	}

	// ---- rows ----

	@Test
	public void rowsCarryNameDistanceDirectionAndCombat()
	{
		List<Map<String, Object>> in = new ArrayList<>();
		in.add(row("Light Work G", 0, 0, 114));
		in.add(row("NorthEast", 12, 10, 126));
		in.add(row("FarWest", -103, 4, 3));
		List<String[]> rows = WildernessPlayersOverlay.rows(in);
		assertEquals("here  lvl 114", rows.get(0)[1]);
		assertEquals("12 NE  lvl 126", rows.get(1)[1]);
		assertEquals("103 W  lvl 3", rows.get(2)[1]);
		assertEquals("Light Work G", rows.get(0)[0]);
	}

	@Test
	public void compassPointsTheRightWay()
	{
		assertEquals("N", WildernessPlayersOverlay.compass(0, 5));
		assertEquals("S", WildernessPlayersOverlay.compass(0, -5));
		assertEquals("E", WildernessPlayersOverlay.compass(10, 1));
		assertEquals("SW", WildernessPlayersOverlay.compass(-7, -6));
		assertEquals("", WildernessPlayersOverlay.compass(0, 0));
	}

	/**
	 * THE WIDEST REAL ROW STAYS ONE LINE in RuneLite's own bold font. A wrapped row pushes its right
	 * column onto the next line and every row below it reads against the wrong player.
	 */
	@Test
	public void theWidestRowNeverWrapsInTheRealFont() throws Exception
	{
		Dimension widest = renderOne("WWWWWWWWWWWW", 3200 - 103, 3700 + 103, 126);
		Dimension narrow = renderOne("Ab", 3201, 3700, 3);
		assertEquals("a 12-char wide name at 103 tiles must be as tall as a short row", narrow.height, widest.height);
	}

	private static Dimension renderOne(String name, int x, int y, int cb) throws Exception
	{
		SurroundingPlayersTest.Rig r = rig(true, true, 0);
		r.players.add(SurroundingPlayersTest.player(name, x, y, cb));
		WildernessPlayersOverlay o = new WildernessPlayersOverlay(r.plugin);
		Graphics2D g = graphics();
		g.setFont(FontManager.getRunescapeBoldFont());
		o.render(g);
		return o.render(g);	// PanelComponent reports the previous frame's layout
	}

	private static Map<String, Object> row(String rsn, int dx, int dy, int cb)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("rsn", rsn);
		m.put("dx", dx);
		m.put("dy", dy);
		m.put("dist", Math.max(Math.abs(dx), Math.abs(dy)));
		m.put("cb", cb);
		return m;
	}

	/** A full window lays out as COLUMNS columns of ROWS_PER_COLUMN lines, not one tall list off the screen. */
	@Test
	public void aFullWindowLaysOutInColumns() throws Exception
	{
		SurroundingPlayersTest.Rig r = rig(true, true, 140);
		WildernessPlayersOverlay o = new WildernessPlayersOverlay(r.plugin);
		Graphics2D g = graphics();
		o.render(g);
		Dimension d = o.render(g);	// PanelComponent reports the previous frame's layout
		int line = g.getFontMetrics().getHeight() + 2;
		assertTrue("width " + d.width + " must hold " + WildernessPlayersOverlay.COLUMNS + " columns",
			d.width >= WildernessPlayersOverlay.COLUMNS * WildernessPlayersOverlay.COLUMN_WIDTH);
		assertTrue("height " + d.height + " must not exceed one column of lines",
			d.height <= WildernessPlayersOverlay.ROWS_PER_COLUMN * line + 8);
	}
}
