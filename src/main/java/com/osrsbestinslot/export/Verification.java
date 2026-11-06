package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.kit.KitType;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Verification: cross-check reads of the live client and the verification frames, under the verification grant. */
@Slf4j
final class Verification
{
	private final AccountConnectPlugin plugin;

	Verification(AccountConnectPlugin plugin)
	{
		this.plugin = plugin;
	}

	boolean discoveryReady()
	{
		if (plugin.client == null || !plugin.grants.verificationGranted() || plugin.client.getGameState() != GameState.LOGGED_IN
			|| plugin.client.getAccountHash() == -1L || plugin.activeHash == null || !plugin.activeHash.equals(Long.toString(plugin.client.getAccountHash())))
		{
			plugin.verificationDiscovery.reset();
			return false;
		}
		plugin.verificationDiscovery.bind(plugin.currentLinkToken(), plugin.activeHash, plugin.verificationEpoch.get());
		return true;
	}

	void emitDiscovery(Map<String, Object> fields)
	{
		if (fields == null) return;
		if (!plugin.grants.verificationFramesEnabled() || !plugin.verificationProbe.eventRoom(System.currentTimeMillis()) || !plugin.eventVerification.room())
		{ plugin.verificationDiscovery.rejected(fields); return; }
		String previous = plugin.verificationMessage;
		plugin.verificationMessage = (String) fields.get("verification_text");
		plugin.discoveryEvidenceQueued = false;
		try { plugin.events.emitEvent("verification_discovery", fields); }
		finally
		{
			plugin.verificationMessage = previous;
			if (!plugin.discoveryEvidenceQueued) plugin.verificationDiscovery.rejected(fields);
		}
	}

	/** Poll only the bounded event queue on client ticks; it never waits for the random sample gap. */
	void pollEventVerification()
	{
		if (plugin.client == null || !plugin.grants.verificationGranted() || plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			plugin.eventVerification.reset();
			return;
		}
		for (EventVerification.Source source : plugin.eventVerification.drain())
		{
			if (!source.matches(plugin.currentLinkToken(), plugin.activeHash, plugin.verificationEpoch.get())
				|| !source.account.equals(Long.toString(plugin.client.getAccountHash()))) continue;
			if (source.expired(plugin.client.getTickCount())) { eventEvidenceUnknown(source, "source_not_visible"); continue; }
			if (!plugin.grants.verificationFramesEnabled()) { eventEvidenceUnknown(source, "frames_not_granted"); continue; }
			if (verificationFrameBlocked()) { plugin.eventVerification.offer(source); continue; }
			List<VerificationFrame.Region> regions = EventVerificationScreen.regions(plugin.client, source);
			if (regions.isEmpty()) { plugin.eventVerification.offer(source); continue; }
			if (!plugin.verificationProbe.takeFrame(System.currentTimeMillis())) { eventEvidenceUnknown(source, "frame_budget"); continue; }
			requestEventVerificationFrame(source, regions);
		}
	}

	private boolean verificationFrameBlocked()
	{
		if (plugin.client.isMenuOpen()) return true;
		for (int group : VERIFY_FRAME_BLOCKERS)
		{
			Widget w = plugin.client.getWidget(group, 0);
			if (w != null && !w.isHidden()) return true;
		}
		return false;
	}

	private boolean eventEvidenceCurrent(EventVerification.Source source)
	{
		return plugin.grants.verificationFramesEnabled() && plugin.client.getGameState() == GameState.LOGGED_IN
			&& source.matches(plugin.currentLinkToken(), plugin.activeHash, plugin.verificationEpoch.get())
			&& source.account.equals(Long.toString(plugin.client.getAccountHash()));
	}

	void eventEvidenceUnknown(EventVerification.Source source, String reason)
	{
		if (!plugin.grants.verificationGranted() || !source.matches(plugin.currentLinkToken(), plugin.activeHash, plugin.verificationEpoch.get())) return;
		Map<String, Object> row = source.common(plugin.client == null ? source.tick : plugin.client.getTickCount());
		row.put("plugin_version", AccountConnectPlugin.PLUGIN_VERSION);
		row.put("ref", randomRef());
		List<Map<String, Object>> missing = EventVerificationScreen.missing(source, Collections.emptyList(), reason);
		emitEventScreenRows(missing, row);
	}

	private void emitEventScreenRows(List<Map<String, Object>> results, Map<String, Object> common)
	{
		// Four bounded text results per row keep the final projected fields below the receiver's 8KB cap.
		int parts = (results.size() + 3) / 4;
		for (int i = 0; i < parts; i++)
		{
			Map<String, Object> row = new LinkedHashMap<>(common);
			row.put("area", "screen"); row.put("check", "ocr_event"); row.put("part", i + 1); row.put("parts", parts);
			row.put("total", results.size()); row.put("mismatches", 0); row.put("unknown", results.size());
			row.put("results", new ArrayList<>(results.subList(i * 4, Math.min(results.size(), i * 4 + 4))));
			plugin.events.emitEvent("verification", row);
		}
	}

	private void requestEventVerificationFrame(EventVerification.Source source, List<VerificationFrame.Region> regions)
	{
		final int readTick = plugin.client.getTickCount();
		final long readMs = System.currentTimeMillis();
		final int canvasW = plugin.client.getCanvasWidth(), canvasH = plugin.client.getCanvasHeight();
		final String ref = randomRef();
		plugin.drawManager.requestNextFrameListener(img ->
		{
			if (!eventEvidenceCurrent(source)) return;
			if (plugin.client.getTickCount() != readTick || verificationFrameBlocked()
				|| !EventVerificationScreen.unchanged(plugin.client, regions))
			{
				plugin.clientThread.invoke(() -> eventEvidenceUnknown(source, "screen_changed_before_frame"));
				return;
			}
			BufferedImage rgb = img == null ? null : StoreClips.toRgbFrame(img);
			if (rgb == null) { plugin.clientThread.invoke(() -> eventEvidenceUnknown(source, "frame_unavailable")); return; }
			final BufferedImage pixels = VerificationFrame.retainRegions(rgb, regions, canvasW, canvasH);
			final long frameMs = System.currentTimeMillis();
			plugin.executor.submit(() ->
			{
				byte[] png = AccountConnectPlugin.encodePng(pixels);
				plugin.clientThread.invoke(() ->
				{
					if (!eventEvidenceCurrent(source)) return;
					if (png == null || png.length == 0 || png.length > TradeCapture.MAX_SCREENSHOT_UPLOAD_BYTES)
					{ eventEvidenceUnknown(source, "frame_encoding_failed"); return; }
					Map<String, Object> common = source.common(readTick);
					common.put("plugin_version", AccountConnectPlugin.PLUGIN_VERSION); common.put("ref", ref);
					List<Map<String, Object>> capture = Arrays.asList(
						VerificationProbe.observed("frame_tick", readTick), VerificationProbe.observed("frame_delay_ms", frameMs - readMs),
						VerificationProbe.observed("width", pixels.getWidth()), VerificationProbe.observed("height", pixels.getHeight()));
					for (Map<String, Object> row : VerificationProbe.rows("frame", "capture", capture, common)) plugin.events.emitEvent("verification", row);
					List<Map<String, Object>> results = VerificationFrame.results(regions, pixels.getWidth(), pixels.getHeight(), canvasW, canvasH);
					results.addAll(EventVerificationScreen.missing(source, regions, "source_field_not_visible"));
					emitEventScreenRows(results, common);
					uploadVerificationFrame(source.token, png, ref, frameMs);
				});
			});
		});
	}

	// ---- verification (VerificationProbe; X-Verification grant only) ----

	/** Interfaces whose content is about other people: a frame is never taken while one is open. */
	static final int[] VERIFY_FRAME_BLOCKERS = {
		TradeCapture.TRADE_MAIN_GROUP_ID, TradeCapture.TRADE_CONFIRM_GROUP_ID, InterfaceID.BANKPIN_KEYPAD};
	/** Once a tick: take the sample that is due (an occasion or a random moment), only under the grant. */
	void runVerification()
	{
		if (plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN || plugin.client.getLocalPlayer() == null
			|| !plugin.grants.verificationGranted())
		{
			plugin.verificationProbe.reset();
			return;
		}
		VerificationProbe.Sample s = plugin.verificationProbe.due(System.currentTimeMillis());
		if (s == null) return;
		try
		{
			verificationSample(s);
		}
		catch (RuntimeException e)
		{
			log.debug("OSRS BiS verification sample failed: {}", e.toString());
		}
	}

	private void verificationSample(VerificationProbe.Sample s)
	{
		long nowMs = System.currentTimeMillis();
		int tick = plugin.client.getTickCount();
		boolean blocked = false;
		for (int g : VERIFY_FRAME_BLOCKERS)
		{
			Widget w = plugin.client.getWidget(g, 0);
			if (w != null && !w.isHidden()) blocked = true;
		}
		boolean frame = !blocked && plugin.uploadAllowed() && plugin.grants.verificationFramesEnabled() && plugin.verificationProbe.takeFrame(nowMs);
		String ref = frame ? randomRef() : null;

		Map<String, Object> common = Fields.of(
			"tick", tick, "snapshot_revision", plugin.lastSnapshotCapturedAtMs > 0 ? plugin.lastSnapshotCapturedAtMs : null,
			"plugin_version", AccountConnectPlugin.PLUGIN_VERSION, "trigger", s.trigger);
		if (s.key != null) common.put("trigger_key", s.key);
		if (s.value != null) common.put("trigger_value", s.value);
		common.put("world", plugin.client.getWorld());
		if (ref != null) common.put("ref", ref);

		List<Map<String, Object>> rows = new ArrayList<>();
		rows.addAll(VerificationProbe.rows("skills", "xp_to_level", VerificationProbe.skillLevels(skillXpAndLevels()), common));
		rows.addAll(VerificationProbe.rows("skills", "derived_levels", VerificationProbe.derivedLevels(combatAndAllRealLevels(),
			plugin.client.getTotalLevel(), plugin.client.getLocalPlayer().getCombatLevel()), common));
		rows.addAll(VerificationProbe.rows("skills", "boosted_vs_orbs", orbResults(), common));
		rows.addAll(VerificationProbe.rows("counters", "server_vs_client", VerificationProbe.serverVsClient(counterReads()), common));
		rows.addAll(VerificationProbe.rows("account", "server_vs_client", VerificationProbe.serverVsClient(accountReads()), common));
		rows.addAll(VerificationProbe.rows("counters", "widget_on_screen", widgetCounterResults(), common));
		rows.addAll(VerificationProbe.rows("inventory", "container_vs_widget", inventoryResults(), common));
		rows.addAll(VerificationProbe.rows("equipment", "container_vs_model", equipmentResults(), common));
		rows.addAll(VerificationProbe.rows("bank", "container_vs_widget", bankResults(), common));
		rows.addAll(VerificationProbe.rows("location", "observed", locationResults(), common));
		for (Map<String, Object> row : rows) plugin.events.emitEvent("verification", row);
		if (frame) requestVerificationFrame(ref, tick, nowMs, plugin.lastSnapshotCapturedAtMs);
	}

	private static String randomRef()
	{
		return String.format("%016x", ThreadLocalRandom.current().nextLong());
	}

	private Map<String, int[]> skillXpAndLevels()
	{
		Map<String, int[]> m = new LinkedHashMap<>();
		for (Skill sk : Skill.values())
		{
			if ("Overall".equalsIgnoreCase(sk.getName())) continue;
			m.put(sk.getName().toLowerCase(), new int[]{plugin.client.getSkillExperience(sk), plugin.client.getRealSkillLevel(sk)});
		}
		return m;
	}

	private Map<String, Integer> combatAndAllRealLevels()
	{
		Map<String, Integer> m = new LinkedHashMap<>();
		for (Skill sk : Skill.values())
		{
			if ("Overall".equalsIgnoreCase(sk.getName())) continue;
			m.put(sk.getName().toLowerCase(), plugin.client.getRealSkillLevel(sk));
		}
		return m;
	}

	/** The orb numbers the player sees against the client's values. A hidden orb is unknown. */
	private List<Map<String, Object>> orbResults()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		out.add(VerificationProbe.shown("hitpoints_orb", null, plugin.client.getBoostedSkillLevel(Skill.HITPOINTS),
			widgetInt(InterfaceID.Orbs.HEALTH_TEXT)));
		out.add(VerificationProbe.shown("prayer_orb", null, plugin.client.getBoostedSkillLevel(Skill.PRAYER),
			widgetInt(InterfaceID.Orbs.PRAYER_TEXT)));
		out.add(VerificationProbe.shown("run_energy_orb", VerificationProbe.inputs("energy_raw", plugin.client.getEnergy()),
			plugin.client.getEnergy() / 100, widgetInt(InterfaceID.Orbs.RUNENERGY_TEXT)));
		int spec = plugin.client.getVarpValue(VarPlayerID.SA_ENERGY);
		out.add(VerificationProbe.shown("special_energy_orb", VerificationProbe.inputs("sa_energy", spec), spec / 10,
			widgetInt(InterfaceID.Orbs.SPECENERGY_TEXT)));
		return out;
	}

	private Integer widgetInt(int packedId)
	{
		Widget w = plugin.client.getWidget(packedId);
		return w == null || w.isHidden() ? null : KillContext.widgetNumber(w.getText());
	}

	/** Every service-unit counter: the server-set value against the client's own value of the same var. */
	private List<Object[]> counterReads()
	{
		List<Object[]> reads = new ArrayList<>();
		for (Object[] c : KillContext.ACTIVITY_COUNTERS)
		{
			int id = (int) c[2];
			boolean varp = "varp".equals(c[1]);
			reads.add(new Object[]{c[0], c[1], id,
				varp ? plugin.client.getServerVarpValue(id) : plugin.client.getServerVarbitValue(id),
				varp ? plugin.client.getVarpValue(id) : plugin.client.getVarbitValue(id)});
		}
		return reads;
	}

	/** Account vars the snapshot sends (quest points, slayer, combat achievements, diaries): server against client. */
	private List<Object[]> accountReads()
	{
		List<Object[]> reads = new ArrayList<>();
		Object[][] varps = {{"quest_points", SnapshotBuilder.VARP_QUEST_POINTS}, {"slayer_task_target", SnapshotBuilder.VARP_SLAYER_TARGET},
			{"slayer_task_remaining", SnapshotBuilder.VARP_SLAYER_COUNT}, {"membership_days", VarPlayer.MEMBERSHIP_DAYS}};
		for (Object[] v : varps)
		{
			int id = (int) v[1];
			reads.add(new Object[]{v[0], "varp", id, plugin.client.getServerVarpValue(id), plugin.client.getVarpValue(id)});
		}
		Object[][] varbits = {{"slayer_points", Varbits.SLAYER_POINTS}, {"slayer_streak", Varbits.SLAYER_TASK_STREAK},
			{"ca_points", SnapshotBuilder.VARBIT_CA_POINTS}, {"iron_mode", SnapshotBuilder.ACCOUNT_TYPE_VARBIT}};
		for (Object[] v : varbits)
		{
			int id = (int) v[1];
			reads.add(new Object[]{v[0], "varbit", id, plugin.client.getServerVarbitValue(id), plugin.client.getVarbitValue(id)});
		}
		String[] tiers = {"easy", "medium", "hard", "elite"};
		for (Object[] d : SnapshotBuilder.DIARIES)
		{
			for (int t = 0; t < tiers.length; t++)
			{
				int id = (int) d[t + 1];
				reads.add(new Object[]{"diary_" + d[0] + "_" + tiers[t], "varbit", id, plugin.client.getServerVarbitValue(id), plugin.client.getVarbitValue(id)});
			}
		}
		for (int i = 0; i < AccountProgressCapture.CA_TASK_VARPS.length; i++)
		{
			int id = AccountProgressCapture.CA_TASK_VARPS[i];
			reads.add(new Object[]{"ca_task_word_" + i, "varp", id, plugin.client.getServerVarpValue(id), plugin.client.getVarpValue(id)});
		}
		return reads;
	}

	/** The balances only an interface shows: what is on screen now, for the frame to confirm. Not a match by itself. */
	private List<Map<String, Object>> widgetCounterResults()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object[] c : KillContext.WIDGET_COUNTERS)
		{
			Widget w = plugin.client.getWidget((int) c[1]);
			if (w == null || w.isHidden()) continue;
			Map<String, Object> r = VerificationProbe.observed((String) c[0], KillContext.widgetNumber(w.getText()));
			r.put("inputs", VerificationProbe.inputs("widget", c[1]));
			out.add(r);
		}
		return out;
	}

	/** The inventory container against the inventory (or bank-side inventory) widget, slot by slot. */
	private List<Map<String, Object>> inventoryResults()
	{
		Map<Integer, int[]> container = containerSlots(plugin.client.getItemContainer(InventoryID.INVENTORY));
		Widget w = plugin.client.getWidget(InterfaceID.Bankside.ITEMS);
		if (w == null || w.isHidden()) w = plugin.client.getWidget(InterfaceID.Inventory.ITEMS);
		return VerificationProbe.slots(container, w == null || w.isHidden() ? null : widgetSlots(w), 28);
	}

	/** Worn items against the player model's equipment (what other players see), per slot. */
	private List<Map<String, Object>> equipmentResults()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		ItemContainer eqp = plugin.client.getItemContainer(InventoryID.EQUIPMENT);
		PlayerComposition comp = plugin.client.getLocalPlayer().getPlayerComposition();
		if (eqp == null || comp == null) return out;
		Object[][] slots = {
			{EquipmentInventorySlot.HEAD, KitType.HEAD}, {EquipmentInventorySlot.CAPE, KitType.CAPE},
			{EquipmentInventorySlot.AMULET, KitType.AMULET}, {EquipmentInventorySlot.WEAPON, KitType.WEAPON},
			{EquipmentInventorySlot.BODY, KitType.TORSO}, {EquipmentInventorySlot.SHIELD, KitType.SHIELD},
			{EquipmentInventorySlot.LEGS, KitType.LEGS}, {EquipmentInventorySlot.GLOVES, KitType.HANDS},
			{EquipmentInventorySlot.BOOTS, KitType.BOOTS}};
		for (Object[] s : slots)
		{
			EquipmentInventorySlot slot = (EquipmentInventorySlot) s[0];
			Item item = eqp.getItem(slot.getSlotIdx());
			int worn = item == null || item.getId() <= 0 ? -1 : item.getId();
			int model = comp.getEquipmentId((KitType) s[1]);
			// The model shows -1 for an empty slot; some worn items show another id (a cosmetic override): a
			// mismatch here is a lead for the frame, not proof the container is wrong.
			out.add(VerificationProbe.result(slot.name().toLowerCase(), VerificationProbe.inputs("slot", slot.getSlotIdx()),
				worn, model < 0 ? -1 : model));
		}
		return out;
	}

	/** The bank's occupied-slot count on screen against the container, only while the bank is open. */
	private List<Map<String, Object>> bankResults()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		ItemContainer bank = plugin.client.getItemContainer(InventoryID.BANK);
		Widget used = plugin.client.getWidget(InterfaceID.Bankmain.OCCUPIEDSLOTS);
		if (bank == null || used == null || used.isHidden()) return out;
		int stacks = 0;
		int placeholders = 0;
		for (Item it : bank.getItems())
		{
			if (it == null || it.getId() <= 0) continue;
			stacks++;
			if (it.getQuantity() == 0) placeholders++;
		}
		out.add(VerificationProbe.shown("occupied_slots", VerificationProbe.inputs("stacks", stacks, "placeholders", placeholders),
			stacks, KillContext.widgetNumber(used.getText())));
		return out;
	}

	/** Where the player is: recorded for the frame (minimap, area) to confirm. */
	private List<Map<String, Object>> locationResults()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		WorldPoint p = plugin.client.getLocalPlayer().getWorldLocation();
		if (p == null) return out;
		out.add(VerificationProbe.observed("x", p.getX()));
		out.add(VerificationProbe.observed("y", p.getY()));
		out.add(VerificationProbe.observed("plane", p.getPlane()));
		out.add(VerificationProbe.observed("region_id", p.getRegionID()));
		out.add(VerificationProbe.observed("members_world", plugin.client.getWorldType().contains(WorldType.MEMBERS)));
		return out;
	}

	private static Map<Integer, int[]> containerSlots(ItemContainer c)
	{
		Map<Integer, int[]> m = new LinkedHashMap<>();
		if (c == null) return m;
		Item[] items = c.getItems();
		for (int i = 0; i < items.length; i++)
		{
			if (items[i] != null && items[i].getId() > 0 && items[i].getQuantity() > 0)
				m.put(i, new int[]{items[i].getId(), items[i].getQuantity()});
		}
		return m;
	}

	private static Map<Integer, int[]> widgetSlots(Widget w)
	{
		Map<Integer, int[]> m = new LinkedHashMap<>();
		Widget[] kids = w.getDynamicChildren();
		if (kids == null || kids.length == 0) kids = w.getChildren();
		if (kids == null) return m;
		for (int i = 0; i < kids.length; i++)
		{
			Widget k = kids[i];
			if (k != null && k.getItemId() > 0 && k.getItemQuantity() > 0 && !k.isHidden())
				m.put(i, new int[]{k.getItemId(), k.getItemQuantity()});
		}
		return m;
	}

	/**
	 * Take the next drawn frame as the sample's verification frame: only numeric widget interiors retained, then
	 * PNG-encoded and uploaded off the render thread. Its own row (area "frame") records the tick and
	 * time the frame was drawn. A frame drawn on a later tick is discarded.
	 */
	private void requestVerificationFrame(String ref, int readTick, long readMs, long snapshotRevision)
	{
		final String token = plugin.currentLinkToken();
		final long epoch = plugin.verificationEpoch.get();
		// Keep only known numeric widget interiors. A chat mask alone misses names in overlays and menus.
		final List<VerificationFrame.Region> regions = new ArrayList<>();
		Object[][] fixed = {
			{"hitpoints_orb", InterfaceID.Orbs.HEALTH_TEXT},
			{"prayer_orb", InterfaceID.Orbs.PRAYER_TEXT},
			{"run_energy_orb", InterfaceID.Orbs.RUNENERGY_TEXT},
			{"special_energy_orb", InterfaceID.Orbs.SPECENERGY_TEXT},
			{"occupied_slots", InterfaceID.Bankmain.OCCUPIEDSLOTS}};
		for (Object[] c : fixed) addVerificationRegion(regions, (String) c[0], (int) c[1]);
		for (Object[] c : KillContext.WIDGET_COUNTERS) addVerificationRegion(regions, (String) c[0], (int) c[1]);
		if (regions.isEmpty() || plugin.client.isMenuOpen()) return;
		final int canvasW = Math.max(1, plugin.client.getCanvasWidth());
		final int canvasH = Math.max(1, plugin.client.getCanvasHeight());
		plugin.drawManager.requestNextFrameListener(img ->
		{
			if (epoch != plugin.verificationEpoch.get() || !plugin.grants.verificationFramesEnabled()
				|| plugin.client.getGameState() != GameState.LOGGED_IN || plugin.client.getTickCount() != readTick || plugin.client.isMenuOpen()) return;
			final int frameTick = plugin.client.getTickCount();
			final long frameMs = System.currentTimeMillis();
			BufferedImage rgb = img == null ? null : StoreClips.toRgbFrame(img);
			if (rgb == null) return;
			final BufferedImage scaled = VerificationFrame.retainRegions(rgb, regions, canvasW, canvasH);
			if (scaled == null) return;
			plugin.executor.submit(() ->
			{
				byte[] png = AccountConnectPlugin.encodePng(scaled);
				if (png == null || png.length == 0 || png.length > TradeCapture.MAX_SCREENSHOT_UPLOAD_BYTES) return;
				plugin.clientThread.invoke(() ->
				{
					if (epoch != plugin.verificationEpoch.get() || !plugin.grants.verificationFramesEnabled() || token == null || !token.equals(plugin.currentLinkToken())) return;
					Map<String, Object> row = Fields.of(
						"tick", readTick, "snapshot_revision", snapshotRevision > 0 ? snapshotRevision : null,
						"plugin_version", AccountConnectPlugin.PLUGIN_VERSION, "ref", ref);
					List<Map<String, Object>> results = new ArrayList<>();
					results.add(VerificationProbe.observed("frame_tick", frameTick));
					results.add(VerificationProbe.observed("frame_delay_ms", frameMs - readMs));
					results.add(VerificationProbe.observed("width", scaled.getWidth()));
					results.add(VerificationProbe.observed("height", scaled.getHeight()));
					results.add(VerificationProbe.observed("retained_regions", regions.size()));
					for (Map<String, Object> r : VerificationProbe.rows("frame", "capture", results, row)) plugin.events.emitEvent("verification", r);
					for (Map<String, Object> r : VerificationProbe.rows("screen", "ocr_numeric",
						VerificationFrame.results(regions, scaled.getWidth(), scaled.getHeight(), canvasW, canvasH), row))
						plugin.events.emitEvent("verification", r);
					uploadVerificationFrame(token, png, ref, frameMs);
				});
			});
		});
	}

	private void addVerificationRegion(List<VerificationFrame.Region> regions, String key, int widgetId)
	{
		Widget w = plugin.client.getWidget(widgetId);
		if (w == null || w.isHidden() || w.getBounds() == null) return;
		String text = Text.removeTags(w.getText()).trim();
		// Labels, player names, abbreviated values and unknown formats need their own reviewed reader.
		if (!text.matches("[0-9]+(?:,[0-9]{3})*%?")) return;
		Integer value = KillContext.widgetNumber(text);
		if (value != null) regions.add(new VerificationFrame.Region(key, widgetId, w.getBounds(), value));
	}

	/** POST one verification frame (kind "verify", the sample's ref, the time the frame was drawn). */
	void uploadVerificationFrame(String token, byte[] pngBytes, String ref, long frameMs)
	{
		if (!plugin.uploadAllowed() || !plugin.grants.verificationFramesEnabled() || !Objects.equals(token, plugin.currentLinkToken())) return;
		RequestBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("token", token)
			.addFormDataPart("account_hash", plugin.activeHash == null ? "" : plugin.activeHash)
			.addFormDataPart("kind", "verify")
			.addFormDataPart("ref", ref)
			.addFormDataPart("captured_at", Long.toString(frameMs / 1000L))
			.addFormDataPart("file", "verify-" + ref + ".png", RequestBody.create(TradeCapture.PNG, pngBytes))
			.build();
		String base = plugin.config.apiBaseUrl() == null ? "" : plugin.config.apiBaseUrl().replaceAll("/+$", "");
		Request request = new Request.Builder().url(base + "/screenshot-ingest").post(body).build();
		plugin.okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS BiS verification frame upload failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.isSuccessful()) plugin.events.healthLastMediaUploadMs.set(System.currentTimeMillis());
				}
				finally
				{
					response.close();
				}
			}
		});
	}
}
