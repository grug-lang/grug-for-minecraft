package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.client.render.texture.DynamicTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

public class GrugStaticTexture extends DynamicTexture {
    public GrugStaticTexture(int sprite, int atlas, InputStream is) {
        super(sprite);
        this.atlas = atlas; // 0 for terrain.png, 1 for gui/items.png
        this.pixels = new byte[1024];

        try {
            BufferedImage image = ImageIO.read(is);
            if (image != null) {
                int[] rgb = new int[256];

                // Read a 16x16 chunk from the top-left of the image
                image.getRGB(0, 0, Math.min(16, image.getWidth()), Math.min(16, image.getHeight()), rgb, 0, 16);

                for (int i = 0; i < 256; i++) {
                    int argb = rgb[i];
                    this.pixels[i * 4 + 0] = (byte) ((argb >> 16) & 0xFF); // R
                    this.pixels[i * 4 + 1] = (byte) ((argb >> 8) & 0xFF); // G
                    this.pixels[i * 4 + 2] = (byte) (argb & 0xFF); // B
                    this.pixels[i * 4 + 3] = (byte) ((argb >> 24) & 0xFF); // A
                }
            }
        } catch (Exception e) {
            GrugModLoader.LOGGER.error("Failed to load static texture into atlas", e);
        }
    }

    @Override
    public void tick() {
        // We override tick() to do nothing because our pixels are static
        // and do not need to be animated every frame!
    }
}
