package com.osrsbestinslot.export;

import java.awt.Rectangle;
import java.util.*;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/** Read-only selection of event-specific game widgets. No unrelated chat or whole-screen fallback. */
final class EventVerificationScreen
{
    static final int MAX_REGIONS = 16;

    static List<VerificationFrame.Region> regions(Client client, EventVerification.Source event)
    {
        List<VerificationFrame.Region> out = new ArrayList<>();
        if (event.text != null)
        {
            List<Widget> matches = new ArrayList<>();
            if ("verification_discovery".equals(event.type) && event.fields.get("widget") instanceof Number
                && ((Number) event.fields.get("widget")).intValue() >= 0)
                match(client, client.getWidget(((Number) event.fields.get("widget")).intValue()), event.text, matches);
            else
            {
                for (int id = InterfaceID.Chatbox.LINE0; id <= InterfaceID.Chatbox.LINE199; id++)
                    match(client, client.getWidget(id), event.text, matches);
                // The static LINEn widgets hold only the sender; the message text is in the dynamic children of the
                // chat scroll area (RuneLite ChatHistoryPlugin reads it there).
                Widget scroll = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
                Widget[] lines = scroll == null ? null : scroll.getDynamicChildren();
                if (lines != null) for (Widget line : lines) match(client, line, event.text, matches);
                for (int id : MinigameLines.DIALOGUE_TEXTS) match(client, client.getWidget(id), event.text, matches);
            }
            // Repeated identical lines are ambiguous, not proof of the current event.
            if (matches.size() == 1) addMessage(out, matches.get(0), event);
        }
        if ("level_up".equals(event.type))
        {
            // Only the dialogue of the event's own skill. Its number is not filtered, so a wrong level stays a mismatch;
            // another skill's dialogue (several level-ups at once) is not this event's evidence.
            for (int id : LEVEL_TEXTS)
            {
                Widget w = client.getWidget(id);
                String text = w == null ? null : EventVerification.clean(w.getText());
                if (visible(client, w) && levelDialogueSkill(text, event.fields.get("skill")) == Boolean.TRUE)
                    add(out, w, "level", event.fields.get("level"), "level_up", "level",
                        VerificationProbe.inputs("skill", VerificationProbe.safeKey(String.valueOf(event.fields.get("skill")))));
            }
        }
        if ("minigame".equals(event.type) && event.fields.get("screen") instanceof Map)
        {
            Map<?, ?> screen = (Map<?, ?>) event.fields.get("screen");
            for (Object[] row : MinigameLines.SCREENS)
            {
                if (!Objects.equals(row[0], event.fields.get("minigame"))) continue;
                Widget w = client.getWidget((int) row[1]);
                if (visible(client, w) && screen.get(row[2]) instanceof Number)
                    add(out, w, (String) row[2], screen.get(row[2]), "integer", "screen." + row[2], null);
            }
        }
        if ("loot".equals(event.type)) reward(client, event, out);
        return out.size() > MAX_REGIONS ? new ArrayList<>(out.subList(0, MAX_REGIONS)) : out;
    }

    static final int[] LEVEL_TEXTS = {InterfaceID.LevelupDisplay.TEXT1, InterfaceID.LevelupDisplay.TEXT2};
    private static final Pattern LEVEL_LINE = Pattern.compile("(?i)^(?:.*\\s)?your ([a-z]+) level is now [0-9,]+[.!]?.*$");

    /** TRUE when the text is the level line of this skill, FALSE when it is another skill's, null when it is not one. */
    static Boolean levelDialogueSkill(String text, Object skill)
    {
        if (text == null) return null;
        java.util.regex.Matcher m = LEVEL_LINE.matcher(text);
        if (!m.matches()) return null;
        return skill instanceof String && m.group(1).trim().equalsIgnoreCase(((String) skill).trim());
    }

    /** Why a level-up source had no evidence: another skill's level dialogue was on screen, or nothing was. */
    static String levelUpUnseenReason(Client client, EventVerification.Source event)
    {
        if (!"level_up".equals(event.type)) return "source_not_visible";
        for (int id : LEVEL_TEXTS)
        {
            Widget w = client.getWidget(id);
            if (visible(client, w) && levelDialogueSkill(EventVerification.clean(w.getText()), event.fields.get("skill")) == Boolean.FALSE)
                return "other_skill_dialogue";
        }
        return "source_not_visible";
    }

    private static void match(Client client, Widget w, String text, List<Widget> out)
    {
        if (visible(client, w) && text.equals(EventVerification.clean(w.getText()))) out.add(w);
    }

    static boolean visible(Client client, Widget w)
    {
        if (w == null || w.isHidden() || w.getBounds() == null) return false;
        Rectangle b = w.getBounds();
        if (b.width <= 0 || b.height <= 0 || b.width > 1200 || b.height > 500
            || !new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight()).contains(b)) return false;
        // A clipped chat line cannot be treated as the complete message.
        Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Widget p = w.getParent(); p != null && seen.add(p); p = p.getParent())
            if (p.isHidden() || (p.getBounds() != null && !p.getBounds().contains(b))) return false;
        return true;
    }

    private static void addMessage(List<VerificationFrame.Region> out, Widget widget, EventVerification.Source event)
    {
        if ("verification_discovery".equals(event.type))
            add(out, widget, "message", event.fields.get("verification_text"), "discovery_text", "verification_text",
                VerificationProbe.inputs("activity", event.fields.get("activity"), "surface", event.fields.get("surface")));
        else if ("kill_count".equals(event.type))
            add(out, widget, "count", event.fields.get("count"), "kill_count", "count", null);
        else if ("minigame".equals(event.type))
        {
            Object[][][] tables = {MinigameLines.LINES, MinigameLines.DIALOGUE};
            for (int t = 0; t < tables.length; t++) for (int i = 0; i < tables[t].length; i++)
            {
                Object[] pattern = tables[t][i];
                if (!Pattern.compile((String) pattern[3]).matcher(event.text).matches()) continue;
                for (String field : new String[]{"count", "total", "result"})
                    if (event.fields.get(field) != null)
                        add(out, widget, field, event.fields.get(field), "minigame", field,
                            VerificationProbe.inputs("pattern_table", t == 0 ? "line" : "dialogue", "pattern_index", i));
                return;
            }
        }
        else if ("level_up".equals(event.type))
            add(out, widget, "level", event.fields.get("level"), "level_up", "level",
                VerificationProbe.inputs("skill", VerificationProbe.safeKey(String.valueOf(event.fields.get("skill")))));
        else if (event.fields.get("verification_text") instanceof String)
            add(out, widget, "message", event.fields.get("verification_text"), "text", "verification_text", null);
    }

    private static void add(List<VerificationFrame.Region> out, Widget w, String key, Object value,
        String reader, String field, Map<String, Object> extra)
    {
        if (value == null || out.size() >= MAX_REGIONS) return;
        Map<String, Object> inputs = VerificationProbe.inputs("reader", reader, "field", field);
        if (extra != null) inputs.putAll(extra);
        VerificationFrame.Region region = new VerificationFrame.Region(key, w.getId(), w.getBounds(), value,
            inputs, EventVerification.clean(w.getText()));
        region.sourceWidget = w;
        region.sourceItemQuantity = w.getItemQuantity();
        out.add(region);
    }

    private static void reward(Client client, EventVerification.Source event, List<VerificationFrame.Region> out)
    {
        int group;
        String source = String.valueOf(event.fields.get("source_name"));
        switch (source)
        {
            case "Chambers of Xeric": group = InterfaceID.RAIDS_REWARDS; break;
            case "Theatre of Blood": group = InterfaceID.TOB_CHESTS; break;
            case "Tombs of Amascut": group = InterfaceID.TOA_CHESTS; break;
            case "Barrows": group = InterfaceID.BARROWS_REWARD; break;
            case "Clue Scroll": group = InterfaceID.TRAIL_REWARDSCREEN; break;
            case "Fortis Colosseum": group = InterfaceID.COLOSSEUM_REWARD_CHEST_2; break;
            case "Lunar Chest": group = InterfaceID.PMOON_REWARD; break;
            case "Loot Chest": group = InterfaceID.WILDY_LOOT_CHEST; break;
            default: return; // Ground drops need a visible source; do not photograph an unrelated panel.
        }
        if (!(event.fields.get("items") instanceof List)) return;
        Map<Integer, Number> quantities = new LinkedHashMap<>();
        for (Object value : (List<?>) event.fields.get("items")) if (value instanceof Map)
        {
            Map<?, ?> item = (Map<?, ?>) value;
            if (item.get("id") instanceof Number && item.get("qty") instanceof Number)
                quantities.put(((Number) item.get("id")).intValue(), (Number) item.get("qty"));
        }
        Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] budget = {512};
        for (int i = 0; i < 256 && budget[0] > 0; i++)
            rewardWidget(client, client.getWidget(group, i), quantities, out, seen, budget, 0);
    }

    private static void rewardWidget(Client client, Widget w, Map<Integer, Number> quantities,
        List<VerificationFrame.Region> out, Set<Widget> seen, int[] budget, int depth)
    {
        if (w == null || depth > 4 || budget[0]-- <= 0 || !seen.add(w) || w.isHidden()) return;
        int item = w.getItemId();
        if (visible(client, w) && quantities.containsKey(item))
            add(out, w, "item_" + item, quantities.get(item), "item_quantity", "items",
                VerificationProbe.inputs("item_id", item));
        for (Widget[] children : new Widget[][]{w.getChildren(), w.getDynamicChildren(), w.getStaticChildren()})
            if (children != null) for (Widget child : children) rewardWidget(client, child, quantities, out, seen, budget, depth + 1);
    }

    /** Expected source operands that had no selected pixels remain explicit unknowns. */
    static List<Map<String, Object>> missing(EventVerification.Source event, List<VerificationFrame.Region> regions, String reason)
    {
        Set<String> shown = new HashSet<>();
        for (VerificationFrame.Region region : regions)
            shown.add(String.valueOf(region.inputs.get("field")) + ":" + String.valueOf(region.inputs.get("item_id")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String field : new String[]{"count", "total", "level", "tasks_completed", "outcome", "verification_text"})
        {
            if ("verification_text".equals(field) && !regions.isEmpty()) continue;
            Object value = event.fields.get(field);
            if (value != null && !shown.contains(field + ":null"))
                out.add(VerificationProbe.result(field, VerificationProbe.inputs("field", field,
                    "reader", value instanceof Number ? "integer" : "text", "reason", reason), value, null));
        }
        if (event.fields.get("screen") instanceof Map)
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) event.fields.get("screen")).entrySet())
            {
                String field = "screen." + entry.getKey();
                if (!shown.contains(field + ":null") && out.size() < MAX_REGIONS)
                    out.add(VerificationProbe.result(String.valueOf(entry.getKey()),
                        VerificationProbe.inputs("field", field, "reader", "integer", "reason", reason), entry.getValue(), null));
            }
        if (out.isEmpty() && regions.isEmpty())
            out.add(VerificationProbe.result("evidence", VerificationProbe.inputs("reason", reason), null, null));
        return out;
    }

    private static boolean attached(Widget current, Widget wanted, Set<Widget> seen, int depth)
    {
        if (current == wanted) return true;
        if (current == null || depth > 4 || seen.size() > 512 || !seen.add(current)) return false;
        for (Widget[] children : new Widget[][]{current.getChildren(), current.getDynamicChildren(), current.getStaticChildren()})
            if (children != null) for (Widget child : children)
                if (attached(child, wanted, seen, depth + 1)) return true;
        return false;
    }

    static boolean unchanged(Client client, List<VerificationFrame.Region> regions)
    {
        for (VerificationFrame.Region r : regions)
        {
            Widget w = r.sourceWidget;
            if (!attached(client.getWidget(r.widget), w, Collections.newSetFromMap(new IdentityHashMap<>()), 0)
                || !visible(client, w) || !r.bounds.equals(w.getBounds())
                || !Objects.equals(r.text, EventVerification.clean(w.getText()))) return false;
            if ("item_quantity".equals(r.inputs.get("reader"))
                && (!Objects.equals(r.inputs.get("item_id"), w.getItemId()) || r.sourceItemQuantity != w.getItemQuantity())) return false;
        }
        return true;
    }
}
