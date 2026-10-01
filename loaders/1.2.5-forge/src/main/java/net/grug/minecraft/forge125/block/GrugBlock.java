package net.grug.minecraft.forge125.block;

import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugEntityType;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.BlockContainer;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.Material;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;

/**
 * A dynamic block backed by a grug block script.
 *
 * <p>Extends {@code BlockContainer} so 1.2.5's {@code World} gives it a {@link GrugBlockEntity} the
 * same way a furnace or chest gets one. Behaviour is delegated to the block script's exports, and
 * script data lives in {@link Grug#blockDataByFileId}.
 */
public class GrugBlock extends BlockContainer {
    public final long blockFileId;

    /**
     * Sprite per face (down, up, north, south, west, east), or null to use `sprite` for all faces.
     */
    public int[] faceSprites;

    /** The single sprite used when {@link #faceSprites} is null. */
    public int sprite;

    public GrugBlock(int id, long blockFileId, Material material, float strength) {
        super(id, 0, material);
        this.blockFileId = blockFileId;
        this.sprite = this.blockIndexInTexture;
        this.setHardness(strength);
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
    public int getBlockTextureFromSide(int side) {
        if (faceSprites != null && side >= 0 && side < faceSprites.length) {
            return faceSprites[side];
        }
        return this.sprite;
    }

    @Override
    public boolean blockActivated(World world, int x, int y, int z, EntityPlayer player) {
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
        return super.blockActivated(world, x, y, z, player);
    }

    @Override
    public void onBlockRemoval(World world, int x, int y, int z) {
        long blockHandle = Grug.createEntity(this.blockFileId);
        if (blockHandle != 0) {
            long worldId = Grug.addEntity(GrugEntityType.Level, world);
            ExportFns.Block_on_break(blockHandle, worldId, x, y, z);
            Grug.destroyEntity(blockHandle);
        }
        super.onBlockRemoval(world, x, y, z);
    }

    @Override
    public TileEntity getBlockEntity() {
        return new GrugBlockEntity();
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, int blockId) {
        TileEntity tile = world.getBlockTileEntity(x, y, z);
        if (tile instanceof GrugBlockEntity) {
            ((GrugBlockEntity) tile).notifyNeighborChanged();
        }
        super.onNeighborBlockChange(world, x, y, z, blockId);
    }
}
