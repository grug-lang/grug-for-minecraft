package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugSpritePadding;
import net.grug.minecraft.stationapi.client.GrugAtlasPadding;
import net.modificationstation.stationapi.api.client.texture.NativeImage;
import net.modificationstation.stationapi.api.client.texture.SpriteContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Uploads a frame with its one-texel border, so the atlas holds the sprite's own edge in the texels
 * a face's boundary coordinate reaches (#919).
 *
 * <p>Every pixel the stitched atlas receives for a sprite goes through {@code SpriteContents}'s
 * frame upload: the atlas's first fill calls {@code Sprite.upload}, which lands here, and so does
 * every later frame of an animated sprite, whether it comes from the sprite's own animation or from
 * an interpolated frame. Redirecting the image upload inside it is therefore the one place that
 * covers both, and it is also the only one that knows which frame rectangle of a sprite sheet is
 * being drawn, which is what the border has to be copied from.
 *
 * <p>The upload moves up and left by the padding, which writes the whole padded image over the
 * region the packer reserved, and the frame inside it lands exactly where the sprite's origin says
 * the content is.
 */
@GrugGenerated(
        "a mixin into StationAPI's sprite contents: Mixin copies the redirect into SpriteContents"
            + " without defining this class, so JaCoCo never sees it run, and the atlas holds no"
            + " border for a face's boundary coordinate to sample without it")
@Mixin(SpriteContents.class)
public class SpriteContentsMixin {

    /** Uploads {@code frame}'s rectangle padded, over the sprite's whole reserved region. */
    @Redirect(
            method =
                    "upload(IIIILnet/modificationstation/stationapi/api/client/texture/NativeImage;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/modificationstation/stationapi/api/client/texture/NativeImage;upload(IIIIIIIZZ)V"))
    private void grug$uploadPadded(
            NativeImage frame,
            int level,
            int offsetX,
            int offsetY,
            int skipPixels,
            int skipRows,
            int width,
            int height,
            boolean mipmap,
            boolean close) {
        NativeImage padded = GrugAtlasPadding.padded(frame, skipPixels, skipRows, width, height);
        try {
            padded.upload(
                    level, offsetX - GrugSpritePadding.PAD, offsetY - GrugSpritePadding.PAD, false);
        } finally {
            padded.close();
        }
    }
}
