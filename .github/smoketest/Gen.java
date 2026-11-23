import java.util.Random;

public class Gen {
    // String.from_number's text, as HostFunctions.numberText answers it: whole finite values
    // through
    // BigDecimal, everything else through Double.toString. Prints selftest.c's number_text vectors.
    static String numberText(double v) {
        if (Double.isFinite(v) && v == Math.rint(v)) {
            return new java.math.BigDecimal(v).toBigInteger().toString();
        }
        return Double.toString(v);
    }

    static void numberTextVectors() {
        double[] vs = {
            2.5,
            -0.25,
            0.1 + 0.2,
            123456.789,
            0.001,
            1.0E-4,
            1.5E-5,
            -3.75E-7,
            1.0 / 3,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            -0.0,
            1e20,
            1.2345678901234567e25,
            4.9e-324,
            0.5,
            9999999.5,
            12345678.9,
            1e7 + 0.5,
            41,
            -7,
        };
        for (double v : vs) {
            System.out.println("NUMBER_TEXT " + Double.toHexString(v) + " " + numberText(v));
        }
        System.out.println("NUMBER_TEXT_MAX " + numberText(Double.MAX_VALUE));
    }

    static String hex(double d) {
        return Double.toHexString(d);
    }

    public static void main(String[] a) {
        numberTextVectors();
        long[] seeds = {42L, 0L, 123456789012345L};
        for (long seed : seeds) {
            Random r = new Random(seed);
            StringBuilder sb = new StringBuilder();
            sb.append("{" + seed + "LL, {");
            for (int i = 0; i < 4; i++) sb.append(r.nextInt() + (i < 3 ? ", " : ""));
            sb.append("}, {");
            for (int i = 0; i < 4; i++) sb.append(r.nextInt(10) + (i < 3 ? ", " : ""));
            sb.append("}, {");
            for (int i = 0; i < 4; i++) sb.append(r.nextInt(16) + (i < 3 ? ", " : ""));
            sb.append("}, {");
            for (int i = 0; i < 4; i++) sb.append(r.nextInt(1000) + (i < 3 ? ", " : ""));
            sb.append("}, {");
            for (int i = 0; i < 4; i++) sb.append(r.nextInt(2147483647) + (i < 3 ? ", " : ""));
            sb.append("}, {");
            for (int i = 0; i < 3; i++) sb.append(hex(r.nextFloat()) + (i < 2 ? ", " : ""));
            sb.append("}, {");
            for (int i = 0; i < 3; i++) sb.append(hex(r.nextDouble()) + (i < 2 ? ", " : ""));
            sb.append("}, {");
            for (int i = 0; i < 5; i++) sb.append(hex(r.nextGaussian()) + (i < 4 ? ", " : ""));
            sb.append("}},");
            System.out.println(sb);
        }
        // a nextInt(bound) whose rejection loop iterates: find the first seed and bound
        int bound = 1431655765 * 1; // > 2^30 so rejection is likely
        for (long s = 0; s < 100000; s++) {
            Random r = new Random(s);
            Random c = new Random(s);
            int u = c.nextInt() >>> 1; // next(31)
            int m = bound - 1;
            int rr = u % bound;
            if (u - rr + m < 0) {
                System.out.println(
                        "REJECT seed="
                                + s
                                + " bound="
                                + bound
                                + " result="
                                + new Random(s).nextInt(bound));
                break;
            }
        }
    }
}
