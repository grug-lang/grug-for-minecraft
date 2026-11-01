package net.grug.minecraft.forge125.block;

import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge125.mod_Grug;
import net.grug.minecraft.grug.ExportFns;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugBlockUse;
import net.grug.minecraft.grug.GrugBox;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugDisplayTicks;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugEntityType;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugShape;
import net.grug.minecraft.grug.GrugShapes;
import net.minecraft.src.AxisAlignedBB;
import net.minecraft.src.BlockContainer;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.Material;
import net.minecraft.src.MovingObjectPosition;
import net.minecraft.src.TileEntity;
import net.minecraft.src.Vec3D;
import net.minecraft.src.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A dynamic block backed by a grug block script.
 *
 * <p>Extends {@code BlockContainer} so 1.2.5's {@code World} gives it a {@link GrugBlockEntity} the
 * same way a furnace or chest gets one. Behaviour is delegated to the block script's exports, and
 * script data lives in {@link GrugDeclarations#blockDataByFileId}.
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
        // The game reads a block's opacity once, in the Block constructor, when the block's grug
        // data is not reachable yet. A block that draws its own geometry is not a full cube.
        if (drawsCustomGeometry()) {
            opaqueCubeLookup[id] = false;
            lightOpacity[id] = 0;
        }
    }

    /**
     * Whether the custom geometry is drawn flat, declared by set_flat_lighting: every face at the
     * light of the block's own position, with no per-side shade.
     */
    public boolean drawsFlatLighting() {
        return blockData().flatLighting;
    }

    /**
     * A block that draws its own geometry is not a full cube, so it neither blocks light nor hides
     * its neighbours' faces.
     *
     * <p>The Block constructor calls this before {@code blockFileId} is set, when there is no data
     * to read yet, so a missing entry answers as an ordinary opaque cube.
     */
    @Override
    public boolean isOpaqueCube() {
        GrugBlockData data = GrugDeclarations.blockDataByFileId.get(this.blockFileId);
        return data == null || !data.customRender;
    }

    public long getEntityFileId() {
        GrugBlockData data = blockData();
        if (data.blockEntityString == null) return Grug.INVALID_GRUG_FILE_ID;

        // "grug:foo_block_entity" -> "foo_block_entity"
        String cleanName =
                data.blockEntityString.substring(data.blockEntityString.lastIndexOf(':') + 1);
        return GrugEntities.entityFileIdsByName.getOrDefault(cleanName, Grug.INVALID_GRUG_FILE_ID);
    }

    public int getInventorySize() {
        return blockData().inventorySize;
    }

    /**
     * Whether the block draws its own geometry from its block entity's render function, declared by
     * set_custom_render or set_frame_render, instead of the cube its model describes.
     */
    public boolean drawsCustomGeometry() {
        return blockData().customRender;
    }

    /**
     * Whether any of that geometry is baked into the chunk's mesh, which a block that declared
     * set_frame_render draws per frame instead.
     */
    public boolean drawsChunkGeometry() {
        return blockData().drawsChunkGeometry();
    }

    /**
     * The render type the game's block renderer switches on.
     *
     * <p>A block that declares custom rendering returns the FML render ID {@code mod_Grug}
     * registered, which is outside the switch's own cases and so reaches FML's {@code
     * renderWorldBlock} hook. A block that does not returns 0, the standard cube.
     */
    @Override
    public int getRenderType() {
        if (drawsCustomGeometry()) {
            return mod_Grug.getCustomRenderId();
        }
        return 0;
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
            long worldId = GrugEntities.addEntity(GrugEntityType.Level, world);
            long playerId = GrugEntities.addEntity(GrugEntityType.Player, player);
            boolean handled = GrugBlockUse.use(blockHandle, worldId, x, y, z, playerId);
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
            long worldId = GrugEntities.addEntity(GrugEntityType.Level, world);
            ExportFns.Block_on_break(blockHandle, worldId, x, y, z);
            Grug.destroyEntity(blockHandle);
        }
        super.onBlockRemoval(world, x, y, z);
    }

    /**
     * Creates the placed tile's grug entity once {@code super} has put the tile in its chunk's map,
     * so an {@code init} that looks the tile up finds it. See {@link GrugBlockEntity#onRegistered}.
     */
    @Override
    public void onBlockAdded(World world, int x, int y, int z) {
        super.onBlockAdded(world, x, y, z);
        TileEntity tile = world.getBlockTileEntity(x, y, z);
        if (tile instanceof GrugBlockEntity) {
            ((GrugBlockEntity) tile).onRegistered();
        }
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

    /**
     * The game's random display tick, which the client calls for blocks it samples around the
     * player. A block that declared a display tick is asked with the generator the game handed
     * over, so the script draws from the same stream the reference's {@code randomDisplayTick}
     * does.
     */
    @Override
    public void randomDisplayTick(World world, int x, int y, int z, Random random) {
        GrugDisplayTicks.run(this.blockFileId, world, x, y, z, random::nextFloat);
        super.randomDisplayTick(world, x, y, z, random);
    }

    /**
     * The boxes the script declared, or null when the block never declared a shape. One call per
     * query, and only for a block that called set_shape.
     */
    private GrugShape shapeAt(World world, int x, int y, int z) {
        return GrugShapes.shape(this.blockFileId, world, x, y, z);
    }

    /**
     * Collides with the boxes the shape declared rather than with the full cube, which is what lets
     * a pipe be a centre with arms.
     *
     * <p>The game asks this for entity movement, so this is where a player is kept out of the space
     * a shape declares and free to walk through the rest of its block.
     */
    @Override
    @SuppressWarnings("rawtypes")
    public void getCollidingBoundingBoxes(
            World world, int x, int y, int z, AxisAlignedBB area, ArrayList boxes) {
        GrugShape shape = shapeAt(world, x, y, z);
        if (shape == null) {
            super.getCollidingBoundingBoxes(world, x, y, z, area, boxes);
            return;
        }

        for (GrugBox box : shape.collision()) {
            AxisAlignedBB collisionBox =
                    AxisAlignedBB.getBoundingBoxFromPool(
                            x + box.x1(),
                            y + box.y1(),
                            z + box.z1(),
                            x + box.x2(),
                            y + box.y2(),
                            z + box.z2());
            if (area.intersectsWith(collisionBox)) {
                boxes.add(collisionBox);
            }
        }
    }

    /**
     * The box the game targets and outlines, which is the one box around every selection box the
     * shape declared: this version's game can only target a single box.
     */
    @Override
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World world, int x, int y, int z) {
        GrugShape shape = shapeAt(world, x, y, z);
        if (shape == null) {
            return super.getSelectedBoundingBoxFromPool(world, x, y, z);
        }
        return union(x, y, z, shape.selection());
    }

    /**
     * The same box, for the callers that ask a block for one collision box instead of its list: an
     * arrow asks this before it sticks, and the box around the whole shape is the closest a
     * single-box answer can come to the arms.
     */
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        GrugShape shape = shapeAt(world, x, y, z);
        if (shape == null) {
            return super.getCollisionBoundingBoxFromPool(world, x, y, z);
        }
        return union(x, y, z, shape.collision());
    }

    /**
     * Clips the ray against the same single box the game outlines, which is the box the reference's
     * c(...) answers with: a click on the part of the block that box does not cover misses the
     * pipe. The inherited method clips against whatever bounds the block last set, which this
     * version's render and collision passes both change, so it cannot be used as it is.
     */
    @Override
    public MovingObjectPosition collisionRayTrace(
            World world, int x, int y, int z, Vec3D start, Vec3D end) {
        GrugShape shape = shapeAt(world, x, y, z);
        if (shape == null) {
            return super.collisionRayTrace(world, x, y, z, start, end);
        }

        // This version keeps one shape on the shared Block instance, so it goes back to the full
        // cube afterwards or the next block of this type is ray traced against this one's box.
        AxisAlignedBB box = union(x, y, z, shape.selection());
        this.setBlockBounds(
                (float) (box.minX - x),
                (float) (box.minY - y),
                (float) (box.minZ - z),
                (float) (box.maxX - x),
                (float) (box.maxY - y),
                (float) (box.maxZ - z));
        try {
            return super.collisionRayTrace(world, x, y, z, start, end);
        } finally {
            this.setBlockBounds(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * The one box around every box of {@code boxes}, in world coordinates, or null when there are
     * none. A shape may declare a selection box and no collision box, and this version says "no
     * collision" with a null box. A pooled box is handed back, as the game expects from the methods
     * this answers for, so the caller has to read it before it asks for another box.
     */
    private static AxisAlignedBB union(int x, int y, int z, List<GrugBox> boxes) {
        if (boxes.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (GrugBox box : boxes) {
            minX = Math.min(minX, box.x1());
            minY = Math.min(minY, box.y1());
            minZ = Math.min(minZ, box.z1());
            maxX = Math.max(maxX, box.x2());
            maxY = Math.max(maxY, box.y2());
            maxZ = Math.max(maxZ, box.z2());
        }
        return AxisAlignedBB.getBoundingBoxFromPool(
                x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ);
    }
}
