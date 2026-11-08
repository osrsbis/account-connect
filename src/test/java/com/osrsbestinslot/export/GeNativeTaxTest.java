package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The game's own per-slot sale tax (long varps 5754..5761) travels on each GE row as an observation. */
public class GeNativeTaxTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private AccountConnectPlugin p;
	private Client client;
	private int tick = 1000;

	private void setUp() throws Exception
	{
		p = new AccountConnectPlugin();
		inject("config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		});
		client = mock(Client.class);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(111L);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[8]);
		inject("client", client);
		p.startUp();
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, value);
	}

	private void offer(int slot, GrandExchangeOfferState s, int item, int sold, int total, int price, long spent)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getState()).thenReturn(s);
		when(o.getItemId()).thenReturn(item);
		when(o.getQuantitySold()).thenReturn(sold);
		when(o.getTotalQuantity()).thenReturn(total);
		when(o.getPrice()).thenReturn((long) price);
		when(o.getSpent()).thenReturn(spent);
		GrandExchangeOfferChanged ev = mock(GrandExchangeOfferChanged.class);
		when(ev.getOffer()).thenReturn(o);
		when(ev.getSlot()).thenReturn(slot);
		p.onGrandExchangeOfferChanged(ev);
	}

	private Map<?, ?> lastObservation(String type)
	{
		for (int i = p.events.pendingEvents.size() - 1; i >= 0; i--)
		{
			Map<String, Object> e = p.events.pendingEvents.get(i);
			if (type.equals(e.get("type"))) return (Map<?, ?>) e.get("ge_observation");
		}
		return null;
	}

	@Test
	public void aSaleRowCarriesItsOwnSlotsNativeTaxAsAnObservation() throws Exception
	{
		setUp();
		// Slot 3 sells a whip; slot 4's varp holds an unrelated value and must not be read.
		when(client.getVarpLongValue(VarPlayerID.GE_TAX_SLOT_LONG_0 + 3)).thenReturn(30_000L);
		when(client.getVarpLongValue(VarPlayerID.GE_TAX_SLOT_LONG_0 + 4)).thenReturn(999L);
		offer(3, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick += 2;
		offer(3, GrandExchangeOfferState.SELLING, 4151, 0, 1, 1_500_000, 0);
		tick += 10;
		offer(3, GrandExchangeOfferState.SOLD, 4151, 1, 1, 1_500_000, 1_500_000L);
		Map<?, ?> o = lastObservation("ge_sell");
		assertNotNull(o);
		assertEquals(30_000L, o.get("native_tax_gp"));
		assertEquals("varp_long_when_row_built", o.get("native_tax_source"));
		// The existing fields keep their meaning: no exact tax or execution price is claimed.
		assertEquals(null, o.get("tax_gp"));
		assertEquals(null, o.get("execution_price"));
	}

	@Test
	public void aRowCarriesTheGamesMinuteClockWithTheClientTimeItWasRead() throws Exception
	{
		setUp();
		tick += 10;
		minute(29_400_122);
		minute(29_400_123);	// seen to tick
		when(client.getVarpValue(VarPlayerID.DATE_MINUTES)).thenReturn(29_400_123);
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick += 2;
		long before = System.currentTimeMillis();
		offer(2, GrandExchangeOfferState.BUYING, 4151, 0, 1, 1_500_000, 0);
		tick += 10;
		offer(2, GrandExchangeOfferState.BOUGHT, 4151, 1, 1, 1_500_000, 1_490_000L);
		Map<?, ?> o = lastObservation("ge_buy");
		assertNotNull(o);
		assertEquals(29_400_123, o.get("server_date_minutes"));
		assertTrue(((Number) o.get("server_clock_read_ms")).longValue() >= before);
		// Public projection keeps both numbers.
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "ge_buy");
		event.put("ge_observation", o);
		Map<?, ?> out = (Map<?, ?>) PublicTransport.event(event).get("ge_observation");
		assertEquals(29_400_123, out.get("server_date_minutes"));
		assertNotNull(out.get("server_clock_read_ms"));
	}

	private void minute(int v)
	{
		net.runelite.api.events.VarbitChanged e = new net.runelite.api.events.VarbitChanged();
		e.setVarpId(VarPlayerID.DATE_MINUTES);
		e.setVarbitId(-1);
		e.setValue(v);
		p.onVarbitChanged(e);
	}

	@Test
	public void aMinuteStepOlderThan65SecondsIsNotSent() throws Exception
	{
		// Review 1dca800 P3-2: a game that stopped stepping must not keep vouching for an old minute.
		setUp();
		tick += 10;
		minute(700);
		minute(701);
		when(client.getVarpValue(VarPlayerID.DATE_MINUTES)).thenReturn(701);
		Field started = GeSlots.class.getDeclaredField("minuteStartedAtMs");
		started.setAccessible(true);
		started.setLong(p.ge, System.currentTimeMillis() - 70_000L);
		offer(4, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick += 2;
		offer(4, GrandExchangeOfferState.BUYING, 4151, 0, 1, 1_500_000, 0);
		tick += 10;
		offer(4, GrandExchangeOfferState.BOUGHT, 4151, 1, 1, 1_500_000, 1_490_000L);
		Map<?, ?> o = lastObservation("ge_buy");
		assertFalse(o.containsKey("server_date_minutes"));
		assertFalse(o.containsKey("server_minute_seen_at_ms"));
	}

	@Test
	public void aSeenMinuteStepGivesTheBoundaryAndAJumpOrFirstValueDoesNot() throws Exception
	{
		setUp();
		tick += 10;	// past any login burst
		minute(500);	// first value: start unknown
		when(client.getVarpValue(VarPlayerID.DATE_MINUTES)).thenReturn(500);
		offer(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick += 2;
		offer(1, GrandExchangeOfferState.BUYING, 4151, 0, 1, 1_500_000, 0);
		tick += 10;
		offer(1, GrandExchangeOfferState.BOUGHT, 4151, 1, 1, 1_500_000, 1_490_000L);
		assertFalse(lastObservation("ge_buy").containsKey("server_minute_seen_at_ms"));
		assertFalse("a minute not seen to tick may be stale: not sent", lastObservation("ge_buy").containsKey("server_date_minutes"));

		long before = System.currentTimeMillis();
		minute(501);	// a +1 step while logged in: a boundary
		when(client.getVarpValue(VarPlayerID.DATE_MINUTES)).thenReturn(501);
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick += 2;
		offer(2, GrandExchangeOfferState.BUYING, 4151, 0, 1, 1_500_000, 0);
		tick += 10;
		offer(2, GrandExchangeOfferState.BOUGHT, 4151, 1, 1, 1_500_000, 1_490_000L);
		Map<?, ?> o = lastObservation("ge_buy");
		assertTrue(((Number) o.get("server_minute_seen_at_ms")).longValue() >= before);
		assertNotNull(((Map<?, ?>) PublicTransport.event(eventOf(o)).get("ge_observation")).get("server_minute_seen_at_ms"));

		minute(510);	// a jump (hop, lag): start unknown again
		when(client.getVarpValue(VarPlayerID.DATE_MINUTES)).thenReturn(510);
		offer(3, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick += 2;
		offer(3, GrandExchangeOfferState.BUYING, 4151, 0, 1, 1_500_000, 0);
		tick += 10;
		offer(3, GrandExchangeOfferState.BOUGHT, 4151, 1, 1, 1_500_000, 1_490_000L);
		assertFalse(lastObservation("ge_buy").containsKey("server_minute_seen_at_ms"));
		assertFalse(lastObservation("ge_buy").containsKey("server_date_minutes"));
	}

	private static Map<String, Object> eventOf(Map<?, ?> observation)
	{
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "ge_buy");
		event.put("ge_observation", observation);
		return event;
	}

	@Test
	public void anUnsetMinuteClockIsLeftOut() throws Exception
	{
		setUp();
		offer(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
		tick += 2;
		offer(2, GrandExchangeOfferState.BUYING, 4151, 0, 1, 1_500_000, 0);
		tick += 10;
		offer(2, GrandExchangeOfferState.BOUGHT, 4151, 1, 1, 1_500_000, 1_490_000L);
		Map<?, ?> o = lastObservation("ge_buy");
		assertNotNull(o);
		assertFalse(o.containsKey("server_date_minutes"));
		assertFalse(o.containsKey("server_clock_read_ms"));
	}

	@Test
	public void theNativeTaxSurvivesThePublicProjectionAndNothingElseIsAdded()
	{
		Map<String, Object> observation = new LinkedHashMap<>();
		observation.put("native_tax_gp", 30_000L);
		observation.put("native_tax_source", "varp_long_when_row_built");
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "ge_sell");
		event.put("ge_observation", observation);
		Map<?, ?> out = (Map<?, ?>) PublicTransport.event(event).get("ge_observation");
		assertEquals(30_000L, out.get("native_tax_gp"));
		assertEquals("varp_long_when_row_built", out.get("native_tax_source"));
		observation.put("native_tax_source", "a player name");
		assertFalse(((Map<?, ?>) PublicTransport.event(event).get("ge_observation")).containsKey("native_tax_source"));
	}
}
