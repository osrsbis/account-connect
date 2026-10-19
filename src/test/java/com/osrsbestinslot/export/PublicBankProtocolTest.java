package com.osrsbestinslot.export;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicBankProtocolTest
{
    private final String a="a".repeat(32), b="b".repeat(32);
    private List<Map<String,Object>> batch()
    { return Arrays.asList(Map.of("type","bank_visit_open","event_id",a),Map.of("type","bank_visit_close","event_id",b)); }
    private String row(String id,String status) { return "{\"event_id\":\""+id+"\",\"status\":\""+status+"\"}"; }
    private String body(String rows) { return "{\"ok\":true,\"results\":["+rows+"]}"; }
    private void retry(int code,String body)
    { assertEquals(Arrays.asList(EventQueueProtocol.Outcome.RETRY,EventQueueProtocol.Outcome.RETRY),PublicBankProtocol.receipts(code,body,batch()).outcomes); }

    @Test public void partialPerIdReceiptSettlesOnlyTheMatchingRowWithoutClaimingArchive()
    {
        EventQueueProtocol.ReceiptSet r=PublicBankProtocol.receipts(200,body(row(b,"duplicate")),batch());
        assertEquals(Arrays.asList(EventQueueProtocol.Outcome.RETRY,EventQueueProtocol.Outcome.DURABLE),r.outcomes);
        assertEquals(Arrays.asList(EventQueueProtocol.Stage.NONE,EventQueueProtocol.Stage.NONE),r.stages);
    }
    @Test public void filteredIsLossAndStoredIsDurable()
    {
        assertEquals(Arrays.asList(EventQueueProtocol.Outcome.DURABLE,EventQueueProtocol.Outcome.REJECTED),
            PublicBankProtocol.receipts(200,body(row(b,"filtered")+","+row(a,"stored")),batch()).outcomes);
    }
    @Test public void genericMalformedContradictoryAndNonSuccessResponsesCannotAck()
    {
        for(String body:Arrays.asList("{\"ok\":true,\"stored\":2}","<html>error</html>",
            body(row(a,"stored")+","+row(a,"filtered")),body(row(a,"stored")+",null"),
            body(row("c".repeat(32),"stored")),body(row(a,"queued")),"{\"ok\":\"true\",\"results\":[]}")) retry(200,body);
        for(int status:new int[]{400,403,429,500,503}) retry(status,body(row(a,"stored")));
    }
    @Test public void onlyThreeBankTypesWithExactIdsAreEligible()
    {
        for(String type:Arrays.asList("bank_visit_open","bank_visit_manifest_chunk","bank_visit_close"))
            assertTrue(PublicBankProtocol.allows(Map.of("type",type,"event_id",a)));
        for(String type:Arrays.asList("bank_session","trade","ge_buy","login","bank_visit_unknown"))
            assertFalse(PublicBankProtocol.allows(Map.of("type",type,"event_id",a)));
        assertFalse(PublicBankProtocol.allows(Map.of("type","bank_visit_open","event_id","bad")));
    }
}
