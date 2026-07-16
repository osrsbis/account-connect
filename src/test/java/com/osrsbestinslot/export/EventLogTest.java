package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the own-account activity log (syncActivityLog). No live client needed: exercises the
 * buffer/emit/session-end logic directly. NOT covered here (needs a live client / manual test plan):
 * trackSessionStart reading a real logged-in player, the @Schedule flush timing, and the /event-ingest
 * endpoint round-trip.
 */
public class EventLogTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";

	/** A linked config: valid token → activity log active (core sync, no toggle). */
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
	public void noTokenEmitIsNoOp() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", new AccountConnectConfig() {}); // linkToken() == "" → activity log inactive
		plugin.emitEvent("login", null);
		assertTrue("no token must buffer nothing", plugin.pendingEvents.isEmpty());
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

	@Test
	public void chatGameMessageEmitsEvent() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
		when(ev.getMessage()).thenReturn("Oh dear, you are dead!");
		plugin.emitChatActivity(ev);
		assertEquals(1, plugin.pendingEvents.size());
		assertEquals("chat", plugin.pendingEvents.get(0).get("type"));
		assertEquals("Oh dear, you are dead!", plugin.pendingEvents.get(0).get("text"));
	}

	@Test
	public void chatPublicMessageIgnored() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.PUBLICCHAT);
		when(ev.getMessage()).thenReturn("buying gf");
		plugin.emitChatActivity(ev);
		assertTrue("public/other-player chat must not be captured", plugin.pendingEvents.isEmpty());
	}

	@Test
	public void onChatMessageRoutesGameMessageToSweep() throws Exception
	{
		// reachability: a non-TRADE game message must fall through onChatMessage to the chat sweep,
		// not be eaten by the trade early-return.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
		when(ev.getMessage()).thenReturn("Congratulations, you've completed a quest!");
		plugin.onChatMessage(ev);
		assertEquals(1, plugin.pendingEvents.size());
		assertEquals("chat", plugin.pendingEvents.get(0).get("type"));
		assertEquals("Congratulations, you've completed a quest!", plugin.pendingEvents.get(0).get("text"));
	}

	@Test
	public void onChatMessageDoesNotSweepPublicChat() throws Exception
	{
		// the revived sweep keeps the original filter: public/other-player chat stays excluded.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.PUBLICCHAT);
		when(ev.getMessage()).thenReturn("buying gf");
		plugin.onChatMessage(ev);
		assertTrue("public/other-player chat must not be swept", plugin.pendingEvents.isEmpty());
	}

	@Test
	public void geFillEmitsOncePerTransition() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(GrandExchangeOfferState.BOUGHT);
		when(offer.getItemId()).thenReturn(20997);
		when(offer.getQuantitySold()).thenReturn(3);
		when(offer.getPrice()).thenReturn(100);
		when(offer.getSpent()).thenReturn(300);
		GrandExchangeOfferChanged ev = mock(GrandExchangeOfferChanged.class);
		when(ev.getOffer()).thenReturn(offer);
		when(ev.getSlot()).thenReturn(2);

		plugin.onGrandExchangeOfferChanged(ev);
		plugin.onGrandExchangeOfferChanged(ev); // unchanged terminal state → dedup, no second event

		assertEquals("GE terminal state should emit exactly once", 1, plugin.pendingEvents.size());
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("ge_buy", e.get("type"));
		assertEquals(Integer.valueOf(20997), e.get("item"));
	}

	@Test
	public void parseTrailingQtyParses()
	{
		assertEquals(10, AccountConnectPlugin.parseTrailingQty("Sell 10"));
		assertEquals(50, AccountConnectPlugin.parseTrailingQty("Buy 50"));
		assertEquals(1, AccountConnectPlugin.parseTrailingQty("Buy"));
	}

	@Test
	public void storeClickArmsPendingAndDefersEmit() throws Exception
	{
		// Deferred model: a store click no longer emits synchronously — it ARMS a pending, and the real
		// event fires on the next INVENTORY change (where the exact coins-delta price is known).
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		MenuOptionClicked ev = mock(MenuOptionClicked.class);
		when(ev.getMenuOption()).thenReturn("Sell 10");
		when(ev.getItemId()).thenReturn(1391);

		plugin.onMenuOptionClicked(ev); // shop closed → nothing armed, nothing emitted
		assertNull("no pending when shop closed", storePending(plugin));
		assertTrue("no store event when shop closed", plugin.pendingEvents.isEmpty());

		inject(plugin, "shopOpen", true);
		plugin.onMenuOptionClicked(ev);
		assertTrue("store click must defer emit to the inventory-change resolve", plugin.pendingEvents.isEmpty());
		AccountConnectPlugin.StorePending p = storePending(plugin);
		assertNotNull("store click must arm a pending", p);
		assertEquals("store_sell", p.type);
		assertEquals(1391, p.item);
		assertEquals(10, p.qty);
	}

	@Test
	public void resolveEmitsBuyWithExactDelta() throws Exception
	{
		// (a) buy → coins fell exactly; gp_total is the coins that left, qty==1 so no unit average.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_buy", 4151, 1, 1000L, 5, false));
		plugin.resolveStorePendingOnInventoryChange(700L, 6); // coins fell 300, one tick later
		assertEquals(1, plugin.pendingEvents.size());
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("store_buy", e.get("type"));
		assertEquals(4151, e.get("item"));
		assertEquals(1, e.get("qty"));
		assertEquals(300L, e.get("gp_total"));
		assertFalse("no unit average for a single item", e.containsKey("unit_price_gp"));
		assertNull("pending consumed after resolve", storePending(plugin));
	}

	@Test
	public void resolveEmitsSellWithUnitAverage() throws Exception
	{
		// (b) sell → coins rose exactly; qty>1 so a labelled unit average is derived.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_sell", 1391, 5, 200L, 5, false));
		plugin.resolveStorePendingOnInventoryChange(950L, 5); // coins rose 750, same tick as the click
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("store_sell", e.get("type"));
		assertEquals(750L, e.get("gp_total"));
		assertEquals(150L, e.get("unit_price_gp")); // 750/5, a labelled average
	}

	@Test
	public void resolvePartialFillUsesActualCoinsNotQtyGuess() throws Exception
	{
		// (c) "Buy 50" but only 10 filled → gp_total is the coins that ACTUALLY moved, never qty×unitguess.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_buy", 4151, 50, 100000L, 5, false));
		plugin.resolveStorePendingOnInventoryChange(97000L, 6); // only 3000 coins actually left
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("gp_total must be the coins that actually moved", 3000L, e.get("gp_total"));
	}

	@Test
	public void stalePendingExpiresWithoutEmitting() throws Exception
	{
		// (d) a failed click leaves a pending armed; an unrelated inventory change well past the expiry
		// window must NOT be paired with the stale coinsBefore — emit nothing, drop the pending.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_buy", 4151, 1, 1000L, 10, false));
		plugin.resolveStorePendingOnInventoryChange(50000L, 15); // 5 ticks later (> STORE_PENDING_MAX_TICKS)
		assertTrue("stale pending must emit nothing", plugin.pendingEvents.isEmpty());
		assertNull("stale pending must be cleared", storePending(plugin));
	}

	@Test
	public void shopCloseClearsStorePending() throws Exception
	{
		// (e) closing the shop widget drops any armed-but-unresolved pending.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_buy", 4151, 1, 1000L, 5, false));
		WidgetClosed wc = new WidgetClosed(300, 0, false); // SHOPMAIN — final class, use the real event
		plugin.onWidgetClosed(wc);
		assertNull("closing the shop must drop any armed pending", storePending(plugin));
	}

	@Test
	public void wrongSignDeltaOmitsPriceButKeepsItemQty() throws Exception
	{
		// A buy paired with a POSITIVE delta (coins rose) is a mispairing — degrade to {item,qty} (option D),
		// never emit a wrong-sign price.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_buy", 4151, 1, 1000L, 5, false));
		plugin.resolveStorePendingOnInventoryChange(1200L, 6); // wrong sign for a buy
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals(4151, e.get("item"));
		assertEquals(1, e.get("qty"));
		assertFalse("must not emit a wrong-sign price", e.containsKey("gp_total"));
	}

	@Test
	public void ambiguousBatchedClicksOmitPrice() throws Exception
	{
		// Same-tick batched clicks merge two transactions into one delta — omit gp_total even when the
		// sign is right, rather than attribute both to one click's qty.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_buy", 4151, 1, 1000L, 5, true));
		plugin.resolveStorePendingOnInventoryChange(700L, 5); // sign ok but batch-ambiguous
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertFalse("same-tick batched clicks must omit gp_total", e.containsKey("gp_total"));
	}

	private static AccountConnectPlugin.StorePending storePending(AccountConnectPlugin plugin) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("storePending");
		f.setAccessible(true);
		return (AccountConnectPlugin.StorePending) f.get(plugin);
	}

	@Test
	public void tradeCounterpartyForwardedForAllUsers() throws Exception
	{
		// default (public) backend: counterparty now forwarded for ALL users (gate removed, Lukas 2026-07-14)
		AccountConnectPlugin pub = new AccountConnectPlugin();
		inject(pub, "config", onConfig()); // apiBaseUrl == default public
		injectPendingTrade(pub, "Bob");
		pub.emitTradeEvent();
		Map<String, Object> pe = pub.pendingEvents.get(0);
		assertEquals("trade", pe.get("type"));
		assertTrue("given items present", pe.containsKey("given"));
		assertEquals("public backend now forwards counterparty", "Bob", pe.get("counterparty"));

		// staff backend: counterparty included
		AccountConnectPlugin staff = new AccountConnectPlugin();
		inject(staff, "config", new AccountConnectConfig()
		{
			@Override public String linkToken() { return TEST_TOKEN; }
			@Override public String apiBaseUrl() { return "https://staff.internal/api"; }
		});
		injectPendingTrade(staff, "Bob");
		staff.emitTradeEvent();
		assertEquals("Bob", staff.pendingEvents.get(0).get("counterparty"));
	}

	private static void injectPendingTrade(AccountConnectPlugin plugin, String counterparty) throws Exception
	{
		java.util.List<Map<String, Object>> given = new ArrayList<>();
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", 20997);
		item.put("qty", 1);
		given.add(item);
		inject(plugin, "pendingTradeGiven", given);
		inject(plugin, "pendingCounterparty", counterparty);
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
