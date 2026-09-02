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
			"Paste the token from osrsbestinslot.com (Connect account) to link this client. While a token "
			+ "is set, this uploads YOUR OWN account to osrsbestinslot.com: your display name, account hash, "
			+ "account type, current world and location, skills, total and combat level, quests, achievement "
			+ "diaries, combat achievements, slayer task, collection log, equipment, inventory, bank, rune "
			+ "pouch, seed vault, Grand Exchange offers, wealth, spellbook, attack style, active prayers, "
			+ "Kourend favour and minigame points. In a Group Ironman group it also uploads your shared group "
			+ "storage, which can include items other members deposited. It also uploads your account "
			+ "activity: Grand Exchange and general-store buys and sells, completed trades INCLUDING the "
			+ "other player's name and the items each side exchanged, items you loot from kills and from "
			+ "reward chests (raids, Barrows, clue caskets and similar), items you drop, pick up or alch, "
			+ "deaths, level-ups, and login and logout times. It also uploads screenshots as delivery proof: "
			+ "your trade confirmation window when a trade completes, which shows the other player's name "
			+ "and the items traded, and a short series of your game screen while a shop window is open, "
			+ "which may include on-screen chat and other players' names (discarded if the visit had no "
			+ "purchase or sale). Your IP address reaches the server with every upload. Clear the token to "
			+ "stop all of it.",
		warning =
			"Setting a token uploads your account and your in-game activity to osrsbestinslot.com, a "
			+ "3rd-party server not controlled or verified by the RuneLite developers. That includes your IP "
			+ "address, your skills, quests, achievement diaries, collection log, equipment, inventory, bank "
			+ "and Group Ironman shared group storage, and your trades, Grand Exchange and shop "
			+ "transactions, loot, drops, deaths, level-ups and login times. Completed trades include the "
			+ "other player's name and the items each side exchanged. It also uploads screenshots of your trade "
			+ "window and of your game screen while a shop is open, which may include on-screen chat "
			+ "messages and other players' names. Clearing the token stops it. Only set it if you agree "
			+ "to that.",
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

	// Delivery-proof screenshots are no longer a separate tick box (2026-09-02). They are part of core
	// sync, active whenever a link token is set — the SAME gate as the account upload and the activity
	// log — so what they capture is disclosed in the linkToken description + warning above rather than on
	// a toggle of their own. Clearing the token stops it, and osrsbestinslot.com can still force it off
	// per token with the X-Screenshots / X-Clips response headers.

	// Sync cadence is no longer a user setting: osrsbestinslot.com dictates it per link token in the
	// ingest response (X-Sync-Interval header), so it can be tuned centrally without a client change.
	// The client starts at a safe 120s default until the server's first response arrives.
}
