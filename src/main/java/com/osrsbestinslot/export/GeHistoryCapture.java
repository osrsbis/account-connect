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
    // 0.7.42 actual client renders "= 11,203 each"; the historic format was ">= N each".
    private static final Pattern EACH = Pattern.compile("(?:^|\\s)>?= " + NUMBER + " each(?:\\s|$)");
    private static final String STRICT_NUMBER = "(0|[1-9][0-9]{0,2}(?:,[0-9]{3})+|[1-9][0-9]*)";
    private static final Pattern DEDUCTION = Pattern.compile(STRICT_NUMBER + " coins?(?:<br>| )\\("
        + STRICT_NUMBER + " - " + STRICT_NUMBER + "(?: tax)?\\)");
    private static final Pattern NET_SEGMENT = Pattern.compile(STRICT_NUMBER + " coins?");
    private static final Pattern DEDUCTION_SEGMENT = Pattern.compile("\\(" + STRICT_NUMBER + " - " + STRICT_NUMBER
        + "(?: tax)?\\)");
    private static final Pattern EACH_SEGMENT = Pattern.compile("= " + STRICT_NUMBER + " each");
    private static final Pattern COLOUR_WRAPPER = Pattern.compile("<col=[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?>(.*)</col>");
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
            // The 0.7.43 actual client shows "Bought:" / "Sold:".
            String side = Text.removeTags(rawState).trim().replaceFirst(":$", "");
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
            captureDeduction(row, rawState, rawPrice);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Historic format reference: Flipping Utilities GeHistoryTabExtractor.getPrice ("(gross - tax tax)").
     * The 0.7.43 actual client (field-read 2026-10-19) renders each line in its own colour wrapper:
     * "<col=ffb83f>2,646 coins</col><br><col=9f9f9f>(2,700 - 54)</col>", optionally followed by
     * "<br>= 1,323 each", with no "tax" word. Both forms are accepted.
     * Unlike that extractor, retain offer totals: division cannot recover exact fills, and
     * displayed net must never have tax deducted again. Missing tax is not evidence of zero tax.
     */
    private static void captureDeduction(Map<String, Object> row, String rawState, String rawPrice)
    {
        row.put("original_gross_gp", null);
        row.put("explicit_tax_gp", null);
        row.put("displayed_net_gp", null);
        row.put("tax_gross_source", "ge_history_widget_383_3_price_text");
        row.put("tax_gross_provenance", "historic_flipping_utilities_ge_history_format");
        row.put("tax_gross_semantics", "unknown");
        row.put("verified_with_actual_client", false);

        // Only a complete plain string or one balanced colour wrapper is accepted. Never
        // strip arbitrary markup: doing so can join split digits or hide truncated text.
        String state = unwrapColour(rawState);
        if (state.endsWith(":")) state = state.substring(0, state.length() - 1);
        boolean buy = "Bought".equals(state);
        if (!buy && !"Sold".equals(state)) return;
        String[] parts = deduction(rawPrice);
        if (parts == null) return;
        try
        {
            long net = Long.parseLong(parts[0].replace(",", ""));
            long gross = Long.parseLong(parts[1].replace(",", ""));
            long tax = Long.parseLong(parts[2].replace(",", ""));
            // Nonnegative longs and tax <= gross make subtraction overflow-safe.
            if (tax > gross || gross - tax != net || (buy && tax != 0)) return;
            row.put("original_gross_gp", gross);
            row.put("explicit_tax_gp", tax);
            row.put("displayed_net_gp", net);
            row.put("tax_gross_semantics", "explicit_ui_offer_totals_not_exact_fills");
        }
        catch (NumberFormatException ex)
        {
            // All three fields remain unknown on overflow, even if a prefix was parseable.
        }
    }

    /**
     * {net, gross, tax} text, or null. Either the historic single string, or lines split on "<br>"
     * where each line is plain or exactly one balanced colour wrapper: net coins, (gross - tax),
     * then an optional "= N each". Any other markup or extra line rejects the whole row.
     */
    private static String[] deduction(String rawPrice)
    {
        Matcher whole = DEDUCTION.matcher(unwrapColour(rawPrice));
        if (whole.matches()) return new String[]{whole.group(1), whole.group(2), whole.group(3)};
        String[] lines = rawPrice.split("<br>", -1);
        if (lines.length < 2 || lines.length > 3) return null;
        for (int i = 0; i < lines.length; i++)
        {
            lines[i] = unwrapColour(lines[i]);
            if (lines[i].indexOf('<') >= 0 || lines[i].indexOf('>') >= 0) return null;
        }
        Matcher net = NET_SEGMENT.matcher(lines[0]), split = DEDUCTION_SEGMENT.matcher(lines[1]);
        if (!net.matches() || !split.matches()) return null;
        if (lines.length == 3 && !EACH_SEGMENT.matcher(lines[2]).matches()) return null;
        return new String[]{net.group(1), split.group(1), split.group(2)};
    }

    private static String unwrapColour(String raw)
    {
        Matcher wrapper = COLOUR_WRAPPER.matcher(raw);
        return wrapper.matches() ? wrapper.group(1) : raw;
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
