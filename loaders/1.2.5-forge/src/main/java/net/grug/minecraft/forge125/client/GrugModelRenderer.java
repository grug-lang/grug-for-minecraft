package net.grug.minecraft.forge125.client;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.forge125.block.GrugBlock;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.grug.GrugModels;
import net.grug.minecraft.grug.GrugQuad;
import net.grug.minecraft.grug.GrugResourceIndex;
import net.minecraft.src.Block;
import net.minecraft.src.IBlockAccess;
import net.minecraft.src.ModLoader;
import net.minecraft.src.RenderBlocks;
import net.minecraft.src.RenderEngine;
import net.minecraft.src.Tessellator;
import net.minecraft.src.forge.ForgeHooksClient;
import net.minecraft.src.forge.MinecraftForgeClient;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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
 * <p>1.2.5 draws every block from the terrain atlas of 16x16 sprites, and a model's texture is a
 * file of any size addressed by its own texture coordinates, so the faces cannot come from the
 * atlas. Each texture is uploaded as a texture of its own instead, and drawn through Forge's own
 * texture switching, which is what a 1.2.5 block with a texture file of its own uses: {@code
 * MinecraftForgeClient.bindTexture} hands out a Tessellator for that texture during a chunk
 * compile, and Forge draws it with the texture bound at the end of the render pass, inside the
 * chunk's display list. Forge looks the name up in {@code ForgeHooksClient.textures} before asking
 * the render engine to load it, so registering the uploaded texture there under a name of grug's
 * own is what lets a texture from the mods tree go through it.
 *
 * <p>A face is lit by the game itself: the block's bounds are set to the box the face covers,
 * {@code renderStandardBlock} draws that box into a Tessellator that only records, and each corner
 * of the face takes the colour and brightness the game gave the same corner of the box's face. That
 * is the smooth lighting a block of the reference draws with, a light per corner from the blocks
 * around it, or the flat light per face when smooth lighting is off, with no second lighting rule
 * to keep in step with the game's. A face the game would not draw for that box, or one that is not
 * an axis-aligned rectangle, has no recorded corners, and falls back to the light of the block the
 * face looks towards, scaled by the game's per-side shade. A block that declared set_flat_lighting
 * is not opaque, so every face gets the light of its own position and no shade, the way
 * BuildCraft's engine renderer draws with GL lighting off.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}: it draws into the
 * game's renderer. What it draws is core's {@code GrugModels}, which is measured there.
 */
@GrugGenerated("draws into a chunk compile and the item renderer, so it is coupled to the renderer")
public final class GrugModelRenderer {

    /** The game's own shade per side, from RenderBlocks: 0 down, 1 up, 2 north ... 5 east. */
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
     * The rotation point of a box of the engine's model, which is the block's centre, as the box
     * renderer's coordinates are measured from it.
     */
    private static final float ROTATION_POINT = 0.5F;

    /** The prefix of the names grug's textures are registered with Forge under. */
    private static final String NAME_PREFIX = "grug-model:";

    /** The texture each model texture path was uploaded as. */
    private static final Map<String, Integer> TEXTURES = new HashMap<String, Integer>();

    /** The texture paths already reported missing, so a chunk rebuild does not report again. */
    private static final Set<String> REPORTED = new HashSet<String>();

    @GrugGenerated("utility class: never instantiated")
    private GrugModelRenderer() {}

    /**
     * How far RenderBlocks pulls a block face's larger u and larger v in, as a fraction of the
     * texture: every face method there takes 0.01 of a terrain texel, which is 0.01 of a model's 16
     * units, off both. Where a texel edge lands on a pixel's centre, that decides which texel the
     * pixel shows, so a model face lit as a block face is pulled in the same way. A flat-lit face
     * is drawn the way BuildCraft's engine renderer draws its ModelRenderer boxes, which has no
     * inset.
     */
    private static final double BLOCK_FACE_INSET = 0.01 / 16.0;

    /** How far apart a recorded corner and a face's corner may be and still be the same corner. */
    private static final double SAME_CORNER = 1.0E-5;

    /** Where renderStandardBlock's corners go when a face is lit. See {@link FaceRecorder}. */
    private static final FaceRecorder RECORDER = new FaceRecorder();

    /**
     * Draws {@code quads} for the block at x, y and z into the chunk being compiled, lit by {@code
     * renderer}, which must be the one compiling the chunk so that the light is that world's.
     */
    public static void drawInWorld(
            RenderBlocks renderer,
            IBlockAccess world,
            Block block,
            int x,
            int y,
            int z,
            List<GrugQuad> quads) {
        boolean flat = block instanceof GrugBlock && ((GrugBlock) block).drawsFlatLighting();
        for (Map.Entry<String, List<GrugQuad>> group : byTexture(quads).entrySet()) {
            String name = register(group.getKey());
            if (name == null) continue;

            MinecraftForgeClient.bindTexture(name);
            // Forge swaps Tessellator.instance for the texture's own, so it is read after the bind.
            Tessellator tessellator = Tessellator.instance;
            for (GrugQuad quad : group.getValue()) {
                int side = quad.side();
                int cull = quad.cullSide();
                if (cull >= 0
                        && !block.shouldSideBeRendered(
                                world,
                                x + OFFSET[cull][0],
                                y + OFFSET[cull][1],
                                z + OFFSET[cull][2],
                                cull)) {
                    continue;
                }
                float[][] lit = flat ? null : litCorners(renderer, block, x, y, z, quad);
                if (flat) {
                    tessellator.setBrightness(block.getMixedBrightnessForBlock(world, x, y, z));
                    tessellator.setColorOpaque_F(1.0F, 1.0F, 1.0F);
                } else if (lit == null) {
                    tessellator.setBrightness(
                            block.getMixedBrightnessForBlock(
                                    world,
                                    x + OFFSET[side][0],
                                    y + OFFSET[side][1],
                                    z + OFFSET[side][2]));
                    tessellator.setColorOpaque_F(SHADE[side], SHADE[side], SHADE[side]);
                }
                double maxU =
                        Math.max(Math.max(quad.u(0), quad.u(1)), Math.max(quad.u(2), quad.u(3)));
                double maxV =
                        Math.max(Math.max(quad.v(0), quad.v(1)), Math.max(quad.v(2), quad.v(3)));
                double inset = flat ? 0.0 : BLOCK_FACE_INSET;
                for (int corner = 0; corner < 4; corner++) {
                    if (lit != null) {
                        tessellator.setColorOpaque_F(
                                lit[corner][0], lit[corner][1], lit[corner][2]);
                        tessellator.setBrightness((int) lit[corner][3]);
                    }
                    tessellator.addVertexWithUV(
                            x + quad.x(corner),
                            y + quad.y(corner),
                            z + quad.z(corner),
                            quad.u(corner) == maxU ? quad.u(corner) - inset : quad.u(corner),
                            quad.v(corner) == maxV ? quad.v(corner) - inset : quad.v(corner));
                }
            }
            MinecraftForgeClient.unbindTexture();
        }
    }

    /**
     * Draws {@code quads} at the camera-relative block origin the frame renderer was handed, with
     * the model's own offset already applied to the matrix, the way BuildCraft's {@code
     * RenderEngine.render} draws its {@code ModelRenderer} boxes from its tile entity renderer.
     *
     * <p>This is the per-frame half of {@link #drawInWorld}: the vertices are submitted beside the
     * block instead of at its place in the world, which is what keeps an edge that moves every tick
     * on the pixel the reference puts it on. Every vertex is float32 either way, so a baked copy at
     * y=75 rounds about 64 times more coarsely than a copy at 0.5, and a plate's edge that crosses
     * a pixel's centre in between lands on either side of it.
     *
     * <p>Nothing here sets a brightness or a colour, as the reference sets neither: it disables GL
     * lighting and lets the tile entity pass's own lightmap and white colour stand, which is what a
     * frame pass draws under.
     *
     * <p>Each face is submitted on its own, starting at the corner the reference's box renderer
     * starts it at: see {@link GrugQuad#submittedCorner}. Two surfaces that cross at one depth are
     * decided by the triangles the rasteriser splits them into, so the split is the reference's.
     *
     * <p>The box is drawn around its own rotation point, which is what the reference's box renderer
     * translates to before it draws the box's coordinates: a vertex is submitted at half a block
     * from the model's origin with the matrix moved there, rather than at the origin with the half
     * block added to the vertex. The two are the same shape, but the reference's float matrix is
     * what decides a pixel whose centre sits on the edge of a box to the last bit, so the chain is
     * the reference's too.
     */
    public static void drawInFrame(
            IBlockAccess world, Block block, int x, int y, int z, List<GrugQuad> quads) {
        Tessellator tessellator = Tessellator.instance;
        GL11.glPushMatrix();
        GL11.glTranslatef(ROTATION_POINT, ROTATION_POINT, ROTATION_POINT);
        try {
            for (Map.Entry<String, List<GrugQuad>> group : byTexture(quads).entrySet()) {
                String name = register(group.getKey());
                if (name == null) continue;

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, TEXTURES.get(group.getKey()));
                for (GrugQuad quad : group.getValue()) {
                    int cull = quad.cullSide();
                    if (cull >= 0
                            && !block.shouldSideBeRendered(
                                    world,
                                    x + OFFSET[cull][0],
                                    y + OFFSET[cull][1],
                                    z + OFFSET[cull][2],
                                    cull)) {
                        continue;
                    }
                    tessellator.startDrawingQuads();
                    for (int corner = 0; corner < 4; corner++) {
                        int submitted = quad.submittedCorner(corner);
                        tessellator.addVertexWithUV(
                                quad.x(submitted) - ROTATION_POINT,
                                quad.y(submitted) - ROTATION_POINT,
                                quad.z(submitted) - ROTATION_POINT,
                                quad.u(submitted),
                                quad.v(submitted));
                    }
                    tessellator.draw();
                }
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    /**
     * The colour and brightness the game gives each corner of {@code quad}, as red, green, blue and
     * brightness, or null when the game draws no face with the same corners.
     *
     * <p>The block's bounds are set to the quad's own box, which is flat along the quad's normal,
     * and {@code renderStandardBlock} draws it into {@link #RECORDER}. A flat box gives two faces
     * in the quad's plane, one each way, so the face is told apart by the way it winds: the game
     * winds every face counter-clockwise seen from outside, which makes its normal point the way it
     * faces. The bounds go back to a full cube before this returns, as GrugBoxRenderer's do.
     */
    private static float[][] litCorners(
            RenderBlocks renderer, Block block, int x, int y, int z, GrugQuad quad) {
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

        Tessellator drawing = Tessellator.instance;
        RECORDER.corners.clear();
        Tessellator.instance = RECORDER;
        try {
            block.setBlockBounds(
                    (float) minX,
                    (float) minY,
                    (float) minZ,
                    (float) maxX,
                    (float) maxY,
                    (float) maxZ);
            renderer.renderStandardBlock(block, x, y, z);
        } finally {
            Tessellator.instance = drawing;
            block.setBlockBounds(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        }

        int[] normal = OFFSET[quad.side()];
        List<double[]> recorded = RECORDER.corners;
        for (int face = 0; face + 3 < recorded.size(); face += 4) {
            double[] a = recorded.get(face);
            double[] b = recorded.get(face + 1);
            double[] c = recorded.get(face + 2);
            // (b - a) x (c - b), the face's normal for a counter-clockwise winding.
            double nx = (b[1] - a[1]) * (c[2] - b[2]) - (b[2] - a[2]) * (c[1] - b[1]);
            double ny = (b[2] - a[2]) * (c[0] - b[0]) - (b[0] - a[0]) * (c[2] - b[2]);
            double nz = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            if (nx * normal[0] + ny * normal[1] + nz * normal[2] <= 0) continue;

            float[][] lit = new float[4][];
            for (int corner = 0; corner < 4; corner++) {
                lit[corner] =
                        recordedAt(
                                recorded,
                                face,
                                x + quad.x(corner),
                                y + quad.y(corner),
                                z + quad.z(corner));
                if (lit[corner] == null) return null;
            }
            return lit;
        }
        return null;
    }

    /** The light of the corner of the recorded face starting at {@code face} that is at x, y, z. */
    private static float[] recordedAt(
            List<double[]> recorded, int face, double x, double y, double z) {
        for (int i = face; i < face + 4; i++) {
            double[] corner = recorded.get(i);
            if (Math.abs(corner[0] - x) < SAME_CORNER
                    && Math.abs(corner[1] - y) < SAME_CORNER
                    && Math.abs(corner[2] - z) < SAME_CORNER) {
                return new float[] {
                    (float) corner[3], (float) corner[4], (float) corner[5], (float) corner[6]
                };
            }
        }
        return null;
    }

    /**
     * A Tessellator that keeps the corners RenderBlocks hands it instead of drawing them: each
     * one's position, and the colour and brightness set before it, which are only what
     * RenderBlocks' face methods call. Only the chunk compile thread draws, so one is enough.
     */
    @GrugGenerated("stands in for the game's Tessellator inside a chunk compile")
    private static final class FaceRecorder extends Tessellator {

        /** x, y, z, red, green, blue and brightness of every corner since the last clear. */
        final List<double[]> corners = new ArrayList<double[]>();

        // Named apart from Tessellator's own private fields, such as its brightness: the
        // reobfuscation
        // renames a field by its name through the class hierarchy, which would point these at the
        // game's fields, which this class cannot reach.
        private float cornerRed;
        private float cornerGreen;
        private float cornerBlue;
        private int cornerBrightness;

        @Override
        public void setColorOpaque_F(float red, float green, float blue) {
            cornerRed = red;
            cornerGreen = green;
            cornerBlue = blue;
        }

        @Override
        public void setBrightness(int brightness) {
            cornerBrightness = brightness;
        }

        @Override
        public void addVertexWithUV(double x, double y, double z, double u, double v) {
            corners.add(
                    new double[] {x, y, z, cornerRed, cornerGreen, cornerBlue, cornerBrightness});
        }
    }

    /**
     * Draws a custom-rendered grug block's item form from its item model, and answers whether it
     * did. A block that does not draw its own geometry is drawn by the game as its cube, and so is
     * one whose item model cannot be drawn, which core has reported.
     */
    public static boolean drawItemForm(Block block) {
        if (!(block instanceof GrugBlock)) return false;
        GrugBlock grugBlock = (GrugBlock) block;
        if (!grugBlock.drawsCustomGeometry()) return false;

        List<GrugQuad> quads =
                GrugModels.itemModel(
                        GrugCore.getAdapter().getGrugModsDirectory(),
                        GrugDeclarations.blockDataByFileId.get(grugBlock.blockFileId).id);
        if (quads == null) return false;

        drawAsItem(quads, grugBlock.drawsFlatLighting());
        return true;
    }

    /**
     * Draws {@code quads} as an item, centred on the origin the way the game draws a block item.
     *
     * <p>A block that declared set_flat_lighting is drawn with OpenGL lighting off, every face at
     * texture brightness times the current GL colour, the way BuildCraft 2.2.14's engine renderer
     * draws with GL lighting disabled. Every other block is lit from each face's normal.
     */
    public static void drawAsItem(List<GrugQuad> quads, boolean flat) {
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT | GL11.GL_ENABLE_BIT);
        if (flat) {
            GL11.glDisable(GL11.GL_LIGHTING);
        }
        GL11.glTranslatef(-0.5F, -0.5F, -0.5F);
        Tessellator tessellator = Tessellator.instance;
        for (Map.Entry<String, List<GrugQuad>> group : byTexture(quads).entrySet()) {
            String name = register(group.getKey());
            if (name == null) continue;

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, TEXTURES.get(group.getKey()));
            for (GrugQuad quad : group.getValue()) {
                tessellator.startDrawingQuads();
                // A flat face carries neither a normal nor a colour of its own, so with lighting
                // off it takes the current GL colour as it is: white in the GUI, and the world's
                // brightness where the game darkens a held or dropped item through glColor.
                if (!flat) {
                    float[] normal = NORMAL[quad.side()];
                    tessellator.setNormal(normal[0], normal[1], normal[2]);
                }
                for (int corner = 0; corner < 4; corner++) {
                    tessellator.addVertexWithUV(
                            quad.x(corner),
                            quad.y(corner),
                            quad.z(corner),
                            quad.u(corner),
                            quad.v(corner));
                }
                tessellator.draw();
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
    public static void reload(RenderEngine engine) {
        for (Map.Entry<String, Integer> entry : TEXTURES.entrySet()) {
            BufferedImage image = read(entry.getKey());
            if (image != null) {
                engine.setupTexture(image, entry.getValue());
            }
        }
    }

    /** The quads grouped by texture, keeping the order each texture first appears in. */
    private static Map<String, List<GrugQuad>> byTexture(List<GrugQuad> quads) {
        Map<String, List<GrugQuad>> groups = new LinkedHashMap<String, List<GrugQuad>>();
        for (GrugQuad quad : quads) {
            List<GrugQuad> group = groups.get(quad.texturePath());
            if (group == null) {
                group = new ArrayList<GrugQuad>();
                groups.put(quad.texturePath(), group);
            }
            group.add(quad);
        }
        return groups;
    }

    /**
     * The name {@code path} is registered with Forge under, uploading it the first time, or null
     * when no mod ships it.
     */
    private static String register(String path) {
        String name = NAME_PREFIX + path;
        if (TEXTURES.containsKey(path)) return name;

        BufferedImage image = read(path);
        if (image == null) return null;

        int texture = ModLoader.getMinecraftInstance().renderEngine.allocateAndSetupTexture(image);
        TEXTURES.put(path, texture);
        ForgeHooksClient.textures.put(name, texture);
        return name;
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
        } catch (IOException e) {
            throw Grug.fatal("Failed to read the model texture " + file, e);
        }
    }
}
