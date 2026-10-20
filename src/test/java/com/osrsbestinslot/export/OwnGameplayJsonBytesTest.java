package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The Hub forbids fresh Gson in plugin code; the size count must still equal Gson's real output. */
public class OwnGameplayJsonBytesTest
{
    private static final Gson GSON = new Gson();
    private static int gson(Object v) { return GSON.toJson(v).getBytes(StandardCharsets.UTF_8).length; }

    @Test public void matchesGsonForRowShapesAndHardStrings()
    {
        List<Object> cases = new ArrayList<>();
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("sequence", 7); row.put("event_id", "12345678-1234-1234-1234-123456789abc:7");
        row.put("kind", "effects.consumption"); row.put("source", "own_client_sequence"); row.put("tick", 1639);
        row.put("observed_at_ms", 1792445289720L); row.put("direct", false);
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("item_id_before", 1985); value.put("quantity_before", 1); value.put("item_id_after", -1);
        value.put("action", "eat"); value.put("missing", null); value.put("ratio", 0.5); value.put("f", 1.25f);
        value.put("list", Arrays.asList(1, 2L, "x", null, true, Collections.emptyMap(), Collections.emptyList()));
        row.put("value", value);
        cases.add(row);
        cases.add("quote\" back\\ nl\n tab\t cr\r bs\b ff\f ctl\u0001 html<>&=' é ✓ 😀   ");
        cases.add(Collections.singletonMap("k<y>", "v&w"));
        cases.add(Arrays.asList(Long.MAX_VALUE, Integer.MIN_VALUE, 0, -1.5, 1e20, 3.0));
        cases.add(new LinkedHashMap<>());
        cases.add(new ArrayList<>());
        cases.add(Boolean.TRUE);
        for (Object c : cases) assertEquals(String.valueOf(c), gson(c), OwnGameplayCapture.jsonBytes(c));
    }

    @Test public void pluginSourceCreatesNoFreshGson() throws Exception
    {
        java.nio.file.Path main = java.nio.file.Paths.get("src/main/java");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(main))
        {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try
                {
                    String src = new String(java.nio.file.Files.readAllBytes(p), StandardCharsets.UTF_8);
                    assertFalse(p + " creates a fresh Gson", src.matches("(?s).*new\\s+(com\\.google\\.gson\\.)?Gson\\s*\\(.*"));
                    assertFalse(p + " creates a fresh GsonBuilder", src.matches("(?s).*new\\s+(com\\.google\\.gson\\.)?GsonBuilder\\s*\\(.*"));
                }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }
}
