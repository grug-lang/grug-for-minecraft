package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The screen calls a client performs, and the error path of the same calls when a dedicated server
 * forwards them and has no client to reach (#1094). The forwarding itself is covered in {@link
 * GrugControlTest}, which has both ends of the channel.
 */
class GrugScreenCallsTest {
    /** A stand-in adapter: the five measurements, the screen flag and the frame bookkeeping. */
    private static ModLoaderAdapter adapter(
            BooleanSupplier open, AtomicInteger forgotten, List<String> closed) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        ModLoaderAdapter.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "isScreenOpenForTest":
                                    return open.getAsBoolean();
                                case "forgetSettledFrames":
                                    forgotten.incrementAndGet();
                                    return null;
                                case "closeOpenScreenForTest":
                                    closed.add("closed");
                                    return null;
                                case "screenWidthForTest":
                                    return 640.0;
                                case "guiLeftForTest":
                                    return 10.0;
                                case "guiTopForTest":
                                    return 20.0;
                                case "guiWidthForTest":
                                    return 176.0;
                                case "guiHeightForTest":
                                    return 166.0;
                                default:
                                    throw new AssertionError(
                                            "unexpected adapter call " + method.getName());
                            }
                        });
    }

    private static ModLoaderAdapter adapter() {
        return adapter(() -> true, new AtomicInteger(), new ArrayList<>());
    }

    @Test
    void screenOpenForgetsTheFramesOnlyWhenAScreenHasJustAppeared() {
        boolean[] open = {false};
        AtomicInteger forgotten = new AtomicInteger();
        List<String> closed = new ArrayList<>();
        ModLoaderAdapter adapter = adapter(() -> open[0], forgotten, closed);
        // The class shares whether a screen was already open across calls, so start from closed.
        GrugScreenCalls.closeScreen(adapter);
        forgotten.set(0);
        closed.clear();

        assertFalse(GrugScreenCalls.screenOpen(adapter));
        assertEquals(0, forgotten.get());

        // A screen that has just appeared forgets the frames counted so far, so a following settle
        // wait can only be answered by frames drawn after it opened (#421).
        open[0] = true;
        assertTrue(GrugScreenCalls.screenOpen(adapter));
        assertEquals(1, forgotten.get());

        // One that was already open keeps them.
        assertTrue(GrugScreenCalls.screenOpen(adapter));
        assertEquals(1, forgotten.get());

        // A screen that closes and opens again is fresh.
        open[0] = false;
        assertFalse(GrugScreenCalls.screenOpen(adapter));
        open[0] = true;
        assertTrue(GrugScreenCalls.screenOpen(adapter));
        assertEquals(2, forgotten.get());

        // closeScreen forgets that one was open, so the next screen is fresh even without the
        // screen reporting closed first.
        GrugScreenCalls.closeScreen(adapter);
        assertTrue(GrugScreenCalls.screenOpen(adapter));
        assertEquals(3, forgotten.get());
        assertEquals(List.of("closed"), closed);
    }

    @Test
    void theScreenReadsAreTheAdaptersOwnMeasurements() {
        ModLoaderAdapter adapter = adapter();
        assertEquals(640.0, GrugScreenCalls.read(adapter, GrugScreenCalls.SCREEN_WIDTH));
        assertEquals(10.0, GrugScreenCalls.read(adapter, GrugScreenCalls.GUI_LEFT));
        assertEquals(20.0, GrugScreenCalls.read(adapter, GrugScreenCalls.GUI_TOP));
        assertEquals(176.0, GrugScreenCalls.read(adapter, GrugScreenCalls.GUI_WIDTH));
        assertEquals(166.0, GrugScreenCalls.read(adapter, GrugScreenCalls.GUI_HEIGHT));
        assertThrows(IllegalArgumentException.class, () -> GrugScreenCalls.read(adapter, "bogus"));
    }

    @Test
    void everyScreenReadHasANameAndNothingElseDoes() {
        assertNull(GrugScreenCalls.readProblem(GrugScreenCalls.SCREEN_WIDTH));
        assertNull(GrugScreenCalls.readProblem(GrugScreenCalls.GUI_LEFT));
        assertNull(GrugScreenCalls.readProblem(GrugScreenCalls.GUI_TOP));
        assertNull(GrugScreenCalls.readProblem(GrugScreenCalls.GUI_WIDTH));
        assertNull(GrugScreenCalls.readProblem(GrugScreenCalls.GUI_HEIGHT));
        assertEquals("The screen read has no name.", GrugScreenCalls.readProblem(null));
        assertEquals("There is no screen read named bogus.", GrugScreenCalls.readProblem("bogus"));
    }

    @Test
    void aScreenPressButtonIsTheLeftRightOrMiddleOne() {
        assertNull(GrugScreenCalls.buttonProblem(0));
        assertNull(GrugScreenCalls.buttonProblem(1));
        assertNull(GrugScreenCalls.buttonProblem(GrugScreenCalls.MAX_BUTTON));
        assertEquals(2, GrugScreenCalls.MAX_BUTTON);
        // The first button past the range, the two the audit found the 1.2.5 client turning into
        // other presses, and one below it (#1217).
        assertTrue(GrugScreenCalls.buttonProblem(3).contains("0 to 2"));
        assertTrue(GrugScreenCalls.buttonProblem(256).contains("0 to 2"));
        assertTrue(GrugScreenCalls.buttonProblem(255).contains("0 to 2"));
        assertTrue(GrugScreenCalls.buttonProblem(-1).contains("0 to 2"));
        assertTrue(GrugScreenCalls.buttonProblem(0.5).contains("whole number"));
        assertTrue(GrugScreenCalls.buttonProblem(Double.NaN).contains("finite"));
        assertTrue(GrugScreenCalls.buttonProblem(Double.POSITIVE_INFINITY).contains("finite"));
    }

    @Test
    void aForwardedScreenCallWithoutAStarterFailsAsTheHostFunctionsError() {
        GrugControlServer server =
                new GrugControlServer(
                        new GrugControlServer.Link() {
                            @Override
                            public boolean sendToStarter(Object starter, byte[] message) {
                                return false;
                            }

                            @Override
                            public void flushWorld() {}

                            @Override
                            public void applyClientWork() {}

                            @Override
                            public boolean mayStartRun(Object sender) {
                                return false;
                            }
                        },
                        1000);
        List<String> failures = new ArrayList<>();
        GrugScreenCalls.Forwarded forwarded =
                new GrugScreenCalls.Forwarded(() -> server, failures::add);

        assertFalse(forwarded.screenOpen());
        assertEquals(0.0, forwarded.read(GrugScreenCalls.SCREEN_WIDTH));
        assertFalse(forwarded.pressScreenKey(1, false, true));
        assertFalse(forwarded.scrollScreen(0, 0, 1));
        forwarded.closeScreen();
        assertFalse(forwarded.clickScreen(12, 34, 0));
        forwarded.openInventory();

        assertEquals(7, failures.size(), failures.toString());
        assertTrue(failures.get(0).startsWith("Test.screen_open: "), failures.get(0));
        assertTrue(failures.get(1).startsWith("Test.screen_width: "), failures.get(1));
        assertTrue(failures.get(2).startsWith("Test.press_screen_key: "), failures.get(2));
        assertTrue(failures.get(3).startsWith("Test.scroll_screen: "), failures.get(3));
        assertTrue(failures.get(4).startsWith("Test.close_screen: "), failures.get(4));
        assertTrue(failures.get(5).startsWith("Test.click_screen: "), failures.get(5));
        assertTrue(failures.get(6).startsWith("Test.open_inventory: "), failures.get(6));
        for (String failure : failures) {
            assertTrue(failure.contains("No client has started a run"), failure);
        }
    }
}
