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
 * <p>A face is lit the way the game lights a block face with its smooth lighting off: by the light
 * of the block the face looks towards, scaled by the game's per-side shade. The light at the
 * block's own position is not used, because a grug block is opaque and its own position is dark. A
 * block that declared set_flat_lighting is not opaque, so every face gets the light of its own
 * position and no shade, the way BuildCraft's engine renderer draws with GL lighting off.
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

    /** The prefix of the names grug's textures are registered with Forge under. */
    private static final String NAME_PREFIX = "grug-model:";

    /** The texture each model texture path was uploaded as. */
    private static final Map<String, Integer> TEXTURES = new HashMap<String, Integer>();

    /** The texture paths already reported missing, so a chunk rebuild does not report again. */
    private static final Set<String> REPORTED = new HashSet<String>();

    @GrugGenerated("utility class: never instantiated")
    private GrugModelRenderer() {}

    /** Draws {@code quads} for the block at x, y and z into the chunk being compiled. */
    public static void drawInWorld(
            IBlockAccess world, Block block, int x, int y, int z, List<GrugQuad> quads) {
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
                if (flat) {
                    tessellator.setBrightness(block.getMixedBrightnessForBlock(world, x, y, z));
                    tessellator.setColorOpaque_F(1.0F, 1.0F, 1.0F);
                } else {
                    tessellator.setBrightness(
                            block.getMixedBrightnessForBlock(
                                    world,
                                    x + OFFSET[side][0],
                                    y + OFFSET[side][1],
                                    z + OFFSET[side][2]));
                    tessellator.setColorOpaque_F(SHADE[side], SHADE[side], SHADE[side]);
                }
                for (int corner = 0; corner < 4; corner++) {
                    tessellator.addVertexWithUV(
                            x + quad.x(corner),
                            y + quad.y(corner),
                            z + quad.z(corner),
                            quad.u(corner),
                            quad.v(corner));
                }
            }
            MinecraftForgeClient.unbindTexture();
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
                if (flat) {
                    tessellator.setColorOpaque_F(1.0F, 1.0F, 1.0F);
                } else {
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
