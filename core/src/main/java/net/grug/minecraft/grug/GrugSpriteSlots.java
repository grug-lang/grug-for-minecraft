package net.grug.minecraft.grug;

/**
 * Which terrain.png slots a loader may hand to a grug block on Alpha and Beta.
 *
 * <p>The game repaints a few slots of terrain.png every frame with an animated texture, so a grug
 * sprite uploaded into one is overwritten at once: the block then renders water or lava where its
 * own texture should be. Handing slots out one after another from 160 reached those slots as soon
 * as enough blocks sorted before a block, so adding any block moved a later block's texture onto
 * the water's. The slots are the animated textures' own: water is tile 205 (row 12, column 13) and
 * its flowing frame 206, and lava is tile 237 (row 14, column 13) with its flowing frame 238. A
 * flowing frame is replicated over a 2x2 block of tiles (the game's {@code replicate} is 2), so it
 * also owns 207, 222 and 223, and the lava's owns 239, 254 and 255: a block's texture on 207 was
 * still painted over by the flowing water.
 *
 * <p>Both atlases a loader writes are 16 by 16 tiles, so a slot past {@link #LAST_SLOT} is outside
 * the image the game uploads, and handing one out writes a sprite that is never drawn. An allocator
 * refuses once it has no slot left, rather than answering a number the atlas cannot hold.
 */
public final class GrugSpriteSlots {

    /** The last slot of the 16 by 16 atlas that terrain.png and gui/items.png both are. */
    public static final int LAST_SLOT = 255;

    @GrugGenerated("utility class: never instantiated")
    private GrugSpriteSlots() {}

    /** Whether the game animates this terrain.png slot, which a grug sprite must not use. */
    public static boolean isAnimated(int slot) {
        // The flowing frames are replicated over a 2x2 block of tiles: 206 covers 206, 207, 222
        // and 223, and 238 covers 238, 239, 254 and 255.
        switch (slot) {
            case 205:
            case 206:
            case 207:
            case 222:
            case 223:
            case 237:
            case 238:
            case 239:
            case 254:
            case 255:
                return true;
            default:
                return false;
        }
    }

    /** The first slot at or after {@code slot} that the game does not animate. */
    public static int firstUsable(int slot) {
        int result = slot;
        while (isAnimated(result)) {
            result++;
        }
        return result;
    }

    /**
     * Hands out atlas slots one after another from a first slot, skipping the slots the game
     * repaints in place, so a loader keeps no counter of its own to get wrong.
     */
    public static final class Allocator {
        private final String atlas;
        private final int first;
        private final boolean skipsAnimated;
        private int cursor;

        /** An allocator for terrain.png, which skips the slots the game animates. */
        public Allocator(int first) {
            this("terrain.png", first, true);
        }

        private Allocator(String atlas, int first, boolean skipsAnimated) {
            this.atlas = atlas;
            this.first = first;
            this.skipsAnimated = skipsAnimated;
            this.cursor = first;
        }

        /**
         * An allocator for gui/items.png, whose slots in the range grug hands out the game never
         * repaints, so every one of them is usable.
         */
        public static Allocator items(int first) {
            return new Allocator("gui/items.png", first, false);
        }

        /** The next usable slot, or a refusal once the atlas has none left. */
        public int next() {
            while (cursor <= LAST_SLOT) {
                int slot = cursor;
                cursor++;
                if (!skipsAnimated || !isAnimated(slot)) {
                    return slot;
                }
            }
            throw Grug.fatal(
                    "No slot is left in "
                            + atlas
                            + " for a grug sprite: the atlas is 16 by 16, and every slot from "
                            + first
                            + " on is taken"
                            + (skipsAnimated ? " or animated by the game" : "")
                            + ".");
        }
    }
}
