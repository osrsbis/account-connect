package com.osrsbestinslot.export;

import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GeHistoryCaptureTest
{
    private Widget[] row(String side, int quantity, String price)
    {
        Widget[] row = new Widget[6];
        row[2] = mock(Widget.class); row[4] = mock(Widget.class); row[5] = mock(Widget.class);
        when(row[2].getText()).thenReturn(side);
        when(row[4].getItemId()).thenReturn(4151);
        when(row[4].getItemQuantity()).thenReturn(quantity);
        when(row[5].getText()).thenReturn(price);
        return row;
    }

    @Test public void preservesRawTaxTextAndDisplayedLongValuesWithoutInventingUnitPrice()
    {
        String raw = "<col=ffffff>3,000,000,000 coins<br>(3,060,000,000 - 60,000,000 tax)</col>";
        Map<String, Object> r = GeHistoryCapture.read(row("Sold", 3, raw)).get(0);
        assertEquals("sell", r.get("side"));
        assertEquals(3000000000L, r.get("displayed_total_gp"));
        assertNull(r.get("displayed_each_gp"));
        assertEquals(raw, r.get("price_text"));
        assertEquals("unknown", r.get("execution_time"));
        assertDeduction(r, 3060000000L, 60000000L, 3000000000L);
        assertEquals("displayed_values_tax_and_rounding_unresolved", r.get("price_semantics"));
    }

    @Test public void currentClientDeductionWithoutTheTaxWordIsRead()
    {
        // 0.7.42 actual client, Varrock GE 2026-10-19: "Sold: Mithril platebody 2,646 coins (2,700 - 54)".
        assertDeduction(GeHistoryCapture.read(row("Sold", 1, "2,646 coins<br>(2,700 - 54)")).get(0), 2700, 54, 2646);
        assertDeduction(GeHistoryCapture.read(row("Sold", 1, "11,939 coins<br>(12,182 - 243)")).get(0), 12182, 243, 11939);
        Map<String, Object> bought = GeHistoryCapture.read(row("Bought", 57, "638,571 coins<br>= 11,203 each")).get(0);
        assertEquals(638571L, bought.get("displayed_total_gp"));
        assertEquals(11203L, bought.get("displayed_each_gp"));
        assertNull(bought.get("explicit_tax_gp"));
        // Arithmetic still binds: a mismatched triple stays unknown.
        assertNull(GeHistoryCapture.read(row("Sold", 1, "2,600 coins (2,700 - 54)")).get(0).get("explicit_tax_gp"));
    }

    @Test public void explicitDeductionIsAnOfferTotalRegardlessOfQuantity()
    {
        for (int quantity : new int[] {1, 3, Integer.MAX_VALUE})
        {
            Map<String, Object> r = GeHistoryCapture.read(row("Sold", quantity,
                "1,001 coins<br>(1,021 - 20 tax)")).get(0);
            assertDeduction(r, 1021L, 20L, 1001L);
            assertEquals(quantity, r.get("quantity"));
            assertNull(r.get("displayed_each_gp")); // no aggregate division or rounding
        }
    }

    @Test public void explicitZeroTaxAndZeroTotalsAreNotMissingValues()
    {
        for (String side : new String[] {"Bought", "Sold", "<col=fff>Sold</col>"})
        {
            assertDeduction(GeHistoryCapture.read(row(side, 1, "1 coin (1 - 0 tax)")).get(0), 1, 0, 1);
            assertDeduction(GeHistoryCapture.read(row(side, 2, "0 coins<br>(0 - 0 tax)")).get(0), 0, 0, 0);
            // Ordinary untaxed UI text does not explicitly supply gross and tax.
            assertUnknown(GeHistoryCapture.read(row(side, 2, "100 coins<br>>= 50 each")).get(0));
            assertUnknown(GeHistoryCapture.read(row(side, 1, "0 coins")).get(0));
        }
    }

    @Test public void acceptsLongBoundariesWithoutAddingTaxOrMultiplyingQuantity()
    {
        assertDeduction(GeHistoryCapture.read(row("Sold", Integer.MAX_VALUE,
            "9,223,372,036,854,775,806 coins<br>(9,223,372,036,854,775,807 - 1 tax)")).get(0),
            Long.MAX_VALUE, 1, Long.MAX_VALUE - 1);
        assertDeduction(GeHistoryCapture.read(row("Sold", 1,
            "0 coins (9223372036854775807 - 9223372036854775807 tax)")).get(0),
            Long.MAX_VALUE, Long.MAX_VALUE, 0);
        assertDeduction(GeHistoryCapture.read(row("Bought", 1,
            "9223372036854775807 coins (9223372036854775807 - 0 tax)")).get(0),
            Long.MAX_VALUE, 0, Long.MAX_VALUE);
    }

    @Test public void rejectsAmbiguousMalformedInconsistentAndOverflowDeductionAtomically()
    {
        String[] invalid = {
            "unknown", "", "98 coins", "98 coins (100 - 2", "98 coins (100 - 2 tax", "98 coins (100 - 2 tx)",
            "98 coins (100 - 3 tax)", "0 coins (1 - 2 tax)", "-1 coins (1 - 2 tax)",
            "98 coins (100 - -2 tax)", "98 coins (0100 - 2 tax)", "98 coins (1,00 - 2 tax)",
            "98 coins (100 - 2 tax) extra", "98 coins (100 - 2 tax) (100 - 2 tax)",
            "98 coins (100 - 2 tax)<br>>= 49 each", // additional units remain unverified
            "<col=fff>98 coins<br>(100 - 2 tax)", "98 coins<br>(100 - 2 tax)</col>",
            "<col=fff>98 coins<br>(100 - 2 tax)</col></col>",
            "98 coins<br>(1<col=fff>00</col> - 2 tax)",
            "98 coins<br/>(100 - 2 tax)", "98 coins<unknown>(100 - 2 tax)",
            "98 coins<br>(100 - 2 tax)\n", "98 coins<br>(100 - 2 tax)...",
            "9223372036854775808 coins (9223372036854775807 - 0 tax)",
            "9223372036854775807 coins (9223372036854775808 - 1 tax)",
            "0 coins (9223372036854775807 - 9223372036854775808 tax)",
            "1 coins (0 - 18446744073709551615 tax)"
        };
        for (String raw : invalid)
        {
            Map<String, Object> r = GeHistoryCapture.read(row("Sold", 2, raw)).get(0);
            assertEquals(raw, r.get("price_text"));
            assertUnknown(r);
        }
        for (String side : new String[] {"Bought", "Cancelled", "", "Unknown", "So<bad>ld", "Sold</col>"})
            assertUnknown(GeHistoryCapture.read(row(side, 2, "98 coins (100 - 2 tax)")).get(0));
    }

    private void assertDeduction(Map<String, Object> r, long gross, long tax, long net)
    {
        assertEquals(gross, r.get("original_gross_gp"));
        assertEquals(tax, r.get("explicit_tax_gp"));
        assertEquals(net, r.get("displayed_net_gp"));
        assertEquals(net, r.get("displayed_total_gp"));
        assertEquals("explicit_ui_offer_totals_not_exact_fills", r.get("tax_gross_semantics"));
        assertEvidence(r);
    }

    private void assertUnknown(Map<String, Object> r)
    {
        for (String field : new String[] {"original_gross_gp", "explicit_tax_gp", "displayed_net_gp"})
        {
            assertTrue(r.containsKey(field));
            assertNull(r.get("price_text").toString(), r.get(field));
        }
        assertEquals("unknown", r.get("tax_gross_semantics"));
        assertEvidence(r);
    }

    private void assertEvidence(Map<String, Object> r)
    {
        assertEquals("ge_history_widget_383_3_price_text", r.get("tax_gross_source"));
        assertEquals("historic_flipping_utilities_ge_history_format", r.get("tax_gross_provenance"));
        assertEquals(false, r.get("verified_with_actual_client"));
        assertEquals("unknown", r.get("execution_time"));
    }

    @Test public void eachIsObservedNotMultipliedAndUnknownIsNotZeroOrSell()
    {
        Map<String, Object> r = GeHistoryCapture.read(row("Bought", 3, "<col=fff>1,001 coins<br>>= 333 each</col>")).get(0);
        assertEquals(1001L, r.get("displayed_total_gp"));
        assertEquals(333L, r.get("displayed_each_gp"));
        r = GeHistoryCapture.read(row("Cancelled", 3, "unknown")).get(0);
        assertEquals("unknown", r.get("side"));
        assertNull(r.get("displayed_total_gp"));
        assertNull(r.get("displayed_each_gp"));
        assertNull(GeHistoryCapture.read(row("Bought", 1, "999999999999999999999999 coins")).get(0).get("displayed_total_gp"));
    }

    @Test public void rejectsPartialAndOversizedLayoutsButRetainsIdenticalRows()
    {
        assertNull(GeHistoryCapture.read(new Widget[7]));
        assertNull(GeHistoryCapture.read(new Widget[246]));
        assertNull(GeHistoryCapture.read(new Widget[6]));
        assertNull(GeHistoryCapture.read(row("Bought", 0, "1 coin")));
        Widget[] one = row("Bought", 1, "1 coin"), two = new Widget[12];
        System.arraycopy(one, 0, two, 0, 6); System.arraycopy(one, 0, two, 6, 6);
        List<Map<String, Object>> rows = GeHistoryCapture.read(two);
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(1).get("row_index"));
    }

    @Test public void delayedPopulationReopenRateBoundAndReset()
    {
        GeHistoryCapture capture = new GeHistoryCapture();
        Client client = mock(Client.class); Widget list = mock(Widget.class);
        when(client.getWidget(383, 3)).thenReturn(list);
        when(list.getDynamicChildren()).thenReturn(new Widget[6]);
        poll(capture, client, 0);
        Widget[] populated = row("Bought", 3, "30 coins<br>>= 10 each");
        when(list.getDynamicChildren()).thenReturn(populated);
        poll(capture, client, 5);
        assertTrue(observations(capture).isEmpty());
        poll(capture, client, 5); // same tick is throttled
        assertTrue(observations(capture).isEmpty());
        poll(capture, client, 10);
        assertEquals(1, observations(capture).size());
        assertEquals(5000L, observations(capture).get(0).get("observed_at_ms"));
        when(list.isHidden()).thenReturn(true); poll(capture, client, 15);
        when(list.isHidden()).thenReturn(false); poll(capture, client, 20); poll(capture, client, 25);
        assertEquals(1, observations(capture).size());
        String session = (String) capture.snapshot().get("collector_session");
        capture.reset();
        assertNotEquals(session, capture.snapshot().get("collector_session"));
        assertTrue(observations(capture).isEmpty());
    }

    @Test public void boundedRecoveryReportsEvictionsAndRetainsLatestRawRows()
    {
        GeHistoryCapture capture = new GeHistoryCapture();
        Client client = mock(Client.class); Widget list = mock(Widget.class);
        when(client.getWidget(383, 3)).thenReturn(list);
        for (int n = 0; n < 12; n++)
        {
            Widget[] populated = row("Bought", n + 1, "100 coins");
            when(list.getDynamicChildren()).thenReturn(populated);
            poll(capture, client, n * 10); poll(capture, client, n * 10 + 5);
        }
        assertEquals(8, observations(capture).size());
        assertEquals(4L, capture.snapshot().get("observations_evicted"));
        assertEquals(12L, observations(capture).get(7).get("sequence"));
    }

    private void poll(GeHistoryCapture c, Client client, int tick)
    {
        when(client.getTickCount()).thenReturn(tick); c.poll(client, tick * 1000L);
    }

    private List<Map<String, Object>> observations(GeHistoryCapture c)
    {
        return (List<Map<String, Object>>) c.snapshot().get("observations");
    }
}
