package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WAVE 1b — trade received[] (the counterparty's side). There is NO counterparty ItemContainer, so the
 * received items are read from the confirm-screen "You will receive" widget (packed component 21889048,
 * javap-verified: net.runelite.api.gameval.InterfaceID$Tradeconfirm.YOU_WILL_RECEIVE).
 *
 * These tests cover the emit wiring and the widget-parse logic against MOCKED widgets. They do NOT — and
 * cannot — verify the real confirm-screen structure: whether YOU_WILL_RECEIVE exposes item children vs a
 * text summary, and whether it is populated at WidgetLoaded time, both need one live two-sided trade.
 */
public class Wave1bTradeReceivedTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";
	// javap-verified packed component id (group 334 << 16 | child 24) for Tradeconfirm.YOU_WILL_RECEIVE.
	private static final int RECEIVE_COMPONENT = 21889048;

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

	@Test
	public void tradeEventCarriesStructuredReceivedItems() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "pendingTradeGiven", itemList(20997, 1));      // twisted bow given
		inject(plugin, "pendingCounterparty", "Bob");
		inject(plugin, "pendingTradeReceived", itemList(995, 5000));  // 5000 coins received

		plugin.emitTradeEvent();

		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("trade", ev.get("type"));
		assertEquals("Bob", ev.get("counterparty"));
		assertTrue("given present", ev.containsKey("given"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> received = (List<Map<String, Object>>) ev.get("received");
		assertEquals(1, received.size());
		assertEquals(995, received.get(0).get("id"));
		assertEquals(5000, received.get(0).get("qty"));
		assertFalse("no text fallback when structured items exist", ev.containsKey("received_text"));
	}

	@Test
	public void tradeEventFallsBackToReceivedText() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "pendingTradeGiven", itemList(20997, 1));
		inject(plugin, "pendingTradeReceived", new ArrayList<Map<String, Object>>()); // empty structured read
		inject(plugin, "pendingReceivedText", "Blood rune x 100");

		plugin.emitTradeEvent();

		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("Blood rune x 100", ev.get("received_text"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> received = (List<Map<String, Object>>) ev.get("received");
		assertTrue("received[] present but empty", received.isEmpty());
	}

	@Test
	public void tradeEventReceivedDefaultsToEmptyList() throws Exception
	{
		// Nothing captured for the received side (widget was absent): received[] must still be present + empty.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "pendingTradeGiven", itemList(20997, 1));

		plugin.emitTradeEvent();

		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertTrue(ev.containsKey("received"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> received = (List<Map<String, Object>>) ev.get("received");
		assertTrue(received.isEmpty());
		assertFalse(ev.containsKey("received_text"));
	}

	@Test
	public void readReceivedOfferReadsItemChildren() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		Client client = mock(Client.class);
		Widget root = mock(Widget.class);
		Widget slotA = mock(Widget.class);
		Widget slotB = mock(Widget.class);
		Widget blank = mock(Widget.class); // an empty slot (id -1) must be skipped
		when(client.getWidget(RECEIVE_COMPONENT)).thenReturn(root);
		when(root.getChildren()).thenReturn(new Widget[]{slotA, slotB, blank});
		when(slotA.getItemId()).thenReturn(995);
		when(slotA.getItemQuantity()).thenReturn(5000);
		when(slotB.getItemId()).thenReturn(560);
		when(slotB.getItemQuantity()).thenReturn(100);
		when(blank.getItemId()).thenReturn(-1);
		inject(plugin, "client", client);

		List<Map<String, Object>> got = plugin.readReceivedOffer();
		assertEquals(2, got.size());
		assertEquals(995, got.get(0).get("id"));
		assertEquals(5000, got.get(0).get("qty"));
		assertEquals(560, got.get(1).get("id"));
		assertEquals(100, got.get(1).get("qty"));
	}

	@Test
	public void readReceivedOfferFallsToDynamicChildren() throws Exception
	{
		// getChildren() empty → the dynamic-child array is consulted instead.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		Client client = mock(Client.class);
		Widget root = mock(Widget.class);
		Widget slot = mock(Widget.class);
		when(client.getWidget(RECEIVE_COMPONENT)).thenReturn(root);
		when(root.getChildren()).thenReturn(new Widget[0]);
		when(root.getDynamicChildren()).thenReturn(new Widget[]{slot});
		when(slot.getItemId()).thenReturn(4151);
		when(slot.getItemQuantity()).thenReturn(1);
		inject(plugin, "client", client);

		List<Map<String, Object>> got = plugin.readReceivedOffer();
		assertEquals(1, got.size());
		assertEquals(4151, got.get(0).get("id"));
	}

	@Test
	public void readReceivedTextCapturesRawSummary() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		Client client = mock(Client.class);
		Widget root = mock(Widget.class);
		when(client.getWidget(RECEIVE_COMPONENT)).thenReturn(root);
		when(root.getText()).thenReturn("Blood rune x 100<br>Coins x 5,000"); // raw, tags intact
		inject(plugin, "client", client);

		String text = plugin.readReceivedText();
		assertEquals("Blood rune x 100<br>Coins x 5,000", text);
		// and the structured read finds no items on a text-only widget
		assertTrue(plugin.readReceivedOffer().isEmpty());
	}

	@Test
	public void readReceivedNullSafeWhenWidgetAbsent() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		Client client = mock(Client.class);
		when(client.getWidget(RECEIVE_COMPONENT)).thenReturn(null);
		inject(plugin, "client", client);

		assertTrue("no widget → empty structured read", plugin.readReceivedOffer().isEmpty());
		assertNull("no widget → null text", plugin.readReceivedText());
	}

	@Test
	public void readReceivedNullSafeWhenClientNull() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin(); // client not injected
		assertTrue(plugin.readReceivedOffer().isEmpty());
		assertNull(plugin.readReceivedText());
	}

	@Test
	public void resetTradeStateClearsReceived() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "pendingTradeReceived", itemList(995, 1));
		inject(plugin, "pendingReceivedText", "something");
		invokeReset(plugin);
		assertNull(field(plugin, "pendingTradeReceived"));
		assertNull(field(plugin, "pendingReceivedText"));
	}

	private static List<Map<String, Object>> itemList(int id, int qty)
	{
		List<Map<String, Object>> l = new ArrayList<>();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("qty", qty);
		l.add(m);
		return l;
	}

	private static void invokeReset(AccountConnectPlugin plugin) throws Exception
	{
		java.lang.reflect.Method m = AccountConnectPlugin.class.getDeclaredMethod("resetTradeState");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	private static Object field(AccountConnectPlugin plugin, String name) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
