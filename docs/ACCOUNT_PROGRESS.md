# Account progress block

The `account_progress` snapshot block is sent when the server answers an upload with `X-Account-Progress: on` for
the current link token. Since 2026-10-29 the server does that for every linked player on plugin 0.7.47 or newer,
unless the token or account is switched off. Revoking the link stops it. The checks listed under "Remaining
real-account checks" are still open, except the own-log collection scan, which was checked on a test account for 0.7.62.

## Additional own-account progress (on for linked players since 2026-10-29)

The additive `account_progress` snapshot block retains `schema_v: 1`. It requires a
successful response with **`X-Account-Progress: on` for the current link token**.
Batch1's `X-Broad-Capture` does not enable it. The plugin adds no setting for it and
does not change the Hub warning.

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
scan never mean unowned. Since 0.7.62 every linked player's own-log open runs
this scan. When it is count-verified, the snapshot's `collection_log` carries the
whole obtained list with `complete: true`, `game_obtained_count`,
`game_total_count` and `verified_at_ms`. A new item ends that until the next
verified scan. Otherwise the block stays partial as before. Unknown ownership
rejects its slots, and explicit zero quantities never enter the obtained-item set.

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
copied. Live client checks of timing, chat wording and ingestion are listed under
"Remaining real-account checks" below.

## Capture-first recovery (on with the account progress grant)

This batch extends the **`X-Account-Progress: on` grant**,
not a user toggle. Both GE history and collection scanning use that token-bound
grant, account/profile identity, two-tick login settlement and existing snapshot
writer. Revocation strips the entire block from cached uploads. Since 0.7.62 the
collection scan also runs for every linked player without the grant (see below).

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

## Remaining real-account checks

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

Apart from the own-log collection scan, these real-account and backend checks are still open.

## Bank recovery and plank sack (on with the account progress grant)

`account_progress.storage_recovery.schema_v: 1` uses the existing token-bound
`X-Account-Progress: on` grant. The existing Hub warning is unchanged, and no setting
or consent screen is added.

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
