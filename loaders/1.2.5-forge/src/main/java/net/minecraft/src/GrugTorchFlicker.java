package net.minecraft.src;

/**
 * Pins the lightmap's torch-flicker value, which 1.2.5 randomizes every game tick and multiplies
 * into the block-light color.
 *
 * <p>The random walk makes each launch light the same scene a step or two brighter or darker than
 * another launch lights it, which no reference can absorb: the world screenshot is compared pixel
 * for pixel, and the whole scene moves with the walk. The fields are package-private, which is why
 * this class lives in the game's own package rather than in the loader's, the same way the Ornithe
 * loaders keep their recipe helper in a game package. See #253.
 */
public final class GrugTorchFlicker {
    private GrugTorchFlicker() {}

    /**
     * Resets the flicker to its neutral value, so the next lightmap build uses a fixed block-light
     * color. Call once per frame, after the game tick has run its own update and before the frame
     * that builds the lightmap is drawn.
     */
    public static void pin(EntityRenderer renderer) {
        renderer.torchFlickerX = 0.0F;
        renderer.torchFlickerY = 0.0F;
    }
}
