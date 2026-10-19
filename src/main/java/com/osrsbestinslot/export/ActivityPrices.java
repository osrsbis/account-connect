package com.osrsbestinslot.export;

import java.util.*;

/**
 * One dated public price snapshot. A quote is usable only at or after the snapshot was available,
 * and carries its own trade time and age; a missing item or side stays unknown, never zero.
 */
final class ActivityPrices
{
    static final int COINS = 995, PLATINUM = 13204;
    static final long FRESH_SECONDS = 3600, STALE_SECONDS = 86400;
    final String source, sourceSha256;
    final long availableAt; // seconds; when this response was received, not its nominal trade times
    private final Map<Integer,long[]> rows; // high, highTime, low, lowTime; -1 means absent

    ActivityPrices(String source, String sourceSha256, long availableAt, Map<Integer,long[]> rows)
    {
        this.source = source; this.sourceSha256 = sourceSha256; this.availableAt = availableAt;
        this.rows = Collections.unmodifiableMap(new HashMap<>(rows));
    }

    /** prices.runescape.wiki /latest body: {data:{id:{high,highTime,low,lowTime}}}. Malformed rows are absent. */
    static ActivityPrices wikiLatest(Map<?,?> body, String sourceSha256, long availableAt)
    {
        Map<Integer,long[]> rows = new HashMap<>();
        Object data = body == null ? null : body.get("data");
        if (data instanceof Map) for (Map.Entry<?,?> e : ((Map<?,?>)data).entrySet())
        {
            if (!(e.getValue() instanceof Map) || !String.valueOf(e.getKey()).matches("[1-9][0-9]{0,6}")) continue;
            Map<?,?> p = (Map<?,?>)e.getValue();
            rows.put(Integer.parseInt(String.valueOf(e.getKey())), new long[]{
                whole(p.get("high")), whole(p.get("highTime")), whole(p.get("low")), whole(p.get("lowTime"))});
        }
        return new ActivityPrices("prices.runescape.wiki/api/v1/osrs/latest", sourceSha256, availableAt, rows);
    }

    private static long whole(Object x)
    {
        if (!(x instanceof Number)) return -1;
        double d = ((Number)x).doubleValue();
        return Double.isFinite(d) && d > 0 && d == Math.floor(d) && d < 9007199254740991D ? (long)d : -1;
    }

    /** side "instant_buy" (Wiki high) or "instant_sell" (Wiki low), quoted at asOf seconds. */
    Map<String,Object> quote(int item, String side, long asOf)
    {
        Map<String,Object> q = new LinkedHashMap<>();
        q.put("item_id", item); q.put("side", side);
        if (item == COINS || item == PLATINUM)
        {
            q.put("unit_gp", item == COINS ? 1L : 1000L); q.put("observed_trade_at", null); q.put("age_seconds", null);
            q.put("freshness", "fixed_face_value"); return q;
        }
        long[] row = rows.get(item);
        int at = "instant_buy".equals(side) ? 0 : 2;
        long price = row == null ? -1 : row[at], time = row == null ? -1 : row[at+1];
        String freshness;
        if (asOf < availableAt) freshness = "snapshot_not_yet_available";
        else if (price <= 0 || time <= 0) freshness = "missing";
        else if (time > asOf) freshness = "future_trade_time";
        else freshness = asOf - time <= FRESH_SECONDS ? "fresh" : asOf - time <= STALE_SECONDS ? "stale" : "too_old";
        boolean usable = freshness.equals("fresh") || freshness.equals("stale");
        q.put("unit_gp", usable ? price : null);
        q.put("observed_trade_at", usable ? time : null);
        q.put("age_seconds", usable ? asOf - time : null);
        q.put("freshness", freshness);
        return q;
    }

    static boolean usable(Map<String,Object> quote) { return quote.get("unit_gp") != null; }
}
