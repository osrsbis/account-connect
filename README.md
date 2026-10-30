# OSRS Best in Slot — Account Connect

Every release also runs the [staff acceptance gate](docs/STAFF_ACCEPTANCE.md). A passing public build does not certify the staff delivery tools.

A RuneLite plugin that connects your Old School RuneScape account to
[osrsbestinslot.com](https://www.osrsbestinslot.com) so its gear and skill
calculators auto-fill from your real account — no manual data entry.

## How it works

1. On osrsbestinslot.com, open **Connect account** and copy your one-time link token.
2. Install this plugin, open its settings, and paste the token into **Link token**.
3. While you are logged in, the plugin periodically reads your account state and sends
   a snapshot to osrsbestinslot.com, keyed by that token. The site reads it back to
   pre-fill the calculators.

## Staff tools

There is one plugin for everyone. The staff tools in it are off for every player. They turn on only
when osrsbestinslot.com answers an upload with `X-Staff-Capture: on` and names, in `X-Staff-Account`,
the account that is logged in. The site sends that only for a link token on its staff list. No setting,
JVM property or other header turns them on, and they turn off again on the next answer without the
grant, on a refused token, on a token change and while another account is logged in.

With the grant, the plugin records trade screenshots, store and drop clips, and the other players
near a shop or a drop pile: the nearby panel, the trade counterparty, `store_taken` and its buyer
candidates. For a player, `PublicTransport` removes every other-player field before a row is queued
or sent.

## What it sends

The snapshot is tied to the link token you chose (not your real identity) and contains
**game account state only**:

- Display name, account hash, account type and membership, current world and location
- Skills (levels, XP), total & combat level
- Quest states and quest points, achievement-diary progress, and every combat-achievement task with its points
- Slayer points, streak, current task and slayer reward unlocks
- Spellbook, attack style, active prayers, Kourend favour, and minigame points and milestones the game keeps in game
  variables
- Worn equipment, inventory, looting bag, seed vault, bonds pouch, rune pouch and bank contents (bank only after you
  open it in-game), with their estimated value
- Grand Exchange offers
- Collection-log entries you have obtained. Opening your own collection log reads the whole log.

When the site turns on account progress for your link (by default it does for every linked player), the snapshot also
carries:

- prayer unlocks (Rigour, Augury, Preserve, Deadeye, Mystic Vigour) and Watson clue holdings
- your recent Grand Exchange history as shown in the game
- recent system messages for clue completions, collection-log additions, combat tasks and salvage sorting
- the plank sack and recently seen bank and inventory contents

The plugin also sends account activity as events: Grand Exchange and store buys and sells, completed trades,
loot, items you drop, pick up or alch, when your dropped items leave the ground, deaths, kill counts, level-ups and XP,
gear changes, bank visits, region changes, world hops, and login and logout times.

It does **not** read or send your password, email, payment details, or any Jagex-account
credentials — RuneLite does not expose those to plugins.

## Engineering contracts

The details of each capture and its release state are in `docs/`:

- [Account progress block](docs/ACCOUNT_PROGRESS.md)
- [Grand Exchange observations](docs/GE_OBSERVATIONS.md)
- [Event delivery and retry](docs/DELIVERY.md), including the latest bank in the snapshot (0.7.62)
- [Own-gameplay capture](docs/OWN_GAMEPLAY.md), which needs a separate server grant
- [Staff acceptance gate](docs/STAFF_ACCEPTANCE.md)

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
