package com.osrsbestinslot.export;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/** Exact integer tax and decimal purchase-basis arithmetic. No market-price inference. */
final class FlipperMath
{
    static final long TAX_CAP = 5_000_000L;
    // Native GE setup script779, revision242, rejects price > floor(this limit / quantity).
    // Pinned source and runtime-total cross-check are documented in docs/FLIPPER.md.
    static final long MAX_OFFER_GP = 2_149_631_130_647L;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    static final class Cost
    {
        final BigInteger gp;
        final long quantity;

        Cost(BigInteger gp, long quantity)
        {
            if (gp == null || gp.signum() < 0 || quantity <= 0)
            {
                throw new IllegalArgumentException("Invalid purchase cost");
            }
            this.gp = gp;
            this.quantity = quantity;
        }

        static Cost perItem(long gp)
        {
            return new Cost(BigInteger.valueOf(gp), 1);
        }

        BigDecimal average()
        {
            return new BigDecimal(gp).divide(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);
        }

        BigDecimal forQuantity(long count)
        {
            return new BigDecimal(gp.multiply(BigInteger.valueOf(count)))
                .divide(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);
        }
    }

    static final class Result
    {
        final long taxPerItem;
        final BigInteger tax;
        final BigInteger proceeds;
        final BigDecimal profit;
        final BigDecimal profitPerItem;
        final Long breakEven;

        Result(long price, int quantity, int rate, Cost cost)
        {
            taxPerItem = taxPerItem(price, rate);
            BigInteger count = BigInteger.valueOf(quantity);
            tax = BigInteger.valueOf(taxPerItem).multiply(count);
            proceeds = BigInteger.valueOf(price - taxPerItem).multiply(count);
            profit = cost == null ? null : new BigDecimal(proceeds).subtract(cost.forQuantity(quantity));
            profitPerItem = cost == null ? null
                : BigDecimal.valueOf(price - taxPerItem).subtract(cost.average());
            Long minimum = cost == null ? null : breakEven(cost, rate);
            breakEven = minimum != null && minimum <= MAX_OFFER_GP / quantity ? minimum : null;
        }
    }

    static Result calculate(long price, int quantity, int rate, Cost cost)
    {
        if (price < 1 || quantity < 1 || rate < 0 || rate > 1000)
        {
            throw new IllegalArgumentException("Invalid sell offer");
        }
        return new Result(price, quantity, rate, cost);
    }

    /** The game supplies the effective rate per thousand for the selected item (zero means exempt). */
    static long taxPerItem(long price, int rate)
    {
        if (price < 0 || rate < 0 || rate > 1000)
        {
            throw new IllegalArgumentException("Invalid tax input");
        }
        // This decomposition cannot overflow, even for long prices and a 1000/1000 rate.
        return Math.min(TAX_CAP, price / 1000 * rate + (price % 1000) * rate / 1000);
    }

    static Long breakEven(Cost cost, int rate)
    {
        BigInteger needed = new BigDecimal(cost.gp)
            .divide(BigDecimal.valueOf(cost.quantity), 0, RoundingMode.CEILING).toBigIntegerExact();
        if (needed.compareTo(LONG_MAX) > 0)
        {
            return null;
        }
        long low = Math.max(1L, needed.longValue());
        long high = needed.add(BigInteger.valueOf(TAX_CAP)).max(BigInteger.ONE).min(LONG_MAX).longValue();
        if (high - taxPerItem(high, rate) < needed.longValue())
        {
            return null;
        }
        while (low < high)
        {
            long middle = low + (high - low) / 2;
            if (middle - taxPerItem(middle, rate) >= needed.longValue())
            {
                high = middle;
            }
            else
            {
                low = middle + 1;
            }
        }
        return low;
    }

    private FlipperMath()
    {
    }
}
