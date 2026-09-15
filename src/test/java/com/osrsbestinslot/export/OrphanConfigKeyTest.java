package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The orphaned {@code uploadTradeScreenshots} key.
 *
 * Its config item was deleted when delivery-proof screenshots became part of core sync, but RuneLite
 * keeps a stored value whose item is gone, so the key is still in every upgraded profile. It was read
 * out of a live profile on 2026-09-15. Nothing reads it, so the risk is not behaviour: it is that a
 * profile file advertises a toggle the code does not have.
 */
public class OrphanConfigKeyTest
{
	@Test
	public void startUpUnsetsTheOrphanedKey() throws Exception
	{
		AccountConnectPlugin plugin = UploadSwitchTest.plugin(false, "");
		ConfigManager cm = mock(ConfigManager.class);
		inject(plugin, "configManager", cm);

		Method startUp = AccountConnectPlugin.class.getDeclaredMethod("startUp");
		startUp.setAccessible(true);
		startUp.invoke(plugin);

		verify(cm, times(1)).unsetConfiguration(
			AccountConnectPlugin.CONFIG_GROUP, AccountConnectPlugin.ORPHAN_SCREENSHOT_KEY);
	}

	/** A missing ConfigManager must not throw — startUp runs before injection in some test paths. */
	@Test
	public void aMissingConfigManagerIsASafeNoOp() throws Exception
	{
		AccountConnectPlugin plugin = UploadSwitchTest.plugin(false, "");
		inject(plugin, "configManager", null);
		plugin.removeOrphanedKeys();
	}

	/** And the key must genuinely be orphaned — if an item ever declares it again, this unset is a bug. */
	@Test
	public void theKeyIsNotDeclaredByAnyConfigItem()
	{
		for (Method m : AccountConnectConfig.class.getDeclaredMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item == null)
			{
				continue;
			}
			assertFalse("no live config item may own the orphaned key",
				AccountConnectPlugin.ORPHAN_SCREENSHOT_KEY.equals(item.keyName()));
		}
	}

	private static void inject(AccountConnectPlugin p, String name, Object value) throws Exception
	{
		Field f = AccountConnectPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(p, value);
	}
}
