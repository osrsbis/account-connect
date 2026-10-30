# Event delivery and retry

How events reach the server: delivery counters, the public bank journal, the latest bank in the snapshot (0.7.62)
and the public Queue journal. Each section states its own release state.

## Queue delivery health (local candidate)

`source.capture_health.queue_delivery` separates valid broker-acceptance receipts
from archive-plus-D1 receipts, by a fixed event-family list. The ten own-state kinds, seventeen own-vars families and fifteen own-signal families have separate counters. It also reports source-to-journal handoffs, confirmed
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

## Latest bank in the snapshot (0.7.62)

The server keeps only the newest complete bank of a player, from the snapshot. The
snapshot's `bank` block carries `manifest_sha256` and `item_stacks`, built from the same
canonical list as a bank visit, when every item is in one stack. On a token with the public
bank receipt, a visit's `bank_visit_open` and normal `bank_visit_close` send no open or
close item list when a snapshot queued for upload on the same token and account holds that
exact bank with a known read time. They say so with `manifest_in_snapshot: true`, and the
close sends `end_item_stacks` instead of `end_chunks`. The open counts the bank-open snapshot,
whose read has no confirmed time yet; a failed upload is never counted. The close forces no
upload: it waits up to 250 game ticks for a regular snapshot the server accepted (2xx) that holds
its end bank with a known read time. Otherwise, and at a logout, hop or new open first, the list
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

A successful token-bound `X-Event-Queue: ge-v1` or `own-history-v1` response,
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
failures retain them; permanent rejected rows become metadata-only quarantine.
Restart needs a fresh grant for the same identity. A logout pauses delivery, except for rows the ended
session already captured (see "Sending after a logout" below). Changing
account/mode/profile cannot replay old rows into the new identity. Unacknowledged
rows remain for a later matching identity and grant. After a successful append,
idle identities outside the newest 16 are retired and counted as gaps. A missing
or incomplete index and an empty read never cause identity retirement. Live
sibling writers remain protected and may temporarily exceed the identity bound.
The index lock keeps a stable file and covers the retirement decision. Older
binaries that delete that lock file must not share this root during rollout.
Token withdrawal or 403 purges the current identity and idle retry files (including
unindexed directories), including a cold start
before any grant. An account-erased receipt targets only the sent identity. Purge
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
