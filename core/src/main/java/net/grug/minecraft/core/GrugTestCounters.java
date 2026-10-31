package net.grug.minecraft.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the #760 measurement, and nothing else.
 *
 * <p>This class exists only on the throwaway branches that measure what a test's frames cost, and
 * is deleted with them. The loader increments the counters from the places that do the work: the
 * frame hook, the render skip, and the section rebuild mixin.
 */
public final class GrugTestCounters {
    /** Level frames drawn, one per {@code AFTER_LEVEL}. */
    public static final AtomicLong framesDrawn = new AtomicLong();

    /** Frames whose level draw was skipped because the test had not asked for a frame yet. */
    public static final AtomicLong framesSkipped = new AtomicLong();

    /** Section mesh rebuilds started, one per {@code RebuildTask.doTask}. */
    public static final AtomicLong sectionRebuilds = new AtomicLong();

    private GrugTestCounters() {}
}
