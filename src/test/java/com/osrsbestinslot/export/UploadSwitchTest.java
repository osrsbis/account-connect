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
		// No ClientThread is injected here, so this arm models a caller that is ALREADY on the
		// client thread. Off it the plugin must queue instead, which the hot-install arms cover.
		when(client.isClientThread()).thenReturn(true);
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
		// ON the client thread on purpose. Otherwise the send is refused for the wrong reason and
		// this arm stays green even with the LOGGED_IN check deleted.
		when(client.isClientThread()).thenReturn(true);
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
		when(client.isClientThread()).thenReturn(true);
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

	// ---- F-A: the Hub hot-install path, which is NOT the client thread ----

	/**
	 * THE ARM THE OLD MOCK COULD NOT SEE, and the reason the send is marshalled.
	 *
	 * A Plugin Hub install, and the 180-minute Hub auto-update, both call startUp from
	 * PluginManager.startPlugin on the Swing event dispatch thread while the user is logged in and
	 * playing. The real injected Client checks isClientThread first and throws off it. Any throw out
	 * of startUp makes startPlugin call stopPlugin and rethrow, so the plugin is dead for the rest
	 * of that session: no uploads, no overlays, and the notice is lost for good because the marker
	 * is already on disk. This Client throws exactly like the real one, so the mock can no longer
	 * hide it.
	 */
	@Test
	public void aHubInstallWhileLoggedInDoesNotKillThePluginAndStillDeliversTheNotice() throws Exception
	{
		java.util.Map<String, String> store = new java.util.HashMap<>();
		store.put(AccountConnectPlugin.CONFIG_GROUP + ".enableUpload", DEFAULT_WRITTEN);
		AccountConnectPlugin plugin = storeBackedPlugin(store);
		ConfigManager cm = fakeConfig(store);
		inject(plugin, "configManager", cm);

		java.util.concurrent.atomic.AtomicBoolean onClientThread =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		java.util.List<String> shown = new java.util.ArrayList<>();
		net.runelite.api.Client client = strictThreadClient(onClientThread, shown);
		inject(plugin, "client", client);

		java.util.List<Runnable> queue = new java.util.ArrayList<>();
		inject(plugin, "clientThread", capturingClientThread(queue));

		net.runelite.client.ui.overlay.OverlayManager overlays =
			mock(net.runelite.client.ui.overlay.OverlayManager.class);
		inject(plugin, "overlayManager", overlays);

		// startUp on the EDT. It must not throw.
		Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		startUp.invoke(plugin);

		// The plugin is still running: startUp reached the code AFTER the disclosure.
		verify(cm, times(1)).unsetConfiguration(AccountConnectPlugin.CONFIG_GROUP,
			AccountConnectPlugin.ORPHAN_SCREENSHOT_KEY);
		verify(overlays, times(2)).add(org.mockito.ArgumentMatchers.any());
		assertTrue("the migrated user must be allowed to upload", plugin.uploadAllowed());

		// Nothing was said on the EDT, and the debt is still owed and still on disk.
		assertTrue("no chat message may be sent off the client thread", shown.isEmpty());
		assertTrue("the notice is still owed until it is actually shown", plugin.uploadDisclosureOwed);
		assertEquals("true", store.get(AccountConnectPlugin.CONFIG_GROUP + "."
			+ AccountConnectPlugin.DISCLOSURE_OWED_KEY));

		// The client thread runs what was queued. NOW the user is told.
		assertEquals("the send must be queued on the client thread", 1, queue.size());
		onClientThread.set(true);
		queue.get(0).run();
		onClientThread.set(false);

		assertEquals(1, shown.size());
		assertEquals(AccountConnectPlugin.UPLOAD_MIGRATION_NOTICE, shown.get(0));
		assertFalse("a delivered notice is no longer owed", plugin.uploadDisclosureOwed);
		assertNull("and the persisted debt is cleared too",
			store.get(AccountConnectPlugin.CONFIG_GROUP + "."
				+ AccountConnectPlugin.DISCLOSURE_OWED_KEY));
	}

	/**
	 * A SEND THAT FAILS LEAVES THE DEBT IN PLACE. Clearing the flag before the send loses the notice
	 * for good, because the marker on disk stops the migration ever running again.
	 */
	@Test
	public void aSendThatThrowsLeavesTheNoticeOwed() throws Exception
	{
		java.util.Map<String, String> store = new java.util.HashMap<>();
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		inject(plugin, "configManager", fakeConfig(store));
		store.put(AccountConnectPlugin.CONFIG_GROUP + ".enableUpload", DEFAULT_WRITTEN);

		java.util.concurrent.atomic.AtomicBoolean onClientThread =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		java.util.List<String> shown = new java.util.ArrayList<>();
		inject(plugin, "client", strictThreadClient(onClientThread, shown));

		java.util.List<Runnable> queue = new java.util.ArrayList<>();
		inject(plugin, "clientThread", capturingClientThread(queue));

		Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		startUp.invoke(plugin);

		// Run the queued send while STILL off the client thread: it throws inside the runnable.
		assertEquals(1, queue.size());
		queue.get(0).run();

		assertTrue("a send that threw has told nobody, so the notice stays owed",
			plugin.uploadDisclosureOwed);
		assertEquals("and the persisted debt must survive it too", "true",
			store.get(AccountConnectPlugin.CONFIG_GROUP + "."
				+ AccountConnectPlugin.DISCLOSURE_OWED_KEY));
		assertTrue(shown.isEmpty());

		// A later LOGGED_IN queues it again rather than dropping it.
		plugin.deliverUploadDisclosure();
		assertEquals("the failed send must be retried", 2, queue.size());
		onClientThread.set(true);
		queue.get(1).run();
		assertEquals(1, shown.size());
		assertFalse(plugin.uploadDisclosureOwed);
	}

	// ---- F-C: the debt survives a client restart ----

	/**
	 * A CLIENT CLOSED BEFORE THE CHAT BOX STILL OWES THE NOTICE.
	 *
	 * The migration writes the marker whatever happens, and the marker stops the migration running
	 * again. So an in-memory-only flag is lost if the user quits on the login screen, and the switch
	 * stays on forever with nobody ever told. The debt is written next to the marker instead.
	 */
	@Test
	public void theOwedNoticeSurvivesARestartAndIsDeliveredOnce() throws Exception
	{
		java.util.Map<String, String> store = new java.util.HashMap<>();
		store.put(AccountConnectPlugin.CONFIG_GROUP + ".enableUpload", DEFAULT_WRITTEN);

		// Session one: migrate on the login screen, then the user closes the client.
		AccountConnectPlugin first = plugin(false, TOKEN);
		inject(first, "configManager", fakeConfig(store));
		net.runelite.api.Client loginScreen = mock(net.runelite.api.Client.class);
		when(loginScreen.getGameState()).thenReturn(net.runelite.api.GameState.LOGIN_SCREEN);
		inject(first, "client", loginScreen);
		Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		startUp.invoke(first);

		verify(loginScreen, never()).addChatMessage(
			org.mockito.ArgumentMatchers.any(net.runelite.api.ChatMessageType.class),
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any());
		assertEquals("the debt must be on disk before the client can close", "true",
			store.get(AccountConnectPlugin.CONFIG_GROUP + "."
				+ AccountConnectPlugin.DISCLOSURE_OWED_KEY));
		assertEquals("the profile is marked migrated, so this never runs again",
			AccountConnectPlugin.MIGRATION_MARKER_VALUE,
			store.get(AccountConnectPlugin.CONFIG_GROUP + "."
				+ AccountConnectPlugin.MIGRATION_MARKER_KEY));

		// Session two: a brand new plugin object, the same profile on disk, and the user logs in.
		AccountConnectPlugin second = plugin(true, TOKEN);
		inject(second, "configManager", fakeConfig(store));
		java.util.concurrent.atomic.AtomicBoolean onClientThread =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		java.util.List<String> shown = new java.util.ArrayList<>();
		inject(second, "client", strictThreadClient(onClientThread, shown));
		java.util.List<Runnable> queue = new java.util.ArrayList<>();
		inject(second, "clientThread", capturingClientThread(queue));

		startUp.invoke(second);
		assertTrue("the restored debt must be owed again", second.uploadDisclosureOwed);
		assertEquals(1, queue.size());
		onClientThread.set(true);
		queue.get(0).run();
		onClientThread.set(false);
		assertEquals(1, shown.size());
		assertEquals(AccountConnectPlugin.UPLOAD_MIGRATION_NOTICE, shown.get(0));

		// Session three: nothing is owed any more, so nobody is told twice.
		AccountConnectPlugin third = plugin(true, TOKEN);
		inject(third, "configManager", fakeConfig(store));
		java.util.List<String> shownAgain = new java.util.ArrayList<>();
		inject(third, "client", strictThreadClient(onClientThread, shownAgain));
		java.util.List<Runnable> laterQueue = new java.util.ArrayList<>();
		inject(third, "clientThread", capturingClientThread(laterQueue));
		startUp.invoke(third);
		assertFalse("the notice is owed once, not every launch", third.uploadDisclosureOwed);
		assertTrue("and nothing is queued for it", laterQueue.isEmpty());
		assertTrue(shownAgain.isEmpty());
	}

	/**
	 * A USER WHO TICKED THE SWITCH THEMSELVES IS NEVER TOLD. Their profile is already marked, so no
	 * migration runs, no debt is written, and no notice is queued on any launch.
	 */
	@Test
	public void aUserWhoTickedTheSwitchThemselvesNeverGetsTheNotice() throws Exception
	{
		java.util.Map<String, String> store = new java.util.HashMap<>();
		store.put(AccountConnectPlugin.CONFIG_GROUP + ".enableUpload", "true");
		store.put(AccountConnectPlugin.CONFIG_GROUP + "." + AccountConnectPlugin.MIGRATION_MARKER_KEY,
			AccountConnectPlugin.MIGRATION_MARKER_VALUE);

		AccountConnectPlugin plugin = plugin(true, TOKEN);
		inject(plugin, "configManager", fakeConfig(store));
		java.util.concurrent.atomic.AtomicBoolean onClientThread =
			new java.util.concurrent.atomic.AtomicBoolean(true);
		java.util.List<String> shown = new java.util.ArrayList<>();
		inject(plugin, "client", strictThreadClient(onClientThread, shown));
		java.util.List<Runnable> queue = new java.util.ArrayList<>();
		inject(plugin, "clientThread", capturingClientThread(queue));

		Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		startUp.invoke(plugin);

		assertFalse("a self-ticker owes nothing", plugin.uploadDisclosureOwed);
		assertTrue("nothing may be queued", queue.isEmpty());
		assertTrue("and nothing may be said", shown.isEmpty());
		assertNull("no debt key may be written",
			store.get(AccountConnectPlugin.CONFIG_GROUP + "."
				+ AccountConnectPlugin.DISCLOSURE_OWED_KEY));
	}

	// ---- helpers ----

	/**
	 * A Client that behaves like the injected one: addChatMessage throws unless the caller really is
	 * on the client thread. A plain Mockito mock accepts the call from any thread, which is exactly
	 * why the unit suite could not see F-A.
	 */
	private static net.runelite.api.Client strictThreadClient(
		final java.util.concurrent.atomic.AtomicBoolean onClientThread,
		final java.util.List<String> shown)
	{
		net.runelite.api.Client client = mock(net.runelite.api.Client.class);
		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		when(client.isClientThread()).thenAnswer(inv -> onClientThread.get());
		when(client.addChatMessage(
			org.mockito.ArgumentMatchers.any(net.runelite.api.ChatMessageType.class),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.nullable(String.class)))
			.thenAnswer(inv ->
			{
				if (!onClientThread.get())
				{
					throw new IllegalStateException("must be called on client thread");
				}
				shown.add((String) inv.getArguments()[2]);
				return null;
			});
		return client;
	}

	/** A ClientThread that records what was handed to it instead of running it. */
	private static net.runelite.client.callback.ClientThread capturingClientThread(
		final java.util.List<Runnable> queue)
	{
		net.runelite.client.callback.ClientThread ct =
			mock(net.runelite.client.callback.ClientThread.class);
		org.mockito.Mockito.doAnswer(inv ->
		{
			queue.add((Runnable) inv.getArguments()[0]);
			return null;
		}).when(ct).invokeLater(org.mockito.ArgumentMatchers.any(Runnable.class));
		return ct;
	}

	/**
	 * A plugin whose enableUpload() reads the same store the migration writes to, like the real
	 * config proxy. A fixed-false config could never show that the migration re-enabled uploading.
	 */
	private static AccountConnectPlugin storeBackedPlugin(final java.util.Map<String, String> store)
		throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public boolean enableUpload()
			{
				return "true".equals(store.get(AccountConnectPlugin.CONFIG_GROUP + ".enableUpload"));
			}

			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		});
		return p;
	}

	/** A ConfigManager backed by a real map, so a restart can be simulated by reusing the map. */
	private static ConfigManager fakeConfig(final java.util.Map<String, String> store)
	{
		return mock(ConfigManager.class, inv ->
		{
			String name = inv.getMethod().getName();
			Object[] a = inv.getArguments();
			if ("getConfiguration".equals(name) && a.length == 2)
			{
				return store.get(a[0] + "." + a[1]);
			}
			if ("setConfiguration".equals(name) && a.length == 3)
			{
				store.put(a[0] + "." + a[1], String.valueOf(a[2]));
				return null;
			}
			if ("unsetConfiguration".equals(name) && a.length == 2)
			{
				store.remove(a[0] + "." + a[1]);
				return null;
			}
			return org.mockito.Answers.RETURNS_DEFAULTS.answer(inv);
		});
	}


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
