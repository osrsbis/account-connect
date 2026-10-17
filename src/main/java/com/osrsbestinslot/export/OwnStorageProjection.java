package com.osrsbestinslot.export;

import java.util.*;

/** Bounded personal loot-key cache facts. No victim, provenance, reference-price or income fields. */
final class OwnStorageProjection
{
    static Map<String,Object> value(String kind,Map<?,?> p)
    {
        Map<String,Object> out=new LinkedHashMap<>();out.put("basis","own_visible_key_cache");out.put("complete",false);out.put("new_loot_verified",false);
        Object status=p.get("status");
        if(kind.equals("loot_key_interface"))
        {
            if(!"interface_unavailable".equals(status)&&!"visible_key_slots".equals(status))throw new IllegalArgumentException();
            out.put("status",status);return out;
        }
        out.put("slot",num(p.get("slot"),0,4));
        if(!(status instanceof String)||!List.of("not_observed_in_session","cache_unverified","observed_empty_cache","cached_items").contains(status))throw new IllegalArgumentException();
        out.put("status",status);out.put("items",null);out.put("container_observed_at_ms",null);
        if("not_observed_in_session".equals(status)||"cache_unverified".equals(status))
        {if(p.get("items")!=null||p.get("container_observed_at_ms")!=null)throw new IllegalArgumentException();return out;}
        if(!(p.get("items") instanceof List))throw new IllegalArgumentException();List<?> raw=(List<?>)p.get("items");
        if(raw.size()>128||raw.isEmpty()!=status.equals("observed_empty_cache"))throw new IllegalArgumentException();
        List<Map<String,Object>> items=new ArrayList<>();long previous=0,total=0;
        for(Object item:raw)
        {
            if(!(item instanceof Map))throw new IllegalArgumentException();Map<?,?> row=(Map<?,?>)item;
            long id=num(row.get("id"),previous+1,Integer.MAX_VALUE),qty=num(row.get("qty"),1,128L*Integer.MAX_VALUE);
            total+=qty;if(total>128L*Integer.MAX_VALUE)throw new IllegalArgumentException();previous=id;items.add(Map.of("id",id,"qty",qty));
        }
        out.put("items",items);out.put("container_observed_at_ms",num(p.get("container_observed_at_ms"),1000000000000L,9007199254740991L));return out;
    }
    private static long num(Object x,long min,long max)
    {
        if(!(x instanceof Number))throw new IllegalArgumentException();Number n=(Number)x;long value=n.longValue();
        if(!Double.isFinite(n.doubleValue())||n.doubleValue()!=value||value<min||value>max)throw new IllegalArgumentException();return value;
    }
}
