package com.osrsbestinslot.export;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The shop-stock diagnostic. It is about to be quoted as evidence for what a reset actually is, so
 * it gets one known-bad run first: a trace that silently writes nothing, or writes the wrong delta,
 * would send the next fix in the wrong direction.
 */
public class ShopTraceTest
{
	/**
	 * A NORMAL BUILD WRITES NOTHING.
	 *
	 * The arm is written as an A/B on ONE file rather than as "some temp path stays absent", which
	 * proves nothing: the plugin was never pointed at that path, so the assertion held whatever the
	 * code did. Here the property is set, a change is traced, the file's size is recorded, then the
	 * property is cleared and the same changes are replayed. The file must not grow by one byte.
	 *
	 * KNOWN LIMIT, stated because it is real: this kills a stale-or-removed guard, which is the
	 * regression that can actually happen. It cannot kill a trace rewritten to a hard-coded constant
	 * path, because a black-box test cannot enumerate every path the process might write.
	 */
	@Test
	public void writesNothingUnlessExplicitlyEnabled() throws Exception
	{
		Path out = Files.createTempFile("shoptrace-ab", ".log");
		Files.delete(out);
		try
		{
			System.setProperty("osrsbis.shoptrace", out.toString());
			AccountConnectPlugin on = configured();
			on.handleShopStockChanged(container(new int[][]{{561, 1}}));
			on.handleShopStockChanged(container(new int[][]{{561, 0}}));
			assertTrue("the control arm must prove the trace CAN write", Files.exists(out));
			long enabledSize = Files.size(out);
			assertTrue("the enabled trace must have content", enabledSize > 0);

			System.clearProperty("osrsbis.shoptrace");
			AccountConnectPlugin off = configured();
			off.handleShopStockChanged(container(new int[][]{{561, 1}}));
			off.handleShopStockChanged(container(new int[][]{{561, 0}}));

			assertEquals("a normal build must write no trace at all",
				enabledSize, Files.size(out));
		}
		finally
		{
			System.clearProperty("osrsbis.shoptrace");
			Files.deleteIfExists(out);
		}
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
			public boolean enableUpload()
			{
				return true;
			}

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
