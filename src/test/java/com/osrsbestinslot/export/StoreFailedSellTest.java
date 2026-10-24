package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Before;
import org.junit.Test;

/**
 * A sell click the shop refused (live run 2026-10-19: Pot still held, shop stock unchanged) still sent a
 * store_sell, because one armed click resolved on any inventory change inside its window. The retry that
 * really sold sent a second row, so one sale showed as two. A click now resolves only on a change that moved
 * its own item, driven here through the real click and inventory events.
 */
public class StoreFailedSellTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int COINS = 995;
	private static final int POT = 1931;
	private static final int LOBSTER = 379;
	private static final int NATURE = 561;
	private static final int DEATH = 560;

	private AccountConnectPlugin plugin;
	private Client client;
	private int tick = 100;
	private Item[] inventory;

	@Before
	public void setUp() throws Exception
	{
		plugin = new AccountConnectPlugin();
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
		ItemContainer inv = mock(ItemContainer.class);
		when(inv.getItems()).thenAnswer(i -> inventory);
		when(client.getItemContainer(any(InventoryID.class))).thenReturn(inv);
		inject("client", client);
		inject("shopOpen", true);
		inventoryIs(735_512L, POT, LOBSTER);
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void sell(int item)
	{
		MenuOptionClicked click = mock(MenuOptionClicked.class);
		when(click.getMenuOption()).thenReturn("Sell 1<col=ff9040>");
		when(click.getItemId()).thenReturn(item);
		plugin.onMenuOptionClicked(click);
	}

	private void inventoryIs(long coins, int... items)
	{
		List<Item> rows = new ArrayList<>();
		rows.add(new Item(COINS, (int) coins));
		for (int item : items) rows.add(new Item(item, 1));
		inventory = rows.toArray(new Item[0]);
	}

	/** The server's inventory update: these items and this coin count. */
	private void inventoryNow(long coins, int... items)
	{
		inventoryIs(coins, items);
		ItemContainer changed = client.getItemContainer(InventoryID.INVENTORY);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.INV, changed));
	}

	private List<Map<String, Object>> storeSells()
	{
		return plugin.pendingEvents.stream().filter(e -> "store_sell".equals(e.get("type"))).collect(Collectors.toList());
	}

	@Test
	public void aRefusedSellThenARealRetryGiveOneRow()
	{
		sell(POT);
		tick++;
		inventoryNow(735_512L, POT, LOBSTER);			// some inventory update, the Pot is still held
		assertEquals("a sale that moved no item is not a sale", 0, storeSells().size());

		tick += AccountConnectPlugin.STORE_PENDING_MAX_TICKS + 1;
		sell(POT);										// the retry
		tick++;
		inventoryNow(735_512L, LOBSTER);				// sold, the shop paid 0 gp

		List<Map<String, Object>> sells = storeSells();
		assertEquals("one real sale, one row", 1, sells.size());
		assertEquals(POT, sells.get(0).get("item"));
		assertEquals(0, plugin.storePendings.size());
	}

	@Test
	public void anUnrelatedCoinChangeIsNotTheSalePrice()
	{
		sell(LOBSTER);
		tick++;
		inventoryNow(735_512L + 100L, POT, LOBSTER);	// coins moved for another reason, Lobster still held
		inventoryNow(735_512L + 100L + 28L, POT);		// the sale itself

		List<Map<String, Object>> sells = storeSells();
		assertEquals(1, sells.size());
		assertEquals("only the sale's own coins", 28L, sells.get(0).get("gp_total"));
	}

	@Test
	public void aRealSaleStillResolvesOnItsOwnChange()
	{
		sell(LOBSTER);
		tick++;
		inventoryNow(735_512L + 28L, POT);

		List<Map<String, Object>> sells = storeSells();
		assertEquals(1, sells.size());
		assertEquals(LOBSTER, sells.get(0).get("item"));
		assertEquals(28L, sells.get(0).get("gp_total"));
	}

	@Test
	public void aRefusedSellLeavesNoRowAtShopClose()
	{
		sell(POT);
		tick++;
		inventoryNow(735_512L, POT, LOBSTER);
		tick += AccountConnectPlugin.STORE_PENDING_MAX_TICKS + 1;
		plugin.flushStorePendingOnShopClose();
		assertEquals(0, storeSells().size());
		assertFalse(plugin.storePendings.containsKey(POT));
	}

	/**
	 * Live 0.7.55 (2026-10-24): a player buying two runes per world got both in one inventory change. The coins
	 * cannot be split between them, but each rune's own count measured what moved, so each row keeps it.
	 */
	@Test
	public void twoBuysInOneChangeKeepTheirMeasuredQuantity() throws Exception
	{
		inventoryIs(735_512L, POT, LOBSTER);
		buy(NATURE, 50);
		buy(DEATH, 50);
		tick++;
		inventory = new Item[]{new Item(COINS, 735_512 - 7_000), new Item(POT, 1), new Item(LOBSTER, 1),
			new Item(NATURE, 30), new Item(DEATH, 30)};
		ItemContainer changed = client.getItemContainer(InventoryID.INVENTORY);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.INV, changed));

		List<Map<String, Object>> buys = plugin.pendingEvents.stream().filter(e -> "store_buy".equals(e.get("type")))
			.collect(Collectors.toList());
		assertEquals(2, buys.size());
		for (Map<String, Object> b : buys)
		{
			assertFalse("the coins of two buys are never split by a guess", b.containsKey("gp_total"));
			assertEquals(30L, b.get("qty_executed"));
			assertEquals(true, b.get("qty_partial"));
			assertFalse(b.containsKey("unit_price_gp"));
		}
	}

	private void buy(int item, int qty)
	{
		MenuOptionClicked click = mock(MenuOptionClicked.class);
		when(click.getMenuOption()).thenReturn("Buy " + qty + "<col=ff9040>");
		when(click.getItemId()).thenReturn(item);
		plugin.onMenuOptionClicked(click);
	}

	@Test
	public void sellThenCloseInsideTheWindowStillReportsTheSale()
	{
		sell(LOBSTER);
		plugin.flushStorePendingOnShopClose();		// buy- or sell-then-close: the change has not arrived yet
		assertEquals(1, storeSells().size());
		assertFalse("no coins could be measured", storeSells().get(0).containsKey("gp_total"));
	}
}
