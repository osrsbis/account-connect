package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The durable event spool, tested as a pure library: no RuneLite, no network.
 *
 * <p>Every test drives real files in a temp directory. A "crash" is {@link EventSpool#crashForTest()},
 * which drops the channels and the shard lock exactly as process death does and writes nothing else.
 */
public class EventSpoolCodecTest
{
	private static final String TOKEN = "0123456789abcdef0123456789abcdef";
	private static final long T0 = 1_700_000_000_123L;

	private Path pluginData;
	private Path dir;
	private final AtomicLong clock = new AtomicLong(T0);
	private final List<EventSpool> opened = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		pluginData = Files.createTempDirectory("spool-codec");
		dir = pluginData.resolve("osrsbis").resolve("spool").resolve(EventSpool.identityKey(TOKEN, "12345"));
	}

	@After
	public void tearDown() throws Exception
	{
		for (EventSpool s : opened)
		{
			s.crashForTest();
		}
		deleteTree(pluginData);
	}

	private EventSpool open()
	{
		return open(EventSpool.ATOMIC, EventSpool.Limits.DEFAULT);
	}

	private EventSpool open(EventSpool.Mover mover, EventSpool.Limits limits)
	{
		EventSpool s = new EventSpool(pluginData, dir, clock::get, mover, limits, new EventSpool.Counters());
		opened.add(s);
		return s;
	}

	private static String id(int n)
	{
		return String.format("%032x", n);
	}

	private static String json(String id, String type)
	{
		return "{\"type\":\"" + type + "\",\"ts\":" + T0 + ",\"event_id\":\"" + id + "\",\"qty\":12345678901}";
	}

	private void append(EventSpool s, int n) throws IOException
	{
		assertTrue(s.append(id(n), "trade", clock.get(), json(id(n), "trade")));
	}

	private static List<String> ids(List<EventSpool.Record> records)
	{
		return records.stream().map(r -> r.eventId).collect(Collectors.toList());
	}

	// ------------------------------------------------------------------ format

	@Test
	public void appendThenReadRoundTripsHeaderFramesCrcAndExactNumbers() throws Exception
	{
		EventSpool s = open();
		append(s, 1);
		append(s, 2);
		append(s, 3);

		byte[] b = Files.readAllBytes(s.shardFile());
		assertEquals('O', b[0]);
		assertEquals('B', b[1]);
		assertEquals('S', b[2]);
		assertEquals('P', b[3]);
		assertEquals("format u16", EventSpool.FORMAT, ((b[4] & 0xff) << 8) | (b[5] & 0xff));

		EventSpool.Scan scan = EventSpool.scan(b);
		assertEquals(EventSpool.ScanStatus.CLEAN, scan.status);
		assertEquals(b.length, scan.validEnd);

		s.crashForTest();
		EventSpool r = open();
		assertEquals(3, r.recover());
		List<EventSpool.Record> pending = r.pending();
		assertEquals(Arrays.asList(id(1), id(2), id(3)), ids(pending));
		assertEquals("the wire JSON survives byte-exact, numbers included", json(id(2), "trade"),
			pending.get(1).eventJson);
		assertEquals(T0, pending.get(1).createdMs);
		assertEquals("trade", pending.get(1).type);
	}

	@Test
	public void crashAfterDurableAppendBeforePostReplaysOnceAndAnAckIsDurable() throws Exception
	{
		EventSpool a = open();
		append(a, 7);
		a.crashForTest();	// durable, never posted

		EventSpool b = open();
		assertEquals(1, b.recover());
		assertEquals(Collections.singletonList(id(7)), ids(b.pending()));
		assertTrue("a recovered record is marked so it replays ahead of new ones", b.pending().get(0).recovered);
		assertFalse("the abandoned shard is deleted once its rows live in this shard",
			Files.exists(a.shardFile()));
		assertEquals(1, b.ack(Collections.singletonList(id(7))));
		b.crashForTest();

		EventSpool c = open();
		assertEquals("an acked row never replays again", 0, c.recover());
		assertTrue(c.pending().isEmpty());
	}

	@Test
	public void tornTailIsTruncatedAndTheValidPrefixRecovered() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		append(a, 2);
		a.crashForTest();
		long good = Files.size(a.shardFile());
		byte[] partial = Arrays.copyOf(EventSpool.frame("{\"k\":\"ev\",\"half\":true}"), 11);
		Files.write(a.shardFile(), partial, java.nio.file.StandardOpenOption.APPEND);

		EventSpool.Scan scan = EventSpool.scan(a.shardFile());
		assertEquals(EventSpool.ScanStatus.TORN_TAIL, scan.status);
		assertEquals(good, scan.validEnd);

		EventSpool b = open();
		assertEquals(2, b.recover());
		assertEquals(Arrays.asList(id(1), id(2)), ids(b.pending()));
		assertEquals("a torn tail is not corruption", 0L, b.counters().corrupt.get());
	}

	@Test
	public void middleCrcCorruptionQuarantinesTheWholeFileAndAFreshShardStarts() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		append(a, 2);
		append(a, 3);
		a.crashForTest();
		byte[] b = Files.readAllBytes(a.shardFile());
		String text = new String(b, StandardCharsets.ISO_8859_1);
		int at = text.indexOf(id(2));
		assertTrue(at > 0);
		b[at] = (byte) (b[at] == 'a' ? 'b' : 'a');	// one flipped byte inside record 2
		Files.write(a.shardFile(), b);

		assertEquals(EventSpool.ScanStatus.CORRUPT, EventSpool.scan(a.shardFile()).status);

		EventSpool c = open();
		assertEquals("a file with middle corruption is never replayed", 0, c.recover());
		assertTrue(c.pending().isEmpty());
		assertEquals(1L, c.counters().corrupt.get());
		assertFalse(Files.exists(a.shardFile()));
		Path corrupt = dir.resolve("shard-" + a.shardId() + ".corrupt");
		assertTrue("renamed aside, not deleted silently", Files.exists(corrupt));

		append(c, 9);
		assertNotEquals(a.shardFile(), c.shardFile());
		assertEquals(Collections.singletonList(id(9)), ids(c.pending()));
	}

	// ------------------------------------------------------------------ compaction

	@Test
	public void crashDuringCompactionWithBothFilesKeepsNewestValidGenerationWithoutResurrection() throws Exception
	{
		EventSpool.Mover crashing = (from, to) -> { throw new SimulatedCrash(); };
		EventSpool a = open(crashing, EventSpool.Limits.DEFAULT);
		append(a, 1);
		append(a, 2);
		assertEquals(1, a.ack(Collections.singletonList(id(1))));
		try
		{
			a.compactNow();
		}
		catch (SimulatedCrash expected)
		{
			// the process dies between the forced .next and the rename
		}
		a.crashForTest();
		Path next = dir.resolve("shard-" + a.shardId() + ".spl.next");
		assertTrue(Files.exists(next));
		assertTrue(Files.exists(a.shardFile()));
		byte[] nextBytes = Files.readAllBytes(next);
		byte[] splBytes = Files.readAllBytes(a.shardFile());

		EventSpool b = open();
		assertEquals("newest valid generation: only the live row", Collections.singletonList(id(2)),
			idsAfterRecover(b));
		assertFalse(Files.exists(next));

		// Same crash, but the .next is torn: the older generation wins, and its tombstone still holds.
		b.crashForTest();
		deleteShards();
		Files.write(dir.resolve("shard-" + a.shardId() + ".spl"), splBytes);
		Files.write(next, Arrays.copyOf(nextBytes, nextBytes.length - 3));
		EventSpool c = open();
		assertEquals("torn .next is discarded; the acked row stays acked", Collections.singletonList(id(2)),
			idsAfterRecover(c));
	}

	@Test
	public void atomicMoveFailureFailsClosedAndKeepsTheOldFileByteIdentical() throws Exception
	{
		EventSpool.Mover refusing = (from, to) -> { throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test"); };
		EventSpool a = open(refusing, EventSpool.Limits.DEFAULT);
		append(a, 1);
		append(a, 2);
		a.ack(Collections.singletonList(id(1)));
		String before = sha(a.shardFile());

		assertFalse("a failed rename is not a successful compaction", a.compactNow());
		assertEquals("the old generation is untouched: no in-place rewrite", before, sha(a.shardFile()));
		assertFalse(Files.exists(dir.resolve("shard-" + a.shardId() + ".spl.next")));
		assertEquals(1L, a.counters().compactionFailed.get());

		append(a, 3);
		a.crashForTest();
		EventSpool b = open();
		assertEquals(Arrays.asList(id(2), id(3)), idsAfterRecover(b));
	}

	@Test
	public void successfulCompactionDropsAckedRowsFromTheFile() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		append(a, 2);
		a.ack(Collections.singletonList(id(1)));
		assertTrue(a.compactNow());
		String text = new String(Files.readAllBytes(a.shardFile()), StandardCharsets.UTF_8);
		assertFalse("the acked row is gone from disk", text.contains(id(1)));
		assertTrue(text.contains(id(2)));
		append(a, 3);
		a.crashForTest();
		assertEquals(Arrays.asList(id(2), id(3)), idsAfterRecover(open()));
	}

	// ------------------------------------------------------------------ bounds

	@Test
	public void recordBoundDropsTheOldestUndeliveredAndCountsIt() throws Exception
	{
		EventSpool a = open(EventSpool.ATOMIC, new EventSpool.Limits(5L * 1024 * 1024, 3, EventSpool.MAX_AGE_MS));
		for (int i = 1; i <= 5; i++)
		{
			clock.incrementAndGet();
			append(a, i);
		}
		assertEquals(Arrays.asList(id(3), id(4), id(5)), ids(a.pending()));
		assertEquals(2L, a.counters().dropped.get());
		a.crashForTest();
		assertEquals("the drop is durable", Arrays.asList(id(3), id(4), id(5)), idsAfterRecover(open()));
	}

	@Test
	public void byteBoundDropsTheOldest() throws Exception
	{
		int one = json(id(1), "trade").getBytes(StandardCharsets.UTF_8).length;
		EventSpool a = open(EventSpool.ATOMIC, new EventSpool.Limits(one * 2L + 1, 5000, EventSpool.MAX_AGE_MS));
		append(a, 1);
		append(a, 2);
		append(a, 3);
		assertEquals(Arrays.asList(id(2), id(3)), ids(a.pending()));
		assertEquals(1L, a.counters().dropped.get());
	}

	@Test
	public void defaultBoundsAre5MiB5000RecordsAnd48Hours()
	{
		assertEquals(5L * 1024 * 1024, EventSpool.Limits.DEFAULT.maxBytes);
		assertEquals(5000, EventSpool.Limits.DEFAULT.maxRecords);
		assertEquals(48L * 3600_000L, EventSpool.Limits.DEFAULT.maxAgeMs);
	}

	@Test
	public void rowsOlderThan48HoursAgeOutLiveAndOnRecovery() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		clock.addAndGet(EventSpool.MAX_AGE_MS + 1);
		append(a, 2);
		assertEquals(Collections.singletonList(id(2)), ids(a.pending()));
		assertEquals(1L, a.counters().dropped.get());

		append(a, 3);
		a.crashForTest();
		clock.addAndGet(EventSpool.MAX_AGE_MS + 1);
		EventSpool b = open();
		assertEquals("nothing older than 48 h is recovered", 0, b.recover());
	}

	@Test
	public void sweepDeletesForeignShardsWhoseNewestRecordIsOlderThan48Hours() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		a.crashForTest();
		Path shard = a.shardFile();
		clock.addAndGet(EventSpool.MAX_AGE_MS + 1);
		Files.setLastModifiedTime(shard, java.nio.file.attribute.FileTime.fromMillis(T0 - EventSpool.MAX_AGE_MS - 1));
		EventSpool.sweepAged(spoolRoot(), clock::get, EventSpool.MAX_AGE_MS);
		assertFalse(Files.exists(shard));
	}

	@Test
	public void sweepKeepsAShardWithAnOldMtimeWhenItsNewestRecordIsFresh() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		a.crashForTest();
		Path shard = a.shardFile();
		Files.setLastModifiedTime(shard, java.nio.file.attribute.FileTime.fromMillis(T0 - 3 * EventSpool.MAX_AGE_MS));
		EventSpool.sweepAged(spoolRoot(), clock::get, EventSpool.MAX_AGE_MS);
		assertTrue("age is decided by the record time, never the file time", Files.exists(shard));
	}

	@Test
	public void sweepDeletesAShardWithARecentMtimeWhenItsNewestRecordIsOld() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		a.crashForTest();
		Path shard = a.shardFile();
		clock.addAndGet(EventSpool.MAX_AGE_MS + 1);
		Files.setLastModifiedTime(shard, java.nio.file.attribute.FileTime.fromMillis(clock.get()));
		EventSpool.sweepAged(spoolRoot(), clock::get, EventSpool.MAX_AGE_MS);
		assertFalse("a fresh mtime does not keep old records alive", Files.exists(shard));
	}

	@Test
	public void sweepNeverTouchesALockedShardAndAgesATornShardByItsValidPrefix() throws Exception
	{
		EventSpool live = open();
		append(live, 1);
		EventSpool torn = open();
		append(torn, 2);
		torn.crashForTest();
		Files.write(torn.shardFile(), new byte[]{0, 0, 0, 40, 1, 2}, java.nio.file.StandardOpenOption.APPEND);
		clock.addAndGet(EventSpool.MAX_AGE_MS + 1);
		EventSpool.sweepAged(spoolRoot(), clock::get, EventSpool.MAX_AGE_MS);
		assertTrue("a live writer's shard is never swept", Files.exists(live.shardFile()));
		assertFalse("the torn shard's valid prefix is old, so it ages out", Files.exists(torn.shardFile()));
	}

	@Test
	public void sweepQuarantinesMiddleCorruptionInsteadOfTrustingIt() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		append(a, 2);
		a.crashForTest();
		byte[] b = Files.readAllBytes(a.shardFile());
		int at = new String(b, StandardCharsets.ISO_8859_1).indexOf(id(1));
		b[at] = (byte) (b[at] == 'a' ? 'b' : 'a');
		Files.write(a.shardFile(), b);
		EventSpool.Counters c = new EventSpool.Counters();
		EventSpool.sweepAged(spoolRoot(), clock::get, EventSpool.MAX_AGE_MS, c);
		assertFalse(Files.exists(a.shardFile()));
		assertTrue(Files.exists(dir.resolve("shard-" + a.shardId() + ".corrupt")));
		assertEquals(1L, c.corrupt.get());
	}

	private Path spoolRoot()
	{
		return pluginData.resolve("osrsbis").resolve("spool");
	}

	// ------------------------------------------------------------------ multi-process

	@Test
	public void secondProcessWritesItsOwnShardAndRecoversOnlyWhenTheLockIsFree() throws Exception
	{
		EventSpool p1 = open();
		append(p1, 1);
		String p1Before = sha(p1.shardFile());

		EventSpool p2 = open();
		assertEquals("a live shard is never touched", 0, p2.recover());
		append(p2, 2);
		assertNotEquals(p1.shardFile(), p2.shardFile());
		assertEquals(p1Before, sha(p1.shardFile()));

		p1.crashForTest();
		assertEquals(1, p2.recover());
		assertEquals(Arrays.asList(id(1), id(2)), ids(p2.pending()));
	}

	@Test
	public void purgeDeletesFreeShardsAndLeavesAMarkerForALiveOne() throws Exception
	{
		EventSpool live = open();
		append(live, 1);
		EventSpool dead = open();
		append(dead, 2);
		dead.crashForTest();

		assertFalse("a live shard blocks a full purge", EventSpool.purgeIdentityDir(dir, clock::get));
		assertTrue("the live writer's shard survives", Files.exists(live.shardFile()));
		assertFalse("a free shard is deleted", Files.exists(dead.shardFile()));
		assertTrue(Files.exists(dir.resolve(EventSpool.MARKER)));
		String marker = new String(Files.readAllBytes(dir.resolve(EventSpool.MARKER)), StandardCharsets.UTF_8);
		assertTrue(marker.contains("\"requested_ms\""));
		assertTrue(live.purgeRequested());
		assertFalse("a marked directory refuses new rows", live.append(id(3), "trade", T0, json(id(3), "trade")));

		live.closeAndDeleteOwn();
		assertTrue(EventSpool.purgeIdentityDir(dir, clock::get));
		assertFalse(Files.exists(dir));
	}

	// ------------------------------------------------------------------ identity + pointer

	@Test
	public void identityKeyIsA32HexDigestOfTokenAndAccountNeverTheToken()
	{
		String k = EventSpool.identityKey(TOKEN, "12345");
		assertTrue(k.matches("^[0-9a-f]{32}$"));
		assertFalse(k.contains(TOKEN.substring(0, 12)));
		assertEquals(AccountConnectPlugin.sha256Hex("spool-identity:" + TOKEN + ":12345").substring(0, 32), k);
		assertNotEquals(k, EventSpool.identityKey(TOKEN, "12346"));
		assertNotEquals(k, EventSpool.identityKey("fedcba9876543210fedcba9876543210", "12345"));
	}

	/**
	 * Round 3 (F1) replaced the single v1 pointer with a bounded v2 INDEX of identity keys this install has
	 * used, so a grant for one identity can still purge an older offline one. It holds only keys and times.
	 */
	@Test
	public void indexHoldsOnlyVersionIdentitiesAndTimesBoundedTo16() throws Exception
	{
		Path root = spoolRoot();
		String k = EventSpool.identityKey(TOKEN, "12345");
		EventSpool.writePointer(pluginData, root, k, T0);
		assertEquals(k, EventSpool.readPointer(root));
		assertEquals(Collections.singletonList(k), EventSpool.readIndex(root));
		String text = new String(Files.readAllBytes(root.resolve(EventSpool.POINTER)), StandardCharsets.UTF_8);
		assertEquals("{\"v\":2,\"identities\":[{\"identity\":\"" + k + "\",\"updated_ms\":" + T0 + "}]}", text);
		EventSpool.writePointer(pluginData, root, k, T0 + 5);
		assertEquals("an identity already indexed is not added twice", 1, EventSpool.readIndex(root).size());

		List<String> added = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			String other = EventSpool.identityKey(TOKEN, "acct" + i);
			added.add(other);
			EventSpool.writePointer(pluginData, root, other, T0 + i);
		}
		List<String> index = EventSpool.readIndex(root);
		assertEquals("bounded", EventSpool.MAX_INDEX, index.size());
		assertEquals(16, EventSpool.MAX_INDEX);
		assertEquals("the oldest entries are dropped", added.subList(4, 20), index);
		assertEquals("the latest identity", added.get(19), EventSpool.readPointer(root));

		EventSpool.deletePointerIf(root, "ffffffffffffffffffffffffffffffff");
		assertEquals(16, EventSpool.readIndex(root).size());
		for (String a : added)
		{
			EventSpool.deletePointerIf(root, a);
		}
		assertNull(EventSpool.readPointer(root));
		assertFalse("an empty index is deleted", Files.exists(root.resolve(EventSpool.POINTER)));
	}

	// ------------------------------------------------------------------ round 3

	@Test
	public void purgeIfIdleNeverTouchesALiveIdentityAndLeavesNoMarker() throws Exception
	{
		EventSpool live = open();
		append(live, 1);
		EventSpool dead = open();
		append(dead, 2);
		dead.crashForTest();

		assertFalse("an identity with a live shard is not purged", EventSpool.purgeIfIdle(dir));
		assertTrue(Files.exists(live.shardFile()));
		assertTrue("all or nothing: no shard is deleted while one is live", Files.exists(dead.shardFile()));
		assertFalse("and no marker is left", Files.exists(dir.resolve(EventSpool.MARKER)));

		live.crashForTest();
		assertTrue(EventSpool.purgeIfIdle(dir));
		assertFalse(Files.exists(dir));
	}

	@Test
	public void gracefulCloseInAMarkedDirectoryDeletesItsOwnShardAndFinishesThePurge() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		assertFalse(EventSpool.purgeIdentityDir(dir, clock::get));
		assertTrue(Files.exists(dir.resolve(EventSpool.MARKER)));
		a.close();
		assertFalse("nothing of a marked identity is left after a graceful close", Files.exists(dir));
	}

	@Test
	public void sweepFinishesAMarkedDirectoryWhoseShardsAreAllUnlockedAndKeepsOneWithALiveShard() throws Exception
	{
		EventSpool dead = open();
		append(dead, 1);
		dead.crashForTest();
		writeMarker();
		EventSpool.sweepAged(spoolRoot(), clock::get, EventSpool.MAX_AGE_MS);
		assertFalse("a marked directory with no live shard is finished by the sweep", Files.exists(dir));

		EventSpool live = open();
		append(live, 2);
		writeMarker();
		EventSpool.sweepAged(spoolRoot(), clock::get, EventSpool.MAX_AGE_MS);
		assertTrue(Files.exists(live.shardFile()));
		assertTrue(Files.exists(dir.resolve(EventSpool.MARKER)));
	}

	@Test
	public void compactionClosesTheShardHandleBeforeTheRenameAndReopensAfter() throws Exception
	{
		EventSpool[] holder = new EventSpool[1];
		List<Boolean> openAtMove = new ArrayList<>();
		EventSpool.Mover watching = (from, to) ->
		{
			openAtMove.add(holder[0].isOpen());
			EventSpool.ATOMIC.move(from, to);
		};
		EventSpool a = open(watching, EventSpool.Limits.DEFAULT);
		holder[0] = a;
		append(a, 1);
		append(a, 2);
		a.ack(Collections.singletonList(id(1)));
		assertTrue(a.compactNow());
		assertEquals("no handle on the shard while it is replaced (Windows)", Collections.singletonList(false), openAtMove);
		assertTrue("reopened after the rename", a.isOpen());
		append(a, 3);
		a.crashForTest();
		assertEquals(Arrays.asList(id(2), id(3)), idsAfterRecover(open()));
	}

	@Test
	public void aReopenFailureAfterCompactionFailsClosedIsCountedAndLosesNothing() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		append(a, 2);
		a.ack(Collections.singletonList(id(1)));
		a.setReopenerForTest(p -> { throw new IOException("reopen refused"); });
		try
		{
			a.compactNow();
			throw new AssertionError("a reopen failure must surface");
		}
		catch (IOException expected)
		{
			// fails closed
		}
		assertTrue(a.isFailed());
		assertEquals(1L, a.counters().reopenFailed.get());
		assertFalse("a failed shard takes no new rows", a.append(id(3), "trade", T0, json(id(3), "trade")));
		a.crashForTest();
		assertEquals("the compacted rows are recovered by the next instance", Collections.singletonList(id(2)),
			idsAfterRecover(open()));
	}

	private void writeMarker() throws IOException
	{
		Files.write(dir.resolve(EventSpool.MARKER), ("{\"v\":1,\"requested_ms\":" + T0 + "}").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void ownerOnlyPermissionsWhereThePlatformSupportsThem() throws Exception
	{
		EventSpool a = open();
		append(a, 1);
		if (!EventSpool.POSIX)
		{
			return;	// Windows: no POSIX view, recorded as such in health
		}
		assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
			Files.getPosixFilePermissions(a.shardFile())));
		assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
			Files.getPosixFilePermissions(dir)));
	}

	// ------------------------------------------------------------------ helpers

	private static final class SimulatedCrash extends RuntimeException
	{
	}

	private List<String> idsAfterRecover(EventSpool s) throws IOException
	{
		s.recover();
		return ids(s.pending());
	}

	private void deleteShards() throws IOException
	{
		try (Stream<Path> files = Files.list(dir))
		{
			for (Path p : files.collect(Collectors.toList()))
			{
				if (p.getFileName().toString().startsWith("shard-"))
				{
					Files.delete(p);
				}
			}
		}
	}

	static String sha(Path p) throws Exception
	{
		byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
		StringBuilder sb = new StringBuilder();
		for (byte x : d)
		{
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}

	static void deleteTree(Path root) throws IOException
	{
		if (root == null || !Files.exists(root))
		{
			return;
		}
		try (Stream<Path> walk = Files.walk(root))
		{
			for (Path p : walk.sorted((x, y) -> y.getNameCount() - x.getNameCount()).collect(Collectors.toList()))
			{
				Files.deleteIfExists(p);
			}
		}
	}
}
