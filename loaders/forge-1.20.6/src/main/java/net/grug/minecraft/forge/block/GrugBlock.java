package net.grug.minecraft.forge.block;

import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugEntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new GrugBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        long blockHandle = Grug.createEntity(this.blockFileId);
        if (blockHandle != 0) {
            long worldId = Grug.addEntity(GrugEntityType.Level, level);
            long playerId = Grug.addEntity(GrugEntityType.Player, player);
            boolean handled = ExportFns.Block_use(blockHandle, worldId, pos.getX(), pos.getY(), pos.getZ(), playerId);
            Grug.destroyEntity(blockHandle);

            if (handled) {
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }
}
