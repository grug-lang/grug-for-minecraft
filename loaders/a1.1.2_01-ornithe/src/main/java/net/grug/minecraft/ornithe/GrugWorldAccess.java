package net.grug.minecraft.ornithe;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/**
 * The world reads whose name differs between this loader's client (Alpha 1.1.2_01) and the Alpha
 * dedicated server (a0.2.1) in Ornithe's intermediary.
 *
 * <p>Most of {@code World} has the same intermediary name on both, but {@code getBlock(x, y, z)},
 * which hands back a block id, and {@code getBlockMetadata(x, y, z)} are names the server's calamus
 * intermediary spells differently, so a call compiled against the client's name throws {@code
 * NoSuchMethodError} on the server. This layer is compiled against the client only, so the server's
 * names are looked up by reflection, once each, in the one place that needs them. A region packet
 * reads both of them for every cell it packs, so the direct call in its {@code WorldSource} was
 * #1522's third finding. See #1021.
 */
@GrugGenerated(
        "the server's names for World's block reads, which only the dedicated-server job"
                + " reaches")
final class GrugWorldAccess {
    /** The server's intermediary name of the method that returns the block id at a position. */
    private static final String SERVER_GET_BLOCK = "m_33234383";

    /** The server's intermediary name of the method that returns the metadata at a position. */
    private static final String SERVER_GET_BLOCK_METADATA = "m_06541281";

    private static Method getBlock = null;

    private static Method getBlockMetadata = null;

    private GrugWorldAccess() {}

    /** The block id at a position, which is {@code World.getBlock} on a client. */
    static int blockId(World world, int x, int y, int z) {
        if (getBlock == null) {
            getBlock = method("getBlock", SERVER_GET_BLOCK);
        }
        return read(getBlock, world, x, y, z);
    }

    /** The block metadata at a position, which is {@code World.getBlockMetadata} on a client. */
    static int metadata(World world, int x, int y, int z) {
        if (getBlockMetadata == null) {
            getBlockMetadata = method("getBlockMetadata", SERVER_GET_BLOCK_METADATA);
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
