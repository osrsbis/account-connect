package com.osrsbestinslot.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.RuneScapeProfileType;


/** Own Grand Exchange slots: login replay, the hash -1 hold, and the slots saved across a restart. */
@Slf4j
final class GeSlots
{
	private final AccountConnectPlugin plugin;

	GeSlots(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Disable: clear client-thread GE state so a re-enable starts from a fresh baseline. */
	void reset()
	{
		observations.clear();
		accountHash = -1L;
		restoredFor = null;
		unkeyed.clear();
	}

	/** Tick of the last LOGGED_IN. The client replays every slot right after login; that replay is a baseline, not activity. */
	int loginTick = Integer.MIN_VALUE / 2;
	/** Set when the account leaves the world (login screen, hop, relog, disconnect); the next LOGGED_IN is a real login. */
	boolean awaitingLogin = true;
	/** Account the remembered GE slots belong to. */
	private long accountHash = -1L;
	/** Ticks after LOGGED_IN during which GE slot replays only set the baseline (the RuneLite GE plugin uses 2). */
	static final int LOGIN_BURST_TICKS = 2;
	/** Minimum ticks between two ge_progress rows for one slot (100 ticks = 1 minute). */
	static final int PROGRESS_MIN_TICKS = 100;
	/**
	 * Take the current GE slots as the baseline. Nothing is emitted, except for a slot saved before a restart
	 * that changed meanwhile (see GE SLOTS ACROSS A RESTART). Runs on the client thread.
	 * If the account hash is still -1, the seed is keyed to -1 and the first event with a real hash clears
	 * it (the account-change rule), so an unkeyed seed is never trusted.
	 */
	void seed()
	{
		plugin.refreshProfileType();
		GrandExchangeOffer[] offers = plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN
			? null : plugin.client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		observations.clear();
		unkeyed.clear();	// the current slots supersede anything held from before the plugin was enabled
		prepareContext();
		accountHash = plugin.client.getAccountHash();
		// Slots saved before a restart are taken back first, so an offer that moved meanwhile is reported once.
		// A slot with nothing saved is only a baseline, as before.
		restore();
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer o = offers[slot];
			if (o == null || o.getState() == null)
			{
				continue;
			}
			judge(slot, o.getState(), new long[]{o.getItemId(), o.getQuantitySold(),
				o.getTotalQuantity(), o.getPrice(), o.getSpent()}, true, plugin.client.getTickCount(), System.currentTimeMillis());
		}
	}

	/**
	 * GE offer lifecycle observations, own account only:
	 * ge_offer (a new offer placed), ge_progress (a partial fill, at most one per slot per minute),
	 * ge_buy / ge_sell / ge_cancel (terminal, as before) and ge_collect (the slot emptied, with what it held).
	 * The client replays slots at login. First sight only baselines; matching retained slots can
	 * report counter differences across the gap, with inferred identity and unknown fill time.
	 */
	void offerChanged(GrandExchangeOfferChanged event)
	{
		GrandExchangeOffer offer = event.getOffer();
		if (offer == null)
		{
			return;
		}
		int slot = event.getSlot();
		if (slot < 0 || slot >= SLOT_COUNT || offer.getState() == null)
		{
			return;
		}
		GrandExchangeOfferState state = offer.getState();
		// The client clears every slot to EMPTY while hopping, logging in and at the login screen. That
		// clear is not a collect and must not overwrite what the slot held (the RuneLite GE plugin skips
		// it the same way). Only an EMPTY seen while logged in is a real collect.
		if (state == GrandExchangeOfferState.EMPTY && plugin.client != null
			&& plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		int tick = plugin.client == null ? 0 : plugin.client.getTickCount();
		long acct = plugin.client == null ? -1L : plugin.client.getAccountHash();
		long[] nowOffer = {offer.getItemId(), offer.getQuantitySold(), offer.getTotalQuantity(),
			offer.getPrice(), offer.getSpent()};
		if (plugin.client != null && acct == -1L)
		{
			// The account is not known yet (before login the hash is -1). The event cannot be judged now, and it
			// must not re-key or clear the slots. It may still be the only replay of an offer that finished while
			// away, so hold it (latest per slot, no state change) until the account is known.
			if (unkeyed.containsKey(slot) || unkeyed.size() < SLOT_COUNT)
			{
				unkeyed.put(slot, new Object[]{state, nowOffer, System.currentTimeMillis()});
			}
			return;
		}
		prepareContext();
		resolveUnkeyed(acct);
		if (acct != accountHash)
		{
			// GE slots belong to one account. Another account's remembered slot must never make this one's
			// replay look like an offer that finished while away. Events with hash -1 never reach here, so a
			// stored -1 can only be the empty start or an unkeyed seed.
			observations.clear();
			accountHash = acct;
		}
		restore();
		// An identical re-send of a slot (same state, same offer) emits nothing through the rules below: an
		// unchanged terminal state, an active offer with no new fill and a repeated EMPTY all yield no row.
		// A replay is every slot event from leaving the world until LOGGED_IN (the RuneLite GE plugin opens its
		// window at LOGGING_IN / HOPPING / CONNECTION_LOST), plus LOGIN_BURST_TICKS after LOGGED_IN.
		// awaitingLogin is set on leaving the world and cleared by the LOGGED_IN handler. It is read only while
		// the client is not LOGGED_IN, so a flag left stale can never swallow live activity in the world.
		boolean loginReplay = plugin.client != null
			&& ((awaitingLogin && plugin.client.getGameState() != GameState.LOGGED_IN)
				|| tick - loginTick <= LOGIN_BURST_TICKS);
		judge(slot, state, nowOffer, loginReplay, tick, System.currentTimeMillis());
	}

	final GeObservationTracker observations = new GeObservationTracker();
	private String profileType;
	private String tokenFingerprint;

	// ---- GE SLOTS ACROSS A RESTART ----
	//
	// The tracker lived in memory, so a client restart forgot every open offer. The login replay then only took
	// a baseline: a fill that happened while away, and the terminal row of an offer that finished while away,
	// were never reported, and the collect came under a new offer_key. Now the slots are written after every
	// GE change and read back at the first GE event of the same token, account and profile. The next
	// observation reports the counter difference since the last row sent, as for a hop: identity
	// inferred_across_gap, the interval from that row to now, no fill time. A slot found EMPTY at login was
	// collected elsewhere and is dropped without a row, so a collect is never counted twice.
	//
	// IDENTITY. One entry per token fingerprint (never the token), account hash and profile. Another
	// account, profile or token never reads it. At most MAX_ENTRIES entries, newest kept.

	static final String FILE = "ge-slots.json";
	static final int MAX_ENTRIES = 8;
	/** The key whose saved slots were last restored or superseded; client-thread only. */
	private String restoredFor;

	private String key()
	{
		String fp = plugin.events.currentEventTokenFingerprint();
		if (fp == null || accountHash == -1L || profileType == null)
		{
			return null;
		}
		return fp + "|" + accountHash + "|" + profileType;
	}

	private Path path()
	{
		return plugin.spooler.spoolDataRoot().resolve("osrsbis").resolve(FILE);
	}

	/** Once per token, account and profile: take back the saved slots. Runs right after the tracker was cleared. */
	void restore()
	{
		String key = key();
		if (key == null || key.equals(restoredFor))
		{
			return;
		}
		restoredFor = key;
		try
		{
			for (Map<String, Object> entry : read())
			{
				if (key.equals(entry.get("key")) && entry.get("slots") instanceof List)
				{
					observations.restore((List<?>) entry.get("slots"));
					return;
				}
			}
		}
		catch (Throwable t)
		{
			log.debug("OSRS BiS GE slots could not be restored", t);
		}
	}

	/** Write the current slots under the current key. A failed write only costs the restart reconciliation. */
	void save()
	{
		String key = key();
		if (key == null || !key.equals(restoredFor) || plugin.gson == null)
		{
			return;	// not restored yet for this key: writing now would overwrite the saved slots with nothing
		}
		try
		{
			List<Map<String, Object>> all = read();
			all.removeIf(e -> key.equals(e.get("key")));
			Map<String, Object> entry = Fields.of(
				"key", key, "saved_at", System.currentTimeMillis(), "slots", observations.export());
			all.add(entry);
			all.sort(Comparator.comparingLong(e -> e.get("saved_at") instanceof Number ? ((Number) e.get("saved_at")).longValue() : 0L));
			while (all.size() > MAX_ENTRIES)
			{
				all.remove(0);
			}
			Path file = path();
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(FILE + ".tmp");
			Files.write(tmp, plugin.gson.toJson(all).getBytes(StandardCharsets.UTF_8));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		}
		catch (Throwable t)
		{
			log.debug("OSRS BiS GE slots could not be written", t);
		}
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> read() throws IOException
	{
		Path file = path();
		List<Map<String, Object>> rows = new ArrayList<>();
		if (plugin.gson == null || !Files.exists(file))
		{
			return rows;
		}
		try
		{
			Object parsed = plugin.gson.fromJson(new String(Files.readAllBytes(file),
				StandardCharsets.UTF_8), Object.class);
			if (parsed instanceof List)
			{
				for (Object row : (List<?>) parsed)
				{
					if (row instanceof Map)
					{
						rows.add(new LinkedHashMap<>((Map<String, Object>) row));
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("OSRS BiS GE slots file unreadable; starting fresh", e);
		}
		return rows;
	}

	private void prepareContext()
	{
		String profile = plugin.client == null || plugin.client.getWorldType() == null ? "UNKNOWN" : RuneScapeProfileType.getCurrent(plugin.client).name();
		String token = plugin.events.currentEventTokenFingerprint();
		if (!Objects.equals(profile, profileType)
			|| !Objects.equals(token, tokenFingerprint))
		{
			if (profileType != null && plugin.client != null)
			{
				loginTick = plugin.client.getTickCount();
			}
			observations.clear();
			profileType = profile;
			tokenFingerprint = token;
		}
	}

	/** GE slots held while the account hash was -1: slot -> {state, offer tuple, callback time}. Client-thread only. */
	final TreeMap<Integer, Object[]> unkeyed = new TreeMap<>();
	/** The client has 8 GE slots; the unkeyed buffer never holds more. */
	static final int SLOT_COUNT = 8;

	/**
	 * Settle the GE events held while the account hash was -1, now that the hash is known. Same account as
	 * the stored slots (a hop or relog): judge them in slot order as a login replay, so an offer that
	 * finished while away is reported once. Another account (or none stored): drop the old slots and take
	 * the held events as the new baseline, emitting nothing.
	 */
	void resolveUnkeyed(long acct)
	{
		if (unkeyed.isEmpty() || acct == -1L)
		{
			return;
		}
		prepareContext();
		List<Map.Entry<Integer, Object[]>> held = new ArrayList<>(unkeyed.entrySet());
		unkeyed.clear();
		int tick = plugin.client == null ? 0 : plugin.client.getTickCount();
		if (acct == accountHash)
		{
			restore();
			for (Map.Entry<Integer, Object[]> e : held)
			{
				judge(e.getKey(), (GrandExchangeOfferState) e.getValue()[0], (long[]) e.getValue()[1], true, tick, (Long) e.getValue()[2]);
			}
			return;
		}
		observations.clear();
		accountHash = acct;
		restore();
		for (Map.Entry<Integer, Object[]> e : held)
		{
			judge(e.getKey(), (GrandExchangeOfferState) e.getValue()[0],
				(long[]) e.getValue()[1], true, tick, (Long) e.getValue()[2]);
		}
	}

	/** Emit legacy cumulative fields plus explicitly qualified observation deltas. */
	private void judge(int slot, GrandExchangeOfferState state, long[] value, boolean replay, int tick, long observedAt)
	{
		Map<String, Object> fields = observations.observe(slot, state, value, replay, tick, observedAt);
		save();
		if (fields != null)
		{
			String type = (String) fields.remove("type");
			fields.put("profile_type", profileType);
			// The session upload loop may still name the previous account during login.
			Player self = plugin.client == null ? null : plugin.client.getLocalPlayer();
			fields.put("rsn", self == null ? null : self.getName());
			if (accountHash != -1L)
			{
				fields.put("account_hash", Long.toString(accountHash));
			}
			plugin.events.emitEvent(type, fields);
		}
	}

	/** "buy" or "sell" for an offer state; null for EMPTY. */
	static String side(GrandExchangeOfferState s)
	{
		if (s == null)
		{
			return null;
		}
		switch (s)
		{
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				return "buy";
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				return "sell";
			default:
				return null;
		}
	}
}
