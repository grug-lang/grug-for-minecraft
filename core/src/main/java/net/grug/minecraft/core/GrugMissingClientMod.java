package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

/**
 * The fatal a client raises when a mod it needs is absent, for the client-only mods a loader cannot
 * declare in its {@code fabric.mod.json}.
 *
 * <p>A loader's dedicated server layer cannot load such a mod, so declaring it would keep the layer
 * from loading there at all. Fabric therefore says nothing when a client lacks it, and the client's
 * first sign is a {@code NoClassDefFoundError} from a mixin's static initializer, which names a
 * class rather than a mod a player can install (#1056).
 */
public final class GrugMissingClientMod {
    private GrugMissingClientMod() {}

    /**
     * The fatal that names the missing {@code modId}, for a client-side call that threw {@code
     * cause}. Use as {@code throw GrugMissingClientMod.fatal(...)} at the call that touched the
     * mod's classes.
     */
    public static RuntimeException fatal(String modId, String client, NoClassDefFoundError cause) {
        return Grug.fatal(
                "Grug on the "
                        + client
                        + " client needs the "
                        + modId
                        + " mod, which is not"
                        + " installed.",
                cause);
    }
}
