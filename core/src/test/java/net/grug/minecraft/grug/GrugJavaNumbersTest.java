package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The expected texts below were printed by {@code java-21-openjdk 21.0.13.1}, the Java the
 * reference BuildGuide 0.4.9 runs on, and are not computed here, because what is under test is
 * exactly the difference between Java 8's text and Java 19's.
 */
class GrugJavaNumbersTest {
    @Test
    void floatsPrintAsJavasFloatText() {
        assertEquals("0.0", GrugJavaNumbers.floatText(0.0));
        assertEquals("-0.0", GrugJavaNumbers.floatText(-0.0));
        assertEquals("1.0", GrugJavaNumbers.floatText(1.0));
        assertEquals("0.1", GrugJavaNumbers.floatText(0.10000000149011612));
        assertEquals("2.5", GrugJavaNumbers.floatText(2.5));
        assertEquals("-3.25", GrugJavaNumbers.floatText(-3.25));
        assertEquals("0.3", GrugJavaNumbers.floatText(0.30000001192092896));
        assertEquals("0.001", GrugJavaNumbers.floatText(0.0010000000474974513));
        assertEquals("1.0E-4", GrugJavaNumbers.floatText(9.999999747378752e-5));
        assertEquals("1.0E-5", GrugJavaNumbers.floatText(9.999999747378752e-6));
        assertEquals("1.0E7", GrugJavaNumbers.floatText(1.0e7));
        assertEquals("9999999.0", GrugJavaNumbers.floatText(9999999.0));
        assertEquals("1.0E8", GrugJavaNumbers.floatText(1.0e8));
        assertEquals("1.0E20", GrugJavaNumbers.floatText(1.0000000200408773e20));
        assertEquals("0.0", GrugJavaNumbers.floatText((float) 1e-50));
        assertEquals("1.4E-45", GrugJavaNumbers.floatText(1.401298464324817e-45));
        assertEquals("3.4028235E38", GrugJavaNumbers.floatText(3.4028234663852886e38));
        assertEquals("0.33333334", GrugJavaNumbers.floatText(0.3333333432674408));
        assertEquals("1.2345679E-5", GrugJavaNumbers.floatText(1.2345679351710714e-5));
        assertEquals("1200000.0", GrugJavaNumbers.floatText(1200000.0));
        assertEquals("1234567.0", GrugJavaNumbers.floatText(1234567.0));
        assertEquals("1.2345678E7", GrugJavaNumbers.floatText(1.2345678e7));
        assertEquals("2.2517998E15", GrugJavaNumbers.floatText(2.251799813685248e15));
        // The tie the shortest-text search breaks by last digit: exactly 0.00146484375, halfway
        // between 0.0014648437 and 0.0014648438, where the even one is what Java writes.
        assertEquals("0.0014648438", GrugJavaNumbers.floatText(Float.intBitsToFloat(0x3ac00000)));
        // The same tie with the even one found first, so the odd one must not replace it.
        assertEquals("2.4414062E-4", GrugJavaNumbers.floatText(Float.intBitsToFloat(0x39800000)));
        // Exactly halfway between the value and its neighbour, so reading it back rounds to the
        // even significand: 2^24 keeps it and 2^24 + 2 does not.
        assertEquals("1.6777216E7", GrugJavaNumbers.floatText(Float.intBitsToFloat(0x4b800000)));
        assertEquals("1.6777218E7", GrugJavaNumbers.floatText(Float.intBitsToFloat(0x4b800001)));
        assertEquals("NaN", GrugJavaNumbers.floatText(Double.NaN));
        assertEquals("Infinity", GrugJavaNumbers.floatText(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", GrugJavaNumbers.floatText(Double.NEGATIVE_INFINITY));
    }

    @Test
    void doublesPrintAsJavasDoubleText() {
        assertEquals("0.0", GrugJavaNumbers.doubleText(0.0));
        assertEquals("-0.0", GrugJavaNumbers.doubleText(-0.0));
        assertEquals("1.0", GrugJavaNumbers.doubleText(1.0));
        assertEquals("0.1", GrugJavaNumbers.doubleText(0.1));
        assertEquals("2.5", GrugJavaNumbers.doubleText(2.5));
        assertEquals("-3.25", GrugJavaNumbers.doubleText(-3.25));
        assertEquals("0.3", GrugJavaNumbers.doubleText(0.3));
        assertEquals("0.30000000000000004", GrugJavaNumbers.doubleText(0.1 + 0.2));
        assertEquals("0.001", GrugJavaNumbers.doubleText(0.001));
        assertEquals("0.0015", GrugJavaNumbers.doubleText(0.0015));
        assertEquals("1.0E-4", GrugJavaNumbers.doubleText(1.0e-4));
        assertEquals("1.0E-5", GrugJavaNumbers.doubleText(1.0e-5));
        assertEquals("1.0E-7", GrugJavaNumbers.doubleText(1.0e-7));
        assertEquals("-1.0E-7", GrugJavaNumbers.doubleText(-1.0e-7));
        assertEquals("1.0E7", GrugJavaNumbers.doubleText(1.0e7));
        assertEquals("9999999.0", GrugJavaNumbers.doubleText(9999999.0));
        assertEquals("1.0E8", GrugJavaNumbers.doubleText(1.0e8));
        assertEquals("1.0E20", GrugJavaNumbers.doubleText(1.0e20));
        assertEquals("1.0E300", GrugJavaNumbers.doubleText(1.0e300));
        assertEquals("1.0E-300", GrugJavaNumbers.doubleText(1.0e-300));
        assertEquals("1.0E-320", GrugJavaNumbers.doubleText(1.0e-320));
        assertEquals("4.9E-324", GrugJavaNumbers.doubleText(4.9e-324));
        assertEquals(
                "2.2250738585072014E-308", GrugJavaNumbers.doubleText(2.2250738585072014e-308));
        assertEquals("1.7976931348623157E308", GrugJavaNumbers.doubleText(1.7976931348623157e308));
        assertEquals("0.3333333333333333", GrugJavaNumbers.doubleText(0.3333333333333333));
        assertEquals("1.234567890123456E15", GrugJavaNumbers.doubleText(1.234567890123456e15));
        assertEquals("1200000.0", GrugJavaNumbers.doubleText(1200000.0));
        assertEquals("2.2517998136852485E15", GrugJavaNumbers.doubleText(2.2517998136852485e15));
        // The same two tie shapes as the float test above, and the same halfway one.
        assertEquals(
                "1.1258999068426248E15",
                GrugJavaNumbers.doubleText(Double.longBitsToDouble(0x4310000000000003L)));
        assertEquals(
                "1.1258999068426242E15",
                GrugJavaNumbers.doubleText(Double.longBitsToDouble(0x4310000000000001L)));
        assertEquals(
                "9.007199254740992E15",
                GrugJavaNumbers.doubleText(Double.longBitsToDouble(0x4340000000000000L)));
        assertEquals(
                "9.007199254740994E15",
                GrugJavaNumbers.doubleText(Double.longBitsToDouble(0x4340000000000001L)));
        assertEquals("NaN", GrugJavaNumbers.doubleText(Double.NaN));
        assertEquals("Infinity", GrugJavaNumbers.doubleText(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", GrugJavaNumbers.doubleText(Double.NEGATIVE_INFINITY));
    }
}
