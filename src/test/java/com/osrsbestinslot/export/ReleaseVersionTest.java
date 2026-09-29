package com.osrsbestinslot.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The release branch must not still declare the SHIPPED version.
 *
 * WHY THIS EXISTS, and it is not hypothetical. This branch sat at `version = '0.7.13'` through two
 * commits whose messages both said the version had been bumped. The bump was written, the write was
 * in a command a commit hook rejected, and the retry committed nothing — so the claim survived in
 * prose while the file never changed. A privacy review caught it, at which point the branch would
 * have shipped to the Plugin Hub declaring the version already pinned there, making a Hub build and
 * a local build indistinguishable by version.
 *
 * No build step reads this number for correctness, which is exactly why nothing else catches it.
 * The one number a human is guaranteed to quote in a release report is the one number nothing
 * verified.
 *
 * 0.7.15 is the SHIPPED version: the hub manifest `plugins/osrs-best-in-slot` pins commit
 * dd4070c (plugin-hub PR #17256, merged), and that commit declares 0.7.15. So the rule is
 * stated as a DIFFERENCE from the shipped version rather than as an equality with a literal: this file
 * only has to be edited when the shipped baseline moves.
 */
public class ReleaseVersionTest
{
	/** The version declared by dd4070c, the commit the Plugin Hub pins today. */
	private static final String SHIPPED_VERSION = "0.7.15";

	private static String declaredVersion() throws IOException
	{
		// Walk up to the module root rather than assuming the working directory: gradle and an IDE
		// start tests from different places, and a test that cannot find its own build file would
		// otherwise pass vacuously.
		Path dir = Paths.get("").toAbsolutePath();
		while (dir != null && !Files.exists(dir.resolve("build.gradle")))
		{
			dir = dir.getParent();
		}
		assertTrue("build.gradle must be findable, or this test proves nothing", dir != null);

		List<String> lines = Files.readAllLines(dir.resolve("build.gradle"));
		Pattern p = Pattern.compile("^version\\s*=\\s*'([^']+)'\\s*$");
		String found = null;
		int hits = 0;
		for (String line : lines)
		{
			Matcher m = p.matcher(line);
			if (m.matches())
			{
				found = m.group(1);
				hits++;
			}
		}
		// EXACTLY ONE, because two assignments would mean the build uses the last and a reader the
		// first. An ambiguous build file is a refusal, not a best guess.
		assertEquals("build.gradle must declare exactly one version", 1, hits);
		return found;
	}

	@Test
	public void theReleaseBranchDoesNotDeclareTheShippedVersion() throws IOException
	{
		assertNotEquals(
			"this branch still declares the SHIPPED version, so a Hub build and this build would be "
				+ "indistinguishable by version — bump build.gradle",
			SHIPPED_VERSION, declaredVersion());
	}

	@Test
	public void theDeclaredVersionIsAPlausibleRelease() throws IOException
	{
		// A guard against "fixing" the test above by deleting the number or writing something that
		// is not a version at all.
		assertTrue("the version must look like x.y.z", declaredVersion().matches("\\d+\\.\\d+\\.\\d+"));
	}
}
