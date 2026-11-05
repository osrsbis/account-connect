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
| Minigame dialogues | Exact known dialogue/message widget matching `MinigameLines.DIALOGUE` | Same indexed grammar comparison. Unparsed contextual text uses the bounded discovery path below. |
| Minigame result screens | Named components in `MinigameLines.SCREENS` | Screen numbers compared with the immutable event's screen map; component meaning remains provisional until observed in real data. |
| Loot/reward panels | Item widgets in the existing CoX, ToB, ToA, Barrows, clue, Colosseum, Lunar and loot-chest groups | Item quantity pixels and expected item metadata retained. Quantity OCR does not establish sprite/item identity and must not produce an item-identity match. |
| Progress, pet, attempt-end game messages | Exact matching recognized own game-message line | Text corroboration only; equal text does not independently validate all semantic fields. |
| Death, slayer or loot without a visible matching source | No unrelated screenshot fallback | Explicit source-not-visible outcome after the short render window. Ground loot and settled death events need not have a result panel. |

The capture selectors support the listed existing parser tables and reward groups, not every activity or
all UI layouts. Frames use actual game pixels; there is no OCR text rendering or reconstruction. Wrapped,
multicolour or stretched text may be unreadable by a particular OCR engine and must remain unknown.
No screenshot is evidence for a source event merely because both happened near the same time.

## Discovery independent of successful parsing

`verification_discovery` is an observation of an unparsed game surface, never a completion/count claim.
It uses the existing verification grant, account identity and media pipeline; its receiver must support
the new source type before the plugin is published. It follows the legacy event-ingest route even when
the client has an unrelated Queue scope. It does not obtain staff access or a new durability guarantee.

- Clan Wars `ClanwarsGameover.MESSAGE/TITLE` and Temple Trekking `TrekRewards.TEXT/DESC/TITLE` are sampled
  on visible text changes without requiring a result parser. A bounded panel opportunity admits up to
  four named result/dialogue fields together; a title cannot consume a spacing window before its result.
- Unparsed NPC/message-box dialogue and GAMEMESSAGE/SPAM lines are sampled in current activity context:
  visible named result/HUD widgets, or a real change in an existing activity counter. Pest Control uses
  its visible portal HUD; Mahogany Homes uses changes in its named contract-progress vars. These are
  context observations, not proof that an activity completed. Context expires after eight game ticks.
- Activities with no known counter/interface still have a low-priority fallback: an unparsed
  GAMEMESSAGE/SPAM line may be sampled as `activity=unknown`, with the local player's region and world.
  No semantic activity is invented from the region. Player/public/private chat channels are excluded.
- Text is normalized and limited to 384 characters. Only an exact matching visible game-message widget,
  or the specific named result/dialogue widget, supplies pixels. There is no whole-screen/chatbox fallback.
- Contextual fields share a twelve-per-hour quota and thirty-second spacing between panel opportunities.
  Unknown-context fields have a separate four-per-hour quota and fifteen-minute spacing. Both remain
  inside the existing shared sixty-sample/thirty-frame limits. All quotas survive relog/toggle resets.
- Dedupe is bounded to 64 entries and one hour. Different named fields with equal text stay distinct.
  A field refused by the shared sample/frame/pending admission does not consume its discovery reservation
  or visibility transition: an unchanged field can be admitted later. A later render/encode failure is
  explicit UNKNOWN. Delayed frames never adopt a new account or snapshot revision.

Discovery sources carry `activity`, `context_basis`, `surface`, `widget`, `region_id`, `discovery_status=unparsed`
and bounded `verification_text`, alongside the event/account/tick/revision envelope. Their screen rows use
`reader=discovery_text`. OCR preserves displayed text but reports UNKNOWN/discovery even when it agrees with
the source text. A readable new message is material for adding a parser, not an automatically verified result.

Eight catalogue activities still have no known readable completion signal: Intelligence Gathering, Mess,
Aldarin Vineyard, Keldagrim tasks, Shayzien Combat Ring, Dorgesh-Kaan trading, Sled Racing, and Underwater
Agility and Thieving. Unknown-context sampling can collect examples; it does not close those semantic gaps.

### Diagnostic target matrix

`VerificationCoverageTest` derives `build/reports/verification-coverage.json` from the actual skill,
activity-counter, diary, achievement-word, widget and grammar tables. Each distinct input/reader lists its
source, comparison, trigger, independent visible evidence (or its absence), and source-only validation state.
The current matrix has 324 distinct targets, not 324 activities or independently verified screens:
103 varbits, 102 varps, 51 widgets, 39 grammars, 24 skill comparisons, three structural families and two
derived-level checks. Shared primitive/widget sources are deduplicated; item slots are not multiplied into
areas. Packed achievement words are raw-word checks, not independent checks of each achievement's meaning.
The eight named unobservable activities remain a separate gap list. The operational 26-capability inventory
is unchanged; this matrix describes diagnostic coverage, not permission or data-arrival status.

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
