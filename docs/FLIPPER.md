# Flipper (candidate)

This candidate adds the requested Flipper toggle, a RuneLite sidebar and a small Grand Exchange sell-offer overlay.
It is disabled by default. It is not a release record; the released plugin remains described in README.md.

## Player behavior

- The sidebar reads the selected sell offer's item, quantity and applied price directly from the game.
- A new offer shows the profit if its whole quantity sells. An existing offer uses only its remaining quantity.
- Completed sales are excluded. A finished offer does not show its old estimate as completed profit.
- The calculation shows after-tax profit, total tax, profit per item, break-even price and after-tax proceeds.
- Unknown purchase cost means unknown profit. The player can enter a whole GP purchase price per item,
  including zero; blank uses a suitable recorded buy average when available.
- The recorded average is the latest fully observed completed or cancelled purchase in this enabled session.
  It uses actual GP spent divided by purchased quantity, not the listing price. A larger sale needs manual input.
  It is a named calculation basis, not a claim that the same inventory units remain or a FIFO profit ledger.
- A manual draft cost follows confirmation only when a new sell-offer event matches its slot, item, quantity
  and price. A collected/replaced slot cannot inherit it. Changing item clears an unconfirmed manual cost.
- Closing the offer editor clears an unconfirmed manual cost. Applied quantity/price edits keep it.
- Invalid manual text shows unknown profit, even when a recorded purchase exists.
- The display predicts neither execution price nor fill probability. The player still sets and confirms orders.

The sidebar uses the existing OSRS Best in Slot wordmark. The overlay carries the same product name and appears
below the GE frame when space exists. Native game input hides the overlay and pauses the estimate until applied.
The overlay follows the frame until the player moves it using RuneLite's overlay controls. Very large
break-even amounts use an explicit approximation in the compact overlay; the sidebar keeps the exact GP value.

## Native state and tax contract

The reader runs on PostClientTick, after clientscript execution. It requires a visible ordinary GE setup/detail
window, a valid selected slot, sell side, matching rendered item/quantity/price, and a native after-tax total
equal to the calculated full-offer total. Missing or mismatched widget data shows a waiting state.

The current native contract was checked against RuneLite 1.13.6 and revision242 scripts:

| Field | API / native value |
| --- | --- |
| Selected item | VarPlayer.CURRENT_GE_ITEM, varp1151 |
| Quantity | VarbitID.GE_NEWOFFER_QUANTITY,4396 |
| Side | VarbitID.GE_NEWOFFER_TYPE,4397; sell=1 |
| Slot | VarbitID.GE_SELECTEDSLOT,4439; subtract1 |
| Long price | VarPlayerID.GE_NEWOFFER_PRICE,5753 |
| Effective tax rate | VarbitID.GE_TRANSMIT_TAXRATE,13139; per thousand |
| Setup children | item23, quantity34, price41, net total45 |
| Detail children | item7, quantity18, price25, net total29 |

The game supplies the selected item's effective rate, including zero for exempt items. Tax per item is
min(5,000,000,floor(price*rate/1000)). The implementation validates its result against the native offer total,
so it does not ship an exemption list that can drift. Slot and rate share native varp375.

Integer tax avoids floating-point rounding and multiplication overflow. Totals use BigInteger. Purchase
averages use DECIMAL128 until display rounding. Break-even is the minimum positive integer selling price
whose after-tax proceeds cover the unrounded purchase basis, with the tax cap applied. A mathematical
break-even that would exceed the native 2,149,631,130,647 GP gross-offer ceiling for the displayed quantity
shows "Above offer limit" rather than an unplaceable price (script779, revision242; equality is allowed).

Sources:
- https://github.com/runelite/cs2-scripts/tree/d022d6f70b76e3890cab1287a7f45e905c2848d8
  (scripts773,779,807,819,821; revision242,2026-11-03).
- https://archive.openrs2.org/caches/runescape/2757 (Jagex revision242).
- https://static.runelite.net/runelite-api/apidocs/net/runelite/api/events/ClientTick.html
- https://www.osrsbestinslot.com/theme/img/osrs-bis-logo.webp (existing product wordmark, converted to PNG).

Alternative layouts, unsupported clients and the native too-much-money state fail closed. A waiting state is
not evidence that the item is tax-exempt. The same source contract must be checked if the game's layout changes.

## Lifecycle and privacy

Game state, recorded buys and manual cost bindings stay on the client thread. Immutable views cross to Swing.
A queued manual edit revalidates native selection before accepting its selection key.
Logout, hop, disconnect, unknown/changed account, changed profile and disable clear local costs and observations.
A region load hides the selected estimate; it does not make an old offer into a new purchase.
Enabling against existing filled offers takes a baseline, never inventing a purchase observed from its start.
Shutdown removes the event subscriber, sidebar and overlay. Queued startup, shutdown and manual-input
callbacks are bound to their lifecycle generation; a rapid restart cannot reuse an old panel or edit.

The calculator makes no network requests, sends no new fields, stores no purchase history or manual amounts,
and works without a link token. Only the requested Flipper toggle is persisted in the plugin configuration.
It does not change the existing account collector or staff gates.

## Verification and release boundary

The focused tests cover tax floors/caps/exemptions, minimum break-even, fractional purchase averages, large
totals, losses, unknown costs, real EventBus-to-view/overlay flow, native-total mismatch, input hiding,
remaining quantities, manual confirmation/slot reuse, stale queued edits, account/session cleanup and shutdown.
A real Swing/Graphics2D fixture produces sidebar PNGs in build/reports/flipper-render at225px width.
Those are synthetic renders, not evidence from a logged-in game client.

The integrated release uses the approved live-data validation approach. Real operation still needs to confirm
GE setup/details, taxed and exempt items, edits, recorded buys, partial sales, cancellation/collection,
account transitions, fixed/resizable layout and coexistence with other GE overlays. Fixture renders and
unit tests do not claim logged-in acceptance. The complete build and unchanged staff artifact acceptance
gate remain required.
