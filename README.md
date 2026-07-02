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

## Optional captures (off by default)

Two additional features can be enabled in the plugin config — both are **off by
default** and capture nothing until you turn them on:

- **Trade screenshots** — on trade completion, uploads a screenshot of the trade
  confirmation window (which shows the other player's name and the traded items)
  as delivery proof.
- **General store clips** — records a short muted video clip of the game screen
  while a shop interface is open and uploads it as delivery proof.

Each toggle shows a full plain-language disclosure of exactly what is sent.
Everything uploads only with your link token to www.osrsbestinslot.com.
