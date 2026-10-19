package com.osrsbestinslot.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Offline QA replay, test classpath only. Reads captured events (one JSON object per line) and a saved
 * Wiki /latest response, then prints one derived activity record. A line may be a wire event, or an
 * own_gameplay snapshot observation row with "collector_session" added; rows go through the real
 * OwnSignalEvent projection, so an invalid row is skipped exactly as the emitter would skip it.
 *
 * args: events.jsonl latest.json latest_available_at_s scope.json
 */
public final class ActivityReplay
{
    private ActivityReplay() {}

    static List<Map<String,Object>> read(List<String> lines)
    {
        Gson gson = new Gson(); List<Map<String,Object>> out = new ArrayList<>();
        for (String line : lines)
        {
            if (line.isBlank()) continue;
            @SuppressWarnings("unchecked") Map<String,Object> e = gson.fromJson(line, Map.class);
            if (e.containsKey("kind") && e.get("collector_session") instanceof String)
            {
                Map<String,Object> row = new LinkedHashMap<>(e);
                // Gson reads numbers as doubles; the projection requires whole numbers, which survive.
                Map<String,Object> signal = OwnSignalEvent.from((String)row.remove("collector_session"), row);
                if (signal == null) continue;
                signal.put("event_id", String.valueOf(row.get("event_id")));
                out.add(gson.fromJson(gson.toJson(signal), Map.class));
            }
            else out.add(e);
        }
        return out;
    }

    public static void main(String[] args) throws Exception
    {
        Gson gson = new Gson();
        List<Map<String,Object>> events = read(Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8));
        byte[] raw = Files.readAllBytes(Paths.get(args[1]));
        String sha = String.format("%064x", new java.math.BigInteger(1, java.security.MessageDigest.getInstance("SHA-256").digest(raw)));
        ActivityPrices prices = ActivityPrices.wikiLatest(gson.fromJson(new String(raw, StandardCharsets.UTF_8), Map.class), sha, Long.parseLong(args[2]));
        @SuppressWarnings("unchecked") Map<String,Object> scope = gson.fromJson(Files.readString(Paths.get(args[3])), Map.class);
        System.out.println(new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(ActivityRecord.derive(scope, events, prices)));
    }
}
