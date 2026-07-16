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
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	public void storeSellEmitsOnlyWhenShopOpen() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		MenuOptionClicked ev = mock(MenuOptionClicked.class);
		when(ev.getMenuOption()).thenReturn("Sell 10");
		when(ev.getItemId()).thenReturn(995);

		plugin.onMenuOptionClicked(ev); // shop closed → ignored
		assertTrue("no store event when shop closed", plugin.pendingEvents.isEmpty());

		inject(plugin, "shopOpen", true);
		plugin.onMenuOptionClicked(ev);
		assertEquals(1, plugin.pendingEvents.size());
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("store_sell", e.get("type"));
		assertEquals(995, e.get("item"));
		assertEquals(10, e.get("qty"));
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
