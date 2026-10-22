package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

/**
 * Live run 2026-10-22 on D: the last Amulet of accuracy was withdrawn as a note with the bank open, then the
 * bank closed. The client drops the bank container on close, so the next snapshot sent no bank, the server
 * carried the bank of an upload made before the withdraw, and the amulet showed in the bank and as a note in
 * the inventory until the bank was opened again. The snapshot now sends the last bank read of this login.
 */
public class BankAfterCloseTest
{
	private static final int AMULET = 1478;
	private static final int NOTE = 1479;
	private static final int COINS = 995;

	private AccountConnectPlugin plugin;
	private Client client;
	private ItemContainer bank;

	@Before
	public void setUp() throws Exception
	{
		plugin = new AccountConnectPlugin();
		inject("config", new AccountConnectConfig()
		{
		});
		inject("gson", new com.google.gson.Gson());
		ItemManager prices = mock(ItemManager.class);
		when(prices.getItemPrice(anyInt())).thenReturn(10L);
		inject("itemManager", prices);
		client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(mock(Player.class));
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		when(client.getVarps()).thenReturn(new int[5000]);
		when(client.getItemContainer(InventoryID.BANK)).thenAnswer(i -> bank);
		inject("client", client);
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	/** The server's bank update while the bank is open. */
	private void bankNow(Item... items)
	{
		bank = mock(ItemContainer.class);
		when(bank.getItems()).thenReturn(items);
		when(bank.getId()).thenReturn(net.runelite.api.gameval.InventoryID.BANK);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.BANK, bank));
	}

	private void closeBank()
	{
		bank = null;
	}

	private void gameState(GameState s)
	{
		GameStateChanged gs = new GameStateChanged();
		gs.setGameState(s);
		plugin.onGameStateChanged(gs);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> snapshot() throws Exception
	{
		Method build = AccountConnectPlugin.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		return (Map<String, Object>) build.invoke(plugin);
	}

	@SuppressWarnings("unchecked")
	private List<Object> bankIds(Map<String, Object> snap)
	{
		List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) snap.get("bank")).get("items");
		return items.stream().map(m -> m.get("id")).collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	private Object bankSynced(Map<String, Object> snap)
	{
		return ((Map<String, Object>) snap.get("capture_flags")).get("bank_synced");
	}

	@Test
	public void aClosedBankStillSendsItsLastReadAfterAWithdraw() throws Exception
	{
		bankNow(new Item(COINS, 30000), new Item(AMULET, 1));
		bankNow(new Item(COINS, 30000));				// Withdraw-1 as a note: the amulet leaves the bank
		closeBank();

		Map<String, Object> snap = snapshot();
		assertEquals("the bank is still known after the close", true, bankSynced(snap));
		assertEquals("the withdrawn amulet is not in the bank any more", List.of(COINS), bankIds(snap));
		assertNotNull("the read keeps its own time", ((Map<?, ?>) snap.get("bank")).get("observed_at"));
		assertEquals(300000L, ((Map<?, ?>) snap.get("wealth")).get("bank_gp"));
	}

	@Test
	public void anEmptyReadDoesNotEraseTheBank() throws Exception
	{
		bankNow(new Item(COINS, 30000), new Item(AMULET, 1));
		bankNow();										// a transient empty read while the bank loads
		closeBank();
		assertEquals(List.of(COINS, AMULET), bankIds(snapshot()));
	}

	@Test
	public void aLogoutOrHopForgetsTheBank() throws Exception
	{
		bankNow(new Item(COINS, 30000));
		closeBank();
		gameState(GameState.HOPPING);
		gameState(GameState.LOGGED_IN);
		Map<String, Object> snap = snapshot();
		assertEquals("another world or account must never reuse this read", false, bankSynced(snap));
		assertEquals(List.of(), bankIds(snap));
	}

	@Test
	public void anOpenBankIsStillReadLive() throws Exception
	{
		bankNow(new Item(COINS, 30000), new Item(AMULET, 1));
		bank = mock(ItemContainer.class);					// a change the event has not delivered yet
		when(bank.getItems()).thenReturn(new Item[]{new Item(COINS, 1)});
		assertEquals(List.of(COINS), bankIds(snapshot()));
	}

	@Test
	public void noBankThisLoginSendsNoBank() throws Exception
	{
		Map<String, Object> snap = snapshot();
		assertEquals(false, bankSynced(snap));
		assertEquals(null, ((Map<?, ?>) snap.get("wealth")).get("bank_gp"));
	}
}
