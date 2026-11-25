package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.stationapi.client.GrugFrozenAnimations;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.client.texture.TextureManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Drops the game's own animated sprites from the texture manager's list while a capture run owns
 * the client, so their atlas slot keeps the tile the texture pack drew.
 *
 * <p>{@code TextureManager.tick} advances each entry and StationAPI's renderer binds every new
 * frame into its own stitched atlas, so cancelling {@code SpriteAtlasTexture.tick} does not stop
 * the animation. Removing the entries rather than skipping their tick matters, because the upload
 * would then write their still-empty pixels over the pack's tile and the tank's column would come
 * out transparent. Which entries are the game's own is {@link GrugFrozenAnimations}' to answer; see
 * there and {@link GrugReference#isAnimationFrozen()} for why. #1771.
 */
@Mixin(TextureManager.class)
public class TextureManagerMixin {
    @Shadow private List<DynamicTexture> dynamicTextures;

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$dropGameAnimations(CallbackInfo ci) {
        if (!GrugReference.isAnimationFrozen()) {
            return;
        }
        for (int i = this.dynamicTextures.size() - 1; i >= 0; i--) {
            if (GrugFrozenAnimations.isGameAnimation(this.dynamicTextures.get(i))) {
                this.dynamicTextures.remove(i);
            }
        }
    }
}
