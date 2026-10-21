package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
		Method build = AccountConnectPlugin.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		Map<String, Object> snap = (Map<String, Object>) build.invoke(plugin);
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
}
