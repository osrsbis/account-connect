# Verification evidence — 0.7.66 candidate

Verification requires the account-bound `X-Verification` grant; images additionally require
`X-Verification-Frames`. The receiver supports these from 0.7.66. Missing/refused grants, logout,
token/account changes and shutdown discard pending evidence. Existing configuration and all user-facing
wording are unchanged. Collection rollout controls activation separately from the source release.

## Event evidence

The event queue keeps the actual projected event ID, type, account, token, grant epoch, source tick,
original snapshot revision and a deep copy of expected fields. The originating event carries
`verification_tick`, `verification_revision` and, for a recognized game line, bounded `verification_text`.
Frame and screen rows carry `source_event_id`, `source_event_type`, `source_event_tick` and
`source_event_revision`. These refer to the originating event, not the verification row's own event ID.
The snapshot revision identifies the last built snapshot; it does not prove server persistence.

A client-tick poll looks for relevant widgets immediately, for at most three game ticks after the event.
It does not wait for the 20-second random-sample gap. At most eight events wait for rendering; overflow is
explicit missing evidence. Both event and random samples share the existing 60-sample/30-frame rolling
hour budgets. Relogging does not refill them. Samples beyond the hourly budget are not captured; this is
sampling, not a complete activity history. A repeated event retains its own event ID and expected values.

Only the selected widget rectangles remain in the image; all other pixels are black. Known trade and
bank-PIN interfaces and open menus block verification capture. Hidden, clipped, ambiguous, detached or
changed widgets are rejected. The selected widgets are revalidated at the next drawn frame, on the same
read tick. Account/token/grant identity is checked before drawing and again after asynchronous encoding.
A delayed source remains attached to its original revision and source tick.

| Event | Selected evidence | Comparison and limits |
|---|---|---|
| Boss/activity kill count | The individual visible chat line exactly matching the recognized game message | OCR count and grammar compared with the event count. No unrelated chat rows or orbs. Repeated identical visible lines are ambiguous. |
| Level increase | Level-up dialogue TEXT1/TEXT2 when the level message materializes | OCR skill/level compared with the stat event. A suppressed or dismissed dialogue is unknown. |
| Minigame game lines | Exact matching game-message widget, indexed by the existing anchored `MinigameLines.LINES` grammar | OCR independently parses the declared grammar and event count/total/result. |
| Minigame dialogues | Exact known dialogue/message widget matching `MinigameLines.DIALOGUE` | Same indexed grammar comparison. Unknown patterns are not captured. |
| Minigame result screens | Named components in `MinigameLines.SCREENS` | Screen numbers compared with the immutable event's screen map; component meaning remains provisional until observed in real data. |
| Loot/reward panels | Item widgets in the existing CoX, ToB, ToA, Barrows, clue, Colosseum, Lunar and loot-chest groups | Item quantity pixels and expected item metadata retained. Quantity OCR does not establish sprite/item identity and must not produce an item-identity match. |
| Progress, pet, attempt-end game messages | Exact matching recognized own game-message line | Text corroboration only; equal text does not independently validate all semantic fields. |
| Death, slayer or loot without a visible matching source | No unrelated screenshot fallback | Explicit source-not-visible outcome after the short render window. Ground loot and settled death events need not have a result panel. |

The capture selectors support the listed existing parser tables and reward groups, not every activity or
all UI layouts. Frames use actual game pixels; there is no OCR text rendering or reconstruction. Wrapped,
multicolour or stretched text may be unreadable by a particular OCR engine and must remain unknown.
No screenshot is evidence for a source event merely because both happened near the same time.

## Wire and comparison

Numeric random samples retain the original `screen/ocr_numeric` contract. Event evidence uses
`screen/ocr_event`, with result inputs containing rectangle, widget, reader and source field. Readers are
`kill_count`, `level_up`, `minigame`, `integer`, `text` and `item_quantity`; minigame grammar readers also
carry `pattern_table` and `pattern_index`. Event screen rows are split into at most four results to stay
below the receiver's 8KB event-field cap. Unknown outcomes carry a reason without a fabricated rectangle.

A `frame/capture` row records the actual drawn tick, delay and image dimensions. A random 16-hex reference
joins this row, screen results and `kind=verify` PNG media. Screenshots retain their actual capture time;
source revision and expected operands are never replaced with current client values after encoding.
The offline report checks identity, source-event pairing, tick bounds, revision and image hash before
comparing independently read pixels. Expected values, actual displayed values, inputs, source identity and
snapshot revision remain in the output. Missing events, screenshots, source values or unambiguous OCR are
UNKNOWN; mismatches are review leads, not automatic proof of a game bug.

## Random state sampling

Occasional random ticks and non-transient occasions retain the 20-second gap. Checks cover XP/levels,
derived levels, orbs, server/client variables, known numeric counters, inventory, equipment, bank occupied
slots and location. Two client paths agreeing proves agreement only. Event triggers no longer silently
substitute these generic numbers for their activity-specific evidence.

No notifications or recurring analysis jobs are introduced. Real incoming evidence validates supported
layouts and readers after release; source tests and fixture OCR do not claim broad live accuracy.
