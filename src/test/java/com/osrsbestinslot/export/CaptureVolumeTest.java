package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Event volume bounds for the always-on capture events. The server event ingest has a global row ceiling,
 * and a 429 over it stops every event type for every user, so these events must stay coarse:
 * equip_change fires only when the SET of worn ids changes (never per ammo shot), xp_gain flushes at most
 * every 5 minutes plus once at session end, and the first bank open with an unloaded bank never reports
 * the whole bank as bank_deposit.
 */
public class CaptureVolumeTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int WORN = net.runelite.api.gameval.InventoryID.WORN;
	private static final int BANK = net.runelite.api.gameval.InventoryID.BANK;
	private static final int BANK_GROUP = 12;	// InterfaceID.BANKMAIN
	private static final int TICKS_PER_HOUR = 6000;	// 0.6s per tick

	private static AccountConnectPlugin plug() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}
		});
		return p;
	}

	private static ItemContainer container(int[]... items)
	{
		ItemContainer c = mock(ItemContainer.class);
		Item[] a = new Item[items.length];
		for (int i = 0; i < items.length; i++)
		{
			a[i] = new Item(items[i][0], items[i][1]);
		}
		when(c.getItems()).thenReturn(a);
		return c;
	}

	private static List<Map<String, Object>> ofType(AccountConnectPlugin p, String type)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> ev : p.pendingEvents)
		{
			if (type.equals(ev.get("type")))
			{
				out.add(ev);
			}
		}
		return out;
	}

	// ---- equip_change ----

	private static final int BOW = 861;		// magic shortbow
	private static final int ARROW = 892;	// rune arrow
	private static final int HELM = 1163;

	@Test
	public void hundredAmmoShotsEmitNoEquipChange() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{BOW, 1}, new int[]{ARROW, 500})));	// baseline
		for (int left = 499; left >= 400; left--)
		{
			p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{BOW, 1}, new int[]{ARROW, left})));
		}
		assertEquals("ammo spent while still worn is not an equipment change", 0, ofType(p, "equip_change").size());
	}

	@Test
	public void weaponSwapEmitsExactlyOneEquipChange() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{HELM, 1}, new int[]{BOW, 1}, new int[]{ARROW, 500})));
		p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{HELM, 1}, new int[]{ARROW, 499})));	// shot, then bow off
		assertEquals(1, ofType(p, "equip_change").size());	// the unequip of the bow
		p.pendingEvents.clear();

		// swap: whip on in the same slot, arrows also spent in the same change
		p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{HELM, 1}, new int[]{4151, 1}, new int[]{ARROW, 498})));
		List<Map<String, Object>> evs = ofType(p, "equip_change");
		assertEquals(1, evs.size());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> equipped = (List<Map<String, Object>>) evs.get(0).get("equipped");
		assertEquals(1, equipped.size());
		assertEquals(4151, equipped.get(0).get("id"));
		assertNull("a quantity change of a still-worn id is not reported", evs.get(0).get("unequipped"));
	}

	@Test
	public void weaponReplacedInSlotEmitsOneEventWithBothSides() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{BOW, 1}, new int[]{ARROW, 500})));
		p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{4151, 1}, new int[]{ARROW, 500})));
		List<Map<String, Object>> evs = ofType(p, "equip_change");
		assertEquals(1, evs.size());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> un = (List<Map<String, Object>>) evs.get(0).get("unequipped");
		assertEquals(1, un.size());
		assertEquals(BOW, un.get(0).get("id"));
	}

	@Test
	public void unequipEmitsExactlyOneEquipChange() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{BOW, 1}, new int[]{ARROW, 500})));
		p.onItemContainerChanged(new ItemContainerChanged(WORN, container(new int[]{BOW, 1})));	// quiver emptied / arrows removed
		List<Map<String, Object>> evs = ofType(p, "equip_change");
		assertEquals(1, evs.size());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> un = (List<Map<String, Object>>) evs.get(0).get("unequipped");
		assertEquals(ARROW, un.get(0).get("id"));
		assertEquals(500L, un.get(0).get("qty"));
	}

	// ---- xp_gain ----

	@Test
	public void oneHourOfTrainingFlushesAtMostThirteenTimesAndLosesNoXp() throws Exception
	{
		AccountConnectPlugin p = plug();
		int xp = 1_000_000;
		p.onStatChanged(new StatChanged(Skill.WOODCUTTING, xp, 80, 80));	// baseline
		long gained = 0;
		for (int t = 0; t < TICKS_PER_HOUR; t++)
		{
			if (t % 4 == 0)		// one log every 4 ticks, 175 xp each
			{
				xp += 175;
				gained += 175;
				p.onStatChanged(new StatChanged(Skill.WOODCUTTING, xp, 80, 80));
			}
			p.onGameTick(new GameTick());
		}
		// a few ticks of extra xp after the last timed flush, then logout
		xp += 175;
		gained += 175;
		p.onStatChanged(new StatChanged(Skill.WOODCUTTING, xp, 80, 80));
		int beforeLogout = ofType(p, "xp_gain").size();
		assertTrue("timed flushes in one hour: " + beforeLogout, beforeLogout <= 12);

		p.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		List<Map<String, Object>> evs = ofType(p, "xp_gain");
		assertEquals("logout flushes the rest", beforeLogout + 1, evs.size());
		assertTrue("flushes for the hour including logout: " + evs.size(), evs.size() <= 13);
		assertEquals("sum of xp in events == xp gained", gained, sumXp(evs, "Woodcutting"));
	}

	@Test
	public void worldHopFlushesAccumulatedXp() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.onStatChanged(new StatChanged(Skill.FISHING, 5000, 40, 40));
		p.onStatChanged(new StatChanged(Skill.FISHING, 5100, 40, 40));
		p.onGameTick(new GameTick());
		assertEquals("no flush before 5 minutes", 0, ofType(p, "xp_gain").size());
		p.onGameStateChanged(gameState(GameState.HOPPING));
		List<Map<String, Object>> evs = ofType(p, "xp_gain");
		assertEquals(1, evs.size());
		assertEquals(100L, sumXp(evs, "Fishing"));
	}

	private static long sumXp(List<Map<String, Object>> evs, String skill)
	{
		long sum = 0;
		for (Map<String, Object> ev : evs)
		{
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> gains = (List<Map<String, Object>>) ev.get("gains");
			for (Map<String, Object> g : gains)
			{
				if (skill.equals(g.get("skill")))
				{
					sum += (Long) g.get("xp");
				}
			}
		}
		return sum;
	}

	private static GameStateChanged gameState(GameState s)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		return e;
	}

	// ---- bank first open ----

	private static AccountConnectPlugin bankPlugin(Client client) throws Exception
	{
		AccountConnectPlugin p = plug();
		inject(p, "client", client);
		return p;
	}

	@Test
	public void firstOpenWithUnloadedBankEmitsNoDeposit() throws Exception
	{
		Client client = mock(Client.class);
		AccountConnectPlugin p = bankPlugin(client);
		ItemContainer full = container(new int[]{995, 1_000_000}, new int[]{20997, 1}, new int[]{385, 200});
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(null);	// unloaded at open
		p.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(full);
		p.onItemContainerChanged(new ItemContainerChanged(BANK, full));	// contents arrive = baseline only
		p.handleBankWidgetClosed(BANK_GROUP);
		assertEquals(0, ofType(p, "bank_deposit").size());
		assertEquals(0, ofType(p, "bank_withdraw").size());
	}

	@Test
	public void firstOpenWithEmptyBankContainerEmitsNoDepositThenDiffsNextChange() throws Exception
	{
		Client client = mock(Client.class);
		AccountConnectPlugin p = bankPlugin(client);
		ItemContainer empty = container();
		ItemContainer full = container(new int[]{995, 1_000_000}, new int[]{20997, 1});
		ItemContainer plusSharks = container(new int[]{995, 1_000_000}, new int[]{20997, 1}, new int[]{385, 50});
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(empty);	// empty at open
		p.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);
		p.onItemContainerChanged(new ItemContainerChanged(BANK, full));	// baseline
		p.onItemContainerChanged(new ItemContainerChanged(BANK, plusSharks));	// the real deposit
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(plusSharks);
		p.handleBankWidgetClosed(BANK_GROUP);
		List<Map<String, Object>> dep = ofType(p, "bank_deposit");
		assertEquals(1, dep.size());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) dep.get(0).get("items");
		assertEquals("only the real deposit, never the whole bank", 1, items.size());
		assertEquals(385, items.get(0).get("id"));
		assertEquals(50L, items.get(0).get("qty"));
		assertEquals(0, ofType(p, "bank_withdraw").size());
	}

	@Test
	public void bankThatNeverLoadsEmitsNothingOnClose() throws Exception
	{
		Client client = mock(Client.class);
		AccountConnectPlugin p = bankPlugin(client);
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(null);
		p.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);
		ItemContainer full = container(new int[]{995, 1_000_000});
		when(client.getItemContainer(InventoryID.BANK)).thenReturn(full);	// readable only at close
		p.handleBankWidgetClosed(BANK_GROUP);
		assertTrue(p.pendingEvents.isEmpty());
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
