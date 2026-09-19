package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Wave 3 firehose: high-frequency rows buffered and emitted as ONE fh_batch event.
 *
 * THE GRANT IS SERVER-SIDE AND THERE IS NO CONFIG ITEM. The original commit gated this on a
 * `maximumCapture` toggle in the plugin's own settings. The operator default (2026-09-19) is that this
 * plugin carries no user-facing settings, so the gate moved onto the X-Max-Capture response header,
 * exactly like the existing X-Store-Tools grant. These tests pin that an ungranted client captures
 * nothing at all, which is the property that keeps other players' names off every ordinary client.
 */
public class Wave3FirehoseTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	// 0.7.14 removed the enableUpload switch: uploadAllowed() is the link token alone, so the config
	// here only carries a token.
	private static AccountConnectConfig config(final String token)
	{
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return token;
			}
		};
	}

	/** A plugin with a linked token. The firehose grant is still OFF until the server sets it. */
	private static AccountConnectPlugin plug() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", config(TOKEN));
		return p;
	}

	private static Map<String, Object> row(String k, Object v)
	{
		Map<String, Object> d = new LinkedHashMap<>();
		d.put(k, v);
		return d;
	}

	@Test
	public void ungrantedClientBuffersNothing() throws Exception
	{
		AccountConnectPlugin p = plug();
		assertTrue("no grant = not capturing", !p.maxCapture());
		p.firehose("pos", row("x", 3200));
		p.firehose("nearby", row("rsn", "Someone Else"));
		p.flushFirehose();
		assertTrue("an ungranted client emits nothing at all", p.pendingEvents.isEmpty());
	}

	@Test
	public void grantedClientBatchesRowsIntoOneEvent() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.serverMaxCaptureEnabled = true;
		p.firehose("pos", row("x", 3200));
		p.firehose("pos", row("x", 3201));
		p.firehose("anim", row("id", 829));
		assertTrue("buffered, not emitted per row", p.pendingEvents.isEmpty());
		p.flushFirehose();
		assertEquals("one batch event", 1, p.pendingEvents.size());
		Map<String, Object> ev = p.pendingEvents.get(0);
		assertEquals("fh_batch", ev.get("type"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) ev.get("events");
		assertEquals(3, rows.size());
		assertEquals("pos", rows.get(0).get("k"));
		assertEquals("anim", rows.get(2).get("k"));
	}

	@Test
	public void bufferIsCappedAndDropsOverflow() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.serverMaxCaptureEnabled = true;
		for (int i = 0; i < 600; i++)
		{
			p.firehose("pos", row("x", i));
		}
		p.flushFirehose();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) p.pendingEvents.get(0).get("events");
		assertEquals("hard cap holds at 500", 500, rows.size());
	}

	@Test
	public void flushOnAnEmptyBufferEmitsNothing() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.serverMaxCaptureEnabled = true;
		p.flushFirehose();
		assertTrue("an empty flush is not an empty batch event", p.pendingEvents.isEmpty());
	}

	@Test
	public void grantAloneIsNotEnoughWithAMalformedToken() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", config("not-a-token"));	// uploadAllowed() is false
		p.serverMaxCaptureEnabled = true;		// server granted anyway
		assertTrue("a malformed token beats a server grant", !p.maxCapture());
		p.firehose("pos", row("x", 3200));
		p.flushFirehose();
		assertTrue(p.pendingEvents.isEmpty());
	}

	@Test
	public void grantAloneIsNotEnoughWithoutALinkedToken() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", config(""));
		p.serverMaxCaptureEnabled = true;
		assertTrue("no token beats a server grant", !p.maxCapture());
		p.firehose("pos", row("x", 3200));
		p.flushFirehose();
		assertTrue(p.pendingEvents.isEmpty());
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
