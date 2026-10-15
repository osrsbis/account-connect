# OSRS Best in Slot — Account Connect

A RuneLite plugin that connects your Old School RuneScape account to
[osrsbestinslot.com](https://www.osrsbestinslot.com) so its gear and skill
calculators auto-fill from your real account — no manual data entry.

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
- Grand Exchange offers, rune pouch
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
`complete` is always false: a page render or quiet ticks do not establish a full
log scan, and absent items never mean unowned. No automatic search/menu action is
performed. The existing legacy `collection_log` block remains unchanged.

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
