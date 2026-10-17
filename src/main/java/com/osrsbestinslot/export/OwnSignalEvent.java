package com.osrsbestinslot.export;

import java.util.*;

/** Closed own system/check projections. A signal is not a balance delta or proof of activity during a gap. */
final class OwnSignalEvent
{
    private OwnSignalEvent() {}
    static final Set<String> KINDS=Set.of("storage.coal_bag","storage.fish_barrel","storage.grape_barrel",
        "combat.chompy_kc","minigame.construction_contract","farming.compost","hunter.rumour","combat.tob_room_death","combat.toa_hazards");
    static boolean supports(Object kind) { return kind instanceof String && (KINDS.contains(kind)||OwnInferredSignal.supports(kind)||OwnClueProjection.supports(kind)); }
    static Map<String,Object> value(String kind,Object raw)
    {
        try
        {
            if(!(raw instanceof Map))return null;Map<?,?> p=(Map<?,?>)raw;Map<String,Object> out=new LinkedHashMap<>();
            if(OwnClueProjection.supports(kind))return OwnClueProjection.signal(kind,p);
            if(OwnInferredSignal.supports(kind))return OwnInferredSignal.value(kind,p);
            switch(kind)
            {
                case "combat.tob_room_death":
                    out.put("party_status_raw",number(p.get("party_status_raw"),2,2));
                    if(!"self".equals(p.get("actor"))||!"observed_own_death_not_team_wipe".equals(p.get("classification")))return null;
                    Object room=p.get("room_observed");if(room!=null&&!OwnActivityCapture.raidRoom(room))return null;
                    out.put("room_observed",room);out.put("actor","self");out.put("classification","observed_own_death_not_team_wipe");break;
                case "combat.toa_hazards":
                    out.put("object_id",number(p.get("object_id"),45570,45576));out.put("spawn_tick",number(p.get("spawn_tick"),0,Integer.MAX_VALUE));
                    if(!"own_tile_overlap_not_proven_damage_or_mistake".equals(p.get("classification")))return null;
                    out.put("classification","own_tile_overlap_not_proven_damage_or_mistake");break;
                case "storage.coal_bag":case "storage.fish_barrel":
                    out.put("count_observed",number(p.get("count_observed"),0,kind.equals("storage.coal_bag")?54:28));break;
                case "storage.grape_barrel":out.put("fill_observed",choice(p.get("fill_observed"),List.of("empty","partial","full")));break;
                case "combat.chompy_kc":
                    String signal=choice(p.get("signal"),List.of("notch_observed","absolute_check"));out.put("signal",signal);
                    if(signal.equals("notch_observed")) { if(p.get("total_kills")!=null)return null;out.put("total_kills",null); }
                    else out.put("total_kills",number(p.get("total_kills"),0,999999999999L));break;
                case "minigame.construction_contract":
                    out.put("contracts_absolute",number(p.get("contracts_absolute"),0,999999999999L));
                    out.put("points_absolute",number(p.get("points_absolute"),0,999999999999L));break;
                case "farming.compost":
                    out.put("compost_type",choice(p.get("compost_type"),List.of("normal","super","ultra")));
                    if(!"unknown".equals(p.get("patch_identity")))return null;out.put("patch_identity","unknown");break;
                case "hunter.rumour":
                    if(p.containsKey("whistle_charges_observed"))
                    {
                        if(p.get("signal")!=null||p.get("target_observed")!=null)return null;
                        out.put("whistle_charges_observed",number(p.get("whistle_charges_observed"),0,999999999999L));
                    }
                    else
                    {
                        String s=choice(p.get("signal"),List.of("whistle_target","whistle_no_active"));out.put("signal",s);
                        if(!Boolean.FALSE.equals(p.get("turn_in_verified")))return null;
                        Object target=p.get("target_observed");
                        if(s.equals("whistle_target")?!OwnActivityCapture.rumourTargetCode(target):target!=null)return null;
                        out.put("target_observed",target);out.put("turn_in_verified",false);
                    }
                    break;
                default:return null;
            }
            return out;
        }
        catch(IllegalArgumentException invalid) { return null; }
    }
    static Map<String,Object> wire(Map<String,Object> p)
    {
        try
        {
            if(number(p.get("signal_schema"),1,1)!=1||!supports(p.get("signal_kind")))return null;
            boolean inferred=OwnInferredSignal.supports(p.get("signal_kind"));
            if(!Boolean.valueOf(!inferred).equals(p.get("direct"))||!(inferred?"inferred_observation":"observed_signal").equals(p.get("observation")))return null;
            Object session=p.get("gameplay_session");if(!(session instanceof String)||!((String)session).matches("[a-f0-9]{32}"))return null;
            String kind=(String)p.get("signal_kind");
            String source=choice(p.get("source"),inferred?List.of("own_client_sequence")
                :OwnClueProjection.supports(kind)?List.of(OwnClueProjection.source(kind))
                :kind.equals("combat.tob_room_death")?List.of("own_client_actor")
                :kind.equals("combat.toa_hazards")?List.of("own_client_scene"):List.of("own_client_system","own_client_dialog"));
            if(source.equals("own_client_dialog")&&!List.of("storage.coal_bag","storage.fish_barrel").contains(kind))return null;
            if(kind.equals("storage.fish_barrel")&&!source.equals("own_client_dialog"))return null;
            Map<String,Object> value=value(kind,p.get("signal_value"));if(value==null)return null;
            Map<String,Object> out=new LinkedHashMap<>();out.put("signal_schema",1);out.put("signal_kind",kind);
            out.put("source",source);out.put("direct",!inferred);out.put("observation",p.get("observation"));out.put("gameplay_session",session);
            out.put("source_sequence",number(p.get("source_sequence"),1,9007199254740991L));
            long tick=number(p.get("source_tick"),0,Integer.MAX_VALUE);
            if(kind.equals("combat.toa_hazards")&&(Long)value.get("spawn_tick")>=tick)return null;
            if(inferred)OwnInferredSignal.checkTick(kind,value,tick);out.put("source_tick",tick);
            out.put("observed_at_ms",number(p.get("observed_at_ms"),1000000000000L,9007199254740991L));out.put("signal_value",value);return out;
        }
        catch(IllegalArgumentException invalid) { return null; }
    }
    static Map<String,Object> from(String session,Map<String,Object> row)
    {
        if(!supports(row.get("kind"))||session==null||!session.matches("[a-f0-9-]{36}"))return null;
        Object rawSource=row.get("source");String source;
        boolean inferred=OwnInferredSignal.supports(row.get("kind"));
        if(inferred) {if(!OwnInferredSignal.source((String)row.get("kind")).equals(rawSource))return null;source="own_client_sequence";}
        else if(OwnClueProjection.supports(row.get("kind"))) {
            if(!(row.get("value") instanceof Map)||!OwnClueProjection.sourceMatches((String)row.get("kind"),rawSource,(Map<?,?>)row.get("value")))return null;
            source=OwnClueProjection.source((String)row.get("kind"));
        }
        else if("combat.tob_room_death".equals(row.get("kind"))&&"ActorDeath:self".equals(rawSource))source="own_client_actor";
        else if("combat.toa_hazards".equals(row.get("kind"))&&"own_tile+observed_acid_object".equals(rawSource))source="own_client_scene";
        else if("ChatMessage:GAMEMESSAGE:exact_english_projection".equals(rawSource)||"ChatMessage:SPAM:exact_english_projection".equals(rawSource))source="own_client_system";
        else if("ClientTick:widget:193.2;own_inventory_possession".equals(rawSource))source="own_client_dialog";
        else return null;
        Map<String,Object> input=new LinkedHashMap<>();input.put("signal_schema",1);input.put("signal_kind",row.get("kind"));
        input.put("signal_value",row.get("value"));input.put("source",source);input.put("direct",row.get("direct"));
        input.put("observation",inferred?"inferred_observation":"observed_signal");input.put("gameplay_session",session.replace("-",""));
        input.put("source_sequence",row.get("sequence"));input.put("source_tick",row.get("tick"));input.put("observed_at_ms",row.get("observed_at_ms"));
        Map<String,Object> out=wire(input);if(out!=null)out.put("type","own_signal");return out;
    }
    private static String choice(Object x,List<String> allowed)
    {if(!(x instanceof String)||!allowed.contains(x))throw new IllegalArgumentException();return (String)x;}
    private static long number(Object x,long min,long max)
    {
        if(!(x instanceof Number))throw new IllegalArgumentException();Number n=(Number)x;long v=n.longValue();
        if(!Double.isFinite(n.doubleValue())||n.doubleValue()!=v||v<min||v>max)throw new IllegalArgumentException();return v;
    }
}
