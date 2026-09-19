package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.WorldChanged;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wave 1 default-on events: equip_change (worn-container diff), the two parsed milestones (kill_count,
 * pet) and world_hop. Logic proven against mocked containers and messages. Real event timing is
 * field-verified on the live client.
 *
 * NO CHAT-CHANNEL TESTS, DELIBERATELY. The original Wave 1 commit also logged private, friends, clan and
 * trade-request lines with the speaker's name. That is the broad chat sweep the hub pushed back on and
 * main deleted in b7a4f9f, so only the two own-account milestones were restored here.
 */
public class Wave1EventsTest
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

	private static ItemContainer worn(int[]... items)
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

	private static AccountConnectPlugin plug() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", onConfig());
		return p;
	}

	// ---- equip_change ----

	@Test
	public void firstEquipChangeOnlyBaselines() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.handleEquipmentChanged(worn(new int[]{1234, 1}));
		assertTrue("first observation = baseline, no event", p.pendingEvents.isEmpty());
	}

	@Test
	public void equipThenUnequipEmitted() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.handleEquipmentChanged(worn(new int[]{1234, 1}));					// baseline: helm
		p.handleEquipmentChanged(worn(new int[]{1234, 1}, new int[]{5678, 1}));	// + weapon
		assertEquals(1, p.pendingEvents.size());
		Map<String, Object> ev = p.pendingEvents.get(0);
		assertEquals("equip_change", ev.get("type"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> equipped = (List<Map<String, Object>>) ev.get("equipped");
		assertEquals(1, equipped.size());
		assertEquals(5678, equipped.get(0).get("id"));
		assertNull(ev.get("unequipped"));

		p.handleEquipmentChanged(worn(new int[]{5678, 1}));					// unequip helm
		Map<String, Object> ev2 = p.pendingEvents.get(1);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> un = (List<Map<String, Object>>) ev2.get("unequipped");
		assertEquals(1, un.size());
		assertEquals(1234, un.get(0).get("id"));
		assertNull(ev2.get("equipped"));
	}

	@Test
	public void equipChangeIsSilentWithoutALinkedToken() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return "";
			}
		});
		p.handleEquipmentChanged(worn(new int[]{1234, 1}));
		p.handleEquipmentChanged(worn(new int[]{1234, 1}, new int[]{5678, 1}));
		assertTrue("no token = no events", p.pendingEvents.isEmpty());
	}

	// ---- parsed milestones ----

	@Test
	public void killCountParsedAndCommaStripped() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.emitParsedMilestones("Your Zulrah kill count is: 1,024");
		assertEquals("exactly one event", 1, p.pendingEvents.size());
		Map<String, Object> e = p.pendingEvents.get(0);
		assertEquals("kill_count", e.get("type"));
		assertEquals("comma-stripped count", 1024L, e.get("count"));
		assertEquals("raw line carried so the count says WHAT was killed",
			"Your Zulrah kill count is: 1,024", e.get("text"));
	}

	@Test
	public void petParsed() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.emitParsedMilestones("You have a funny feeling like you're being followed.");
		assertEquals(1, p.pendingEvents.size());
		assertEquals("pet", p.pendingEvents.get(0).get("type"));
	}

	@Test
	public void ordinaryGameMessageEmitsNothing() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.emitParsedMilestones("Oh dear, you are dead!");
		assertTrue("no milestone false-positive, and no raw chat line either", p.pendingEvents.isEmpty());
	}

	@Test
	public void milestonesAreSilentWithoutALinkedToken() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return "";
			}
		});
		p.emitParsedMilestones("Your Zulrah kill count is: 1,024");
		assertTrue("no token = no events", p.pendingEvents.isEmpty());
	}

	// ---- world_hop ----

	@Test
	public void worldHopEmitsFromToAndSuppressesLogin() throws Exception
	{
		AccountConnectPlugin p = plug();
		Client c = mock(Client.class);
		inject(p, "client", c);
		when(c.getWorld()).thenReturn(302);
		p.onWorldChanged(mock(WorldChanged.class));		// first change = login, suppressed
		assertTrue("login world change suppressed", p.pendingEvents.isEmpty());
		when(c.getWorld()).thenReturn(330);
		p.onWorldChanged(mock(WorldChanged.class));		// real hop 302 -> 330
		Map<String, Object> e = p.pendingEvents.get(0);
		assertEquals("world_hop", e.get("type"));
		assertEquals(302, e.get("from"));
		assertEquals(330, e.get("to"));
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
