package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.events.ConfigChanged;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Review d1d15d0 P2: a held GE fill must survive switching to another profile or token and back. The slots of the
 * returning identity are read back from disk and the fill is sent once, across the gap, never lost or restarted.
 */
public class GeIdentityRoundTripTest
{
	private static final String TOKEN_A = "0123456789abcdef0123456789abcdef";
	private static final String TOKEN_B = "fedcba9876543210fedcba9876543210";
	@Rule public TemporaryFolder tmp = new TemporaryFolder();
	private AccountConnectPlugin p;
	private Client client;
	private String token = TOKEN_A;
	private int tick = 1000;
	private EnumSet<WorldType> world = EnumSet.noneOf(WorldType.class);
	private String originalKey;

	private void inject(String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, value);
	}

	@Before
	public void setup() throws Exception
	{
		p = new AccountConnectPlugin();
		inject("gson", new Gson());
		inject("config", new AccountConnectConfig()
		{
			@Override public String linkToken() { return token; }
		});
		client = mock(Client.class);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(111L);
		when(client.getWorldType()).thenAnswer(i -> world);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[8]);
		inject("client", client);
		p.spooler.setSpoolDataRootForTest(tmp.newFolder("plugin-data").toPath());
		p.startUp();
		p.activeHash = "111";
		p.activeProfileType = "STANDARD";
		p.events.pendingEvents.clear();
		offer(0, 0);
		originalKey = (String) row("ge_offer").get("offer_key");
		tick++;
		offer(10, 2400);
		assertNull("the partial is throttled", row("ge_progress"));
	}

	private void offer(int qty, long spent)
	{
		GrandExchangeOffer o = mock(GrandExchangeOffer.class);
		when(o.getState()).thenReturn(GrandExchangeOfferState.BUYING);
		when(o.getItemId()).thenReturn(560);
		when(o.getQuantitySold()).thenReturn(qty);
		when(o.getTotalQuantity()).thenReturn(100);
		when(o.getPrice()).thenReturn(300L);
		when(o.getSpent()).thenReturn(spent);
		GrandExchangeOfferChanged e = mock(GrandExchangeOfferChanged.class);
		when(e.getOffer()).thenReturn(o);
		when(e.getSlot()).thenReturn(0);
		p.onGrandExchangeOfferChanged(e);
	}

	private Map<String, Object> row(String type)
	{
		for (int i = p.events.pendingEvents.size() - 1; i >= 0; i--)
		{
			Map<String, Object> e = p.events.pendingEvents.get(i);
			if (type.equals(e.get("type"))) return e;
		}
		return null;
	}

	private void setToken(String from, String to)
	{
		token = to;
		ConfigChanged change = mock(ConfigChanged.class);
		when(change.getGroup()).thenReturn(AccountConnectPlugin.CONFIG_GROUP);
		when(change.getKey()).thenReturn("linkToken");
		when(change.getOldValue()).thenReturn(from);
		when(change.getNewValue()).thenReturn(to);
		p.onConfigChanged(change);
	}

	private void assertHeldFillArrivesOnceAcrossTheGap()
	{
		tick += 200;
		p.ge.flushDue();
		Map<String, Object> progress = row("ge_progress");
		assertNotNull("the held fill is sent after coming back", progress);
		assertEquals(originalKey, progress.get("offer_key"));
		Map<?, ?> o = (Map<?, ?>) progress.get("ge_observation");
		assertEquals(10L, o.get("delta_qty"));
		assertEquals(2400L, o.get("delta_gp"));
		assertEquals("inferred_across_gap", o.get("identity"));
	}

	@Test
	public void aHopToASeasonalWorldAndBackKeepsTheHeldFill()
	{
		world = EnumSet.of(WorldType.SEASONAL);
		tick += 5;
		p.ge.flushDue();
		assertNull("nothing is sent under the seasonal profile", row("ge_progress"));
		world = EnumSet.noneOf(WorldType.class);
		tick += 5;
		p.ge.flushDue();	// back on STANDARD: the slots are read back
		assertHeldFillArrivesOnceAcrossTheGap();
	}

	@Test
	public void aTokenEditedAndRestoredKeepsTheHeldFill()
	{
		setToken(TOKEN_A, TOKEN_B);
		tick += 5;
		p.ge.flushDue();
		assertNull("nothing is sent under the other token", row("ge_progress"));
		setToken(TOKEN_B, TOKEN_A);
		tick += 5;
		p.ge.flushDue();
		assertHeldFillArrivesOnceAcrossTheGap();
	}

	@Test
	public void stepsNotYetSentSurviveARestart()
	{
		tick++;
		offer(12, 2900);
		GeObservationTracker restarted = new GeObservationTracker();
		restarted.restore(p.ge.observations.export());
		Map<?, ?> o = (Map<?, ?>) restarted.due(5000).get(0).getValue().get("ge_observation");
		java.util.List<?> steps = (java.util.List<?>) o.get("steps");
		assertEquals(2, steps.size());
		assertEquals(2400L, ((Map<?, ?>) steps.get(0)).get("cumulative_gp"));
		assertEquals(2900L, ((Map<?, ?>) steps.get(1)).get("cumulative_gp"));
	}
}
