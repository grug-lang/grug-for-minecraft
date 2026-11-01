package net.grug.minecraft.stationapi.block;

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
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.modificationstation.stationapi.api.template.block.TemplateBlockWithEntity;
import net.modificationstation.stationapi.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

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
     * Whether the custom geometry is drawn flat, declared by set_flat_lighting: every face at the
     * light of the block's own position, with no per-side shade.
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

    /**
     * A block that draws its own geometry is not a full cube, so it does not hide its neighbours'
     * faces. The game culls a face by asking the world, which asks this method, and the {@code
     * BLOCKS_OPAQUE} table the init listener clears is not what it reads (#577).
     *
     * <p>The Block constructor calls this before {@code blockFileId} is set, when there is no data
     * to read yet, so a missing entry answers as an ordinary opaque cube.
     */
    @Override
    public boolean isOpaque() {
        GrugBlockData data = GrugDeclarations.blockDataByFileId.get(this.blockFileId);
        return data == null || !data.customRender;
    }

    @Override
    public boolean onUse(World world, int x, int y, int z, PlayerEntity player) {
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
        return super.onUse(world, x, y, z, player);
    }

    @Override
    public void onBreak(World world, int x, int y, int z) {
        long blockHandle = Grug.createEntity(this.blockFileId);
        if (blockHandle != 0) {
            long worldId = GrugEntities.addEntity(GrugEntityType.Level, world);
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
    public void addIntersectingBoundingBox(
            World world, int x, int y, int z, Box area, ArrayList boxes) {
        GrugShape shape = shapeAt(world, x, y, z);
        if (shape == null) {
            super.addIntersectingBoundingBox(world, x, y, z, area, boxes);
            return;
        }

        for (GrugBox box : shape.collision()) {
            Box collisionBox =
                    Box.createCached(
                            x + box.x1(),
                            y + box.y1(),
                            z + box.z1(),
                            x + box.x2(),
                            y + box.y2(),
                            z + box.z2());
            if (area.intersects(collisionBox)) {
                boxes.add(collisionBox);
            }
        }
    }

    /**
     * The box the game targets and outlines, which is the one box around every selection box the
     * shape declared: this version's game can only target a single box.
     */
    @Override
    public Box getBoundingBox(World world, int x, int y, int z) {
        GrugShape shape = shapeAt(world, x, y, z);
        if (shape == null) {
            return super.getBoundingBox(world, x, y, z);
        }
        return union(x, y, z, shape.selection());
    }

    /**
     * The same box, for the callers that ask a block for one collision box instead of its list, and
     * the one {@link #addIntersectingBoundingBox} answers for when a version asks a block for its
     * shape.
     */
    @Override
    public Box getCollisionShape(World world, int x, int y, int z) {
        GrugShape shape = shapeAt(world, x, y, z);
        if (shape == null) {
            return super.getCollisionShape(world, x, y, z);
        }
        return union(x, y, z, shape.collision());
    }

    /**
     * Clips the ray against the same single box the game outlines, which is the box the reference
     * targets: a click on the part of the block that box does not cover misses the pipe. The
     * inherited method clips against whatever bounds the block last set, which this version's
     * render and collision passes both change, so it cannot be used as it is.
     */
    @Override
    public HitResult raycast(World world, int x, int y, int z, Vec3d start, Vec3d end) {
        GrugShape shape = shapeAt(world, x, y, z);
        if (shape == null) {
            return super.raycast(world, x, y, z, start, end);
        }

        // This version keeps one shape on the shared Block instance, so it goes back to the full
        // cube afterwards or the next block of this type is ray traced against this one's box.
        Box box = union(x, y, z, shape.selection());
        this.setBoundingBox(
                (float) (box.minX - x),
                (float) (box.minY - y),
                (float) (box.minZ - z),
                (float) (box.maxX - x),
                (float) (box.maxY - y),
                (float) (box.maxZ - z));
        try {
            return super.raycast(world, x, y, z, start, end);
        } finally {
            this.setBoundingBox(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * The one box around every box of {@code boxes}, in world coordinates, or null when there are
     * none. A shape may declare a selection box and no collision box, and this version says "no
     * collision" with a null box. A cached box is handed back, as the game expects from the methods
     * this answers for, so the caller has to read it before it asks for another box.
     */
    private static Box union(int x, int y, int z, List<GrugBox> boxes) {
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
        return Box.createCached(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ);
    }
}
