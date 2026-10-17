# OSRS Best in Slot — Account Connect

A RuneLite plugin that connects your Old School RuneScape account to
[osrsbestinslot.com](https://www.osrsbestinslot.com) so its gear and skill
calculators auto-fill from your real account — no manual data entry.

## Own-event provenance (0.7.18 candidate)

Own events carry a random collector-session identifier, emit sequence and plugin
version. These identifiers rotate on login, account, world profile, link, local
profile or process changes. Replayed journal rows retain their original context.
The sequence covers public emits, not successfully delivered events or complete
gameplay. World profile is separate from account mode. Mode is observed from the
own-account varbit on a matching logged-in client tick; absent definitions,
unsettled identity, clock reversal and context older than ten seconds stay
unknown. No raw local profile ID, token or player name enters this context.
Backend projection support and exact-client QA remain release prerequisites;
older projected archives do not acquire this metadata retroactively.

## How it works

1. On osrsbestinslot.com, open **Connect account** and copy your one-time link token.
2. Install this plugin, open its settings, and paste the token into **Link token**.
3. While you are logged in, the plugin periodically reads your account state and sends
   a snapshot to osrsbestinslot.com, keyed by that token. The site reads it back to
   pre-fill the calculators.

## What it sends

The snapshot is tied to the link token you chose (not your real identity) and contains
**game account state only**:

- Skills (levels, XP), total & combat level
- Quest states, achievement-diary and combat-achievement progress
- Slayer points / current task
- Worn equipment, inventory, and bank contents (bank only after you open it in-game)
- Grand Exchange offers, displayed recent GE history (rollout-gated), rune pouch
- Collection-log entries you have obtained (captured only while the collection-log interface is open)

It does **not** read or send your password, email, payment details, or any Jagex-account
credentials — RuneLite does not expose those to plugins.

## Privacy

Data is sent only while the plugin is enabled and a valid link token is set. Clear the
token (or disable the plugin) to stop sending. The snapshot is associated with the link
token you pasted.

## Building

```
./gradlew compileJava       # build the plugin
./gradlew runClient         # run a dev client with the plugin loaded
```

## Credits

Plumbing patterns (injected client services, scheduled upload, collection-log cache walk)
follow the open-source [WikiSync](https://github.com/weirdgloop/WikiSync) plugin by
andmcadams (BSD-2-Clause). The data model is original.

## Additional own-account progress (batch 2, inactive)

The additive `account_progress` snapshot block retains `schema_v: 1`. It requires a
successful response with **`X-Account-Progress: on` for the current link token**.
Batch1's `X-Broad-Capture` does not enable it. This header must remain disabled
until the single-install disclosure covers the new capture and ingestion is approved; this change does
not activate a server policy, change the Hub warning, or add settings.

| Field | First-party source and interpretation |
|---|---|
| `slayer.task_target_id`, `task_remaining`, `boss_id`, `area_id`, `task_original` | Varps 395, 394, 2096, 4258; varbit 4723. Boss only for active target 98; area/original omitted or null without an active task. Names remain a server cache lookup. |
| `prayer_unlocks` | Rigour 5451, Augury 5452, Preserve 5453, Deadeye 16097, Mystic Vigour 16098. Unlocks, not currently active prayers or level eligibility. `knight_waves_state` is raw varbit 3909; no unverified Chivalry/Piety threshold is asserted. |
| `watson_clues` | Easy/medium/hard/elite holdings, varbits 5186–5189. |
| `combat_achievement_points` | Varbit 14815. |
| `collection_log` | Absolute item quantities from script 4100, observed item count/time; game obtained/total counts from varps 2943/2944; notification setting from varbit 11959. Varbit 12061 must positively identify an own log. Unknown host status rejects slots; house-host totals are suppressed. |
| `recent_signals` | Exact own GAMEMESSAGE/SPAM lines for clue completion counts, collection additions (name only), combat tasks (tier/name and optional game task ID), and salvage sorting (shipwreck/item name/quantity). Each carries `source` and `observed_at`. |

Observations are session-local and additive. `recent_signals` keeps the latest
observation per kind/subject, at most 128, with `signals_evicted`; it is **not an
exhaustive event stream** and repeated salvage lines must not be summed as loot.
Collection slots are capped at 4096; repeated draws replace absolute quantities.
Passive page renders remain incomplete. The capture-first follow-up below adds an
own-log Search scan with explicit count verification. Missing items in a partial
scan never mean unowned. The legacy collection block remains partial; unknown
ownership now also rejects its slots, and explicit zero quantities never enter
the legacy obtained-item set.

Reads wait for an identified account, positive Hitpoints level, and two game
ticks. A single cloned varp array supplies each structured snapshot. Missing,
default, or out-of-range varbit definitions yield unknown (null/omitted), not
false or zero. Collection totals stay unknown until a positive maximum and a
consistent obtained count are available. Known zero is preserved. Account,
profile mode, token, hop, relog, disconnect, and plugin shutdown discard observations;
ordinary region loads retain last-seen observations but suspend reads. Revocation
also strips the new block from cached snapshot uploads. No third-party plugin is
loaded, no other player's data source is added, and no new runtime dependency is
introduced.

Source provenance: exact-pinned RuneLite Plugin Hub implementations, RuneLite gameval 1.13.1,
and game-cache revision 2740. Capture fields use checked variable definitions and source references.
Numeric source IDs are checked against those cache-derived tables; optional
new enum fields are not linked at runtime. The implementation and tests are
independently authored; no audited plugin implementation or bundled dataset is
copied. Live client verification remains required for timing, chat wording,
collection transmissions, and ingestion before enabling this grant.

## GE execution observations (local correctness batch)

Existing `ge_offer`, `ge_progress`, `ge_buy`, `ge_sell`, `ge_cancel` and
`ge_collect` rows retain their cumulative `qty`, `gp`, listing `price`, slot,
side and offer key. `ge_observation.schema_v: 1` adds:

- `observed_at_ms`: callback observation time, retained when an unknown-account
  login event waits for identity. Outer `ts` remains enqueue time.
  `counter_observed_at_ms` can be earlier on collection.
- `cumulative_qty/gp`, `delta_from_qty/gp`, `delta_qty/gp`, and an increasing
  per-key `sequence`. Deltas span emitted observations, including coalesced
  partial updates; a terminal or collect carries any pending difference once.
  A first sight without an observed empty slot is `baseline_only` with zero
  credited delta, even when already filled. An observed empty slot supplies a
  zero baseline and earlier observation bound for an instant fill. These are counter differences, not individual trades.
- `interval_start_ms` and `interval_end_ms`: bounds between observations,
  conditional on inferred offer continuity. `fill_time` is always `unknown`.
  Backward wall-clock steps or counter ambiguity remove the bounds.
- `listing_price` explicitly identifies the limit price. `execution_price`
  and `tax_gp` are unknown (null, possibly omitted by JSON serialization).
  `gp_semantics` is `runelite_spent_tax_unknown`: no tax formula, gross/net
  conversion, listing-price multiplication or per-fill price is invented.
- `identity`: `inferred_session_slot`, `inferred_across_gap`, or
  `ambiguous_counter_regression`. Quantity or GP regression retains the previous
  high-water tuple and quarantines subsequent deltas (null, `delta_status:
  unknown`) until a new offer can be distinguished. An identical terminal-to-active
  reuse without an observed empty slot is also ambiguous.

Keys rotate on observed collection, changed item/side/total/listing price,
account, profile mode, link token and restart. Matching offers retain their key
across a hop/relog, with gap inference explicitly labelled. A partial increase
on replay is retained, not silently absorbed into a new baseline. Synthetic
EMPTY events outside LOGGED_IN are ignored. Startup snapshots baseline without
crediting historical totals. GE rows stamp the current account hash and profile
at the callback, rather than depending on the slower snapshot loop.

`offer_key_scope: collector_session` is intentional: RuneLite supplies no global
order identifier. An unseen identical relist, activity completed and collected
elsewhere, or overlapping observations from separate clients cannot be resolved
with certainty. There is no persistence or cross-client identity claim. Missing
legacy `profile_type` means unknown, not STANDARD; missing `ge_observation` means
legacy cumulative data, not zero incremental fill. Consumers must deduplicate
`event_id`, partition by account/profile and collector offer key, track sequence
and cumulative watermarks, and never sum legacy cumulative rows together with
new deltas. The existing ledger v1 requires a versioned consumer update before
using this contract; it treats post-terminal rows as new offers and does not
partition by profile. Repeated terminal counter corrections must not become
additional completed offers. Gaps/unknown intervals must remain visible.

No new configuration, consent screen, grant or external data source is added.
The product policy is one truthful Plugin Hub install disclosure. Existing broad
capture server grants are operational rollout controls, intended to default on
for everyone after the updated warning and backend support are approved; they
are not separate user choices. This local change does not approve or activate
that rollout.

Implementation is independently authored. GE-specific references were checked
in the existing 95-plugin audit corpus (2026-10-14/15): Flipping Copilot
`c8dbb515` (`GrandExchangeOfferEventHandler`, cumulative deltas and consistency),
Flipping Utilities `4420ad64` (`OfferEvent`, average versus listing price), and
Exchange Logger `79d83f3b` (`ExchangeLoggerWriter`/`SlotStatus`, cumulative values,
long GP and dedup). The local ledger v1 source/result and batch-C audit were also
reviewed. Their tax assumptions, inferred fill timestamps, datasets and source
implementations are not copied.

### Field verification and rollout prerequisites

Use only the existing authorized test accounts, manually operated in an isolated
client, with a local capture sink. Do not change consent settings or contact
production ingestion. This plan does not provision accounts or authorize extra
spending. Use an approved small item/GP budget for live orders.

1. Record build SHA, RuneLite version, account alias and mode. Place a small buy
   with a limit above execution price; obtain multiple partials, complete and
   collect. Compare raw callback counters to rows. Deltas must telescope from
   the first observed baseline; collection adds no already-reported quantity/GP.
2. Repeat with a sell, partial cancel, zero-fill cancel, and collection while
   still active. Record actual counter-reset behavior; any decrease must mark
   the remaining interval unknown, never re-credit the recovered total.
3. Re-list identically after EMPTY; then change price, quantity, item and side.
   Confirm key rotation and that a new baseline never credits old cumulative
   fills. Exercise a fast active-to-terminal pair and repeated callbacks.
4. Hop, disconnect/reconnect and relog during partial fills. Verify synthetic
   clears are ignored, partial/terminal replays retain the key only for matching
   signatures, and observation time predates deferred identity resolution.
5. Restart/disable/re-enable mid-offer. Continue manually from another authorized
   client, then return. Confirm baseline-only history and session-scoped keys;
   do not assert cross-client dedup. Switch authorized account and, where
   available, seasonal/beta mode: no counters or account labels may cross modes.
6. Replay captured fixtures to a local backend candidate alongside legacy,
   slotless, duplicate, out-of-order, missing-profile and unknown-delta rows.
   Verify versioned normalization and event-ID idempotency. Test retries,
   sequence gaps and overflow without double counting.

Real-client tests above have not been run here. The unchanged one-per-100-tick
live partial cadence stays bounded; reconnects and terminal corrections may add
rows. Backend acceptance/preservation of nested metadata, mixed-version ledger
migration, bytes per event, queue/spool size, ingestion capacity and retention
need separate server-side verification at expected concurrency before rollout.
Exact trade timestamps, individual execution prices, tax allocation and unseen
remote offer history remain physically unobservable from this callback.


## Capture-first recovery (local, inactive)

This batch extends the existing **disabled `X-Account-Progress: on` rollout control**,
not a user toggle. Both GE history and collection scanning use that token-bound
grant, account/profile identity, two-tick login settlement and existing snapshot
writer. Revocation strips the entire block from cached uploads. No server, Hub,
release configuration or production writer is changed. Keep this grant disabled
until the installation disclosure, ingestion and field checks below are approved.

`account_progress.ge_history` contains:

- `source: ge_history_widget_383_3`, `collector_session` (random per capture
  lifecycle), `identity_scope: account_profile_collector_session`, `complete: false`,
  `observations_evicted` and at most eight `observations`.
- Each observation has `sequence`, `observed_at_ms` (first matching UI read),
  `confirmed_at_ms` (second matching read) and ordered `rows`.
- Each row has `row_index`, `item_id`, `quantity`, `side` (`buy`, `sell`, or
  `unknown`), original `state_text` and `price_text`, `displayed_total_gp` and
  `displayed_each_gp` where strictly parseable, `execution_time: unknown`, and
  `price_semantics: displayed_values_tax_and_rounding_unresolved`.

The six-child row layout is checked before capture. Hidden, null, partial and
oversized layouts wait for recovery. Reads occur at most once per game tick;
two matching reads are required, with at most one new observation per five ticks.
Limits are 40 rows/window, 128 state-text characters and 512 price-text characters
per row. Overflow/unsupported price text remains unknown; raw supported-length
text survives. No multiplication, division, inferred tax or execution timestamp
is introduced. UI time is wall-clock observation time, not a trade interval;
clock adjustments may reverse the two observation timestamps.

Unchanged history is suppressed across reopening in the same collector session.
Identical rows remain distinct by position. An intervening changed window can
produce another observation of a prior-looking window: there is no game trade ID.
Backend retries deduplicate by account/profile plus `collector_session`/`sequence`;
these observations MUST NOT be summed with each other or live GE deltas. Recovery
is bounded recent UI evidence, not an exhaustive durable execution stream. Hops,
relogs, account/mode/token changes and restart reset its session and buffer.

`account_progress.collection_log` retains absolute `items` quantities and adds
`observed_positive_item_count`, `scan_expected_obtained_count`,
`scan_expected_total_count`, `scan_started_at_ms`, `scan_verified_at_ms`,
`scan_status` and `completeness_basis`. On script 7797, a settled, positively
identified own log with a visible Search widget may invoke exactly the collection
Search action and script 2240. This is RuneLite's documented sole `menuAction`
exception ([current rules, checked 2026-10-15](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features#sensitive-apis)).
No packet-permission override, general menu automation, chat input or extra button
is used. API failure falls back to passive script-4100 observations.

There is one attempt per open, at least 100 ticks between attempts, no automatic
retry in that open, a 20-tick timeout, and a 4096-item ceiling. A scan starts with
fresh item evidence. After three quiet ticks, **distinct positive-quantity items**
must equal the game's obtained count, with unchanged, valid obtained/maximum
counts from scan start. Only then is `complete: true` with
`completeness_basis: own_search_scan_unique_count_matches_game`. This verifies
obtained-item coverage, not every unowned item definition or historical acquisition.
Explicit zero-quantity transmissions during the scan are retained without counting
as obtained; absent items in incomplete scans remain unknown. Zero obtained can be
verified only against a known positive game maximum. Slot/count changes invalidate
completion. Early close, timeout, host/unknown ownership, old-client failure or
missed setup during login settlement cannot claim completeness. Last verified
scan timestamps describe observation time, never acquisition time. No adventure
log is scanned; both legacy and new slot handlers require positive own ownership.

Bank recovery is now implemented by the bounded, inactive storage batch below.
Legacy `bank_session` and `bank_visit_*` remain unchanged: their deposited/withdrawn
directions are inferences from container differences, not authoritative transfer
transactions. Recovery observations are an additional evidence source and MUST
NOT be added to those legacy totals.

Independent implementations were informed by these exact pinned BSD-2-Clause
sources, not copied source or datasets:

- Flipping Copilot `c8dbb5155c44156ed3050c50155b086c25815556`,
  `rs/GeHistoryStateRS.java`: widget 383:3 and six-child row layout.
- GIM Hub `890e250370e468dc96c47b95c4023953748a1af4`,
  `CollectionLogManager.java`: setup/transmission scripts, count verification and
  adventure-log guard; `items/ItemTransferQueue.java`: inferred container transfer
  reconciliation, assessed and deferred here.
- WikiSync `1ff4ea0653395b552844d658d3668815d82e0a79`,
  `SyncButtonManager.java`: Search plus script 2240. No WikiSync button or extra
  consent workflow is introduced.

### Release disclosure and outstanding field gates

Proposed public installation disclosure, pending review against the exact expanded artifact:
“This plugin sends your linked game-account data to osrsbestinslot.com, a service
not controlled or verified by RuneLite: your account name/hash/type, world/location,
skills, quests, diaries, own collection-log progress, equipment, inventory, bank
and other observed own storage; GE offers and displayed recent GE history;
own trades and item movements, loot, deaths, session times and gameplay observations.
Separately enabled progress/gameplay uploads include own combat/NPC encounters,
skilling, travel and raw settings/progress observations. Observations can be partial
or unknown. Your IP address reaches the service. Eligible events can replay from a bounded local journal for up to 48 hours,
with at most 5,000 rows or 5 MiB per journal. Local files are cleaned on journal
access or token withdrawal; files can remain while the client or grant is inactive.
It does not receive your Jagex credentials.”

This public artifact removes counterparty/nearby-player context and shared group
storage before transport, and disables screenshots, clips and broad firehose uploads
regardless of remote grants. Private staff/Ops capture remains a separate distribution
and disclosure obligation. The currently published Hub warning has not been changed;
this draft and the expanded artifact still need release-owner and Hub acceptance.
No new permission toggle or account action is introduced here.

### Public Queue retry contract (local candidate)

A successful token-bound `X-Event-Queue: ge-v1` or `own-history-v1` response,
a settled own account/mode and a known RuneLite profile are required. Only the
14 Queue-supported event types enter the public journal: six GE types plus
bank_session, equip_change, loot, kill_count, level_up, xp_gain, alch and pickup.
The existing first-party field projection runs before disk, during recovery and
before POST. Other public families and snapshots retain their existing memory
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
Restart needs a fresh grant for the same identity; logout pauses delivery. Changing
account/mode/profile cannot replay old rows into the new identity. Idle prior retry
identities are retired; another live writer is preserved. Token withdrawal or 403
purges the current identity and idle indexed retry files, including a cold start
before any grant. An account-erased receipt targets only the sent identity. Purge
markers and age bounds are checked again before dispatch; restored rows still need
a valid fresh server grant and are subject to the server's deletion tombstones.
The 48-hour local retry bound does not change indefinite eligible server history
or explicit account erasure. Actual-client restart/fault QA remains outstanding.

Remaining real-account checks, using the existing authorized manually operated
client and a local sink:

1. Capture actual buy/sell, single/multiple quantity, tax, large-GP and unknown
   format GE rows; verify text, IDs, quantities, unit/total display and row order
   against the UI. Open briefly, reopen unchanged, reopen after a new trade and
   change interfaces during rendering. Confirm no execution timestamp or double
   credit to the live-offer ledger.
2. Open own logs with zero, partial and many obtained entries; compare distinct
   positives to the game total and verify full transmissions and timeout behavior.
   Close early and reopen; manually view an adventure log and return to your own.
   Confirm no host item ever enters either collection block. Test concurrent
   installed collection plugins and the visible Search/reset behavior.
3. Repeat on supported old/current clients, profile modes, hop/disconnect/relog,
   account/token changes and disabled/revoked grants. Confirm fallback stays passive
   when setup/widget/API support is missing and no observations cross identities.
4. Replay actual payloads into a local backend candidate. Verify schema preservation,
   null/omitted unknowns, session/sequence idempotency, history/live-offer separation,
   request size and retention limits, and server grant disabled-by-default behavior.
   At maximum text lengths GE history can occupy hundreds of KB; size/capacity
   acceptance is a release gate, not inferred from passing plugin tests.

These real-account and backend checks have not been performed in this worktree.

## Bank recovery and plank sack (local, inactive)

`account_progress.storage_recovery.schema_v: 1` uses the existing token-bound
`X-Account-Progress: on` grant. Keep it OFF until the single installation disclosure,
backend storage, mixed-client ingestion and capacity/rollout are accepted. No new
consent screen, settings, server change or release is included.

- `bank` and `inventory`: raw item-ID quantity maps, `last_seen_ms`,
  `full_or_partial: partial`, `source: item_container_changed`, `uncertain`.
  These retain observed container slots, not a protocol-proven complete account
  inventory. Bank reads require its visible widget and a nonempty callback;
  closing it, loading, rejected reads and closed-bank updates mark a gap. Empty
  bank updates remain uncertain (including genuinely withdrawing the final stack).
  Snapshot polling never refreshes these callback timestamps. No inferred deposit
  changes these observations or the legacy sticky bank/wealth baseline.
- `observations`: at most 64 rows, monotonic `sequence`, `collector_session`,
  `observations_evicted`, account hash, profile type, world, time and source.
  `bank_baseline` credits no delta. Later `bank_observed_delta.delta` contains signed
  raw item-count differences and `from_observed_at_ms`/`across_gap`.
  `authoritative_container_difference` describes the two observed states only;
  `transfer_attribution: unknown` deliberately does not assert a deposit, withdrawal
  or individual transfer time. Across-gap changes cannot recover gross movement.
- `deposit_box_recovery`: only fixed Deposit-1/5/10/All operations on verified
  deposit-box widget 192:24. A click arms evidence, never changes bank contents.
  At two ticks, a fresh inventory callback must contain exactly the expected
  single raw item loss; the result is still `delta_status: inferred`, with
  `inventory_delta`, `raw_item_id`, requested quantity, click/callback timestamps,
  menu operation and `bank_delta_status: unknown`. Failed, late or competing
  actions produce `uncertain`; unsupported Default/X, equipment/container buttons
  and withdrawal reconstruction are not inferred. Notes retain their raw IDs;
  there is no guessed note conversion or container-content expansion. Unseen
  concurrent actions can mimic the loss, so these rows are never authoritative
  bank credits. Loading/identity transitions discard pending attribution.
- `plank_sack.quantities`: plain/oak/teak/mahogany/camphor/ironwood/rosewood from
  checked varbit definitions 18171–18177, sampled from one cloned varp array at
  most every 100 ticks. Missing definitions/ranges stay unknown; valid zero is
  retained but does not prove sack ownership. `last_seen_ms` is sample time,
  `authority: client_varbit_values`, `server_freshness: unknown`,
  `ownership: unknown`, `full_or_partial: partial`. This complements raw
  `game_vars`; it does not change that existing block or infer farming readiness.

All capture waits for settled account identity. Account/profile/token changes,
hop/relog/disconnect, revocation and restart reset the lifecycle; normal region
loads retain last-seen states with a bank gap. No cross-session persistence or
cross-client dedup is claimed. Consumers deduplicate session/sequence within
account/profile, accept absent blocks from old clients, retain unknowns and gaps,
and never sum these observations with bank_session or bank_visit manifests.
The bank baseline and inventory are capped at 1500 received slots; malformed or
oversized reads are rejected. Each signed difference can contain up to 3000 raw
IDs. The 64-row window is intentionally bounded but can still approach several
MB in a pathological case; backend payload/retention acceptance remains required.
Snapshots are detached from mutable live capture state and keep the existing
snapshot writer/retry policy. This is recent recovery evidence, not a lossless
transaction log or a replacement for durable backend storage.

Independently authored after inspecting the pinned BSD-2-Clause sources:
GIM Hub `890e250370e468dc96c47b95c4023953748a1af4` (`ItemTransferQueue`,
`ItemRepository`, `BankItems`, `TrackedItemContainer`, `PlankSackItems`);
Dude Where's My Stuff `2f636ab8f75400219b6e35dd0412b42f510e528a`
(`ItemContainerWatcher`, `CarryableStorageManager`, `PlankSack`, `BottomlessBucket`);
Time Tracking Reminder `f66b6af1c6dfa2a5180fe6710a1126dcf4ce4e7b`
(`FarmingTracker`); Farming Patch Advisor
`fb0aa6115dd0a7dc71b146b42870b7c19b1ca10d` (`PatchTimerManager`).
RuneLite 1.13.1 compiled gameval constants independently verify the widget and
plank varbit IDs; numeric constants avoid new runtime enum-field dependencies.
No competitor source or dataset is copied. Region-local farming variables,
modal deferral and estimated growth durations require a separate verified batch;
no remote-patch readiness or exact harvest timer is invented here.

Targeted JDK 11 tests exercise callback-only baselines, signed/gapped deltas,
fixed deposit evidence, click-only failure, competing/delayed/multiple changes,
raw notes, empty/null/oversized data, long totals, bounded eviction, identity
reset and missing/zero varbits. Existing account-progress grant/revocation and
bank consumer tests remain applicable. Real-client tests are future separate:
manually check deposit-box operation IDs and callback order, fixed/All/X/failed
and noted deposits, bank close/reopen/final stack, sack quantities with/without
ownership, and supported old/current clients/account modes. Compare local sink
payloads to UI before backend acceptance. No live-client correctness is claimed.

Local verification for this batch (2026-10-15): OpenJDK 11 targeted suite passed
78 tests; the single full `./gradlew build --offline` passed 883 tests with zero
failures/errors/skips. Offline resolution used the existing cached dependencies;
no dependency was installed or upgraded. This verifies local compilation/tests,
not real-client behavior or deployed ingestion.
