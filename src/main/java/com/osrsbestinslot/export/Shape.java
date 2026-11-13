package com.osrsbestinslot.export;

import java.util.*;
import java.util.function.Predicate;

import static com.osrsbestinslot.export.EventValues.*;

/**
 * Closed value shapes for the own-account events. Only declared keys leave, each one checked. A shape is:
 * long[]{min, max}, a whole number in that range; a List, the allowed strings; a Predicate, a value it accepts;
 * Object[]{min, max, element}, a list of that many elements of one shape; a Map of key to shape in wire order, where
 * a key ending in "?" may be absent or null; a String, a Boolean or null, a fixed value written whatever the input
 * holds. Anything else throws IllegalArgumentException.
 */
final class Shape
{
    private Shape() {}
    static long[] num(long min, long max) { return new long[]{min, max}; }
    static Object[] list(int min, int max, Object element) { return new Object[]{min, max, element}; }
    static Predicate<Object> is(Predicate<Object> test) { return test; }
    static Map<String,Object> obj(Object... keysAndShapes) { return Fields.of(keysAndShapes); }

    @SuppressWarnings("unchecked")
    static Object project(Object shape, Object value)
    {
        if (shape == null || shape instanceof String || shape instanceof Boolean) return shape;
        if (shape instanceof long[]) return number(value, ((long[]) shape)[0], ((long[]) shape)[1]);
        if (shape instanceof List) return choice(value, (List<String>) shape);
        if (shape instanceof Predicate)
        {
            if (!((Predicate<Object>) shape).test(value)) throw new IllegalArgumentException();
            return value;
        }
        if (shape instanceof Object[])
        {
            Object[] s = (Object[]) shape;
            if (!(value instanceof List) || ((List<?>) value).size() < (int) s[0] || ((List<?>) value).size() > (int) s[1])
                throw new IllegalArgumentException();
            List<Object> out = new ArrayList<>();
            for (Object element : (List<?>) value) out.add(project(s[2], element));
            return out;
        }
        Map<?,?> in = map(value);
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : ((Map<String,Object>) shape).entrySet())
        {
            String key = e.getKey();
            boolean optional = key.endsWith("?");
            if (optional) key = key.substring(0, key.length() - 1);
            Object v = in.get(key);
            out.put(key, optional && v == null ? null : project(e.getValue(), v));
        }
        return out;
    }
}
