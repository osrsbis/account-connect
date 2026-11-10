package com.osrsbestinslot.export;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * What the two store overlays share: placement, plate, the two gates and the once-per-visit anchor
 * under the shop's item grid. Each overlay only says how wide it is, how far below the grid edge it
 * parks, and which rows it draws. See StoreResetOverlay and StoreNearbyOverlay.
 */
abstract class StoreOverlay extends OverlayPanel
{
	/**
	 * The mockup's plate: near-opaque so text never fights the game scene behind it. One plate colour
	 * across both overlays.
	 */
	private static final Color PLATE = new Color(26, 26, 26, 235);

	final AccountConnectPlugin plugin;
	private final int panelWidth;
	private final int below;

	StoreOverlay(AccountConnectPlugin plugin, int panelWidth, int below)
	{
		super(plugin);
		this.plugin = plugin;
		this.panelWidth = panelWidth;
		this.below = below;
		// Option D (operator choice, 2026-09-03): both overlays on the RIGHT, clear of the item grid;
		// the nearby list sits under the reset timer. Both stay draggable.
		// DYNAMIC: the plugin supplies a live location each frame (under the shop's item grid) —
		// see anchorUnderItems below. A fixed corner cannot follow a window that moves with the
		// client size, and "below the items" is what was asked for.
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		// Let the user drag it where they want in overlay edit mode. Placement is taste, not policy.
		setMovable(true);
		setSnappable(true);
		panelComponent.setPreferredSize(new Dimension(panelWidth, 0));
		// STYLE, to match the signed-off mockup: a solid dark plate rather than RuneLite's default
		// translucent grey, which let the game scene bleed through and made the text hard to read
		// against a bright shop. Tighter border and row gap than the default so the plate reads as
		// one deliberate object instead of a loose stack. Same plate on both overlays so the two
		// read as one design, not two accidents.
		panelComponent.setBackgroundColor(PLATE);
		panelComponent.setBorder(new Rectangle(6, 4, 6, 4));
		panelComponent.setGap(new Point(0, 2));	// default 0 makes rows collide
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		anchorUnderItems();
		// Two gates, both required: a shop is open, and the backend granted store tools to this
		// token. The second is what keeps these overlays off every ordinary player's screen.
		if (!plugin.store.isShopOpen() || !plugin.grants.storeToolsEnabled())
		{
			return null;
		}

		addRows();

		builtRows = panelComponent.getChildren().size();
		return super.render(graphics);
	}

	/** Add this overlay's rows to panelComponent. Called only while both gates are open. */
	abstract void addRows();

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
		Point p = plugin.store.shopItemsBottomLeft();
		if (p == null)
		{
			return;		// widget not laid out yet — try again next frame
		}
		if (getPreferredLocation() == null)
		{
			// p.x is the grid's RIGHT edge, so subtract our own width to end flush with it. Both
			// overlays do this, so their right edges line up whatever each one's width is. The
			// nearby list is also pushed down by the countdown's own height so the two stack
			// without touching.
			setPreferredLocation(new Point(p.x - panelWidth, p.y + below));
		}
		anchored = true;
	}

	/** One anchor per visit; cleared when the shop closes so the next visit re-places it. */
	private boolean anchored;

	void resetAnchorForVisit()
	{
		anchored = false;
	}

	/* Test seam: OverlayPanel clears its children after render, so the drawn rows are otherwise
	 * unobservable. */
	private int builtRows;

	/** Rows the last render BUILT — OverlayPanel clears them afterwards, so this is the only view. */
	int builtRowCountForTest()
	{
		return builtRows;
	}
}
