package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class VerificationCaptureModesTest
{
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    @Test public void uploadAnswerSeparatesStoredDroppedRefusedAndUnconfirmed()
    {
        assertArrayEquals(new String[]{"stored", "id"}, Verification.uploadResult(GSON, 200, "{\"ok\":true,\"id\":7}"));
        assertArrayEquals(new String[]{"stored", "pending"}, Verification.uploadResult(GSON, 200, "{\"ok\":true,\"id\":null,\"pending\":true}"));
        // Deliberate drops are 200s that stored nothing; they are never retried and never count as stored.
        assertArrayEquals(new String[]{"dropped", "uploads_paused"}, Verification.uploadResult(GSON, 200, "{\"ok\":true,\"dropped\":\"uploads_paused\"}"));
        assertArrayEquals(new String[]{"dropped", "not_granted"}, Verification.uploadResult(GSON, 200, "{\"ok\":true,\"dropped\":\"not_granted\"}"));
        assertArrayEquals(new String[]{"dropped", "screenshots_disabled"}, Verification.uploadResult(GSON, 200, "{\"ok\":true,\"dropped\":\"screenshots_disabled\"}"));
        // Refused by the application before storage, by its own named text: safe to retry.
        assertArrayEquals(new String[]{"retry", "http_429"}, Verification.uploadResult(GSON, 429, "{\"ok\":false,\"error\":\"Too many uploads for this link.\"}"));
        assertArrayEquals(new String[]{"retry", "http_503"}, Verification.uploadResult(GSON, 503, "{\"ok\":false,\"error\":\"Capture identity unavailable — retry.\"}"));
        // A bare 429/503 can come from a proxy or edge after storage: unconfirmed, not retried.
        assertArrayEquals(new String[]{"unconfirmed", "http_429"}, Verification.uploadResult(GSON, 429, "{}"));
        assertArrayEquals(new String[]{"unconfirmed", "http_503"}, Verification.uploadResult(GSON, 503, "<html>busy</html>"));
        assertArrayEquals(new String[]{"unconfirmed", "http_503"}, Verification.uploadResult(GSON, 503, "{\"ok\":false,\"error\":\"Storage failed.\"}"));
        // A 500 can follow a stored object (each POST makes a new one): unconfirmed, not retried.
        assertArrayEquals(new String[]{"unconfirmed", "http_500"}, Verification.uploadResult(GSON, 500, "{}"));
        assertArrayEquals(new String[]{"refused", "http_413"}, Verification.uploadResult(GSON, 413, "{}"));
        assertArrayEquals(new String[]{"unconfirmed", "unrecognised_answer"}, Verification.uploadResult(GSON, 200, "{\"ok\":true}"));
        assertArrayEquals(new String[]{"unconfirmed", "unrecognised_answer"}, Verification.uploadResult(GSON, 200, "not json"));
        assertArrayEquals(new String[]{"unconfirmed", "unrecognised_answer"}, Verification.uploadResult(GSON, 200, null));
    }

    @Test public void aSolidBlackDrawnFrameIsBlankAndASceneIsNot()
    {
        // A software GPU renderer handed back solid-black drawn frames on the Linux rig (measured 2026-09-02).
        BufferedImage black = new BufferedImage(765, 503, BufferedImage.TYPE_INT_RGB);
        assertTrue(FullWindowFrame.blank(black));
        BufferedImage scene = new BufferedImage(765, 503, BufferedImage.TYPE_INT_RGB);
        scene.setRGB(384, 240, 0x3A5F0B);
        for (int x = 0; x < 765; x += 11) scene.setRGB(x, 0, 0x101010 * (x % 7 + 1));
        assertFalse(FullWindowFrame.blank(scene));
    }

    @Test public void fullWindowTriggersAreCompletionsRewardsInterruptionsAndUnknownScreens()
    {
        for (String t : Arrays.asList("kill_count", "loot", "minigame", "attempt_end", "verification_discovery"))
            assertTrue(t, FullWindowFrame.TRIGGERS.contains(t));
        assertFalse(FullWindowFrame.TRIGGERS.contains("level_up"));
    }
}
