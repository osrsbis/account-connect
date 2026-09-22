package com.osrsbestinslot.export;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("osrsbisexport")
public interface AccountConnectConfig extends Config
{
	/**
	 * THE UPLOAD SWITCH. Every network send in this plugin checks it and returns early.
	 *
	 * It is a BOOLEAN on purpose. RuneLite shows a config item's warning dialog from
	 * ConfigPanel.changeConfiguration, and for a text field that method is called from a FocusAdapter
	 * whose focusLost fires whether or not the value changed. So a warning on the token text field
	 * asked the user to confirm the whole upload disclosure every time they clicked out of the box,
	 * even when they only clicked in to read it. A checkbox has no focus listener: its warning is
	 * shown once, when the user actually ticks it.
	 *
	 * The disclosure itself did not move out of the plugin. It lives here, on the switch that turns
	 * uploading on, and in full on the link token's description below.
	 */
	@ConfigItem(
		keyName = "enableUpload",
		name = "Upload to osrsbestinslot.com",
		description =
			"Turn this on to upload your account and your in-game activity to osrsbestinslot.com. "
			+ "Nothing is sent while it is off, whatever the link token says. See the link token "
			+ "below for the full list of what is uploaded.",
		warning =
			"This feature submits your IP address to a 3rd-party server not controlled or verified by "
			+ "Runelite developers. It also uploads your account and your in-game activity to "
			+ "osrsbestinslot.com: your skills, quests, achievement diaries, collection log, equipment, "
			+ "inventory, bank and Group Ironman shared group storage, and your trades, Grand Exchange "
			+ "and shop transactions, loot, drops, deaths, level-ups and login times. Completed trades "
			+ "include the other player's name and the items each side exchanged. It also uploads "
			+ "screenshots of your trade window and of your game screen while a shop is open, which may "
			+ "include on-screen chat messages and other players' names. For staff accounts where "
			+ "osrsbestinslot.com enables drop-trade proof, it may also capture and upload a recording "
			+ "of your rendered game screen throughout a drop trade, starting when you choose Drop and "
			+ "continuing until five seconds after the final dropped pile disappears. The recording may "
			+ "include visible chat messages and other players' names. Turning this off stops all of "
			+ "it. Only turn it on if you agree to that.",
		position = 1
	)
	default boolean enableUpload()
	{
		return false;
	}

	/**
	 * NO {@code warning=} ON THIS ITEM, DELIBERATELY. See enableUpload above: a warning on a text
	 * field is re-shown on every focus loss, with no edit made. The full disclosure is carried by the
	 * description here and by the warning on enableUpload, which is the item that actually starts the
	 * uploading.
	 */
	@ConfigItem(
		keyName = "linkToken",
		name = "Link token",
		description =
			"Paste the token from osrsbestinslot.com (Connect account) to link this client. With the "
			+ "upload switch above turned on, this uploads YOUR OWN account to osrsbestinslot.com, a "
			+ "3rd-party server not controlled or verified by the RuneLite developers: your display "
			+ "name, account hash, account type, current world and location, skills, total and combat "
			+ "level, quests, achievement diaries, combat achievements, slayer task, collection log, "
			+ "equipment, inventory, bank, rune pouch, seed vault, Grand Exchange offers, wealth, "
			+ "spellbook, attack style, active prayers, Kourend favour and minigame points. In a Group "
			+ "Ironman group it also uploads your shared group storage, which can include items other "
			+ "members deposited. It also uploads your account activity: Grand Exchange and "
			+ "general-store buys and sells, completed trades INCLUDING the other player's name and the "
			+ "items each side exchanged, items you loot from kills and from reward chests (raids, "
			+ "Barrows, clue caskets and similar), items you drop, pick up or alch, deaths, level-ups, "
			+ "and login and logout times. It also uploads screenshots as delivery proof: your trade "
			+ "confirmation window when a trade completes, which shows the other player's name and the "
			+ "items traded, and a short series of your game screen while a shop window is open, which "
			+ "may include on-screen chat and other players' names (discarded if the visit had no "
			+ "purchase or sale). For staff accounts where osrsbestinslot.com enables drop-trade proof, "
			+ "it may also capture and upload a recording of your rendered game screen throughout a drop "
			+ "trade, starting when you choose Drop and continuing until five seconds after the final "
			+ "dropped pile disappears. That recording can run for several minutes and may include "
			+ "visible chat messages and other players' names. Only osrsbestinslot.com can enable it, "
			+ "per account. Your IP address reaches the server with every upload. Turn the upload "
			+ "switch off, or clear the token, to stop all of it.",
		position = 2
	)
	default String linkToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "Where to send the snapshot. Leave as default unless testing.",
		position = 3
	)
	default String apiBaseUrl()
	{
		return "https://www.osrsbestinslot.com/wp-json/osrsbis/v1";
	}

	// Delivery-proof screenshots are no longer a separate tick box (2026-09-02). They are part of core
	// sync, active whenever the upload switch is on and a link token is set — the SAME gate as the
	// account upload and the activity log — so what they capture is disclosed in the linkToken
	// description and in the enableUpload warning above rather than on a toggle of their own.
	// osrsbestinslot.com can still force it off per token with the X-Screenshots / X-Clips headers.

	// Sync cadence is no longer a user setting: osrsbestinslot.com dictates it per link token in the
	// ingest response (X-Sync-Interval header), so it can be tuned centrally without a client change.
	// The client starts at a safe 120s default until the server's first response arrives.
}
