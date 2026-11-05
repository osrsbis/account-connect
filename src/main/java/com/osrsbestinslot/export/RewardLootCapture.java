package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewards that reach the inventory without an NPC drop or a reward interface: the Tempoross reward pool, the
 * Wintertodt reward cart, Guardians of the Rift and Herbiboar. The plugin's NPC and chest paths never see these, so
 * before this class a Tempoross or Herbiboar kill count had no loot row at all.
 *
 * The trigger is the game's own line, checked against the region it belongs to, and the reward is the inventory
 * gain on the next inventory change. This is the same rule RuneLite's own Loot Tracker uses (BSD-2-Clause,
 * runelite/runelite); the source names are Loot Tracker's, so the two can be compared.
 *
 * Limits, all of which emit nothing rather than guess:
 * - herbs that go into a herb sack, or a reward dropped on the ground because the inventory is full;
 * - an inventory change more than ARM_TICKS after the line (the reward is no longer distinguishable from play);
 * - a change that only removes items.
 * Items only gained count; something used up in the same change is not subtracted from the reward.
 */
final class RewardLootCapture
{
	static final int TEMPOROSS_REGION = 12588;
	static final int WINTERTODT_REGION = 6461;
	static final int GUARDIANS_OF_THE_RIFT_REGION = 14484;
	static final String LOOT_LINE = "You found some loot: ";
	static final String HERBIBOAR_LINE = "You harvest herbs from the herbiboar, whereupon it escapes.";
	/** 10 game ticks = 6 s. A reward lands within a tick or two of its line. */
	static final int ARM_TICKS = 10;

	private String armedSource;
	private int armedTick = -1;
	private Map<Integer, Long> before;

	/** The loot source a plain game line names in this region, or null. */
	static String source(String text, int regionId)
	{
		if (text == null) return null;
		if (text.equals(HERBIBOAR_LINE)) return "Herbiboar";
		if (!text.startsWith(LOOT_LINE)) return null;
		switch (regionId)
		{
			case TEMPOROSS_REGION:
				return "Reward pool (Tempoross)";
			case WINTERTODT_REGION:
				return "Reward cart (Wintertodt)";
			case GUARDIANS_OF_THE_RIFT_REGION:
				return "Guardians of the Rift";
			default:
				return null;
		}
	}

	/** Remember the inventory as it is when the reward line arrives. A second line replaces the first. */
	void arm(String source, int tick, Map<Integer, Long> inventory)
	{
		if (source == null || inventory == null) return;
		armedSource = source;
		armedTick = tick;
		before = new HashMap<>(inventory);
	}

	boolean armed()
	{
		return armedSource != null;
	}

	void reset()
	{
		armedSource = null;
		armedTick = -1;
		before = null;
	}

	/**
	 * On an inventory change: the reward as {source, items:[[id, qty]...]} when this change answers an armed line,
	 * else null. The capture disarms after one answered change, or when the line is older than ARM_TICKS.
	 */
	Map<String, Object> inventoryChanged(int tick, Map<Integer, Long> after)
	{
		if (armedSource == null) return null;
		if (tick - armedTick > ARM_TICKS || tick < armedTick || after == null)
		{
			reset();
			return null;
		}
		List<long[]> gained = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : after.entrySet())
		{
			int id = e.getKey();
			long now = e.getValue() == null ? 0 : e.getValue();
			long was = before.getOrDefault(id, 0L);
			if (id > 0 && now > was) gained.add(new long[]{id, now - was});
		}
		if (gained.isEmpty()) return null; // a change that only removed items: keep waiting for the reward
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", armedSource);
		out.put("items", gained);
		reset();
		return out;
	}
}
