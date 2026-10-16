# Private operational contract reference

These unchanged tests specify delivery-proof media, recipient inference and surrounding-player
capture. Those capabilities are deliberately unavailable in the public Hub artifact. They are
preserved here for the separate operational owner, not counted as public release tests and not
claimed to pass against the public artifact. No Gradle exclusion or hidden feature toggle is used.

Their runnable source and original test paths remain on `feat/broad-capture-b1` at
`a9bb0fc6ff19ee0a0f3c50dcbd117a488c124995` (832 tests previously passed). Do not submit that
revision to the Hub, or auto-update operational clients to this public branch. Separate private
distribution and client acceptance belong to the existing operational release owner.

`PublicTransportTest` verifies refusal at public network boundaries, including legacy disk replay,
remote grants and media upload leaves. Own GE, bank, XP, event retry/backoff and spool ACK tests
remain in the normal public test tree. Pure capture/segmentation utilities retain their tests.
