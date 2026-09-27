package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bank move capture — ONE bank_session per bank session, carrying the GROSS per-item deposits and
 * withdrawals seen across every bank container change, plus complete (closed normally) and truncated.
 * Bank transfers fire no trade / GE / store event, so this is the only event-plane record of what left or
 * entered the bank (the audit gap seen on regardo's session: 2 Twisted bows withdrawn, zero events). The
 * 0.7.14 build diffed only open against close, so a deposit and a withdraw of the same item inside one
 * session cancelled out and disappeared. Logic proven against mocked ItemContainers; container timing at
 * real WidgetLoaded / WidgetClosed is runtime-only (the rig bank deposit + withdraw is the field check).
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

	private static final class Rig
	{
		final AccountConnectPlugin plugin;
		final Client client;

		Rig(AccountConnectPlugin plugin, Client client)
		{
			this.plugin = plugin;
			this.client = client;
		}

		/** The bank opens reading {@code bank}. */
		void open(ItemContainer bank)
		{
			when(client.getItemContainer(InventoryID.BANK)).thenReturn(bank);
			plugin.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);
		}

		/** A bank container change (a deposit or a withdraw lands). */
		void change(ItemContainer bank)
		{
			when(client.getItemContainer(InventoryID.BANK)).thenReturn(bank);
			plugin.handleBankContainerChanged(bank);
		}

		void close()
		{
			plugin.handleBankWidgetClosed(BANK_GROUP);
		}

		void state(GameState s)
		{
			GameStateChanged e = new GameStateChanged();
			e.setGameState(s);
			plugin.onGameStateChanged(e);
		}

		List<Map<String, Object>> sessions()
		{
			List<Map<String, Object>> out = new ArrayList<>();
			for (Map<String, Object> e : plugin.pendingEvents)
			{
				if ("bank_session".equals(e.get("type")))
				{
					out.add(e);
				}
			}
			return out;
		}
	}

	private static Rig rig() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		inject(plugin, "client", client);
		return new Rig(plugin, client);
	}

	@SuppressWarnings("unchecked")
	private static Map<Integer, Long> list(Map<String, Object> ev, String key)
	{
		Map<Integer, Long> m = new LinkedHashMap<>();
		for (Map<String, Object> it : (List<Map<String, Object>>) ev.get(key))
		{
			m.put((Integer) it.get("id"), (Long) it.get("qty"));
		}
		return m;
	}

	// ---- the three required cases ----

	/** Deposit then withdraw the SAME item in one session: both movements survive (net would be zero). */
	@Test
	public void depositThenWithdrawOfTheSameItemKeepsBothMovements() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{995, 1000}, new int[]{303, 1}));
		r.change(containerOf(new int[]{995, 1000}, new int[]{303, 2}));	// deposit 1 net
		r.change(containerOf(new int[]{995, 1000}, new int[]{303, 1}));	// withdraw 1 net
		r.close();
		List<Map<String, Object>> s = r.sessions();
		assertEquals("exactly one summary per session", 1, s.size());
		assertEquals(Long.valueOf(1), list(s.get(0), "deposited").get(303));
		assertEquals(Long.valueOf(1), list(s.get(0), "withdrawn").get(303));
		assertEquals(true, s.get(0).get("complete"));
		assertNull(s.get(0).get("truncated"));
	}

	/** An item absent at open, deposited and withdrawn again, is still both movements. */
	@Test
	public void aNewItemDepositedAndWithdrawnAgainKeepsBothMovements() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{995, 1000}));
		r.change(containerOf(new int[]{995, 1000}, new int[]{385, 50}));
		r.change(containerOf(new int[]{995, 1000}));
		r.close();
		Map<String, Object> s = r.sessions().get(0);
		assertEquals(Long.valueOf(50), list(s, "deposited").get(385));
		assertEquals(Long.valueOf(50), list(s, "withdrawn").get(385));
	}

	/** First open after login with the bank not loaded yet: the first contents are a baseline, not a deposit. */
	@Test
	public void firstOpenAfterLoginNeverLooksLikeAWholeBankDeposit() throws Exception
	{
		Rig r = rig();
		r.state(GameState.LOGGED_IN);
		r.open(null);		// unloaded at open
		r.change(containerOf(new int[]{995, 1_000_000}, new int[]{20997, 1}, new int[]{385, 200}));
		r.close();
		assertTrue("no whole-bank deposit", r.sessions().isEmpty());

		Rig r2 = rig();
		r2.open(containerOf());	// empty (not yet populated) at open
		r2.change(containerOf(new int[]{995, 1_000_000}, new int[]{20997, 1}));	// baseline
		r2.change(containerOf(new int[]{995, 1_000_000}, new int[]{20997, 1}, new int[]{385, 50}));	// real
		r2.close();
		assertEquals(1, r2.sessions().size());
		Map<Integer, Long> dep = list(r2.sessions().get(0), "deposited");
		assertEquals("only the real deposit", 1, dep.size());
		assertEquals(Long.valueOf(50), dep.get(385));
	}

	/** Hop, logout and disconnect before the close: the session is marked incomplete; nothing is invented. */
	@Test
	public void anInterruptedSessionIsIncompleteAndCarriesOnlyWhatWasSeen() throws Exception
	{
		for (GameState s : new GameState[]{GameState.HOPPING, GameState.LOGIN_SCREEN, GameState.CONNECTION_LOST})
		{
			Rig r = rig();
			r.open(containerOf(new int[]{995, 1000}, new int[]{303, 1}));
			r.change(containerOf(new int[]{995, 1000}));	// withdrew the net
			// the bank still reads a different state when the session breaks; it must NOT be diffed
			ItemContainer unseen = containerOf(new int[]{995, 5});
			when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(unseen);
			r.state(s);
			List<Map<String, Object>> sess = r.sessions();
			assertEquals(s + ": one summary", 1, sess.size());
			assertEquals(s + ": incomplete", false, sess.get(0).get("complete"));
			assertEquals(s + ": the seen withdraw", Long.valueOf(1), list(sess.get(0), "withdrawn").get(303));
			assertNull(s + ": no coins move invented from the unseen state", list(sess.get(0), "withdrawn").get(995));
			assertTrue(s + ": nothing deposited", list(sess.get(0), "deposited").isEmpty());
			assertNull(s + ": session cleared", field(r.plugin, "bankAtOpen"));
			r.close();	// a late close after the break must not emit a second row
			assertEquals(s + ": no second row", 1, r.sessions().size());
		}
	}

	/** A re-open while a session is still running (close never seen) ends the old one as incomplete. */
	@Test
	public void aReopenEndsTheUnclosedSessionAsIncomplete() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{995, 1000}, new int[]{303, 1}));
		r.change(containerOf(new int[]{995, 1000}));
		r.open(containerOf(new int[]{995, 1000}));
		assertEquals(1, r.sessions().size());
		assertEquals(false, r.sessions().get(0).get("complete"));
	}

	// ---- budget, shape and prior behaviour ----

	@Test
	public void manyChangesStillEmitOneSummaryWithGrossTotals() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{995, 1000}));
		for (int i = 0; i < 20; i++)
		{
			r.change(containerOf(new int[]{995, 900}));
			r.change(containerOf(new int[]{995, 1000}));
		}
		r.close();
		assertEquals("one event for 40 container changes", 1, r.plugin.pendingEvents.size());
		Map<String, Object> s = r.sessions().get(0);
		assertEquals(Long.valueOf(2000), list(s, "withdrawn").get(995));
		assertEquals(Long.valueOf(2000), list(s, "deposited").get(995));
	}

	@Test
	public void theItemListsAreCappedAndMarkedTruncated() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{995, 1}));
		int n = AccountConnectPlugin.BANK_SESSION_ITEM_CAP + 5;
		int[][] items = new int[n + 1][];
		items[0] = new int[]{995, 1};
		for (int i = 1; i <= n; i++)
		{
			items[i] = new int[]{10000 + i, 1};
		}
		r.change(containerOf(items));
		r.close();
		Map<String, Object> s = r.sessions().get(0);
		assertEquals(AccountConnectPlugin.BANK_SESSION_ITEM_CAP, list(s, "deposited").size());
		assertEquals(true, s.get("truncated"));
	}

	@Test
	public void aWithdrawSeenOnlyAtCloseIsStillCounted() throws Exception
	{
		// open: 3 Twisted bows + 1000 coins; close reads 1 bow -> 2 bows withdrawn (no change event seen).
		Rig r = rig();
		r.open(containerOf(new int[]{20997, 3}, new int[]{995, 1000}));
		ItemContainer atClose = containerOf(new int[]{20997, 1}, new int[]{995, 1000});
		when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(atClose);
		r.close();
		Map<String, Object> s = r.sessions().get(0);
		assertEquals(Long.valueOf(2), list(s, "withdrawn").get(20997));
		assertTrue(list(s, "deposited").isEmpty());
		assertNull("bankAtOpen cleared after close", field(r.plugin, "bankAtOpen"));
	}

	/** A transient empty read mid-session is not a whole-bank withdraw followed by a whole-bank deposit. */
	@Test
	public void aTransientEmptyReadFabricatesNothing() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{995, 1000}, new int[]{20997, 1}));
		r.change(containerOf());
		r.change(containerOf(new int[]{995, 1000}, new int[]{20997, 1}));
		r.close();
		assertTrue("no movement invented", r.sessions().isEmpty());
	}

	@Test
	public void noChangeEmitsNothing() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{1, 10}));
		r.change(containerOf(new int[]{1, 10}));
		r.close();
		assertTrue(r.plugin.pendingEvents.isEmpty());
	}

	@Test
	public void noOpenSnapshotEmitsNothing() throws Exception
	{
		Rig r = rig();
		ItemContainer b = containerOf(new int[]{1, 10});
		when(r.client.getItemContainer(InventoryID.BANK)).thenReturn(b);
		r.change(containerOf(new int[]{1, 5}));
		r.close();
		assertTrue(r.plugin.pendingEvents.isEmpty());
	}

	@Test
	public void noTokenEmitsNothing() throws Exception
	{
		Rig r = rig();
		inject(r.plugin, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return "";
			}
		});
		r.open(containerOf(new int[]{1, 10}));
		r.change(containerOf(new int[]{1, 5}));
		r.close();
		assertTrue(r.plugin.pendingEvents.isEmpty());
	}

	@Test
	public void nonBankGroupIgnoredAndOpenStatePreserved() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{1, 10}));
		r.plugin.handleBankWidgetClosed(335);	// trade group, not the bank
		assertTrue(r.plugin.pendingEvents.isEmpty());
		assertNotNull("bankAtOpen untouched for a non-bank widget", field(r.plugin, "bankAtOpen"));
	}

	@Test
	public void openCaptureSnapshotsBankState() throws Exception
	{
		Rig r = rig();
		r.open(containerOf(new int[]{20997, 3}, new int[]{995, 1000}));
		@SuppressWarnings("unchecked")
		Map<Integer, Long> open = (Map<Integer, Long>) field(r.plugin, "bankAtOpen");
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
