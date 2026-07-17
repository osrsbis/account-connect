package com.osrsbestinslot.export;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("osrsbisexport")
public interface AccountConnectConfig extends Config
{
	@ConfigItem(
		keyName = "linkToken",
		name = "Link token",
		description =
			"Paste the token from osrsbestinslot.com (Connect account) to link this client. While linked, "
			+ "this syncs YOUR OWN account to osrsbestinslot.com — gear, stats, quests and bank — plus your "
			+ "account activity: GE and shop buys/sells, completed trades INCLUDING the other player's name "
			+ "and the items each side exchanged, items you loot from kills and from reward chests (raids, "
			+ "Barrows, clue caskets and similar), items you drop, pick up or alch, deaths, level-ups, and "
			+ "login/logout times — to power your calculators and account dashboard. If you are in a Group "
			+ "Ironman group, this also includes your shared group storage contents, which can include items "
			+ "other group members deposited. Clear the token to "
			+ "stop syncing.",
		position = 1
	)
	default String linkToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "Where to send the snapshot. Leave as default unless testing.",
		position = 2
	)
	default String apiBaseUrl()
	{
		return "https://www.osrsbestinslot.com/wp-json/osrsbis/v1";
	}

	@ConfigItem(
		keyName = "uploadTradeScreenshots",
		name = "Upload delivery screenshots",
		description =
			"When enabled, this captures screenshots as delivery proof and uploads them with your "
			+ "osrsbestinslot.com link token to osrsbestinslot.com's servers: your trade confirmation "
			+ "window when a trade completes (which shows the other player's name and the items traded), "
			+ "and a low-rate (1 per second) series while a shop window is open (discarded if the visit "
			+ "had no purchase or sale). Nothing is captured while this is off.",
		position = 3,
		warning =
			"This uploads screenshots — your trade window, and your game screen while a shop is open "
			+ "(which may include on-screen chat messages and other players' names) — to osrsbestinslot.com, "
			+ "a 3rd-party server not controlled or verified by the RuneLite developers. Only enable it if you agree to that."
	)
	default boolean uploadTradeScreenshots()
	{
		return false;	// OFF by default — explicit opt-in required
	}

	// Sync cadence is no longer a user setting: osrsbestinslot.com dictates it per link token in the
	// ingest response (X-Sync-Interval header), so it can be tuned centrally without a client change.
	// The client starts at a safe 120s default until the server's first response arrives.
}
