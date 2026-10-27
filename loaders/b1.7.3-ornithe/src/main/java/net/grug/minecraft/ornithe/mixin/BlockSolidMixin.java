package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.minecraft.block.Block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a block that draws its own geometry stop hiding its neighbours' faces.
 *
 * <p>This version culls a face by asking the world whether the block next to it is solid, and the
 * world asks the block's own {@code isSolidRender}, which answers true unless a subclass says
 * otherwise. The {@code IS_SOLID_RENDER} table the loader fills in is a cache the game never
 * consults for that question, so clearing it changed nothing: the pipe's neighbours lost the face
 * that touched it, and the block behind a pipe showed the sky through its missing faces (#577).
 *
 * <p>The base class cannot be overridden from {@code GrugBlock}, because the shared source builds
 * against more than one version and the method is named differently in each, so the answer is given
 * from here.
 */
@GrugGenerated(
        "a mixin into the game's block code: the game runs it, and the wooden_pipe_shapes"
                + " captures fail without it")
@Mixin(Block.class)
public class BlockSolidMixin {

    @Inject(method = "isSolidRender", at = @At("HEAD"), cancellable = true)
    private void grug$customGeometryIsNotSolid(CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (self instanceof GrugBlock && ((GrugBlock) self).drawsCustomGeometryOnceDeclared()) {
            cir.setReturnValue(false);
        }
    }
}
