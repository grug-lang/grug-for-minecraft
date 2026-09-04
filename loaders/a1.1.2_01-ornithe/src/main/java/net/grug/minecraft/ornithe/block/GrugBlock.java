package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugEntityType;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.minecraft.block.BlockWithBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.world.World;

public class GrugBlock extends BlockWithBlockEntity {
    public final long blockFileId;

    public GrugBlock(int id, long blockFileId, Material material, float strength) {
        super(id, material);
        this.blockFileId = blockFileId;
        this.setStrength(strength);
    }

    public long getEntityFileId() {
        GrugBlockData data = Grug.blockDataByFileId.get(this.blockFileId);
        if (data != null && data.blockEntityString != null) {
            String[] parts = data.blockEntityString.split(":");
            String cleanName = parts.length == 2 ? parts[1] : data.blockEntityString;
            return Grug.entityFileIdsByName.getOrDefault(cleanName, Grug.INVALID_GRUG_FILE_ID);
        }
        return Grug.INVALID_GRUG_FILE_ID;
    }

    public int getInventorySize() {
        GrugBlockData data = Grug.blockDataByFileId.get(this.blockFileId);
        return data != null ? data.inventorySize : 0;
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
    protected BlockEntity createBlockEntity() {
        return new GrugBlockEntity();
    }
}
