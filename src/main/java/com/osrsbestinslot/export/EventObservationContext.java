package com.osrsbestinslot.export;

import java.util.*;

/** Per-client observation context, not a stable game session/order identifier. */
final class EventObservationContext
{
    static final Set<String> MODES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "NORMAL", "IRONMAN", "ULTIMATE_IRONMAN", "HARDCORE_IRONMAN", "GROUP_IRONMAN",
        "HARDCORE_GROUP_IRONMAN", "UNRANKED_GROUP_IRONMAN")));
    private String owner, session, mode;
    private long sequence, observedAt;
    private Integer tick;

    synchronized void clear() { owner = session = mode = null; sequence = observedAt = 0; tick = null; }
    private void bind(String identity)
    {
        if (!Objects.equals(owner, identity))
        {
            clear(); owner = identity; session = EventSpool.newEventId();
        }
    }
    synchronized void observe(String identity, String accountMode, int gameTick, long now)
    {
        if (identity == null) { clear(); return; }
        bind(identity);
        mode = MODES.contains(accountMode) ? accountMode : null;
        tick = gameTick >= 0 ? gameTick : null;
        observedAt = now;
    }
    synchronized Map<String,Object> event(String identity, String version, long now)
    {
        if (identity == null) return null;
        bind(identity);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("schema_v", 1); out.put("collector_session", session); out.put("sequence", ++sequence);
        out.put("plugin_version", version); out.put("source", "public_event_emitter");
        boolean fresh = observedAt > 0 && now >= observedAt && now - observedAt <= 10_000;
        out.put("context_status", fresh ? "observed_client_tick" : "unknown_or_stale");
        out.put("context_observed_at_ms", fresh ? observedAt : null);
        out.put("context_tick", fresh ? tick : null);
        out.put("account_mode", fresh ? mode : null);
        out.put("account_mode_source", fresh && mode != null ? "varbit_1777" : null);
        return out;
    }

    /** Strict nested replay projection: no raw profile ID, token, account name or free text. */
    static Map<String,Object> project(Object input)
    {
        Map<String,Object> out = new LinkedHashMap<>();
        if (!(input instanceof Map)) return out;
        Map<?,?> m = (Map<?,?>)input;
        for (String key : Arrays.asList("schema_v", "sequence", "context_observed_at_ms", "context_tick"))
        {
            Object v = m.get(key);
            if (v == null || v instanceof Number && Double.isFinite(((Number)v).doubleValue())
                && ((Number)v).doubleValue() >= 0 && ((Number)v).doubleValue() <= 9007199254740991D
                && ((Number)v).doubleValue() == ((Number)v).longValue()) out.put(key,v);
        }
        Object s = m.get("collector_session"), v = m.get("plugin_version"), mode = m.get("account_mode");
        if (s instanceof String && ((String)s).matches("[a-f0-9]{32}")) out.put("collector_session",s);
        if (v instanceof String && ((String)v).matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) out.put("plugin_version",v);
        if (mode == null || MODES.contains(mode)) out.put("account_mode",mode);
        if ("public_event_emitter".equals(m.get("source"))) out.put("source",m.get("source"));
        if ("observed_client_tick".equals(m.get("context_status")) || "unknown_or_stale".equals(m.get("context_status")))
            out.put("context_status",m.get("context_status"));
        if (m.get("account_mode_source") == null || "varbit_1777".equals(m.get("account_mode_source")))
            out.put("account_mode_source",m.get("account_mode_source"));
        return out;
    }
}
