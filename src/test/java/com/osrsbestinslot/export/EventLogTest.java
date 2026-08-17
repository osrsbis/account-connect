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

	// ---- The broad chat sweep is REMOVED (0.7.3). These four tests replace the four that asserted it. ----
	// They are written as the INVERSE of the originals, deliberately: the sweep's old inputs are replayed
	// and each must now produce NOTHING. A removal with no test is indistinguishable from an untested one,
	// and the failure mode being guarded against is silent reintroduction under a different event name.

	@Test
	public void gameMessageEmitsNothing() throws Exception
	{
		// Was chatGameMessageEmitsEvent: this exact input used to produce a "chat" event.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
		when(ev.getMessage()).thenReturn("Oh dear, you are dead!");
		plugin.onChatMessage(ev);
		assertTrue("game chat must no longer be captured at all", plugin.pendingEvents.isEmpty());
	}

	@Test
	public void spamMessageEmitsNothing() throws Exception
	{
		// SPAM was the other half of the sweep's filter and produced the bulk of the flood volume
		// (skilling lines: "You catch a harpoonfish!" and similar, thousands per hour per account).
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.SPAM);
		when(ev.getMessage()).thenReturn("You catch a harpoonfish!");
		plugin.onChatMessage(ev);
		assertTrue("spam chat must no longer be captured at all", plugin.pendingEvents.isEmpty());
	}

	@Test
	public void publicChatStillEmitsNothing() throws Exception
	{
		// Unchanged guarantee, kept explicit: other-player chat was never captured and still is not.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.PUBLICCHAT);
		when(ev.getMessage()).thenReturn("buying gf");
		plugin.onChatMessage(ev);
		assertTrue("public/other-player chat must not be captured", plugin.pendingEvents.isEmpty());
	}

	@Test
	public void acceptedTradeStillEmitsTradeEventThroughOnChatMessage() throws Exception
	{
		// The trade path SHARES onChatMessage with the removed sweep, and removing the sweep changed that
		// method's control flow (fall-through became an early return). This is the regression that change
		// could plausibly cause, and nothing else in the suite drives onChatMessage with a TRADE message.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		// The accept path calls refreshSnapshotCacheAfterTrade, which reads the client. A mock at the
		// login screen (LOGGED_IN not stubbed -> null state) makes it return immediately, so this test
		// exercises the ROUTING without dragging in snapshot construction.
		inject(plugin, "client", mock(net.runelite.api.Client.class));
		injectPendingTrade(plugin, "Bob");
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.TRADE);
		when(ev.getMessage()).thenReturn("Accepted trade.");
		plugin.onChatMessage(ev);
		assertEquals("the accepted trade must still emit exactly one event", 1, plugin.pendingEvents.size());
		assertEquals("trade", plugin.pendingEvents.get(0).get("type"));
		assertEquals("Bob", plugin.pendingEvents.get(0).get("counterparty"));
	}

	@Test
	public void declinedTradeChatEmitsNothing() throws Exception
	{
		// A TRADE message that is not the accept line must reach handleTradeChat without emitting an event
		// — proving the early return narrowed the method to TRADE only, not that it swallowed the path.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.TRADE);
		when(ev.getMessage()).thenReturn("Other player declined trade.");
		plugin.onChatMessage(ev);
		assertTrue("a declined trade emits no event", plugin.pendingEvents.isEmpty());
	}

	@Test
	public void jagexDropBroadcastEmitsNothing() throws Exception
	{
		// The privacy case, named so it cannot be lost: Jagex broadcasts another player's RSN as a
		// GAMEMESSAGE. 845 such rows reached the server under the sweep, covering 270 distinct
		// third-party names. This input must now produce nothing.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		ChatMessage ev = mock(ChatMessage.class);
		when(ev.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
		when(ev.getMessage()).thenReturn("SomeOtherPlayer received a drop: Abyssal lantern");
		plugin.onChatMessage(ev);
		assertTrue("other players' names must not be captured via broadcasts", plugin.pendingEvents.isEmpty());
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
	public void unresolvableItemIdArmsNothing() throws Exception
	{
		// A store click whose menu entry carries no item id (getItemId() == -1) must NOT arm a pending.
		// Such clicks were observed in testing; comparing inventory contents either side of them showed
		// no item entering the inventory, so the click is a no-op rather than a purchase of an unknown
		// item, and there is nothing to recover. Mirrors the off-book path's own <= 0 guard.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "shopOpen", true);
		MenuOptionClicked ev = mock(MenuOptionClicked.class);
		when(ev.getMenuOption()).thenReturn("Buy 1");
		when(ev.getItemId()).thenReturn(-1);

		plugin.onMenuOptionClicked(ev);
		assertNull("an itemless store click must not arm a pending", storePending(plugin));
		assertTrue("an itemless store click must emit nothing", plugin.pendingEvents.isEmpty());
	}

	@Test
	public void unresolvableItemIdDoesNotClobberAnArmedPending() throws Exception
	{
		// The skip must be a plain return, not a null-item pending: a real armed buy waiting on its
		// inventory resolve must survive a stray itemless click.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "shopOpen", true);
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_buy", 4151, 1, 1000L, 5, false));
		MenuOptionClicked ev = mock(MenuOptionClicked.class);
		when(ev.getMenuOption()).thenReturn("Buy 1");
		when(ev.getItemId()).thenReturn(-1);

		plugin.onMenuOptionClicked(ev);
		AccountConnectPlugin.StorePending p = storePending(plugin);
		assertNotNull("the real armed pending must survive an itemless click", p);
		assertEquals("the surviving pending must be the original", 4151, p.item);
	}

	@Test
	public void shopCloseFlushesAnUnresolvedPendingRatherThanDroppingIt() throws Exception
	{
		// REGRESSION (0.7.2): buy-then-close is a common sequence, and the inventory change can arrive
		// after the shop widget is gone. Dropping the pending there silently loses the whole transaction.
		// 0.7.1 (the build on the Hub) emits the degraded {item, qty} form instead; that must not regress.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_buy", 4151, 1, 1000L, 5, false));

		// Drive the REAL event, not the helper: a mutation that reverts the call site back to
		// `storePending = null` leaves the helper itself perfect and the feature dead, and a test
		// that calls the helper directly would stay green through exactly that regression.
		plugin.onWidgetClosed(new WidgetClosed(300, 0, false)); // 300 = SHOPMAIN

		assertEquals("the transaction must still be reported", 1, plugin.pendingEvents.size());
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("store_buy", e.get("type"));
		assertEquals(4151, e.get("item"));
		assertEquals(1, e.get("qty"));
		assertFalse("a zero delta must never be reported as a price", e.containsKey("gp_total"));
		assertNull("pending consumed", storePending(plugin));
	}

	@Test
	public void shopCloseWithNoPendingEmitsNothing() throws Exception
	{
		// The discriminator: the flush must not invent an event when nothing was armed.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		plugin.flushStorePendingOnShopClose();
		assertEquals("no pending -> no event", 0, plugin.pendingEvents.size());
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
	public void resolveEmitsSellWithUnitAverageWhenTheExecutedQuantityConfirmsTheClick() throws Exception
	{
		// (b) sell → coins rose exactly, and the item count fell by exactly the clicked 5, so the
		// denominator is CONFIRMED and a labelled unit average is derived.
		//
		// Updated 2026-08-17: this test used to pass no item baseline at all and still expect a unit
		// price. The field showed a single "Sell 50" that only moved 27 items emitting a 10x-wrong unit
		// price, so an unmeasured quantity no longer licenses the division — see
		// AccountConnectPlugin#buildStoreTxFields and singleClickPartialFillIsLabelledNotPriced below.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending(
			"store_sell", 1391, 5, 200L, 5L, 5, false, false));	// itemBefore 5
		plugin.resolveStorePendingOnInventoryChange(950L, 0L, 5);	// coins rose 750, all 5 items gone
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("store_sell", e.get("type"));
		assertEquals(750L, e.get("gp_total"));
		assertEquals(150L, e.get("unit_price_gp")); // 750/5, a labelled average over a confirmed 5
	}

	@Test
	public void resolveOmitsUnitPriceWhenTheExecutedQuantityCannotBeMeasured() throws Exception
	{
		// No item baseline → no evidence the click executed in full → no derived unit price. gp_total is
		// still the exact measured coin movement.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending("store_sell", 1391, 5, 200L, 5, false));
		plugin.resolveStorePendingOnInventoryChange(950L, 5);
		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals(750L, e.get("gp_total"));
		assertNull("no evidence is not confirmation", e.get("unit_price_gp"));
	}

	/**
	 * FIELD DEFECT 3 (2026-08-17, Varrock General Store). One "Buy 50" click on a shop holding 5 emitted
	 * {@code qty:50, gp_total:3925, unit_price_gp:78} — the true unit was ~785, so the published rate was
	 * 10x low and looked exact. Reproduced on the sell side: one "Sell 50" moved 27 items and published
	 * {@code unit_price_gp:43} against ~80.
	 *
	 * <p>A single click's quantity is click INTENT, exactly like a merged one: shop stock, free inventory
	 * space and the player's coin balance all cap what actually executes. The row must therefore be
	 * LABELLED with what really moved, and must carry no derived price.
	 */
	@Test
	public void singleClickPartialFillIsLabelledNotPriced() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		// "Buy 50" with 0 in the inventory; only 5 arrive, costing 3,925 gp.
		inject(plugin, "storePending", new AccountConnectPlugin.StorePending(
			"store_buy", 22660, 50, 26_207_203L, 0L, 5, false, false));
		plugin.resolveStorePendingOnInventoryChange(26_203_278L, 5L, 5);

		Map<String, Object> e = plugin.pendingEvents.get(0);
		assertEquals("the measured coin movement, exact as always", 3_925L, e.get("gp_total"));
		assertEquals("the click's own quantity, for continuity with every historical row", 50, e.get("qty"));
		assertEquals("what the server actually moved", 5L, e.get("qty_executed"));
		assertEquals(Boolean.TRUE, e.get("qty_partial"));
		assertNull("78 gp/item was the fabrication this test exists to prevent", e.get("unit_price_gp"));
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
		// default (public) backend: counterparty now forwarded for ALL users (gate removed, product decision 2026-07-14)
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
