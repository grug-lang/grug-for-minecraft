package net.grug.minecraft.grug;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Java's own text for a float and a double: the shortest decimal that reads back as the same value,
 * written plainly from 0.001 up to ten million and as {@code d.dddE+x} outside that, which is what
 * Java 19 and later write.
 *
 * <p>Implemented here instead of delegating to {@code Float.toString} and {@code Double.toString}
 * because Java 8 writes a different text for some values (about one in twelve thousand of them) and
 * a mod that runs on several Minecraft versions has to write the same file on each of them.
 */
final class GrugJavaNumbers {
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private GrugJavaNumbers() {}

    /** The text of {@code (float) value}, as Java's {@code Float.toString} writes it. */
    static String floatText(double value) {
        float single = (float) value;
        return text((double) single, true);
    }

    /** The text of {@code value}, as Java's {@code Double.toString} writes it. */
    static String doubleText(double value) {
        return text(value, false);
    }

    private static String text(double value, boolean single) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value == 0) {
            return 1.0 / value < 0 ? "-0.0" : "0.0";
        }
        boolean negative = value < 0;
        double magnitude = Math.abs(value);
        BigDecimal exact = new BigDecimal(magnitude);
        // floor(log10(magnitude)), the power of ten the leading digit stands for.
        int exponent = exact.precision() - exact.scale() - 1;
        int maxDigits = single ? 9 : 17;
        // The rounding at maxDigits digits reads back as the value, because 17 significant digits
        // identify a double and 9 a float, so it is the answer when no shorter decimal does. Every
        // shorter length is tried in turn and the first that has a decimal which reads back wins:
        // the first length with one is the shortest text. Two digits are tried before one, as Java
        // writes 4.9E-324 for the smallest double rather than the one digit 5.0E-324.
        BigDecimal chosen = exact.round(new MathContext(maxDigits, RoundingMode.HALF_EVEN));
        for (int digits = 2; digits < maxDigits; digits++) {
            // The gap between two decimals of this length that end in the same power of ten.
            BigDecimal step = BigDecimal.ONE.scaleByPowerOfTen(exponent - digits + 1);
            BigDecimal nearest = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
            BigDecimal bestDistance = null;
            BigDecimal best = null;
            // The nearest decimal can sit on either side of the value, and the one that reads back
            // can be its neighbour on the other side, so both neighbours are tried as well.
            for (int k = -1; k <= 1; k++) {
                BigDecimal candidate =
                        k == 0 ? nearest : nearest.add(step.multiply(BigDecimal.valueOf(k)));
                if (!readsBack(candidate, exact, magnitude, single)) {
                    continue;
                }
                BigDecimal distance = candidate.subtract(exact).abs();
                // Closer wins, and a decimal exactly as close as the best one so far wins when its
                // last digit is even, which is how Java breaks that tie: 0x3ac00000 is exactly
                // 0.00146484375, halfway between 0.0014648437 and 0.0014648438, and Java writes the
                // even one.
                if (bestDistance == null
                        || distance.compareTo(bestDistance) < 0
                        || (distance.compareTo(bestDistance) == 0 && isEven(candidate))) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                chosen = best;
                break;
            }
        }
        return layout(chosen, negative);
    }

    /** Whether the decimal's last significant digit is even. */
    private static boolean isEven(BigDecimal decimal) {
        return !decimal.unscaledValue().testBit(0);
    }

    /**
     * Whether the decimal {@code candidate} reads back as the value, in the type's own rounding.
     */
    private static boolean readsBack(
            BigDecimal candidate, BigDecimal exact, double magnitude, boolean single) {
        BigDecimal difference = candidate.subtract(exact);
        if (difference.signum() == 0) {
            return true;
        }
        // Half of the gap to the neighbour on the candidate's side, which is not the same on both
        // sides of the smallest normal value and of the largest finite one.
        BigDecimal border;
        if (difference.signum() > 0) {
            border =
                    new BigDecimal(single ? Math.ulp((float) magnitude) : Math.ulp(magnitude))
                            .divide(TWO);
        } else {
            double below = single ? Math.nextDown((float) magnitude) : Math.nextDown(magnitude);
            border = exact.subtract(new BigDecimal(below)).divide(TWO);
        }
        int comparison = difference.abs().compareTo(border);
        if (comparison != 0) {
            return comparison < 0;
        }
        // Exactly halfway between the value and that neighbour. Reading it back rounds to the one
        // whose significand is even, so it names this value only when this value's own is even.
        return single
                ? (Float.floatToRawIntBits((float) magnitude) & 1) == 0
                : (Double.doubleToRawLongBits(magnitude) & 1) == 0;
    }

    /** The decimal in Java's shape: plain, or with an exponent outside 0.001 to ten million. */
    private static String layout(BigDecimal chosen, boolean negative) {
        String digits = chosen.unscaledValue().abs().toString();
        int scale = chosen.scale();
        while (digits.length() > 1 && digits.charAt(digits.length() - 1) == '0') {
            digits = digits.substring(0, digits.length() - 1);
            scale--;
        }
        int exponent = digits.length() - scale - 1;
        StringBuilder text = new StringBuilder();
        if (negative) {
            text.append('-');
        }
        if (exponent >= -3 && exponent < 7) {
            if (exponent >= 0) {
                if (digits.length() <= exponent + 1) {
                    text.append(digits);
                    for (int i = digits.length(); i <= exponent; i++) {
                        text.append('0');
                    }
                    text.append(".0");
                } else {
                    text.append(digits, 0, exponent + 1)
                            .append('.')
                            .append(digits.substring(exponent + 1));
                }
            } else {
                text.append("0.");
                for (int i = 0; i < -exponent - 1; i++) {
                    text.append('0');
                }
                text.append(digits);
            }
        } else {
            text.append(digits.charAt(0)).append('.');
            text.append(digits.length() > 1 ? digits.substring(1) : "0");
            text.append('E').append(exponent);
        }
        return text.toString();
    }
}
