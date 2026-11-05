package com.osrsbestinslot.export;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MinigameLinesTest
{
	private static Map<String, Object> p(String line)
	{
		return MinigameLines.parse(line);
	}

	@Test
	public void sourceQuotedLinesParse()
	{
		Map<String, Object> r = p("Your completion count for TzHaar-Ket-Rak's First Challenge is: <col=ff0000>1</col>.");
		assertEquals("tzhaar_ket_rak", r.get("minigame"));
		assertEquals("first", r.get("detail"));
		assertEquals(1L, r.get("total"));
		r = p("You won! You have now won 1,909 duels.");
		assertEquals("win", r.get("result"));
		assertEquals(1909L, r.get("total"));
		assertEquals("loss", p("You were defeated! You have won one duel.").get("result"));
		assertEquals("a lost-duel total is not a second loss", "total", p("You have now lost 12 duels.").get("result"));
		assertEquals(206L, p("Your Agility Arena Total Ticket count is: <col=ff0000>206</col>.").get("total"));
		r = p("You have completed Floor 5 of the Hallowed Sepulchre! Total completions: <col=ff0000>1,024</col>.");
		assertEquals("floor_5", r.get("detail"));
		assertEquals(1024L, r.get("total"));
		assertEquals("grand_coffin", p("You have opened the Grand Hallowed Coffin <col=ff0000>3</col> times!").get("detail"));
		assertEquals(812L, p("Amount of Rifts you have closed: <col=ff0000>812</col>.").get("total"));
		assertNull("a shared reward line is the loot path's, gated by region", p("You found some loot: 12 x Nature rune"));
		assertEquals(77L, p("You have completed @mes_hl_red@77</col> rumours for the Hunter Guild.").get("total"));
		r = p("You've been awarded <col=00ff00>15 Anima-infused bark</col>.");
		assertEquals(15L, r.get("count"));
		assertEquals("sword_finished", p("You have successfully finished the sword. You should give it to Kovac for a reward.").get("detail"));
		assertEquals("start", p("The mine is currently dormant, and will awaken within the next 30 seconds.").get("result"));
	}

	@Test
	public void dialogueAndMessageBoxesParse()
	{
		Map<String, Object> r = MinigameLines.parseDialogue("Sword completed in: 4:12 at quality: 165<br>Best completed in: 3:50 at quality: 182<br>"
			+ "You're awarded: 4,800 Smithing XP and 2,640 coins.<br>Total swords made in the Giants Foundry: 1,210");
		assertEquals("sword_handed_in", r.get("detail"));
		assertEquals(165L, r.get("count"));
		assertEquals(1210L, r.get("total"));
		assertEquals("book_delivered", MinigameLines.parseDialogue("Thank you very much, Player One, please accept a token of my thanks.").get("detail"));
		assertEquals(4000L, MinigameLines.parseDialogue("*** Congratulations! 4,000 Chompies! ***").get("total"));
		assertEquals("guthix_cape", MinigameLines.parseDialogue("You kneel and chant to Guthix... You feel a rush of energy charge through your veins. Suddenly a cape appears in your pack.").get("detail"));
		assertEquals("challenge", MinigameLines.parseDialogue("Congratulations! You have completed the challenge!").get("detail"));
		assertEquals(12L, MinigameLines.parse("Dragon chainbody!<br>High level gamble count: <col=7f0000>12</col>").get("total"));
		assertNull("the dialogue table is not used for chat", MinigameLines.parse("*** Congratulations! 4,000 Chompies! ***"));
		assertNull(MinigameLines.parseDialogue("Total swords made in the Giants Foundry: 1,,210"));
		for (Object[] d : MinigameLines.DIALOGUE) assertTrue("fixed label only", d[2] == null || ((String) d[2]).matches("[a-z0-9_]+"));
	}

	@Test
	public void wrongShapesGiveNothing()
	{
		for (String bad : new String[]{null, "", "You won! You have now won 1,,909 duels.", "You won! You have now won 12,50 duels.",
			"Amount of Rifts you have closed: -3.", "Amount of Rifts you have closed: 1.5K.",
			"Your completion count for TzHaar-Ket-Rak's Seventh Challenge is: 1.",
			"You have completed Floor 6 of the Hallowed Sepulchre! Total completions: 4.",
			"Someone: You won! You have now won 3 duels.", "You have been awarded 15 Anima-infused bark."})
		{
			assertNull("no event for: " + bad, p(bad));
		}
	}

	@Test
	public void screensKeepOnlyPlainNumbers()
	{
		Map<String, String> t = new LinkedHashMap<>();
		t.put("pieces_of_eight", "15");
		t.put("score", "Score: 1,250");
		t.put("xp_reward", "1.5K");
		t.put("blue_team_final_score", null);
		Map<String, Object> r = MinigameLines.screen("trouble_brewing", t);
		@SuppressWarnings("unchecked") Map<String, Object> v = (Map<String, Object>) r.get("screen");
		assertEquals(15, v.get("pieces_of_eight"));
		assertEquals(1250, v.get("score"));
		assertTrue("an unknown shape is left out", !v.containsKey("xp_reward"));
		Map<String, String> none = new LinkedHashMap<>();
		none.put("wins", "-");
		assertNull(MinigameLines.screen("last_man_standing", none));
		for (Object[] c : MinigameLines.SCREENS) assertTrue(MinigameLines.SCREEN_GROUPS.contains(((int) c[1]) >>> 16));
		Map<String, Object> wire = new LinkedHashMap<>(r);
		wire.put("type", "minigame");
		assertEquals("screen", PublicTransport.event(wire).get("result"));
		@SuppressWarnings("unchecked") Map<String, Object> pub = (Map<String, Object>) PublicTransport.event(wire).get("screen");
		assertEquals(15, pub.get("pieces_of_eight"));
		Map<String, Object> forged = new LinkedHashMap<>();
		forged.put("score", 3);
		forged.put("player_name", "Someone");
		forged.put("not_a_label", 9);
		wire.put("screen", forged);
		@SuppressWarnings("unchecked") Map<String, Object> clean = (Map<String, Object>) PublicTransport.event(wire).get("screen");
		assertEquals("only known labels with numbers survive", 1, clean.size());
	}

	@Test
	public void newVarsAreUnverifiedNeverCounted()
	{
		for (String k : new String[]{"tzhaar_ket_rak_1_completions", "castle_wars_losses", "trouble_brewing_pieces",
			"barracuda_tempor_tantrum_completed", "pete_kayer_boss_expert", "blast_furnace_runite_bars"})
		{
			assertEquals(k, "unverified", KillContext.kindOf(k));
		}
	}
}
