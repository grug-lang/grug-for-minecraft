package net.grug.minecraft.forge125.block;

import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugBlockGeometry;
import net.grug.minecraft.grug.GrugBox;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.Block;
import net.minecraft.src.IBlockAccess;
import net.minecraft.src.RenderBlocks;
import net.minecraft.src.TileEntity;

import java.util.List;

/**
 * Draws the boxes a grug block entity's render pass recorded, through 1.2.5's own block
 * tesselation.
 *
 * <p>Each box is drawn by setting the block's bounds to the box and handing it back to {@code
 * renderStandardBlock}, which is what 1.2.5's fences and stairs do too: the block's bounds say
 * where the geometry is, and the renderer works out the atlas coordinates, the lighting and which
 * faces a neighbouring block hides. A grug-drawn box is therefore lit and culled exactly like the
 * cube it stands in for, with no second lighting rule to keep in step with the game's.
 *
 * <p>Going through the block's own texture is deliberate. The block's sprite still decides which
 * texture each face uses, so declaring custom rendering changes the shape and nothing else, which
 * is what a mod author expects of the assets that already work.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}, which JaCoCo drops
 * whole (see that annotation). The pass bookkeeping and box this works from are game-independent,
 * and are measured in core's GrugRenderPass and GrugBox.
 */
@GrugGenerated("draws into a chunk compile, so it is coupled to the renderer rather than to grug")
public final class GrugBoxRenderer {

    @GrugGenerated("utility class: never instantiated")
    private GrugBoxRenderer() {}

    /**
     * Draws a custom-rendered block's geometry, or reports that this block does not draw its own.
     *
     * <p>Called from FML's renderWorldBlock hook, which RenderBlocks reaches for any render type
     * outside its own switch.
     *
     * @param renderer the renderer whose world, lighting and texture atlas to use
     * @param world the world the renderer is drawing, which is where the block entity comes from
     * @param block the block being drawn, whose bounds each box is set on
     * @return false when the block does not draw its own geometry, so the caller draws its cube
     */
    public static boolean render(
            RenderBlocks renderer, IBlockAccess world, Block block, int x, int y, int z) {
        // This loader compiles to Java 8 bytecode, so no pattern matching here.
        if (!(block instanceof GrugBlock)) return false;
        GrugBlock grugBlock = (GrugBlock) block;
        if (!grugBlock.drawsCustomGeometry()) return false;

        TileEntity blockEntity = world.getBlockTileEntity(x, y, z);
        if (!(blockEntity instanceof GrugBlockEntity)) return false;
        GrugBlockEntity grugBlockEntity = (GrugBlockEntity) blockEntity;

        // The bounds have to be back to a full cube before this returns. 1.2.5 keeps min/max on
        // the shared Block instance rather than per position, so leaving a box behind would
        // reshape every later block of the same type in this chunk compile, and the frame after
        // it.
        try {
            for (GrugBox box : drawGeometry(grugBlock, grugBlockEntity, x, y, z)) {
                setBounds(block, box);
                renderer.renderStandardBlock(block, x, y, z);
            }
        } finally {
            block.setBlockBounds(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        }

        return true;
    }

    /**
     * Runs the block entity's render pass.
     *
     * <p>A block entity with no render function is handed to core rather than special-cased here:
     * core is what reports it, and it reports it as a block that declared custom rendering and then
     * drew nothing, which is what the author wrote.
     *
     * <p>The block and its position go with the call so the report can name them. A mod with a
     * machine that renders as nothing has one of those among a hundred blocks in a chunk, and a
     * report that cannot say which is a report the author has to search for.
     */
    private static List<GrugBox> drawGeometry(
            GrugBlock grugBlock, GrugBlockEntity blockEntity, int x, int y, int z) {
        return GrugBlockGeometry.draw(
                blockEntity.getGrugEntityHandle(),
                blockEntity.getRenderFnId(),
                describe(grugBlock, x, y, z));
    }

    /**
     * The block and where it is, for a report to name.
     *
     * <p>The id the block was registered under, which is the name a mod author wrote, rather than
     * the number the game knows it by. Taken from the block's own grug data rather than from the
     * world, so it costs nothing here: this runs inside a chunk compile.
     */
    private static String describe(GrugBlock grugBlock, int x, int y, int z) {
        GrugBlockData data = Grug.blockDataByFileId.get(grugBlock.blockFileId);
        String name = data != null ? data.id : "an unknown grug block";
        return name + " at " + x + ", " + y + ", " + z;
    }

    private static void setBounds(Block block, GrugBox box) {
        block.setBlockBounds(
                (float) box.x1(),
                (float) box.y1(),
                (float) box.z1(),
                (float) box.x2(),
                (float) box.y2(),
                (float) box.z2());
    }
}
