# Event delivery and retry

How events reach the server: delivery counters, the public bank and own-events journals, the latest bank in the
snapshot (0.7.62) and the public Queue journal. Each section states its own release state.

## Transient responses (0.7.68)

The legacy memory sender, which also sends the staff journal's rows (see "Staff journal" below), retains events
after HTTP 404 or 408, using its existing bounded retry delays. This matches the queue-v1 treatment of unavailable routes and request
timeouts. Retried events keep their original event IDs; durable rows are not quarantined by these responses.
Integer Retry-After values are honored on transient responses and capped at the existing five-minute ceiling
before conversion to milliseconds, including headers large enough to overflow an uncapped multiplication.

From 0.7.70 a snapshot answered with 429 or any 5xx leaves every server grant as it was, and the rows those
grants allow keep being captured and retried. Earlier builds treated such an answer as a policy without the grant
headers and switched the grants off until the next accepted snapshot.

HTTP 403 retains the existing identity-revocation handling. Permanent application rejections, including
HTTP 400, retain their terminal handling. This adds no collection grant, field, event or request type.
This change is new in 0.7.68; 0.7.67 did not have it.

## Staff journal (0.7.72)

The staff journal (`osrsbis/spool`, only under `X-Event-Spool: on` for the current token and a real account) uses the
public journals' model. While it is on, every event goes to the one memory sender through the disk thread, in emit
order; an allowlisted row is appended and forced first, so nothing is sent before it is durable. A pump on the
5-second tick stages the forced rows the sender does not hold: rows a stopped or killed client left, rows the
500-row memory cap dropped, and rows a 2xx did not settle by their own `event_id`. It stages at most 100 per tick,
only into free buffer room, and backs off after such a 2xx; staged rows are the first the cap drops, and they are
not counted in `events_sent_total` or `events_lost_total`.

Up to 0.7.72 a second sender replayed those rows, one request at most every 5 seconds, with its own backoff. Now
they share the sender's batches and its retry ladder; the request bodies, `event_id`s, ack rules, purge triggers,
48-hour bound and on-disk format are unchanged, so a journal an older client left is sent after the upgrade. A
staged row waits while its token and account are not the live ones or the operator pause holds, and is dropped
from memory (`spool_replay_aged_at_dispatch_total`) and then tombstoned once it is past 48 hours. An event the
disk thread has not handed over within 3 seconds goes out anyway (`spool_gate_timeout_total`), as before. A staged
row the journal no longer owns (a withdrawal or purge let it go while its request was in flight) is dropped when it
comes back from a failed request, is never written to the exit file, and is counted only by the journal.

## Queue delivery health (local candidate)

`source.capture_health.queue_delivery` separates valid broker-acceptance receipts
from archive-plus-D1 receipts, by a fixed event-family list. The ten own-state kinds, seventeen own-vars families and
fifteen own-signal families keep their counters, which stay zero: those events use the legacy receiver
(OWN_GAMEPLAY.md). It also reports source-to-journal handoffs, confirmed
forced appends, fallback/refused handoffs and per-ID retry/rejected/erased response
rows. No token, account, event ID or arbitrary source label is retained in these
counters. The older `events_sent_total` combines legacy HTTP success and Queue
ownership acceptance; it must not be described as an archive count.

These are process-local operation counts, not unique event totals or a partition
of gameplay. Recovered rows can predate the process; retry responses repeat rows,
and a broker-accepted event may archive later without another client response.
Network failures are not response rows. Handoff is not persistence, fallback may
follow a successful append but failed staging, and no completeness percentage is
inferred. Existing snapshot hashing excludes capture health, so changing counters
alone does not trigger an upload. This telemetry remains release-gated with the
expanded client and is not measured production coverage.

## Public bank retry contract (local candidate)

A successful token-bound `X-Bank-Receipt: event-id-v1` response, bank capture enabled,
and a settled own account/mode/RuneLite profile enable the bounded public journal
for `bank_visit_open`, `bank_visit_manifest_chunk` and `bank_visit_close` only.
These rows use the existing event endpoint and its exact per-ID `stored`, `duplicate`
or `filtered` results. A generic success/count does not clear the journal. A D1
receipt is not a Queue or archive receipt. No staff-spool or Queue grant is added.
Its `osrsbis/public-bank-events-v1` root is separate from the Queue journal, so an
older Queue client cannot quarantine bank rows it does not understand. Each journal
has the existing 5,000-row, 5 MiB and 48-hour bounds; this is not indefinite local storage.
The same identity, disk bounds, retry/backoff, revocation and tombstone protections
below apply. Missing capability or bank opt-out pauses replay; explicit token
withdrawal/revocation purges local retry data. Older servers retain memory-only
behavior. No new capture fields or family are enabled.

An unreadable bank close is incomplete regardless of callback order. The opening
`manifest_complete` flag still describes the opening inventory. `moves_complete`
and the session's `complete` flag are false without a reliable closing read.
`close_reason: closed` records an observed widget close when its later teardown
cause was not known; it does not establish a normal complete visit. No ending
inventory is invented. Actual final-build account/crash acceptance remains required.

## Public own-events retry contract (0.7.71)

A successful token-bound `X-Event-Receipt: own-p01-v1` snapshot answer, on a token without the staff spool, and a
settled own account/mode/RuneLite profile enable a second bounded public journal. It holds the public P0 and P1 rows
and `xp_gain` and `world_hop`: the six `ge_*` types, `store_buy`, `store_sell`, `trade`, `death`, `login`, `logout`,
`loot`, `drop`, `pickup`, `alch`, `ground_removed`, `level_up`, `kill_count`, `pet` and `bank_session`.
- `bank_visit_*` rows keep the bank journal. Every other P2 row, `own_signal` included, keeps the memory path.
- The rows use the existing event endpoint and its exact per-ID `stored`, `duplicate` or `filtered` results, with the
  bank journal's parser; a generic success settles nothing. `filtered` is terminal, as for bank rows.
- Its `osrsbis/public-own-events-v1` root is separate from the bank and Queue roots, so 0.7.70 and older never read
  it and their bank journal keeps working after a downgrade.
- When the token also holds a Queue grant, these types use this journal, so one session's rows share one journal.
- The projection before disk and on recovery, the hashed identity, the 5,000-row, 5 MiB and 48-hour bounds, the
  logout window and the purges on revocation, token change and withdrawal are the bank journal's.
- `off`, a missing header or a refused snapshot ends the grant and pauses replay without purging; a 429 or 5xx answer
  keeps it, as from 0.7.70. A 403 or a token change purges the journal.
- Without the header the rows keep the 0.7.70 memory path. No event, field or request type is added.

The journal replays in append order, so a session's logout reaches the server after the rows before it, also after a
throttled run and after a process kill. From 0.7.71 a full 500-row memory buffer first drops the oldest memory-only
P2 row, then the newest row a public journal still holds (it stays on disk and is staged again after the rows before
it), and only then the oldest row of the lowest class. `events_overflow_p0/p1/p2_total` and `events_lost_total` count
only rows that are lost; in 0.7.70 and older, `events_overflow_p1_total` also counted bank-journal rows that left
memory but stayed on disk (`events_lost_total` did not).
`public_outbox_oldest_age_ms` in capture health is the age of the oldest unsettled row across the public journals
this process has open, 0 when there is none.

## Latest bank in the snapshot (0.7.62)

The server keeps only the newest complete bank of a player, from the snapshot. The
snapshot's `bank` block carries `manifest_sha256` and `item_stacks`, built from the same
canonical list as a bank visit, when every item is in one stack. On a token with the public
bank receipt, a visit's `bank_visit_open` and normal `bank_visit_close` send no open or
close item list when a snapshot queued for upload on the same token and account holds that
exact bank with a known read time. They say so with `manifest_in_snapshot: true`, and the
close sends `end_item_stacks` instead of `end_chunks`. The open counts the bank-open snapshot,
whose read has no confirmed time yet; a failed upload is never counted. An open sent while that upload is still
in flight sends its open list as chunks after all, in the same visit, if the answer says nothing was stored (from
0.7.68); a token or account change in between sends nothing. The close forces no
upload: it waits up to 250 game ticks for a regular snapshot the server stored (a 2xx with `ok`
that is neither `stale` nor `paused` from 0.7.68; before that, any 2xx) that holds its end bank
with a known read time. Because the server keeps one bank per player, such a close's end bank can be
read back only until the player's next stored bank replaces it (median 166 s on 0.7.67, recheck 11-08). Otherwise, and at a logout, hop or new open first, the list
goes as chunks as before. A snapshot an event forces inside a widget-close script is built on the
next game tick.
Deposited and withdrawn lists, `bank_session` and teardown closes are unchanged. Staff
visits always send every list.

## Sending after a logout (0.7.63)

Rows a session captured before its logout, such as the bank close of a visit open at the logout or a close
still waiting for its snapshot, are sent after the logout under that session's own token, account, game mode
and RuneLite profile. Sending stops at the next login, a token change, a revocation, a RuneLite profile switch
or a plugin stop; anything left waits for the same identity, as before. A client close ends an open visit as a
`logout` teardown and sends up to three more batches after its first, so a journaled bank close goes in the
same close. A refused close request is not retried at the close; its rows stay saved for the next start.

## Public Queue retry contract (local candidate)

A successful token-bound `X-Event-Queue: ge-v1` or `own-history-v1` response (the only scopes a server sends and
the plugin accepts; the four `own-*` scopes earlier candidates accepted were removed in the 0.7.67 simplification),
a settled own account/mode and a known RuneLite profile are required. The base contract journals
14 Queue-supported event types: six GE types plus
bank_session, equip_change, loot, kill_count, level_up, xp_gain, alch and pickup.
The existing first-party field projection runs before disk, during recovery and
before POST. Apart from the separately negotiated bank path above, other public families and snapshots retain their existing memory
path; this is not whole-intake crash durability.

The separate `osrsbis/public-events-v1` journal uses hashed token/account/mode/profile
identities and the existing forced-write/CRC journal core. No raw token, profile identifier or own RSN is persisted. Normally a row enters
HTTP delivery after its forced append. If disk IO or the bounded append backlog
fails, the previous bounded memory Queue retry path remains available under the
same identity checks; those rows are explicitly not crash-durable. This fallback
never overrides an erasure marker or ownership mismatch. There can be up to 500
not-yet-durable append jobs; process death before force and bound eviction remain
explicit gaps. A failed compaction writer recovers without restarting the client.
`public_outbox_not_durable_total`, `public_outbox_append_pending`,
`public_outbox_dropped_total`, pending rows/bytes and retired-identity counters expose
these limits. Retiring an idle prior identity is an explicit local retry gap; its
exact lost-row count is unknown for unopened shards. Counters are process-local,
not a durable completeness denominator.

Only exact per-ID Queue receipts settle durable rows. Generic 2xx and retryable
failures retain them; permanent rejected rows are tombstoned and counted as
quarantined, and no copy of them is kept. A shard with middle corruption is counted
and never replayed; a clean compaction output of it (`.spl.next`) replays instead,
otherwise the shard is deleted. (0.7.69 and older also wrote `quarantine.jsonl` and kept
`shard-*.corrupt` files that nothing read; 0.7.70 deletes them.)
Restart needs a fresh grant for the same identity. A logout pauses delivery, except for rows the ended
session already captured (see "Sending after a logout" below). Changing
account/mode/profile cannot replay old rows into the new identity. Unacknowledged
rows remain for a later matching identity and grant. An idle prior identity stays
until the 48-hour age bound or an explicit withdrawal; from 0.7.70 there is no bound
on the number of identities. The hashed identity directories are the list of
identities, and each one is forced into its root before its first row. (0.7.69 and
older also kept an `active.json` index with `index.lock` and retired idle identities
outside the newest 16 after an append; 0.7.70 deletes a leftover index under that
client's own lock and leaves the lock file, whose inode those clients rely on.) Live
sibling writers remain protected.
Token withdrawal or 403 purges the current identity and every idle identity
directory, including a cold start before any grant. An account-erased receipt targets only the sent identity. Purge
markers and age bounds are checked again before dispatch; restored rows still need
a valid fresh server grant and are subject to the server's deletion tombstones.
The 48-hour local retry bound does not change indefinite eligible server history
or explicit account erasure. Actual-client restart/fault QA remains outstanding.

## Drop event linkage (0.7.64)

Each pending drop retains the session and sequence assigned at its own click. The ground-spawn and inventory-change
paths use that identity even when several clicks precede either signal or spawns arrive in a different order.
A ground spawn corroborates only one pending action. Its added quantity bounds the emitted drop quantity, and
pending drops that share the same inventory baseline cannot consume that loss repeatedly.

The existing fields and their meaning are unchanged: a drop sequence identifies its own action within one session.
Expired, interrupted and unobserved actions still remain incomplete; the fix does not infer a recipient or recover
events that an older client missed. The exact-JAR acceptance suite includes rapid mixed-item drops and repeated
same-item drops with both inventory-first and spawn-first ordering.
