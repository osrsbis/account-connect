package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/** Bounded UI evidence, never a trade ledger or a source of execution timestamps. */
final class GeHistoryCapture
{
    private static final String NUMBER = "([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)";
    private static final Pattern TOTAL = Pattern.compile("^" + NUMBER + " coins?(?:\\s|$)");
    private static final Pattern EACH = Pattern.compile("(?:^|\\s)>= " + NUMBER + " each(?:\\s|$)");
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private List<Map<String, Object>> previous;
    private List<Map<String, Object>> candidate;
    private long candidateAt;
    private int nextTick;
    private int nextObservationTick;
    private long sequence;
    private long evicted;
    private String collectorSession = java.util.UUID.randomUUID().toString();

    void reset()
    {
        collectorSession = java.util.UUID.randomUUID().toString();
        observations.clear();
        previous = candidate = null;
        nextTick = nextObservationTick = 0;
        sequence = evicted = 0;
    }

    void poll(Client client, long now)
    {
        int tick = client.getTickCount();
        if (tick < nextTick) return;
        nextTick = tick + 1;
        Widget list = client.getWidget(383, 3);
        if (list == null || list.isHidden())
        {
            candidate = null;
            return;
        }
        List<Map<String, Object>> rows = read(list.getDynamicChildren());
        if (rows == null || rows.isEmpty())
        {
            candidate = null;
            return;
        }
        // Require two equal reads: WidgetLoaded can precede layout and item population.
        if (!rows.equals(candidate))
        {
            candidate = rows;
            candidateAt = now;
            return;
        }
        if (rows.equals(previous)) return; // opening the same history is not another execution
        if (tick < nextObservationTick) return;
        nextObservationTick = tick + 5;
        previous = rows;
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("sequence", ++sequence);
        observation.put("observed_at_ms", candidateAt);
        observation.put("confirmed_at_ms", now);
        observation.put("rows", rows);
        observations.add(observation);
        if (observations.size() > 8)
        {
            observations.remove(0);
            evicted++;
        }
    }

    static List<Map<String, Object>> read(Widget[] children)
    {
        if (children == null || children.length == 0 || children.length % 6 != 0 || children.length > 240)
            return null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int offset = 0; offset < children.length; offset += 6)
        {
            Widget state = children[offset + 2], item = children[offset + 4], price = children[offset + 5];
            if (state == null || item == null || price == null) return null;
            String rawState = state.getText(), rawPrice = price.getText();
            if (rawState == null || rawPrice == null || rawState.length() > 128 || rawPrice.length() > 512)
                return null;
            if (rawState.isEmpty() && item.getItemId() <= 0) continue; // empty history row
            if (item.getItemId() <= 0 || item.getItemQuantity() <= 0) return null;
            String side = Text.removeTags(rawState).trim();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("row_index", offset / 6);
            row.put("item_id", item.getItemId());
            row.put("quantity", item.getItemQuantity());
            row.put("side", "Bought".equals(side) ? "buy" : "Sold".equals(side) ? "sell" : "unknown");
            row.put("state_text", rawState);
            row.put("price_text", rawPrice);
            String plain = Text.removeTags(rawPrice.replace("<br>", " ")).replace('\u00a0', ' ').trim();
            row.put("displayed_total_gp", number(TOTAL, plain));
            row.put("displayed_each_gp", number(EACH, plain));
            row.put("execution_time", "unknown");
            row.put("price_semantics", "displayed_values_tax_and_rounding_unresolved");
            rows.add(row);
        }
        return rows;
    }

    private static Long number(Pattern pattern, String text)
    {
        Matcher match = pattern.matcher(text);
        if (!match.find()) return null;
        try { return Long.parseLong(match.group(1).replace(",", "")); }
        catch (NumberFormatException ex) { return null; }
    }

    Map<String, Object> snapshot()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "ge_history_widget_383_3");
        out.put("collector_session", collectorSession);
        out.put("identity_scope", "account_profile_collector_session");
        out.put("observations", new ArrayList<>(observations));
        out.put("observations_evicted", evicted);
        out.put("complete", false); // UI is a limited recent history, not all trades
        return out;
    }
}
