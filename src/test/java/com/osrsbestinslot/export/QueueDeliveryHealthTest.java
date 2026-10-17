package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class QueueDeliveryHealthTest
{
    private static Map<String,Object> event(String type,int id)
    { return new LinkedHashMap<>(Map.of("type",type,"event_id",String.format("%032x",id))); }
    private static String receipt(List<Map<String,Object>> batch)
    {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<batch.size();i++)rows.add(Map.of("index",i,"event_id",batch.get(i).get("event_id"),
            "status",i==0?"queued":"stored","broker_ack",true,"archive_receipt",true,"d1_receipt",true));
        return new Gson().toJson(Map.of("receipt_version",1,"results",rows));
    }
    private static Map<?,?> family(QueueDeliveryHealth h,String type)
    { return (Map<?,?>)((Map<?,?>)h.snapshot().get("by_family")).get(type); }

    @Test public void queuedAndArchivedReceiptsAreDifferentEvidenceWithNoIdentityOrGlobalCompletenessClaim()
    {
        QueueDeliveryHealth h=new QueueDeliveryHealth();Map<String,Object> a=event("ge_progress",1),b=event("own_state",2);
        b.put("state_kind","skill_goals");a.put("account_hash","PRIVATE_ACCOUNT");
        h.handoff(a);h.forced(a);h.handoff(b);h.undurable(b);
        List<Map<String,Object>> batch=Arrays.asList(a,b);h.response(batch,EventQueueProtocol.parseReceipts(receipt(batch),batch));
        assertEquals(1L,family(h,"ge_progress").get("broker_receipts"));assertEquals(0L,family(h,"ge_progress").get("archive_receipts"));
        assertEquals(1L,family(h,"own_state_skill_goals").get("archive_receipts"));
        assertEquals(1L,family(h,"own_state_skill_goals").get("fallback_or_refused_handoffs"));
        assertEquals(0L,family(h,"own_state_skill_goals").get("forced_appends"));
        assertEquals("unknown",h.snapshot().get("archival_after_broker_receipt"));
        assertNull(h.snapshot().get("unique_events"));assertNull(h.snapshot().get("complete_capture"));
        String encoded=new Gson().toJson(h.snapshot());assertFalse(encoded.contains("PRIVATE_ACCOUNT"));assertFalse(encoded.contains("event_id"));
        QueueDeliveryHealth restarted=new QueueDeliveryHealth();restarted.response(batch,EventQueueProtocol.parseReceipts(receipt(batch),batch));
        assertEquals(0L,family(restarted,"ge_progress").get("capture_handoffs"));
        assertEquals(1L,family(restarted,"ge_progress").get("broker_receipts"));
    }
    @Test public void contradictoryReceiptsCountOnlyRetryRowsAndUnknownFamiliesCannotGrowOrLeakCounters()
    {
        QueueDeliveryHealth h=new QueueDeliveryHealth();Map<String,Object> e=event("pickup",1);
        List<Map<String,Object>> batch=List.of(e);
        String bad="{\"receipt_version\":1,\"results\":[{\"index\":0,\"event_id\":\""+e.get("event_id")+"\",\"status\":\"queued\",\"broker_ack\":true},{\"index\":0,\"status\":\"retry\"}]}";
        EventQueueProtocol.ReceiptSet parsed=EventQueueProtocol.parseReceipts(bad,batch);
        assertEquals(List.of(EventQueueProtocol.Stage.NONE),parsed.stages);h.response(batch,parsed);
        h.response(batch,EventQueueProtocol.ReceiptSet.rejected(1));
        assertEquals(1L,family(h,"pickup").get("retry_response_rows"));assertEquals(0L,family(h,"pickup").get("broker_receipts"));
        assertEquals(1L,family(h,"pickup").get("rejected_response_rows"));
        for(int i=0;i<1000;i++)h.handoff(event("PRIVATE_"+i,i));
        Map<String,Object> state=event("own_state",1);state.put("state_kind","PRIVATE_STATE");h.handoff(state);
        assertEquals(1,((Map<?,?>)h.snapshot().get("by_family")).size());
    }
}
