# Verification sampling — 0.7.66 candidate, inactive

- Verification is a separate testing grant. `X-Verification: on` enables rows for the current link token.
  Frames additionally require `X-Verification-Frames: on`. The backend grants both only to version 0.7.66+;
  an older or missing version receives off.
  Missing or failed policy replies, network failure, token change, logout and shutdown clear it.
  Staff capture and kill-context grants do not enable it. Non-staff `X-Screenshots: off` still blocks trade
  screenshots but does not override the separate frame grant. An operator screenshot off-switch disables frames;
  pausing uploads disables both grants, and the media handler refuses paused uploads. No new configuration setting is added.
  Collection rollout controls activation; the existing confirmation and all user-facing wording stay unchanged.
  This source change does not itself activate a token.

- `VerificationProbe` samples activity occasions and random game ticks, at least 20 seconds apart, at most 60 samples
  and 30 requested frames per rolling hour. Relogging does not reset these budgets. Each check carries its inputs,
  expected and observed values, game tick, plugin version and the last built snapshot's `captured_at_ms` revision.
  That revision identifies a built snapshot, not proof that the server stored it. Missing operands mean unknown.
  A newly received grant also queues one `verification_ready` sample under the same budgets; it does not depend
  on winning a random draw or mislabel the grant as a game login.

- Checks currently cover skill XP/levels, derived levels, orbs, server/client activity and account variables,
  visible numeric counters, inventory, worn equipment, bank occupied slots and location observations.
  Agreement between two client readings is not independent evidence of game meaning. Cosmetic equipment overrides
  and UI layouts may produce mismatches that require investigation.

- Verification events use the closed public transport projection for both player and staff clients.
  Each result's match is recomputed from retained operands; omitted operands cannot retain a pass.

## Numeric screenshot evidence

- Frames use `kind=verify`, a shared random 16-hex reference, and the actual frame capture time.
  The frame row preserves the sample's original snapshot revision through asynchronous encoding.
  A token/session change or a later game tick cancels the pending frame. Missing frames remain missing evidence.
  Trade interfaces, the bank PIN interface and open context menus prevent a frame.

- The candidate retains only numeric interiors of known orb, bank-slot and counter widgets; all other pixels are black.
  Only full decimal or comma-separated integer text, optionally ending in `%`, qualifies. Labels and abbreviations
  are excluded. Coordinates are scaled to the image's native pixels, rounded inward, and sent as `screen/ocr_numeric`
  results with expected values. No downscaling is applied. Empty selections produce no frame.

- This intentionally limits screenshot coverage. It does not yet cover trade panels, chat messages, level-up dialogue,
  all minigame screens, or every activity. Live validation must check fixed/resizable layouts, stretched mode, overlays
  and tooltip overlap before enabling capture. Numeric-region exclusion alone is not that acceptance evidence.

- The server's verification pool and the offline OCR report use the same frame reference. The report distinguishes
  MATCH, MISMATCH and UNKNOWN, and requires matching capture tick and snapshot revision. A mismatch is a review lead.
  Unreadable OCR, absent images or incomplete parts never pass. The server's `ops/verification/README.md` describes input
  and execution. No notifications are introduced. The separately granted plugin inventory is described in `COLLECTION_CAPTURE.md`.
