package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells a grug block who placed it (#1558). Alpha has no placer callback on the block, so the hook
 * is the block item's own placement, right after the block has set its metadata for the face that
 * was clicked, which is where Beta calls the block's placer callback. The coordinates are the ones
 * the item moved to the placed block's own position, which the injector reads from the method's own
 * slots.
 */
@GrugGenerated(
        "a mixin into the game's block item code: the game runs it, and the placed test fails"
                + " without it")
@Mixin(BlockItem.class)
public abstract class BlockItemPlacedMixin {
    @Inject(
            method = "useOn",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/block/Block;updateMetadataOnPlaced"
                                            + "(Lnet/minecraft/world/World;IIII)V",
                            shift = At.Shift.AFTER))
    private void grug$placed(
            ItemStack stack,
            PlayerEntity player,
            World world,
            int x,
            int y,
            int z,
            int face,
            CallbackInfoReturnable<Boolean> cir) {
        Block block = Block.BY_ID[world.getBlock(x, y, z)];
        if (block instanceof GrugBlock) {
            ((GrugBlock) block).grugPlaced(world, x, y, z, player);
        }
    }
}
