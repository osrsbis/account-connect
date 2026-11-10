package com.osrsbestinslot.export;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** The public journals' driver: the live and ended-session owner, capture before transmission, staging, recovery pump, settlement, erasure and revocation. */
@Slf4j
@RequiredArgsConstructor
final class PublicOutboxDriver
{
	private final AccountConnectPlugin plugin;

	final Set<String> publicOutboxMemoryIds = ConcurrentHashMap.newKeySet();
	final AtomicInteger publicOutboxWrites = new AtomicInteger();
	private final AtomicBoolean publicOutboxPumpQueued = new AtomicBoolean();
	final AtomicLong publicOutboxUndurable = new AtomicLong();
	PublicEventOutbox publicOutbox; // disk executor only
	volatile int publicOutboxPending;
	volatile long publicOutboxBytes;
	volatile long publicOutboxDropped;
	volatile long publicOutboxPurged;
	volatile boolean publicOutboxPaused;
	/**
	 * The public owner of the session a logout just ended, with its token. Rows that session captured (a bank
	 * close at logout, a close that waited for its snapshot) still go to the server until the next login, a token
	 * change or a revocation. Live 0.7.62 (10-29..30): 45 of 49 visits with no close ended in a logout, client close
	 * or idle stop, with the close journaled but never sent until that account logged in again.
	 */
	volatile PublicEventOutbox.Context endedPublicOwner;
	volatile String endedPublicToken;

	/** Public journal gate: a settled game identity and a known RuneLite profile. */
	PublicEventOutbox.Context publicQueueContext(boolean requireGrant)
	{
		String token = plugin.currentLinkToken();
		if (publicOutboxPaused || plugin.config == null || !plugin.uploadAllowed() || !plugin.session.sessionActive || token.equals(plugin.spooler.spoolGrantToken)
			|| requireGrant && !(token.equals(plugin.grants.eventQueueGrantToken) || token.equals(plugin.grants.bankJournalGrantToken))) return null;
		return context(token, plugin.activeHash, plugin.activeProfileType);
	}

	/** The journal identity for this token, account and mode in the current RuneLite profile, or null. */
	private PublicEventOutbox.Context context(String token, String account, String mode)
	{
		if (plugin.configManager == null || plugin.configManager.getProfile() == null) return null;
		try
		{
			return new PublicEventOutbox.Context(token, account, mode, Long.toString(plugin.configManager.getProfile().getId()));
		}
		catch (IllegalArgumentException unknown) { return null; }
	}

	/** The live owner, or after a logout the owner of the session that ended, while its token and grant still hold. */
	PublicEventOutbox.Context publicSendContext()
	{
		PublicEventOutbox.Context live = publicQueueContext(true);
		if (live != null || plugin.session.sessionActive) return live;
		PublicEventOutbox.Context ended = endedPublicOwner;
		String token = plugin.currentLinkToken();
		if (ended == null || publicOutboxPaused || plugin.config == null || !plugin.uploadAllowed() || !token.equals(endedPublicToken)
			|| token.equals(plugin.spooler.spoolGrantToken) || !(token.equals(plugin.grants.eventQueueGrantToken) || token.equals(plugin.grants.bankJournalGrantToken)))
			return null;
		// Rows wait for their own RuneLite profile too: a profile switched after the logout ends this window.
		PublicEventOutbox.Context check = context(token, ended.account, ended.mode);
		return check != null && ended.identity.equals(check.identity) ? ended : null;
	}

	PublicEventOutbox publicBankOutbox;

	private PublicEventOutbox publicBankOutboxOnDiskThread()
	{
		if (publicBankOutbox == null) publicBankOutbox = new PublicEventOutbox(plugin.spooler.spoolDataRoot(), plugin.gson,
			() -> plugin.spooler.wallClock.getAsLong(), EventSpool.Limits.DEFAULT, true);
		return publicBankOutbox;
	}

	private PublicEventOutbox publicOutboxForScope(String scope)
	{
		return PublicBankProtocol.SCOPE.equals(scope) ? publicBankOutboxOnDiskThread() : publicOutboxOnDiskThread();
	}

	private PublicEventOutbox publicOutboxOnDiskThread()
	{
		if (publicOutbox == null) publicOutbox = new PublicEventOutbox(plugin.spooler.spoolDataRoot(), plugin.gson, () -> plugin.spooler.wallClock.getAsLong());
		return publicOutbox;
	}

	private void publicOutboxHealth()
	{
		int pending = 0;
		long bytes = 0, dropped = 0, purged = 0;
		for (PublicEventOutbox o : new PublicEventOutbox[]{publicOutbox, publicBankOutbox})
		{
			if (o == null) continue;
			pending += o.size();
			bytes += o.bytes();
			dropped += o.counters.dropped.get();
			purged += o.counters.purged.get();
		}
		publicOutboxPending = pending;
		publicOutboxBytes = bytes;
		publicOutboxDropped = dropped;
		publicOutboxPurged = purged;
	}

	static String publicOutboxIdentity(Map<String, Object> event)
	{
		Object owner = event.get(EventQueue.EVENT_PUBLIC_OUTBOX);
		return owner instanceof PublicEventOutbox.Context ? ((PublicEventOutbox.Context) owner).identity : null;
	}

	private boolean publicScopeGranted(String scope)
	{
		String token = plugin.currentLinkToken();
		return PublicBankProtocol.SCOPE.equals(scope) ? token.equals(plugin.grants.bankJournalGrantToken)
			: scope != null && scope.equals(plugin.grants.eventQueueScope) && token.equals(plugin.grants.eventQueueGrantToken);
	}

	private String publicScopeFor(Map<String,Object> event)
	{
		if (PublicBankProtocol.allows(event) && publicScopeGranted(PublicBankProtocol.SCOPE)) return PublicBankProtocol.SCOPE;
		return publicScopeGranted(plugin.grants.eventQueueScope) && EventQueueProtocol.allows(plugin.grants.eventQueueScope,event) ? plugin.grants.eventQueueScope : null;
	}

	String stagedPublicScope(Map<String,Object> event)
	{
		String scope = Boolean.TRUE.equals(event.get(EventQueue.EVENT_PUBLIC_BANK)) ? PublicBankProtocol.SCOPE : plugin.grants.eventQueueScope;
		return publicScopeGranted(scope) ? scope : null;
	}

	private boolean capturedPublicOwnerStillValid(PublicEventOutbox.Context owner, String scope)
	{
		if (!publicScopeGranted(scope)) return false;
		PublicEventOutbox.Context current = publicSendContext();
		if (current != null) return owner.identity.equals(current.identity);
		// A bank close is captured before trackLogout clears the mode. Finish that already captured
		// disk write after logout, but do not replay until a matching logged-in identity returns.
		if (!PublicBankProtocol.SCOPE.equals(scope) || publicOutboxPaused || !plugin.uploadAllowed()
			|| !owner.account.equals(plugin.activeHash) || plugin.activeProfileType != null && !owner.mode.equals(plugin.activeProfileType)
) return false;
		PublicEventOutbox.Context check = context(plugin.currentLinkToken(), plugin.activeHash, owner.mode);
		return check != null && owner.identity.equals(check.identity);
	}

	/** Capture before transmission; a bounded executor backlog is an explicit non-durable gap. */
	boolean capturePublicOutbox(Map<String, Object> event)
	{
		PublicEventOutbox.Context owner = publicQueueContext(true);
		String scope = publicScopeFor(event);
		if (owner == null || !owner.owns(event) || scope == null) return false;
		plugin.events.queueDeliveryHealth.handoff(event);
		if (publicOutboxWrites.incrementAndGet() > EventQueue.MAX_PENDING_EVENTS)
		{
			publicOutboxWrites.decrementAndGet();
			stageUndurablePublicEvent(owner, scope, event);
			return true;
		}
		boolean submitted = plugin.spooler.onSpoolThread(() ->
		{
			try
			{
				if (!capturedPublicOwnerStillValid(owner, scope)
					|| !publicOutboxForScope(scope).append(owner, scope, event))
				{
					publicOutboxUndurable.incrementAndGet();
					plugin.events.queueDeliveryHealth.undurable(event);
					plugin.events.healthEventsLost.incrementAndGet();
					return;
				}
				plugin.events.queueDeliveryHealth.forced(event);
				stagePublicOutbox(owner, scope);
			}
			catch (IOException | RuntimeException failure)
			{
				stageUndurablePublicEvent(owner, scope, event);
				log.debug("OSRS BiS public event journal append failed", failure);
			}
			finally { publicOutboxWrites.decrementAndGet(); publicOutboxHealth(); }
		});
		if (!submitted)
		{
			publicOutboxWrites.decrementAndGet();
			stageUndurablePublicEvent(owner, scope, event);
		}
		return true;
	}

	/** IO/backlog failures retain the old bounded memory service, with no durability claim. */
	private void stageUndurablePublicEvent(PublicEventOutbox.Context owner, String scope, Map<String, Object> event)
	{
		publicOutboxUndurable.incrementAndGet();
		plugin.events.queueDeliveryHealth.undurable(event);
		PublicEventOutbox.Context current = publicSendContext();
		if (current == null || !owner.identity.equals(current.identity) || !publicScopeGranted(scope))
		{
			plugin.events.healthEventsLost.incrementAndGet();
			return;
		}
		Map<String, Object> retry = new LinkedHashMap<>(event);
		retry.put(EventQueue.EVENT_QUEUE, !PublicBankProtocol.SCOPE.equals(scope));
		retry.put(EventQueue.EVENT_PUBLIC_BANK, PublicBankProtocol.SCOPE.equals(scope));
		retry.put(EventQueue.EVENT_PUBLIC_OUTBOX, owner);
		retry.put(EventQueue.EVENT_PUBLIC_VOLATILE, true);
		synchronized (plugin.events.pendingEvents)
		{
			if (!publicOutboxMemoryIds.add(String.valueOf(event.get("event_id")))) return;
			plugin.events.pendingEvents.add(retry);
			plugin.events.trimPending();
		}
		plugin.events.scheduleCoalescedFlush();
	}

	private void stagePublicOutbox(PublicEventOutbox.Context owner, String scope) throws IOException
	{
		List<Map<String, Object>> recovered = publicOutboxForScope(scope).pending(owner, scope, EventQueue.MAX_PENDING_EVENTS);
		PublicEventOutbox.Context current = publicSendContext();
		if (current == null || !owner.identity.equals(current.identity) || !publicScopeGranted(scope)) return;
		synchronized (plugin.events.pendingEvents)
		{
			for (Map<String, Object> event : recovered)
			{
				if (plugin.events.pendingEvents.size() >= EventQueue.MAX_PENDING_EVENTS) break;
				if (!publicOutboxMemoryIds.add(String.valueOf(event.get("event_id")))) continue;
				event.put(EventQueue.EVENT_TOKEN_FP, plugin.events.currentEventTokenFingerprint());
				event.put(EventQueue.EVENT_QUEUE, !PublicBankProtocol.SCOPE.equals(scope));
				event.put(EventQueue.EVENT_PUBLIC_BANK, PublicBankProtocol.SCOPE.equals(scope));
				event.put(EventQueue.EVENT_PUBLIC_OUTBOX, owner);
				plugin.events.pendingEvents.add(event);
			}
		}
		plugin.events.scheduleCoalescedFlush();
	}

	/** One coalesced disk read, never file IO on the client tick. */
	void pumpPublicOutbox()
	{
		PublicEventOutbox.Context owner = publicSendContext();
		String scope = plugin.grants.eventQueueScope;
		if (owner == null || !publicOutboxPumpQueued.compareAndSet(false, true)) return;
		if (!plugin.spooler.onSpoolThread(() ->
		{
			try
			{
				PublicEventOutbox.Context current = publicSendContext();
				if (current != null && owner.identity.equals(current.identity))
				{
					if (publicScopeGranted(scope)) stagePublicOutbox(owner, scope);
					if (publicScopeGranted(PublicBankProtocol.SCOPE)) stagePublicOutbox(owner, PublicBankProtocol.SCOPE);
				}
			}
			catch (IOException | RuntimeException failure) { log.debug("OSRS BiS public event recovery failed", failure); }
			finally { publicOutboxPumpQueued.set(false); publicOutboxHealth(); }
		})) publicOutboxPumpQueued.set(false);
	}

	void settlePublicOutbox(List<Map<String, Object>> batch,
		List<EventQueueProtocol.Outcome> outcomes, String sentToken)
	{
		Object bound = batch.get(0).get(EventQueue.EVENT_PUBLIC_OUTBOX);
		if (!(bound instanceof PublicEventOutbox.Context)) return;
		PublicEventOutbox.Context owner = (PublicEventOutbox.Context) bound;
		if (outcomes.contains(EventQueueProtocol.Outcome.ERASED))
		{
			PublicEventOutbox.Context current = publicQueueContext(false);
			if (sentToken.equals(plugin.currentLinkToken()) && (current == null || owner.identity.equals(current.identity)))
			{
				plugin.grants.eventQueueGrantToken = null;
				plugin.grants.eventQueueScope = null;
				plugin.grants.bankJournalGrantToken = null;
			}
			synchronized (plugin.events.pendingEvents)
			{
				plugin.events.pendingEvents.removeIf(event -> owner.identity.equals(publicOutboxIdentity(event)));
				for (Map<String, Object> event : batch) publicOutboxMemoryIds.remove(String.valueOf(event.get("event_id")));
			}
			plugin.spooler.onSpoolThread(() ->
			{
				try { try { publicOutboxOnDiskThread().erase(owner); } finally { publicBankOutboxOnDiskThread().erase(owner); } }
				catch (IOException failure) { log.debug("OSRS BiS public identity erase incomplete", failure); }
				finally { publicOutboxHealth(); }
			});
			return;
		}
		plugin.spooler.onSpoolThread(() ->
		{
			try
			{
				PublicEventOutbox journal = Boolean.TRUE.equals(batch.get(0).get(EventQueue.EVENT_PUBLIC_BANK)) ? publicBankOutbox : publicOutbox;
				if (journal != null) journal.settle(owner, batch, outcomes);
			}
			catch (IOException failure) { log.debug("OSRS BiS public event settlement failed; retain for replay", failure); }
			finally
			{
				for (int i = 0; i < batch.size(); i++)
					if (outcomes.get(i) != EventQueueProtocol.Outcome.RETRY)
						publicOutboxMemoryIds.remove(String.valueOf(batch.get(i).get("event_id")));
				publicOutboxHealth();
			}
		});
	}

	void revokePublicOutbox(String sentToken)
	{
		if (sentToken != null && !sentToken.equals(plugin.currentLinkToken())) return;
		endedPublicOwner = null;
		plugin.grants.eventQueueGrantToken = null;
		plugin.grants.eventQueueScope = null;
		plugin.grants.bankJournalGrantToken = null;
		synchronized (plugin.events.pendingEvents)
		{
			plugin.events.pendingEvents.removeIf(event -> event.containsKey(EventQueue.EVENT_PUBLIC_OUTBOX));
			publicOutboxMemoryIds.clear();
		}
		plugin.spooler.onSpoolThread(() ->
		{
			try { try { publicOutboxOnDiskThread().purge(); } finally { publicBankOutboxOnDiskThread().purge(); } }
			catch (IOException failure) { log.debug("OSRS BiS public event purge incomplete", failure); }
			finally { publicOutboxHealth(); }
		});
	}

	/** Disk bounds, cross-process purge markers and current grant are checked again before sending. */
	void dispatchPublicOutbox(List<Map<String, Object>> selected, String token)
	{
		try
		{
			PublicEventOutbox.Context owner = (PublicEventOutbox.Context) selected.get(0).get(EventQueue.EVENT_PUBLIC_OUTBOX);
			PublicEventOutbox.Context current = publicSendContext();
			String scope = stagedPublicScope(selected.get(0));
			if (current == null || !owner.identity.equals(current.identity) || scope == null)
			{
				plugin.events.requeueEvents(selected, 0);
				return;
			}
			Set<String> retained = new HashSet<>();
			PublicEventOutbox journal = PublicBankProtocol.SCOPE.equals(scope) ? publicBankOutbox : publicOutbox;
			if (journal != null)
				for (Map<String, Object> event : journal.pending(owner, scope, EventSpool.Limits.DEFAULT.maxRecords))
					retained.add(String.valueOf(event.get("event_id")));
			List<Map<String, Object>> ready = new ArrayList<>();
			for (Map<String, Object> event : selected)
			{
				if (retained.contains(String.valueOf(event.get("event_id")))) ready.add(event);
				else publicOutboxMemoryIds.remove(String.valueOf(event.get("event_id")));
			}
			if (ready.isEmpty())
			{
				plugin.events.eventPostInFlight = false;
				plugin.events.completeFinalFlush();
				return;
			}
			plugin.events.dispatchEventBatch(ready, token);
		}
		catch (IOException | RuntimeException failure) { plugin.events.requeueEvents(selected, 0); }
		finally { publicOutboxHealth(); }
	}
}
