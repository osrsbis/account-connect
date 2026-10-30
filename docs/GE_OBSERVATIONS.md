# Grand Exchange observations

Every linked player sends the Grand Exchange events below. The field-verification plan at the end is still open.

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
The existing Plugin Hub install warning remains unchanged. Broad-capture server
grants are operational rollout controls; this local change does not approve or
activate their rollout. They are not separate user choices.

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
