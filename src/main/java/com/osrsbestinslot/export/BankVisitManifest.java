package com.osrsbestinslot.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bank-visit evidence (0.7.18, staff only): the canonical item list, its hash, and its chunks.
 *
 * Server ingest contract:
 *   canonical list = drop id &lt;= 0 or qty &lt;= 0, sum by id, sort by id ascending, written as the compact
 *   JSON [[id,qty],...] with no spaces, UTF-8; the hash is its lowercase hex SHA-256. Chunks carry
 *   consecutive slices of that same list, each at most MAX_STACKS stacks AND at most FIELDS_BUDGET chars
 *   of encoded fields, whichever is hit first. The server drops (ACKs "filtered") any event whose fields
 *   are longer than 8192 chars, so the budget keeps 512 chars of headroom, like the surrounding snapshot.
 */
final class BankVisitManifest
{
	static final int MAX_STACKS = 150;
	static final int FIELDS_BUDGET = 7680;

	private BankVisitManifest()
	{
	}

	/** The canonical list: positive ids and quantities only, summed by id, sorted by id. */
	static List<long[]> canonical(Map<Integer, Long> counts)
	{
		TreeMap<Integer, Long> sorted = new TreeMap<>();
		if (counts != null)
		{
			for (Map.Entry<Integer, Long> e : counts.entrySet())
			{
				if (e.getKey() != null && e.getKey() > 0 && e.getValue() != null && e.getValue() > 0)
				{
					sorted.merge(e.getKey(), e.getValue(), Long::sum);
				}
			}
		}
		List<long[]> out = new ArrayList<>(sorted.size());
		for (Map.Entry<Integer, Long> e : sorted.entrySet())
		{
			out.add(new long[]{e.getKey(), e.getValue()});
		}
		return out;
	}

	/** Compact JSON [[id,qty],...]: integers only, no spaces. */
	static String json(List<long[]> list)
	{
		StringBuilder b = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++)
		{
			if (i > 0)
			{
				b.append(',');
			}
			b.append('[').append(list.get(i)[0]).append(',').append(list.get(i)[1]).append(']');
		}
		return b.append(']').toString();
	}

	static String sha256(List<long[]> list)
	{
		return EventSpool.sha256Hex(json(list));
	}

	/** Encoded length of one stack inside the list, without its separating comma. */
	static int stackLength(long[] s)
	{
		return 3 + Long.toString(s[0]).length() + Long.toString(s[1]).length();
	}

	/**
	 * Split the list into consecutive chunks. {@code emptyChunkLength} is the encoded length of a chunk's
	 * fields with an EMPTY items list (event_id and the largest chunk numbers included), so every chunk's
	 * real length is that plus the items it holds. An empty list gives no chunks.
	 */
	static List<List<long[]>> chunk(List<long[]> list, int emptyChunkLength)
	{
		List<List<long[]>> out = new ArrayList<>();
		List<long[]> cur = new ArrayList<>();
		int len = emptyChunkLength;
		for (long[] s : list)
		{
			int add = stackLength(s) + (cur.isEmpty() ? 0 : 1);
			if (!cur.isEmpty() && (cur.size() >= MAX_STACKS || len + add > FIELDS_BUDGET))
			{
				out.add(cur);
				cur = new ArrayList<>();
				len = emptyChunkLength;
				add = stackLength(s);
			}
			cur.add(s);
			len += add;
		}
		if (!cur.isEmpty())
		{
			out.add(cur);
		}
		return out;
	}
}
