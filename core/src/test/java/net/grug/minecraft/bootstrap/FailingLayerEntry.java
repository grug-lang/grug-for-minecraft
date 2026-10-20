package net.grug.minecraft.bootstrap;

/** A layer's entrypoint whose own start fails. */
public class FailingLayerEntry {
    public FailingLayerEntry() {
        throw new IllegalArgumentException("the layer failed");
    }
}
