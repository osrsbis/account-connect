package com.osrsbestinslot.export;

import com.google.gson.*;
import java.util.*;

/**
 * Legacy event-id receipts: the D1 receipts of the public bank and own-events journals, negotiated separately from
 * Queue and the staff spool, and the staff spool's own per-id results. One parser serves all; only the journal
 * answers are strict.
 */
final class PublicBankProtocol
{
    static final String SCOPE = "bank-visit-v1";
    /** X-Event-Receipt: the public P0/P1 types, xp_gain and world_hop, each deduped on event_id by event-ingest. */
    static final String OWN_SCOPE = "own-p01-v1";
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "bank_visit_open", "bank_visit_manifest_chunk", "bank_visit_close"));
    private static final Set<String> OWN_TYPES = new HashSet<>(Arrays.asList(("ge_offer ge_progress ge_buy ge_sell"
        + " ge_cancel ge_collect store_buy store_sell trade death login logout loot drop pickup alch ground_removed"
        + " level_up kill_count pet bank_session xp_gain world_hop").split(" ")));
    private static final Set<String> TERMINAL = new HashSet<>(Arrays.asList("stored", "duplicate", "filtered"));
    private PublicBankProtocol() {}

    static boolean handles(String scope) { return SCOPE.equals(scope) || OWN_SCOPE.equals(scope); }

    static boolean allows(String scope, Map<String,Object> event)
    {
        Object id = event.get("event_id");
        return (SCOPE.equals(scope) ? TYPES : OWN_SCOPE.equals(scope) ? OWN_TYPES : Collections.emptySet()).contains(event.get("type"))
            && id instanceof String && ((String)id).matches("[a-f0-9]{32}");
    }

    static EventQueueProtocol.ReceiptSet receipts(String scope, int code, String body, List<Map<String,Object>> batch)
    {
        EventQueueProtocol.ReceiptSet out = new EventQueueProtocol.ReceiptSet(batch.size());
        Map<String,Integer> indices = new HashMap<>();
        for (int i=0;i<batch.size();i++)
        {
            if (!allows(scope, batch.get(i)) || indices.put((String)batch.get(i).get("event_id"),i) != null) return out;
        }
        Map<String,String> results = code >= 200 && code < 300 ? results(body, true) : null;
        if (results != null) for (Map.Entry<String,String> row : results.entrySet())
        {
            Integer i = indices.get(row.getKey());
            // A D1 receipt is not an archive or Queue broker receipt: stages remain NONE.
            if (i != null) out.outcomes.set(i, "filtered".equals(row.getValue())
                ? EventQueueProtocol.Outcome.REJECTED : EventQueueProtocol.Outcome.DURABLE);
        }
        return out;
    }

    /**
     * {"ok":true,"results":[{"event_id":..,"status":..}]} as event_id -> stored, duplicate or filtered; null when the
     * body has no results ARRAY, and then nothing may be settled. strict (bank receipts) also needs ok:true, and one
     * malformed or repeated entry voids the whole answer; the staff spool skips a malformed entry. Unknown statuses
     * and ids that are not 32 hex settle nothing.
     */
    static Map<String,String> results(String body, boolean strict)
    {
        try
        {
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            JsonElement ok = root.get("ok"), rows = root.get("results");
            if (strict && (ok == null || !ok.isJsonPrimitive() || !ok.getAsJsonPrimitive().isBoolean() || !ok.getAsBoolean())
                || rows == null || !rows.isJsonArray()) return null;
            Map<String,String> out = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement element : rows.getAsJsonArray())
            {
                JsonObject row = element.isJsonObject() ? element.getAsJsonObject() : null;
                String id = row == null ? null : EventQueueProtocol.string(row, "event_id");
                String status = row == null ? null : EventQueueProtocol.string(row, "status");
                if (strict && (id == null || status == null || !seen.add(id))) return null;
                if (id != null && status != null && id.matches("[0-9a-f]{32}") && TERMINAL.contains(status)) out.put(id, status);
            }
            return out;
        }
        catch (RuntimeException invalid) { return null; }
    }
}
