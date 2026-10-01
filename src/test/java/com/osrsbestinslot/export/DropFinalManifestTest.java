package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DropFinalManifestTest
{
	private static final String TEST_TOKEN = "0123456789abcdef0123456789abcdef";

	@Test
	public void finalManifestReconcilesOneSession() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		AccountConnectPlugin.DropSessionAudit audit =
			new AccountConnectPlugin.DropSessionAudit("sessiona", "123", "Worker", plugin.currentEventTokenFingerprint(), 1000L);
		audit.declaredSegments = 2;
		audit.declaredDrops = 2;
		audit.frames = 120;
		audit.dropEvents.set(2);
		audit.removals.set(2);
		audit.resolvedCounterparties.set(1);
		audit.segmentResults.put(0, "uploaded");
		audit.segmentResults.put(1, "uploaded");
		audit.recorderComplete = true;
		audit.endedAtMillis = 2000L;
		audit.closed = true;
		audits(plugin).put(audit.sessionId, audit);

		invokeFinal(plugin, audit.sessionId);

		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("drop_trade_clip_final", ev.get("type"));
		assertEquals("FINAL", ev.get("manifest_stage"));
		assertEquals("COMPLETE", ev.get("proof_status"));
		assertEquals(2, ev.get("drop_events"));
		assertEquals(2, ev.get("ground_removals"));
		assertEquals(1, ev.get("counterparties_resolved"));
		assertEquals(0, ev.get("missing_segments"));
		assertFalse(audits(plugin).containsKey(audit.sessionId));
	}

	@Test
	public void rejectedFrameMakesFinalManifestPartial() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		AccountConnectPlugin.DropSessionAudit audit =
			new AccountConnectPlugin.DropSessionAudit("sessionb", "456", "Worker2", plugin.currentEventTokenFingerprint(), 3000L);
		audit.declaredSegments = 1;
		audit.declaredDrops = 1;
		audit.frames = 60;
		audit.framesRejected = 1;
		audit.dropEvents.set(1);
		audit.segmentResults.put(0, "uploaded");
		audit.recorderComplete = true;
		audit.closed = true;
		audits(plugin).put(audit.sessionId, audit);

		invokeFinal(plugin, audit.sessionId);

		Map<String, Object> ev = plugin.pendingEvents.get(0);
		assertEquals("PARTIAL", ev.get("proof_status"));
		assertEquals(1, ev.get("frames_rejected"));
	}

	@Test
	public void finalManifestCannotCrossAChangedLinkToken() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		String oldFingerprint = plugin.currentEventTokenFingerprint();
		AccountConnectPlugin.DropSessionAudit audit =
			new AccountConnectPlugin.DropSessionAudit("sessionc", "789", "Worker3", oldFingerprint, 4000L);
		audit.declaredSegments = 1;
		audit.declaredDrops = 1;
		audit.dropEvents.set(1);
		audit.segmentResults.put(0, "uploaded");
		audit.recorderComplete = true;
		audit.closed = true;
		audits(plugin).put(audit.sessionId, audit);

		inject(plugin, "config", tokenConfig("fedcba9876543210fedcba9876543210"));
		invokeFinal(plugin, audit.sessionId);

		assertEquals("old-session manifest must not enter the new token's queue", 0, plugin.pendingEvents.size());
		assertEquals(1L, plugin.captureHealthSnapshot().get("events_lost_total"));
	}

	@Test
	public void finalManifestDoesNotPublishAfterGrantWithdrawal() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		AccountConnectPlugin.DropSessionAudit audit =
			new AccountConnectPlugin.DropSessionAudit("sessiond", "999", "Worker4",
				plugin.currentEventTokenFingerprint(), 5000L);
		audit.declaredSegments = 1;
		audit.declaredDrops = 1;
		audit.dropEvents.set(1);
		audit.segmentResults.put(0, "uploaded");
		audit.recorderComplete = true;
		audit.closed = true;
		audits(plugin).put(audit.sessionId, audit);

		plugin.serverDropProofEnabled = false;
		invokeFinal(plugin, audit.sessionId);

		assertEquals("withdrawn grant must suppress the final evidence row", 0, plugin.pendingEvents.size());
		assertEquals(1L, plugin.captureHealthSnapshot().get("events_lost_total"));
	}

	@Test
	public void failedTailSegmentStillEmitsPartialFinalAndReleasesAudit() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		String sid = "sessiontail";
		AccountConnectPlugin.DropSessionAudit audit = new AccountConnectPlugin.DropSessionAudit(
			sid, "321", "WorkerTail", plugin.currentEventTokenFingerprint(), 6000L);
		audit.dropEvents.set(1);
		audit.removals.set(1);
		audits(plugin).put(sid, audit);

		DropFrameSegmenter seg = new DropFrameSegmenter(4, 1000, 1_000_000);
		seg.add(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3}, 6100L);
		audit.segmenter = seg;
		inject(plugin, "dropCapturing", true);
		inject(plugin, "dropSegmenter", seg);
		// executor intentionally remains null: the flushed tail is real, but cannot be scheduled.
		plugin.stopDropCapture(DropSessionRecorder.Outcome.COMPLETE, sid, 1, 6000L, null);

		Map<String, Object> finalRow = null;
		for (Map<String, Object> ev : plugin.pendingEvents)
		{
			if ("drop_trade_clip_final".equals(ev.get("type")))
			{
				finalRow = ev;
				break;
			}
		}
		assertTrue("terminal media failure must still publish the FINAL row", finalRow != null);
		assertEquals("PARTIAL", finalRow.get("proof_status"));
		assertEquals(1, finalRow.get("segments_declared"));
		assertEquals(0, finalRow.get("segments_uploaded"));
		assertEquals(1, finalRow.get("segments_failed"));
		assertEquals(1, finalRow.get("missing_segments"));
		assertFalse("terminal audit must not leak in memory", audits(plugin).containsKey(sid));
	}

	private static AccountConnectPlugin plugin() throws Exception
	{
		AccountConnectPlugin plugin = new AccountConnectPlugin();
		inject(plugin, "config", tokenConfig(TEST_TOKEN));
		plugin.serverStoreToolsEnabled = true;
		plugin.serverDropProofEnabled = true;
		plugin.serverClipsDisabled = false;
		return plugin;
	}

	private static AccountConnectConfig tokenConfig(final String token)
	{
		return new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return token;
			}
		};
	}

	@SuppressWarnings("unchecked")
	private static ConcurrentHashMap<String, AccountConnectPlugin.DropSessionAudit> audits(
		AccountConnectPlugin plugin) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("dropAudits");
		f.setAccessible(true);
		return (ConcurrentHashMap<String, AccountConnectPlugin.DropSessionAudit>) f.get(plugin);
	}

	private static void invokeFinal(AccountConnectPlugin plugin, String sessionId) throws Exception
	{
		Method m = AccountConnectPlugin.class.getDeclaredMethod("maybeEmitFinalDropManifest", String.class);
		m.setAccessible(true);
		m.invoke(plugin, sessionId);
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(target, value);
	}
}
