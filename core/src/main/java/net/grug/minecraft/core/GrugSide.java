package net.grug.minecraft.core;

/**
 * Which of the two physical processes a grug runtime is living in.
 *
 * <p>A Minecraft client is one process whether it is alone in singleplayer or connected to somebody
 * else, so {@link #CLIENT} covers both. The third thing grug runs in is a dedicated server: a
 * second process with no client of its own, and so with no window, no renderer and no local player.
 * The difference matters to the host functions that need any of those, which is why the runtime
 * asks the loader rather than assuming.
 *
 * <p>The loader knows, because the game told it: Fabric Loader has an environment type, Forge has a
 * distribution, and neither is something grug has to guess at.
 */
public enum GrugSide {
    /** A client process, in singleplayer or connected to a server. */
    CLIENT("a client"),
    /** A dedicated server process. */
    DEDICATED_SERVER("a dedicated server");

    private final String description;

    GrugSide(String description) {
        this.description = description;
    }

    /**
     * The side named the way a sentence wants it, such as "There is no player to test from on a
     * dedicated server yet." A sentence has to read, and an enum constant does not.
     */
    public String description() {
        return description;
    }
}
