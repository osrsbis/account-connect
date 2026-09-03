package com.osrsbestinslot.export;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shop-stock diagnostic. It is about to be quoted as evidence for what a reset actually is, so
 * it gets one known-bad run first: a trace that silently writes nothing, or writes the wrong delta,
 * would send the next fix in the wrong direction.
 */
public class ShopTraceTest
{
	@Test
	public void writesNothingUnlessExplicitlyEnabled() throws Exception
	{
		System.clearProperty("osrsbis.shoptrace");
		Path out = Files.createTempFile("shoptrace-off", ".log");
		Files.delete(out);
		AccountConnectPlugin p = configured();
		p.handleShopStockChanged(container(new int[][]{{561, 1}}));
		assertFalse("a normal build must write no trace at all", Files.exists(out));
	}

	@Test
	public void recordsTheItemsThatActuallyChanged() throws Exception
	{
		Path out = Files.createTempFile("shoptrace", ".log");
		Files.delete(out);
		System.setProperty("osrsbis.shoptrace", out.toString());
		try
		{
			AccountConnectPlugin p = configured();
			p.handleShopStockChanged(container(new int[][]{{561, 1}, {995, 10}}));	// baseline
			p.handleShopStockChanged(container(new int[][]{{561, 0}, {995, 11}}));	// a reset shape

			String text = new String(Files.readAllBytes(out), "UTF-8");
			String[] lines = text.split("\n");
			// The fixture is deliberately a real tick shape (one fall + one rise), so the plugin
			// also writes its ANCHOR line. Assert on CONTENT rather than a line count, which would
			// break every time a new diagnostic line is added.
			String delta = null;
			for (String l : lines)
			{
				if (l.contains("delta=") && l.contains("561:1->0"))
				{
					delta = l;
				}
			}
			assertTrue("the fall must be recorded", delta != null);
			assertTrue("the restock must be recorded too", delta.contains("995:10->11"));
			// KNOWN-BAD CONTROL: an unchanged container must NOT look like a change.
			p.handleShopStockChanged(container(new int[][]{{561, 0}, {995, 11}}));
			String all = new String(Files.readAllBytes(out), "UTF-8");
			assertTrue("an unchanged read must say so", all.contains("(none)"));
		}
		finally
		{
			System.clearProperty("osrsbis.shoptrace");
			Files.deleteIfExists(out);
		}
	}

	private static AccountConnectPlugin configured() throws Exception
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

	private static net.runelite.api.ItemContainer container(int[][] items)
	{
		net.runelite.api.ItemContainer c = org.mockito.Mockito.mock(net.runelite.api.ItemContainer.class);
		net.runelite.api.Item[] arr = new net.runelite.api.Item[items.length];
		for (int i = 0; i < items.length; i++)
		{
			arr[i] = new net.runelite.api.Item(items[i][0], items[i][1]);
		}
		org.mockito.Mockito.when(c.getItems()).thenReturn(arr);
		return c;
	}
}
