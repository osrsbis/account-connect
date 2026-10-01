package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CaptureHealthTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";

	@Test
	public void eventQueueIsVisibleInHealth() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		plugin.emitEvent("test_event", new LinkedHashMap<>());
		Map<String, Object> h = plugin.captureHealthSnapshot();
		assertEquals(1L, h.get("events_queued_total"));
		assertEquals(1, h.get("events_pending"));
		assertEquals(0L, h.get("events_lost_total"));
	}

	@Test
	public void canonicalHashIgnoresCaptureHealth() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		Map<String, Object> snapshot = new LinkedHashMap<>();
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("plugin", "osrsbis-export");
		source.put("plugin_version", "0.7.16");
		Map<String, Object> health = new LinkedHashMap<>();
		health.put("events_queued_total", 1L);
		source.put("capture_health", health);
		snapshot.put("source", source);
		snapshot.put("captured_at", 1L);

		String first = plugin.canonicalHash(snapshot);
		health.put("events_queued_total", 99L);
		snapshot.put("captured_at", 2L);
		String second = plugin.canonicalHash(snapshot);
		assertEquals("health counters must not create snapshot traffic", first, second);
	}

	private static AccountConnectPlugin plugin() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "gson", new Gson());
		inject(plugin, "config", onConfig());
		return plugin;
	}

	private static AccountConnectConfig onConfig()
	{
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TEST_TOKEN;
			}
		};
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(target, value);
	}
}
