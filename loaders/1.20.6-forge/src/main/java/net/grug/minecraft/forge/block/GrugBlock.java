package net.grug.minecraft.forge.block;

import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugEntityType;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GrugBlock extends Block implements EntityBlock {
    public final long blockFileId;

    public GrugBlock(Properties properties, long blockFileId) {
        super(properties);
        this.blockFileId = blockFileId;
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new GrugBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : (level0, pos0, state0, blockEntity) -> {
                    if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
                        grugBlockEntity.tick();
                    }
                };
    }

    @Override
    public void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            long blockHandle = Grug.createEntity(this.blockFileId);
            if (blockHandle != 0) {
                long worldId = Grug.addEntity(GrugEntityType.Level, level);
                ExportFns.Block_on_break(blockHandle, worldId, pos.getX(), pos.getY(), pos.getZ());
                Grug.destroyEntity(blockHandle);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    public void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block block,
            BlockPos fromPos,
            boolean isMoving) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof GrugBlockEntity grugBlockEntity) {
            grugBlockEntity.notifyNeighborChanged();
        }
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        long blockHandle = Grug.createEntity(this.blockFileId);
        if (blockHandle != 0) {
            long worldId = Grug.addEntity(GrugEntityType.Level, level);
            long playerId = Grug.addEntity(GrugEntityType.Player, player);
            boolean handled =
                    ExportFns.Block_use(
                            blockHandle, worldId, pos.getX(), pos.getY(), pos.getZ(), playerId);
            Grug.destroyEntity(blockHandle);

            if (handled) {
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }
}
