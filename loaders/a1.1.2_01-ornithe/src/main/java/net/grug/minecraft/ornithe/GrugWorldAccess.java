package net.grug.minecraft.ornithe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/**
 * The world reads whose name differs between this loader's client (Alpha 1.1.2_01) and the Alpha
 * dedicated server (a0.2.1) in Ornithe's intermediary.
 *
 * <p>Most of {@code World} has the same intermediary name on both, but the client's {@code
 * getBlock(x, y, z)}, which hands back a block id, is a method the server's calamus intermediary
 * names differently, so a call compiled against the client's name throws {@code NoSuchMethodError}
 * on the server. This layer is compiled against the client only, so the server's name is looked up
 * by reflection, once, in the one place that needs it. See #1021.
 */
@GrugGenerated("the server's name for World.getBlock, which only the dedicated-server job reaches")
final class GrugWorldAccess {
    /** The server's intermediary name of the method that returns the block id at a position. */
    private static final String SERVER_GET_BLOCK = "m_33234383";

    private static final boolean CLIENT =
            FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

    private static Method serverGetBlock = null;

    private GrugWorldAccess() {}

    /** The block id at a position, which is {@code World.getBlock} on a client. */
    static int blockId(World world, int x, int y, int z) {
        if (CLIENT) {
            return world.getBlock(x, y, z);
        }
        try {
            if (serverGetBlock == null) {
                serverGetBlock =
                        World.class.getMethod(SERVER_GET_BLOCK, int.class, int.class, int.class);
            }
            return (Integer) serverGetBlock.invoke(world, x, y, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "The dedicated server's World has no " + SERVER_GET_BLOCK + "(int, int, int).",
                    e);
        }
    }
}
