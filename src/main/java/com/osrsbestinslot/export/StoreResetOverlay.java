package com.osrsbestinslot.export;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * The store reset countdown, ON ITS OWN so it never moves.
 *
 * Split out of the combined panel on 2026-09-03 for one measured reason: the nearby list changes
 * length every few ticks as players walk past, and a panel anchored by its bottom edge grows and
 * shrinks upward — so the countdown jittered between positions and was hard to read at a glance.
 * A separate overlay drawing exactly ONE row can never change height, so it sits still.
 *
 * The number is the thing staff act on, so it gets its own fixed spot and the strongest colour.
 */
class StoreResetOverlay extends OverlayPanel
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

	/** The mockup's plate: near-opaque so text never fights the game scene behind it. */
	private static final Color PLATE = new Color(26, 26, 26, 235);

	/**
	 * ONE fixed width, always. A width that tracks its content is the other half of the jitter:
	 * "8s" and "sell junk" are very different lengths, so the panel edge would jump on every
	 * rollover. Sized for the longest string this overlay can ever draw.
	 */
	private static final int PANEL_WIDTH = 112;

	private final AccountConnectPlugin plugin;

	StoreResetOverlay(AccountConnectPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		// Option D (operator choice, 2026-09-03): both overlays on the RIGHT, clear of the item grid.
		// DYNAMIC: the plugin supplies a live location each frame (under the shop's item grid) —
		// see anchorUnderItems below. A fixed corner cannot follow a window that moves with the
		// client size, and "below the items" is what was asked for.
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
		setSnappable(true);
		panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));
		// STYLE, to match the signed-off mockup: a solid dark plate rather than RuneLite's default
		// translucent grey, which let the game scene bleed through and made the text hard to read
		// against a bright shop. Tighter border and row gap than the default so the plate reads as
		// one deliberate object instead of a loose stack.
		panelComponent.setBackgroundColor(PLATE);
		panelComponent.setBorder(new java.awt.Rectangle(6, 4, 6, 4));
		panelComponent.setGap(new java.awt.Point(0, 2));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		anchorUnderItems();
		if (!plugin.isShopOpen() || !plugin.storeToolsEnabled())
		{
			return null;
		}

		long remainMs = plugin.msUntilNextResetForDisplay();
		lastText = resetText(remainMs);
		lastColor = remainMs <= 0 ? DIM : countdownColor((int) ((remainMs + 999) / 1000));

		// EXACTLY ONE ROW, on every path. This is what keeps the overlay still.
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Reset")
			.leftColor(LABEL)
			.right(lastText)
			.rightColor(lastColor)
			.build());

		builtRows = panelComponent.getChildren().size();
		return super.render(graphics);
	}

	/**
	 * Park this overlay under the shop's item grid, unless the user has dragged it somewhere.
	 *
	 * setPreferredLocation is what RuneLite persists when a user moves an overlay in edit mode, so
	 * writing it every frame would pin the overlay and silently undo their drag. The default is
	 * therefore applied ONCE per shop visit; after that the position is theirs.
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
			// p.x is the grid's RIGHT edge, so subtract our own width to end flush with it. Both
			// overlays do this, so their right edges line up whatever each one's width is.
			setPreferredLocation(new java.awt.Point(p.x - PANEL_WIDTH, p.y));
		}
		anchored = true;
	}

	/** One anchor per visit; cleared when the shop closes so the next visit re-places it. */
	private boolean anchored;

	void resetAnchorForVisit()
	{
		anchored = false;
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
	private String lastText = "";
	private Color lastColor;
	private int builtRows;

	/** Rows the last render BUILT — OverlayPanel clears them afterwards, so this is the only view. */
	int builtRowCountForTest()
	{
		return builtRows;
	}

	String lastTextForTest()
	{
		return lastText;
	}

	Color lastColorForTest()
	{
		return lastColor;
	}
}
