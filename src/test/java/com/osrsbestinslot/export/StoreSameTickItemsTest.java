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
 * Two different items bought in one tick (2026-09-26, Torva full helm + amulet of fury). The single store
 * pending slot kept only the last click, so the fury got one store_buy without gp_total and the helm got no
 * event at all. Each item now has its own pending, driven here through the real click and inventory events.
 */
public class StoreSameTickItemsTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int COINS = 995;
	private static final int HELM = 26382;
	private static final int FURY = 6585;

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
		inventory = new Item[]{new Item(COINS, 300_000_000)};
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void buy(int item)
	{
		MenuOptionClicked click = mock(MenuOptionClicked.class);
		when(click.getMenuOption()).thenReturn("Buy 1");
		when(click.getItemId()).thenReturn(item);
		plugin.onMenuOptionClicked(click);
	}

	/** The server's inventory update: these items and this coin count. */
	private void inventoryNow(long coins, int... items)
	{
		List<Item> rows = new ArrayList<>();
		rows.add(new Item(COINS, (int) coins));
		for (int item : items) rows.add(new Item(item, 1));
		inventory = rows.toArray(new Item[0]);
		ItemContainer changed = client.getItemContainer(InventoryID.INVENTORY);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.INV, changed));
	}

	private List<Map<String, Object>> storeBuys()
	{
		return plugin.pendingEvents.stream().filter(e -> "store_buy".equals(e.get("type"))).collect(Collectors.toList());
	}

	@Test
	public void twoItemsInOneInventoryUpdateGiveTwoEventsAndNoGuessedPrice()
	{
		buy(HELM);
		buy(FURY);					// same tick
		tick++;
		inventoryNow(300_000_000L - 216_042_420L - 2_846_161L, HELM, FURY);

		List<Map<String, Object>> buys = storeBuys();
		assertEquals("one store_buy per item", 2, buys.size());
		assertEquals(HELM, buys.get(0).get("item"));
		assertEquals(FURY, buys.get(1).get("item"));
		for (Map<String, Object> e : buys)
		{
			assertEquals(1, e.get("qty"));
			assertFalse("one coin change cannot be split between two items", e.containsKey("gp_total"));
		}
		assertEquals(0, plugin.storePendings.size());
	}

	@Test
	public void twoItemsInSeparateInventoryUpdatesEachGetTheirOwnGpTotal()
	{
		buy(HELM);
		buy(FURY);					// same tick
		tick++;
		inventoryNow(300_000_000L - 216_042_420L, HELM);
		inventoryNow(300_000_000L - 216_042_420L - 2_846_161L, HELM, FURY);

		List<Map<String, Object>> buys = storeBuys();
		assertEquals(2, buys.size());
		assertEquals(HELM, buys.get(0).get("item"));
		assertEquals(216_042_420L, buys.get(0).get("gp_total"));
		assertEquals(FURY, buys.get(1).get("item"));
		assertEquals("the fury's own coins, not the helm's as well", 2_846_161L, buys.get(1).get("gp_total"));
	}

	@Test
	public void aClickThatNeverExecutesExpiresWithoutBlockingTheOtherItem()
	{
		buy(HELM);
		buy(FURY);					// the fury buy fails: out of stock
		tick++;
		inventoryNow(300_000_000L - 216_042_420L, HELM);
		tick += AccountConnectPlugin.STORE_PENDING_MAX_TICKS + 1;
		inventoryNow(300_000_000L - 216_042_420L - 10L, HELM);	// an unrelated later change

		List<Map<String, Object>> buys = storeBuys();
		assertEquals(1, buys.size());
		assertEquals(216_042_420L, buys.get(0).get("gp_total"));
		assertEquals(0, plugin.storePendings.size());
	}

	@Test
	public void shopCloseReportsEveryArmedItem() throws Exception
	{
		buy(HELM);
		buy(FURY);
		plugin.flushStorePendingOnShopClose();
		assertEquals(2, storeBuys().size());
	}
}
