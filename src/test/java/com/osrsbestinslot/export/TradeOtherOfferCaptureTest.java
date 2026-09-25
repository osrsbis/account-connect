package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Trade RECEIVED-side + counterparty capture from the MAIN trade screen (335) — the fix for the prod bug
 * where 0/745 trades carried a counterparty or itemised received[] because everything was read at the
 * CONFIRM screen (334), by which point the 335 title widget is gone and the confirm "You will receive"
 * column is a value-text summary that collapses to "Lots!".
 *
 * These tests prove the capture + buffering LOGIC against MOCKED widgets. They do NOT — and cannot —
 * prove the real runtime shape of Trademain.OTHER_OFFER (21954588): whether it exposes item children and
 * is populated when polled. That requires one live two-sided in-game trade (see the plan's verify step).
 */
public class TradeOtherOfferCaptureTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int MAIN_OTHER_OFFER = 21954588;	// Trademain.OTHER_OFFER (other player's grid, 335)
	private static final int TRADE_TITLE = 21954591;		// Trademain.TITLE ("Trading With: X")
	private static final int CONFIRM_RECEIVE = 21889048;	// Tradeconfirm.YOU_WILL_RECEIVE (value-text, 334)
	private static final int TRADE_MAIN_GROUP = 335;
	private static final int TRADE_CONFIRM_GROUP = 334;

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

	/** A widget with the given [id,qty] item children (root itself carries no item). */
	private static Widget gridOf(int[]... items)
	{
		Widget root = mock(Widget.class);
		when(root.getItemId()).thenReturn(-1);
		Widget[] kids = new Widget[items.length];
		for (int i = 0; i < items.length; i++)
		{
			Widget k = mock(Widget.class);
			when(k.getItemId()).thenReturn(items[i][0]);
			when(k.getItemQuantity()).thenReturn(items[i][1]);
			kids[i] = k;
		}
		when(root.getChildren()).thenReturn(kids);
		return root;
	}

	private static Widget titleOf(String text)
	{
		Widget w = mock(Widget.class);
		when(w.getText()).thenReturn(text);
		return w;
	}

	@Test
	public void capturesReceivedItemsAndCounterpartyFromMainScreen() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		Widget grid = gridOf(new int[]{20997, 3}, new int[]{22486, 1});	// build BEFORE stubbing (gridOf uses when())
		Widget title = titleOf("Trading With: Snaauz");
		when(client.getWidget(MAIN_OTHER_OFFER)).thenReturn(grid);
		when(client.getWidget(TRADE_TITLE)).thenReturn(title);
		inject(plugin, "client", client);

		plugin.captureOtherOfferWhileMainOpen();

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> recv = (List<Map<String, Object>>) field(plugin, "pendingTradeReceived");
		assertEquals(2, recv.size());
		assertEquals(20997, recv.get(0).get("id"));
		assertEquals(3, recv.get(0).get("qty"));
		assertEquals(22486, recv.get(1).get("id"));
		assertEquals("Snaauz", field(plugin, "pendingCounterparty"));
	}

	@Test
	public void gameTickPollsOnlyWhileMainScreenOpen() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		Widget grid = gridOf(new int[]{995, 5000});
		Widget title = titleOf("Trading With: Bob");
		when(client.getWidget(MAIN_OTHER_OFFER)).thenReturn(grid);
		when(client.getWidget(TRADE_TITLE)).thenReturn(title);
		inject(plugin, "client", client);

		// tradeMainOpen == false → tick is a no-op
		plugin.onGameTick(mock(GameTick.class));
		assertNull("no capture while main screen closed", field(plugin, "pendingTradeReceived"));

		// tradeMainOpen == true → tick captures
		inject(plugin, "tradeMainOpen", true);
		plugin.onGameTick(mock(GameTick.class));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> recv = (List<Map<String, Object>>) field(plugin, "pendingTradeReceived");
		assertEquals(1, recv.size());
		assertEquals(995, recv.get(0).get("id"));
	}

	@Test
	public void mainScreenCaptureNotOverwrittenByConfirmTextSummary() throws Exception
	{
		// Simulate: the 335 poll already captured the itemised other-offer + name. Then the confirm screen
		// (334) loads with only a "Lots!" text column. The confirm branch must NOT clobber the good capture.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "tradeMainOpen", true);
		inject(plugin, "pendingTradeReceived", itemList(20997, 3));
		inject(plugin, "pendingCounterparty", "Snaauz");
		Client client = mock(Client.class);
		Widget textOnly = mock(Widget.class);
		when(textOnly.getItemId()).thenReturn(-1);
		when(textOnly.getText()).thenReturn("Lots!");
		when(client.getWidget(CONFIRM_RECEIVE)).thenReturn(textOnly);	// confirm value-text column
		when(client.getItemContainer(90)).thenReturn(null);				// own offer empty in this test
		inject(plugin, "client", client);

		plugin.handleActivityWidgetLoaded(TRADE_CONFIRM_GROUP);

		assertFalse("polling stopped at confirm", (boolean) field(plugin, "tradeMainOpen"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> recv = (List<Map<String, Object>>) field(plugin, "pendingTradeReceived");
		assertEquals("main-screen items preserved", 1, recv.size());
		assertEquals(20997, recv.get(0).get("id"));
		assertEquals("Snaauz", field(plugin, "pendingCounterparty"));
		assertNull("no text fallback when items already captured", field(plugin, "pendingReceivedText"));
	}

	@Test
	public void lastNonEmptyPollWins() throws Exception
	{
		// Offer starts empty (just opened), then the other player adds items, then (defensively) an empty
		// read must not wipe the captured offer.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "tradeMainOpen", true);
		Client client = mock(Client.class);
		Widget title = titleOf("Trading With: Bob");
		Widget empty1 = gridOf();
		Widget full = gridOf(new int[]{11832, 1});
		Widget empty2 = gridOf();
		when(client.getWidget(TRADE_TITLE)).thenReturn(title);
		when(client.getWidget(MAIN_OTHER_OFFER))
			.thenReturn(empty1)   // 1st poll: empty offer
			.thenReturn(full)     // 2nd poll: bandos chestplate added
			.thenReturn(empty2);  // 3rd poll: transient empty read
		inject(plugin, "client", client);

		plugin.captureOtherOfferWhileMainOpen();
		assertNull("empty offer leaves buffer null", field(plugin, "pendingTradeReceived"));
		plugin.captureOtherOfferWhileMainOpen();
		plugin.captureOtherOfferWhileMainOpen();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> recv = (List<Map<String, Object>>) field(plugin, "pendingTradeReceived");
		assertEquals("last NON-EMPTY read retained", 1, recv.size());
		assertEquals(11832, recv.get(0).get("id"));
	}

	@Test
	public void resetClearsTradeMainOpen() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "tradeMainOpen", true);
		java.lang.reflect.Method m = AccountConnectPlugin.class.getDeclaredMethod("resetTradeState");
		m.setAccessible(true);
		m.invoke(plugin);
		assertFalse((boolean) field(plugin, "tradeMainOpen"));
	}

	private static List<Map<String, Object>> itemList(int id, int qty)
	{
		List<Map<String, Object>> l = new ArrayList<>();
		Map<String, Object> mm = new java.util.LinkedHashMap<>();
		mm.put("id", id);
		mm.put("qty", qty);
		l.add(mm);
		return l;
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
