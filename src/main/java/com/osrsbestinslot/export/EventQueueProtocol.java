package com.osrsbestinslot.export;

import com.google.gson.*;
import java.util.*;

/** Public memory transport only. A receipt proves durable ownership, not a market fill. */
final class EventQueueProtocol
{
    private EventQueueProtocol() {}
    private static final Set<String> GE = new HashSet<>(Arrays.asList(
        "ge_offer", "ge_progress", "ge_buy", "ge_sell", "ge_cancel", "ge_collect"));
    private static final Set<String> HISTORY = new HashSet<>(Arrays.asList(
        "bank_session", "bank_visit_open", "bank_visit_close", "bank_visit_manifest_chunk",
        "equip_change", "loot", "kill_count", "level_up", "xp_gain", "alch", "pickup"));
    static boolean allows(String scope, Map<String,Object> event)
    {
        Object id = event.get("event_id");
        return id instanceof String && ((String) id).matches("[a-f0-9]{32}")
            && (("ge-v1".equals(scope) || "own-history-v1".equals(scope)) && GE.contains(event.get("type"))
                || "own-history-v1".equals(scope) && HISTORY.contains(event.get("type")));
    }
    enum Outcome { RETRY, DURABLE, ERASED }

    static List<Outcome> receipts(String body, List<Map<String,Object>> batch)
    {
        List<Outcome> out = new ArrayList<>(Collections.nCopies(batch.size(), Outcome.RETRY));
        try
        {
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            if (!root.has("receipt_version") || !"1".equals(root.get("receipt_version").toString())) return out;
            JsonArray rows = root.getAsJsonArray("results");
            Set<Integer> seen = new HashSet<>();
            for (JsonElement element : rows)
            {
                JsonObject row = element.getAsJsonObject();
                JsonElement rawIndex = row.get("index");
                if (rawIndex == null || !rawIndex.isJsonPrimitive() || !rawIndex.getAsJsonPrimitive().isNumber()) continue;
                int i = rawIndex.getAsInt();
                if (i < 0 || i >= batch.size() || !Integer.toString(i).equals(rawIndex.toString())) continue;
                // Duplicate/contradictory rows invalidate the response; no ambiguous event is settled.
                if (!seen.add(i)) return new ArrayList<>(Collections.nCopies(batch.size(), Outcome.RETRY));
                if (!Objects.equals(batch.get(i).get("event_id"), string(row, "event_id"))) continue;
                String status = string(row, "status");
                if ("queued".equals(status) && flag(row, "broker_ack")
                    || "stored".equals(status) && flag(row, "archive_receipt") && flag(row, "d1_receipt"))
                    out.set(i, Outcome.DURABLE);
                else if ("rejected".equals(status) && "erased".equals(string(row, "reason"))) out.set(i, Outcome.ERASED);
            }
        }
        catch (RuntimeException ignored)
        {
            // A malformed/HTML/truncated answer is not permission to discard any memory event.
            return new ArrayList<>(Collections.nCopies(batch.size(), Outcome.RETRY));
        }
        return out;
    }
    private static String string(JsonObject row, String key)
    {
        JsonElement v = row.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
    }
    private static boolean flag(JsonObject row, String key)
    {
        JsonElement v = row.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }
}
