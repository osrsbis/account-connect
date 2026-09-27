package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Store-transfer counterparty capture — nearby[] (players seen over a shop visit), world, loc attached to
 * store events. Unmasks the untracked receiver of the general-store method: a seller lists an item cheap into
 * the shared store, an untracked player standing there buys it out (no trade event). The receiver is in the
 * nearby set — presence + persistence + proximity finger them. Logic proven against mocked players; real
 * WorldView timing is field-verified on a live shop visit.
 */
public class StoreNearbyCaptureTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";

	private static AccountConnectConfig onConfig()
	{
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TEST_TOKEN;
			}
		};
	}

	private static Player player(String name, int x, int y, int cb)
	{
		Player p = mock(Player.class);
		when(p.getName()).thenReturn(name);
		when(p.getWorldLocation()).thenReturn(new WorldPoint(x, y, 0));
		when(p.getCombatLevel()).thenReturn(cb);
		return p;
	}

	/** plugin wired to a client at self-loc, given nearby players list + world + tick. */
	private static AccountConnectPlugin plugin(Player self, List<Player> players, int world, int tick) throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		Client client = mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(self);
		when(client.getPlayers()).thenReturn(players);
		when(client.getWorld()).thenReturn(world);
		when(client.getTickCount()).thenReturn(tick);
		inject(plugin, "client", client);
		return plugin;
	}

	@Test
	public void accumulateTracksClosestApproachAndPersistence() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player b = mock(Player.class);
		when(b.getName()).thenReturn("Receiver");
		when(b.getCombatLevel()).thenReturn(126);
		Client client = mock(Client.class);
		when(client.getLocalPlayer()).thenReturn(self);
		List<Player> players = new ArrayList<>();
		players.add(self);
		players.add(b);
		when(client.getPlayers()).thenReturn(players);

		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", onConfig());
		inject(plugin, "client", client);

		// tick 10: Receiver 3 tiles east
		when(b.getWorldLocation()).thenReturn(new WorldPoint(3167, 3486, 0));
		when(client.getTickCount()).thenReturn(10);
		plugin.accumulateShopNearby();
		// tick 13: Receiver steps adjacent (1 tile)
		when(b.getWorldLocation()).thenReturn(new WorldPoint(3165, 3486, 0));
		when(client.getTickCount()).thenReturn(13);
		plugin.accumulateShopNearby();

		@SuppressWarnings("unchecked")
		Map<String, int[]> seen = (Map<String, int[]>) field(plugin, "shopVisitNearby");
		int[] rec = seen.get("Receiver");
		assertEquals("closest approach kept", 1, rec[0]);
		assertEquals("first tick", 10, rec[1]);
		assertEquals("last tick", 13, rec[2]);
		assertEquals("combat level", 126, rec[3]);
	}

	@Test
	public void storeContextAttachesNearbyWorldLocNearestFirst() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player near = player("Adjacent", 3165, 3486, 100);	// dist 1
		Player far = player("Bystander", 3170, 3486, 50);	// dist 6
		List<Player> players = new ArrayList<>();
		players.add(self);
		players.add(far);
		players.add(near);
		AccountConnectPlugin plugin = plugin(self, players, 302, 20);

		plugin.accumulateShopNearby();		// build the visit set
		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(fields);

		assertEquals(302, fields.get("world"));
		assertEquals(java.util.Arrays.asList(3164, 3486, 0), fields.get("loc"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nearby = (List<Map<String, Object>>) fields.get("nearby");
		assertEquals(2, nearby.size());
		assertEquals("nearest first", "Adjacent", nearby.get(0).get("rsn"));
		assertEquals(1, nearby.get(0).get("dist"));
		assertEquals("Bystander", nearby.get(1).get("rsn"));
		assertEquals(6, nearby.get(1).get("dist"));
	}

	@Test
	public void selfAndNamelessExcluded() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player nameless = mock(Player.class);
		when(nameless.getName()).thenReturn(null);
		when(nameless.getWorldLocation()).thenReturn(new WorldPoint(3165, 3486, 0));
		List<Player> players = new ArrayList<>();
		players.add(self);		// self must be excluded
		players.add(nameless);	// unnamed (rendering) player excluded
		AccountConnectPlugin plugin = plugin(self, players, 302, 5);

		plugin.accumulateShopNearby();
		@SuppressWarnings("unchecked")
		Map<String, int[]> seen = (Map<String, int[]>) field(plugin, "shopVisitNearby");
		assertTrue("no self, no nameless", seen.isEmpty());
	}

	@Test
	public void liveSnapshotBackfillsWhenAccumulatorEmpty() throws Exception
	{
		// store event fires before any tick accumulated -> nearby[] falls back to a live snapshot.
		Player self = player("Seller", 3164, 3486, 90);
		Player near = player("Walker", 3166, 3486, 70);	// dist 2
		List<Player> players = new ArrayList<>();
		players.add(self);
		players.add(near);
		AccountConnectPlugin plugin = plugin(self, players, 308, 1);

		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(fields);	// no accumulate() first

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> nearby = (List<Map<String, Object>>) fields.get("nearby");
		assertEquals(1, nearby.size());
		assertEquals("Walker", nearby.get(0).get("rsn"));
		assertEquals(2, nearby.get(0).get("dist"));
		assertEquals("live snapshot carries offsets", 2, nearby.get(0).get("dx"));
	}

	@Test
	public void noPlayersNoNearbyStillCarriesWorldLoc() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		List<Player> players = new ArrayList<>();
		players.add(self);
		AccountConnectPlugin plugin = plugin(self, players, 302, 5);

		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(fields);

		assertNull("no nearby field when nobody around", fields.get("nearby"));
		assertEquals(302, fields.get("world"));
		assertFalse("world/loc still present", fields.isEmpty());
	}

	// ---- nearby_at_tx: the TRANSACTION-MOMENT snapshot (was completely untested until 2026-09-03) ----

	/**
	 * nearby_at_tx is the whole point of the field: nearby[] is everyone seen across the WHOLE visit,
	 * which over a long visit accumulates passers-by. nearby_at_tx is who was standing there at the
	 * instant the item moved. On production the two differ on roughly 1 event in 10, and the narrower
	 * list is the one that names a receiver — so a bug that made them identical would destroy the
	 * feature's value while every existing assertion still passed.
	 */
	@Test
	public void nearbyAtTxIsTheTransactionMomentNotTheWholeVisit() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player receiver = player("Receiver", 3164, 3486, 126);
		Player passerby = player("PasserBy", 3170, 3490, 60);

		// Visit accumulator saw BOTH — receiver and a passer-by who has since walked off.
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self, receiver), 311, 100);
		java.util.Map<String, int[]> visit = new java.util.LinkedHashMap<>();
		visit.put("Receiver", new int[]{0, 90, 100, 126});
		visit.put("PasserBy", new int[]{6, 90, 92, 60});
		inject(plugin, "shopVisitNearby", visit);

		// At the transaction moment only the receiver is present.
		inject(plugin, "nearbyAtTx", plugin.nearbyPlayersSnapshot(24));

		java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(fields);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> visitWide = (List<Map<String, Object>>) fields.get("nearby");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> atTx = (List<Map<String, Object>>) fields.get("nearby_at_tx");

		assertNotNull("nearby_at_tx must be attached when a snapshot was taken", atTx);
		assertEquals("the visit-wide list keeps everyone seen", 2, visitWide.size());
		assertEquals("the transaction-moment list holds only who was there then", 1, atTx.size());
		assertEquals("Receiver", atTx.get(0).get("rsn"));
	}

	/**
	 * The field must be CONSUMED, not left set. It is a per-transaction snapshot, so a stale value
	 * leaking into the next store event would attach the previous transaction's bystanders to it —
	 * the exact shape of a false accusation.
	 */
	@Test
	public void nearbyAtTxIsClearedAfterItIsAttached() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player other = player("Other", 3164, 3486, 100);
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self, other), 311, 100);
		inject(plugin, "nearbyAtTx", plugin.nearbyPlayersSnapshot(24));

		java.util.Map<String, Object> first = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(first);
		assertNotNull("first event carries the snapshot", first.get("nearby_at_tx"));

		java.util.Map<String, Object> second = new java.util.LinkedHashMap<>();
		plugin.addStoreContextFields(second);
		assertNull("a second event must NOT inherit the previous transaction's snapshot",
			second.get("nearby_at_tx"));
	}

	// ---- store_taken: the shop-stock counterparty inference (was completely untested) ----

	/**
	 * store_taken fires when the stock of an item THIS VISIT SOLD falls — someone bought it out of the
	 * dead drop. Only sold items are watched, because our own BUY also lowers stock and must never be
	 * reported as a taker.
	 */
	@Test
	public void storeTakenFiresOnlyForItemsThisVisitSold() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player taker = player("Taker", 3164, 3486, 126);
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self, taker), 412, 200);

		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>();
		sold.add(20997);						// we sold this
		inject(plugin, "soldThisVisit", sold);
		java.util.Map<Integer, Integer> baseline = new java.util.LinkedHashMap<>();
		baseline.put(20997, 1);					// our item, stock 1
		baseline.put(29415, 43);				// an unrelated shop item
		inject(plugin, "shopStock", baseline);

		// Both fall: ours to 0 (a taker), the unrelated one to 42 (nothing to do with us).
		plugin.handleShopStockChanged(shopWith(new int[][]{{29415, 42}}));

		List<Map<String, Object>> events = plugin.pendingEvents;
		long taken = events.stream().filter(e -> "store_taken".equals(e.get("type"))).count();
		assertEquals("exactly one store_taken, for the SOLD item only", 1, taken);
		Map<String, Object> ev = events.stream()
			.filter(e -> "store_taken".equals(e.get("type"))).findFirst().orElseThrow(AssertionError::new);
		assertEquals(20997, ev.get("item"));
		assertEquals("stock fell by one", 1, ev.get("qty"));
	}

	/**
	 * A store_taken during a capturing visit marks a moment on the visit clip, so the clip keeps the
	 * seconds around the customer buying our item out. Without the call the moment is lost to the
	 * 1fps baseline.
	 */
	@Test
	public void storeTakenMarksAMomentOnTheVisitClip() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self), 412, 200);
		StoreVisitClip clip = new StoreVisitClip(AccountConnectPlugin.MAX_CLIP_FRAMES,
			AccountConnectPlugin.MAX_CLIP_BURST_BYTES, AccountConnectPlugin.MAX_CLIP_FRAME_BYTES);
		inject(plugin, "clipVisit", clip);
		inject(plugin, "clipCapturing", true);

		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>();
		sold.add(20997);
		inject(plugin, "soldThisVisit", sold);
		java.util.Map<Integer, Integer> baseline = new java.util.LinkedHashMap<>();
		baseline.put(20997, 1);
		inject(plugin, "shopStock", baseline);

		plugin.handleShopStockChanged(shopWith(new int[][]{}));

		assertEquals("store_taken must mark one moment on the visit clip", 1, clip.momentCount());
	}

	/**
	 * The candidate list is EVIDENCE, not an accusation, and the code is careful to call it
	 * taken_by_candidates. An empty shop must therefore emit the event with NO candidate list at all
	 * rather than an empty one — "we do not know who" must not read as "nobody".
	 */
	@Test
	public void storeTakenOmitsCandidatesWhenNobodyIsPresent() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self), 602, 200);

		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>();
		sold.add(29415);
		inject(plugin, "soldThisVisit", sold);
		java.util.Map<Integer, Integer> baseline = new java.util.LinkedHashMap<>();
		baseline.put(29415, 43);
		inject(plugin, "shopStock", baseline);

		plugin.handleShopStockChanged(shopWith(new int[][]{{29415, 42}}));

		Map<String, Object> ev = plugin.pendingEvents.stream()
			.filter(e -> "store_taken".equals(e.get("type"))).findFirst().orElseThrow(AssertionError::new);
		assertNull("an empty shop must omit the candidate list, never emit an empty one",
			ev.get("taken_by_candidates"));
		assertEquals("the stock movement is still recorded", 29415, ev.get("item"));
	}

	/**
	 * OUR OWN BUY-BACK IS NOT A CUSTOMER (fixed 2026-09-03, found in field testing).
	 *
	 * The seller's own procedure has an explicit emergency step: if the customer cannot buy the item in time,
	 * the seller buys it back. That purchase lowers the shop stock exactly like a customer's, so the
	 * inference named a bystander as the taker. Measured on live events 3914798/3914799: a buy-back
	 * and a store_taken landed in the same second, and the plugin still named a candidate.
	 *
	 * A false counterparty is the worst output this feature can produce — it is evidence that could
	 * reach a dispute.
	 */
	@Test
	public void ourOwnBuybackIsNotReportedAsATaker() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player bystander = player("Bystander", 3164, 3486, 126);
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self, bystander), 412, 200);

		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>();
		sold.add(20997);
		inject(plugin, "soldThisVisit", sold);
		java.util.Map<Integer, Integer> baseline = new java.util.LinkedHashMap<>();
		baseline.put(20997, 1);
		inject(plugin, "shopStock", baseline);

		// We just bought it back ourselves.
		inject(plugin, "lastSelfBuyItem", 20997);
		inject(plugin, "lastSelfBuyAtMs", System.currentTimeMillis());

		plugin.handleShopStockChanged(shopWith(new int[][]{}));

		assertTrue("a self buy-back must emit NO store_taken at all",
			plugin.pendingEvents.stream().noneMatch(e -> "store_taken".equals(e.get("type"))));
	}

	/** The suppression is time-boxed: an OLD self-buy must not mask a later genuine customer. */
	@Test
	public void anOldSelfBuyDoesNotMaskALaterCustomer() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player taker = player("Taker", 3164, 3486, 126);
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self, taker), 412, 200);

		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>();
		sold.add(20997);
		inject(plugin, "soldThisVisit", sold);
		java.util.Map<Integer, Integer> baseline = new java.util.LinkedHashMap<>();
		baseline.put(20997, 1);
		inject(plugin, "shopStock", baseline);

		inject(plugin, "lastSelfBuyItem", 20997);
		inject(plugin, "lastSelfBuyAtMs", System.currentTimeMillis() - 60_000L);	// a minute ago

		plugin.handleShopStockChanged(shopWith(new int[][]{}));

		assertTrue("a stale self-buy must not suppress a real taker",
			plugin.pendingEvents.stream().anyMatch(e -> "store_taken".equals(e.get("type"))));
	}

	/** A stock RISE (the shop's own restock timer) is not someone taking our item. */
	@Test
	public void storeTakenIgnoresRestock() throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self), 412, 200);

		java.util.Set<Integer> sold = new java.util.LinkedHashSet<>();
		sold.add(20997);
		inject(plugin, "soldThisVisit", sold);
		java.util.Map<Integer, Integer> baseline = new java.util.LinkedHashMap<>();
		baseline.put(20997, 1);
		inject(plugin, "shopStock", baseline);

		plugin.handleShopStockChanged(shopWith(new int[][]{{20997, 5}}));	// stock ROSE

		assertTrue("a stock rise must emit nothing",
			plugin.pendingEvents.stream().noneMatch(e -> "store_taken".equals(e.get("type"))));
	}

	/**
	 * F-A1 (rig 2026-09-27): selling a Pot into Varrock GS (default stock 5) raised it to 6 and the shop
	 * normalised it back to 5 within seconds. That emitted store_taken 6->5 with a bystander as candidate:
	 * a false customer. The shop's own normalisation of default stock is not a buy-out.
	 */
	@Test
	public void defaultStockNormalisationIsNotACustomer() throws Exception
	{
		AccountConnectPlugin plugin = shopVisit(new int[][]{{1931, 5}});
		plugin.onMenuOptionClicked(sellClick(1931));
		plugin.handleShopStockChanged(shopWith(new int[][]{{1931, 6}}));	// our sale lands
		plugin.handleShopStockChanged(shopWith(new int[][]{{1931, 5}}));	// the shop normalises
		assertEquals("no store_taken for default-stock normalisation", 0, storeTaken(plugin).size());
	}

	/** A real buy-out of a default-stock item (a fall of more than one) is still reported. */
	@Test
	public void aMultiItemBuyOutOfDefaultStockIsStillReported() throws Exception
	{
		AccountConnectPlugin plugin = shopVisit(new int[][]{{1931, 5}});
		plugin.onMenuOptionClicked(sellClick(1931));
		plugin.handleShopStockChanged(shopWith(new int[][]{{1931, 6}}));
		plugin.handleShopStockChanged(shopWith(new int[][]{{1931, 4}}));
		assertEquals(1, storeTaken(plugin).size());
		assertEquals(2, storeTaken(plugin).get(0).get("qty"));
	}

	/** Default stock bought out to ZERO is a buy-out, never normalisation. */
	@Test
	public void defaultStockTakenToZeroIsStillReported() throws Exception
	{
		AccountConnectPlugin plugin = shopVisit(new int[][]{{1931, 1}});
		plugin.onMenuOptionClicked(sellClick(1931));
		plugin.handleShopStockChanged(shopWith(new int[][]{{1931, 2}}));
		plugin.handleShopStockChanged(shopWith(new int[][]{{1931, 1}}));	// normalisation, suppressed
		plugin.handleShopStockChanged(shopWith(new int[][]{}));			// 1 -> 0: a buy-out
		assertEquals(1, storeTaken(plugin).size());
		assertEquals(0, storeTaken(plugin).get(0).get("stock_after"));
	}

	/** An item the shop does NOT stock (the delivery case) keeps its store_taken for a fall of one. */
	@Test
	public void aNonStockItemFallingByOneIsStillReported() throws Exception
	{
		AccountConnectPlugin plugin = shopVisit(new int[][]{{1931, 5}});
		plugin.onMenuOptionClicked(sellClick(20997));
		plugin.handleShopStockChanged(shopWith(new int[][]{{1931, 5}, {20997, 2}}));
		plugin.handleShopStockChanged(shopWith(new int[][]{{1931, 5}, {20997, 1}}));
		assertEquals(1, storeTaken(plugin).size());
		assertEquals(20997, storeTaken(plugin).get(0).get("item"));
	}

	/** An open shop whose first container read is the given stock, with a bystander present. */
	private AccountConnectPlugin shopVisit(int[][] stock) throws Exception
	{
		Player self = player("Seller", 3164, 3486, 90);
		Player bystander = player("Bystander", 3164, 3486, 126);
		AccountConnectPlugin plugin = plugin(self, java.util.Arrays.asList(self, bystander), 308, 200);
		inject(plugin, "shopOpen", true);
		plugin.handleShopStockChanged(shopWith(stock));		// baseline read at open
		return plugin;
	}

	private static List<Map<String, Object>> storeTaken(AccountConnectPlugin plugin)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> e : plugin.pendingEvents)
		{
			if ("store_taken".equals(e.get("type")))
			{
				out.add(e);
			}
		}
		return out;
	}

	private static net.runelite.api.events.MenuOptionClicked sellClick(int itemId)
	{
		net.runelite.api.events.MenuOptionClicked e = mock(net.runelite.api.events.MenuOptionClicked.class);
		when(e.getMenuOption()).thenReturn("Sell 1");
		when(e.getItemId()).thenReturn(itemId);
		return e;
	}

	/**
	 * Build a shop container holding the given {itemId, qty} pairs.
	 *
	 * net.runelite.api.Item is FINAL, so Mockito cannot mock it — construct real ones. (The mock
	 * version of this helper failed with "Cannot mock/spy because - final class", 2026-09-03.)
	 */
	private static net.runelite.api.ItemContainer shopWith(int[][] pairs)
	{
		net.runelite.api.ItemContainer c = mock(net.runelite.api.ItemContainer.class);
		net.runelite.api.Item[] items = new net.runelite.api.Item[pairs.length];
		for (int i = 0; i < pairs.length; i++)
		{
			items[i] = new net.runelite.api.Item(pairs[i][0], pairs[i][1]);
		}
		when(c.getItems()).thenReturn(items);
		return c;
	}

	private static Object field(AccountConnectPlugin plugin, String name) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private static void inject(AccountConnectPlugin plugin, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
