package com.osrsbestinslot.export;

import java.util.*;

/** Bounded event evidence queue. Source identity and expected values never follow later client state. */
final class EventVerification
{
    static final int MAX_PENDING = 8;
    static final int MAX_DELAY_TICKS = 3;
    static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "kill_count", "loot", "level_up", "minigame", "progress", "attempt_end", "death", "pet", "slayer_task",
        "verification_discovery"));
    private final ArrayDeque<Source> pending = new ArrayDeque<>();

    static final class Source
    {
        final String id, type, token, account, text;
        final long epoch, revision, at;
        final int tick;
        final Map<String, Object> fields;
        /** The source had frame room on its first look; a retry after a changed screen keeps that decision. */
        boolean framePaid;
        /** A full-window frame was decided for this source on its first look; retries keep it. */
        boolean fullWindowPaid;
        /** Why the last look was retried (changed screen, blank regions); reported if the delay runs out. */
        String retryReason;
        Source(Map<String, Object> event, String token, String account, long epoch, int tick,
            long revision, long at, String text)
        {
            this.id = String.valueOf(event.get("event_id"));
            this.type = String.valueOf(event.get("type"));
            this.fields = copyMap(event);
            this.token = token;
            this.account = account;
            this.epoch = epoch;
            this.tick = tick;
            this.revision = revision;
            this.at = at;
            this.text = clean(text);
        }
        Map<String, Object> common(int readTick)
        {
            Map<String, Object> out = Fields.of(
                "source_event_id", id, "source_event_type", type, "source_event_tick", tick,
                "source_event_revision", revision > 0 ? revision : null,
                "snapshot_revision", revision > 0 ? revision : null, "tick", readTick);
            return out;
        }
        boolean matches(String token, String account, long epoch)
        {
            return Objects.equals(this.token, token) && Objects.equals(this.account, account) && this.epoch == epoch;
        }
        boolean expired(int nowTick) { return nowTick < tick || nowTick - tick > MAX_DELAY_TICKS; }
    }

    synchronized boolean offer(Source source)
    {
        if (pending.size() >= MAX_PENDING) return false;
        pending.addLast(source);
        return true;
    }
    /**
     * Room for one more source at this tick. Sources already past their delay hold no slot: they are removed here and
     * returned, so the caller records each as UNKNOWN before admitting the new one.
     */
    synchronized List<Source> room(int nowTick, List<Source> expiredOut)
    {
        if (pending.size() >= MAX_PENDING)
            for (java.util.Iterator<Source> it = pending.iterator(); it.hasNext(); )
            {
                Source s = it.next();
                if (s.expired(nowTick)) { it.remove(); expiredOut.add(s); }
            }
        return expiredOut;
    }
    synchronized boolean room() { return pending.size() < MAX_PENDING; }
    synchronized List<Source> drain()
    {
        List<Source> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }
    synchronized void reset() { pending.clear(); }

    static String clean(String text)
    {
        if (text == null) return null;
        String value = text.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]*>", "")
            .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        return value.isEmpty() || value.length() > 384 ? null : value;
    }
    @SuppressWarnings("unchecked")
    private static Object copy(Object value)
    {
        if (value instanceof Map) return copyMap((Map<String, Object>) value);
        if (value instanceof List)
        {
            List<Object> result = new ArrayList<>();
            for (Object entry : (List<?>) value) result.add(copy(entry));
            return Collections.unmodifiableList(result);
        }
        return value;
    }
    static Map<String, Object> copyMap(Map<String, Object> value)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) result.put(entry.getKey(), copy(entry.getValue()));
        return Collections.unmodifiableMap(result);
    }
}
