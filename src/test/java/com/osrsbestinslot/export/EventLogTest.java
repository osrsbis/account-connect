package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the own-account activity log (syncActivityLog). No live client needed: exercises the
 * buffer/emit/session-end logic directly. NOT covered here (needs a live client / manual test plan):
 * trackSessionStart reading a real logged-in player, the @Schedule flush timing, and the /event-ingest
 * endpoint round-trip.
 */
public class EventLogTest
{
	private static AccountConnectConfig onConfig()
	{
		return new AccountConnectConfig()
		{
			@Override
			public boolean syncActivityLog()
			{
				return true;
			}
		};
	}

	@Test
	public void toggleOffEmitIsNoOp() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", new AccountConnectConfig() {}); // syncActivityLog() == false (default)
		plugin.emitEvent("login", null);
		assertTrue("toggle off must buffer nothing", plugin.pendingEvents.isEmpty());
	}

	@Test
	public void emitBuffersOwnAccountEvent() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "activeRsn", "OSRS BOI");
		inject(plugin, "activeHash", "12345");
		plugin.emitEvent("login", null);

		assertEquals(1, plugin.pendingEvents.size());
		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("login", ev.get("type"));
		assertEquals("OSRS BOI", ev.get("rsn"));
		assertEquals("12345", ev.get("account_hash"));
		assertTrue("ts must be stamped", ((Long) ev.get("ts")) > 0L);
	}

	@Test
	public void emitMergesExtraFields() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("session_ms", 4200L);
		plugin.emitEvent("logout", fields);
		assertEquals(4200L, plugin.pendingEvents.get(0).get("session_ms"));
	}

	@Test
	public void trackLogoutEmitsDurationAndEndsSession() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "activeRsn", "regardo");
		inject(plugin, "activeHash", "999");
		inject(plugin, "sessionActive", true);
		inject(plugin, "sessionStartMillis", System.currentTimeMillis() - 1500L);

		plugin.trackLogout();
		assertEquals(1, plugin.pendingEvents.size());
		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("logout", ev.get("type"));
		assertEquals("regardo", ev.get("rsn"));
		assertTrue("session_ms should be >= elapsed", ((Long) ev.get("session_ms")) >= 1500L);

		// session ended → a second logout is a no-op (no duplicate)
		plugin.trackLogout();
		assertEquals("logout must fire once per session", 1, plugin.pendingEvents.size());
	}

	@Test
	public void bufferIsBounded() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		for (int i = 0; i < 600; i++)
		{
			plugin.emitEvent("tick", null);
		}
		assertEquals("buffer must be capped at MAX_PENDING_EVENTS", 500, plugin.pendingEvents.size());
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
