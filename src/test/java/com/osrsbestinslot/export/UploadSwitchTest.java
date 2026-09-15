package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The upload switch (0.7.12) and the config surface it replaced.
 *
 * The link token used to carry a {@code warning=}. RuneLite raises that dialog from
 * ConfigPanel.changeConfiguration, and for a text field that method is called by a FocusAdapter whose
 * focusLost runs on every focus loss, with or without an edit. So reading the token asked the user to
 * re-confirm the whole upload disclosure. The disclosure now lives on a default-false boolean, which
 * only prompts when it is actually ticked, and every network send checks that boolean.
 */
public class UploadSwitchTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private MockWebServer server;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	// ---- the config surface ----

	/**
	 * THE REGRESSION ARM FOR THE REPEATED DIALOG, and the only shape a unit test can hold.
	 *
	 * Reverting item 1 means putting a warning back on the token, and the token is a String. So:
	 * every config item that carries a non-empty warning must be a boolean. A boolean has no focus
	 * listener in ConfigPanel, so its warning can only be raised by a real tick.
	 */
	@Test
	public void onlyBooleanConfigItemsMayCarryAWarning()
	{
		int checked = 0;
		for (Method m : AccountConnectConfig.class.getDeclaredMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item == null)
			{
				continue;
			}
			checked++;
			if (item.warning() != null && !item.warning().isEmpty())
			{
				assertTrue("a warning may only sit on a boolean item, not on " + item.keyName()
						+ " of type " + m.getReturnType(),
					m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class);
			}
		}
		assertTrue("the config must actually declare items", checked >= 3);
	}

	/** The token itself must carry NO warning at all — that attribute is what fired on focus loss. */
	@Test
	public void theLinkTokenCarriesNoWarning() throws Exception
	{
		ConfigItem item = AccountConnectConfig.class.getMethod("linkToken").getAnnotation(ConfigItem.class);
		assertNotNull(item);
		assertTrue("linkToken must declare no warning", item.warning() == null || item.warning().isEmpty());
		assertTrue("the disclosure must survive in the description instead",
			item.description().contains("3rd-party server not controlled or verified"));
		assertTrue("the description must still name the counterparty upload",
			item.description().contains("other player's name"));
	}

	/** The switch is default-false and carries riktenx's required IP sentence plus our disclosure. */
	@Test
	public void theUploadSwitchIsDefaultFalseAndCarriesTheRequiredWarning() throws Exception
	{
		AccountConnectConfig fresh = new AccountConnectConfig()
		{
		};
		assertFalse("the upload switch must default to off", fresh.enableUpload());

		ConfigItem item = AccountConnectConfig.class.getMethod("enableUpload").getAnnotation(ConfigItem.class);
		assertNotNull(item);
		assertEquals("enableUpload", item.keyName());
		assertEquals("Upload to osrsbestinslot.com", item.name());
		assertTrue("the hub template sentence is required verbatim",
			item.warning().contains("This feature submits your IP address to a 3rd-party server not "
				+ "controlled or verified by Runelite developers"));
		assertTrue("our own disclosure must ride along",
			item.warning().contains("other player's name"));
	}

	/** The switch must sit ABOVE the token, so the user meets it first. */
	@Test
	public void theUploadSwitchIsPositionedBeforeTheLinkToken() throws Exception
	{
		int upload = AccountConnectConfig.class.getMethod("enableUpload")
			.getAnnotation(ConfigItem.class).position();
		int token = AccountConnectConfig.class.getMethod("linkToken")
			.getAnnotation(ConfigItem.class).position();
		assertTrue("enableUpload must come first (" + upload + " vs " + token + ")", upload < token);
	}

	// ---- the gate ----

	@Test
	public void uploadIsAllowedOnlyWithBothTheSwitchAndAValidToken() throws Exception
	{
		assertFalse("switch off, token good", plugin(false, TOKEN).uploadAllowed());
		assertFalse("switch on, no token", plugin(true, "").uploadAllowed());
		assertFalse("switch on, malformed token", plugin(true, "not-a-token").uploadAllowed());
		assertTrue("switch on, token good", plugin(true, TOKEN).uploadAllowed());
	}

	/**
	 * THE MUTATION ARM. With the switch off, every OkHttp call site must send NOTHING. Removing any
	 * one of the four early returns turns this red, because the mock server counts every request.
	 */
	@Test
	public void theSwitchOffProducesZeroHttpCalls() throws Exception
	{
		AccountConnectPlugin plugin = wired(false);

		// events
		plugin.pendingEvents.add(new java.util.LinkedHashMap<String, Object>());
		plugin.flushEvents();
		// store clip
		plugin.submitStoreClipUpload(java.util.Collections.singletonList(new byte[]{1, 2, 3}));
		// snapshot
		call(plugin, "postSnapshot", new Class<?>[]{String.class, java.util.Map.class, String.class},
			TOKEN, new java.util.LinkedHashMap<String, Object>(), "hash");
		// trade screenshot
		call(plugin, "uploadTradeScreenshot", new Class<?>[]{String.class, byte[].class, String.class},
			TOKEN, new byte[]{1, 2, 3}, "confirm");

		assertNull("no request may reach the server with the switch off",
			server.takeRequest(500, TimeUnit.MILLISECONDS));
		assertEquals(0, server.getRequestCount());
	}

	/** And with the switch on, the same event flush DOES reach the server — the gate is not "always off". */
	@Test
	public void theSwitchOnLetsTheEventFlushThrough() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200));
		AccountConnectPlugin plugin = wired(true);

		plugin.pendingEvents.add(new java.util.LinkedHashMap<String, Object>());
		plugin.flushEvents();

		assertNotNull("the flush must reach the server with the switch on",
			server.takeRequest(5, TimeUnit.SECONDS));
	}

	// ---- the one-time migration ----

	/**
	 * THE ARM THAT MODELS A REAL CLIENT, and the reason the migration is keyed on its own marker.
	 *
	 * RuneLite runs PluginManager.loadDefaultPluginConfiguration BEFORE startUp, and
	 * ConfigManager.setDefaultConfiguration writes every default-valued @ConfigItem whose string
	 * form is non-empty. A boolean false converts to "false", which is non-empty, so
	 * osrsbisexport.enableUpload is ALREADY "false" in the profile when the migration runs, for a
	 * user who never chose anything. So every migration arm here stubs enableUpload as "false", not
	 * as null. A migration keyed on enableUpload's own presence reads that as a choice and never
	 * fires, which silently stops the upload for every existing linked user.
	 */
	private static final String DEFAULT_WRITTEN = "false";

	/**
	 * AN EXISTING USER IS NOT SILENTLY DISCONNECTED. enableUpload defaults to false, so without this
	 * the 0.7.12 upgrade would stop every current upload and look like a broken plugin rather than an
	 * off switch. The marker key is the one signal that separates "never chose" from "chose off",
	 * because RuneLite has no default to write for a key that is not a config item.
	 */
	@Test
	public void anUnmigratedProfileWithAValidTokenIsTurnedOnOnce() throws Exception
	{
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		ConfigManager cm = mock(ConfigManager.class);
		// The real pre-startUp state: the switch is already default-written, the marker is not.
		when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload"))
			.thenReturn(DEFAULT_WRITTEN);
		when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP,
			AccountConnectPlugin.MIGRATION_MARKER_KEY)).thenReturn(null);
		inject(plugin, "configManager", cm);

		plugin.migrateUploadSwitch();

		verify(cm, times(1)).setConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload", true);
		verify(cm, times(1)).setConfiguration(AccountConnectPlugin.CONFIG_GROUP,
			AccountConnectPlugin.MIGRATION_MARKER_KEY, AccountConnectPlugin.MIGRATION_MARKER_VALUE);
		assertTrue("an auto-migrated user is owed the disclosure", plugin.uploadDisclosureOwed);
	}

	/**
	 * A MARKED PROFILE IS NEVER TOUCHED AGAIN. This is the arm that protects an explicit off: the
	 * user turned the switch off after migrating, and no later startUp may turn it back on.
	 */
	@Test
	public void aMigratedProfileIsNeverTouchedAgain() throws Exception
	{
		for (String stored : new String[]{"false", "true"})
		{
			AccountConnectPlugin plugin = plugin(false, TOKEN);
			ConfigManager cm = mock(ConfigManager.class);
			when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload")).thenReturn(stored);
			when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP,
				AccountConnectPlugin.MIGRATION_MARKER_KEY))
				.thenReturn(AccountConnectPlugin.MIGRATION_MARKER_VALUE);
			inject(plugin, "configManager", cm);

			plugin.migrateUploadSwitch();

			// The value overload MATTERS. ConfigManager has both setConfiguration(String,String,String)
			// and a generic setConfiguration(String,String,T); the plugin calls the generic one with a
			// boolean. A verify written with anyString()/any() resolves to the String overload, which
			// the plugin never calls, so it can never fail. Name the exact call instead — that is what
			// the migration mutants exercise.
			verify(cm, never()).setConfiguration(
				AccountConnectPlugin.CONFIG_GROUP, "enableUpload", (Object) Boolean.TRUE);
			verify(cm, never()).setConfiguration(
				AccountConnectPlugin.CONFIG_GROUP, "enableUpload", (Object) Boolean.FALSE);
			// And the marker is not rewritten either, so nothing about this startUp is a write.
			verify(cm, never()).setConfiguration(AccountConnectPlugin.CONFIG_GROUP,
				AccountConnectPlugin.MIGRATION_MARKER_KEY, AccountConnectPlugin.MIGRATION_MARKER_VALUE);
			assertFalse("a marked profile owes no disclosure", plugin.uploadDisclosureOwed);
		}
	}

	/**
	 * A FRESH INSTALL: no valid token, so nothing to migrate. The switch stays off and only the
	 * marker is written, so the next startUp does not re-examine the profile.
	 */
	@Test
	public void noTokenMeansTheMarkerOnlyAndTheSwitchStaysOff() throws Exception
	{
		for (String bad : new String[]{"", "   ", "not-a-token", "0123456789abcdef"})
		{
			AccountConnectPlugin plugin = plugin(false, bad);
			ConfigManager cm = mock(ConfigManager.class);
			when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload"))
				.thenReturn(DEFAULT_WRITTEN);
			when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP,
				AccountConnectPlugin.MIGRATION_MARKER_KEY)).thenReturn(null);
			inject(plugin, "configManager", cm);

			plugin.migrateUploadSwitch();

			verify(cm, never()).setConfiguration(
				AccountConnectPlugin.CONFIG_GROUP, "enableUpload", (Object) Boolean.TRUE);
			verify(cm, never()).setConfiguration(
				AccountConnectPlugin.CONFIG_GROUP, "enableUpload", (Object) Boolean.FALSE);
			// The marker still goes down: a fresh install must not be re-examined every startUp.
			verify(cm, times(1)).setConfiguration(AccountConnectPlugin.CONFIG_GROUP,
				AccountConnectPlugin.MIGRATION_MARKER_KEY, AccountConnectPlugin.MIGRATION_MARKER_VALUE);
			assertFalse("no auto-migration means no disclosure is owed", plugin.uploadDisclosureOwed);
		}
	}

	/**
	 * THE MARKER KEY IS NOT A CONFIG ITEM, and that is the whole fix.
	 *
	 * setDefaultConfiguration walks the config interface's declared methods and skips every one
	 * without a @ConfigItem annotation, so a key no config item declares has no default for RuneLite
	 * to write. If somebody ever adds a uploadMigrated @ConfigItem, the default write returns and
	 * the migration breaks again in exactly the way F1 described.
	 */
	@Test
	public void theMigrationMarkerIsNotAConfigItem()
	{
		for (Method m : AccountConnectConfig.class.getDeclaredMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item == null)
			{
				continue;
			}
			assertFalse("the migration marker must never become a @ConfigItem, or RuneLite "
					+ "default-writes it before startUp and the migration can never fire",
				AccountConnectPlugin.MIGRATION_MARKER_KEY.equals(item.keyName()));
		}
	}

	/** startUp must actually run the migration — a method nobody calls migrates nobody. */
	@Test
	public void startUpRunsTheMigration() throws Exception
	{
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		ConfigManager cm = mock(ConfigManager.class);
		when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload"))
			.thenReturn(DEFAULT_WRITTEN);
		when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP,
			AccountConnectPlugin.MIGRATION_MARKER_KEY)).thenReturn(null);
		inject(plugin, "configManager", cm);

		Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		startUp.invoke(plugin);

		verify(cm, times(1)).setConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload", true);
		verify(cm, times(1)).setConfiguration(AccountConnectPlugin.CONFIG_GROUP,
			AccountConnectPlugin.MIGRATION_MARKER_KEY, AccountConnectPlugin.MIGRATION_MARKER_VALUE);
	}

	// ---- the disclosure the auto-migration owes (F2) ----

	/**
	 * A PROGRAMMATIC WRITE SHOWS NO DIALOG. RuneLite raises a config item's warning from
	 * ConfigPanel.changeConfiguration, which only a real tick reaches, so an auto-migrated user
	 * never sees the sentence they would have seen had they ticked the box themselves. They are told
	 * in game chat instead, once.
	 */
	@Test
	public void theAutoMigratedUserIsToldOnceInGameChat() throws Exception
	{
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		net.runelite.api.Client client = mock(net.runelite.api.Client.class);
		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		inject(plugin, "client", client);
		plugin.uploadDisclosureOwed = true;

		plugin.deliverUploadDisclosure();
		plugin.deliverUploadDisclosure();	// a second call must add nothing

		verify(client, times(1)).addChatMessage(
			net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
			AccountConnectPlugin.UPLOAD_MIGRATION_NOTICE, null);
		assertFalse("the notice is owed once, not every tick", plugin.uploadDisclosureOwed);
	}

	/** The notice must carry the required sentence and name the switch that turns it back off. */
	@Test
	public void theNoticeCarriesTheDisclosureAndTheWayOut()
	{
		String notice = AccountConnectPlugin.UPLOAD_MIGRATION_NOTICE;
		assertTrue("the hub template sentence is required",
			notice.contains("submits your IP address to a 3rd-party server not controlled or "
				+ "verified by Runelite developers"));
		assertTrue("the notice must say uploading is ON", notice.contains("ON"));
		assertTrue("the notice must name the switch that stops it",
			notice.contains("Upload to osrsbestinslot.com"));
	}

	/** Not logged in means no chat box, so the notice stays owed rather than being lost. */
	@Test
	public void theNoticeWaitsForTheChatBox() throws Exception
	{
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		net.runelite.api.Client client = mock(net.runelite.api.Client.class);
		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGIN_SCREEN);
		inject(plugin, "client", client);
		plugin.uploadDisclosureOwed = true;

		plugin.deliverUploadDisclosure();

		verify(client, never()).addChatMessage(
			net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
			AccountConnectPlugin.UPLOAD_MIGRATION_NOTICE, null);
		assertTrue("an undelivered notice must stay owed", plugin.uploadDisclosureOwed);
	}

	/**
	 * startUp MUST DELIVER TOO, not only LOGGED_IN. A Plugin Hub install happens while the user is
	 * logged in and plays on, so a notice that only fires on the next LOGGED_IN is a notice the
	 * upgraded user may not see for hours while their account is already uploading.
	 */
	@Test
	public void startUpDeliversTheNoticeToAnAlreadyLoggedInUser() throws Exception
	{
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		ConfigManager cm = mock(ConfigManager.class);
		when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload"))
			.thenReturn(DEFAULT_WRITTEN);
		when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP,
			AccountConnectPlugin.MIGRATION_MARKER_KEY)).thenReturn(null);
		inject(plugin, "configManager", cm);
		net.runelite.api.Client client = mock(net.runelite.api.Client.class);
		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		inject(plugin, "client", client);

		Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		startUp.invoke(plugin);

		verify(client, times(1)).addChatMessage(
			net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
			AccountConnectPlugin.UPLOAD_MIGRATION_NOTICE, null);
		assertFalse("startUp must clear the owed notice it delivered", plugin.uploadDisclosureOwed);
	}

	/** A user who was never auto-migrated is never shown the notice. */
	@Test
	public void aUserWhoWasNotMigratedIsNeverToldAnything() throws Exception
	{
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		net.runelite.api.Client client = mock(net.runelite.api.Client.class);
		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		inject(plugin, "client", client);

		plugin.deliverUploadDisclosure();

		verify(client, never()).addChatMessage(
			net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
			AccountConnectPlugin.UPLOAD_MIGRATION_NOTICE, null);
	}

	// ---- helpers ----

	/** A plugin pointed at the mock server, with the http client and gson injected. */
	private AccountConnectPlugin wired(boolean upload) throws Exception
	{
		final String base = server.url("/api").toString().replaceAll("/+$", "");
		AccountConnectPlugin p = new AccountConnectPlugin();
		final boolean on = upload;
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public boolean enableUpload()
			{
				return on;
			}

			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public String apiBaseUrl()
			{
				return base;
			}
		});
		inject(p, "okHttpClient", new OkHttpClient());
		inject(p, "gson", new com.google.gson.Gson());
		inject(p, "executor", java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
		return p;
	}

	static AccountConnectPlugin plugin(boolean upload, String token) throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public boolean enableUpload()
			{
				return upload;
			}

			@Override
			public String linkToken()
			{
				return token;
			}
		});
		return p;
	}

	private static void call(AccountConnectPlugin p, String name, Class<?>[] types, Object... args)
		throws Exception
	{
		Method m = AccountConnectPlugin.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		m.invoke(p, args);
	}

	private static void inject(AccountConnectPlugin p, String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, value);
	}
}
