# Staff acceptance contracts

These tests specify the staff tools: delivery-proof media, recipient evidence and surrounding-player
capture. There is one plugin for everyone (2026-10-28), so they run in the `staffAcceptance`
Gradle task against the exact Hub JAR, with the server's staff-capture grant. `StaffGrant` gives each
fixture that grant when it links a token or attaches a client, the same answer a staff token gets from
`/account-ingest`. The assertions themselves are unchanged.

See [staff release acceptance](../docs/STAFF_ACCEPTANCE.md) for the command, the required workflows,
the coverage safeguards and the field acceptance.

The original eight suites are unchanged apart from that fixture. Their original runnable paths remain at
`a9bb0fc6ff19ee0a0f3c50dcbd117a488c124995` on `feat/broad-capture-b1`. Four positive trade/media suites
were restored from that revision with Staff class-name prefixes only.

`PublicTransportTest` verifies that a player without the grant still gets nothing: no media, no other
player in any queued or sent row, including legacy disk replay and every old grant header.
