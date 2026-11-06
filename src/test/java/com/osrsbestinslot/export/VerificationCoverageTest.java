package com.osrsbestinslot.export;

import com.google.gson.GsonBuilder;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generates the diagnostic inventory from real code tables, without treating source agreement as live proof. */
public class VerificationCoverageTest
{
    private static void add(Map<String, Map<String,Object>> rows, String id, String label, String source,
        String comparison, String screen, String trigger)
    {
        if (rows.containsKey(id))
        {
            @SuppressWarnings("unchecked") List<String> labels = (List<String>) rows.get(id).get("labels");
            if (!labels.contains(label)) labels.add(label);
            return;
        }
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("labels", new ArrayList<>(Collections.singletonList(label)));
        row.put("source", source); row.put("comparison", comparison); row.put("independent_visible_evidence", screen);
        row.put("trigger", trigger); row.put("live_validation", "not_established_by_source_tests"); rows.put(id, row);
    }

    @Test public void inventoryUsesActualDistinctSourcesAndListsUnobservableActivities() throws Exception
    {
        Map<String,Map<String,Object>> rows = new LinkedHashMap<>();
        for (Skill skill : Skill.values()) if (!"Overall".equalsIgnoreCase(skill.getName()))
            add(rows, "skill:" + skill.name(), skill.getName(), "Skill XP and real level",
                "XP-derived level versus client level", "level dialogue only when displayed; XP is not independently read",
                "random sampling; level event");
        add(rows,"derived:total_level","total_level","sum of real skill levels","client total versus derived sum","none selected","random sampling");
        add(rows,"derived:combat_level","combat_level","Experience.getCombatLevel and combat skills","client combat versus derived value","none selected","random sampling");
        Object[][] orbs = {{"hitpoints_orb", InterfaceID.Orbs.HEALTH_TEXT}, {"prayer_orb", InterfaceID.Orbs.PRAYER_TEXT},
            {"run_energy_orb", InterfaceID.Orbs.RUNENERGY_TEXT}, {"special_energy_orb", InterfaceID.Orbs.SPECENERGY_TEXT},
            {"occupied_slots", InterfaceID.Bankmain.OCCUPIEDSLOTS}};
        for (Object[] row : orbs) add(rows,"widget:"+row[1],(String)row[0],"widget:"+row[1],"held value versus visible widget",
            "selected numeric widget OCR when visible","random sampling");
        for (Object[] row : KillContext.ACTIVITY_COUNTERS)
            add(rows,row[1]+":"+row[2],(String)row[0],row[1]+":"+row[2],"server-set versus client var",
                "not implied by agreeing vars; context discovery after a real change","random sampling; activity counter change");
        String[] accountFields = {"VARP_QUEST_POINTS","VARP_SLAYER_TARGET","VARP_SLAYER_COUNT","ACCOUNT_TYPE_VARBIT","VARBIT_CA_POINTS"};
        for (String name : accountFields)
        {
            Field field=SnapshotBuilder.class.getDeclaredField(name);field.setAccessible(true);int id=field.getInt(null);
            String kind=name.contains("VARBIT")?"varbit":"varp";
            add(rows,kind+":"+id,name,kind+":"+id,"server-set versus client var","none selected","random sampling");
        }
        add(rows,"varp:"+net.runelite.api.VarPlayer.MEMBERSHIP_DAYS,"membership_days","varp:"+net.runelite.api.VarPlayer.MEMBERSHIP_DAYS,
            "server-set versus client var","none selected","random sampling");
        for(int id:new int[]{net.runelite.api.Varbits.SLAYER_POINTS,net.runelite.api.Varbits.SLAYER_TASK_STREAK})
            add(rows,"varbit:"+id,"slayer_progress","varbit:"+id,"server-set versus client var","none selected","random sampling");
        for (int id : AccountReadiness.CA_TASK_VARPS) add(rows,"varp:"+id,"combat_achievement_word","varp:"+id,
            "server-set versus client packed word","none selected; not one target per decoded achievement","random sampling");
        Field diaries=SnapshotBuilder.class.getDeclaredField("DIARIES");diaries.setAccessible(true);
        for (Object[] diary : (Object[][])diaries.get(null)) for(int tier=1;tier<=4;tier++)
            add(rows,"varbit:"+diary[tier],diary[0]+"_tier_"+tier,"varbit:"+diary[tier],
                "server-set versus client diary var","none selected","random sampling");
        for(Object[] row:KillContext.WIDGET_COUNTERS) add(rows,"widget:"+row[1],(String)row[0],"widget:"+row[1],
            "widget-derived number versus OCR","selected numeric widget OCR when visible","random sampling; visible balance");
        for(Object[] row:MinigameLines.SCREENS) add(rows,"widget:"+row[1],row[0]+"."+row[2],"widget:"+row[1],
            "source screen value versus OCR; semantic label provisional","selected result widget OCR","result interface event");
        Object[][][] grammars={MinigameLines.LINES,MinigameLines.DIALOGUE};
        for(int table=0;table<grammars.length;table++) for(int i=0;i<grammars[table].length;i++)
            add(rows,"grammar:"+table+":"+i,grammars[table][i][0]+"."+grammars[table][i][1],
                "MinigameLines."+(table==0?"LINES":"DIALOGUE")+"["+i+"]",
                "parsed fields versus independently read displayed text; semantics still provisional",
                "exact visible game-message or dialogue crop","recognized event");
        for(Object[] row:VerificationDiscovery.RESULT_TEXTS) add(rows,"widget:"+row[1],(String)row[0],"widget:"+row[1],
            "discovery only; no semantic matcher","selected named result text; OCR always UNKNOWN discovery","text/visibility transition, independent of parser");
        for(String family:Arrays.asList("inventory","equipment","location")) add(rows,"family:"+family,family,
            "AccountConnectPlugin."+family+"Results","existing structural comparison","no independent sprite/location reader","random sampling");
        List<Map<String,String>> gaps=new ArrayList<>();
        for(String activity:VerificationDiscovery.UNOBSERVABLE_ACTIVITIES)
        {
            Map<String,String> gap=new LinkedHashMap<>();gap.put("activity",activity);
            gap.put("completion_signal","not found in maintained catalogue");
            gap.put("discovery","bounded unknown-activity game-message sample with region; not completion coverage");gaps.add(gap);
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("schema",1);
        report.put("scope","Distinct implemented inputs/readers, not a count of independently verified activities. Slots/items are not multiplied into areas.");
        report.put("distinct_targets",rows.size());report.put("targets",rows.values());report.put("unobservable_activity_gaps",gaps);
        report.put("discovery_limits",VerificationProbe.inputs("context_fields_per_hour",12,"unknown_fields_per_hour",4,
            "panel_batch_fields",4,"shared_samples_per_hour",60,"shared_frames_per_hour",30));
        assertEquals(8,gaps.size());
        for(Map<String,Object> row:rows.values())
        {
            assertNotNull(row.get("source"));assertNotNull(row.get("independent_visible_evidence"));
            assertEquals("not_established_by_source_tests",row.get("live_validation"));
        }
        Path path=Paths.get("build/reports/verification-coverage.json");Files.createDirectories(path.getParent());
        Files.write(path,new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8));
    }
}
