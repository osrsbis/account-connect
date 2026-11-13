package com.osrsbestinslot.export;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicBankProtocolTest
{
    private static final String BANK=PublicBankProtocol.SCOPE, OWN=PublicBankProtocol.OWN_SCOPE;
    private final String a="a".repeat(32), b="b".repeat(32);
    private List<Map<String,Object>> batch()
    { return Arrays.asList(Map.of("type","bank_visit_open","event_id",a),Map.of("type","bank_visit_close","event_id",b)); }
    private String row(String id,String status) { return "{\"event_id\":\""+id+"\",\"status\":\""+status+"\"}"; }
    private String body(String rows) { return "{\"ok\":true,\"results\":["+rows+"]}"; }
    private void retry(int code,String body)
    { assertEquals(Arrays.asList(EventQueueProtocol.Outcome.RETRY,EventQueueProtocol.Outcome.RETRY),PublicBankProtocol.receipts(BANK,code,body,batch()).outcomes); }

    @Test public void partialPerIdReceiptSettlesOnlyTheMatchingRowWithoutClaimingArchive()
    {
        EventQueueProtocol.ReceiptSet r=PublicBankProtocol.receipts(BANK,200,body(row(b,"duplicate")),batch());
        assertEquals(Arrays.asList(EventQueueProtocol.Outcome.RETRY,EventQueueProtocol.Outcome.DURABLE),r.outcomes);
        assertEquals(Arrays.asList(EventQueueProtocol.Stage.NONE,EventQueueProtocol.Stage.NONE),r.stages);
    }
    @Test public void filteredIsLossAndStoredIsDurable()
    {
        assertEquals(Arrays.asList(EventQueueProtocol.Outcome.DURABLE,EventQueueProtocol.Outcome.REJECTED),
            PublicBankProtocol.receipts(BANK,200,body(row(b,"filtered")+","+row(a,"stored")),batch()).outcomes);
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
            assertTrue(PublicBankProtocol.allows(BANK,Map.of("type",type,"event_id",a)));
        for(String type:Arrays.asList("bank_session","trade","ge_buy","login","bank_visit_unknown"))
            assertFalse(PublicBankProtocol.allows(BANK,Map.of("type",type,"event_id",a)));
        assertFalse(PublicBankProtocol.allows(BANK,Map.of("type","bank_visit_open","event_id","bad")));
    }

    /** The types event-ingest dedupes on event_id (2026-11-10), apart from bank_visit_*. */
    private static final Set<String> SERVER_DEDUPE=new HashSet<>(Arrays.asList(("own_signal loot alch drop pickup bank_session"
        +" trade death store_buy store_sell ge_offer ge_buy ge_sell ge_collect ge_cancel ge_progress kill_count level_up pet"
        +" login logout ground_removed xp_gain world_hop").split(" ")));

    @Test public void ownEventsAreThePublicP0P1TypesWithXpGainAndWorldHopAndEachIsDedupedByTheServer()
    {
        Set<String> own=new TreeSet<>();
        for(String type:new String[]{"ge_offer","ge_progress","ge_buy","ge_sell","ge_cancel","ge_collect","store_buy","store_sell",
            "store_taken","trade","death","login","logout","loot","drop","pickup","alch","ground_removed","level_up","kill_count","pet",
            "bank_session","bank_visit_open","bank_visit_manifest_chunk","bank_visit_close","drop_trade_clip","drop_trade_clip_final",
            "xp_gain","world_hop","region","equip_change","own_signal","own_state","own_vars","combat_hit","kill_timing","progress"})
            if(PublicBankProtocol.allows(OWN,Map.of("type",type,"event_id",a))) own.add(type);
        Set<String> expected=new TreeSet<>();
        for(String type:SERVER_DEDUPE)
            if(PublicTransport.allowsEvent(type,false)&&(EventPriority.classOf(type)<=EventPriority.P1||type.equals("xp_gain")||type.equals("world_hop")))
                expected.add(type);
        assertEquals(expected,own);
        assertEquals(23,own.size());
        assertTrue(SERVER_DEDUPE.containsAll(own));
        assertFalse(own.contains("own_signal"));
        assertFalse(PublicBankProtocol.allows(OWN,Map.of("type","loot","event_id","bad")));
        assertFalse(PublicBankProtocol.allows(BANK,Map.of("type","loot","event_id",a)));
        assertFalse(PublicBankProtocol.allows(null,Map.of("type","loot","event_id",a)));
    }
    @Test public void anOwnReceiptBatchIsStrictAndNeverAcceptsABankRow()
    {
        List<Map<String,Object>> rows=Arrays.asList(Map.of("type","logout","event_id",a),Map.of("type","loot","event_id",b));
        assertEquals(Arrays.asList(EventQueueProtocol.Outcome.DURABLE,EventQueueProtocol.Outcome.REJECTED),
            PublicBankProtocol.receipts(OWN,200,body(row(a,"duplicate")+","+row(b,"filtered")),rows).outcomes);
        assertEquals(Arrays.asList(EventQueueProtocol.Outcome.RETRY,EventQueueProtocol.Outcome.RETRY),
            PublicBankProtocol.receipts(OWN,200,body(row(a,"stored")),batch()).outcomes);
    }
}
