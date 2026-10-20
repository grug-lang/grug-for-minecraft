package net.grug.minecraft.bootstrap;

/** A layer's entrypoint that fails with an Error rather than an exception. */
public class ErrorLayerEntry {
    public ErrorLayerEntry() {
        throw new LinkageError("a class of another game");
    }
}
