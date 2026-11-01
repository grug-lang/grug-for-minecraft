package net.grug.minecraft.grug;

public class GrugBlockData {
    public final String id;
    public String blockEntityString;

    public float hardness = 0.0f;
    public String material = "stone";
    public int inventorySize = 0;

    /**
     * Whether the block draws its own geometry from its block entity's render function, declared by
     * set_custom_render or set_frame_render. When it does, the loader skips the model its
     * blockstate names.
     */
    public boolean customRender = false;

    /**
     * Whether all of that geometry is drawn per frame, declared by set_frame_render: the chunk mesh
     * holds nothing for this block and render() is not asked for.
     */
    public boolean frameRender = false;

    /**
     * Whether the chunk's geometry holds this block's shape, which a frame-only block has none of.
     */
    public boolean drawsChunkGeometry() {
        return customRender && !frameRender;
    }

    /**
     * Whether the custom geometry is drawn flat, declared by set_flat_lighting: no shade per side,
     * and the light at the block's own position on every face.
     */
    public boolean flatLighting = false;

    /**
     * Whether the block's collision and selection boxes come from its shape export, declared by
     * set_shape. When it does, the loader asks the script for the boxes at a position rather than
     * using the full cube, which is what lets a shape follow its neighbours.
     */
    public boolean hasShape = false;

    public GrugBlockData(String id) {
        this.id = id;
    }
}
