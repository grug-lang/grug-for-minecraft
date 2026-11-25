package net.grug.minecraft.core;

import java.util.ArrayList;
import java.util.List;

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

    /** The 16-high sections the loaders that call this keep in a fixed grid: a 128-high world. */
    private static final int WORLD_SECTIONS = 8;

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

    /**
     * The sections a relit box covers, each as its own whole box in blocks: minX, minY, minZ, maxX,
     * maxY, maxZ, with both corners inclusive. A box that reaches past the top or the bottom of the
     * world is cut to the world, and one entirely outside it covers nothing.
     *
     * <p>A loader that applies light itself has to rebuild the mesh of every section whose baked
     * brightness can change, and its renderer's rebuild request is per section or per block range
     * rather than per cell. Handing it the sections the sweep covered, rather than the swept box,
     * is what makes the request cover the whole section: a section the box only partly covers bakes
     * faces from cells outside the box, so the mark has to reach all of it. Each box is a whole
     * section, and the game's own range request grows a box by one block, so a caller that passes
     * one of these marks that section and, through the one block it reaches into each neighbour,
     * the sections beside it.
     *
     * <p>The y section index is clamped to the world's own sections, because the loaders that call
     * this keep the sections of their 128-high world in a fixed grid indexed by that number: an
     * index past the grid wraps, and a box above the world would mark the sections at its bottom
     * instead of none. x and z need no clamp, since a section outside the loaded chunks is one
     * their renderer does not hold.
     *
     * <p>Pure, so its unit test covers a box inside one section, one that spans several, negative
     * coordinates, a box whose corners sit on section boundaries, one that spans more than one
     * section in y, and one outside the world.
     */
    public static List<int[]> sectionBoxes(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int fromX = minX >> 4;
        int fromY = Math.max(0, minY >> 4);
        int fromZ = minZ >> 4;
        int toX = maxX >> 4;
        int toY = Math.min(WORLD_SECTIONS - 1, maxY >> 4);
        int toZ = maxZ >> 4;
        List<int[]> boxes = new ArrayList<>();
        for (int sectionY = fromY; sectionY <= toY; sectionY++) {
            for (int sectionZ = fromZ; sectionZ <= toZ; sectionZ++) {
                for (int sectionX = fromX; sectionX <= toX; sectionX++) {
                    boxes.add(
                            new int[] {
                                sectionX << 4,
                                sectionY << 4,
                                sectionZ << 4,
                                (sectionX << 4) + 15,
                                (sectionY << 4) + 15,
                                (sectionZ << 4) + 15
                            });
                }
            }
        }
        return boxes;
    }
}
