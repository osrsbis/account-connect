package com.osrsbestinslot.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The public wire contract (PublicTransport ENVELOPE, FIELDS and STAFF_EVENT_TYPES) equals the committed copy in
 * event-contract.json. A new event type or field is sent only after the server accepts it and its event registry
 * entry exists; this test makes such a change visible in the same commit as the copy it updates.
 */
public class EventContractTest
{
	private static final String RESOURCE = "/com/osrsbestinslot/export/event-contract.json";
	private static final String HINT = "A new event type or field needs a server event registry entry first; "
		+ "then update src/test/resources" + RESOURCE + " in the same change.";

	@Test
	public void publicTransportMatchesTheCommittedContract() throws Exception
	{
		JsonObject doc = read();
		List<String> differences = new ArrayList<>();
		compare("envelope", strings(doc.getAsJsonArray("envelope")), keys(field("ENVELOPE")), differences);
		compare("staff-only types", strings(doc.getAsJsonArray("staff_event_types")), keys(field("STAFF_EVENT_TYPES")), differences);

		Map<String, Set<String>> expected = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("types").entrySet())
		{
			expected.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
		}
		Map<String, Set<String>> actual = new TreeMap<>();
		for (Map.Entry<?, ?> e : ((Map<?, ?>) field("FIELDS")).entrySet())
		{
			actual.put((String) e.getKey(), keys(e.getValue()));
		}
		compare("event types", new TreeSet<>(expected.keySet()), new TreeSet<>(actual.keySet()), differences);
		for (String type : actual.keySet())
		{
			if (expected.containsKey(type))
			{
				compare("fields of " + type, expected.get(type), actual.get(type), differences);
			}
		}
		if (!differences.isEmpty())
		{
			fail("PublicTransport differs from " + RESOURCE + ":\n  " + String.join("\n  ", differences) + "\n" + HINT);
		}
	}

	@Test
	public void theCommittedContractIsNotEmpty() throws Exception
	{
		JsonObject doc = read();
		assertTrue("no event types in " + RESOURCE, doc.getAsJsonObject("types").size() > 0);
		assertTrue("no envelope keys in " + RESOURCE, doc.getAsJsonArray("envelope").size() > 0);
	}

	private static JsonObject read() throws Exception
	{
		try (InputStream in = EventContractTest.class.getResourceAsStream(RESOURCE))
		{
			assertNotNull("missing test resource " + RESOURCE, in);
			return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static Object field(String name) throws Exception
	{
		Field f = PublicTransport.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(null);
	}

	/** The key set as the source holds it. allow(type, "") stores one empty key, which matches no real key. */
	private static Set<String> keys(Object set)
	{
		Set<String> out = new TreeSet<>();
		for (Object o : (Set<?>) set)
		{
			if (!"".equals(o))
			{
				out.add((String) o);
			}
		}
		return out;
	}

	private static Set<String> strings(JsonArray array)
	{
		Set<String> out = new TreeSet<>();
		for (JsonElement e : array)
		{
			out.add(e.getAsString());
		}
		return out;
	}

	private static void compare(String what, Set<String> expected, Set<String> actual, List<String> differences)
	{
		Set<String> added = new TreeSet<>(actual);
		added.removeAll(expected);
		Set<String> removed = new TreeSet<>(expected);
		removed.removeAll(actual);
		if (!added.isEmpty())
		{
			differences.add(what + ": not in the committed copy " + added);
		}
		if (!removed.isEmpty())
		{
			differences.add(what + ": in the committed copy but no longer sent " + removed);
		}
	}
}
