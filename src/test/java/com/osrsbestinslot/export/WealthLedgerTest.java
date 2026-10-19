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
		assertEquals(Long.valueOf(100L), classes(r3, LOBSTER).get(WealthReconciliation.GE_SETTLEMENT_INFERRED));
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
		assertTrue(WealthReconciliation.tradeFactsFromC1(reduced, "999", "STANDARD", 0, Long.MAX_VALUE / 2,
			new ArrayList<>()).isEmpty());
	}

	@Test
	public void sellOfferVanishedOutOfViewIsBoundedInferenceNotComplete()
	{
		Snap a = new Snap(T0).inv(995, 0).offer(3, WHIP, "SELLING", 1, 0, 1_500_000, 0).bank(T0 - 5, 995, 1);
		Snap b = new Snap(T0 + 600).inv(995, 1_620_000).bank(T0 - 5, 995, 1);
		Map<String, Object> r = rec(a, b);
		assertEquals(Map.of(WealthReconciliation.GE_SETTLEMENT_INFERRED, -1L), classes(r, WHIP));
		assertEquals(Map.of(WealthReconciliation.GE_SETTLEMENT_INFERRED, 1_620_000L), classes(r, 995));
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
	public void storageWithoutObservedAtIsUnknownAndBadRowsAreRejected()
	{
		Snap s = new Snap(T0).bank(T0 - 5, 995, 1);
		Map<String, Object> quiver = Snap.block(T0, RUNE_ARROW, 500);
		quiver.put("observed_at", null);
		s.m.put("quiver_ammo", quiver);
		assertEquals(WealthLedger.UNKNOWN, s.h().locations.get("quiver_ammo").state);
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
		assertEquals(true, r.get("core_complete"));
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
