package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Covers the choice of the native library for the running platform. */
class GrugNativeTest {

    /** The fatal a test provoked is cleared so it cannot fail the next export call on this JVM. */
    @AfterEach
    void drainPendingFatal() {
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive the test.
        }
    }

    @Test
    void linuxLoadsTheSharedObject() {
        assertEquals("libadapter.so", GrugNative.nativeFileName("Linux", "amd64"));
        assertEquals("libadapter.so", GrugNative.nativeFileName("Linux", "x86_64"));
    }

    @Test
    void everyWindowsReleaseLoadsTheDll() {
        assertEquals("adapter.dll", GrugNative.nativeFileName("Windows 11", "amd64"));
        assertEquals("adapter.dll", GrugNative.nativeFileName("Windows 10", "x86_64"));
    }

    @Test
    void anotherArchitectureNamesThePlatformAndWhatTheJarHolds() {
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugNative.nativeFileName("Linux", "aarch64"));
        assertTrue(e.getMessage().contains("Linux on aarch64"), e.getMessage());
        assertTrue(e.getMessage().contains("libadapter.so"), e.getMessage());
        assertTrue(e.getMessage().contains("adapter.dll"), e.getMessage());
    }

    @Test
    void anotherOperatingSystemNamesThePlatform() {
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugNative.nativeFileName("Mac OS X", "x86_64"));
        assertTrue(e.getMessage().contains("Mac OS X on x86_64"), e.getMessage());
    }
}
