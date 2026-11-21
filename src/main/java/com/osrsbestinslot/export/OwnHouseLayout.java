package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.VarbitID;

/**
 * The player's own house as loaded: its rooms from the instance templates and the ids of what stands in it. Only a
 * house where building mode or the costume room storage was seen counts as one's own (neither opens in another's);
 * that stays true while the player is in a house, so leaving building mode keeps it, and the first scene outside a
 * house ends it. Client thread.
 */
final class OwnHouseLayout
{
    private boolean own, building, due;
    private int dueTick = -1;
    volatile Map<String, Object> layout;

    void reset()
    {
        own = building = due = false;
        layout = null;
    }

    /** A scene is loading: read the house on the tick after it has settled. */
    void loading()
    {
        due = true;
        dueTick = -1;
    }

    /** The costume room storage arrived: in a house, it is one's own; read it on the next tick. */
    void costumes(Client client)
    {
        if (chunks(client) == null) return;
        own = due = true;
        dueTick = client.getTickCount() + 1;
    }

    /** The instance template chunks while the player stands in a house, else null. */
    private static int[][][] chunks(Client client)
    {
        WorldView view = client.getTopLevelWorldView();
        Scene scene = view == null ? null : view.getScene();
        int[][][] chunks = scene != null && scene.isInstance() ? scene.getInstanceTemplateChunks() : null;
        Player player = client.getLocalPlayer();
        LocalPoint at = player == null ? null : player.getLocalLocation();
        return chunks == null || at == null || !house(chunks[view.getPlane()][at.getSceneX() >> 3][at.getSceneY() >> 3]) ? null : chunks;
    }

    void tick(Client client, long nowMs)
    {
        int[][][] chunks = chunks(client);
        if (chunks == null)
        {
            own = building = false;
            dueTick = -1;
            return;
        }
        boolean nowBuilding = client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 1;
        own |= nowBuilding;
        due |= building && !nowBuilding;
        building = nowBuilding;
        if (!due || !own) return;
        if (dueTick < 0) dueTick = client.getTickCount() + 1;
        else if (client.getTickCount() >= dueTick)
        {
            layout = read(client, client.getTopLevelWorldView().getScene(), chunks, nowMs / 1000L);
            due = false;
            dueTick = -1;
        }
    }

    /** A template chunk copied from the house room templates (regions 7534-8303, the game's POH area). */
    static boolean house(int chunk)
    {
        int x = chunk >> 14 & 1023, y = chunk >> 3 & 2047;
        return chunk != -1 && x >= 232 && x < 264 && y >= 880 && y < 896;
    }

    /** [plane, x, y] house-relative chunk, then template x, y (tiles), template plane and rotation. */
    static List<List<Integer>> rooms(int[][][] chunks)
    {
        List<List<Integer>> rooms = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (int[][] plane : chunks)
            for (int x = 0; x < plane.length; x++)
                for (int y = 0; y < plane[x].length; y++)
                    if (house(plane[x][y]))
                    {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                    }
        for (int z = 0; z < chunks.length; z++)
            for (int x = 0; x < chunks[z].length; x++)
                for (int y = 0; y < chunks[z][x].length; y++)
                {
                    int c = chunks[z][x][y];
                    if (house(c)) rooms.add(Arrays.asList(z, x - minX, y - minY, (c >> 14 & 1023) * 8, (c >> 3 & 2047) * 8, c >> 24 & 3, c >> 1 & 3));
                }
        return rooms;
    }

    private static Map<String, Object> read(Client client, Scene scene, int[][][] chunks, long now)
    {
        Map<Integer, Integer> objects = new TreeMap<>();
        Tile[][][] tiles = scene.getTiles();
        for (int z = 0; z < tiles.length && z < chunks.length; z++)
            for (int x = 0; x < tiles[z].length; x++)
                for (int y = 0; y < tiles[z][x].length; y++)
                {
                    Tile tile = tiles[z][x][y];
                    if (tile == null || x >> 3 >= chunks[z].length || y >> 3 >= chunks[z][x >> 3].length || !house(chunks[z][x >> 3][y >> 3])) continue;
                    for (GameObject g : tile.getGameObjects())
                        if (g != null && tile.getSceneLocation().equals(g.getSceneMinLocation())) count(objects, g);
                    count(objects, tile.getWallObject());
                    count(objects, tile.getDecorativeObject());
                    count(objects, tile.getGroundObject());
                }
        return Fields.of("observed_at", now, "location", client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION),
            "rooms", rooms(chunks), "objects", objects);
    }

    private static void count(Map<Integer, Integer> objects, TileObject o)
    {
        if (o != null && o.getId() > 0) objects.merge(o.getId(), 1, Integer::sum);
    }
}
