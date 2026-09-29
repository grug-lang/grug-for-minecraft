package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugEntityType;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.block.BlockWithBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.world.World;

public class GrugBlock extends BlockWithBlockEntity {
    public final long blockFileId;

    /**
     * Sprite per face (down, up, north, south, west, east), or null to use `sprite` for all faces.
     */
    public int[] faceSprites;

    public GrugBlock(int id, long blockFileId, Material material, float strength) {
        super(id, material);
        this.blockFileId = blockFileId;
        this.setStrength(strength);
    }

    public long getEntityFileId() {
        GrugBlockData data = blockData();
        if (data.blockEntityString == null) return Grug.INVALID_GRUG_FILE_ID;

        String cleanName =
                data.blockEntityString.substring(data.blockEntityString.lastIndexOf(':') + 1);
        return Grug.entityFileIdsByName.getOrDefault(cleanName, Grug.INVALID_GRUG_FILE_ID);
    }

    public int getInventorySize() {
        return blockData().inventorySize;
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
    public int getSprite(int side) {
        if (faceSprites != null && side >= 0 && side < faceSprites.length) {
            return faceSprites[side];
        }
        return super.getSprite(side);
    }

    @Override
    public boolean use(World world, int x, int y, int z, PlayerEntity player) {
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
        return super.use(world, x, y, z, player);
    }

    @Override
    public void onRemoved(World world, int x, int y, int z) {
        long blockHandle = Grug.createEntity(this.blockFileId);
        if (blockHandle != 0) {
            long worldId = Grug.addEntity(GrugEntityType.Level, world);
            ExportFns.Block_on_break(blockHandle, worldId, x, y, z);
            Grug.destroyEntity(blockHandle);
        }
        super.onRemoved(world, x, y, z);
    }

    @Override
    public void neighborChanged(World world, int x, int y, int z, int side) {
        BlockEntity blockEntity = world.getBlockEntity(x, y, z);
        if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            grugBlockEntity.notifyNeighborChanged();
        }
        super.neighborChanged(world, x, y, z, side);
    }

    @Override
    protected BlockEntity createBlockEntity() {
        return new GrugBlockEntity();
    }
}
