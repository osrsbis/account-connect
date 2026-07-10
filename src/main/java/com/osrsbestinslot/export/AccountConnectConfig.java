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
		description = "Paste the token from osrsbestinslot.com (Connect account) to link this client.",
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
		keyName = "syncIntervalSeconds",
		name = "Sync interval (seconds)",
		description =
			"How often to sync when your account data changes (5-600). Lower = more up to date on the "
			+ "site, slightly more network; the client only sends when something actually changed. "
			+ "The default is right for most users.",
		position = 4
	)
	default int syncIntervalSeconds()
	{
		return 120;	// preserves the historical cadence; the value is clamped to [5, 600] in the plugin
	}
}
