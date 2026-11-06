package com.osrsbestinslot.export;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.ItemComposition;

/** Local-only, read-only GE calculator. All game and purchase state belongs to the client thread. */
final class FlipperController
{
    private static final Pattern COINS = Pattern.compile("^([0-9][0-9,]*) coins?(?:\\s.*)?$");
    private final Client client;
    private final ConfigManager configManager;
    private final ClientThread clientThread;
    private final ClientToolbar toolbar;
    private final OverlayManager overlays;
    private final EventBus eventBus;
    private final TooltipManager tooltips;
    private final FlipperOfferActivity offerActivity = new FlipperOfferActivity();
    private volatile FlipperOverviewOverlay.View overview;
    private FlipperOverviewOverlay overviewOverlay;
    private final FlipperBuyHistory history = new FlipperBuyHistory();
    private volatile FlipperView view = FlipperView.empty(false, 0, "Enable Flipper to calculate a sell offer.");
    private volatile boolean running;
    private volatile boolean enabled;
    private final AtomicLong lifecycle = new AtomicLong();
    private FlipperPanel panel;
    private NavigationButton navigation;
    private FlipperOverlay overlay;
    private String account;
    private String selection;
    private long selectionKey;
    private String manualInput = "";
    private final ManualOfferCost[] offerCosts = new ManualOfferCost[8];

    private static final class ManualOfferCost
    {
        final int item;
        final int quantity;
        final long price;
        final String input;
        int filled;
        GrandExchangeOfferState state;

        ManualOfferCost(GrandExchangeOffer offer, String input)
        {
            item = offer.getItemId();
            quantity = offer.getTotalQuantity();
            price = offer.getPrice();
            this.input = input;
            filled = offer.getQuantitySold();
            state = offer.getState();
        }

        boolean matches(GrandExchangeOffer offer)
        {
            return sell(offer) && item == offer.getItemId() && quantity == offer.getTotalQuantity()
                && price == offer.getPrice() && filled <= offer.getQuantitySold()
                && !(state != GrandExchangeOfferState.SELLING
                    && offer.getState() == GrandExchangeOfferState.SELLING);
        }
    }

    private static boolean sell(GrandExchangeOffer offer)
    {
        return offer != null && (offer.getState() == GrandExchangeOfferState.SELLING
            || offer.getState() == GrandExchangeOfferState.SOLD
            || offer.getState() == GrandExchangeOfferState.CANCELLED_SELL);
    }

    @Inject
    FlipperController(Client client, ConfigManager configManager,
        ClientThread clientThread, ClientToolbar toolbar, OverlayManager overlays, EventBus eventBus, TooltipManager tooltips)
    {
        this.client = client;
        this.configManager = configManager;
        this.clientThread = clientThread;
        this.toolbar = toolbar;
        this.overlays = overlays;
        this.eventBus = eventBus;
        this.tooltips = tooltips;
    }

    void startUp(Plugin plugin)
    {
        if (running) return;
        final long generation = lifecycle.incrementAndGet();
        running = true;
        enabled = false;
        view = FlipperView.empty(false, view.key, "Enable Flipper to calculate a sell offer.");
        eventBus.register(this);
        overlay = new FlipperOverlay(plugin, this);
        overlays.add(overlay);
        overviewOverlay = new FlipperOverviewOverlay(plugin, client, tooltips, () -> overview, this::showOverlay);
        overlays.add(overviewOverlay);
        SwingUtilities.invokeLater(() ->
        {
            if (!running || lifecycle.get() != generation)
            {
                return;
            }
            if (navigation != null) toolbar.removeNavigation(navigation);
            BufferedImage logo = logo();
            panel = new FlipperPanel(logo,
                value -> { if (lifecycle.get() == generation) changeToggle(value); },
                (key, value) -> { if (lifecycle.get() == generation) changeCost(key, value); });
            panel.showView(view);
            navigation = NavigationButton.builder().tooltip("OSRS Best in Slot").icon(resize(logo, 32, 17))
                .priority(7).panel(panel).build();
            toolbar.addNavigation(navigation);
        });
        clientThread.invoke(() ->
        {
            if (running && lifecycle.get() == generation)
            {
                resetSession();
                refresh();
            }
        });
    }

    void shutDown()
    {
        final long generation = lifecycle.incrementAndGet();
        running = false;
        enabled = false;
        eventBus.unregister(this);
        if (overlay != null)
        {
            overlays.remove(overlay);
            overlay = null;
        }
        overview = null;
        if (overviewOverlay != null)
        {
            overlays.remove(overviewOverlay);
            overviewOverlay = null;
        }
        view = FlipperView.empty(false, view.key, "Flipper is off.");
        clientThread.invoke(() -> { if (lifecycle.get() == generation) resetSession(); });
        SwingUtilities.invokeLater(() ->
        {
            if (lifecycle.get() != generation) return;
            if (navigation != null)
            {
                toolbar.removeNavigation(navigation);
                navigation = null;
            }
            panel = null;
        });
    }

    private boolean configuredEnabled()
    {
        return Boolean.TRUE.equals(configManager.getConfiguration(AccountConnectPlugin.CONFIG_GROUP,
            "flipper", Boolean.class));
    }

    private void changeToggle(boolean value)
    {
        if (!value)
        {
            enabled = false;
            view = FlipperView.empty(false, view.key, "Flipper is off.");
            clientThread.invoke(this::resetSession);
        }
        configManager.setConfiguration(AccountConnectPlugin.CONFIG_GROUP, "flipper", value);
        clientThread.invoke(this::refresh);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (AccountConnectPlugin.CONFIG_GROUP.equals(event.getGroup()) && "flipper".equals(event.getKey()))
        {
            if (!configuredEnabled())
            {
                enabled = false;
                view = FlipperView.empty(false, view.key, "Flipper is off.");
                clientThread.invoke(this::resetSession);
            }
            clientThread.invoke(this::refresh);
        }
    }

    @Subscribe
    public void onPostClientTick(PostClientTick event)
    {
        refresh();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() != GameState.LOGGED_IN)
        {
            overview = null;
            if (event.getGameState() != GameState.LOADING)
            {
                resetSession();
            }
            clearSelection();
            publish(FlipperView.empty(enabled, selectionKey, "Open a sell offer in the Grand Exchange."));
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        if (ready())
        {
            int slot = event.getSlot();
            GrandExchangeOffer offer = event.getOffer();
            history.observe(slot, offer, false);
            offerActivity.observe(slot, offer, activityNow());
            if (slot < 0 || slot >= offerCosts.length)
            {
                return;
            }
            ManualOfferCost bound = offerCosts[slot];
            if (bound != null)
            {
                if (!bound.matches(offer))
                {
                    offerCosts[slot] = null;
                    if (selection != null && selection.startsWith("offer:" + slot + ":"))
                    {
                        clearSelection();
                    }
                }
                else
                {
                    bound.filled = offer.getQuantitySold();
                    bound.state = offer.getState();
                }
            }
            FlipperView draft = view;
            if (sell(offer) && selection != null && selection.equals("draft:" + slot + ":" + draft.item)
                && draft.item == offer.getItemId() && draft.quantity == offer.getTotalQuantity()
                && draft.price == offer.getPrice() && !manualInput.isEmpty())
            {
                offerCosts[slot] = new ManualOfferCost(offer, manualInput);
            }
            if (offer != null && offer.getState() == GrandExchangeOfferState.EMPTY
                && selection != null && selection.startsWith("offer:" + slot + ":"))
            {
                clearSelection();
            }
        }
    }

    private boolean ready()
    {
        if (!running || !enabled || !configuredEnabled() || client.getGameState() != GameState.LOGGED_IN)
        {
            return false;
        }
        if (client.getAccountHash() == -1L)
        {
            resetSession();
            return false;
        }
        String current = client.getAccountHash() + "|" + RuneScapeProfileType.getCurrent(client).name();
        if (!Objects.equals(account, current))
        {
            resetSession();
            account = current;
            history.seed(client.getGrandExchangeOffers());
            offerActivity.seed(client.getGrandExchangeOffers(), activityNow());
        }
        return true;
    }

    private void resetSession()
    {
        history.clear();
        offerActivity.clear();
        overview = null;
        Arrays.fill(offerCosts, null);
        account = null;
        clearSelection();
    }

    private void clearSelection()
    {
        if (selection != null || !manualInput.isEmpty())
        {
            selectionKey++;
        }
        selection = null;
        manualInput = "";
    }

    void changeCost(long key, String input)
    {
        final long generation = lifecycle.get();
        clientThread.invoke(() ->
        {
            if (lifecycle.get() != generation) return;
            refresh();
            if (running && enabled && ready() && key == selectionKey && selection != null)
            {
                manualInput = input.trim();
                if (selection.startsWith("offer:"))
                {
                    int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
                    GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
                    if (offers != null && slot >= 0 && slot < offers.length && slot < offerCosts.length
                        && sell(offers[slot]))
                    {
                        offerCosts[slot] = manualInput.isEmpty() ? null : new ManualOfferCost(offers[slot], manualInput);
                    }
                }
                refresh();
            }
        });
    }

    void refresh()
    {
        if (!running)
        {
            return;
        }
        boolean wanted = configuredEnabled();
        if (wanted != enabled)
        {
            resetSession();
            enabled = wanted;
        }
        if (!ready())
        {
            overview = null;
            clearSelection();
            publish(FlipperView.empty(enabled, selectionKey, enabled
                ? "Open a sell offer in the Grand Exchange." : "Enable Flipper to calculate a sell offer."));
            return;
        }
        refreshOverview();
        Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
        Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS);
        Widget frame = client.getWidget(InterfaceID.GeOffers.FRAME);
        boolean draft = visible(setup);
        Widget container = draft ? setup : details;
        int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
        if (!visible(container) || !visible(frame) || slot < 0 || slot >= 8)
        {
            noSelection("Open a sell offer in the Grand Exchange.");
            return;
        }
        int item;
        int total;
        int sold = 0;
        long price;
        if (draft)
        {
            if (client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) != 1)
            {
                noSelection("Set up a sell offer to calculate its profit.");
                return;
            }
            item = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
            total = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
            price = client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE);
        }
        else
        {
            GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
            GrandExchangeOffer offer = offers == null || slot >= offers.length ? null : offers[slot];
            if (offer == null || (offer.getState() != GrandExchangeOfferState.SELLING
                && offer.getState() != GrandExchangeOfferState.SOLD
                && offer.getState() != GrandExchangeOfferState.CANCELLED_SELL))
            {
                noSelection("Select a sell offer to calculate its remaining profit.");
                return;
            }
            item = offer.getItemId();
            total = offer.getTotalQuantity();
            sold = offer.getQuantitySold();
            price = offer.getPrice();
        }
        String next = (draft ? "draft:" : "offer:") + slot + ":" + item;
        if (!next.equals(selection))
        {
            clearSelection();
            selection = next;
            selectionKey++;
            if (!draft && offerCosts[slot] != null)
            {
                GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
                if (offers != null && slot < offers.length && offerCosts[slot].matches(offers[slot]))
                {
                    manualInput = offerCosts[slot].input;
                }
                else
                {
                    offerCosts[slot] = null;
                }
            }
        }
        int rate = client.getVarbitValue(VarbitID.GE_TRANSMIT_TAXRATE);
        Widget itemWidget = container.getChild(draft ? 23 : 7);
        Widget quantityWidget = container.getChild(draft ? 34 : 18);
        Widget priceWidget = container.getChild(draft ? 41 : 25);
        Widget totalWidget = container.getChild(draft ? 45 : 29);
        if (item <= 0 || total <= 0 || sold < 0 || sold > total || price <= 0 || rate < 0 || rate > 1000
            || itemWidget == null || itemWidget.getItemId() != item
            || !matchesNumber(quantityWidget, total) || !matchesCoins(priceWidget, BigInteger.valueOf(price))
            || !matchesCoins(totalWidget, FlipperMath.calculate(price, total, rate, null).proceeds))
        {
            publish(FlipperView.empty(enabled, selectionKey, "Waiting for the Grand Exchange offer to update."));
            return;
        }
        int quantity = total - sold;
        FlipperMath.Cost cost = history.cost(item, quantity);
        String source = cost == null ? "Purchase cost unknown" : "Recorded buy average";
        if (!manualInput.isEmpty())
        {
            try
            {
                if (!manualInput.matches("[0-9]+|[0-9]{1,3}(,[0-9]{3})+"))
                {
                    throw new NumberFormatException();
                }
                cost = FlipperMath.Cost.perItem(Long.parseLong(manualInput.replace(",", "")));
                source = "Manual purchase cost";
            }
            catch (IllegalArgumentException error)
            {
                cost = null;
                source = "Enter a whole GP amount";
            }
        }
        boolean typing = client.getVarcIntValue(VarClientInt.INPUT_TYPE) != 0;
        Rectangle bounds = frame.getBounds();
        Point anchor = !typing && bounds != null && client.getCanvasWidth() >= FlipperOverlay.WIDTH
            && bounds.y + bounds.height + FlipperOverlay.HEIGHT + 4 <= client.getCanvasHeight()
            ? new Point(Math.max(0, Math.min(bounds.x + 6, client.getCanvasWidth() - FlipperOverlay.WIDTH)),
                bounds.y + bounds.height + 4) : null;
        String status = typing ? "Finish entering the Grand Exchange value."
            : quantity == 0 ? "Sale completed. Completed profit is not calculated."
            : draft ? "Offer not placed." : "Estimate for the remaining items only.";
        ItemComposition definition = client.getItemDefinition(item);
        String name = definition == null || definition.getName() == null ? "Item " + item : definition.getName();
        publish(new FlipperView(enabled, selectionKey, item, name, status, quantity, sold, price,
            typing ? -1 : rate, source, manualInput, cost, anchor));
    }

    private static long activityNow()
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private void refreshOverview()
    {
        long now = activityNow();
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) { overview = null; return; }
        for (int i = 0; i < Math.min(8, offers.length); i++) offerActivity.observe(i, offers[i], now);
        Widget index = client.getWidget(InterfaceID.GeOffers.INDEX);
        Widget frame = client.getWidget(InterfaceID.GeOffers.FRAME);
        if (!visible(index) || !visible(frame) || frame.getBounds() == null
            || visible(client.getWidget(InterfaceID.GeOffers.SETUP)) || visible(client.getWidget(InterfaceID.GeOffers.DETAILS)))
        { overview = null; return; }
        Map<Integer, Rectangle> slots = new LinkedHashMap<>();
        for (int i = 0; i < FlipperOverviewOverlay.SLOT_WIDGETS.length; i++)
        {
            Widget slot = client.getWidget(FlipperOverviewOverlay.SLOT_WIDGETS[i]);
            if (visible(slot) && slot.getBounds() != null && slot.getBounds().width > 0 && slot.getBounds().height > 0)
                slots.put(i, slot.getBounds());
        }
        overview = new FlipperOverviewOverlay.View(account, frame.getBounds(), slots, offerActivity.snapshot(), now);
    }

    private void noSelection(String status)
    {
        clearSelection();
        publish(FlipperView.empty(enabled, selectionKey, status));
    }

    static boolean matchesCoins(Widget widget, BigInteger value)
    {
        if (widget == null || widget.isHidden() || widget.getText() == null)
        {
            return false;
        }
        Matcher matcher = COINS.matcher(Text.removeTags(widget.getText()).trim());
        return matcher.matches() && new BigInteger(matcher.group(1).replace(",", "")).equals(value);
    }

    private static boolean matchesNumber(Widget widget, long value)
    {
        return widget != null && !widget.isHidden() && widget.getText() != null
            && Text.removeTags(widget.getText()).replace(",", "").trim().equals(Long.toString(value));
    }

    private static boolean visible(Widget widget)
    {
        return widget != null && !widget.isHidden();
    }

    private void publish(FlipperView next)
    {
        if (next.fingerprint().equals(view.fingerprint()))
        {
            return;
        }
        view = next;
        SwingUtilities.invokeLater(() ->
        {
            if (running && panel != null && view == next)
            {
                panel.showView(next);
            }
        });
    }

    FlipperView view()
    {
        return view;
    }

    boolean showOverlay()
    {
        return running && enabled && configuredEnabled();
    }

    static BufferedImage logo()
    {
        try
        {
            return ImageIO.read(FlipperController.class.getResourceAsStream("/osrsbis-wordmark.png"));
        }
        catch (IOException | IllegalArgumentException error)
        {
            throw new IllegalStateException("Missing plugin wordmark", error);
        }
    }

    private static BufferedImage resize(BufferedImage image, int width, int height)
    {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return result;
    }
}
