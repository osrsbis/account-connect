package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression guards for the two HIGH defects found by the 0.7.4 adversarial review. Both were
 * introduced BY 0.7.4 and neither was covered by the release's own tests — the suite was green while
 * both were live, which is why these exist.
 */
public class TradeLeakAndFlushWedgeTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int TRADE_MAIN_GROUP_ID = 335;

	private AccountConnectPlugin plugin;

	@Before
	public void setUp() throws Exception
	{
		plugin = new AccountConnectPlugin();
		inject("gson", new Gson());
		inject("okHttpClient", new OkHttpClient());
	}

	private void inject(String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private Object get(String name) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private void configWith(String apiBaseUrl) throws Exception
	{
		AccountConnectConfig config = mock(AccountConnectConfig.class);
		when(config.linkToken()).thenReturn(TOKEN);
		when(config.apiBaseUrl()).thenReturn(apiBaseUrl);
		inject("config", config);
	}

	// ============================================================ FINDING 1 — cross-trade leak

	/**
	 * A trade that ends WITHOUT acceptance must not leak its counterparty or received items into the
	 * next trade's event.
	 *
	 * <p>The leak path: a decline routes through handleTradeChat, which early-returns when trade
	 * screenshots are off — the DEFAULT — so resetTradeState() never runs. handleTradeWidgetClosed only
	 * resets when tradeArmed, which is set at confirm-load with screenshots ON, so a trade abandoned at
	 * the first screen never arms either. 0.7.4's last-non-empty-wins poll then PRESERVES the stale
	 * items past a next partner who offers nothing — the classic gold-delivery shape — producing
	 * {@code counterparty=Alice, received=[Bob's item]}.
	 *
	 * <p>0.7.3 could not do this: it assigned the pendings unconditionally at confirm, so every trade
	 * overwrote the last. The fix clears them at 335-load, the one point guaranteed to run per trade.
	 */
	@Test
	public void a335LoadClearsStalePendingsFromAnAbandonedTrade() throws Exception
	{
		configWith("https://example.invalid/api");

		// Trade 1 with Bob ended without acceptance — pendings survive in the real leak scenario.
		List<Map<String, Object>> bobsOffer = new ArrayList<>();
		Map<String, Object> bandos = new LinkedHashMap<>();
		bandos.put("id", 11832);
		bandos.put("qty", 1);
		bobsOffer.add(bandos);
		inject("pendingCounterparty", "Bob");
		inject("pendingTradeReceived", bobsOffer);
		inject("pendingReceivedText", "Bandos chestplate x 1");

		// Trade 2 opens. The 335-load must wipe Bob's residue before any polling happens.
		plugin.handleActivityWidgetLoaded(TRADE_MAIN_GROUP_ID);

		assertNull("counterparty from the abandoned trade must not survive", get("pendingCounterparty"));
		assertNull("received items must not survive — this is the field the release exists to fix",
			get("pendingTradeReceived"));
		assertNull("the text fallback must not survive either", get("pendingReceivedText"));
		assertTrue("polling must still be armed for the new trade", plugin.tradeMainOpen);
	}

	@Test
	public void repeatedAbandonedTradesNeverAccumulateResidue() throws Exception
	{
		configWith("https://example.invalid/api");
		for (int i = 0; i < 3; i++)
		{
			inject("pendingCounterparty", "Ghost" + i);
			List<Map<String, Object>> junk = new ArrayList<>();
			Map<String, Object> it = new LinkedHashMap<>();
			it.put("id", 4151 + i);
			it.put("qty", 1);
			junk.add(it);
			inject("pendingTradeReceived", junk);

			plugin.handleActivityWidgetLoaded(TRADE_MAIN_GROUP_ID);

			assertNull("each new trade starts clean", get("pendingCounterparty"));
			assertNull(get("pendingTradeReceived"));
		}
	}

	// ============================================================ FINDING 2 — permanent flush wedge

	/**
	 * A throw between draining the buffer and enqueueing the request must not lose the batch, and must
	 * NEVER leave delivery permanently wedged.
	 *
	 * <p>{@code apiBaseUrl} is user-editable and {@code Request.Builder.url()} throws
	 * IllegalArgumentException on a malformed value — a pasted non-URL is enough. Before the guard, that
	 * threw AFTER the buffer was cleared and {@code eventPostInFlight} set, so the batch was lost and
	 * every later flush early-returned forever, silently, with no further log lines. 0.7.3 recovered as
	 * soon as the user fixed the typo; unguarded 0.7.4 would stay dead until a plugin restart.
	 */
	@Test
	public void aMalformedApiUrlRequeuesTheBatchAndDoesNotWedgeDelivery() throws Exception
	{
		configWith("not a url at all");

		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("type", "trade");
		synchronized (plugin.pendingEvents)
		{
			plugin.pendingEvents.add(ev);
		}

		plugin.flushEvents();	// must not propagate

		synchronized (plugin.pendingEvents)
		{
			assertEquals("the batch must be preserved, not destroyed by the throw",
				1, plugin.pendingEvents.size());
		}
		assertFalse("the in-flight flag MUST be cleared, or every later flush early-returns forever",
			plugin.eventPostInFlight);
		assertTrue("the failure must arm the backoff like any other", plugin.eventRetryBackoffUntilMs > 0);
	}

	/**
	 * The recovery half: after the user CORRECTS the URL, delivery must resume. This is the difference
	 * between a transient config typo and a session-long silent outage.
	 */
	@Test
	public void deliveryResumesOnceTheUrlIsCorrected() throws Exception
	{
		configWith("not a url at all");
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("type", "trade");
		synchronized (plugin.pendingEvents)
		{
			plugin.pendingEvents.add(ev);
		}
		plugin.flushEvents();
		assertFalse(plugin.eventPostInFlight);

		// User fixes the config; the next tick must actually attempt a send rather than early-return.
		configWith("http://127.0.0.1:1/api");	// refused connection, but a VALID url
		plugin.eventRetryBackoffUntilMs = 0L;
		plugin.flushEvents();

		// The batch left the buffer (a real attempt happened) or came back via onFailure — either way
		// the flag must not be stuck true, which is what "wedged" means.
		Thread.sleep(500);
		assertFalse("delivery must not be permanently wedged after a corrected config",
			plugin.eventPostInFlight);
	}
}
