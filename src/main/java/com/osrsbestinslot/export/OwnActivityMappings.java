package com.osrsbestinslot.export;

import java.util.stream.IntStream;

/**
 * Factual API 1.13.1 IDs from the pinned charting/totem acquisition reviews. The reviewed lists happen to be
 * contiguous, so they are written as the exact ranges they equal; the pinned lists themselves live in
 * OwnActivityCaptureTest, which checks these arrays element for element. Do not widen a range by inference.
 */
final class OwnActivityMappings
{
    private OwnActivityMappings() {}
    // sea-charting-quest-helper pinned source table task IDs 0..357; protocol keys are varbit IDs 18574..18931.
    static final int[] CHARTING = IntStream.rangeClosed(18574, 18931).toArray();
    static final String[] TOTEM_FIELDS = {"base_raw", "base_carved_raw", "low_raw", "mid_raw", "top_raw", "decorations_raw", "animal_1_raw", "animal_2_raw", "animal_3_raw", "decay_raw", "points_raw"};
    // Site s (0..7) uses varbits 17611 + 18*s + offset; offset 2 is not a totem field.
    static final int[][] TOTEMS = new int[8][];
    static
    {
        for (int site = 0; site < TOTEMS.length; site++)
        {
            int base = 17611 + 18 * site;
            TOTEMS[site] = IntStream.of(0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11).map(o -> base + o).toArray();
        }
    }
    static final int[][] BOLTS = {
        {879, 9236},
        {9337, 9240},
        {9335, 9237},
        {880, 9238},
        {9338, 9241},
        {9336, 9239},
        {9339, 9242},
        {9340, 9243},
        {9341, 9244},
        {9342, 9245},
        {21955, 21932},
        {21963, 21940},
        {21957, 21934},
        {21959, 21936},
        {21965, 21942},
        {21961, 21938},
        {21967, 21944},
        {21969, 21946},
        {21971, 21948},
        {21973, 21950},
    };
}
