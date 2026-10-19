package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.runelite.api.GrandExchangeOfferState.*;

public class GeArchiveAuditTest
{
    private List<Map<String, Object>> sequence()
    {
        GeObservationTracker tracker = new GeObservationTracker();
        tracker.observe(0, EMPTY, new long[]{0,0,0,0,0}, false, 1, 1000L);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int n = 0; n < 2; n++)
        {
            Map<String, Object> event = tracker.observe(0, n == 0 ? BUYING : BOUGHT,
                new long[]{560,n == 0 ? 0 : 10,10,250,n == 0 ? 0 : 2400}, false, 2+n, 2000L+n*1000);
            event.put("event_id", "private-event-"+n);
            event.put("profile_type", "STANDARD");
            event.put("account_hash", "private-account");
            Map<String, Object> wire = PublicTransport.event(event);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", wire.remove("type"));
            row.put("id", n+1L);
            row.put("account_hash", "private-account");
            row.put("link_token", "private-token");
            row.put("fields", wire);
            rows.add(row);
        }
        return rows;
    }

    @Test public void actualTrackerProjectionProducesQualifiedCountsWithoutIdentifiers()
    {
        Map<String, Object> result = GeArchiveAudit.audit(sequence(), 2);
        assertEquals(2, result.get("eligible_versioned_rows"));
        assertEquals(1, result.get("qualified_offers"));
        assertEquals(1, result.get("observed_fill_intervals"));
        for (String identifier : Arrays.asList("private-account", "private-token", "private-event"))
            assertFalse(new Gson().toJson(result).contains(identifier));
        assertFalse(new Gson().toJson(result).contains("560"));
    }

    @Test public void legacyRowsAreExcludedWithoutInventingModeOrFill()
    {
        List<Map<String, Object>> rows = sequence();
        rows.get(0).put("fields", "{\"item\":560,\"qty\":10,\"gp\":2400}");
        rows.get(1).put("fields", "[]");
        Map<String, Object> result = GeArchiveAudit.audit(rows, 2);
        assertEquals(0, result.get("eligible_versioned_rows"));
        assertEquals(0, result.get("offers"));
        assertEquals(2, result.get("excluded_rows"));
    }

    @Test public void differentOwnerBindingsNeverMergeTheSameOfferKey()
    {
        List<Map<String, Object>> rows = sequence();
        rows.get(1).put("link_token", "another-private-token");
        Map<String, Object> result = GeArchiveAudit.audit(rows, 2);
        assertEquals(2, result.get("offers"));
        assertEquals(0, result.get("quarantined_offers"));
        // The terminal row carries its own measured delta interval; it does not need an archive-side join.
        assertEquals(1, result.get("observed_fill_intervals"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aboveAckFailsInsteadOfClaimingPreservation() { GeArchiveAudit.audit(sequence(), 1); }

    @Test public void conflictingPayloadAccountIsExcluded()
    {
        List<Map<String, Object>> rows = sequence();
        ((Map<String, Object>) rows.get(1).get("fields")).put("account_hash", "other-account");
        Map<String, Object> result = GeArchiveAudit.audit(rows, 2);
        assertEquals(1, result.get("eligible_versioned_rows"));
        assertEquals(1, ((Map<?, ?>) result.get("exclusion_reasons")).get("conflicting_account"));
    }
}
