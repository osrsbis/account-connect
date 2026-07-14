package com.osrsbestinslot.export;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

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
			+ "and the items each side exchanged, items you loot from kills, deaths, level-ups, and "
			+ "login/logout times — to power your calculators and account dashboard. Clear the token to "
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
		name = "Upload trade screenshots",
		description =
			"When enabled, this captures a screenshot of your trade confirmation window — which shows "
			+ "the other player's name and the items traded — when a trade completes, and uploads it with "
			+ "your osrsbestinslot.com link token to osrsbestinslot.com's servers as delivery proof. "
			+ "Nothing else is sent, and nothing is captured while this is off.",
		position = 3,
		warning =
			"This uploads a screenshot of your trade window (which includes the other player's name and "
			+ "the traded items) to osrsbestinslot.com. Only enable it if you agree to that."
	)
	default boolean uploadTradeScreenshots()
	{
		return false;	// OFF by default — explicit opt-in required
	}

	@ConfigItem(
		keyName = "excludedAccounts",
		name = "Don't sync these accounts",
		description =
			"Comma-separated account display names that should NEVER sync, even while logged in with a "
			+ "token set (e.g. a personal alt you don't want tracked). Matched on the logged-in "
			+ "character name, case-insensitive. Leave blank to sync every account you log in.",
		position = 4
	)
	default String excludedAccounts()
	{
		return "";
	}

	@ConfigItem(
		keyName = "resyncHotkey",
		name = "Re-sync now hotkey",
		description =
			"Press this key in-game to re-sync your account to osrsbestinslot.com immediately — it sends a "
			+ "fresh snapshot right away instead of waiting for the next automatic sync. Handy right after "
			+ "the site tells you to \"Re-sync\", or after opening your bank. Unset by default: click the "
			+ "field and press a key to assign one.",
		position = 5
	)
	default Keybind resyncHotkey()
	{
		return Keybind.NOT_SET;	// no key bound until the user assigns one
	}

	// Sync cadence is no longer a user setting: osrsbestinslot.com dictates it per link token in the
	// ingest response (X-Sync-Interval header), so it can be tuned centrally without a client change.
	// The client starts at a safe 120s default until the server's first response arrives.
}
