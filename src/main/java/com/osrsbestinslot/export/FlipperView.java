package com.osrsbestinslot.export;

import java.awt.Point;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

final class FlipperView
{
    final boolean enabled;
    final long key;
    final int item;
    final String itemName;
    final String status;
    final int quantity;
    final int sold;
    final long price;
    final int rate;
    final String costSource;
    final String manualInput;
    final FlipperMath.Cost cost;
    final FlipperMath.Result result;
    final Point anchor;

    FlipperView(boolean enabled, long key, int item, String itemName, String status, int quantity, int sold,
        long price, int rate, String costSource, String manualInput, FlipperMath.Cost cost, Point anchor)
    {
        this.enabled = enabled;
        this.key = key;
        this.item = item;
        this.itemName = itemName;
        this.status = status;
        this.quantity = quantity;
        this.sold = sold;
        this.price = price;
        this.rate = rate;
        this.costSource = costSource;
        this.manualInput = manualInput;
        this.cost = cost;
        this.anchor = anchor == null ? null : new Point(anchor);
        result = item > 0 && quantity > 0 && price > 0 && rate >= 0 && rate <= 1000
            ? FlipperMath.calculate(price, quantity, rate, cost) : null;
    }

    static FlipperView empty(boolean enabled, long key, String status)
    {
        return new FlipperView(enabled, key, 0, "", status, 0, 0, 0, -1, "", "", null, null);
    }

    static String number(Number value)
    {
        if (value == null)
        {
            return "Unknown";
        }
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }

    static String profit(BigDecimal value)
    {
        return value == null ? "Cost needed" : (value.signum() > 0 ? "+" : "") + number(value) + " GP";
    }

    static String compactProfit(BigDecimal value)
    {
        if (value == null || value.abs().compareTo(BigDecimal.valueOf(1_000_000_000L)) < 0)
        {
            return profit(value);
        }
        if (value.abs().compareTo(new BigDecimal("1000000000000000")) >= 0)
        {
            int exponent = value.precision() - value.scale() - 1;
            return "≈" + (value.signum() > 0 ? "+" : "") + number(value.movePointLeft(exponent))
                + "e" + exponent + " GP";
        }
        return "≈" + (value.signum() > 0 ? "+" : "")
            + number(value.divide(BigDecimal.valueOf(1_000_000_000L))) + "B GP";
    }

    static String compactGp(long value)
    {
        return value < 1_000_000_000L ? number(value) + " GP"
            : "≈" + number(BigDecimal.valueOf(value).divide(BigDecimal.valueOf(1_000_000_000L))) + "B GP";
    }

    String fingerprint()
    {
        return enabled + "|" + key + "|" + item + "|" + itemName + "|" + status + "|" + quantity + "|" + sold + "|" + price
            + "|" + rate + "|" + costSource + "|" + manualInput + "|"
            + (cost == null ? "" : cost.gp + "/" + cost.quantity) + "|" + anchor;
    }
}
