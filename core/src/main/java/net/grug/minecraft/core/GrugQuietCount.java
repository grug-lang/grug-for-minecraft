package net.grug.minecraft.core;

/**
 * Whether a count has stopped changing: true once it has been the same, and at least {@code
 * minimum}, for {@code quietTicks} ticks in a row. The Alpha client uses it on the number of chunks
 * it has loaded around its player, so its run starts only after the server has finished sending the
 * world (#1040): a server frozen while chunks are half sent left the client's recursive light
 * update running off its stack.
 */
public final class GrugQuietCount {
    private final int quietTicks;
    private final int minimum;
    private int last = -1;
    private int steady = 0;

    public GrugQuietCount(int quietTicks, int minimum) {
        this.quietTicks = quietTicks;
        this.minimum = minimum;
    }

    /** One tick with the current count; true when the count has settled. */
    public boolean tick(int count) {
        if (count != last) {
            last = count;
            steady = 0;
            return false;
        }
        steady++;
        return count >= minimum && steady >= quietTicks;
    }
}
