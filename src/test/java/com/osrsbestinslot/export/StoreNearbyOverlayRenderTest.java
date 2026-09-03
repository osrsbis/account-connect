package com.osrsbestinslot.export;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The visibility gate, exercised through the REAL RuneLite OverlayPanel render path against a real
 * Graphics2D. A unit test of the row builder cannot catch an overlay that draws in the wrong state,
 * and drawing over the game outside a shop is this feature's worst failure.
 */
public class StoreNearbyOverlayRenderTest
{
	/**
	 * A plugin with a linked token. storeToolsEnabled() requires BOTH the server grant and a linked
	 * token, so a config-less plugin cannot represent either state honestly.
	 */
	private static AccountConnectPlugin plugin() throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		java.lang.reflect.Field f = AccountConnectPlugin.class.getDeclaredField("config");
		f.setAccessible(true);
		f.set(p, new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return "0123456789abcdef0123456789abcdef";
			}
		});
		return p;
	}

	private static Graphics2D graphics()
	{
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
		return img.createGraphics();
	}

	@Test
	public void drawsNothingWhileNoShopIsOpen() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		plugin.setShopOpenForTest(false);
		StoreNearbyOverlay overlay = new StoreNearbyOverlay(plugin);
		assertNull("overlay must not render outside a shop", overlay.render(graphics()));
	}

	@Test
	public void drawsWhileAShopIsOpen() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		plugin.setShopOpenForTest(true);
		plugin.setStoreToolsForTest(true);
		StoreNearbyOverlay overlay = new StoreNearbyOverlay(plugin);
		// No client is injected, so nearbyPlayersSnapshot returns empty — the panel must still draw
		// its title and the explicit "nobody nearby" line rather than vanishing.
		Dimension d = overlay.render(graphics());
		assertNotNull("overlay must render while a shop is open", d);
		// OverlayPanel clears its children at the END of render, so inspect what it BUILT. A panel
		// with only a title is indistinguishable from one whose data source silently broke — the
		// explicit empty-state line is what tells those apart, so assert it is actually produced.
		java.util.List<String[]> rows = StoreNearbyOverlay.visibleRows(plugin.nearbyPlayersSnapshot(6));
		org.junit.Assert.assertTrue("no client injected, so the row list must be empty", rows.isEmpty());
		// One line: the "Nobody nearby" heading. The reset countdown is its own overlay now.
		org.junit.Assert.assertEquals("empty-state heading only",
			1, overlay.builtRowCountForTest());
	}



	/**
	 * THE STAFF GATE. These overlays render other players' names, and they are granted only by the
	 * backend to a staff-allowlisted token. A regular player must see nothing even inside a shop.
	 */
	@Test
	public void drawsNothingWhenTheServerHasNotGrantedStoreTools() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		plugin.setShopOpenForTest(true);
		// store tools deliberately NOT granted — this is every ordinary player's state
		StoreNearbyOverlay overlay = new StoreNearbyOverlay(plugin);
		assertNull("ungranted tokens must see no overlay", overlay.render(graphics()));
	}

	/**
	 * THE DEV OVERRIDE IS OFF UNLESS A JVM FLAG SETS IT, and only the exact grant words count.
	 * A Hub build launched normally has no such property, so this can never leak to a player.
	 */
	@Test
	public void theDevOverrideIsOffWithoutTheFlagAndRefusesJunkValues() throws Exception
	{
		System.clearProperty("osrsbis.storetools");
		org.junit.Assert.assertFalse("no flag = no override",
			AccountConnectPlugin.storeToolsDevOverride());
		try
		{
			for (String bad : new String[]{"", " ", "off", "false", "0", "yes", "ON"})
			{
				System.setProperty("osrsbis.storetools", bad);
				org.junit.Assert.assertFalse("'" + bad + "' must not grant",
					AccountConnectPlugin.storeToolsDevOverride());
			}
			for (String good : new String[]{"on", "true", "1"})
			{
				System.setProperty("osrsbis.storetools", good);
				org.junit.Assert.assertTrue("'" + good + "' must grant",
					AccountConnectPlugin.storeToolsDevOverride());
			}
		}
		finally
		{
			System.clearProperty("osrsbis.storetools");
		}
	}

	/** The override still requires a linked token — it bypasses the server, not the account. */
	@Test
	public void theDevOverrideStillRequiresALinkedToken() throws Exception
	{
		System.setProperty("osrsbis.storetools", "on");
		try
		{
			AccountConnectPlugin unlinked = new AccountConnectPlugin();
			java.lang.reflect.Field f = AccountConnectPlugin.class.getDeclaredField("config");
			f.setAccessible(true);
			f.set(unlinked, new AccountConnectConfig()
			{
				@Override
				public String linkToken()
				{
					return "";
				}
			});
			org.junit.Assert.assertFalse("no token = no overlays even with the flag",
				unlinked.storeToolsEnabled());
			org.junit.Assert.assertTrue("linked token + flag = granted", plugin().storeToolsEnabled());
		}
		finally
		{
			System.clearProperty("osrsbis.storetools");
		}
	}

	@Test
	public void theGateDefaultsToOff() throws Exception
	{
		AccountConnectPlugin plugin = plugin();
		org.junit.Assert.assertFalse("store tools must default OFF", plugin.storeToolsEnabled());
	}

	@Test
	public void repeatedRendersDoNotAccumulateRows() throws Exception
	{
		// OverlayPanel clears its children between frames only when clearChildren is on. If it is
		// not, the panel grows one row per frame and covers the screen within seconds.
		AccountConnectPlugin plugin = plugin();
		plugin.setShopOpenForTest(true);
		plugin.setStoreToolsForTest(true);
		StoreNearbyOverlay overlay = new StoreNearbyOverlay(plugin);
		overlay.render(graphics());
		int afterFirst = overlay.getPanelComponent().getChildren().size();
		for (int i = 0; i < 20; i++)
		{
			overlay.render(graphics());
		}
		int afterMany = overlay.getPanelComponent().getChildren().size();
		org.junit.Assert.assertEquals("panel children grew across frames", afterFirst, afterMany);
	}
}
