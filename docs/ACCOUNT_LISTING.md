# Account listing fields — candidate after 0.7.72

Five snapshot fields describe an account for a listing: boss, raid and clue counts from game variables, the same
counters from the collection-log pages the player opens, the membership days left, whether a Jagex Launcher session
started the game, and the tool leprechaun's storage. They are sent for every linked player, with no server grant,
no setting and no new text. `schema_v` stays 1.

| Field | Source | Value |
|---|---|---|
| `account.membership_days` | Varp 1780 (gameval `ACCOUNT_CREDIT`, legacy `VarPlayer.MEMBERSHIP_DAYS`), the varp `account.members` and RuneLite's friends list already read. | Integer days, or null until the player's variables have settled after login (the `account.members` rule). A settled 0 is a known 0. |
| `kill_counts_raw` | The 89 varps in `SnapshotBuilder.KILL_COUNT_VARPS` (list below). | Map of varp id (string) to raw value. Present only after the variables have settled. |
| `collection_log_pages` | The header of the collection-log page on screen (`InterfaceID.Collection.HEADER_TEXT` children), read each game tick while the player's own log is open. | Map of page title to `{obtained, total, counts, observed_at}`. |
| `account.jagex_launcher` | `Client.getLauncherDisplayName()`, which RuneLite sets when the Jagex Launcher started a Jagex Account session. | `true` when that name is present, else null. Null means unknown, not a legacy login: a new character without a display name or another launcher also gives null. Never false, and the name is never sent. |
| `tool_leprechaun_raw` | The 23 `FARMING_TOOLS_*` varbits in `SnapshotBuilder.TOOL_LEPRECHAUN`. | Map of name to raw varbit value. Present only after the variables have settled. |

## Collection-log pages

- The plugin never opens a page. It reads the page the player opens, and nothing while the existing Search scan runs.
- Header line 0 is the page title, the line `Obtained: x/y` gives `obtained` and `total`, and every other line of the
  form `<label>: <number>` becomes `counts[label]`, for example `"Zulrah kills": 1234` or the raid, Inferno,
  Colosseum and clue-tier completion lines. Labels are kept as the game shows them; the server maps them.
- Only the player's own log is read (varbit 12061 is 0), never a house host's.
- A page counts as read when it has a title and an `Obtained` line or at least one counter. A number with wrong
  digit grouping refuses the whole page rather than guessing.
- Coverage: a page title absent from the map was not read this login. A counter of 0 is a read 0.
- `observed_at` (seconds) is when these values were first seen this login. Re-reading the same values keeps it, so an
  open log does not change the snapshot each tick.
- At most 256 pages. The map is cleared with the other collection observations on an account, profile or token change.
- Ingest rebuilds `collection_log` with a fixed key set, so the pages are a separate top-level block. Each upload
  carries only this login's pages, so the server has to keep the newest reading per page across uploads (open
  server follow-up: ingest stores the block as sent and does not carry it).

## Kill counts from varps

`kill_counts_raw` is the primary source for boss kill counts, raid completions and clue completions; the
collection-log pages remain the fallback and cover counters no varp holds. Keyed by varp id to keep the plugin small;
the gameval names (runelite-api 1.13.6 `VarPlayerID`) are below.

- Clue tiers: beginner 2201 `COMPLETED_CLUES5`, easy 1111 `COMPLETED_CLUES`, medium 1112 `COMPLETED_CLUES1`, hard 1354
  `COMPLETED_CLUES2`, elite 1533 `COMPLETED_CLUES3`, master 1534 `COMPLETED_CLUES4`; 1116 `COMPLETED_CLUES_TOTAL` is
  their sum.
- Verified on real accounts before this change: boss kill counts against the game's own kill-count chat lines (82 of
  90 account and boss pairs equal, the other 8 explained by timing), and the clue-tier mapping above with its total.
- Unverified: the values the client may not receive. A varp the game does not send reads 0, the same as no kills, so
  a 0 for a boss outside the verified pairs is not proof of no kills.
- 4148-4150 are the kills of each Perilous Moons boss (Eclipse, Blue, Blood); 4186 is the moons' chest count.

All ids:

- 1111 `COMPLETED_CLUES`
- 1112 `COMPLETED_CLUES1`
- 1116 `COMPLETED_CLUES_TOTAL`
- 1354 `COMPLETED_CLUES2`
- 1503 `TOTAL_ARMADYL_KILLS`
- 1504 `TOTAL_BANDOS_KILLS`
- 1505 `TOTAL_SARADOMIN_KILLS`
- 1506 `TOTAL_ZAMORAK_KILLS`
- 1507 `TOTAL_PRIME_KILLS`
- 1508 `TOTAL_REX_KILLS`
- 1509 `TOTAL_SUPREME_KILLS`
- 1510 `TOTAL_CALLISTO_KILLS`
- 1511 `TOTAL_VENENATIS_KILLS`
- 1512 `TOTAL_VETION_KILLS`
- 1513 `TOTAL_CHAOSELE_KILLS`
- 1514 `TOTAL_KBD_KILLS`
- 1515 `TOTAL_MOLE_KILLS`
- 1516 `TOTAL_KALPHITE_KILLS`
- 1517 `TOTAL_CORP_KILLS`
- 1518 `TOTAL_SNAKEBOSS_KILLS`
- 1519 `TOTAL_CHAOSFANATIC_KILLS`
- 1520 `TOTAL_SCORPIA_KILLS`
- 1521 `TOTAL_CRAZYARCHAEOLOGIST_KILLS`
- 1522 `TOTAL_JAD_KILLS`
- 1523 `TOTAL_KRAKEN_BOSS_KILLS`
- 1524 `TOTAL_THERMY_KILLS`
- 1525 `TOTAL_CERBERUS_KILLS`
- 1526 `TOTAL_ABYSSALSIRE_KILLS`
- 1527 `TOTAL_CATA_BOSS_KILLS`
- 1528 `TOTAL_WINTERTODT_KILLS`
- 1529 `TOTAL_HILLGIANT_BOSS_KILLS`
- 1532 `TOTAL_COMPLETED_XERICCHAMBERS`
- 1533 `COMPLETED_CLUES3`
- 1534 `COMPLETED_CLUES4`
- 1585 `TOTAL_ZUK_KILLS`
- 1661 `TOTAL_DERANGEDARCHAEOLOGIST_KILLS`
- 1669 `TOTAL_GARGBOSS_KILLS`
- 1691 `TOTAL_VORKATH_KILLS`
- 1733 `TOTAL_BRYOPHYTA_KILLS`
- 1735 `TOTAL_COMPLETED_XERICCHAMBERS_CHALLENGE`
- 1748 `TOTAL_COMPLETED_THEATREOFBLOOD`
- 2074 `TOTAL_HYDRABOSS_KILLS`
- 2075 `TOTAL_HESPORI_KILLS`
- 2201 `COMPLETED_CLUES5`
- 2221 `TOTAL_MIMIC_KILLS`
- 2233 `TOTAL_SARACHNIS_KILLS`
- 2352 `TOTAL_ZALCANO_KILLS`
- 2353 `TOTAL_COMPLETED_GAUNTLET`
- 2354 `TOTAL_COMPLETED_GAUNTLET_HM`
- 2664 `TOTAL_NIGHTMARE_KILLS`
- 2671 `TOTAL_NIGHTMARE_CHALLENGE_KILLS`
- 2934 `TOTAL_TEMPOROSS_KILLS`
- 3057 `TOTAL_COMPLETED_THEATREOFBLOOD_HARD`
- 3058 `TOTAL_COMPLETED_THEATREOFBLOOD_STORY`
- 3269 `TOTAL_NEX_KILLS`
- 3397 `TOTAL_GOTR_KILLS`
- 3645 `TOTAL_COMPLETED_TOMBSOFAMASCUT_ENTRY`
- 3646 `TOTAL_COMPLETED_TOMBSOFAMASCUT`
- 3647 `TOTAL_COMPLETED_TOMBSOFAMASCUT_EXPERT`
- 3752 `TOTAL_MUSPAH_KILLS`
- 3761 `TOTAL_ARTIO_KILLS`
- 3762 `TOTAL_SPINDEL_KILLS`
- 3763 `TOTAL_CALVARION_KILLS`
- 3967 `TOTAL_DUKE_SUCELLUS_KILLS`
- 3968 `TOTAL_LEVIATHAN_KILLS`
- 3969 `TOTAL_WHISPERER_KILLS`
- 3970 `TOTAL_VARDORVIS_KILLS`
- 3971 `TOTAL_VARDORVIS_AWAKENED_KILLS`
- 3972 `TOTAL_WHISPERER_AWAKENED_KILLS`
- 3973 `TOTAL_LEVIATHAN_AWAKENED_KILLS`
- 3974 `TOTAL_DUKE_SUCELLUS_AWAKENED_KILLS`
- 4079 `TOTAL_RAT_BOSS_KILLS`
- 4148 `PMOON_ECLIPSE_KILLS`
- 4149 `PMOON_BLUE_KILLS`
- 4150 `PMOON_BLOOD_KILLS`
- 4186 `TOTAL_PMOON_CHESTS`
- 4187 `TOTAL_SOL_KILLS`
- 4260 `TOTAL_ARAXXOR_KILLS`
- 4403 `TOTAL_AMOXLIATL_KILLS`
- 4404 `TOTAL_HUEY_KILLS`
- 4648 `TOTAL_ROYAL_TITAN_KILLS`
- 4701 `TOTAL_YAMA_KILLS`
- 4825 `TOTAL_GEMSTONE_CRAB_KILLS`
- 4945 `TOTAL_GRYPHON_BOSS_KILLS`
- 5407 `TOTAL_COWBOSS_KILLS`
- 5408 `TOTAL_COWBOSS_HARDMODE_KILLS`
- 5614 `TOTAL_MAGGOT_KING_KILLS`
- 5712 `TOTAL_MAD_ANGEL_KILLS`
- 5894 `TOTAL_COMPLETED_THEFRACTUREDARCHIVE`

## Tool leprechaun

Values are raw. `rake`, `dibber`, `spade`, `trowel`, `secateurs` and `magic_secateurs` are stored flags,
`watering_can` is the stored can's state, and `buckets`, `compost`, `supercompost`, `ultracompost` and `plant_cure` are
counts. The `extra_*` and `extra2_*` values extend those counts and tools; how they combine is decoded server-side and is
not verified in game.

## Open checks (in game)

- The exact header wording of boss, raid, Inferno, Colosseum and clue pages, and that the counter lines are present on
  the tick after a page opens.
- That every `kill_counts_raw` varp outside the verified pairs is sent to the client.
- That varp 1780 equals the days the game shows, and that the tool varbits arrive at login without opening the
  leprechaun.
