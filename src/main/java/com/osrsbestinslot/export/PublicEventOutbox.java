package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.LongSupplier;
import java.nio.charset.StandardCharsets;

/**
 * The journal adapter for every durable event scope: the staff spool, the public Queue journal and the public bank
 * and own-events journals, each in its own root with its own identity, purge and receipt rules. All methods run on the plugin's disk
 * executor. Uses the existing forced journal and tombstones. It owns projected event rows only, never snapshots,
 * credentials or gameplay actions. Staff identities are opened by key (token and account); public ones by Context.
 */
final class PublicEventOutbox
{
    static final class Context
    {
        final String identity;
        final String account;
        final String mode;
        Context(String token, String account, String mode, String profile)
        {
            if (token == null || !token.matches("[a-f0-9]{32}") || account == null || account.isEmpty()
                || "-1".equals(account) || mode == null || "UNKNOWN".equals(mode)
                || !mode.matches("[A-Z_]{1,64}") || profile == null || profile.isEmpty())
                throw new IllegalArgumentException("public outbox requires a settled identity");
            this.account = account;
            this.mode = mode;
            // Length-delimited identity components; raw token/profile never reach disk.
            identity = EventSpool.identityKey(token, "public-v1:" + account.length() + ":" + account
                + ":" + mode.length() + ":" + mode + ":" + profile.length() + ":" + profile);
        }
        boolean owns(Map<String, Object> event)
        {
            return account.equals(event.get("account_hash"))
                && mode.equals(event.getOrDefault("profile_type", "STANDARD"));
        }
    }

    private final Path data;
    private final Path root;
    private final Gson gson;
    private final LongSupplier clock;
    private final EventSpool.Limits limits;
    /** The event-id receipt scope (PublicBankProtocol) this journal holds, or null for the Queue journal. */
    private final String receiptScope;
    final EventSpool.Counters counters;
    /** The open journal and its identity key, or null. */
    EventSpool journal;
    String openId;

    PublicEventOutbox(Path data, Gson gson, LongSupplier clock)
    {
        this(data, gson, clock, EventSpool.Limits.DEFAULT);
    }

    PublicEventOutbox(Path data, Gson gson, LongSupplier clock, EventSpool.Limits limits)
    {
        this(data,gson,clock,limits,null);
    }

    /** Each scope has its own root, so an older client never reads rows of a scope it does not know. */
    PublicEventOutbox(Path data, Gson gson, LongSupplier clock, EventSpool.Limits limits, String receiptScope)
    {
        this(data, receiptScope == null ? "public-events-v1" : PublicBankProtocol.SCOPE.equals(receiptScope)
            ? "public-bank-events-v1" : "public-own-events-v1", gson, clock, limits, receiptScope, new EventSpool.Counters());
    }

    /** The staff spool: root "spool", the plugin's spool counters. Rows are appended by the plugin, never by Context. */
    PublicEventOutbox(Path data, LongSupplier clock, EventSpool.Counters counters)
    {
        this(data, "spool", null, clock, EventSpool.Limits.DEFAULT, null, counters);
    }

    private PublicEventOutbox(Path data, String root, Gson gson, LongSupplier clock, EventSpool.Limits limits,
        String receiptScope, EventSpool.Counters counters)
    {
        this.data = data;
        this.root = data.resolve("osrsbis").resolve(root);
        this.receiptScope = receiptScope;
        this.gson = gson;
        this.clock = clock;
        this.limits = limits;
        this.counters = counters;
    }


    /** The journal of `id` is open and usable. */
    boolean isOpen(String id) { return journal != null && id.equals(openId) && !journal.isFailed(); }

    /**
     * Staff: open `id` after the caller purged the other identities. Ages out foreign shards, waits for a marked
     * directory's live writer and recovers this identity's abandoned shards.
     */
    boolean openStaff(String id) throws IOException
    {
        return open(id, true);
    }

    /**
     * Age out foreign shards, refuse a marked directory (staff first finishes an idle one), open `id` and recover
     * its abandoned shards. The staff adapter keeps the shard open even when its recovery fails.
     */
    private boolean open(String id, boolean staff) throws IOException
    {
        EventSpool.sweepAged(root, clock, limits.maxAgeMs, counters);
        Path dir = root.resolve(id);
        if (Files.exists(dir.resolve(EventSpool.MARKER)) && !(staff && EventSpool.purgeIfIdle(dir))) return false;
        EventSpool opened = new EventSpool(data, dir, clock, EventSpool.ATOMIC, limits, counters);
        if (staff) { journal = opened; openId = id; }
        if (Files.isDirectory(dir)) opened.recover();
        journal = opened;
        openId = id;
        return true;
    }

    /**
     * Close this writer's own shard of `id` (deleting it) if open, then purge the directory. withdrawn marks live
     * siblings so they stop too; otherwise only an idle directory goes. True when an existing directory went.
     */
    boolean drop(String id, boolean withdrawn) throws IOException
    {
        if (id.equals(openId)) closeOwn();
        Path dir = root.resolve(id);
        boolean existed = Files.isDirectory(dir);
        boolean gone = withdrawn ? EventSpool.purgeIdentityDir(dir, clock) : EventSpool.purgeIfIdle(dir);
        return existed && (gone || withdrawn);
    }

    /** No grant or no settled account means no open, recovery or replay. */
    boolean activate(Context next) throws IOException
    {
        if (next == null) return false;
        if (next.identity.equals(openId) && journal != null)
        {
            if (journal.purgeRequested())
            {
                // Remove this writer's sensitive payload now. Keep the marker/closed handle so
                // the same stale grant cannot reopen it on the following tick.
                journal.closeAndDeleteOwn();
                return false;
            }
            if (!journal.isFailed()) return true;
            // A compaction reopen failure leaves recoverable rows on disk. Retire the failed
            // writer and recover under the same still-valid context, as the staff adapter does.
        }
        close();
        // A normal account/profile change is not an erasure or ACK. Retain recent
        // identities for a later matching grant; the 48 h age bound still applies.
        return open(next.identity, false);
    }

    /** Returns true only for a row still present after a forced append and bound enforcement. */
    boolean append(Context owner, String scope, Map<String, Object> input) throws IOException
    {
        Map<String, Object> event = PublicTransport.event(input);
        if (owner == null || event == null || !owner.owns(event) || !knownType(event) || !allows(scope, event))
            return false;
        event.remove("rsn"); // account_hash and the token-bound context suffice for public Queue routing
        Object timestamp = event.get("ts");
        if (!(timestamp instanceof Number) || ((Number) timestamp).longValue() <= 0) return false;
        String json = gson.toJson(event);
        if (json.getBytes(StandardCharsets.UTF_8).length >= EventQueue.EVENT_BATCH_MAX_BYTES)
        {
            counters.dropped.incrementAndGet();
            return false;
        }
        if (!activate(owner)) return false;
        // The first append forces the hashed identity directory into the root before the row is written.
        String id = (String) event.get("event_id");
        long created = Math.min(((Number) timestamp).longValue(), clock.getAsLong());
        boolean appended = journal.append(id, String.valueOf(event.get("type")), created, json);
        journal.maybeCompact();
        return appended && journal.get(id) != null;
    }

    /** Re-project recovered rows and re-check ownership before exposing any payload. */
    List<Map<String, Object>> pending(Context owner, String scope, int maximum) throws IOException
    {
        List<Map<String, Object>> result = new ArrayList<>();
        if (maximum <= 0 || !activate(owner)) return result;
        journal.enforceBoundsNow();
        List<String> rejected = new ArrayList<>();
        for (EventSpool.Record row : journal.pending())
        {
            if (result.size() >= maximum) break; // later rows are checked when they come up
            Map<String, Object> event;
            try { event = PublicTransport.event(gson.fromJson(row.eventJson, Map.class)); }
            catch (RuntimeException invalid) { event = null; }
            if (event == null || !owner.owns(event) || !row.eventId.equals(event.get("event_id"))
                || !knownType(event))
            {
                rejected.add(row.eventId);
                continue;
            }
            // A narrower grant pauses retained broader rows; it does not acknowledge them.
            if (allows(scope, event)) result.add(event);
        }
        journal.quarantine(rejected);
        return result;
    }

    static boolean allows(String scope, Map<String,Object> event)
    {
        return PublicBankProtocol.handles(scope) ? PublicBankProtocol.allows(scope, event) : EventQueueProtocol.allows(scope,event);
    }

    private boolean knownType(Map<String,Object> event)
    {
        return allows(receiptScope == null ? "own-history-v1" : receiptScope, event);
    }

    /** Only validated per-ID queue outcomes settle rows, including partial non-2xx receipts. */
    void settle(Context sentOwner, List<Map<String, Object>> sent, List<EventQueueProtocol.Outcome> outcomes)
        throws IOException
    {
        if (openId == null || sentOwner == null || !openId.equals(sentOwner.identity)
            || journal == null || outcomes.size() != sent.size()) return;
        List<String> accepted = new ArrayList<>(), rejected = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++)
        {
            String id = String.valueOf(sent.get(i).get("event_id"));
            if (!sentOwner.owns(sent.get(i)) || journal.get(id) == null) continue;
            switch (outcomes.get(i))
            {
                case ERASED:
                    purge();
                    return;
                case DURABLE: accepted.add(id); break;
                case REJECTED: rejected.add(id); break;
                default: break;
            }
        }
        counters.acked.addAndGet(journal.ack(accepted));
        journal.quarantine(rejected);
        journal.maybeCompact();
    }

    /** Explicit withdrawal: mark known live siblings and retire idle retry payloads. */
    void purge() throws IOException
    {
        if (openId != null) drop(openId, true);
        // A cold client can receive revocation or clear its token before any grant/recovery.
        // Use only the hashed directory names; never open payloads or mark an unrelated live writer.
        for (String old : EventSpool.identities(root)) EventSpool.purgeIfIdle(root.resolve(old));
        counters.purged.incrementAndGet();
    }

    /** A late account-erased receipt may only retire the identity that was sent. */
    void erase(Context erased) throws IOException
    {
        if (erased == null) return;
        drop(erased.identity, true);
        counters.purged.incrementAndGet();
    }

    /** Close this writer and delete its own shard; other shards of the identity stay. */
    void closeOwn()
    {
        if (journal != null) journal.closeAndDeleteOwn();
        journal = null;
        openId = null;
    }

    /** Logout/normal close retains retry rows; a future matching grant is required to recover. */
    void close()
    {
        if (journal != null) journal.close();
        journal = null;
        openId = null;
    }

    int size() { return journal == null ? 0 : journal.pendingCount(); }
    long bytes() { return journal == null ? 0 : journal.pendingPayloadBytes(); }
    /** Created time of the oldest unsettled row of the open identity, or -1. */
    long oldest() { return journal == null ? -1 : journal.oldestCreatedMs(); }
    void crashForTest() { if (journal != null) journal.crashForTest(); journal = null; openId = null; }
}
