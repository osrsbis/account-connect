package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bank move capture — bank_withdraw / bank_deposit emitted from the NET diff of the bank between open and
 * close. Bank transfers fire no trade / GE / store event, so this diff is the only event-plane record of
 * what left or entered the bank (the audit gap seen on regardo's session: 2 Twisted bows withdrawn, zero
 * events). Logic proven against mocked ItemContainers; container timing at real WidgetLoaded / WidgetClosed
 * is runtime-only (a live bank open→withdraw→close is the field verification).
 */
public class BankMoveCaptureTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int BANK_GROUP = 12;	// InterfaceID.BANKMAIN

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

	/** Mock ItemContainer whose getItems() returns the given [id,qty] items (Item is final — construct it). */
	private static ItemContainer containerOf(int[]... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		Item[] arr = new Item[items.length];
		for (int i = 0; i < items.length; i++)
		{
			arr[i] = new Item(items[i][0], items[i][1]);
		}
		when(c.getItems()).thenReturn(arr);
		return c;
	}

	private static AccountConnectPlugin pluginWith(ItemContainer bankAfter) throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(bankAfter);
		inject(plugin, "client", client);
		return plugin;
	}

	private static Map<Integer, Long> counts(long... idQtyPairs)
	{
		Map<Integer, Long> m = new LinkedHashMap<>();
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			m.put((int) idQtyPairs[i], idQtyPairs[i + 1]);
		}
		return m;
	}

	@Test
	public void withdrawEmitsBankWithdrawWithNetQty() throws Exception
	{
		// open: 3 Twisted bows + 1000 coins; close: 1 Twisted bow + 1000 coins -> 2 bows withdrawn.
		AccountConnectPlugin plugin = pluginWith(containerOf(new int[]{20997, 1}, new int[]{995, 1000}));
		inject(plugin, "bankAtOpen", counts(20997, 3, 995, 1000));

		plugin.handleBankWidgetClosed(BANK_GROUP);

		assertEquals(1, plugin.pendingEvents.size());
		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("bank_withdraw", ev.get("type"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) ev.get("items");
		assertEquals(1, items.size());
		assertEquals(20997, items.get(0).get("id"));
		assertEquals(2L, items.get(0).get("qty"));
		assertNull("bankAtOpen cleared after close", field(plugin, "bankAtOpen"));
	}

	@Test
	public void depositEmitsBankDepositForNewItems() throws Exception
	{
		// open: 1000 coins; close: 1000 coins + 50 sharks (385) -> 50 sharks deposited.
		AccountConnectPlugin plugin = pluginWith(containerOf(new int[]{995, 1000}, new int[]{385, 50}));
		inject(plugin, "bankAtOpen", counts(995, 1000));

		plugin.handleBankWidgetClosed(BANK_GROUP);

		assertEquals(1, plugin.pendingEvents.size());
		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("bank_deposit", ev.get("type"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) ev.get("items");
		assertEquals(1, items.size());
		assertEquals(385, items.get(0).get("id"));
		assertEquals(50L, items.get(0).get("qty"));
	}

	@Test
	public void mixedMovesEmitBothEventsUnchangedItemsIgnored() throws Exception
	{
		// A(1): 10->7 withdraw 3;  B(2): 5->5 no change;  C(3): 0->2 deposit 2.
		AccountConnectPlugin plugin = pluginWith(containerOf(new int[]{1, 7}, new int[]{2, 5}, new int[]{3, 2}));
		inject(plugin, "bankAtOpen", counts(1, 10, 2, 5));

		plugin.handleBankWidgetClosed(BANK_GROUP);

		assertEquals(2, plugin.pendingEvents.size());
		Map<String, Object> withdraw = plugin.pendingEvents.get(0);	// withdraw emitted before deposit
		Map<String, Object> deposit = plugin.pendingEvents.get(1);
		assertEquals("bank_withdraw", withdraw.get("type"));
		assertEquals("bank_deposit", deposit.get("type"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> w = (List<Map<String, Object>>) withdraw.get("items");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> d = (List<Map<String, Object>>) deposit.get("items");
		assertEquals(1, w.size());
		assertEquals(1, w.get(0).get("id"));
		assertEquals(3L, w.get(0).get("qty"));
		assertEquals(1, d.size());
		assertEquals(3, d.get(0).get("id"));
		assertEquals(2L, d.get(0).get("qty"));
	}

	@Test
	public void noChangeEmitsNothing() throws Exception
	{
		AccountConnectPlugin plugin = pluginWith(containerOf(new int[]{1, 10}));
		inject(plugin, "bankAtOpen", counts(1, 10));
		plugin.handleBankWidgetClosed(BANK_GROUP);
		assertTrue(plugin.pendingEvents.isEmpty());
	}

	@Test
	public void noOpenSnapshotEmitsNothing() throws Exception
	{
		// bankAtOpen null (close with no captured open state, e.g. a relog cleared it) -> nothing.
		AccountConnectPlugin plugin = pluginWith(containerOf(new int[]{1, 10}));
		plugin.handleBankWidgetClosed(BANK_GROUP);
		assertTrue(plugin.pendingEvents.isEmpty());
	}

	@Test
	public void nonBankGroupIgnoredAndOpenStatePreserved() throws Exception
	{
		AccountConnectPlugin plugin = pluginWith(containerOf(new int[]{1, 1}));
		inject(plugin, "bankAtOpen", counts(1, 10));
		plugin.handleBankWidgetClosed(335);	// trade group, not the bank
		assertTrue(plugin.pendingEvents.isEmpty());
		assertNotNull("bankAtOpen untouched for a non-bank widget", field(plugin, "bankAtOpen"));
	}

	@Test
	public void openCaptureSnapshotsBankState() throws Exception
	{
		// handleCaptureOnOpenWidgetLoaded(BANK) snapshots the bank into bankAtOpen (forceSendSnapshot bails:
		// mock getGameState() != LOGGED_IN).
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		ItemContainer bank = containerOf(new int[]{20997, 3}, new int[]{995, 1000});	// build BEFORE stubbing
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(bank);
		inject(plugin, "client", client);

		plugin.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);

		@SuppressWarnings("unchecked")
		Map<Integer, Long> open = (Map<Integer, Long>) field(plugin, "bankAtOpen");
		assertNotNull(open);
		assertEquals(Long.valueOf(3L), open.get(20997));
		assertEquals(Long.valueOf(1000L), open.get(995));
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
