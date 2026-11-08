package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WorldChanged;

/** Own session rows: login, logout with its reason, world hop, region change, xp gains and equipment changes. */
final class SessionEvents
{
	private final AccountConnectPlugin plugin;

	SessionEvents(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	// Equipment change capture: last-seen worn-item counts, diffed on each WORN-container change to emit
	// equip_change {equipped[], unequipped[]}. Only a change in the SET of worn item ids emits: a quantity
	// change of an item still worn (ammo spent on every attack) is not an equipment change and emits
	// nothing, else one ranger floods the event ingest. The first change after a login only baselines, so
	// a normal gear load does not emit a spurious full-kit equip. Reset on hop and relog.
	Map<Integer, Long> equipLast;

	// World-hop capture: the last world we were on, 0 when unknown. WorldChanged after a hop emits
	// world_hop {from,to}. The initial login WorldChanged is suppressed by lastWorld 0. Reset on logout.
	int lastWorld;

	// XP-gain capture (coalesced): accumulate per-skill xp deltas from StatChanged, which fires on every
	// drop, and flush them as ONE xp_gain event every XP_FLUSH_TICKS (5 minutes) and at logout, world hop
	// and disconnect, so per-action xp does not flood the event plane: at most 12 timed flushes per hour of
	// training plus one at session end, and no accumulated xp is dropped at a session boundary. lastSkillXp = last-seen total per skill (the baseline). Reset per account
	// on hop, relog and logout.
	final Map<String, Integer> lastSkillXp = new LinkedHashMap<>();
	final Map<String, Long> xpAccum = new LinkedHashMap<>();
	int xpFlushTicks;
	static final int XP_FLUSH_TICKS = 500;	// 5 minutes at 0.6s per tick
	// Region capture: emit region {from,to} only when the map region changes, which coalesces naturally.
	Integer lastRegion;
	volatile boolean sessionActive;
	volatile long sessionStartMillis;
	// WAVE 3: idle counters sampled on the last logged-in tick (syncTask), used to classify the logout reason.
	volatile int lastKeyboardIdleTicks;
	volatile int lastMouseIdleTicks;
	// Logout-reason heuristics: a session within this window of 6h is the in-game six-hour cap; both idle
	// counters at or above this count at logout = an idle timeout, else a manual logout. The counters count client
	// ticks (Constants.CLIENT_TICK_LENGTH, 20 ms), not game ticks. The game's idle logout comes after 5 minutes without
	// input; the sample is up to one 5 s sync old, so the bound is 5 minutes less 10 s.
	private static final long SIX_HOUR_MS = 6L * 60L * 60L * 1000L;
	private static final long SIX_HOUR_SLACK_MS = 3L * 60L * 1000L;
	static final int IDLE_LOGOUT_TICKS = (5 * 60 * 1000 - 10 * 1000) / Constants.CLIENT_TICK_LENGTH;
	/** Dedup state for the broad activity sweep: emit a level event only on a real level change, a GE event
	 *  only on a state transition (not every partial-fill tick). Client-thread only. */
	final Map<String, Integer> lastSkillLevel = new HashMap<>();

	/**
	 * Detect session start on the client thread (player available): the first logged-in tick, or a hop
	 * into a different account, emits a "login" event and stamps the session. Called from syncTask.
	 */
	boolean trackSessionStart()
	{
		if (plugin.clientClosing) return false;
		String rsn = plugin.client.getLocalPlayer().getName();
		String hash = Long.toString(plugin.client.getAccountHash());
		if (!sessionActive || !hash.equals(plugin.activeHash))
		{
			plugin.activeRsn = rsn;
			plugin.activeHash = hash;
			plugin.outbox.endedPublicOwner = null;
			plugin.spooler.syncSpoolState();	// an account change purges the previous account's durable spool
			sessionActive = true;
			sessionStartMillis = System.currentTimeMillis();
			plugin.events.emitEvent("login", loginFields());
			return true;
		}
		plugin.activeRsn = rsn; // keep the display name fresh
		return false;
	}

	/** Extra fields carried on the login event. WAVE 3: net worth at login (from the just-built snapshot). */
	private Map<String, Object> loginFields()
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		Long netWorth = lastKnownNetWorth();
		if (netWorth != null)
		{
			fields.put("wealth", netWorth);
		}
		return fields;
	}

	/**
	 * Net worth (gp) from the most recently built snapshot's wealth block, for the login/logout events:
	 * net_worth_gp (inventory + equipment + bank). null when no snapshot has been built yet or this login has
	 * not read the bank. The old fallback to carried inventory + worn equipment gave one field two meanings
	 * (live 0.7.55: 11.38M with the bank, 5.05M without it on the same account), so it is omitted instead.
	 */
	Long lastKnownNetWorth()
	{
		Map<String, Object> snap = plugin.uploader.lastBuiltSnapshot;
		if (snap == null)
		{
			return null;
		}
		Object w = snap.get("wealth");
		if (!(w instanceof Map))
		{
			return null;
		}
		Object net = ((Map<?, ?>) w).get("net_worth_gp");
		return net instanceof Number ? ((Number) net).longValue() : null;
	}

	/** Emit a "logout" event (duration + classified reason) for the tracked account, if a session was active. */
	void trackLogout()
	{
		trackLogout(classifyLogoutReason());
	}

	/**
	 * WAVE 3: emit a "logout" with an explicit reason (idle / manual / six_hour_cap / connection_lost) plus
	 * session duration and the account's net worth at logout. reason=connection_lost is passed by the
	 * CONNECTION_LOST game-state (which previously emitted nothing).
	 */
	void trackLogout(String reason)
	{
		if (!sessionActive)
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		if (sessionStartMillis > 0)
		{
			fields.put("session_ms", System.currentTimeMillis() - sessionStartMillis);
		}
		if (reason != null)
		{
			fields.put("reason", reason);
		}
		Long netWorth = lastKnownNetWorth();
		if (netWorth != null)
		{
			fields.put("wealth", netWorth);
		}
		plugin.events.emitEvent("logout", fields);
		plugin.outbox.endedPublicOwner = plugin.outbox.publicQueueContext(true);
		plugin.outbox.endedPublicToken = plugin.currentLinkToken();
		sessionActive = false;
		plugin.eventObservationContext.clear();
	}

	/**
	 * Best-effort logout classification from the last logged-in tick's idle counters + session length. A
	 * session length within {@link #SIX_HOUR_SLACK_MS} of the 6h cap is the in-game six-hour logout; both
	 * idle counters high = an idle timeout; otherwise a manual logout. Heuristic (no client API says WHY the
	 * client logged out) — connection loss is signalled explicitly via {@link #trackLogout(String)} instead.
	 */
	String classifyLogoutReason()
	{
		long sessionMs = sessionStartMillis > 0 ? System.currentTimeMillis() - sessionStartMillis : 0L;
		if (sessionMs >= SIX_HOUR_MS - SIX_HOUR_SLACK_MS)
		{
			return "six_hour_cap";
		}
		if (Math.min(lastKeyboardIdleTicks, lastMouseIdleTicks) >= IDLE_LOGOUT_TICKS)
		{
			return "idle";
		}
		return "manual";
	}

	/**
	 * Emit equip_change from the diff of the worn-equipment container against its last-seen state. The
	 * first change after a login only baselines, so a normal gear load does not emit a spurious full-kit
	 * equip. Own account, and the same data class as the equipment snapshot.
	 */
	void handleEquipmentChanged(ItemContainer worn)
	{
		if (worn == null)
		{
			return;
		}
		Map<Integer, Long> now = new LinkedHashMap<>();
		plugin.snapshots.addContainerCounts(now, worn);
		Map<Integer, Long> before = equipLast;
		equipLast = now;
		if (before == null || !plugin.activityLogActive())
		{
			return;		// first observation this session is a baseline, no event
		}
		// Diff the id SETS only. A quantity change of an id worn before and after (ammo shot, a dart
		// thrown, a stack topped up) is not an equip/unequip and emits nothing.
		List<Map<String, Object>> equipped = new ArrayList<>();
		List<Map<String, Object>> unequipped = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : now.entrySet())
		{
			if (!before.containsKey(e.getKey()))
			{
				equipped.add(plugin.snapshots.itemMapLong(e.getKey(), e.getValue()));
			}
		}
		for (Map.Entry<Integer, Long> e : before.entrySet())
		{
			if (!now.containsKey(e.getKey()))
			{
				unequipped.add(plugin.snapshots.itemMapLong(e.getKey(), e.getValue()));
			}
		}
		if (equipped.isEmpty() && unequipped.isEmpty())
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		if (!equipped.isEmpty())
		{
			fields.put("equipped", equipped);
		}
		if (!unequipped.isEmpty())
		{
			fields.put("unequipped", unequipped);
		}
		plugin.events.emitEvent("equip_change", fields);
	}

	/** World hop: emit world_hop {from,to} when the world changes. The login's first change is suppressed. */
	void onWorldChanged(WorldChanged event)
	{
		if (plugin.client == null)
		{
			return;
		}
		int w = plugin.client.getWorld();
		int prev = lastWorld;
		lastWorld = w;
		if (prev != 0 && w != prev && plugin.activityLogActive())
		{
			Map<String, Object> fields = Fields.of("from", prev, "to", w);
			GameState state = plugin.client.getGameState();
			if (state == GameState.HOPPING || state == GameState.LOADING)
			{
				// During a hop the game mode and the observation context are cleared until the next login. Live
				// 0.7.61/0.7.62 (10-29..30): all 2,930 world_hop rows had neither. It is sent on the first tick in the world.
				pendingWorldHop = fields;
				return;
			}
			plugin.events.emitEvent("world_hop", fields);
		}
	}

	/** A hop seen while the client was between worlds, sent once the new world's context is known. */
	Map<String, Object> pendingWorldHop;

	void finishPendingWorldHop()
	{
		Map<String, Object> hop = pendingWorldHop;
		if (hop == null || plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		pendingWorldHop = null;
		if (plugin.activityLogActive())
		{
			plugin.events.emitEvent("world_hop", hop);
		}
	}

	/** Level-ups: StatChanged fires on every xp drop, so emit only when the real level increases. */
	void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == null)
		{
			return;
		}
		String skill = event.getSkill().getName();
		int level = event.getLevel();
		Integer prev = lastSkillLevel.put(skill, level);
		if (prev != null && level > prev && plugin.activityLogActive())
		{
			Map<String, Object> fields = Fields.of("skill", skill, "level", level);
			if (plugin.client != null)
			{
				fields.put("xp", plugin.client.getSkillExperience(event.getSkill()));	// WAVE 3: total xp at level-up
			}
			plugin.events.emitEvent("level_up", fields);
		}
		// XP gain (coalesced): accumulate the per-skill delta from the event's total xp. The tick timer
		// flushes it as one xp_gain event. First observation per skill only baselines (prevXp null).
		int nowXp = event.getXp();
		Integer prevXp = lastSkillXp.put(skill, nowXp);
		if (prevXp != null && nowXp > prevXp)
		{
			xpAccum.merge(skill, (long) (nowXp - prevXp), Long::sum);
		}
	}

	/** Emit one coalesced xp_gain event for all per-skill xp accumulated since the last flush. */
	void flushXpGain()
	{
		if (xpAccum.isEmpty() || !plugin.activityLogActive())
		{
			return;
		}
		List<Map<String, Object>> gains = new ArrayList<>();
		long total = 0;
		for (Map.Entry<String, Long> e : xpAccum.entrySet())
		{
			Map<String, Object> g = Fields.of("skill", e.getKey(), "xp", e.getValue());
			gains.add(g);
			total += e.getValue();
		}
		xpAccum.clear();
		Map<String, Object> fields = Fields.of("gains", gains, "total", total);
		plugin.events.emitEvent("xp_gain", fields);
	}

	/** Emit region {from,to,x,y} when the map region changes. The first observation only baselines. */
	void checkRegionChange()
	{
		if (plugin.client == null)
		{
			return;
		}
		Player self = plugin.client.getLocalPlayer();
		WorldPoint wp = self == null ? null : self.getWorldLocation();
		if (wp == null)
		{
			return;
		}
		int region = wp.getRegionID();
		Integer prev = lastRegion;
		lastRegion = region;
		if (prev != null && prev != region)
		{
			Map<String, Object> fields = Fields.of("from", prev, "to", region, "x", wp.getX(), "y", wp.getY());
			plugin.events.emitEvent("region", fields);
		}
	}

	/** Clog + trade state are per-account — clear them on hop / relog so we never mix two accounts. */
	void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOADING) { plugin.accountProgress.storageRecovery.gap(); plugin.ownGameplay.gap(); }
		if (state != GameState.LOGGED_IN && state != GameState.LOADING)
		{
			plugin.grants.clearVerificationGrant();
			plugin.bankVisit.finishPublicBankClose(true);	// a close still waiting goes now, with its list unless a snapshot holds it
			plugin.collectionSetupPending = false;
			plugin.uploader.deferredForce = false;
			plugin.uploader.queuedBankSha = null;
			plugin.uploader.acceptedBankSha = null;
			plugin.collectionLogOpenTick = -1;
			plugin.snapshots.containerObservedAtMs.clear(); // observations from a prior account/world login cannot timestamp a new cache
			plugin.snapshots.lastBankItems = null;
			plugin.bankVisit.bankChangeTick = Integer.MIN_VALUE / 2;
			plugin.accountProgress.reset();
			plugin.collectionScan.reset();
			plugin.ownGameplay.reset();
			plugin.ge.observations.gap();
			// Same token, next account: one account's counters must never be compared with another's, even when
			// no game tick runs between the logout and the next login.
			plugin.slayerTasks.reset();
			plugin.activityCounters.reset();
		}
		// A store-clip visit must not survive a hop / logout / disconnect. End it here; frames are still
		// valid delivery proof if the visit had a buy/sell (upload=true only uploads when it did), else dropped.
		if (plugin.clips.clipCapturing
			&& (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN
				|| state == GameState.CONNECTION_LOST || state == GameState.LOGGING_IN))
		{
			plugin.clips.stopStoreClipCapture(true);
		}
		// A hop or a disconnect ends the shop session, so the observed phase no longer describes the
		// shop in front of us. Drop it: an anchor that survives a hop is a confidently wrong countdown.
		if (state == GameState.HOPPING || state == GameState.LOGIN_SCREEN
			|| state == GameState.CONNECTION_LOST || state == GameState.LOGGING_IN)
		{
			plugin.store.shopOpen = false; // WidgetClosed is not guaranteed during teardown
			plugin.store.storeResetAnchorMs = 0;
			plugin.store.clearResetPhaseEvidence();
			plugin.store.removeResetTimer();
		}
		switch (event.getGameState())
		{
			case LOADING:
				// Region transition — a NEW instanced chest may lie ahead; the belt keys are per-visit.
				plugin.kills.chestLooted = false;
				plugin.kills.lastChestEmitKey = null;
				// Scene reload replays existing ground piles as fresh ItemSpawned events (review
				// 2026-07-18) — a surviving pending could pair a replayed same-id pile with an unrelated
				// inventory loss. A drop clicked right before a region boundary is lost; miss beats fabricate.
				plugin.offBook.clearInvDeltaPendings();
				// A scene reload despawns EVERY pile in the old scene at once. Those removals say nothing
				// about anyone taking the item, so stop trusting observation and discard what we tracked
				// rather than emit a wave of "removed_early" that means nothing.
				plugin.piles.groundObservationUnreliable = true;
				plugin.piles.clearGroundDrops();
				plugin.piles.clearPendingRemovals();
				plugin.dropProof.interruptDropSession();	// a scene reload ends the session as INTERRUPTED; footage still uploads
				break;
			case HOPPING:
			case LOGGING_IN:
				plugin.clogObtained.clear();
				plugin.clogSeen = false;
				plugin.ge.awaitingLogin = true;	// the next LOGGED_IN replays the GE slots
				plugin.trade.resetTradeState();	// a pending trade frame must never leak across accounts/sessions
				plugin.offBook.clearInvDeltaPendings();	// an armed drop/pickup/alch must never resolve across a hop/relog
				plugin.bankVisit.closeBankVisit(BankVisit.teardownReason(event.getGameState()), null);
				plugin.bankVisit.endBankSession(false);	// a bank left open across a hop/relog: incomplete, never diffed against another account
				equipLast = null;	// re-baseline equipment on the next change so a relog emits no full-kit diff
				flushXpGain();		// a hop ends the xp window: emit what accumulated, never drop it
				xpFlushTicks = 0;
				lastSkillXp.clear();	// xp deltas re-baseline per account
				lastSkillLevel.clear();	// level-ups too: the next account's login burst only baselines
				xpAccum.clear();
				lastRegion = null;
				plugin.piles.groundObservationUnreliable = true;
				plugin.piles.clearGroundDrops();
				plugin.piles.clearPendingRemovals();		// ground state is per world AND per account
				plugin.dropProof.interruptDropSession();	// a hop ends the session as INTERRUPTED; footage still uploads
				plugin.store.shopVisitNearby.clear();	// nearby-candidate set must not carry rsns across accounts
				plugin.store.shopStock.clear();		// stock/sold/at-tx state is per visit AND per account
				plugin.store.storeResetAnchorMs = 0;	// phase is per-visit: never carry it across accounts
				plugin.store.lastStockChangeMs = 0;
				plugin.store.clearResetPhaseEvidence();
				plugin.store.storeProbeItem = 0;
				plugin.store.soldThisVisit.clear();
				plugin.store.defaultStockSoldThisVisit.clear();
				plugin.store.lastSellTickThisVisit.clear();
				plugin.store.lastOwnStoreClickMs = 0;
				plugin.store.shopkeeperName = null;
				plugin.store.shopkeeperIndex = -1;
				plugin.store.nearbyAtTx = null;
				plugin.kills.chestLooted = false;
				plugin.kills.lastChestEmitKey = null;
				break;
			case CONNECTION_LOST:
				pendingWorldHop = null;
				plugin.clogObtained.clear();
				plugin.clogSeen = false;
				plugin.ge.awaitingLogin = true;
				plugin.trade.resetTradeState();
				plugin.offBook.clearInvDeltaPendings();	// an armed drop/pickup/alch must never survive a disconnect
				plugin.bankVisit.closeBankVisit("disconnect", null);
				plugin.bankVisit.endBankSession(false);	// a bank open at disconnect: incomplete, never diffed against the next session
				equipLast = null;	// re-baseline equipment on reconnect
				flushXpGain();		// a disconnect ends the xp window: emit what accumulated, never drop it
				xpFlushTicks = 0;
				lastSkillXp.clear();
				lastSkillLevel.clear();
				xpAccum.clear();
				lastRegion = null;
				plugin.piles.groundObservationUnreliable = true;
				plugin.piles.clearGroundDrops();
				plugin.piles.clearPendingRemovals();
				plugin.dropProof.interruptDropSession();	// a disconnect ends the session as INTERRUPTED; footage still uploads
				// a feed-death then instant disconnect: record the death, but the containers here are null or
				// not-yet-settled, so OMIT items_lost (computeLoss=false) rather than emit a wrong diff.
				plugin.death.resolveDeathPending(null, false);
				// WAVE 3: a dropped connection previously emitted NOTHING. Emit an explicit logout so a crash /
				// disconnect is distinguishable from a clean logout. Ends the session; a reconnect re-logs in.
				trackLogout("connection_lost");
				break;
			case LOGIN_SCREEN:
				// Real logout (HOPPING keeps the session and is handled above, without a flush).
				plugin.ge.awaitingLogin = true;
				plugin.ge.unkeyed.clear();	// held -1 GE events belong to the login that just ended
				plugin.offBook.clearInvDeltaPendings();	// an armed drop/pickup/alch must never survive a logout
				plugin.bankVisit.closeBankVisit("logout", null);
				plugin.bankVisit.endBankSession(false);	// a bank open at logout: incomplete, never diffed against the next session
				lastWorld = 0;
				pendingWorldHop = null;	// a hop that never reached the new world is not sent	// the next login's WorldChanged must not be read as a hop
				flushXpGain();		// flush accumulated xp before the session ends
				xpFlushTicks = 0;
				lastSkillXp.clear();
				lastSkillLevel.clear();
				xpAccum.clear();
				lastRegion = null;
				plugin.piles.groundObservationUnreliable = true;
				plugin.piles.clearGroundDrops();
				plugin.piles.clearPendingRemovals();
				plugin.dropProof.interruptDropSession();	// a logout ends the session as INTERRUPTED; footage still uploads
				plugin.death.resolveDeathPending(null, false);	// feed-death then logout: record death, omit untrustworthy items_lost
				trackLogout(); // buffer a "logout" event (duration + reason); flushed live (WAVE 2) / next tick
				plugin.uploader.flushPendingSnapshot();
				break;
			case LOGGED_IN:
				plugin.outbox.endedPublicOwner = null;	// rows of the session before wait for their own account again
				plugin.refreshProfileType(); // before replay/loot events, not five seconds later at syncTask
				// Scene is settled again. Nothing tracked survives from before, so observation is trustworthy
				// for piles dropped from here on.
				plugin.piles.groundObservationUnreliable = false;
				// A login to the world the login screen already had fires no WorldChanged, so lastWorld stayed 0
				// and the FIRST hop of the session was read as the login and emitted nothing (F-H1). Seed it
				// here. Only when unset: after a hop lastWorld already holds the world and must not be touched.
				if (lastWorld == 0 && plugin.client != null)
				{
					lastWorld = plugin.client.getWorld();
				}
				// Only a real login or hop replays the GE slots. LOGGED_IN also follows every scene LOADING, and a
				// fill landing right after a region change must not be swallowed as a replay.
				if (plugin.client != null && plugin.ge.awaitingLogin)
				{
					plugin.ge.loginTick = plugin.client.getTickCount();
					plugin.worldLoginTick = plugin.ge.loginTick;
				}
				plugin.ge.awaitingLogin = false;
				break;
			default:
				break;
		}
		if (state == GameState.HOPPING || state == GameState.LOGGING_IN
			|| state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			// Flush old-session rows above with their old mode, then discard it for the next login.
			plugin.activeProfileType = null;
			plugin.eventObservationContext.clear();
		}
	}
}
