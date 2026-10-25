package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.client.GrugModelRenderer;
import net.minecraft.client.render.vertex.Tesselator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the corners the block renderer draws to GrugModelRenderer while it records, instead of
 * drawing them, so a model face can be lit by the game's own block lighting.
 *
 * <p>This version's Tesselator is a final singleton, so it cannot be swapped for a recorder the way
 * 1.2.5's is. These are the only two calls the block renderer's face methods make to it for a
 * corner: the colour, which holds the corner's light, and the corner itself.
 */
@Mixin(Tesselator.class)
public class TesselatorMixin {

    @Inject(method = "color(FFF)V", at = @At("HEAD"), cancellable = true)
    private void grug$recordColor(float red, float green, float blue, CallbackInfo ci) {
        if (GrugModelRenderer.recordingFaces) {
            GrugModelRenderer.recordColor(red, green, blue);
            ci.cancel();
        }
    }

    @Inject(method = "vertex(DDDDD)V", at = @At("HEAD"), cancellable = true)
    private void grug$recordVertex(
            double x, double y, double z, double u, double v, CallbackInfo ci) {
        if (GrugModelRenderer.recordingFaces) {
            GrugModelRenderer.recordCorner(x, y, z);
            ci.cancel();
        }
    }
}
