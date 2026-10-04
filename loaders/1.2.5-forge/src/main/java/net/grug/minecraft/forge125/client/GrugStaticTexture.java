package net.grug.minecraft.forge125.client;

import cpw.mods.fml.client.FMLTextureFX;

import net.grug.minecraft.grug.Grug;
import net.minecraft.src.RenderEngine;

import org.lwjgl.opengl.GL11;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

/**
 * A grug texture living in one tile of one of the game's own atlases.
 *
 * <p>1.2.5 stitches {@code /terrain.png} and {@code /gui/items.png} once and has no way to add a
 * sprite to either of them afterwards, so this class writes into a tile the game already stitched.
 * It is FML's shape of that, a TextureFX, because FML is what owns the sprite bookkeeping: {@code
 * RenderEngine.registerTextureFX} adds it to {@code textureList}, FML hands out the slot, and FML
 * re-runs {@code setup()} on every effect when a texture pack is re-stitched.
 *
 * <p>It is not {@code net.minecraft.src.ModTextureStatic}, which is vanilla's shape of the same
 * idea and the obvious thing to subclass, because that one keeps its image in a private field
 * nothing can replace: a changed texture could not be pushed into an already registered effect.
 * This one keeps the file it reads and re-reads it on demand, so a texture edited on disk reaches
 * the atlas without a restart.
 *
 * <p>The {@code glTexSubImage2D} is issued here rather than left to {@code
 * RenderEngine.updateDynamicTextures}, which would be the one line less. Vanilla only calls that
 * from {@code Minecraft.runTick} and only {@code if (!this.isGamePaused)}, so a paused game never
 * uploads: and in a headless run the game is always paused, because {@code EntityRenderer} opens
 * the in-game menu whenever {@code !Display.isActive()} for half a second, which under a virtual
 * framebuffer with no window manager it always is. Uploading at the moment the pixels change makes
 * a reload independent of whether the game happens to be paused, which is the whole point of a hot
 * reload.
 */
public class GrugStaticTexture extends FMLTextureFX {

    /** The file this sprite's pixels come from, read again by {@link #read()}. */
    private final File file;

    /** The render engine whose atlas this sprite is written into, for the upload. */
    private final RenderEngine engine;

    /**
     * The image as it was last read, one ARGB int per pixel, kept so the anaglyph form can be
     * re-derived when the player turns that option on without the file changing.
     */
    private int[] pixels;

    /** Scratch for the upload, reallocated only if the tile ever changes size. */
    private ByteBuffer tileBuffer;

    /**
     * @param sprite the tile index inside the atlas
     * @param atlas {@code 0} for {@code /terrain.png} and {@code 1} for {@code /gui/items.png},
     *     which is what the inherited {@code bindImage} switches on
     */
    public GrugStaticTexture(int sprite, int atlas, File file, RenderEngine engine) {
        super(sprite);
        this.file = file;
        this.engine = engine;
        this.tileImage = atlas;
        // One tile, which is what a grug texture is: the sprite has no size of its own, and
        // updateDynamicTextures walks this many tiles of the atlas per effect.
        this.tileSize = 1;
        // Read here rather than waiting for FML to call setup(), so the object is never in the
        // textureList with pixels that belong to no file.
        read();
    }

    /**
     * Reads the file again and writes it into this sprite's tile of the atlas.
     *
     * <p>Callable more than once on purpose: that is what a hot reload is. The effect stays
     * registered for the rest of the session and its pixels change, rather than a second effect
     * being added for the same slot, which would leave the stale one in {@code textureList} still
     * pushing its old pixels into that slot once per tick, and once more for every reload before
     * it.
     */
    public void read() {
        BufferedImage image = readImage();
        int side = this.tileSizeBase;
        if (image.getWidth() != side || image.getHeight() != side) {
            // A grug texture is one tile of the atlas, so an image of any other size is scaled into
            // it. ModTextureStatic does the same for an image that is not a whole number of tiles.
            BufferedImage scaled = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.drawImage(
                    image,
                    0,
                    0,
                    side,
                    side,
                    0,
                    0,
                    image.getWidth(),
                    image.getHeight(),
                    (ImageObserver) null);
            graphics.dispose();
            image = scaled;
        }
        this.pixels = new int[side * side];
        image.getRGB(0, 0, side, side, this.pixels, 0, side);
        writeImageData();
        pushToTile();
    }

    /**
     * Writes {@link #pixels} into {@code imageData} as RGBA, greyed out in anaglyph mode.
     *
     * <p>Kept apart from {@link #pushToTile} because the two are needed at different times: FML
     * uploads {@code imageData} itself from {@code RenderEngine.updateDynamicTextures}, which does
     * nothing while the game is paused, so a reload cannot rely on it.
     */
    private void writeImageData() {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int red = pixel >> 16 & 0xFF;
            int green = pixel >> 8 & 0xFF;
            int blue = pixel & 0xFF;
            if (this.anaglyphEnabled) {
                int grey = (red + green + blue) / 3;
                red = grey;
                green = grey;
                blue = grey;
            }
            this.imageData[i * 4 + 0] = (byte) red;
            this.imageData[i * 4 + 1] = (byte) green;
            this.imageData[i * 4 + 2] = (byte) blue;
            this.imageData[i * 4 + 3] = (byte) (pixel >>> 24);
        }
    }

    /**
     * The {@code glTexSubImage2D} that puts {@link #imageData} into this sprite's tile.
     *
     * <p>Same arithmetic as {@code RenderEngine.updateDynamicTextures}, which is the vanilla code
     * this mirrors: a tile index counts across a row of sixteen, and the atlas is addressed in
     * sixteen-tile rows. The tile the game handed out is the only thing written, so no other
     * block's or item's sprite is disturbed.
     */
    private void pushToTile() {
        int side = this.tileSizeBase;
        // LWJGL 2 takes a direct buffer for pixel data, and one per sprite is kept rather than
        // allocated per upload because this runs on the render thread inside a tick.
        if (this.tileBuffer == null || this.tileBuffer.capacity() != this.imageData.length) {
            this.tileBuffer = ByteBuffer.allocateDirect(this.imageData.length);
        }
        this.tileBuffer.clear();
        this.tileBuffer.put(this.imageData);
        this.tileBuffer.flip();

        bindImage(this.engine);
        GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                this.iconIndex % 16 * side,
                this.iconIndex / 16 * side,
                side,
                side,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                this.tileBuffer);
    }

    /**
     * FML runs this whenever an atlas is re-stitched, which allocates {@code imageData} again and
     * so empties it, and the re-stitch itself overwrites every tile including this one. Re-deriving
     * from the pixels already read is what keeps the sprite in the atlas across a texture pack
     * change.
     */
    @Override
    protected void setup() {
        super.setup();
        writeImageData();
        pushToTile();
    }

    @Override
    public void onTick() {
        // Reached from RenderEngine.updateDynamicTextures, which uploads imageData itself straight
        // afterwards, so the tile is not pushed here. Only the pixels are re-derived, because
        // anaglyph is a display option the player can change while the game runs and every other
        // sprite in the atlas desaturates the moment they turn it on.
        writeImageData();
    }

    private BufferedImage readImage() {
        try {
            BufferedImage image = ImageIO.read(this.file);
            if (image == null) {
                throw Grug.fatal("The grug texture " + this.file + " is not a readable image.");
            }
            return image;
        } catch (IOException e) {
            // Half a texture would leave the tile as whatever the atlas held before it, which is a
            // rendering no mod asked for.
            throw Grug.fatal("Failed to read the grug texture " + this.file, e);
        }
    }

    @Override
    public String toString() {
        return "GrugStaticTexture " + this.file + " @ " + this.iconIndex;
    }
}
