package com.osrsbestinslot.export;

import com.google.gson.*;
import java.util.*;

/** Legacy D1 bank receipts, negotiated separately from Queue and the staff spool. */
final class PublicBankProtocol
{
    static final String SCOPE = "bank-visit-v1";
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "bank_visit_open", "bank_visit_manifest_chunk", "bank_visit_close"));
    private PublicBankProtocol() {}

    static boolean allows(Map<String,Object> event)
    {
        Object id = event.get("event_id");
        return TYPES.contains(event.get("type")) && id instanceof String && ((String)id).matches("[a-f0-9]{32}");
    }

    static EventQueueProtocol.ReceiptSet receipts(int code, String body, List<Map<String,Object>> batch)
    {
        EventQueueProtocol.ReceiptSet out = new EventQueueProtocol.ReceiptSet(batch.size());
        if (code < 200 || code >= 300) return out;
        try
        {
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            JsonElement ok = root.get("ok");
            if (ok == null || !ok.isJsonPrimitive() || !ok.getAsJsonPrimitive().isBoolean() || !ok.getAsBoolean()) return out;
            Map<String,Integer> indices = new HashMap<>();
            for (int i=0;i<batch.size();i++)
            {
                if (!allows(batch.get(i)) || indices.put((String)batch.get(i).get("event_id"),i) != null) return out;
            }
            Set<String> seen = new HashSet<>();
            for (JsonElement element : root.getAsJsonArray("results"))
            {
                JsonObject row = element.getAsJsonObject();
                JsonElement id = row.get("event_id"), status = row.get("status");
                if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()
                    || status == null || !status.isJsonPrimitive() || !status.getAsJsonPrimitive().isString())
                    return new EventQueueProtocol.ReceiptSet(batch.size());
                if (!seen.add(id.getAsString())) return new EventQueueProtocol.ReceiptSet(batch.size());
                Integer i = indices.get(id.getAsString());
                if (i == null) continue;
                String value = status.getAsString();
                if ("stored".equals(value) || "duplicate".equals(value))
                    out.outcomes.set(i,EventQueueProtocol.Outcome.DURABLE);
                else if ("filtered".equals(value)) out.outcomes.set(i,EventQueueProtocol.Outcome.REJECTED);
                // A D1 receipt is not an archive or Queue broker receipt: stages remain NONE.
            }
        }
        catch (RuntimeException invalid) { return new EventQueueProtocol.ReceiptSet(batch.size()); }
        return out;
    }
}
