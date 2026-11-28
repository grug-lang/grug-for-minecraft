package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * refuses once it has no slot left, rather than answering a number the atlas cannot hold, and a
 * loader counts what a pack needs first so an over-large pack reaches its title screen with a
 * recorded shortage instead of that refusal (#1858).
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
     * The slots the blocks need, keyed the way {@link Allocator#hasRoomForSprites} counts them and
     * valued by every mod that needs each: one entry per distinct face texture, because two blocks
     * that name the same texture share a slot, and one entry per block whose faces do not resolve,
     * because it falls back to a slot of its own.
     *
     * <p>A texture two mods share lists both, so the screen knows it is freed only when both are
     * turned off (#1921).
     *
     * @param modByBlock the mod of each block, by the name registration keys it by
     * @param facesByBlock the six face texture references of each block, or null for one whose
     *     model did not resolve
     */
    public static Map<String, List<String>> blockSpriteNeeds(
            Map<String, String> modByBlock, Map<String, String[]> facesByBlock) {
        Map<String, List<String>> needs = new HashMap<>();
        for (Map.Entry<String, String> block : modByBlock.entrySet()) {
            String[] faces = facesByBlock.get(block.getKey());
            if (faces == null) {
                // A texture reference always carries a namespace, so it never meets this key.
                addMod(needs, "block " + block.getKey(), block.getValue());
                continue;
            }
            for (String face : faces) {
                addMod(needs, face, block.getValue());
            }
        }
        return needs;
    }

    /** Notes that the mod needs the slot for {@code key}, once however many faces name it. */
    private static void addMod(Map<String, List<String>> needs, String key, String mod) {
        List<String> mods = needs.computeIfAbsent(key, unused -> new ArrayList<>());
        if (!mods.contains(mod)) mods.add(mod);
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

        /** How many usable slots the allocator has not handed out yet. */
        public int remaining() {
            int remaining = 0;
            for (int slot = cursor; slot <= LAST_SLOT; slot++) {
                if (!skipsAnimated || !isAnimated(slot)) remaining++;
            }
            return remaining;
        }

        /**
         * Whether the mods' sprites fit in the slots left. When they do not, the shortage is
         * recorded for the mod manager's screen (see {@link GrugIdShortage}) and the loader writes
         * no sprite of this kind.
         *
         * <p>Run before the first slot is taken, so the screen shows what all the mods need rather
         * than where the atlas happened to run out, and so no block is registered with no sprite to
         * draw. {@link #next()} keeps refusing a caller that ignores this and allocates anyway. A
         * key two mods share is one slot but is recorded for both, so the screen does not free it
         * while either mod still needs it (#1921).
         *
         * @param needed how many slots the mods need together
         * @param kind what the slots hold, such as "block sprite", which the screen shows
         * @param modsByKey the mods of each thing that takes a slot, keyed by what the slot is for:
         *     two blocks that name the same texture take one slot, so it is counted once
         */
        public boolean hasRoomForSprites(
                int needed, String kind, Map<String, ? extends Collection<String>> modsByKey) {
            if (needed <= remaining()) return true;

            GrugIdShortage.recordSlots(kind, needed, remaining(), modsByKey);
            return false;
        }

        /**
         * Whether each thing that takes a slot, one mod to a name, fits in the slots left. See
         * {@link #hasRoomForSprites}, which a texture two mods share needs.
         *
         * @param needed how many slots the mods need together
         * @param kind what the slots hold, such as "item sprite", which the screen shows
         * @param modByKey the mod of each thing that takes a slot, keyed by what the slot is for
         */
        public boolean hasRoomFor(int needed, String kind, Map<String, String> modByKey) {
            if (needed <= remaining()) return true;

            Map<String, List<String>> modsByKey = new HashMap<>();
            for (Map.Entry<String, String> key : modByKey.entrySet()) {
                modsByKey.put(key.getKey(), Collections.singletonList(key.getValue()));
            }
            return hasRoomForSprites(needed, kind, modsByKey);
        }
    }
}
