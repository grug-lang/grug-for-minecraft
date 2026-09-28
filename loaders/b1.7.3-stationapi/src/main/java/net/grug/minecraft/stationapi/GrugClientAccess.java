package net.grug.minecraft.stationapi;

/**
 * The few Minecraft members the StationAPI client hooks need that a plain {@code net.grug.*} class
 * cannot call directly: chat, the private window resize, and shutdown.
 *
 * <p>A mixin implements this and merges the methods into the target, so {@link GrugClientHooks} can
 * drive the client without depending on mixin-only members.
 */
public interface GrugClientAccess {
    /** Sends one already-split chat line. */
    void grug$sendChat(String line);

    /** Resizes the game window, which maps to the private {@code Minecraft.resize}. */
    void grug$resize(int width, int height);

    /** Stops the game. */
    void grug$shutdown();
}
