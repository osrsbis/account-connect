package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ProjectileMoved;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Capture that lets reporting decide a drop recipient and a general-store buyer mechanically
 * (owner defaults PIO-014 items 2-4). The plugin records evidence; it never names a recipient itself
 * beyond the old one-on-the-tile rule, and a Telekinetic Grab aimed at the pile withdraws even that.
 */
public class RecipientMechanicsTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final int PX = 3200;
	private static final int PY = 3400;

	private static AccountConnectPlugin plugin() throws Exception
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

	private static void inject(Object target, String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Player player(String name, int x, int y, int anim)
	{
		Player pl = mock(Player.class);
		when(pl.getName()).thenReturn(name);
		when(pl.getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
		when(pl.getCombatLevel()).thenReturn(3);
		when(pl.getAnimation()).thenReturn(anim);
		return pl;
	}

	private static Client client(Player me, int tick, Player... others)
	{
		Client c = mock(Client.class);
		List<Player> all = new ArrayList<>();
		all.add(me);
		all.addAll(Arrays.asList(others));
		when(c.getLocalPlayer()).thenReturn(me);
		when(c.getPlayers()).thenReturn(all);
		when(c.getTickCount()).thenReturn(tick);
		when(c.getWorld()).thenReturn(308);
		return c;
	}

	/** A pile we track, removed early (tick 120 is far before its 400 deadline). */
	private static AccountConnectPlugin.DroppedGroundItem pile()
	{
		java.util.Map<String, Object> loc = new java.util.LinkedHashMap<>();
		loc.put("region_id", 12853);
		return new AccountConnectPlugin.DroppedGroundItem(995, 5L, PX, PY, 0, loc, 100, 400);
	}

	private static Map<String, Object> removal(AccountConnectPlugin p)
	{
		for (Map<String, Object> e : p.pendingEvents)
		{
			if ("ground_removed".equals(e.get("type")))
			{
				return e;
			}
		}
		throw new AssertionError("no ground_removed row");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> cands(Map<String, Object> ev)
	{
		return (List<Map<String, Object>>) ev.get("taken_by_candidates");
	}

	// ---- item 1: candidate animation at the despawn tick ----

	@Test
	public void eachCandidateCarriesItsAnimationAndPileOffsets() throws Exception
	{
		AccountConnectPlugin p = plugin();
		Player me = player("Staff", 3210, 3400, -1);
		Player taker = player("Taker", PX, PY, 827);		// floor pickup
		Player idle = player("Idle", PX + 1, PY, -1);
		inject(p, "client", client(me, 120, taker, idle));
		p.emitGroundRemoval(pile(), 120, false);

		List<Map<String, Object>> c = cands(removal(p));
		assertEquals(2, c.size());
		assertEquals("Taker", c.get(0).get("rsn"));
		assertEquals(827, c.get(0).get("anim"));
		assertEquals(0, c.get(0).get("dist"));
		assertEquals("Idle", c.get(1).get("rsn"));
		assertEquals(-1, c.get(1).get("anim"));
		assertEquals(1, c.get(1).get("dx"));
		assertEquals(0, c.get(1).get("dy"));
	}

	@Test
	public void theCandidateListIsBoundedAtEight() throws Exception
	{
		AccountConnectPlugin p = plugin();
		Player me = player("Staff", 3210, 3400, -1);
		Player[] crowd = new Player[20];
		for (int i = 0; i < crowd.length; i++)
		{
			crowd[i] = player("P" + i, PX, PY + (i % 3), -1);
		}
		inject(p, "client", client(me, 120, crowd));
		p.emitGroundRemoval(pile(), 120, false);
		assertEquals(8, cands(removal(p)).size());
	}

	@Test
	public void aGrabCasterOutsideTheRingIsStillACandidate() throws Exception
	{
		// Seven tiles out is beyond the 3-tile ring, but a caster there can take the pile by spell.
		AccountConnectPlugin p = plugin();
		Player me = player("Staff", 3210, 3400, -1);
		Player caster = player("Caster", PX + 7, PY, DropCandidates.TELEGRAB_CAST_ANIMATION);
		Player walker = player("Walker", PX + 7, PY + 1, -1);
		inject(p, "client", client(me, 120, caster, walker));
		p.emitGroundRemoval(pile(), 120, false);
		List<Map<String, Object>> c = cands(removal(p));
		assertNotNull(c);
		assertEquals(1, c.size());
		assertEquals("Caster", c.get(0).get("rsn"));
		assertEquals(7, c.get(0).get("dist"));
	}

	/** Review LOW-1: a crowd near the pile never pushes the grab caster or the player on the tile out. */
	@Test
	public void theCapKeepsThePlayerOnTheTileAndTheCaster()
	{
		List<DropCandidates.Observed> obs = new ArrayList<>();
		for (int i = 0; i < 12; i++)
		{
			obs.add(new DropCandidates.Observed("Crowd" + i, PX + 1 + (i % 2), PY + 1, 0, 3, -1));
		}
		obs.add(new DropCandidates.Observed("Caster", PX + 7, PY, 0, 3, DropCandidates.TELEGRAB_CAST_ANIMATION));
		obs.add(new DropCandidates.Observed("OnTile", PX, PY, 0, 3, -1));
		List<Map<String, Object>> c = DropCandidates.candidatesAt(obs, PX, PY, 0);
		assertEquals(8, c.size());
		assertEquals("OnTile", c.get(0).get("rsn"));
		assertEquals("Caster", c.get(1).get("rsn"));
	}

	@Test
	public void aCasterBeyondGrabRangeIsNotACandidate()
	{
		List<Map<String, Object>> c = DropCandidates.candidatesAt(Arrays.asList(
			new DropCandidates.Observed("Far", PX + 11, PY, 0, 3, DropCandidates.TELEGRAB_CAST_ANIMATION)), PX, PY, 0);
		assertTrue(c.isEmpty());
	}

	// ---- item 1: Telekinetic Grab aimed at the pile's tile ----

	private static void grabProjectile(AccountConnectPlugin p, Client c, Player caster, int tx, int ty)
	{
		Projectile pr = mock(Projectile.class);
		when(pr.getId()).thenReturn(DropCandidates.TELEGRAB_PROJECTILE);
		when(pr.getTargetPoint()).thenReturn(new WorldPoint(tx, ty, 0));
		when(pr.getSourceActor()).thenReturn(caster);
		ProjectileMoved ev = new ProjectileMoved();
		ev.setProjectile(pr);
		p.onProjectileMoved(ev);
		p.onProjectileMoved(ev);	// fires every client cycle; recorded once
	}

	@Test
	public void aGrabAtThePileIsFlaggedWithItsCasterAndBlocksTheOneOnTheTileRule() throws Exception
	{
		AccountConnectPlugin p = plugin();
		Player me = player("Staff", 3210, 3400, -1);
		Player bystander = player("Bystander", PX, PY, -1);
		Player caster = player("Caster", PX + 6, PY + 2, DropCandidates.TELEGRAB_CAST_ANIMATION);
		Client c = client(me, 116, bystander, caster);
		inject(p, "client", c);
		p.trackGroundDrop(995, 5L, PX, PY, 0, null, 100, 400, false);
		grabProjectile(p, c, caster, PX, PY);
		assertEquals(1, p.telegrabSightings.size());

		when(c.getTickCount()).thenReturn(120);
		p.emitGroundRemoval(pile(), 120, false);
		Map<String, Object> ev = removal(p);
		@SuppressWarnings("unchecked")
		Map<String, Object> grab = (Map<String, Object>) ev.get("telegrab");
		assertNotNull("the grab is on the row", grab);
		assertEquals("Caster", grab.get("caster"));
		assertEquals(6, grab.get("caster_dist"));
		assertEquals(4, grab.get("ticks_before"));
		assertEquals(Arrays.asList("projectile"), grab.get("via"));
		assertNull("one player on the tile is no longer enough", ev.get("counterparty_inferred"));
		assertEquals(DropCandidates.STATUS_AMBIGUOUS, ev.get("counterparty_status"));
	}

	@Test
	public void aGrabAtAnotherTileOrLongAgoIsNotFlagged() throws Exception
	{
		AccountConnectPlugin p = plugin();
		Player me = player("Staff", 3210, 3400, -1);
		Player customer = player("Customer", PX, PY, 827);
		Player caster = player("Caster", PX + 6, PY, DropCandidates.TELEGRAB_CAST_ANIMATION);
		Client c = client(me, 100, customer, caster);
		inject(p, "client", c);
		p.trackGroundDrop(995, 5L, PX, PY, 0, null, 100, 400, false);
		p.trackGroundDrop(995, 5L, PX + 2, PY, 0, null, 100, 400, false);
		grabProjectile(p, c, caster, PX, PY);			// at our tile, but 20 ticks before the removal
		when(c.getTickCount()).thenReturn(118);
		grabProjectile(p, c, caster, PX + 2, PY);		// recent, but at the other pile's tile

		p.emitGroundRemoval(pile(), 120, false);
		Map<String, Object> ev = removal(p);
		assertNull(ev.get("telegrab"));
		assertEquals("Customer", ev.get("counterparty_inferred"));
	}

	@Test
	public void aGrabAtAnUntrackedTileIsNeverStored() throws Exception
	{
		AccountConnectPlugin p = plugin();
		Player me = player("Staff", 3210, 3400, -1);
		Player caster = player("Caster", PX + 6, PY, DropCandidates.TELEGRAB_CAST_ANIMATION);
		Client c = client(me, 100, caster);
		inject(p, "client", c);
		grabProjectile(p, c, caster, PX, PY);
		assertTrue("nothing of ours is there", p.telegrabSightings.isEmpty());
	}

	@Test
	public void telegrabSightingsAreBounded() throws Exception
	{
		AccountConnectPlugin p = plugin();
		p.trackGroundDrop(995, 5L, PX, PY, 0, null, 100, 400, false);
		for (int i = 0; i < 100; i++)
		{
			p.recordTelegrabSighting(new DropCandidates.TelegrabSighting(PX, PY, 0, 100, "impact", null, false, PX, PY));
		}
		assertEquals(AccountConnectPlugin.TELEGRAB_SIGHTING_CAP, p.telegrabSightings.size());
	}

	@Test
	public void anImpactWithoutACasterStillFlagsTheGrab()
	{
		Map<String, Object> g = DropCandidates.telegrabAt(Arrays.asList(
			new DropCandidates.TelegrabSighting(PX, PY, 0, 118, "impact", null, false, PX, PY)), PX, PY, 0, 120);
		assertNotNull(g);
		assertNull(g.get("caster"));
		assertEquals(Arrays.asList("impact"), g.get("via"));
	}

	@Test
	public void aGrabOlderThanTheWindowOrAfterTheRemovalIsIgnored()
	{
		int old = 120 - DropCandidates.TELEGRAB_WINDOW_TICKS - 1;
		assertNull(DropCandidates.telegrabAt(Arrays.asList(
			new DropCandidates.TelegrabSighting(PX, PY, 0, old, "impact", null, false, PX, PY)), PX, PY, 0, 120));
		assertNull(DropCandidates.telegrabAt(Arrays.asList(
			new DropCandidates.TelegrabSighting(PX, PY, 0, 121, "impact", null, false, PX, PY)), PX, PY, 0, 120));
		assertNotNull("the window edge still counts", DropCandidates.telegrabAt(Arrays.asList(
			new DropCandidates.TelegrabSighting(PX, PY, 0, old + 1, "impact", null, false, PX, PY)), PX, PY, 0, 120));
	}

	@Test
	public void ourOwnGrabIsMarkedAsSelf()
	{
		Map<String, Object> g = DropCandidates.telegrabAt(Arrays.asList(
			new DropCandidates.TelegrabSighting(PX, PY, 0, 118, "projectile", null, true, PX + 3, PY)), PX, PY, 0, 120);
		assertEquals(true, g.get("caster_self"));
		assertNull(g.get("caster"));
	}

	@Test
	public void aSelfPickupNeverCarriesAGrab() throws Exception
	{
		AccountConnectPlugin p = plugin();
		p.trackGroundDrop(995, 5L, PX, PY, 0, null, 100, 400, false);
		p.recordTelegrabSighting(new DropCandidates.TelegrabSighting(PX, PY, 0, 118, "impact", null, false, PX, PY));
		AccountConnectPlugin.DroppedGroundItem g = pile();
		g.selfPickedUp = true;
		p.emitGroundRemoval(g, 120, false);
		assertNull(removal(p).get("telegrab"));
	}

	// ---- item 2: general-store buyer mechanics ----

	private static final java.util.Map<String, NPC> NPCS = new java.util.HashMap<>();

	/** Face an NPC. Same key = the same NPC object and index; "Name#2" is another NPC with the same name. */
	private static Player withNpc(Player pl, String key)
	{
		NPC n = NPCS.get(key);
		if (n == null)
		{
			n = mock(NPC.class);
			when(n.getName()).thenReturn(key.contains("#") ? key.substring(0, key.indexOf('#')) : key);
			when(n.getIndex()).thenReturn(100 + NPCS.size());
			NPCS.put(key, n);
		}
		when(pl.getInteracting()).thenReturn(n);
		return pl;
	}

	private static ItemContainerStub shop(int item, int qty)
	{
		return new ItemContainerStub(item, qty);
	}

	/** Minimal shop container. */
	private static final class ItemContainerStub
	{
		final net.runelite.api.ItemContainer c;

		ItemContainerStub(int item, int qty)
		{
			c = mock(net.runelite.api.ItemContainer.class);
			when(c.getItems()).thenReturn(qty > 0 ? new net.runelite.api.Item[]{new net.runelite.api.Item(item, qty)}
				: new net.runelite.api.Item[0]);
		}
	}

	@Test
	public void storeTakenRecordsWhatEveryoneInTheShopWasDoing() throws Exception
	{
		AccountConnectPlugin p = plugin();
		Player me = withNpc(player("Staff", 3217, 3415, -1), "Shop keeper");
		Player buyer = withNpc(player("Buyer", 3218, 3415, -1), "Shop keeper");
		Player banker = withNpc(player("Other", 3220, 3416, 832), "Banker");
		Player idle = player("Idle", 3216, 3418, -1);
		Player elsewhere = withNpc(player("SameName", 3219, 3418, -1), "Shop keeper#2");
		Client c = client(me, 205, buyer, banker, idle, elsewhere);
		inject(p, "client", c);
		inject(p, "serverClipsDisabled", true);	// no clip recorder in a unit test
		p.handleActivityWidgetLoaded(net.runelite.api.gameval.InterfaceID.SHOPMAIN);
		assertEquals("Shop keeper", p.shopkeeperName);

		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>(Arrays.asList(20997));
		inject(p, "soldThisVisit", sold);
		java.util.Map<Integer, Integer> sellTicks = new java.util.HashMap<>();
		sellTicks.put(20997, 203);
		inject(p, "lastSellTickThisVisit", sellTicks);
		java.util.Map<Integer, Integer> base = new java.util.LinkedHashMap<>();
		base.put(20997, 1);
		inject(p, "shopStock", base);
		p.handleShopStockChanged(shop(20997, 0).c);

		Map<String, Object> ev = p.pendingEvents.stream().filter(e -> "store_taken".equals(e.get("type")))
			.findFirst().orElseThrow(AssertionError::new);
		assertEquals("Shop keeper", ev.get("shopkeeper"));
		assertEquals(2, ev.get("ticks_since_sell"));
		List<Map<String, Object>> cs = cands(ev);
		assertEquals(4, cs.size());
		Map<String, Map<String, Object>> by = new java.util.HashMap<>();
		for (Map<String, Object> m : cs)
		{
			by.put((String) m.get("rsn"), m);
			assertTrue("every row has a distance", m.get("dist") instanceof Integer);
			assertTrue("every row has an animation", m.containsKey("anim"));
		}
		assertEquals(true, by.get("Buyer").get("with_shopkeeper"));
		assertEquals("Shop keeper", by.get("Buyer").get("interacting_npc"));
		assertEquals(false, by.get("Other").get("with_shopkeeper"));
		assertEquals(832, by.get("Other").get("anim"));
		assertFalse("a player facing nothing has no NPC key", by.get("Idle").containsKey("interacting_npc"));
		assertEquals("a same-named but different NPC is not our shopkeeper", false,
			by.get("SameName").get("with_shopkeeper"));
		assertEquals("Shop keeper", by.get("SameName").get("interacting_npc"));
	}

	@Test
	public void aPlayerTargetIsNeverNamedOnAStoreRow() throws Exception
	{
		AccountConnectPlugin p = plugin();
		Player me = player("Staff", 3217, 3415, -1);
		Player a = player("A", 3218, 3415, -1);
		Player b = player("B", 3219, 3415, -1);
		when(a.getInteracting()).thenReturn(b);
		inject(p, "client", client(me, 10, a, b));
		List<Map<String, Object>> s = p.nearbyPlayersSnapshot(24, true);
		for (Map<String, Object> m : s)
		{
			assertFalse(m.containsKey("interacting_npc"));
		}
	}

	@Test
	public void theDefaultStockSkipStillHolds() throws Exception
	{
		AccountConnectPlugin p = plugin();
		Player me = player("Staff", 3217, 3415, -1);
		Player other = withNpc(player("Other", 3218, 3415, -1), "Shop keeper");
		inject(p, "client", client(me, 10, other));
		inject(p, "soldThisVisit", new java.util.LinkedHashSet<>(Arrays.asList(1931)));
		inject(p, "defaultStockSoldThisVisit", new java.util.HashSet<>(Arrays.asList(1931)));
		java.util.Map<Integer, Integer> base = new java.util.LinkedHashMap<>();
		base.put(1931, 6);
		inject(p, "shopStock", base);
		p.handleShopStockChanged(shop(1931, 5).c);
		assertTrue(p.pendingEvents.stream().noneMatch(e -> "store_taken".equals(e.get("type"))));
	}
}
