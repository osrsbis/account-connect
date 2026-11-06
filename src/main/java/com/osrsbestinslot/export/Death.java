package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.InventoryID;
import net.runelite.api.NPC;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.ActorDeath;

/** Own death: the settle window and the item loss read from the live containers. */
final class Death
{
	private final AccountConnectPlugin plugin;

	Death(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	// ---- death items-lost: read pre-death inv+equip live at ActorDeath, resolve the loss diff once the
	// containers have settled (a few ticks later, at syncTask) or on logout — whichever comes first. ----
	static final int DEATH_SETTLE_TICKS = 4;	// item removal follows the death animation by a few ticks
	volatile DeathPending deathPending;

	static final class DeathPending
	{
		final Map<String, Object> location;	// {region_id, plane} at death, or null
		final String kind;					// "wilderness" | "pvp" | "safe" (only wilderness/pvp is a transfer)
		final Map<Integer, Long> preCounts;	// merged inventory + equipment item -> qty just before death
		final int tick;

		DeathPending(Map<String, Object> location, String kind, Map<Integer, Long> preCounts, int tick)
		{
			this.location = location;
			this.kind = kind;
			this.preCounts = preCounts;
			this.tick = tick;
		}
	}

	/** Own-account death (item-loss context). ActorDeath fires for any nearby actor, so filter to self. */
	void onActorDeath(ActorDeath event)
	{
		if (plugin.gameplayReady()) plugin.ownGameplay.activities.death(plugin.client, event.getActor(), System.currentTimeMillis());
		if (plugin.gameplayReady() && event.getActor() instanceof NPC)
			plugin.ownGameplay.endNpc(plugin.client, (NPC) event.getActor(), true, System.currentTimeMillis());
		if (event.getActor() != null && event.getActor() == plugin.client.getLocalPlayer())
		{
			// A death spawns the player's items on the ground at their tile WITH an inventory loss — exactly the
			// drop pending's dual confirmation signal. Kill any armed drop/pickup/alch pending: the loss belongs
			// to the death event's items_lost, never to a fabricated player-initiated drop.
			plugin.offBook.clearInvDeltaPendings();
			// Capture the pre-death inventory + equipment LIVE now: OSRS removes items a few ticks AFTER the death
			// animation, so the containers are still intact at ActorDeath ([verify in-client] — the standard
			// RuneLite death-tracking timing assumption). Defer the emit; items_lost is the pre/post diff resolved
			// once the containers settle (at syncTask, DEATH_SETTLE_TICKS later) or on logout — whichever first.
			deathPending = new DeathPending(plugin.snapshots.currentLocation(), deathKind(),
				mergedInvEquipCounts(), plugin.client == null ? 0 : plugin.client.getTickCount());
		}
	}

	/**
	 * death_kind: "wilderness" (inside the Wilderness — items drop to the killer), "pvp" (a PvP / DMM / high-risk
	 * world), or "safe" (PvM / minigame — a gp-sink item-retrieval reclaim, NOT a transfer). Only wilderness/pvp is
	 * an inter-account transfer. IN_WILDERNESS is the standard client wilderness signal.
	 */
	private String deathKind()
	{
		if (plugin.client == null)
		{
			return "safe";
		}
		if (plugin.client.getVarbitValue(Varbits.IN_WILDERNESS) > 0)
		{
			return "wilderness";
		}
		Set<WorldType> wt = plugin.client.getWorldType();
		if (wt != null && (wt.contains(WorldType.PVP) || wt.contains(WorldType.DEADMAN) || wt.contains(WorldType.HIGH_RISK)))
		{
			return "pvp";
		}
		return "safe";
	}

	/** Merged inventory + equipment item -> total qty, read live. Null-safe (empty when a container is absent). */
	private Map<Integer, Long> mergedInvEquipCounts()
	{
		Map<Integer, Long> counts = new LinkedHashMap<>();
		if (plugin.client == null)
		{
			return counts;
		}
		plugin.snapshots.addContainerCounts(counts, plugin.client.getItemContainer(InventoryID.INVENTORY));
		plugin.snapshots.addContainerCounts(counts, plugin.client.getItemContainer(InventoryID.EQUIPMENT));
		return counts;
	}

	/** Resolve a deferred death from the settle-window path: containers are live AND settled, so the loss diff
	 * is trustworthy (computeLoss=true). The logout/disconnect path calls resolveDeathPending(null, false). */
	void resolveDeathPendingViaLiveContainers()
	{
		if (deathPending != null)
		{
			resolveDeathPending(mergedInvEquipCounts(), true);
		}
	}

	/**
	 * Emit the deferred death event: {location?, death_kind, items_lost[]?}. death + kind + location are always
	 * recorded. items_lost is emitted ONLY when it can be trusted: (a) computeLoss — the settle-window path where
	 * the containers are live AND settled (the logout/disconnect path passes false, where the containers are null
	 * or not-yet-settled and a diff would over- or under-report), AND (b) the death is a real inter-account
	 * transfer (wilderness/pvp). A safe death is a gp-sink reclaim, not a transfer, and its settle-window diff is
	 * polluted by post-death eating/drinking — so it never carries items_lost. A wrong items_lost on a monitoring
	 * feed is worse than an absent one; the server wealth-reconciliation still catches the delta either way.
	 * Package-private + map arg so it is unit-testable without a client.
	 */
	void resolveDeathPending(Map<Integer, Long> postCounts, boolean computeLoss)
	{
		DeathPending p = deathPending;
		if (p == null)
		{
			return;
		}
		deathPending = null;
		Map<String, Object> fields = new LinkedHashMap<>();
		if (p.location != null)
		{
			fields.put("location", p.location);
		}
		fields.put("death_kind", p.kind);
		fields.put("death_tick", p.tick);
		if (computeLoss && ("wilderness".equals(p.kind) || "pvp".equals(p.kind)))
		{
			List<Map<String, Object>> lost = new ArrayList<>();
			for (Map.Entry<Integer, Long> e : p.preCounts.entrySet())
			{
				long before = e.getValue();
				long after = postCounts == null ? 0L : postCounts.getOrDefault(e.getKey(), 0L);
				long d = before - after;
				if (d > 0)
				{
					Map<String, Object> m = Fields.of("id", e.getKey(), "qty", d);
					lost.add(m);
				}
			}
			fields.put("items_lost", lost);
		}
		plugin.events.emitEvent("death", fields);
	}
}
