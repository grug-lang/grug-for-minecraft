package net.grug.minecraft.stationapi.client;

import net.grug.minecraft.grug.GrugSpritePadding;
import net.modificationstation.stationapi.api.client.texture.NativeImage;

/**
 * Turns one frame of a sprite sheet into the image the atlas stores for it, with the one-texel
 * border of the sprite's own edge that {@link GrugSpritePadding} decides.
 *
 * <p>This class is the plumbing between that decision and StationAPI's images: it reads the frame
 * out of the sprite sheet, asks for the padded pixels, and writes them into a fresh image, which is
 * what the caller uploads. It holds no rule of its own, so the rule stays unit-tested where it is.
 */
public final class GrugAtlasPadding {

    private GrugAtlasPadding() {}

    /**
     * The frame starting at {@code skipX}, {@code skipY} in {@code frame}, {@code width} by {@code
     * height} pixels, with a border of its own edge added on every side.
     *
     * <p>The caller owns the returned image and has to close it. The frame may be a rectangle of a
     * larger sheet, which is how an animated sprite is stored.
     */
    public static NativeImage padded(
            NativeImage frame, int skipX, int skipY, int width, int height) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = frame.getColor(skipX + x, skipY + y);
            }
        }
        int[] padded = GrugSpritePadding.padded(width, height, pixels);
        int paddedWidth = GrugSpritePadding.paddedWidth(width);
        int paddedHeight = GrugSpritePadding.paddedHeight(height);
        NativeImage image = new NativeImage(paddedWidth, paddedHeight, false);
        for (int y = 0; y < paddedHeight; y++) {
            for (int x = 0; x < paddedWidth; x++) {
                image.setColor(x, y, padded[y * paddedWidth + x]);
            }
        }
        return image;
    }
}
