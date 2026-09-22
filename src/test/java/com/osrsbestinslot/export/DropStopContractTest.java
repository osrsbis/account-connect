package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * THE STOP CONTRACT. Thirteen named cases, one arm each, driven through the plugin's own methods.
 *
 * THE CONTRACT THESE ARMS ENFORCE, stated once:
 *
 *   FIRST Drop action -> one continuous session
 *     -> every live pile AND every pending drop represented correctly
 *     -> no stop while ANY of those remain
 *     -> the final pile disappears, or the final pending drop definitively expires
 *     -> exactly TAIL_MILLIS more -> stop.
 *
 * WHY THIS FILE EXISTS. The 0.7.14 privacy review measured a recorder that could never stop in
 * three ordinary cases, while both user-facing strings promised it stopped five seconds after the
 * last pile went. The old suite was green throughout, because its longest arm asserted the recorder
 * KEEPS GOING — the opposite property — and nothing asserted an end at all.
 *
 * AND THE SECOND RULE. An outcome that cannot be PROVEN is never COMPLETE. Cases 2, 5, 6, 7, 8, 9,
 * 10 and 11 all end INTERRUPTED, each for a reason the manifest names in outcome_reason.
 */
public class DropStopContractTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final String OTHER_TOKEN = "fedcba9876543210fedcba9876543210";

	/** A config whose two user-controlled values can be changed mid-session, exactly as a user can. */
	private static final class MutableConfig implements AccountConnectConfig
	{
		volatile boolean upload = true;
		volatile String token = TOKEN;

		@Override
		public boolean enableUpload()
		{
			return upload;
		}

		@Override
		public String linkToken()
		{
			return token;
		}
	}

	private static final class Rig
	{
		final AccountConnectPlugin plugin = new AccountConnectPlugin();
		final MutableConfig config = new MutableConfig();
	}

	private static Rig rig() throws Exception
	{
		Rig r = new Rig();
		Field f = AccountConnectPlugin.class.getDeclaredField("config");
		f.setAccessible(true);
		f.set(r.plugin, r.config);
		r.plugin.setStoreToolsForTest(true);		// the shop-overlay grant
		r.plugin.setDropProofRolloutForTest(true);	// the SEPARATE drop-proof rollout flag
		return r;
	}

	private static void dropAction(AccountConnectPlugin p) throws Exception
	{
		Method m = AccountConnectPlugin.class.getDeclaredMethod("onDropActionForProof");
		m.setAccessible(true);
		m.invoke(p);
	}

	private static AccountConnectPlugin.DroppedGroundItem pile(int item, long qty, int x, int y)
	{
		Map<String, Object> loc = new java.util.LinkedHashMap<>();
		loc.put("region_id", 12853);
		loc.put("plane", 0);
		return new AccountConnectPlugin.DroppedGroundItem(item, qty, x, y, 0, loc, 100, 400);
	}

	private static void attach(AccountConnectPlugin p, AccountConnectPlugin.DroppedGroundItem g)
		throws Exception
	{
		Method m = AccountConnectPlugin.class.getDeclaredMethod(
			"attachPileToDropSession", AccountConnectPlugin.DroppedGroundItem.class);
		m.setAccessible(true);
		m.invoke(p, g);
	}

	private static void release(AccountConnectPlugin p, AccountConnectPlugin.DroppedGroundItem g)
		throws Exception
	{
		Method m = AccountConnectPlugin.class.getDeclaredMethod(
			"releasePileFromDropSession", AccountConnectPlugin.DroppedGroundItem.class);
		m.setAccessible(true);
		m.invoke(p, g);
	}

	private static Object field(AccountConnectPlugin p, String name) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(p);
	}

	private static Map<String, Object> manifest(AccountConnectPlugin p)
	{
		for (Map<String, Object> e : p.pendingEvents)
		{
			if ("drop_trade_clip".equals(e.get("type")))
			{
				return e;
			}
		}
		return null;
	}

	/** Make an armed tail due without sleeping five real seconds. */
	private static void forceTailDue(AccountConnectPlugin p) throws Exception
	{
		Field f = DropSessionRecorder.class.getDeclaredField("stopAtMillis");
		f.setAccessible(true);
		f.setLong(p.dropSession, 1L);
	}

	/**
	 * Move every pending drop's deadline into the past, so the next poll expires it.
	 *
	 * The alternative is sleeping PENDING_DROP_EXPIRY_MILLIS, which would put ten real seconds into
	 * the suite for every arm that needs one. The DEADLINE is what the production code reads, so
	 * rewriting it exercises the same branch a real ten-second wait would.
	 */
	@SuppressWarnings("unchecked")
	private static void forcePendingsExpired(AccountConnectPlugin p) throws Exception
	{
		Field f = DropSessionRecorder.class.getDeclaredField("pendingDrops");
		f.setAccessible(true);
		Map<Integer, Long> pendings = (Map<Integer, Long>) f.get(p.dropSession);
		for (Map.Entry<Integer, Long> e : pendings.entrySet())
		{
			e.setValue(1L);
		}
	}

	/** Drive the tick poll enough times that nothing can be waiting on a later tick. */
	private static void poll(AccountConnectPlugin p, int times)
	{
		for (int i = 0; i < times; i++)
		{
			p.pollDropSession();
		}
	}

	// ================= CASE 1 — a single ordinary drop =================

	@Test
	public void case01_singleOrdinaryDropStopsFiveSecondsAfterThePileGoes() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 1_000_000L, 3200, 3400);
		attach(p, g);
		assertEquals("the pile resolves the pending", 0, p.dropSession.pendingDropCount());
		assertEquals(1, p.dropSession.activePileCount());

		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		poll(p, 5);
		assertTrue("a live pile means no stop, however long we poll", p.dropSession.active());
		assertFalse(p.dropSession.stopPending());

		release(p, g);
		assertTrue("the last pile going arms the tail", p.dropSession.stopPending());
		poll(p, 1);
		assertTrue("and the tail has NOT elapsed yet", p.dropSession.active());

		forceTailDue(p);
		poll(p, 1);
		assertFalse("the tail elapsed, so the session is over", p.dropSession.active());
		Map<String, Object> m = manifest(p);
		assertNotNull(m);
		assertEquals("COMPLETE", m.get("outcome"));
		assertNull("a complete session names no reason", m.get("outcome_reason"));
	}

	// ============ CASE 2 — two stackable drops, ONE physical pile ============

	/**
	 * FINDING F1 PATH A, the case that could never stop.
	 *
	 * Dropping a stackable onto our own live pile of the same item on the same tile MERGES it in
	 * the game. The client fires one ItemQuantityChanged growth, and later exactly ONE
	 * ItemDespawned. The old code tracked a second pile and minted a second session key, which no
	 * despawn could ever release, so the tail never armed.
	 */
	@Test
	public void case02_twoStackableDropsOnOneTileAreOnePileAndOneDespawn() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals("the first drop makes one real pile", 1, p.dropSession.activePileCount());

		dropAction(p);
		// The SECOND drop arrives as a quantity growth on the same tile — a merge, not a spawn.
		p.trackGroundDrop(995, 1_000L, 3200, 3400, 0, null, 101, 400, true);

		assertEquals("the game has ONE pile, so the session must hold ONE key",
			1, p.dropSession.activePileCount());
		assertEquals("and the merged drop's pending is resolved, not left outstanding",
			0, p.dropSession.pendingDropCount());
		assertEquals("two drops are still counted", 2, p.dropSession.dropCount());

		// The one despawn the client will actually fire.
		AccountConnectPlugin.DroppedGroundItem tracked = onlyTrackedPile(p);
		release(p, tracked);
		assertTrue("the single despawn must arm the tail", p.dropSession.stopPending());

		forceTailDue(p);
		poll(p, 1);
		assertFalse("and the session must actually end", p.dropSession.active());
		assertEquals("every drop was observed on the merged pile, so this IS complete",
			"COMPLETE", manifest(p).get("outcome"));
	}

	@SuppressWarnings("unchecked")
	private static AccountConnectPlugin.DroppedGroundItem onlyTrackedPile(AccountConnectPlugin p)
		throws Exception
	{
		java.util.Deque<AccountConnectPlugin.DroppedGroundItem> q =
			(java.util.Deque<AccountConnectPlugin.DroppedGroundItem>) field(p, "groundDrops");
		assertEquals("exactly one tracked pile", 1, q.size());
		return q.peekFirst();
	}

	// ================= CASE 3 — rapid multi-drop =================

	@Test
	public void case03_rapidMultiDropIsOneSessionThatStopsOnlyAfterTheLastPile() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		AccountConnectPlugin.DroppedGroundItem[] piles = new AccountConnectPlugin.DroppedGroundItem[8];
		String sid = null;
		for (int i = 0; i < 8; i++)
		{
			dropAction(p);
			if (sid == null)
			{
				sid = p.dropSession.sessionId();
			}
			assertEquals("eight drops, ONE session", sid, p.dropSession.sessionId());
			// Eight DIFFERENT tiles, so these are eight genuinely separate piles.
			piles[i] = pile(4151, 1L, 3200 + i, 3400);
			attach(p, piles[i]);
		}
		assertEquals(8, p.dropSession.activePileCount());
		assertEquals(0, p.dropSession.pendingDropCount());

		for (int i = 0; i < 7; i++)
		{
			release(p, piles[i]);
			assertFalse("seven gone, one live — still no stop", p.dropSession.stopPending());
		}
		release(p, piles[7]);
		assertTrue("the eighth arms the tail", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertEquals("COMPLETE", manifest(p).get("outcome"));
		assertEquals(8, manifest(p).get("drops"));
	}

	// ========= CASE 4 — a new drop during the tail cancels the stop =========

	@Test
	public void case04_aDropInsideTheTailCancelsTheStopAndKeepsOneSession() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		String sid = p.dropSession.sessionId();
		AccountConnectPlugin.DroppedGroundItem a = pile(995, 5L, 3200, 3400);
		attach(p, a);
		release(p, a);
		assertTrue("the tail is armed", p.dropSession.stopPending());

		dropAction(p);
		assertFalse("a drop inside the tail cancels the stop", p.dropSession.stopPending());
		assertEquals("and never starts a second session", sid, p.dropSession.sessionId());
		assertEquals("the new drop is outstanding until its pile lands",
			1, p.dropSession.pendingDropCount());

		AccountConnectPlugin.DroppedGroundItem b = pile(4151, 1L, 3201, 3400);
		attach(p, b);
		poll(p, 3);
		assertTrue("still recording with a live pile", p.dropSession.active());
		release(p, b);
		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
		assertEquals("COMPLETE", manifest(p).get("outcome"));
		assertEquals("both drops belong to the one session", 2, manifest(p).get("drops"));
	}

	// ========= CASE 5 — a Drop action that never produces a pile =========

	/**
	 * FINDING F1 PATH B. Measured before the fix: activePiles 0, stopPending false, still recording
	 * after 1000 polls. Reachable from the destroy dialog answered No, a pile landing out of range,
	 * a foreign-ownership pile, or a region boundary between the click and the spawn.
	 */
	@Test
	public void case05_aDropThatNeverProducesAPileStillStops() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		assertTrue(p.dropSession.active());
		assertEquals("nothing on the ground", 0, p.dropSession.activePileCount());
		assertEquals("but the drop IS outstanding", 1, p.dropSession.pendingDropCount());

		poll(p, 50);
		assertTrue("before its deadline the drop still holds the session open", p.dropSession.active());
		assertFalse(p.dropSession.stopPending());

		forcePendingsExpired(p);
		poll(p, 1);
		assertEquals("the expired pending is gone", 0, p.dropSession.pendingDropCount());
		assertTrue("and the tail is armed", p.dropSession.stopPending());

		forceTailDue(p);
		poll(p, 1);
		assertFalse("the session ends without any despawn, hop or logout", p.dropSession.active());
		Map<String, Object> m = manifest(p);
		assertEquals("a drop nobody saw land is NOT provable coverage",
			"INTERRUPTED", m.get("outcome"));
		assertEquals(DropSessionRecorder.REASON_PENDING_EXPIRED, m.get("outcome_reason"));
	}

	// ============ CASE 6 — tracking-cap eviction ============

	/**
	 * FINDING F1 PATH C. trackGroundDrop evicts the oldest pile past GROUND_TRACK_MAX and nothing
	 * used to tell the session, so a 30-item drop trade stranded keys nobody could release.
	 * Measured before the fix: tracked 28, sessionPiles 31.
	 */
	@Test
	public void case06_evictionPastTheTrackingCapCannotLeaveAnImmortalRecorder() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		int over = AccountConnectPlugin.GROUND_TRACK_MAX + 3;
		for (int i = 0; i < over; i++)
		{
			dropAction(p);
			// A distinct tile each time, so every one of these is a genuine separate pile.
			p.trackGroundDrop(4151, 1L, 3200 + i, 3400, 0, null, 100 + i, 400, false);
		}
		assertEquals("the session never holds more keys than the client tracks piles",
			AccountConnectPlugin.GROUND_TRACK_MAX, p.dropSession.activePileCount());
		assertEquals("no drop is left outstanding either", 0, p.dropSession.pendingDropCount());
		assertTrue("an evicted pile makes the session unprovable", p.dropSession.unprovable());

		// Release every pile the client still tracks. That must be enough to stop the recorder.
		for (AccountConnectPlugin.DroppedGroundItem g : trackedPiles(p))
		{
			release(p, g);
		}
		assertTrue("with nothing outstanding the tail arms", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse("no immortal recorder", p.dropSession.active());
		Map<String, Object> m = manifest(p);
		assertEquals("three piles were never observed leaving, so coverage is not proven",
			"INTERRUPTED", m.get("outcome"));
		assertEquals(DropSessionRecorder.REASON_PILE_ABANDONED, m.get("outcome_reason"));
	}

	@SuppressWarnings("unchecked")
	private static java.util.List<AccountConnectPlugin.DroppedGroundItem> trackedPiles(
		AccountConnectPlugin p) throws Exception
	{
		java.util.Deque<AccountConnectPlugin.DroppedGroundItem> q =
			(java.util.Deque<AccountConnectPlugin.DroppedGroundItem>) field(p, "groundDrops");
		return new java.util.ArrayList<>(q);
	}

	// ================= CASES 7, 8, 9 — the external ends =================

	@Test
	public void case07_logoutEndsTheSessionInterrupted() throws Exception
	{
		assertExternalEndIsInterrupted();
	}

	@Test
	public void case08_worldHopEndsTheSessionInterrupted() throws Exception
	{
		assertExternalEndIsInterrupted();
	}

	@Test
	public void case09_disconnectOrSceneResetEndsTheSessionInterrupted() throws Exception
	{
		assertExternalEndIsInterrupted();
	}

	/**
	 * Logout, hop, disconnect and scene reload all reach interruptDropSession, which is the single
	 * method the four GameState arms call. Driving that method is therefore the same test three
	 * times over, and saying so is more honest than three copies pretending to differ.
	 *
	 * THE FOOTAGE IS KEPT HERE, deliberately. The user still consents; the session merely ended
	 * untidily, and that is exactly the session somebody needs to look at. Contrast case 10, where
	 * consent itself is withdrawn and the frames are destroyed.
	 */
	private void assertExternalEndIsInterrupted() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
		attach(p, g);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 7}, 1_000L);

		p.interruptDropSession();

		assertFalse(p.dropSession.active());
		Map<String, Object> m = manifest(p);
		assertNotNull("an interrupted session still publishes its manifest", m);
		assertEquals("INTERRUPTED", m.get("outcome"));
		assertEquals(DropSessionRecorder.REASON_EXTERNAL, m.get("outcome_reason"));
		assertEquals("the captured frame is NOT discarded", 1, m.get("frames"));
	}

	// ========= CASE 10 — Upload switched OFF mid-session =========

	/**
	 * FINDING F2. Before the fix: dropCapturing stayed true, frames kept being accepted, and the
	 * frames taken while the switch was off passed the upload gate as soon as it went back on.
	 */
	@Test
	public void case10_uploadOffMidSessionStopsCaptureImmediately() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
		attach(p, g);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		assertNotNull(field(p, "dropSegmenter"));

		r.config.upload = false;

		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 2}, 2_000L);
		poll(p, 1);

		assertFalse("the session is over", p.dropSession.active());
		assertFalse("capture is disarmed", (Boolean) field(p, "dropCapturing"));
		assertNull("and the buffer is gone", field(p, "dropSegmenter"));
		assertNull("no manifest either: publishing one is itself an upload about withdrawn consent",
			manifest(p));
	}

	// ========= CASE 11 — token cleared or changed mid-session =========

	@Test
	public void case11_tokenClearedOrChangedMidSessionStopsCapture() throws Exception
	{
		Rig cleared = rig();
		dropAction(cleared.plugin);
		attach(cleared.plugin, pile(995, 5L, 3200, 3400));
		cleared.config.token = "";
		cleared.plugin.pollDropSession();
		assertFalse("a cleared token stops it", cleared.plugin.dropSession.active());
		assertNull(field(cleared.plugin, "dropSegmenter"));

		Rig changed = rig();
		dropAction(changed.plugin);
		attach(changed.plugin, pile(995, 5L, 3200, 3400));
		changed.plugin.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 3}, 1_000L);
		changed.config.token = OTHER_TOKEN;
		changed.plugin.pollDropSession();
		// ROUND 3 CORRECTION. Round 2 pinned the opposite here, because the code did not stop on a
		// swap to another VALID token and the arm recorded that honestly. It is an identity
		// boundary, not accounting: frames captured under token A would upload under token B and
		// attribute one account's evidence to another. Cases 14 to 20 below own the full contract.
		assertFalse("a swap to a DIFFERENT valid token stops it too",
			changed.plugin.dropSession.active());
		assertNull(field(changed.plugin, "dropSegmenter"));

		Rig malformed = rig();
		dropAction(malformed.plugin);
		attach(malformed.plugin, pile(995, 5L, 3200, 3400));
		malformed.config.token = "not-a-token";
		malformed.plugin.pollDropSession();
		assertFalse("an INVALID token does stop it", malformed.plugin.dropSession.active());
		assertNull(field(malformed.plugin, "dropSegmenter"));
	}

	// ===== CASE 12 — OFF then ON must not upload the OFF-window frames =====

	/**
	 * FINDING F2, THE LEAK ITSELF. Measured before the fix with a known-bad / known-good control
	 * pair: switch stays OFF -> 0 reached the uploader; switch back ON -> the OFF-window frames did.
	 *
	 * The fix makes the leak unreachable by construction: no frame is ACCEPTED while the gate is
	 * closed, and the buffer holding the earlier frames is destroyed at the moment it closes. So
	 * there is nothing left for a re-enable to release.
	 */
	@Test
	public void case12_uploadOffThenOnNeverUploadsTheFramesTakenWhileOff() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		attach(p, pile(995, 5L, 3200, 3400));
		for (int i = 0; i < 10; i++)
		{
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i);
		}
		DropFrameSegmenter before = (DropFrameSegmenter) field(p, "dropSegmenter");
		assertEquals("ten frames were captured while consent held", 10, before.acceptedFrames());

		r.config.upload = false;
		poll(p, 1);
		assertNull("the buffer is destroyed at the moment consent is withdrawn",
			field(p, "dropSegmenter"));
		assertEquals("and the frames it held are gone", 0, before.bufferedFrames());

		// Frames offered while OFF are refused outright.
		for (int i = 0; i < 10; i++)
		{
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) (100 + i)}, 3_000L + i);
		}
		assertNull("no buffer is resurrected by an offered frame", field(p, "dropSegmenter"));

		// The switch goes back on an hour later.
		r.config.upload = true;
		poll(p, 3);
		assertNull("turning the switch back on must not resurrect anything",
			field(p, "dropSegmenter"));
		assertFalse("and it must not restart the old session", p.dropSession.active());
		assertNull("nothing from the withdrawn session is published", manifest(p));

		// A NEW drop after re-enabling records normally, and carries none of the old frames.
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 42}, 9_000L);
		DropFrameSegmenter after = (DropFrameSegmenter) field(p, "dropSegmenter");
		assertNotNull("a fresh session records again", after);
		assertEquals("starting from zero, not from the withdrawn session's frames",
			1, after.acceptedFrames());
	}

	// ===== CASE 13 — no state may record with nothing outstanding =====

	/**
	 * THE INVARIANT, checked as an invariant rather than as one scenario.
	 *
	 * Every state reachable from a drop action is driven forward here, and at every step the rule
	 * is the same: zero live piles AND zero pending drops means a stop is armed. A session that
	 * holds neither and has no stop armed is the immortal recorder, whatever the path that reached
	 * it.
	 */
	@Test
	public void case13_zeroPilesAndZeroPendingsAlwaysMeansAStopIsArmed() throws Exception
	{
		// Path A: pile released.
		assertNeverRecordsWithNothingOutstanding(p ->
		{
			AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
			attach(p, g);
			release(p, g);
		});
		// Path B: pending expired, no pile ever.
		assertNeverRecordsWithNothingOutstanding(p ->
		{
			forcePendingsExpired(p);
			p.pollDropSession();
		});
		// Path C: pile abandoned by eviction.
		assertNeverRecordsWithNothingOutstanding(p ->
		{
			AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
			attach(p, g);
			Method m = AccountConnectPlugin.class.getDeclaredMethod(
				"abandonPileFromDropSession", AccountConnectPlugin.DroppedGroundItem.class);
			m.setAccessible(true);
			m.invoke(p, g);
		});
		// Path D: a merged second drop, then the one despawn.
		assertNeverRecordsWithNothingOutstanding(p ->
		{
			p.trackGroundDrop(995, 5L, 3200, 3400, 0, null, 100, 400, false);
			dropAction(p);
			p.trackGroundDrop(995, 10L, 3200, 3400, 0, null, 101, 400, true);
			release(p, onlyTrackedPile(p));
		});
	}

	private interface Step
	{
		void run(AccountConnectPlugin p) throws Exception;
	}

	private void assertNeverRecordsWithNothingOutstanding(Step step) throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		step.run(p);
		if (!p.dropSession.active())
		{
			return;		// already stopped is the strongest form of the property
		}
		boolean nothingOutstanding =
			p.dropSession.activePileCount() == 0 && p.dropSession.pendingDropCount() == 0;
		if (nothingOutstanding)
		{
			assertTrue("nothing outstanding, so a stop MUST be armed", p.dropSession.stopPending());
			forceTailDue(p);
			poll(p, 1);
			assertFalse("and the poll must actually end it", p.dropSession.active());
		}
		else
		{
			assertTrue("something is outstanding, so recording legitimately continues",
				p.dropSession.activePileCount() > 0 || p.dropSession.pendingDropCount() > 0);
		}
	}

	// ================================================================
	// CASES 14-20 — THE TOKEN IDENTITY BOUNDARY
	//
	// THE DEFECT ROUND 2 PINNED AND DID NOT FIX. uploadAllowed() asked whether the configured token
	// was WELL-FORMED, never whether it was the SAME token. So a mid-session swap from token A to a
	// different valid token B left every gate open, and frames captured under A were uploaded with B
	// in the multipart body. That files one account's evidence against another account.
	//
	// THE CONTRACT THESE SEVEN ARMS ENFORCE:
	//   any change of the configured token during a session INTERRUPTS it immediately
	//     -> pre-roll, buffered frames, outstanding retry media and pending session state all die
	//     -> nothing captured under A may upload under B
	//     -> NO drop_trade_clip manifest is published for a session whose identity was withdrawn
	//     -> a new or returning token may only start a NEW FUTURE session.
	//
	// Case 20 is the control arm. A fix that simply broke all recording would pass 14 to 19.
	// ================================================================

	/**
	 * Fill a session with frames and force one FULL segment out, so bytes sit in the RETRY BUFFER
	 * and not only in the segment being filled.
	 *
	 * This is what makes case 18 a real leak test rather than a flag test. A segment handed to the
	 * uploader is accounted in the segmenter's outstanding map, and clear() does not touch that map.
	 */
	private static DropFrameSegmenter fillOneWholeSegment(AccountConnectPlugin p) throws Exception
	{
		for (int i = 0; i <= DropFrameSegmenter.SEGMENT_FRAMES; i++)
		{
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i, 7, 7, 7}, 1_000L + i);
		}
		DropFrameSegmenter seg = (DropFrameSegmenter) field(p, "dropSegmenter");
		assertNotNull(seg);
		assertTrue("a whole segment must have been handed to the uploader", seg.segmentCount() >= 1);
		return seg;
	}

	// ===== CASE 14 — A -> B, a swap to a DIFFERENT VALID token =====

	@Test
	public void case14_swapToADifferentValidTokenInterruptsTheSession() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		attach(p, pile(995, 5L, 3200, 3400));
		// Baseline-cadence frames are HELD as pre-roll rather than kept, which is the thing that
		// has to die: a pre-roll survivor would be flushed into the next burst's clip.
		for (int i = 0; i < 12; i++)
		{
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i * 5L);
		}
		DropCaptureRate rateUnderA = (DropCaptureRate) field(p, "dropRate");
		assertNotNull(rateUnderA);
		assertNotNull("a session is recording under token A", field(p, "dropSegmenter"));
		assertTrue(p.dropSession.active());

		r.config.token = OTHER_TOKEN;		// still 32 hex characters, still perfectly valid
		p.pollDropSession();

		assertFalse("the session ends the moment the identity changes", p.dropSession.active());
		assertFalse("capture is disarmed", (Boolean) field(p, "dropCapturing"));
		assertNull("the frame buffer is destroyed", field(p, "dropSegmenter"));
		assertNull("the pre-roll controller is destroyed", field(p, "dropRate"));
		assertEquals("and the frames it was holding are gone, not merely unreferenced",
			0, rateUnderA.preRollSize());
		assertNull("and no pending session state survives", field(p, "pendingDropSessionId"));
	}

	// ===== CASE 15 — A -> empty =====

	@Test
	public void case15_clearingTheTokenInterruptsTheSessionAndClearsTheBuffers() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		attach(p, pile(995, 5L, 3200, 3400));
		DropFrameSegmenter seg = fillOneWholeSegment(p);

		r.config.token = "";
		p.pollDropSession();

		assertFalse(p.dropSession.active());
		assertNull(field(p, "dropSegmenter"));
		assertNull(field(p, "dropRate"));
		assertEquals("the filling segment is emptied", 0, seg.bufferedFrames());
		assertTrue("and the retry buffer is discarded, not merely emptied", seg.discarded());
		assertEquals("nothing is held at all", 0L, seg.heldBytes());
		assertEquals("no segment is still outstanding", 0, seg.unsettledSegments());
	}

	// ===== CASE 16 — A -> malformed =====

	@Test
	public void case16_aMalformedTokenInterruptsTheSession() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		attach(p, pile(995, 5L, 3200, 3400));
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);

		r.config.token = "zzzz-not-a-token";
		p.pollDropSession();

		assertFalse(p.dropSession.active());
		assertNull(field(p, "dropSegmenter"));
		assertNull(field(p, "dropRate"));
	}

	// ===== CASE 17 — A -> B -> A, the returning token =====

	/**
	 * THE RESURRECTION ARM. Token A comes back. It must NOT revive the session it left, its id, its
	 * frames or its manifest. A returning token is simply a token that may start something new.
	 */
	@Test
	public void case17_aReturningTokenDoesNotResurrectTheOldSessionOrItsBuffers() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		String firstSessionId = p.dropSession.sessionId();
		assertNotNull(firstSessionId);
		attach(p, pile(995, 5L, 3200, 3400));
		DropFrameSegmenter seg = fillOneWholeSegment(p);
		int framesUnderA = seg.acceptedFrames();
		assertTrue(framesUnderA > 0);

		r.config.token = OTHER_TOKEN;
		p.pollDropSession();
		assertFalse(p.dropSession.active());

		// A comes back.
		r.config.token = TOKEN;
		poll(p, 5);

		assertFalse("the old session must not restart", p.dropSession.active());
		assertNull("no buffer is resurrected", field(p, "dropSegmenter"));
		assertNull("no pre-roll is resurrected", field(p, "dropRate"));
		assertNull("and no manifest appears for the abandoned session", manifest(p));
		assertTrue("the old segmenter stays discarded forever", seg.discarded());
		assertNull("it yields nothing even when asked directly", seg.flushRemainder());
		seg.add(new byte[]{(byte) 0xff, (byte) 0xd8, 9}, 9_000L);
		assertEquals("and it accepts nothing more, so its frame count cannot grow",
			framesUnderA, seg.acceptedFrames());

		// A genuinely new drop is allowed, and it is a DIFFERENT session.
		dropAction(p);
		assertTrue("token A may start a NEW session", p.dropSession.active());
		assertFalse("with a new id, never the old one",
			firstSessionId.equals(p.dropSession.sessionId()));
	}

	// ===== CASE 18 — frames buffered before the swap reach NEITHER upload =====

	/**
	 * THE LEAK ARM, and a flag is not enough here.
	 *
	 * Bytes captured under token A live in two places: the segment being filled, and the RETRY
	 * BUFFER of segments already handed to the uploader. Round 2's discard path called clear(),
	 * which empties only the first. A segment in the second would still have been retried, and a
	 * retry reads the CURRENT token, so those bytes would have uploaded under token B.
	 *
	 * This arm proves the bytes are unreachable by three independent routes, not that a flag flipped:
	 *   1. the segmenter yields nothing when asked for a segment directly;
	 *   2. it accepts nothing more, so no new segment can form from it;
	 *   3. the plugin's own upload entry point, driven with the old segment and the NEW token,
	 *      sends nothing and strands the bytes.
	 */
	@Test
	public void case18_framesBufferedBeforeTheSwapReachNeitherUpload() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		attach(p, pile(995, 5L, 3200, 3400));
		DropFrameSegmenter seg = fillOneWholeSegment(p);

		// PUT BYTES IN THE RETRY BUFFER. A segment is handed out and left UNSETTLED, which is
		// exactly the state of a segment whose POST is in flight when the token changes. Those
		// bytes are the ones clear() would have left accounted and retriable.
		for (int i = 0; i < DropFrameSegmenter.SEGMENT_FRAMES; i++)
		{
			seg.add(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i, 3, 3, 3}, 5_000L + i);
		}
		assertEquals("one segment is in flight and unacknowledged", 1, seg.unsettledSegments());
		long heldUnderA = seg.heldBytes();
		assertTrue("so real bytes are held for a retry", heldUnderA > 0L);
		int framesUnderA = seg.acceptedFrames();
		java.util.concurrent.atomic.AtomicInteger sent =
			(java.util.concurrent.atomic.AtomicInteger) field(p, "dropSegmentsSent");
		assertEquals("nothing reached the server under token A either", 0, sent.get());

		r.config.token = OTHER_TOKEN;
		p.pollDropSession();

		// Route 1: nothing can be taken out of it, and the retry buffer is gone.
		assertTrue(seg.discarded());
		assertNull("no tail segment can be produced", seg.flushRemainder());
		assertEquals("nothing is held", 0L, seg.heldBytes());
		assertEquals("the in-flight segment's bytes are discarded", 0, seg.unsettledSegments());
		assertEquals("no frames remain buffered", 0, seg.bufferedFrames());

		// Route 2: nothing more can go in, so no segment can re-form.
		for (int i = 0; i < DropFrameSegmenter.SEGMENT_FRAMES * 2; i++)
		{
			seg.add(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i, 7, 7, 7}, 20_000L + i);
		}
		assertEquals("the discarded segmenter accepts nothing", framesUnderA, seg.acceptedFrames());
		assertEquals("so it can never hand out another segment", 2, seg.segmentCount());
		assertNull(seg.flushRemainder());

		// Route 3: THE RETRY ITSELF. A segment captured under token A, retried after the swap, must
		// not reach the network. Driven straight at postDropSegment, because that is the only code
		// an in-flight retry runs. The rig has no OkHttp client, so REACHING the network step throws
		// a NullPointerException — which is what makes this a discriminating probe rather than a
		// flag check. A mismatched token must return quietly; a matching one must reach the call.
		DropFrameSegmenter.Segment old = takeOneSegment();
		assertFalse("the guard must stop the old-token retry before any network work",
			postDropSegmentReachedTheNetwork(p, TOKEN, old));
		assertTrue("and the same call with the CURRENT token does reach it, so the probe is live",
			postDropSegmentReachedTheNetwork(p, OTHER_TOKEN, old));

		assertEquals("nothing was ever reported as sent", 0, sent.get());

		// And frames offered to the PLUGIN after the swap are refused before any buffer exists.
		for (int i = 0; i < 10; i++)
		{
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) (200 + i)}, 30_000L + i);
		}
		assertNull("no buffer is created by a post-swap frame", field(p, "dropSegmenter"));
	}

	/** One real Segment, made by a standalone segmenter so no plugin state is disturbed. */
	private static DropFrameSegmenter.Segment takeOneSegment()
	{
		DropFrameSegmenter solo = new DropFrameSegmenter(1, DropFrameSegmenter.MAX_FRAME_BYTES,
			DropFrameSegmenter.UNACKED_BYTE_BUDGET);
		DropFrameSegmenter.Segment out = solo.add(new byte[]{(byte) 0xff, (byte) 0xd8, 4, 4}, 1_000L);
		assertNotNull(out);
		return out;
	}

	/**
	 * Run one upload attempt and report whether it got as far as the network.
	 *
	 * The rig injects no OkHttp client, so an attempt that reaches the call throws a
	 * NullPointerException. That is the signal: true means the attempt was NOT stopped.
	 */
	private static boolean postDropSegmentReachedTheNetwork(AccountConnectPlugin p, String token,
		DropFrameSegmenter.Segment segment) throws Exception
	{
		Method m = AccountConnectPlugin.class.getDeclaredMethod("postDropSegment", String.class,
			String.class, DropFrameSegmenter.Segment.class, String.class, int.class);
		m.setAccessible(true);
		try
		{
			m.invoke(p, "https://example.invalid", token, segment, "sid", 0);
			return false;
		}
		catch (java.lang.reflect.InvocationTargetException e)
		{
			if (e.getCause() instanceof NullPointerException)
			{
				return true;
			}
			throw e;
		}
	}

	// ===== CASE 19 — no OLD manifest is emitted after the swap =====

	/**
	 * A manifest is itself an upload ABOUT the recording. Publishing one for a session whose
	 * identity was withdrawn would file the old account's session id, drop count and start time
	 * under whoever is linked now. Cases 10 to 12 set this precedent for a withdrawn grant; a swap
	 * is the same event with a different cause.
	 */
	@Test
	public void case19_noManifestIsPublishedForASessionWhoseTokenWasSwapped() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		String swappedAwayId = p.dropSession.sessionId();
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
		attach(p, g);
		fillOneWholeSegment(p);

		r.config.token = OTHER_TOKEN;
		p.pollDropSession();
		assertNull("no manifest at the moment of the swap", manifest(p));

		// NOTHING AFTERWARDS MAY PUBLISH IT EITHER, and the session is driven all the way to the
		// state that WOULD publish one: the last pile gone, every pending expired, and the tail due.
		// A session that was merely paused rather than ended emits its old manifest right here.
		release(p, g);
		forcePendingsExpired(p);
		forceTailDue(p);
		poll(p, 5);
		assertNull("and none appears even when the tail is driven to due", manifest(p));

		// Not even after the account the frames belonged to comes back.
		r.config.token = TOKEN;
		poll(p, 5);
		assertNull("nor when the original token returns", manifest(p));

		for (Map<String, Object> e : p.pendingEvents)
		{
			assertFalse("no event may carry the swapped-away session id",
				swappedAwayId.equals(e.get("drop_session_id")));
		}
	}

	// ===== CASE 20 — THE CONTROL ARM: a fresh post-swap session works =====

	/**
	 * A fix that simply stopped all recording would pass cases 14 to 19. This arm fails it.
	 *
	 * After a swap to token B, a brand-new drop under B must record normally: a new session id,
	 * a live buffer, frames accepted, a stop when the pile goes, and a COMPLETE manifest naming the
	 * NEW session.
	 */
	@Test
	public void case20_aFreshSessionAfterTheSwapRecordsNormally() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		String oldId = p.dropSession.sessionId();
		attach(p, pile(995, 5L, 3200, 3400));
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);

		r.config.token = OTHER_TOKEN;
		p.pollDropSession();
		assertFalse(p.dropSession.active());

		// A NEW drop, under the NEW token.
		dropAction(p);
		assertTrue("recording still works after the swap", p.dropSession.active());
		String newId = p.dropSession.sessionId();
		assertNotNull(newId);
		assertFalse("and it is a different session", newId.equals(oldId));

		AccountConnectPlugin.DroppedGroundItem g2 = pile(995, 7L, 3201, 3400);
		attach(p, g2);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 2}, 2_000L);
		DropFrameSegmenter fresh = (DropFrameSegmenter) field(p, "dropSegmenter");
		assertNotNull("a fresh buffer exists", fresh);
		assertFalse("and it is not a discarded one", fresh.discarded());
		assertEquals("carrying only the frames taken under the new token", 1, fresh.acceptedFrames());

		release(p, g2);
		assertTrue(p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse("the fresh session ends normally", p.dropSession.active());

		Map<String, Object> m = manifest(p);
		assertNotNull("and it DOES publish its own manifest", m);
		assertEquals("named for the new session, never the old one", newId, m.get("drop_session_id"));
		assertEquals("COMPLETE", m.get("outcome"));
	}

	// ============ the rollout flag is not an authorization ============

	/**
	 * FINDING F4, THE SEPARATION ITSELF. Rollout decides WHICH granted clients record. Whether a
	 * drop_frames upload is KEPT is the server's staff check on the token, which no client flag can
	 * reach. This arm pins what the client can and cannot do; the server half is not testable here
	 * and the report says so.
	 */
	@Test
	public void theRolloutFlagIsRolloutOnlyAndFailsClosed() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		p.setDropProofRolloutForTest(false);
		assertFalse("absent or false disables capture", p.dropProofEnabled());
		dropAction(p);
		assertFalse("and no session starts", p.dropSession.active());

		p.setStoreToolsForTest(false);
		p.setDropProofRolloutForTest(true);
		assertFalse("the rollout flag alone grants nothing without the store-tools grant",
			p.dropProofEnabled());

		p.setStoreToolsForTest(true);
		r.config.upload = false;
		assertFalse("and it can never override the user's own switch", p.dropProofEnabled());
	}

	/** A brand new plugin has never heard from the server, and must not record. */
	@Test
	public void anUnknownServerStateFailsCaptureOff() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		Field f = AccountConnectPlugin.class.getDeclaredField("config");
		f.setAccessible(true);
		MutableConfig c = new MutableConfig();
		f.set(p, c);
		p.setStoreToolsForTest(true);		// even with the shop grant already in hand
		assertFalse("no policy response yet means no recording", p.dropProofEnabled());
	}
}
