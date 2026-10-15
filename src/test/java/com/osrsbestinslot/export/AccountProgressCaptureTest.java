package com.osrsbestinslot.export;

import java.lang.reflect.Field;
import java.util.Map;
import net.runelite.api.*;
import net.runelite.api.events.*;
import okhttp3.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AccountProgressCaptureTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private Client client;
    private AccountProgressCapture capture;
    private int[] vars;
    private int tick;

    @Before
    public void setup()
    {
        client = mock(Client.class);
        capture = new AccountProgressCapture();
        vars = new int[5000];
        when(client.getVarps()).thenReturn(vars);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(mock(Player.class));
        when(client.getAccountHash()).thenReturn(42L);
        when(client.getWorldType()).thenReturn(java.util.EnumSet.noneOf(WorldType.class));
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
        when(client.getTickCount()).thenAnswer(i -> tick);
        bit(12061, 2049, 11, 11);
    }

    private void bit(int id, int index, int low, int high)
    {
        VarbitComposition def = mock(VarbitComposition.class);
        when(def.getIndex()).thenReturn(index);
        when(def.getLeastSignificantBit()).thenReturn(low);
        when(def.getMostSignificantBit()).thenReturn(high);
        when(client.getVarbit(id)).thenReturn(def);
    }

    private void settle()
    {
        assertFalse(capture.ready(client, TOKEN));
        tick += 2;
        assertTrue(capture.ready(client, TOKEN));
    }

    private Map<?, ?> block(String name)
    {
        return (Map<?, ?>) capture.snapshot(client).get(name);
    }

    @Test
    public void requiresHpIdentityAndTwoSettledTicks()
    {
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(0);
        assertFalse(capture.ready(client, TOKEN));
        tick += 5;
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);
        assertFalse(capture.ready(client, TOKEN));
        tick++;
        assertFalse(capture.ready(client, TOKEN));
        tick++;
        assertTrue(capture.ready(client, TOKEN));
        when(client.getAccountHash()).thenReturn(-1L);
        assertFalse(capture.ready(client, TOKEN));
    }

    @Test
    public void slayerBossDiscriminatorAndStaleTaskFields()
    {
        bit(4723, 1077, 17, 23);
        vars[1077] = 23 << 17;
        vars[395] = 98;
        vars[394] = 17;
        vars[2096] = 9;
        vars[4258] = 35;
        assertEquals(23, block("slayer").get("boss_id"));
        assertEquals(9, block("slayer").get("area_id"));
        assertEquals(35, block("slayer").get("task_original"));
        vars[395] = 12;
        assertNull(block("slayer").get("boss_id"));
        vars[394] = 0;
        assertNull(block("slayer").get("area_id"));
        assertNull(block("slayer").get("task_original"));
        assertEquals(0, block("slayer").get("task_remaining"));
    }

    @Test
    public void missingDefinitionsAndShortVarpArraysStayUnknown()
    {
        assertNull(block("prayer_unlocks").get("rigour"));
        bit(5451, 1429, 1, 1);
        assertEquals(false, block("prayer_unlocks").get("rigour"));
        vars[1429] = 2;
        assertEquals(true, block("prayer_unlocks").get("rigour"));
        when(client.getVarps()).thenReturn(new int[100]);
        assertNull(block("prayer_unlocks").get("rigour"));
        assertNull(block("slayer").get("task_remaining"));
        when(client.getVarbit(5451)).thenThrow(new IllegalArgumentException());
        assertNull(block("prayer_unlocks").get("rigour"));
    }

    @Test
    public void emptyCacheDefinitionIsUnknownNotLocked()
    {
        bit(5451, 0, 0, 0);
        assertNull(block("prayer_unlocks").get("rigour"));
    }

    @Test
    public void prayerWatsonAndAchievementSourcesDecodeIndependently()
    {
        bit(5451, 1429, 1, 1); bit(5452, 1429, 2, 2); bit(5453, 1429, 3, 3);
        bit(16097, 4655, 0, 0); bit(16098, 4655, 1, 1); bit(3909, 1050, 1, 4);
        bit(5186, 1353, 8, 8); bit(5187, 1353, 9, 9);
        bit(14815, 3791, 0, 15);
        vars[1429] = 10; vars[4655] = 2; vars[1050] = 16; vars[1353] = 256; vars[3791] = 1234;
        Map<?, ?> p = block("prayer_unlocks");
        assertEquals(true, p.get("rigour")); assertEquals(false, p.get("augury"));
        assertEquals(true, p.get("preserve")); assertEquals(false, p.get("deadeye"));
        assertEquals(true, p.get("mystic_vigour")); assertEquals(8, p.get("knight_waves_state"));
        assertEquals(true, block("watson_clues").get("easy"));
        assertEquals(false, block("watson_clues").get("medium"));
        assertNull(block("watson_clues").get("elite"));
        assertEquals(1234, capture.snapshot(client).get("combat_achievement_points"));
    }

    @Test
    public void collectionIsPartialHasAbsoluteQuantitiesAndRejectsHost()
    {
        assertNull(block("collection_log").get("observed_item_count"));
        vars[2943] = 120; vars[2944] = 1600;
        capture.collectionSlot(client, new Object[]{4100, 4151, 3}, 123L);
        capture.collectionSlot(client, new Object[]{4100, 4151, 3}, 123L);
        Map<?, ?> log = block("collection_log");
        assertEquals(1, log.get("observed_item_count"));
        assertEquals(3, ((Map<?, ?>) log.get("items")).get(4151));
        assertEquals(120, log.get("game_obtained_count"));
        assertEquals(false, log.get("complete"));
        vars[2049] = 1 << 11;
        capture.collectionSlot(client, new Object[]{4100, 11832, 1}, 124L);
        assertEquals(1, block("collection_log").get("observed_item_count"));
        assertNull(block("collection_log").get("game_obtained_count"));
        assertEquals(123L, block("collection_log").get("observed_at"));
    }

    @Test
    public void malformedSlotsAndUnknownHostNeverBecomeOwnItems()
    {
        capture.collectionSlot(client, null, 1);
        capture.collectionSlot(client, new Object[]{4100, -1, 2}, 1);
        capture.collectionSlot(client, new Object[]{4100, 4151, 0}, 1);
        capture.collectionSlot(client, new Object[]{4100, "4151", 1}, 1);
        when(client.getVarbit(12061)).thenReturn(null);
        capture.collectionSlot(client, new Object[]{4100, 4151, 1}, 1);
        assertNull(block("collection_log").get("observed_at"));
    }

    @Test
    public void accountModeAndTokenChangesClearObservations()
    {
        settle();
        capture.collectionSlot(client, new Object[]{4100, 4151, 1}, 1);
        capture.chat("You have completed 12 hard Treasure Trails.", 1);
        when(client.getAccountHash()).thenReturn(43L);
        assertFalse(capture.ready(client, TOKEN));
        assertNull(block("collection_log").get("observed_at"));
        assertTrue(((java.util.List<?>) capture.snapshot(client).get("recent_signals")).isEmpty());
        tick += 2;
        assertTrue(capture.ready(client, TOKEN));
        when(client.getWorldType()).thenReturn(java.util.EnumSet.of(WorldType.SEASONAL));
        assertFalse(capture.ready(client, TOKEN));
        tick += 2;
        assertTrue(capture.ready(client, TOKEN));
        assertFalse(capture.ready(client, "another-token"));
    }

    @Test
    public void exactMessagesParseAndUnrelatedOrMalformedCountsDoNot()
    {
        Map<String, Object> clue = AccountProgressCapture.parseMessage("You have completed <col=ff0000>1,204</col> hard Treasure Trails.");
        assertEquals(1204L, clue.get("count")); assertEquals("hard", clue.get("subject"));
        assertNotNull(AccountProgressCapture.parseMessage("You have completed 1 easy Treasure Trail."));
        assertNull(AccountProgressCapture.parseMessage("You have completed 1,,2 hard Treasure Trails."));
        assertNull(AccountProgressCapture.parseMessage("You have completed 9999999999999999999999 hard Treasure Trails."));
        assertNull(AccountProgressCapture.parseMessage("Other player has completed 12 hard Treasure Trails."));
        assertNull(AccountProgressCapture.parseMessage("You have completed 12 unknown Treasure Trails."));
        assertNull(AccountProgressCapture.parseMessage("You have completed 0 hard Treasure Trails."));
        assertNull(AccountProgressCapture.parseMessage(null));
        assertEquals("Abyssal whip", AccountProgressCapture.parseMessage("New item added to your collection log: @mes_hl_red@Abyssal whip").get("subject"));
        Map<String, Object> ca = AccountProgressCapture.parseMessage("CA_ID:123|Congratulations, you've completed an elite combat task: Perfect kill.");
        assertEquals(123L, ca.get("task_id")); assertEquals("elite", ca.get("tier"));
        assertNull(AccountProgressCapture.parseMessage("Player has completed an elite combat task: Perfect kill."));
        Map<String, Object> salvage = AccountProgressCapture.parseMessage("You sort through the Small salvage and find: 12 x Coal.");
        assertEquals(12L, salvage.get("quantity")); assertEquals("Coal", salvage.get("subject"));
    }

    @Test
    public void signalsAreBoundedLastSeenObservationsNotAnEventCounter()
    {
        capture.chat("You have completed 12 hard Treasure Trails.", 100);
        capture.chat("You have completed 12 hard Treasure Trails.", 101);
        java.util.List<?> rows = (java.util.List<?>) capture.snapshot(client).get("recent_signals");
        assertEquals(1, rows.size());
        assertEquals(100L, ((Map<?, ?>) rows.get(0)).get("observed_at"));
        for (int i = 0; i < 130; i++) capture.chat("New item added to your collection log: Item " + i, 102);
        assertEquals(128, ((java.util.List<?>) capture.snapshot(client).get("recent_signals")).size());
        assertEquals(3, capture.snapshot(client).get("signals_evicted"));
    }

    private AccountConnectPlugin plugin() throws Exception
    {
        AccountConnectPlugin p = new AccountConnectPlugin();
        inject(p, "client", client);
        inject(p, "config", new AccountConnectConfig() { public String linkToken() { return TOKEN; } });
        return p;
    }

    private void inject(AccountConnectPlugin p, String name, Object value) throws Exception
    {
        Field f = AccountConnectPlugin.class.getDeclaredField(name); f.setAccessible(true); f.set(p, value);
    }

    private Response policy(String header, String value)
    {
        return new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header(header, value).build();
    }

    @Test
    public void batch1GrantDoesNotEnableBatch2AndLateTokenCannotGrant() throws Exception
    {
        AccountConnectPlugin p = plugin();
        assertNull(p.progressBlock());
        p.applyServerPolicy(policy("X-Broad-Capture", "on"), TOKEN);
        assertNull(p.progressBlock());
        p.applyServerPolicy(policy("X-Account-Progress", "on"), "old-token");
        assertFalse(p.progressGranted());
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        assertNull(p.progressBlock()); tick += 2;
        assertNotNull(p.progressBlock());
        p.applyServerPolicy(policy("X-Account-Progress", "off"), TOKEN);
        assertNull(p.progressBlock());
    }

    @Test
    public void revokeRegrantWithinTickRestartsSettleAndClearsData() throws Exception
    {
        AccountConnectPlugin p = plugin();
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        p.progressBlock(); tick += 2; assertNotNull(p.progressBlock());
        ChatMessage msg = new ChatMessage(); msg.setType(ChatMessageType.GAMEMESSAGE);
        msg.setMessage("You have completed 12 hard Treasure Trails.");
        p.onChatMessage(msg);
        assertEquals(1, ((java.util.List<?>) p.progressBlock().get("recent_signals")).size());
        p.applyServerPolicy(policy("X-Account-Progress", "off"), TOKEN);
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        assertNull(p.progressBlock()); tick += 2;
        assertTrue(((java.util.List<?>) p.progressBlock().get("recent_signals")).isEmpty());
    }

    @Test
    public void publicChatCannotSupplyProgress() throws Exception
    {
        AccountConnectPlugin p = plugin();
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        p.progressBlock(); tick += 2; p.progressBlock();
        ChatMessage msg = new ChatMessage(); msg.setType(ChatMessageType.PUBLICCHAT);
        msg.setMessage("You have completed 12 hard Treasure Trails.");
        p.onChatMessage(msg);
        assertTrue(((java.util.List<?>) p.progressBlock().get("recent_signals")).isEmpty());
        msg.setType(ChatMessageType.GAMEMESSAGE); p.onChatMessage(msg);
        assertEquals(1, ((java.util.List<?>) p.progressBlock().get("recent_signals")).size());
    }
    @Test
    public void realSnapshotKeepsSchemaOneAndChangesHashOnlyForNewProgress() throws Exception
    {
        AccountConnectPlugin p = plugin();
        inject(p, "gson", new com.google.gson.Gson());
        java.lang.reflect.Method build = AccountConnectPlugin.class.getDeclaredMethod("buildSnapshot");
        build.setAccessible(true);
        Map<String, Object> ungranted = (Map<String, Object>) build.invoke(p);
        assertEquals(1, ungranted.get("schema_v"));
        assertFalse(ungranted.containsKey("account_progress"));
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        build.invoke(p); tick += 2;
        Map<String, Object> a = (Map<String, Object>) build.invoke(p);
        assertTrue(a.containsKey("account_progress"));
        String hash = p.canonicalHash(a);
        tick++;
        assertEquals(hash, p.canonicalHash((Map<String, Object>) build.invoke(p)));
        vars[394] = 17;
        assertNotEquals(hash, p.canonicalHash((Map<String, Object>) build.invoke(p)));
    }

    @Test
    public void revokedGrantIsStrippedFromActualCachedUploadRequest() throws Exception
    {
        AccountConnectPlugin p = plugin();
        inject(p, "gson", new com.google.gson.Gson());
        OkHttpClient http = mock(OkHttpClient.class);
        when(http.newCall(any(Request.class))).thenReturn(mock(Call.class));
        inject(p, "okHttpClient", http);
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        p.progressBlock(); tick += 2;
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("schema_v", 1);
        snapshot.put("account_progress", p.progressBlock());
        java.lang.reflect.Method send = AccountConnectPlugin.class.getDeclaredMethod("postSnapshot", String.class, Map.class, String.class);
        send.setAccessible(true);
        send.invoke(p, TOKEN, snapshot, "hash");
        p.applyServerPolicy(policy("X-Account-Progress", "off"), TOKEN);
        send.invoke(p, TOKEN, snapshot, "hash");
        org.mockito.ArgumentCaptor<Request> requests = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(http, times(2)).newCall(requests.capture());
        okio.Buffer allowed = new okio.Buffer(); requests.getAllValues().get(0).body().writeTo(allowed);
        okio.Buffer revoked = new okio.Buffer(); requests.getAllValues().get(1).body().writeTo(revoked);
        assertTrue(allowed.readUtf8().contains("account_progress"));
        assertFalse(revoked.readUtf8().contains("account_progress"));
        assertTrue("do not mutate the logout cache", snapshot.containsKey("account_progress"));
    }

    @Test
    public void realScriptHandlerCapturesQuantityAndGameStateClearsIt() throws Exception
    {
        AccountConnectPlugin p = plugin();
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        p.progressBlock(); tick += 2;
        ScriptEvent script = mock(ScriptEvent.class);
        when(script.getArguments()).thenReturn(new Object[]{4100, 4151, 7});
        ScriptPreFired event = new ScriptPreFired(4100); event.setScriptEvent(script);
        p.onScriptPreFired(event);
        Map<?, ?> log = (Map<?, ?>) p.progressBlock().get("collection_log");
        assertEquals(7, ((Map<?, ?>) log.get("items")).get(4151));
        GameStateChanged hop = new GameStateChanged(); hop.setGameState(GameState.HOPPING);
        p.onGameStateChanged(hop);
        assertNull(p.progressBlock()); tick += 2;
        log = (Map<?, ?>) p.progressBlock().get("collection_log");
        assertNull(log.get("observed_at"));
    }

    @Test
    public void zeroCollectionTotalIsUnknownButKnownZeroObtainedIsValid()
    {
        assertNull(block("collection_log").get("game_obtained_count"));
        vars[2944] = 1500;
        assertEquals(0, block("collection_log").get("game_obtained_count"));
        vars[2943] = 1501;
        assertNull(block("collection_log").get("game_obtained_count"));
    }

    @Test
    public void signedAccountHashIsValidExceptUnknownSentinel()
    {
        when(client.getAccountHash()).thenReturn(-12345L);
        settle();
    }

    @Test
    public void regionLoadingRetainsLastSeenButDisconnectDiscardsIt()
    {
        settle();
        capture.chat("You have completed 12 hard Treasure Trails.", 100);
        when(client.getGameState()).thenReturn(GameState.LOADING);
        assertFalse(capture.ready(client, TOKEN));
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        assertTrue(capture.ready(client, TOKEN));
        assertEquals(1, ((java.util.List<?>) capture.snapshot(client).get("recent_signals")).size());
        when(client.getGameState()).thenReturn(GameState.CONNECTION_LOST);
        assertFalse(capture.ready(client, TOKEN));
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        settle();
        assertTrue(((java.util.List<?>) capture.snapshot(client).get("recent_signals")).isEmpty());
    }

    @Test
    public void shutdownDiscardsObservationsAcrossMissedSessionTransitions() throws Exception
    {
        AccountConnectPlugin p = plugin();
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        p.progressBlock(); tick += 2;
        assertNotNull(p.progressBlock());
        ChatMessage msg = new ChatMessage(); msg.setType(ChatMessageType.GAMEMESSAGE);
        msg.setMessage("You have completed 12 hard Treasure Trails.");
        p.onChatMessage(msg);
        assertEquals(1, ((java.util.List<?>) p.progressBlock().get("recent_signals")).size());
        p.shutDown();
        // No game-state callbacks arrive while disabled, including a relog to the same account.
        assertNull(p.progressBlock()); tick += 2;
        assertTrue(((java.util.List<?>) p.progressBlock().get("recent_signals")).isEmpty());
    }

    @Test
    public void failedResponsesCannotGrantAndForbiddenRevokes() throws Exception
    {
        AccountConnectPlugin p = plugin();
        p.applyServerPolicy(policy("X-Account-Progress", "on").newBuilder().code(500).build(), TOKEN);
        assertFalse(p.progressGranted());
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        assertTrue(p.progressGranted());
        p.applyServerPolicy(policy("X-Sync-Interval", "30").newBuilder().code(403).build(), TOKEN);
        assertFalse(p.progressGranted());
        assertNull(p.progressBlock());
    }


    private void searchable(int obtained)
    {
        vars[2943] = obtained; vars[2944] = 1600;
        when(client.getWidget(net.runelite.api.gameval.InterfaceID.Collection.SEARCH_TOGGLE))
            .thenReturn(mock(net.runelite.api.widgets.Widget.class));
    }

    @Test public void ownScanNeedsDistinctCountQuietPeriodAndStableTotals()
    {
        searchable(2);
        capture.collectionSetup(client, 1000);
        capture.collectionSlot(client, new Object[]{4100, 4151, 3}, 1);
        capture.collectionSlot(client, new Object[]{4100, 4151, 3}, 1);
        tick = 3; capture.poll(client, 4000);
        assertEquals(false, block("collection_log").get("complete"));
        capture.collectionSlot(client, new Object[]{4100, 11832, 1}, 4);
        tick = 5; capture.poll(client, 6000);
        assertEquals(false, block("collection_log").get("complete"));
        tick = 6; capture.poll(client, 7000);
        assertEquals(true, block("collection_log").get("complete"));
        assertEquals(7000L, block("collection_log").get("scan_verified_at_ms"));
        vars[2943] = 3; capture.poll(client, 8000);
        vars[2943] = 2;
        assertEquals(false, block("collection_log").get("complete"));
    }

    @Test public void scanKnownZeroAndExplicitZeroSlotsDifferFromUnknown()
    {
        searchable(0);
        capture.collectionSetup(client, 1000);
        capture.collectionSlot(client, new Object[]{4100, 4151, 0}, 1);
        tick = 3; capture.poll(client, 4000);
        assertEquals(true, block("collection_log").get("complete"));
        assertEquals(0, ((Map<?, ?>) block("collection_log").get("items")).get(4151));
        capture.reset(); vars[2944] = 0;
        capture.collectionSetup(client, 5000);
        tick = 6; capture.poll(client, 7000);
        assertEquals(false, block("collection_log").get("complete"));
    }

    @Test public void timeoutCloseAndExceptionsNeverClaimComplete()
    {
        searchable(1); capture.collectionSetup(client, 1000);
        tick = 21; capture.poll(client, 22000);
        assertEquals("timeout", block("collection_log").get("scan_status"));
        assertEquals(false, block("collection_log").get("complete"));
        capture.reset(); capture.collectionSetup(client, 23000); capture.collectionClosed();
        capture.collectionSlot(client, new Object[]{4100, 4151, 1}, 24);
        tick = 25; capture.poll(client, 26000);
        assertEquals(false, block("collection_log").get("complete"));
        capture.reset();
        doThrow(new IllegalStateException("unsupported script")).when(client).runScript(2240);
        capture.collectionSetup(client, 27000);
        assertEquals("unsupported_client", block("collection_log").get("scan_status"));
    }

    @Test public void scanOnlyOncePerOpenWithCooldownAndRejectsHostOrUnknown()
    {
        searchable(1);
        vars[2049] = 1 << 11; capture.collectionSetup(client, 1);
        verify(client, never()).runScript(anyInt());
        vars[2049] = 0;
        capture.collectionSetup(client, 2); capture.collectionSetup(client, 3);
        capture.collectionClosed(); tick = 99; capture.collectionSetup(client, 4);
        verify(client, times(1)).runScript(2240);
        tick = 100; capture.collectionSetup(client, 5);
        verify(client, times(2)).runScript(2240);
        capture.reset(); when(client.getVarbit(12061)).thenReturn(null);
        capture.collectionSetup(client, 6);
        verify(client, times(2)).runScript(2240);
    }

    @Test public void realSetupHandlerIsGrantGatedAndHostSlotsCannotReachLegacy() throws Exception
    {
        AccountConnectPlugin p = plugin(); searchable(1);
        ScriptPostFired setup = new ScriptPostFired(7797);
        p.onScriptPostFired(setup);
        verify(client, never()).runScript(2240);
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        p.progressBlock(); tick += 2;
        p.onScriptPostFired(setup);
        verify(client).menuAction(-1, net.runelite.api.gameval.InterfaceID.Collection.SEARCH_TOGGLE,
            MenuAction.CC_OP, 1, -1, "Search", null);
        verify(client).runScript(2240);
        when(client.getVarbit(12061)).thenReturn(null);
        ScriptEvent script = mock(ScriptEvent.class);
        when(script.getArguments()).thenReturn(new Object[]{4100, 4151, 7});
        ScriptPreFired slot = new ScriptPreFired(4100); slot.setScriptEvent(script);
        p.onScriptPreFired(slot);
        Field legacy = AccountConnectPlugin.class.getDeclaredField("clogObtained"); legacy.setAccessible(true);
        assertTrue(((java.util.Set<?>) legacy.get(p)).isEmpty());
        assertTrue(((Map<?, ?>) ((Map<?, ?>) p.progressBlock().get("collection_log")).get("items")).isEmpty());
    }

    @Test public void tickHandlerHistoryIsGatedAndCannotCrossAccountOrMode() throws Exception
    {
        AccountConnectPlugin p = plugin();
        net.runelite.api.widgets.Widget list = mock(net.runelite.api.widgets.Widget.class);
        net.runelite.api.widgets.Widget state = mock(net.runelite.api.widgets.Widget.class);
        net.runelite.api.widgets.Widget item = mock(net.runelite.api.widgets.Widget.class);
        net.runelite.api.widgets.Widget price = mock(net.runelite.api.widgets.Widget.class);
        when(state.getText()).thenReturn("Bought"); when(item.getItemId()).thenReturn(4151);
        when(item.getItemQuantity()).thenReturn(2); when(price.getText()).thenReturn("100 coins");
        when(list.getDynamicChildren()).thenReturn(new net.runelite.api.widgets.Widget[]{null, null, state, null, item, price});
        when(client.getWidget(383, 3)).thenReturn(list);
        p.onGameTick(new GameTick());
        verify(client, never()).getWidget(383, 3);
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        p.onGameTick(new GameTick()); tick += 2;
        p.onGameTick(new GameTick()); tick++;
        p.onGameTick(new GameTick());
        Map<?, ?> history = (Map<?, ?>) p.progressBlock().get("ge_history");
        assertEquals(1, ((java.util.List<?>) history.get("observations")).size());
        String collector = (String) history.get("collector_session");
        when(client.getAccountHash()).thenReturn(43L);
        p.onGameTick(new GameTick()); tick += 2;
        history = (Map<?, ?>) p.progressBlock().get("ge_history");
        assertTrue(((java.util.List<?>) history.get("observations")).isEmpty());
        assertNotEquals(collector, history.get("collector_session"));
        when(client.getWorldType()).thenReturn(java.util.EnumSet.of(WorldType.SEASONAL));
        assertNull(p.progressBlock()); tick += 2;
        assertTrue(((java.util.List<?>) ((Map<?, ?>) p.progressBlock().get("ge_history")).get("observations")).isEmpty());
        p.applyServerPolicy(policy("X-Account-Progress", "off"), TOKEN);
        assertNull(p.progressBlock());
    }

    @Test public void automaticZeroTransmissionNeverPollutesLegacyObtainedSet() throws Exception
    {
        AccountConnectPlugin p = plugin(); searchable(0);
        p.applyServerPolicy(policy("X-Account-Progress", "on"), TOKEN);
        p.progressBlock(); tick += 2;
        p.onScriptPostFired(new ScriptPostFired(7797));
        ScriptEvent script = mock(ScriptEvent.class);
        when(script.getArguments()).thenReturn(new Object[]{4100, 4151, 0});
        ScriptPreFired slot = new ScriptPreFired(4100); slot.setScriptEvent(script);
        p.onScriptPreFired(slot);
        Field legacy = AccountConnectPlugin.class.getDeclaredField("clogObtained"); legacy.setAccessible(true);
        assertTrue(((java.util.Set<?>) legacy.get(p)).isEmpty());
        Map<?, ?> log = (Map<?, ?>) p.progressBlock().get("collection_log");
        assertEquals(0, ((Map<?, ?>) log.get("items")).get(4151));
    }

    @Test public void changingCountsDuringScanCannotLaterResurrectCompleteness()
    {
        searchable(1); capture.collectionSetup(client, 1);
        capture.collectionSlot(client, new Object[]{4100, 4151, 1}, 1);
        tick = 1; vars[2943] = 2; capture.poll(client, 1000);
        tick = 4; vars[2943] = 1; capture.poll(client, 4000);
        assertEquals(false, block("collection_log").get("complete"));
        assertEquals("counts_changed", block("collection_log").get("scan_status"));
    }
}
