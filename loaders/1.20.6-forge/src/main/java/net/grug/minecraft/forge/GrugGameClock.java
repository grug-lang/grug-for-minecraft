package net.grug.minecraft.forge;

/**
 * How many times faster than real time the client's tick timer runs, which {@code
 * MinecraftTimerMixin} reads and {@link ForgeAdapter#setGameSpeed} writes.
 *
 * <p>It is a plain class and not a field of the mixin because Mixin refuses code that names a mixin
 * class directly. The runner sets it on the server thread and the timer reads it on the client
 * thread, so it is volatile.
 */
public final class GrugGameClock {
    private GrugGameClock() {}

    /** The speed, where 1 is the 20 ticks per second of real time. */
    public static volatile float speed = 1.0F;
}
