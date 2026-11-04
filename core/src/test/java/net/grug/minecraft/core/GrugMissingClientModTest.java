package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrugMissingClientModTest {
    /**
     * Drains the fatal a test provoked. {@code Grug.fatal} remembers the first error per thread and
     * rethrows it at the next native call boundary, so leaving one set would fail the next test on
     * this JVM that drives an export function, for a reason unrelated to that test.
     */
    @AfterEach
    void drainPendingFatal() {
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive the test.
        }
    }

    @Test
    void namesTheModThePlayerHasToInstall() {
        RuntimeException fatal =
                GrugMissingClientMod.fatal(
                        "osl-keybinds", "Alpha 1.1.2_01", noClassDefFound("KeybindEvents"));

        assertTrue(fatal.getMessage().contains("osl-keybinds"), fatal.getMessage());
        assertTrue(fatal.getMessage().contains("Alpha 1.1.2_01"), fatal.getMessage());
        assertTrue(fatal.getMessage().contains("which is not installed"), fatal.getMessage());
    }

    @Test
    void namesTheResourceLoaderModOnAlpha() {
        NoClassDefFoundError cause =
                new NoClassDefFoundError(
                        "net/ornithemc/osl/resource/loader/api/client/ClientResourceLoaderEvents");

        RuntimeException fatal =
                GrugMissingClientMod.fatal("osl-resource-loader", "Alpha 1.1.2_01", cause);

        assertTrue(fatal.getMessage().contains("osl-resource-loader"), fatal.getMessage());
        assertTrue(fatal.getMessage().contains("Alpha 1.1.2_01"), fatal.getMessage());
        assertTrue(fatal.getMessage().contains("which is not installed"), fatal.getMessage());
        assertSame(cause, fatal.getCause());
    }

    @Test
    void keepsTheClassThatWasMissingAsTheCause() {
        NoClassDefFoundError cause = noClassDefFound("KeybindEvents");

        RuntimeException fatal =
                GrugMissingClientMod.fatal("osl-keybinds", "Alpha 1.1.2_01", cause);

        assertSame(cause, fatal.getCause());
    }

    private static NoClassDefFoundError noClassDefFound(String simpleName) {
        return new NoClassDefFoundError("net/ornithemc/osl/keybinds/api/" + simpleName);
    }
}
