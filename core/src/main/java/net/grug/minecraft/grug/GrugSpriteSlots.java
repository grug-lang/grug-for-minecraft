package net.grug.minecraft.grug;

/**
 * Which terrain.png slots a loader may hand to a grug block on Alpha and Beta.
 *
 * <p>The game repaints a few slots of terrain.png every frame with an animated texture, so a grug
 * sprite uploaded into one is overwritten at once: the block then renders water or lava where its
 * own texture should be. Handing slots out one after another from 160 reached those slots as soon
 * as enough blocks sorted before a block, so adding any block moved a later block's texture onto
 * the water's. The slots are the animated textures' own: water is tile 205 (row 12, column 13) and
 * its flowing frame 206, and lava is tile 237 (row 14, column 13) with its flowing frame 238.
 */
public final class GrugSpriteSlots {

    @GrugGenerated("utility class: never instantiated")
    private GrugSpriteSlots() {}

    /** Whether the game animates this terrain.png slot, which a grug sprite must not use. */
    public static boolean isAnimated(int slot) {
        return slot == 205 || slot == 206 || slot == 237 || slot == 238;
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
