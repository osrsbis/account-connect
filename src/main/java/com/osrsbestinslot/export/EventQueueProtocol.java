package com.osrsbestinslot.export;

import com.google.gson.*;
import java.util.*;

/** Public queue-v1 receipts. A receipt proves durable ownership, not a market fill. */
final class EventQueueProtocol
{
    private EventQueueProtocol() {}
    private static final Set<String> GE = new HashSet<>(Arrays.asList(
        "ge_offer", "ge_progress", "ge_buy", "ge_sell", "ge_cancel", "ge_collect"));
    private static final Set<String> HISTORY = new HashSet<>(Arrays.asList(
        "bank_session",
        "equip_change", "loot", "kill_count", "level_up", "xp_gain", "alch", "pickup"));
    static boolean allows(String scope, Map<String,Object> event)
    {
        Object id = event.get("event_id");
        boolean states = "own-state-v1".equals(scope), combat = states || "own-combat-v1".equals(scope);
        return id instanceof String && ((String) id).matches("[a-f0-9]{32}")
            && ((combat || "ge-v1".equals(scope) || "own-history-v1".equals(scope)) && GE.contains(event.get("type"))
                || (combat || "own-history-v1".equals(scope)) && HISTORY.contains(event.get("type"))
                || combat && ("combat_hit".equals(event.get("type")) || "combat_npc_end".equals(event.get("type")))
                || states && "own_state".equals(event.get("type")));
    }
    enum Outcome { RETRY, DURABLE, ERASED, REJECTED }
    enum Stage { NONE, BROKER, ARCHIVE }
    static final class ReceiptSet
    {
        final List<Outcome> outcomes;
        final List<Stage> stages;
        ReceiptSet(int size)
        {
            outcomes = new ArrayList<>(Collections.nCopies(size, Outcome.RETRY));
            stages = new ArrayList<>(Collections.nCopies(size, Stage.NONE));
        }
        static ReceiptSet rejected(int size)
        {
            ReceiptSet r = new ReceiptSet(size); Collections.fill(r.outcomes, Outcome.REJECTED); return r;
        }
    }

    static boolean terminalError(int status, String body)
    {
        // Provider404/timeout/rate-limit and HTML answers stay retryable. A parsed
        // application4xx rejection is a loss, never a durable acknowledgement.
        if (status < 400 || status >= 500 || status == 404 || status == 408 || status == 429) return false;
        try {
            String error = string(new JsonParser().parse(body).getAsJsonObject(), "error");
            return error != null && !error.isEmpty();
        } catch (RuntimeException ignored) { return false; }
    }

    static List<Outcome> receipts(String body, List<Map<String,Object>> batch)
    {
        return parseReceipts(body, batch).outcomes;
    }

    static ReceiptSet parseReceipts(String body, List<Map<String,Object>> batch)
    {
        ReceiptSet parsed = new ReceiptSet(batch.size()); List<Outcome> out = parsed.outcomes;
        try
        {
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            if (!root.has("receipt_version") || !"1".equals(root.get("receipt_version").toString())) return parsed;
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
                if (!seen.add(i)) return new ReceiptSet(batch.size());
                if (!Objects.equals(batch.get(i).get("event_id"), string(row, "event_id"))) continue;
                String status = string(row, "status");
                if ("queued".equals(status) && flag(row, "broker_ack")
                    || "stored".equals(status) && flag(row, "archive_receipt") && flag(row, "d1_receipt"))
                {
                    out.set(i, Outcome.DURABLE);
                    parsed.stages.set(i, "queued".equals(status) ? Stage.BROKER : Stage.ARCHIVE);
                }
                else if ("rejected".equals(status) && string(row, "reason") != null && !string(row, "reason").isEmpty())
                    out.set(i, "erased".equals(string(row, "reason")) ? Outcome.ERASED : Outcome.REJECTED);
            }
        }
        catch (RuntimeException ignored)
        {
            // A malformed/HTML/truncated answer is not permission to discard any memory event.
            return new ReceiptSet(batch.size());
        }
        return parsed;
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
