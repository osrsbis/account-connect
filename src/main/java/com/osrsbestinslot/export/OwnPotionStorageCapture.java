package com.osrsbestinslot.export;

import java.util.*;
import java.util.stream.IntStream;

/** Pinned own-account raw storage values. No scripts, widget scans or inferred dose counts. */
final class OwnPotionStorageCapture
{
    private OwnPotionStorageCapture() {}
    // RuneLite1.13.1: explicit named storage varps only; exclude packed UI bases and favourites.
    // The 113 VarPlayerID.POTIONSTORE_* ids in their named-list order, which is these runs; OwnActivityCaptureTest
    // pins this array against the named constants element for element.
    static final int[] VARPS = IntStream.concat(IntStream.concat(IntStream.rangeClosed(4286, 4384),
        IntStream.of(4481, 4482, 4483, 4497, 4700)), IntStream.rangeClosed(4929, 4937)).toArray();
    static Map<String,Object> raw(int[] vars)
    {
        Map<String,Object> values = new LinkedHashMap<>();
        for (int id : VARPS) values.put(String.valueOf(id), AccountProgressCapture.varp(vars,id));
        return values;
    }
}
