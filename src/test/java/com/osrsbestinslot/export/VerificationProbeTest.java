package com.osrsbestinslot.export;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class VerificationProbeTest
{
    @Test public void unknownIsNotPassAndProjectionSurvivesPublicAndStaffTransport()
    {
        Map<String, Object> result = VerificationProbe.result("hitpoints_orb",
            VerificationProbe.inputs("widget", 123, "x", 4), 42, null);
        result.put("private_text", "not retained");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "verification");
        event.put("results", Arrays.asList(result));
        event.put("snapshot_revision", 123456789L);
        event.put("private_text", "not retained");
        for (boolean staff : new boolean[]{false, true})
        {
            Map<String, Object> wire = PublicTransport.event(event, staff);
            assertNotNull(wire);
            assertEquals(123456789L, wire.get("snapshot_revision"));
            assertFalse(wire.containsKey("private_text"));
            Map<?, ?> row = (Map<?, ?>) ((List<?>) wire.get("results")).get(0);
            assertNull(row.get("match"));
            assertEquals(42, row.get("expected"));
            assertFalse(row.containsKey("private_text"));
        }
        assertEquals(false, VerificationProbe.result("level", null, 42, 41).get("match"));
        assertEquals(true, VerificationProbe.result("level", null, 42, 42L).get("match"));
    }

    @Test public void occasionsAndRandomSamplesRespectBudgetsAcrossReset()
    {
        VerificationProbe probe = new VerificationProbe(n -> 1);
        assertNull(probe.due(0));
        probe.occasion("level_up", null);
        assertEquals("level_up", probe.due(0).trigger);
        probe.occasion("login", null);
        assertNull(probe.due(19999));
        assertEquals("login", probe.due(20000).trigger);
        for (int i = 2; i < 60; i++)
        {
            probe.occasion("login", null);
            assertNotNull(probe.due(i * 20000L));
        }
        probe.reset();
        probe.occasion("login", null);
        assertNull(probe.due(1200000));
        assertNotNull(probe.due(3600000));
        for (int i = 0; i < 30; i++) assertTrue(probe.takeFrame(i));
        probe.reset();
        assertFalse(probe.takeFrame(31));
        assertTrue(probe.takeFrame(3600000));
        assertEquals("random", new VerificationProbe(n -> 0).due(0).trigger);
    }

    @Test public void screenPixelsOutsideSelectedNumbersAreBlackAndSourceIsUnchanged()
    {
        BufferedImage source = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 20; x++) for (int y = 0; y < 20; y++) source.setRGB(x, y, 0xffffff);
        List<VerificationFrame.Region> regions = Arrays.asList(
            new VerificationFrame.Region("prayer_orb", 123, new Rectangle(2, 3, 3, 2), 42));
        BufferedImage frame = VerificationFrame.retainRegions(source, regions, 10, 10);
        for (int x = 0; x < 20; x++) for (int y = 0; y < 20; y++)
        {
            boolean inside = x >= 4 && x < 10 && y >= 6 && y < 10;
            assertEquals(inside ? 0xffffff : 0, frame.getRGB(x, y) & 0xffffff);
            assertEquals(0xffffff, source.getRGB(x, y) & 0xffffff);
        }
        Map<String, Object> result = VerificationFrame.results(regions, 20, 20, 10, 10).get(0);
        assertEquals(42, result.get("expected"));
        assertNull(result.get("match"));
        assertEquals(4, ((Map<?, ?>) result.get("inputs")).get("x"));
    }
}
