package net.grug.minecraft.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the #704 and #759 measurements, and nothing else.
 *
 * <p>This class exists only on the throwaway branches that measure what the tests pay for, and is
 * deleted with them. The loader increments the counters from the places that do the work: the
 * section rebuild mixin, the per-frame hook, the block writes, and the room build's own answer about
 * whether the room was already there.
 */
public final class GrugTestCounters {
    /** Section mesh rebuilds started, one per {@code RebuildTask.doTask}. */
    public static final AtomicLong sectionRebuilds = new AtomicLong();

    /** Level frames drawn, one per {@code AFTER_LEVEL}. */
    public static final AtomicLong frames = new AtomicLong();

    /** Block writes the room build and the tests made through the adapter. */
    public static final AtomicLong placements = new AtomicLong();

    /** Block reads the room build's clear made through the adapter. */
    public static final AtomicLong airProbes = new AtomicLong();

    /** Room builds that found their room already whole and left it standing. */
    public static final AtomicLong wholeRooms = new AtomicLong();

    private GrugTestCounters() {}
}
