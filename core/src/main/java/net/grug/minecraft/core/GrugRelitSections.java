package net.grug.minecraft.core;

/**
 * The sections a loader's room relight swept, for a loader whose client cannot reproduce the light
 * of those sections (#1240).
 *
 * <p>1.2.5's incremental light engine leaves the client's copy of a section at a field that depends
 * on the changes it saw, while its server relights the test's room by sweeping its own copy until
 * it reaches the one field the blocks define. The two copies are therefore not expected to agree in
 * those sections, and a server that answered "not applied" there would wait for an equality that is
 * not a defect. The read it needs is "is this position in one of the swept sections", which is this
 * class.
 *
 * <p>The box is compared as whole sections rather than as the box a sweep was asked for, because
 * the comparison a light read makes is per section: one cell the sweep left different changes its
 * section's fingerprint, so a position in a section the box only partly covers compares no more
 * usefully than one inside the box.
 *
 * <p>Pure, so that its unit test covers every combination of recorded and not recorded, inside,
 * outside and on a section boundary, instead of the loader's adapter carrying arms only a
 * dispatch-only run reaches.
 */
public final class GrugRelitSections {

    /** Whether a sweep has been recorded, so the box below describes the last room relit. */
    private boolean recorded;

    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    /**
     * Records the box the room relight swept, in blocks, replacing any earlier room's.
     *
     * <p>Called on the thread that builds the room, and once per build that was not already whole,
     * so the box the previous build recorded describes the room a test has now: a build that is
     * whole is the same room at the same radius.
     */
    public synchronized void record(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.recorded = true;
    }

    /**
     * Whether the section this position falls in is one the last sweep touched, or false where no
     * room has been relit yet.
     */
    public synchronized boolean covers(double x, double y, double z) {
        if (!recorded) {
            return false;
        }
        int sectionX = ((int) Math.floor(x)) >> 4;
        int sectionY = ((int) Math.floor(y)) >> 4;
        int sectionZ = ((int) Math.floor(z)) >> 4;
        return sectionX >= minX >> 4
                && sectionX <= maxX >> 4
                && sectionY >= minY >> 4
                && sectionY <= maxY >> 4
                && sectionZ >= minZ >> 4
                && sectionZ <= maxZ >> 4;
    }
}
