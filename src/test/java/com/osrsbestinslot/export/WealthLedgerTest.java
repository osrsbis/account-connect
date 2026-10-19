package com.osrsbestinslot.export;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.junit.Test;

import static org.junit.Assert.*;

/** C4 wealth ledger: holdings projection and reconciliation over buildSnapshot-shaped maps. */
public class WealthLedgerTest
{
	static final int LOBSTER = 379, SHARK = 385, BONES = 536, WHIP = 4151, RUNE_ARROW = 892;
	static final long T0 = 1_760_000_000L;	// captured_at seconds

	/** A snapshot map in the exact shape AccountConnectPlugin.buildSnapshot produces. */
	static final class Snap
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		final Map<String, Object> flags = new LinkedHashMap<>();
		final List<Map<String, Object>> inv = new ArrayList<>(), offers = new ArrayList<>();
		final Map<String, Object> eqp = new LinkedHashMap<>();

		Snap(long capturedAt)
		{
			m.put("schema_v", 1);
			m.put("captured_at", capturedAt);
			Map<String, Object> account = new LinkedHashMap<>();
			account.put("rsn", "Someone");
			account.put("account_hash", "123456789");
			account.put("account_type", "STANDARD");
			account.put("profile_type", "STANDARD");
			m.put("account", account);
			flags.put("equipment_synced", true);
			flags.put("inventory_synced", true);
			flags.put("bank_synced", false);
			m.put("capture_flags", flags);
			m.put("inventory", inv);
			m.put("equipment", eqp);
			m.put("ge_offers", offers);
			Map<String, Object> bank = new LinkedHashMap<>();
			bank.put("items", new ArrayList<>());
			bank.put("value_gp", 0L);
			bank.put("observed_at", null);
			m.put("bank", bank);
		}

		Snap inv(int id, long qty)
		{
			inv.add(item(id, qty));
			return this;
		}

		Snap worn(String slot, int id, long qty)
		{
			eqp.put(slot, item(id, qty));
			return this;
		}

		Snap bank(long observedAt, long... idQty)
		{
			flags.put("bank_synced", true);
			Map<String, Object> bank = block(observedAt, idQty);
			m.put("bank", bank);
			return this;
		}

		Snap storage(String key, long observedAt, long... idQty)
		{
			m.put(key, block(observedAt, idQty));
			return this;
		}

		Snap offer(int slot, int itemId, String state, int total, int done, int price, long spent)
		{
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("slot", slot);
			o.put("item_id", itemId);
			o.put("state", state);
			o.put("total_qty", total);
			o.put("transferred_qty", done);
			o.put("price_per_item", price);
			o.put("spent", spent);
			offers.add(o);
			return this;
		}

		Snap account(String hash, String profile)
		{
			@SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) m.get("account");
			a.put("account_hash", hash);
			a.put("profile_type", profile);
			return this;
		}

		WealthLedger.Holdings h()
		{
			return WealthLedger.fromSnapshot(m);
		}

		private static Map<String, Object> block(long observedAt, long... idQty)
		{
			List<Map<String, Object>> items = new ArrayList<>();
			for (int i = 0; i < idQty.length; i += 2)
			{
				items.add(item((int) idQty[i], idQty[i + 1]));
			}
			Map<String, Object> b = new LinkedHashMap<>();
			b.put("items", items);
			b.put("value_gp", 0L);
			b.put("observed_at", observedAt);
			return b;
		}
	}

	static Map<String, Object> item(int id, long qty)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("qty", qty);
		return m;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Long> classes(Map<String, Object> result, int itemId)
	{
		for (Map<String, Object> row : (List<Map<String, Object>>) result.get("items"))
		{
			if ((int) row.get("item_id") == itemId)
			{
				return (Map<String, Long>) row.get("classes");
			}
		}
		return Collections.emptyMap();
	}

	static Map<String, Object> rec(Snap a, Snap b)
	{
		return WealthReconciliation.reconcile(a.h(), b.h(), null, null, null, null);
	}

	@Test
	public void unopenedBankIsUnknownNotEmptyAndNoTotalIsClaimed()
	{
		WealthLedger.Holdings h = new Snap(T0).inv(995, 5000).inv(LOBSTER, 10).worn("weapon", WHIP, 1).h();
		assertEquals(WealthLedger.UNKNOWN, h.locations.get("bank").state);
		assertNull(h.locations.get("bank").items);
		assertFalse(h.coreComplete());
		assertEquals(5000L, h.knownCoins());
		Map<String, Object> view = h.toMap();
		assertEquals(false, view.get("account_total_claimed"));
		assertTrue(((List<?>) view.get("unknown_locations")).contains("bank"));
		assertTrue(((List<?>) view.get("unsupported_locations")).contains("rune_pouch"));
		// Not seen in what was read is never "not owned".
		Map<String, Object> owns = h.owns(SHARK);
		assertEquals("no_in_observed_locations", owns.get("answer"));
		assertTrue(((List<?>) owns.get("unknown_locations")).contains("bank"));
		assertEquals("yes", h.owns(WHIP).get("answer"));
	}

	@Test
	public void nothingReadAnswersUnknown()
	{
		Snap s = new Snap(T0);
		s.flags.put("inventory_synced", false);
		s.flags.put("equipment_synced", false);
		s.m.remove("ge_offers");
		assertEquals(WealthLedger.UNKNOWN, s.h().owns(WHIP).get("answer"));
	}

	@Test
	public void bankDepositIsTransferNotIncomeOrLoss()
	{
		Snap a = new Snap(T0).inv(995, 5000).inv(LOBSTER, 100).bank(T0 - 10, 995, 1_000_000, SHARK, 50);
		Snap b = new Snap(T0 + 60).inv(995, 0).bank(T0 + 50, 995, 1_005_000, SHARK, 50, LOBSTER, 100);
		Map<Integer, Long> p = Map.of(LOBSTER, 150L, SHARK, 900L);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, null, p, p);
		assertEquals("reconciled", r.get("status"));
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 100L), classes(r, LOBSTER));
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 5000L), classes(r, 995));
		assertEquals(true, r.get("conservation_holds"));
		assertEquals(0L, r.get("revaluation_gp"));
		@SuppressWarnings("unchecked") Map<String, Long> value = (Map<String, Long>) r.get("value_by_class_gp_at_from_prices");
		assertEquals(Long.valueOf(0L), value.get(WealthReconciliation.TRANSFER));
		assertTrue((Boolean) r.get("core_complete"));
	}

	@Test
	public void openBuyOfferLocksOnlyUnfilledCoinsAndCollectedItemsAreNotCountedTwice()
	{
		// Buy 100 lobsters at 200 each, 40 filled for 7,600 gp (overpay refund 400), not yet collected.
		Snap a = new Snap(T0).inv(995, 20_000).offer(0, LOBSTER, "BUYING", 100, 40, 200, 7600).bank(T0 - 5, 995, 1);
		WealthLedger.Holdings ha = a.h();
		WealthLedger.Escrow e = ha.escrow.get(0);
		assertEquals(60L * 200, e.lockedCoins);
		assertEquals(40L, e.maxUncollectedItems);
		assertEquals(400L, e.maxUncollectedCoins);
		assertEquals(WealthLedger.BOUNDED, e.escrowState);
		// Uncollected lobsters are an upper bound only: not in the known items.
		assertNull(ha.knownItems().get(LOBSTER));
		assertEquals(20_000L + 1 + 12_000L, ha.knownCoins());

		// The player collects the 40 lobsters (and the refund) while the offer stays open.
		Snap b = new Snap(T0 + 30).inv(995, 20_400).inv(LOBSTER, 40).offer(0, LOBSTER, "BUYING", 100, 40, 200, 7600)
			.bank(T0 - 5, 995, 1);
		WealthLedger.Holdings hb = b.h();
		assertEquals(Long.valueOf(40L), hb.knownItems().get(LOBSTER));	// once, from the inventory
		Map<String, Object> r = WealthReconciliation.reconcile(ha, hb, null, null, null, null);
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, 40L), classes(r, LOBSTER));
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, 400L), classes(r, 995));
		assertEquals(true, r.get("conservation_holds"));
	}

	@Test
	public void placingAnOfferIsTransferIntoEscrowAndFillIsTrade()
	{
		Snap a = new Snap(T0).inv(995, 20_000).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 10).inv(995, 0).offer(0, LOBSTER, "BUYING", 100, 0, 200, 0).bank(T0 - 5, 995, 1);
		Map<String, Object> r1 = rec(a, b);
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 20_000L), classes(r1, 995));

		// The offer completes at 190 each and nothing is collected yet: trade, and the goods sit in the collect box.
		Snap c = new Snap(T0 + 20).inv(995, 0).offer(0, LOBSTER, "BOUGHT", 100, 100, 200, 19_000).bank(T0 - 5, 995, 1);
		Map<String, Object> r2 = rec(b, c);
		Map<String, Long> lob = classes(r2, LOBSTER);
		assertEquals(Long.valueOf(100L), lob.get(WealthReconciliation.TRADE));
		assertEquals(Long.valueOf(-100L), lob.get(WealthReconciliation.GE_COLLECT_BOX));
		Map<String, Long> coins = classes(r2, 995);
		assertEquals(Long.valueOf(-19_000L), coins.get(WealthReconciliation.TRADE));
		assertEquals(Long.valueOf(-1_000L), coins.get(WealthReconciliation.GE_COLLECT_BOX));
		assertNull(coins.get(WealthReconciliation.UNEXPLAINED));
		assertEquals(Collections.singletonList("snapshot_slot_counter"), r2.get("trade_sources"));
		assertEquals(true, r2.get("conservation_holds"));

		// Collected: the slot empties and goods arrive in the inventory. No second trade.
		Snap d = new Snap(T0 + 30).inv(995, 1_000).inv(LOBSTER, 100).bank(T0 - 5, 995, 1);
		Map<String, Object> r3 = rec(c, d);
		assertNull(classes(r3, LOBSTER).get(WealthReconciliation.TRADE));
		// The 100 were already counted as trade in c; collecting them is an internal collect-box move (value 0).
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, 100L), classes(r3, LOBSTER));
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, 1_000L), classes(r3, 995));
		assertEquals(false, r3.get("core_complete"));
	}

	@Test
	public void c1TradeFactsReplaceCounterDerivationAndAreNotDoubleCounted()
	{
		Snap b = new Snap(T0 + 10).offer(0, LOBSTER, "BUYING", 100, 0, 200, 0).bank(T0 - 5, 995, 1);
		Snap c = new Snap(T0 + 20).offer(0, LOBSTER, "BUYING", 100, 30, 200, 5_700).bank(T0 - 5, 995, 1);
		List<WealthReconciliation.TradeFact> c1 = List.of(
			new WealthReconciliation.TradeFact(LOBSTER, 30, -5_700L, "c1_offer_lifecycle"));
		Map<String, Object> r = WealthReconciliation.reconcile(b.h(), c.h(), c1, null, null, null);
		assertEquals(Long.valueOf(30L), classes(r, LOBSTER).get(WealthReconciliation.TRADE));
		assertEquals(Long.valueOf(-5_700L), classes(r, 995).get(WealthReconciliation.TRADE));
		assertEquals(Collections.singletonList("c1_offer_lifecycle"), r.get("trade_sources"));
	}

	@Test
	public void c1DerivedRecordAdapterKeepsOnlyFillsInsideTheWindow()
	{
		Map<String, Object> fillIn = new LinkedHashMap<>();
		fillIn.put("qty", 30);
		fillIn.put("gp", 5_700);
		fillIn.put("interval_start_ms", T0 * 1000 + 11_000);
		fillIn.put("interval_end_ms", T0 * 1000 + 19_000);
		Map<String, Object> straddle = new LinkedHashMap<>(fillIn);
		straddle.put("interval_start_ms", T0 * 1000 + 5_000);
		Map<String, Object> offer = new LinkedHashMap<>();
		offer.put("offer_key", "k1");
		offer.put("account_hash", "123456789");
		offer.put("profile_type", "STANDARD");
		offer.put("item_id", LOBSTER);
		offer.put("side", "buy");
		offer.put("quarantined", false);
		offer.put("fills", List.of(fillIn, straddle));
		Map<String, Object> quarantined = new LinkedHashMap<>(offer);
		quarantined.put("offer_key", "k2");
		quarantined.put("quarantined", true);
		Map<String, Object> reduced = Map.of("offers", List.of(offer, quarantined));
		List<String> excluded = new ArrayList<>();
		List<WealthReconciliation.TradeFact> facts = WealthReconciliation.tradeFactsFromC1(reduced, "123456789",
			"STANDARD", T0 * 1000 + 10_000, T0 * 1000 + 20_000, excluded);
		assertEquals(1, facts.size());
		assertEquals(30L, facts.get(0).qtyDelta);
		assertEquals(Long.valueOf(-5_700L), facts.get(0).coinsDelta);
		assertEquals(List.of("k1:fill_straddles_window", "k2:quarantined"), excluded);
		// C1 lost-middle-row shape: total 100, timed fills 60, untimed 40. The 40 is listed, never placed in the window.
		offer.put("fills", List.of(fillIn));
		offer.put("untimed_fill_qty", 40);
		offer.put("first_seen_at_ms", T0 * 1000 + 1_000);
		offer.put("as_of_observed_ms", T0 * 1000 + 30_000);
		List<String> ex2 = new ArrayList<>();
		List<WealthReconciliation.TradeFact> f2 = WealthReconciliation.tradeFactsFromC1(Map.of("offers", List.of(offer)),
			"123456789", "STANDARD", T0 * 1000 + 10_000, T0 * 1000 + 20_000, ex2);
		assertEquals(1, f2.size());
		assertEquals(30L, f2.get(0).qtyDelta);
		assertEquals(List.of("k1:untimed_fill_qty=40"), ex2);
		assertTrue(WealthReconciliation.tradeFactsFromC1(reduced, "999", "STANDARD", 0, Long.MAX_VALUE / 2,
			new ArrayList<>()).isEmpty());
	}

	@Test
	public void c1CommittedExampleOutputFeedsTheLedger() throws Exception
	{
		// Pinned copy of C1 GeOfferLifecycle.reduce output at 4c38900 (sha256 7d49157f...). Buy 400 runes for 98,000,
		// then sell them in two fills for 39,150 + 65,250 (RuneLite gp counter, tax unknown).
		Map<String, Object> reduced = WealthLedgerReplay.load(Paths.get("src", "test", "resources", "wealth",
			"c1-example-output-4c38900.json").toString());
		List<String> excluded = new ArrayList<>();
		List<WealthReconciliation.TradeFact> all = WealthReconciliation.tradeFactsFromC1(reduced, "42", "STANDARD",
			1_000_000, 1_300_000, excluded);
		assertTrue(excluded.isEmpty());
		long items = 0, coins = 0;
		for (WealthReconciliation.TradeFact t : all)
		{
			assertEquals(560, t.itemId);
			items += t.qtyDelta;
			coins += t.coinsDelta;
		}
		assertEquals(0L, items);
		assertEquals(-98_000L + 39_150 + 65_250, coins);
		// A window ending mid-sell keeps only the fills inside it and lists the one that straddles.
		excluded.clear();
		List<WealthReconciliation.TradeFact> part = WealthReconciliation.tradeFactsFromC1(reduced, "42", "STANDARD",
			1_000_000, 1_200_000, excluded);
		assertEquals(2, part.size());
		assertEquals(1, excluded.size());
		assertTrue(excluded.get(0).endsWith(":fill_straddles_window"));
	}

	@Test
	public void sellOfferVanishedOutOfViewIsBoundedInferenceNotComplete()
	{
		Snap a = new Snap(T0).inv(995, 0).offer(3, WHIP, "SELLING", 1, 0, 1_500_000, 0).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 600).inv(995, 1_620_000).bank(T0 - 5, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(Map.of(WealthReconciliation.GE_SETTLEMENT_INFERRED, -1L), classes(r, WHIP));
		// Sold above the 1.5M ask: only the ask is justified by the offer terms; the 120k excess stays unexplained.
		Map<String, Long> coins = classes(r, 995);
		assertEquals(Long.valueOf(1_500_000L), coins.get(WealthReconciliation.GE_SETTLEMENT_INFERRED));
		assertEquals(Long.valueOf(120_000L), coins.get(WealthReconciliation.UNEXPLAINED));
		assertEquals(false, r.get("core_complete"));
	}

	@Test
	public void unexplainedLossStaysUnexplainedWithoutAC3Fact()
	{
		Snap a = new Snap(T0).inv(SHARK, 20).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(SHARK, 12).bank(T0 - 5, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(Map.of(WealthReconciliation.UNEXPLAINED, -8L), classes(r, SHARK));
		assertEquals(false, r.get("core_complete"));
	}

	@Test
	public void c3FlowsClassifyConsumptionRewardAndIgnoreDuplicates()
	{
		Snap a = new Snap(T0).inv(SHARK, 20).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(SHARK, 12).inv(BONES, 3).bank(T0 - 5, 995, 1);
		List<WealthReconciliation.Flow> flows = List.of(
			new WealthReconciliation.Flow("f1", SHARK, -8, "consumed_observed", "observed"),
			new WealthReconciliation.Flow("f2", BONES, 3, "output_reward", "observed"),
			new WealthReconciliation.Flow("f2", BONES, 3, "output_reward", "observed"));
		Map<Integer, Long> p = Map.of(SHARK, 900L, BONES, 2_000L);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, flows, p, p);
		assertEquals(Map.of(WealthReconciliation.CONSUMPTION_LOSS, -8L), classes(r, SHARK));
		assertEquals(Map.of(WealthReconciliation.REWARD, 3L), classes(r, BONES));
		assertEquals(1, r.get("duplicate_flows_ignored"));
		@SuppressWarnings("unchecked") Map<String, Long> v = (Map<String, Long>) r.get("value_by_class_gp_at_from_prices");
		assertEquals(Long.valueOf(-7_200L), v.get(WealthReconciliation.CONSUMPTION_LOSS));
		assertEquals(Long.valueOf(6_000L), v.get(WealthReconciliation.REWARD));
		assertEquals(true, r.get("explained"));
		// The bank was not resent between the two snapshots, so the account view is not complete.
		assertEquals(false, r.get("core_complete"));
	}

	@Test
	public void c3ClassifyOutputAdapterWindowsFlowsAndKeepsUnknownDeathLoss()
	{
		long t = T0 * 1000;
		Map<String, Object> eat = new LinkedHashMap<>();
		eat.put("flow_id", "e1:0");
		eat.put("item_id", SHARK);
		eat.put("qty_delta", -1L);
		eat.put("observed_at_ms", t + 5_000);
		eat.put("flow_class", "consumed_inferred");
		eat.put("provenance", "inferred");
		Map<String, Object> early = new LinkedHashMap<>(eat);
		early.put("flow_id", "e0:0");
		early.put("observed_at_ms", t - 1);
		Map<String, Object> death = new LinkedHashMap<>();
		death.put("flow_id", "d1:0");
		death.put("item_id", null);
		death.put("qty_delta", null);
		death.put("observed_at_ms", t + 9_000);
		death.put("flow_class", "lost_death");
		death.put("provenance", "unknown");
		List<String> excluded = new ArrayList<>();
		List<WealthReconciliation.Flow> flows = WealthReconciliation.flowsFromC3(List.of(eat, early, death), t, t + 60_000,
			excluded);
		assertEquals(2, flows.size());
		assertTrue(excluded.isEmpty());
		Snap a = new Snap(T0).inv(SHARK, 5).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(SHARK, 4).bank(T0 - 5, 995, 1);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, flows, null, null);
		assertEquals(Map.of(WealthReconciliation.CONSUMPTION_LOSS, -1L), classes(r, SHARK));
		assertEquals(List.of("d1:0:lost_death"), r.get("unknown_flows"));
		assertEquals(false, r.get("explained"));
	}

	@Test
	public void c3TransferredFlowDoesNotDoubleCountASeenBankDeposit()
	{
		Snap a = new Snap(T0).inv(LOBSTER, 28).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).bank(T0 + 55, 995, 1, LOBSTER, 20);
		List<WealthReconciliation.Flow> flows = List.of(
			new WealthReconciliation.Flow("t1", LOBSTER, -28, "transferred", "observed"));
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, flows, null, null);
		// 20 seen arriving in the bank are a transfer; the other 8 left to somewhere not read (trade, drop, ...).
		assertEquals(Long.valueOf(20L), classes(r, LOBSTER).get(WealthReconciliation.TRANSFER));
		assertEquals(Long.valueOf(-8L), classes(r, LOBSTER).get(WealthReconciliation.TRANSFER_OUT_OF_VIEW));
		assertNull(classes(r, LOBSTER).get(WealthReconciliation.UNEXPLAINED));
	}

	@Test
	public void priceMoveIsRevaluationNotEarnedGp()
	{
		Snap a = new Snap(T0).inv(WHIP, 2).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 3600).inv(WHIP, 2).bank(T0 - 5, 995, 1);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, null,
			Map.of(WHIP, 1_500_000L), Map.of(WHIP, 1_450_000L));
		assertEquals(-100_000L, r.get("revaluation_gp"));
		assertTrue(((List<?>) r.get("items")).isEmpty());
		@SuppressWarnings("unchecked") Map<String, Long> v = (Map<String, Long>) r.get("value_by_class_gp_at_from_prices");
		assertTrue(v.isEmpty());
	}

	@Test
	public void accountOrModeSwitchIsNeverDiffed()
	{
		Snap a = new Snap(T0).inv(995, 100);
		Snap other = new Snap(T0 + 10).inv(995, 9_999).account("987654321", "STANDARD");
		Snap league = new Snap(T0 + 10).inv(995, 9_999).account("123456789", "LEAGUE");
		assertEquals("not_comparable_account", rec(a, other).get("status"));
		assertEquals("not_comparable_account", rec(a, league).get("status"));
		Snap noHash = new Snap(T0 + 10).account("-1", "STANDARD");
		assertNull(noHash.h().accountKey);
		assertEquals("not_comparable_account", rec(noHash, noHash).get("status"));
	}

	@Test
	public void staleOrCarriedBankIsListedAndBlocksCompleteness()
	{
		Snap a = new Snap(T0).bank(T0 - 7200, 995, 1);
		WealthLedger.Holdings ha = a.h();
		assertEquals(WealthLedger.STALE, ha.locations.get("bank").state);
		Snap b = new Snap(T0 + 60).bank(T0 - 7200, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(Collections.singletonList("bank"), r.get("carried_forward_locations"));
		assertEquals(Collections.singletonList("bank"), r.get("stale_locations"));
		assertEquals(false, r.get("core_complete"));
	}

	@Test
	public void bankOpenedOnlyInLaterSnapshotIsNotComparableNotAGain()
	{
		Snap a = new Snap(T0).inv(995, 100);
		Snap b = new Snap(T0 + 60).inv(995, 100).bank(T0 + 50, 995, 50_000_000);
		Map<String, Object> r = rec(a, b);
		assertTrue(((Map<?, ?>) r.get("not_comparable_locations")).containsKey("bank"));
		assertTrue(classes(r, 995).isEmpty());
	}

	@Test
	public void platinumTokensFoldIntoCoins()
	{
		Snap a = new Snap(T0).inv(995, 5_000).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 10).inv(WealthLedger.PLATINUM_TOKEN, 5).bank(T0 - 5, 995, 1);
		assertEquals(5_001L, b.h().knownCoins());
		Map<String, Object> r = rec(a, b);
		assertTrue(classes(r, 995).isEmpty());
		assertTrue(classes(r, WealthLedger.PLATINUM_TOKEN).isEmpty());
	}

	@Test
	public void storageWithoutObservedAtIsUndatedAndBadRowsAreRejected()
	{
		Snap s = new Snap(T0).bank(T0 - 5, 995, 1);
		Map<String, Object> quiver = Snap.block(T0, RUNE_ARROW, 500);
		quiver.put("observed_at", null);
		s.m.put("quiver_ammo", quiver);
		assertEquals(WealthLedger.UNDATED, s.h().locations.get("quiver_ammo").state);
		assertNotEquals(WealthLedger.OBSERVED, s.h().locations.get("quiver_ammo").state);
		s.inv.add(item(-1, 5));
		try
		{
			s.h();
			fail("negative item id accepted");
		}
		catch (IllegalArgumentException expected)
		{
		}
	}

	@Test
	public void replayReadsUploadBodiesAfterJsonRoundTrip() throws Exception
	{
		// Gson turns every number into a double. The replay tool must give the same ledger as the in-memory maps.
		Snap a = new Snap(T0).inv(995, 300_000).inv(LOBSTER, 28).bank(T0 - 5, 995, 2_000_000)
			.offer(1, SHARK, "BUYING", 200, 50, 1_000, 46_000);
		Snap b = new Snap(T0 + 60).inv(995, 300_000).bank(T0 + 55, 995, 2_000_000, LOBSTER, 28)
			.offer(1, SHARK, "BUYING", 200, 80, 1_000, 74_000);
		Path dir = Files.createTempDirectory("wealth-replay");
		Path fa = dir.resolve("a.json"), fb = dir.resolve("b.json");
		Files.write(fa, new com.google.gson.Gson().toJson(Map.of("snapshot", a.m)).getBytes(StandardCharsets.UTF_8));
		Files.write(fb, new com.google.gson.Gson().toJson(b.m).getBytes(StandardCharsets.UTF_8));
		Map<String, Object> direct = rec(a, b);
		Map<String, Object> replay = WealthReconciliation.reconcile(
			WealthLedger.fromSnapshot(WealthLedgerReplay.load(fa.toString())),
			WealthLedger.fromSnapshot(WealthLedgerReplay.load(fb.toString())), null, null, null, null);
		assertEquals(direct.get("items"), replay.get("items"));
		assertEquals(Long.valueOf(30L), classes(replay, SHARK).get(WealthReconciliation.TRADE));
	}

	/**
	 * Shape of the real 0.7.39 run (retained166, 2026-10-19, djinn, five snapshots): bank absent, then present with no
	 * observed_at on first open, then dated after a deposit, then absent again after the bank closed. Synthetic values.
	 */
	@Test
	public void realRunShapeBankUndatedThenObservedThenCarried()
	{
		Snap s1 = new Snap(T0).inv(995, 735_517).inv(1511, 1).worn("weapon", 1353, 1);
		Snap s2 = new Snap(T0 + 29).inv(995, 735_517).inv(1511, 1).worn("weapon", 1353, 1).bank(T0 + 29, 1511, 23);
		@SuppressWarnings("unchecked") Map<String, Object> bank2 = (Map<String, Object>) s2.m.get("bank");
		bank2.put("observed_at", null);
		Snap s3 = new Snap(T0 + 125).inv(995, 735_512).worn("weapon", 1353, 1).bank(T0 + 81, 995, 5, 1511, 24);
		Snap s4 = new Snap(T0 + 250).inv(995, 735_513).inv(1353, 1);
		Snap s5 = new Snap(T0 + 372).inv(995, 735_513).worn("weapon", 1353, 1);
		WealthLedger.Holdings h1 = s1.h(), h2 = s2.h().carryForward(h1), h3 = s3.h().carryForward(h2);
		WealthLedger.Holdings h4 = s4.h().carryForward(h3), h5 = s5.h().carryForward(h4);
		assertEquals(WealthLedger.UNKNOWN, h1.locations.get("bank").state);
		assertEquals(WealthLedger.UNDATED, h2.locations.get("bank").state);
		assertEquals(WealthLedger.OBSERVED, h3.locations.get("bank").state);
		assertEquals(WealthLedger.CARRIED, h4.locations.get("bank").state);
		assertFalse(h4.coreComplete());
		// The withdrawn coin is in the inventory now; the carried bank still says 5. Never add them together.
		assertEquals(735_513L, h4.knownCoins());
		assertEquals(5L, h4.toMap().get("carried_coins_last_read"));
		assertEquals("yes", h4.owns(1511).get("answer"));	// last seen in the carried bank, state carried

		// Deposit seen in both locations: transfer only.
		Map<String, Object> r23 = WealthReconciliation.reconcile(h2, h3, null, null, null, null);
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 5L), classes(r23, 995));
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 1L), classes(r23, 1511));
		assertEquals(Collections.singletonList("bank"), r23.get("stale_locations"));	// undated at one end
		assertEquals(false, r23.get("core_complete"));

		// Withdraw 1 coin, then the bank closed: inferred transfer from the carried bank; the unequip is a transfer.
		Map<String, Object> r34 = WealthReconciliation.reconcile(h3, h4, null, null, null, null);
		assertEquals(Map.of(WealthReconciliation.TRANSFER_INFERRED_CARRIED, 1L), classes(r34, 995));
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 1L), classes(r34, 1353));
		assertEquals(false, r34.get("explained"));
		Map<String, Object> r45 = WealthReconciliation.reconcile(h4, h5, null, null, null, null);
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 1L), classes(r45, 1353));
		assertTrue(classes(r45, 995).isEmpty());

		// More coins out of a carried bank than its last reading held: the excess stays unexplained. The bound is the
		// last real reading (5), not reduced by earlier inferred withdrawals, so it is loose by design.
		Snap s6 = new Snap(T0 + 400).inv(995, 735_613).worn("weapon", 1353, 1);
		Map<String, Object> r56 = WealthReconciliation.reconcile(h5, s6.h().carryForward(h5), null, null, null, null);
		assertEquals(Long.valueOf(5L), classes(r56, 995).get(WealthReconciliation.TRANSFER_INFERRED_CARRIED));
		assertEquals(Long.valueOf(95L), classes(r56, 995).get(WealthReconciliation.UNEXPLAINED));
	}

	@Test
	public void conflictingCopiesOfOneFlowIdAreDroppedAndListedNotFirstWins()
	{
		Snap a = new Snap(T0).inv(SHARK, 20).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(SHARK, 12).bank(T0 - 5, 995, 1);
		List<WealthReconciliation.Flow> flows = List.of(
			new WealthReconciliation.Flow("e1:0", SHARK, -8, "consumed_inferred", "inferred"),
			new WealthReconciliation.Flow("e1:0", SHARK, -3, "consumed_inferred", "inferred"));
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, flows, null, null);
		assertEquals(List.of("e1:0"), r.get("conflicting_flow_ids"));
		assertEquals(Map.of(WealthReconciliation.UNEXPLAINED, -8L), classes(r, SHARK));
		assertEquals(false, r.get("explained"));
	}

	@Test
	public void carryForwardNeverCrossesAccounts()
	{
		WealthLedger.Holdings a = new Snap(T0).bank(T0 - 5, 995, 1_000).h();
		WealthLedger.Holdings b = new Snap(T0 + 60).account("987654321", "STANDARD").h().carryForward(a);
		assertEquals(WealthLedger.UNKNOWN, b.locations.get("bank").state);
	}

	// ---- Regression tests for adversarial review r1 (da11975). Probe names are the reviewer's. ----

	@Test
	public void h1TransferredFlowCannotHideALossWhenTheDepositIsSeen()
	{
		Snap a = new Snap(T0).inv(995, 13_000_000).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).bank(T0 + 55, 995, 10_000_001);
		List<WealthReconciliation.Flow> flows = List.of(
			new WealthReconciliation.Flow("bank1:0", 995, -10_000_000, "transferred", "observed", "bank_deposit"));
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, flows, null, null);
		assertEquals(Long.valueOf(10_000_000L), classes(r, 995).get(WealthReconciliation.TRANSFER));
		assertEquals(Long.valueOf(-3_000_000L), classes(r, 995).get(WealthReconciliation.UNEXPLAINED));
		assertNull(classes(r, 995).get(WealthReconciliation.TRANSFER_OUT_OF_VIEW));
		assertEquals(false, r.get("explained"));
		// Mirror (P19): a seen withdraw cannot hide an unrecorded gain.
		Snap c = new Snap(T0 + 120).inv(995, 13_000_000).bank(T0 + 115, 995, 1);
		Map<String, Object> r2 = WealthReconciliation.reconcile(b.h(), c.h(), null, List.of(
			new WealthReconciliation.Flow("bank2:0", 995, 10_000_000, "transferred", "observed", "bank_withdraw")), null, null);
		assertEquals(Long.valueOf(3_000_000L), classes(r2, 995).get(WealthReconciliation.UNEXPLAINED));
	}

	@Test
	public void h2CollectBoxIsInferredAndNeverCompleteAndTradeIncomeIsNotABoxMove()
	{
		// R1: the same open offer in three snapshots; lobsters keep appearing.
		Snap s1 = new Snap(T0).offer(0, LOBSTER, "BUYING", 10_000, 5_000, 200, 1_000_000).bank(T0 - 5, 995, 1);
		Snap s2 = new Snap(T0 + 60).inv(LOBSTER, 5_000).offer(0, LOBSTER, "BUYING", 10_000, 5_000, 200, 1_000_000)
			.bank(T0 + 55, 995, 1);
		Map<String, Object> r = rec(s1, s2);
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, 5_000L), classes(r, LOBSTER));
		assertEquals(false, r.get("explained"));
		assertEquals(false, r.get("core_complete"));
		// P20: a recorded trade receipt is applied before collect-box room.
		Snap s3 = new Snap(T0 + 120).inv(LOBSTER, 5_500).offer(0, LOBSTER, "BUYING", 10_000, 5_000, 200, 1_000_000)
			.bank(T0 + 115, 995, 1);
		Map<String, Object> r2 = WealthReconciliation.reconcile(s2.h(), s3.h(), null, List.of(
			new WealthReconciliation.Flow("trade1:0", LOBSTER, 500, "transferred", "observed", "trade_received")), null, null);
		assertEquals(Map.of(WealthReconciliation.TRANSFER_OUT_OF_VIEW, 500L), classes(r2, LOBSTER));
	}

	@Test
	public void h3CollectingAFinishedOfferIsNotASecondValuedSettlement()
	{
		Map<Integer, Long> p = Map.of(LOBSTER, 200L);
		Snap s1 = new Snap(T0).offer(0, LOBSTER, "BUYING", 100, 0, 200, 0).bank(T0 - 5, 995, 1);
		Snap s2 = new Snap(T0 + 60).offer(0, LOBSTER, "BOUGHT", 100, 100, 200, 19_000).bank(T0 + 55, 995, 1);
		Snap s3 = new Snap(T0 + 120).inv(LOBSTER, 100).inv(995, 1_000).bank(T0 + 115, 995, 1);
		Map<String, Object> r12 = WealthReconciliation.reconcile(s1.h(), s2.h(), null, null, p, p);
		Map<String, Object> r23 = WealthReconciliation.reconcile(s2.h(), s3.h(), null, null, p, p);
		@SuppressWarnings("unchecked") Map<String, Long> v12 = (Map<String, Long>) r12.get("value_by_class_gp_at_from_prices");
		@SuppressWarnings("unchecked") Map<String, Long> v23 = (Map<String, Long>) r23.get("value_by_class_gp_at_from_prices");
		long total = 0;
		for (Map<String, Long> v : List.of(v12, v23))
		{
			for (long x : v.values())
			{
				total += x;
			}
		}
		assertEquals(1_000L, total);	// 100 lobsters worth 20,000 bought for 19,000
		assertNull(classes(r23, LOBSTER).get(WealthReconciliation.GE_SETTLEMENT_INFERRED));
	}

	@Test
	public void h4NotedWithdrawIsATransferWithACanonicalMap()
	{
		java.util.function.IntUnaryOperator canon = id -> id == 380 ? LOBSTER : id;
		Snap a = new Snap(T0).bank(T0 - 5, LOBSTER, 100);
		Snap b = new Snap(T0 + 10).inv(380, 100).bank(T0 + 5, 995, 1);
		WealthLedger.Holdings ha = WealthLedger.fromSnapshot(a.m, WealthLedger.DEFAULT_STALE_AFTER_MS, null, canon);
		WealthLedger.Holdings hb = WealthLedger.fromSnapshot(b.m, WealthLedger.DEFAULT_STALE_AFTER_MS, null, canon);
		Map<String, Object> r = WealthReconciliation.reconcile(ha, hb, null, null, null, null);
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 100L), classes(r, LOBSTER));
		// Without the map the split stays visible as unexplained, never hidden.
		Map<String, Object> raw = rec(a, b);
		assertEquals(Map.of(WealthReconciliation.UNEXPLAINED, 100L), classes(raw, 380));
	}

	@Test
	public void m1CancelMovesLockedCoinsIntoTheCollectBox()
	{
		Snap a = new Snap(T0).offer(0, LOBSTER, "BUYING", 100, 40, 200, 7_600).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).offer(0, LOBSTER, "CANCELLED_BUY", 100, 40, 200, 7_600).bank(T0 + 55, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, -12_000L), classes(r, 995));
		Snap c = new Snap(T0 + 120).inv(LOBSTER, 40).inv(995, 12_400).bank(T0 + 115, 995, 1);
		Map<String, Object> r2 = rec(b, c);
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, 12_400L), classes(r2, 995));
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, 40L), classes(r2, LOBSTER));
		Snap sa = new Snap(T0).offer(1, SHARK, "SELLING", 100, 50, 900, 45_000).bank(T0 - 5, 995, 1);
		Snap sb = new Snap(T0 + 60).offer(1, SHARK, "CANCELLED_SELL", 100, 50, 900, 45_000).bank(T0 + 55, 995, 1);
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, -50L), classes(rec(sa, sb), SHARK));
	}

	@Test
	public void m2VanishedSellCoinsAreBoundedByTheAsk()
	{
		Snap a = new Snap(T0).offer(3, SHARK, "SELLING", 1, 0, 900, 0).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(995, 50_000_900).bank(T0 + 55, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(Long.valueOf(900L), classes(r, 995).get(WealthReconciliation.GE_SETTLEMENT_INFERRED));
		assertEquals(Long.valueOf(50_000_000L), classes(r, 995).get(WealthReconciliation.UNEXPLAINED));
	}

	@Test
	public void m3BankFlowIntoAnUnreadBankHasNoValue()
	{
		Snap a = new Snap(T0).inv(LOBSTER, 28);
		Snap b = new Snap(T0 + 60);
		Map<Integer, Long> p = Map.of(LOBSTER, 200L);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, List.of(
			new WealthReconciliation.Flow("bank1:0", LOBSTER, -28, "transferred", "observed", "bank_deposit")), p, p);
		assertEquals(Map.of(WealthReconciliation.TRANSFER_OWN_UNREAD, -28L), classes(r, LOBSTER));
		@SuppressWarnings("unchecked") Map<String, Long> v = (Map<String, Long>) r.get("value_by_class_gp_at_from_prices");
		assertEquals(Long.valueOf(0L), v.get(WealthReconciliation.TRANSFER_OWN_UNREAD));
	}

	@Test
	public void m4UnknownFlowClassKeepsConservation()
	{
		Snap a = new Snap(T0).inv(SHARK, 20).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(SHARK, 12).bank(T0 + 55, 995, 1);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, List.of(
			new WealthReconciliation.Flow("u1:0", SHARK, -5, "unknown", "unknown")), null, null);
		assertEquals(Map.of(WealthReconciliation.UNEXPLAINED, -8L), classes(r, SHARK));
		assertEquals(true, r.get("conservation_holds"));
	}

	@Test
	public void m5OfferFirstSeenWithFillsIsInferredNotProvenTrade()
	{
		Snap a = new Snap(T0).inv(995, 0).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 5).offer(0, LOBSTER, "BOUGHT", 100, 100, 200, 19_000).bank(T0 - 5, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(List.of("slot0:placement_not_observed"), r.get("inferred_trade_slots"));
		assertEquals(false, r.get("explained"));
	}

	@Test
	public void l1SameSecondFlowsAndFillsAreNotPlaced()
	{
		long from = T0 * 1000, to = (T0 + 60) * 1000;
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("flow_id", "x:0");
		f.put("item_id", SHARK);
		f.put("qty_delta", -1L);
		f.put("observed_at_ms", from + 800);
		f.put("flow_class", "consumed_inferred");
		f.put("provenance", "inferred");
		List<String> ex = new ArrayList<>();
		assertTrue(WealthReconciliation.flowsFromC3(List.of(f), from, to, ex).isEmpty());
		assertEquals(List.of("x:0:same_second_as_snapshot"), ex);
	}

	@Test
	public void l2BankReadingTakenBeforeFromIsCarriedNotFresh()
	{
		Snap a = new Snap(T0).bank(T0 - 30, 995, 1);
		Snap b = new Snap(T0 + 3_000).bank(T0 - 30, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(Collections.singletonList("bank"), r.get("carried_forward_locations"));
		assertEquals(false, r.get("core_complete"));
	}

	@Test
	public void l3BondsEscrowIsReadAndL5DuplicateSlotsAndL7OverflowAreRejected()
	{
		Snap s = new Snap(T0).storage("bonds_escrow", T0 - 1, 13190, 1).bank(T0 - 5, 995, 1);
		assertEquals(WealthLedger.OBSERVED, s.h().locations.get("bonds_escrow").state);
		Snap dup = new Snap(T0).offer(0, LOBSTER, "BUYING", 100, 0, 200, 0).offer(0, LOBSTER, "BUYING", 100, 0, 200, 0);
		try
		{
			dup.h();
			fail("duplicate slot accepted");
		}
		catch (IllegalArgumentException expected)
		{
		}
		Snap big = new Snap(T0);
		for (int i = 0; i < 8; i++)
		{
			big.offer(i, LOBSTER, "BUYING", Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0);
		}
		try
		{
			big.h().knownCoins();
			fail("overflow accepted");
		}
		catch (ArithmeticException expected)
		{
		}
	}

	@Test
	public void l4NullFlowFieldsAreRejected()
	{
		try
		{
			new WealthReconciliation.Flow(null, SHARK, -1, "consumed_inferred", "inferred");
			fail("null flow id accepted");
		}
		catch (NullPointerException expected)
		{
		}
	}

	// ---- Regression tests for adversarial review r2 (9598dd1). Probe names are the reviewer's. ----

	@Test
	public void r2p22BankFlowCannotHideALossWhenTheBankIsReadFresh()
	{
		Snap a = new Snap(T0).inv(LOBSTER, 28).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).bank(T0 + 55, 995, 1, LOBSTER, 20);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, List.of(
			new WealthReconciliation.Flow("bank1:0", LOBSTER, -28, "transferred", "observed", "bank_deposit")), null, null);
		assertEquals(Long.valueOf(20L), classes(r, LOBSTER).get(WealthReconciliation.TRANSFER));
		assertEquals(Long.valueOf(-8L), classes(r, LOBSTER).get(WealthReconciliation.UNEXPLAINED));
		assertNull(classes(r, LOBSTER).get(WealthReconciliation.TRANSFER_OWN_UNREAD));
		assertEquals(false, r.get("core_complete"));
	}

	@Test
	public void r2p11p20VanishedOpenBuyClaimsNoSettlementValue()
	{
		Map<Integer, Long> p = Map.of(LOBSTER, 200L, WHIP, 3_000_000L);
		Snap a = new Snap(T0).offer(0, LOBSTER, "BUYING", 100, 40, 200, 7_600).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(LOBSTER, 100).inv(995, 1_000).bank(T0 + 55, 995, 1);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, null, p, p);
		@SuppressWarnings("unchecked") Map<String, Long> v = (Map<String, Long>) r.get("value_by_class_gp_at_from_prices");
		assertNull(v.get(WealthReconciliation.GE_SETTLEMENT_INFERRED));
		assertTrue((Long) r.get("inferred_value_at_risk_gp") > 0);
		assertEquals(true, r.get("conservation_holds"));
		Snap c = new Snap(T0).offer(0, WHIP, "BUYING", 2, 1, 3_000_000, 1_500_000).bank(T0 - 5, 995, 1);
		Snap d = new Snap(T0 + 60).inv(WHIP, 2).inv(995, 3_000_000).bank(T0 + 55, 995, 1);
		Map<String, Object> r2 = WealthReconciliation.reconcile(c.h(), d.h(), null, null, p, p);
		@SuppressWarnings("unchecked") Map<String, Long> v2 = (Map<String, Long>) r2.get("value_by_class_gp_at_from_prices");
		long claimed = 0;
		for (long x : v2.values())
		{
			claimed += x;
		}
		assertEquals(0L, claimed);
	}

	@Test
	public void r2p3NoCoinSettlementWithoutSettledItems()
	{
		Snap a = new Snap(T0).inv(995, 5_000_000).offer(0, LOBSTER, "BUYING", 1_000, 0, 200, 0).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(995, 4_800_000).bank(T0 + 55, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(Map.of(WealthReconciliation.UNEXPLAINED, -400_000L), classes(r, 995));
	}

	@Test
	public void r2p2CanonicalMapAppliesToFlows()
	{
		java.util.function.IntUnaryOperator canon = id -> id == 380 ? LOBSTER : id;
		Snap a = new Snap(T0).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).inv(380, 50).bank(T0 + 55, 995, 1);
		WealthLedger.Holdings ha = WealthLedger.fromSnapshot(a.m, WealthLedger.DEFAULT_STALE_AFTER_MS, null, canon);
		WealthLedger.Holdings hb = WealthLedger.fromSnapshot(b.m, WealthLedger.DEFAULT_STALE_AFTER_MS, null, canon);
		Map<String, Object> r = WealthReconciliation.reconcile(ha, hb, null, List.of(
			new WealthReconciliation.Flow("loot1:0", 380, 50, "output_reward", "observed", "server_loot_NPC")), null, null);
		assertEquals(Map.of(WealthReconciliation.REWARD, 50L), classes(r, LOBSTER));
		assertTrue(classes(r, 380).isEmpty());
	}

	@Test
	public void r2p9CopiesDifferingOnlyInOriginConflict()
	{
		Snap a = new Snap(T0).inv(LOBSTER, 28);
		Snap b = new Snap(T0 + 60);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, List.of(
			new WealthReconciliation.Flow("x:0", LOBSTER, -28, "transferred", "observed", "bank_deposit"),
			new WealthReconciliation.Flow("x:0", LOBSTER, -28, "transferred", "observed", "trade_given")), null, null);
		assertEquals(List.of("x:0"), r.get("conflicting_flow_ids"));
		assertEquals(Map.of(WealthReconciliation.UNEXPLAINED, -28L), classes(r, LOBSTER));
	}

	@Test
	public void r2p14bp15NonBankFlowsApplyInFullBesideInViewMoves()
	{
		Snap a = new Snap(T0).inv(RUNE_ARROW, 1_500).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).worn("ammo", RUNE_ARROW, 500).bank(T0 + 55, 995, 1);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, List.of(
			new WealthReconciliation.Flow("d:0", RUNE_ARROW, -1_000, "transferred", "observed", "own_drop_spawn_confirmed")),
			null, null);
		assertEquals(Long.valueOf(500L), classes(r, RUNE_ARROW).get(WealthReconciliation.TRANSFER));
		assertEquals(Long.valueOf(-1_000L), classes(r, RUNE_ARROW).get(WealthReconciliation.TRANSFER_OUT_OF_VIEW));
		assertNull(classes(r, RUNE_ARROW).get(WealthReconciliation.UNEXPLAINED));
		// P15: a withdraw seen in view plus a trade receipt, with collect-box room for the same item.
		Snap c = new Snap(T0).offer(0, LOBSTER, "BUYING", 1_000, 500, 200, 100_000).bank(T0 - 5, LOBSTER, 100);
		Snap d = new Snap(T0 + 60).inv(LOBSTER, 600).offer(0, LOBSTER, "BUYING", 1_000, 500, 200, 100_000)
			.bank(T0 + 55, 995, 1);
		Map<String, Object> r2 = WealthReconciliation.reconcile(c.h(), d.h(), null, List.of(
			new WealthReconciliation.Flow("t:0", LOBSTER, 500, "transferred", "observed", "trade_received")), null, null);
		assertEquals(Long.valueOf(100L), classes(r2, LOBSTER).get(WealthReconciliation.TRANSFER));
		assertEquals(Long.valueOf(500L), classes(r2, LOBSTER).get(WealthReconciliation.TRANSFER_OUT_OF_VIEW));
		assertNull(classes(r2, LOBSTER).get(WealthReconciliation.GE_COLLECT_BOX));
	}

	@Test
	public void r2p13C1FactsDoNotFlagUnobservedPlacement()
	{
		Snap a = new Snap(T0).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 60).offer(0, LOBSTER, "BOUGHT", 10, 10, 200, 2_000).bank(T0 + 55, 995, 1);
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), List.of(
			new WealthReconciliation.TradeFact(LOBSTER, 10, -2_000L, "c1_offer_lifecycle")), null, null, null);
		assertEquals(Collections.emptyList(), r.get("inferred_trade_slots"));
	}

	@Test
	public void r2p10C3RowsWithoutIdOrClassAreExcluded()
	{
		long from = T0 * 1000, to = (T0 + 60) * 1000;
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("flow_id", null);
		f.put("item_id", SHARK);
		f.put("qty_delta", -1L);
		f.put("observed_at_ms", from + 5_000);
		f.put("flow_class", "consumed_inferred");
		List<String> ex = new ArrayList<>();
		assertTrue(WealthReconciliation.flowsFromC3(List.of(f, f), from, to, ex).isEmpty());
		assertEquals(2, ex.size());
	}

	/** Shape from the real 0.7.42 run (retained166, 2026-10-19): feather buy placed and cancelled between snapshots. */
	@Test
	public void real0742BuyPlacedAndCancelledInsideTheWindowIsACollectBoxMove()
	{
		Snap a = new Snap(T0).inv(995, 735_427).bank(T0 - 5, 995, 4);
		Snap b = new Snap(T0 + 67).inv(995, 731_427).offer(0, 314, "CANCELLED_BUY", 2_000, 0, 2, 0).bank(T0 - 5, 995, 4);
		Map<String, Object> r = rec(a, b);
		assertEquals(Map.of(WealthReconciliation.GE_COLLECT_BOX, -4_000L), classes(r, 995));
		// A new offer that filled inside the window still gets exactly its own box contents as room, not double.
		Snap c = new Snap(T0 + 67).offer(0, LOBSTER, "BOUGHT", 10, 10, 200, 1_900).bank(T0 - 5, 995, 4);
		WealthLedger.Escrow e = c.h().escrow.get(0);
		assertEquals(10L, e.maxUncollectedItems);
		assertEquals(100L, e.maxUncollectedCoins);
	}

	/** Shape from the real 0.7.43 run (retained166): withdraw, bank closes, bank reopens with a reading from before. */
	@Test
	public void real0743RefreshedCarriedBankIsNotCountedTwice()
	{
		Snap s3 = new Snap(T0).bank(T0 - 25, LOBSTER, 92, 841, 1);
		Snap s4 = new Snap(T0 + 121).inv(LOBSTER, 2).inv(841, 1);
		Snap s5 = new Snap(T0 + 124).inv(LOBSTER, 2).inv(841, 1).bank(T0 + 44, LOBSTER, 90);
		WealthLedger.Holdings h3 = s3.h(), h4 = s4.h().carryForward(h3), h5 = s5.h().carryForward(h4);
		List<WealthReconciliation.Flow> flows = List.of(
			new WealthReconciliation.Flow("b:0", 841, 1, "transferred", "observed", "bank_withdraw"),
			new WealthReconciliation.Flow("b:1", LOBSTER, 2, "transferred", "observed", "bank_withdraw"));
		Map<String, Object> r34 = WealthReconciliation.reconcile(h3, h4, null, flows, null, null);
		assertEquals(Map.of(WealthReconciliation.TRANSFER_OWN_UNREAD, 2L), classes(r34, LOBSTER));
		Map<String, Object> r45 = WealthReconciliation.reconcile(h4, h5, null, null, null, null);
		assertTrue(classes(r45, LOBSTER).isEmpty());
		assertTrue(classes(r45, 841).isEmpty());
		assertTrue(((Map<?, ?>) r45.get("not_comparable_locations")).containsKey("bank"));
		assertEquals(true, r45.get("explained"));
	}

	/** Shape from the real 0.7.46 run (retained166): rat loot drops to the ground; only some is picked up. */
	@Test
	public void real0746GroundLootIsRewardOnlyWhenItReachesTheInventory()
	{
		Snap a = new Snap(T0).inv(BONES, 1).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 120).inv(BONES, 2).bank(T0 - 5, 995, 1);
		List<WealthReconciliation.Flow> flows = List.of(
			new WealthReconciliation.Flow("l1:0", BONES, 1, "output_reward", "observed", "server_loot_npc"),
			new WealthReconciliation.Flow("l1:1", 2134, 1, "output_reward", "observed", "server_loot_npc"),
			new WealthReconciliation.Flow("l2:0", BONES, 1, "output_reward", "observed", "server_loot_npc"));
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), b.h(), null, flows, null, null);
		assertEquals(Map.of(WealthReconciliation.REWARD, 1L), classes(r, BONES));
		assertTrue(classes(r, 2134).isEmpty());	// left on the ground: not wealth, not unexplained
		// Loot that reached the inventory directly (not a server_loot origin) stays a plain reward fact.
		Map<String, Object> r2 = WealthReconciliation.reconcile(a.h(), b.h(), null, List.of(
			new WealthReconciliation.Flow("c:0", BONES, 1, "output_reward", "observed", null)), null, null);
		assertEquals(Map.of(WealthReconciliation.REWARD, 1L), classes(r2, BONES));
	}

	/** Writes the derived-result example for the handoff (build/wealth-ledger-example.json). */
	@Test
	public void derivedResultExample() throws Exception
	{
		// Session: bank open, deposit lobsters; place a shark buy that half-fills; eat 4 sharks; whip price moves.
		Snap a = new Snap(T0).inv(995, 300_000).inv(LOBSTER, 28).inv(SHARK, 10).worn("weapon", WHIP, 1)
			.bank(T0 - 5, 995, 2_000_000, LOBSTER, 500).storage("looting_bag", T0 - 9000, BONES, 12);
		Snap b = new Snap(T0 + 900).inv(995, 100_000).inv(SHARK, 6).worn("weapon", WHIP, 1)
			.offer(0, SHARK, "BUYING", 200, 100, 1_000, 92_000)
			.bank(T0 + 880, 995, 2_000_000, LOBSTER, 528).storage("looting_bag", T0 - 9000, BONES, 12);
		List<WealthReconciliation.Flow> flows = List.of(
			new WealthReconciliation.Flow("c3-eat-1", SHARK, -4, "consumed_observed", "observed"));
		Map<Integer, Long> p0 = Map.of(LOBSTER, 150L, SHARK, 950L, WHIP, 1_500_000L, BONES, 2_000L);
		Map<Integer, Long> p1 = Map.of(LOBSTER, 152L, SHARK, 920L, WHIP, 1_460_000L, BONES, 2_000L);
		WealthLedger.Holdings hb = b.h();
		Map<String, Object> r = WealthReconciliation.reconcile(a.h(), hb, null, flows, p0, p1);
		assertEquals(true, r.get("conservation_holds"));
		// Bounded escrow and an inferred collect-box move: correct classes, but not proven complete.
		assertEquals(false, r.get("core_complete"));
		assertEquals(false, r.get("explained"));
		assertEquals(Collections.singletonList("looting_bag"), r.get("stale_locations"));
		@SuppressWarnings("unchecked") Map<String, Long> v = (Map<String, Long>) r.get("value_by_class_gp_at_from_prices");
		assertEquals(Long.valueOf(0L), v.get(WealthReconciliation.GE_COLLECT_BOX));	// still owned, only out of view
		assertEquals(Long.valueOf(3_000L), v.get(WealthReconciliation.TRADE));		// 100 sharks worth 95,000 for 92,000
		assertEquals(-39_124L, r.get("revaluation_gp"));
		assertEquals(Map.of(WealthReconciliation.TRANSFER, 28L), classes(r, LOBSTER));
		assertEquals(Long.valueOf(-4L), classes(r, SHARK).get(WealthReconciliation.CONSUMPTION_LOSS));
		assertEquals(Long.valueOf(100L), classes(r, SHARK).get(WealthReconciliation.TRADE));
		Map<String, Object> example = new LinkedHashMap<>();
		example.put("holdings_to", hb.toMap());
		example.put("owns_shark", hb.owns(SHARK));
		example.put("ledger", r);
		String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(example);
		Path out = Paths.get("build", "wealth-ledger-example.json");
		Files.createDirectories(out.getParent());
		Files.write(out, json.getBytes(StandardCharsets.UTF_8));
	}
}
