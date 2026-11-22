import java.util.Random;

public class Gen {
    static String hex(double d) {
        return Double.toHexString(d);
    }

    public static void main(String[] a) {
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
