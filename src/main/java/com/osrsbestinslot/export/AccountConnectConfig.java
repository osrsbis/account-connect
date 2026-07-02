package com.osrsbestinslot.export;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("osrsbisexport")
public interface AccountConnectConfig extends Config
{
	// Exact disclosure sentence shown on the store-clip enabling toggle (RuneLite Plugin Hub rule:
	// a warning on the config option stating exactly what is sent).
	String CLIP_DISCLOSURE =
		"When enabled, this records a short muted video clip of your game screen while a shop "
			+ "is open and uploads it, together with your osrsbestinslot.com link token, to "
			+ "osrsbestinslot.com's servers as delivery proof. Only the rendered game frames are "
			+ "captured — no keyboard, mouse, password or other data is sent. Off by default.";

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
		keyName = "recordGeneralStoreClips",
		name = "Record store-visit clips",
		description = CLIP_DISCLOSURE,
		position = 4
	)
	default boolean recordGeneralStoreClips()
	{
		return false; // opt-in: OFF by default
	}

	@ConfigItem(
		keyName = "clipSeconds",
		name = "Clip length (seconds)",
		description = "How many seconds of the store visit to keep in the buffer and encode.",
		position = 5
	)
	@Range(min = 2, max = 15)
	default int clipSeconds()
	{
		return 6;
	}

	@ConfigItem(
		keyName = "clipFps",
		name = "Clip frame rate",
		description = "Frames per second captured and encoded. Lower = smaller file, less CPU.",
		position = 6
	)
	@Range(min = 5, max = 20)
	default int clipFps()
	{
		return 10;
	}
}
