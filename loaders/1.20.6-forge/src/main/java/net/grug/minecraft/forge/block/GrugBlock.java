package net.grug.minecraft.forge.block;

import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugBlockUse;
import net.grug.minecraft.grug.GrugBox;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugEntityType;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugShape;
import net.grug.minecraft.grug.GrugShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
        return GrugEntities.entityFileIdsByName.getOrDefault(cleanName, Grug.INVALID_GRUG_FILE_ID);
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
     * Whether the custom geometry is drawn flat, declared by set_flat_lighting: no shade per side
     * and no smooth lighting.
     */
    public boolean drawsFlatLighting() {
        return blockData().flatLighting;
    }

    /**
     * The data for this block.
     *
     * <p>A {@code GrugBlock} is only ever built from a declared block, so the lookup always has an
     * entry; the missing-entry path is kept out of coverage.
     */
    @GrugGenerated("block data lookup: a GrugBlock always has data")
    private GrugBlockData blockData() {
        return GrugDeclarations.blockDataByFileId.get(this.blockFileId);
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
                long worldId = GrugEntities.addEntity(GrugEntityType.Level, level);
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
            long worldId = GrugEntities.addEntity(GrugEntityType.Level, level);
            long playerId = GrugEntities.addEntity(GrugEntityType.Player, player);
            boolean handled =
                    GrugBlockUse.use(
                            blockHandle, worldId, pos.getX(), pos.getY(), pos.getZ(), playerId);
            Grug.destroyEntity(blockHandle);

            if (handled) {
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    /**
     * The boxes the script declared, or null when the block never declared a shape.
     *
     * <p>A script reads its neighbours through a Level, and this version also asks for a block's
     * shape with a chunk's render region, which is a BlockGetter and not a Level. There the answer
     * is the full cube. That costs nothing visible for a block that draws its own geometry, which
     * turns occlusion off, so no neighbour's face is culled against this shape; a block that uses a
     * model keeps the cube its model describes, which is what the game already culls against.
     */
    private GrugShape shapeAt(BlockGetter level, BlockPos pos) {
        if (!(level instanceof Level)) {
            return null;
        }
        return GrugShapes.shape(this.blockFileId, level, pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * The box the game targets and outlines, which is the shape a player clicks and the outline
     * drawn around it. A version whose game can target several boxes draws each of them, so the
     * selection boxes are handed over as their own boxes rather than as one box around all of them.
     */
    @Override
    public VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        GrugShape shape = shapeAt(level, pos);
        if (shape == null) {
            return super.getShape(state, level, pos, context);
        }
        return voxels(shape.selection());
    }

    /**
     * Collides with the boxes the shape declared rather than with the full cube, which is what lets
     * a pipe be a centre with arms.
     */
    @Override
    public VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        GrugShape shape = shapeAt(level, pos);
        if (shape == null) {
            return super.getCollisionShape(state, level, pos, context);
        }
        return voxels(shape.collision());
    }

    /** The boxes as one voxel shape, which is how this version describes a shape. */
    private static VoxelShape voxels(List<GrugBox> boxes) {
        VoxelShape shape = Shapes.empty();
        for (GrugBox box : boxes) {
            shape =
                    Shapes.or(
                            shape,
                            Shapes.box(box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2()));
        }
        return shape;
    }
}
