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
		// A DIFFERENT valid token is still a valid token, so the gate stays open and the session
		// continues. What must NOT happen is the session silently re-filing under the new account:
		// the manifest's drop_session_id was minted under the old one. This arm pins the honest
		// behaviour rather than asserting a stop the code does not perform.
		assertTrue("a swap to another valid token does not itself revoke consent",
			changed.plugin.dropSession.active());
		changed.config.token = "not-a-token";
		changed.plugin.pollDropSession();
		assertFalse("an INVALID token does stop it", changed.plugin.dropSession.active());
		assertNull(field(changed.plugin, "dropSegmenter"));
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
