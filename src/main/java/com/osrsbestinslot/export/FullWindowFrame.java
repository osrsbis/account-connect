package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The second verification capture mode: the whole game canvas as the player sees it (the drawn frame, which in GPU
 * mode is the only image that holds the 3D scene), uploaded as drawn: the complete game canvas, with no areas blacked
 * out. It is taken from the same drawn frame, on the same tick, as the
 * event's region frame, and carries the same source event, so the server can compare what each mode adds and extract
 * further regions later. No frame of either mode is taken while a trade screen or the bank PIN keypad is open
 * (`Verification.VERIFY_FRAME_BLOCKERS`).
 */
final class FullWindowFrame
{
	static final int MAX_PER_HOUR = 6;
	/** Event types whose evidence also takes a full-window frame: completions, reward screens, interruptions, unknown screens. */
	static final Set<String> TRIGGERS = new HashSet<>(Arrays.asList(
		"kill_count", "loot", "minigame", "attempt_end", "verification_discovery"));

	private FullWindowFrame()
	{
	}

	/**
	 * True when the image is a single colour (sampled on a grid). A software GPU renderer can hand back solid-black drawn
	 * frames; such a frame shows nothing and is recorded as `drawn_blank`, never uploaded as if it held the scene.
	 */
	static boolean blank(BufferedImage image)
	{
		int w = image.getWidth(), h = image.getHeight();
		if (w <= 0 || h <= 0) return true;
		int first = image.getRGB(0, 0) & 0xFFFFFF;
		int stepX = Math.max(1, w / 64), stepY = Math.max(1, h / 64);
		for (int y = 0; y < h; y += stepY)
			for (int x = 0; x < w; x += stepX)
				if ((image.getRGB(x, y) & 0xFFFFFF) != first) return false;
		return true;
	}
}
