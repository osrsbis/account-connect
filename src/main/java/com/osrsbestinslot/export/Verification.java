package com.osrsbestinslot.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

	/** Pending sources already past their delay hold no slot: each is recorded as UNKNOWN, as the poll would have. */
	void evictExpiredSources()
	{
		if (plugin.client == null) return;
		for (EventVerification.Source expired : plugin.eventVerification.room(plugin.client.getTickCount(), new ArrayList<>()))
			eventEvidenceUnknown(expired, expiryReason(expired));
	}

	/** Why a source ran out of its delay: the reason its last look was retried, else what the screen shows now. */
	private String expiryReason(EventVerification.Source source)
	{
		return source.retryReason != null ? source.retryReason : EventVerificationScreen.levelUpUnseenReason(plugin.client, source);
	}

	void emitDiscovery(Map<String, Object> fields)
	{
		if (fields == null) return;
		evictExpiredSources();
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
			if (source.expired(plugin.client.getTickCount())) { eventEvidenceUnknown(source, expiryReason(source)); continue; }
			if (!plugin.grants.verificationFramesEnabled()) { eventEvidenceUnknown(source, "frames_not_granted"); continue; }
			if (verificationFrameBlocked()) { source.retryReason = "frame_blocked"; plugin.eventVerification.offer(source); continue; }
			List<VerificationFrame.Region> regions = EventVerificationScreen.regions(plugin.client, source);
			if (regions.isEmpty()) { source.retryReason = null; plugin.eventVerification.offer(source); continue; }
			// Room is checked on the first look; a frame slot is taken only when a frame is uploaded (uploadFrameCharged).
			if (!source.framePaid && !plugin.verificationProbe.frameRoom(System.currentTimeMillis())) { eventEvidenceUnknown(source, "frame_budget"); continue; }
			// Decided on the first look; the full-window budget is charged only when a full window is actually uploaded.
			if (!source.framePaid && FullWindowFrame.TRIGGERS.contains(source.type))
				source.fullWindowPaid = plugin.grants.fullWindowCaptureAllowed() && plugin.verificationProbe.fullWindowRoom(System.currentTimeMillis());
			source.framePaid = true;
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
				|| plugin.client.getCanvasWidth() != canvasW || plugin.client.getCanvasHeight() != canvasH
				|| !drawnMatchesCanvas(img, canvasW, canvasH)
				|| !EventVerificationScreen.unchanged(plugin.client, regions))
			{
				// A reward screen often settles a tick after its event. Look again while the source is still within its
				// delay; the regions are selected afresh, and only an expired source is reported as changed.
				plugin.clientThread.invoke(() ->
				{
					source.retryReason = "screen_changed_before_frame";
					if (!source.expired(plugin.client.getTickCount()) && plugin.eventVerification.offer(source)) return;
					eventEvidenceUnknown(source, "screen_changed_before_frame");
				});
				return;
			}
			final BufferedImage nativePixels = nativeRegions(regions, canvasW, canvasH);
			BufferedImage rgb = nativePixels != null || img == null ? null : StoreClips.toRgbFrame(img);
			if (nativePixels == null && rgb == null) { plugin.clientThread.invoke(() -> eventEvidenceUnknown(source, "frame_unavailable")); return; }
			// A drawn frame of one colour (a software renderer's black frame) holds no evidence for the regions either.
			if (nativePixels == null && FullWindowFrame.blank(rgb)) { plugin.clientThread.invoke(() -> eventEvidenceUnknown(source, "drawn_blank")); return; }
			final BufferedImage pixels = nativePixels != null ? nativePixels : VerificationFrame.retainRegions(rgb, regions, canvasW, canvasH);
			if (VerificationFrame.regionsBlank(pixels, regions, canvasW, canvasH))
			{
				// Nothing drawn in the selected regions yet: look again within the delay, as for a changed screen.
				plugin.clientThread.invoke(() ->
				{
					source.retryReason = "regions_blank";
					if (!source.expired(plugin.client.getTickCount()) && plugin.eventVerification.offer(source)) return;
					eventEvidenceUnknown(source, "regions_blank");
				});
				return;
			}
			final String pixelSource = nativePixels != null ? "client_buffer" : "drawn_frame";
			final long frameMs = System.currentTimeMillis();
			// The full window comes from the drawn frame: in GPU mode only it holds the 3D scene.
			final BufferedImage drawn = source.fullWindowPaid && img != null ? (rgb != null ? rgb : StoreClips.toRgbFrame(img)) : null;
			final boolean drawnBlank = drawn != null && FullWindowFrame.blank(drawn);
			final BufferedImage full = drawn;
			final String fullRef = full == null ? null : randomRef();
			plugin.executor.submit(() ->
			{
				byte[] png = AccountConnectPlugin.encodePng(pixels);
				byte[] fullPng = full == null || drawnBlank ? null : AccountConnectPlugin.encodePng(full);
				plugin.clientThread.invoke(() ->
				{
					if (!eventEvidenceCurrent(source)) return;
					if (png == null || png.length == 0 || png.length > TradeCapture.MAX_SCREENSHOT_UPLOAD_BYTES)
					{ eventEvidenceUnknown(source, "frame_encoding_failed"); return; }
					FrameOwner owner = new FrameOwner(source.token, source.account, source.epoch);
					if (!ownerCurrent(owner)) return;
					// A trade or bank PIN screen opened while the frame was encoded: nothing of this frame is uploaded.
					if (verificationFrameBlocked()) { eventEvidenceUnknown(source, "frame_blocked"); return; }
					// The frame slot is taken here, for a frame that is about to be uploaded, never earlier.
					if (!plugin.verificationProbe.takeFrame(System.currentTimeMillis())) { eventEvidenceUnknown(source, "frame_budget"); return; }
					Map<String, Object> common = source.common(readTick);
					common.put("plugin_version", AccountConnectPlugin.PLUGIN_VERSION); common.put("ref", ref);
					List<Map<String, Object>> capture = Arrays.asList(
						VerificationProbe.observed("frame_tick", readTick), VerificationProbe.observed("frame_delay_ms", frameMs - readMs),
						VerificationProbe.observed("width", pixels.getWidth()), VerificationProbe.observed("height", pixels.getHeight()),
						VerificationProbe.observed("pixel_source", pixelSource), VerificationProbe.observed("scope", "regions"),
						VerificationProbe.observed("paired_ref", fullRef));
					for (Map<String, Object> row : VerificationProbe.rows("frame", "capture", capture, common)) plugin.events.emitEvent("verification", row);
					List<Map<String, Object>> results = VerificationFrame.results(regions, pixels.getWidth(), pixels.getHeight(), canvasW, canvasH);
					results.addAll(EventVerificationScreen.missing(source, regions, "source_field_not_visible"));
					emitEventScreenRows(results, common);
					uploadVerificationFrame(owner, png, ref, frameMs, common, 1);
					if (full != null) emitFullWindow(owner, source.common(readTick), "ocr_event", full, drawnBlank, fullPng, fullRef, ref, readTick, frameMs - readMs, frameMs, regions, canvasW, canvasH);
				});
			});
		});
	}

	/**
	 * The full-window frame of an event: its own ref, a capture row with scope "full_window", the region frame's ref,
	 * and the same screen checks with their coordinates in this image, so the server reads both modes the same way.
	 */
	private void emitFullWindow(FrameOwner owner, Map<String, Object> base, String screenCheck, BufferedImage full, boolean drawnBlank, byte[] png, String fullRef, String regionRef,
		int readTick, long delayMs, long frameMs, List<VerificationFrame.Region> regions, int canvasW, int canvasH)
	{
		Map<String, Object> common = new LinkedHashMap<>(base);
		common.put("plugin_version", AccountConnectPlugin.PLUGIN_VERSION); common.put("ref", fullRef);
		boolean encoded = png != null && png.length > 0 && png.length <= TradeCapture.MAX_SCREENSHOT_UPLOAD_BYTES;
		// The budget is charged here, for a full window that will be uploaded, never for one that was not taken.
		boolean budget = encoded && ownerCurrent(owner) && plugin.grants.fullWindowCaptureAllowed() && plugin.verificationProbe.takeFullWindow(System.currentTimeMillis());
		List<Map<String, Object>> capture = new ArrayList<>(Arrays.asList(
			VerificationProbe.observed("frame_tick", readTick), VerificationProbe.observed("frame_delay_ms", delayMs),
			VerificationProbe.observed("width", full.getWidth()), VerificationProbe.observed("height", full.getHeight()),
			VerificationProbe.observed("pixel_source", "drawn_frame"), VerificationProbe.observed("scope", "full_window"),
			VerificationProbe.observed("paired_ref", regionRef), VerificationProbe.observed("gpu", plugin.client.isGpu()),
			VerificationProbe.observed("encoded", encoded), VerificationProbe.observed("drawn_blank", drawnBlank),
			VerificationProbe.observed("uploaded", budget)));
		for (Map<String, Object> row : VerificationProbe.rows("frame", "capture", capture, common)) plugin.events.emitEvent("verification", row);
		if (!budget) return;
		List<Map<String, Object>> checks = VerificationFrame.results(regions, full.getWidth(), full.getHeight(), canvasW, canvasH);
		if ("ocr_event".equals(screenCheck)) emitEventScreenRows(checks, common);
		else for (Map<String, Object> r : VerificationProbe.rows("screen", screenCheck, checks, common)) plugin.events.emitEvent("verification", r);
		uploadVerificationFrame(owner, png, fullRef, frameMs, common, 1, true);
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
		// Room only: the slot is taken when the sample's frame is uploaded.
		boolean frame = !blocked && plugin.uploadAllowed() && plugin.grants.verificationFramesEnabled() && plugin.verificationProbe.frameRoom(nowMs);
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
		boolean discrepancy = false;
		for (Map<String, Object> row : rows)
		{
			plugin.events.emitEvent("verification", row);
			if (row.get("mismatches") instanceof Number && ((Number) row.get("mismatches")).intValue() > 0) discrepancy = true;
		}
		if (frame) requestVerificationFrame(ref, tick, nowMs, plugin.lastSnapshotCapturedAtMs,
			discrepancy && plugin.grants.fullWindowCaptureAllowed() && plugin.verificationProbe.fullWindowRoom(nowMs));
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
		int since = plugin.client.getTickCount() - plugin.worldLoginTick;
		int hp = plugin.client.getBoostedSkillLevel(Skill.HITPOINTS);
		int prayer = plugin.client.getBoostedSkillLevel(Skill.PRAYER);
		int run = plugin.client.getEnergy() / 100;
		int spec = plugin.client.getVarpValue(VarPlayerID.SA_ENERGY);
		Integer hpOrb = widgetInt(InterfaceID.Orbs.HEALTH_TEXT), prayerOrb = widgetInt(InterfaceID.Orbs.PRAYER_TEXT),
			runOrb = widgetInt(InterfaceID.Orbs.RUNENERGY_TEXT), specOrb = widgetInt(InterfaceID.Orbs.SPECENERGY_TEXT);
		out.add(VerificationProbe.shown("hitpoints_orb", null, hp, VerificationProbe.orbUndrawn(since, hp, hpOrb) ? null : hpOrb));
		out.add(VerificationProbe.shown("prayer_orb", null, prayer, VerificationProbe.orbUndrawn(since, prayer, prayerOrb) ? null : prayerOrb));
		out.add(VerificationProbe.shown("run_energy_orb", VerificationProbe.inputs("energy_raw", plugin.client.getEnergy()),
			run, VerificationProbe.orbUndrawn(since, run, runOrb) ? null : runOrb));
		out.add(VerificationProbe.shown("special_energy_orb", VerificationProbe.inputs("sa_energy", spec), spec / 10,
			VerificationProbe.orbUndrawn(since, spec / 10, specOrb) ? null : specOrb));
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
	void requestVerificationFrame(String ref, int readTick, long readMs, long snapshotRevision, boolean fullWindow)
	{
		final String token = plugin.currentLinkToken();
		final long epoch = plugin.verificationEpoch.get();
		final FrameOwner owner = currentOwner(token);
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
		// The sample rows already carry this ref: every way the frame is not taken leaves a capture row saying why.
		if (regions.isEmpty()) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token,"no_numeric_region", null); return; }
		if (plugin.client.isMenuOpen()) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token,"menu_open", null); return; }
		final int canvasW = Math.max(1, plugin.client.getCanvasWidth());
		final int canvasH = Math.max(1, plugin.client.getCanvasHeight());
		plugin.drawManager.requestNextFrameListener(img ->
		{
			if (epoch != plugin.verificationEpoch.get() || !plugin.grants.verificationFramesEnabled()
				|| plugin.client.getGameState() != GameState.LOGGED_IN) return;
			// The sample is read on the game tick; its next drawn frame can already carry the next tick number (seen on every
			// sample on the rig, 11-07). That frame is used only when every selected widget still shows the value read
			// for the sample, in the same place; its rows then carry the frame's tick, which is what the pixels show.
			int now = plugin.client.getTickCount();
			// A trade or bank PIN screen opened after the sample was selected blocks the frame as it would have before.
			String changed = now != readTick && now != readTick + 1 ? "frame_on_later_tick" : plugin.client.isMenuOpen() ? "menu_open"
				: verificationFrameBlocked() ? "frame_blocked"
				: plugin.client.getCanvasWidth() != canvasW || plugin.client.getCanvasHeight() != canvasH ? "canvas_resized"
				: !drawnMatchesCanvas(img, canvasW, canvasH) ? "frame_size_mismatch"
				: now == readTick + 1 && !regionsUnchanged(regions) ? "screen_changed_before_frame" : null;
			if (changed != null) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token,changed, now); return; }
			final int frameTick = now;
			final long frameMs = System.currentTimeMillis();
			final BufferedImage nativePixels = nativeRegions(regions, canvasW, canvasH);
			BufferedImage rgb = nativePixels != null || img == null ? null : StoreClips.toRgbFrame(img);
			if (nativePixels == null && rgb == null) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token,"frame_unavailable", frameTick); return; }
			if (nativePixels == null && FullWindowFrame.blank(rgb)) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token,"drawn_blank", frameTick); return; }
			final BufferedImage scaled = nativePixels != null ? nativePixels : VerificationFrame.retainRegions(rgb, regions, canvasW, canvasH);
			if (scaled == null) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token,"frame_unavailable", frameTick); return; }
			if (VerificationFrame.regionsBlank(scaled, regions, canvasW, canvasH)) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token, "regions_blank", frameTick); return; }
			final String pixelSource = nativePixels != null ? "client_buffer" : "drawn_frame";
			// A sample that found a discrepancy also keeps the whole window (the drawn frame as drawn), linked by ref.
			final BufferedImage full = fullWindow && img != null ? (rgb != null ? rgb : StoreClips.toRgbFrame(img)) : null;
			final boolean drawnBlank = full != null && FullWindowFrame.blank(full);
			final String fullRef = full == null ? null : randomRef();
			plugin.executor.submit(() ->
			{
				byte[] png, fullPng;
				try
				{
					png = AccountConnectPlugin.encodePng(scaled);
					fullPng = full == null || drawnBlank ? null : AccountConnectPlugin.encodePng(full);
				}
				catch (RuntimeException e)
				{
					// An exception in a submitted task is otherwise lost in its Future.
					sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token,"frame_encoding_failed", frameTick);
					return;
				}
				if (png == null || png.length == 0 || png.length > TradeCapture.MAX_SCREENSHOT_UPLOAD_BYTES)
				{ sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token,"frame_encoding_failed", frameTick); return; }
				plugin.clientThread.invoke(() ->
				{
					if (epoch != plugin.verificationEpoch.get() || !plugin.grants.verificationFramesEnabled() || token == null || !token.equals(plugin.currentLinkToken())) return;
					if (!ownerCurrent(owner)) return;
					if (verificationFrameBlocked()) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token, "frame_blocked", frameTick); return; }
					// The frame slot is taken here, for a frame that is about to be uploaded, never earlier.
					if (!plugin.verificationProbe.takeFrame(System.currentTimeMillis())) { sampleFrameSkipped(ref, readMs, readTick, snapshotRevision, epoch, token, "frame_budget", frameTick); return; }
					Map<String, Object> row = Fields.of(
						"tick", frameTick, "snapshot_revision", snapshotRevision > 0 ? snapshotRevision : null,
						"plugin_version", AccountConnectPlugin.PLUGIN_VERSION, "ref", ref);
					List<Map<String, Object>> results = new ArrayList<>();
					results.add(VerificationProbe.observed("frame_tick", frameTick));
					results.add(VerificationProbe.observed("sample_tick", readTick));
					results.add(VerificationProbe.observed("frame_delay_ms", frameMs - readMs));
					results.add(VerificationProbe.observed("width", scaled.getWidth()));
					results.add(VerificationProbe.observed("height", scaled.getHeight()));
					results.add(VerificationProbe.observed("pixel_source", pixelSource));
					results.add(VerificationProbe.observed("retained_regions", regions.size()));
					results.add(VerificationProbe.observed("scope", "regions"));
					results.add(VerificationProbe.observed("paired_ref", fullRef));
					for (Map<String, Object> r : VerificationProbe.rows("frame", "capture", results, row)) plugin.events.emitEvent("verification", r);
					for (Map<String, Object> r : VerificationProbe.rows("screen", "ocr_numeric",
						VerificationFrame.results(regions, scaled.getWidth(), scaled.getHeight(), canvasW, canvasH), row))
						plugin.events.emitEvent("verification", r);
					uploadVerificationFrame(owner, png, ref, frameMs, row, 1);
					if (full != null) emitFullWindow(owner, row, "ocr_numeric", full, drawnBlank, fullPng, fullRef, ref, frameTick, frameMs - readMs, frameMs,
						regions, canvasW, canvasH);
				});
			});
		});
	}

	/**
	 * The drawn frame has the size the widget bounds refer to: the canvas, or in stretched mode the stretched size, times
	 * the display scale. During a window resize the drawn frame can lag the canvas (rig, 11-07: a 1069x730 frame for a
	 * 1119x760 canvas); the evidence regions, scaled by the size ratio, would then point at the wrong pixels, so such a
	 * frame is never used. A missing frame passes here and is handled by the caller.
	 */
	boolean drawnMatchesCanvas(java.awt.Image img, int canvasW, int canvasH)
	{
		if (img == null) return true;
		int w = img.getWidth(null), h = img.getHeight(null);
		int baseW = canvasW, baseH = canvasH;
		if (plugin.client.isStretchedEnabled())
		{
			java.awt.Dimension d = plugin.client.getStretchedDimensions();
			if (d == null) return false;
			baseW = d.width; baseH = d.height;
		}
		// RuneLite scales the drawn image by the display (DPI) transform: (int) (w * scale + 0.5) on the CPU path,
		// (int) (w * scale) with the GPU plugin. Accept either complete dimension pair, without an extra pixel.
		double sx = 1, sy = 1;
		java.awt.Canvas canvas = plugin.client.getCanvas();
		java.awt.GraphicsConfiguration gc = canvas == null ? null : canvas.getGraphicsConfiguration();
		if (gc != null) { sx = gc.getDefaultTransform().getScaleX(); sy = gc.getDefaultTransform().getScaleY(); }
		double expectedW = baseW * sx, expectedH = baseH * sy;
		return (w == (int) (expectedW + 0.5) && h == (int) (expectedH + 0.5))
			|| (w == (int) expectedW && h == (int) expectedH);
	}

	/** Every numeric region's widget still shows the sample's value, in the same bounds. */
	private boolean regionsUnchanged(List<VerificationFrame.Region> regions)
	{
		for (VerificationFrame.Region r : regions)
		{
			Widget w = plugin.client.getWidget(r.widget);
			if (w == null || w.isHidden() || w.getBounds() == null || !r.bounds.equals(w.getBounds())) return false;
			if (!java.util.Objects.equals(r.value, KillContext.widgetNumber(Text.removeTags(w.getText()).trim()))) return false;
		}
		return true;
	}

	/**
	 * A random sample whose frame was charged but not taken: one capture row under its ref with the reason
	 * (`frame_skipped`), so a ref with no image is explained. Written on the client thread, only for the same grant.
	 */
	private void sampleFrameSkipped(String ref, long readMs, int readTick, long snapshotRevision, long epoch, String token, String reason, Integer frameTick)
	{
		Runnable write = () ->
		{
			if (epoch != plugin.verificationEpoch.get() || !plugin.grants.verificationGranted() || token == null || !token.equals(plugin.currentLinkToken())) return;
			Map<String, Object> row = Fields.of("tick", readTick, "snapshot_revision", snapshotRevision > 0 ? snapshotRevision : null,
				"plugin_version", AccountConnectPlugin.PLUGIN_VERSION, "ref", ref);
			List<Map<String, Object>> results = new ArrayList<>();
			if (frameTick != null) results.add(VerificationProbe.observed("frame_tick", frameTick));
			results.add(VerificationProbe.observed("frame_skipped", reason));
			if ("drawn_blank".equals(reason)) { results.add(VerificationProbe.observed("pixel_source", "drawn_frame")); results.add(VerificationProbe.observed("drawn_blank", true)); }
			results.add(VerificationProbe.observed("uploaded", false));
			for (Map<String, Object> r : VerificationProbe.rows("frame", "capture", results, row)) plugin.events.emitEvent("verification", r);
		};
		if (plugin.clientThread != null) plugin.clientThread.invoke(write); else write.run();
	}

	/** The regions from the client's native pixel buffer (exact font pixels at any stretch or display scale), or null. */
	private BufferedImage nativeRegions(List<VerificationFrame.Region> regions, int canvasW, int canvasH)
	{
		net.runelite.api.BufferProvider buffer = plugin.client.getBufferProvider();
		if (buffer == null) return null;
		return VerificationFrame.nativeRegions(buffer.getPixels(), buffer.getWidth(), buffer.getHeight(), regions, canvasW, canvasH);
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

	/**
	 * Delays before each retry of a frame upload the server provably did not store: a refusal the application names
	 * before any storage (see RETRYABLE_REFUSALS). The server keeps a new object per POST, so a network failure (which
	 * can follow a delivered body), a bare 5xx or a proxy error is not retried: it could store the same frame twice.
	 */
	static final long[] UPLOAD_RETRY_DELAYS_MS = {10_000L, 60_000L};

	/**
	 * The screenshot ingest's own error texts for refusals it makes before writing anything (rate limits, capacity, an
	 * unreadable staff or policy check). Only a 429 or 503 whose JSON body carries one of these exact texts is retried.
	 */
	static final Set<String> RETRYABLE_REFUSALS = new HashSet<>(Arrays.asList(
		"Too many uploads \u2014 try later.", "Too many uploads for this link.", "Upload capacity reached \u2014 try later.",
		"Capture identity unavailable \u2014 retry.", "Collection policy unavailable \u2014 retry."));

	/**
	 * Who a frame belongs to, fixed when it was taken: the link token, the account and the verification epoch. Every
	 * attempt and its outcome row use these, never the client's state at upload time; a changed token, account or
	 * epoch (a revoke and regrant included) ends the upload.
	 */
	static final class FrameOwner
	{
		final String token, account;
		final long epoch;

		FrameOwner(String token, String account, long epoch)
		{
			this.token = token;
			this.account = account;
			this.epoch = epoch;
		}
	}

	FrameOwner currentOwner(String token)
	{
		return new FrameOwner(token, plugin.activeHash, plugin.verificationEpoch.get());
	}

	/** The frame's owner is still the one linked, logged in and granted now. */
	boolean ownerCurrent(FrameOwner owner)
	{
		if (owner == null || owner.token == null || owner.account == null || !plugin.uploadAllowed()
			|| !plugin.grants.verificationFramesEnabled() || owner.epoch != plugin.verificationEpoch.get()
			|| !owner.token.equals(plugin.currentLinkToken()) || !owner.account.equals(plugin.activeHash)) return false;
		long live = plugin.client == null ? -1L : plugin.client.getAccountHash();
		return live == -1L || owner.account.equals(Long.toString(live));
	}

	private okhttp3.OkHttpClient frameHttp;
	private okhttp3.OkHttpClient frameHttpBase;

	/**
	 * The shared client without OkHttp's own retry on a connection failure: RuneLite's client keeps it on, and OkHttp
	 * then re-sends a POST on a pooled connection after the server already received it, which stores the frame twice.
	 * Same pool and dispatcher.
	 */
	okhttp3.OkHttpClient frameHttp()
	{
		if (frameHttp == null || frameHttpBase != plugin.okHttpClient)
		{
			frameHttpBase = plugin.okHttpClient;
			frameHttp = plugin.okHttpClient.newBuilder().retryOnConnectionFailure(false).build();
		}
		return frameHttp;
	}

	/** Test seam: the client frame uploads use, as frameHttp() would derive it. */
	void frameHttpForTest(okhttp3.OkHttpClient client)
	{
		frameHttp = client;
		frameHttpBase = plugin.okHttpClient;
	}

	/** POST one verification frame (kind "verify", the sample's ref, the time the frame was drawn). */
	void uploadVerificationFrame(String token, byte[] pngBytes, String ref, long frameMs)
	{
		Map<String, Object> common = Fields.of("plugin_version", AccountConnectPlugin.PLUGIN_VERSION, "ref", ref);
		uploadVerificationFrame(currentOwner(token), pngBytes, ref, frameMs, common, 1);
	}

	/**
	 * POST one verification frame and record what the server did with it, on its own row (area "frame", check
	 * "upload", the frame's ref and source fields): stored (an id, or pending after durable storage), dropped (a
	 * deliberate 200 drop: paused, not granted, screenshots off; never retried), refused (another 4xx), failed (still
	 * refused before storage after the bounded retries) or unconfirmed (the answer was lost or unreadable; the frame may
	 * or may not be stored). A capture row without a stored image is then explained.
	 */
	void uploadVerificationFrame(FrameOwner owner, byte[] pngBytes, String ref, long frameMs, Map<String, Object> common, int attempt)
	{
		uploadVerificationFrame(owner, pngBytes, ref, frameMs, common, attempt, false);
	}

	/**
	 * As above; a full window (an unmasked whole canvas) also needs the staff grant at EVERY attempt, so a retry after the
	 * grant was withdrawn (or the API base changed) sends nothing and its upload row says `dropped` `staff_grant_cleared`.
	 */
	void uploadVerificationFrame(FrameOwner owner, byte[] pngBytes, String ref, long frameMs, Map<String, Object> common, int attempt, boolean fullWindow)
	{
		if (!ownerCurrent(owner)) return;
		if (fullWindow && !plugin.grants.fullWindowCaptureAllowed())
		{
			uploadOutcome(owner, pngBytes, ref, frameMs, common, attempt, "dropped", "staff_grant_cleared", null, true);
			return;
		}
		RequestBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("token", owner.token)
			.addFormDataPart("account_hash", owner.account)
			.addFormDataPart("kind", "verify")
			.addFormDataPart("ref", ref)
			.addFormDataPart("captured_at", Long.toString(frameMs / 1000L))
			.addFormDataPart("file", "verify-" + ref + ".png", RequestBody.create(TradeCapture.PNG, pngBytes))
			.build();
		String base = plugin.config.apiBaseUrl() == null ? "" : plugin.config.apiBaseUrl().replaceAll("/+$", "");
		Request request = new Request.Builder().url(base + "/screenshot-ingest").post(body).build();
		frameHttp().newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS BiS verification frame upload failed", e);
				// A network failure can come after the server received the whole body (OkHttp reports a dropped
				// connection to a host that stopped listening as ConnectException), so none is proof of "not stored".
				uploadOutcome(owner, pngBytes, ref, frameMs, common, attempt, "unconfirmed", "no_answer", null, fullWindow);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					String[] outcome = uploadResult(plugin.gson, response.code(), response.body() == null ? null : response.body().string());
					if ("stored".equals(outcome[0])) plugin.events.healthLastMediaUploadMs.set(System.currentTimeMillis());
					uploadOutcome(owner, pngBytes, ref, frameMs, common, attempt, outcome[0], outcome[1], response.code(), fullWindow);
				}
				catch (IOException | RuntimeException e)
				{
					uploadOutcome(owner, pngBytes, ref, frameMs, common, attempt, "unconfirmed", "unreadable_response", response.code(), fullWindow);
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	/**
	 * The server's answer to a frame upload: {status, reason}. Stored is ok with an id, or ok and pending (the bytes are
	 * in storage and the row is queued). A 200 with "dropped" is a deliberate drop and is never retried. Only a 429 or 503
	 * whose body is the application's own named refusal before storage is retried; a bare 429/503 (a proxy, an edge
	 * limit) or any other 5xx may follow a stored object and is unconfirmed. Any other answer, a 2xx without either shape
	 * included, is not proof of storage.
	 */
	static String[] uploadResult(com.google.gson.Gson gson, int code, String body)
	{
		Map<?, ?> json = null;
		try { Object parsed = body == null ? null : gson.fromJson(body, Object.class); if (parsed instanceof Map) json = (Map<?, ?>) parsed; }
		catch (RuntimeException ignored) { }
		if (code == 429 || code == 503)
			return json != null && Boolean.FALSE.equals(json.get("ok")) && RETRYABLE_REFUSALS.contains(json.get("error"))
				? new String[]{"retry", "http_" + code} : new String[]{"unconfirmed", "http_" + code};
		if (code >= 500) return new String[]{"unconfirmed", "http_" + code};
		if (code < 200 || code >= 300) return new String[]{"refused", "http_" + code};
		if (json == null || !Boolean.TRUE.equals(json.get("ok"))) return new String[]{"unconfirmed", "unrecognised_answer"};
		if (json.get("dropped") instanceof String)
		{
			String why = VerificationProbe.safeKey((String) json.get("dropped"));
			return new String[]{"dropped", why == null ? "unnamed" : why};
		}
		if (json.get("id") instanceof Number) return new String[]{"stored", "id"};
		if (Boolean.TRUE.equals(json.get("pending"))) return new String[]{"stored", "pending"};
		return new String[]{"unconfirmed", "unrecognised_answer"};
	}

	private void uploadOutcome(FrameOwner owner, byte[] pngBytes, String ref, long frameMs, Map<String, Object> common, int attempt,
		String status, String reason, Integer httpCode, boolean fullWindow)
	{
		if ("retry".equals(status) && attempt <= UPLOAD_RETRY_DELAYS_MS.length && plugin.executor != null && plugin.clientThread != null)
		{
			// The retry starts on the client thread so the owner (and, for a full window, staff) check reads consistent client state.
			plugin.executor.schedule(() -> plugin.clientThread.invoke(() -> uploadVerificationFrame(owner, pngBytes, ref, frameMs, common, attempt + 1, fullWindow)),
				UPLOAD_RETRY_DELAYS_MS[attempt - 1], java.util.concurrent.TimeUnit.MILLISECONDS);
			return;
		}
		if (plugin.clientThread == null) return;
		final String finalStatus = "retry".equals(status) ? "failed" : status;
		plugin.clientThread.invoke(() ->
		{
			// The outcome row is the frame owner's: it is not written under another token, account or epoch.
			if (!plugin.grants.verificationGranted() || owner.epoch != plugin.verificationEpoch.get()
				|| !Objects.equals(owner.token, plugin.currentLinkToken()) || !Objects.equals(owner.account, plugin.activeHash)) return;
			Map<String, Object> row = new LinkedHashMap<>(common);
			List<Map<String, Object>> results = new ArrayList<>(Arrays.asList(
				VerificationProbe.observed("upload_status", finalStatus), VerificationProbe.observed("upload_reason", reason),
				VerificationProbe.observed("attempts", attempt)));
			if (httpCode != null) results.add(VerificationProbe.observed("http_status", httpCode));
			for (Map<String, Object> r : VerificationProbe.rows("frame", "upload", results, row)) plugin.events.emitEvent("verification", r);
		});
	}
}
