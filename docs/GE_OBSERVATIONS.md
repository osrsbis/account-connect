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
- `native_tax_gp` (0.7.68): the game's own cumulative sale tax for the slot, the long varp
  `GE_TAX_SLOT_LONG_<slot>` (5754-5761) that the GE interface subtracts from the gross to show the net, read when
  the row is built (`native_tax_source: varp_long_when_row_built`). It is an observation, not `tax_gp`. On a real
  client (two sells, 0.7.68 test build) it is cumulative per offer (for example 10, 20 and then 250 GP for one offer of
  25 items at 516 GP that filled in three parts), the client and server values are equal, the tax step arrives 1-2 ms before the counter step on
  the same tick, and the collect does not clear it (the next offer does). The collected coins equal the gross counter
  minus this tax. The server derives the tax of each fill as the difference between rows of the same offer.
- `native_tax_server_gp` (0.7.68): the same varp's last value set by the server
  (`getServerVarpLongValue`), independent of client-side prediction. Both are Java longs; the server reads them as
  JavaScript numbers, exact up to 2^53.
- `steps` (0.7.68): when one row covers more than one counter change, or a tax var change, it lists each in
  order: `kind` (`counter` or `tax`), `at_ms` and `tick` of the callback, the cumulative quantity and GP at that
  moment, and for a tax step `native_tax_gp` / `native_tax_server_gp`. So two fills inside one throttle window keep
  their own prices (e.g. 200 and 300 GP, not one 250 average) and times. At most 16 per row; `steps_dropped` counts
  the rest (the row's totals still cover them). An identical repeat callback no longer moves `counter_observed_at_ms`.
- Times: `at_ms`, `observed_at_ms` and `counter_observed_at_ms` are when this client received the callback
  (`step_time: client_callback_receipt`); the outer `ts` is when the row was queued; the server's `created_at` is
  when it arrived. None of them is the exchange's execution time, which RuneLite's GE offer, its offer event and the
  GE scripts checked do not expose.
- `server_date_minutes`, `server_clock_read_ms` and `server_minute_seen_at_ms` (0.7.68): the game's minute
  counter (varp `DATE_MINUTES`, 3078, which the game's own scripts compare with server-set times), this client's
  clock when the row is built, and when this client saw that minute begin. All three are sent together, and only when
  the client saw the current minute begin as a +1 step while logged in (not at login, after a hop or a jump) at most
  65 seconds earlier, so a minute the game stopped updating is never sent. The boundary's client time is the game's minute start plus the
  network delay, which bounds the client clock's offset from the game's clock. Not an execution time.
- `delta_gp / delta_qty` over one step is the exact average price of the units filled in that step (a rational
  number; exact per unit only when one unit filled). Fills the game reports in one counter change cannot be
  separated.
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
rows. From 0.7.68 a partial fill held back by that window (or carried
across a restart) is sent as `ge_progress` once the window passes, even when no
later offer callback arrives, dated by its counter observation; uncertain
counters and terminal slots are never flushed this way. Backend acceptance/preservation of nested metadata, mixed-version ledger
migration, bytes per event, queue/spool size, ingestion capacity and retention
need separate server-side verification at expected concurrency before rollout.
Exact trade timestamps, individual execution prices, tax allocation and unseen
remote offer history remain physically unobservable from this callback.
