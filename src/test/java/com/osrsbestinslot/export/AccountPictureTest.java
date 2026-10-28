package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import org.junit.Test;

/**
 * 0.7.62: the snapshot gives the site what it needs to judge requirements on its own. A quest the client could not
 * read is counted, so its absence from states is known to be unread. Quest points, every combat-achievement task
 * bit and points, the later slayer reward words and the var-backed minigame milestones are sent raw.
 */
public class AccountPictureTest
{
	@SuppressWarnings("unchecked")
	private static Map<String, Object> snapshot(Client client) throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		for (String[] f : new String[][]{{"client"}, {"gson"}})
		{
			Field field = AccountConnectPlugin.class.getDeclaredField(f[0]);
			field.setAccessible(true);
			field.set(p, f[0].equals("client") ? client : new Gson());
		}
		Field config = AccountConnectPlugin.class.getDeclaredField("config");
		config.setAccessible(true);
		config.set(p, new AccountConnectConfig()
		{
		});
		Method build = AccountConnectPlugin.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		return (Map<String, Object>) build.invoke(p);
	}

	private static Client client()
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Player player = mock(Player.class);
		when(player.getName()).thenReturn("Fixture Main");
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getWorldType()).thenReturn(java.util.EnumSet.noneOf(WorldType.class));
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		when(client.getVarps()).thenReturn(new int[20000]);
		when(client.getVarpValue(anyInt())).thenAnswer(i -> 1000 + (int) i.getArgument(0));
		when(client.getVarbitValue(anyInt())).thenAnswer(i -> 2000 + (int) i.getArgument(0));
		return client;
	}

	@Test
	public void questsSayHowManyWereRead() throws Exception
	{
		Map<?, ?> quests = (Map<?, ?>) snapshot(client()).get("quests");
		int read = ((Map<?, ?>) quests.get("states")).size();
		assertEquals(read, quests.get("read_count"));
		assertEquals(Quest.values().length, quests.get("known_count"));
		assertEquals(1000 + 101, quests.get("quest_points"));
	}

	@Test
	public void everyCombatTaskBitAndThePointsAreSent() throws Exception
	{
		Map<?, ?> ca = (Map<?, ?>) snapshot(client()).get("combat_achievements_raw");
		List<?> words = (List<?>) ca.get("task_words");
		assertEquals(AccountReadiness.CA_TASK_VARPS.length, words.size());
		assertEquals(1000 + AccountReadiness.CA_TASK_VARPS[0], words.get(0));
		assertEquals(2000 + 14815, ca.get("points"));
	}

	@Test
	public void theLaterSlayerRewardWordsAreSent() throws Exception
	{
		Map<?, ?> slayer = (Map<?, ?>) snapshot(client()).get("slayer");
		assertEquals(1000 + 1076, slayer.get("unlocks_raw"));
		assertEquals(1000 + 1344, slayer.get("unlocks1_raw"));
		assertEquals(1000 + 5587, slayer.get("unlocks2_raw"));
	}

	@Test
	public void varBackedMilestonesAreSentRaw() throws Exception
	{
		Map<?, ?> m = (Map<?, ?>) snapshot(client()).get("minigame_points");
		assertEquals(2000 + 4759, m.get("ba_attacker_base"));
		assertEquals(2000 + 11410, m.get("colosseum_highest_wave"));
		assertEquals(2000 + 11936, m.get("tempoross_permits"));
		assertEquals(1000 + 2871, m.get("soul_wars_zeal"));
		assertEquals(1000 + 4416, m.get("mixology_mox"));
	}
}
