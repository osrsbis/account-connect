package com.osrsbestinslot.export;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered field maps for the wire: insertion order kept, null values allowed, values never copied. */
final class Fields
{
	private Fields()
	{
	}

	static Map<String, Object> of(Object... keysAndValues)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2)
		{
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}
}
