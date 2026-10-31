package net.grug.minecraft.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the #704 measurement, and nothing else.
 *
 * <p>This class exists only on the throwaway branch that measures which CPU work the 1.20.6 tests
 * could avoid, and is deleted with that branch. The loader increments the counters from the places
 * that do the work: the section rebuild mixin, the per-frame hook, and the block writes.
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

    private GrugTestCounters() {}
}
