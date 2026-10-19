package net.grug.minecraft.stationapi.client;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.grug.GrugModels;
import net.grug.minecraft.grug.GrugQuad;
import net.grug.minecraft.grug.GrugResourceIndex;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.world.BlockView;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
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
 * <p>Beta draws every block from one terrain atlas, and a model's texture is a file of any size
 * addressed by its own texture coordinates, so the faces cannot come from the atlas. Each texture
 * is uploaded as a texture of its own instead, and a chunk compile switches to it for the faces
 * that use it: the Tessellator is flushed, the texture bound, the faces drawn and flushed, and the
 * binding put back. A chunk is compiled into a display list, which records the binds along with the
 * geometry, so the list draws the faces with their texture every frame. The binding is put back
 * with glPushAttrib and glPopAttrib rather than by binding the atlas by name, so whatever the
 * renderer had bound when it called the list is what the rest of the list draws with.
 *
 * <p>A face is lit the way the game lights a block face with its smooth lighting off: by the light
 * of the block the face looks towards, scaled by the game's per-side shade. The light at the
 * block's own position is not used, because a grug block is opaque and its own position is dark.
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

    /** The texture each model texture path was uploaded as. */
    private static final Map<String, Integer> TEXTURES = new HashMap<>();

    /** The texture paths already reported missing, so a chunk rebuild does not report again. */
    private static final Set<String> REPORTED = new HashSet<>();

    private GrugModelRenderer() {}

    /**
     * Draws {@code quads} for the block at x, y and z into the chunk being compiled.
     *
     * <p>Called with the Tessellator begun and the chunk's offset set, and leaves it the same way,
     * so the blocks after this one carry on into the same display list.
     */
    public static void drawInWorld(
            BlockView world, Block block, int x, int y, int z, List<GrugQuad> quads) {
        if (quads.isEmpty()) return;

        boolean flat = block instanceof GrugBlock grugBlock && grugBlock.drawsFlatLighting();

        TextureManager textures =
                ((Minecraft) FabricLoader.getInstance().getGameInstance()).textureManager;
        Tessellator tesselator = Tessellator.INSTANCE;
        tesselator.draw();
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);

        for (Map.Entry<String, List<GrugQuad>> group : byTexture(quads).entrySet()) {
            Integer texture = texture(textures, group.getKey());
            if (texture == null) continue;

            textures.bindTexture(texture);
            tesselator.startQuads();
            for (GrugQuad quad : group.getValue()) {
                int side = quad.side();
                int nx = x + OFFSET[side][0];
                int ny = y + OFFSET[side][1];
                int nz = z + OFFSET[side][2];
                if (quad.cullSide() >= 0
                        && !block.isSideVisible(
                                world,
                                x + OFFSET[quad.cullSide()][0],
                                y + OFFSET[quad.cullSide()][1],
                                z + OFFSET[quad.cullSide()][2],
                                quad.cullSide())) {
                    continue;
                }
                float light =
                        flat
                                ? block.getLuminance(world, x, y, z)
                                : block.getLuminance(world, nx, ny, nz) * SHADE[side];
                tesselator.color(light, light, light);
                for (int corner = 0; corner < 4; corner++) {
                    tesselator.vertex(
                            x + quad.x(corner),
                            y + quad.y(corner),
                            z + quad.z(corner),
                            quad.u(corner),
                            quad.v(corner));
                }
            }
            tesselator.draw();
        }

        GL11.glPopAttrib();
        tesselator.startQuads();
    }

    /**
     * Draws the box the block's bounding box is set to with no per-side shade, every face at the
     * light of the block's own position: the flat look BuildCraft's engine renderer has with GL
     * lighting off.
     *
     * <p>A face is still left out when a neighbour hides it, as {@code renderBlock} does, but its
     * brightness is never the neighbour's: the block is not opaque, so its own position holds real
     * light.
     */
    public static void renderFlat(
            BlockRenderManager renderer, BlockView world, Block block, int x, int y, int z) {
        float light = block.getLuminance(world, x, y, z);
        Tessellator.INSTANCE.color(light, light, light);
        if (block.isSideVisible(world, x, y - 1, z, 0)) {
            renderer.renderBottomFace(block, x, y, z, block.getTextureId(world, x, y, z, 0));
        }
        if (block.isSideVisible(world, x, y + 1, z, 1)) {
            renderer.renderTopFace(block, x, y, z, block.getTextureId(world, x, y, z, 1));
        }
        if (block.isSideVisible(world, x, y, z - 1, 2)) {
            renderer.renderEastFace(block, x, y, z, block.getTextureId(world, x, y, z, 2));
        }
        if (block.isSideVisible(world, x, y, z + 1, 3)) {
            renderer.renderWestFace(block, x, y, z, block.getTextureId(world, x, y, z, 3));
        }
        if (block.isSideVisible(world, x - 1, y, z, 4)) {
            renderer.renderNorthFace(block, x, y, z, block.getTextureId(world, x, y, z, 4));
        }
        if (block.isSideVisible(world, x + 1, y, z, 5)) {
            renderer.renderSouthFace(block, x, y, z, block.getTextureId(world, x, y, z, 5));
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
     * full texture brightness, the way BuildCraft 2.2.14's engine renderer draws with GL lighting
     * disabled. Every other block is lit from each face's normal.
     */
    public static void drawAsItem(List<GrugQuad> quads, boolean flat) {
        TextureManager textures =
                ((Minecraft) FabricLoader.getInstance().getGameInstance()).textureManager;
        Tessellator tesselator = Tessellator.INSTANCE;

        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT | GL11.GL_ENABLE_BIT);
        if (flat) {
            GL11.glDisable(GL11.GL_LIGHTING);
        }
        GL11.glTranslatef(-0.5F, -0.5F, -0.5F);
        for (Map.Entry<String, List<GrugQuad>> group : byTexture(quads).entrySet()) {
            Integer texture = texture(textures, group.getKey());
            if (texture == null) continue;

            textures.bindTexture(texture);
            for (GrugQuad quad : group.getValue()) {
                tesselator.startQuads();
                if (flat) {
                    tesselator.color(1.0F, 1.0F, 1.0F);
                } else {
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
                tesselator.draw();
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
        TextureManager textures =
                ((Minecraft) FabricLoader.getInstance().getGameInstance()).textureManager;
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
