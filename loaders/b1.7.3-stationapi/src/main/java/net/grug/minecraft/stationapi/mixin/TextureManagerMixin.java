package net.grug.minecraft.stationapi.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.grug.minecraft.grug.GrugReference;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.client.texture.TextureManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
 *
 * <p>A sprite is identified by its binary class name, not by its simple name, which two classes in
 * different packages can share and which a lambda's synthetic hidden class makes unstable. An
 * installed game names the class in its intermediary namespace, so the name is unmapped to the
 * named one before it is compared.
 */
@Mixin(TextureManager.class)
public class TextureManagerMixin {
    /**
     * The named binary names of the six sprites Beta animates, with the clock and compass absent.
     */
    private static final Set<String> ANIMATED =
            new HashSet<>(
                    Arrays.asList(
                            "net.minecraft.client.render.texture.WaterSprite",
                            "net.minecraft.client.render.texture.WaterSideSprite",
                            "net.minecraft.client.render.texture.LavaSprite",
                            "net.minecraft.client.render.texture.LavaSideSprite",
                            "net.minecraft.client.render.texture.FireSprite",
                            "net.minecraft.client.render.texture.NetherPortalSprite"));

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

    /** Whether {@code texture} is one of the game's own animated sprites. */
    static boolean isGameAnimation(DynamicTexture texture) {
        MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
        return isGameAnimationName(
                texture.getClass().getName(), name -> resolver.unmapClassName("named", name));
    }

    /**
     * Whether the class with this binary name is one of the sprites, once {@code toNamed} has
     * unmapped a name the running game's namespace to the named one. Split out so a unit test can
     * pin the identity without a game or a mapping resolver.
     */
    static boolean isGameAnimationName(String binaryName, Function<String, String> toNamed) {
        return ANIMATED.contains(toNamed.apply(binaryName));
    }
}
