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
	 * Drive the ONE ItemDespawned the game fires for a merged pile, exactly as onItemDespawned
	 * drives it: resolve the oldest tracked match, remove it, then tell the session.
	 *
	 * Going through findGroundDrop rather than through a pile reference is the whole point. The
	 * defect is that the oldest match is the WRONG entry, and a test holding the right reference
	 * would never see it.
	 */
	private static void despawnOldestTrackedPileAt(AccountConnectPlugin p, int item, int x, int y)
		throws Exception
	{
		Method find = AccountConnectPlugin.class.getDeclaredMethod(
			"findGroundDrop", int.class, int.class, int.class, int.class);
		find.setAccessible(true);
		AccountConnectPlugin.DroppedGroundItem g =
			(AccountConnectPlugin.DroppedGroundItem) find.invoke(p, item, x, y, 0);
		assertNotNull("the despawn must resolve to a tracked pile", g);
		java.util.Deque<AccountConnectPlugin.DroppedGroundItem> q = groundDrops(p);
		synchronized (q)
		{
			q.remove(g);
		}
		release(p, g);
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
		despawnOldestTrackedPileAt(p, 995, 3200, 3400);
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

		despawnOldestTrackedPileAt(p, 995, 3200, 3400);
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
		despawnOldestTrackedPileAt(p, 995, 3201, 3400);
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
		despawnOldestTrackedPileAt(p, 995, 3200, 3400);
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
}
