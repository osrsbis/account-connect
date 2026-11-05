package com.osrsbestinslot.export;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class FlipperMathTest
{
    @Test
    public void requestedExampleSubtractsPerItemTaxBeforeMultiplying()
    {
        FlipperMath.Result result = FlipperMath.calculate(10_500, 100, 20, FlipperMath.Cost.perItem(10_000));
        assertEquals(210, result.taxPerItem);
        assertEquals(new BigInteger("21000"), result.tax);
        assertEquals(0, new BigDecimal("29000").compareTo(result.profit));
        assertEquals(Long.valueOf(10_204), result.breakEven);
    }

    @Test
    public void floorAndCapBoundariesUseIntegerArithmetic()
    {
        long[][] cases = {{0, 0}, {49, 0}, {50, 1}, {99, 1}, {100, 2},
            {249_999_999, 4_999_999}, {250_000_000, 5_000_000},
            {5_000_000_000L, 5_000_000}, {Long.MAX_VALUE, 5_000_000}};
        for (long[] test : cases)
        {
            assertEquals("price=" + test[0], test[1], FlipperMath.taxPerItem(test[0], 20));
        }
        assertEquals(0, FlipperMath.taxPerItem(Long.MAX_VALUE, 0));
    }

    @Test
    public void selectedItemExemptionChangesProfitAndBreakEven()
    {
        FlipperMath.Result result = FlipperMath.calculate(10_500, 100, 0, FlipperMath.Cost.perItem(10_000));
        assertEquals(BigInteger.ZERO, result.tax);
        assertEquals(0, new BigDecimal("50000").compareTo(result.profit));
        assertEquals(Long.valueOf(10_000), result.breakEven);
    }

    @Test
    public void highValueBreakEvenUsesCapInsteadOfDividingByPointNineEight()
    {
        assertEquals(Long.valueOf(249_999_999), FlipperMath.breakEven(FlipperMath.Cost.perItem(245_000_000), 20));
        assertEquals(Long.valueOf(255_000_000), FlipperMath.breakEven(FlipperMath.Cost.perItem(250_000_000), 20));
        assertEquals(Long.valueOf(5_005_000_000L),
            FlipperMath.breakEven(FlipperMath.Cost.perItem(5_000_000_000L), 20));
        assertNull(FlipperMath.breakEven(FlipperMath.Cost.perItem(Long.MAX_VALUE), 20));
    }

    @Test
    public void breakEvenIsMinimalAgainstIndependentSmallPriceOracle()
    {
        for (int cost = 0; cost < 10_000; cost += 7)
        {
            long actual = FlipperMath.breakEven(FlipperMath.Cost.perItem(cost), 20);
            assertTrue(actual - Math.min(5_000_000L, actual * 2 / 100) >= cost);
            if (actual > 1)
            {
                long previous = actual - 1;
                assertTrue(previous - Math.min(5_000_000L, previous * 2 / 100) < cost);
            }
        }
    }

    @Test
    public void recordedFractionalAverageIsNotRoundedBeforeCalculation()
    {
        FlipperMath.Cost cost = new FlipperMath.Cost(BigInteger.valueOf(501), 2);
        FlipperMath.Result result = FlipperMath.calculate(260, 2, 20, cost);
        assertEquals(0, new BigDecimal("9").compareTo(result.profit));
        assertEquals(Long.valueOf(256), result.breakEven);
    }

    @Test
    public void totalBeyondLongDoesNotWrapAndUnknownCostStaysUnknown()
    {
        FlipperMath.Result result = FlipperMath.calculate(5_000_000_000L, 2_000_000_000, 20, null);
        assertEquals(new BigInteger("9990000000000000000"), result.proceeds);
        assertNull(result.profit);
        assertNull(result.breakEven);
    }

    @Test
    public void lossesAndFreeManualCostRemainExplicit()
    {
        assertTrue(FlipperMath.calculate(100, 5, 20, FlipperMath.Cost.perItem(101)).profit.signum() < 0);
        assertEquals(0, new BigDecimal("490").compareTo(
            FlipperMath.calculate(100, 5, 20, FlipperMath.Cost.perItem(0)).profit));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidRateCannotBecomeExempt()
    {
        FlipperMath.calculate(100, 1, -1, null);
    }
    @Test
    public void breakEvenMustFitTheNativeWholeOfferLimit()
    {
        assertNull(FlipperMath.calculate(100, 1, 20, FlipperMath.Cost.perItem(3_000_000_000_000L)).breakEven);
        assertEquals(Long.valueOf(1001), FlipperMath.calculate(1001, Integer.MAX_VALUE, 0,
            FlipperMath.Cost.perItem(1001)).breakEven);
        assertNull(FlipperMath.calculate(1001, Integer.MAX_VALUE, 0, FlipperMath.Cost.perItem(1002)).breakEven);
    }

}
