package com.osrsbestinslot.export;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Staff only: every player the client has loaded around you while you are in the Wilderness.
 *
 * Reads the plugin's surroundingPlayersNow() on every frame, so a player who arrives or leaves shows
 * up or drops off on the next frame. It captures nothing and sends nothing. "Loaded" is what the
 * server sent this client: it covers every minimap dot at any zoom, it is not a claim that nobody
 * else was nearby, and another player's floor cannot be told (see surroundingPlayersNow).
 *
 * NO SILENT OMISSION. The panel shows a window of COLUMNS x ROWS_PER_COLUMN lines. When more players
 * are loaded than fit, the title says which ones are shown out of how many, and the mouse wheel over
 * the panel scrolls the window, so every player can be reached.
 *
 * Independent of the General Store: no shop, no anchor to a shop widget. Movable.
 */
class WildernessPlayersOverlay extends OverlayPanel implements MouseWheelListener
{
	/** Lines per column; the panel starts the next column after this many. */
	static final int ROWS_PER_COLUMN = 20;

	/** Columns in the window. */
	static final int COLUMNS = 2;

	/** Player rows in one window: every line except the title. */
	static final int PAGE_ROWS = ROWS_PER_COLUMN * COLUMNS - 1;

	/**
	 * One column's width. Measured with RuneLite 1.13.1's own fonts: the widest real row (12 x "W" and
	 * "103 NW  lvl 126") is 246 px in RuneScape Bold, 193 px in RuneScape. Wider than that, a row would
	 * break onto a second line and every row below it would read against the wrong player.
	 */
	static final int COLUMN_WIDTH = 256;

	/** Gap between columns and between lines. */
	private static final int COLUMN_GAP = 10;
	private static final int LINE_GAP = 2;

	/** Panel border: left, top, right, bottom. */
	private static final int BORDER_X = 6;
	private static final int BORDER_Y = 4;

	private static final Color GOLD = new Color(255, 193, 71);
	private static final Color NAME = new Color(225, 225, 225);
	private static final Color NEAR = new Color(190, 190, 190);
	private static final Color DIM = new Color(140, 140, 140);
	private static final Color PLATE = new Color(26, 26, 26, 235);

	private final AccountConnectPlugin plugin;

	/** First player row of the window. Written by the wheel (AWT thread), read and clamped by render. */
	private volatile int offset;

	/** Rows the last render had, so the wheel can clamp without reading game state off the client thread. */
	private volatile int lastTotal;

	WildernessPlayersOverlay(AccountConnectPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		setMovable(true);
		setSnappable(true);
		panelComponent.setBackgroundColor(PLATE);
		panelComponent.setBorder(new Rectangle(BORDER_X, BORDER_Y, BORDER_X, BORDER_Y));
		panelComponent.setGap(new java.awt.Point(COLUMN_GAP, LINE_GAP));
		// WRAP into columns. With wrap on, PanelComponent keeps each line at its own preferred width and
		// starts a new column once the column height reaches the panel height render() sets.
		panelComponent.setWrap(true);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Two gates, both required: the server's staff store-tools grant, and our character in the
		// Wilderness. Either one off and nothing is drawn.
		if (!plugin.wildernessPanelEnabled())
		{
			lastTotal = 0;
			return null;
		}

		// Column height = exactly ROWS_PER_COLUMN lines: one font height plus the gap per line.
		int lineHeight = graphics.getFontMetrics().getHeight() + LINE_GAP;
		panelComponent.setPreferredSize(new Dimension(COLUMN_WIDTH + 2 * BORDER_X,
			ROWS_PER_COLUMN * lineHeight + 2 * BORDER_Y));

		List<String[]> rows = rows(plugin.surroundingPlayersNow());
		int total = rows.size();
		lastTotal = total;
		int first = clampOffset(offset, total);
		offset = first;
		int last = Math.min(total, first + PAGE_ROWS);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(title(total, first, last))
			.color(total == 0 ? DIM : NAME)
			.preferredSize(new Dimension(COLUMN_WIDTH, 0))
			.build());
		for (int i = first; i < last; i++)
		{
			String[] row = rows.get(i);
			int dist = Integer.parseInt(row[2]);
			panelComponent.getChildren().add(LineComponent.builder()
				.left(row[0])
				.right(row[1])
				.leftColor(dist <= 1 ? GOLD : NAME)
				.rightColor(distanceColor(dist))
				.preferredSize(new Dimension(COLUMN_WIDTH, 0))
				.build());
		}
		builtRows = panelComponent.getChildren().size();
		return super.render(graphics);
	}

	/**
	 * Wheel over the panel scrolls the window by one column. Consumed only inside the panel, so the
	 * wheel still zooms the game camera everywhere else. Runs on the AWT thread: it only moves the
	 * offset, and render clamps it.
	 */
	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		Rectangle b = getBounds();
		if (lastTotal <= PAGE_ROWS || b == null || b.isEmpty() || !b.contains(event.getPoint()))
		{
			return event;
		}
		offset = clampOffset(offset + Integer.signum(event.getWheelRotation()) * ROWS_PER_COLUMN, lastTotal);
		event.consume();
		return event;
	}

	/** Keep the window full: never before row 0, never past the last full window. */
	static int clampOffset(int offset, int total)
	{
		int max = Math.max(0, total - PAGE_ROWS);
		return Math.max(0, Math.min(offset, max));
	}

	/** "Wilderness players (N)", or "Wilderness players 21-59 of 140" when the window is not everyone. */
	static String title(int total, int first, int last)
	{
		if (total == 0)
		{
			return "No players loaded";
		}
		if (first == 0 && last == total)
		{
			return "Wilderness players (" + total + ")";
		}
		return "Wilderness players " + (first + 1) + "-" + last + " of " + total;
	}

	/** Gold beside you, then fading. Thresholds sized for a Wilderness screen, not a shop counter. */
	static Color distanceColor(int dist)
	{
		if (dist <= 1)
		{
			return GOLD;
		}
		return dist <= 10 ? NEAR : DIM;
	}

	/** Eight-way compass from our tile to theirs; north is +y. Empty when on our tile. */
	static String compass(int dx, int dy)
	{
		int ax = Math.abs(dx);
		int ay = Math.abs(dy);
		// A component counts when it is at least half the other, so 10 east 1 north reads E, not NE.
		String ns = ay * 2 >= ax && dy != 0 ? (dy > 0 ? "N" : "S") : "";
		String ew = ax * 2 >= ay && dx != 0 ? (dx > 0 ? "E" : "W") : "";
		return ns + ew;
	}

	/**
	 * Turn the plugin's player maps into display rows: {name, "12 NE  lvl 126", dist}. Every player is
	 * a row; skips only an entry missing the fields it needs, rather than drawing a broken row.
	 */
	static List<String[]> rows(List<Map<String, Object>> players)
	{
		List<String[]> out = new ArrayList<>();
		if (players == null)
		{
			return out;
		}
		for (Map<String, Object> p : players)
		{
			Object rsn = p.get("rsn");
			Object dist = p.get("dist");
			Object dx = p.get("dx");
			Object dy = p.get("dy");
			if (!(rsn instanceof String) || !(dist instanceof Integer) || !(dx instanceof Integer)
				|| !(dy instanceof Integer))
			{
				continue;
			}
			int d = (Integer) dist;
			String right = d == 0 ? "here" : d + " " + compass((Integer) dx, (Integer) dy);
			Object cb = p.get("cb");
			if (cb instanceof Integer)
			{
				right = right + "  lvl " + cb;
			}
			out.add(new String[]{StoreNearbyOverlay.shortenRsn((String) rsn), right, String.valueOf(d)});
		}
		return out;
	}

	/** How many lines the last render BUILT, before OverlayPanel cleared them. Test seam only. */
	private int builtRows;

	int builtRowCountForTest()
	{
		return builtRows;
	}

	int offsetForTest()
	{
		return offset;
	}
}
