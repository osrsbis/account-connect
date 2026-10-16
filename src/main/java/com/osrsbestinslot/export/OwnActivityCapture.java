package com.osrsbestinslot.export;

import java.util.*;
import java.util.regex.*;
import net.runelite.api.*;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/** Narrow own-account acquisition. Values remain absolute observations, never rewards or deltas. */
final class OwnActivityCapture
{
    private final OwnGameplayCapture out;
    private int messageTick = -1, deathTick = -1;
    private final Set<String> messages = new HashSet<>();
    private static final Pattern CONTRACT_TOTAL = Pattern.compile("You have completed ([0-9,]+) contracts with a total of ([0-9,]+) points?\\.");
    private static final Pattern CHOMPY_TOTAL = Pattern.compile("You've scratched up a total of ([0-9,]+) chompy bird kills?\\.");
    private static final Pattern COAL = Pattern.compile("The coal bag (?:still )?contains (one|[0-9]+) pieces? of coal\\.");
    private static final Pattern COMPOST = Pattern.compile("You treat the [A-Za-z -]{1,80} with (ultra|super|)compost\\.");
    private static final String[] ROOMS = {"The Maiden of Sugadinti", "Pestilent Bloat", "Nylocas Prinkipas", "Nylocas Vasilias", "Sotetseg", "Xarpus", "Verzik Vitur"};

    OwnActivityCapture(OwnGameplayCapture out) { this.out = out; }
    void reset() { messageTick = deathTick = -1; messages.clear(); }

    private void bits(Client c, int[] vars, String family, String[] keys, int[] ids, long at)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        for (int i=0; i<ids.length; i++) value.put(keys[i], AccountProgressCapture.bit(c,vars,ids[i]));
        out.state(family, "varbits:"+Arrays.toString(ids), value, c.getTickCount(), at);
    }

    void poll(Client c, int[] vars, long at)
    {
        Map<String,Object> potions = new LinkedHashMap<>();
        potions.put("storage_values_raw", OwnPotionStorageCapture.raw(vars));
        potions.put("unlocked_raw", AccountProgressCapture.bit(c,vars,11295));
        potions.put("units", "unverified_not_doses_or_bottles");
        out.state("storage.potion_store", "RuneLite1.13.1:113_named_varps;varbit:11295", potions,c.getTickCount(),at);

        // Read fixed own progress fields; task identity and reward decoding remain unverified.
        Map<String,Object> port = new LinkedHashMap<>();
        int[] taskVars = {VarPlayerID.PORT_TASKS_0,VarPlayerID.PORT_TASKS_1,VarPlayerID.PORT_TASKS_2,
            VarPlayerID.PORT_TASKS_3,VarPlayerID.PORT_TASKS_4,VarPlayerID.PORT_TASKS_GENERAL};
        Map<String,Object> slots = new LinkedHashMap<>();
        for (int id : taskVars) slots.put(String.valueOf(id),AccountProgressCapture.varp(vars,id));
        port.put("packed_slots_raw",slots);
        port.put("completed_total_raw",AccountProgressCapture.varp(vars,VarPlayerID.PORT_TASKS_COMPLETED));
        port.put("bounty_item_counter_raw",AccountProgressCapture.varp(vars,VarPlayerID.PORT_TASKS_BOUNTY_ITEM_COUNTER));
        port.put("extra_slots_raw",AccountProgressCapture.bit(c,vars,VarbitID.PORT_TASK_EXTRA_SLOTS_UNLOCKED));
        port.put("completed_today_raw",AccountProgressCapture.bit(c,vars,VarbitID.PORT_TASKS_COMPLETED_TODAY));
        port.put("interpretation","task_identity_and_reward_unverified");
        out.state("sailing.port_tasks", "RuneLite1.13.1:8_named_varps;varbits:19589,19591", port,c.getTickCount(),at);

        bits(c,vars,"minigame.tears",new String[]{"countdown_raw","tears_raw","collecting_raw"},new int[]{5099,455,453},at);
        // Only the local player's contribution, never team bottles or other players' scoreboards.
        bits(c,vars,"minigame.brewing",new String[]{"own_contribution_raw"},new int[]{2290},at);
        bits(c,vars,"minigame.restaurant_order",new String[]{"started_raw","customer_id_raw","order_id_raw","refused_hard_raw"},new int[]{2478,2484,2483,2485},at);
        bits(c,vars,"minigame.plunder_state",new String[]{"timer_raw","room_raw"},new int[]{2375,2377},at);
        bits(c,vars,"sailing.trial_progress",new String[]{"tempor_tantrum_raw","jubbly_jive_raw","gwenith_glide_raw"},new int[]{18427,18432,18437},at);
        bits(c,vars,"storage.bank_tabs",new String[]{"tab_1_slots","tab_2_slots","tab_3_slots","tab_4_slots","tab_5_slots","tab_6_slots","tab_7_slots","tab_8_slots","tab_9_slots"},new int[]{4171,4172,4173,4174,4175,4176,4177,4178,4179},at);

        Map<String,Object> totems = new LinkedHashMap<>();
        totems.put("research_points_raw", AccountProgressCapture.varp(vars,4764));
        for (int site=0; site<OwnActivityMappings.TOTEMS.length; site++)
        {
            Map<String,Object> fields = new LinkedHashMap<>();
            for (int f=0; f<OwnActivityMappings.TOTEM_FIELDS.length; f++)
                fields.put(OwnActivityMappings.TOTEM_FIELDS[f], AccountProgressCapture.bit(c,vars,OwnActivityMappings.TOTEMS[site][f]));
            totems.put("site_"+(site+1),fields);
        }
        out.state("minigame.totem_state","varp:4764;varbits:17611-17748 audited site projection",totems,c.getTickCount(),at);
        Map<String,Object> chart = new LinkedHashMap<>();
        for(int id:OwnActivityMappings.CHARTING) chart.put(String.valueOf(id),AccountProgressCapture.bit(c,vars,id));
        out.state("sailing.charting","358 pinned completion varbits; keys are varbit IDs",chart,c.getTickCount(),at);

        Map<String,Object> special = new LinkedHashMap<>();
        special.put("energy_raw",AccountProgressCapture.varp(vars,300));
        // A decrease is not evidence of a particular hit, target defence or a teammate action.
        out.state("combat.special_attack","varp:300",special,c.getTickCount(),at);
        raid(c,vars,at);
    }

    private Map<String,Object> raid(Client c, int[] vars, long at)
    {
        Integer status=AccountProgressCapture.bit(c,vars,6440);
        Map<String,Object> value=new LinkedHashMap<>(); value.put("party_status_raw",status);
        String room=null;
        if (Integer.valueOf(2).equals(status))
        {
            Widget parent=c.getWidget(28,1);
            Widget child=parent==null || parent.isHidden() ? null : parent.getChild(2);
            String label=child==null || child.isHidden() ? "" : clean(child.getText());
            for(String known:ROOMS) if(known.equals(label)) room=known;
        }
        value.put("room_observed",room); // HUD disappearance clears context instead of retaining stale text.
        out.state("combat.raid_context","varbit:6440;visible widget:28.1 child:2 allowlist",value,c.getTickCount(),at);
        return value;
    }

    void death(Client c, Actor actor, long at)
    {
        if(actor==null || actor!=c.getLocalPlayer() || c.getTickCount()==deathTick) return;
        deathTick=c.getTickCount();
        Map<String,Object> context=raid(c,c.getVarps(),at);
        if(!Integer.valueOf(2).equals(context.get("party_status_raw"))) return;
        Map<String,Object> value=new LinkedHashMap<>(context);
        value.put("actor","self"); value.put("classification","observed_own_death_not_team_wipe");
        out.append("combat.tob_room_death","ActorDeath:self",c.getTickCount(),at,value);
    }

    static String clean(String text)
    {
        if(text==null || text.length()>512) return "";
        return Text.removeTags(text.replace("<br>"," ")).trim();
    }

    static Long number(String text)
    {
        if(text==null || !text.matches("(?:0|[1-9][0-9]{0,9}|[1-9][0-9]{0,2}(?:,[0-9]{3}){1,3})")) return null;
        try { return Long.parseLong(text.replace(",","")); } catch(NumberFormatException e) { return null; }
    }

    void chat(Client c, ChatMessage event, long at)
    {
        ChatMessageType type=event.getType();
        if(type!=ChatMessageType.GAMEMESSAGE && type!=ChatMessageType.SPAM) return;
        String text=clean(event.getMessage());
        if(text.isEmpty()) return;
        if(type==ChatMessageType.GAMEMESSAGE && text.startsWith("Your party failed to complete the challenge"))
            out.scene.reset(); // discard hazard continuity; do not upload party text or infer team deaths
        // Bound duplicate callback suppression to a tick; never infer a lifetime/session count.
        if(messageTick!=c.getTickCount()) { messages.clear(); messageTick=c.getTickCount(); }
        Map<String,Object> value=new LinkedHashMap<>();
        String family=null;
        Matcher m=CONTRACT_TOTAL.matcher(text);
        if(type==ChatMessageType.GAMEMESSAGE && m.matches())
        {
            Long contracts=number(m.group(1)), points=number(m.group(2));
            if(contracts==null || points==null) return;
            value.put("contracts_absolute",contracts); value.put("points_absolute",points);
            family="minigame.construction_contract";
        }
        else if(type==ChatMessageType.SPAM && text.equals("You scratch a notch on your bow for the chompy bird kill."))
        {
            value.put("signal","notch_observed"); value.put("total_kills",null); family="combat.chompy_kc";
        }
        else if(type==ChatMessageType.GAMEMESSAGE && (m=CHOMPY_TOTAL.matcher(text)).matches())
        {
            Long total=number(m.group(1)); if(total==null) return;
            value.put("signal","absolute_check"); value.put("total_kills",total); family="combat.chompy_kc";
        }
        else if(type==ChatMessageType.GAMEMESSAGE && (m=Pattern.compile("Your quetzal whistle has ([0-9]+) charges remaining\\.").matcher(text)).matches())
        {
            Long charges=number(m.group(1)); if(charges==null) return;
            value.put("whistle_charges_observed",charges); family="hunter.rumour";
        }
        else if((m=COAL.matcher(text)).matches())
        {
            Long count="one".equals(m.group(1))?1L:number(m.group(1)); if(count==null || count>54) return;
            value.put("count_observed",count); family="storage.coal_bag";
        }
        else if(text.equals("The coal bag is empty.") || text.equals("The coal bag is now empty."))
        {
            value.put("count_observed",0L); family="storage.coal_bag";
        }
        else if(text.matches("(?:Your grape barrel is (?:now|already) full|The grape barrel is (?:empty|partially full))\\."))
        {
            value.put("fill_observed",text.contains("partially")?"partial":text.contains("empty")?"empty":"full"); family="storage.grape_barrel";
        }
        else if((m=COMPOST.matcher(text)).matches())
        {
            value.put("compost_type",m.group(1).isEmpty()?"normal":m.group(1)); value.put("patch_identity","unknown");
            family="farming.compost"; // Never attach a stale menu target/patch or emit the raw patch text.
        }
        if(family!=null && messages.size()<64 && messages.add(family+":"+value))
            out.append(family,"ChatMessage:"+type+":exact_english_projection",c.getTickCount(),at,value);
    }
}
