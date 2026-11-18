package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import org.junit.Before;
import org.junit.Test;

/**
 * account.members was TRUE on a members world and null everywhere else, never FALSE, so the site could not
 * act on a free-to-play account. An f2p world proves nothing (members play there too); the account's own
 * membership-days varp does, once the player's variables have arrived after login.
 */
public class AccountMembersTest
{
	private AccountConnectPlugin plugin;
	private Client client;
	private int tick = 1000;
	private int days;
	private EnumSet<WorldType> world = EnumSet.noneOf(WorldType.class);

	@Before
	public void setUp() throws Exception
	{
		plugin = new AccountConnectPlugin();
		inject("config", new AccountConnectConfig()
		{
		});
		inject("gson", new com.google.gson.Gson());
		client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(mock(Player.class));
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getWorldType()).thenAnswer(i -> world);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getVarpValue(VarPlayer.MEMBERSHIP_DAYS)).thenAnswer(i -> days);
		when(client.getVarps()).thenReturn(new int[5000]);
		inject("client", client);
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void login()
	{
		GameStateChanged gs = new GameStateChanged();
		gs.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(gs);
		gs.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(gs);
	}

	@SuppressWarnings("unchecked")
	private Object members() throws Exception
	{
		Method build = SnapshotBuilder.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		Map<String, Object> snap = (Map<String, Object>) build.invoke(plugin.snapshots);
		return ((Map<String, Object>) snap.get("account")).get("members");
	}

	@Test
	public void freeToPlayAccountIsFalseOnlyAfterItsVariablesSettle() throws Exception
	{
		login();
		assertNull("a zero right after login may still be on its way", members());
		tick += AccountConnectPlugin.MEMBERS_SETTLE_TICKS;
		assertEquals(Boolean.FALSE, members());
	}

	@Test
	public void memberOnAFreeWorldIsTrue() throws Exception
	{
		days = 23;
		login();
		assertNull("the days left by the account logged in before are not trusted yet", members());
		tick += AccountConnectPlugin.MEMBERS_SETTLE_TICKS;
		assertEquals(Boolean.TRUE, members());
	}

	@Test
	public void membersWorldIsTrueAtOnce() throws Exception
	{
		world = EnumSet.of(WorldType.MEMBERS);
		login();
		assertEquals(Boolean.TRUE, members());
	}

	@Test
	public void unloadedPlayerIsNeverFalse() throws Exception
	{
		login();
		tick += AccountConnectPlugin.MEMBERS_SETTLE_TICKS;
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(0);
		assertNull(members());
	}

	@Test
	public void sceneLoadingDoesNotRestartTheWait() throws Exception
	{
		login();
		tick += AccountConnectPlugin.MEMBERS_SETTLE_TICKS;
		GameStateChanged gs = new GameStateChanged();
		gs.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(gs);
		gs.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(gs);
		assertEquals(Boolean.FALSE, members());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> snapshot() throws Exception
	{
		Method build = SnapshotBuilder.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		return (Map<String, Object>) build.invoke(plugin.snapshots);
	}

	@Test
	public void membershipDaysAreUnknownUntilTheVariablesSettle() throws Exception
	{
		days = 23;
		world = EnumSet.of(WorldType.MEMBERS);
		login();
		assertNull(((Map<?, ?>) snapshot().get("account")).get("membership_days"));
		assertNull("no tool store read before the variables settle", snapshot().get("tool_leprechaun_raw"));
		tick += AccountConnectPlugin.MEMBERS_SETTLE_TICKS;
		assertEquals(23, ((Map<?, ?>) snapshot().get("account")).get("membership_days"));
		days = 0;
		assertEquals("a settled zero is a known zero", 0, ((Map<?, ?>) snapshot().get("account")).get("membership_days"));
	}

	@Test
	public void toolLeprechaunIsTheRawVarbitsAfterSettle() throws Exception
	{
		when(client.getVarbitValue(1441)).thenReturn(17);
		when(client.getVarbitValue(5732)).thenReturn(250);
		login();
		tick += AccountConnectPlugin.MEMBERS_SETTLE_TICKS;
		Map<?, ?> tools = (Map<?, ?>) snapshot().get("tool_leprechaun_raw");
		assertEquals(17, tools.get("buckets"));
		assertEquals(250, tools.get("ultracompost"));
		assertEquals(0, tools.get("rake"));
		assertEquals(SnapshotBuilder.TOOL_LEPRECHAUN.length / 2, tools.size());
	}

	@Test
	public void jagexLauncherIsTrueOrUnknownAndNeverSendsTheName() throws Exception
	{
		login();
		Map<String, Object> account = (Map<String, Object>) snapshot().get("account");
		assertNull("no launcher name is unknown, never false", account.get("jagex_launcher"));
		assertTrue(account.containsKey("jagex_launcher"));
		when(client.getLauncherDisplayName()).thenReturn("Launcher Name");
		Map<String, Object> snap = snapshot();
		assertEquals(Boolean.TRUE, ((Map<?, ?>) snap.get("account")).get("jagex_launcher"));
		assertFalse("the name is never sent", new com.google.gson.Gson().toJson(snap).contains("Launcher Name"));
	}

	@Test
	public void killCountsAreTheRawVarpsByIdAfterSettle() throws Exception
	{
		when(client.getVarpValue(1691)).thenReturn(412);	// Vorkath
		when(client.getVarpValue(2201)).thenReturn(7);	// beginner clues
		login();
		assertNull("no kill counts before the variables settle", snapshot().get("kill_counts_raw"));
		tick += AccountConnectPlugin.MEMBERS_SETTLE_TICKS;
		Map<?, ?> kills = (Map<?, ?>) snapshot().get("kill_counts_raw");
		assertEquals(412, kills.get("1691"));
		assertEquals(7, kills.get("2201"));
		assertEquals("a read zero is sent", 0, kills.get("1503"));
		assertEquals(SnapshotBuilder.KILL_COUNT_VARPS.length, kills.size());
	}
}
