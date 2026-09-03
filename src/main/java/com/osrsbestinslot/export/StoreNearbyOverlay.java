package com.osrsbestinslot.export;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Who is standing next to you while a shop is open, nearest first.
 *
 * The plugin already computes this list every tick to attach to a store event. This renders the
 * same list and captures nothing new — no extra data is read and none leaves the client.
 *
 * The reset countdown used to live here and was SPLIT OUT on 2026-09-03: this list changes length
 * as players walk past, and a growing panel dragged the countdown around with it. See
 * StoreResetOverlay.
 *
 * Shows ONLY while a shop interface is open. Movable, so placement is the user's choice.
 */
class StoreNearbyOverlay extends OverlayPanel
{
	/** Rows to draw. The event payload caps at 24; a panel that tall would cover the shop. */
	static final int DISPLAY_CAP = 6;

	/**
	 * An OSRS display name is at most 12 characters, so EVERY real RSN must fit — truncating one
	 * would name the wrong player. The panel is sized for 12 plus the right column instead.
	 * The guard stays for anything longer than a real name could be.
	 */
	static final int RSN_MAX_CHARS = 12;

	/** Fixed width: 12-char RSN plus the right column. Also what the anchor subtracts to right-align. */
	static final int PANEL_WIDTH = 196;

	/** Beyond this many tiles a player cannot be the counterparty of a store hand-off. */
	static final int DISPLAY_RANGE_TILES = 15;

	/**
	 * A deliberately QUIET palette. The countdown is the thing staff act on and it owns the loud
	 * colours (StoreResetOverlay); if this list shouted too, neither would read at a glance. Only
	 * the adjacent player — the plausible counterparty — gets an accent.
	 */
	private static final Color GOLD = new Color(255, 193, 71);
	private static final Color NAME = new Color(225, 225, 225);
	private static final Color NEAR = new Color(190, 190, 190);
	private static final Color DIM = new Color(140, 140, 140);

	/** Matches StoreResetOverlay: one plate colour across both overlays. */
	private static final Color PLATE = new Color(26, 26, 26, 235);

	private final AccountConnectPlugin plugin;

	StoreNearbyOverlay(AccountConnectPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		// Option D: sits under the reset timer on the right. Both stay draggable.
		// DYNAMIC: the plugin supplies a live location each frame (under the shop's item grid) —
		// see anchorUnderItems below. A fixed corner cannot follow a window that moves with the
		// client size, and "below the items" is what was asked for.
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		// Let the user drag it where they want in overlay edit mode. Placement is taste, not policy.
		setMovable(true);
		setSnappable(true);
		panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));	// 12-char RSN + "12 tiles  126"
		// Same plate as the countdown so the two read as one design, not two accidents.
		panelComponent.setBackgroundColor(PLATE);
		panelComponent.setBorder(new java.awt.Rectangle(6, 4, 6, 4));
		panelComponent.setGap(new java.awt.Point(0, 2));	// default 0 makes rows collide
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		anchorUnderItems();
		// Two gates, both required: a shop is open, and the backend granted store tools to this
		// token. The second is what keeps these overlays off every ordinary player's screen.
		if (!plugin.isShopOpen() || !plugin.storeToolsEnabled())
		{
			return null;
		}

		// No "Store" title any more: the countdown left for its own overlay, and "Nearby (N)"
		// already names this panel. One less line covering the shop.
		List<String[]> rows = visibleRows(plugin.nearbyPlayersSnapshot(DISPLAY_CAP));

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(rows.isEmpty() ? "Nobody nearby" : "Nearby (" + rows.size() + ")")
			.color(rows.isEmpty() ? DIM : NAME)
			.build());

		for (String[] row : rows)
		{
			int dist = Integer.parseInt(row[2]);
			panelComponent.getChildren().add(LineComponent.builder()
				.left(row[0])
				.right(row[1])
				.leftColor(dist <= 1 ? GOLD : NAME)
				.rightColor(distanceColor(dist))
				.build());
		}

		builtRows = panelComponent.getChildren().size();
		return super.render(graphics);
	}

	/**
	 * Park this list under the reset timer, which itself sits under the shop's item grid, unless
	 * the user has dragged it. setPreferredLocation is what RuneLite persists on a drag, so writing
	 * it every frame would pin the overlay and silently undo their placement — hence once per visit.
	 */
	private void anchorUnderItems()
	{
		if (anchored)
		{
			return;
		}
		java.awt.Point p = plugin.shopItemsBottomLeft();
		if (p == null)
		{
			return;		// widget not laid out yet — try again next frame
		}
		if (getPreferredLocation() == null)
		{
			// Right-aligned to the same grid edge as the countdown (p.x is that edge), and pushed
			// down by the countdown's own height so the two stack without touching.
			setPreferredLocation(new java.awt.Point(p.x - PANEL_WIDTH, p.y + 32));
		}
		anchored = true;
	}

	/** One anchor per visit; cleared when the shop closes so the next visit re-places it. */
	private boolean anchored;

	void resetAnchorForVisit()
	{
		anchored = false;
	}

	/** Gold for the tile next to you — the plausible counterparty — fading out with distance. */
	static Color distanceColor(int dist)
	{
		if (dist <= 1)
		{
			return GOLD;
		}
		return dist <= 5 ? NEAR : DIM;
	}

	/** How many lines the last render BUILT, before OverlayPanel cleared them. Test seam only. */
	private int builtRows;

	int builtRowCountForTest()
	{
		return builtRows;
	}

	/**
	 * Trim an RSN that would wrap. A wrapped name pushes its own right column onto the next line and
	 * every row below it reads against the wrong player — worse than a truncated name.
	 */
	static String shortenRsn(String rsn)
	{
		if (rsn == null)
		{
			return "";
		}
		if (rsn.length() <= RSN_MAX_CHARS)
		{
			return rsn;
		}
		return rsn.substring(0, RSN_MAX_CHARS - 1) + "\u2026";
	}

	/**
	 * Turn the plugin's nearby[] maps into display rows: {left, right, distance}.
	 *
	 * Pure and static so the filtering and formatting are testable without a game client or a
	 * Graphics2D. Skips any entry missing the two fields it needs rather than drawing a broken row.
	 */
	static List<String[]> visibleRows(List<Map<String, Object>> nearby)
	{
		List<String[]> out = new ArrayList<>();
		if (nearby == null)
		{
			return out;
		}
		for (Map<String, Object> p : nearby)
		{
			Object rsnValue = p.get("rsn");
			Object distValue = p.get("dist");
			if (!(rsnValue instanceof String) || !(distValue instanceof Integer))
			{
				continue;
			}
			int dist = (Integer) distValue;
			if (dist > DISPLAY_RANGE_TILES)
			{
				continue;
			}
			String right = dist <= 1 ? "beside you" : dist + " tiles";
			Object cb = p.get("cb");
			if (cb instanceof Integer)
			{
				right = right + "  lvl " + cb;
			}
			out.add(new String[]{shortenRsn((String) rsnValue), right, String.valueOf(dist)});
			if (out.size() >= DISPLAY_CAP)
			{
				break;
			}
		}
		return out;
	}
}
