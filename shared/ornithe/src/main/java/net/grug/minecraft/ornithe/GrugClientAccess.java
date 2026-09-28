package net.grug.minecraft.ornithe;

/**
 * The few Minecraft members the client hooks need that a plain {@code net.grug.*} class cannot call
 * directly: the version-specific chat call, the private window resize, and shutdown.
 *
 * <p>A mixin implements this and merges the methods into the target, so {@link GrugClientHooks} can
 * drive the client without depending on mixin-only members.
 */
public interface GrugClientAccess {
    /** Sends one already-split chat line, respecting the loader's version-specific chat API. */
    void grug$sendChat(String line);

    /** Resizes the game window, which maps to the private {@code Minecraft.resize}. */
    void grug$resize(int width, int height);

    /** Shuts the game down. */
    void grug$shutdown();
}
