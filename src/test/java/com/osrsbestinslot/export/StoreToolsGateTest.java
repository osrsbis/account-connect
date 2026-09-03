package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * THE STAFF GATE, as one place. These overlays render other players' names on screen, so the
 * question "could an ordinary player ever see this?" must have exactly one answer: no.
 *
 * Three independent conditions, ALL required: a linked token, the backend grant, and an open shop.
 * The grant itself is server-derived from the staff-RSN allowlist and cannot be self-issued —
 * tests/e3a/store-tools-gate.test.mjs holds that half.
 */
public class StoreToolsGateTest
{
	private static java.awt.Graphics2D g()
	{
		return new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB).createGraphics();
	}

	private static AccountConnectPlugin withToken(String token) throws Exception
	{
		AccountConnectPlugin p = new AccountConnectPlugin();
		java.lang.reflect.Field f = AccountConnectPlugin.class.getDeclaredField("config");
		f.setAccessible(true);
		f.set(p, new AccountConnectConfig()
		{
			@Override
			public String linkToken()
			{
				return token;
			}
		});
		return p;
	}

	@Test
	public void aFreshPluginGrantsNothing() throws Exception
	{
		System.clearProperty("osrsbis.storetools");
		assertFalse("default must be closed", withToken("0123456789abcdef0123456789abcdef").storeToolsEnabled());
	}

	/** No token means no account is linked at all — the grant cannot apply to anybody. */
	@Test
	public void noLinkedTokenMeansNoOverlaysEvenWithTheGrant() throws Exception
	{
		for (String bad : new String[]{"", "   ", "not-a-token", "0123456789abcdef"})
		{
			AccountConnectPlugin p = withToken(bad);
			p.setStoreToolsForTest(true);
			assertFalse("token '" + bad + "' must not enable store tools", p.storeToolsEnabled());
		}
	}

	/** BOTH overlays must obey the gate. One of them leaking is the whole failure. */
	@Test
	public void neitherOverlayDrawsWithoutTheGrant() throws Exception
	{
		AccountConnectPlugin p = withToken("0123456789abcdef0123456789abcdef");
		p.setShopOpenForTest(true);		// shop open, grant withheld
		assertNull("reset overlay must stay hidden", new StoreResetOverlay(p).render(g()));
		assertNull("nearby overlay must stay hidden", new StoreNearbyOverlay(p).render(g()));
	}

	/** And with the grant, both draw — otherwise the gate is just "always off". */
	@Test
	public void bothOverlaysDrawWhenGrantedInsideAShop() throws Exception
	{
		AccountConnectPlugin p = withToken("0123456789abcdef0123456789abcdef");
		p.setShopOpenForTest(true);
		p.setStoreToolsForTest(true);
		org.junit.Assert.assertNotNull(new StoreResetOverlay(p).render(g()));
		org.junit.Assert.assertNotNull(new StoreNearbyOverlay(p).render(g()));
	}

	/** Outside a shop, a granted staff account still sees nothing. */
	@Test
	public void theGrantDoesNotFollowStaffOutOfTheShop() throws Exception
	{
		AccountConnectPlugin p = withToken("0123456789abcdef0123456789abcdef");
		p.setStoreToolsForTest(true);		// granted, but no shop open
		assertNull(new StoreResetOverlay(p).render(g()));
		assertNull(new StoreNearbyOverlay(p).render(g()));
	}

	/**
	 * THE SHIPPED BUILD HAS NO LOCAL WAY IN. The dev override is a JVM system property, which the
	 * Plugin Hub launcher never sets — but a config item or an env read WOULD be reachable by a
	 * player, so assert the override's shape rather than trusting the comment above it.
	 */
	@Test
	public void theOverrideIsAJvmPropertyAndNothingElse() throws Exception
	{
		System.clearProperty("osrsbis.storetools");
		assertFalse(AccountConnectPlugin.storeToolsDevOverride());
		try
		{
			System.setProperty("osrsbis.storetools", "on");
			assertTrue(AccountConnectPlugin.storeToolsDevOverride());
		}
		finally
		{
			System.clearProperty("osrsbis.storetools");
		}
		// A player-reachable surface would be a config key. There must be none.
		for (java.lang.reflect.Method m : AccountConnectConfig.class.getMethods())
		{
			assertFalse("config must expose no store-tools switch: " + m.getName(),
				m.getName().toLowerCase().contains("storetool"));
		}
	}

	/** A server revoke mid-session closes the gate immediately. */
	@Test
	public void aRevokeClosesTheGate() throws Exception
	{
		AccountConnectPlugin p = withToken("0123456789abcdef0123456789abcdef");
		p.setStoreToolsForTest(true);
		p.setShopOpenForTest(true);
		assertTrue(p.storeToolsEnabled());
		p.setStoreToolsForTest(false);
		assertFalse("revoke must take effect at once", p.storeToolsEnabled());
		assertNull(new StoreNearbyOverlay(p).render(g()));
	}
}
