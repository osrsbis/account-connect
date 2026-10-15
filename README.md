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
