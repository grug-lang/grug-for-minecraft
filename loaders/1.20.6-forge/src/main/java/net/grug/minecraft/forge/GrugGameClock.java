package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugTickLockstep;

/**
 * How many times faster than real time the client's tick timer runs, which {@code
 * MinecraftTimerMixin} reads and {@link ForgeAdapter#setGameSpeed} writes, and the lockstep that
 * keeps the integrated server's ticks in step with the client's.
 *
 * <p>It is a plain class and not a field of the mixin because Mixin refuses code that names a mixin
 * class directly. The runner sets it on the server thread and the timer reads it on the client
 * thread, so it is volatile.
 */
public final class GrugGameClock {
    private GrugGameClock() {}

    /** The speed, where 1 is the 20 ticks per second of real time. */
    public static volatile float speed = 1.0F;

    /**
     * Holds each tick of the integrated server back until the client has made as many, while the
     * speed is above 1. See {@link GrugTickLockstep}.
     */
    public static final GrugTickLockstep LOCKSTEP = new GrugTickLockstep();
}
