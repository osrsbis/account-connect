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
