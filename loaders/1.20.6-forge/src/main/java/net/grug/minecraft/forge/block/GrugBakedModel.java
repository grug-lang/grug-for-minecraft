package net.grug.minecraft.forge.block;

import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugBlockGeometry;
import net.grug.minecraft.grug.GrugBox;
import net.grug.minecraft.grug.GrugDeclarations;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugGeometry;
import net.grug.minecraft.grug.GrugQuad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.client.model.pipeline.QuadBakingVertexConsumer;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the boxes a grug block entity's render pass recorded, as the quads the game's own model
 * tesselation lights and shades.
 *
 * <p>This is the 1.20.6 port's draw loop. The other loaders set the block's shape per recorded box
 * and hand it back to the game's block renderer; 1.20.6 has no mutable block shape to set, so the
 * shape is emitted as quads instead. Everything after that stays the game's: {@code
 * ModelBlockRenderer} computes the smooth lighting and the ambient occlusion for the quads it is
 * given, and each quad carries the sprite of the block's own model face, so declaring custom
 * rendering changes the shape and nothing else.
 *
 * <p>The quads are all returned from the null-direction call, which the game renders without asking
 * {@code Block.shouldRenderFace} for the block state. The per-direction calls return nothing, so
 * the state's full-cube face shape cannot hide a box that stands inset from a face. Culling is done
 * here instead, per box, with the same {@code Block.shouldRenderFace} the game would use: a box's
 * face is dropped only when the box reaches that block boundary and the neighbour hides a face
 * there. Alpha's renderer gets the same result by setting each box as the block's shape.
 *
 * <p>The block entity is found through the position the model data carries. {@code
 * SectionRenderDispatcher} calls {@link #getModelData} for every block it meshes and passes the
 * result to the quad calls, which is what puts the position and the level within reach of a baked
 * model, which is otherwise asked about a block state rather than about a place in the world.
 *
 * <p>A call with no position, which is the breaking overlay and anything else rendering the model
 * outside a level, draws nothing. A cube-shaped crack over a shape that is not a cube is not a
 * thing anyone asked for, and the other ports' hooks cancel the crack overlay along with the cube.
 * See #201.
 *
 * <p>Excluded from coverage because the class carries {@link GrugGenerated}, which JaCoCo drops
 * whole (see that annotation). The pass bookkeeping and box this works from are game-independent,
 * and are measured in core's GrugRenderPass and GrugBox.
 */
@GrugGenerated("draws into a chunk compile, so it is coupled to the renderer rather than to grug")
public class GrugBakedModel extends BakedModelWrapper<BakedModel> {

    /** Where in the world this block is, put here by {@link #getModelData}. */
    public static final ModelProperty<BlockPos> POS = new ModelProperty<>();

    /** The level the block is in, put here by {@link #getModelData}. */
    public static final ModelProperty<BlockAndTintGetter> LEVEL = new ModelProperty<>();

    /** Serializes the init-then-render window across the chunk mesher's worker threads. */
    private static final Object RENDER_LOCK = new Object();

    public GrugBakedModel(BakedModel original) {
        super(original);
    }

    @Override
    public ModelData getModelData(
            BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        // The position has to be copied: the section compiler walks a mutable position and mutates
        // it after this returns. The level is the RenderChunkRegion for a chunk compile, which is
        // safe to read from the meshing thread that is holding the data.
        return modelData.derive().with(POS, pos.immutable()).with(LEVEL, level).build();
    }

    /**
     * Whether the game lights this model's quads with smooth lighting.
     *
     * <p>{@code ModelBlockRenderer.tesselateBlock} picks the smooth path or the flat one from this
     * answer for the whole model, and never reads the flag on each quad, so a flat block has to say
     * no here. The wrapper would otherwise pass on the wrapped model's yes, and a block that
     * declared set_flat_lighting would be lit with the smooth lighting's gradient across each face.
     */
    @Override
    public boolean useAmbientOcclusion(BlockState state, @Nullable RenderType renderType) {
        if (state != null
                && state.getBlock() instanceof GrugBlock grugBlock
                && grugBlock.drawsFlatLighting()) {
            return false;
        }
        return super.useAmbientOcclusion(state, renderType);
    }

    @Override
    public List<BakedQuad> getQuads(
            @Nullable BlockState state,
            @Nullable Direction side,
            RandomSource rand,
            ModelData data,
            @Nullable RenderType renderType) {
        BlockPos pos = data.get(POS);
        BlockAndTintGetter level = data.get(LEVEL);
        if (state == null || pos == null || level == null) {
            // No place in the world, so this is not a chunk compile: the breaking overlay, or some
            // other render of the block model. The shape would be a guess, so draw nothing.
            return List.of();
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof GrugBlockEntity grugBlockEntity)) {
            // A block entity is what the geometry belongs to, and this block type always makes one.
            // Without it there is nothing to run, so the ordinary model is left to draw.
            return super.getQuads(state, side, rand, data, renderType);
        }

        // Everything is emitted from the null-direction pass, which the game renders without its
        // own face culling. The direction passes are empty so that culling cannot drop a box whose
        // face is inset, and so the pass runs once per compile instead of once per direction.
        if (side != null) return List.of();

        return customGeometry(state, level, pos, grugBlockEntity, rand, data, renderType);
    }

    /** Runs the block entity's render pass and turns its boxes into quads. */
    private List<BakedQuad> customGeometry(
            BlockState state,
            BlockAndTintGetter level,
            BlockPos pos,
            GrugBlockEntity blockEntity,
            RandomSource rand,
            ModelData data,
            @Nullable RenderType renderType) {
        GrugGeometry geometry;
        // Serialized with the other meshing workers: this is the first thread that can run a block
        // entity's init, and init moves process-global state around the call into the VM
        // (currentlyInitializingBlockEntity, fnEntities) that the state lock does not cover
        // between calls. The pass itself is already serialized by GrugBlockGeometry.draw; this
        // closes the window around it.
        synchronized (RENDER_LOCK) {
            geometry =
                    GrugBlockGeometry.draw(
                            blockEntity.getGrugEntityHandle(),
                            blockEntity.getRenderFnId(),
                            describe(state, pos));
        }

        // A flat block gets no shade per side and no smooth lighting, and ModelBlockRendererMixin
        // lights its quads at the block's own position, so its faces share one brightness instead
        // of each borrowing a different neighbour's.
        boolean flat =
                state.getBlock() instanceof GrugBlock grugBlock && grugBlock.drawsFlatLighting();

        List<BakedQuad> quads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            boolean hidden = Block.shouldRenderFace(state, level, pos, direction, neighbour);
            Face face = face(state, direction, rand, data, renderType);
            for (GrugBox box : geometry.boxes()) {
                // The game would hide the whole face of a full cube here. Hiding the box's face
                // only when the box reaches this boundary is what keeps an inset arm visible
                // against the neighbour, the same as Alpha's shape-per-box tesselation.
                if (hidden && reaches(box, direction)) continue;
                quads.add(quad(box, direction, face, flat));
            }
        }
        for (GrugQuad modelQuad : geometry.quads()) {
            // A model face is hidden only when the model gave it a cullface, as the game's own
            // baked models are.
            if (modelQuad.cullSide() >= 0) {
                Direction cull = DIRECTIONS[modelQuad.cullSide()];
                if (!Block.shouldRenderFace(state, level, pos, cull, pos.relative(cull))) {
                    continue;
                }
            }
            quads.add(modelQuad(modelQuad, flat));
        }
        return quads;
    }

    /** Directions in grug's side order: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east. */
    private static final Direction[] DIRECTIONS = {
        Direction.DOWN,
        Direction.UP,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST
    };

    /**
     * One face draw_model recorded, as a quad from the block atlas.
     *
     * <p>The texture is a sprite of the block atlas, which stitches every texture under a
     * namespace's {@code textures/block}, so the face's texture coordinates, which are fractions of
     * the texture, are mapped into the sprite. The corners are already in the order the game's
     * baker gives them, since core's GrugModels reads the model the way the baker does.
     */
    private static BakedQuad modelQuad(GrugQuad modelQuad, boolean flat) {
        TextureAtlasSprite sprite =
                Minecraft.getInstance()
                        .getModelManager()
                        .getAtlas(InventoryMenu.BLOCK_ATLAS)
                        .getSprite(new ResourceLocation(modelQuad.texture()));
        Direction direction = DIRECTIONS[modelQuad.side()];

        QuadBakingVertexConsumer.Buffered baker = new QuadBakingVertexConsumer.Buffered();
        baker.setDirection(direction);
        baker.setSprite(sprite);
        baker.setTintIndex(-1);
        baker.setShade(!flat);
        baker.setHasAmbientOcclusion(true);
        for (int corner = 0; corner < 4; corner++) {
            baker.vertex(
                            (float) modelQuad.x(corner),
                            (float) modelQuad.y(corner),
                            (float) modelQuad.z(corner))
                    .color(255, 255, 255, 255)
                    .uv(
                            sprite.getU((float) modelQuad.u(corner)),
                            sprite.getV((float) modelQuad.v(corner)))
                    .uv2(0, 0)
                    .normal(direction.getStepX(), direction.getStepY(), direction.getStepZ())
                    .endVertex();
        }
        return baker.getQuad();
    }

    /** The block and where it is, for a report to name. */
    private static String describe(BlockState state, BlockPos pos) {
        GrugBlockData data =
                state.getBlock() instanceof GrugBlock grugBlock
                        ? GrugDeclarations.blockDataByFileId.get(grugBlock.blockFileId)
                        : null;
        String name = data != null ? data.id : "an unknown grug block";
        return name + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /** Whether the box reaches the block boundary on {@code direction}'s side. */
    private static boolean reaches(GrugBox box, Direction direction) {
        return switch (direction) {
            case DOWN -> box.y1() <= 0.0;
            case UP -> box.y2() >= 1.0;
            case NORTH -> box.z1() <= 0.0;
            case SOUTH -> box.z2() >= 1.0;
            case WEST -> box.x1() <= 0.0;
            case EAST -> box.x2() >= 1.0;
        };
    }

    /**
     * One box face, built with the winding and the texture coordinates of the game's own model
     * baker.
     *
     * <p>{@code FaceInfo} is the corner order vanilla puts a quad's vertices in for each direction,
     * and the UVs are the ones a JSON element with no explicit {@code uv} gets: the sprite is
     * clipped to the box's portion of the block's face, not stretched across it. That is the same
     * mapping Alpha's and Beta's shape tesselation uses through the block's min/max.
     */
    private static BakedQuad quad(GrugBox box, Direction direction, Face face, boolean flat) {
        QuadBakingVertexConsumer.Buffered baker = new QuadBakingVertexConsumer.Buffered();
        baker.setDirection(direction);
        baker.setSprite(face.sprite());
        baker.setTintIndex(face.tintIndex());
        baker.setShade(face.shade() && !flat);
        // The same flag a JSON-baked quad carries. Without it the renderer takes the flat-light
        // path for this quad even in its ambient-occlusion pass, which is the difference between
        // smooth lighting and one shade value per face.
        baker.setHasAmbientOcclusion(true);

        float[] uv = uvsByFace(box, direction);
        FaceInfo.VertexInfo[] corners = corners(direction);
        for (int i = 0; i < 4; i++) {
            FaceInfo.VertexInfo corner = corners[i];
            baker.vertex(
                            pick(box.x1(), box.x2(), corner.xFace),
                            pick(box.y1(), box.y2(), corner.yFace),
                            pick(box.z1(), box.z2(), corner.zFace))
                    .color(255, 255, 255, 255)
                    .uv(
                            face.sprite().getU((i < 2 ? uv[0] : uv[2]) / 16.0F),
                            face.sprite().getV((i == 1 || i == 2 ? uv[3] : uv[1]) / 16.0F))
                    .uv2(0, 0)
                    .normal(direction.getStepX(), direction.getStepY(), direction.getStepZ())
                    .endVertex();
        }
        return baker.getQuad();
    }

    private static FaceInfo.VertexInfo[] corners(Direction direction) {
        FaceInfo face = FaceInfo.fromFacing(direction);
        return new FaceInfo.VertexInfo[] {
            face.getVertexInfo(0),
            face.getVertexInfo(1),
            face.getVertexInfo(2),
            face.getVertexInfo(3)
        };
    }

    private static float pick(double min, double max, int cornerFace) {
        return (float)
                (cornerFace == FaceInfo.Constants.MIN_X
                                || cornerFace == FaceInfo.Constants.MIN_Y
                                || cornerFace == FaceInfo.Constants.MIN_Z
                        ? min
                        : max);
    }

    /**
     * The texture coordinates the game gives an element with no explicit {@code uv}, from {@code
     * BlockElement.uvsByFace}, with the box scaled to the element's 0 to 16 units.
     */
    private static float[] uvsByFace(GrugBox box, Direction direction) {
        float x1 = (float) box.x1() * 16.0F;
        float y1 = (float) box.y1() * 16.0F;
        float z1 = (float) box.z1() * 16.0F;
        float x2 = (float) box.x2() * 16.0F;
        float y2 = (float) box.y2() * 16.0F;
        float z2 = (float) box.z2() * 16.0F;
        return switch (direction) {
            case DOWN -> new float[] {x1, 16.0F - z2, x2, 16.0F - z1};
            case UP -> new float[] {x1, z1, x2, z2};
            case NORTH -> new float[] {16.0F - x2, 16.0F - y2, 16.0F - x1, 16.0F - y1};
            case SOUTH -> new float[] {x1, 16.0F - y2, x2, 16.0F - y1};
            case WEST -> new float[] {z1, 16.0F - y2, z2, 16.0F - y1};
            case EAST -> new float[] {16.0F - z2, 16.0F - y2, 16.0F - z1, 16.0F - y1};
        };
    }

    /**
     * The sprite, tint and shade of one of the block's own faces, read from the model this wraps.
     *
     * <p>Per face rather than one sprite for the block, because a mod's model can texture each face
     * differently, and the grug shape has to keep the model's faces rather than invent one.
     */
    private Face face(
            BlockState state,
            Direction direction,
            RandomSource rand,
            ModelData data,
            @Nullable RenderType renderType) {
        List<BakedQuad> reference =
                originalModel.getQuads(state, direction, rand, data, renderType);
        if (reference.isEmpty()) {
            // A model that leaves a face empty still names a texture for the block, and a grug box
            // is not the place to decide it has no texture after all.
            return new Face(originalModel.getParticleIcon(data), -1, true);
        }
        BakedQuad quad = reference.get(0);
        return new Face(quad.getSprite(), quad.getTintIndex(), quad.isShade());
    }

    private record Face(TextureAtlasSprite sprite, int tintIndex, boolean shade) {}
}
