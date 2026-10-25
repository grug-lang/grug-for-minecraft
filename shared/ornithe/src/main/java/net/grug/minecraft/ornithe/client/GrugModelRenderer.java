package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.grug.GrugModels;
import net.grug.minecraft.grug.GrugQuad;
import net.grug.minecraft.grug.GrugResourceIndex;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.client.render.block.BlockRenderer;
import net.minecraft.client.render.texture.TextureManager;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.world.WorldView;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * Draws the model faces a grug render pass recorded with draw_model, each from its own texture.
 *
 * <p>Alpha and Beta draw every block from one atlas of 16x16 sprites, and a model's texture is a
 * file of any size addressed by its own texture coordinates, so the faces cannot come from the
 * atlas. Each texture is uploaded as a texture of its own instead, and a chunk compile switches to
 * it for the faces that use it: the Tesselator is flushed, the texture bound, the faces drawn and
 * flushed, and the binding put back. A chunk is compiled into a display list, which records the
 * binds along with the geometry, so the list draws the faces with their texture every frame. The
 * binding is put back with glPushAttrib and glPopAttrib rather than by binding the atlas by name,
 * so whatever the renderer had bound when it called the list is what the rest of the list draws
 * with.
 *
 * <p>A face is lit by the game itself: the block's shape is set to the box the face covers, {@code
 * tesselateBlock} draws that box while TesselatorMixin records its corners instead of drawing them,
 * and each corner of the face takes the colour the game gave the same corner of the box's face.
 * That is Beta's smooth lighting, a light per corner from the blocks around it, when it is on, and
 * the flat light per face the game gives its own blocks otherwise, as on Alpha, with no second
 * lighting rule to keep in step with the game's. A face the game would not draw for that box, or
 * one that is not an axis-aligned rectangle, has no recorded corners, and falls back to the light
 * of the block the face looks towards, scaled by the game's per-side shade.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}: it draws into the
 * game's renderer. What it draws is core's {@code GrugModels}, which is measured there.
 */
@GrugGenerated("draws into a chunk compile and the item renderer, so it is coupled to the renderer")
public final class GrugModelRenderer {

    /** The game's own shade per side, from BlockRenderer: 0 down, 1 up, 2 north ... 5 east. */
    private static final float[] SHADE = {0.5F, 1.0F, 0.8F, 0.8F, 0.6F, 0.6F};

    /** The normal per side, for the item form, which the game lights with OpenGL. */
    private static final float[][] NORMAL = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    /** The offset to the neighbour per side, in x, y and z. */
    private static final int[][] OFFSET = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    /**
     * Whether Alpha is drawing an item into a GUI slot right now, which ItemRendererMixin sets
     * around {@code ItemRenderer.renderGuiItem}. Beta does not need it, so its loader never sets
     * it.
     */
    public static boolean drawingGuiItem = false;

    /**
     * How far the block renderer pulls a block face's larger u and larger v in, as a fraction of
     * the texture: every face method there takes 0.01 of a terrain texel, which is 0.01 of a
     * model's 16 units, off both. Where a texel edge lands on a pixel's centre, that decides which
     * texel the pixel shows, so a model face lit as a block face is pulled in the same way. A
     * flat-lit face is drawn the way BuildCraft's engine renderer draws its ModelRenderer boxes,
     * which has no inset.
     */
    private static final double BLOCK_FACE_INSET = 0.01 / 16.0;

    /** How far apart a recorded corner and a face's corner may be and still be the same corner. */
    private static final double SAME_CORNER = 1.0E-5;

    /** Whether TesselatorMixin is recording corners for {@link #litCorners} right now. */
    public static boolean recordingFaces = false;

    /** x, y, z, red, green and blue of every corner recorded since recording began. */
    private static final List<double[]> RECORDED = new ArrayList<>();

    private static float recordedRed;
    private static float recordedGreen;
    private static float recordedBlue;

    /** Keeps the colour the block renderer sets for the corners after it. See TesselatorMixin. */
    public static void recordColor(float red, float green, float blue) {
        recordedRed = red;
        recordedGreen = green;
        recordedBlue = blue;
    }

    /** Keeps a corner the block renderer draws, with its colour. See TesselatorMixin. */
    public static void recordCorner(double x, double y, double z) {
        RECORDED.add(new double[] {x, y, z, recordedRed, recordedGreen, recordedBlue});
    }

    /** The texture each model texture path was uploaded as. */
    private static final Map<String, Integer> TEXTURES = new HashMap<>();

    /** The texture paths already reported missing, so a chunk rebuild does not report again. */
    private static final Set<String> REPORTED = new HashSet<>();

    private GrugModelRenderer() {}

    /**
     * Draws {@code quads} for the block at x, y and z into the chunk being compiled.
     *
     * <p>Called with the Tesselator begun and the chunk's offset set, and leaves it the same way,
     * so the blocks after this one carry on into the same display list.
     */
    public static void drawInWorld(
            BlockRenderer renderer,
            WorldView world,
            Block block,
            int x,
            int y,
            int z,
            List<GrugQuad> quads) {
        if (quads.isEmpty()) return;

        boolean flat = block instanceof GrugBlock && ((GrugBlock) block).drawsFlatLighting();

        TextureManager textures = MinecraftInstance.get().textureManager;
        Tesselator tesselator = Tesselator.INSTANCE;
        tesselator.end();
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);

        for (Map.Entry<String, List<GrugQuad>> group : byTexture(quads).entrySet()) {
            Integer texture = texture(textures, group.getKey());
            if (texture == null) continue;

            textures.bind(texture);
            tesselator.begin();
            for (GrugQuad quad : group.getValue()) {
                int side = quad.side();
                int nx = x + OFFSET[side][0];
                int ny = y + OFFSET[side][1];
                int nz = z + OFFSET[side][2];
                if (quad.cullSide() >= 0
                        && !block.shouldRenderFace(
                                world,
                                x + OFFSET[quad.cullSide()][0],
                                y + OFFSET[quad.cullSide()][1],
                                z + OFFSET[quad.cullSide()][2],
                                quad.cullSide())) {
                    continue;
                }
                float[][] lit = flat ? null : litCorners(renderer, block, x, y, z, quad);
                if (lit == null) {
                    float light =
                            flat
                                    ? block.getBrightness(world, x, y, z)
                                    : block.getBrightness(world, nx, ny, nz) * SHADE[side];
                    tesselator.color(light, light, light);
                }
                double maxU =
                        Math.max(Math.max(quad.u(0), quad.u(1)), Math.max(quad.u(2), quad.u(3)));
                double maxV =
                        Math.max(Math.max(quad.v(0), quad.v(1)), Math.max(quad.v(2), quad.v(3)));
                double inset = flat ? 0.0 : BLOCK_FACE_INSET;
                for (int corner = 0; corner < 4; corner++) {
                    if (lit != null) {
                        tesselator.color(lit[corner][0], lit[corner][1], lit[corner][2]);
                    }
                    tesselator.vertex(
                            x + quad.x(corner),
                            y + quad.y(corner),
                            z + quad.z(corner),
                            quad.u(corner) == maxU ? quad.u(corner) - inset : quad.u(corner),
                            quad.v(corner) == maxV ? quad.v(corner) - inset : quad.v(corner));
                }
            }
            tesselator.end();
        }

        GL11.glPopAttrib();
        tesselator.begin();
    }

    /**
     * The colour the game gives each corner of {@code quad}, as red, green and blue, or null when
     * the game draws no face with the same corners.
     *
     * <p>The block's shape is set to the quad's own box, which is flat along the quad's normal, and
     * {@code tesselateBlock} draws it while TesselatorMixin records. A flat box gives two faces in
     * the quad's plane, one each way, so the face is told apart by the way it winds: the game winds
     * every face counter-clockwise seen from outside, which makes its normal point the way it
     * faces. The shape goes back to a full cube before this returns, as GrugBoxRenderer's does.
     */
    private static float[][] litCorners(
            BlockRenderer renderer, Block block, int x, int y, int z, GrugQuad quad) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            minX = Math.min(minX, quad.x(corner));
            minY = Math.min(minY, quad.y(corner));
            minZ = Math.min(minZ, quad.z(corner));
            maxX = Math.max(maxX, quad.x(corner));
            maxY = Math.max(maxY, quad.y(corner));
            maxZ = Math.max(maxZ, quad.z(corner));
        }

        RECORDED.clear();
        recordingFaces = true;
        try {
            block.setShape(
                    (float) minX,
                    (float) minY,
                    (float) minZ,
                    (float) maxX,
                    (float) maxY,
                    (float) maxZ);
            renderer.tesselateBlock(block, x, y, z);
        } finally {
            recordingFaces = false;
            block.setShape(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        }

        int[] normal = OFFSET[quad.side()];
        for (int face = 0; face + 3 < RECORDED.size(); face += 4) {
            double[] a = RECORDED.get(face);
            double[] b = RECORDED.get(face + 1);
            double[] c = RECORDED.get(face + 2);
            // (b - a) x (c - b), the face's normal for a counter-clockwise winding.
            double nx = (b[1] - a[1]) * (c[2] - b[2]) - (b[2] - a[2]) * (c[1] - b[1]);
            double ny = (b[2] - a[2]) * (c[0] - b[0]) - (b[0] - a[0]) * (c[2] - b[2]);
            double nz = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            if (nx * normal[0] + ny * normal[1] + nz * normal[2] <= 0) continue;

            float[][] lit = new float[4][];
            for (int corner = 0; corner < 4; corner++) {
                lit[corner] =
                        recordedAt(
                                face, x + quad.x(corner), y + quad.y(corner), z + quad.z(corner));
                if (lit[corner] == null) return null;
            }
            return lit;
        }
        return null;
    }

    /**
     * The colour of the corner of the recorded face starting at {@code face} that is at x, y, z.
     */
    private static float[] recordedAt(int face, double x, double y, double z) {
        for (int i = face; i < face + 4; i++) {
            double[] corner = RECORDED.get(i);
            if (Math.abs(corner[0] - x) < SAME_CORNER
                    && Math.abs(corner[1] - y) < SAME_CORNER
                    && Math.abs(corner[2] - z) < SAME_CORNER) {
                return new float[] {(float) corner[3], (float) corner[4], (float) corner[5]};
            }
        }
        return null;
    }

    /**
     * Draws the box the block's shape is set to with no per-side shade, every face at the light of
     * the block's own position: the flat look BuildCraft's engine renderer has with GL lighting
     * off.
     *
     * <p>A face is still left out when a neighbour hides it, as {@code tesselateBlock} does, but
     * its brightness is never the neighbour's: the block is not opaque, so its own position holds
     * real light.
     */
    public static void tesselateFlat(
            BlockRenderer renderer, WorldView world, Block block, int x, int y, int z) {
        float light = block.getBrightness(world, x, y, z);
        Tesselator.INSTANCE.color(light, light, light);
        if (block.shouldRenderFace(world, x, y - 1, z, 0)) {
            renderer.tesselateBottomFace(block, x, y, z, block.getSprite(world, x, y, z, 0));
        }
        if (block.shouldRenderFace(world, x, y + 1, z, 1)) {
            renderer.tesselateTopFace(block, x, y, z, block.getSprite(world, x, y, z, 1));
        }
        if (block.shouldRenderFace(world, x, y, z - 1, 2)) {
            renderer.tesselateNorthFace(block, x, y, z, block.getSprite(world, x, y, z, 2));
        }
        if (block.shouldRenderFace(world, x, y, z + 1, 3)) {
            renderer.tesselateSouthFace(block, x, y, z, block.getSprite(world, x, y, z, 3));
        }
        if (block.shouldRenderFace(world, x - 1, y, z, 4)) {
            renderer.tesselateWestFace(block, x, y, z, block.getSprite(world, x, y, z, 4));
        }
        if (block.shouldRenderFace(world, x + 1, y, z, 5)) {
            renderer.tesselateEastFace(block, x, y, z, block.getSprite(world, x, y, z, 5));
        }
    }

    /**
     * Draws a custom-rendered grug block's item form from its item model, and answers whether it
     * did. A block that does not draw its own geometry is drawn by the game as its cube, and so is
     * one whose item model cannot be drawn, which core has reported.
     */
    public static boolean drawItemForm(Block block) {
        return drawItemForm(block, false);
    }

    /**
     * {@link #drawItemForm(Block)}, mirrored front to back for a version whose GUI shows a block
     * item's far side.
     *
     * <p>Alpha turns a GUI block item so that the camera looks at it from below, and draws it with
     * the depth test off, so a cube shows whichever faces it draws last: the top and the sides
     * facing away from the camera. A cube looks the same either way, so nobody sees it. A model is
     * not a cube, and drawn honestly it shows its underside. Mirroring it through the plane of the
     * screen, around the item's own centre, and drawing it with the depth test on shows the faces
     * Alpha shows of a cube, so a model item looks like the block items beside it.
     */
    public static boolean drawItemForm(Block block, boolean showFarSide) {
        if (!(block instanceof GrugBlock)) return false;
        GrugBlock grugBlock = (GrugBlock) block;
        if (!grugBlock.drawsCustomGeometry()) return false;

        List<GrugQuad> quads =
                GrugModels.itemModel(
                        GrugCore.getAdapter().getGrugModsDirectory(),
                        GrugDeclarations.blockDataByFileId.get(grugBlock.blockFileId).id);
        if (quads == null) return false;

        if (showFarSide) {
            mirrorFrontToBack();
        }
        drawAsItem(quads, grugBlock.drawsFlatLighting());
        if (showFarSide) {
            GL11.glPopMatrix();
        }
        return true;
    }

    /**
     * Pushes the modelview matrix and mirrors it through the screen-parallel plane through the
     * current origin: every eye-space depth is reflected around the origin's own, and nothing moves
     * on screen.
     */
    private static void mirrorFrontToBack() {
        GL11.glPushMatrix();
        java.nio.FloatBuffer matrix = org.lwjgl.BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrix);
        float[] m = new float[16];
        matrix.get(m);
        // Column-major: the eye-space z of a point is row 2, which is m[2], m[6], m[10] and m[14].
        // Negating its first three reflects the depth around m[14], the origin's own.
        m[2] = -m[2];
        m[6] = -m[6];
        m[10] = -m[10];
        matrix.clear();
        matrix.put(m);
        matrix.flip();
        GL11.glLoadMatrix(matrix);
    }

    /**
     * Draws {@code quads} as an item, centred on the origin the way the game draws a block item.
     *
     * <p>A block that declared set_flat_lighting is drawn with OpenGL lighting off, every face at
     * texture brightness times the current GL colour, the way BuildCraft 2.2.14's engine renderer
     * draws with GL lighting disabled. Every other block is lit from each face's normal.
     */
    public static void drawAsItem(List<GrugQuad> quads, boolean flat) {
        TextureManager textures = MinecraftInstance.get().textureManager;
        Tesselator tesselator = Tesselator.INSTANCE;

        // Alpha draws a GUI item with the depth test off, which is harmless for the cube it expects
        // because a cube's back faces are culled, but a model is not convex: the base's top would
        // be drawn over the trunk standing on it. The test is switched on for the model and put
        // back afterwards.
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        if (flat) {
            GL11.glDisable(GL11.GL_LIGHTING);
        }
        GL11.glTranslatef(-0.5F, -0.5F, -0.5F);
        for (Map.Entry<String, List<GrugQuad>> group : byTexture(quads).entrySet()) {
            Integer texture = texture(textures, group.getKey());
            if (texture == null) continue;

            textures.bind(texture);
            for (GrugQuad quad : group.getValue()) {
                tesselator.begin();
                // A flat face carries neither a normal nor a colour of its own, so with lighting
                // off it takes the current GL colour as it is: white in the GUI, and the world's
                // brightness where the game darkens a held or dropped item through glColor.
                if (!flat) {
                    float[] normal = NORMAL[quad.side()];
                    tesselator.normal(normal[0], normal[1], normal[2]);
                }
                for (int corner = 0; corner < 4; corner++) {
                    tesselator.vertex(
                            quad.x(corner),
                            quad.y(corner),
                            quad.z(corner),
                            quad.u(corner),
                            quad.v(corner));
                }
                tesselator.end();
            }
        }
        GL11.glTranslatef(0.5F, 0.5F, 0.5F);
        GL11.glPopAttrib();
    }

    /**
     * Reads every model texture again into the texture it was uploaded as, so a hot reload of one
     * shows without the chunk being compiled again: the display list refers to the texture, not to
     * its pixels.
     */
    public static void reload() {
        TextureManager textures = MinecraftInstance.get().textureManager;
        for (Map.Entry<String, Integer> entry : TEXTURES.entrySet()) {
            BufferedImage image = read(entry.getKey());
            if (image != null) {
                textures.load(image, entry.getValue());
            }
        }
    }

    /** The quads grouped by texture, keeping the order each texture first appears in. */
    private static Map<String, List<GrugQuad>> byTexture(List<GrugQuad> quads) {
        Map<String, List<GrugQuad>> groups = new LinkedHashMap<>();
        for (GrugQuad quad : quads) {
            groups.computeIfAbsent(quad.texturePath(), path -> new java.util.ArrayList<>())
                    .add(quad);
        }
        return groups;
    }

    /**
     * The texture {@code path} was uploaded as, uploading it the first time, or null if no mod
     * ships it.
     */
    private static Integer texture(TextureManager textures, String path) {
        Integer texture = TEXTURES.get(path);
        if (texture != null) return texture;

        BufferedImage image = read(path);
        if (image == null) return null;

        texture = textures.load(image);
        TEXTURES.put(path, texture);
        return texture;
    }

    /**
     * The image at {@code path} in the mods tree, or null once a missing one has been reported.
     *
     * <p>A model naming a texture no mod ships is a mod-tree defect: the faces would draw with
     * whatever texture was bound last, which is a picture nobody chose. It is reported once and the
     * faces are left out.
     */
    private static BufferedImage read(String path) {
        File file =
                GrugResourceIndex.findDirectResource(
                        GrugCore.getAdapter().getGrugModsDirectory(), path);
        if (file == null) {
            if (REPORTED.add(path)) {
                GrugModTreeDefect.report(
                        "A model names the texture " + path + ", which no mod ships.");
            }
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                throw Grug.fatal("The model texture " + file + " is not a readable image.");
            }
            return image;
        } catch (java.io.IOException e) {
            throw Grug.fatal("Failed to read the model texture " + file, e);
        }
    }
}
