package net.grug.minecraft.core;

/**
 * The sections whose meshes a block's block or sky light change can reach, for a loader that has to
 * re-mark a client's meshes after a light packet is applied (#1207).
 *
 * <p>A section's mesh bakes the light its block faces read, and a face reads the light on the
 * outside of the face and the blocks around each of its vertices: the renderer's smooth lighting
 * samples the 2x2x2 block lights around a vertex, so a face on a section boundary reads the block
 * on the other side of the boundary, and a face whose vertex sits on an edge or corner of the
 * section also reads the block across that edge or corner. A light change in one section therefore
 * changes the mesh of the section across every boundary the changed block touches, including the
 * edge and corner neighbours, not only the changed section's own mesh.
 *
 * <p>What it does not reach is a section the changed block is not touching: a block in the middle
 * of a section is read by no other section's mesh. Marking all 26 neighbours for every changed
 * section rebuilt sections whose mesh could not have changed, and the earlier attempt that did that
 * was dropped because the extra rebuilds made {@code world_settled_beyond_view} run past its tick
 * budget (#1209). The caller therefore compares the section's old and new light layer, and asks
 * this class for the sections each block that actually changed can reach.
 *
 * <p>The answer for one block is the cross product of the directions in which it lies on a section
 * boundary, with the block's own section always in the set: an interior block reaches only its own
 * section, a block on a face boundary reaches the section across that face, an edge block reaches
 * the four sections around the edge, and a corner block reaches the eight sections around the
 * corner. Pure, so its unit test covers every block position instead of a loader carrying a rule a
 * dispatch-only run meets.
 */
public final class GrugLightSections {

    /**
     * The 27 section offsets, indexed by {@code (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)}, so the
     * block's own section is {@link #SELF}.
     */
    private static final int[][] OFFSETS = new int[27][3];

    /** The bit of the offset {@code (0, 0, 0)}, the block's own section. */
    public static final int SELF = index(0, 0, 0);

    static {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    OFFSETS[index(dx, dy, dz)] = new int[] {dx, dy, dz};
                }
            }
        }
    }

    private GrugLightSections() {}

    private static int index(int dx, int dy, int dz) {
        return (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
    }

    /**
     * The sections whose mesh can read the light of a block whose light changed.
     *
     * <p>The block is given in its section's local coordinates, each from 0 to 15. The returned
     * mask has one bit per {@link #offsetX}, {@link #offsetY} and {@link #offsetZ} triple, and the
     * block's own section is always set. A block on the section's minimum side of an axis reaches
     * the section at offset -1 on that axis, a block on its maximum side reaches the section at
     * offset +1, and a block in between reaches no section on that axis; the edges and corners are
     * the cross product of those choices.
     *
     * @param x the block's local x, from 0 to 15
     * @param y the block's local y, from 0 to 15
     * @param z the block's local z, from 0 to 15
     * @return a mask of the offsets that can read the block's light
     * @throws IllegalArgumentException if a coordinate is outside a section
     */
    public static int maskForBlock(int x, int y, int z) {
        if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) {
            throw new IllegalArgumentException(
                    "A block coordinate must be from 0 to 15, got " + x + ", " + y + ", " + z);
        }
        int mask = 0;
        for (int i = 0; i < OFFSETS.length; i++) {
            int dx = OFFSETS[i][0];
            int dy = OFFSETS[i][1];
            int dz = OFFSETS[i][2];
            if (reaches(dx, x) && reaches(dy, y) && reaches(dz, z)) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** Whether a section offset on one axis can read a block at that local coordinate. */
    private static boolean reaches(int offset, int local) {
        if (local == 0) {
            return offset == 0 || offset == -1;
        }
        if (local == 15) {
            return offset == 0 || offset == 1;
        }
        return offset == 0;
    }

    /** The x offset of a mask bit, from -1 to 1. */
    public static int offsetX(int index) {
        return OFFSETS[index][0];
    }

    /** The y offset of a mask bit, from -1 to 1. */
    public static int offsetY(int index) {
        return OFFSETS[index][1];
    }

    /** The z offset of a mask bit, from -1 to 1. */
    public static int offsetZ(int index) {
        return OFFSETS[index][2];
    }
}
