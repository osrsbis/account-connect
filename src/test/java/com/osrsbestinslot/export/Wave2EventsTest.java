package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.StatChanged;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wave 2 coalesced events: xp_gain (per-skill deltas from StatChanged, flushed as one event on a timer /
 * logout so per-action drops don't flood) and region (emitted only on map-region change). Logic proven
 * against mocked events; the tick-timer cadence is field-verified live.
 */
public class Wave2EventsTest
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

	private static AccountConnectPlugin plug() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", onConfig());
		return p;
	}

	private static StatChanged stat(Skill skill, int level, int xp)
	{
		return new StatChanged(skill, xp, level, level);	// StatChanged is final — construct (skill,xp,level,boosted)
	}

	@Test
	public void xpGainCoalescesPerSkillThenFlushesOnce() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.onStatChanged(stat(Skill.WOODCUTTING, 50, 1000));	// baseline WC
		p.onStatChanged(stat(Skill.WOODCUTTING, 50, 1050));	// +50
		p.onStatChanged(stat(Skill.FISHING, 40, 500));		// baseline fishing
		p.onStatChanged(stat(Skill.FISHING, 40, 530));		// +30
		assertTrue("nothing emitted before a flush", p.pendingEvents.isEmpty());

		p.flushXpGain();
		assertEquals(1, p.pendingEvents.size());
		Map<String, Object> ev = p.pendingEvents.get(0);
		assertEquals("xp_gain", ev.get("type"));
		assertEquals(80L, ev.get("total"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> gains = (List<Map<String, Object>>) ev.get("gains");
		assertEquals(2, gains.size());
		assertEquals("Woodcutting", gains.get(0).get("skill"));
		assertEquals(50L, gains.get(0).get("xp"));
		assertEquals("Fishing", gains.get(1).get("skill"));
		assertEquals(30L, gains.get(1).get("xp"));

		p.flushXpGain();	// nothing new accumulated
		assertEquals("empty flush emits nothing", 1, p.pendingEvents.size());
	}

	@Test
	public void firstXpObservationOnlyBaselines() throws Exception
	{
		AccountConnectPlugin p = plug();
		p.onStatChanged(stat(Skill.MINING, 60, 273742));	// first sight of Mining xp
		p.flushXpGain();
		assertTrue("baseline alone yields no xp_gain", p.pendingEvents.isEmpty());
	}

	@Test
	public void regionEmittedOnChangeOnly() throws Exception
	{
		AccountConnectPlugin p = plug();
		Client c = mock(Client.class);
		Player self = mock(Player.class);
		when(c.getLocalPlayer()).thenReturn(self);
		inject(p, "client", c);

		when(self.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
		p.checkRegionChange();	// baseline
		assertTrue("first region only baselines", p.pendingEvents.isEmpty());

		p.checkRegionChange();	// same region again
		assertTrue("no move = no event", p.pendingEvents.isEmpty());

		when(self.getWorldLocation()).thenReturn(new WorldPoint(2500, 3000, 0));	// different region
		p.checkRegionChange();
		assertEquals(1, p.pendingEvents.size());
		Map<String, Object> ev = p.pendingEvents.get(0);
		assertEquals("region", ev.get("type"));
		assertEquals(new WorldPoint(3200, 3200, 0).getRegionID(), ev.get("from"));
		assertEquals(new WorldPoint(2500, 3000, 0).getRegionID(), ev.get("to"));
		assertEquals(2500, ev.get("x"));
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
