package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
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
	private static final String OTHER_TOKEN = "fedcba9876543210".repeat(2);

	/** A config whose two user-controlled values can be changed mid-session, exactly as a user can. */
	private static final class MutableConfig implements AccountConnectConfig
	{
		/** false models the user clearing the link token, the one user-side stop. */
		volatile boolean upload = true;
		volatile String token = TOKEN;

		@Override
		public String linkToken()
		{
			return upload ? token : "";
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

	/**
	 * ROUND 4. These three used to call interruptDropSession() DIRECTLY, so the wiring from a real
	 * GameState to the recorder was never exercised: deleting the interruptDropSession() call from
	 * any one of the four GameState arms, or adding a fifth state without one, passed all three
	 * green. They now post a real GameStateChanged into the real @Subscribe handler, which is the
	 * only entry point a running client ever uses.
	 *
	 * THE FOOTAGE IS KEPT HERE, deliberately. The user still consents; the session merely ended
	 * untidily, and that is exactly the session somebody needs to look at. Contrast case 10, where
	 * consent itself is withdrawn and the frames are destroyed.
	 */
	@Test
	public void case07_logoutEndsTheSessionInterrupted() throws Exception
	{
		assertGameStateEndIsInterrupted(GameState.LOGIN_SCREEN);
	}

	@Test
	public void case08_worldHopEndsTheSessionInterrupted() throws Exception
	{
		assertGameStateEndIsInterrupted(GameState.HOPPING);
	}

	@Test
	public void case09_disconnectOrSceneResetEndsTheSessionInterrupted() throws Exception
	{
		assertGameStateEndIsInterrupted(GameState.CONNECTION_LOST);
		assertGameStateEndIsInterrupted(GameState.LOADING);
		assertGameStateEndIsInterrupted(GameState.LOGGING_IN);
	}

	private static GameStateChanged gameState(GameState st)
	{
		GameStateChanged ev = new GameStateChanged();
		ev.setGameState(st);
		return ev;
	}

	private void assertGameStateEndIsInterrupted(GameState state) throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		// A REAL tracked pile, not a bare attach: the ground-tracking teardown assertion below is
		// meaningless against a session whose pile was never in groundDrops.
		p.trackGroundDrop(995, 5L, 3200, 3400, 0, null, 100, 400, false);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 7}, 1_000L);
		assertEquals("the pile is tracked before the end", 1, p.groundDropCount());

		p.onGameStateChanged(gameState(state));

		assertFalse(state + " must end the session", p.dropSession.active());
		assertEquals("ROUND 4: and it must untrack the session's pile, or a later merged re-drop "
			+ "onto that tile mints a second entry for one physical pile (finding B)",
			0, p.groundDropCount());
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
		p.trackGroundDrop(995, 5L, 3200, 3400, 0, null, 100, 400, false);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		assertNotNull(field(p, "dropSegmenter"));
		assertEquals("the pile is tracked before the withdrawal", 1, p.groundDropCount());

		r.config.upload = false;

		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 2}, 2_000L);
		poll(p, 1);

		assertFalse("the session is over", p.dropSession.active());
		assertFalse("capture is disarmed", (Boolean) field(p, "dropCapturing"));
		assertNull("and the buffer is gone", field(p, "dropSegmenter"));
		assertEquals("ROUND 4, FINDING B: the ground tracking is reset too. Without this the pile "
			+ "stays tracked and unowned, and the next merged re-drop onto that tile makes a "
			+ "second entry for one physical pile",
			0, p.groundDropCount());
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
		p.trackGroundDrop(995, 5L, 3200, 3400, 0, null, 100, 400, false);
		for (int i = 0; i < 10; i++)
		{
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i);
		}
		DropFrameSegmenter before = (DropFrameSegmenter) field(p, "dropSegmenter");
		assertEquals("ten frames were captured while consent held", 10, before.acceptedFrames());
		assertEquals("the pile is tracked before the withdrawal", 1, p.groundDropCount());

		r.config.upload = false;
		poll(p, 1);
		assertNull("the buffer is destroyed at the moment consent is withdrawn",
			field(p, "dropSegmenter"));
		assertEquals("and the frames it held are gone", 0, before.bufferedFrames());
		assertEquals("ROUND 4, FINDING B: the ground tracking is reset too",
			0, p.groundDropCount());

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
		p.trackGroundDrop(995, 5L, 3200, 3400, 0, null, 100, 400, false);
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
		assertEquals("ROUND 4, FINDING B: the ground tracking is reset on the identity path too",
			0, p.groundDropCount());
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

	// ================================================================
	// CASES 21-23 — ROUND 4, FINDING B: A WITHDRAWAL MUST NOT STRAND A PILE
	//
	// THE DEFECT. discardDropSessionOnWithdrawnConsent cleared dropPileSeq and dropPileSession and
	// left the pile in groundDrops. Every GameState path already untracked its piles; the
	// withdrawal path was the single exception, and it is the only end path a player can enter and
	// LEAVE with the pile still physically on the ground.
	//
	// WHAT THAT COSTS. The next session re-drops the same stackable onto the same tile. The game
	// MERGES the two into one pile and fires exactly ONE ItemDespawned. liveSessionPileAt refuses
	// the stale entry, because dropPileSession no longer names it, so trackGroundDrop mints a
	// SECOND DroppedGroundItem for ONE physical pile. The one despawn resolves to the OLDEST match,
	// which is the stale entry, releasePileFromDropSession reads seq -1 and tells the recorder
	// nothing. The new session's key is never released, the tail can never arm, and the recorder
	// runs until a logout. That is finding F1 path A returning through a different door.
	//
	// THE FIX is one shared teardown, endDropSessionTracking(), that every end path calls.
	//
	// Case 23 is the control arm. A fix that untracked every pile, or that stopped recording
	// altogether, would pass 21 and 22.
	// ================================================================

	/**
	 * Drive the ONE ItemDespawned the game fires for a pile, exactly as onItemDespawned drives it:
	 * resolve through resolveDespawnedGroundDrop, remove that record, then tell the session.
	 *
	 * Going through the RESOLVER rather than through a pile reference is the whole point. Both
	 * findings live in the resolver — R1 was it picking a phantom, R2 was it picking our own pile
	 * when another real one went — and a test holding the right reference would never see either.
	 */
	private static void despawnTrackedPileAt(AccountConnectPlugin p, int item, int x, int y)
		throws Exception
	{
		AccountConnectPlugin.DroppedGroundItem g =
			p.resolveDespawnedGroundDrop(item, x, y, 0);
		assertNotNull("the despawn must resolve to a tracked pile", g);
		java.util.Deque<AccountConnectPlugin.DroppedGroundItem> q = groundDrops(p);
		synchronized (q)
		{
			q.remove(g);
		}
		release(p, g);
	}

	/**
	 * The same despawn, but PUBLISHING the ground_removed row, which is what onItemDespawned does
	 * for a pile with no live Take against it. Returns the record the resolver chose.
	 */
	private static AccountConnectPlugin.DroppedGroundItem despawnAndPublishAt(
		AccountConnectPlugin p, int item, int x, int y, int tick) throws Exception
	{
		AccountConnectPlugin.DroppedGroundItem g =
			p.resolveDespawnedGroundDrop(item, x, y, 0);
		assertNotNull("the despawn must resolve to a tracked pile", g);
		java.util.Deque<AccountConnectPlugin.DroppedGroundItem> q = groundDrops(p);
		synchronized (q)
		{
			q.remove(g);
		}
		p.emitGroundRemoval(g, tick, false);	// this also releases the pile from the session
		return g;
	}

	/** The last ground_removed row published, or null. */
	private static Map<String, Object> lastRemoval(AccountConnectPlugin p)
	{
		Map<String, Object> last = null;
		for (Map<String, Object> e : p.pendingEvents)
		{
			if ("ground_removed".equals(e.get("type")))
			{
				last = e;
			}
		}
		return last;
	}

	@SuppressWarnings("unchecked")
	private static java.util.Deque<AccountConnectPlugin.DroppedGroundItem> groundDrops(
		AccountConnectPlugin p) throws Exception
	{
		return (java.util.Deque<AccountConnectPlugin.DroppedGroundItem>) field(p, "groundDrops");
	}

	// ===== CASE 21 — the USER's own switch, off then on, then a merged re-drop =====

	@Test
	public void case21_aWithdrawalThenAMergedReDropOntoTheSameTileStillStops() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		// Session 1: a real tracked pile of a stackable on one tile.
		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals(1, p.groundDropCount());
		assertEquals(1, p.dropSession.activePileCount());

		// The user unticks the upload switch. The pile is still physically on the ground.
		r.config.upload = false;
		poll(p, 1);
		assertFalse("the withdrawal ends the session", p.dropSession.active());
		assertEquals("FINDING B: and it must not leave the pile tracked but unowned",
			0, p.groundDropCount());

		// The switch goes back on and session 2 drops the SAME stackable onto the SAME tile.
		r.config.upload = true;
		dropAction(p);
		assertTrue("a new session starts", p.dropSession.active());
		p.trackGroundDrop(995, 1_000L, 3200, 3400, 0, null, 200, 400, false);
		assertEquals("one physical pile means ONE tracked entry", 1, p.groundDropCount());
		dropAction(p);
		p.trackGroundDrop(995, 1_500L, 3200, 3400, 0, null, 201, 400, true);
		assertEquals("a merge is still ONE tracked entry", 1, p.groundDropCount());
		assertEquals("and the session holds ONE key for it", 1, p.dropSession.activePileCount());
		assertEquals("with no pending left outstanding", 0, p.dropSession.pendingDropCount());

		// The ONE despawn the game fires.
		despawnTrackedPileAt(p, 995, 3200, 3400);
		forcePendingsExpired(p);
		poll(p, 50);
		assertTrue("the single despawn must arm the tail", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse("NO IMMORTAL RECORDER: the session must actually end",
			p.dropSession.active());
		assertFalse("and capture must be disarmed", (Boolean) field(p, "dropCapturing"));
	}

	// ===== CASE 22 — the OPERATOR withdrawing and restoring the grant =====

	@Test
	public void case22_aGrantWithdrawalThenAMergedReDropOntoTheSameTileStillStops() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals(1, p.groundDropCount());

		// The operator sets drop_proof: false. This is the path ops/ACTIVATION.md A0d documents.
		p.setDropProofRolloutForTest(false);
		p.onDropProofCapabilityChanged();
		assertFalse("the withdrawn grant ends the session", p.dropSession.active());
		assertEquals("FINDING B: and it must not strand the pile", 0, p.groundDropCount());

		// The grant comes back.
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();

		dropAction(p);
		p.trackGroundDrop(995, 1_000L, 3200, 3400, 0, null, 200, 400, false);
		dropAction(p);
		p.trackGroundDrop(995, 1_500L, 3200, 3400, 0, null, 201, 400, true);
		assertEquals("one physical pile, one tracked entry", 1, p.groundDropCount());
		assertEquals("one session key", 1, p.dropSession.activePileCount());

		despawnTrackedPileAt(p, 995, 3200, 3400);
		forcePendingsExpired(p);
		poll(p, 50);
		assertTrue(p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse("NO IMMORTAL RECORDER on the operator path either", p.dropSession.active());
	}

	// ===== CASE 23 — THE CONTROL: the teardown must untrack only the SESSION's piles =====

	/**
	 * A fix that called clearGroundDrops() would pass cases 21 and 22 and silently delete the
	 * ground_removed evidence for every ordinary pile the player is tracking for unrelated reasons.
	 * This arm fails that fix, and it also fails a fix that simply stopped recording.
	 */
	@Test
	public void case23_theTeardownUntracksOnlyTheSessionsOwnPiles() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		// A pile tracked with NO drop session running. Nothing about it belongs to drop proof.
		p.trackGroundDrop(4151, 1L, 3300, 3500, 0, null, 50, 400, false);
		assertEquals(1, p.groundDropCount());
		assertNull("it belongs to no session", p.dropSessionForPile(onlyTrackedPile(p)));

		// Now a real session with its own pile on a different tile.
		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals(2, p.groundDropCount());

		r.config.upload = false;
		poll(p, 1);
		assertFalse(p.dropSession.active());
		assertEquals("the unrelated pile MUST survive the teardown", 1, p.groundDropCount());
		AccountConnectPlugin.DroppedGroundItem survivor = onlyTrackedPile(p);
		assertEquals("and it is the unrelated one, not the session's", 4151, survivor.item);

		// And recording still works afterwards, so this is not a fix that broke capture.
		r.config.upload = true;
		dropAction(p);
		assertTrue("a fresh session still records", p.dropSession.active());
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		assertNotNull("with a live buffer", field(p, "dropSegmenter"));
		p.trackGroundDrop(995, 5L, 3201, 3400, 0, null, 300, 400, false);
		despawnTrackedPileAt(p, 995, 3201, 3400);
		forceTailDue(p);
		poll(p, 1);
		assertFalse("and it ends normally", p.dropSession.active());
		assertNotNull("publishing its own manifest", manifest(p));
	}

	// ===== CASE 24 — THE HARM ITSELF, asserted with NO intermediate state check =====

	/**
	 * ROUND 4, FINDING B, THE OUTCOME ARM.
	 *
	 * Cases 21 and 22 catch the stranded pile at the moment it is stranded, which is the earliest
	 * and clearest signal. This arm deliberately asserts NOTHING about groundDrops. It drives the
	 * whole scenario and asserts only the property the two user-facing strings promise: the
	 * recording always ends. It is the arm that would have caught this defect written by somebody
	 * who had never heard of groundDrops.
	 *
	 * IT NEVER CALLS forceTailDue BEFORE THE ARM. forceTailDue writes stopAtMillis straight into
	 * the recorder, which is exactly what an immortal recorder cannot do for itself, so using it
	 * here would hide the defect: the stranded key means maybeArmStop is NEVER REACHED, and a
	 * forced stopAtMillis would end the session anyway and leave the arm green. The discriminating
	 * assertion is therefore that the tail ARMS ON ITS OWN after the single despawn. forceTailDue
	 * appears only after that, to show the armed tail then really ends the session.
	 */
	@Test
	public void case24_theRecordingAlwaysEndsAfterAWithdrawalAndAMergedReDrop() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);

		r.config.upload = false;
		poll(p, 1);
		r.config.upload = true;

		dropAction(p);
		p.trackGroundDrop(995, 1_000L, 3200, 3400, 0, null, 200, 400, false);
		dropAction(p);
		p.trackGroundDrop(995, 1_500L, 3200, 3400, 0, null, 201, 400, true);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		assertTrue("the second session is recording", (Boolean) field(p, "dropCapturing"));

		// The ONE despawn the game fires for the merged pile. Every pending is expired and the
		// poll is driven 50 times, so only a genuinely stranded key can hold the session open.
		despawnTrackedPileAt(p, 995, 3200, 3400);
		forcePendingsExpired(p);
		poll(p, 50);

		assertEquals("the despawn released the session's only pile key",
			0, p.dropSession.activePileCount());
		assertEquals("and nothing is pending", 0, p.dropSession.pendingDropCount());
		assertTrue("NO IMMORTAL RECORDER: with nothing outstanding the tail MUST have armed itself",
			p.dropSession.stopPending());

		forceTailDue(p);
		poll(p, 1);
		assertFalse("and the armed tail really ends the recording", p.dropSession.active());
		assertFalse("and the screen is no longer being captured",
			(Boolean) field(p, "dropCapturing"));
	}

	// ================================================================
	// CASES 25-26 — ROUND 4, FINDING A: A QUEUED RETRY AFTER A WITHDRAWN GRANT
	//
	// THE DEFECT. postDropSegment re-checked uploadAllowed() and the token on every attempt and
	// never asked dropProofEnabled(). uploadAllowed() is the user's own switch plus a well-formed
	// token, and neither moves when the OPERATOR withdraws drop proof. So the guard set was
	// INCONSISTENT: the user switch stopped a queued retry, a token swap stopped it, and the
	// operator's own documented "turn it off" did not.
	//
	// WHY IT MATTERS. discardDropSessionOnWithdrawnConsent cannot reach a Runnable already sitting
	// on the executor: the closure holds its own Segment with the frame bytes inside it. Retries
	// run DROP_SEGMENT_RETRIES times at a CLIP_UPLOAD_TIMEOUT_SECONDS call timeout each, so the
	// window is minutes. The server stores the late POST, because /store-frames-ingest authorizes
	// on the staff token and never reads drop_proof.
	//
	// THE PROBE. The rig injects no OkHttp client, so an attempt that REACHES the network throws a
	// NullPointerException. postDropSegmentReachedTheNetwork reports that. Every arm below is
	// two-sided: without the live control a fix that broke all drop uploads would look identical.
	// ================================================================

	/** A real segment, and a plugin whose gates are all open, ready for one upload attempt. */
	private static DropFrameSegmenter.Segment segmentForUpload()
	{
		return takeOneSegment();
	}

	// ===== CASE 25 — the operator withdraws the grant mid-retry =====

	@Test
	public void case25_aQueuedRetryIsStrandedAfterTheOperatorWithdrawsTheGrant() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		DropFrameSegmenter.Segment seg = segmentForUpload();

		// THE LIVE CONTROL FIRST. With the grant still held, the identical attempt must reach the
		// network, or every assertion below passes against a plugin that simply cannot upload.
		assertTrue("CONTROL: with the grant live the retry must reach the network",
			postDropSegmentReachedTheNetwork(p, TOKEN, seg));

		// The operator sets drop_proof: false. Nothing else changes: the user's switch is still on
		// and the token is unchanged and still the one the bytes were captured under.
		p.setDropProofRolloutForTest(false);
		assertTrue("the user's own switch is untouched", p.uploadAllowed());
		assertEquals("and so is the token", TOKEN, p.currentLinkToken());

		assertFalse("FINDING A: the queued retry must be stranded before any network work",
			postDropSegmentReachedTheNetwork(p, TOKEN, seg));

		// And it comes back when the operator restores the grant, so this is a gate and not a latch.
		p.setDropProofRolloutForTest(true);
		assertTrue("CONTROL: restoring the grant restores the upload",
			postDropSegmentReachedTheNetwork(p, TOKEN, seg));
	}

	// ===== CASE 26 — the whole guard set is CONSISTENT =====

	/**
	 * THE CONSISTENCY ARM. Three ways a drop recording can lose its authority to upload, each
	 * driven through the same call with the same segment. All three must strand it, and the live
	 * control between each pair proves the probe still discriminates.
	 *
	 * The reviewer's point was not that one guard was missing. It was that two of the three were
	 * present, which made the gap look deliberate.
	 */
	@Test
	public void case26_everyWayToLoseAuthorityStrandsAQueuedRetry() throws Exception
	{
		DropFrameSegmenter.Segment seg = segmentForUpload();

		// 1. The USER's own switch.
		Rig a = rig();
		assertTrue("CONTROL", postDropSegmentReachedTheNetwork(a.plugin, TOKEN, seg));
		a.config.upload = false;
		assertFalse("the user switch strands it", postDropSegmentReachedTheNetwork(a.plugin, TOKEN, seg));

		// 2. A TOKEN swap. The attempt carries the token the bytes were captured under.
		Rig b = rig();
		assertTrue("CONTROL", postDropSegmentReachedTheNetwork(b.plugin, TOKEN, seg));
		b.config.token = OTHER_TOKEN;
		assertFalse("a token swap strands it", postDropSegmentReachedTheNetwork(b.plugin, TOKEN, seg));

		// 3. The OPERATOR's grant. This is the one that leaked.
		Rig c = rig();
		assertTrue("CONTROL", postDropSegmentReachedTheNetwork(c.plugin, TOKEN, seg));
		c.plugin.setDropProofRolloutForTest(false);
		assertFalse("and a withdrawn grant must strand it too",
			postDropSegmentReachedTheNetwork(c.plugin, TOKEN, seg));

		// 4. X-Clips forced off, which is the state every non-staff token is in.
		Rig d = rig();
		assertTrue("CONTROL", postDropSegmentReachedTheNetwork(d.plugin, TOKEN, seg));
		d.plugin.applyServerPolicy(policyResponse("X-Clips", "off"));
		assertFalse("clips forced off strands it as well",
			postDropSegmentReachedTheNetwork(d.plugin, TOKEN, seg));
	}

	// ================================================================
	// CASES 27-30 — ROUND 4: THE REAL X-Drop-Proof HEADER PARSING PATH
	//
	// X-Drop-Proof was never parsed in ANY client test. Every existing arm reached the rollout flag
	// through setDropProofRolloutForTest, which is a package-private test hook, so the only code
	// that ever writes the flag in production was unexercised. These arms drive applyServerPolicy
	// with a real okhttp3.Response, which is exactly what the postSnapshot callback hands it.
	// ================================================================

	/** A bare 200 carrying the given header key/value pairs, the shape applyServerPolicy reads. */
	private static okhttp3.Response policyResponse(String... headerKV)
	{
		okhttp3.Response.Builder b = new okhttp3.Response.Builder()
			.request(new okhttp3.Request.Builder().url("http://localhost/account-ingest").build())
			.protocol(okhttp3.Protocol.HTTP_1_1)
			.code(200)
			.message("OK");
		for (int i = 0; i + 1 < headerKV.length; i += 2)
		{
			b.header(headerKV[i], headerKV[i + 1]);
		}
		return b.build();
	}

	// ===== CASE 27 — ON, in every spelling the parser accepts =====

	@Test
	public void case27_theHeaderTurnsTheGrantOnThroughTheRealParsingPath() throws Exception
	{
		for (String on : new String[]{"on", "enabled", "true", "1", "ON", " On "})
		{
			Rig r = rig();
			AccountConnectPlugin p = r.plugin;
			p.setDropProofRolloutForTest(false);
			assertFalse("starts off", p.dropProofEnabled());

			p.applyServerPolicy(policyResponse("X-Drop-Proof", on));

			assertTrue("X-Drop-Proof: " + on + " must grant the rollout", p.dropProofEnabled());
			dropAction(p);
			assertTrue("and a drop must then start a session", p.dropSession.active());
		}
	}

	// ===== CASE 28 — OFF, through the real header, ends a running session =====

	/**
	 * This is the operator's documented "turning it off" in ops/ACTIVATION.md A0d, driven end to
	 * end for the first time: a real response header, the real parser, the real capability hook.
	 */
	@Test
	public void case28_theOffHeaderEndsARunningRecordingAndItsGroundTracking() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		p.applyServerPolicy(policyResponse("X-Drop-Proof", "on"));

		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		assertTrue(p.dropSession.active());
		assertNotNull(field(p, "dropSegmenter"));
		assertEquals(1, p.groundDropCount());

		p.applyServerPolicy(policyResponse("X-Drop-Proof", "off"));

		assertFalse("the grant is gone", p.dropProofEnabled());
		assertFalse("the session ends on that response", p.dropSession.active());
		assertFalse("capture is disarmed", (Boolean) field(p, "dropCapturing"));
		assertNull("the buffer is destroyed", field(p, "dropSegmenter"));
		assertEquals("and the ground tracking is reset (finding B)", 0, p.groundDropCount());
		assertNull("no manifest is published about a withdrawn recording", manifest(p));
	}

	// ===== CASE 29 — ABSENT leaves the flag exactly as it was, in BOTH directions =====

	/**
	 * An absent header must not revoke a live rollout, and it must not grant one either. A single
	 * malformed or older response is the case this protects against. Both directions are asserted,
	 * because a parser that treated absent as "off" and one that treated it as "on" are different
	 * defects and only one of them is caught by a one-sided arm.
	 */
	@Test
	public void case29_anAbsentHeaderChangesNothingInEitherDirection() throws Exception
	{
		Rig granted = rig();
		granted.plugin.applyServerPolicy(policyResponse("X-Drop-Proof", "on"));
		assertTrue(granted.plugin.dropProofEnabled());
		granted.plugin.applyServerPolicy(policyResponse("X-Sync-Interval", "5"));
		assertTrue("an absent header must not revoke a live grant",
			granted.plugin.dropProofEnabled());

		Rig never = rig();
		never.plugin.setDropProofRolloutForTest(false);
		never.plugin.applyServerPolicy(policyResponse("X-Sync-Interval", "5"));
		assertFalse("and it must not grant one that was never made",
			never.plugin.dropProofEnabled());
	}

	// ===== CASE 30 — GARBAGE fails CLOSED =====

	/**
	 * Anything that is not an explicit on-value is off. The header is present, so it IS read; a
	 * value the parser does not understand must revoke rather than be ignored, because an ignored
	 * garbage value would leave a grant standing that the server may be trying to withdraw.
	 */
	@Test
	public void case30_aGarbageHeaderValueFailsClosed() throws Exception
	{
		for (String junk : new String[]{"yes", "maybe", "", "  ", "0", "off", "disabled",
			"false", "2", "on-ish", "null", "<script>"})
		{
			Rig r = rig();
			AccountConnectPlugin p = r.plugin;
			p.applyServerPolicy(policyResponse("X-Drop-Proof", "on"));
			assertTrue("the grant is live before the junk arrives", p.dropProofEnabled());

			// A session is running when the junk lands, so the arm also proves it is torn down.
			dropAction(p);
			p.trackGroundDrop(995, 5L, 3200, 3400, 0, null, 100, 400, false);
			assertTrue(p.dropSession.active());

			p.applyServerPolicy(policyResponse("X-Drop-Proof", junk));

			assertFalse("X-Drop-Proof: '" + junk + "' must fail closed", p.dropProofEnabled());
			assertFalse("and end the running session", p.dropSession.active());
			assertEquals("and reset the ground tracking", 0, p.groundDropCount());
			dropAction(p);
			assertFalse("and no new session may start", p.dropSession.active());
		}
	}

	// ================================================================
	// CASE 31 — ROUND 4, FINDING E: A 200-WITH-dropped IS NOT AN UPLOAD
	//
	// /store-frames-ingest answers a deliberate refusal with 200 {ok:true, dropped:'not_staff'} or
	// 200 {ok:true, dropped:'clips_disabled'}. The 200 is deliberate: it stops a public plugin
	// error-retrying something the server meant to throw away. The client read only
	// response.isSuccessful(), so it counted the refusal as sent and acknowledged the segment, and
	// segments_uploaded_at_emit then overstated what actually reached R2.
	// ================================================================

	/** A 2xx with the given JSON body, the shape /store-frames-ingest returns. */
	private static okhttp3.Response jsonResponse(int code, String body)
	{
		return new okhttp3.Response.Builder()
			.request(new okhttp3.Request.Builder().url("http://localhost/store-frames-ingest").build())
			.protocol(okhttp3.Protocol.HTTP_1_1)
			.code(code)
			.message("OK")
			.body(okhttp3.ResponseBody.create(
				okhttp3.MediaType.parse("application/json"), body))
			.build();
	}

	@Test
	public void case31_a200CarryingDroppedIsCountedAsARefusalNotAnUpload() throws Exception
	{
		// THE REFUSALS the server actually sends.
		assertTrue("not_staff is a refusal", AccountConnectPlugin.dropSegmentWasRefused(
			jsonResponse(200, "{\"ok\":true,\"dropped\":\"not_staff\"}")));
		assertTrue("clips_disabled is a refusal", AccountConnectPlugin.dropSegmentWasRefused(
			jsonResponse(200, "{\"ok\":true,\"dropped\":\"clips_disabled\"}")));

		// THE CONTROL SIDE, and it is what stops this becoming "no drop upload ever counts".
		assertFalse("a real store is NOT a refusal", AccountConnectPlugin.dropSegmentWasRefused(
			jsonResponse(200, "{\"ok\":true,\"key\":\"drop/abc/0.jpg\"}")));
		assertFalse("an empty body is not a refusal",
			AccountConnectPlugin.dropSegmentWasRefused(jsonResponse(200, "")));
		assertFalse("and neither is a body that merely mentions the word",
			AccountConnectPlugin.dropSegmentWasRefused(
				jsonResponse(200, "{\"ok\":true,\"note\":\"nothing dropped here\"}")));
		assertFalse("a null response can never invent a failure",
			AccountConnectPlugin.dropSegmentWasRefused(null));

		// THE BODY IS NOT CONSUMED. peekBody must leave the response readable, or the real callback
		// would break the paths that read a body after this check.
		okhttp3.Response live = jsonResponse(200, "{\"ok\":true,\"dropped\":\"not_staff\"}");
		assertTrue(AccountConnectPlugin.dropSegmentWasRefused(live));
		assertTrue("the body survives the peek",
			live.body().string().contains("not_staff"));
	}

	// ===== CASE 32 — THE REAL CALLBACK, against a server that really refuses =====

	/**
	 * Case 31 pins the predicate. This arm drives the whole upload through the real OkHttp callback
	 * against a live local HTTP server, because the predicate being right proves nothing about the
	 * callback calling it. The server answers exactly what /store-frames-ingest answers for a
	 * deliberate drop, and the arm asserts on the plugin's own counters.
	 *
	 * The control is the same server on the second run answering a real store, so a fix that
	 * counted every upload as failed fails this arm.
	 */
	@Test
	public void case32_theRealCallbackCountsARefusalAsFailedAndAStoreAsSent() throws Exception
	{
		assertUploadCounted("{\"ok\":true,\"dropped\":\"clips_disabled\"}", 0, 1,
			"a 200-with-dropped is a refusal and must never be counted as sent");
		assertUploadCounted("{\"ok\":true,\"key\":\"drop/abc/0.jpg\"}", 1, 0,
			"CONTROL: a real store must still be counted as sent");
	}

	private void assertUploadCounted(String responseBody, int expectSent, int expectFailed,
		String why) throws Exception
	{
		byte[] resp = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
			new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		final java.util.concurrent.CountDownLatch hit = new java.util.concurrent.CountDownLatch(1);
		server.createContext("/", exchange ->
		{
			byte[] buf = new byte[4096];
			while (exchange.getRequestBody().read(buf) > 0)
			{
				// drain, so the client's write completes before the response
			}
			exchange.sendResponseHeaders(200, resp.length);
			exchange.getResponseBody().write(resp);
			exchange.close();
			hit.countDown();
		});
		server.start();
		java.util.concurrent.ScheduledExecutorService exec =
			java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
		try
		{
			Rig r = rig();
			AccountConnectPlugin p = r.plugin;
			Field ex = AccountConnectPlugin.class.getDeclaredField("executor");
			ex.setAccessible(true);
			ex.set(p, exec);
			Field ok = AccountConnectPlugin.class.getDeclaredField("okHttpClient");
			ok.setAccessible(true);
			ok.set(p, new okhttp3.OkHttpClient());

			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			Method m = AccountConnectPlugin.class.getDeclaredMethod("postDropSegment", String.class,
				String.class, DropFrameSegmenter.Segment.class, String.class, int.class);
			m.setAccessible(true);
			m.invoke(p, base, TOKEN, takeOneSegment(), "sid", 0);

			assertTrue("the server never received the upload",
				hit.await(15, java.util.concurrent.TimeUnit.SECONDS));

			java.util.concurrent.atomic.AtomicInteger sent =
				(java.util.concurrent.atomic.AtomicInteger) field(p, "dropSegmentsSent");
			java.util.concurrent.atomic.AtomicInteger failed =
				(java.util.concurrent.atomic.AtomicInteger) field(p, "dropSegmentsFailed");
			// The counter is written on the OkHttp callback thread, so wait for it rather than
			// reading it the instant the server's handler returned.
			long deadline = System.currentTimeMillis() + 15_000L;
			while (System.currentTimeMillis() < deadline
				&& sent.get() + failed.get() == 0)
			{
				Thread.sleep(20L);
			}
			assertEquals(why, expectSent, sent.get());
			assertEquals(why + " (failed count)", expectFailed, failed.get());
		}
		finally
		{
			exec.shutdownNow();
			server.stop(0);
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

	// ================================================================
	// CASES 33-36 — ROUND 5, FINDING B2: A STALE PILE WITH NO SESSION ENDING AT ALL
	//
	// THE DEFECT, AND WHY ROUND 4 DID NOT CLOSE IT. Round 4 shut the door where a WITHDRAWAL left
	// an owned pile tracked but unowned. It did not address the commoner way a pile ends up in
	// groundDrops with no session owner: a pile that was NEVER OWNED in the first place.
	//
	// Ground tracking is gated on activityLogActive() — a valid link token, nothing more. The drop
	// session is gated on dropProofEnabled() — the token AND the upload switch AND the store-tools
	// grant AND the rollout flag. The two gates are deliberately different widths, so a pile
	// tracked while the narrow gate is shut and still physically on the ground when it opens is the
	// same stale entry, arrived at with nothing ending.
	//
	// Then the harm is identical: liveSessionPileAt refused the unowned pile, trackGroundDrop
	// minted a SECOND DroppedGroundItem for ONE physical pile, the single ItemDespawned resolved to
	// the OLDEST match (the unowned entry), releasePileFromDropSession read seq -1 and told the
	// recorder nothing, the session's key was never released and the recorder ran until a logout.
	//
	// THE FIX, AT THE CAUSE. liveSessionPileAt is right for ATTRIBUTION and wrong for MERGE
	// DETECTION. The merge lookup is now findGroundDrop — ownership-agnostic, and the SAME resolver
	// onItemDespawned uses — and the pile found is ADOPTED into the running session.
	//
	// EVERY ARM BELOW ASSERTS THE TAIL ARMS ON ITS OWN. forceTailDue writes stopAtMillis straight
	// into the recorder, which is exactly what an immortal recorder cannot do for itself, so it is
	// never called before the discriminating assertion. Case 36 is the same control case 23 is, on
	// the tile where it is hardest.
	// ================================================================

	// ===== CASE 33 — the GRANT arrives after the pile is already on the tile =====

	/**
	 * The operator's own rollout path, and no gate moves against the player at all. A staff member
	 * with the switch on and a token linked tracks a pile normally, because ground tracking never
	 * asked about the grant. The grant then lands mid-scene.
	 */
	@Test
	public void case33_aGrantArrivingAfterThePileIsAlreadyOnTheTileStillStops() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		// No grant yet. Ground tracking still runs: its gate is the link token, not the grant.
		p.setDropProofRolloutForTest(false);
		assertFalse("the narrow gate is shut", p.dropProofEnabled());
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals("the pile is tracked anyway", 1, p.groundDropCount());
		assertFalse("and no session exists to own it", p.dropSession.active());
		assertNull("so it is unowned", p.dropSessionForPile(onlyTrackedPile(p)));

		// The grant lands. The pile is still physically on the ground.
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();

		// The staff member drops the same stackable onto the same tile. The game MERGES it.
		dropAction(p);
		assertTrue("a session starts", p.dropSession.active());
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(995, 1_500L, 3200, 3400, 0, null, 200, 400, true);

		assertEquals("FINDING B2: one physical pile is ONE tracked entry", 1, p.groundDropCount());
		assertEquals("and the session holds exactly ONE key for it",
			1, p.dropSession.activePileCount());
		assertEquals("with nothing left pending", 0, p.dropSession.pendingDropCount());

		// The ONE despawn the game fires.
		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 50);
		assertTrue("NO IMMORTAL RECORDER: the single despawn MUST arm the tail on its own",
			p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse("and the armed tail really ends it", p.dropSession.active());
		assertFalse("and the screen is no longer captured", (Boolean) field(p, "dropCapturing"));
	}

	// ===== CASE 34 — the USER's own switch, off while the pile is tracked, then on =====

	/**
	 * The same defect with NO operator action at all. The user's upload switch is the only thing
	 * that moves, and it moves in the direction that GRANTS. activityLogActive() does not read the
	 * switch, so the pile is tracked throughout.
	 */
	@Test
	public void case34_aUserSwitchOffThenOnOverAnAlreadyTrackedPileStillStops() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		r.config.upload = false;
		assertFalse("the switch off shuts the narrow gate", p.dropProofEnabled());
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals("the pile is tracked with the switch off", 1, p.groundDropCount());
		assertNull("and belongs to no session", p.dropSessionForPile(onlyTrackedPile(p)));

		r.config.upload = true;
		dropAction(p);
		assertTrue(p.dropSession.active());
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(995, 1_200L, 3200, 3400, 0, null, 200, 400, true);

		assertEquals("FINDING B2: still ONE tracked entry", 1, p.groundDropCount());
		assertEquals("and ONE session key", 1, p.dropSession.activePileCount());

		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 50);
		assertTrue("NO IMMORTAL RECORDER on the user's own path either",
			p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
	}

	// ===== CASE 35 — a LATE pile, after its own pending expired, with no gate moving =====

	/**
	 * The grant is held the whole time and nothing is withdrawn or restored. A Drop click whose
	 * pile spawns after its own pending expired (PENDING_DROP_EXPIRY_MILLIS — the destroy dialog, a
	 * laggy spawn) is refused by attachPileToDropSession, because pendingDropSeq is 0 by then. So
	 * the pile is tracked and unowned inside a LIVE session, which is the narrowest route of the
	 * three and the one no gate can be blamed for.
	 */
	@Test
	public void case35_aLatePileAfterItsPendingExpiredStillStops() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		forcePendingsExpired(p);
		poll(p, 1);
		assertTrue("the session is still running, in its tail", p.dropSession.active());
		assertEquals("the pending expired, so nothing is outstanding",
			0, p.dropSession.pendingDropCount());

		// The pile finally lands. attachPileToDropSession refuses it: pendingDropSeq is 0.
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 120, 400, false);
		assertEquals(1, p.groundDropCount());
		assertNull("the late pile is unowned", p.dropSessionForPile(onlyTrackedPile(p)));
		assertEquals("and the session holds no key for it", 0, p.dropSession.activePileCount());

		// A second Drop onto the same tile. The game MERGES it into that same pile.
		dropAction(p);
		p.trackGroundDrop(995, 900L, 3200, 3400, 0, null, 130, 400, true);

		assertEquals("FINDING B2: still ONE tracked entry", 1, p.groundDropCount());
		assertEquals("and ONE session key", 1, p.dropSession.activePileCount());
		assertEquals("with nothing pending", 0, p.dropSession.pendingDropCount());

		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 50);
		assertTrue("NO IMMORTAL RECORDER after a late pile either", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
		assertEquals("an expired pending can never be called COMPLETE",
			"INTERRUPTED", manifest(p).get("outcome"));
	}

	// ===== CASE 36 — THE CONTROL, on the SAME tile: an unrelated pile still survives =====

	/**
	 * Case 23's property, moved onto the hardest tile. The merge lookup is now ownership-agnostic,
	 * so a fix that read "any pile on this tile now belongs to the session" would pass cases 33-35
	 * and quietly delete an unrelated pile's ground_removed evidence at teardown.
	 *
	 * The unrelated pile is a DIFFERENT ITEM on the SESSION'S OWN TILE, tracked before the session
	 * starts. The session never drops that item, so nothing can merge into it, and the teardown
	 * must leave it exactly where it is.
	 */
	@Test
	public void case36_anUnrelatedPileOnTheSessionsOwnTileSurvivesTheTeardown() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		// A pile of a DIFFERENT item, on the tile the session will use, tracked with no session.
		p.trackGroundDrop(4151, 1L, 3200, 3400, 0, null, 50, 400, false);
		assertEquals(1, p.groundDropCount());
		assertNull("it belongs to no session", p.dropSessionForPile(onlyTrackedPile(p)));

		// The session runs on that same tile with its own stackable.
		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals("two different items on one tile are two piles", 2, p.groundDropCount());
		dropAction(p);
		p.trackGroundDrop(995, 800L, 3200, 3400, 0, null, 101, 400, true);
		assertEquals("the merge adds no third entry", 2, p.groundDropCount());
		assertEquals("and the session owns exactly one key", 1, p.dropSession.activePileCount());

		// End the session through a withdrawal, which is the teardown path case 23 uses.
		r.config.upload = false;
		poll(p, 1);
		assertFalse(p.dropSession.active());
		assertEquals("the unrelated pile on the SAME tile MUST survive the teardown",
			1, p.groundDropCount());
		assertEquals("and it is the unrelated one", 4151, onlyTrackedPile(p).item);
	}

	// ===== CASE 36b — THE HARM ITSELF, with NO reference to groundDrops =====

	/**
	 * ROUND 5, FINDING B2, THE OUTCOME ARM, and the exact shape case 24 has for finding B.
	 *
	 * Cases 33-35 catch the second tracked entry at the moment it is minted, which is the earliest
	 * and clearest signal. Under a reverted fix they fail on that count, before they ever reach the
	 * tail. This arm deliberately asserts NOTHING about groundDrops. It drives the grant-arrives-
	 * late route and asserts only the property the two user-facing strings promise: the recording
	 * always ends. It is the arm that would have caught this defect written by somebody who had
	 * never heard of groundDrops.
	 *
	 * IT NEVER CALLS forceTailDue BEFORE THE ARM, for the reason case 24 states: forceTailDue
	 * writes stopAtMillis straight into the recorder, which is exactly what an immortal recorder
	 * cannot do for itself, so using it early would hide the defect.
	 *
	 * IT ALSO DOES NOT LEAN ON THE BACKSTOP. The poll count is far below the invariant grace, so
	 * only the merge-detection fix can make this arm green.
	 */
	@Test
	public void case36b_theRecordingAlwaysEndsAfterAGrantArrivesOverAnExistingPile() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		p.setDropProofRolloutForTest(false);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();

		dropAction(p);
		p.trackGroundDrop(995, 1_500L, 3200, 3400, 0, null, 200, 400, true);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		assertTrue("the session is recording", (Boolean) field(p, "dropCapturing"));

		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 50);

		assertEquals("the despawn released the session's only pile key",
			0, p.dropSession.activePileCount());
		assertEquals("and nothing is pending", 0, p.dropSession.pendingDropCount());
		assertTrue("NO IMMORTAL RECORDER: with nothing outstanding the tail MUST have armed itself",
			p.dropSession.stopPending());

		forceTailDue(p);
		poll(p, 1);
		assertFalse("and the armed tail really ends the recording", p.dropSession.active());
		assertFalse("and the screen is no longer being captured",
			(Boolean) field(p, "dropCapturing"));
	}

	// ================================================================
	// CASES 37-39 — ROUND 5, THE STRUCTURAL BACKSTOP
	//
	// WHY A BACKSTOP AND NOT A FOURTH PATCH. "The recording always ends" has now failed three
	// review rounds by three different routes: a merged stack, the consent-withdrawal end path, and
	// finding B2's never-owned pile. The END-PATH ENUMERATION WAS COMPLETE EVERY TIME. The defect
	// was always in how stale state ENTERS, and entry routes are an open set. So these arms pin a
	// bound that does not care how the state went wrong.
	//
	// THEY MUST NOT DEPEND ON THE B2 FIX. Each one injects the broken state DIRECTLY — an owned key
	// the caller holds no pile for, or a session clock rewound past the cap — so reverting the B2
	// fix leaves them green and removing the backstop turns them red. That is what makes them an
	// independent last line of defence rather than a second reading of case 33.
	// ================================================================

	/**
	 * LEAK AN OWNED KEY, exactly as an unknown entry route would.
	 *
	 * The recorder is left holding a live key for a pile the caller no longer tracks, with no
	 * pending outstanding. Nothing can ever release that key: a removal is only reported for a pile
	 * that is still in groundDrops. This is the invariant violation itself, injected rather than
	 * reached, so no arm below inherits any assumption about which route produced it.
	 */
	private static void leakOneOwnedKey(AccountConnectPlugin p) throws Exception
	{
		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals(1, p.dropSession.activePileCount());
		AccountConnectPlugin.DroppedGroundItem g = onlyTrackedPile(p);
		java.util.Deque<AccountConnectPlugin.DroppedGroundItem> q = groundDrops(p);
		synchronized (q)
		{
			q.remove(g);		// the pile is gone from the tracker; the KEY is not released
		}
		assertEquals("the caller tracks nothing", 0, p.groundDropCount());
		assertEquals("and the recorder still waits on a key", 1, p.dropSession.activePileCount());
		assertFalse("so the tail can never arm by itself", p.dropSession.stopPending());
	}

	/** Rewind the recorder's orphan stopwatch, so the grace elapses without sleeping 30 seconds. */
	private static void forceOrphanGraceElapsed(AccountConnectPlugin p) throws Exception
	{
		Field f = DropSessionRecorder.class.getDeclaredField("orphanedSinceMillis");
		f.setAccessible(true);
		long since = f.getLong(p.dropSession);
		assertTrue("the invariant must already be tripped before the grace can elapse", since > 0L);
		f.setLong(p.dropSession, since - DropSessionRecorder.ORPHANED_STATE_GRACE_MILLIS);
	}

	// ===== CASE 37 — the invariant backstop ends a session with a leaked key =====

	@Test
	public void case37_aLeakedOwnedKeyIsEndedByTheInvariantBackstop() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		leakOneOwnedKey(p);
		assertTrue("the recorder is capturing", (Boolean) field(p, "dropCapturing"));

		poll(p, 1);			// first poll: the invariant trips and the stopwatch starts
		assertTrue("the grace has not elapsed yet, so the session runs on", p.dropSession.active());

		forceOrphanGraceElapsed(p);
		poll(p, 2);

		assertFalse("THE BACKSTOP: an immortal recorder must be ended by the bound",
			p.dropSession.active());
		assertFalse("and capture disarmed", (Boolean) field(p, "dropCapturing"));
		Map<String, Object> m = manifest(p);
		assertNotNull("the footage is still published", m);
		assertEquals("a backstop end can NEVER be COMPLETE", "INTERRUPTED", m.get("outcome"));
		assertEquals("and the manifest names the bound that fired",
			DropSessionRecorder.REASON_ORPHANED_STATE, m.get("outcome_reason"));
	}

	// ===== CASE 38 — THE CONTROL: a healthy session is never touched by the invariant =====

	/**
	 * Without this arm a backstop that simply ended every session after one poll would pass case
	 * 37. Two healthy states are driven: a live pile, which is a drop trade waiting for a customer
	 * and may legitimately run for many minutes, and an outstanding pending drop.
	 */
	@Test
	public void case38_aHealthySessionIsNeverEndedByTheInvariantBackstop() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);

		poll(p, 200);
		assertTrue("a live pile is a healthy session, however long it waits",
			p.dropSession.active());
		assertFalse("and no stop is armed while it is on the ground", p.dropSession.stopPending());

		// A pending drop with no pile yet is healthy too.
		Rig r2 = rig();
		AccountConnectPlugin p2 = r2.plugin;
		dropAction(p2);
		assertEquals(1, p2.dropSession.pendingDropCount());
		poll(p2, 3);
		assertTrue("an outstanding pending is healthy", p2.dropSession.active());

		// And the stopwatch does not accumulate across a healthy poll: the pile goes, the tail arms
		// the ordinary way, and the manifest says COMPLETE rather than naming a backstop.
		AccountConnectPlugin.DroppedGroundItem g = onlyTrackedPile(p);
		java.util.Deque<AccountConnectPlugin.DroppedGroundItem> q = groundDrops(p);
		synchronized (q)
		{
			q.remove(g);
		}
		release(p, g);
		assertTrue("the ordinary tail still arms", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
		assertEquals("an ordinary end is COMPLETE, not a backstop end",
			"COMPLETE", manifest(p).get("outcome"));
		assertNull("and names no reason", manifest(p).get("outcome_reason"));
	}

	// ===== CASE 39 — the HARD CAP ends a session that is otherwise perfectly healthy =====

	/**
	 * The second bound, and it is deliberately the one the invariant cannot reach: a live pile on
	 * the ground the whole time, so every other check reads this session as healthy. Only the
	 * clock ends it.
	 *
	 * IT KEEPS THE DISCLOSURE TRUE. Both strings promise recording continues UNTIL five seconds
	 * after the final dropped pile disappears. A cap can only ever end a recording EARLIER than
	 * that, never later, so it removes recording the user was told about and adds none.
	 */
	@Test
	public void case39_theHardCapEndsAnOtherwiseHealthySession() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		dropAction(p);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		assertEquals("a live pile, so the invariant can never fire",
			1, p.dropSession.activePileCount());

		poll(p, 5);
		assertTrue("well inside the cap, the session runs", p.dropSession.active());

		// Rewind the session's start past MAX_SESSION_MILLIS, rather than record for half an hour.
		Field started = DropSessionRecorder.class.getDeclaredField("startedAtMillis");
		started.setAccessible(true);
		started.setLong(p.dropSession,
			started.getLong(p.dropSession) - DropSessionRecorder.MAX_SESSION_MILLIS);

		poll(p, 2);
		assertFalse("THE HARD CAP: no recording may outlive it", p.dropSession.active());
		assertEquals("a capped session can NEVER be COMPLETE",
			"INTERRUPTED", manifest(p).get("outcome"));
		assertEquals("and the manifest names the cap",
			DropSessionRecorder.REASON_MAX_DURATION, manifest(p).get("outcome_reason"));
		assertTrue("the cap must sit comfortably above a real 20-minute drop trade",
			DropSessionRecorder.MAX_SESSION_MILLIS >= 30L * 60L * 1_000L);
	}

	// ================================================================
	// CASE 40 — ROUND 5, FINDING E2: A 403 ON A DROP SEGMENT IS A REVOCATION
	//
	// /store-frames-ingest answers a revoked link with 403, from revocationBlock, which runs before
	// anything is stored — so no data reaches the server and this is not a disclosure. What DID
	// happen is that the recorder kept capturing the rendered screen and kept posting segments that
	// all got the same 403, until the player logged out. Recording locally with nowhere to send is
	// exactly what a withdrawn grant is supposed to stop.
	//
	// The 403 clears the rollout flag rather than tearing the session down on the OkHttp callback
	// thread, so the next pollDropSession runs the SAME withdrawal path an X-Drop-Proof: off header
	// runs. One revocation path, not two.
	// ================================================================

	@Test
	public void case40_a403OnADropSegmentEndsTheSessionAndA404DoesNot() throws Exception
	{
		assertGrantAfterUploadStatus(403, false,
			"FINDING E2: a 403 is a revocation and must withdraw the grant");
		assertGrantAfterUploadStatus(404, true,
			"CONTROL: an ordinary 4xx is a segment failure and must NOT withdraw the grant");
		assertGrantAfterUploadStatus(400, true,
			"CONTROL: nor does a 400");
	}

	/** Drive one real upload against a local server answering {@code status}, then read the grant. */
	private void assertGrantAfterUploadStatus(int status, boolean expectGrantHeld, String why)
		throws Exception
	{
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
			new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		final java.util.concurrent.CountDownLatch hit = new java.util.concurrent.CountDownLatch(1);
		server.createContext("/", exchange ->
		{
			byte[] buf = new byte[4096];
			while (exchange.getRequestBody().read(buf) > 0)
			{
				// drain, so the client's write completes before the response
			}
			byte[] body = "{\"ok\":false,\"error\":\"This link has been revoked.\"}"
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
			hit.countDown();
		});
		server.start();
		java.util.concurrent.ScheduledExecutorService exec =
			java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
		try
		{
			Rig r = rig();
			AccountConnectPlugin p = r.plugin;
			Field ex = AccountConnectPlugin.class.getDeclaredField("executor");
			ex.setAccessible(true);
			ex.set(p, exec);
			Field ok = AccountConnectPlugin.class.getDeclaredField("okHttpClient");
			ok.setAccessible(true);
			ok.set(p, new okhttp3.OkHttpClient());

			// A real session, recording, exactly as it would be when the revocation lands.
			dropAction(p);
			p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
			assertTrue("CONTROL: the grant is held before the upload", p.dropProofEnabled());
			assertTrue(p.dropSession.active());

			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			Method m = AccountConnectPlugin.class.getDeclaredMethod("postDropSegment", String.class,
				String.class, DropFrameSegmenter.Segment.class, String.class, int.class);
			m.setAccessible(true);
			m.invoke(p, base, TOKEN, takeOneSegment(), "sid", 0);

			assertTrue("the server never received the upload",
				hit.await(15, java.util.concurrent.TimeUnit.SECONDS));

			// The flag is written on the OkHttp callback thread, so wait for the counter first.
			java.util.concurrent.atomic.AtomicInteger failed =
				(java.util.concurrent.atomic.AtomicInteger) field(p, "dropSegmentsFailed");
			long deadline = System.currentTimeMillis() + 15_000L;
			while (System.currentTimeMillis() < deadline && failed.get() == 0)
			{
				Thread.sleep(20L);
			}
			assertEquals("the segment is counted failed either way", 1, failed.get());
			assertEquals(why, expectGrantHeld, p.dropProofEnabled());

			// And when the grant is gone, the next poll runs the ordinary withdrawal path.
			poll(p, 1);
			assertEquals(why + " — and the session follows the grant",
				expectGrantHeld, p.dropSession.active());
		}
		finally
		{
			exec.shutdownNow();
			server.stop(0);
		}
	}

	// ================================================================
	// CASES 41-45 — ROUND 6, FINDING R1: THE PHANTOM RECORD
	//
	// The merge branch in trackGroundDrop is scoped to a running session, so with NO session a
	// stackable dropped twice onto one tile is still TWO records for ONE physical pile. The single
	// despawn consumed one and left the other on a tile that is now physically empty: a PHANTOM.
	// Nothing removes it, because a teardown only untracks the session's own piles and no despawn
	// will ever come for a pile that is not there.
	//
	// A later session dropping on that tile then lost its OWN despawn to the phantom, because the
	// resolver returned the OLDEST match. Its recorder key was never released, the tail never armed,
	// and only MAX_SESSION_MILLIS ended the recording — up to 30 minutes, where both user-facing
	// strings promise five seconds after the final pile.
	//
	// THE FIX IS THE RESOLUTION ORDER, and these arms are written against the OUTCOME. Each one
	// asserts the tail ARMS ON ITS OWN and the session ends, and NONE of them calls forceTailDue
	// before that assertion: forceTailDue writes stopAtMillis straight into the recorder, which is
	// exactly what a stranded recorder cannot do for itself.
	//
	// THEY DO NOT LEAN ON THE BACKSTOP EITHER. Every poll count here is far below
	// ORPHANED_STATE_GRACE_MILLIS, and the invariant is structurally blind to this state anyway: the
	// session still owns the record whose physical pile is gone, so dropSessionHoldsOwnedPile reads
	// healthy on every poll.
	//
	// CASE 44 is the other side. It kills a resolver that prefers the NEWEST record instead of the
	// session's own. CASE 45 pins the no-session order, which the ground-removal arms depend on.
	// ================================================================

	/** Two records for ONE physical pile, made with no session running, then one despawn. */
	private static void seedPhantomRecordWithNoSession(AccountConnectPlugin p) throws Exception
	{
		assertFalse("the phantom is seeded with NO session running", p.dropSession.active());
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.trackGroundDrop(995, 1_000L, 3200, 3400, 0, null, 101, 400, true);
		assertEquals("the merge branch is scoped out, so one real stack is tracked twice",
			2, p.groundDropCount());

		// The customer takes the stack. The game fires exactly ONE ItemDespawned for it.
		despawnTrackedPileAt(p, 995, 3200, 3400);
		assertEquals("the tile is now physically EMPTY and one record lingers",
			1, p.groundDropCount());
		assertNull("and the lingering record belongs to no session",
			p.dropSessionForPile(onlyTrackedPile(p)));
	}

	// ===== CASE 41 — a phantom left by an earlier no-session drop, then a session on that tile =====

	@Test
	public void case41_aSessionDroppingOntoAPhantomsTileStillEndsOnItsOwnTail() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		p.setDropProofRolloutForTest(false);
		seedPhantomRecordWithNoSession(p);

		// The grant lands and an ordinary drop trade runs on that same tile. The pile SPAWNS, so
		// the merge branch is never even reached and the phantom is untouched by it.
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(995, 700L, 3200, 3400, 0, null, 300, 400, false);
		assertEquals("the session owns exactly one key", 1, p.dropSession.activePileCount());

		// The resolver's own answer, read BEFORE the despawn consumes it, asserted after the
		// outcome below. Held here because the record is gone once the despawn is driven.
		AccountConnectPlugin.GroundDespawnMatch match =
			p.matchDespawnedGroundDrop(995, 3200, 3400, 0);

		// The customer takes it. The ONE despawn must resolve to the SESSION'S record, not the
		// older phantom, or the key is never released.
		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 50);

		assertEquals("FINDING R1: the despawn must release the session's own key",
			0, p.dropSession.activePileCount());
		assertEquals("and nothing is left pending", 0, p.dropSession.pendingDropCount());
		assertTrue("NO 30-MINUTE OVERRUN: the tail MUST arm on its own",
			p.dropSession.stopPending());

		forceTailDue(p);
		poll(p, 1);
		assertFalse("and the armed tail really ends the recording", p.dropSession.active());
		assertFalse("and the screen is no longer captured", (Boolean) field(p, "dropCapturing"));

		// ROUND 7. The same resolution, pinned at the resolver. The phantom is a merge SHADOW: one
		// physical pile tracked twice, standing for no pile of its own, so it is NOT a rival and
		// this choice is not ambiguous. A fix for R2 that treated it as one would strand this
		// recorder again, which is R1 coming straight back.
		assertNotNull(match.record);
		assertEquals("the resolver must take the SESSION'S record, not the older phantom",
			700L, match.record.qty);
		assertFalse("FINDING R1 STAYS CLOSED: a phantom is not a second physical pile",
			match.ambiguous);
	}

	// ===== CASE 42 — the phantom strands the session INSIDE one ordinary two-drop trade =====

	/**
	 * The narrowest route: the staff member drops before the grant arrives, the grant lands, and
	 * the recorded trade is two drops onto the same tile. No session ever ends in between.
	 */
	@Test
	public void case42_aTwoDropTradeOverAPhantomStillEndsOnItsOwnTail() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		p.setDropProofRolloutForTest(false);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.trackGroundDrop(995, 1_000L, 3200, 3400, 0, null, 101, 400, true);
		assertEquals("one physical pile, two records", 2, p.groundDropCount());

		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();

		// DROP 1 of the recorded trade merges into that same pile and adopts a record.
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(995, 1_500L, 3200, 3400, 0, null, 200, 400, true);
		assertEquals("the merge adds no third record", 2, p.groundDropCount());
		assertEquals("and the session holds one key", 1, p.dropSession.activePileCount());

		// The customer takes it: ONE despawn. A phantom record survives on an empty tile.
		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 2);
		assertTrue("the first take arms the tail", p.dropSession.stopPending());
		assertEquals("and leaves the phantom behind", 1, p.groundDropCount());

		// DROP 2, inside the tail. Physically a NEW pile on the same tile.
		dropAction(p);
		p.trackGroundDrop(995, 700L, 3200, 3400, 0, null, 300, 400, false);
		assertEquals("the second drop re-arms the session", 1, p.dropSession.activePileCount());
		assertFalse("so the tail is disarmed again", p.dropSession.stopPending());

		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 50);

		assertEquals("FINDING R1: the second despawn must release the session's own key",
			0, p.dropSession.activePileCount());
		assertTrue("NO 30-MINUTE OVERRUN inside one trade: the tail MUST re-arm on its own",
			p.dropSession.stopPending());

		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
	}

	// ===== CASE 43 — the phantom outlives a CLEAN session end and strands the NEXT session =====

	@Test
	public void case43_aPhantomSurvivingACleanSessionEndDoesNotStrandTheNextOne() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		p.setDropProofRolloutForTest(false);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.trackGroundDrop(995, 1_000L, 3200, 3400, 0, null, 101, 400, true);

		// SESSION ONE. It merges into the pile, the customer takes it, and it ends normally.
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(995, 1_500L, 3200, 3400, 0, null, 200, 400, true);
		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 5);
		assertTrue("session one arms its tail", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse("session one is over", p.dropSession.active());
		assertEquals("but the phantom outlives it", 1, p.groundDropCount());
		assertNull("unowned, so no teardown removed it",
			p.dropSessionForPile(onlyTrackedPile(p)));

		// SESSION TWO, on that same tile, with the phantom already sitting there.
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 2}, 2_000L);
		p.trackGroundDrop(995, 700L, 3200, 3400, 0, null, 300, 400, false);
		assertTrue("a second session is running", p.dropSession.active());
		assertEquals("holding one key", 1, p.dropSession.activePileCount());

		despawnTrackedPileAt(p, 995, 3200, 3400);
		poll(p, 50);

		assertEquals("FINDING R1: a phantom from an earlier session must not absorb this despawn",
			0, p.dropSession.activePileCount());
		assertTrue("NO 30-MINUTE OVERRUN across sessions: the tail MUST arm on its own",
			p.dropSession.stopPending());

		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
	}

	// ===== CASE 44 — TWO REAL PILES, THE SESSION'S THE OLDER ONE: still a refusal =====

	/**
	 * ROUND 7 REWROTE THIS ARM, and the rewrite is finding R2 itself.
	 *
	 * Round 6 wrote this case to kill a resolver that simply preferred the NEWEST record, and it
	 * asserted that the despawn RELEASES the session's key. That assertion was wrong, and it is
	 * exactly the defect R2 names: the route puts TWO REAL PILES of an unstackable on one tile, only
	 * one of them the session's, so which pile the game just removed is unknowable. Releasing the
	 * key there stops the recording while the session's own pile may still be lying on the ground.
	 *
	 * The route is unchanged, on purpose, so the two rounds can be compared line for line: the
	 * session's own pile spawns first, a second Drop's pending expires, and its pile lands unowned
	 * and NEWER because attachPileToDropSession refuses it with pendingDropSeq at 0.
	 *
	 * WHAT THIS COSTS. Round 6's anti-prefer-the-newest mutation no longer has a discriminating arm,
	 * and it cannot have one: after the R2 fix the ONLY unowned record that does not make a despawn
	 * ambiguous is a merge SHADOW, and a merge shadow can only be minted while no session owns a
	 * record on that tile, so it is always the OLDER of the two. "Prefer the session's own" and
	 * "prefer the newest" now differ only on states the plugin cannot reach. Case 41 pins the
	 * resolver's answer directly instead, which is the stronger assertion of the two.
	 */
	@Test
	public void case44_anOlderSessionPileAgainstANewerRealPileIsStillAmbiguous() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 1L, 3200, 3400, 0, null, 100, 400, false);
		assertEquals("the session owns its pile", 1, p.dropSession.activePileCount());

		// A second Drop whose pending expires before its pile lands. The pile is tracked and
		// unowned, and it is NEWER than the session's own record.
		dropAction(p);
		forcePendingsExpired(p);
		poll(p, 1);
		p.trackGroundDrop(1931, 1L, 3200, 3400, 0, null, 130, 400, false);
		assertEquals("two real piles on one tile", 2, p.groundDropCount());
		assertEquals("and the session still holds exactly one key",
			1, p.dropSession.activePileCount());

		despawnTrackedPileAt(p, 1931, 3200, 3400);
		poll(p, 50);

		assertTrue("FINDING R2: the recording must still be running", p.dropSession.active());
		assertFalse("NO EARLY STOP: the tail must not arm while the session's pile may be down",
			p.dropSession.stopPending());
		assertEquals("FINDING R2: the session's key is KEPT", 1, p.dropSession.activePileCount());
		assertTrue("and the session can never be proven complete", p.dropSession.unprovable());

		assertEquals("the record the resolver took is the one left gone", 1, p.groundDropCount());
		assertNotNull("so what is left tracked is the session's own",
			p.dropSessionForPile(onlyTrackedPile(p)));
	}

	// ===== CASE 45 — THE CONTROL: with no session, the OLDEST record still answers =====

	/**
	 * The no-session order is what the ground-removal arms pin, and the fix must not move it. With
	 * no session there is no owner, so the resolver falls straight through to the oldest match and
	 * the two-records-for-one-stack behaviour is exactly as it was.
	 */
	@Test
	public void case45_withNoSessionTheOldestRecordStillAnswersTheDespawn() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		p.setDropProofRolloutForTest(false);

		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.trackGroundDrop(995, 1_000L, 3200, 3400, 0, null, 101, 400, true);
		assertEquals("one real stack is tracked twice, as it always was", 2, p.groundDropCount());
		assertFalse("and no session exists", p.dropSession.active());

		Method find = AccountConnectPlugin.class.getDeclaredMethod(
			"findGroundDrop", int.class, int.class, int.class, int.class);
		find.setAccessible(true);
		AccountConnectPlugin.DroppedGroundItem resolved =
			(AccountConnectPlugin.DroppedGroundItem) find.invoke(p, 995, 3200, 3400, 0);
		assertNotNull(resolved);
		assertEquals("with no session the OLDEST record answers, unchanged", 500L, resolved.qty);
	}

	// ================================================================
	// CASES 46-50 — ROUND 7, FINDING R2: THE AMBIGUOUS DESPAWN
	//
	// Round 6 closed R1 by making the resolver prefer the running session's own record. On a tile
	// holding TWO REAL PILES of the same UNSTACKABLE, only one of them the session's, that
	// preference answered the despawn of the OTHER pile with OUR record. Three things went wrong at
	// once: the session's key was released while its pile was still on the ground, the recording
	// stopped five seconds later and the manifest said COMPLETE on a clip missing the collection,
	// and the published row carried our session id, our sequence, our quantity and our time on
	// ground for a pile that was never ours.
	//
	// WHICH PILE WENT IS UNKNOWABLE. The game reports an item id leaving a tile, not which of two
	// identical piles it was. Round 5 guessed "the oldest" and round 6 guessed "ours". Both guess.
	//
	// THE FIX REFUSES. An owned and an unowned REAL record both matching is detected, the row falls
	// back to the unowned record with NO session linkage, the session's key is KEPT because its pile
	// may still be down, and the session is marked unprovable so COMPLETE is out of reach forever.
	//
	// A MERGE SHADOW IS NOT A RIVAL PILE, which is what keeps R1 closed. The R1 phantom is one
	// physical pile tracked twice because the merge branch is scoped to a running session, so the
	// second record was minted from a quantityMerge and stands for no pile of its own. Cases 41-43
	// are the arms that fail if that distinction is dropped.
	//
	// THESE ARMS ASSERT THE OUTCOME FIRST: the recording is still running with a session pile still
	// tracked, the outcome is never COMPLETE, and the row carries no session linkage. The
	// bookkeeping assertions come after.
	// ================================================================

	/**
	 * The route, in one helper: an unstackable pile lands before the grant, so it is REAL and
	 * unowned, and the session then drops its own real pile of the same unstackable on that tile.
	 */
	private static void seedTwoRealUnstackablePiles(AccountConnectPlugin p) throws Exception
	{
		p.setDropProofRolloutForTest(false);
		p.trackGroundDrop(1931, 1L, 3200, 3400, 0, null, 100, 400, false);
		assertFalse("the pre-grant pile is laid down with no session running",
			p.dropSession.active());

		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 1L, 3200, 3400, 0, null, 200, 400, false);
		assertEquals("two REAL piles of an unstackable are two records", 2, p.groundDropCount());
		assertEquals("and the session owns exactly one of them", 1, p.dropSession.activePileCount());
	}

	// ===== CASE 46 — the recording MUST keep running while the session's pile is still down =====

	/**
	 * Ported from the review's ProbeSteal2. On 159aa8a the one despawn released the session's key,
	 * the tail armed and the recording stopped five seconds later with the session's own pile still
	 * physically on the ground.
	 */
	@Test
	public void case46_anAmbiguousDespawnNeverStopsARecordingWhoseOwnPileIsStillDown()
		throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		seedTwoRealUnstackablePiles(p);

		// The customer takes ONE of the two piles. Which one is unknowable.
		despawnTrackedPileAt(p, 1931, 3200, 3400);
		poll(p, 50);

		// THE OUTCOME, asserted first.
		assertTrue("FINDING R2: the recording must still be running", p.dropSession.active());
		assertTrue("and the screen must still be captured", (Boolean) field(p, "dropCapturing"));
		assertFalse("NO EARLY STOP: the tail must NOT arm while a session pile is still tracked",
			p.dropSession.stopPending());
		assertEquals("because the session's key is KEPT: its pile may still be down",
			1, p.dropSession.activePileCount());
		assertTrue("and the caller still tracks a pile for this session",
			p.dropSessionHoldsOwnedPile());

		// The bookkeeping behind it.
		assertTrue("FINDING R2: the session can never be proven complete from here",
			p.dropSession.unprovable());
		assertEquals("and it names the ambiguity",
			DropSessionRecorder.REASON_AMBIGUOUS_DESPAWN, p.dropSession.reason());
		assertEquals("one real pile is left on the ground", 1, p.groundDropCount());
		assertNotNull("and it is the session's own",
			p.dropSessionForPile(onlyTrackedPile(p)));
	}

	// ===== CASE 47 — the manifest may never say COMPLETE on that session =====

	/**
	 * Ported from the review's ProbeManifest. The session ends on the SECOND despawn, which is no
	 * longer ambiguous because only one record is left. It ends on its own tail, so nothing here
	 * leans on a backstop, and the outcome must still be INTERRUPTED.
	 */
	@Test
	public void case47_aSessionWithAnAmbiguousDespawnIsNeverCOMPLETE() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		seedTwoRealUnstackablePiles(p);

		despawnTrackedPileAt(p, 1931, 3200, 3400);
		poll(p, 5);
		assertTrue("the recording is still running after the ambiguous take",
			p.dropSession.active());

		// The customer takes the second pile too. Only one record is left, so this one is certain.
		despawnTrackedPileAt(p, 1931, 3200, 3400);
		poll(p, 2);
		assertEquals("the last key is released", 0, p.dropSession.activePileCount());
		assertTrue("so the session ends on ITS OWN tail, not on a backstop",
			p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse("and the recording really ends", p.dropSession.active());

		Map<String, Object> m = manifest(p);
		assertNotNull("the footage is still published", m);
		assertEquals("FINDING R2: a session that could not tell which pile went is NOT complete",
			"INTERRUPTED", m.get("outcome"));
		assertEquals("and the manifest names why",
			DropSessionRecorder.REASON_AMBIGUOUS_DESPAWN, m.get("outcome_reason"));
	}

	// ===== CASE 48 — the published row carries NO fabricated attribution =====

	/**
	 * Ported from the review's ProbeAttrib. On 159aa8a the row for the OTHER player's pile carried
	 * our drop_session_id, our drop_seq, our recorded qty and our ticks_on_ground.
	 */
	@Test
	public void case48_anAmbiguousDespawnPublishesNoSessionLinkage() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		// Distinct recorded quantities, so the row names which record answered.
		p.setDropProofRolloutForTest(false);
		p.trackGroundDrop(1931, 11L, 3200, 3400, 0, null, 100, 400, false);
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 400, false);
		assertEquals("two REAL piles", 2, p.groundDropCount());

		AccountConnectPlugin.DroppedGroundItem chosen =
			despawnAndPublishAt(p, 1931, 3200, 3400, 500);

		Map<String, Object> row = lastRemoval(p);
		assertNotNull(row);
		assertNull("FINDING R2: no fabricated session linkage on an ambiguous removal",
			row.get("drop_session_id"));
		assertNull("and no fabricated drop sequence", row.get("drop_seq"));

		// And the attribution itself went back to the pre-round-6 answer: the unowned record.
		assertEquals("ATTRIBUTION falls back to the other pile's OWN record", 11L, chosen.qty);
		assertNull("which belongs to no session", p.dropSessionForPile(chosen));
		assertEquals("so the row reports that record's quantity", 11L, row.get("qty"));
		assertEquals("and that record's time on the ground", 400, row.get("ticks_on_ground"));

		// The recorder is untouched by the row: its pile may still be down.
		assertTrue("the recording continues", p.dropSession.active());
		assertEquals("holding its own key", 1, p.dropSession.activePileCount());
	}

	// ===== CASE 49 — the realistic five-plus-five trade =====

	/**
	 * Ported from the review's ProbeBulk. Five unstackables land before the grant, five after it,
	 * and the customer takes all ten. On 159aa8a the recording stopped after the FIFTH take with
	 * five piles still on the ground, and the manifest said COMPLETE drops=5.
	 */
	@Test
	public void case49_aTenPileTradeKeepsRecordingUntilTheLastPileGoes() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;

		p.setDropProofRolloutForTest(false);
		for (int i = 0; i < 5; i++)
		{
			p.trackGroundDrop(1931, 1L, 3200, 3400, 0, null, 100 + i, 900, false);
		}
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();
		for (int i = 0; i < 5; i++)
		{
			dropAction(p);
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L + i);
			p.trackGroundDrop(1931, 1L, 3200, 3400, 0, null, 200 + i, 900, false);
		}
		assertEquals("ten REAL piles on one tile", 10, p.groundDropCount());
		assertEquals("five of them the session's", 5, p.dropSession.activePileCount());

		// The customer takes them one at a time.
		for (int take = 1; take <= 10; take++)
		{
			despawnAndPublishAt(p, 1931, 3200, 3400, 300 + take);
			poll(p, 2);
			if (take < 10)
			{
				assertTrue("FINDING R2: take " + take + " of 10 must not end the recording while "
						+ p.groundDropCount() + " piles are still on the ground",
					p.dropSession.active());
			}
		}

		// Only the tenth take, which empties the tile, may arm the tail.
		assertEquals("every pile is gone", 0, p.groundDropCount());
		assertEquals("and every key with it", 0, p.dropSession.activePileCount());
		assertTrue("only now does the tail arm", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());

		Map<String, Object> m = manifest(p);
		assertEquals("FINDING R2: a trade this ambiguous is never COMPLETE",
			"INTERRUPTED", m.get("outcome"));
		assertEquals("all five session drops are still counted", 5, m.get("drops"));

		// THE ROWS. The first five takes each had an owned and an unowned real candidate, so each
		// refuses to name a session. By the sixth take the five unowned piles are gone and only the
		// session's own remain, so those removals ARE ours and keep their honest linkage. Refusing
		// attribution where it is unknowable must not become refusing it everywhere.
		java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
		for (Map<String, Object> e : p.pendingEvents)
		{
			if ("ground_removed".equals(e.get("type")))
			{
				rows.add(e);
			}
		}
		assertEquals("ten rows were published", 10, rows.size());
		for (int i = 0; i < 5; i++)
		{
			assertNull("FINDING R2: row " + (i + 1) + " was ambiguous and must name no session",
				rows.get(i).get("drop_session_id"));
			assertNull("nor a drop sequence", rows.get(i).get("drop_seq"));
		}
		for (int i = 5; i < 10; i++)
		{
			assertNotNull("row " + (i + 1) + " had no rival pile left, so its linkage is honest",
				rows.get(i).get("drop_session_id"));
		}
	}

	// ===== CASE 50 — THE CONTROLS: a stackable merge and an unambiguous single-pile session =====

	/**
	 * Ported from the review's ProbeStackCtl, plus the plain happy path.
	 *
	 * Without these a fix that called EVERY despawn ambiguous would pass cases 46-49. A stackable
	 * merges in the game, so one physical pile is one record and one key: there is no second pile
	 * and nothing to be ambiguous about. And an ordinary single-pile session must still end on its
	 * own tail with outcome COMPLETE.
	 */
	@Test
	public void case50_aStackableMergeAndAPlainSessionAreNeverAmbiguous() throws Exception
	{
		// CONTROL A — a real unowned STACKABLE pile, then the session drops the same stackable on
		// it. The game merges, so trackGroundDrop is called with quantityMerge=true and adopts.
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		p.setDropProofRolloutForTest(false);
		p.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 100, 400, false);
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(995, 1_200L, 3200, 3400, 0, null, 200, 400, true);
		assertEquals("CONTROL: a stackable merge is ONE record", 1, p.groundDropCount());
		assertEquals("and ONE key", 1, p.dropSession.activePileCount());

		despawnAndPublishAt(p, 995, 3200, 3400, 500);
		poll(p, 2);
		assertEquals("CONTROL: the one despawn releases the key", 0, p.dropSession.activePileCount());
		assertTrue("CONTROL: and the tail arms, because nothing was ambiguous",
			p.dropSession.stopPending());
		assertFalse("CONTROL: nothing marked this session unprovable", p.dropSession.unprovable());
		assertNotNull("CONTROL: and the row keeps its honest session linkage",
			lastRemoval(p).get("drop_session_id"));
		forceTailDue(p);
		poll(p, 1);
		assertEquals("CONTROL: a merge-only session is still COMPLETE",
			"COMPLETE", manifest(p).get("outcome"));

		// CONTROL B — one unstackable pile, one session, nothing else on the tile.
		Rig r2 = rig();
		AccountConnectPlugin p2 = r2.plugin;
		dropAction(p2);
		p2.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p2.trackGroundDrop(1931, 1L, 3200, 3400, 0, null, 100, 400, false);
		despawnAndPublishAt(p2, 1931, 3200, 3400, 200);
		poll(p2, 2);
		assertTrue("CONTROL: an unambiguous single-pile session arms its own tail",
			p2.dropSession.stopPending());
		assertFalse("CONTROL: and is never marked unprovable", p2.dropSession.unprovable());
		forceTailDue(p2);
		poll(p2, 1);
		assertFalse(p2.dropSession.active());
		assertEquals("CONTROL: it ends COMPLETE", "COMPLETE", manifest(p2).get("outcome"));
		assertNull("naming no reason", manifest(p2).get("outcome_reason"));
	}

	// ================================================================
	// ROUND 8 — THE SCENE IS THE SOURCE OF TRUTH. Cases 51-57.
	//
	// Every round from 1 to 7 inferred "is our pile still on the ground?" from our own event
	// bookkeeping, and every round a pile the bookkeeping never knew about broke it. groundDrops
	// only ever holds OWNERSHIP_SELF piles that had an armed drop pending, so three REAL piles are
	// invisible to it: another player's pile, our own pile from a session that ended while the pile
	// was still down, and a pile dropped before the token was linked.
	//
	// These arms model the GAME'S WORLD STATE separately from what the plugin tracked, which is the
	// only way to express the defect at all. FakeScene is the physical ground; trackGroundDrop is
	// the bookkeeping. An untracked rival is a FakeScene pile with no record behind it.
	// ================================================================

	/**
	 * One PHYSICAL pile lying on a tile, whether or not the plugin ever tracked it.
	 *
	 * ROUND 9. A pile carries its QUANTITY, and it can state that quantity when it is the one
	 * despawning. The live path reads it from {@code net.runelite.api.TileItem.getQuantity()};
	 * implementing {@link AccountConnectPlugin.DespawnQuantity} is how the harness answers the same
	 * question without implementing the whole TileItem interface.
	 */
	private static final class FakePile implements AccountConnectPlugin.DespawnQuantity
	{
		final int item;
		final int x;
		final int y;
		final int plane;
		final long qty;
		final String label;

		FakePile(String label, int item, int x, int y, int plane, long qty)
		{
			this.label = label;
			this.item = item;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.qty = qty;
		}

		@Override
		public long despawnedQuantity()
		{
			return qty;
		}
	}

	/**
	 * The game's own ground state, as the scene reader sees it.
	 *
	 * Four things the arms need to model, which the review named: an untracked rival present, a
	 * rival removed, OUR pile removed, and a tile OUT OF SCENE. The last one is separate from an
	 * empty tile on purpose: collapsing them is mutation (c).
	 */
	private static final class FakeScene implements AccountConnectPlugin.SceneGroundReader
	{
		final java.util.List<FakePile> piles = new java.util.ArrayList<>();
		final java.util.Set<String> outOfScene = new java.util.LinkedHashSet<>();

		FakePile lay(String label, int item, int x, int y)
		{
			return lay(label, item, x, y, 1L);
		}

		/** ROUND 9. A pile whose QUANTITY the scene can state, which is what names it at despawn. */
		FakePile lay(String label, int item, int x, int y, long qty)
		{
			FakePile f = new FakePile(label, item, x, y, 0, qty);
			piles.add(f);
			return f;
		}

		void remove(FakePile f)
		{
			piles.remove(f);
		}

		void leaveScene(int x, int y, int plane)
		{
			outOfScene.add(x + ":" + y + ":" + plane);
		}

		/**
		 * ROUND 9. COUNTS the piles and reports their quantities, exactly as the live reader now
		 * does. Round 8 returned on the first match, which is the shape FINDING R7 lived in.
		 */
		@Override
		public AccountConnectPlugin.SceneTileItems itemsOnTile(int item, int x, int y, int plane,
			Object excluding)
		{
			if (outOfScene.contains(x + ":" + y + ":" + plane))
			{
				return AccountConnectPlugin.SceneTileItems.unknown();
			}
			java.util.List<Long> q = new java.util.ArrayList<>();
			for (FakePile f : piles)
			{
				if (f != excluding && f.item == item && f.x == x && f.y == y && f.plane == plane)
				{
					q.add(f.qty);
				}
			}
			long[] qs = new long[q.size()];
			for (int i = 0; i < qs.length; i++)
			{
				qs[i] = q.get(i);
			}
			return AccountConnectPlugin.SceneTileItems.of(qs.length, qs);
		}
	}

	/**
	 * The game fires ONE ItemDespawned for {@code going}, and the plugin resolves it exactly as
	 * onItemDespawned does, publishing the row when a record answers.
	 *
	 * @param alreadyGoneFromScene models the ORDERING we cannot verify without a live client. true
	 *                             means the client removed the TileItem from the tile before posting
	 *                             the event; false means it has not yet. The resolver excludes the
	 *                             despawning TileItem by identity, so both orders must agree.
	 * @return the record the resolver chose, or null when it refused the despawn.
	 */
	private static AccountConnectPlugin.DroppedGroundItem despawnInScene(AccountConnectPlugin p,
		FakeScene scene, FakePile going, int tick, boolean alreadyGoneFromScene) throws Exception
	{
		if (alreadyGoneFromScene)
		{
			scene.remove(going);
		}
		AccountConnectPlugin.DroppedGroundItem g =
			p.resolveDespawnedGroundDrop(going.item, going.x, going.y, going.plane, going);
		if (!alreadyGoneFromScene)
		{
			scene.remove(going);
		}
		if (g == null)
		{
			return null;	// the resolver refused this despawn: no record removed, no row published
		}
		java.util.Deque<AccountConnectPlugin.DroppedGroundItem> q = groundDrops(p);
		synchronized (q)
		{
			q.remove(g);
		}
		p.emitGroundRemoval(g, tick, false);
		return g;
	}

	/** A rig whose scene reader is a FakeScene, so the arms control the physical ground. */
	private static FakeScene withScene(AccountConnectPlugin p)
	{
		FakeScene s = new FakeScene();
		p.setSceneReaderForTest(s);
		return s;
	}

	// ===== CASE 51 — FINDING R4: an UNTRACKED real rival pile, with its no-rival control =====

	/**
	 * Ported from the review's R5Foreign. On abdf227 this produced a false COMPLETE: the resolver
	 * saw exactly one matching record, OURS, called the despawn unambiguous, released the key,
	 * stopped five seconds later with our pile still on the ground, and published our session id
	 * and our quantity on a row describing the OTHER pile.
	 *
	 * The ARM and the CTRL were indistinguishable in every field the manifest and the row carried,
	 * which is the whole defect: a reader could not tell the honest COMPLETE from the false one.
	 */
	@Test
	public void case51_anUntrackedRealRivalPileMakesTheDespawnAmbiguous() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		// The physical ground: a REAL rival pile of item 1931 that the plugin NEVER tracked.
		FakePile rival = scene.lay("RIVAL", 1931, 3200, 3400);

		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		FakePile ours = scene.lay("OURS", 1931, 3200, 3400);
		assertEquals("the plugin tracks only OUR pile", 1, p.groundDropCount());
		assertEquals("and holds one key", 1, p.dropSession.activePileCount());
		assertFalse("the RECORD-only matcher still sees no rival at all",
			p.matchDespawnedGroundDrop(1931, 3200, 3400, 0).ambiguous);

		// The customer takes the RIVAL. Our pile is still physically down.
		AccountConnectPlugin.DroppedGroundItem chosen =
			despawnInScene(p, scene, rival, 500, true);
		poll(p, 50);

		// THE OUTCOME, asserted first.
		assertTrue("FINDING R4: NO EARLY STOP while our pile is physically on the ground",
			p.dropSession.active());
		assertTrue("and the screen is still being captured", (Boolean) field(p, "dropCapturing"));
		assertFalse("and the tail must NOT arm", p.dropSession.stopPending());
		assertTrue("FINDING R4: COMPLETE is out of reach", p.dropSession.unprovable());
		assertNull("FINDING R4: no row may be published from our record for another pile's despawn",
			lastRemoval(p));
		// The mechanism behind it.
		assertNull("the despawn itself is refused, because our pile may still be the one down",
			chosen);
		assertEquals("the session KEEPS its key", 1, p.dropSession.activePileCount());

		// The customer then takes OUR pile too, and the tile is finally clear.
		AccountConnectPlugin.DroppedGroundItem mine = despawnInScene(p, scene, ours, 600, true);
		assertNotNull("with nothing left on the tile, our own despawn resolves normally", mine);
		poll(p, 2);
		assertEquals("the key is released", 0, p.dropSession.activePileCount());
		assertTrue("so the session ends on its own tail", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
		assertEquals("FINDING R4: a session that shared its tile with an untracked pile is NOT complete",
			"INTERRUPTED", manifest(p).get("outcome"));

		// THE CONTROL — the identical session with nothing else on the tile. Without this, a fix
		// that called every despawn ambiguous would pass the arm above.
		Rig r2 = rig();
		AccountConnectPlugin p2 = r2.plugin;
		FakeScene clean = withScene(p2);
		dropAction(p2);
		p2.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p2.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		FakePile only = clean.lay("OURS", 1931, 3200, 3400);
		AccountConnectPlugin.DroppedGroundItem got = despawnInScene(p2, clean, only, 500, true);
		poll(p2, 2);
		assertNotNull("CONTROL: an empty tile resolves the despawn normally", got);
		assertTrue("CONTROL: the tail arms", p2.dropSession.stopPending());
		assertFalse("CONTROL: nothing is unprovable", p2.dropSession.unprovable());
		assertNotNull("CONTROL: and the row keeps its honest linkage",
			lastRemoval(p2).get("drop_session_id"));
		forceTailDue(p2);
		poll(p2, 1);
		assertEquals("CONTROL: an honest COMPLETE is still reachable",
			"COMPLETE", manifest(p2).get("outcome"));
	}

	// ===== CASE 52 — FINDING R4 through the CONSENT-WITHDRAWAL route =====

	/**
	 * Ported from the review's R5Withdraw. This is the ordinary route, not an exotic one: the user
	 * unticks the upload switch while a pile is still down. discardDropSessionOnWithdrawnConsent
	 * runs endDropSessionTracking, which REMOVES the record and leaves the PILE. The switch goes
	 * back on, session 2 drops the same unstackable on the same tile, and the customer takes the
	 * OLD pile.
	 */
	@Test
	public void case52_aPileSurvivingAConsentWithdrawalStillMakesTheDespawnAmbiguous()
		throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		// SESSION 1 drops a real unstackable. The pile is never taken.
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 11L, 3200, 3400, 0, null, 100, 900, false);
		FakePile old = scene.lay("OLD", 1931, 3200, 3400);

		// The user unticks the upload switch.
		r.config.upload = false;
		poll(p, 1);
		assertFalse("the session ended on the withdrawal", p.dropSession.active());
		assertEquals("and its RECORD is gone", 0, p.groundDropCount());
		assertEquals("while the PILE is still physically on the ground", 1, scene.piles.size());

		// The switch goes back on and SESSION 2 drops on the same tile.
		r.config.upload = true;
		p.onDropProofCapabilityChanged();
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 2}, 2_000L);
		p.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		scene.lay("OURS", 1931, 3200, 3400);
		assertEquals("session 2 tracks only its own pile", 1, p.groundDropCount());

		// The customer takes the OLD pile.
		AccountConnectPlugin.DroppedGroundItem chosen = despawnInScene(p, scene, old, 500, true);
		poll(p, 50);

		assertTrue("FINDING R4: session 2 keeps recording, its own pile is still down",
			p.dropSession.active());
		assertFalse("NO EARLY STOP", p.dropSession.stopPending());
		assertTrue("and it can never be proven complete", p.dropSession.unprovable());
		assertNull("no row is published for a pile we cannot claim", lastRemoval(p));
		assertNull("the despawn itself is refused", chosen);
	}

	// ===== CASE 53 — FINDING R4 through the HOP / LOGOUT interrupt route =====

	/**
	 * Ported from the review's R5Untracked. A hop, a logout, a disconnect, a scene reload and the
	 * 30-minute cap all reach endDropSessionTracking, so they all leave a real pile untracked. On
	 * abdf227 this published cause=removed_early with SESSION 2's id on a row describing session 1's
	 * pile.
	 */
	@Test
	public void case53_aPileSurvivingAnInterruptStillMakesTheDespawnAmbiguous() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 11L, 3200, 3400, 0, null, 100, 900, false);
		FakePile old = scene.lay("OLD", 1931, 3200, 3400);
		p.interruptDropSession();
		assertFalse("session 1 ended on the hop", p.dropSession.active());
		assertEquals("its record is gone", 0, p.groundDropCount());
		assertEquals("its pile is not", 1, scene.piles.size());

		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 2}, 2_000L);
		p.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		scene.lay("OURS", 1931, 3200, 3400);

		AccountConnectPlugin.DroppedGroundItem chosen = despawnInScene(p, scene, old, 500, true);
		poll(p, 50);

		assertTrue("FINDING R4: session 2 keeps recording", p.dropSession.active());
		assertFalse("NO EARLY STOP", p.dropSession.stopPending());
		assertTrue("COMPLETE is out of reach", p.dropSession.unprovable());
		assertNull("and no fabricated row is published", lastRemoval(p));
		assertNull("the despawn itself is refused", chosen);
	}

	// ===== CASE 54 — FINDING R6: a WRONG mergeShadow flag must not reach COMPLETE =====

	/**
	 * Ported from the review's R5ShadowB, which forced the one state the review could not otherwise
	 * reach: the ONLY unowned record on the tile carries mergeShadow=true while standing for a REAL
	 * separate pile. On abdf227 a single wrong flag re-opened R2 in full — false COMPLETE, early
	 * stop, fabricated linkage — because mergeShadow was the only thing between the resolver and a
	 * guess.
	 *
	 * mergeShadow is KEPT, because attribution still needs it. This arm pins the missing half: with
	 * physical confirmation, a wrong flag can no longer produce a COMPLETE while our pile is down.
	 */
	@Test
	public void case54_aWrongMergeShadowFlagStillCannotReachCOMPLETE() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		// The rival record is minted from a MERGE report, so mergeShadow=true, but it stands for a
		// REAL separate pile. That is the premise being broken on purpose.
		p.setDropProofRolloutForTest(false);
		p.trackGroundDrop(1931, 11L, 3200, 3400, 0, null, 100, 900, true);
		FakePile rival = scene.lay("RIVAL", 1931, 3200, 3400);
		assertTrue("the rival record really carries the wrong flag",
			onlyTrackedPile(p).mergeShadow);

		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		scene.lay("OURS", 1931, 3200, 3400);
		assertFalse("the RECORD-only matcher is fooled by the flag, exactly as R5ShadowB measured",
			p.matchDespawnedGroundDrop(1931, 3200, 3400, 0).ambiguous);

		// The customer physically takes the RIVAL. Ours is still down.
		AccountConnectPlugin.DroppedGroundItem chosen = despawnInScene(p, scene, rival, 500, true);
		poll(p, 50);

		assertTrue("FINDING R6: NO EARLY STOP with our pile still physically down",
			p.dropSession.active());
		assertFalse("the tail must not arm", p.dropSession.stopPending());
		assertTrue("FINDING R6: a forced wrong shadow state can never reach COMPLETE",
			p.dropSession.unprovable());
		assertNull("the scene refuses the despawn the flag would have waved through", chosen);
	}

	// ===== CASE 55 — the DISCLOSED STOP, pinned in the LATE direction =====

	/**
	 * FINDING R5. The disclosure promises the recording stops five seconds after the final dropped
	 * pile disappears. On abdf227 the ambiguous route was late by up to THIRTY MINUTES: the despawn
	 * was credited to the rival's record, our own record lingered for a pile that was physically
	 * gone, dropSessionHoldsOwnedPile kept returning true, the 30-second orphaned-state invariant
	 * could never accumulate its grace, and only the hard cap ended it. Every one of those frames
	 * uploaded.
	 *
	 * The per-poll physical check ends it instead, on the first poll after the tile is clear of that
	 * item, plus the ordinary five-second tail. This arm asserts BOTH directions: not early while an
	 * item of that id is still on the tile, and not late once it is not.
	 */
	@Test
	public void case55_theAmbiguousRouteEndsOnTheTailOnceTheTileIsPhysicallyClear() throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		// The round-7 route: two REAL tracked piles of an unstackable, one of them ours.
		p.setDropProofRolloutForTest(false);
		p.trackGroundDrop(1931, 11L, 3200, 3400, 0, null, 100, 900, false);
		FakePile rival = scene.lay("RIVAL", 1931, 3200, 3400);
		p.setDropProofRolloutForTest(true);
		p.onDropProofCapabilityChanged();
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		FakePile ours = scene.lay("OURS", 1931, 3200, 3400);

		// The customer takes OUR pile and walks away, leaving the rival's pile down. The despawn is
		// ambiguous, so it is credited to the RIVAL's record and OUR record lingers for a pile that
		// is physically gone. This is R5Linger's exact state.
		AccountConnectPlugin.DroppedGroundItem chosen = despawnInScene(p, scene, ours, 500, true);
		assertNotNull("the round-7 refusal still answers with the rival's own record", chosen);
		assertEquals("which is the rival's record, not ours", 11L, chosen.qty);
		poll(p, 5);

		// EARLY DIRECTION. An item of that id is still on the tile, so nothing may be released.
		assertTrue("the recording is still running", p.dropSession.active());
		assertEquals("our key is still held", 1, p.dropSession.activePileCount());
		assertFalse("NOT EARLY: the tail must not arm while an item of that id is still on the tile",
			p.dropSession.stopPending());

		// LATE DIRECTION. The rival's pile finally leaves. The tile is clear of item 1931, so the
		// scene proves our pile is gone too, and the ordinary tail ends the recording.
		scene.remove(rival);
		poll(p, 1);
		assertEquals("NOT LATE: the physical check releases the key on the very next poll",
			0, p.dropSession.activePileCount());
		assertTrue("so the ordinary five-second tail arms", p.dropSession.stopPending());
		assertTrue("and the session is still running until it elapses", p.dropSession.active());
		assertEquals("the recorder names the physical evidence",
			DropSessionRecorder.REASON_AMBIGUOUS_DESPAWN, p.dropSession.reason());

		forceTailDue(p);
		poll(p, 1);
		assertFalse("the recording ends on the tail, NOT on the 30-minute cap",
			p.dropSession.active());
		Map<String, Object> m = manifest(p);
		assertEquals("a despawn it could not attribute is never COMPLETE",
			"INTERRUPTED", m.get("outcome"));
		assertEquals("and the cap is NOT what ended it",
			DropSessionRecorder.REASON_AMBIGUOUS_DESPAWN, m.get("outcome_reason"));
	}

	// ===== CASE 56 — a tile OUT OF SCENE is not an empty tile =====

	/**
	 * UNKNOWN is the third answer and it exists so a failed read can never be read as proof.
	 * Collapsing it into "empty" would turn every world hop, scene reload and walk out of render
	 * distance into "your pile is gone", which is a new false negative in place of the old false
	 * positive. Mutation (c) is exactly this collapse.
	 *
	 * The arm also pins the ITEM ID in the per-poll check: a tile emptied of some OTHER item says
	 * nothing about our pile. Mutation (d) is that omission.
	 */
	@Test
	public void case56_anUnreadableSceneChangesNothingAndTheItemIdIsPartOfTheQuestion()
		throws Exception
	{
		// A — the tile is OUT OF SCENE. The physical check must do nothing at all.
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		scene.leaveScene(3200, 3400, 0);
		assertEquals("the scene cannot answer for a tile it does not hold",
			AccountConnectPlugin.ScenePileState.UNKNOWN,
			p.scenePileState(1931, 3200, 3400, 0, null));
		poll(p, 50);
		assertEquals("UNKNOWN must NOT release the key", 1, p.dropSession.activePileCount());
		assertTrue("so the recording keeps running", p.dropSession.active());
		assertFalse("and the tail stays unarmed", p.dropSession.stopPending());
		assertFalse("nothing marked it unprovable either", p.dropSession.unprovable());

		// B — the tile is IN scene and holds a DIFFERENT item. Our pile of 1931 is not there, so the
		// key is released; a pile of item 995 on the tile must not keep it alive.
		Rig r2 = rig();
		AccountConnectPlugin p2 = r2.plugin;
		FakeScene scene2 = withScene(p2);
		dropAction(p2);
		p2.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p2.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		scene2.lay("SOMEBODY ELSE'S COINS", 995, 3200, 3400);
		poll(p2, 1);
		assertEquals("a pile of another item is not our pile",
			0, p2.dropSession.activePileCount());
		assertTrue("so the tail arms", p2.dropSession.stopPending());
		assertTrue("and the session can never claim it watched the pile go",
			p2.dropSession.unprovable());
		assertEquals("the reason names the physical evidence",
			DropSessionRecorder.REASON_PILE_SCENE_GONE, p2.dropSession.reason());
		forceTailDue(p2);
		poll(p2, 1);
		assertEquals("a pile that vanished without an attributable despawn is NOT complete",
			"INTERRUPTED", manifest(p2).get("outcome"));

		// C — the tile is IN scene and OUR item is still on it. Nothing may be released.
		Rig r3 = rig();
		AccountConnectPlugin p3 = r3.plugin;
		FakeScene scene3 = withScene(p3);
		dropAction(p3);
		p3.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p3.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
		scene3.lay("OURS", 1931, 3200, 3400);
		poll(p3, 50);
		assertEquals("our pile is still physically there, so the key is kept",
			1, p3.dropSession.activePileCount());
		assertFalse("and the tail stays unarmed", p3.dropSession.stopPending());

		// D — TWO of the session's own piles of DIFFERENT items on ONE tile, and only one of them
		// has gone. A drop trade of a rune platebody and a stack of coins onto the same tile is
		// ordinary, and the two piles leave separately. The check must answer PER PILE: the key for
		// the item that left is released, and the key for the item still lying there is NOT. A check
		// that read one answer for the whole tile would either strand both keys or release both, and
		// releasing both is an early stop with a pile still physically down.
		Rig r4 = rig();
		AccountConnectPlugin p4 = r4.plugin;
		FakeScene scene4 = withScene(p4);
		dropAction(p4);
		p4.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p4.trackGroundDrop(1931, 1L, 3200, 3400, 0, null, 300, 900, false);
		dropAction(p4);
		p4.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 2}, 2_000L);
		p4.trackGroundDrop(995, 500L, 3200, 3400, 0, null, 301, 900, false);
		assertEquals("the session holds two keys on one tile", 2, p4.dropSession.activePileCount());
		// Only the COINS are still physically on the tile. The 1931 pile has gone.
		scene4.lay("OUR COINS", 995, 3200, 3400);
		poll(p4, 50);
		assertFalse("NO EARLY STOP: the coins are still physically down, so no tail may arm",
			p4.dropSession.stopPending());
		assertTrue("and the recording is still running", p4.dropSession.active());
		assertTrue("and the screen is still being captured", (Boolean) field(p4, "dropCapturing"));
		// The mechanism behind it.
		assertEquals("exactly ONE key is released: the pile whose OWN item left the tile",
			1, p4.dropSession.activePileCount());
		assertEquals("only the departed pile's record was dropped", 1, p4.groundDropCount());
		assertEquals("and it is the coins that are still tracked", 995, onlyTrackedPile(p4).item);
	}

	// ===== CASE 57 — the despawn verdict does not depend on an event ordering we cannot verify =====

	/**
	 * RuneLite posts ItemDespawned from inside the client's own scene update, and nothing in the
	 * 1.12.39 obfuscated client proves whether the TileItem being despawned has already left the
	 * tile's item layer when the event arrives. If it has not, a naive scene read at despawn time
	 * would see the very pile that is going and call EVERY despawn ambiguous.
	 *
	 * The resolver excludes the despawning TileItem by OBJECT IDENTITY, so both orderings must reach
	 * the same verdict. This arm drives the same route twice, once in each order.
	 */
	@Test
	public void case57_theDespawnVerdictIsTheSameInBothSceneUpdateOrders() throws Exception
	{
		for (boolean alreadyGone : new boolean[]{true, false})
		{
			String order = alreadyGone ? "already removed from the tile" : "still on the tile";

			// A — nothing else on the tile: the despawn must resolve, in BOTH orders.
			Rig r = rig();
			AccountConnectPlugin p = r.plugin;
			FakeScene scene = withScene(p);
			dropAction(p);
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
			p.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
			FakePile ours = scene.lay("OURS", 1931, 3200, 3400);
			assertNotNull("the despawning pile itself must never make its own despawn ambiguous ("
					+ order + ")",
				despawnInScene(p, scene, ours, 500, alreadyGone));
			poll(p, 2);
			assertTrue("so the tail arms (" + order + ")", p.dropSession.stopPending());
			assertFalse("and nothing is unprovable (" + order + ")", p.dropSession.unprovable());

			// B — an untracked rival is also on the tile: the despawn must be refused, in BOTH orders.
			Rig r2 = rig();
			AccountConnectPlugin p2 = r2.plugin;
			FakeScene scene2 = withScene(p2);
			FakePile rival = scene2.lay("RIVAL", 1931, 3200, 3400);
			dropAction(p2);
			p2.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
			p2.trackGroundDrop(1931, 99L, 3200, 3400, 0, null, 300, 900, false);
			scene2.lay("OURS", 1931, 3200, 3400);
			AccountConnectPlugin.DroppedGroundItem refused =
				despawnInScene(p2, scene2, rival, 500, alreadyGone);
			poll(p2, 2);
			assertFalse("a rival on the tile arms no tail (" + order + ")",
				p2.dropSession.stopPending());
			assertTrue("COMPLETE is out of reach (" + order + ")", p2.dropSession.unprovable());
			assertNull("and the despawn itself is refused (" + order + ")", refused);
		}
	}

	// ================================================================
	// ROUND 9 — COUNT AND QUANTITY, NOT PRESENCE. Cases 58-62.
	//
	// FINDING R7 (review D round 6, HIGH) is a regression of bb1e090. The round-8 refusal asked the
	// scene "is an item of this id still on this tile?", and that question cannot tell the session's
	// OWN second pile from a stranger's. Ten of our own piles of one item on one tile made the first
	// nine despawns look exactly like FINDING R4's untracked rival: nine of ten evidence rows were
	// never published, the one that survived described the WRONG pile under our session id, and the
	// manifest always said INTERRUPTED.
	//
	// The review could not see it from the suite because cases 49 and 50 never call
	// setSceneReaderForTest, so they run with UNKNOWN on every read. EVERY arm below installs a
	// working scene reader. That is the whole reason R7 shipped.
	// ================================================================

	/** Every ground_removed row published so far, oldest first. */
	private static java.util.List<Map<String, Object>> allRemovals(AccountConnectPlugin p)
	{
		java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
		for (Map<String, Object> e : p.pendingEvents)
		{
			if ("ground_removed".equals(e.get("type")))
			{
				out.add(e);
			}
		}
		return out;
	}

	// ===== CASE 58 — FINDING R7, ported from the review's R6Multi and R6Rows =====

	/**
	 * TEN of the session's OWN piles of ONE item on ONE tile, with DISTINCT quantities, a working
	 * scene reader, and nothing untracked anywhere. This is the ordinary shape of a store delivery:
	 * DROP_SPAWN_MAX_DIST is 2, so every pile normally lands on the same tile.
	 *
	 * On 3967e39 this printed `take 1..9: resolver=REFUSED rows=0` and
	 * `RESULT MANIFEST outcome=INTERRUPTED reason=ambiguous_despawn drops=10`. Ten physical removals
	 * produced ONE row, and that row carried the oldest record's quantity and drop_seq.
	 *
	 * The outcome is asserted first, then the per-row attribution, then the mechanism.
	 */
	@Test
	public void case58_tenOwnPilesOfOneItemOnOneTilePublishTenHonestRowsAndComplete()
		throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		FakePile[] physical = new FakePile[10];
		for (int i = 0; i < 10; i++)
		{
			dropAction(p);
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i);
			// Distinct quantities and distinct drop ticks, so a WRONG choice is visible on the row.
			p.trackGroundDrop(1931, 11L * (i + 1), 3200, 3400, 0, null, 100 + i, 900, false);
			physical[i] = scene.lay("OURS" + i, 1931, 3200, 3400, 11L * (i + 1));
		}
		assertEquals("ten of our own piles on one tile", 10, p.groundDropCount());
		assertEquals("and ten keys held", 10, p.dropSession.activePileCount());

		// The customer takes them NEWEST FIRST, which is the order that exposed the wrong
		// attribution in R6Attrib.
		for (int i = 9; i >= 0; i--)
		{
			AccountConnectPlugin.DroppedGroundItem chosen =
				despawnInScene(p, scene, physical[i], 500 + (9 - i), true);
			assertNotNull("FINDING R7: take " + (10 - i) + " of 10 must NOT be refused; every pile "
					+ "on this tile is ours and the count proves one of ours went", chosen);
			assertEquals("FINDING R7: and the resolver must choose the pile that actually left",
				11L * (i + 1), chosen.qty);
			poll(p, 2);
		}

		// THE OUTCOME, asserted first.
		assertEquals("FINDING R7: all TEN evidence rows are published, not one",
			10, allRemovals(p).size());
		assertEquals("every key is released", 0, p.dropSession.activePileCount());
		assertTrue("so the tail arms", p.dropSession.stopPending());
		assertFalse("FINDING R7: an honest same-item multi-pile trade is NOT unprovable",
			p.dropSession.unprovable());
		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
		assertEquals("FINDING R7: a trade in which every pile was ours ends COMPLETE",
			"COMPLETE", manifest(p).get("outcome"));
		assertNull("naming no reason", manifest(p).get("outcome_reason"));
		assertEquals("all ten drops counted", 10, manifest(p).get("drops"));

		// PER-ROW ATTRIBUTION. The rows were published newest-first, so row k describes pile 9-k.
		java.util.List<Map<String, Object>> rows = allRemovals(p);
		for (int k = 0; k < 10; k++)
		{
			int pileIndex = 9 - k;
			Map<String, Object> row = rows.get(k);
			assertEquals("FINDING R7: row " + k + " carries the DEPARTED pile's quantity",
				11L * (pileIndex + 1), row.get("qty"));
			assertEquals("FINDING R7: and the DEPARTED pile's drop_seq",
				pileIndex + 1, row.get("drop_seq"));
			assertEquals("FINDING R7: and the DEPARTED pile's own lifetime, not the oldest one's",
				(500 + k) - (100 + pileIndex), row.get("ticks_on_ground"));
			assertNotNull("and its honest session linkage", row.get("drop_session_id"));
			assertNull("with no uncertainty marker, because every quantity was distinct",
				row.get("attribution_uncertain"));
		}
	}

	// ===== CASE 59 — FINDING R7, ported from the review's R6Attrib, BOTH take orders =====

	/**
	 * Three own piles of item 1931 with quantities 11, 22 and 33, driven in BOTH physical take
	 * orders. On 3967e39 both orders published a single row reading `qty=11 drop_seq=1
	 * ticks_on_ground=420` whatever actually left: a row with our session id and our sequence number
	 * describing a different pile of ours.
	 */
	@Test
	public void case59_theRowDescribesThePileThatActuallyLeftInBothTakeOrders() throws Exception
	{
		for (boolean oldestFirst : new boolean[]{true, false})
		{
			String order = oldestFirst ? "OLDEST first" : "NEWEST first";
			Rig r = rig();
			AccountConnectPlugin p = r.plugin;
			FakeScene scene = withScene(p);

			long[] qty = {11L, 22L, 33L};
			FakePile[] physical = new FakePile[3];
			for (int i = 0; i < 3; i++)
			{
				dropAction(p);
				p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i);
				p.trackGroundDrop(1931, qty[i], 3200, 3400, 0, null, 100 + i, 900, false);
				physical[i] = scene.lay("OURS" + i, 1931, 3200, 3400, qty[i]);
			}

			int[] takeOrder = oldestFirst ? new int[]{0, 1, 2} : new int[]{2, 1, 0};
			for (int t = 0; t < 3; t++)
			{
				int i = takeOrder[t];
				AccountConnectPlugin.DroppedGroundItem chosen =
					despawnInScene(p, scene, physical[i], 500 + t * 10, true);
				assertNotNull("FINDING R7 (" + order + "): take " + (t + 1) + " must not be refused",
					chosen);
				poll(p, 2);
				Map<String, Object> row = lastRemoval(p);
				assertEquals("FINDING R7 (" + order + "): the row must carry the DEPARTED pile's qty",
					qty[i], row.get("qty"));
				assertEquals("FINDING R7 (" + order + "): and its own drop_seq",
					i + 1, row.get("drop_seq"));
			}

			assertEquals("all three rows published (" + order + ")", 3, allRemovals(p).size());
			assertFalse("nothing is unprovable (" + order + ")", p.dropSession.unprovable());
			forceTailDue(p);
			poll(p, 1);
			assertEquals("FINDING R7 (" + order + "): the trade ends COMPLETE",
				"COMPLETE", manifest(p).get("outcome"));
		}
	}

	// ===== CASE 60 — the SAME trade with ONE STRANGER'S PILE: refused, never COMPLETE =====

	/**
	 * Ten own piles PLUS one untracked rival of the same item on the same tile. The count rule must
	 * come down on the CONSERVATIVE side here: with C piles left and |S| records owned, C >= |S|
	 * means at least one pile on that tile is not ours, so the despawn is refused whole. No row may
	 * be fabricated and the manifest must never say COMPLETE.
	 *
	 * This is the control that stops "just stop refusing" from passing case 58. FINDING R4 must stay
	 * closed.
	 */
	@Test
	public void case60_oneStrangerPileAmongTenOwnPilesStillRefusesAndNeverCompletes()
		throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		// The physical ground: a REAL rival pile of item 1931 the plugin NEVER tracked.
		FakePile rival = scene.lay("RIVAL", 1931, 3200, 3400, 777L);

		FakePile[] physical = new FakePile[10];
		for (int i = 0; i < 10; i++)
		{
			dropAction(p);
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i);
			p.trackGroundDrop(1931, 11L * (i + 1), 3200, 3400, 0, null, 100 + i, 900, false);
			physical[i] = scene.lay("OURS" + i, 1931, 3200, 3400, 11L * (i + 1));
		}
		assertEquals("the plugin tracks only OUR ten", 10, p.groundDropCount());

		// The customer takes one of OURS while the stranger's pile is still down.
		AccountConnectPlugin.DroppedGroundItem chosen =
			despawnInScene(p, scene, physical[0], 500, true);
		poll(p, 5);

		// THE OUTCOME, asserted first.
		assertTrue("FINDING R4: NO EARLY STOP while a pile we do not own is on the tile",
			p.dropSession.active());
		assertFalse("and the tail must NOT arm", p.dropSession.stopPending());
		assertTrue("FINDING R4: COMPLETE is out of reach", p.dropSession.unprovable());
		assertEquals("FINDING R4: NO row may be fabricated for an ambiguous take",
			0, allRemovals(p).size());
		assertNull("the despawn itself is refused", chosen);
		assertEquals("the session keeps every key", 10, p.dropSession.activePileCount());

		// The whole tile then clears, ours and the stranger's alike.
		for (int i = 1; i < 10; i++)
		{
			despawnInScene(p, scene, physical[i], 510 + i, true);
			poll(p, 2);
		}
		despawnInScene(p, scene, rival, 600, true);
		poll(p, 5);
		forceTailDue(p);
		poll(p, 1);
		assertFalse(p.dropSession.active());
		assertEquals("FINDING R4: a session that shared its tile with a stranger's pile is "
				+ "INTERRUPTED, never COMPLETE", "INTERRUPTED", manifest(p).get("outcome"));
	}

	// ===== CASE 61 — EQUAL-quantity own piles: rows still published, session still provable =====

	/**
	 * Three of the session's own piles of one item on one tile, ALL with the same quantity. The
	 * count still proves one of OURS left, so a row is owed. Which of our equal piles it was cannot
	 * be told apart, so the row says so in `attribution_uncertain` and keeps its honest qty and
	 * session linkage.
	 *
	 * The SESSION must NOT be marked unprovable. Two of our own equal piles are not a stranger, and
	 * refusing the whole trade over them would be FINDING R7 again in a smaller form.
	 */
	@Test
	public void case61_equalQuantityOwnPilesStillPublishRowsAndTheSessionStaysProvable()
		throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		FakePile[] physical = new FakePile[3];
		for (int i = 0; i < 3; i++)
		{
			dropAction(p);
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i);
			p.trackGroundDrop(1931, 50L, 3200, 3400, 0, null, 100 + i, 900, false);
			physical[i] = scene.lay("OURS" + i, 1931, 3200, 3400, 50L);
		}

		for (int t = 0; t < 3; t++)
		{
			AccountConnectPlugin.DroppedGroundItem chosen =
				despawnInScene(p, scene, physical[t], 500 + t, true);
			assertNotNull("equal quantities are OURS either way, so the take is not refused", chosen);
			poll(p, 2);
		}

		// THE OUTCOME, asserted first.
		assertEquals("all three rows are published", 3, allRemovals(p).size());
		assertFalse("the session is NOT unprovable: every candidate pile was ours",
			p.dropSession.unprovable());
		assertTrue("the tail arms", p.dropSession.stopPending());
		forceTailDue(p);
		poll(p, 1);
		assertEquals("and the trade ends COMPLETE", "COMPLETE", manifest(p).get("outcome"));

		// The rows are honest about the ONE thing that is uncertain.
		java.util.List<Map<String, Object>> rows = allRemovals(p);
		assertEquals("the first two rows name their tie", "equal_quantity_own_piles",
			rows.get(0).get("attribution_uncertain"));
		assertEquals("equal_quantity_own_piles", rows.get(1).get("attribution_uncertain"));
		assertNull("the LAST pile has no equal left to be confused with",
			rows.get(2).get("attribution_uncertain"));
		for (Map<String, Object> row : rows)
		{
			assertEquals("the quantity is certain, because every candidate carried it",
				50L, row.get("qty"));
			assertNotNull("and the linkage is kept: the pile was certainly ours",
				row.get("drop_session_id"));
		}
	}

	// ===== CASE 62 — FINDING R8: the per-poll release COUNTS, it does not ask presence =====

	/**
	 * Two of the session's own piles of ONE item on ONE tile, with different quantities. One leaves
	 * with NO despawn event reaching us, which is the state FINDING R5 was about.
	 *
	 * On 3967e39 the poll asked "is an item of that id still on that tile?" once per RECORD, so both
	 * records asked the SAME question, got PRESENT, and BOTH keys were kept. The recording then ran
	 * past the moment the disclosure names. Counting releases exactly the one key whose pile is
	 * gone, and the QUANTITY says which one.
	 */
	@Test
	public void case62_thePerPollReleaseCountsPilesAndPicksTheOneWhoseQuantityIsGone()
		throws Exception
	{
		Rig r = rig();
		AccountConnectPlugin p = r.plugin;
		FakeScene scene = withScene(p);

		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1}, 1_000L);
		p.trackGroundDrop(1931, 7L, 3200, 3400, 0, null, 100, 900, false);
		FakePile seven = scene.lay("OURS7", 1931, 3200, 3400, 7L);
		dropAction(p);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 2}, 1_001L);
		p.trackGroundDrop(1931, 9L, 3200, 3400, 0, null, 101, 900, false);
		scene.lay("OURS9", 1931, 3200, 3400, 9L);
		assertEquals("two own piles of one item on one tile", 2, p.dropSession.activePileCount());

		// The pile of 7 physically leaves and NO despawn event reaches the plugin.
		scene.remove(seven);
		poll(p, 3);

		// THE OUTCOME, asserted first. Keeping BOTH keys is the LATE direction FINDING R5 named: the
		// recording runs on past the moment a pile the disclosure counts actually disappeared, and
		// every one of those frames uploads. Releasing BOTH would be the EARLY direction.
		assertEquals("FINDING R8: NOT LATE and NOT EARLY — exactly ONE key is released, because "
				+ "exactly one of our two piles left the tile",
			1, p.dropSession.activePileCount());
		assertTrue("the session is still recording, because our other pile is still down",
			p.dropSession.active());
		assertFalse("NO EARLY STOP: the tail must not arm while a session pile is on the ground",
			p.dropSession.stopPending());
		assertTrue("a pile released without being observed to leave makes the session unprovable",
			p.dropSession.unprovable());

		// And it released the RIGHT one: the surviving record is the pile still physically down.
		assertEquals("one record survives", 1, p.groundDropCount());
		assertEquals("FINDING R8: and it is the pile of 9, which is still on the tile",
			9L, groundDrops(p).iterator().next().qty);

		// When the second pile goes too, the tail arms on the very next poll.
		scene.piles.clear();
		poll(p, 2);
		assertEquals("both keys released", 0, p.dropSession.activePileCount());
		assertTrue("NOT LATE: the tail arms one poll after the tile is clear",
			p.dropSession.stopPending());
	}
}
