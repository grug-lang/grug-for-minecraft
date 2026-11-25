package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.ornithe.client.GrugFrozenAnimations;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.client.render.texture.TextureManager;

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
 * <p>Done at the head of the first {@code tick}, which uploads every list entry's pixels: removing
 * an entry before that upload ever happens means its slot is never overwritten. Removing it rather
 * than skipping its {@code tick} matters, because the upload would then write its still-empty
 * pixels over the pack's tile and a capture of water would come out transparent. {@link
 * GrugFrozenAnimations} names which entries are the game's; grug's own static textures and the skin
 * textures the game downloads stay in the list and keep uploading. See {@link
 * GrugReference#isAnimationFrozen()} and #1771.
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
