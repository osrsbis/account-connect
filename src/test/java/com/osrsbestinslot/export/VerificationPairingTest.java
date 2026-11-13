package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The frame upload's own copy of its rows' numbers: source event, frame tick, region boxes and expected numbers only. */
public class VerificationPairingTest
{
    @Test public void keepsNumbersAndBoxesAndNeverText()
    {
        Map<String, Object> common = Fields.of("source_event_id", "ab".repeat(16), "source_event_type", "level_up",
            "source_event_tick", 1200, "source_event_revision", 7L, "snapshot_revision", 7L, "tick", 1201,
            "plugin_version", "x", "ref", "0123456789abcdef");
        List<Map<String, Object>> results = Arrays.asList(
            VerificationProbe.result("level", VerificationProbe.inputs("widget", 15335426, "x", 10, "y", 400, "width", 300,
                "height", 30, "reader", "level_up", "field", "level", "skill", "attack"), 71, null),
            VerificationProbe.result("message", VerificationProbe.inputs("widget", 1, "x", 0, "y", 0, "width", 5, "height", 5,
                "reader", "discovery_text", "activity", "unknown"), "Some Player received a drop", null),
            VerificationProbe.result("count", VerificationProbe.inputs("reader", "kill_count"), null, "source_field_not_visible"));
        String json = new Gson().toJson(VerificationFrame.pairing(common, 1201, results));
        assertEquals("{\"v\":1,\"source\":{\"id\":\"" + "ab".repeat(16) + "\",\"type\":\"level_up\",\"tick\":1200,\"revision\":7},"
            + "\"frame_tick\":1201,\"regions\":[{\"widget\":15335426,\"x\":10,\"y\":400,\"width\":300,\"height\":30,"
            + "\"reader\":\"level_up\",\"field\":\"level\",\"skill\":\"attack\",\"key\":\"level\",\"expected\":71}]}", json);
        assertFalse(json.contains("Some Player"));
        assertFalse(json.contains("plugin_version"));
    }

    @Test public void aRandomSampleHasNoSourceEvent()
    {
        List<Map<String, Object>> results = Arrays.asList(VerificationProbe.result("hitpoints_orb",
            VerificationProbe.inputs("widget", 10485765, "x", 1, "y", 2, "width", 20, "height", 12, "widget_value", 98), 99, null));
        assertEquals("{\"v\":1,\"source\":{},\"frame_tick\":5,\"regions\":[]}", new Gson().toJson(VerificationFrame.pairing(
            Fields.of("tick", 5, "snapshot_revision", null), 5, java.util.Collections.emptyList())));
        // A sample's snapshot revision stands in for the source event's, so its numbers can be checked against that snapshot.
        Map<String, Object> row = Fields.of("tick", 5, "snapshot_revision", 42L, "plugin_version", "x", "ref", "0123456789abcdef");
        assertEquals("{\"v\":1,\"source\":{\"revision\":42},\"frame_tick\":5,\"regions\":[{\"widget\":10485765,\"x\":1,\"y\":2,\"width\":20,"
            + "\"height\":12,\"widget_value\":98,\"key\":\"hitpoints_orb\",\"expected\":99}]}", new Gson().toJson(VerificationFrame.pairing(row, 5, results)));
    }
}
