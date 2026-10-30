# Working on this plugin

This repository is the OSRS Best in Slot RuneLite plugin (`osrs-best-in-slot` on the Plugin Hub). It links a player's
account to osrsbestinslot.com so the calculators fill in from the real account.

## This tree is public

- The Plugin Hub builds a public mirror of this tree, so every file, comment and commit message here is public.
- Keep out of it: people's names, internal operations, staff identities, link tokens, grant values, session IDs and
  tool or assistant attribution. Private notes stay in the maintainers' own records.

## Build and test

- Use JDK 11 and the Gradle wrapper: `./gradlew build`. Tests write under `build/test-home`, never the real `~/.runelite`.
- `./gradlew runClient` starts a RuneLite client with the plugin loaded, for a manual login check.
- There is one plugin for everyone. The staff tools open only when the server grants staff capture to the linked token,
  so a green build does not accept a release.
  - Every release passes the staff acceptance gate in `docs/STAFF_ACCEPTANCE.md` on the exact JAR the Hub commit
    builds. That suite cannot be narrowed.
- Bump `version` in `build.gradle` with each release.

## Plugin Hub rules

Every Hub update is reviewed by a RuneLite maintainer. Each rule below came from a review; do not repeat a rejected
pattern.
- No `new Gson()` or `new GsonBuilder()`. Inject the client's Gson and customise it with `.newBuilder()`.
- No `Thread.sleep` or `Thread.interrupt`; use a scheduled executor.
- No runtime dependency in `build.gradle`, and nothing off RuneLite's allowlist.
- No `KeyboardFocusManager` and no `client.hopToWorld`.
- Exactly one `@PluginDescriptor` in `src/main`.
- No overlay or panel about other players.
- The Hub manifest change is the `commit=` line only. The `warning=` line stays byte-identical.
- No new config item, warning, description, notice or chat text unless the project owner asks for that exact item.
- Add each new reviewer rule here in the same change that records it.

## Documentation

Each document has one job. Put a fact in the one place that owns it and link to it from elsewhere.

| File | Reader | Holds |
|---|---|---|
| `README.md` | players and Hub reviewers | what the plugin does, how to connect, what it sends, privacy, building |
| `docs/STAFF_ACCEPTANCE.md` | release maintainers | the staff release gate and how to run it |
| `docs/<topic>.md` | engineers | one engineering contract per topic: what is captured, when, retry and delivery rules |
| `ops-reference-tests/README.md` | engineers | what the staff contract tests specify |
| `AGENTS.md` | anyone changing the code | how to work here and these rules |

- Update the document in the same commit as the behaviour it describes. A release whose docs describe an older build
  is not ready.
- `README.md` describes the released plugin. Work that is not released, or released but switched off, goes in
  `docs/<topic>.md`, with its state in the heading (for example "inactive" or "candidate").
- "What it sends" in `README.md` lists what the current release sends. When a release adds or removes a field, change
  that list in the same release.
- Name a version or commit for every claim about behaviour that changed between releases.
- Keep lines under about 120 characters, one rule per bullet. Remove a section when its feature is removed.
