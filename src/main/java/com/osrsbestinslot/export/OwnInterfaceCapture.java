package com.osrsbestinslot.export;

import java.util.*;
import java.util.regex.*;
import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.MapClue;
import net.runelite.client.plugins.cluescrolls.clues.CoordinateClue;

/** Fixed, bounded interface projections. Never emit arbitrary labels, names, widget trees or unlock claims. */
final class OwnInterfaceCapture
{
    private final OwnGameplayCapture out;
    private String checkEpisode;
    private static final int[] FAVOURITES = {InterfaceID.FairyringsLog.FAVE_CODE_1,InterfaceID.FairyringsLog.FAVE_CODE_2,
        InterfaceID.FairyringsLog.FAVE_CODE_3,InterfaceID.FairyringsLog.FAVE_CODE_4,InterfaceID.FairyringsLog.FAVE_CODE_5,
        InterfaceID.FairyringsLog.FAVE_CODE_6,InterfaceID.FairyringsLog.FAVE_CODE_7,InterfaceID.FairyringsLog.FAVE_CODE_8,
        InterfaceID.FairyringsLog.FAVE_CODE_9,InterfaceID.FairyringsLog.FAVE_CODE_10};
    private static final String[] POTIONS={"Alco-augmentator","Mammoth-might mix","Liplack liquor","Mystic mana amalgam",
        "Marley's moonlight","Azure aura mix","Aqualux amalgam","Megalite liquid","Anti-leech lotion","Mixalot"};
    private static final String[] CODES={"AAA","MMM","LLL","MMA","MML","AAM","ALA","MLL","ALL","MAL"};
    private static final Pattern FISH_LIST=Pattern.compile("The barrel contains: (?:[0-9]{1,2} x [A-Za-z ]+,? ?)+");
    private static final Pattern FISH_COUNT=Pattern.compile("([0-9]{1,2}) x ");
    private static final Set<String> TREES=new HashSet<>(Arrays.asList("Tree Gnome Village","Gnome Stronghold","Battlefield of Khazard",
        "Grand Exchange","Feldip Hills","Prifddinas","Port Sarim","Etceteria","Brimhaven","Hosidius","Farming Guild","Your house","Poison Waste","Laguna Aurorae"));
    OwnInterfaceCapture(OwnGameplayCapture out) { this.out=out; }
    void reset() { checkEpisode=null; }
    static boolean visible(Widget widget) { return widget!=null && !widget.isHidden(); }

    void poll(Client client,long at)
    {
        Map<String,Object> fairy=new LinkedHashMap<>();
        List<String> codes=new ArrayList<>();
        for(int id:FAVOURITES)
        {
            Widget label=client.getWidget(id);
            String text=visible(label)?OwnActivityCapture.clean(label.getText()):"";
            codes.add(text.matches("[ABCD][IJKL][PQRS]")?text:null);
        }
        fairy.put("favourite_codes_visible",codes); fairy.put("authority","visible_ui_not_unlock_proof");
        out.state("travel.fairy_destinations","fixed favourite code widgets; hidden/filtered unknown",fairy,client.getTickCount(),at);

        Widget orders=client.getWidget(882,2);
        List<String> result=new ArrayList<>();
        Widget[] children=visible(orders)?orders.getChildren():null;
        boolean valid=children!=null && children.length<=64;
        if(valid) for(Widget child:children)
        {
            if(!visible(child) || child.getType()!=WidgetType.TEXT) continue;
            String text=OwnActivityCapture.clean(child.getText());
            if(text.isEmpty()) continue;
            int index=Arrays.asList(POTIONS).indexOf(text);
            if(index<0) { valid=false; break; } // substring matches and plugin-decorated labels are not authority
            result.add(CODES[index]); // duplicate orders are real, preserve order and multiplicity
            if(result.size()>3) { valid=false; break; }
        }
        Map<String,Object> mix=new LinkedHashMap<>();
        mix.put("orders_observed",valid && result.size()==3?result:null);
        mix.put("authority","visible_ui_not_completion");
        out.state("minigame.mixology_orders","widget:882.2 exact potion-name projection",mix,client.getTickCount(),at);
        spirit(client,at);
        clues(client,at);
    }

    private void spirit(Client client,long at)
    {
        Widget parent=client.getWidget(187,0), list=client.getWidget(187,3);
        Widget title=visible(parent)?parent.getChild(1):null;
        List<Map<String,Object>> entries=null;
        if(visible(title) && "Spirit Tree Locations".equals(OwnActivityCapture.clean(title.getText())) && visible(list))
        {
            Widget[] children=list.getDynamicChildren();
            if(children!=null && children.length<=32)
            {
                entries=new ArrayList<>();
                for(Widget child:children) if(visible(child))
                {
                    String raw=child.getText();
                    String text=OwnActivityCapture.clean(raw);
                    int colon=text.indexOf(": "); if(colon<0) continue;
                    String name=text.substring(colon+2);
                    if(name.matches("Your house(?: \\([A-Za-z ]{1,32}\\))?")) name="Your house";
                    if(!TREES.contains(name)) continue;
                    Map<String,Object> row=new LinkedHashMap<>(); row.put("destination",name);
                    // UI color is only an observation; never assert permanent unlock or account ownership of a POH.
                    row.put("disabled_style_observed",raw!=null && raw.contains("<col=5f5f5f>")); entries.add(row);
                }
            }
        }
        Map<String,Object> value=new LinkedHashMap<>(); value.put("visible_destinations",entries);
        value.put("authority","classic_ui_not_unlock_proof");
        out.state("travel.spirit_destinations","widget:187.0 title:1;187.3 bounded allowlist",value,client.getTickCount(),at);
    }

    private void clues(Client client,long at)
    {
        ItemContainer inventory=client.getItemContainer(InventoryID.INVENTORY);
        Map<String,Object> destinations=null;
        if(inventory!=null && inventory.getItems()!=null && inventory.getItems().length<=28)
        {
            destinations=new LinkedHashMap<>();
            for(Item item:inventory.getItems()) if(item!=null && item.getId()>0 && item.getQuantity()>0)
            {
                MapClue map=MapClue.forItemId(item.getId());
                CoordinateClue coordinate=map==null?CoordinateClue.forItemId(item.getId()):null;
                // These two exact API 1.13.1 methods are direct field getters (verified bytecode).
                // Other subtypes need a live ClueScrollPlugin context and are deliberately not called with null.
                WorldPoint point=map!=null?map.getLocation(null):coordinate!=null?coordinate.getLocation(null):null;
                if(point==null) continue;
                Map<String,Object> value=new LinkedHashMap<>(); value.put("kind",map!=null?"map":"coordinate");
                value.put("x",point.getX()); value.put("y",point.getY()); value.put("plane",point.getPlane());
                value.put("qualification","library_base_location_not_resolved_quest_alternative");
                destinations.put(String.valueOf(item.getId()),value);
            }
        }
        Map<String,Object> value=new LinkedHashMap<>(); value.put("inventory_map_coordinate_clues",destinations);
        value.put("complete",false);
        out.state("clues.destination","own_inventory+RuneLite1.13.1_map_coordinate_tables",value,client.getTickCount(),at);
    }

    // ClientTick sees dialogs that open and close before the next GameTick. No game action is generated.
    void clientTick(Client client,long at)
    {
        Widget widget=client.getWidget(193,2);
        if(!visible(widget)) { checkEpisode=null; return; }
        String text=OwnActivityCapture.clean(widget.getText());
        ItemContainer inv=client.getItemContainer(InventoryID.INVENTORY);
        if(inv==null || inv.getItems()==null) return;
        boolean coal=false,fish=false;
        for(Item item:inv.getItems()) if(item!=null)
        {
            int id=item.getId();
            coal |= id==12019 || id==24480;
            fish |= id==25582 || id==25584 || id==25585 || id==25587;
        }
        Long count=null; String kind=null;
        if(coal)
        {
            Matcher m=Pattern.compile("The coal bag (?:still )?contains (one|[0-9]{1,2}) pieces? of coal\\.").matcher(text);
            if(m.matches()) count="one".equals(m.group(1))?1L:OwnActivityCapture.number(m.group(1));
            else if(text.matches("The coal bag is (?:now )?empty\\.")) count=0L;
            if(count!=null && count<=54) kind="storage.coal_bag";
        }
        if(kind==null && fish)
        {
            if(text.equals("The barrel is empty.")) count=0L;
            else if(FISH_LIST.matcher(text).matches())
            {
                count=0L; Matcher m=FISH_COUNT.matcher(text);
                while(m.find()) count+=Long.parseLong(m.group(1));
            }
            else count=null;
            if(count!=null && count<=28) kind="storage.fish_barrel";
        }
        if(kind!=null)
        {
            String episode=kind+":"+count;
            if(episode.equals(checkEpisode)) return;
            checkEpisode=episode;
            Map<String,Object> value=new LinkedHashMap<>(); value.put("count_observed",count);
            value.put("scope","visible_check_only_not_continuous_balance");
            out.append(kind,"ClientTick:widget:193.2;own_inventory_possession",client.getTickCount(),at,value);
        }
        else checkEpisode=null;
    }
}
