# Working on this plugin

Rules owner: plugin maintainers. Last cleaned: 2026-11-18.

This tree, its comments and commit messages are public (the Hub builds a mirror). Keep out people's names, internal
operations, staff identities, link tokens, grant values, session IDs and tool or assistant attribution.

## Build and release

- JDK 11 and the Gradle wrapper: `./gradlew build` (tests write under `build/test-home`); `./gradlew runClient` for a
  manual login check. Bump `version` in `build.gradle` with each release.
- Staff tools open only when the server grants staff capture, so a green build does not accept a release: every
  release passes the full staff gate in `docs/STAFF_ACCEPTANCE.md` on the exact JAR the Hub commit builds.
- Size: under 200,000 tokens as the Hub reviewer counts them (estimated with `cl100k_base` over comment-stripped
  `src/main` Java; the release check refuses at or over the limit). 150,000 is a reported target, never a block.

## Engineering principles

- The plugin does only what the client must (transient state, filtering before data leaves, delivery, immediate UI);
  analysis belongs on the server once ingest stores its inputs, preferably as a field on an existing row.
- A new field or event is sent only after the server accepts it.
- Reuse RuneLite events and bundled libraries; offline and replay models stay out of `src/main`.
- Merge code paths only when identity, storage, purge, retry, receipt and old-client behaviour all match.
- Measure size or speed before and after (token count, JAR bytes, payload, runtime); label estimates.
- Remove code that cannot run; retiring a capability the server can still switch on is a product decision.
- A simplification changes no user-facing wording or release gate and removes nothing collected.

## Plugin Hub rules (each from a review)

- No `new Gson()`/`new GsonBuilder()` (inject the client's Gson, `.newBuilder()`); no `Thread.sleep`/`interrupt`
  (use a scheduled executor); no `KeyboardFocusManager`, no `client.hopToWorld`.
- No runtime dependency in `build.gradle`, nothing off RuneLite's allowlist; one `@PluginDescriptor` in `src/main`.
- No overlay or panel about other players.
- The Hub manifest change is the `commit=` line only; `warning=` stays byte-identical.
- No new config item, warning, description, notice or chat text unless the project owner asks for that exact item.
- Add each new reviewer rule here in the change that records it.

## Documentation

- One fact, one place: `README.md` (released behaviour, what it sends, privacy), `docs/STAFF_ACCEPTANCE.md`,
  `docs/<topic>.md` (one contract each; unreleased work with its state in the heading), this file.
- Update docs in the commit that changes the behaviour; "What it sends" lists exactly what the current release sends.
- Name a version or commit for behaviour that changed between releases. Lines under about 120 characters.
