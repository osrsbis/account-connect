package com.osrsbestinslot.export;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The account capture panel (ServerGrants.accountCaptureEnabled): which parts of this account the plugin has read
 * this login, and the next one to open. It draws only; what it reports is what the snapshot already carries.
 */
final class AccountCaptureOverlay extends OverlayPanel
{
	static final String[] ROWS = {"Bank", "Looting bag", "Collection log", "House storage"};
	static final String[] PROMPTS = {"Open your bank.", "Check your looting bag.", "Open your collection log.", "Enter your house."};
	static final String CAPTURED = "Captured", MISSING = "Missing", NOT_NEEDED = "Not needed", NO_HOUSE = "No house";

	private final AccountConnectPlugin plugin;
	private int statesTick = Integer.MIN_VALUE;
	private String[] states;

	AccountCaptureOverlay(AccountConnectPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		panelComponent.setPreferredSize(new Dimension(170, 0));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Client client = plugin.client;
		if (client == null || client.getGameState() != GameState.LOGGED_IN || !plugin.grants.accountCaptureEnabled())
		{
			return null;
		}
		if (states == null || statesTick != client.getTickCount())
		{
			states = states();	// once per tick: the collection check copies the varps
			statesTick = client.getTickCount();
		}
		panelComponent.getChildren().add(TitleComponent.builder().text("Account capture").build());
		for (int i = 0; i < ROWS.length; i++)
		{
			panelComponent.getChildren().add(LineComponent.builder().left(ROWS[i]).right(states[i])
				.rightColor(CAPTURED.equals(states[i]) ? Color.GREEN : MISSING.equals(states[i]) ? Color.ORANGE : Color.LIGHT_GRAY)
				.build());
		}
		String prompt = prompt(states);
		if (prompt != null) panelComponent.getChildren().add(LineComponent.builder().left(prompt).leftColor(Color.YELLOW).build());
		panelComponent.getChildren().add(LineComponent.builder().left(footer()).build());
		return super.render(graphics);
	}

	/** The prompt of the first missing row, or null when none is missing. */
	static String prompt(String[] states)
	{
		int i = Arrays.asList(states).indexOf(MISSING);
		return i < 0 ? null : PROMPTS[i];
	}

	/**
	 * Row states, in ROWS order, from what the snapshot carries this login (observation times and the last bank read
	 * clear on every hop and relog). A looting bag is not needed once the bank is read and neither it nor the
	 * inventory holds one; a house is absent when the game's house location is none.
	 */
	String[] states()
	{
		Client client = plugin.client;
		SnapshotBuilder snapshots = plugin.snapshots;
		ItemContainer liveBank = client.getItemContainer(InventoryID.BANK);
		Item[] bank = liveBank != null ? liveBank.getItems() : snapshots.lastBankItems;
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		boolean bagHeld = bank == null || holdsLootingBag(bank) || inv != null && holdsLootingBag(inv.getItems());
		AccountProgressCapture collection = plugin.activeCollection();
		return new String[]{
			bank != null ? CAPTURED : MISSING,
			readThisLogin(InventoryID.LOOTING_BAG) ? CAPTURED : bagHeld ? MISSING : NOT_NEEDED,
			collection != null && collection.verifiedCollection(client) != null ? CAPTURED : MISSING,
			readThisLogin(InventoryID.POH_COSTUMES) ? CAPTURED
				: plugin.loginSettled() && client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION) == 0 ? NO_HOUSE : MISSING};
	}

	/** The same test as SnapshotBuilder.addContainerSnapshot: present, and changed since this login began. */
	private boolean readThisLogin(int containerId)
	{
		return plugin.client.getItemContainer(containerId) != null && plugin.snapshots.observedAtSeconds(containerId) != null;
	}

	private static boolean holdsLootingBag(Item[] items)
	{
		return items != null && Arrays.stream(items).anyMatch(i -> i != null
			&& (i.getId() == ItemID.LOOTING_BAG || i.getId() == ItemID.LOOTING_BAG_OPEN));
	}

	/** The snapshot uploader's state: an answer pending, the last failed (it retries on backoff), or all sent. */
	String footer()
	{
		SnapshotUploader u = plugin.uploader;
		if (u.snapshotsInFlight.get() > 0) return "Uploading...";
		if (u.snapshotRetryPending) return "Upload failed. It will retry.";
		return u.lastUploadedHash != null && u.lastUploadedHash.equals(u.lastBuiltHash) ? "Upload complete." : "Uploading...";
	}
}
