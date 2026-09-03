package com.osrsbestinslot.export;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The standalone reset countdown. Split from the nearby panel because a list that changes height
 * moved the number around the screen — so the arms here are mostly about STAYING STILL.
 */
public class StoreResetOverlayTest
{
	private static Graphics2D graphics()
	{
		return new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB).createGraphics();
	}

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

	/**
	 * THE JITTER FIX. The overlay must draw exactly one row in every state, so its height can never
	 * change. This is the whole reason it is a separate overlay.
	 */
	@Test
	public void alwaysDrawsExactlyOneRowWhateverTheState() throws Exception
	{
		AccountConnectPlugin p = plugin();
		p.setShopOpenForTest(true);
		p.setStoreToolsForTest(true);
		StoreResetOverlay o = new StoreResetOverlay(p);

		// phase unknown
		o.render(graphics());
		assertEquals("waiting state must be one row", 1, o.builtRowCountForTest());

		// phase known — drive the anchor through the real field
		java.lang.reflect.Field a = AccountConnectPlugin.class.getDeclaredField("storeResetAnchorMs");
		a.setAccessible(true);
		a.setLong(p, System.currentTimeMillis() - 30_000L);
		o.render(graphics());
		assertEquals("counting state must also be one row", 1, o.builtRowCountForTest());
	}

	@Test
	public void repeatedRendersDoNotAccumulateRows() throws Exception
	{
		AccountConnectPlugin p = plugin();
		p.setShopOpenForTest(true);
		p.setStoreToolsForTest(true);
		StoreResetOverlay o = new StoreResetOverlay(p);
		for (int i = 0; i < 25; i++)
		{
			o.render(graphics());
		}
		assertEquals("the panel must not grow across frames", 1, o.builtRowCountForTest());
	}

	@Test
	public void drawsNothingOutsideAShopOrWithoutTheStaffGrant() throws Exception
	{
		AccountConnectPlugin outside = plugin();
		outside.setStoreToolsForTest(true);
		assertNull("no shop, no overlay", new StoreResetOverlay(outside).render(graphics()));

		AccountConnectPlugin ungranted = plugin();
		ungranted.setShopOpenForTest(true);
		assertNull("no grant, no overlay", new StoreResetOverlay(ungranted).render(graphics()));
	}

	@Test
	public void drawsWhileAShopIsOpenAndGranted() throws Exception
	{
		AccountConnectPlugin p = plugin();
		p.setShopOpenForTest(true);
		p.setStoreToolsForTest(true);
		Dimension d = new StoreResetOverlay(p).render(graphics());
		assertNotNull(d);
	}

	@Test
	public void theWaitingStateIsNamedAndNeutral() throws Exception
	{
		// A blank or red waiting state is the failure: blank looks broken, red is a standing
		// "do not hand off" warning on a shop that has never reset.
		AccountConnectPlugin p = plugin();
		p.setShopOpenForTest(true);
		p.setStoreToolsForTest(true);
		StoreResetOverlay o = new StoreResetOverlay(p);
		o.render(graphics());
		assertEquals("sell junk", o.lastTextForTest());
		org.junit.Assert.assertNotEquals("waiting must not look urgent",
			StoreResetOverlay.countdownColor(1), o.lastColorForTest());
	}

	@Test
	public void theCountdownRoundsUpAndNeverShowsZero()
	{
		assertEquals("sell junk", StoreResetOverlay.resetText(0));
		assertEquals("sell junk", StoreResetOverlay.resetText(-5));
		assertEquals("1s", StoreResetOverlay.resetText(1));
		assertEquals("rounds up", "1s", StoreResetOverlay.resetText(999));
		assertEquals("13s", StoreResetOverlay.resetText(12_500));
		assertEquals("60s", StoreResetOverlay.resetText(60_000));
	}

	@Test
	public void theColourBandsAreDistinctAndOrdered()
	{
		java.awt.Color safe = StoreResetOverlay.countdownColor(StoreResetOverlay.SAFE_SECONDS);
		java.awt.Color mid = StoreResetOverlay.countdownColor(StoreResetOverlay.URGENT_SECONDS + 1);
		java.awt.Color urgent = StoreResetOverlay.countdownColor(StoreResetOverlay.URGENT_SECONDS);
		org.junit.Assert.assertNotEquals(safe, mid);
		org.junit.Assert.assertNotEquals(mid, urgent);
		org.junit.Assert.assertNotEquals(safe, urgent);
		assertTrue("urgent reads red", urgent.getRed() > urgent.getGreen());
		assertTrue("safe reads green", safe.getGreen() > safe.getRed());
	}

	@Test
	public void theUrgentBandIsInclusiveOfItsBoundary()
	{
		assertEquals(StoreResetOverlay.countdownColor(StoreResetOverlay.URGENT_SECONDS),
			StoreResetOverlay.countdownColor(1));
		org.junit.Assert.assertNotEquals(
			StoreResetOverlay.countdownColor(StoreResetOverlay.URGENT_SECONDS),
			StoreResetOverlay.countdownColor(StoreResetOverlay.URGENT_SECONDS + 1));
	}
}
