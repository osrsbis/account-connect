package com.osrsbestinslot.export;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.runelite.api.GrandExchangeOfferState.*;

/** Exercise current domain implementations together, rather than a historical JSON handoff fixture. */
public class HistoryDomainIntegrationTest
{
    @Test public void currentWireReducerAndWealthAdapterPreserveFillAndOwnerBoundary()
    {
        GeObservationTracker tracker = new GeObservationTracker();
        tracker.observe(0, EMPTY, new long[]{0,0,0,0,0}, false, 1, 1_001_000L);
        List<Map<String, Object>> wire = new ArrayList<>();
        for (int n = 0; n < 2; n++)
        {
            Map<String, Object> event = tracker.observe(0, n == 0 ? BUYING : BOUGHT,
                new long[]{560,n == 0 ? 0 : 10,10,250,n == 0 ? 0 : 2400}, false, n+2, 1_002_000L+n*1000);
            event.put("account_hash", "42");
            event.put("profile_type", "STANDARD");
            event.put("event_id", String.format("%032x", n+1));
            wire.add(PublicTransport.event(event));
        }
        Map<String, Object> reduced = GeOfferLifecycle.reduce(wire, null, "same-owned-generation");
        List<String> excluded = new ArrayList<>();
        List<WealthReconciliation.TradeFact> facts = WealthReconciliation.tradeFactsFromC1(
            reduced, "42", "STANDARD", 1_000_000L, 1_020_000L, excluded);
        assertEquals(1, facts.size());
        assertEquals(560, facts.get(0).itemId);
        assertEquals(10L, facts.get(0).qtyDelta);
        assertEquals(Long.valueOf(-2400L), facts.get(0).coinsDelta);
        assertTrue(excluded.isEmpty());
        assertTrue(WealthReconciliation.tradeFactsFromC1(reduced, "other", "STANDARD",
            1_000_000L, 1_020_000L, new ArrayList<>()).isEmpty());
        assertTrue(WealthReconciliation.tradeFactsFromC1(reduced, "42", "LEAGUE",
            1_000_000L, 1_020_000L, new ArrayList<>()).isEmpty());
        excluded.clear();
        assertTrue(WealthReconciliation.tradeFactsFromC1(reduced, "42", "STANDARD",
            1_002_000L, 1_020_000L, excluded).isEmpty());
        assertTrue(excluded.get(0).endsWith(":fill_straddles_window"));
    }

    @Test public void currentLoadoutConsumerKeepsRealWealthUnknownLocations()
    {
        LoadoutRevision revisions = new LoadoutRevision("same-owned-generation");
        Map<String, Object> revision = revisions.observe(Collections.singletonMap(3, 4151), 10, 10_000L);
        WealthLedger.Holdings unreadBank = new WealthLedgerTest.Snap(WealthLedgerTest.T0).h();
        Map<?, ?> slots = (Map<?, ?>) LoadoutRevision.ownership(revision, unreadBank::owns).get("slots");
        Map<?, ?> weapon = (Map<?, ?>) slots.get("weapon");
        assertEquals("not_seen_in_observed", weapon.get("current_holdings"));
        assertTrue(((List<?>) weapon.get("unknown_locations")).contains("bank"));
        WealthLedger.Holdings worn = new WealthLedgerTest.Snap(WealthLedgerTest.T0).worn("weapon", 4151, 1).h();
        slots = (Map<?, ?>) LoadoutRevision.ownership(revision, worn::owns).get("slots");
        assertEquals("owned_observed", ((Map<?, ?>) slots.get("weapon")).get("current_holdings"));
    }
}
