package com.osrsbestinslot.export;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Staff-visible outcomes driven through RuneLite's real registration and event dispatch. */
public class StoreWorkflowRegressionTest
{
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String ACCOUNT = "12345";
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final AccountConnectPlugin plugin = new AccountConnectPlugin();
    private final Client client = mock(Client.class);
    private final OverlayManager manager = mock(OverlayManager.class);
    private final List<Overlay> registered = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final EventBus bus = new EventBus(failures::add);
    private String token = TOKEN;
    private String oldHome;
    private Graphics2D graphics;
    private StoreNearbyOverlay nearby;
    private StoreResetOverlay reset;
    private Player self;

    private static void inject(Object target, String field, Object value) throws Exception
    {
        Field f = AccountConnectPlugin.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Before public void start() throws Exception
    {
        oldHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.getRoot().getAbsolutePath());
        graphics = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB).createGraphics();
        inject(plugin, "config", new AccountConnectConfig()
        {
            @Override public String linkToken() { return token; }
            @Override public String apiBaseUrl() { return "http://localhost/never-networked"; }
        });
        inject(plugin, "client", client);
        inject(plugin, "gson", new Gson());
        inject(plugin, "overlayManager", manager);
        inject(plugin, "drawManager", mock(net.runelite.client.ui.DrawManager.class));
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getTickCount()).thenReturn(100);
        when(client.getWorld()).thenReturn(301);
        when(client.getWorldType()).thenReturn(java.util.EnumSet.noneOf(net.runelite.api.WorldType.class));
        inject(plugin, "itemManager", mock(net.runelite.client.game.ItemManager.class));
        // Keep network outside this UI workflow; production handlers and snapshot construction still run.
        okhttp3.OkHttpClient http = mock(okhttp3.OkHttpClient.class);
        okhttp3.Call httpCall = mock(okhttp3.Call.class);
        when(http.newCall(any(Request.class))).thenReturn(httpCall);
        inject(plugin, "okHttpClient", http);
        self = player("Self", 3210);
        when(client.getLocalPlayer()).thenReturn(self);
        when(client.getPlayers()).thenReturn(Collections.singletonList(self));
        ItemContainer inventory = mock(ItemContainer.class);
        when(inventory.getItems()).thenReturn(new Item[]{new Item(995, 1000), new Item(1191, 2), new Item(999, 1)});
        when(client.getItemContainer(net.runelite.api.InventoryID.INVENTORY)).thenReturn(inventory);
        doAnswer(call -> { registered.add(call.getArgument(0)); return true; }).when(manager).add(any(Overlay.class));
        doAnswer(call -> { registered.remove(call.getArgument(0)); return true; }).when(manager).remove(any(Overlay.class));
        inject(plugin, "activeHash", ACCOUNT);
        when(client.getAccountHash()).thenReturn(Long.parseLong(ACCOUNT));
        plugin.staffApiBase = "http://localhost/never-networked";	// this rig's stand-in for osrsbestinslot.com
        bus.register(plugin);
        plugin.startUp();
        captureRegisteredOverlays();
    }

    private void captureRegisteredOverlays()
    {
        assertEquals("startup must register both visible staff tools", 2, registered.size());
        nearby = (StoreNearbyOverlay) registered.stream().filter(o -> o instanceof StoreNearbyOverlay).findFirst().orElse(null);
        reset = (StoreResetOverlay) registered.stream().filter(o -> o instanceof StoreResetOverlay).findFirst().orElse(null);
        assertNotNull("nearby overlay missing from OverlayManager", nearby);
        assertNotNull("countdown missing from OverlayManager", reset);
    }

    @After public void stop()
    {
        try
        {
            bus.unregister(plugin);
            plugin.pendingEvents.clear();
            plugin.shutDown();
            assertTrue("shutdown left staff overlays registered", registered.isEmpty());
            if (!failures.isEmpty()) throw new AssertionError("RuneLite subscriber threw", failures.get(0));
        }
        finally
        {
            if (graphics != null) graphics.dispose();
            if (oldHome != null) System.setProperty("user.home", oldHome);
        }
    }

    private static Player player(String name, int x)
    {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        when(p.getWorldLocation()).thenReturn(new WorldPoint(x, 3210, 0));
        when(p.getCombatLevel()).thenReturn(80);
        return p;
    }

    private void post(Object event)
    {
        bus.post(event);
        if (!failures.isEmpty()) throw new AssertionError("RuneLite subscriber threw", failures.get(0));
    }

    private void policy(String value, String sentToken)
    {
        Response response = new Response.Builder().request(new Request.Builder().url("http://localhost/never-networked/account-ingest").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("X-Store-Tools", value)
            // The server names a staff account only for an approved staff token; "off" withdraws both.
            .header("X-Staff-Capture", value).header("X-Staff-Account", "on".equals(value) ? ACCOUNT : "").build();
        plugin.applyServerPolicy(response, sentToken);
    }

    private void open()
    {
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        WidgetLoaded event = new WidgetLoaded();
        event.setGroupId(InterfaceID.SHOPMAIN);
        post(event);
    }

    private void sell(int itemId)
    {
        net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
        when(entry.getOption()).thenReturn("Sell 1<col=ff9040>");
        when(entry.getItemId()).thenReturn(itemId);
        // EventBus dispatches by exact class: post a real event, not a Mockito subclass.
        post(new MenuOptionClicked(entry));
    }

    private void stock(Item... items)
    {
        ItemContainer container = mock(ItemContainer.class);
        when(container.getItems()).thenReturn(items);
        post(new ItemContainerChanged(InventoryID.GENERALSHOP1, container));
    }

    private void assertHidden()
    {
        assertNull("nearby panel visible outside an active granted visit", nearby.render(graphics));
        assertNull("countdown visible outside an active granted visit", reset.render(graphics));
    }

    private void assertUnknown()
    {
        assertNotNull(reset.render(graphics));
        assertEquals("new visit must require a fresh probe", "sell junk", reset.lastTextForTest());
    }

    private void observeProbeReset()
    {
        sell(1191);
        stock(new Item(1191, 1));
        stock();
        assertNotNull("observed stock reset must render a countdown", reset.render(graphics));
        String text = reset.lastTextForTest();
        assertTrue("actual sell/container events never reached the visible timer: " + text, text.matches("[0-9]+s"));
        int seconds = Integer.parseInt(text.substring(0, text.length() - 1));
        assertTrue("countdown outside the store's 60-second cycle", seconds >= 1 && seconds <= 60);
    }

    @Test public void startupPolicyAndShopEventsReachLiveNearbyRowsAndCloseBothPanels()
    {
        assertHidden();
        open();
        assertHidden();
        policy("on", token);
        Player visitor = player("Visitor", 3211);
        when(client.getPlayers()).thenReturn(Arrays.asList(self, visitor));
        assertNotNull(nearby.render(graphics));
        assertEquals("registered overlay must read actual Client players", 2, nearby.builtRowCountForTest());
        assertUnknown();
        when(client.getPlayers()).thenReturn(Collections.singletonList(self));
        assertNotNull(nearby.render(graphics));
        assertEquals("departed players must disappear on the next frame", 1, nearby.builtRowCountForTest());
        post(new WidgetClosed(InterfaceID.SHOPMAIN, 0, true));
        assertHidden();
    }

    @Test public void realSellAndStockEventsDriveCountdownWithoutReanchoringOnMerchandise() throws Exception
    {
        policy("on", token);
        open();
        assertUnknown();
        observeProbeReset();
        Field anchor = AccountConnectPlugin.class.getDeclaredField("storeResetAnchorMs");
        anchor.setAccessible(true);
        long observed = anchor.getLong(plugin);
        sell(999);
        stock(new Item(999, 1));
        stock();
        assertEquals("a customer's merchandise purchase must not reset the clock", observed, anchor.getLong(plugin));
        post(new WidgetClosed(InterfaceID.SHOPMAIN, 0, true));
        assertHidden();
        open();
        assertUnknown();
    }

    @Test public void sessionLossHidesPanelsEvenWhenRuneLiteNeverSendsWidgetClosed()
    {
        policy("on", token);
        for (GameState state : new GameState[]{GameState.HOPPING, GameState.LOGIN_SCREEN,
            GameState.CONNECTION_LOST, GameState.LOGGING_IN})
        {
            open();
            observeProbeReset();
            GameStateChanged event = new GameStateChanged();
            event.setGameState(state);
            when(client.getGameState()).thenReturn(state);
            post(event);
            assertHidden();
            open();
            assertUnknown();
        }
    }

    @Test public void revokedUnlinkedAndStalePolicyResponsesCannotShowPanels()
    {
        open();
        policy("on", token);
        assertNotNull(nearby.render(graphics));
        policy("off", token);
        assertHidden();
        token = "abcdef0123456789abcdef0123456789";
        policy("on", TOKEN);
        assertHidden();
        policy("on", token);
        assertNotNull(nearby.render(graphics));
        token = "";
        assertHidden();
    }

    @Test public void disableAndReenableRequiresANewShopVisit()
    {
        policy("on", token);
        open();
        observeProbeReset();
        plugin.pendingEvents.clear();
        plugin.shutDown();
        assertTrue("disable must remove both registered overlays", registered.isEmpty());
        plugin.startUp();
        captureRegisteredOverlays();
        assertHidden();
        open();
        assertUnknown();
    }
}
