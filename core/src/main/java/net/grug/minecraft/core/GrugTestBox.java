package net.grug.minecraft.core;

/**
 * The sealed room the runner builds around the player before every test.
 *
 * <p>Why a room at all: the sky is not a reproducible light source. Its brightness moves with the
 * time of day, which a run reaches at a different tick every time, and the terrain and canopy above
 * a fixture shade it unevenly, so a world screenshot taken under it lands on different lighting
 * bands from run to run. Inside the room there is no sky light at all: the torches are the only
 * light, and block light does not change with the world clock. See #253.
 *
 * <p>The room is a hollow box of stone with a torch on every floor tile but the player's column, so
 * the light level is the same across the whole floor. The floor itself sits three blocks below the
 * player's feet and the player stands on a single block at their own level: a torch's smoke rises a
 * couple of blocks, and from a floor at the player's feet it would drift into a capture, while from
 * three blocks down it stays out of frame and its light still reaches the fixture around the block
 * the player stands on.
 *
 * <p>The whole box the room occupies is cleared to air first, so whatever terrain, leaves or
 * earlier fixtures were there do not block the torch light or show in a capture. That is the inside
 * plus the one-block ring the shell is built in: the shell replaces a ring column from the bottom
 * up, so a plant standing on one would otherwise see the block under it replaced first and drop as
 * an item the player can pick up. It is all cleared top down, so a plant goes before the block it
 * stands on, and so does one on the ceiling layer. The walls reach below the floor, so no sky light
 * leaks in under them.
 *
 * <p>It is built before every test with {@link #DEFAULT_RADIUS}, and again whenever a test asks for
 * a different one through {@code Test.set_box_radius}, because a fixture that reaches further than
 * the default room needs a bigger one around it.
 */
public final class GrugTestBox {
    /**
     * The room's half-size in blocks. The default is a 7x7x7 room, small enough that rebuilding it
     * before every test is cheap and that a test's fixture at one of the usual offsets lands
     * outside it.
     */
    public static final int DEFAULT_RADIUS = 3;

    /** How far below the player's feet the torch floor sits, in blocks. */
    private static final int TORCH_FLOOR_DEPTH = 4;

    private GrugTestBox() {}

    /**
     * Builds the room, centred on the player's feet.
     *
     * <p>The feet, rather than the eye, are the anchor: the block the player stands on goes in the
     * block under them, so the player does not move when it replaces the ground they were standing
     * on. A caller that knows only the eye height has to subtract it first, which is what each
     * adapter's version of {@link ModLoaderAdapter#buildTestBox(int)} does.
     */
    public static void build(
            ModLoaderAdapter adapter,
            Object level,
            double feetX,
            double feetY,
            double feetZ,
            int radius) {
        int px = (int) Math.floor(feetX);
        int py = (int) Math.floor(feetY);
        int pz = (int) Math.floor(feetZ);

        int minX = px - radius;
        int maxX = px + radius;
        int minZ = pz - radius;
        int maxZ = pz + radius;
        int ceilingY = py + 2 * radius + 1;
        int torchFloorY = py - TORCH_FLOOR_DEPTH;
        int torchY = torchFloorY + 1;
        // Below the floor by more than the room is wide, so a slope or a dip just outside the wall
        // cannot leave a gap under it for the sky light to pour through.
        int wallBottomY = Math.min(py - radius - 1, torchFloorY);

        // The whole box the room will occupy, cleared first and top down, so a plant is cleared
        // before the block it stands on: the other order pops it off as an item, which the player
        // then picks up and carries into later captures. Only blocks that are not already air are
        // replaced.
        for (int y = ceilingY; y >= wallBottomY; y--) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                for (int x = minX - 1; x <= maxX + 1; x++) {
                    clearBlock(adapter, level, x, y, z);
                }
            }
        }

        // The torch floor, and the single block the player stands on. The floor goes in below the
        // player's feet, so the light comes up around the block they stand on and their feet stay
        // where they are.
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                adapter.placeBlock(level, x, torchFloorY, z, "minecraft:stone");
            }
        }
        adapter.placeBlock(level, px, py - 1, pz, "minecraft:stone");

        // A torch on every floor tile, so the floor's light level is the same everywhere. The
        // player's column is left clear: a torch under the block they stand on would be boxed in.
        // The torches go in before the shell: the ring is clear then, so every torch is placed on
        // its floor and none attaches to a wall. That matters on 1.2.5, where a torch attaches to a
        // solid side when it has one and a torch whose attachment is cleared drops even while its
        // floor still supports it.
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (x != px || z != pz) {
                    adapter.placeBlock(level, x, torchY, z, "minecraft:torch");
                }
            }
        }

        for (int y = wallBottomY; y <= ceilingY; y++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                adapter.placeBlock(level, minX - 1, y, z, "minecraft:stone");
                adapter.placeBlock(level, maxX + 1, y, z, "minecraft:stone");
            }
            for (int x = minX - 1; x <= maxX + 1; x++) {
                adapter.placeBlock(level, x, y, minZ - 1, "minecraft:stone");
                adapter.placeBlock(level, x, y, maxZ + 1, "minecraft:stone");
            }
        }

        for (int z = minZ - 1; z <= maxZ + 1; z++) {
            for (int x = minX - 1; x <= maxX + 1; x++) {
                adapter.placeBlock(level, x, ceilingY, z, "minecraft:stone");
            }
        }

        // The items go last, once the room is sealed again: whatever the block clearing freed,
        // including the roof an earlier test's drop was resting on, and whatever else is inside.
        // The player picks up anything that lands near them, and an item in the hotbar shows in
        // every capture that has one in frame. The ceiling is back by this point, so an item still
        // above the box settles on the roof rather than falling into the room behind the sweep. See
        // #254.
        adapter.clearItemEntities(
                level, minX - 1, wallBottomY, minZ - 1, maxX + 1, ceilingY, maxZ + 1);
    }

    /** Clears one block to air, unless it is already air. */
    private static void clearBlock(ModLoaderAdapter adapter, Object level, int x, int y, int z) {
        if (!adapter.isAir(level, x, y, z)) {
            adapter.placeBlock(level, x, y, z, "minecraft:air");
        }
    }
}
