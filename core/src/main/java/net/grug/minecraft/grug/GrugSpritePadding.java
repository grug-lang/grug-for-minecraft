package net.grug.minecraft.grug;

/**
 * One texel of a stitched sprite's own edge, on each of its four sides.
 *
 * <p>StationAPI packs every block and item sprite into one game atlas, and a face whose texture
 * coordinate stands exactly on its sprite's edge samples the texel on the other side of that edge,
 * which belongs to whichever sprite the packer put there. The coordinate is correctly on the
 * sprite; what is wrong is the atlas, so the fix is to give the sprite a border of its own edge
 * texels rather than to move any coordinate (#919).
 *
 * <p>The border repeats the sprite's outermost row and column, which is the colour the face would
 * have sampled if its coordinate had landed the smallest fraction inside the sprite. Nothing is
 * rescaled: a full face still spans its sprite's content exactly, so every interior texel it draws
 * is the one it drew before, and only the texels that were picked up from a neighbour change.
 *
 * <p>This class holds only the decision, over plain pixel ints, so it is unit-tested here; the
 * loader turns a sprite frame into the padded image and hands that to the atlas.
 */
public final class GrugSpritePadding {

    /** How many texels of its own edge a sprite gains on each of its four sides. */
    public static final int PAD = 1;

    private GrugSpritePadding() {}

    /**
     * The width {@code width} texels of sprite take in the atlas once padded, which is what the
     * stitcher has to reserve for it.
     */
    public static int paddedWidth(int width) {
        return width + 2 * PAD;
    }

    /** The height {@code height} texels of sprite take in the atlas once padded. */
    public static int paddedHeight(int height) {
        return height + 2 * PAD;
    }

    /**
     * The padded form of one frame: {@code argb} in row-major order, {@code width} by {@code
     * height} pixels, with a border of {@link #PAD} texels of its own edge added on every side.
     *
     * <p>Each border texel is the nearest pixel of the frame on that side, so the padded result is
     * the frame surrounded by copies of its own first and last row and column, and each of the four
     * corners is the frame's corner pixel. The frame itself is moved {@link #PAD} texels in from
     * the top left and is otherwise unchanged, pixel for pixel.
     *
     * @param width the frame's width in pixels, at least 1
     * @param height the frame's height in pixels, at least 1
     * @param argb the frame's pixels in row-major order, {@code width} times {@code height} of them
     */
    public static int[] padded(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "a sprite is at least one texel across, was " + width + "x" + height);
        }
        if (argb.length < width * height) {
            throw new IllegalArgumentException(
                    "a "
                            + width
                            + "x"
                            + height
                            + " frame holds "
                            + (width * height)
                            + " pixels, got "
                            + argb.length);
        }
        int paddedWidth = paddedWidth(width);
        int paddedHeight = paddedHeight(height);
        int[] padded = new int[paddedWidth * paddedHeight];
        for (int y = 0; y < paddedHeight; y++) {
            int sourceY = edge(y - PAD, height);
            for (int x = 0; x < paddedWidth; x++) {
                padded[y * paddedWidth + x] = argb[sourceY * width + edge(x - PAD, width)];
            }
        }
        return padded;
    }

    /** The pixel {@code index} names inside a side of {@code size} pixels, clamped to that side. */
    private static int edge(int index, int size) {
        if (index < 0) return 0;
        if (index >= size) return size - 1;
        return index;
    }
}
