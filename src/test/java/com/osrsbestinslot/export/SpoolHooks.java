package com.osrsbestinslot.export;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Test hooks for the spool writer thread (same package only); they were members of SpoolDriver. */
final class SpoolHooks
{
	private SpoolHooks()
	{
	}

	static void dataRoot(SpoolDriver s, Path root)
	{
		s.spoolDataRootOverride = root;
	}

	static int durabilityPendingCount(SpoolDriver s)
	{
		return s.spoolDurabilityPending.size();
	}

	/** Simulate process death: drop the shard handle and lock, write nothing, do nothing ever again. */
	static void crash(SpoolDriver s) throws Exception
	{
		ExecutorService e = s.spoolExec;
		if (e == null || e.isShutdown())
		{
			s.spoolDead = true;
			return;
		}
		AccountConnectPlugin plugin = s.plugin;
		e.submit(() ->
		{
			if (plugin.outbox.publicOutbox != null) plugin.outbox.publicOutbox.crashForTest();
			if (plugin.outbox.publicBankOutbox != null) plugin.outbox.publicBankOutbox.crashForTest();
			if (s.staffJournal != null) s.staffJournal.crashForTest();
			s.spoolDead = true;
		}).get(5, TimeUnit.SECONDS);
		e.shutdown();
	}

	static void awaitIdle(SpoolDriver s) throws Exception
	{
		ExecutorService e = s.spoolExec;
		if (e != null && !e.isShutdown())
		{
			e.submit(() -> { }).get(5, TimeUnit.SECONDS);
		}
	}

	private static <T> T onSpoolThreadAndWait(SpoolDriver s, Callable<T> c, T empty) throws Exception
	{
		ExecutorService e = s.spoolExec;
		if (e == null || e.isShutdown())
		{
			return empty;
		}
		return e.submit(c).get(5, TimeUnit.SECONDS);
	}

	static List<String> pendingIds(SpoolDriver s) throws Exception
	{
		return onSpoolThreadAndWait(s, () ->
		{
			List<String> out = new ArrayList<>();
			EventSpool spool = s.spool();
			if (spool != null)
			{
				for (EventSpool.Record r : spool.pending())
				{
					out.add(r.eventId);
				}
			}
			return out;
		}, new ArrayList<>());
	}

	static List<String> replayCandidates(SpoolDriver s) throws Exception
	{
		return onSpoolThreadAndWait(s, () ->
		{
			List<String> out = new ArrayList<>();
			for (EventSpool.Record r : s.spoolReplayCandidates())
			{
				out.add(r.eventId);
			}
			return out;
		}, new ArrayList<>());
	}

	static Path shardFile(SpoolDriver s) throws Exception
	{
		return onSpoolThreadAndWait(s, () -> s.spool() == null ? null : s.spool().shardFile(), null);
	}

	static void recoverAbandonedShards(SpoolDriver s) throws Exception
	{
		onSpoolThreadAndWait(s, () -> s.spool() == null ? 0 : s.spool().recover(), 0);
	}
}
