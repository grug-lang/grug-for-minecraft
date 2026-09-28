package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

/** Covers the guards that only fire before {@code grug_state} is initialized. */
class GrugStateGuardTest {
    private long savedStatePtr;

    @BeforeEach
    void saveStatePtr() {
        savedStatePtr = Grug.statePtr;
    }

    @AfterEach
    void restoreStatePtr() {
        Grug.statePtr = savedStatePtr;
    }

    @Test
    void updateReturnsNothingBeforeInit() {
        Grug.statePtr = 0;
        assertArrayEquals(new String[0], Grug.update(message -> {}));
    }

    @Test
    void compileAllFilesThrowsBeforeInit() {
        Grug.statePtr = 0;
        assertThrows(IllegalStateException.class, Grug::compileAllFiles);
    }

    @Test
    void initIsSkippedWhenAlreadyInitialized() {
        Grug.statePtr = 12345L;
        Grug.init(new File("missing-mod-api.json"), new File("missing-mods"));
        assertEquals(12345L, Grug.statePtr);
    }
}
