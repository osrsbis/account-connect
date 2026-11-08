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

    @Test public void blankObjectInTheWidgetIsAnEmptySlot()
    {
        Map<Integer, int[]> container = new HashMap<>();
        container.put(0, new int[]{995, 100});
        Map<Integer, int[]> widget = new HashMap<>();
        widget.put(0, new int[]{995, 100});
        widget.put(5, new int[]{VerificationProbe.BLANK_OBJECT, 1});
        assertTrue(VerificationProbe.slots(container, widget, 28).stream().allMatch(r -> Boolean.TRUE.equals(r.get("match"))));
        container.put(7, new int[]{440, 1});
        widget.put(7, new int[]{VerificationProbe.BLANK_OBJECT, 1});
        List<Map<String, Object>> rows = VerificationProbe.slots(container, widget, 28);
        Map<String, Object> slot7 = rows.stream().filter(r -> "slot_7".equals(r.get("key"))).findFirst().get();
        assertEquals(false, slot7.get("match"));
        assertEquals("empty", slot7.get("observed"));
    }

    @Test public void anOrbReadingZeroIsUndrawnOnlyRightAfterLogin()
    {
        // Live rig 11-07, tick 10 after login: HP, prayer and run read 0 while the spec orb already read 100.
        assertTrue(VerificationProbe.orbUndrawn(10, 76, 0));
        assertTrue(VerificationProbe.orbUndrawn(10, 50, 0));
        assertFalse(VerificationProbe.orbUndrawn(10, 100, 100));
        // A non-zero wrong reading in the window stays a lead (prayer 50 shown 40).
        assertFalse(VerificationProbe.orbUndrawn(10, 50, 40));
        // Established session: a zero orb is a mismatch lead, never unknown (HP 76 shown 0).
        assertFalse(VerificationProbe.orbUndrawn(500, 76, 0));
        assertFalse(VerificationProbe.orbUndrawn(VerificationProbe.ORB_DRAW_TICKS + 1, 76, 0));
        // A real zero, a hidden orb, or a tick count from before the login: no exemption.
        assertFalse(VerificationProbe.orbUndrawn(10, 0, 0));
        assertFalse(VerificationProbe.orbUndrawn(10, 76, null));
        assertFalse(VerificationProbe.orbUndrawn(-1, 76, 0));
    }

    @Test public void nativeRegionsCopyOnlyTheSelectedPixelsAtClientResolution()
    {
        int w = 10, h = 8;
        int[] buffer = new int[w * h];
        for (int i = 0; i < buffer.length; i++) buffer[i] = 0xFF000000 | (i * 1013);
        VerificationFrame.Region r = new VerificationFrame.Region("k", 1, new Rectangle(2, 3, 4, 2), 7);
        BufferedImage out = VerificationFrame.nativeRegions(buffer, w, h, Collections.singletonList(r), w, h);
        assertNotNull(out);
        assertEquals(w, out.getWidth()); assertEquals(h, out.getHeight());
        assertEquals(buffer[3 * w + 2] & 0xFFFFFF, out.getRGB(2, 3) & 0xFFFFFF);
        assertEquals(buffer[4 * w + 5] & 0xFFFFFF, out.getRGB(5, 4) & 0xFFFFFF);
        assertEquals(0, out.getRGB(1, 3) & 0xFFFFFF);
        assertEquals(0, out.getRGB(2, 5) & 0xFFFFFF);
        // A buffer that does not match the canvas the bounds refer to is not used.
        assertNull(VerificationFrame.nativeRegions(buffer, w, h, Collections.singletonList(r), 20, 16));
        assertNull(VerificationFrame.nativeRegions(null, w, h, Collections.singletonList(r), w, h));
        assertNull(VerificationFrame.nativeRegions(new int[5], w, h, Collections.singletonList(r), w, h));
    }
}
