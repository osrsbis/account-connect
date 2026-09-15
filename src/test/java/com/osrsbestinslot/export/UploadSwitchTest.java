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
	 * AN EXISTING USER IS NOT SILENTLY DISCONNECTED. enableUpload defaults to false, so without this
	 * the 0.7.12 upgrade would stop every current upload and look like a broken plugin rather than an
	 * off switch. getConfiguration returns null only when the key was never written, which is the one
	 * signal that separates "never chose" from "chose off".
	 */
	@Test
	public void anUnsetSwitchWithAValidTokenIsTurnedOnOnce() throws Exception
	{
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		ConfigManager cm = mock(ConfigManager.class);
		when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload")).thenReturn(null);
		inject(plugin, "configManager", cm);

		plugin.migrateUploadSwitch();

		verify(cm, times(1)).setConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload", true);
	}

	/** A switch the user has already set — to anything — is never overwritten. */
	@Test
	public void anAlreadySetSwitchIsNeverOverwritten() throws Exception
	{
		for (String stored : new String[]{"false", "true"})
		{
			AccountConnectPlugin plugin = plugin(false, TOKEN);
			ConfigManager cm = mock(ConfigManager.class);
			when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload")).thenReturn(stored);
			inject(plugin, "configManager", cm);

			plugin.migrateUploadSwitch();

			verify(cm, never()).setConfiguration(org.mockito.Mockito.anyString(),
				org.mockito.Mockito.anyString(), org.mockito.Mockito.any());
		}
	}

	/** Without a valid token there is no existing user to migrate, so the switch stays off. */
	@Test
	public void noTokenMeansNoMigration() throws Exception
	{
		for (String bad : new String[]{"", "   ", "not-a-token", "0123456789abcdef"})
		{
			AccountConnectPlugin plugin = plugin(false, bad);
			ConfigManager cm = mock(ConfigManager.class);
			when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload")).thenReturn(null);
			inject(plugin, "configManager", cm);

			plugin.migrateUploadSwitch();

			verify(cm, never()).setConfiguration(org.mockito.Mockito.anyString(),
				org.mockito.Mockito.anyString(), org.mockito.Mockito.any());
		}
	}

	/** startUp must actually run the migration — a method nobody calls migrates nobody. */
	@Test
	public void startUpRunsTheMigration() throws Exception
	{
		AccountConnectPlugin plugin = plugin(false, TOKEN);
		ConfigManager cm = mock(ConfigManager.class);
		when(cm.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload")).thenReturn(null);
		inject(plugin, "configManager", cm);

		Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		startUp.invoke(plugin);

		verify(cm, times(1)).setConfiguration(AccountConnectPlugin.CONFIG_GROUP, "enableUpload", true);
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
