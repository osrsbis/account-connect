package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Before;
import org.junit.Test;

/**
 * Live run 2026-10-22 (B4 on D): the last Bat bones (530) were withdrawn with placeholders on. The bank then
 * holds placeholder 19269 at quantity 1, so the snapshot listed 19269 x1 as an item, bank_session showed a
 * deposit of 19269 when the bones left and a withdrawal of it when they came back, and the manifests listed it.
 * A placeholder is now dropped everywhere; it is never mapped to its base item, which the player does not hold.
 */
public class BankPlaceholderTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int BANK_GROUP = 12;
	private static final int BONES = 530;
	private static final int PLACEHOLDER = 19269;
	private static final int COINS = 995;

	private AccountConnectPlugin p;
	private Client client;
	private ItemContainer bank;

	@Before
	public void setUp() throws Exception
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
		inject("gson", new Gson());
		ItemComposition normal = mock(ItemComposition.class);
		when(normal.getPlaceholderTemplateId()).thenReturn(-1);
		ItemComposition held = mock(ItemComposition.class);
		when(held.getPlaceholderTemplateId()).thenReturn(14401);
		ItemManager items = mock(ItemManager.class);
		when(items.getItemComposition(anyInt())).thenAnswer(i -> (int) i.getArgument(0) == PLACEHOLDER ? held : normal);
		when(items.getItemPrice(anyInt())).thenReturn(10L);
		inject("itemManager", items);
		client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);	// no local player: the open sends no snapshot
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		when(client.getVarps()).thenReturn(new int[5000]);
		when(client.getItemDefinition(anyInt())).thenAnswer(i -> items.getItemComposition(i.getArgument(0)));
		when(client.getItemContainer(InventoryID.BANK)).thenAnswer(i -> bank);
		inject("client", client);
		Response.Builder b = new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
			.protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Bank-Evidence", "on");
		p.applyServerPolicy(b.build(), TOKEN);
	}

	private void inject(String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(p, value);
	}

	private static ItemContainer container(int[]... rows)
	{
		ItemContainer c = mock(ItemContainer.class);
		Item[] arr = new Item[rows.length];
		for (int i = 0; i < rows.length; i++)
		{
			arr[i] = new Item(rows[i][0], rows[i][1]);
		}
		when(c.getItems()).thenReturn(arr);
		when(c.getId()).thenReturn(net.runelite.api.gameval.InventoryID.BANK);
		return c;
	}

	private void open(ItemContainer b)
	{
		when(client.getLocalPlayer()).thenReturn(null);
		bank = b;
		p.handleCaptureOnOpenWidgetLoaded(BANK_GROUP);
	}

	private void change(ItemContainer b)
	{
		bank = b;
		p.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.BANK, b));
	}

	private void close()
	{
		when(client.getLocalPlayer()).thenReturn(null);
		p.handleBankWidgetClosed(BANK_GROUP);
		bank = null;
	}

	private List<Map<String, Object>> of(String type)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> e : p.pendingEvents)
		{
			if (type.equals(e.get("type")))
			{
				out.add(e);
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<Long> ids(Object rows)
	{
		List<Long> out = new ArrayList<>();
		for (Object r : (List<Object>) rows)
		{
			Object id = r instanceof long[] ? (Object) ((long[]) r)[0]
				: r instanceof Map ? ((Map<String, Object>) r).get("id") : ((List<Object>) r).get(0);
			out.add(((Number) id).longValue());
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private List<Object> snapshotBankIds() throws Exception
	{
		when(client.getLocalPlayer()).thenReturn(mock(Player.class));
		Method build = AccountConnectPlugin.class.getDeclaredMethod("buildSnapshot");
		build.setAccessible(true);
		Map<String, Object> snap = (Map<String, Object>) build.invoke(p);
		List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) snap.get("bank")).get("items");
		return items.stream().map(m -> m.get("id")).collect(Collectors.toList());
	}

	@Test
	public void aPlaceholderIsNeverAnItemOrAMove() throws Exception
	{
		open(container(new int[]{COINS, 100}, new int[]{BONES, 1}));
		change(container(new int[]{COINS, 100}, new int[]{PLACEHOLDER, 1}));	// the last bones leave
		assertEquals("snapshot while open", List.of(COINS), snapshotBankIds());
		close();
		assertEquals("retained read after the close", List.of(COINS), snapshotBankIds());

		Map<String, Object> session = of("bank_session").get(0);
		assertEquals(List.of((long) BONES), ids(session.get("withdrawn")));
		assertEquals("no phantom deposit of the placeholder", List.of(), ids(session.get("deposited")));

		open(container(new int[]{COINS, 100}, new int[]{PLACEHOLDER, 1}));
		change(container(new int[]{COINS, 100}, new int[]{BONES, 1}));		// the bones go back in
		close();
		Map<String, Object> back = of("bank_session").get(1);
		assertEquals(List.of((long) BONES), ids(back.get("deposited")));
		assertEquals("no phantom withdrawal of the placeholder", List.of(), ids(back.get("withdrawn")));

		for (Map<String, Object> chunk : of("bank_visit_manifest_chunk"))
		{
			assertFalse("manifests never list a placeholder", ids(chunk.get("items")).contains((long) PLACEHOLDER));
		}
	}

	@Test
	public void aPlaceholderIsNeverMappedToItsBaseItem() throws Exception
	{
		open(container(new int[]{COINS, 100}, new int[]{PLACEHOLDER, 1}));
		List<Object> ids = snapshotBankIds();
		assertTrue(ids.contains(COINS));
		assertFalse("the player does not hold the base item", ids.contains(BONES));
		assertFalse(ids.contains(PLACEHOLDER));
	}
}
