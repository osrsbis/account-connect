package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/**
 * 0.7.4 item 3 — STORE gp_total INTEGRITY FOR MULTI-QUANTITY TRANSACTIONS.
 *
 * <p>Production evidence (2026-08-15 audit, newest clients only): {@code gp_total} was missing from
 * <b>140 of 377 (37.1%)</b> multi-quantity {@code store_buy} events against <b>14 of 176 (8.0%)</b> at
 * qty=1. The miss concentrates on qty&gt;1 because a player spam-clicking "Buy 10" lands two clicks on
 * one tick, which the old code flagged {@code ambiguous} and then dropped the gold for.
 *
 * <p>General-store selling is how gold is delivered, so those four weeks of events showed the item
 * leaving and never the coins arriving.
 *
 * <p>The fix folds same-tick clicks on the SAME item and direction into ONE pending with a summed
 * quantity and the ORIGINAL coinsBefore — sound because a pending is only consumed by an inventory
 * change, so an un-resolved pending proves no inventory change has happened and its coinsBefore still
 * predates both transactions. Genuinely un-splittable cases (different item, opposite direction) stay
 * ambiguous and still degrade to {item, qty} rather than emitting a guess.
 */
public class StoreMultiQtyGpTest
{
	private static final int TICK = 4242;
	private static final int COAL = 453;
	private static final int ORE = 440;

	// ------------------------------------------------------------------ the defect, directly

	@Test
	public void sameTickRepeatBuyOnOneItemNowCarriesExactGpInsteadOfDroppingIt()
	{
		// Two "Buy 10" clicks on the same tick, coins 1,000,000 before either.
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending merged =
			AccountConnectPlugin.mergeStorePending(first, "store_buy", COAL, 10, 999_000L, TICK);

		assertFalse("a same-item same-tick repeat is NOT ambiguous — one delta covers both",
			merged.ambiguous);
		assertEquals("quantities must sum", 20, merged.qty);
		assertEquals("coinsBefore must stay the ORIGINAL pre-transaction reading",
			1_000_000L, merged.coinsBefore);

		// 20 coal cost 2,000 total; coins end at 998,000. Both clicks executed here.
		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			merged.type, merged.item, merged.qty, merged.coinsBefore, 998_000L, merged.ambiguous, merged.qtyMerged);

		assertEquals("the exact transacted gold must be present", 2_000L, fields.get("gp_total"));
		assertEquals(20, fields.get("qty"));
		assertNull("merged qty is click intent, so no unit price may be derived from it",
			fields.get("unit_price_gp"));
	}

	@Test
	public void beforeTheFixThisExactCaseEmittedNoGold()
	{
		// The old behaviour, reproduced by passing ambiguous=true: item and qty survive, gold does not.
		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			"store_buy", COAL, 20, 1_000_000L, 998_000L, true);

		assertNull("this is the production defect: 37.1% of multi-qty buys looked like this",
			fields.get("gp_total"));
		assertEquals("only item and qty survived", 2, fields.size());
	}

	// ------------------------------------------------------------------ the half-price regression

	/**
	 * REGRESSION GUARD for the defect the conservative fix exists to prevent.
	 *
	 * <p>A merged qty is the sum of click INTENT. A click can fail — shop out of stock, inventory full,
	 * not enough coins — while still having been counted into that sum. So when two "Buy 10" clicks merge
	 * but only the first executes, the truth is: 10 items moved for 1,000 gp (100 gp each).
	 *
	 * <p>gp_total is still EXACT, because it is the measured coin delta and the coins only moved for the
	 * click that actually happened. But qty reads 20, so a derived unit price would be 1000/20 = 50 —
	 * <b>half the real unit price</b>, and plausible enough that nothing downstream would ever question it.
	 *
	 * <p>That is strictly worse than the bug this release fixes: an absent field is recoverable, a
	 * confidently wrong number is not. unit_price_gp must therefore be ABSENT on any merged pending.
	 */
	@Test
	public void mergedQuantityNeverProducesAHalfPriceUnitCost()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending merged =
			AccountConnectPlugin.mergeStorePending(first, "store_buy", COAL, 10, 999_000L, TICK);

		assertTrue("a merged pending must be marked as such", merged.qtyMerged);

		// Only the FIRST click executed: 10 coal at 100 gp = 1,000 gp. Coins 1,000,000 -> 999,000.
		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			merged.type, merged.item, merged.qty, merged.coinsBefore, 999_000L, merged.ambiguous, merged.qtyMerged);

		assertEquals("the coin delta is ground truth and stays exact", 1_000L, fields.get("gp_total"));
		assertNull("unit_price_gp MUST be omitted — 1000/20 = 50 would be half the real 100 gp price",
			fields.get("unit_price_gp"));
	}

	@Test
	public void unmergedMultiQuantityStillCarriesAUnitPriceBecauseItsDenominatorIsConfirmed()
	{
		// A single "Buy 20" click: qty came from ONE menu option, so the denominator is trustworthy.
		AccountConnectPlugin.StorePending p =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 20, 1_000_000L, TICK);
		assertFalse("a single click is not merged", p.qtyMerged);

		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			p.type, p.item, p.qty, p.coinsBefore, 998_000L, p.ambiguous, p.qtyMerged);
		assertEquals(2_000L, fields.get("gp_total"));
		assertEquals("a confirmed denominator may still yield a unit price", 100L, fields.get("unit_price_gp"));
	}

	/**
	 * A merged row must SAY it is merged, so nothing downstream pairs gp_total against an unconfirmed
	 * qty. Two things can make the merged qty overstate what those coins bought: a failed click, or the
	 * server settling the two clicks on different ticks (the first inventory update then carries only
	 * the first transaction's delta). Neither is detectable client-side, so the row is labelled rather
	 * than guessed at.
	 */
	@Test
	public void aMergedRowIsLabelledSoConsumersTreatQtyAsAnUpperBound()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending merged =
			AccountConnectPlugin.mergeStorePending(first, "store_buy", COAL, 10, 999_000L, TICK);

		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			merged.type, merged.item, merged.qty, merged.coinsBefore, 998_000L, merged.ambiguous, merged.qtyMerged);

		assertEquals("coins that really moved", 2_000L, fields.get("gp_total"));
		assertEquals("the row must carry the merged marker", Boolean.TRUE, fields.get("qty_merged"));
		assertNull("and never a derived unit price", fields.get("unit_price_gp"));
	}

	@Test
	public void anUnmergedRowCarriesNoMergedMarker()
	{
		AccountConnectPlugin.StorePending p =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 20, 1_000_000L, TICK);
		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			p.type, p.item, p.qty, p.coinsBefore, 998_000L, p.ambiguous, p.qtyMerged);

		assertNull("a single click needs no caveat", fields.get("qty_merged"));
		assertEquals("and keeps its unit price", 100L, fields.get("unit_price_gp"));
	}

	@Test
	public void sameTickRepeatSellOnOneItemAlsoResolves()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_sell", COAL, 5, 500_000L, TICK);
		AccountConnectPlugin.StorePending merged =
			AccountConnectPlugin.mergeStorePending(first, "store_sell", COAL, 5, 500_100L, TICK);

		assertFalse(merged.ambiguous);
		assertEquals(10, merged.qty);

		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			merged.type, merged.item, merged.qty, merged.coinsBefore, 500_800L, merged.ambiguous, merged.qtyMerged);
		assertEquals("sell proceeds must be captured", 800L, fields.get("gp_total"));
		assertNull("merged sell qty is intent too — no unit price", fields.get("unit_price_gp"));
	}

	// ------------------------------------------------------------------ genuine ambiguity is PRESERVED

	@Test
	public void sameTickClicksOnDifferentItemsStayAmbiguousBecauseOneDeltaCannotBeSplit()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending merged =
			AccountConnectPlugin.mergeStorePending(first, "store_buy", ORE, 10, 999_000L, TICK);

		assertTrue("two different items in one delta cannot be attributed", merged.ambiguous);

		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			merged.type, merged.item, merged.qty, merged.coinsBefore, 990_000L, merged.ambiguous);
		assertNull("we must still refuse to emit a guessed number", fields.get("gp_total"));
	}

	@Test
	public void sameTickBuyThenSellStaysAmbiguous()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending merged =
			AccountConnectPlugin.mergeStorePending(first, "store_sell", COAL, 10, 999_000L, TICK);

		assertTrue("opposite directions net against each other in one delta", merged.ambiguous);
	}

	@Test
	public void anAlreadyAmbiguousPendingStaysAmbiguousWhenAThirdClickLands()
	{
		AccountConnectPlugin.StorePending a =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending b =
			AccountConnectPlugin.mergeStorePending(a, "store_buy", ORE, 10, 999_000L, TICK);
		AccountConnectPlugin.StorePending c =
			AccountConnectPlugin.mergeStorePending(b, "store_buy", COAL, 10, 998_000L, TICK);

		assertTrue("ambiguity must not be laundered away by a later same-item click", c.ambiguous);
	}

	// ------------------------------------------------------------------ unaffected paths

	/**
	 * SUPERSEDED BY FIELD EVIDENCE (2026-08-17). This test previously asserted that a click one tick
	 * later "is its own transaction — quantities must NOT sum", and it passed. The live client then
	 * showed that assertion describes a defect: two "Buy 10" clicks a tick apart emitted one click's
	 * qty against both clicks' gold plus a fabricated {@code unit_price_gp} of 1,132 (true ~755). The
	 * premise was wrong — a pending is only consumed by an inventory change, so an unresolved pending a
	 * tick later still has a {@code coinsBefore} that predates both transactions.
	 *
	 * <p>Kept, rewritten to assert the corrected contract at the boundary that actually matters: inside
	 * the resolution window the pending merges and is LABELLED; outside it the clicks stay independent.
	 * See {@code adjacentTickRepeatBuyMergesInsteadOfPairingOneQtyWithTwoClicksOfGold} and
	 * {@code aPendingOlderThanTheMergeWindowDoesNotMerge}.
	 */
	@Test
	public void clicksMergeInsideTheResolutionWindowAndAreIndependentOutsideIt()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);

		AccountConnectPlugin.StorePending inside = AccountConnectPlugin.mergeStorePending(
			first, "store_buy", COAL, 10, 999_000L, TICK + AccountConnectPlugin.STORE_PENDING_MAX_TICKS);
		assertTrue("still unresolved, so still one delta", inside.qtyMerged);
		assertEquals(20, inside.qty);
		assertEquals("the original pre-transaction reading", 1_000_000L, inside.coinsBefore);

		AccountConnectPlugin.StorePending outside = AccountConnectPlugin.mergeStorePending(
			first, "store_buy", COAL, 10, 999_000L,
			TICK + AccountConnectPlugin.STORE_PENDING_MAX_TICKS + 1);
		assertFalse("past the window the resolver would drop it anyway", outside.qtyMerged);
		assertFalse(outside.ambiguous);
		assertEquals("its own transaction — quantities must NOT sum", 10, outside.qty);
		assertEquals("and it uses its OWN coinsBefore", 999_000L, outside.coinsBefore);
	}

	/**
	 * A colour tag's own hex digits must never be read as a quantity. "Sell 10&lt;col=ff9040&gt;" scanned
	 * naively for a trailing digit run yields 9040 — a plausible-looking number, which is the failure
	 * shape this whole release is about. Tags are stripped before the quantity is read.
	 */
	@Test
	public void colourTagHexDigitsAreNeverMistakenForAQuantity()
	{
		assertEquals(10, AccountConnectPlugin.parseTrailingQty("Sell 10<col=ff9040>"));
		assertEquals("a tag containing digits, with no quantity at all", 1,
			AccountConnectPlugin.parseTrailingQty("Sell<col=ff9040>"));
		assertEquals("leading tag too", 10,
			AccountConnectPlugin.parseTrailingQty("<col=00ff00>Sell 10<col=ff9040>"));
	}

	@Test
	public void singleQuantityBuyIsUnchanged()
	{
		AccountConnectPlugin.StorePending p =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 1, 1_000_000L, TICK);
		assertFalse(p.ambiguous);

		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			p.type, p.item, p.qty, p.coinsBefore, 999_900L, p.ambiguous);
		assertEquals(100L, fields.get("gp_total"));
		assertNull("no average unit price for a single item", fields.get("unit_price_gp"));
	}

	@Test
	public void wrongSignDeltaStillDegradesRatherThanEmittingNonsense()
	{
		// A buy whose coin count went UP cannot be that buy's delta — refuse it.
		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			"store_buy", COAL, 20, 1_000_000L, 1_005_000L, false);
		assertNull(fields.get("gp_total"));
	}

	// ------------------------------------------------------------------ FIELD-OBSERVED defects (2026-08-17)

	/**
	 * FIELD DEFECT 1 — every general-store SELL was logging qty 1.
	 *
	 * <p>Observed live on 2026-08-17 at the Varrock General Store: one "Sell 10" click moved ten items
	 * out of the inventory and 1,325 gp in, and the event reached production D1 as
	 * {@code {"item":22660,"qty":1,"gp_total":1325}} — a consumer dividing those gets 1,325 gp per item
	 * against a true 132.5, ten times over. Reproduced twice.
	 *
	 * <p>Cause: the client's SELL option string carries a colour tag the BUY string does not, so the
	 * trailing token is "10&lt;col=ff9040&gt;", which {@code Integer.parseInt} rejects and the catch
	 * silently defaults to 1. The pre-existing test asserted on the clean string "Sell 10", which the
	 * live client never sends — the assertion was true and the behaviour was still wrong.
	 *
	 * <p>The strings below are verbatim from the client's own MenuOptionClicked trace.
	 */
	@Test
	public void sellQuantityIsParsedFromTheColourTaggedOptionTheClientActuallySends()
	{
		assertEquals("verbatim from the live client trace", 10,
			AccountConnectPlugin.parseTrailingQty("Sell 10<col=ff9040>"));
		assertEquals(50, AccountConnectPlugin.parseTrailingQty("Sell 50<col=ff9040>"));
		assertEquals(5, AccountConnectPlugin.parseTrailingQty("Sell 5<col=ff9040>"));
		assertEquals(1, AccountConnectPlugin.parseTrailingQty("Sell 1<col=ff9040>"));
		// the buy strings are untagged and must keep working exactly as before
		assertEquals(10, AccountConnectPlugin.parseTrailingQty("Buy 10"));
		assertEquals(50, AccountConnectPlugin.parseTrailingQty("Buy 50"));
		assertEquals("no trailing number still means one", 1,
			AccountConnectPlugin.parseTrailingQty("Buy"));
		assertEquals("a tag with no number is still one", 1,
			AccountConnectPlugin.parseTrailingQty("Sell<col=ff9040>"));
	}

	/**
	 * FIELD DEFECT 2 — two clicks on ADJACENT ticks emitted one click's qty against both clicks' gold,
	 * and then derived a unit price from the mismatch.
	 *
	 * <p>Observed live on 2026-08-17: two rapid "Buy 10" clicks, fifteen packs received (the shop ran
	 * short), 11,325 gp spent. D1 got {@code qty:10, gp_total:11325, unit_price_gp:1132} with NO
	 * {@code qty_merged} flag — an exact-looking unit price against a true ~755, indistinguishable
	 * downstream from a genuinely correct single-click row. Reproduced twice.
	 *
	 * <p>Cause: the merge only fired on {@code prev.tick == tick}. One tick apart, {@code prev} was
	 * dropped and the new pending kept its own {@code coinsBefore} — but no inventory change had
	 * resolved yet, so that reading STILL predates both transactions. The same argument that justifies
	 * the same-tick merge applies to any unresolved pending: what makes {@code coinsBefore} usable is
	 * that nothing has been consumed, not that the clock agrees.
	 */
	@Test
	public void adjacentTickRepeatBuyMergesInsteadOfPairingOneQtyWithTwoClicksOfGold()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending merged =
			AccountConnectPlugin.mergeStorePending(first, "store_buy", COAL, 10, 999_000L, TICK + 1);

		assertTrue("an unresolved pending one tick back must still merge", merged.qtyMerged);
		assertEquals("quantities sum", 20, merged.qty);
		assertEquals("the ORIGINAL pre-transaction coin reading survives",
			1_000_000L, merged.coinsBefore);

		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			merged.type, merged.item, merged.qty, merged.coinsBefore, 998_000L,
			merged.ambiguous, merged.qtyMerged);
		assertEquals("both clicks' gold", 2_000L, fields.get("gp_total"));
		assertNull("a merged qty may never produce a unit price", fields.get("unit_price_gp"));
	}

	/**
	 * The field defect's exact numbers, as the fixed code must now report them: two "Buy 10" clicks a
	 * tick apart that delivered fifteen items for 11,325 gp. The row must be LABELLED and must not
	 * carry the fabricated 1,132 unit price.
	 */
	@Test
	public void theFieldObservedAdjacentTickBuyNoLongerFabricatesAUnitPrice()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", 22660, 10, 26_233_393L, TICK);
		AccountConnectPlugin.StorePending merged =
			AccountConnectPlugin.mergeStorePending(first, "store_buy", 22660, 10, 26_227_000L, TICK + 1);

		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			merged.type, merged.item, merged.qty, merged.coinsBefore, 26_222_068L,
			merged.ambiguous, merged.qtyMerged);

		assertEquals("the measured coin delta, unchanged", 11_325L, fields.get("gp_total"));
		assertEquals(Boolean.TRUE, fields.get("qty_merged"));
		assertNull("1132 gp/item was the fabrication this test exists to prevent",
			fields.get("unit_price_gp"));
	}

	/**
	 * The merge window must stay BOUNDED. Two clicks far apart are two separate transactions and the
	 * later one owns its own coin reading — merging them would attribute the first purchase's gold to
	 * the second.
	 */
	@Test
	public void aPendingOlderThanTheMergeWindowDoesNotMerge()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending second = AccountConnectPlugin.mergeStorePending(
			first, "store_buy", COAL, 10, 999_000L, TICK + AccountConnectPlugin.STORE_PENDING_MAX_TICKS + 1);

		assertFalse("too far apart to share one delta", second.qtyMerged);
		assertEquals("qty is this click's alone", 10, second.qty);
		assertEquals("and it uses its OWN coinsBefore", 999_000L, second.coinsBefore);
	}
}
