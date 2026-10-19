package net.grug.minecraft.stationapi.mixin;

import net.minecraft.item.BlockItem;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the block id a block item was made for, which this version keeps private.
 *
 * <p>The block item renderer needs it to find the block an engine item belongs to, so it can tell
 * whether the block declared set_flat_lighting. See {@link BakedModelRendererImplMixin}.
 */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {

    @Accessor("blockId")
    int grug$getBlockId();
}
