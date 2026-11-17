package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class GrugPendingUploadsTest {
    @Test
    void aTextureQueuedDuringACompileIsHandedBackOnceInFirstQueuedOrder() {
        GrugPendingUploads pending = new GrugPendingUploads();
        pending.add("b.png");
        pending.add("a.png");
        pending.add("b.png");

        assertEquals(Arrays.asList("b.png", "a.png"), pending.drain());
    }

    @Test
    void aDrainedQueueIsEmptyUntilTheNextTexture() {
        GrugPendingUploads pending = new GrugPendingUploads();
        pending.add("a.png");
        pending.drain();

        assertTrue(pending.drain().isEmpty());
        pending.add("a.png");
        assertEquals(Arrays.asList("a.png"), pending.drain());
    }
}
