package com.osrsbestinslot.export;

import java.awt.Color;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * The store reset countdown, ON ITS OWN so it never moves.
 *
 * Split out of the combined panel on 2026-09-03 for one measured reason: the nearby list changes
 * length every few ticks as players walk past, and a panel anchored by its bottom edge grows and
 * shrinks upward — so the countdown jittered between positions and was hard to read at a glance.
 * A separate overlay drawing exactly ONE row can never change height, so it sits still.
 *
 * The number is the thing the user acts on, so it gets its own fixed spot and the strongest colour.
 */
class StoreResetOverlay extends StoreOverlay
{
	/** Under this many seconds, do not start a hand-off — the window is closing. */
	static final int URGENT_SECONDS = 12;

	/** Above this many seconds there is comfortable room to work. */
	static final int SAFE_SECONDS = 25;

	static final Color GREEN = new Color(106, 214, 106);
	static final Color AMBER = new Color(245, 184, 56);
	static final Color RED = new Color(232, 88, 88);
	static final Color DIM = new Color(140, 140, 140);
	private static final Color LABEL = new Color(215, 215, 215);

	/**
	 * ONE fixed width, always. A width that tracks its content is the other half of the jitter:
	 * "8s" and "sell junk" are very different lengths, so the panel edge would jump on every
	 * rollover. Sized for the longest string this overlay can ever draw.
	 */
	private static final int PANEL_WIDTH = 112;

	StoreResetOverlay(AccountConnectPlugin plugin)
	{
		// Parked directly under the shop's item grid.
		super(plugin, PANEL_WIDTH, 0);
	}

	@Override
	void addRows()
	{
		long remainMs = plugin.store.msUntilNextResetForDisplay();
		lastText = resetText(remainMs);
		lastColor = remainMs <= 0 ? DIM : countdownColor((int) ((remainMs + 999) / 1000));

		// EXACTLY ONE ROW, on every path. This is what keeps the overlay still.
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Reset")
			.leftColor(LABEL)
			.right(lastText)
			.rightColor(lastColor)
			.build());
	}

	/** Green with room to work, amber as it tightens, red when a hand-off should not be started. */
	static Color countdownColor(int secs)
	{
		if (secs <= URGENT_SECONDS)
		{
			return RED;
		}
		return secs >= SAFE_SECONDS ? GREEN : AMBER;
	}

	/**
	 * The countdown, or what it is waiting for.
	 *
	 * Never blank: a silent overlay is indistinguishable from a broken one, and that is exactly how
	 * the first live test read ("store reset not appearing anywhere"). Whole seconds, rounded UP so
	 * it never shows 0s — the last second reads 1s and then rolls to 60s.
	 */
	static String resetText(long remainMs)
	{
		if (remainMs <= 0)
		{
			return "sell junk";
		}
		return ((int) ((remainMs + 999) / 1000)) + "s";
	}

	/* Test seams: OverlayPanel clears its children after render, so the drawn values are
	 * otherwise unobservable. */
	String lastText = "";
	Color lastColor;
}
