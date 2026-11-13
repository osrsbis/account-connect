/*
 * OSRS Best in Slot — durable structured-event spool (staff only, server-granted).
 *
 * A pure file library: no RuneLite types, no network. The plugin drives it from ONE dedicated
 * single-thread writer, so an instance is confined to that thread and needs no locking of its own.
 * File locks are for OTHER processes (two RuneLite clients on one OS user), not for threads.
 *
 * LAYOUT. <plugin-data>/osrsbis/spool/<identity>/ where identity = first 32 hex of
 * sha256("spool-identity:" + token + ":" + accountHash). The token itself is never written anywhere:
 * not in a file, not in a file name, not in a directory name. Per process instance:
 *   shard-<16 hex>.spl       the shard: [magic "OBSP"][format u16] then records [len u32][crc32 u32][UTF-8 JSON]
 *   shard-<16 hex>.spl.next  compaction output, ATOMIC_MOVEd over the shard, never merged with it
 *   shard-<16 hex>.lock      held with an exclusive FileLock while the writer lives
 *   recovery.lock            taken while a process reads or deletes shards it does not own
 *   purge-requested          {"v":1,"requested_ms":N}: the identity was purged while a shard was still live
 * The identity directories themselves are the list of identities this install has used; there is no index file.
 * (0.7.69 and older kept one, active.json with index.lock; the sweep deletes the index and leaves the lock file.)
 *
 * RECORD KINDS (payload JSON, fixed layout written and read here only):
 *   {"v":1,"k":"ev","id":"<32 hex>","c":<created ms>,"t":"<type>","e":<wire event JSON, byte-exact>}
 *   {"v":1,"k":"ack","ids":["<32 hex>",...]}   tombstone: the ids are no longer pending (acked, dropped
 *                                                by a bound, or quarantined). Acks are tombstones in the
 *                                                same file; compaction later removes both rows.
 * 0.7.69 and older also ended a compaction output with {"v":1,"k":"end","n":<count>}; it is skipped.
 *
 * RECOVERY RULES. A torn or CRC-bad TAIL is cut off (the valid prefix is kept). A bad record that is
 * followed by ANY valid record is middle corruption: the shard is deleted, counted, and never replayed.
 * Compaction forces the .next before it moves it over the shard and never deletes the shard first, so a
 * .next beside its .spl is discarded: the .spl is complete. A lone .next is a 0.7.69 or older load that
 * died between deleting the .spl and moving the .next; it becomes the .spl when it scans clean, and so does
 * a clean .next beside a corrupt .spl (still counted; a row acked after that compaction may replay, and the
 * server dedupes by event_id). The two
 * are never merged, so an acked row cannot come back. 0.7.69 and older also kept quarantine.jsonl and
 * shard-*.corrupt files that nothing reads; the sweep deletes them.
 */
package com.osrsbestinslot.export;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.Collections;
import java.nio.file.LinkOption;
import lombok.AllArgsConstructor;

final class EventSpool
{
	static final byte[] MAGIC = {'O', 'B', 'S', 'P'};
	static final int FORMAT = 1;
	static final int HEADER = 6;
	static final long MAX_AGE_MS = 48L * 3600_000L;
	/** A frame longer than this is not one of ours: the length field itself is damaged. */
	static final int MAX_FRAME_PAYLOAD = 4 * 1024 * 1024;
	static final long COMPACT_DEAD_BYTES = 1024L * 1024L;
	static final long COMPACT_RETRY_MS = 60_000L;
	static final String MARKER = "purge-requested";
	static final String RECOVERY_LOCK = "recovery.lock";
	static final String QUARANTINE = "quarantine.jsonl";
	/**
	 * Owner-only permissions apply only where the file system has a POSIX view. On Windows this is
	 * false and the files inherit the user profile's ACL: plaintext, readable by that OS user.
	 */
	static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");
	private static final Pattern TYPE = Pattern.compile("^[a-z0-9_]{1,32}$");
	private static final Pattern EV_HEAD = Pattern.compile(
		"^\\{\"v\":1,\"k\":\"ev\",\"id\":\"([0-9a-f]{32})\",\"c\":(\\d{1,19}),\"t\":\"([a-z0-9_]{1,32})\",\"e\":");
	private static final Pattern ACK = Pattern.compile(
		"^\\{\"v\":1,\"k\":\"ack\",\"ids\":\\[(\"[0-9a-f]{32}\"(,\"[0-9a-f]{32}\")*)?\\]\\}$");
	private static final Pattern END = Pattern.compile("^\\{\"v\":1,\"k\":\"end\",\"n\":\\d{1,9}\\}$");
	private static final Pattern ID_IN = Pattern.compile("\"([0-9a-f]{32})\"");
	private static final Pattern SHARD_NAME = Pattern.compile("^shard-([0-9a-f]{16})\\.(spl|spl\\.next|lock|corrupt)$");

	/** The compaction rename. Production is ATOMIC_MOVE only; there is no in-place fallback. */
	interface Mover
	{
		void move(Path from, Path to) throws IOException;
	}

	static final Mover ATOMIC = (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);

	@AllArgsConstructor
	static final class Limits
	{
		static final Limits DEFAULT = new Limits(5L * 1024 * 1024, 5000, MAX_AGE_MS);
		final long maxBytes;
		final int maxRecords;
		final long maxAgeMs;
	}

	/** Cumulative, content-free counters. Shared by every spool instance of one plugin process. */
	static final class Counters
	{
		final AtomicLong corrupt = new AtomicLong();
		final AtomicLong dropped = new AtomicLong();
		final AtomicLong compactionFailed = new AtomicLong();
		final AtomicLong purged = new AtomicLong();
		final AtomicLong quarantined = new AtomicLong();
		final AtomicLong filtered = new AtomicLong();
		final AtomicLong acked = new AtomicLong();
		final AtomicLong replayed = new AtomicLong();
		final AtomicLong unacked2xx = new AtomicLong();
		final AtomicLong appendFailed = new AtomicLong();
		final AtomicLong reopenFailed = new AtomicLong();
		final AtomicLong gateTimeout = new AtomicLong();
		final AtomicLong replayAgedAtDispatch = new AtomicLong();
		final AtomicLong lastAckMs = new AtomicLong();
	}

	static final class Record
	{
		final String eventId;
		final String type;
		final String eventJson;
		final long createdMs;
		final boolean recovered;
		final int payloadBytes;

		Record(String eventId, String type, String eventJson, long createdMs, boolean recovered)
		{
			this.eventId = eventId;
			this.type = type;
			this.eventJson = eventJson;
			this.createdMs = createdMs;
			this.recovered = recovered;
			this.payloadBytes = eventJson.getBytes(StandardCharsets.UTF_8).length;
		}
	}

	enum ScanStatus
	{
		CLEAN, TORN_TAIL, CORRUPT
	}

	static final class Scan
	{
		ScanStatus status;
		long validEnd;
		final List<String> payloads = new ArrayList<>();
	}

	/** One shard generation after its tombstones are applied. */
	static final class Gen
	{
		boolean corrupt;
		final LinkedHashMap<String, Record> live = new LinkedHashMap<>();
		long newestCreated = -1L;
	}

	private final Path pluginData;
	private final Path dir;
	private final LongSupplier clock;
	private final Mover mover;
	private final Limits limits;
	final Counters counters;
	final String shardId;

	private FileChannel ch;
	private Locked ownLock;
	private boolean closed;
	private long fileBytes;
	private long liveFrameBytes;
	private long payloadBytes;
	private long noCompactBeforeMs;
	private LinkedHashMap<String, Record> pending = new LinkedHashMap<>();

	/** Creates nothing on disk. The directory and the shard appear only at the first durable write. */
	EventSpool(Path pluginData, Path dir, LongSupplier clock, Mover mover, Limits limits, Counters counters)
	{
		this.pluginData = pluginData;
		this.dir = dir;
		this.clock = clock;
		this.mover = mover;
		this.limits = limits;
		this.counters = counters;
		byte[] b = new byte[8];
		RANDOM.nextBytes(b);
		this.shardId = hex(b);
	}

	// ------------------------------------------------------------------ identity

	static String identityKey(String token, String accountHash)
	{
		return sha256Hex("spool-identity:" + token + ":" + accountHash).substring(0, 32);
	}

	static String newEventId()
	{
		byte[] b = new byte[16];
		RANDOM.nextBytes(b);
		return hex(b);
	}

	// ------------------------------------------------------------------ accessors

	Path shardFile()
	{
		return dir.resolve("shard-" + shardId + ".spl");
	}

	Path dir()
	{
		return dir;
	}

	boolean isOpen()
	{
		return ch != null;
	}

	boolean purgeRequested()
	{
		return Files.exists(dir.resolve(MARKER));
	}

	List<Record> pending()
	{
		return new ArrayList<>(pending.values());
	}

	int pendingCount()
	{
		return pending.size();
	}

	long pendingPayloadBytes()
	{
		return payloadBytes;
	}

	long oldestCreatedMs()
	{
		long oldest = -1L;
		for (Record r : pending.values())
		{
			if (oldest < 0 || r.createdMs < oldest)
			{
				oldest = r.createdMs;
			}
		}
		return oldest;
	}

	Record get(String eventId)
	{
		return pending.get(eventId);
	}

	// ------------------------------------------------------------------ write path

	/**
	 * Append one event durably. True only once the record is forced to disk. False when this spool is
	 * closed or its directory carries a purge marker; nothing is written then.
	 */
	boolean append(String eventId, String type, long createdMs, String eventJson) throws IOException
	{
		if (closed || failed || purgeRequested())
		{
			return false;
		}
		if (eventId == null || !HEX32.matcher(eventId).matches() || type == null || !TYPE.matcher(type).matches()
			|| eventJson == null || !isJsonObject(eventJson))
		{
			return false;
		}
		if (pending.containsKey(eventId))
		{
			return true;
		}
		Record r = new Record(eventId, type, eventJson, createdMs, false);
		ensureOpen();
		writeFrames(Collections.singletonList(evPayload(r)));
		put(pending, r);
		enforceBounds();
		return true;
	}

	/** Tombstone the listed ids that are still pending. Returns how many were removed. */
	int ack(Collection<String> eventIds) throws IOException
	{
		List<String> hit = new ArrayList<>();
		for (String id : eventIds)
		{
			if (id != null && pending.containsKey(id) && !hit.contains(id))
			{
				hit.add(id);
			}
		}
		tombstone(hit);
		return hit.size();
	}

	/** Terminal rejection: tombstone the rows and count them (spool_quarantined_total). Nothing else is kept. */
	int quarantine(Collection<String> eventIds) throws IOException
	{
		int n = ack(eventIds);
		counters.quarantined.addAndGet(n);
		return n;
	}

	/** Compact when acked bytes exceed a quarter of the file or 1 MiB. Called by the owner's tick. */
	void maybeCompact() throws IOException
	{
		if (ch == null || closed)
		{
			return;
		}
		long dead = fileBytes - HEADER - liveFrameBytes;
		if (dead <= 0 || clock.getAsLong() < noCompactBeforeMs)
		{
			return;
		}
		if (dead * 4 > fileBytes || dead > COMPACT_DEAD_BYTES)
		{
			if (!compactNow())
			{
				noCompactBeforeMs = clock.getAsLong() + COMPACT_RETRY_MS;
			}
		}
	}

	/**
	 * Rewrite the live rows to .next, force it, ATOMIC_MOVE it over the shard and force the directory.
	 * If the move is refused the old shard is kept exactly as it was (fail closed) and false is returned.
	 */
	boolean compactNow() throws IOException
	{
		if (ch == null || closed)
		{
			return true;
		}
		Path next = dir.resolve("shard-" + shardId + ".spl.next");
		List<String> payloads = new ArrayList<>();
		for (Record r : pending.values())
		{
			payloads.add(evPayload(r));
		}
		try (FileChannel n = open(next, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
		{
			long pos = 0;
			pos += writeFully(n, ByteBuffer.wrap(header()), pos);
			for (String p : payloads)
			{
				pos += writeFully(n, ByteBuffer.wrap(frame(p)), pos);
			}
			n.force(true);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(next);
			counters.compactionFailed.incrementAndGet();
			return false;
		}
		// Close OUR handle on the shard before it is replaced: Windows refuses to rename over a file that
		// is open. The shard lock file stays held the whole time, so no other process can take the shard
		// while no handle is open, and only this writer thread ever touches it.
		ch.close();
		ch = null;
		boolean moved;
		try
		{
			mover.move(next, shardFile());
			moved = true;
		}
		catch (IOException e)
		{
			Files.deleteIfExists(next);
			counters.compactionFailed.incrementAndGet();
			moved = false;	// fail closed: the old generation stays exactly as it was
		}
		if (moved)
		{
			forceDir(dir);
		}
		try
		{
			ch = reopener.reopen(shardFile());
		}
		catch (IOException | RuntimeException e)
		{
			// The data is safe on disk (old or new generation), but this writer can no longer append.
			// Fail closed: stop taking rows; the next process recovers the shard once our lock is gone.
			failed = true;
			counters.reopenFailed.incrementAndGet();
			throw e instanceof IOException ? (IOException) e : new IOException(e);
		}
		if (!moved)
		{
			return false;
		}
		fileBytes = ch.size();
		liveFrameBytes = fileBytes - HEADER;
		return true;
	}

	/** How the shard is reopened after a compaction. Production opens it read/write. */
	interface Reopener
	{
		FileChannel reopen(Path shard) throws IOException;
	}

	Reopener reopener = p -> FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE);
	private boolean failed;

	/** True once this writer can no longer append (a reopen failed). It takes no rows after that. */
	boolean isFailed()
	{
		return failed;
	}

	// ------------------------------------------------------------------ recovery

	/**
	 * Merge abandoned shards of THIS identity directory into this process. A shard whose lock is held
	 * belongs to a live writer and is never read. A marked directory is never replayed from. Recovered
	 * rows are re-written into this process's own shard (forced) before their source file is deleted,
	 * and they go ahead of rows this process generated itself. Returns the number of rows merged.
	 */
	int recover() throws IOException
	{
		if (closed || !Files.isDirectory(dir) || purgeRequested())
		{
			return 0;
		}
		Locked rl = lock(dir.resolve(RECOVERY_LOCK));
		if (rl == null)
		{
			return 0;
		}
		LinkedHashMap<String, Record> merged = new LinkedHashMap<>();
		try
		{
			long cutoff = clock.getAsLong() - limits.maxAgeMs;
			for (String sid : shardIds(dir))
			{
				if (sid.equals(shardId))
				{
					continue;
				}
				Locked sl = lockOther(dir, sid);
				if (sl == null)
				{
					continue;	// a live writer: never touch it
				}
				try
				{
					Gen g = load(dir, sid, counters);
					if (g.corrupt)
					{
						dropCorrupt(dir, sid, counters);
						continue;
					}
					List<Record> add = new ArrayList<>();
					for (Record r : g.live.values())
					{
						if (r.createdMs < cutoff)
						{
							counters.dropped.incrementAndGet();
						}
						else if (!pending.containsKey(r.eventId) && !merged.containsKey(r.eventId))
						{
							add.add(new Record(r.eventId, r.type, r.eventJson, r.createdMs, true));
						}
					}
					if (!add.isEmpty())
					{
						ensureOpen();
						List<String> payloads = new ArrayList<>();
						for (Record r : add)
						{
							payloads.add(evPayload(r));
						}
						writeFrames(payloads);
						for (Record r : add)
						{
							merged.put(r.eventId, r);
						}
					}
					Files.deleteIfExists(dir.resolve("shard-" + sid + ".spl"));
					Files.deleteIfExists(dir.resolve("shard-" + sid + ".spl.next"));
					forceDir(dir);
				}
				finally
				{
					sl.releaseAndDelete();
				}
			}
		}
		finally
		{
			rl.close();
		}
		if (!merged.isEmpty())
		{
			LinkedHashMap<String, Record> reordered = new LinkedHashMap<>();
			for (Record r : merged.values())
			{
				reordered.put(r.eventId, r);
			}
			for (Record r : pending.values())
			{
				reordered.put(r.eventId, r);
			}
			pending = reordered;
			for (Record r : merged.values())
			{
				payloadBytes += r.payloadBytes;
				liveFrameBytes += frame(evPayload(r)).length;
			}
			enforceBounds();
		}
		return merged.size();
	}

	// ------------------------------------------------------------------ shutdown + purge

	/**
	 * Graceful close: rows stay on disk for the next process. EXCEPT in a marked directory: the identity
	 * was purged while we held the shard, so our shard is deleted too and, when no other live shard is
	 * left, the purge is finished (marker and directory gone). Nothing of a purged identity survives a close.
	 */
	void close()
	{
		if (!closed && purgeRequested())
		{
			closeAndDeleteOwn();
			try
			{
				purgeIfIdle(dir);
			}
			catch (IOException ignored)
			{
				// the sweep finishes it later
			}
			return;
		}
		closed = true;
		closeQuietly();
	}

	/** Process death: drop channels and the lock, write nothing else. */
	void crashForTest()
	{
		closed = true;
		closeQuietly();
	}

	/** Stop, and delete this process's own shard and lock. Other shards are not touched. */
	void closeAndDeleteOwn()
	{
		closed = true;
		closeQuietly();
		try
		{
			Files.deleteIfExists(shardFile());
			Files.deleteIfExists(dir.resolve("shard-" + shardId + ".spl.next"));
			Files.deleteIfExists(lockPath(dir, shardId));
		}
		catch (IOException ignored)
		{
			// best effort; the age-out sweep removes anything left behind
		}
		pending.clear();
		payloadBytes = 0;
	}

	private void closeQuietly()
	{
		try
		{
			if (ch != null)
			{
				ch.close();
			}
		}
		catch (IOException ignored)
		{
			// closing
		}
		ch = null;
		if (ownLock != null)
		{
			ownLock.close();
			ownLock = null;
		}
	}

	/**
	 * Purge one identity directory. Takes the recovery lock, deletes every shard whose lock can be
	 * acquired, and NEVER deletes a shard another live process holds: for those it leaves a purge
	 * marker, which makes that writer stop and delete its own shard. True when the directory is gone.
	 * Creates nothing when the directory does not exist.
	 */
	static boolean purgeIdentityDir(Path dir, LongSupplier clock) throws IOException
	{
		if (!Files.isDirectory(dir))
		{
			return true;
		}
		Locked rl = lock(dir.resolve(RECOVERY_LOCK));
		if (rl == null)
		{
			writeMarker(dir, clock.getAsLong());
			return false;
		}
		boolean live = false;
		try
		{
			for (String sid : shardIds(dir))
			{
				Locked sl = lockOther(dir, sid);
				if (sl == null)
				{
					live = true;
					continue;
				}
				try
				{
					deleteShardFiles(dir, sid);
				}
				finally
				{
					sl.releaseAndDelete();
				}
			}
			Files.deleteIfExists(dir.resolve(QUARANTINE));
			Files.deleteIfExists(dir.resolve(QUARANTINE + ".next"));
			if (live)
			{
				writeMarker(dir, clock.getAsLong());
				return false;
			}
			Files.deleteIfExists(dir.resolve(MARKER));
		}
		finally
		{
			rl.releaseAndDelete();
		}
		return deleteDirIfEmpty(dir);
	}

	/**
	 * Purge an identity ONLY when no live writer holds any of its shards: all or nothing, and never a marker.
	 * This is the purge used when ANOTHER identity becomes active (two clients on one OS user may each hold a
	 * different identity, and neither may cost the other its rows). A live identity is left untouched and is
	 * retried at the next grant or sweep. True when the directory is gone (or never existed).
	 */
	static boolean purgeIfIdle(Path dir) throws IOException
	{
		if (!Files.isDirectory(dir))
		{
			return true;
		}
		Locked rl = lock(dir.resolve(RECOVERY_LOCK));
		if (rl == null)
		{
			return false;
		}
		List<Locked> held = new ArrayList<>();
		boolean live = false;
		try
		{
			for (String sid : shardIds(dir))
			{
				Locked sl = lockOther(dir, sid);
				if (sl == null)
				{
					live = true;
					break;
				}
				held.add(sl);
			}
			if (live)
			{
				for (Locked sl : held)
				{
					sl.close();
				}
				held.clear();
				return false;
			}
			for (String sid : shardIds(dir))
			{
				deleteShardFiles(dir, sid);
			}
			deleteIdentityFiles(dir);
		}
		finally
		{
			for (Locked sl : held)
			{
				sl.releaseAndDelete();
			}
			rl.releaseAndDelete();
		}
		return deleteDirIfEmpty(dir);
	}

	private static void deleteShardFiles(Path dir, String sid) throws IOException
	{
		Files.deleteIfExists(dir.resolve("shard-" + sid + ".spl"));
		Files.deleteIfExists(dir.resolve("shard-" + sid + ".spl.next"));
		Files.deleteIfExists(dir.resolve("shard-" + sid + ".corrupt"));
	}

	private static void deleteIdentityFiles(Path dir) throws IOException
	{
		Files.deleteIfExists(dir.resolve(QUARANTINE));
		Files.deleteIfExists(dir.resolve(QUARANTINE + ".next"));
		Files.deleteIfExists(dir.resolve(MARKER));
	}

	/** True when the directory is gone; false when another process put something in it meanwhile. */
	private static boolean deleteDirIfEmpty(Path dir) throws IOException
	{
		try
		{
			Files.deleteIfExists(dir);
			return true;
		}
		catch (DirectoryNotEmptyException e)
		{
			return false;
		}
	}

	private static void writeMarker(Path dir, long nowMs) throws IOException
	{
		writeForced(dir.resolve(MARKER), "{\"v\":1,\"requested_ms\":" + nowMs + "}");
	}

	/** Create or truncate, write the whole text and force it to disk before the caller moves or relies on it. */
	private static void writeForced(Path p, String text) throws IOException
	{
		try (FileChannel c = open(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
		{
			writeFully(c, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)), 0L);
			c.force(true);
		}
	}

	/**
	 * Age out abandoned shards in every identity directory under the spool root. Age is the created
	 * time of the newest valid durable record, never the file time. A shard whose lock is held is
	 * skipped; middle corruption is dropped and counted exactly as recovery does; a torn shard is aged by
	 * its valid prefix. A directory left with no shard is removed.
	 */
	static void sweepAged(Path root, LongSupplier clock, long maxAgeMs, Counters counters) throws IOException
	{
		if (!Files.isDirectory(root))
		{
			return;
		}
		retireLegacyIndex(root);
		long cutoff = clock.getAsLong() - maxAgeMs;
		for (String name : identities(root))
		{
			Path d = root.resolve(name);
			// A marked directory is a purge someone could not finish because a shard was live. Finish it
			// now if every shard is free; if one is still live, leave it to its writer.
			if (Files.exists(d.resolve(MARKER)))
			{
				purgeIfIdle(d);
				continue;
			}
			Locked rl = lock(d.resolve(RECOVERY_LOCK));
			if (rl == null)
			{
				continue;
			}
			boolean empty;
			try
			{
				for (String sid : shardIds(d))
				{
					Locked sl = lockOther(d, sid);
					if (sl == null)
					{
						continue;
					}
					try
					{
						Path spl = d.resolve("shard-" + sid + ".spl");
						Path next = d.resolve("shard-" + sid + ".spl.next");
						if (Files.exists(spl) || Files.exists(next))
						{
							Gen g = load(d, sid, counters);
							if (g.corrupt)
							{
								dropCorrupt(d, sid, counters);
							}
							else if (g.live.isEmpty() || g.newestCreated < cutoff)
							{
								Files.deleteIfExists(spl);
								Files.deleteIfExists(next);
							}
						}
						Files.deleteIfExists(d.resolve("shard-" + sid + ".corrupt"));
					}
					finally
					{
						if (hasShardData(d, sid))
						{
							sl.close();
						}
						else
						{
							sl.releaseAndDelete();
						}
					}
				}
				Files.deleteIfExists(d.resolve(QUARANTINE));	// written by 0.7.69 and older, read by nothing
				Files.deleteIfExists(d.resolve(QUARANTINE + ".next"));
				empty = shardIds(d).isEmpty();
				if (empty)
				{
					deleteIdentityFiles(d);
				}
			}
			finally
			{
				rl.releaseAndDelete();
			}
			if (empty)
			{
				deleteDirIfEmpty(d);	// false: another process created something meanwhile
			}
		}
	}

	// ------------------------------------------------------------------ identities

	/**
	 * Every identity directory under a root, by name (32 hex, never a token). The directories are the list: a
	 * grant for one identity purges the OTHER idle ones, and a cold withdrawal finds them all, with no index
	 * file to keep in step. Empty when the root is absent or unreadable. A symbolic link is never followed.
	 */
	static List<String> identities(Path root)
	{
		List<String> out = new ArrayList<>();
		if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
		{
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(root))
			{
				for (Path d : ds)
				{
					if (HEX32.matcher(d.getFileName().toString()).matches() && Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS))
					{
						out.add(d.getFileName().toString());
					}
				}
			}
			catch (IOException e)
			{
				out.clear();
			}
		}
		return out;
	}

	/**
	 * Delete the identity index a 0.7.69 or older client kept (active.json and its .next). Only under that client's
	 * own index.lock, so an older client still running on this OS user is never cut off mid-write. The lock file
	 * itself stays: those clients rely on it keeping one inode.
	 */
	private static void retireLegacyIndex(Path root) throws IOException
	{
		if (!Files.exists(root.resolve("active.json")) && !Files.exists(root.resolve("active.json.next")))
		{
			return;
		}
		Locked il = lock(root.resolve("index.lock"));
		if (il == null)
		{
			return;	// an older client holds it; a later sweep retries
		}
		try
		{
			Files.deleteIfExists(root.resolve("active.json"));
			Files.deleteIfExists(root.resolve("active.json.next"));
		}
		finally
		{
			il.close();
		}
	}

	// ------------------------------------------------------------------ codec

	static byte[] header()
	{
		return new byte[]{MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], (byte) (FORMAT >>> 8), (byte) FORMAT};
	}

	static byte[] frame(String payload)
	{
		byte[] p = payload.getBytes(StandardCharsets.UTF_8);
		CRC32 crc = new CRC32();
		crc.update(p, 0, p.length);
		ByteBuffer b = ByteBuffer.allocate(8 + p.length);
		b.putInt(p.length);
		b.putInt((int) crc.getValue());
		b.put(p);
		return b.array();
	}

	static Scan scan(Path file) throws IOException
	{
		return scan(Files.readAllBytes(file));
	}

	static Scan scan(byte[] b)
	{
		Scan s = new Scan();
		// A short file that is a prefix of the header is a torn tail; any other header mismatch is corrupt.
		int n = Math.min(b.length, HEADER);
		boolean prefix = Arrays.equals(b, 0, n, header(), 0, n);
		if (!prefix || n < HEADER)
		{
			s.status = prefix ? ScanStatus.TORN_TAIL : ScanStatus.CORRUPT;
			s.validEnd = 0;
			return s;
		}
		int pos = HEADER;
		while (true)
		{
			if (pos == b.length)
			{
				s.status = ScanStatus.CLEAN;
				s.validEnd = pos;
				return s;
			}
			String p = frameAt(b, pos);
			if (p == null)
			{
				s.status = anyValidFrameAfter(b, pos + 1) ? ScanStatus.CORRUPT : ScanStatus.TORN_TAIL;
				s.validEnd = pos;
				return s;
			}
			s.payloads.add(p);
			pos += 8 + p.getBytes(StandardCharsets.UTF_8).length;
		}
	}

	/** The payload of a well-formed frame at pos, or null. */
	private static String frameAt(byte[] b, int pos)
	{
		if (b.length - pos < 8)
		{
			return null;
		}
		long len = ByteBuffer.wrap(b, pos, 4).getInt() & 0xffffffffL;
		if (len < 2 || len > MAX_FRAME_PAYLOAD || len > b.length - pos - 8L)
		{
			return null;
		}
		int n = (int) len;
		int start = pos + 8;
		if (b[start] != '{' || b[start + n - 1] != '}')
		{
			return null;
		}
		CRC32 crc = new CRC32();
		crc.update(b, start, n);
		int stored = ByteBuffer.wrap(b, pos + 4, 4).getInt();
		if ((int) crc.getValue() != stored)
		{
			return null;
		}
		try
		{
			CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(b, start, n));
			return cb.toString();
		}
		catch (CharacterCodingException e)
		{
			return null;
		}
	}

	/** Is there any well-formed frame starting after a bad one? Then the damage is in the middle. */
	private static boolean anyValidFrameAfter(byte[] b, int from)
	{
		for (int q = from; q <= b.length - 10; q++)
		{
			if (frameAt(b, q) != null)
			{
				return true;
			}
		}
		return false;
	}

	/** Load one shard id, tombstones applied. Never merges two files. */
	static Gen load(Path dir, String sid, Counters counters) throws IOException
	{
		Path spl = dir.resolve("shard-" + sid + ".spl");
		Path next = dir.resolve("shard-" + sid + ".spl.next");
		if (Files.exists(next))
		{
			boolean splCorrupt = Files.exists(spl) && scan(spl).status == ScanStatus.CORRUPT;
			if (Files.exists(spl) && !splCorrupt)
			{
				Files.deleteIfExists(next);	// an unfinished compaction: the shard it was built from is complete
			}
			else if (scan(next).status == ScanStatus.CLEAN)
			{
				// A lone .next (0.7.69 and older died between delete and move), or a clean compaction output
				// beside a corrupt shard, which would replay nothing. The corrupt shard is still counted.
				Files.move(next, spl, StandardCopyOption.ATOMIC_MOVE);
				if (splCorrupt)
				{
					counters.corrupt.incrementAndGet();
				}
			}
		}
		Gen g = new Gen();
		if (!Files.exists(spl))
		{
			return g;
		}
		Scan s = scan(spl);
		if (s.status == ScanStatus.CORRUPT)
		{
			g.corrupt = true;
			return g;
		}
		Gen applied = apply(s.payloads);
		return applied == null ? corruptGen() : applied;
	}

	private static Gen corruptGen()
	{
		Gen g = new Gen();
		g.corrupt = true;
		return g;
	}

	/** Apply records in order. Returns null when a record that passed the CRC is still not one of ours. */
	private static Gen apply(List<String> payloads)
	{
		Gen g = new Gen();
		for (String p : payloads)
		{
			Matcher m = EV_HEAD.matcher(p);
			if (m.find())
			{
				String json = p.substring(m.end(), p.length() - 1);
				if (!isJsonObject(json))
				{
					return null;
				}
				Record r = new Record(m.group(1), m.group(3), json, Long.parseLong(m.group(2)), false);
				g.live.put(r.eventId, r);
				g.newestCreated = Math.max(g.newestCreated, r.createdMs);
				continue;
			}
			if (ACK.matcher(p).matches())
			{
				Matcher ids = ID_IN.matcher(p);
				while (ids.find())
				{
					g.live.remove(ids.group(1));
				}
				continue;
			}
			if (!END.matcher(p).matches())
			{
				return null;
			}
		}
		return g;
	}

	/** Middle corruption: the shard is never replayed. Delete it and count it (spool_corrupt_total). */
	private static void dropCorrupt(Path dir, String sid, Counters counters) throws IOException
	{
		deleteShardFiles(dir, sid);
		counters.corrupt.incrementAndGet();
	}

	// ------------------------------------------------------------------ internals

	private void ensureOpen() throws IOException
	{
		if (ch != null)
		{
			return;
		}
		if (closed)
		{
			throw new IOException("spool closed");
		}
		createOwnerOnlyDirs(pluginData, dir);
		if (ownLock == null)
		{
			ownLock = lock(lockPath(dir, shardId));
			// A purge in another process may have deleted the lock file between our open and our tryLock.
			// Then we hold a lock on an unlinked file: refuse to write rather than create an unlocked shard.
			if (ownLock == null || !Files.exists(lockPath(dir, shardId)) || purgeRequested())
			{
				if (ownLock != null)
				{
					ownLock.close();
					ownLock = null;
				}
				throw new IOException("shard lock unavailable");
			}
		}
		ch = open(shardFile(), StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
		writeFully(ch, ByteBuffer.wrap(header()), 0L);
		ch.force(true);
		forceDir(dir);
		// The identity directory is durable in its root before the first row: after a crash the next process
		// still finds it, and can purge it without the token.
		forceDir(dir.getParent());
		fileBytes = HEADER;
		liveFrameBytes = 0;
	}

	private void writeFrames(List<String> payloads) throws IOException
	{
		if (payloads.isEmpty())
		{
			return;
		}
		ByteArrayOutputStream all = new ByteArrayOutputStream();
		for (String p : payloads)
		{
			all.writeBytes(frame(p));
		}
		byte[] bytes = all.toByteArray();
		writeFully(ch, ByteBuffer.wrap(bytes), fileBytes);
		ch.force(false);
		fileBytes += bytes.length;
	}

	private void put(Map<String, Record> into, Record r)
	{
		into.put(r.eventId, r);
		payloadBytes += r.payloadBytes;
		liveFrameBytes += frame(evPayload(r)).length;
	}

	private void tombstone(List<String> ids) throws IOException
	{
		if (ids.isEmpty())
		{
			return;
		}
		List<String> payloads = new ArrayList<>();
		for (int i = 0; i < ids.size(); i += 500)
		{
			StringBuilder sb = new StringBuilder("{\"v\":1,\"k\":\"ack\",\"ids\":[");
			for (int j = i; j < Math.min(ids.size(), i + 500); j++)
			{
				if (j > i)
				{
					sb.append(',');
				}
				sb.append('"').append(ids.get(j)).append('"');
			}
			payloads.add(sb.append("]}").toString());
		}
		writeFrames(payloads);
		for (String id : ids)
		{
			Record r = pending.remove(id);
			if (r != null)
			{
				payloadBytes -= r.payloadBytes;
				liveFrameBytes -= frame(evPayload(r)).length;
			}
		}
	}

	/** Enforce the age, record and byte bounds now (durable tombstones, counted). The replay calls it first. */
	void enforceBoundsNow() throws IOException
	{
		if (ch != null && !closed && !failed && !pending.isEmpty())
		{
			enforceBounds();
		}
	}

	/** Age, record-count and byte bounds. Drops the OLDEST undelivered rows, durably. */
	private void enforceBounds() throws IOException
	{
		long cutoff = clock.getAsLong() - limits.maxAgeMs;
		List<String> drop = new ArrayList<>();
		Set<String> dropSet = new HashSet<>();
		long bytes = payloadBytes;
		int count = pending.size();
		for (Record r : pending.values())
		{
			if (r.createdMs < cutoff)
			{
				drop.add(r.eventId);
				dropSet.add(r.eventId);
				bytes -= r.payloadBytes;
				count--;
			}
		}
		for (Record r : pending.values())
		{
			if (count <= limits.maxRecords && bytes <= limits.maxBytes)
			{
				break;
			}
			if (dropSet.contains(r.eventId))
			{
				continue;
			}
			drop.add(r.eventId);
			dropSet.add(r.eventId);
			bytes -= r.payloadBytes;
			count--;
		}
		if (!drop.isEmpty())
		{
			tombstone(drop);
			counters.dropped.addAndGet(drop.size());
		}
	}

	static String evPayload(Record r)
	{
		return "{\"v\":1,\"k\":\"ev\",\"id\":\"" + r.eventId + "\",\"c\":" + r.createdMs + ",\"t\":\"" + r.type
			+ "\",\"e\":" + r.eventJson + "}";
	}

	private static boolean isJsonObject(String json)
	{
		try (JsonReader r = new JsonReader(new StringReader(json)))
		{
			if (r.peek() != JsonToken.BEGIN_OBJECT)
			{
				return false;
			}
			r.skipValue();
			return r.peek() == JsonToken.END_DOCUMENT;
		}
		catch (IOException | RuntimeException e)
		{
			return false;
		}
	}

	private static Set<String> shardIds(Path dir) throws IOException
	{
		Set<String> ids = new TreeSet<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir))
		{
			for (Path p : ds)
			{
				Matcher m = SHARD_NAME.matcher(p.getFileName().toString());
				if (m.matches())
				{
					ids.add(m.group(1));
				}
			}
		}
		return ids;
	}

	private static Path lockPath(Path dir, String sid)
	{
		return dir.resolve("shard-" + sid + ".lock");
	}

	/**
	 * Lock another process's shard. A writer takes its lock BEFORE it creates its .spl, so when a .spl (or
	 * .next / .corrupt) exists its owner either holds the lock or is dead, and creating the lock file is
	 * safe. A lock-only entry may belong to a writer between open and tryLock: never create it then.
	 */
	private static Locked lockOther(Path dir, String sid) throws IOException
	{
		return lock(lockPath(dir, sid), hasShardData(dir, sid));
	}

	private static boolean hasShardData(Path dir, String sid)
	{
		return Files.exists(dir.resolve("shard-" + sid + ".spl"))
			|| Files.exists(dir.resolve("shard-" + sid + ".spl.next"))
			|| Files.exists(dir.resolve("shard-" + sid + ".corrupt"));
	}

	/** An exclusive lock, or null when another holder (this JVM or another process) has it. */
	static Locked lock(Path file) throws IOException
	{
		return lock(file, true);
	}

	/**
	 * create=false is how a process locks ANOTHER process's shard lock: it never re-creates a lock file that
	 * its owner may be about to take, so an owner that finds its lock file gone after tryLock knows it lost
	 * that race and refuses to write. Returns null when the file is absent or held.
	 */
	static Locked lock(Path file, boolean create) throws IOException
	{
		FileChannel c;
		try
		{
			c = create ? open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
				: FileChannel.open(file, StandardOpenOption.WRITE);
		}
		catch (NoSuchFileException e)
		{
			return null;
		}
		try
		{
			FileLock l = c.tryLock();
			if (l == null)
			{
				c.close();
				return null;
			}
			return new Locked(file, c, l);
		}
		catch (OverlappingFileLockException e)
		{
			c.close();
			return null;
		}
		catch (IOException | RuntimeException e)
		{
			c.close();
			throw e;
		}
	}

	@AllArgsConstructor
	static final class Locked
	{
		private final Path file;
		private final FileChannel channel;
		private final FileLock lock;

		void close()
		{
			try
			{
				lock.release();
			}
			catch (IOException ignored)
			{
				// closing the channel releases it too
			}
			try
			{
				channel.close();
			}
			catch (IOException ignored)
			{
				// closing
			}
		}

		void releaseAndDelete()
		{
			close();
			try
			{
				Files.deleteIfExists(file);
			}
			catch (IOException ignored)
			{
				// Windows may refuse while another handle is open; harmless, re-used next time
			}
		}
	}

	private static FileAttribute<?>[] fileAttrs()
	{
		return POSIX
			? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
			: new FileAttribute<?>[0];
	}

	private static FileChannel open(Path p, OpenOption... options) throws IOException
	{
		Set<OpenOption> set = new HashSet<>(Arrays.asList(options));
		return FileChannel.open(p, set, fileAttrs());
	}

	/** Create every directory from pluginData down to target, owner-only where POSIX applies. */
	static void createOwnerOnlyDirs(Path pluginData, Path target) throws IOException
	{
		Files.createDirectories(pluginData);
		Path rel = pluginData.relativize(target);
		Path cur = pluginData;
		for (Path part : rel)
		{
			cur = cur.resolve(part);
			if (Files.isDirectory(cur))
			{
				continue;
			}
			try
			{
				if (POSIX)
				{
					Files.createDirectory(cur, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
				}
				else
				{
					Files.createDirectory(cur);
				}
			}
			catch (FileAlreadyExistsException ignored)
			{
				// another process created it
			}
		}
	}

	private static void forceDir(Path dir)
	{
		try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ))
		{
			d.force(true);
		}
		catch (IOException | RuntimeException ignored)
		{
			// not supported on every platform (Windows); the file forces already ran
		}
	}

	private static long writeFully(FileChannel c, ByteBuffer b, long pos) throws IOException
	{
		long written = 0;
		while (b.hasRemaining())
		{
			int n = pos < 0 ? c.write(b) : c.write(b, pos + written);
			written += n;
		}
		return written;
	}

	/** Lowercase hex SHA-256 of the UTF-8 text: the one fingerprint the plugin uses (Guava, shipped by the client). */
	static String sha256Hex(String s)
	{
		return Hashing.sha256().hashString(s, StandardCharsets.UTF_8).toString();
	}

	private static String hex(byte[] b)
	{
		return BaseEncoding.base16().lowerCase().encode(b);
	}
}
