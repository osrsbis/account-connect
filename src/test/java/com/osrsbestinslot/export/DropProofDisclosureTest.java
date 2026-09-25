package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The drop-proof disclosure, and every claim inside it that can rot.
 *
 * WHY THIS EXISTS. 0.7.14 adds continuous screen recording of a drop trade. The Plugin Hub's own
 * {@code warning=} manifest line is FROZEN — editing it produced three escalating strikes and a
 * closed PR — so per-feature disclosure lives here, on the config items, which is also the
 * maintainer's stated preference. That makes these two strings the only place a user is told their
 * screen is recorded, and nothing else in the build checks that they say so.
 *
 * WHAT THE FIRST VERSION OF THIS TEST MISSED, and the reason it is now much longer. The privacy
 * review found it guarded ONE number out of five claims. Two of the unguarded claims were FALSE
 * while this suite was green:
 *
 *   - "Only osrsbestinslot.com can enable it" was false, because X-Store-Tools ALONE turned on
 *     recording and no drop-proof grant existed. Now {@link #onlyTheServerCanEnableDropProof()}
 *     drives the real gate and that sentence is tied to behaviour.
 *   - Nothing asserted that the recording ENDS. It could not end in three ordinary cases. Now
 *     {@link #theRecordingAlwaysEnds()} drives the recorder to a stop with no pile ever removed.
 *
 * THE STALE-NUMBER TRAP. The disclosure promises the recording stops "five seconds after the final
 * dropped pile disappears". That five is {@link DropSessionRecorder#TAIL_MILLIS}. A future change
 * to the tail would leave the user-facing promise quietly wrong — a disclosure that has drifted
 * from the behaviour is worse than a vague one, because it reads as precise. So the RELATIONSHIP
 * is asserted, not the literal: change TAIL_MILLIS and this goes red naming the sentence to fix.
 */
public class DropProofDisclosureTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private static String warningOf(String method) throws Exception
	{
		Method m = AccountConnectConfig.class.getMethod(method);
		return m.getAnnotation(ConfigItem.class).warning();
	}

	private static String descriptionOf(String method) throws Exception
	{
		Method m = AccountConnectConfig.class.getMethod(method);
		return m.getAnnotation(ConfigItem.class).description();
	}

	/** Both user-facing strings. Every claim below must hold in BOTH, not in whichever is easier. */
	private static String[] bothStrings() throws Exception
	{
		return new String[]{warningOf("enableUpload"), descriptionOf("linkToken")};
	}

	private static AccountConnectPlugin granted() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		Field f = AccountConnectPlugin.class.getDeclaredField("config");
		f.setAccessible(true);
		f.set(p, new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public boolean enableUpload()
			{
				return true;
			}
		});
		return p;
	}

	@Test
	public void theUploadWarningDisclosesDropTradeRecording() throws Exception
	{
		String w = warningOf("enableUpload");
		assertTrue("the warning must say a RECORDING is captured, not only screenshots",
			w.contains("recording of your rendered game screen"));
		assertTrue("it must name the trigger the user performs",
			w.contains("when you choose Drop"));
		assertTrue("it must name what else can appear in frame",
			w.contains("visible chat messages") && w.contains("other players' names"));
	}

	@Test
	public void theLinkTokenDescriptionDisclosesDropTradeRecording() throws Exception
	{
		String d = descriptionOf("linkToken");
		assertTrue("the fuller description must carry the recording too",
			d.contains("recording of your rendered game screen"));
		assertTrue("it must say the user cannot switch it on themselves",
			d.contains("You cannot turn drop-trade proof on yourself"));
	}

	/**
	 * FINDING F5. The two strings must say the SAME things. The weaker one was the
	 * {@code enableUpload} warning, which is the string RuneLite shows in a blocking dialog at the
	 * moment of consent — so the duration claim was in the string the user can avoid reading and
	 * missing from the one they cannot.
	 */
	@Test
	public void bothStringsCarryEveryDropProofClaim() throws Exception
	{
		String[] claims = {
			"recording of your rendered game screen",
			"when you choose Drop",
			"five seconds after the final dropped pile disappears",
			"The recording always ends.",
			"can run for several minutes",
			"You cannot turn drop-trade proof on yourself",
			"visible chat messages",
			"other players' names",
		};
		for (String s : bothStrings())
		{
			for (String claim : claims)
			{
				assertTrue("both disclosure strings must carry: " + claim, s.contains(claim));
			}
		}
	}

	/**
	 * ROUND 4, FINDING D. `counterparty_inferred` names a specific other player as the likely taker
	 * of an item you dropped, and no disclosure sentence described it.
	 *
	 * THE INCREMENTAL NAME EXPOSURE IS ZERO. The same name is already in `taken_by_candidates` on
	 * the same row, which is pre-existing and sits under Lukas's standing 2026-07-16 decision. This
	 * arm does not re-open that decision and does not change what is sent. What was missing was the
	 * CLAIM: the plugin now asserts, in a field whose name reads as an answer, that a named third
	 * party took your item, and the user was never told.
	 *
	 * The gate is worth stating plainly, because it is wider than drop proof: emitGroundRemoval is
	 * gated on activityLogActive(), which is a valid link token. So this fires for every ordinary
	 * linked user with the upload switch on, not only for a staff member holding the drop-proof
	 * grant. The sentence therefore sits in the general upload paragraph, not in the drop-proof one.
	 */
	@Test
	public void bothStringsDiscloseTheInferredTaker() throws Exception
	{
		String[] claims = {
			"uploads the names of players standing near that item",
			"records that name as the likely taker",
		};
		for (String s : bothStrings())
		{
			for (String claim : claims)
			{
				assertTrue("both disclosure strings must carry: " + claim, s.contains(claim));
			}
		}
	}

	/**
	 * AND THE SENTENCE MUST STAY TRUE OF THE CODE. It promises the name is recorded only where
	 * EXACTLY ONE player stands on the pile, so the resolver is driven to prove that.
	 *
	 * Without this the disclosure could outlive the rule it describes: a future change that
	 * resolved on two candidates, or on a player merely nearby, would leave the string quietly
	 * overstating our own restraint in the safe-sounding direction.
	 */
	@Test
	public void theInferredTakerRuleMatchesTheSentence() throws Exception
	{
		assertEquals("exactly one player ON the tile is named",
			"Alice", DropCandidates.resolveCounterparty(candidates(cand("Alice", 0))));
		assertNull("two on the tile is ambiguous and names nobody",
			DropCandidates.resolveCounterparty(candidates(cand("Alice", 0), cand("Bob", 0))));
		assertNull("a player merely NEARBY is never named as the taker",
			DropCandidates.resolveCounterparty(candidates(cand("Alice", 1))));
		assertNull("nor is the nearest of several nearby players",
			DropCandidates.resolveCounterparty(candidates(cand("Alice", 1), cand("Bob", 2))));
		assertNull("an empty tile names nobody",
			DropCandidates.resolveCounterparty(candidates()));
	}

	private static java.util.Map<String, Object> cand(String rsn, int dist)
	{
		java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("rsn", rsn);
		m.put("dist", dist);
		return m;
	}

	@SafeVarargs
	private static java.util.List<java.util.Map<String, Object>> candidates(
		java.util.Map<String, Object>... cs)
	{
		return new java.util.ArrayList<>(java.util.Arrays.asList(cs));
	}

	/**
	 * THE CONTROL THAT MATTERS. Both strings promise a tail in WORDS; the recorder implements it as
	 * a constant. If they disagree the disclosure is a false statement about our own software.
	 */
	@Test
	public void theDisclosedTailMatchesTheRecorder() throws Exception
	{
		long seconds = DropSessionRecorder.TAIL_MILLIS / 1000L;
		assertEquals("this test only knows how to spell small numbers; update it with the tail",
			5L, seconds);
		String phrase = "five seconds after the final dropped pile disappears";
		assertTrue("the enableUpload warning must state the real tail", warningOf("enableUpload").contains(phrase));
		assertTrue("the linkToken description must state the real tail", descriptionOf("linkToken").contains(phrase));
	}

	/**
	 * FINDING F1, AS A DISCLOSURE CONTROL. "The recording always ends." is a claim about behaviour,
	 * so it is checked against behaviour: a session whose drop NEVER produces a pile must still
	 * reach a stop, with no removal, no interrupt and no logout.
	 *
	 * Before the fix this arm could not pass. pileRemoved was the only thing that armed a stop, so
	 * a drop with no pile recorded until the player logged out.
	 */
	@Test
	public void theRecordingAlwaysEnds() throws Exception
	{
		for (String s : bothStrings())
		{
			assertTrue("the strings must claim the recording ends", s.contains("The recording always ends."));
		}
		DropSessionRecorder r = new DropSessionRecorder();
		long t0 = 1_000_000L;
		r.onDropAction("s", t0);
		// No pile ever spawns. Poll far past the pending expiry and then past the tail.
		r.settle(t0 + DropSessionRecorder.PENDING_DROP_EXPIRY_MILLIS);
		assertTrue("a pending that expired must arm the tail",
			r.stopPending());
		assertTrue("and the tail must actually elapse",
			r.shouldStop(t0 + DropSessionRecorder.PENDING_DROP_EXPIRY_MILLIS
				+ DropSessionRecorder.TAIL_MILLIS));
		assertEquals("a drop nobody saw land can never be called COMPLETE",
			DropSessionRecorder.Outcome.INTERRUPTED, r.finish());
	}

	/**
	 * FINDING F4, AS A DISCLOSURE CONTROL. "You cannot turn drop-trade proof on yourself: only
	 * osrsbestinslot.com can" is a claim about the gate, so it is checked against the gate.
	 *
	 * THIS ARM WAS RED BEFORE THE FIX. X-Store-Tools alone reached dropProofEnabled() == true.
	 */
	@Test
	public void onlyTheServerCanEnableDropProof() throws Exception
	{
		AccountConnectPlugin p = granted();
		assertFalse("nothing granted: no recording", p.dropProofEnabled());

		p.setStoreToolsForTest(true);
		assertFalse("the shop-overlay grant alone must NOT enable screen recording",
			p.dropProofEnabled());

		p.setDropProofRolloutForTest(true);
		assertTrue("the drop-proof rollout flag is what enables it", p.dropProofEnabled());

		p.setDropProofRolloutForTest(false);
		assertFalse("and withdrawing it disables it again", p.dropProofEnabled());
	}

	/**
	 * "Turning this switch off stops all of it" is a claim about the upload switch, so the switch
	 * is checked. A user with both server grants and the switch OFF must reach no recording at all.
	 */
	@Test
	public void theUploadSwitchOffStopsIt() throws Exception
	{
		for (String s : bothStrings())
		{
			assertTrue("both strings must promise the frames are discarded, not held",
				s.contains("discarded rather than uploaded later"));
		}
		AccountConnectPlugin p = new AccountConnectPlugin();
		Field f = AccountConnectPlugin.class.getDeclaredField("config");
		f.setAccessible(true);
		f.set(p, new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return TOKEN;
			}

			@Override
			public boolean enableUpload()
			{
				return false;
			}
		});
		p.setStoreToolsForTest(true);
		p.setDropProofRolloutForTest(true);
		assertFalse("the user's own switch beats every server grant", p.dropProofEnabled());
	}

	/**
	 * NO SECOND CONSENT TOGGLE. Disclosure is not a feature switch: the server grants drop proof, so
	 * a checkbox would imply a control the user does not have, and the standing rule is no new
	 * plugin settings without an explicit request. Three items is what this config has.
	 *
	 * The one-shot chat notice added for finding F3 is deliberately NOT a config item, and this arm
	 * is what stops a future change from turning it into one.
	 */
	// ================================================================
	// ROUND 5, FINDING D2 — THE NOTICE DEBT MUST NOT SURVIVE A REVOCATION INTO A DELIVERY
	//
	// THE DEFECT. onDropProofCapabilityChanged owes the notice when dropProofEnabled() is true, and
	// when there is no chat box yet the debt is HELD and re-delivered on the next LOGGED_IN. That
	// holding is correct. What was missing is a re-check of the capability at DELIVERY time, and
	// deliverDropProofDisclosure returned early only on the debt, a null client, an already-queued
	// send and a non-LOGGED_IN game state — never on the capability.
	//
	// TWO CONSEQUENCES, and the second is the one that matters. The line says "drop-trade screen
	// recording is now active for this linked account", which after a revocation is false. And
	// sendDropProofDisclosure PERSISTS DROP_PROOF_NOTICE_VERSION as soon as the line lands, so
	// noteDropProofDisclosureOwed then refuses to owe it again: the user is shown the notice at a
	// moment when nothing is recording, and when recording genuinely begins later they are shown
	// nothing. The notice and the capability drift apart by exactly one revocation.
	//
	// THE FIX is one guard at the top of deliverDropProofDisclosure. The debt stays OWED, so a
	// grant that comes back still shows the notice.
	// ================================================================

	/** A plugin with a real chat box, a real config store, and every drop-proof grant in hand. */
	private static AccountConnectPlugin withChatBox(java.util.List<String> lines,
		java.util.Map<String, String> store) throws Exception
	{
		AccountConnectPlugin p = granted();
		p.setStoreToolsForTest(true);
		p.setDropProofRolloutForTest(true);

		net.runelite.api.Client client = org.mockito.Mockito.mock(net.runelite.api.Client.class);
		org.mockito.Mockito.when(client.getGameState())
			.thenReturn(net.runelite.api.GameState.LOGGED_IN);
		org.mockito.Mockito.when(client.isClientThread()).thenReturn(true);
		org.mockito.Mockito.doAnswer(inv ->
		{
			lines.add((String) inv.getArguments()[2]);
			return null;
		}).when(client).addChatMessage(org.mockito.Mockito.any(), org.mockito.Mockito.anyString(),
			org.mockito.Mockito.anyString(), org.mockito.Mockito.any());
		inject(p, "client", client);

		net.runelite.client.config.ConfigManager cm =
			org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		org.mockito.Mockito.when(cm.getConfiguration(org.mockito.Mockito.anyString(),
			org.mockito.Mockito.anyString()))
			.thenAnswer(inv -> store.get((String) inv.getArguments()[1]));
		org.mockito.Mockito.doAnswer(inv ->
		{
			store.put((String) inv.getArguments()[1], String.valueOf(inv.getArguments()[2]));
			return null;
		}).when(cm).setConfiguration(org.mockito.Mockito.anyString(),
			org.mockito.Mockito.anyString(), org.mockito.Mockito.any());
		inject(p, "configManager", cm);
		return p;
	}

	private static void inject(AccountConnectPlugin p, String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, value);
	}

	private static void setGrant(AccountConnectPlugin p, boolean on)
	{
		p.setDropProofRolloutForTest(on);
	}

	/**
	 * FINDING D2, THE OUTCOME ARM. The grant is owed while there is no chat box, revoked, and then
	 * restored. The notice must be shown EXACTLY ONCE, and AFTER the restore.
	 *
	 * The discriminating moment is the delivery attempt made while the grant is gone. Before the
	 * fix that attempt sent the line and persisted the version, so the restore showed nothing.
	 */
	@Test
	public void aRevokedGrantNeverDeliversTheRecordingIsActiveNotice() throws Exception
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		java.util.Map<String, String> store = new java.util.LinkedHashMap<>();
		AccountConnectPlugin p = withChatBox(lines, store);

		// The grant lands with no chat box, so the notice is owed and held.
		inject(p, "client", null);
		p.onDropProofCapabilityChanged();
		assertTrue("the notice is owed", p.dropProofDisclosureOwed);

		// The chat box arrives, but the grant has been revoked in the meantime.
		AccountConnectPlugin p2 = withChatBox(lines, store);
		p2.dropProofDisclosureOwed = true;
		setGrant(p2, false);
		assertFalse("the grant really is gone", p2.dropProofEnabled());

		p2.deliverDropProofDisclosure();

		assertTrue("FINDING D2: a revoked grant must deliver NOTHING", lines.isEmpty());
		assertTrue("and the debt must stay owed", p2.dropProofDisclosureOwed);
		assertNull("and no notice version may be persisted",
			store.get(AccountConnectPlugin.DROP_PROOF_NOTICE_KEY));

		// The grant comes back. NOW the user is told, once, and only now.
		setGrant(p2, true);
		p2.deliverDropProofDisclosure();

		assertEquals("CONTROL: the restored grant delivers the notice exactly once",
			1, lines.size());
		assertTrue("and it is the drop-proof notice",
			lines.get(0).contains("drop-trade screen recording is now active"));
		assertFalse("the debt is settled", p2.dropProofDisclosureOwed);
		assertEquals("and the version is persisted only now",
			Integer.toString(AccountConnectPlugin.DROP_PROOF_NOTICE_VERSION),
			store.get(AccountConnectPlugin.DROP_PROOF_NOTICE_KEY));

		// And it is never shown a second time.
		p2.noteDropProofDisclosureOwed();
		p2.deliverDropProofDisclosure();
		assertEquals("a delivered notice is never repeated", 1, lines.size());
	}

	@Test
	public void disclosureDidNotSmuggleInANewSetting()
	{
		int items = 0;
		for (Method m : AccountConnectConfig.class.getDeclaredMethods())
		{
			if (m.getAnnotation(ConfigItem.class) != null)
			{
				items++;
			}
		}
		assertEquals("enableUpload, linkToken, apiBaseUrl — and no disclosure-only toggle", 3, items);
	}
}
