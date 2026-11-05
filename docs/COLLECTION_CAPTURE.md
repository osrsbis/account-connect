# Collection permissions — 0.7.66 candidate

- `X-Delivery-Capture: on` plus `X-Delivery-Account` grants collection for the current link token and game account.
  The grant is accepted only from the official API. Missing or failed responses clear the new delivery grant.
- `X-Collection-Account` also binds optional gameplay/progress and inventory grants to the response account.
  A later account cannot reuse that binding.
- Staff identity remains separate. A delivery grant enables no staff API access, overlays or retention exemption.
  The legacy staff grant remains supported for existing clients and staff workflows.
- Separate `X-Delivery-Screenshots`, `X-Delivery-Clips` and `X-Delivery-Drop-Proof` directives control the recorders.
  The server's explicit off switches remain effective. No configuration item or user-facing text is changed.
- Captured participant fields use the existing capture projection. Closed own-state, variable, signal and verification
  projections remain closed even when delivery capture is granted.
- Media uploads carry their capture account. Worker queues and retries retain that account and refuse uploads after
  a token/account change. New non-staff receivers verify it against a stored compatible account snapshot.
- The unchanged-state policy heartbeat includes delivery grants so quiet clients can receive withdrawal.
- `X-Plugin-Inventory: on` separately permits a bounded local plugin inventory in snapshots:
  - At most 256 class identifiers, each with independent `enabled` and `active` booleans.
  - At most one sample per 15 minutes; no configuration, credentials, usage events or arbitrary plugin fields.
  - Observation time and truncation are explicit. Enabled or active does not establish feature usage.
- Existing `X-Broad-Capture` controls own raw variables and the ten declared own-storage containers.
  Group-Ironman storage is additionally read only in a group mode after a current-session container observation,
  labelled `shared_visible_contents` and excluded from personal wealth. Unproven boat storage remains excluded.
  The retired maximum/firehose collector stays disabled.
- Own combat, signals, state and variables may use the existing legacy receiver under `X-Own-Gameplay` when no Queue
  scope is active. Existing limits and projections remain; this is not a claim of durable public restart history.
- Exact-client game, layout, upload/readback and performance acceptance remain release work. A source implementation,
  a permission header, a stored metadata row and a readable evidence object are separate facts.
