# Account capture (0.7.74, unreleased: built and unit-tested, not checked in a game client)

A staff panel for one account at a time. It shows which parts of the logged-in account the plugin has read and asks
for the next missing one. It sends nothing new: the costume-room items it asks for ride in the broad-capture
`poh_costume_room` block (`SnapshotBuilder.OWN_STORAGE_CONTAINERS`), which the same server answer grants.

## Grant

`ServerGrants.accountCaptureEnabled()` is true only while all of these hold:

- the last answer to this link token from the official endpoint (`ServerGrants.OFFICIAL_API_BASE`, as for staff
  capture) carried `X-Account-Capture: on` and `X-Account-Capture-Account` with an account hash (`-?[0-9]{1,19}`);
- the setting still names the official endpoint, the token is unchanged and uploads are allowed;
- that account is both the session's account and the live client's (`captureAccountMatches`);
- broad capture is on for this token and account (`X-Broad-Capture: on`).

An answer without the header, a refusal (any 4xx) or an answer from another endpoint clears it; a 429 or 5xx changes
nothing, as for every grant. A token change clears it. No setting, JVM property or other header turns it on.

## Panel

Overlay `AccountCaptureOverlay`, top left, drawn only while logged in with the grant on. Its only text:

| Part | Text |
|---|---|
| Title | `Account capture` |
| Rows, in order | `Bank`, `Looting bag`, `Collection log`, `House storage` |
| Row states | `Captured`, `Missing`, `Not needed`; the house row `No house` |
| Prompt, for the first `Missing` row only | `Open your bank.` / `Check your looting bag.` / `Open your collection log.` / `Enter your house.` |
| Footer | `Uploading...` / `Upload complete.` / `Upload failed. It will retry.` |

Every state is from this login: container observation times and the last bank read clear on each hop and relog.

| Row | Captured | Not needed / No house | Otherwise |
|---|---|---|---|
| Bank | the snapshot carries a bank (open now, or the last populated read of this login) | never | Missing |
| Looting bag | the looting bag container changed this login (the test `addContainerSnapshot` uses to send it) | Not needed: the bank is read and neither the bank nor the inventory holds a looting bag (closed or open) | Missing, including while the bank is unread |
| Collection log | the collection scan verified the whole log against the game's counts (`verifiedCollection`); a new item ends it until the next scan | never | Missing |
| House storage | the costume-room container (`POH_COSTUMES`) changed this login | No house: house location varbit `POH_HOUSE_LOCATION` is 0 after the login settles | Missing |

A bank with no real item is Captured only while it is open: the plugin keeps only a populated bank read after a close.

Footer: `Uploading...` while a snapshot upload is unanswered or the newest built snapshot differs from the last
accepted one; `Upload failed. It will retry.` after a failed upload until the retry; `Upload complete.` when the last
accepted snapshot is the newest built one.

## Not verified without a game client

- That the costume-room container arrives when the player enters the house, not only when a costume store opens.
- That `POH_HOUSE_LOCATION` is 0 for an account that has never bought a house.
- Panel placement and line wrapping at each client size.
