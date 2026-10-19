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
    /** The direction each side points to, as x, y and z, indexed by side. */
    private static final int[][] NORMALS = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

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

    /**
     * This face turned about the block's centre by whole quarter turns, the way a model renderer
     * turns its boxes: about z first, then y, then x. A positive turn is clockwise seen from the
     * positive end of its axis, as the {@code x} and {@code y} of a blockstate variant are, so a
     * turn of 1 about z carries up to east and a turn of 1 about x carries up to north. The corners
     * keep their order and their texture coordinates, so the texture turns with the face, and the
     * side and the cull side follow the face to where it now points.
     */
    public GrugQuad turned(int xTurns, int yTurns, int zTurns) {
        GrugQuad result = this;
        int[] turns = {xTurns, yTurns, zTurns};
        for (int axis = 2; axis >= 0; axis--) {
            for (int i = Math.floorMod(-turns[axis], 4); i > 0; i--) {
                result = result.quarterTurn(axis);
            }
        }
        return result;
    }

    /**
     * This face moved by the given number of blocks along the world's axes, which is where a part
     * that slides along the way the model faces ends up whichever way the model was turned. The
     * side, the cull side and the texture coordinates stay as they are.
     */
    public GrugQuad moved(double dx, double dy, double dz) {
        double[] moved = positions.clone();
        for (int corner = 0; corner < 4; corner++) {
            moved[corner * 3] += dx;
            moved[corner * 3 + 1] += dy;
            moved[corner * 3 + 2] += dz;
        }
        return new GrugQuad(texture, side, cullSide, moved, uvs);
    }

    /** One counter-clockwise quarter turn about the axis, 0 for x, 1 for y and 2 for z. */
    private GrugQuad quarterTurn(int axis) {
        int from = (axis + 1) % 3;
        int to = (axis + 2) % 3;
        double[] turned = positions.clone();
        for (int corner = 0; corner < 4; corner++) {
            double a = positions[corner * 3 + from] - 0.5;
            double b = positions[corner * 3 + to] - 0.5;
            turned[corner * 3 + from] = 0.5 - b;
            turned[corner * 3 + to] = 0.5 + a;
        }
        return new GrugQuad(
                texture, turnedSide(side, from, to), turnedSide(cullSide, from, to), turned, uvs);
    }

    /**
     * Where a side points after a quarter turn that carries the axis {@code from} to {@code to}.
     */
    private static int turnedSide(int side, int from, int to) {
        if (side < 0) {
            return side;
        }
        int[] normal = NORMALS[side];
        int[] turned = normal.clone();
        turned[from] = -normal[to];
        turned[to] = normal[from];
        if (turned[1] != 0) {
            return turned[1] < 0 ? 0 : 1;
        }
        if (turned[2] != 0) {
            return turned[2] < 0 ? 2 : 3;
        }
        return turned[0] < 0 ? 4 : 5;
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
