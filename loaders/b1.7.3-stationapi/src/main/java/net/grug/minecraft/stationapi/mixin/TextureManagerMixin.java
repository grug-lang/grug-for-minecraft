package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugReference;
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
 * <p>Beta animates water, lava, fire and the nether portal through {@link DynamicTexture#tick()},
 * which {@code TextureManager.tick} calls before it uploads each entry's pixels into the atlas.
 * Cancelling StationAPI's {@code SpriteAtlasTexture.tick} does not stop that path, so a capture of
 * the tank's column held in water was a different animation frame in every run, and the golden one
 * run wrote never matched the next run or the dedicated leg. Removing the entries rather than
 * skipping their tick matters, because the upload would then write their still-empty pixels over
 * the pack's tile and the column would come out transparent. See {@link
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
            if (isGameAnimation(this.dynamicTextures.get(i))) {
                this.dynamicTextures.remove(i);
            }
        }
    }

    /**
     * Whether {@code texture} is one of the game's own animated sprites, matched by simple name as
     * the ornithe loader matches them, so one list can serve a version that ships fewer sprites.
     */
    private static boolean isGameAnimation(DynamicTexture texture) {
        switch (texture.getClass().getSimpleName()) {
            case "WaterSprite":
            case "WaterSideSprite":
            case "LavaSprite":
            case "LavaSideSprite":
            case "FireSprite":
            case "NetherPortalSprite":
                return true;
            default:
                return false;
        }
    }
}
