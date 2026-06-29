package com.osrsbestinslot.export;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev launcher: starts a full RuneLite client with the OSRS Best in Slot plugin side-loaded.
 * Run with: ./gradlew runClient  (see build.gradle). Used for the real account-login round-trip test.
 */
public class AccountConnectPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AccountConnectPlugin.class);
		RuneLite.main(args);
	}
}
