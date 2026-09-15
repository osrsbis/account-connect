package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE REAL EDT, not a simulated flag.
 *
 * RuneLite's PluginManager.startPlugin asserts it is on the Swing event dispatch thread and calls
 * startUp from there. This probe runs startUp on the ACTUAL EDT through
 * SwingUtilities.invokeAndWait, with a Client that throws off the client thread exactly as the
 * injected client does, and with a ClientThread that runs its runnable on a separate thread it
 * marks as the client thread. So the thread identities are real, not asserted.
 */
public class EdtStartUpProbeTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	@Test
	public void startUpOnTheRealEdtDoesNotThrowAndTheNoticeArrivesOnTheClientThread() throws Exception
	{
		final Map<String, String> store = new HashMap<>();
		store.put(AccountConnectPlugin.CONFIG_GROUP + ".enableUpload", "false");

		final Thread[] clientThreadHolder = new Thread[1];
		final List<String> shown = new ArrayList<>();

		net.runelite.api.Client client = mock(net.runelite.api.Client.class);
		when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
		when(client.isClientThread()).thenAnswer(
			inv -> Thread.currentThread() == clientThreadHolder[0]);
		when(client.addChatMessage(
			org.mockito.ArgumentMatchers.any(net.runelite.api.ChatMessageType.class),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.nullable(String.class)))
			.thenAnswer(inv ->
			{
				if (Thread.currentThread() != clientThreadHolder[0])
				{
					throw new IllegalStateException("must be called on client thread");
				}
				shown.add((String) inv.getArguments()[2]);
				return null;
			});

		final List<Runnable> queued = new ArrayList<>();
		net.runelite.client.callback.ClientThread ct =
			mock(net.runelite.client.callback.ClientThread.class);
		org.mockito.Mockito.doAnswer(inv ->
		{
			queued.add((Runnable) inv.getArguments()[0]);
			return null;
		}).when(ct).invokeLater(org.mockito.ArgumentMatchers.any(Runnable.class));

		final AccountConnectPlugin plugin = new AccountConnectPlugin();
		set(plugin, "config", new AccountConnectConfig()
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
		set(plugin, "configManager", fakeConfig(store));
		set(plugin, "client", client);
		set(plugin, "clientThread", ct);
		set(plugin, "overlayManager",
			mock(net.runelite.client.ui.overlay.OverlayManager.class));

		// --- startUp ON THE REAL EDT, exactly as PluginManager.startPlugin does it ---
		final AtomicReference<Throwable> thrown = new AtomicReference<>();
		final AtomicReference<Boolean> wasEdt = new AtomicReference<>(false);
		SwingUtilities.invokeAndWait(() ->
		{
			wasEdt.set(SwingUtilities.isEventDispatchThread());
			try
			{
				Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
				startUp.setAccessible(true);
				startUp.invoke(plugin);
			}
			catch (Throwable t)
			{
				thrown.set(t.getCause() == null ? t : t.getCause());
			}
		});

		assertTrue("the probe must really have run on the EDT", wasEdt.get());
		assertNull("startUp must not throw on the EDT: " + thrown.get(), thrown.get());
		assertTrue("nothing may be said from the EDT", shown.isEmpty());
		assertTrue("the notice is still owed", plugin.uploadDisclosureOwed);
		assertEquals("the migration ran", "true",
			store.get(AccountConnectPlugin.CONFIG_GROUP + ".enableUpload"));

		// --- a real separate thread plays the client thread and drains the queue ---
		assertEquals(1, queued.size());
		Thread clientThread = new Thread(queued.get(0), "probe-client-thread");
		clientThreadHolder[0] = clientThread;
		clientThread.start();
		clientThread.join(5000);

		assertEquals(1, shown.size());
		assertEquals(AccountConnectPlugin.UPLOAD_MIGRATION_NOTICE, shown.get(0));
		assertFalse("the delivered notice is cleared", plugin.uploadDisclosureOwed);
		assertNull("and the persisted debt too",
			store.get(AccountConnectPlugin.CONFIG_GROUP + "."
				+ AccountConnectPlugin.DISCLOSURE_OWED_KEY));
	}

	private static ConfigManager fakeConfig(final Map<String, String> store)
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

	private static void set(AccountConnectPlugin p, String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, value);
	}
}
