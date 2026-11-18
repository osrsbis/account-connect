package com.osrsbestinslot.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.VarbitComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

/** Collection-log page headers: the counters a page shows, kept per page title; a read 0 is 0, an unread page absent. */
public class CollectionLogPagesTest
{
	private Client client;
	private int[] vars;
	private final AccountProgressCapture capture = new AccountProgressCapture();
	private Widget header;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		vars = new int[5000];
		when(client.getVarps()).thenReturn(vars);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(mock(Player.class));
		when(client.getAccountHash()).thenReturn(42L);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
		VarbitComposition host = mock(VarbitComposition.class);
		when(host.getIndex()).thenReturn(2049);
		when(host.getLeastSignificantBit()).thenReturn(11);
		when(host.getMostSignificantBit()).thenReturn(11);
		when(client.getVarbit(12061)).thenReturn(host);
		header = mock(Widget.class);
		when(client.getWidget(InterfaceID.Collection.HEADER_TEXT)).thenReturn(header);
	}

	private void show(String... lines)
	{
		Widget[] rows = new Widget[lines.length];
		for (int i = 0; i < lines.length; i++)
		{
			rows[i] = mock(Widget.class);
			when(rows[i].getText()).thenReturn(lines[i]);
		}
		when(header.getChildren()).thenReturn(rows);
	}

	@Test
	public void killCountLinesAreParsedWithTagsAndCommas()
	{
		Map<String, Object> page = AccountProgressCapture.parsePage(new String[]{"Chambers of Xeric",
			"Obtained: <col=ff0000>12/27</col>", "Chambers of Xeric completions: <col=ffffff>1,234</col>",
			"Chambers of Xeric (CM) completions: 0"});
		assertEquals("Chambers of Xeric", page.get("title"));
		assertEquals(12L, page.get("obtained"));
		assertEquals(27L, page.get("total"));
		Map<?, ?> counts = (Map<?, ?>) page.get("counts");
		assertEquals(1234L, counts.get("Chambers of Xeric completions"));
		assertEquals("a shown zero is a read zero", 0L, counts.get("Chambers of Xeric (CM) completions"));
	}

	@Test
	public void noPageOrMalformedCountersAreNotARead()
	{
		assertNull(AccountProgressCapture.parsePage(new String[]{"Search"}));
		assertNull(AccountProgressCapture.parsePage(new String[]{"", "Obtained: 1/2"}));
		assertNull(AccountProgressCapture.parsePage(new String[]{"Zulrah", "not a counter"}));
		assertNull("a misgrouped number is refused, never guessed",
			AccountProgressCapture.parsePage(new String[]{"Zulrah", "Obtained: 1/2", "Zulrah kills: 1,23"}));
		Map<?, ?> page = AccountProgressCapture.parsePage(new String[]{"Zulrah", "Obtained: 3/13"});
		assertTrue(((Map<?, ?>) page.get("counts")).isEmpty());
	}

	@Test
	public void ownLogPageIsKeptAndUnchangedValuesKeepTheirTime()
	{
		show("Zulrah", "Obtained: 3/13", "Zulrah kills: 0");
		capture.collectionPage(client, 100);
		capture.collectionPage(client, 200);
		Map<String, Object> zulrah = capture.pages().get("Zulrah");
		assertEquals(100L, zulrah.get("observed_at"));
		assertEquals(0L, ((Map<?, ?>) zulrah.get("counts")).get("Zulrah kills"));
		show("Zulrah", "Obtained: 3/13", "Zulrah kills: 1");
		capture.collectionPage(client, 300);
		assertEquals(300L, capture.pages().get("Zulrah").get("observed_at"));
		assertFalse("an unread page is absent", capture.pages().containsKey("Vorkath"));
		capture.reset();
		assertTrue(capture.pages().isEmpty());
	}

	@Test
	public void anotherPlayersLogIsNeverRead()
	{
		vars[2049] = 1 << 11;	// house host's log open
		show("Zulrah", "Obtained: 3/13", "Zulrah kills: 50");
		capture.collectionPage(client, 100);
		assertTrue(capture.pages().isEmpty());
	}
}
