package com.osrsbestinslot.export;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Who is standing next to you while a shop is open, nearest first.
 *
 * Rows are read only for this display while the staff store-tools gate is open. They are
 * neither retained nor passed to the public capture readers or upload paths.
 *
 * The reset countdown used to live here and was SPLIT OUT on 2026-09-03: this list changes length
 * as players walk past, and a growing panel dragged the countdown around with it. See
 * StoreResetOverlay.
 *
 * Shows ONLY while a shop interface is open. Movable, so placement is the user's choice.
 */
class StoreNearbyOverlay extends StoreOverlay
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
	 * A deliberately QUIET palette. The countdown is the thing the user acts on and it owns the loud
	 * colours (StoreResetOverlay); if this list shouted too, neither would read at a glance. Only
	 * the adjacent player — the plausible counterparty — gets an accent.
	 */
	private static final Color GOLD = new Color(255, 193, 71);
	private static final Color NAME = new Color(225, 225, 225);
	private static final Color NEAR = new Color(190, 190, 190);
	private static final Color DIM = new Color(140, 140, 140);

	StoreNearbyOverlay(AccountConnectPlugin plugin)
	{
		// Sits under the reset timer on the right: the same grid edge as the countdown (width 12-char
		// RSN + "12 tiles  126"), pushed down by the countdown's own height so the two stack without
		// touching.
		super(plugin, PANEL_WIDTH, 32);
	}

	@Override
	void addRows()
	{
		// No "Store" title any more: the countdown left for its own overlay, and "Nearby (N)"
		// already names this panel. One less line covering the shop.
		List<String[]> rows = visibleRows(plugin.store.nearbyPlayersForOverlay(DISPLAY_CAP));

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
