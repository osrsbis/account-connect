/*
 * OSRS Best in Slot — event priority classes for the in-memory queue.
 *
 * A pure helper: no RuneLite types, no network. The pending buffer holds at most MAX_PENDING_EVENTS rows.
 * When it overflows, the plugin used to drop the OLDEST row whatever its type, so a long 429 run could
 * throw away a Grand Exchange completion to keep a region crossing. Overflow now drops the oldest row of
 * the LOWEST class present, so money and session rows survive as long as any lower row can go instead.
 *
 *   P0  ge_*, trade, store_*, death, login, logout   money movements and session edges
 *   P1  loot, drop, pickup, alch, ground_removed, level_up, kill_count, pet, bank_session, bank_visit_*
 *   P2  everything else (region, equip_change, xp_gain, world_hop, fh_batch, ...)
 *   A verification row of a frame taken for upload (its capture, screen and upload rows, marked FRAME_BOUND) stays
 *   P2 but goes last within P2: a 429 hold drops every other P2 row first, so an image keeps its rows as long as
 *   possible, and verification evidence never pushes out a P0 or P1 game event.
 *
 * Order inside the buffer is never changed: a row is removed, the others keep their positions.
 */
package com.osrsbestinslot.export;

import java.util.List;
import java.util.Map;

final class EventPriority
{
	static final int P0 = 0;
	static final int P1 = 1;
	static final int P2 = 2;
	/** Local delivery key (never sent): the row belongs to a verification frame taken for upload. */
	static final String FRAME_BOUND = "__frame_bound";

	private EventPriority()
	{
	}

	static int classOf(String type)
	{
		if (type == null)
		{
			return P2;
		}
		if (type.startsWith("ge_") || type.startsWith("store_") || type.equals("trade") || type.equals("death")
			|| type.equals("login") || type.equals("logout"))
		{
			return P0;
		}
		if (type.equals("loot") || type.equals("drop") || type.equals("pickup") || type.equals("alch")
			|| type.equals("ground_removed") || type.equals("level_up") || type.equals("kill_count")
			|| type.equals("pet") || type.equals("bank_session") || type.startsWith("bank_visit_")
			|| type.startsWith("drop_trade_clip"))
		{
			return P1;
		}
		return P2;
	}

	static int rowClass(Map<String, Object> row)
	{
		Object t = row.get("type");
		return classOf(t == null ? null : String.valueOf(t));
	}

	/**
	 * Index of the row overflow should drop: the oldest row of the lowest class present, where within P2 a frame's row
	 * goes only when no other P2 row is left. -1 when empty.
	 */
	static int evictIndex(List<Map<String, Object>> pending)
	{
		int best = -1;
		int bestClass = -1;
		int oldestFrameRow = -1;
		for (int i = 0; i < pending.size(); i++)
		{
			Map<String, Object> row = pending.get(i);
			int c = rowClass(row);
			if (c == P2 && Boolean.TRUE.equals(row.get(FRAME_BOUND)))
			{
				if (oldestFrameRow < 0) oldestFrameRow = i;
				continue;
			}
			if (c > bestClass)
			{
				bestClass = c;
				best = i;
				if (c == P2)
				{
					return i;	// the oldest P2 row that is not a frame's
				}
			}
		}
		return oldestFrameRow >= 0 ? oldestFrameRow : best;
	}
}
