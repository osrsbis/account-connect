/*
 * OSRS Best in Slot — the player's own game variables, as raw values.
 *
 * WHY RAW. Every account-state source the audited Plugin Hub plugins read (quests, diaries, combat
 * achievement tasks, Slayer task/boss/area/size, prayer unlocks, Sailing charting, farming patches,
 * minigame points, unlocks, kill counters kept in varps) is a VARP or a VARBIT, and every varbit is a bit
 * range of a varp. So the full varp array decodes to all of them on the server, with no per-feature
 * client code and no plugin release when a new game update adds a variable. The registry in the lane
 * (`registry/registry_aggregate.tsv`) maps each decoded field to its source id and the plugins that use it.
 *
 * WHAT IS SENT. Only the LOCAL player's own variables, which the game server sends to this client for this
 * account. Nothing about other players is in this array. The encoding is sparse (non-zero entries only),
 * because a fresh account has almost every varp at 0: {"n": length, "nz": {"<index>": value, ...},
 * "sha": sha256 of the encoded map}. Absent index = 0 in the game, which is the game's own default for a
 * varp, so this does not turn an unknown into zero: the whole block is absent until the client has
 * finished loading the player's variables (the caller checks that).
 *
 * A pure helper: no RuneLite types, so it is unit-testable without a client.
 */
package com.osrsbestinslot.export;

import java.util.LinkedHashMap;
import java.util.Map;

final class GameVarsCapture
{
	private GameVarsCapture()
	{
	}

	/** Sparse encoding of a varp array, or null when the array is missing. */
	static Map<String, Object> encode(int[] varps)
	{
		if (varps == null)
		{
			return null;
		}
		Map<String, Object> nz = new LinkedHashMap<>();
		StringBuilder canon = new StringBuilder();
		for (int i = 0; i < varps.length; i++)
		{
			if (varps[i] != 0)
			{
				nz.put(Integer.toString(i), varps[i]);
				canon.append(i).append('=').append(varps[i]).append(';');
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("n", varps.length);
		out.put("nz", nz);
		out.put("sha", AccountConnectPlugin.sha256Hex(canon.toString()));
		return out;
	}

	/** Decode one varbit from a varp value, given the varbit's bit range (inclusive). Used by tests and docs. */
	static int varbit(int varpValue, int lsb, int msb)
	{
		int width = msb - lsb + 1;
		int mask = width >= 32 ? -1 : (1 << width) - 1;
		return (varpValue >>> lsb) & mask;
	}
}
