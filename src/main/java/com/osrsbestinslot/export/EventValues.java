package com.osrsbestinslot.export;

import java.util.List;
import java.util.Map;

/** Scalar guards shared by the closed own-event projections. */
final class EventValues
{
    private EventValues() {}

    static long number(Object x, long min, long max)
    {
        if (!(x instanceof Number)) throw new IllegalArgumentException();
        Number n = (Number) x;
        long value = n.longValue();
        if (!Double.isFinite(n.doubleValue()) || n.doubleValue() != value || value < min || value > max)
            throw new IllegalArgumentException();
        return value;
    }

    static Map<?, ?> map(Object x)
    {
        if (!(x instanceof Map)) throw new IllegalArgumentException();
        return (Map<?, ?>) x;
    }

    static String choice(Object x, List<String> allowed)
    {
        if (!(x instanceof String) || !allowed.contains(x)) throw new IllegalArgumentException();
        return (String) x;
    }
}
