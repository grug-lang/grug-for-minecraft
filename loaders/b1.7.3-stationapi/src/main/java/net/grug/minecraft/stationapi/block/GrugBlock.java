package net.grug.minecraft.stationapi.block;

import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugEntityType;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import net.modificationstation.stationapi.api.template.block.TemplateBlockWithEntity;
import net.modificationstation.stationapi.api.util.Identifier;

public class GrugBlock extends TemplateBlockWithEntity {
    public final Identifier identifier;
    public long blockFileId;

    /**
     * Terrain-atlas sprite per face (down, up, north, south, west, east) for a block that declares
     * custom rendering, or null to use the block's own texture. Filled on every resource reload by
     * {@code ClientInitListener}, because each reload stitches a new terrain atlas and the indices
     * are positions in it.
     */
    public int[] faceTextures;

    public GrugBlock(Identifier identifier, long blockFileId, Material material, float hardness) {
        super(identifier, material);
        this.identifier = identifier;
        this.blockFileId = blockFileId;
        this.setHardness(hardness);
    }

    /**
     * The texture each face of a custom-rendered block draws with, which is the texture its model
     * asks for rather than the block's default sprite.
     *
     * <p>Only custom-rendered blocks are redirected: a block whose cube the game draws gets its
     * model's sprites from the model bake like every other block, and taking that away would make
     * the block's own model render with the wrong texture.
     */
    @Override
    public int getTexture(int side) {
        if (faceTextures != null && side >= 0 && side < faceTextures.length) {
            return faceTextures[side];
        }
        return super.getTexture(side);
    }

    public long getEntityFileId() {
        GrugBlockData data = blockData();
        if (data.blockEntityString == null) return Grug.INVALID_GRUG_FILE_ID;

        // "grug:foo_block_entity" -> "foo_block_entity"
        String cleanName =
                data.blockEntityString.substring(data.blockEntityString.lastIndexOf(':') + 1);

        return Grug.entityFileIdsByName.getOrDefault(cleanName, Grug.INVALID_GRUG_FILE_ID);
    }

    public int getInventorySize() {
        return blockData().inventorySize;
    }

    /**
     * Whether the block draws its own geometry from its block entity's render function, declared by
     * set_custom_render, instead of the cube its model describes.
     */
    public boolean drawsCustomGeometry() {
        return blockData().customRender;
    }

    /**
     * The data for this block.
     *
     * <p>A {@code GrugBlock} is only ever built from a declared block, so the lookup always has an
     * entry; the missing-entry path is kept out of coverage.
     */
    @GrugGenerated("block data lookup: a GrugBlock always has data")
    private GrugBlockData blockData() {
        return Grug.blockDataByFileId.get(this.blockFileId);
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, PlayerEntity player) {
        long blockHandle = Grug.createEntity(this.blockFileId);
        if (blockHandle != 0) {
            long worldId = Grug.addEntity(GrugEntityType.Level, world);
            long playerId = Grug.addEntity(GrugEntityType.Player, player);
            boolean handled = ExportFns.Block_use(blockHandle, worldId, x, y, z, playerId);
            Grug.destroyEntity(blockHandle);
            if (handled) {
                return true;
            }
        }
        return super.onUse(world, x, y, z, player);
    }

    @Override
    public void onBreak(World world, int x, int y, int z) {
        long blockHandle = Grug.createEntity(this.blockFileId);
        if (blockHandle != 0) {
            long worldId = Grug.addEntity(GrugEntityType.Level, world);
            ExportFns.Block_on_break(blockHandle, worldId, x, y, z);
            Grug.destroyEntity(blockHandle);
        }
        super.onBreak(world, x, y, z);
    }

    @Override
    public void neighborUpdate(World world, int x, int y, int z, int side) {
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            grugBlockEntity.notifyNeighborChanged();
        }
        super.neighborUpdate(world, x, y, z, side);
    }

    @Override
    protected BlockEntity createBlockEntity() {
        return new GrugBlockEntity();
    }
}
