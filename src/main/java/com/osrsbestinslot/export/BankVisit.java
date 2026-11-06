package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;

/** Own bank visits: the open baseline, the moves during the visit and the close manifest. */
final class BankVisit
{
	private final AccountConnectPlugin plugin;

	BankVisit(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	// Capture-on-open groups: opening either forces an immediate snapshot so the bank / collection log
	// sync the moment they become readable (verified vs runelite-api 1.12.32 gameval enums, javap).
	static final int BANK_GROUP_ID = InterfaceID.BANKMAIN;					// 12
	static final int COLLECTION_LOG_GROUP_ID = InterfaceID.COLLECTION;		// 621

	// Bank move capture: item-id -> qty of the bank as LAST SEEN in this bank session (the open read, then
	// every BANK container change). Each change is diffed against it and the movement is added to the GROSS
	// per-item totals below, so a deposit and a withdraw of the same item inside one session both survive
	// (a net open-vs-close diff cancelled them out). One bank_session event per session carries the totals.
	// Bank moves are internal (no trade / GE / store), so this is the ONLY event-plane record of them. Own
	// account. null = no bank session with a baseline is running.
	Map<Integer, Long> bankAtOpen;
	final Map<Integer, Long> bankGrossDeposited = new LinkedHashMap<>();
	final Map<Integer, Long> bankGrossWithdrawn = new LinkedHashMap<>();
	// True while the bank is open but its container was empty or unloaded at open (the first open after a
	// login can precede the bank contents). Diffing a real bank against that empty state would report the
	// whole bank as deposited, so no baseline is taken then. The first BANK container change while
	// this is set becomes the baseline, and the close diffs against it.
	boolean bankBaselinePending;
	// Widget unload may precede the logout state. An unreadable close waits for that state or the
	// next world tick; reads arriving after the close must not become the old visit's end inventory.
	private boolean bankClosePending;
	// True only while the snapshot for a bank open is built: the open has been seen, its visit state not yet.
	private boolean bankOpeningNow;
	// Tick of the last populated bank ItemContainerChanged. Only a change at or after the open proves the
	// container holds this visit's contents; a container read at the open alone could be an earlier copy.
	int bankChangeTick = Integer.MIN_VALUE / 2;

	// Bank-visit evidence (staff only, 0.7.17). One visit per BANKMAIN open: bank_visit_open, the item list at
	// open and at close as bank_visit_manifest_chunk events, and bank_visit_close. Follows the server's
	// bank-visit ingest contract. Only while the
	// LAST snapshot response for the CURRENT token said X-Bank-Evidence: on, read once when the bank opens.
	volatile String bankEvidenceToken;
	private String bankVisitId;	// null = no visit running
	private long bankVisitOpenedMs;
	private boolean bankVisitOpenSent;	// the open event is deferred until the first reliable bank read
	private boolean bankVisitOpenRead;	// the item list at open was read (from a non-empty container)
	// A public visit, read once when it starts like the evidence grant. Its open or close item list is not sent
	// when a snapshot queued for upload holds the same bank: the server keeps only the newest complete bank.
	private boolean bankVisitPublic;
	// A public normal close waits for the bank session to end, so the snapshot that holds its bank has a known time.
	private Map<String, Object> publicCloseFields;
	private List<long[]> publicCloseList;
	private int publicCloseTick;
	// A close waits at most this long for a regular snapshot to be accepted: the 120 s upload interval plus margin.
	static final int PUBLIC_CLOSE_WAIT_TICKS = 250;
	/** bank_session: at most this many items per list; the rest are dropped and the row says truncated. */
	static final int BANK_SESSION_ITEM_CAP = 50;

	/**
	 * Capture-on-open: opening the bank (BANKMAIN 12) or the collection log (COLLECTION 621) forces an
	 * immediate snapshot so those containers sync the moment they become readable, instead of waiting up
	 * to a full debounce interval for the next scheduled tick (the "opened my bank, still says not synced"
	 * complaint). WidgetLoaded is delivered on the client thread, so forceSendSnapshot reads containers
	 * directly. Its own guards (token / backoff) still apply — an unlinked account
	 * opening its bank sends nothing.
	 */
	/** Tick of the last bank open handled here; client-thread only. */
	private int bankOpenTick = Integer.MIN_VALUE / 2;

	/** The bank interface loaded again with no close since the open one tick ago or less. */
	private boolean bankReloadedWhileOpen()
	{
		return !bankClosePending && (bankVisitId != null || bankAtOpen != null || bankBaselinePending)
			&& plugin.client != null && plugin.client.getTickCount() - bankOpenTick <= 1;
	}

	void handleCaptureOnOpenWidgetLoaded(int groupId)
	{
		if (groupId == BANK_GROUP_ID)
		{
			finishPendingBankClose();
		}
		if (groupId == BANK_GROUP_ID || groupId == COLLECTION_LOG_GROUP_ID)
		{
			bankOpeningNow = groupId == BANK_GROUP_ID;
			try
			{
				plugin.uploader.forceSendSnapshot();
			}
			finally
			{
				bankOpeningNow = false;
			}
		}
		if (groupId == BANK_GROUP_ID && plugin.activityLogActive() && bankReloadedWhileOpen())
		{
			// Live 0.7.55 (2026-10-24): one client loaded the bank interface twice 34-66 ms apart on every
			// open, with no close between. Starting a second visit left the first with no close (60 of
			// that player's 120 visits). A reload with the visit still open in the same or next tick keeps it.
			advanceBankSession(plugin.client.getItemContainer(InventoryID.BANK));	// a move only this read shows is kept
			return;
		}
		if (groupId == BANK_GROUP_ID && plugin.activityLogActive())
		{
			bankOpenTick = plugin.client.getTickCount();
			// Snapshot the bank the moment it opens; handleBankWidgetClosed diffs against this on close.
			// The bank is readable here — the same read forceSendSnapshot just used to set bank_synced.
			Map<Integer, Long> b = new LinkedHashMap<>();
			plugin.snapshots.addContainerCounts(b, plugin.client.getItemContainer(InventoryID.BANK));
			// A session still running here was never closed: fold in this read (moves since the last change)
			// and end it as incomplete before starting fresh.
			advanceBankSession(plugin.client.getItemContainer(InventoryID.BANK));
			endBankSession(false);
			// An empty or unloaded container is not a baseline: wait for the first bank contents instead.
			bankAtOpen = b.isEmpty() ? null : b;
			bankBaselinePending = b.isEmpty();
			startBankVisit(b);
		}
	}

	/**
	 * The bank contents arrived after an open whose container was empty or unloaded: take this first
	 * populated state as the baseline. No event is emitted here. The close diffs against this baseline.
	 */
	void handleBankContainerChanged(ItemContainer bank)
	{
		if (bankClosePending)
		{
			return;
		}
		if (bankBaselinePending)
		{
			Map<Integer, Long> b = new LinkedHashMap<>();
			plugin.snapshots.addContainerCounts(b, bank);
			if (!b.isEmpty())
			{
				bankAtOpen = b;
				bankBaselinePending = false;
				sendBankVisitOpen(b);
			}
			return;
		}
		advanceBankSession(bank);
	}

	/**
	 * Add the movement from the last-seen bank to {@code bank} to the session's GROSS totals and make it
	 * the last-seen state. A null or EMPTY read is skipped, never read as "everything was withdrawn": an
	 * empty container is not evidence (the first-open rule above), and one transient empty read would
	 * otherwise fabricate a whole-bank withdraw and a whole-bank deposit. Cost: emptying the bank of its
	 * very last stack is not seen. Miss beats fabricate.
	 */
	private void advanceBankSession(ItemContainer bank)
	{
		Map<Integer, Long> before = bankAtOpen;
		if (before == null || bank == null)
		{
			return;
		}
		Map<Integer, Long> now = new LinkedHashMap<>();
		plugin.snapshots.addContainerCounts(now, bank);
		if (now.isEmpty())
		{
			return;
		}
		for (Map.Entry<Integer, Long> e : before.entrySet())
		{
			long delta = now.getOrDefault(e.getKey(), 0L) - e.getValue();
			if (delta < 0)
			{
				bankGrossWithdrawn.merge(e.getKey(), -delta, Long::sum);
			}
			else if (delta > 0)
			{
				bankGrossDeposited.merge(e.getKey(), delta, Long::sum);
			}
		}
		for (Map.Entry<Integer, Long> e : now.entrySet())
		{
			if (!before.containsKey(e.getKey()))
			{
				bankGrossDeposited.merge(e.getKey(), e.getValue(), Long::sum);
			}
		}
		bankAtOpen = now;
	}

	/**
	 * Bank close: fold in the final bank read, then emit ONE bank_session for the session. Bank transfers
	 * fire no trade / GE / store event, so this is the only event-plane record of what left or entered the
	 * bank. Own account; same data class as the bank snapshot, which the hub already approved.
	 */
	void handleBankWidgetClosed(int groupId)
	{
		handleBankWidgetClosed(groupId, false);
	}

	/**
	 * True when a bank close happens because the client is leaving the world (hop, logout, disconnect)
	 * rather than the player closing it. NOT WidgetClosed.isUnload(): the rig (2026-09-27, 0.7.15 @ 1af4d7f)
	 * showed an ordinary X-button bank close arrives with unload=true, which marked every visit incomplete.
	 */
	boolean bankCloseIsTeardown()
	{
		GameState s = plugin.client == null ? null : plugin.client.getGameState();
		return s == GameState.HOPPING || s == GameState.LOGIN_SCREEN || s == GameState.CONNECTION_LOST
			|| s == GameState.LOGGING_IN;
	}

	/**
	 * @param unload true when the client tore the bank down for a hop or logout rather than the player
	 *               closing it: the session is incomplete and the bank read during teardown is not used.
	 */
	void handleBankWidgetClosed(int groupId, boolean unload)
	{
		if (groupId != BANK_GROUP_ID)
		{
			return;
		}
		bankChangeTick = Integer.MIN_VALUE / 2;	// a change seen before this close never confirms the next open
		if (unload)
		{
			closeBankVisit(teardownReason(plugin.client == null ? null : plugin.client.getGameState()), null);
			endBankSession(false);
			return;
		}
		if (bankClosePending)
		{
			return;
		}
		ItemContainer bank = plugin.client == null ? null : plugin.client.getItemContainer(InventoryID.BANK);
		Map<Integer, Long> read = new LinkedHashMap<>();
		plugin.snapshots.addContainerCounts(read, bank);
		if (read.isEmpty() && (bankVisitId != null || bankAtOpen != null || bankBaselinePending))
		{
			bankClosePending = true;
			return;
		}
		advanceBankSession(bank);
		closeBankVisit("closed", bank);
		endBankSession(true);
		// The close script is still running here, and a snapshot build runs scripts (quest states), which the
		// client does not allow inside another script. The close is finished on the next game tick.
	}

	void finishPendingBankClose()
	{
		if (!bankClosePending)
		{
			return;
		}
		boolean teardown = bankCloseIsTeardown();
		closeBankVisit(teardown ? teardownReason(plugin.client == null ? null : plugin.client.getGameState()) : "closed", null);
		// A world tick can precede LOGIN_SCREEN during idle logout. No readable end inventory means
		// movement completeness is unknown even when the teardown cause is not known yet.
		endBankSession(false);
	}

	/**
	 * End the running bank session and emit its bank_session summary: GROSS deposited[] and withdrawn[]
	 * per item, each capped at BANK_SESSION_ITEM_CAP (truncated:true when cut), and complete:false when
	 * the session ended without a bank close (hop, logout, disconnect, a re-open). An incomplete row
	 * carries only moves actually observed; nothing is inferred for the part we did not see. No row when
	 * nothing moved, so an idle bank visit costs no event.
	 */
	/** The server granted bank-visit evidence to the configured token (newest snapshot response). */
	boolean bankEvidenceEnabled()
	{
		String t = bankEvidenceToken;
		return t != null && t.equals(plugin.currentLinkToken()) && plugin.uploadAllowed();
	}

	/**
	 * A bank open. The grant is read ONCE here: a visit started without it sends nothing, and one started
	 * with it is finished even if a later response withdraws the grant (the server then marks it
	 * grant_revoked). A visit still running here was never closed: it is dropped without a close, and the
	 * server marks it no_close when this newer open arrives.
	 */
	private void startBankVisit(Map<Integer, Long> atOpen)
	{
		bankVisitId = null;
		if (!bankEvidenceEnabled())
		{
			return;
		}
		bankVisitId = EventSpool.newEventId();
		bankVisitOpenedMs = System.currentTimeMillis();
		bankVisitOpenSent = false;
		bankVisitOpenRead = false;
		finishPublicBankClose(true);
		bankVisitPublic = publicBankVisit();
		if (!atOpen.isEmpty())
		{
			sendBankVisitOpen(atOpen);
			return;
		}
		// An empty read is NOT claimed as an empty bank: the container can read empty before the bank
		// contents arrive, and this build has no proven used-slot source to tell the two apart, so
		// verified_empty is never sent. The open goes out now as unloaded_unknown; the
		// phase:open chunks follow in the SAME visit at the first non-empty read, which the server records as
		// open_basis first_read.
		Map<String, Object> o = Fields.of(
			"visit_id", bankVisitId, "opened_at_ms", bankVisitOpenedMs, "bank_state", "unloaded_unknown",
			"plugin_version", AccountConnectPlugin.PLUGIN_VERSION);
		plugin.events.emitEvent("bank_visit_open", o);
		bankVisitOpenSent = true;
	}

	/**
	 * The item list at open, from the first reliable (non-empty) bank read. When the bank was readable at
	 * open this also sends the loaded open event; when the open already went out as unloaded_unknown only
	 * the phase:open chunks are sent, in the same visit.
	 */
	private void sendBankVisitOpen(Map<Integer, Long> read)
	{
		if (bankVisitId == null || bankVisitOpenRead)
		{
			return;
		}
		List<long[]> list = BankVisitManifest.canonical(read);
		if (bankVisitOpenSent)
		{
			bankVisitOpenRead = true;
			emitBankVisitChunks("open", list);
			return;
		}
		Map<String, Object> f = Fields.of(
			"visit_id", bankVisitId, "opened_at_ms", bankVisitOpenedMs, "read_at_ms", System.currentTimeMillis(),
			"bank_state", "loaded", "item_stacks", list.size(), "manifest_sha256", BankVisitManifest.sha256(list),
			"plugin_version", AccountConnectPlugin.PLUGIN_VERSION);
		// The bank-open snapshot was queued just before this visit started, with this exact bank. Its read has no
		// confirmed time yet, so the server keeps it only until a timed bank arrives, which the close forces.
		boolean inSnapshot = bankVisitPublic && snapshotQueuedWithBank(BankVisitManifest.sha256(list), false);
		if (inSnapshot)
		{
			f.put("manifest_in_snapshot", true);
		}
		plugin.events.emitEvent("bank_visit_open", f);
		bankVisitOpenSent = true;
		bankVisitOpenRead = true;
		if (!inSnapshot)
		{
			emitBankVisitChunks("open", list);
		}
	}

	/**
	 * A visit for a token the server named public (X-Bank-Receipt, never sent to staff) and that holds no staff
	 * spool. Staff visits keep the full lists for the audit trail.
	 */
	private boolean publicBankVisit()
	{
		String token = plugin.currentLinkToken();
		return token.equals(plugin.grants.bankJournalGrantToken) && !token.equals(plugin.spooler.spoolGrantToken);
	}

	/** The newest snapshot the server accepted on this token and account holds this bank with a known read time. */
	private boolean snapshotAcceptedWithBank(String sha)
	{
		return plugin.currentLinkToken().equals(plugin.uploader.acceptedBankToken) && plugin.activeHash != null && plugin.activeHash.equals(plugin.uploader.acceptedBankAccount)
			&& sha.equals(plugin.uploader.acceptedBankSha) && plugin.uploader.acceptedBankTimed;
	}

	/** A snapshot queued for upload on this token holds this bank, with a known read time when {@code timed}. */
	private boolean snapshotQueuedWithBank(String sha, boolean timed)
	{
		return plugin.currentLinkToken().equals(plugin.uploader.queuedBankToken) && plugin.activeHash != null && plugin.activeHash.equals(plugin.uploader.queuedBankAccount)
			&& sha.equals(plugin.uploader.queuedBankSha) && (!timed || plugin.uploader.queuedBankTimed);
	}

	/**
	 * Send the close of a public visit once a snapshot the server accepted holds its end bank with a known read
	 * time. The close forces no snapshot: it waits for the regular one, so a visit costs no extra request. At
	 * {@code now} (a teardown or a new open), or after {@link #PUBLIC_CLOSE_WAIT_TICKS}, the close sends its item
	 * list as chunks, as before.
	 */
	void finishPublicBankClose(boolean now)
	{
		Map<String, Object> f = publicCloseFields;
		List<long[]> endList = publicCloseList;
		if (f == null)
		{
			return;
		}
		String sha = BankVisitManifest.sha256(endList);
		boolean accepted = snapshotAcceptedWithBank(sha);
		if (!accepted && !now && plugin.client != null && plugin.client.getTickCount() - publicCloseTick < PUBLIC_CLOSE_WAIT_TICKS)
		{
			return;
		}
		publicCloseFields = null;
		publicCloseList = null;
		if (accepted)
		{
			f.put("end_item_stacks", endList.size());
			f.put("manifest_in_snapshot", true);
		}
		else
		{
			List<List<long[]>> endChunks = chunksFor("close", endList);
			f.put("end_chunks", endChunks.size());
			emitChunkList((String) f.get("visit_id"), "close", endList, endChunks);
		}
		plugin.events.emitEvent("bank_visit_close", f);
	}

	/**
	 * End the running visit. {@code bankAtClose} is the final bank read for a normal close, null for a
	 * teardown (hop, logout, disconnect), whose read is not trusted. Must run BEFORE endBankSession, which
	 * clears the gross move totals this close reports.
	 */
	void closeBankVisit(String reason, ItemContainer bankAtClose)
	{
		if (bankVisitId == null)
		{
			return;
		}
		boolean normal = "closed".equals(reason);
		Map<Integer, Long> end = new LinkedHashMap<>();
		if (normal)
		{
			plugin.snapshots.addContainerCounts(end, bankAtClose);
		}
		List<long[]> endList = BankVisitManifest.canonical(end);
		boolean endRead = normal && !endList.isEmpty(); // an empty read is not evidence of an empty bank
		List<long[]> dep = BankVisitManifest.canonical(bankGrossDeposited);
		List<long[]> wd = BankVisitManifest.canonical(bankGrossWithdrawn);

		Map<String, Object> f = Fields.of(
			"visit_id", bankVisitId, "closed_at_ms", System.currentTimeMillis(), "close_reason", reason,
			"manifest_complete", bankVisitOpenRead);
		// Both boundary reads are required. A missing end can be teardown before its state callback.
		f.put("moves_complete", normal && bankVisitOpenRead && endRead);
		f.put("deposited_sha256", BankVisitManifest.sha256(dep));
		f.put("withdrawn_sha256", BankVisitManifest.sha256(wd));
		boolean publicClose = endRead && bankVisitPublic;
		List<List<long[]>> endChunks = endRead && !publicClose ? chunksFor("close", endList)
			: Collections.emptyList();
		if (endRead)
		{
			f.put("end_manifest_sha256", BankVisitManifest.sha256(endList));
			if (!publicClose)
			{
				f.put("end_chunks", endChunks.size());
			}
		}
		Map<String, Object> inline = new LinkedHashMap<>(f);
		inline.put("deposited", dep);
		inline.put("withdrawn", wd);
		boolean movesInline = encodedFieldsLength(inline) <= BankVisitManifest.FIELDS_BUDGET;
		List<List<long[]>> depChunks = movesInline ? Collections.emptyList() : chunksFor("deposited", dep);
		List<List<long[]>> wdChunks = movesInline ? Collections.emptyList() : chunksFor("withdrawn", wd);
		if (!movesInline)
		{
			f.put("deposited_chunks", depChunks.size());
			f.put("withdrawn_chunks", wdChunks.size());
			f.put("moves_chunks", depChunks.size() + wdChunks.size());
		}
		String id = bankVisitId;
		emitChunkList(id, "close", endList, endChunks);
		emitChunkList(id, "deposited", dep, depChunks);
		emitChunkList(id, "withdrawn", wd, wdChunks);
		if (publicClose)
		{
			publicCloseFields = movesInline ? inline : f;
			publicCloseList = endList;
			publicCloseTick = plugin.client == null ? 0 : plugin.client.getTickCount();
		}
		else
		{
			plugin.events.emitEvent("bank_visit_close", movesInline ? inline : f);
		}
		bankVisitId = null;
	}

	/**
	 * Forget the running visit WITHOUT a close: its state cannot be trusted any more (plugin disabled, token
	 * changed). The server marks it no_close, which is honest; a close built from stale state would not be.
	 */
	void dropBankVisit()
	{
		publicCloseFields = null;
		publicCloseList = null;
		bankClosePending = false;
		bankVisitId = null;
		bankVisitOpenSent = false;
		bankVisitOpenRead = false;
	}

	/** hop / logout / disconnect, from the game state a teardown happened in. */
	static String teardownReason(GameState s)
	{
		if (s == GameState.HOPPING)
		{
			return "hop";
		}
		if (s == GameState.CONNECTION_LOST)
		{
			return "disconnect";
		}
		return "logout";
	}

	private void emitBankVisitChunks(String phase, List<long[]> list)
	{
		emitChunkList(bankVisitId, phase, list, chunksFor(phase, list));
	}

	private List<List<long[]>> chunksFor(String phase, List<long[]> list)
	{
		Map<String, Object> probe = Fields.of(
			"visit_id", bankVisitId == null ? AccountConnectPlugin.EVENT_ID_PLACEHOLDER : bankVisitId, "phase", phase, "chunk", 99_999,
			"chunks", 99_999, "manifest_sha256", BankVisitManifest.sha256(list), "items", new ArrayList<>());
		return BankVisitManifest.chunk(list, encodedFieldsLength(probe));
	}

	private void emitChunkList(String visitId, String phase, List<long[]> list, List<List<long[]>> chunks)
	{
		String sha = BankVisitManifest.sha256(list);
		for (int i = 0; i < chunks.size(); i++)
		{
			Map<String, Object> c = Fields.of(
				"visit_id", visitId, "phase", phase, "chunk", i, "chunks", chunks.size(), "manifest_sha256", sha,
				"items", chunks.get(i));
			plugin.events.emitEvent("bank_visit_manifest_chunk", c);
		}
	}

	/** Length of the event's fields as the server measures them, event_id included. */
	private int encodedFieldsLength(Map<String, Object> fields)
	{
		Map<String, Object> wire = new LinkedHashMap<>(fields);
		wire.put("event_id", AccountConnectPlugin.EVENT_ID_PLACEHOLDER);
		return plugin.gson.toJson(wire).length();
	}

	void endBankSession(boolean complete)
	{
		bankClosePending = false;
		bankAtOpen = null;
		bankBaselinePending = false;
		if ((bankGrossDeposited.isEmpty() && bankGrossWithdrawn.isEmpty()) || !plugin.activityLogActive())
		{
			bankGrossDeposited.clear();
			bankGrossWithdrawn.clear();
			return;
		}
		boolean[] truncated = {false};
		Map<String, Object> f = Fields.of(
			"deposited", cappedItemList(bankGrossDeposited, truncated),
			"withdrawn", cappedItemList(bankGrossWithdrawn, truncated), "complete", complete);
		if (truncated[0])
		{
			f.put("truncated", true);
		}
		bankGrossDeposited.clear();
		bankGrossWithdrawn.clear();
		plugin.events.emitEvent("bank_session", f);
	}

	private List<Map<String, Object>> cappedItemList(Map<Integer, Long> totals, boolean[] truncated)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : totals.entrySet())
		{
			if (out.size() >= BANK_SESSION_ITEM_CAP)
			{
				truncated[0] = true;
				break;
			}
			out.add(plugin.snapshots.itemMapLong(e.getKey(), e.getValue()));
		}
		return out;
	}

	/**
	 * bank.open_at_capture: the bank items in this snapshot are the live container of an open bank. True only
	 * with the open seen and no close since, a populated live read, no first read still pending, and a bank
	 * change delivered since the open; absent otherwise (closed, closing, unloaded, a retained read from earlier
	 * in this login, or an open whose contents no change has confirmed yet). Uses the bank-session state, which
	 * runs for every linked token, not the staff-only visit id.
	 *
	 * Live 0.7.57 QA (2026-10-24): the snapshot sent at the open read a populated container before this open's
	 * ItemContainerChanged (bank.observed_at still the previous change). The contents were right there, but
	 * nothing proves a container read at the open is not an earlier copy, so the open snapshot carries the key
	 * only when the change arrived in the open's own tick, and the next snapshot carries it after the change.
	 */
	boolean bankOpenAtCapture(ItemContainer liveBank)
	{
		if (liveBank == null || bankClosePending || plugin.client == null)
		{
			return false;
		}
		boolean confirmed = bankOpeningNow
			? bankChangeTick == plugin.client.getTickCount()
			: bankAtOpen != null && !bankBaselinePending && bankChangeTick >= bankOpenTick;
		if (!confirmed)
		{
			return false;
		}
		Item[] items = liveBank.getItems();
		return items != null && Arrays.stream(items).anyMatch(plugin.snapshots::realItem);
	}

	/**
	 * The snapshot reads the live container of a bank open now (the open itself, or a visit with no close since)
	 * and no bank change of this open has arrived yet. Live 0.7.58 QA (2026-10-24, G4): after a deposit at a
	 * deposit box while the bank was closed, the open upload held the deposited item with the observed_at of the
	 * read before the deposit. A retained read after a close is not this case and keeps its own time.
	 */
	boolean bankReadUnconfirmed(ItemContainer liveBank)
	{
		if (liveBank == null || plugin.client == null || bankClosePending)
		{
			return false;
		}
		if (bankOpeningNow)
		{
			return bankChangeTick != plugin.client.getTickCount();
		}
		return (bankAtOpen != null || bankBaselinePending) && bankChangeTick < bankOpenTick;
	}
}
