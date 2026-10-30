# Staff release acceptance

There is one plugin for everyone (2026-10-28): there is no separate staff build. Staff run the Hub JAR like every player. The staff tools in it open only when the server
grants staff capture to a staff token for the logged-in account (`X-Staff-Capture: on` with
`X-Staff-Account`). So a release is accepted for staff only when that exact JAR passes this gate:
a green `./gradlew build` says nothing about the staff tools.

## Required automated check

Use JDK 11 with this repository's Gradle wrapper. Supply the exact, immutable JAR that the
Hub commit builds, preferably outside this checkout's build directory:

```bash
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 ./scripts/verify-staff-release.sh /absolute/path/staff-candidate.jar
```

The wrapper runs `check -PstaffCandidateJar=...`: the normal public regression suite plus
the separate positive staff acceptance suite. Direct Gradle invocation is equivalent.
Without that property, `check` and `build` still run staff acceptance, against this
checkout's own built JAR. That catches a broken staff tool in every normal build, but only
the run against the exact Hub JAR qualifies that artifact.

The gate compiles and runs the staff contracts against the supplied JAR, without
`sourceSets.main.output` on its classpath. Preflight verifies the loaded plugin's code source,
then drives `applyServerPolicy` with real responses: the staff grant for the logged-in account must
open the trade, store and drop media gates, and every other answer (no grant, no account, another
account, `off`, a later answer without the grant) must keep all of them closed. The longer suite exercises actual
capture/commit/upload behavior, not just configuration flags. Candidate path and SHA-256
are recorded under `build/reports/staff-acceptance/`; JUnit reports are under
`build/reports/tests/staffCapabilities/` and `build/reports/tests/staffAcceptance/`.

All required suites must execute with no skipped tests. Narrowing acceptance with
`--tests`, test include/exclude filters, or deleting coverage below a suite's recorded
minimum is rejected. Run individual tests with the normal test task while developing;
only the complete staff acceptance task qualifies an artifact.

## Required workflows

| Workflow | What must remain true | Coverage |
| --- | --- | --- |
| General-store panels | Startup registers both overlays; a real shop-open event and server grant show them; live nearby players appear and departed players disappear | StoreWorkflowRegressionTest, StoreNearbyOverlayTest |
| Reset countdown | A sell click and subsequent stock removal produce a visible countdown; removing merchandise does not re-anchor it; each new visit requires a new probe | StoreWorkflowRegressionTest, StoreResetTimerTest, StoreResetOverlayTest |
| Lifecycle and access | Close, hop, logout, disconnect, logging in, disable/re-enable, revocation, unlink and stale responses cannot leave visible stale panels | StoreWorkflowRegressionTest, StoreToolsGateTest |
| Store quantities and GP | Failed sells do not become sales; multiple quantities and same-tick items retain correct quantities/prices | StoreFailedSellTest, StoreMultiQtyGpTest, StoreSameTickItemsTest |
| Trade proof | Accepted trade uploads confirmation/completion frames; decline/disconnect discards them; received items and counterparty capture remain populated | StaffTradeScreenshotTest, StaffTradeReceivedTest, StaffTradeOtherOfferCaptureTest |
| Store proof | Real upload path sends the clip; transient errors retry, terminal errors stop, token changes cannot misdeliver | StaffStoreClipUploadTest, StoreClipRetryTest |
| Drop proof | Session capture, stop, final manifest, retries and interruption contracts still work | DropProofIntegrationTest, DropStopContractTest, DropFinalManifestTest |
| Recipient evidence | Nearby/at-transaction evidence, surrounding-player snapshots and reset-phase evidence retain their existing uncertainty and attribution rules | StoreNearbyCaptureTest, SurroundingPlayersTest, RecipientMechanicsTest, StoreResetPhaseEvidenceTest |
| Public boundary | Without the staff-capture grant for the logged-in account, no grant, header or JVM property turns on media or other-player capture; a player's queued and sent rows carry no other player | Normal PublicTransportTest and UploadGatingTest, StaffCapabilitiesTest |

The five workflow tests use the real RuneLite EventBus and registered overlays, real event
objects and a real Graphics2D. Client/world data and the network boundary are synthetic.
Subscriber exceptions fail immediately with the underlying stack trace. Shop state and
server permissions are reached through events and policy responses, not injected test flags.

## Field acceptance and distribution

Automated acceptance is necessary, but it does not prove current game-client behavior.
Before the Hub commit is published, record the JAR hash and test it twice on a real client:

1. As a player (a link token that is not on the server's staff list): open a general store with another
   tester nearby, sell and buy, trade, drop and pick up. No screenshot, clip, nearby panel,
   `store_taken` or other player's name may reach the server or the screen.
2. As staff (a link token on the server's staff list): open a general store, observe another tester
   arriving and leaving, sell the junk probe, observe the countdown, sell and buy back
   merchandise, close and reopen, hop, disconnect and disable/re-enable. Complete and decline a
   test trade, then verify the proof on the server. Verify one store proof and one drop-proof
   session, including an interruption.

Staff get the release through the Plugin Hub like every player. There is no separate staff
JAR or updater. Do not count an old private JAR's preflight success as acceptance of a release.

## Contract provenance

The original eight private reference suites are unchanged. Four additional positive suites
were recovered from `a9bb0fc6ff19ee0a0f3c50dcbd117a488c124995`:
`TradeScreenshotTest`, `TradeOtherOfferCaptureTest`, `Wave1bTradeReceivedTest` and
`StoreClipUploadTest`. Their classes were renamed with a Staff prefix so their positive
requirements can coexist with the public tests. Their assertions were preserved.

## Verification recorded on 2026-10-25

- Full JDK 11 offline build: 1,130 passed, 0 failed/errors; one pre-existing opt-in
  `PublicEventOutboxLocalSiteTest` skipped because no loopback site was configured.
- Final focused store/access/account-switch/public-boundary run: 141 passed, 0 skipped.
- Replacing the nearby overlay's reader with the old empty capture reader made the new
  event-to-render workflow fail (expected two rows, observed one); restoring it passed.
- The new workflow exposed stale shop state after session loss and disable/re-enable.
  Both lifecycle paths were fixed and the five workflow cases now pass.
- All 313 staff contract tests and the three preflight tests compile against the current
  candidate API. The public candidate is rejected at all three positive media checks.
- Filtering acceptance with `--tests`, intentionally skipping a preflight test, and
  removing a required test each failed the gate. The temporary mutations were restored.
- A fresh diagnostic JAR built from historical private source
  `a9bb0fc6ff19ee0a0f3c50dcbd117a488c124995` passed all three media preflight checks.
  Its full acceptance failed compilation because it lacks the current multi-item
  `storePendings` contract. It is not a current release candidate and was not distributed.
- No current staff artifact has passed all 313 positive contracts in this verification.
  No staff updater, Hub submission, deployment or live-game test was performed.

## Verification recorded on 2026-10-28 (one plugin, server grant)

- The staff tools are behind `staffCaptureEnabled()`, which needs the server's staff-capture grant
  for this link token and the logged-in account. The separate staff build variant is removed.
- `staffAcceptance` against the Hub-shaped JAR: 3 preflight and 313 contract tests passed, 0 skipped,
  all 20 required suites.
- Public suite: 1,139 tests, 0 failed, 1 skipped (the opt-in local-site test). New public-boundary
  tests fail on each of four mutations: no account check, a 403 that keeps the grant, an emit path
  that always projects as staff, an ungated trade counterparty.
