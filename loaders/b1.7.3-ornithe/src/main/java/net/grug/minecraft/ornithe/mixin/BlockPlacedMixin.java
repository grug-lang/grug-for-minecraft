package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a grug block who placed it (#1558). The block item calls the block's placer callback once
 * the block, its metadata and its block entity are in the world, and a grug block does not override
 * it itself, because the shared source builds against Alpha too, which has no such method. A placer
 * that is not a player is ignored.
 */
@GrugGenerated(
        "a mixin into the game's block code: the game runs it, and the placed test fails without"
            + " it")
@Mixin(Block.class)
public abstract class BlockPlacedMixin {
    @Inject(method = "onPlaced", at = @At("HEAD"))
    private void grug$placed(World world, int x, int y, int z, MobEntity placer, CallbackInfo ci) {
        Object self = this;
        if (self instanceof GrugBlock && placer instanceof PlayerEntity) {
            ((GrugBlock) self).grugPlaced(world, x, y, z, (PlayerEntity) placer);
        }
    }
}
