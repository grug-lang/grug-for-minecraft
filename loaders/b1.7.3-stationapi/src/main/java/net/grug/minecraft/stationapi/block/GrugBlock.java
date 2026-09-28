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

    public GrugBlock(Identifier identifier, long blockFileId, Material material, float hardness) {
        super(identifier, material);
        this.identifier = identifier;
        this.blockFileId = blockFileId;
        this.setHardness(hardness);
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
    protected BlockEntity createBlockEntity() {
        return new GrugBlockEntity();
    }
}
