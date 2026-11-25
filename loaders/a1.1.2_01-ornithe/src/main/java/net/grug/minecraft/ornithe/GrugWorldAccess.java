package net.grug.minecraft.ornithe;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/**
 * The world reads this layer looks up by name rather than calling directly: the block id at a
 * position, which {@code World.getBlock} names in the a1.1.2_01 development jar, and the metadata,
 * which {@code World.getBlockMetadata} names there.
 *
 * <p>Ornithe remaps the game and this layer together for what it installs, but a name held in a
 * string is not remapped with the code around it. In an installed jar, on the client and on the
 * a0.2.1 dedicated server alike, the two reads are {@code m_33234383} and {@code m_06541281} on
 * {@code World}, and neither {@code getBlock} nor {@code getBlockMetadata} exists at runtime. A
 * lookup therefore tries the development name first, which also answers on a development classpath,
 * and the intermediary name second. Because the lookup answers which name exists by itself, it
 * needs no environment test to decide the side it runs on. See #1021 and #1522.
 *
 * <p>The lookup is not free: a region packet reads both methods for every cell it packs, so a
 * full-height 16 x 128 x 16 region makes two {@code Method.invoke} calls per cell, up to 65536 per
 * packet.
 */
@GrugGenerated(
        "the development name arm, which only a development classpath takes; every"
                + " installed jar names the two reads in the intermediary")
final class GrugWorldAccess {
    /** The intermediary name of the method that returns the block id at a position. */
    private static final String GET_BLOCK = "m_33234383";

    /** The intermediary name of the method that returns the metadata at a position. */
    private static final String GET_BLOCK_METADATA = "m_06541281";

    private static Method getBlock = null;

    private static Method getBlockMetadata = null;

    private GrugWorldAccess() {}

    /** The block id at a position, which {@code World.getBlock} names in development. */
    static int blockId(World world, int x, int y, int z) {
        if (getBlock == null) {
            getBlock = method("getBlock", GET_BLOCK);
        }
        return read(getBlock, world, x, y, z);
    }

    /**
     * The block metadata at a position, which {@code World.getBlockMetadata} names in development.
     */
    static int metadata(World world, int x, int y, int z) {
        if (getBlockMetadata == null) {
            getBlockMetadata = method("getBlockMetadata", GET_BLOCK_METADATA);
        }
        return read(getBlockMetadata, world, x, y, z);
    }

    /** The world's method of the first of the two names it holds. */
    private static Method method(String developmentName, String intermediaryName) {
        try {
            return GrugMethodAccess.method(
                    World.class,
                    developmentName,
                    intermediaryName,
                    int.class,
                    int.class,
                    int.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "The world has neither "
                            + developmentName
                            + " nor "
                            + intermediaryName
                            + "(int, int, int), so the block at a position cannot be read.",
                    e);
        }
    }

    /** The answer of one of those methods. */
    private static int read(Method method, World world, int x, int y, int z) {
        try {
            return (Integer) method.invoke(world, x, y, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "The world's " + method.getName() + "(int, int, int) failed.", e);
        }
    }
}
