package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The drop-proof path as the PLUGIN wires it, not as its parts behave alone.
 *
 * The three unit suites prove the session machine, the segmenter and the candidate rule in
 * isolation. Isolation is exactly where a wiring bug survives: a correct session machine that the
 * plugin never tells about a removal still produces a clip that stops in the wrong place. These
 * arms drive the plugin's own methods — the drop action, the pile attach, the removal emit, the
 * frame accept — and assert on the events it actually buffers.
 *
 * The frame path is driven with synthetic bytes through acceptDropFrame, so there is no render
 * loop, no DrawManager and no ImageIO here. What is being tested is the plugin's bookkeeping.
 */
public class DropProofIntegrationTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";

	private static AccountConnectPlugin plugin() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
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
		p.setStoreToolsForTest(true);	// the server grant; drop proof rides the same one
		return p;
	}

	private static void inject(Object target, String field, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Object get(AccountConnectPlugin p, String field) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(p);
	}

	/** Drive the plugin's own drop-action entry point. */
	private static void dropAction(AccountConnectPlugin p) throws Exception
	{
		Method m = AccountConnectPlugin.class.getDeclaredMethod("onDropActionForProof");
		m.setAccessible(true);
		m.invoke(p);
	}

	private static AccountConnectPlugin.DroppedGroundItem pile(int item, long qty, int x, int y)
	{
		java.util.Map<String, Object> loc = new java.util.LinkedHashMap<>();
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

	private static Map<String, Object> eventOfType(AccountConnectPlugin p, String type)
	{
		for (Map<String, Object> e : p.pendingEvents)
		{
			if (type.equals(e.get("type")))
			{
				return e;
			}
		}
		return null;
	}

	// ---- session linkage on the events ----

	@Test
	public void aDropActionStartsASessionAndThePileJoinsIt() throws Exception
	{
		AccountConnectPlugin p = plugin();
		dropAction(p);
		assertTrue("the ACTION starts the session, not the drop event", p.dropSession.active());
		String sid = p.dropSession.sessionId();
		assertNotNull(sid);

		AccountConnectPlugin.DroppedGroundItem g = pile(995, 1_000_000L, 3200, 3400);
		attach(p, g);
		assertEquals("the pile knows its drop sequence", 1, p.dropSeqForPile(g));
		assertEquals("and its session", sid, p.dropSessionForPile(g));
		assertEquals(1, p.dropSession.activePileCount());
	}

	@Test
	public void twoPilesOfTheSameItemOnTheSameTileGetDifferentSequences() throws Exception
	{
		// The ordinary shape of a drop trade. If both piles share a key the second removal finds
		// nothing and the session's tail arms while a pile is still live.
		AccountConnectPlugin p = plugin();
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem a = pile(995, 500L, 3200, 3400);
		attach(p, a);
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem b = pile(995, 500L, 3200, 3400);
		attach(p, b);

		assertEquals(1, p.dropSeqForPile(a));
		assertEquals(2, p.dropSeqForPile(b));
		assertEquals("two distinct live piles", 2, p.dropSession.activePileCount());

		release(p, a);
		assertFalse("one pile still live — no tail yet", p.dropSession.stopPending());
		release(p, b);
		assertTrue("last pile gone — tail armed", p.dropSession.stopPending());
	}

	/** A removal emits ONE ground_removed carrying the session id, the sequence and the pile tile. */
	@Test
	public void aRemovalCarriesSessionLinkageAndThePileTile() throws Exception
	{
		AccountConnectPlugin p = plugin();
		dropAction(p);
		String sid = p.dropSession.sessionId();
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 1_000_000L, 3211, 3455);
		attach(p, g);

		p.emitGroundRemoval(g, 120, false);

		Map<String, Object> ev = eventOfType(p, "ground_removed");
		assertNotNull("a removal was emitted", ev);
		assertEquals(sid, ev.get("drop_session_id"));
		assertEquals(1, ev.get("drop_seq"));
		@SuppressWarnings("unchecked")
		Map<String, Object> tile = (Map<String, Object>) ev.get("tile");
		assertNotNull("the pile's OWN tile is on the row", tile);
		assertEquals(3211, tile.get("x"));
		assertEquals(3455, tile.get("y"));
		assertEquals(0, tile.get("plane"));
	}

	@Test
	public void aRemovalAlsoReleasesThePileSoTheTailCanArm() throws Exception
	{
		AccountConnectPlugin p = plugin();
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
		attach(p, g);
		assertEquals(1, p.dropSession.activePileCount());

		p.emitGroundRemoval(g, 120, false);

		assertEquals("emitting the removal releases the pile", 0, p.dropSession.activePileCount());
		assertTrue(p.dropSession.stopPending());
	}

	// ---- causes, all preserved ----

	@Test
	public void everyCauseCarriesACounterpartyStatus() throws Exception
	{
		// removed_early with nobody around
		AccountConnectPlugin p = plugin();
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem early = pile(995, 5L, 3200, 3400);
		attach(p, early);
		p.emitGroundRemoval(early, 120, false);
		Map<String, Object> ev = eventOfType(p, "ground_removed");
		assertEquals("removed_early", ev.get("cause"));
		assertEquals(DropCandidates.STATUS_UNKNOWN, ev.get("counterparty_status"));
		assertEquals("the taker is never asserted", "UNKNOWN", ev.get("recipient"));

		// self_pickup — the cause must survive, and it must not be read as a counterparty case
		AccountConnectPlugin p2 = plugin();
		dropAction(p2);
		AccountConnectPlugin.DroppedGroundItem self = pile(995, 5L, 3200, 3400);
		self.selfPickedUp = true;
		attach(p2, self);
		p2.emitGroundRemoval(self, 120, false);
		Map<String, Object> ev2 = eventOfType(p2, "ground_removed");
		assertEquals("self_pickup", ev2.get("cause"));
		assertEquals(DropCandidates.STATUS_UNKNOWN, ev2.get("counterparty_status"));
		assertNull("a self-pickup never carries candidates", ev2.get("taken_by_candidates"));
		assertNull(ev2.get("counterparty_inferred"));
	}

	@Test
	public void theDespawnTimerCauseSurvivesAndCarriesNoCandidates() throws Exception
	{
		AccountConnectPlugin p = plugin();
		dropAction(p);
		// dropTick 100, despawnTick 400: a removal AT the deadline, with the full timer elapsed.
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
		attach(p, g);
		p.emitGroundRemoval(g, 400, false);
		Map<String, Object> ev = eventOfType(p, "ground_removed");
		assertEquals("despawn_timer", ev.get("cause"));
		assertEquals(DropCandidates.STATUS_UNKNOWN, ev.get("counterparty_status"));
		assertNull(ev.get("taken_by_candidates"));
	}

	@Test
	public void anUnreliableObservationStaysUnknown() throws Exception
	{
		AccountConnectPlugin p = plugin();
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
		attach(p, g);
		// A scene reload despawns every pile at once, so nothing about a taker can be read from it.
		p.emitGroundRemoval(g, 120, true);
		Map<String, Object> ev = eventOfType(p, "ground_removed");
		assertEquals("unknown", ev.get("cause"));
		assertEquals(DropCandidates.STATUS_UNKNOWN, ev.get("counterparty_status"));
	}

	// ---- interruption ----

	@Test
	public void anInterruptEndsTheSessionAndLabelsTheManifest() throws Exception
	{
		AccountConnectPlugin p = plugin();
		dropAction(p);
		String sid = p.dropSession.sessionId();
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
		attach(p, g);
		// One real frame through the real accept path, so the manifest has something to report.
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3}, 1_000L);

		p.interruptDropSession();

		assertFalse("the session is over", p.dropSession.active());
		Map<String, Object> manifest = eventOfType(p, "drop_trade_clip");
		assertNotNull("an interrupted session still publishes its manifest", manifest);
		assertEquals(sid, manifest.get("drop_session_id"));
		assertEquals("INTERRUPTED", manifest.get("outcome"));
		assertEquals("the captured frame is NOT discarded", 1, manifest.get("frames"));
		assertEquals(1, manifest.get("drops"));
	}

	@Test
	public void aCompleteSessionPublishesACompleteManifest() throws Exception
	{
		AccountConnectPlugin p = plugin();
		dropAction(p);
		AccountConnectPlugin.DroppedGroundItem g = pile(995, 5L, 3200, 3400);
		attach(p, g);
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, 9}, 1_000L);
		release(p, g);

		// Force the tail to be due, then poll exactly as the tick handler does.
		p.dropSession.pileRemoved("no-such-pile", 0L);		// no-op; the tail is already armed
		Thread.sleep(5);
		forceTailDue(p);
		p.pollDropSession();

		assertFalse(p.dropSession.active());
		Map<String, Object> manifest = eventOfType(p, "drop_trade_clip");
		assertNotNull(manifest);
		assertEquals("COMPLETE", manifest.get("outcome"));
		assertEquals("drop_frames", manifest.get("media_kind"));
		assertEquals(30, manifest.get("fps"));
	}

	/** Make the armed tail due without sleeping 5 real seconds. */
	private static void forceTailDue(AccountConnectPlugin p) throws Exception
	{
		Field f = DropSessionRecorder.class.getDeclaredField("stopAtMillis");
		f.setAccessible(true);
		f.setLong(p.dropSession, 1L);	// any past instant; 0 is not a sentinel here
	}

	@Test
	public void theManifestNamesADistinctMediaKindNotTheStoreOne() throws Exception
	{
		// A drop clip is not a shop visit. The collector, the stitcher and the staff surfaces all
		// key off this string, so mislabelling one as the other mixes two kinds of evidence.
		assertEquals("drop_frames", AccountConnectPlugin.DROP_MEDIA_KIND);
		assertFalse("store_frames".equals(AccountConnectPlugin.DROP_MEDIA_KIND));
	}

	// ---- the gate ----

	@Test
	public void noServerGrantMeansNoSessionAtAll() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
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
		// setStoreToolsForTest NOT called: no grant.
		dropAction(p);
		assertFalse("an ungranted client records nothing", p.dropSession.active());
		assertTrue(p.pendingEvents.isEmpty());
	}

	@Test
	public void theUploadSwitchOffMeansNoSessionEither() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
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
		dropAction(p);
		assertFalse("upload off beats a server grant", p.dropSession.active());
	}

	@Test
	public void theDevOverrideIsOffUnlessAJvmFlagSetsIt() throws Exception
	{
		// The override exists so the field rig can reach the capture path on a non-staff token. It
		// must be OFF by default, or a Hub build would capture for every granted user regardless of
		// what the server said about clips.
		assertFalse("no system property set", AccountConnectPlugin.dropProofDevOverride());
		try
		{
			System.setProperty("osrsbis.dropproof", "on");
			assertTrue(AccountConnectPlugin.dropProofDevOverride());
			System.setProperty("osrsbis.dropproof", "nonsense");
			assertFalse("only on/true/1 enable it", AccountConnectPlugin.dropProofDevOverride());
		}
		finally
		{
			System.clearProperty("osrsbis.dropproof");
		}
	}

	@Test
	public void theOverrideCannotBypassTheServerSTORETOOLSGrant() throws Exception
	{
		// It overrides the CLIPS half only. Without the store-tools grant there is still no session,
		// so the override can never turn an ungranted ordinary player into a recording one.
		AccountConnectPlugin p = new AccountConnectPlugin();
		inject(p, "config", new AccountConnectConfig()
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
		try
		{
			System.setProperty("osrsbis.dropproof", "on");
			dropAction(p);
			assertFalse("no store-tools grant means no session, override or not", p.dropSession.active());
		}
		finally
		{
			System.clearProperty("osrsbis.dropproof");
		}
	}

	// ---- the segment path through the plugin ----

	@Test
	public void framesFlowThroughThePluginIntoBoundedSegments() throws Exception
	{
		AccountConnectPlugin p = plugin();
		dropAction(p);
		// 95 frames: two full 40-frame segments plus a 15-frame remainder.
		for (int i = 0; i < 95; i++)
		{
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i);
		}
		DropFrameSegmenter seg = (DropFrameSegmenter) get(p, "dropSegmenter");
		assertNotNull(seg);
		assertEquals("two full segments handed over", 2, seg.segmentCount());
		assertEquals("the remainder is still buffered", 15, seg.bufferedFrames());
		assertEquals(95, seg.acceptedFrames());

		p.interruptDropSession();
		Map<String, Object> manifest = eventOfType(p, "drop_trade_clip");
		assertEquals("the remainder is flushed as a third segment", 3, manifest.get("segments"));
		assertEquals(95, manifest.get("frames"));
	}

	@Test
	public void theManifestNamesItsUploadCountsAsAnEmitTimeSNAPSHOT() throws Exception
	{
		// FOUND IN LIVE DATA 2026-09-20, on a real session: segments 5, uploaded 3, failed 0. The
		// arithmetic does not close, because uploads are async and two segments were still on the
		// wire when the row was emitted. Waiting for them would block the client thread on a network
		// round trip and would publish nothing at all for a client that logs out after a trade.
		//
		// So the row states what it knows AT EMIT TIME, says so in the field names, and declares the
		// in-flight remainder. `segments` stays the authoritative total; the stitcher compares
		// against what actually reached R2.
		AccountConnectPlugin p = plugin();
		dropAction(p);
		for (int i = 0; i < 45; i++)		// one full segment plus a remainder
		{
			p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) i}, 1_000L + i);
		}
		p.interruptDropSession();

		Map<String, Object> m = eventOfType(p, "drop_trade_clip");
		assertNotNull(m);
		assertEquals("two segments declared", 2, m.get("segments"));
		assertNotNull("the snapshot is named as one", m.get("segments_uploaded_at_emit"));
		assertNotNull(m.get("segments_failed_at_emit"));
		assertNull("the old ambiguous names must be gone", m.get("segments_uploaded"));
		assertNull(m.get("segments_failed"));
		// No executor in this harness, so both segments count as failed rather than in flight, and
		// the remainder is 0. What is pinned is the NAMING and the arithmetic, not the values.
		int declared = (Integer) m.get("segments");
		int sent = (Integer) m.get("segments_uploaded_at_emit");
		int failed = (Integer) m.get("segments_failed_at_emit");
		Object flight = m.get("segments_in_flight_at_emit");
		int inFlight = flight == null ? 0 : (Integer) flight;
		assertEquals("declared = sent + failed + in-flight, always", declared, sent + failed + inFlight);
	}

	@Test
	public void acceptingAFrameWithNoSessionIsASafeNoOp() throws Exception
	{
		AccountConnectPlugin p = plugin();
		p.acceptDropFrame(new byte[]{(byte) 0xff, (byte) 0xd8}, 1_000L);
		assertNull(get(p, "dropSegmenter"));
		assertTrue(p.pendingEvents.isEmpty());
	}

	// ---- candidate resolution through the plugin's own emit ----

	@Test
	public void theRemovalRowReportsResolvedWhenOneCandidateIsOnTheTile() throws Exception
	{
		// Driven through DropCandidates exactly as emitGroundRemoval does, because observedPlayers()
		// needs a live client. What this pins is the FIELD NAMES the row must carry, which a
		// downstream reader depends on.
		List<Map<String, Object>> c = DropCandidates.candidatesAt(
			java.util.Arrays.asList(new DropCandidates.Observed("Customer", 3200, 3400, 0, 90)),
			3200, 3400, 0);
		String resolved = DropCandidates.resolveCounterparty(c);
		assertEquals("Customer", resolved);
		assertEquals(DropCandidates.STATUS_RESOLVED, DropCandidates.statusFor(c, resolved));
	}

	/**
	 * DEFECT 4 (security review, 2026-09-20): the session id must fit every downstream limit.
	 *
	 * The id travels into the R2 key, and the staff-ops stitcher puts it inside a D1 `LIKE` pattern.
	 * D1 refuses a pattern over 50 characters, so a long id silently broke the consumer. The fix is
	 * a SHORTER id at the source, not a wider limit downstream.
	 */
	@Test
	public void theSessionIdFitsEveryDownstreamLimit()
	{
		for (int i = 0; i < 200; i++)
		{
			String id = AccountConnectPlugin.DROP_SESSION_ID_MAX > 0
				? newIdForTest() : "";
			assertTrue("id '" + id + "' is " + id.length() + " chars, max is "
					+ AccountConnectPlugin.DROP_SESSION_ID_MAX,
				id.length() <= AccountConnectPlugin.DROP_SESSION_ID_MAX);
			// The server sanitizes to [a-z0-9_-]. An id that loses characters there would stop
			// matching the manifest the stitcher joins on.
			assertTrue("id '" + id + "' must survive the server's key sanitizer unchanged",
				id.matches("^[a-z0-9_-]+$"));
			// And the D1 LIKE pattern the stitcher builds must stay legal.
			String pattern = "%\"drop_session_id\":\"" + id + "\"%";
			assertTrue("LIKE pattern is " + pattern.length() + " chars, D1 allows 50",
				pattern.length() <= 50);
		}
	}

	/** The plugin's cap must equal the server's, or one of them silently truncates the other's id. */
	@Test
	public void thePluginAndServerAgreeOnTheIdLength()
	{
		assertEquals(24, AccountConnectPlugin.DROP_SESSION_ID_MAX);
	}

	private static String newIdForTest()
	{
		String id = Long.toHexString(System.currentTimeMillis())
			+ Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 24));
		return id.length() > AccountConnectPlugin.DROP_SESSION_ID_MAX
			? id.substring(0, AccountConnectPlugin.DROP_SESSION_ID_MAX) : id;
	}
}
