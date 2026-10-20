package net.grug.minecraft.bootstrap;

/** A layer's entrypoint that records being constructed. */
public class FakeLayerEntry {
    static int constructed;

    public FakeLayerEntry() {
        constructed++;
    }
}
