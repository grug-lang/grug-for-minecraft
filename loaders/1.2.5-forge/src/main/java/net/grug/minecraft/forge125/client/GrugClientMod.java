package net.grug.minecraft.forge125.client;

import net.grug.minecraft.forge125.block.GrugBoxRenderer;
import net.minecraft.src.BaseMod;
import net.minecraft.src.Block;
import net.minecraft.src.IBlockAccess;
import net.minecraft.src.RenderBlocks;

/**
 * The client half of the Forge 1.2.5 entry point, split out of {@code mod_Grug} because FML
 * reflects over a discovered {@code mod_} class's methods, and a dedicated server has no {@code
 * RenderBlocks}. This class is not named {@code mod_*}, so ModLoader never discovers it, and only
 * the client's {@code mod_Grug.load()} constructs it.
 */
public class GrugClientMod extends BaseMod {

    @Override
    public String getVersion() {
        return net.grug.minecraft.forge125.mod_Grug.VERSION;
    }

    @Override
    public void load() {}

    /**
     * Draws a custom-rendered block's geometry, called by FML when a block's render type is outside
     * RenderBlocks' own switch.
     *
     * <p>This is the 1.2.5 equivalent of Alpha's BlockRendererMixin: the game's own tesselation
     * does the drawing, so lighting, face culling and atlas coordinates come from the game rather
     * than from a second rule kept in step with it. The block does not also draw its cube, because
     * the default case in renderBlockByRenderType does not call renderStandardBlock.
     *
     * <p>The mining overlay goes through {@code RenderBlocks.renderBlockUsingTexture}, which sets
     * {@code overrideBlockTexture} and then reaches this hook for a custom render type. The face
     * methods GrugBoxRenderer draws through honour it, so a mined custom-rendered block shows the
     * crack on its own boxes. See #201.
     */
    @Override
    public boolean renderWorldBlock(
            RenderBlocks renderer,
            IBlockAccess world,
            int x,
            int y,
            int z,
            Block block,
            int modelID) {
        return GrugBoxRenderer.render(
                renderer, world, block, x, y, z, renderer.overrideBlockTexture >= 0);
    }
}
