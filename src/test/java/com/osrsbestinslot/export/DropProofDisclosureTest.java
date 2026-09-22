package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
