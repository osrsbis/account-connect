# Kill detail and kill context (0.7.65)

Two parts with separate server grants. Each sends nothing until the server answers the
linked token with its header set to `on`: `X-Kill-Detail` and `X-Kill-Context`. A refused, failed or missing answer,
or an answer without the header, turns that part off again.

## What it adds

All rows are the player's own and come from the game's own GAMEMESSAGE/SPAM lines or the player's own inventory.
No line read here names another player. The line shapes are the ones RuneLite's own Chat Commands and Loot Tracker
plugins read; the code here is written independently.

| Row | From | Fields |
|---|---|---|
| `kill_timing` | fight, lap, challenge and raid completion lines ("Fight duration: 1:36 (new personal best)") | `seq`, `label`, `duration_s`, `new_pb`, `personal_best_s`, and when printed `team_size` (raids) and `room` (Tombs of Amascut, Colosseum) |
| `kill_count` | Hallowed Sepulchre floor and Grand Hallowed Coffin lines, Guardians of the Rift rifts closed | `source`, `kind`, `count`, as for every other count line |
| `loot`, `source_type` `event` | the reward line of the Tempoross reward pool, the Wintertodt reward cart, Guardians of the Rift (each only in its own region) and Herbiboar, then the inventory gain on the next inventory change | `source_name` (Loot Tracker's name), `items`, `value` |

- A timing line does not name the boss. It arrives on the same game tick as the boss's `kill_count` line, so the server
  pairs the two by account and time; the plugin does not.
- `personal_best_s` is the previous best when the game prints one. On a new best it equals `duration_s`.
- A ToA or Colosseum line prints two times (the room and the whole run); each is its own row, numbered by `seq`.

## Kill context (`X-Kill-Context`)

A separate grant, because it says more about the player: what they carried at every kill.

| Row | From | Fields |
|---|---|---|
| `kill_context` | read on the tick of each `kill_count` row | `source`, `count`, `worn` and `inventory` as [item id, quantity] pairs, `levels_boosted_real` (attack, strength, defence, ranged, magic, hitpoints, prayer: [boosted, real]), `special_energy` (percent), `world_members`, and for a Tombs of Amascut count `toa_raid_level` (the raid level the game shows) |
| `slayer_task` | the game's completed slayer tasks counter (varbit 4069) going up by exactly one | `tasks_completed`, `task_target_id` of the task that ended |
| `progress` | the clue, collection-log, combat-task and salvage lines that `docs/ACCOUNT_PROGRESS.md` describes, sent as timed rows | `kind`, `subject` and the fields of that kind |
| `attempt_end` | the game's "Your party failed to complete the challenge" line | `outcome` `failed`, `basis` |
| `activity_counter` | a change of one of the game's own unit counters: Doom of Mokhaiotl clears per delve level and deepest level, Colosseum waves completed and highest wave, Tempoross permits, Tithe Farm points, Barbarian Assault role points, and the game's own completion totals for Jad, Zuk, Sol Heredit, Doom levels, both Gauntlets, The Fractured Archive, Wintertodt, Tempoross, Guardians of the Rift, every raid mode (CoX normal and Challenge Mode; ToB entry, normal and hard; ToA entry, normal and expert), Nightmare, Phosani's Nightmare, Nex, Zalcano, Moons chests, Yama and the Maggot King, plus Soul Wars zeal and games, Giants' Foundry points and Mastering Mixology orders and points. Each is the last value the server set. The first change after a login may be a late initial update rather than a unit played then. Also the other minigames with a named persistent var (Castle Wars wins and games, Soul Wars wins, kills and tokens, Barbarian Assault high gambles and queen kills, Nightmare Zone points, LMS rank, Bounty Hunter, PvP Arena, Vale Totems research points, golem crafting, the Warriors' Guild games var), and balances the game shows only on screen, read while that screen is open (Magic Training Arena room points, Pest Control, LMS, Volcanic Mine, Mahogany Homes and PvP Arena shop points), and the remaining minigame vars found by the signal research (TzHaar-Ket-Rak's six challenge totals, Castle Wars losses and per-game kills and captures, LMS kills per game, Pest Control activity, Guardians of the Rift points this game, Fishing Trawler contribution, Fight Pit foes remaining, Archery Competition, Barracuda Trials, Gnome Restaurant, Pyramid Plunder room, Trouble Brewing pieces of eight, Tai Bwo Wannai cleanup, Tears of Guthix, stealing artefacts and valuables, Wise Old Man tasks, Burthorpe Games Room ranks, Shades of Mort'ton sanctity, Miscellania coffers and approval, Blast Furnace bars, Blast Mine ore, Mage Arena, Knight Waves and Pete Kayer's challenges). `kind` is `total` (a completion total that only rises with play), `balance` (points the player also spends, or a rating; never a unit count by itself) or `unverified` (meaning known from the var's name only; never counted until the rig confirms it). A screen balance seen for the first time is sent once with `seen: first` and no `from` | `key`, `from`, `to`, `kind`, `seen` |
| `minigame` | a minigame result from the game itself: a completion, win, loss, reward or start line (TzHaar-Ket-Rak's challenge named, PvP Arena duels, Agility Arena tickets, Hallowed Sepulchre floors and Grand Coffin, Guardians of the Rift rifts closed and reward searches, Hunter rumours, Forestry anima bark, Giants' Foundry swords, Pete Kayer's special-attack challenge, Volcanic Mine start, Barbarian Assault high gambles, Agility Arena ticket exchange, Chompy milestones), a result shown in a dialogue or message box (Arceuus Library book delivered, Giants' Foundry sword quality and total, Rogues' Den, Sorceress's Garden, Warriors' Guild keg balancing, Mage Arena capes, Knight Waves, Pete Kayer's challenges; only fixed labels and numbers leave the plugin, never the dialogue text), or the numbers on an end-of-game screen (Barbarian Assault wave points, Last Man Standing scoreboard, Castle Wars scores, Trouble Brewing score and pieces of eight, Soul Wars results and zeal, Werewolf Skullball time and XP, Rat Pits scores). A screen row carries numbers only, under fixed labels; no name or message text is read | `minigame`, `result`, `detail`, `count`, `total`, `screen` |

- Worn item ids and quantities do not establish remaining charges. An id tells the variant (charged or uncharged, and
  some degradation stages) and ammo carries its count, but charges stored inside an item (powered staves, blowpipe
  darts and scales, crystal equipment, bracelets and similar) change neither. Remaining charges are not captured.
- The inventory at two kills does not give the supplies used between them. Loot and pickups, banking, trades,
  drops, and items that change form (a potion losing a dose, a pie being half eaten, a charged item recharged) all
  move items too. Supplies used are not established; an estimate would need every one of those movements accounted
  for, and the plugin sends no inferred amount.
- A first read of the slayer counter, a jump or a drop (another account) only sets the baseline.
- Both counter trackers restart on every logout, hop, disconnect and grant change, so one account's counters are
  never compared with another's under the same token. After a restart the unit counters' first two ticks only set
  the baseline. That guards against values still arriving at login but does not prove every value has arrived; a
  late one shows as a single change.
- These rows do not delimit kill attempts. A kill count line marks a success, and a death or the party-failure line a
  failure, but nothing marks the start of an attempt: repeated fights in one area or instance produce no new region
  row, and an attempt abandoned by walking or teleporting away leaves no end line.

## Reward loot limits

These emit nothing instead of guessing:

- herbs that go into a herb sack, and a reward dropped on the ground because the inventory is full;
- an inventory change more than 10 game ticks after the reward line;
- a change that only removes items.

Only gained items count. An item used up in the same inventory change is not subtracted from the reward.

## Tests

`KillDetailTest`: parsing against the game's own lines, the region check, the reward window, the kill context shape
and its public projection, the slayer counter, and both grants (nothing sent without its own grant, revoked by an
answer without it).
