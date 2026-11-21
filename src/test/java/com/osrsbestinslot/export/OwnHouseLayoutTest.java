package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.VarbitID;
import org.junit.Before;
import org.junit.Test;

public class OwnHouseLayoutTest
{
	private final OwnHouseLayout house = new OwnHouseLayout();
	private Client client;
	private Scene scene;
	private int[][][] chunks;
	private Tile[][][] tiles;
	private int tick, building;

	/** A template chunk as the client packs it: plane << 24 | x / 8 << 14 | y / 8 << 3 | rotation << 1. */
	static int chunk(int plane, int x, int y, int rotation)
	{
		return plane << 24 | (x / 8) << 14 | (y / 8) << 3 | rotation << 1;
	}

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		WorldView view = mock(WorldView.class);
		scene = mock(Scene.class);
		chunks = new int[4][13][13];
		for (int[][] plane : chunks) for (int[] row : plane) Arrays.fill(row, -1);
		chunks[1][6][6] = chunk(0, 1864, 7056, 0);		// the room the player stands in
		chunks[1][7][6] = chunk(0, 1880, 7064, 3);
		chunks[0][6][6] = chunk(0, 1912, 7096, 1);		// a dungeon room
		chunks[2][3][3] = chunk(0, 3200, 3200, 0);		// not a house template: ignored
		tiles = new Tile[4][104][104];
		when(client.getTopLevelWorldView()).thenReturn(view);
		when(view.getScene()).thenReturn(scene);
		when(view.getPlane()).thenReturn(1);
		when(scene.isInstance()).thenReturn(true);
		when(scene.getInstanceTemplateChunks()).thenAnswer(i -> chunks);
		when(scene.getTiles()).thenReturn(tiles);
		Player player = mock(Player.class);
		when(player.getLocalLocation()).thenReturn(new LocalPoint(52 * 128, 52 * 128, -1));
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getTickCount()).thenAnswer(i -> tick);
		when(client.getVarbitValue(VarbitID.POH_BUILDING_MODE)).thenAnswer(i -> building);
		when(client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION)).thenReturn(1);
		// A 2x2 object counted once, a wall and a decoration; a tile outside the house is not read.
		GameObject table = mock(GameObject.class);
		when(table.getId()).thenReturn(13300);
		when(table.getSceneMinLocation()).thenReturn(new Point(50, 50));
		tile(1, 50, 50, table);
		tile(1, 51, 50, table);
		WallObject wall = mock(WallObject.class);
		when(wall.getId()).thenReturn(13100);
		when(tile(1, 56, 50, null).getWallObject()).thenReturn(wall);
		DecorativeObject deco = mock(DecorativeObject.class);
		when(deco.getId()).thenReturn(13200);
		when(tile(0, 50, 50, null).getDecorativeObject()).thenReturn(deco);
		when(tile(2, 10, 10, null).getWallObject()).thenReturn(wall);
	}

	private Tile tile(int z, int x, int y, GameObject g)
	{
		Tile t = tiles[z][x][y] = mock(Tile.class);
		when(t.getSceneLocation()).thenReturn(new Point(x, y));
		when(t.getGameObjects()).thenReturn(new GameObject[]{g});
		return t;
	}

	private void ticks(int n)
	{
		for (int i = 0; i < n; i++, tick++) house.tick(client, 1_000_000L + tick * 600L);
	}

	@Test
	public void decodesEachHouseChunkOnEveryPlane()
	{
		assertTrue(OwnHouseLayout.house(chunk(2, 2047, 7167, 3)));
		assertFalse(OwnHouseLayout.house(-1));
		assertFalse(OwnHouseLayout.house(chunk(0, 3200, 3200, 0)));
		assertEquals(Arrays.asList(Arrays.asList(0, 0, 0, 1912, 7096, 0, 1), Arrays.asList(1, 0, 0, 1864, 7056, 0, 0),
			Arrays.asList(1, 1, 0, 1880, 7064, 0, 3)), OwnHouseLayout.rooms(chunks));
		chunks[1][7][6] = chunk(2, 1880, 7064, 2);
		assertEquals(Arrays.asList(1, 1, 0, 1880, 7064, 2, 2), OwnHouseLayout.rooms(chunks).get(2));
	}

	@Test
	public void aHouseNeverSeenInBuildingModeIsNotRead()
	{
		house.loading();
		ticks(5);
		assertNull(house.layout);
	}

	@Test
	public void buildingModeProvesOwnHouseAndLeavingItReadsAgain()
	{
		building = 1;
		house.loading();
		ticks(1);
		assertNull("not on the first tick", house.layout);
		ticks(1);
		Map<String, Object> read = house.layout;
		assertNotNull(read);
		assertEquals("{\"observed_at\":1000,\"location\":1,\"rooms\":[[0,0,0,1912,7096,0,1],[1,0,0,1864,7056,0,0],"
			+ "[1,1,0,1880,7064,0,3]],\"objects\":{\"13100\":1,\"13200\":1,\"13300\":1}}", new Gson().toJson(read));
		building = 0;					// leaving building mode reloads the same house
		house.loading();
		ticks(2);
		assertTrue("read again", read != house.layout);
		chunks[1][6][6] = -1;			// a scene outside any house ends ownership
		ticks(1);
		chunks[1][6][6] = chunk(0, 1864, 7056, 0);
		Map<String, Object> last = house.layout;
		house.loading();
		ticks(3);
		assertTrue("a house entered without building mode is not read", last == house.layout);
		house.reset();
		assertNull(house.layout);
	}

	@Test
	public void theCostumeRoomStorageInAHouseProvesItIsOwnAndReadsOnTheNextTick()
	{
		chunks[1][6][6] = -1;			// outside a house: the event proves nothing
		house.costumes(client);
		ticks(3);
		assertNull(house.layout);
		chunks[1][6][6] = chunk(0, 1864, 7056, 0);
		ticks(3);
		assertNull("in a house without a proof", house.layout);
		house.costumes(client);
		ticks(1);
		assertNull("not on the tick the storage arrived", house.layout);
		ticks(1);
		assertNotNull("read on the next tick", house.layout);
		assertEquals(3, ((List<?>) house.layout.get("rooms")).size());
		chunks[1][6][6] = -1;
		ticks(1);
		chunks[1][6][6] = chunk(0, 1864, 7056, 0);
		Map<String, Object> last = house.layout;
		house.loading();
		ticks(3);
		assertTrue("ownership ended outside the house", last == house.layout);
	}

	@Test
	public void notInAnInstanceOrWithoutAPlayerNothingHappens()
	{
		when(scene.isInstance()).thenReturn(false);
		building = 1;
		house.loading();
		ticks(3);
		assertNull(house.layout);
		when(client.getTopLevelWorldView()).thenReturn(null);
		ticks(1);
		assertNull(house.layout);
		List<?> none = OwnHouseLayout.rooms(new int[1][2][2]);
		assertEquals("0 is no house chunk", 0, none.size());
	}
}
