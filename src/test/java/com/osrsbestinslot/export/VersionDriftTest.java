package com.osrsbestinslot.export;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Drift gate for the PLUGIN_VERSION constant. The plugin stamps PLUGIN_VERSION into every snapshot's
 * source.plugin_version, which is how osrsbestinslot.com tells which build an account is running. That
 * constant and build.gradle's project version are two hand-edited places, so they can silently diverge
 * (they did: the constant was "0.5.0" while the build shipped 0.6.0). This test parses build.gradle's
 * version and asserts the constant equals it, turning any future drift into a build failure — you can
 * no longer bump one without the other. It fails loud (not silently) if build.gradle can't be found or
 * the version line can't be uniquely located.
 */
public class VersionDriftTest
{
	@Test
	public void pluginVersionConstantMatchesBuildGradle() throws Exception
	{
		String buildVersion = parseBuildGradleVersion();
		String pluginVersion = readPluginVersionConstant();
		assertEquals(
			"PLUGIN_VERSION must equal build.gradle's version — bump both together",
			buildVersion, pluginVersion);
	}

	/** Read the private static final PLUGIN_VERSION reflectively (no need to widen production visibility). */
	private static String readPluginVersionConstant() throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField("PLUGIN_VERSION");
		f.setAccessible(true);
		Object value = f.get(null);
		assertNotNull("PLUGIN_VERSION must be set", value);
		return (String) value;
	}

	/**
	 * Extract the single top-level {@code version = '...'} assignment from build.gradle. Case-sensitive
	 * and line-anchored on purpose: it must not match {@code def runeLiteVersion = 'latest.release'}
	 * (capital V) nor a dependency map's {@code version: runeLiteVersion} (colon, unquoted). Fails if the
	 * file is missing or the assignment is absent / ambiguous, so the gate can never pass vacuously.
	 */
	private static String parseBuildGradleVersion() throws Exception
	{
		File gradle = locateBuildGradle();
		String text = new String(Files.readAllBytes(gradle.toPath()), StandardCharsets.UTF_8);
		Pattern p = Pattern.compile("(?m)^\\s*version\\s*=\\s*['\"]([^'\"]+)['\"]\\s*$");
		Matcher m = p.matcher(text);
		String found = null;
		int count = 0;
		while (m.find())
		{
			found = m.group(1);
			count++;
		}
		assertEquals("expected exactly one top-level version assignment in build.gradle", 1, count);
		return found;
	}

	/** build.gradle lives at the module root = the gradle test working directory; probe the usual spots. */
	private static File locateBuildGradle()
	{
		File[] candidates = {
			new File("build.gradle"),
			new File(System.getProperty("user.dir"), "build.gradle"),
		};
		for (File f : candidates)
		{
			if (f.isFile())
			{
				return f;
			}
		}
		fail("could not locate build.gradle (cwd=" + System.getProperty("user.dir") + ")");
		return null; // unreachable — fail() throws
	}
}
