package net.grug.minecraft.grug;

/**
 * One textured face of a model element, as the draw_model host function recorded it during a block
 * entity's render pass.
 *
 * <p>The four corners are block-local, from 0 to 1 on each axis, in the order the game's own model
 * baker puts them: counter-clockwise seen from outside the face, so a loader that draws them in
 * this order gets the face back-face culled the same way as every other block face. Each corner
 * carries its texture coordinate as a fraction of the whole texture, from 0 to 1, so a loader that
 * binds the texture itself uses them as they are, and one that draws from an atlas maps them into
 * the sprite.
 *
 * <p>The side is the one the face points to, numbered the way the rest of the codebase numbers
 * them: 0 down, 1 up, 2 north, 3 south, 4 west and 5 east. The cull side is the model's {@code
 * cullface}: the neighbour on that side hides the face when it is a full opaque block, and -1 means
 * the model asked for the face to be drawn whatever is next to it.
 */
public final class GrugQuad {
    private final String texture;
    private final int side;
    private final int cullSide;
    private final double[] positions;
    private final double[] uvs;

    /**
     * @param texture the texture reference, namespace and all, such as {@code
     *     grug:block/redstone_engine_base}
     * @param positions the four corners' x, y and z, twelve numbers in corner order
     * @param uvs the four corners' u and v, eight numbers in corner order
     */
    public GrugQuad(String texture, int side, int cullSide, double[] positions, double[] uvs) {
        this.texture = texture;
        this.side = side;
        this.cullSide = cullSide;
        this.positions = positions.clone();
        this.uvs = uvs.clone();
    }

    /** The texture reference, such as {@code grug:block/redstone_engine_base}. */
    public String texture() {
        return texture;
    }

    /**
     * Where the texture lives in a mod, such as {@code
     * assets/grug/textures/block/redstone_engine_base.png}, which is the path every loader reads it
     * from.
     */
    public String texturePath() {
        return GrugModels.texturePath(texture);
    }

    /** The side the face points to: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east. */
    public int side() {
        return side;
    }

    /** The side whose neighbour may hide this face, or -1 when none may. */
    public int cullSide() {
        return cullSide;
    }

    public double x(int corner) {
        return positions[corner * 3];
    }

    public double y(int corner) {
        return positions[corner * 3 + 1];
    }

    public double z(int corner) {
        return positions[corner * 3 + 2];
    }

    /** The corner's u, as a fraction of the texture's width. */
    public double u(int corner) {
        return uvs[corner * 2];
    }

    /** The corner's v, as a fraction of the texture's height. */
    public double v(int corner) {
        return uvs[corner * 2 + 1];
    }

    @Override
    public String toString() {
        return "GrugQuad[" + texture + ", side " + side + "]";
    }
}
