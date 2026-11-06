package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the decisions behind has_sound and play_ui_sound that a Java test can drive without the
 * game: which names and levels are refused, and which names reach the adapter. See #914.
 */
class HostFunctionsSoundTest {

    /** An adapter whose sound table holds {@code known}, recording every name it was asked. */
    private static ModLoaderAdapter adapter(String known, List<String> asked) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        HostFunctionsSoundTest.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("hasSound")) {
                                asked.add((String) args[0]);
                                return args[0].equals(known);
                            }
                            return null;
                        });
    }

    @Test
    void aNamespaceAndAPathAreAName() {
        assertNull(HostFunctions.soundNameProblem("minecraft:ui.button.click"));
        assertNull(HostFunctions.soundNameProblem("minecraft:random.click"));
        assertNull(HostFunctions.soundNameProblem("grug:item/armor-equip_elytra"));
    }

    @Test
    void aStringThatIsNotANamespaceAndAPathIsRefused() {
        for (String bad :
                new String[] {
                    "",
                    "ui.button.click",
                    ":x",
                    "minecraft:",
                    "a:b:c",
                    "Minecraft:x",
                    "a:B",
                    "a b:c"
                }) {
            assertTrue(
                    HostFunctions.soundNameProblem(bad).contains("namespace, a colon and a path"),
                    bad);
        }
    }

    @Test
    void aVolumeFromZeroToOneAndAPitchFromHalfToTwoAreAccepted() {
        assertNull(HostFunctions.soundLevelsProblem(0, 0.5));
        assertNull(HostFunctions.soundLevelsProblem(1, 2));
        assertNull(HostFunctions.soundLevelsProblem(0.25, 1));
    }

    @Test
    void aVolumeOrPitchOutsideItsRangeOrNotANumberIsRefused() {
        assertTrue(HostFunctions.soundLevelsProblem(-0.1, 1).contains("volume"));
        assertTrue(HostFunctions.soundLevelsProblem(1.1, 1).contains("volume"));
        assertTrue(HostFunctions.soundLevelsProblem(Double.NaN, 1).contains("volume"));
        assertTrue(HostFunctions.soundLevelsProblem(1, 0.4).contains("pitch"));
        assertTrue(HostFunctions.soundLevelsProblem(1, 2.1).contains("pitch"));
        assertTrue(HostFunctions.soundLevelsProblem(1, Double.NaN).contains("pitch"));
        assertTrue(HostFunctions.soundLevelsProblem(1, Double.POSITIVE_INFINITY).contains("pitch"));
    }

    @Test
    void aMinecraftNameIsLookedUpByItsPathInTheAdapter() {
        List<String> asked = new ArrayList<>();
        ModLoaderAdapter adapter = adapter("random.click", asked);

        assertTrue(HostFunctions.soundKnown(adapter, "minecraft:random.click"));
        assertFalse(HostFunctions.soundKnown(adapter, "minecraft:ui.button.click"));
        assertEquals(List.of("random.click", "ui.button.click"), asked);
    }

    @Test
    void anotherNamespaceIsNeverKnownAndNeverAsksTheAdapter() {
        List<String> asked = new ArrayList<>();

        assertFalse(HostFunctions.soundKnown(adapter("random.click", asked), "grug:random.click"));
        assertEquals(List.of(), asked);
    }

    @Test
    void aFailedForwardedLookupIsTheOnlyErrorAPlayReports() {
        // An adapter whose lookup is a forwarded call that fails, as on a server with no starter.
        net.grug.minecraft.core.GrugControlServer server =
                new net.grug.minecraft.core.GrugControlServer(
                        new net.grug.minecraft.core.GrugControlServer.Link() {
                            @Override
                            public boolean sendToStarter(Object starter, byte[] message) {
                                return true;
                            }

                            @Override
                            public void flushWorld() {}

                            @Override
                            public void applyClientWork() {}

                            @Override
                            public boolean mayStartRun(Object sender) {
                                return true;
                            }
                        },
                        10);
        ModLoaderAdapter failing =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                HostFunctionsSoundTest.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    try {
                                        return server.hasSound((String) args[0]);
                                    } catch (
                                            net.grug.minecraft.core.GrugControlServer
                                                            .ControlException
                                                    e) {
                                        return false;
                                    }
                                });

        assertEquals("", HostFunctions.playUiSoundProblem(failing, "minecraft:random.click", 1, 1));

        // A lookup that really answers no keeps the usual message, and an earlier failure does
        // not leak into it.
        String unknown =
                HostFunctions.playUiSoundProblem(
                        adapter("random.click", new ArrayList<>()), "minecraft:nope", 1, 1);
        assertTrue(unknown.contains("The game has no sound named"), unknown);
        assertNull(
                HostFunctions.playUiSoundProblem(
                        adapter("random.click", new ArrayList<>()),
                        "minecraft:random.click",
                        1,
                        1));
        assertTrue(
                HostFunctions.playUiSoundProblem(failing, "bad name", 1, 1)
                        .contains("namespace, a colon and a path"));
        assertTrue(
                HostFunctions.playUiSoundProblem(failing, "minecraft:x", 2, 1).contains("volume"));
    }

    @Test
    void aProblemTheAdapterAlreadyReportedIsNotReportedAgain() {
        List<String> reported = new ArrayList<>();
        HostFunctions.reportPlayProblem("", reported::add);
        assertEquals(List.of(), reported);
        HostFunctions.reportPlayProblem("bad", reported::add);
        assertEquals(List.of("play_ui_sound: bad"), reported);
    }
}
