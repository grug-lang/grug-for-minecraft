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
 */
public final class GrugSpriteSlots {

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
     * Hands out terrain.png slots one after another from a first slot, skipping the animated ones,
     * so a loader keeps no counter of its own to get wrong.
     */
    public static final class Allocator {
        private int cursor;

        public Allocator(int first) {
            cursor = first;
        }

        /** The next slot the game does not animate. */
        public int next() {
            int slot = firstUsable(cursor);
            cursor = slot + 1;
            return slot;
        }
    }
}
