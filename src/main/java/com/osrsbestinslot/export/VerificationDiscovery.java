package com.osrsbestinslot.export;

import java.util.*;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/** Samples unknown own-game messages and named result surfaces without claiming their meaning. */
final class VerificationDiscovery
{
    static final int CONTEXT_TICKS = 8;
    static final int MAX_CONTEXT_HOUR = 12, MAX_UNKNOWN_HOUR = 4;
    static final int MAX_PANEL_BATCH = 4;
    static final long CONTEXT_GAP_MS = 30_000L, UNKNOWN_GAP_MS = 900_000L;
    static final Object[][] RESULT_TEXTS = {
        {"clan_wars", InterfaceID.ClanwarsGameover.MESSAGE},
        {"clan_wars", InterfaceID.ClanwarsGameover.TITLE},
        {"temple_trekking", InterfaceID.TrekRewards.TEXT},
        {"temple_trekking", InterfaceID.TrekRewards.DESC},
        {"temple_trekking", InterfaceID.TrekRewards.TITLE}
    };
    static final String[] UNOBSERVABLE_ACTIVITIES = {"intelligence_gathering", "mess", "aldarin_vineyard",
        "keldagrim_tasks", "shayzien_combat_ring", "dorgesh_kaan_trading", "sled_racing", "underwater_agility_thieving"};
    private static final Set<String> COUNTERS = new HashSet<>();
    private static final int[] CONTRACT_VARS = {VarbitID.CON_CONTRACT_HOTSPOT_1_DISPLAY,
        VarbitID.CON_CONTRACT_HOTSPOT_2_DISPLAY, VarbitID.CON_CONTRACT_HOTSPOT_3_DISPLAY,
        VarbitID.CON_CONTRACT_HOTSPOT_4_DISPLAY, VarbitID.CON_CONTRACT_HOTSPOT_5_DISPLAY,
        VarbitID.CON_CONTRACT_HOTSPOT_6_DISPLAY, VarbitID.CON_CONTRACT_HOTSPOT_7_DISPLAY,
        VarbitID.CON_CONTRACT_HOTSPOT_8_DISPLAY, VarbitID.CON_CONTRACT_DISCUSSED};
    static { for (Object[] counter : KillContext.ACTIVITY_COUNTERS) COUNTERS.add((String) counter[0]); }
    private final ArrayDeque<Long> contextSamples = new ArrayDeque<>(), unknownSamples = new ArrayDeque<>();
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    private final Map<Integer, String> widgetTexts = new HashMap<>();
    private String owner, activity, basis;
    private int contextTick = -1;
    private int[] contract;
    private long lastContext = Long.MIN_VALUE / 2, lastUnknown = Long.MIN_VALUE / 2;

    /** Session/account resets discard context, never refill hourly sampling budgets. */
    synchronized void reset()
    {
        owner = activity = basis = null; contextTick = -1; contract = null;
        widgetTexts.clear(); seen.clear();
    }

    synchronized void bind(String token, String account, long epoch)
    {
        String current = token + ":" + account + ":" + epoch;
        if (!current.equals(owner)) { reset(); owner = current; }
    }

    synchronized void counter(Map<String, Object> fields, int tick)
    {
        if (fields == null || !COUNTERS.contains(fields.get("key")) || !(fields.get("from") instanceof Number)
            || !(fields.get("to") instanceof Number) || fields.get("from").equals(fields.get("to"))) return;
        context((String) fields.get("key"), "counter_change", tick);
    }

    private void context(String activity, String basis, int tick)
    { this.activity = activity; this.basis = basis; contextTick = tick; }

    private boolean current(int tick)
    { return activity != null && tick >= contextTick && tick - contextTick <= CONTEXT_TICKS; }

    private void refreshContext(Client client)
    {
        int tick = client.getTickCount();
        for (Object[] row : MinigameLines.SCREENS)
            if (EventVerificationScreen.visible(client, client.getWidget((int) row[1])))
            { context((String) row[0], "visible_widget", tick); break; }
        if (EventVerificationScreen.visible(client, client.getWidget(InterfaceID.ClanwarsHud.INFO)))
            context("clan_wars", "visible_widget", tick);
        if (EventVerificationScreen.visible(client, client.getWidget(InterfaceID.PestStatusOverlay.PEST_STATUS_PORT2)))
            context("pest_control", "visible_widget", tick);
        int[] next = new int[CONTRACT_VARS.length];
        for (int i = 0; i < next.length; i++) next[i] = client.getVarbitValue(CONTRACT_VARS[i]);
        if (contract != null && !Arrays.equals(contract, next))
            context("mahogany_homes", "contract_var_change", tick);
        contract = next;
    }

    synchronized Map<String, Object> message(Client client, ChatMessageType channel, String raw, long now)
    {
        if (channel != ChatMessageType.GAMEMESSAGE && channel != ChatMessageType.SPAM) return null;
        String text = EventVerification.clean(raw);
        if (text == null) return null;
        refreshContext(client);
        boolean known = current(client.getTickCount());
        return sample(client, known ? activity : "unknown", known ? basis : "bounded_unknown_context",
            "system_message", -1, text, now, !known, false);
    }

    /** Known text result widgets and contextual dialogue are sampled on text/visibility transitions. */
    synchronized List<Map<String, Object>> poll(Client client, long now)
    {
        refreshContext(client);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : RESULT_TEXTS)
        {
            if (out.size() >= MAX_PANEL_BATCH) break;
            int id = (int) row[1]; Widget w = client.getWidget(id);
            String text = EventVerificationScreen.visible(client, w) ? EventVerification.clean(w.getText()) : null;
            String previous = widgetTexts.get(id);
            if (text == null) { widgetTexts.remove(id); continue; }
            if (text == null || text.equals(previous)) continue;
            context((String) row[0], "visible_widget", client.getTickCount());
            Map<String, Object> result = sample(client, (String) row[0], "visible_widget", "result_widget", id, text, now, false, true);
            if (result != null) { widgetTexts.put(id, text); out.add(result); }
        }
        for (int id : MinigameLines.DIALOGUE_TEXTS)
        {
            if (out.size() >= MAX_PANEL_BATCH) break;
            Widget w = client.getWidget(id);
            String text = EventVerificationScreen.visible(client, w) ? EventVerification.clean(w.getText()) : null;
            String previous = widgetTexts.get(id);
            if (text == null) { widgetTexts.remove(id); continue; }
            if (text == null || text.equals(previous) || !current(client.getTickCount())
                || MinigameLines.parseDialogue(text) != null) continue;
            Map<String, Object> result = sample(client, activity, basis, "dialogue", id, text, now, false, true);
            if (result != null) { widgetTexts.put(id, text); out.add(result); }
        }
        // A coherent panel opportunity admits its bounded fields together. The gap applies between batches,
        // not between a panel's title and result. Each field still consumes an hourly sample and frame budget.
        if (!out.isEmpty()) lastContext = now;
        return out;
    }

    private Map<String, Object> sample(Client client, String activity, String basis, String surface,
        int widget, String text, long now, boolean unknown, boolean batch)
    {
        ArrayDeque<Long> budget = unknown ? unknownSamples : contextSamples;
        while (!budget.isEmpty() && now - budget.peekFirst() >= VerificationProbe.HOUR_MS) budget.pollFirst();
        seen.entrySet().removeIf(e -> now - e.getValue() >= VerificationProbe.HOUR_MS);
        String key = activity + ":" + surface + ":" + widget + ":" + text;
        if (seen.containsKey(key) || budget.size() >= (unknown ? MAX_UNKNOWN_HOUR : MAX_CONTEXT_HOUR)
            || now - (unknown ? lastUnknown : lastContext) < (unknown ? UNKNOWN_GAP_MS : CONTEXT_GAP_MS)) return null;
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getWorldLocation() == null) return null;
        budget.addLast(now); if (unknown) lastUnknown = now; else if (!batch) lastContext = now;
        seen.put(key, now); if (seen.size() > 64) seen.remove(seen.keySet().iterator().next());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("activity", activity); fields.put("context_basis", basis); fields.put("surface", surface);
        fields.put("widget", widget); fields.put("region_id", client.getLocalPlayer().getWorldLocation().getRegionID());
        fields.put("discovery_status", "unparsed"); fields.put("verification_text", text);
        // Internal admission receipt: PublicTransport never sends this field.
        fields.put("_discovery_at", now);
        return fields;
    }

    /** A source refused by the outer shared budget/queue was never admitted; leave its visible text retryable. */
    synchronized void rejected(Map<String, Object> fields)
    {
        if (fields == null || !(fields.get("_discovery_at") instanceof Long)) return;
        long at = (Long) fields.get("_discovery_at");
        boolean unknown = "bounded_unknown_context".equals(fields.get("context_basis"));
        ArrayDeque<Long> budget = unknown ? unknownSamples : contextSamples;
        budget.removeLastOccurrence(at);
        if (unknown) lastUnknown = budget.isEmpty() ? Long.MIN_VALUE / 2 : budget.peekLast();
        else lastContext = budget.isEmpty() ? Long.MIN_VALUE / 2 : budget.peekLast();
        String key = fields.get("activity") + ":" + fields.get("surface") + ":" + fields.get("widget") + ":" + fields.get("verification_text");
        if (Objects.equals(seen.get(key), at)) seen.remove(key);
        int widget = ((Number) fields.get("widget")).intValue();
        if (Objects.equals(widgetTexts.get(widget), fields.get("verification_text"))) widgetTexts.remove(widget);
    }
}
