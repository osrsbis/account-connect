package com.osrsbestinslot.export;

import java.util.*;

/** Bounded process-local observations; no identities or payloads and no unique/completeness claim. */
final class QueueDeliveryHealth
{
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList("ge_offer", "ge_progress", "ge_buy",
        "ge_sell", "ge_cancel", "ge_collect", "bank_session", "equip_change", "loot", "kill_count",
        "level_up", "xp_gain", "alch", "pickup", "combat_hit", "combat_npc_end"));
    private static final String[] COUNTERS = {"capture_handoffs", "forced_appends", "fallback_or_refused_handoffs",
        "broker_receipts", "archive_receipts", "retry_response_rows", "rejected_response_rows", "erased_response_rows"};
    private final Map<String,long[]> families = new TreeMap<>();

    private String family(Map<String,Object> event)
    {
        Object type = event.get("type");
        if (TYPES.contains(type)) return (String)type;
        Object kind = event.get("state_kind");
        if ("own_vars".equals(type) && OwnVarsEvent.supports(event.get("var_family")))
            return "own_vars_" + event.get("var_family");
        if ("own_state".equals(type) && Arrays.asList("skill_goals", "rune_pouch", "bank_tabs").contains(kind))
            return "own_state_" + kind;
        return null;
    }
    private void count(Map<String,Object> event, int counter)
    {
        String family = family(event); if (family == null) return;
        long[] values = families.computeIfAbsent(family, ignored -> new long[COUNTERS.length]);
        if (values[counter] < Long.MAX_VALUE) values[counter]++;
    }
    synchronized void handoff(Map<String,Object> event) { count(event, 0); }
    synchronized void forced(Map<String,Object> event) { count(event, 1); }
    synchronized void undurable(Map<String,Object> event) { count(event, 2); }
    synchronized void response(List<Map<String,Object>> batch, EventQueueProtocol.ReceiptSet receipts)
    {
        if (receipts.outcomes.size() != batch.size() || receipts.stages.size() != batch.size()) return;
        for (int i=0;i<batch.size();i++)
        {
            switch (receipts.outcomes.get(i))
            {
                case DURABLE:
                    if (receipts.stages.get(i) == EventQueueProtocol.Stage.BROKER) count(batch.get(i),3);
                    else if (receipts.stages.get(i) == EventQueueProtocol.Stage.ARCHIVE) count(batch.get(i),4);
                    break;
                case RETRY: count(batch.get(i),5); break;
                case REJECTED: count(batch.get(i),6); break;
                case ERASED: count(batch.get(i),7); break;
                default: break;
            }
        }
    }
    synchronized Map<String,Object> snapshot()
    {
        Map<String,Object> out = new LinkedHashMap<>(), byFamily = new LinkedHashMap<>();
        families.forEach((family,values) -> {
            Map<String,Long> counters = new LinkedHashMap<>();
            for(int i=0;i<COUNTERS.length;i++)counters.put(COUNTERS[i],values[i]);
            byFamily.put(family,counters);
        });
        out.put("schema_v",1);out.put("scope","process_queue_operations");out.put("by_family",byFamily);
        out.put("unique_events",null);out.put("complete_capture",null);out.put("archival_after_broker_receipt","unknown");
        out.put("counts_are_not_a_partition",true);out.put("recovery_may_predate_process",true);
        out.put("network_failures_not_response_rows",true);
        return out;
    }
}
