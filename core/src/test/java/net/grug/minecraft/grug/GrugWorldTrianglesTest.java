package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers {@code Color.rgba}, the world pass's translucent triangles, {@code
 * TestTickContext.set_player_position} and {@code TestTickContext.break_block}: what each accepts,
 * what it refuses and what it records.
 */
class GrugWorldTrianglesTest {
    private Object savedAdapter;
    private boolean savedTrianglesSupported;
    private final List<double[]> moves = new ArrayList<>();
    private final List<double[]> breaks = new ArrayList<>();
    private boolean chunkLoaded = true;

    @BeforeEach
    void installAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        moves.clear();
        breaks.clear();
        chunkLoaded = true;
        savedTrianglesSupported = GrugWorldOverlay.trianglesSupported();
        setTrianglesSupported(false);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("setPlayerPositionForTest")) {
                                moves.add(
                                        new double[] {
                                            (double) args[0], (double) args[1], (double) args[2]
                                        });
                                return chunkLoaded;
                            }
                            if (method.getName().equals("breakBlockForTest")) {
                                breaks.add(
                                        new double[] {
                                            Math.floor((double) args[1]),
                                            Math.floor((double) args[2]),
                                            Math.floor((double) args[3])
                                        });
                                return null;
                            }
                            if (method.isDefault()) {
                                return InvocationHandler.invokeDefault(proxy, method, args);
                            }
                            if (method.getName().equals("logError")) {
                                return null;
                            }
                            throw new AssertionError("does not ask " + method.getName());
                        }));
    }

    @AfterEach
    void restoreAdapter() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        setTrianglesSupported(savedTrianglesSupported);
        if (GrugWorldOverlay.isWorld()) {
            GrugWorldOverlay.closeWorld();
        }
        GrugEntities.entityData.clear();
        drain();
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal a test provoked, cleared so it cannot outlive the test.
        }
    }

    /** Sets the loader's answer to whether it draws world triangles, which a game sets once. */
    private static void setTrianglesSupported(boolean supported) throws Exception {
        Field field = GrugWorldOverlay.class.getDeclaredField("trianglesSupported");
        field.setAccessible(true);
        field.setBoolean(null, supported);
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    @Test
    void anRgbaColorKeepsItsAlpha() {
        long id = HostFunctions.Color_rgba(255, 34, 34, 64);
        Color color = (Color) GrugEntities.entityData.get(id).object;
        assertEquals(64, color.a());
        assertEquals(255, color.r());
        assertEquals(0x40ff2222, color.getARGB());
        assertTrue(drain().isEmpty());
    }

    @Test
    void twoColorsThatDifferOnlyInAlphaDoNotShareAHash() {
        long opaque = HostFunctions.Color_rgba(255, 34, 34, 255);
        long translucent = HostFunctions.Color_rgba(255, 34, 34, 64);
        Color first = (Color) GrugEntities.entityData.get(opaque).object;
        Color second = (Color) GrugEntities.entityData.get(translucent).object;
        assertNotEquals(first, second);
        assertNotEquals(first.hashCode(), second.hashCode(), "a hash that ignores alpha collides");
    }

    @Test
    void anRgbaValueOutOfRangeOrWithAFractionIsRefused() {
        assertEquals(0L, HostFunctions.Color_rgba(256, 0, 0, 0));
        assertEquals(0L, HostFunctions.Color_rgba(0, -1, 0, 0));
        assertEquals(0L, HostFunctions.Color_rgba(0, 0, 1.5, 0));
        assertEquals(0L, HostFunctions.Color_rgba(0, 0, 0, Double.NaN));
        assertEquals(4, drain().size());
        assertNull(HostFunctions.colorProblem(0, 255, 0, 255));
    }

    @Test
    void aTriangleIsRecordedWithItsCornersAndAlpha() {
        GrugWorldOverlay.noteTrianglesSupported();
        long color = HostFunctions.Color_rgba(0, 255, 0, 100);
        GrugWorldOverlay.openWorld(0, 0, 0, 0, 0, 0, 0, null);
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldContext, GrugWorldOverlay.context());

        HostFunctions.OverlayWorldContext_draw_triangle(context, 0, 1, 2, 3, 4, 5, 6, 7, 8, color);
        HostFunctions.OverlayWorldContext_draw_triangle(
                context, 0, 1, Double.NaN, 3, 4, 5, 6, 7, 8, color);

        assertEquals(1, drain().size(), "The triangle with a corner that is NaN is refused.");
        GrugWorldOverlay.closeWorld();
        List<GrugWorldOverlay.Triangle> triangles = GrugWorldOverlay.lastTriangles();
        assertEquals(1, triangles.size());
        assertEquals(8, triangles.get(0).corner(2, 2));
        assertEquals(3, triangles.get(0).corner(1, 0));
        assertEquals(0x6400ff00, triangles.get(0).argb());
        assertTrue(GrugWorldOverlay.trianglesSupported());
    }

    @Test
    void aTriangleOutsideAWorldPassIsNotRecorded() {
        assertFalse(GrugWorldOverlay.recordTriangle(new double[9], 0));
    }

    @Test
    void aLoaderThatCannotDrawTrianglesStopsTheGameInsteadOfRecordingOne() {
        long color = HostFunctions.Color_rgba(0, 255, 0, 100);
        GrugWorldOverlay.openWorld(0, 0, 0, 0, 0, 0, 0, null);
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldContext, GrugWorldOverlay.context());

        IllegalStateException fatal =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                HostFunctions.OverlayWorldContext_draw_triangle(
                                        context, 0, 1, 2, 3, 4, 5, 6, 7, 8, color));

        assertTrue(
                fatal.getMessage().contains("draw_triangle is not supported on this loader"),
                fatal.getMessage());
        GrugWorldOverlay.closeWorld();
        assertTrue(
                GrugWorldOverlay.lastTriangles().isEmpty(), "The refused call recorded nothing.");
        assertTrue(drain().isEmpty(), "An unsupported loader gets a fatal, not a report.");
    }

    @Test
    void thePlayerIsMovedWhenTheChunkIsLoadedAndTheCallReportsWhenItIsNot() {
        long context = GrugTestContexts.tick();

        HostFunctions.TestTickContext_set_player_position(context, 1, 64, -2);
        assertTrue(drain().isEmpty());
        assertEquals(1, moves.size());
        assertEquals(-2, moves.get(0)[2]);

        chunkLoaded = false;
        HostFunctions.TestTickContext_set_player_position(context, 500, 64, 0);
        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("is not loaded"));

        HostFunctions.TestTickContext_set_player_position(context, Double.NaN, 64, 0);
        HostFunctions.TestTickContext_set_player_position(context, 1, Double.NaN, 0);
        HostFunctions.TestTickContext_set_player_position(context, 1, 64, Double.POSITIVE_INFINITY);
        assertEquals(3, drain().size());
        assertEquals(2, moves.size(), "A position that is not finite never reaches the loader.");
    }

    @Test
    void theBlockAtTheFlooredPositionIsBrokenAndANonFiniteOneIsRefused() {
        long context = GrugTestContexts.tick();
        long level = GrugEntities.addEntity(GrugEntityType.Level, new Object());

        // A finite fraction floors to the block below it, as every loader has always done.
        HostFunctions.TestTickContext_break_block(context, level, 1.9, 64.2, -2.7);
        assertTrue(drain().isEmpty());
        assertEquals(1, breaks.size());
        assertEquals(1, breaks.get(0)[0]);
        assertEquals(64, breaks.get(0)[1]);
        assertEquals(-3, breaks.get(0)[2]);

        HostFunctions.TestTickContext_break_block(context, level, Double.NaN, 64, 0);
        HostFunctions.TestTickContext_break_block(context, level, 1, Double.NaN, 0);
        HostFunctions.TestTickContext_break_block(context, level, 1, 64, Double.POSITIVE_INFINITY);
        List<String> reports = drain();
        assertEquals(3, reports.size());
        for (String report : reports) {
            assertTrue(report.contains("TestTickContext.break_block"), report);
            assertTrue(report.contains("has to be finite"), report);
        }
        assertEquals(1, breaks.size(), "A position that is not finite never reaches the loader.");
    }
}
