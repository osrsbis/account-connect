package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import org.junit.Test;

/**
 * WAVE 3 — event enrichment: logout reason (idle / manual / six_hour_cap / connection_lost), login+logout
 * wealth, level_up xp, and death location. store_* price is intentionally NOT added (no clean transacted-price
 * API off the shop widget — see the note in onMenuOptionClicked); items-lost on death is deferred likewise.
 */
public class Wave3EnrichmentTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private static AccountConnectConfig onConfig()
	{
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		};
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static AccountConnectPlugin linkedPlugin() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "activeRsn", "TestPlayer");
		inject(plugin, "activeHash", "42");
		plugin.session.sessionActive = true;
		return plugin;
	}

	private static Map<String, Object> snapshotWithNetWorth(long net)
	{
		Map<String, Object> wealth = new LinkedHashMap<>();
		wealth.put("net_worth_gp", net);
		Map<String, Object> snap = new LinkedHashMap<>();
		snap.put("wealth", wealth);
		return snap;
	}

	@Test
	public void logoutReasonManualWhenNotIdle() throws Exception
	{
		AccountConnectPlugin plugin = linkedPlugin();
		plugin.session.sessionStartMillis = System.currentTimeMillis() - 60_000L;
		plugin.session.lastKeyboardIdleTicks = 3;
		plugin.session.lastMouseIdleTicks = 1;	// just clicked -> manual
		plugin.session.trackLogout();
		assertEquals("manual", plugin.events.pendingEvents.get(0).get("reason"));
	}

	@Test
	public void logoutReasonIdleWhenIdleTicksHigh() throws Exception
	{
		AccountConnectPlugin plugin = linkedPlugin();
		plugin.session.sessionStartMillis = System.currentTimeMillis() - 60_000L;
		plugin.session.lastKeyboardIdleTicks = 900;
		plugin.session.lastMouseIdleTicks = 900;	// no input for minutes -> idle
		plugin.session.trackLogout();
		assertEquals("idle", plugin.events.pendingEvents.get(0).get("reason"));
	}

	@Test
	public void logoutReasonSixHourCapWhenSessionNearSixHours() throws Exception
	{
		AccountConnectPlugin plugin = linkedPlugin();
		plugin.session.sessionStartMillis = System.currentTimeMillis() - (6L * 60L * 60L * 1000L);
		plugin.session.lastKeyboardIdleTicks = 0;
		plugin.session.lastMouseIdleTicks = 0;
		plugin.session.trackLogout();
		assertEquals("six_hour_cap", plugin.events.pendingEvents.get(0).get("reason"));
	}

	@Test
	public void connectionLostEmitsLogoutWithReason() throws Exception
	{
		AccountConnectPlugin plugin = linkedPlugin();
		plugin.session.sessionStartMillis = System.currentTimeMillis() - 60_000L;
		GameStateChanged ev = new GameStateChanged();
		ev.setGameState(GameState.CONNECTION_LOST);
		plugin.onGameStateChanged(ev);

		assertEquals(1, plugin.events.pendingEvents.size());
		Map<String, Object> e = plugin.events.pendingEvents.get(0);
		assertEquals("logout", e.get("type"));
		assertEquals("connection_lost", e.get("reason"));
		assertFalse("connection loss ends the session", plugin.session.sessionActive);
	}

	@Test
	public void logoutCarriesWealthFromLastSnapshot() throws Exception
	{
		AccountConnectPlugin plugin = linkedPlugin();
		plugin.session.sessionStartMillis = System.currentTimeMillis() - 60_000L;
		plugin.uploader.lastBuiltSnapshot = snapshotWithNetWorth(1_234_567L);
		plugin.session.trackLogout();
		assertEquals(1_234_567L, plugin.events.pendingEvents.get(0).get("wealth"));
	}

	@Test
	public void loginCarriesWealthFromLastSnapshot() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(client.getAccountHash()).thenReturn(42L);
		inject(plugin, "client", client);
		plugin.uploader.lastBuiltSnapshot = snapshotWithNetWorth(555L);

		boolean newLogin = plugin.session.trackSessionStart();
		assertTrue(newLogin);
		Map<String, Object> e = plugin.events.pendingEvents.get(0);
		assertEquals("login", e.get("type"));
		assertEquals(555L, e.get("wealth"));
	}

	@Test
	public void noNetWorthBeforeTheBankIsRead() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		Map<String, Object> wealth = new LinkedHashMap<>();
		wealth.put("net_worth_gp", null);	// bank not opened yet
		wealth.put("inventory_gp", 300L);
		wealth.put("equipment_gp", 200L);
		Map<String, Object> snap = new LinkedHashMap<>();
		snap.put("wealth", wealth);
		plugin.uploader.lastBuiltSnapshot = snap;
		assertNull("inventory + gear alone is not net worth", plugin.session.lastKnownNetWorth());
	}

	@Test
	public void levelUpCarriesXp() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		when(client.getSkillExperience(Skill.ATTACK)).thenReturn(13_363);
		inject(plugin, "client", client);

		// StatChanged is a final class -> construct directly (Skill, xp, level, boostedLevel).
		plugin.onStatChanged(new StatChanged(Skill.ATTACK, 12_000, 24, 24));	// primes previous level, no emit
		plugin.onStatChanged(new StatChanged(Skill.ATTACK, 13_363, 25, 25));	// real level increase -> emit

		assertEquals(1, plugin.events.pendingEvents.size());
		Map<String, Object> e = plugin.events.pendingEvents.get(0);
		assertEquals("level_up", e.get("type"));
		assertEquals(25, e.get("level"));
		assertEquals(13_363, e.get("xp"));
	}

	@Test
	public void deathDefersThenCarriesLocationAndKind() throws Exception
	{
		// Deferred model: ActorDeath arms a pending (reads pre-death inv+equip live) and emits NOTHING; the
		// death event with location + death_kind is emitted once the containers settle (syncTask) or on logout.
		// This mock is a SAFE death (no wilderness varbit / pvp world), so it carries NO items_lost — a safe death
		// is a gp-sink reclaim, not an inter-account transfer, and its diff would be polluted by post-death eating.
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		WorldPoint wp = new WorldPoint(3200, 3200, 0);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getWorldLocation()).thenReturn(wp);
		inject(plugin, "client", client);

		// ActorDeath is a final class -> construct directly with the (mockable) player as the dead actor.
		plugin.onActorDeath(new ActorDeath((Actor) player));
		assertTrue("death emit is deferred to the settle/logout resolve", plugin.events.pendingEvents.isEmpty());

		plugin.death.resolveDeathPending(new LinkedHashMap<>(), true); // settle-window path
		assertEquals(1, plugin.events.pendingEvents.size());
		Map<String, Object> e = plugin.events.pendingEvents.get(0);
		assertEquals("death", e.get("type"));
		assertEquals("safe", e.get("death_kind")); // no wilderness varbit / pvp world in the mock
		assertFalse("safe death carries no items_lost (not a transfer)", e.containsKey("items_lost"));
		@SuppressWarnings("unchecked")
		Map<String, Object> loc = (Map<String, Object>) e.get("location");
		assertEquals(wp.getRegionID(), loc.get("region_id"));
		assertEquals(0, loc.get("plane"));
	}

	private static boolean getBool(AccountConnectPlugin plugin, String fieldName) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		return f.getBoolean(plugin);
	}
}
