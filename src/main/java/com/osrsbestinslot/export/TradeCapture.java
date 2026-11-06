package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Own trades: both offers, the counterparty, the trade row and the staff confirm-screen screenshot. */
@Slf4j
final class TradeCapture
{
	private final AccountConnectPlugin plugin;

	TradeCapture(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	// Trade-screenshot delivery proof (opt-in, see uploadTradeScreenshots): grab one frame when the
	// trade CONFIRM screen (group 334) loads, buffer it, and upload it only on "Accepted trade.".
	// NOTE the legacy net.runelite.api.InventoryID imported above is a DIFFERENT type from these
	// gameval int constants — referenced fully-qualified so the two are never conflated.
	private static final int TRADE_OFFER_CONTAINER_ID = net.runelite.api.gameval.InventoryID.TRADEOFFER;	// 90
	static final int TRADE_CONFIRM_GROUP_ID = InterfaceID.TRADECONFIRM;	// 334
	static final int TRADE_MAIN_GROUP_ID = InterfaceID.TRADEMAIN;			// 335
	private static final int TRADE_TITLE_COMPONENT = InterfaceID.Trademain.TITLE;	// 21954591 ("Trading with X")
	// The other player's live offer GRID on the MAIN trade screen (335). This is the only place the received
	// items are readable as item sprites — the confirm screen (334) shows them only as a value-text summary
	// that collapses to "Lots!" on big trades. javap-verified vs runelite-api 1.12.33.
	private static final int TRADE_MAIN_OTHER_OFFER = InterfaceID.Trademain.OTHER_OFFER;	// 21954588
	// WAVE 1b: the confirm screen's "You will receive" column — a value-TEXT summary (not item sprites), kept
	// only as a last-resort fallback. Packed component id = group 334 << 16 | child 24.
	private static final int TRADE_CONFIRM_RECEIVE_COMPONENT = InterfaceID.Tradeconfirm.YOU_WILL_RECEIVE;	// 21889048
	static final String TRADE_ACCEPTED_MESSAGE = "Accepted trade.";
	private static final String TRADE_DECLINED_MESSAGE = "Other player declined trade.";
	static final MediaType PNG = MediaType.parse("image/png");
	static final int MAX_SCREENSHOT_UPLOAD_BYTES = 5 * 1024 * 1024;	// enforced client-side and server-side

	// Trade state machine (package-private so unit tests can assert on it without a live client).
	// IDLE -> (container 90) tradeActive -> (334 load) tradeArmed + frame buffered -> commit/discard.
	volatile boolean tradeActive;
	volatile boolean tradeArmed;
	// MAIN trade screen (335) open: while true, onGameTick polls the other player's offer + name (readable
	// only here — 334 confirm replaces 335 and shows the other side as a "Lots!" text summary with its
	// 335-only title widget already gone). Root cause of the prod 0-counterparty / empty-received[] bug.
	volatile boolean tradeMainOpen;

	final AtomicReference<BufferedImage> pendingTradeFrame = new AtomicReference<>();
	/** Trade offer + counterparty captured at the confirm screen, emitted as a "trade" event on accept. */
	volatile List<Map<String, Object>> pendingTradeGiven;
	volatile String pendingCounterparty;
	// Staff-only enrichment: exact-name combat level observed while the direct-trade window is open.
	// Never guessed from hiscores or stale state; null means the named player was not uniquely visible.
	volatile Integer pendingCounterpartyCombatLevel;
	// WAVE 1b: the counterparty's side (what WE receive), read from the confirm-screen YOU_WILL_RECEIVE widget
	// at 334-load. pendingTradeReceived = structured [{id,qty}] if the widget exposes item children;
	// pendingReceivedText = the raw "Blood rune x 100 ..." summary as a lossless fallback when it does not.
	volatile List<Map<String, Object>> pendingTradeReceived;
	volatile String pendingReceivedText;

	void handleTradeContainerChanged(int containerId)
	{
		if (tradeScreenshotsDisabled())
		{
			return;
		}
		if (containerId == TRADE_OFFER_CONTAINER_ID)
		{
			tradeActive = true;
		}
	}

	/**
	 * While the MAIN trade screen (335) is open, snapshot the other player's offer + name — the only place
	 * they are readable as item sprites (the confirm screen 334 replaces 335 and shows the other side as a
	 * value-text summary that collapses to "Lots!"; its 335-only title widget is gone by 334-load). Last
	 * non-empty read wins, so the buffer holds the FINAL offer at the moment we transition to confirm.
	 * Null-safe; runs on the client thread from onGameTick / WidgetLoaded.
	 */
	void captureOtherOfferWhileMainOpen()
	{
		if (plugin.client == null)
		{
			return;
		}
		List<Map<String, Object>> other = new ArrayList<>();
		collectReceivedItems(plugin.client.getWidget(TRADE_MAIN_OTHER_OFFER), other);
		if (!other.isEmpty())
		{
			pendingTradeReceived = other;
		}
		if (!plugin.grants.captureEnabled())
		{
			pendingCounterparty = null;
			pendingCounterpartyCombatLevel = null;
			return;
		}
		String cp = counterpartyName();
		if (cp != null && !cp.isEmpty())
		{
			if (pendingCounterparty == null || !cp.equalsIgnoreCase(pendingCounterparty))
			{
				pendingCounterpartyCombatLevel = null;
			}
			pendingCounterparty = cp;
			Integer observedCombat = visibleCounterpartyCombatLevel(cp);
			if (observedCombat != null)
			{
				pendingCounterpartyCombatLevel = observedCombat;
			}
		}
	}

	/** Snapshot the items in our own trade offer (container 90) as [{id, qty}]. */
	List<Map<String, Object>> readOwnOffer()
	{
		ItemContainer c = plugin.client.getItemContainer(TRADE_OFFER_CONTAINER_ID);
		List<Map<String, Object>> items = new ArrayList<>();
		if (c != null)
		{
			for (Item it : c.getItems())
			{
				if (it != null && it.getId() >= 0 && it.getQuantity() > 0)
				{
					Map<String, Object> m = Fields.of("id", it.getId(), "qty", it.getQuantity());
					items.add(m);
				}
			}
		}
		return items;
	}

	/**
	 * WAVE 1b — read the counterparty's side (what WE receive) from the confirm-screen "You will receive"
	 * widget (component {@link #TRADE_CONFIRM_RECEIVE_COMPONENT}). There is NO counterparty ItemContainer, so
	 * this widget is the only source. Tries item-bearing children first → [{id, qty}]; returns an EMPTY list if
	 * none are present (the caller then falls back to {@link #readReceivedText}). Null-safe throughout: the
	 * widget, its child arrays and per-child item fields may all be absent.
	 *
	 * ⚠ NOT unit-verifiable against real runtime. Two things need one in-game two-sided trade to confirm:
	 * (1) whether this widget exposes item CHILDREN at all (it is likely a text summary — item sprites may
	 * instead live under Tradeconfirm.OTHER_OFFER / 21889053), and (2) whether it is even populated at
	 * WidgetLoaded time (the confirm-screen CS2 scripts may run a tick later). Written defensively so an empty
	 * read never throws and never blocks the text fallback.
	 */
	List<Map<String, Object>> readReceivedOffer()
	{
		List<Map<String, Object>> items = new ArrayList<>();
		if (plugin.client == null)
		{
			return items;
		}
		collectReceivedItems(plugin.client.getWidget(TRADE_CONFIRM_RECEIVE_COMPONENT), items);
		return items;
	}

	/** Collect item-bearing widgets (the widget itself + its children) as [{id, qty}]. Null-safe. */
	private void collectReceivedItems(Widget w, List<Map<String, Object>> out)
	{
		if (w == null)
		{
			return;
		}
		addReceivedItem(w, out);
		Widget[] kids = w.getChildren();
		if (kids == null || kids.length == 0)
		{
			kids = w.getDynamicChildren();	// some interfaces expose item slots only via the dynamic-child array
		}
		if (kids != null)
		{
			for (Widget k : kids)
			{
				addReceivedItem(k, out);
			}
		}
	}

	/** Append {id, qty} if this widget carries a real item (id/qty > 0). */
	private void addReceivedItem(Widget w, List<Map<String, Object>> out)
	{
		if (w == null)
		{
			return;
		}
		int id = w.getItemId();
		int qty = w.getItemQuantity();
		if (id > 0 && qty > 0)
		{
			out.add(plugin.snapshots.itemMap(id, qty));
		}
	}

	/** Trade partner name from the trade window title ("Trading With: Name"), or null. Staff capture only. */
	String counterpartyName()
	{
		Widget w = plugin.client.getWidget(TRADE_TITLE_COMPONENT);
		if (w == null)
		{
			return null;
		}
		String t = Text.removeTags(w.getText() == null ? "" : w.getText()).trim();
		int idx = t.toLowerCase(Locale.ROOT).indexOf("with");
		if (idx >= 0)
		{
			String name = t.substring(idx + 4).replaceFirst("^[:\\s]+", "").trim();
			return name.isEmpty() ? null : name;
		}
		return t.isEmpty() ? null : t;
	}

	/**
	 * WAVE 1b fallback — the "You will receive" column is likely a TEXT summary ("Blood rune x 100<br>Coins
	 * x 5,000"), not item sprites. When no item children are found, capture that RAW text (tags/&lt;br&gt;
	 * intact so nothing is lost, child lines joined) as received_text. Returns null only when there is no
	 * actual content (emptiness is tested tag-stripped). Null-safe.
	 */
	String readReceivedText()
	{
		if (plugin.client == null)
		{
			return null;
		}
		Widget w = plugin.client.getWidget(TRADE_CONFIRM_RECEIVE_COMPONENT);
		if (w == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		appendReceivedText(w, sb);
		Widget[] kids = w.getChildren();
		if (kids == null || kids.length == 0)
		{
			kids = w.getDynamicChildren();
		}
		if (kids != null)
		{
			for (Widget k : kids)
			{
				appendReceivedText(k, sb);
			}
		}
		String raw = sb.toString().trim();
		return Text.removeTags(raw).trim().isEmpty() ? null : raw;
	}

	/** Append a widget's non-empty raw text as its own line. */
	private void appendReceivedText(Widget w, StringBuilder sb)
	{
		if (w == null)
		{
			return;
		}
		String t = w.getText();
		if (t != null && !t.isEmpty())
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append(t);
		}
	}


	/** RSN comparison form for the trade partner: tags, NBSP and underscores normalised. */
	private static String normalizeRsnForMatch(String rsn)
	{
		if (rsn == null)
		{
			return "";
		}
		return Text.removeTags(rsn)
			.replace('\u00A0', ' ')
			.replace('_', ' ')
			.trim()
			.replaceAll(" +", " ")
			.toLowerCase(Locale.ROOT);
	}

	Integer visibleCounterpartyCombatLevel(String rsn)
	{
		String wanted = normalizeRsnForMatch(rsn);
		if (!plugin.grants.captureEnabled() || plugin.client == null || wanted.isEmpty())
		{
			return null;
		}
		List<Player> players = plugin.client.getPlayers();
		if (players == null)
		{
			return null;
		}
		Integer found = null;
		int matches = 0;
		for (Player p : players)
		{
			if (p == null || p == plugin.client.getLocalPlayer() || p.getName() == null)
			{
				continue;
			}
			String name = normalizeRsnForMatch(p.getName());
			if (!wanted.equals(name))
			{
				continue;
			}
			matches++;
			int level = p.getCombatLevel();
			if (level > 0)
			{
				found = level;
			}
		}
		return matches == 1 ? found : null;
	}

	void handleTradeWidgetLoaded(int groupId)
	{
		if (tradeScreenshotsDisabled())
		{
			return;
		}
		// Receive-side fix: the first trade window (335) opening marks the trade active regardless of
		// which side put items up. Previously tradeActive was set only when OUR offer container (90)
		// changed, so a receive-only trade (we add nothing) never armed the confirm capture.
		if (groupId == TRADE_MAIN_GROUP_ID)
		{
			tradeActive = true;
		}
		if (groupId == TRADE_CONFIRM_GROUP_ID && tradeActive)
		{
			tradeArmed = true;
			plugin.drawManager.requestNextFrameListener(image ->
			{
				if (plugin.grants.screenshotsEnabled())	// re-check: toggle may flip before the frame lands
				{
					pendingTradeFrame.set(AccountConnectPlugin.toBufferedImage(image));
				}
			});
		}
	}

	void handleTradeWidgetClosed(int groupId)
	{
		if (tradeScreenshotsDisabled())
		{
			return;
		}
		if (tradeArmed && (groupId == TRADE_CONFIRM_GROUP_ID || groupId == TRADE_MAIN_GROUP_ID))
		{
			resetTradeState();
		}
	}

	void emitTradeEvent()
	{
		List<Map<String, Object>> given = pendingTradeGiven;
		String counterparty = pendingCounterparty;
		Integer counterpartyCombatLevel = pendingCounterpartyCombatLevel;
		List<Map<String, Object>> received = pendingTradeReceived;
		String receivedText = pendingReceivedText;
		pendingTradeGiven = null;
		pendingCounterparty = null;
		pendingCounterpartyCombatLevel = null;
		pendingTradeReceived = null;
		pendingReceivedText = null;
		if (given == null)
		{
			return;
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("given", given);
		// Legacy operational context. PublicTransport excludes it from both queues and the wire.
		if (counterparty != null)
		{
			fields.put("counterparty", counterparty);
		}
		if (counterpartyCombatLevel != null)
		{
			fields.put("counterparty_combat_level", counterpartyCombatLevel);
		}
		plugin.putWorldContext(fields);
		// WAVE 1b: the received side. Always carry received[] (may be empty if the widget was text-only or not
		// yet populated); attach received_text only when the structured read came back empty and text was found.
		fields.put("received", received == null ? new ArrayList<>() : received);
		if (receivedText != null && !receivedText.isEmpty())
		{
			fields.put("received_text", receivedText);
		}
		// Staff Wilderness trades only: who the client had loaded at "Accepted trade.". Sampled here, not at
		// the confirm screen: players stay loaded at acceptance, and moving would have closed the trade.
		// Presence only: `counterparty` above is the trade partner, these rows never change it.
		plugin.wilderness.attachSurroundingSnapshot(fields, "surrounding_at_trade");
		plugin.events.emitEvent("trade", fields);
	}

	void handleTradeChat(String message)
	{
		if (tradeScreenshotsDisabled())
		{
			return;
		}
		String text = Text.removeTags(message == null ? "" : message).trim();
		if (TRADE_ACCEPTED_MESSAGE.equalsIgnoreCase(text))
		{
			BufferedImage frame = pendingTradeFrame.getAndSet(null);
			resetTradeState();
			if (frame != null)
			{
				submitTradeScreenshotUpload(frame, "confirm");
			}
			// Second proof frame: the trade just completed and the window closed, so the NEXT rendered
			// frame shows the "Accepted trade." chat confirmation. Delivery proof needs BOTH — the
			// confirm screen (items + partner) and the completion frame (proof it actually went through).
			// Only capture when a valid token is configured: nothing to upload otherwise, and this also
			// keeps the no-token path from touching drawManager.
			String completedToken = plugin.config.linkToken() == null ? "" : plugin.config.linkToken().trim();
			if (completedToken.matches("^[a-f0-9]{32}$"))
			{
				plugin.drawManager.requestNextFrameListener(image ->
				{
					if (plugin.grants.screenshotsEnabled())	// re-check: the gate may close before the frame lands
					{
						submitTradeScreenshotUpload(AccountConnectPlugin.toBufferedImage(image), "completed");
					}
				});
			}
		}
		else if (TRADE_DECLINED_MESSAGE.equalsIgnoreCase(text))
		{
			resetTradeState();
		}
	}

	/**
	 * The whole feature is gated on screenshotsEnabled(): no link token (or a server force-disable)
	 * means no arming, no frame request, no buffer, no upload. Also drops any frame buffered before the
	 * token was cleared or the server disabled it mid-trade.
	 */
	private boolean tradeScreenshotsDisabled()
	{
		if (plugin.grants.screenshotsEnabled())
		{
			return false;
		}
		if (tradeActive || tradeArmed || pendingTradeFrame.get() != null)
		{
			resetTradeState();
		}
		return true;
	}

	void resetTradeState()
	{
		tradeActive = false;
		tradeArmed = false;
		tradeMainOpen = false;
		pendingTradeFrame.set(null);
		pendingTradeGiven = null;	// activity-log trade capture — drop on decline/abandon/hop so it never leaks
		pendingCounterparty = null;
		pendingCounterpartyCombatLevel = null;
		pendingTradeReceived = null;	// WAVE 1b: received side — drop with the rest so it never leaks across trades
		pendingReceivedText = null;
	}

	/**
	 * PNG-encoding a full frame can hitch the render loop, so encode + upload run on the injected
	 * background executor; the OkHttp call itself is async (enqueue), same as postSnapshot.
	 */
	private void submitTradeScreenshotUpload(BufferedImage frame, String phase)
	{
		if (!plugin.uploadAllowed())
		{
			return;		// upload switch off — send nothing
		}
		String token = plugin.config.linkToken() == null ? "" : plugin.config.linkToken().trim();
		if (!token.matches("^[a-f0-9]{32}$"))
		{
			return;	// same guard as syncTask: no / malformed token configured yet — never POST
		}
		final String captureAccount = plugin.activeHash;
		plugin.executor.submit(() ->
		{
			byte[] bytes = AccountConnectPlugin.encodePng(frame);
			if (bytes == null || bytes.length == 0)
			{
				return;
			}
			if (bytes.length > MAX_SCREENSHOT_UPLOAD_BYTES)
			{
				log.debug("OSRS BiS trade screenshot dropped: {} bytes exceeds {} byte cap",
					bytes.length, MAX_SCREENSHOT_UPLOAD_BYTES);
				return;
			}
			uploadTradeScreenshot(token, bytes, phase, captureAccount);
		});
	}

	void uploadTradeScreenshot(String token, byte[] pngBytes, String phase)
	{
		uploadTradeScreenshot(token, pngBytes, phase, plugin.activeHash);
	}

	void uploadTradeScreenshot(String token, byte[] pngBytes, String phase, String captureAccount)
	{
		if (!plugin.grants.captureAccountMatches(captureAccount) || !plugin.grants.screenshotsEnabled() || !plugin.uploadAllowed() || !token.equals(plugin.currentLinkToken()))
		{
			return;		// upload switch off — send nothing
		}
		long capturedAt = System.currentTimeMillis() / 1000L;
		RequestBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("token", token)
			.addFormDataPart("account_hash", captureAccount == null ? "" : captureAccount)
			.addFormDataPart("kind", "trade")
			.addFormDataPart("phase", phase)	// "confirm" = trade window; "completed" = post-accept chat frame
			.addFormDataPart("captured_at", Long.toString(capturedAt))
			.addFormDataPart("file", "trade-" + phase + "-" + capturedAt + ".png", RequestBody.create(PNG, pngBytes))
			.build();

		String base = plugin.config.apiBaseUrl() == null ? "" : plugin.config.apiBaseUrl().replaceAll("/+$", "");
		Request request = new Request.Builder()
			.url(base + "/screenshot-ingest")
			.post(body)
			.build();

		plugin.okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS BiS trade screenshot upload failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.isSuccessful())
					{
						plugin.events.healthLastMediaUploadMs.set(System.currentTimeMillis());
					}
					log.debug("OSRS BiS trade screenshot upload response: {}", response.code());
				}
				finally
				{
					response.close();
				}
			}
		});
	}
}
