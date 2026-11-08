# Own-gameplay capture

These contracts describe capture shipped in 0.7.67, controlled by `X-Own-Gameplay: on`.
The grant is delivered through collection rollout independently of staff identity. Closed combat, signal, state
and variable events use the existing granted, capped legacy event receiver, and are not sent while a `ge-v1` or
`own-history-v1` Queue grant is active. They are not kept in a durable public journal across a crash.
No server grants a Queue scope for them: the server sends only `ge-v1`, `own-history-v1` or `off`, and the
four `own-*` Queue scopes earlier candidates accepted were removed in the 0.7.67 simplification.

## Own-event provenance

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
verification remains open. These caches are also sent as `own_state` events, described below.
Source: [pinned personal-key container mapping](https://github.com/pk-enjoyer/real-loot-key-value/blob/938d5157fb8202f3766b358466743048e0ce44c4/src/main/java/com/reallootkeyvalue/LootKeyValueCalculator.java).

The granted hunter snapshot history records a whistle's observed target from a
closed 28-creature list, or its exact no-active-rumour response. It exports fixed
codes only, with no message, hunter or player names. These observations do not
prove when an assignment began, a rare item was obtained or a rumour was turned
in; missing activity remains unknown. Other chat and dialogue are excluded.
Account/grant changes clear this recent snapshot history. Exact English client
wording still needs game QA; `own_signal` events below carry this family.
Source: [pinned rumour-reminder acquisition](https://github.com/DrJam/rumour-reminder/blob/38a5b7aefc4314dc1f321bea74c994bcea23aa61/src/main/java/com/drjam/rumourreminder/RumoursManager.java).

The granted own-gameplay snapshot also decodes carried rune-pouch cache slots.
Only four recognized unnoted pouch items in the player's inventory enable the
read. Missing inventory, absent varbits and unknown rune mappings stay unknown;
duplicate rune slots use 64-bit totals. These are cached absolute observations,
not consumption events, complete storage or a wealth valuation. The original
raw snapshot remains raw. This new decoder is local and still needs game-client
verification; `own_state` events below carry it.
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
recent snapshot history. `own_state` events below also carry these typed observations; actual-client
verification is still pending.
Sources: [reviewed tab-offset technique](https://github.com/BrastaSauce/purchase-progress/blob/9b190e3cdfee0c3848380e57d044028e63f05e34/src/main/java/com/brastasauce/purchaseprogress/BankCalculation.java) and [RuneLite1.13.1 bank interface](https://github.com/runelite/runelite/blob/runelite-parent-1.13.1/runelite-client/src/main/java/net/runelite/client/plugins/bank/BankPlugin.java).

The granted seed-box snapshot now merges duplicate IDs with long quantities.
A missing container or invalid item array is omitted, while a valid empty array
is explicitly an observed empty cache. The fixed seed-box container ID is checked
before reading items. Values remain client price estimates, with unpriced,
overflowing or inexact JSON totals unknown. This is neither verified current
wealth nor a durable history stream. Container observation timestamps clear on
relog, hop and disconnect; old-account timestamps cannot label a new cache.
Source-specific callback and actual-client acceptance remain separate.

`own_state` events also carry map/coordinate clue references
against the 173 primary entries in the pinned RuneLite1.13.1 library. Unknown
inventory and an empty supported subset stay distinct. These are public library
locations, not the player's location or a resolved quest-dependent route. A
`own_signal` event carries SELF-owned ground-clue appearance/removal
observations and explicit ground/object tracking-capacity gaps. Ground events
contain no location or player labels. Drop origin, removal cause and timers
across client sessions remain unknown; removal is never a claimed pickup,
expiry or completion. These additions ship in 0.7.67 under the own-gameplay grant; each source still needs
its own live evidence before its game-client behavior is considered verified.

## Own-signal, own-vars and own-state events (0.7.67)

From 0.7.68, `EventValues` holds the scalar guards these closed projections share (a refactor; no output change).
Each projection retains its field allowlist, numeric bounds, source checks and unknown-value behavior.

`own_signal` events carry seven closed system-message and visible-check
families: coal bag, fish barrel, grape barrel, chompy count,
construction totals, compost and hunter rumour signals. They require the
own-gameplay grant for the current token and a live client on the active account
and profile. Only fixed numeric fields and allowlisted codes are projected; raw chat,
player names and arbitrary labels are excluded. A notch does not supply a total,
absolute totals are not new rewards, and no-active-rumour is not a verified turn-in.
Observed storage checks are not continuous balances. Source time and observation
kind remain explicit. Three additional sequence families are explicitly inferred:
stationary samples in the GE courtyard closed by observed movement, paired bolt
inventory changes with Magic XP, and own Eat/Drink/Chew intent followed by a slot
change within two ticks. Missing position or sample gaps cannot close a stationary
interval as movement. These records never prove input idleness, an aura, a cast,
consumption effect, cooldown or income. No coordinates, input-device activity,
menu target text or other-player fields are sent.

Two own combat callbacks are also `own_signal` events: own ActorDeath with the observed ToB status and a fixed HUD
room label (or unknown), and own tile overlap with a previously observed ToA
acid object. Other actors and names are excluded. The death observation does not
prove encounter membership or a team wipe; the overlap does not prove damage or
a mistake. No location is uploaded in these events. Existing scene/account/grant
gaps clear source continuity. These callbacks ship in 0.7.67; exact game-client timing remains unverified.


A change of link token or of the own-gameplay grant starts a new capture baseline
after two client ticks. Unchanged policy responses and replies for an old token do
not reset it. Only newly observed values form the baseline; the earlier snapshot
is not replayed as current. Rows already queued keep their original IDs. This
behavior is locally tested; exact-client QA remains open.

`own_vars` events carry seventeen closed own-player raw
counter/settings families: Surge, Death Charge, escape crystal settings, arena,
Barrows, personal raid points, Tears, own brewing contribution, restaurant order,
Pyramid Plunder, Sailing trials, special energy, Sailing charting and port tasks,
potion storage, totems and raw bank-tab counts, under the same own-gameplay grant. Missing
numeric fields stay unknown through JSON/restart; zero and negative raw values
remain distinct. Only fixed fields are retained, with session, observation time
and baseline/update provenance. No reward, completion or duration is inferred. Potion readings are not decoded
as bottles or doses. Charting retains 358 pinned fields; missing values stay
unknown.

The existing memory-queue bounds and counted overflow apply. Natural per-family
rates and cost need measurement; the existing pilot cap and spending gates are
unchanged. An active timer can change every game tick, so the earlier 14-family
budget must not be presented as a whole-gameplay quote.

One closed `own_state` event type carries
skill goal settings, carried rune-pouch cache, inferred bank-tab currency, visible
fairy-ring favourites, mixology orders, Spirit Tree options, own raid context and
carried clue-library references, plus personal key visibility and slot caches.
Missing values
remain unknown, repeated orders survive, and only fixed game codes/destinations
are retained. Visible options do not prove permanent unlocks or house ownership;
raid context excludes team details and completion claims.
It requires current-token own-gameplay permission.
Only these ten typed projections are sent as events; raw snapshots keep their
existing path. Unknown values stay unknown, goals remain settings rather than XP
earned, and bank ranges remain inferred rather than a complete wealth balance.

Personal loot-key records use one visibility state and up to five separate
slot states, each bounded to the source's 128-entry limit. Unchanged slots and
reference-price changes do not append new raw item records. Closing the chest
marks visibility unavailable; reopening requires fresh packet evidence. The
original snapshot is preserved without duplicating split item arrays in its
recent-history buffer. Unknown and empty remain distinct. Records keep
item IDs, long quantities and packet time; estimates, victim fields and income
claims are excluded. Scene/account/grant resets clear the six bounded dedupe
states. These additions ship in 0.7.67; source-specific exact-client acceptance remains unverified.

Each source baseline/change is one event with its own `event_id` on the legacy receiver.
Process-local observed/handed-off/failure counters are not durable delivery counts.
There is one plugin artifact; capture grants and staff rights are separate.
Exact-source review, a matched receiver and actual-client acceptance remain required before release.

## Own-combat events (0.7.67)

With the own-gameplay grant, own incoming hits and own hits on NPCs are sent as
`combat_hit` and `combat_npc_end` events on the legacy receiver. Observed NPC
death and despawn remain distinct; neither establishes kill credit. The first own hit on a new
target event also carries the worn item IDs and quantities read at that hit (`worn`), so no separate gear row is
needed; later hits on the same target do not repeat it. No attacker,
other-player name or player target is recorded. The existing snapshot remains, and its hit rows carry no `worn`.
Session counters distinguish observations from event handoff;
handoff does not prove disk persistence or server acceptance.
These events ship in 0.7.67 and are accepted by the granted legacy receiver. Captured events do not prove
complete combat history: admission caps, memory-queue limits and missing observations still apply.
The server reader for `loot.npc_id`, `death.death_tick` and `combat_hit.worn` remains a separate open follow-up.
Storing those inputs does not yet establish encounter reconstruction. Not all snapshot families are durable.
