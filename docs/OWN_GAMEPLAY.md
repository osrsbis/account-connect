# Own-gameplay capture

These contracts describe capture that needs the server's own-gameplay grant (`X-Own-Gameplay: on`) and, for the
journals, an explicit Queue scope. The server keeps that grant off unless a linked player is opted in, so an ordinary
player's client does not send this data. Each section states its own release state.

## Own-event provenance (0.7.37 candidate)

RuneLite subscriber names follow the actual event-bus contract. The 0.7.36 isolated Java17 client exposed a startup rejection for three noncanonical method names; the focused regression registers the plugin with RuneLite's EventBus. Exact-client gameplay and release acceptance remain separate.

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

The granted own-gameplay snapshot now reads five personal loot-key slots only
while the fixed chest tabs are visible and a matching container packet was seen
in the current session. Missing, empty and changed-without-packet caches remain
distinct. Duplicate item quantities use long totals; unpriced or unsafe reference
values stay unknown. No labels, victim fields or original-owner information are
read. This is a cache valuation, not new loot or realized income. Closing the UI,
a scene gap, account switch or grant reset clears the source cache. Exact-client
verification remains open. The future own-state scope now adds bounded journal transport below.
Source: [pinned personal-key container mapping](https://github.com/pk-enjoyer/real-loot-key-value/blob/938d5157fb8202f3766b358466743048e0ce44c4/src/main/java/com/reallootkeyvalue/LootKeyValueCalculator.java).

The granted hunter snapshot history records a whistle's observed target from a
closed 28-creature list, or its exact no-active-rumour response. It exports fixed
codes only, with no message, hunter or player names. These observations do not
prove when an assignment began, a rare item was obtained or a rumour was turned
in; missing activity remains unknown. Other chat and dialogue are excluded.
Account/grant changes clear this recent snapshot history. Exact English client
wording still needs game QA; the unreleased own-signals scope now adds durable transport for this family.
Source: [pinned rumour-reminder acquisition](https://github.com/DrJam/rumour-reminder/blob/38a5b7aefc4314dc1f321bea74c994bcea23aa61/src/main/java/com/drjam/rumourreminder/RumoursManager.java).

The granted own-gameplay snapshot also decodes carried rune-pouch cache slots.
Only four recognized unnoted pouch items in the player's inventory enable the
read. Missing inventory, absent varbits and unknown rune mappings stay unknown;
duplicate rune slots use 64-bit totals. These are cached absolute observations,
not consumption events, complete storage or a wealth valuation. The original
raw snapshot remains raw. This new decoder is local and still needs game-client
verification; explicit future own-state scope adds bounded journal transport as described below.
Source: [RuneLite1.13.1 rune-pouch overlay](https://github.com/runelite/runelite/blob/runelite-parent-1.13.1/runelite-client/src/main/java/net/runelite/client/plugins/runepouch/RunepouchOverlay.java).

The same granted snapshot now qualifies bank-tab ranges and currency quantities.
It requires an own-bank container callback in the current gameplay session, a
normal bank-build callback and matching cached contents. Missing counts, an
out-of-range total, a changed cache without a packet, potion storage and scene
gaps remain unknown. The nine raw tab counts stay available separately. Derived
ranges are explicitly inferred; tab 0 displays the whole bank, so the remainder
is labelled untabbed. Only coins and platinum quantities are summed, using long
values. Inventory is not added, and no gain, item-price valuation or spendable
wealth is claimed. The packet observation time remains visible. This is bounded
recent snapshot history. The future own-state scope also journals these typed observations; actual-client verification is still pending.
Sources: [reviewed tab-offset technique](https://github.com/BrastaSauce/purchase-progress/blob/9b190e3cdfee0c3848380e57d044028e63f05e34/src/main/java/com/brastasauce/purchaseprogress/BankCalculation.java) and [RuneLite1.13.1 bank interface](https://github.com/runelite/runelite/blob/runelite-parent-1.13.1/runelite-client/src/main/java/net/runelite/client/plugins/bank/BankPlugin.java).

The granted seed-box snapshot now merges duplicate IDs with long quantities.
A missing container or invalid item array is omitted, while a valid empty array
is explicitly an observed empty cache. The fixed seed-box container ID is checked
before reading items. Values remain client price estimates, with unpriced,
overflowing or inexact JSON totals unknown. This is neither verified current
wealth nor a durable history stream. Container observation timestamps clear on
relog, hop and disconnect; old-account timestamps cannot label a new cache.
Source-specific callback and actual-client acceptance remain separate.

The future own-state journal also preserves carried map/coordinate clue references
against the 173 primary entries in the pinned RuneLite1.13.1 library. Unknown
inventory and an empty supported subset stay distinct. These are public library
locations, not the player's location or a resolved quest-dependent route. A
future own-signals grant preserves SELF-owned ground-clue appearance/removal
observations and explicit ground/object tracking-capacity gaps. Ground events
contain no location or player labels. Drop origin, removal cause and timers
across client sessions remain unknown; removal is never a claimed pickup,
expiry or completion. These additions are LOCAL and not activated.

## Own-state journal candidate

The future `own-signals-v1` scope adds seven closed system-message and visible-check
families to the durable journal: coal bag, fish barrel, grape barrel, chompy count,
construction totals, compost and hunter rumour signals. It requires the explicit
`own_signals` grant plus the existing gameplay/combat/state/vars grants, with
server schema7. Older scopes retain pending signal rows and never use the legacy
writer. Only fixed numeric fields and allowlisted codes are projected; raw chat,
player names and arbitrary labels are excluded. A notch does not supply a total,
absolute totals are not new rewards, and no-active-rumour is not a verified turn-in.
Observed storage checks are not continuous balances. Source time and observation
kind remain explicit. Three additional sequence families are explicitly inferred:
stationary samples in the GE courtyard closed by observed movement, paired bolt
inventory changes with Magic XP, and own Eat/Drink/Chew intent followed by a slot
change within two ticks. Missing position or sample gaps cannot close a stationary
interval as movement. These records never prove input idleness, an aura, a cast,
consumption effect, cooldown or income. No coordinates, input-device activity,
menu target text or other-player fields are journaled. This extends the same
unreleased own-signals contract; no capture scope is activated.

Two own combat callbacks also enter that future journal: own ActorDeath with the observed ToB status and a fixed HUD
room label (or unknown), and own tile overlap with a previously observed ToA
acid object. Other actors and names are excluded. The death observation does not
prove encounter membership or a team wipe; the overlap does not prove damage or
a mistake. No location is uploaded in these events. Existing scene/account/grant
gaps clear source continuity. Exact game-client timing and release remain open.


A change in the effective gameplay Queue scope or settlement of its current
account/profile journal gate starts a new capture baseline after two client
ticks. This prevents a state first seen under a narrower scope from staying
absent forever when the scope expands. Unchanged policy responses and replies
for an old token do not reset it. Only newly observed values form the baseline;
the earlier snapshot is not replayed as current. Pending journal rows keep their
original IDs and survive the capture reset. This behavior is locally tested;
exact-client QA and activation remain open.

The separate future `own-vars-v1` scope journals seventeen closed own-player raw
counter/settings families: Surge, Death Charge, escape crystal settings, arena,
Barrows, personal raid points, Tears, own brewing contribution, restaurant order,
Pyramid Plunder, Sailing trials, special energy, Sailing charting and port tasks,
potion storage, totems and raw bank-tab counts. Schema6 requires an explicit
current-token `own_vars` grant in addition to the existing gameplay/combat/state
grants. Older scopes pause these rows; they never use the legacy writer. Missing
numeric fields stay unknown through JSON/restart; zero and negative raw values
remain distinct. Only fixed fields are retained, with session, observation time
and baseline/update provenance. No reward, completion or duration is inferred. Potion readings are not decoded
as bottles or doses. Charting retains 358 pinned fields; missing values stay
unknown. These five additions extend the unreleased scope, not a live grant.

This is LOCAL tested transport, not activated capture or whole-intake coverage.
The existing outbox bounds and counted overflow still apply. Natural per-family
rates and cost need measurement before enabling this scope; the existing pilot
cap and spending gates are unchanged. An active timer can change every game tick,
so the earlier14-family budget must not be presented as a whole-gameplay quote.

The future `own-state-v1` Queue scope adds one closed `own_state` event type for
skill goal settings, carried rune-pouch cache, inferred bank-tab currency, visible
fairy-ring favourites, mixology orders, Spirit Tree options, own raid context and
carried clue-library references, plus personal key visibility and slot caches.
The four interface/context additions extend an unreleased scope. Missing values
remain unknown, repeated orders survive, and only fixed game codes/destinations
are retained. Visible options do not prove permanent unlocks or house ownership;
raid context excludes team details and completion claims.
It requires current-token own-gameplay permission plus the explicit scope. The
matched backend additionally requires `own_state`, `own_gameplay` and `own_combat`
permissions before advertising this superset. Old grants do not enable it.
Only these ten typed projections enter the journal; raw snapshots keep their
existing path. Unknown values stay unknown, goals remain settings rather than XP
earned, and bank ranges remain inferred rather than a complete wealth balance.

Personal loot-key journal records use one visibility state and up to five separate
slot states, each bounded to the source's 128-entry limit. Unchanged slots and
reference-price changes do not append new raw item records. Closing the chest
marks visibility unavailable; reopening requires fresh packet evidence. The
original snapshot is preserved without duplicating split item arrays in its
recent-history buffer. Unknown and empty remain distinct. Journal records keep
item IDs, long quantities and packet time; estimates, victim fields and income
claims are excluded. Scene/account/grant resets clear the six bounded dedupe
states. These additions are unreleased and require exact-client acceptance.

Each source baseline/change uses the existing per-ID journal and ACK path.
Restart or scope rollback retains pending rows until the matching scope returns;
explicit erasure still prevents replay. Process-local observed/handed-off/failure
counters are not durable delivery counts. Schema5 and the separate state policy
are LOCAL candidates; no server policy, public release or live retention scope
has been activated. The frozen public QA artifact and private staff build remain
separate. Exact-source review, truthful disclosure, matched backend and actual
client QA are still required before release.

## Own-combat journal candidate

With both the own-gameplay grant and explicit `own-combat-v1` Queue scope, own
incoming hits and own hits on NPCs enter the bounded public journal. Observed NPC
death and despawn remain distinct; neither establishes kill credit. No attacker,
other-player name or player target is recorded. The existing snapshot remains.
Restart and narrower Queue scopes retain pending records; explicit erasure keeps
its tombstones. Session counters distinguish observations from event handoff;
handoff does not prove disk persistence or server acceptance.
This extension is local, default off and separate from the frozen public build.
Exact-source review, the matched backend, disclosure, client QA and normal release
are still required. It does not make all snapshot families durable.
