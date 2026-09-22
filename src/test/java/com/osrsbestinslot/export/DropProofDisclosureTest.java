package com.osrsbestinslot.export;

import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The drop-proof disclosure, and the ONE number inside it that can rot.
 *
 * WHY THIS EXISTS. 0.7.14 adds continuous screen recording of a drop trade. The Plugin Hub's own
 * {@code warning=} manifest line is FROZEN — editing it produced three escalating strikes and a
 * closed PR — so per-feature disclosure lives here, on the config items, which is also the
 * maintainer's stated preference. That makes these two strings the only place a user is told their
 * screen is recorded, and nothing else in the build checks that they say so.
 *
 * THE STALE-NUMBER TRAP. The disclosure promises the recording stops "five seconds after the final
 * dropped pile disappears". That five is {@link DropSessionRecorder#TAIL_MILLIS}. A future change to
 * the tail would leave the user-facing promise quietly wrong — a disclosure that has drifted from
 * the behaviour is worse than a vague one, because it reads as precise. So the RELATIONSHIP is
 * asserted, not the literal: change TAIL_MILLIS and this goes red naming the sentence to fix.
 *
 * This same class of defect has been caught before by asserting a relationship rather than a
 * repeated constant. It is not hypothetical here: the capture rate already moved once, from 8fps to
 * 6fps, after a live run truncated its own tail.
 */
public class DropProofDisclosureTest
{
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
			d.contains("Only osrsbestinslot.com can enable it"));
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
	 * NO SECOND CONSENT TOGGLE. Disclosure is not a feature switch: the server grants drop proof, so
	 * a checkbox would imply a control the user does not have, and the standing rule is no new
	 * plugin settings without an explicit request. Three items is what this config has.
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
