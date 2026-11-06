package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Player;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/** Players the client has loaded around us in the Wilderness, attached to own drop and trade rows. */
final class WildernessPlayers
{
	private final AccountConnectPlugin plugin;

	WildernessPlayers(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	// ---- WILDERNESS SURROUNDING PLAYERS (staff only) ----
	//
	// WHAT THIS CAN SEE, from the RuneLite 1.13.1 API itself. client.getPlayers() is the top-level world
	// view's player set: every player the SERVER sent to this client, and nobody else. The minimap draws
	// a zoom-dependent subset of it. So the honest label is "client_loaded": it is not a claim that nobody
	// else was nearby. FLOOR IS UNKNOWN: Player.getWorldLocation() is built from the world view's plane
	// (the viewer's), so another player's floor cannot be told apart; rows say plane_known:false and no
	// row is ever dropped for its floor.
	//
	// PRESENCE IS EVIDENCE, NEVER ATTRIBUTION: nothing here names who took a pile or who received a trade.
	// NAMES are the exact displayed names at that moment. No alias, history or identity link is made.
	// ONLY CURRENT-AT-EVENT SNAPSHOTS are emitted. Nothing is accumulated across ticks.

	/** Evidence label for {@link #surroundingPlayersNow}. Changing it changes what the rows claim. */
	static final String SURROUNDING_OBSERVATION = "client_loaded";
	/** Most rows one snapshot carries before the size budget is even considered. */
	static final int SURROUNDING_ROW_CAP = 64;
	/**
	 * Largest encoded event the snapshot may grow to. The live /event-ingest skips an event whose fields
	 * blob exceeds 8192 chars and still answers 200, so nothing would ever report the loss. 512 chars of
	 * headroom under that.
	 */
	static final int SURROUNDING_EVENT_BUDGET = 7680;

	/** Is our character in the Wilderness right now? The same varbit drop.wilderness and death_kind use. */
	boolean inWilderness()
	{
		return plugin.client != null && plugin.client.getVarbitValue(Varbits.IN_WILDERNESS) > 0;
	}

	/**
	 * Every other loaded player right now: {rsn, dx, dy, dist, cb}, nearest first, ties by name so the
	 * order is deterministic. Uncapped; callers cap and say so. dist is 2D Chebyshev (floor unknown).
	 */
	List<Map<String, Object>> surroundingPlayersNow()
	{
		if (!plugin.grants.captureEnabled()) return Collections.emptyList();

		List<Map<String, Object>> out = new ArrayList<>();
		if (plugin.client == null)
		{
			return out;
		}
		Player self = plugin.client.getLocalPlayer();
		List<Player> players = plugin.client.getPlayers();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me == null || players == null)
		{
			return out;
		}
		for (Player p : players)
		{
			WorldPoint loc = p == null ? null : p.getWorldLocation();
			if (p == null || p == self || p.getName() == null || p.getName().isEmpty() || loc == null)
			{
				continue;
			}
			int dx = loc.getX() - me.getX();
			int dy = loc.getY() - me.getY();
			Map<String, Object> m = Fields.of(
				"rsn", Text.removeTags(p.getName()), "dx", dx, "dy", dy, "dist", Math.max(Math.abs(dx), Math.abs(dy)),
				"cb", p.getCombatLevel());
			out.add(m);
		}
		out.sort((a, b) ->
		{
			int c = Integer.compare((Integer) a.get("dist"), (Integer) b.get("dist"));
			return c != 0 ? c : ((String) a.get("rsn")).compareTo((String) b.get("rsn"));
		});
		return out;
	}

	/**
	 * Attach a current-at-event snapshot to {@code fields} under {@code key}, if the staff drop-proof grant
	 * holds and we are in the Wilderness. Rows are the nearest first; the first {@link #SURROUNDING_ROW_CAP}
	 * are kept, then rows drop from the far end until the whole event fits {@link #SURROUNDING_EVENT_BUDGET}.
	 * The own-account row is never shrunk: with no room at all the block says so with zero rows.
	 */
	void attachSurroundingSnapshot(Map<String, Object> fields, String key)
	{
		if (!plugin.grants.dropProofEnabled() || !inWilderness())
		{
			return;
		}
		Player self = plugin.client.getLocalPlayer();
		WorldPoint me = self == null ? null : self.getWorldLocation();
		if (me == null)
		{
			return;
		}
		List<Map<String, Object>> all = surroundingPlayersNow();
		Map<String, Object> block = Fields.of(
			"observation", SURROUNDING_OBSERVATION, "plane_known", false, "wilderness", true,
			"anchor", Arrays.asList(me.getX(), me.getY(), me.getPlane()), "observed_count", all.size());
		fields.put(key, fitSurrounding(fields, key, block, all));
	}

	/**
	 * Fill {@code block} with as many of {@code rows} as fit. Deterministic: the same rows always give the
	 * same output. Measures the event as the server will see it, event_id and context included.
	 */
	Map<String, Object> fitSurrounding(Map<String, Object> fields, String key, Map<String, Object> block,
		List<Map<String, Object>> rows)
	{
		int n = Math.min(rows.size(), SURROUNDING_ROW_CAP);
		String reason = n < rows.size() ? "row_cap" : null;
		while (true)
		{
			List<Map<String, Object>> kept = new ArrayList<>(rows.subList(0, n));
			block.put("emitted_count", n);
			block.put("truncated", n < rows.size());
			if (n < rows.size())
			{
				block.put("truncation_reason", reason);
			}
			else
			{
				block.remove("truncation_reason");
			}
			block.put("players", kept);
			if (n == 0 || encodedEventLength(fields, key, block) <= SURROUNDING_EVENT_BUDGET)
			{
				return block;
			}
			// One row at a time from the far end, so the cut lands on the exact largest count that fits.
			n--;
			reason = "size_budget";
		}
	}

	/** Length of the event as the server's fields blob will see it, with this block attached. */
	private int encodedEventLength(Map<String, Object> fields, String key, Map<String, Object> block)
	{
		Map<String, Object> wire = new LinkedHashMap<>(fields);
		wire.put(key, block);	// under its REAL key: the key's own length counts too
		wire.put("event_id", AccountConnectPlugin.EVENT_ID_PLACEHOLDER);
		return plugin.gson.toJson(wire).length();
	}
}
