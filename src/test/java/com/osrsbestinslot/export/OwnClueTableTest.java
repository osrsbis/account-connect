package com.osrsbestinslot.export;

import java.util.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.MapClue;
import net.runelite.client.plugins.cluescrolls.clues.CoordinateClue;
import org.junit.Test;
import static org.junit.Assert.*;

/** Public game metadata only. No client, profile, account, UI or network access. */
public class OwnClueTableTest
{
    static Map<String,List<Object>> table() throws Exception
    {
        Set<Integer> ids=new TreeSet<>();
        Field maps=MapClue.class.getDeclaredField("CLUES");maps.setAccessible(true);
        for(Object clue:(List<?>)maps.get(null))ids.add(((MapClue)clue).getItemId());
        Field coords=CoordinateClue.class.getDeclaredField("CLUES");coords.setAccessible(true);
        for(Object clue:((Map<?,?>)coords.get(null)).values())ids.add(((CoordinateClue)clue).getItemId());
        Map<String,List<Object>> out=new LinkedHashMap<>();
        for(int id:ids)if(id>0)
        {
            MapClue map=MapClue.forItemId(id);CoordinateClue coordinate=map==null?CoordinateClue.forItemId(id):null;
            WorldPoint point=map!=null?map.getLocation(null):coordinate.getLocation(null);
            assertNotNull(point);out.put(String.valueOf(id),List.of(map!=null?"map":"coordinate",point.getX(),point.getY(),point.getPlane()));
        }
        return out;
    }
    @Test public void checkedInPrimaryTableMatchesPinnedRuntimeAndEveryProjection() throws Exception
    {
        com.google.gson.JsonObject locked=new com.google.gson.JsonParser().parse(new String(getClass().getResourceAsStream("/own-clue-primary-1.13.1.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String,List<Object>> actual=table();assertEquals(173,actual.size());assertEquals(locked.get("table"),new com.google.gson.Gson().toJsonTree(actual));
        for(Map.Entry<String,List<Object>> e:actual.entrySet()) {
            List<Object> a=e.getValue();Map<String,Object> row=Map.of("kind",a.get(0),"x",a.get(1),"y",a.get(2),"plane",a.get(3),"qualification","library_base_location_not_resolved_quest_alternative");
            assertNotNull(OwnStateEvent.projectValue("clue_inventory",Map.of("inventory_map_coordinate_clues",Map.of(e.getKey(),row))));
        }
    }
    @Test public void exportPinnedPublicPrimaryClueTableWithoutClientState() throws Exception
    {
        Map<String,List<Object>> table=table();assertTrue(table.size()>50);assertTrue(table.size()<512);
        assertEquals(List.of("map",3300,3291,0),table.get("12179"));
        String target=System.getenv("OSRSBIS_CLUE_TABLE_EXPORT");
        if(target!=null)
        {
            assertTrue(target.startsWith("/tmp/osrsbis-clue-"));
            Files.writeString(Path.of(target),new com.google.gson.Gson().toJson(Map.of("table",table,
                "beginner",ItemID.TRAIL_CLUE_BEGINNER,"master",ItemID.TRAIL_CLUE_MASTER,"library","RuneLite1.13.1")));
        }
    }
}
