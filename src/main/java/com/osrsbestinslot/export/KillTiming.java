package com.osrsbestinslot.export;

import com.google.common.primitives.Longs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * Own fight, raid and lap timings from the game's own completion lines, and the count lines the kill_count gate
 * does not cover (Hallowed Sepulchre floors and Grand Hallowed Coffin, Guardians of the Rift).
 *
 * Pure: one game message in, one event body out (or null). Only GAMEMESSAGE/SPAM lines reach it, and none of these
 * lines names another player. The line shapes are the ones RuneLite's own Chat Commands plugin reads for its
 * personal-best command (BSD-2-Clause, runelite/runelite); the expressions here are written independently.
 *
 * A timing line does not name the boss ("Fight duration: 1:36"). It arrives on the same game tick as the boss's
 * kill_count line, before or after it, so the server pairs the two by account and time. Nothing is paired here.
 */
final class KillTiming
{
	private KillTiming() {}

	/** m:ss, mm:ss, h:mm:ss or mmm:ss, with optional tenths/hundredths. */
	private static final String TIME = "([0-9]{1,3}(?::[0-9]{2}){1,2}(?:\\.[0-9]{1,2})?)";

	private static final Pattern RAID = Pattern.compile(
		"^Congratulations - your raid is complete!\\s*Team size:\\s*([0-9]{1,2}(?:\\+|-[0-9]{1,2})? players?|Solo)\\s+"
			+ "Duration:\\s*" + TIME + "\\s*(\\(new personal best\\))?(?:\\s*Personal best:\\s*" + TIME + ")?");
	/**
	 * One labelled time. The label is one of the game's fixed words, or "<content> challenge completion time" /
	 * "<content> total completion time" for Tombs of Amascut and the Colosseum, whose content name carries the mode.
	 */
	private static final Pattern PART = Pattern.compile(
		"((?:Fight |Lap |Challenge |Corrupted challenge )?[Dd]uration:|Subdued in|Total:"
			+ "|[A-Z][A-Za-z' ]{2,40}(?:: [A-Za-z ]{2,20})? (?:challenge |total )?completion time:)\\s*"
			+ TIME + "(\\s*\\(new personal best\\))?(?:\\.?\\s*Personal best:\\s*" + TIME + ")?");
	private static final Pattern ROOM = Pattern.compile("^Challenge complete: ([A-Z][A-Za-z' -]{1,40})\\.");

	private static final Pattern HS_FLOOR = Pattern.compile(
		"^You have completed Floor ([1-5]) of the Hallowed Sepulchre! Total completions: ([0-9,]{1,13})\\.$");
	private static final Pattern HS_COFFIN = Pattern.compile(
		"^You have opened the Grand Hallowed Coffin ([0-9,]{1,13}) times?!$");
	private static final Pattern RIFTS = Pattern.compile(
		"^Amount of Rifts you have closed: ([0-9,]{1,13})\\.$", Pattern.CASE_INSENSITIVE);

	/** Game markup to plain text: a line break becomes a space, colour and other tags are removed. */
	static String clean(String message)
	{
		if (message == null || message.length() > 512) return "";
		String s = message.replace("<br>", " ");
		s = KillActivity.CHAT_MARKUP_RE.matcher(s).replaceAll("");
		return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
	}

	/** Seconds, rounded to hundredths, or null when the text is not a game time. */
	static Double seconds(String time)
	{
		if (time == null) return null;
		String[] frac = time.split("\\.", 2);
		String[] parts = frac[0].split(":");
		double total = 0;
		for (String p : parts)
		{
			total = total * 60 + Integer.parseInt(p);
		}
		if (frac.length == 2)
		{
			total += Integer.parseInt(frac[1]) / Math.pow(10, frac[1].length());
		}
		return Math.round(total * 100) / 100.0;
	}

	/**
	 * kill_timing body for a timing line, or null. Fields: {@code timings} (list of {label, duration_s, new_pb,
	 * personal_best_s}), and when printed {@code team_size} (raids) and {@code room} (Tombs of Amascut, Colosseum).
	 * personal_best_s is the previous best when the game prints it; on a new best it equals duration_s.
	 */
	static Map<String, Object> parse(String message)
	{
		String text = clean(message);
		if (text.isEmpty()) return null;
		List<Map<String, Object>> timings = new ArrayList<>();
		Map<String, Object> out = new LinkedHashMap<>();
		Matcher raid = RAID.matcher(text);
		if (raid.find())
		{
			out.put("team_size", raid.group(1));
			timings.add(timing("raid duration", raid.group(2), raid.group(3) != null, raid.group(4)));
		}
		else
		{
			Matcher room = ROOM.matcher(text);
			if (room.find()) out.put("room", room.group(1));
			Matcher m = PART.matcher(text);
			while (m.find() && timings.size() < 4)
			{
				String label = m.group(1).replaceAll(":$", "").trim().toLowerCase(Locale.ROOT);
				timings.add(timing(label, m.group(2), m.group(3) != null, m.group(4)));
			}
		}
		if (timings.isEmpty() || timings.contains(null)) return null;
		out.put("timings", timings);
		return out;
	}

	private static Map<String, Object> timing(String label, String time, boolean newPb, String pbTime)
	{
		Double duration = seconds(time);
		if (duration == null) return null;
		return Fields.of("label", label, "duration_s", duration, "new_pb", newPb,
			"personal_best_s", newPb ? duration : seconds(pbTime));
	}

	/** {source, kind, count} for the activity count lines the kill_count gate does not match, or null. */
	static Map<String, Object> parseActivityCount(String message)
	{
		String text = clean(message);
		Matcher m;
		String source;
		String kind;
		if ((m = HS_FLOOR.matcher(text)).matches())
		{
			source = "Hallowed Sepulchre Floor " + m.group(1);
			kind = "completion";
			return count(source, kind, m.group(2));
		}
		if ((m = HS_COFFIN.matcher(text)).matches()) return count("Grand Hallowed Coffin", "count", m.group(1));
		if ((m = RIFTS.matcher(text)).matches()) return count("Guardians of the Rift", "count", m.group(1));
		return null;
	}

	private static Map<String, Object> count(String source, String kind, String digits)
	{
		Long n = Longs.tryParse(digits.replace(",", ""));
		return n == null ? null : Fields.of("source", source, "kind", kind, "count", n);
	}
}
