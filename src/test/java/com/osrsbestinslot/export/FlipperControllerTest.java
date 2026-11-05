package com.osrsbestinslot.export;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarPlayer;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FlipperControllerTest
{
    static final class Fixture implements AutoCloseable
    {
        final Client client = mock(Client.class);
        final AtomicReference<Boolean> enabled = new AtomicReference<>(true);
        final ConfigManager configuration = mock(ConfigManager.class);
        final List<Throwable> failures = new ArrayList<>();
        final EventBus bus = new EventBus(failures::add);
        final ClientThread thread = mock(ClientThread.class);
        final ClientToolbar toolbar = mock(ClientToolbar.class);
        final OverlayManager manager = mock(OverlayManager.class);
        final List<Overlay> overlays = new ArrayList<>();
        final Widget setup = mock(Widget.class);
        final Widget details = mock(Widget.class);
        final Widget frame = mock(Widget.class);
        final Widget item = mock(Widget.class);
        final Widget quantity = mock(Widget.class);
        final Widget price = mock(Widget.class);
        final Widget total = mock(Widget.class);
        final GrandExchangeOffer[] offers = new GrandExchangeOffer[8];
        final AccountConnectPlugin owner = new AccountConnectPlugin();
        final FlipperController controller;

        Fixture() throws Exception { this(true); }

        Fixture(Boolean initialSetting) throws Exception
        {
            enabled.set(initialSetting);
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getAccountHash()).thenReturn(10L);
            when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
            when(client.getCanvasHeight()).thenReturn(503);
            when(client.getCanvasWidth()).thenReturn(765);
            when(client.getGrandExchangeOffers()).thenReturn(offers);
            for (int i = 0; i < offers.length; i++)
            {
                offers[i] = FlipperBuyHistoryTest.offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
            }
            when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(setup);
            when(client.getWidget(InterfaceID.GeOffers.DETAILS)).thenReturn(details);
            when(client.getWidget(InterfaceID.GeOffers.FRAME)).thenReturn(frame);
            when(frame.getBounds()).thenReturn(new Rectangle(8, 2, 510, 334));
            when(details.isHidden()).thenReturn(true);
            when(client.getVarbitValue(VarbitID.GE_SELECTEDSLOT)).thenReturn(1);
            when(client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE)).thenReturn(1);
            when(setup.getChild(23)).thenReturn(item);
            when(setup.getChild(34)).thenReturn(quantity);
            when(setup.getChild(41)).thenReturn(price);
            when(setup.getChild(45)).thenReturn(total);
            when(details.getChild(7)).thenReturn(item);
            when(details.getChild(18)).thenReturn(quantity);
            when(details.getChild(25)).thenReturn(price);
            when(details.getChild(29)).thenReturn(total);
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(thread).invoke(any(Runnable.class));
            doAnswer(call -> { overlays.add(call.getArgument(0)); return null; })
                .when(manager).add(any(Overlay.class));
            doAnswer(call -> { overlays.remove(call.getArgument(0)); return null; })
                .when(manager).remove(any(Overlay.class));
            when(configuration.getConfiguration(AccountConnectPlugin.CONFIG_GROUP, "flipper", Boolean.class))
                .thenAnswer(call -> enabled.get());
            doAnswer(call -> { enabled.set(call.getArgument(2)); return null; })
                .when(configuration).setConfiguration(eq(AccountConnectPlugin.CONFIG_GROUP), eq("flipper"), any(Boolean.class));
            controller = new FlipperController(client, configuration, thread, toolbar, manager, bus);
            draft(100, 100, 10_500, 20, "1,029,000 coins (1,050,000 - 2%)");
            controller.startUp(owner);
            SwingUtilities.invokeAndWait(() -> {});
        }

        void draft(int id, int count, long sell, int rate, String nativeTotal)
        {
            when(client.getVarpValue(VarPlayer.CURRENT_GE_ITEM)).thenReturn(id);
            when(client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY)).thenReturn(count);
            when(client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE)).thenReturn(sell);
            when(client.getVarbitValue(VarbitID.GE_TRANSMIT_TAXRATE)).thenReturn(rate);
            when(item.getItemId()).thenReturn(id);
            when(quantity.getText()).thenReturn(Integer.toString(count));
            when(price.getText()).thenReturn(FlipperView.number(sell) + " coins");
            when(total.getText()).thenReturn(nativeTotal);
        }

        void tick()
        {
            bus.post(new PostClientTick());
            assertTrue(failures.toString(), failures.isEmpty());
        }

        void cost(String value)
        {
            controller.changeCost(controller.view().key, value);
        }

        void state(GameState state)
        {
            when(client.getGameState()).thenReturn(state);
            GameStateChanged event = new GameStateChanged();
            event.setGameState(state);
            bus.post(event);
        }

        void offer(int slot, GrandExchangeOffer value)
        {
            offers[slot] = value;
            GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
            event.setSlot(slot);
            event.setOffer(value);
            bus.post(event);
        }

        @Override
        public void close() throws Exception
        {
            controller.shutDown();
            SwingUtilities.invokeAndWait(() -> {});
            assertTrue(failures.toString(), failures.isEmpty());
        }
    }

    @Test
    public void realEventBusReadsNativeOfferAndManualCostWithoutLinkToken() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.cost("10,000");
            f.tick();
            assertEquals(0, new BigDecimal("29000").compareTo(f.controller.view().result.profit));
            assertEquals("Manual purchase cost", f.controller.view().costSource);
            assertEquals(1, f.overlays.size());
            assertNotNull(f.overlays.get(0).render(new BufferedImage(600, 500, 2).createGraphics()));
        }
    }

    @Test
    public void staleTaxItemQuantityAndNativeTotalFailClosed() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.cost("10000");
            when(f.client.getVarbitValue(VarbitID.GE_TRANSMIT_TAXRATE)).thenReturn(0);
            f.tick();
            assertNull(f.controller.view().result);
            when(f.total.getText()).thenReturn("1,050,000 coins");
            f.tick();
            assertEquals(0, new BigDecimal("50000").compareTo(f.controller.view().result.profit));
            when(f.item.getItemId()).thenReturn(999);
            f.tick();
            assertNull(f.controller.view().result);
            when(f.item.getItemId()).thenReturn(100);
            when(f.quantity.getText()).thenReturn("99");
            f.tick();
            assertNull(f.controller.view().result);
            when(f.quantity.getText()).thenReturn("100");
            when(f.total.getText()).thenReturn("Too much money!");
            f.tick();
            assertNull(f.controller.view().result);
        }
    }

    @Test
    public void nativeTypingHidesOverlayAndReturnsOnlyAfterCoherentPrice() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.cost("10000");
            when(f.client.getVarcIntValue(VarClientInt.INPUT_TYPE)).thenReturn(7);
            f.tick();
            assertNull(f.controller.view().result);
            assertNull(f.overlays.get(0).render(new BufferedImage(600, 500, 2).createGraphics()));
            when(f.client.getVarcIntValue(VarClientInt.INPUT_TYPE)).thenReturn(0);
            f.draft(100, 100, 10_600, 20, "1,038,800 coins (1,060,000 - 2%)");
            f.tick();
            assertEquals(0, new BigDecimal("38800").compareTo(f.controller.view().result.profit));
        }
    }

    @Test
    public void remainingEstimateExcludesSoldQuantity() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.draft(100, 10_000, 180, 20, "1,770,000 coins (1,800,000 - 2%)");
            when(f.setup.isHidden()).thenReturn(true);
            when(f.details.isHidden()).thenReturn(false);
            f.offers[0] = FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING, 100, 10_000, 4_000,
                180, 720_000);
            f.tick();
            f.cost("170");
            assertEquals(6_000, f.controller.view().quantity);
            assertEquals(4_000, f.controller.view().sold);
            assertEquals(0, new BigDecimal("42000").compareTo(f.controller.view().result.profit));
            f.offers[0] = FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SOLD, 100, 10_000, 10_000,
                180, 1_800_000);
            f.tick();
            assertNull(f.controller.view().result);
            assertTrue(f.controller.view().status.contains("Completed profit is not calculated"));
        }
    }

    @Test
    public void itemAccountAndSessionChangesDiscardManualCostAndIgnoreLateEdits() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.cost("10000");
            long oldKey = f.controller.view().key;
            f.draft(101, 100, 10_500, 20, "1,029,000 coins (1,050,000 - 2%)");
            f.tick();
            f.controller.changeCost(oldKey, "1");
            assertNull(f.controller.view().result.profit);
            f.cost("10000");
            when(f.client.getAccountHash()).thenReturn(11L);
            f.tick();
            assertNull(f.controller.view().result.profit);
            for (GameState state : new GameState[]{GameState.HOPPING, GameState.CONNECTION_LOST,
                GameState.LOGIN_SCREEN, GameState.LOGGING_IN})
            {
                f.cost("10000");
                f.state(state);
                assertNull(f.controller.view().result);
                f.state(GameState.LOGGED_IN);
                f.tick();
                assertNull(f.controller.view().result.profit);
            }
        }
    }

    @Test
    public void recordedPurchaseFlowsThroughEventsAndDisableClearsIt() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.offer(1, FlipperBuyHistoryTest.offer(GrandExchangeOfferState.BOUGHT, 100, 100, 100, 10_000,
                1_000_000));
            f.tick();
            assertEquals("Recorded buy average", f.controller.view().costSource);
            assertEquals(0, new BigDecimal("29000").compareTo(f.controller.view().result.profit));
            f.enabled.set(false);
            f.tick();
            assertFalse(f.controller.view().enabled);
            assertNull(f.controller.view().result);
            f.enabled.set(true);
            f.tick();
            assertNull(f.controller.view().result.profit);
        }
    }

    @Test
    public void malformedManualCostCannotFallBackToAnApparentlyCertainProfit() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.cost("10000");
            for (String invalid : new String[]{"-1", "1.5", "1,0", "oops", "9223372036854775808"})
            {
                f.cost(invalid);
                assertNull(invalid, f.controller.view().result.profit);
            }
            f.cost("0");
            assertEquals(0, new BigDecimal("1029000").compareTo(f.controller.view().result.profit));
        }
    }

    @Test
    public void manualDraftCostSurvivesMatchingConfirmationButNotSlotReuse() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.cost("10000");
            f.offer(0, FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING, 100, 100, 0, 10_500, 0));
            when(f.setup.isHidden()).thenReturn(true);
            when(f.details.isHidden()).thenReturn(true);
            f.tick();
            when(f.details.isHidden()).thenReturn(false);
            f.tick();
            assertEquals(0, new BigDecimal("29000").compareTo(f.controller.view().result.profit));
            f.offer(0, FlipperBuyHistoryTest.offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
            f.offer(0, FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING, 100, 100, 0, 10_500, 0));
            f.tick();
            assertNull(f.controller.view().result.profit);
        }
    }

    @Test
    public void queuedManualInputCannotBindToAnotherSlotBeforeTheNextTick() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            when(f.setup.isHidden()).thenReturn(true);
            when(f.details.isHidden()).thenReturn(false);
            f.offers[0] = FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING, 100, 100, 0, 10_500, 0);
            f.tick();
            long oldKey = f.controller.view().key;
            when(f.client.getVarbitValue(VarbitID.GE_SELECTEDSLOT)).thenReturn(2);
            f.offers[1] = FlipperBuyHistoryTest.offer(GrandExchangeOfferState.SELLING, 101, 100, 0, 10_500, 0);
            f.draft(101, 100, 10_500, 20, "1,029,000 coins (1,050,000 - 2%)");
            f.controller.changeCost(oldKey, "1");
            assertEquals(101, f.controller.view().item);
            assertNull(f.controller.view().result.profit);
            assertEquals("", f.controller.view().manualInput);
        }
    }

    @Test
    public void nativeQuantityOnlyAndPriceOnlyChangesKeepTheManualBasis() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.cost("10000");
            when(f.client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY)).thenReturn(50);
            f.tick();
            assertNull(f.controller.view().result);
            f.draft(100, 50, 10_600, 20, "519,400 coins (530,000 - 2%)");
            f.tick();
            assertEquals(0, new BigDecimal("19400").compareTo(f.controller.view().result.profit));
        }
    }

    @Test
    public void stopRemovesNavigationOverlayAndEventSubscribers() throws Exception
    {
        Fixture f = new Fixture();
        f.cost("10000");
        f.close();
        assertTrue(f.overlays.isEmpty());
        verify(f.toolbar).removeNavigation(any());
        f.bus.post(new PostClientTick());
        assertNull(f.controller.view().result);
    }
    @Test
    public void rapidRestartLeavesExactlyOneSidebarAndFreshSessionState() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.cost("10000");
            SwingUtilities.invokeAndWait(() ->
            {
                f.controller.shutDown();
                f.controller.startUp(f.owner);
                f.controller.startUp(f.owner); // Duplicate lifecycle delivery is idempotent.
            });
            SwingUtilities.invokeAndWait(() -> {});
            f.tick();
            assertEquals(1, f.overlays.size());
            assertEquals("", f.controller.view().manualInput);
            assertNull(f.controller.view().result.profit);
            verify(f.toolbar, times(2)).addNavigation(any());
            verify(f.toolbar, times(1)).removeNavigation(any());
        }
    }

    @Test
    public void lateLoadedItemNameRefreshesWithoutChangingTheOffer() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            assertEquals("Item 100", f.controller.view().itemName);
            net.runelite.api.ItemComposition definition = mock(net.runelite.api.ItemComposition.class);
            when(definition.getName()).thenReturn("Example item");
            when(f.client.getItemDefinition(100)).thenReturn(definition);
            f.tick();
            assertEquals("Example item", f.controller.view().itemName);
        }
    }

    @Test
    public void automaticOverlayAnchorFitsTheAvailableCanvas() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            when(f.frame.getBounds()).thenReturn(new Rectangle(600, 2, 510, 334));
            f.tick();
            assertNotNull(f.controller.view().anchor);
            assertTrue(f.controller.view().anchor.x + FlipperOverlay.WIDTH <= f.client.getCanvasWidth());
            when(f.client.getCanvasWidth()).thenReturn(200);
            f.tick();
            assertNull(f.controller.view().anchor);
        }
    }

    private static javax.swing.JCheckBox sidebarToggle(FlipperController controller) throws Exception
    {
        java.lang.reflect.Field panel = FlipperController.class.getDeclaredField("panel");
        panel.setAccessible(true);
        for (java.awt.Component child : ((FlipperPanel) panel.get(controller)).getComponents())
            if (child instanceof javax.swing.JCheckBox) return (javax.swing.JCheckBox) child;
        throw new AssertionError("Missing sidebar toggle");
    }

    @Test
    public void absentLocalToggleDefaultsOffWithoutConfigItem() throws Exception
    {
        try (Fixture f = new Fixture(null))
        {
            f.tick();
            assertFalse(f.controller.view().enabled);
            assertFalse(f.controller.showOverlay());
            assertFalse(sidebarToggle(f.controller).isSelected());
            assertEquals(0L, mockingDetails(f.configuration).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("setConfiguration")).count());
        }
    }

    @Test
    public void sidebarTogglePersistsAcrossRestartAndOnlyToggleIsStored() throws Exception
    {
        try (Fixture f = new Fixture(null))
        {
            SwingUtilities.invokeAndWait(() ->
            {
                try { sidebarToggle(f.controller).doClick(); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            f.tick();
            assertTrue(f.controller.view().enabled);
            assertEquals(Boolean.TRUE, f.enabled.get());
            f.cost("10000");
            f.controller.shutDown();f.controller.startUp(f.owner);
            SwingUtilities.invokeAndWait(() -> {});f.tick();
            assertTrue(f.controller.view().enabled);
            assertEquals("", f.controller.view().manualInput);
            assertNull(f.controller.view().result.profit);
            SwingUtilities.invokeAndWait(() ->
            {
                try { sidebarToggle(f.controller).doClick(); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertFalse(f.controller.showOverlay());
            assertEquals(Boolean.FALSE, f.enabled.get());
            f.controller.shutDown();f.controller.startUp(f.owner);
            SwingUtilities.invokeAndWait(() -> {});f.tick();
            assertFalse(f.controller.view().enabled);
            verify(f.configuration).setConfiguration(AccountConnectPlugin.CONFIG_GROUP, "flipper", true);
            verify(f.configuration).setConfiguration(AccountConnectPlugin.CONFIG_GROUP, "flipper", false);
            assertEquals(2L, mockingDetails(f.configuration).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("setConfiguration")).count());
        }
    }

    @Test
    public void oldQueuedCostEditCannotSurviveControllerRestart() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            List<Runnable> queued = new ArrayList<>();
            doAnswer(call -> { queued.add(call.getArgument(0)); return null; }).when(f.thread).invoke(any(Runnable.class));
            f.controller.changeCost(f.controller.view().key, "12345");
            f.controller.shutDown();f.controller.startUp(f.owner);
            for (Runnable task : new ArrayList<>(queued)) task.run();
            f.tick();
            assertTrue(f.controller.view().enabled);
            assertEquals("", f.controller.view().manualInput);
            assertNull(f.controller.view().result.profit);
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(f.thread).invoke(any(Runnable.class));
        }
    }

}
