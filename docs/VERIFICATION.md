# Verification evidence (0.7.66 to 0.7.68)

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
explicit missing evidence (`pending_capacity`). Both event and random samples share the existing
60-sample/30-frame rolling hour budgets. Relogging does not refill them. From 0.7.68 an event is
charged one of the 60 only when the pending queue accepts it; pending sources already past their 3-tick delay are
recorded as UNKNOWN and make room first. A frame slot is taken only when a frame is about to be uploaded, so a frame
that is skipped, blank, blocked, failed or cancelled (grant cleared, link changed) takes none. A trade or bank PIN
screen that opens after the frame was selected still blocks it (`frame_blocked`). A frame whose selected regions are
still one colour (nothing drawn there yet, e.g. right after login) is not uploaded: a sample says `regions_blank`; an
event looks again within its delay and, if they stay blank, ends UNKNOWN `regions_blank`. Samples beyond the hourly budget are not
captured; the first event refused by the 60-an-hour budget after an admitted one is recorded as UNKNOWN
`event_budget`, so each gap is on record. This is sampling, not a complete activity history. A repeated event retains its own event ID and expected values.

In the region frame only the selected widget rectangles remain; all other pixels are black (the full window, below,
is the whole canvas and is taken only for staff). Known trade and
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
- When the selected widgets change between selection and the drawn frame (a reward screen often settles a tick after
  its event), the source is looked at again on a later tick while it is within its 3-tick delay; the hourly frame budget
  is charged once per source. Only a source that is still changing when its delay ends is reported as
  `screen_changed_before_frame`.
- Pest Control end of game (message box: win with points awarded, loss) and Mahogany Homes (game messages: contract
  finished, running contract total) have grammars since 0.7.68, read from real 0.7.67 discovery samples where earlier
  research found no signal. Independent Hub plugins read the same texts from the game. Wording alone cannot prove that
  the game sent a line, because another plugin can add game messages; a line known to come from another plugin (e.g. a
  timer plugin's own kill counter) is never parsed as game data.
- Chat lines are matched in the chat scroll area's dynamic children, where the game keeps each line's message text
  (the static `Chatbox.LINEn` widgets hold only the sender). The same visibility, clipping and single-match rules apply.
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

### Two capture modes

- Regions (every framed check): only the selected widget pixels, from the client buffer when it matches the canvas.
  The capture row says `scope: regions`.
- Full window, staff only from 0.7.68: it needs the account-bound staff grant (`X-Staff-Capture: on` naming the
  logged-in account) as well as `X-Verification-Frames`; a public link gets region frames only. The grant is checked
  when the window is chosen, after encoding and again before every upload attempt, retries included; a retry after the
  grant ended sends nothing and its upload row says `dropped` `staff_grant_cleared`. At most 6 an hour, on
  its own budget; it never takes one of the 30 region-frame slots, so it cannot crowd out the small evidence frames.
  It is the whole drawn frame, for kill counts, reward-screen
  loot, minigame results, interrupted attempts, unfamiliar result screens or dialogues (`verification_discovery`), and a
  random sample that found a mismatch. It comes from the drawn frame because in GPU mode only that image holds the 3D
  scene; the client buffer holds the interface alone. Its capture row says `scope: full_window` and `gpu`. Both frames
  carry the same source event, tick, revision and plugin version, and each names the other in `paired_ref`. The same
  screen checks are repeated with coordinates in the full image.
- The full window is the complete game canvas exactly as drawn, with nothing blacked out. It therefore
  shows whatever was on screen: chat, other players, their names and overhead text, open interfaces and other plugins'
  overlays. Earlier 0.7.68 candidates masked these areas; on the rig the masks left fixed-mode full windows showing
  only the evidence line and busy-area full windows black, so they added nothing beyond the region frame. No frame of
  either mode is taken while a trade screen or the bank PIN keypad is open.
- A drawn frame of one colour (a software GPU renderer can return solid black) is checked first:
  - the full window is not uploaded (`drawn_blank: true`, `uploaded: false`); the region frame still is when its pixels
    come from the client buffer, which holds the interface in that case;
  - when the regions would also come from the drawn frame, the event is UNKNOWN `drawn_blank` and a random sample gets a
    capture row with `drawn_blank: true`, so its ref is explained; nothing is uploaded.
- From 0.7.68, a drawn frame must match the canvas size (or the stretched size) multiplied by the display
  scale, as one complete CPU-rounded or GPU-truncated width/height pair. Adjacent sizes and mixed rounding are rejected.
  During a resize the drawn frame can lag the canvas, and resizable-layout bounds do not scale with the window, so the
  evidence regions would point at the wrong pixels. The event retries within its delay or ends
  `screen_changed_before_frame`; a sample says `frame_size_mismatch`.
- The full-window budget is charged only for a frame that is uploaded. A renderer that draws the interface but leaves
  the 3D scene black is not detected (limit).

### Upload outcome

Each frame upload adds a `frame/upload` row under the frame's ref: `upload_status` is `stored` (an id, or pending after
storage), `dropped` (a deliberate 200 drop such as `uploads_paused`, `not_granted` or `screenshots_disabled`; never
retried), `refused` (another 4xx), `failed` (still refused with 429 or 503 after two retries at 10 s and 60 s) or
`unconfirmed` (any network failure, an answer lost or unreadable, a bare 429/503 without the ingest's own refusal text,
or another 5xx). The server stores a new object per POST, so only the ingest's named refusals before storage are
retried. A network failure is never retried: OkHttp can report a dropped connection after a delivered body as a
connection failure. Frame uploads also use a client with OkHttp's own connection-failure retry off, which would
otherwise re-send a delivered POST on a pooled connection.

A frame belongs to the link token, account and verification epoch it was taken under. Every attempt sends that account
and stops when any of the three has changed (an account switch on the same token, a revoke and regrant); its outcome row
is written only under the same owner.

A level-up frame selects only the level dialogue that names the event's skill; its number is not filtered, so a wrong
level stays a mismatch. Another skill's dialogue is not used, and a source that expires with one on screen is UNKNOWN
`other_skill_dialogue`.

## Random state sampling

Occasional random ticks and non-transient occasions retain the 20-second gap. Checks cover XP/levels,
derived levels, orbs, server/client variables, known numeric counters, inventory, equipment, bank occupied
slots and location. Two client paths agreeing proves agreement only. Event triggers no longer silently
substitute these generic numbers for their activity-specific evidence.

- Frame pixels come from the client's own pixel buffer at its native resolution when that buffer matches the canvas
  the widget bounds refer to, so text keeps its exact font pixels in stretched mode and at display scaling. The
  capture row states `pixel_source` (`client_buffer` or `drawn_frame`); the drawn, possibly scaled frame is the fallback.
- An inventory slot the widget draws with the game's blank object (item 6512) is an empty slot.
- A random sample whose frame is charged but not taken gets one `frame/capture` row under its ref with
  `frame_skipped`: `no_numeric_region`, `menu_open`, `frame_on_later_tick` (drawn two or more ticks after the sample),
  `screen_changed_before_frame`, `canvas_resized`, `frame_size_mismatch`, `frame_unavailable`, `drawn_blank` or `frame_encoding_failed`.
- The next drawn frame often already carries the next tick number (every sample on the rig, 11-07). That frame is used
  when every selected widget still shows the sampled value in the same bounds; its frame and screen rows carry the
  frame's `tick`, plus `sample_tick`.
- Right after a login an orb text can still read 0 before it is drawn; the orbs are not drawn together. One orb's
  reading is unknown, not a mismatch, only when it reads 0, its value is not 0 and the sample is within 25 ticks of the
  login. A zero later in a session, or any non-zero wrong reading, stays a mismatch lead.

No notifications or recurring analysis jobs are introduced. Real incoming evidence validates supported
layouts and readers after release; source tests and fixture OCR do not claim broad live accuracy.
