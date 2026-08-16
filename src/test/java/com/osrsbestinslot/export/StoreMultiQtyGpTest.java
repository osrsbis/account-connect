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

		// 20 coal cost 2,000 total; coins end at 998,000.
		Map<String, Object> fields = AccountConnectPlugin.buildStoreTxFields(
			merged.type, merged.item, merged.qty, merged.coinsBefore, 998_000L, merged.ambiguous);

		assertEquals("the exact transacted gold must be present", 2_000L, fields.get("gp_total"));
		assertEquals(20, fields.get("qty"));
		assertEquals("average unit price", 100L, fields.get("unit_price_gp"));
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
			merged.type, merged.item, merged.qty, merged.coinsBefore, 500_800L, merged.ambiguous);
		assertEquals("sell proceeds must be captured", 800L, fields.get("gp_total"));
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

	@Test
	public void clicksOnDifferentTicksAreIndependentAndNeverMerge()
	{
		AccountConnectPlugin.StorePending first =
			AccountConnectPlugin.mergeStorePending(null, "store_buy", COAL, 10, 1_000_000L, TICK);
		AccountConnectPlugin.StorePending second =
			AccountConnectPlugin.mergeStorePending(first, "store_buy", COAL, 10, 999_000L, TICK + 1);

		assertFalse(second.ambiguous);
		assertEquals("a later tick is its own transaction — quantities must NOT sum", 10, second.qty);
		assertEquals("and it uses its OWN coinsBefore", 999_000L, second.coinsBefore);
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
}
