package com.osrsbestinslot.export;

import java.util.*;
import java.util.regex.Pattern;
import net.runelite.api.Experience;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/**
 * Verification rows: the plugin checks its own readings against a second source on the same tick, so production
 * data can be compared with the code for accuracy. Off unless the server answers this exact token with
 * X-Verification: on (a separately controlled collection grant for this token).
 *
 * One SAMPLE is taken on an occasion (a kill count, a counter change, a minigame result, a level, a login and the
 * like) or at a random moment, never more often than MIN_GAP_MS and at most MAX_SAMPLES_PER_HOUR. A sample is a set
 * of rows, one per area and check. Each row carries the tick it was read on, the revision of the last snapshot the
 * plugin built, and per result its inputs, the expected value, the observed value and whether they match.
 *
 * WHAT A MATCH PROVES. Two of the plugin's own paths agreeing (a container against the widget showing it, the
 * client's var against the server's) proves agreement only, not meaning. Meaning is checked by a person against the
 * screen: a sample may carry numeric screenshot regions from the next drawn frame on the same tick, with their
 * own frame time, stored under the same 16-hex frame ref as the rows. All other pixels are black before encoding.
 * A "match": null result means one side was not readable, never a pass.
 */
final class VerificationProbe
{
	static final long MIN_GAP_MS = 20_000L;
	static final int MAX_SAMPLES_PER_HOUR = 60;
	static final int MAX_FRAMES_PER_HOUR = 30;
	/** On a tick with no occasion, a random sample with probability 1 in RANDOM_ONE_IN (about once in 5 minutes). */
	static final int RANDOM_ONE_IN = 500;
	/** Results per row: a large check (128 vars) is split into parts, so no row nears the event size cap. */
	static final int RESULTS_PER_ROW = 32;
	static final long HOUR_MS = 3_600_000L;

	/** Event types whose arrival is an occasion for a sample. Verification rows are never one. */
	static final Set<String> OCCASIONS = new HashSet<>(Arrays.asList(
		"kill_count", "activity_counter", "minigame", "level_up", "slayer_task", "progress", "loot", "death",
		"login", "pet", "attempt_end", "kill_context", "verification_ready"));

	private final IntUnaryOperator random;
	private final ArrayDeque<Long> samples = new ArrayDeque<>();
	private final ArrayDeque<Long> frames = new ArrayDeque<>();
	private long lastSampleMs = Long.MIN_VALUE / 2;
	private String pendingTrigger;
	private String pendingKey;
	private Integer pendingValue;

	VerificationProbe(IntUnaryOperator random)
	{
		this.random = random;
	}

	VerificationProbe()
	{
		this(n -> ThreadLocalRandom.current().nextInt(n));
	}

	/** Forget the queued occasion. The hourly budgets are kept: a relog does not refill them. */
	synchronized void reset()
	{
		pendingTrigger = null;
		pendingKey = null;
		pendingValue = null;
	}

	/** An event of `type` was sent; the first occasion since the last sample is kept, with its key and value. */
	synchronized void occasion(String type, Map<String, Object> fields)
	{
		if (type == null || !OCCASIONS.contains(type) || pendingTrigger != null) return;
		pendingTrigger = type;
		Object key = fields == null ? null : fields.get("activity_counter".equals(type) ? "key" : "source");
		pendingKey = key instanceof String ? safeKey((String) key) : null;
		Object value = fields == null ? null : fields.get("activity_counter".equals(type) ? "to" : "count");
		pendingValue = value instanceof Number ? ((Number) value).intValue() : null;
	}

	/** The sample due now, or null. An occasion waits (up to the gap) rather than being lost to the budget. */
	synchronized Sample due(long nowMs)
	{
		while (!samples.isEmpty() && nowMs - samples.peekFirst() >= HOUR_MS) samples.pollFirst();
		boolean occasion = pendingTrigger != null;
		if (!occasion && random.applyAsInt(RANDOM_ONE_IN) != 0) return null;
		if (nowMs - lastSampleMs < MIN_GAP_MS || samples.size() >= MAX_SAMPLES_PER_HOUR) return null;
		Sample s = occasion ? new Sample(pendingTrigger, pendingKey, pendingValue) : new Sample("random", null, null);
		reset();
		lastSampleMs = nowMs;
		samples.addLast(nowMs);
		return s;
	}

	/** Transient activity evidence bypasses the random-sample spacing, never the shared hourly budget. */
	synchronized boolean takeEvent(long nowMs)
	{
		while (!samples.isEmpty() && nowMs - samples.peekFirst() >= HOUR_MS) samples.pollFirst();
		if (samples.size() >= MAX_SAMPLES_PER_HOUR) return false;
		samples.addLast(nowMs);
		lastSampleMs = nowMs;
		return true;
	}

	synchronized boolean eventRoom(long nowMs)
	{
		while (!samples.isEmpty() && nowMs - samples.peekFirst() >= HOUR_MS) samples.pollFirst();
		while (!frames.isEmpty() && nowMs - frames.peekFirst() >= HOUR_MS) frames.pollFirst();
		return samples.size() < MAX_SAMPLES_PER_HOUR && frames.size() < MAX_FRAMES_PER_HOUR;
	}

	/** True when this sample may also take a frame; counts it. */
	synchronized boolean takeFrame(long nowMs)
	{
		while (!frames.isEmpty() && nowMs - frames.peekFirst() >= HOUR_MS) frames.pollFirst();
		if (frames.size() >= MAX_FRAMES_PER_HOUR) return false;
		frames.addLast(nowMs);
		return true;
	}

	static final class Sample
	{
		final String trigger;
		final String key;
		final Integer value;

		Sample(String trigger, String key, Integer value)
		{
			this.trigger = trigger;
			this.key = key;
			this.value = value;
		}
	}

	// ---- results ----

	private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9_]{1,48}");
	private static final Pattern SAFE_STRING = Pattern.compile("[A-Za-z0-9_:.\\-]{0,48}");

	/** Lower-case letters, digits and underscores only, at most 48: a boss or key name becomes a safe key. */
	static String safeKey(String s)
	{
		if (s == null) return null;
		String k = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (k.length() > 48) k = k.substring(0, 48);
		return k.isEmpty() ? null : k;
	}

	/** One result. match is null when either side is unknown; numbers compare by value. */
	static Map<String, Object> result(String key, Map<String, Object> inputs, Object expected, Object observed)
	{
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("key", key);
		if (inputs != null && !inputs.isEmpty()) r.put("inputs", inputs);
		r.put("expected", expected);
		r.put("observed", observed);
		r.put("match", expected == null || observed == null ? null : same(expected, observed));
		return r;
	}

	/** An observation with no second source: recorded for the screen check, never a match. */
	static Map<String, Object> observed(String key, Object observed)
	{
		Map<String, Object> r = Fields.of("key", key, "observed", observed, "match", null);
		return r;
	}

	private static boolean same(Object a, Object b)
	{
		if (a instanceof Number && b instanceof Number) return ((Number) a).longValue() == ((Number) b).longValue();
		return a.equals(b);
	}

	static Map<String, Object> inputs(Object... kv)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	/** "id x qty", or "empty". The one string shape item results use. */
	static String item(int id, int qty)
	{
		return id <= 0 || qty <= 0 ? "empty" : id + "x" + qty;
	}

	/**
	 * The rows for one check: results split into parts of RESULTS_PER_ROW, each with the sample's common fields,
	 * the part number, the check's total and how many results in the whole check mismatched or were unknown.
	 * An empty check gives one row with no results, so "nothing to compare" is visible.
	 */
	static List<Map<String, Object>> rows(String area, String check, List<Map<String, Object>> results, Map<String, Object> common)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		int mismatches = 0;
		int unknown = 0;
		for (Map<String, Object> r : results)
		{
			Object m = r.get("match");
			if (Boolean.FALSE.equals(m)) mismatches++;
			else if (m == null) unknown++;
		}
		int parts = Math.max(1, (results.size() + RESULTS_PER_ROW - 1) / RESULTS_PER_ROW);
		for (int p = 0; p < parts; p++)
		{
			Map<String, Object> row = new LinkedHashMap<>(common);
			row.put("area", area);
			row.put("check", check);
			row.put("part", p + 1);
			row.put("parts", parts);
			row.put("total", results.size());
			row.put("mismatches", mismatches);
			row.put("unknown", unknown);
			row.put("results", new ArrayList<>(results.subList(p * RESULTS_PER_ROW, Math.min(results.size(), (p + 1) * RESULTS_PER_ROW))));
			out.add(row);
		}
		return out;
	}

	/** The wire projection of `results`: known shapes only; anything else is dropped. */
	static List<Object> project(Object input)
	{
		List<Object> out = new ArrayList<>();
		if (!(input instanceof List)) return out;
		for (Object o : (List<?>) input)
		{
			if (!(o instanceof Map) || out.size() >= RESULTS_PER_ROW) continue;
			Map<?, ?> r = (Map<?, ?>) o;
			if (!(r.get("key") instanceof String) || !SAFE_KEY.matcher((String) r.get("key")).matches()) continue;
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("key", r.get("key"));
			if (r.get("inputs") instanceof Map)
			{
				Map<String, Object> in = new LinkedHashMap<>();
				for (Map.Entry<?, ?> e : ((Map<?, ?>) r.get("inputs")).entrySet())
					if (e.getKey() instanceof String && SAFE_KEY.matcher((String) e.getKey()).matches() && scalar(e.getValue()))
						in.put((String) e.getKey(), e.getValue());
				if (!in.isEmpty()) p.put("inputs", in);
			}
			if (r.containsKey("expected") && (scalar(r.get("expected")) || textOperand(r, r.get("expected")))) p.put("expected", r.get("expected"));
			if (scalar(r.get("observed"))) p.put("observed", r.get("observed"));
			// Never retain a claimed pass after projection removed one of its operands.
			p.put("match", p.get("expected") == null || p.get("observed") == null ? null
				: same(p.get("expected"), p.get("observed")));
			out.add(p);
		}
		return out;
	}

	private static boolean textOperand(Map<?, ?> row, Object value)
	{
		Object inputs = row.get("inputs");
		return inputs instanceof Map && ("text".equals(((Map<?, ?>) inputs).get("reader"))
			|| "discovery_text".equals(((Map<?, ?>) inputs).get("reader")))
			&& value instanceof String && value.equals(EventVerification.clean((String) value));
	}

	private static boolean scalar(Object v)
	{
		return v == null || v instanceof Number || v instanceof Boolean
			|| v instanceof String && SAFE_STRING.matcher((String) v).matches();
	}

	// ---- pure checks (the plugin reads the client; these compute the comparison) ----

	/** Each skill's real level against the level its xp gives (capped at 99, as the client shows real levels). */
	static List<Map<String, Object>> skillLevels(Map<String, int[]> xpAndLevel)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<String, int[]> e : xpAndLevel.entrySet())
		{
			int xp = e.getValue()[0];
			int expected = Math.min(Experience.MAX_REAL_LEVEL, Experience.getLevelForXp(Math.max(0, xp)));
			out.add(result(safeKey(e.getKey()), inputs("xp", xp), expected, e.getValue()[1]));
		}
		return out;
	}

	/** Total level from the real levels, and combat level from the seven combat levels, against the client's own. */
	static List<Map<String, Object>> derivedLevels(Map<String, Integer> real, int clientTotal, int clientCombat)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		int sum = 0;
		for (int v : real.values()) sum += v;
		out.add(result("total_level", inputs("skills", real.size()), sum, clientTotal));
		Integer a = real.get("attack"), s = real.get("strength"), d = real.get("defence"), h = real.get("hitpoints"),
			m = real.get("magic"), r = real.get("ranged"), p = real.get("prayer");
		Integer combat = a == null || s == null || d == null || h == null || m == null || r == null || p == null ? null
			: Experience.getCombatLevel(a, s, d, h, m, r, p);
		out.add(result("combat_level", inputs("attack", a, "strength", s, "defence", d, "hitpoints", h, "magic", m,
			"ranged", r, "prayer", p), combat, clientCombat));
		return out;
	}

	/** Server-set var against the client's own value, per key: {key, server, client}. */
	static List<Map<String, Object>> serverVsClient(List<Object[]> reads)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object[] r : reads) out.add(result((String) r[0], inputs("var", r[1], "id", r[2]), r[3], r[4]));
		return out;
	}

	/** One container against the widget that shows it, slot by slot: {slot -> [id, qty]}. Empty on both sides is skipped. */
	static List<Map<String, Object>> slots(Map<Integer, int[]> container, Map<Integer, int[]> widget, int slotCount)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < slotCount; i++)
		{
			int[] c = container.get(i);
			int[] w = widget == null ? null : widget.get(i);
			String expected = c == null ? "empty" : item(c[0], c[1]);
			String observed = widget == null ? null : w == null ? "empty" : item(w[0], w[1]);
			if ("empty".equals(expected) && (observed == null || "empty".equals(observed))) continue;
			out.add(result("slot_" + i, inputs("slot", i), expected, observed));
		}
		return out;
	}

	/** A number shown on screen against the value the plugin holds. A widget not on screen is unknown. */
	static Map<String, Object> shown(String key, Map<String, Object> inputs, Integer held, Integer onScreen)
	{
		return result(key, inputs, held, onScreen);
	}
}
