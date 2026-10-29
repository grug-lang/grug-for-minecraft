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
 *
 * <p>The build clears the whole test area, not just the room's box: {@link #AREA_RADIUS} blocks
 * around the player and {@link #AREA_TOP} above their feet, which covers the bands the tests build
 * their fixtures in. That is what lets every test start from the same empty world instead of from
 * what an earlier test, or an earlier suite pass, left behind. See #254.
 */
public final class GrugTestBox {
    /**
     * The room's half-size in blocks. The default is a 7x7x7 room, small enough that rebuilding it
     * before every test is cheap and that a test's fixture at one of the usual offsets lands
     * outside it.
     */
    public static final int DEFAULT_RADIUS = 3;

    /**
     * The test area's half-size in blocks. Every fixture the suite builds is within this of the
     * player, so clearing the area removes what an earlier test left and no test has to compensate.
     */
    private static final int AREA_RADIUS = 16;

    /**
     * How far above the player's feet the test area reaches: the top band's fixtures are at +50,
     * and an item dropped on one of them rests a fraction of a block above it, so the top has to
     * clear the fixture's own block, not just the band.
     */
    private static final int AREA_TOP = 56;

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

        // The whole test area is cleared first, top down, so a plant is cleared before the block it
        // stands on: the other order pops it off as an item, which the player then picks up and
        // carries into later captures. The area reaches past the room's box to the bands the tests
        // build their fixtures in, so every test starts from the same empty world. Only blocks that
        // are not already air are replaced.
        int areaMinX = px - AREA_RADIUS;
        int areaMaxX = px + AREA_RADIUS;
        int areaMinZ = pz - AREA_RADIUS;
        int areaMaxZ = pz + AREA_RADIUS;
        int areaTopY = py + AREA_TOP;
        //
        // A room that is already whole is left in place and only what is not the room is cleared,
        // for a loader that can tell (see ModLoaderAdapter#isBlock), because rebuilding a room is
        // the cost of a test there. The end state is the one the full rebuild reaches.
        Room room =
                new Room(
                        px,
                        py,
                        pz,
                        minX,
                        maxX,
                        minZ,
                        maxZ,
                        torchFloorY,
                        torchY,
                        wallBottomY,
                        ceilingY);
        boolean roomIsWhole = room.isWhole(adapter, level);
        for (int y = areaTopY; y >= wallBottomY; y--) {
            for (int z = areaMinZ; z <= areaMaxZ; z++) {
                for (int x = areaMinX; x <= areaMaxX; x++) {
                    if (roomIsWhole && room.blockAt(x, y, z) != null) {
                        continue;
                    }
                    clearBlock(adapter, level, x, y, z);
                }
            }
        }
        if (roomIsWhole) {
            clearItems(
                    adapter, level, areaMinX, wallBottomY, areaMinZ, areaMaxX, areaTopY, areaMaxZ);
            return;
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

        // The items go last, once the room is sealed again: whatever the block clearing freed and
        // whatever else is in the area. The player picks up anything that lands near them, and an
        // item in the hotbar shows in every capture that has one in frame. See #254.
        clearItems(adapter, level, areaMinX, wallBottomY, areaMinZ, areaMaxX, areaTopY, areaMaxZ);
    }

    /**
     * Sweeps the item entities out of the test area without touching a block, which is what the
     * runner does in the tick a test ends. The room build's sweep runs only when the next test
     * starts, and the gap before that is not bounded: around a resource reload it reached about six
     * ticks, long enough for an item spawned above the player to fall into their pick-up box. Doing
     * it at the end of the test means no gap can let the player pick up what the test dropped. See
     * #321 and #323.
     *
     * <p>The bottom is the lowest the room's walls can reach, since a test can rebuild the room
     * with a bigger radius and this does not know which one it asked for.
     */
    public static void sweepItems(
            ModLoaderAdapter adapter, Object level, double feetX, double feetY, double feetZ) {
        int px = (int) Math.floor(feetX);
        int py = (int) Math.floor(feetY);
        int pz = (int) Math.floor(feetZ);
        clearItems(
                adapter,
                level,
                px - AREA_RADIUS,
                py - AREA_RADIUS - 1,
                pz - AREA_RADIUS,
                px + AREA_RADIUS,
                py + AREA_TOP,
                pz + AREA_RADIUS);
    }

    /**
     * Whether the block at {@code (x, y, z)} is inside the area the room build clears around a
     * player whose feet are at the given position, which is where a test starts with nothing of an
     * earlier test's. The bottom is the lowest the room's walls can reach, as in {@link
     * #sweepItems}, since this does not know which radius the room was built with. See #583.
     */
    public static boolean inClearedArea(
            double feetX, double feetY, double feetZ, int x, int y, int z) {
        int px = (int) Math.floor(feetX);
        int py = (int) Math.floor(feetY);
        int pz = (int) Math.floor(feetZ);
        return Math.abs(x - px) <= AREA_RADIUS
                && Math.abs(z - pz) <= AREA_RADIUS
                && y >= py - AREA_RADIUS - 1
                && y <= py + AREA_TOP;
    }

    /** The area's half-size in blocks, for a caller that walks the chunks it covers. */
    public static int areaRadius() {
        return AREA_RADIUS;
    }

    /** Removes the item entities from the area whose last cleared block is the max corner. */
    private static void clearItems(
            ModLoaderAdapter adapter,
            Object level,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {
        // The upper bounds are one past the last cleared block: a block at maxX covers
        // [maxX, maxX + 1), so an item resting in that block has a position past its own
        // coordinate and the sweep would leave it behind. The lower bounds need no adjustment,
        // because a block's interior starts at its coordinate.
        adapter.clearItemEntities(level, minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    /**
     * Where the room's blocks are, as a function of a position, so a room that is already there can
     * be recognised without being rebuilt. It mirrors the order {@link #build} places them in: the
     * floor and the player's block, a torch on every floor tile but the player's column, the shell
     * and the ceiling.
     */
    private static final class Room {
        private static final String STONE = "minecraft:stone";
        private static final String TORCH = "minecraft:torch";

        private final int px;
        private final int py;
        private final int pz;
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int torchFloorY;
        private final int torchY;
        private final int wallBottomY;
        private final int ceilingY;

        Room(
                int px,
                int py,
                int pz,
                int minX,
                int maxX,
                int minZ,
                int maxZ,
                int torchFloorY,
                int torchY,
                int wallBottomY,
                int ceilingY) {
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.torchFloorY = torchFloorY;
            this.torchY = torchY;
            this.wallBottomY = wallBottomY;
            this.ceilingY = ceilingY;
        }

        /**
         * The block the room has at the position, or null where the room has none. Both callers
         * stay at or above the bottom of the walls, so only the top is checked.
         */
        String blockAt(int x, int y, int z) {
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                if (y == torchFloorY || y == ceilingY) {
                    return STONE;
                }
                if (y == py - 1 && x == px && z == pz) {
                    return STONE;
                }
                if (y == torchY && (x != px || z != pz)) {
                    return TORCH;
                }
                return null;
            }
            boolean inShell = x >= minX - 1 && x <= maxX + 1 && z >= minZ - 1 && z <= maxZ + 1;
            return inShell && y <= ceilingY ? STONE : null;
        }

        /** Whether every block of the room is in place, which a loader that cannot tell denies. */
        boolean isWhole(ModLoaderAdapter adapter, Object level) {
            for (int y = wallBottomY; y <= ceilingY; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    for (int x = minX - 1; x <= maxX + 1; x++) {
                        String block = blockAt(x, y, z);
                        if (block != null && !adapter.isBlock(level, x, y, z, block)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    /** Clears one block to air, unless it is already air. */
    private static void clearBlock(ModLoaderAdapter adapter, Object level, int x, int y, int z) {
        if (!adapter.isAir(level, x, y, z)) {
            adapter.placeBlock(level, x, y, z, "minecraft:air");
        }
    }
}
