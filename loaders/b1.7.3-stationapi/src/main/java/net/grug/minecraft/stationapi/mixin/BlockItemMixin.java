package net.grug.minecraft.stationapi.mixin;

import net.minecraft.item.BlockItem;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the block a StationAPI block item places.
 *
 * <p>This version gives items their own id space, so an item's id is not the id of the block it
 * places, and {@code BlockItem} keeps that block id in a private field with no getter. The frame
 * renderer needs it to tell a block item, which is drawn as a cube, from a plain item, which is a
 * sprite.
 */
@Mixin(BlockItem.class)
public interface BlockItemMixin {

    @Accessor("blockId")
    int grug$blockId();
}
