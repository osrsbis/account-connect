package com.osrsbestinslot.export;

import java.util.Collections;

/**
 * Primitive-argument entry points into OffBookPendings for unit tests (same package only). They were members of
 * OffBookPendings with no production caller: the live client resolves through the ItemContainer and TileItem forms.
 */
final class OffBookHooks
{
	private OffBookHooks()
	{
	}

	/**
	 * Ground-spawn confirmation for drops (the M3 redesign). A "drop" pending resolves ONLY when BOTH signals
	 * co-occur: the armed item APPEARS ON THE GROUND on/adjacent to the player's tile, AND the player's
	 * inventory holds fewer of it than at click time. An inventory removal alone (equip / eat / bank deposit /
	 * destroy — including via widget buttons that carry no item id) spawns no ground item, so it can never
	 * fabricate a drop; a ground item alone (another player's drop becoming visible) shows no inventory loss,
	 * so it never resolves either.
	 * A plain ItemSpawned is always a fresh (grown) ground item.
	 */
	static void resolveDropPendingOnGroundSpawn(OffBookPendings o, int spawnedItemId, int dist, long invCountAfter, int currentTick)
	{
		resolveDropPendingOnGroundSpawn(o, spawnedItemId, dist, invCountAfter, currentTick, true);
	}

	static void resolveDropPendingOnGroundSpawn(OffBookPendings o, int spawnedItemId, int dist, long invCountAfter, int currentTick,
		boolean stackGrew)
	{
		resolveDropPendingOnGroundSpawn(o, spawnedItemId, dist, invCountAfter, currentTick, stackGrew, -1, -1, -1);
	}

	/** @param tileX tileY tilePlane the spawn's world tile, recorded on the drop row; -1 when unknown. */
	static void resolveDropPendingOnGroundSpawn(OffBookPendings o, int spawnedItemId, int dist, long invCountAfter, int currentTick,
		boolean stackGrew, int tileX, int tileY, int tilePlane)
	{
		if (dist > OffBookPendings.DROP_SPAWN_MAX_DIST || !stackGrew) return;
		o.resolveDropPendingOnGroundSpawn(o.pendingDropForSpawn(spawnedItemId, currentTick), invCountAfter,
			currentTick, tileX, tileY, tilePlane, -1);
	}

	/**
	 * Head-only entry point, kept for the single-pending call shape. The live client path uses
	 * resolveInvDeltaPendings(ItemContainer, ...) instead, which walks EVERY armed pending: with a queue,
	 * an unconfirmed drop sits at the head and would otherwise hide a later pickup behind it forever.
	 */
	static void resolveInvDeltaPending(OffBookPendings o, long itemCountAfter, long coinsAfter, int currentTick)
	{
		OffBookPendings.InvDeltaPending p;
		synchronized (o.invDeltaPendings)
		{
			p = o.invDeltaPendings.peekFirst();
		}
		if (p == null)
		{
			return;
		}
		o.resolveOnePending(p, itemCountAfter, coinsAfter, currentTick, Collections.singletonList(p));
	}
}
