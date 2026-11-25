package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Covers the cube scan a mod asks for through a world pass: the reference's z, y, x order, the
 * height and spawn-space filters, the light it keeps, and the growth past its first capacity.
 */
class GrugSpawnSquaresTest {

    /** A level that is solid everywhere {x + y + z} is even, inside y from 0 to 19. */
    private static ModLoaderAdapter toy() {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        GrugSpawnSquaresTest.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "isOutOfHeightLimit":
                                    int y = (Integer) args[1];
                                    return y < 0 || y >= 20;
                                case "isHostileSpawnSpace":
                                    int x = number(args[1]);
                                    y = number(args[2]);
                                    int z = number(args[3]);
                                    return (x + y + z) % 2 == 0;
                                case "getBlockLight":
                                    return number(args[1]) + 1;
                                case "getSkyLight":
                                    return 15 - number(args[2]);
                                case "logError":
                                    return null;
                                default:
                                    if (method.isDefault()) {
                                        return InvocationHandler.invokeDefault(proxy, method, args);
                                    }
                                    throw new AssertionError(
                                            "unexpected adapter call " + method.getName());
                            }
                        });
    }

    @Test
    void everyKeptSquareIsTheScalarsTheModReads() {
        GrugSpawnSquares squares = GrugSpawnSquares.scan(toy(), new Object(), 0, 10, 0, 1);

        // z, then y, then x, each from the lowest: the reference's Checker walks its grid so.
        assertEquals(13, squares.count());
        assertSquare(squares, 0, 0, 9, -1);
        assertSquare(squares, 1, -1, 10, -1);
        assertSquare(squares, 2, 1, 10, -1);
        assertSquare(squares, 6, 0, 10, 0);
        assertSquare(squares, 12, 0, 11, 1);
    }

    @Test
    void theHeightLimitSkipsTheSlicesOutsideIt() {
        GrugSpawnSquares squares = GrugSpawnSquares.scan(toy(), new Object(), 0, 0, 0, 1);

        for (int i = 0; i < squares.count(); i++) {
            assertEquals(true, squares.y(i) >= 0, "No square is below the level.");
            assertEquals(true, squares.y(i) < 20, "No square is above the level.");
        }
    }

    @Test
    void aCubeWithNoSpawnSpaceKeepsNothing() {
        ModLoaderAdapter empty =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "isOutOfHeightLimit":
                                        case "isHostileSpawnSpace":
                                            return false;
                                        case "logError":
                                            return null;
                                        default:
                                            if (method.isDefault()) {
                                                return InvocationHandler.invokeDefault(
                                                        proxy, method, args);
                                            }
                                            throw new AssertionError(
                                                    "unexpected adapter call " + method.getName());
                                    }
                                });

        GrugSpawnSquares squares = GrugSpawnSquares.scan(empty, new Object(), 5, 5, 5, 2);

        assertEquals(0, squares.count());
    }

    @Test
    void theFirstCapacityGrowsToAWholeRadius16Cube() {
        ModLoaderAdapter all =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "isOutOfHeightLimit":
                                            return false;
                                        case "isHostileSpawnSpace":
                                            return true;
                                        case "getBlockLight":
                                        case "getSkyLight":
                                            return 3;
                                        case "logError":
                                            return null;
                                        default:
                                            if (method.isDefault()) {
                                                return InvocationHandler.invokeDefault(
                                                        proxy, method, args);
                                            }
                                            throw new AssertionError(
                                                    "unexpected adapter call " + method.getName());
                                    }
                                });

        GrugSpawnSquares squares = GrugSpawnSquares.scan(all, new Object(), 0, 0, 0, 16);

        assertEquals(33 * 33 * 33, squares.count());
        assertEquals(-16, squares.x(0));
        assertEquals(-16, squares.y(0));
        assertEquals(-16, squares.z(0));
        assertEquals(3, squares.blockLight(0));
        assertEquals(3, squares.skyLight(0));
        int last = squares.count() - 1;
        assertEquals(16, squares.x(last));
        assertEquals(16, squares.y(last));
        assertEquals(16, squares.z(last));
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    /**
     * Assertion order is z-major, so a square is named by its position in the list and the three
     * coordinates the scan kept.
     */
    private static void assertSquare(GrugSpawnSquares squares, int i, int x, int y, int z) {
        assertEquals(x, squares.x(i), "x at " + i);
        assertEquals(y, squares.y(i), "y at " + i);
        assertEquals(z, squares.z(i), "z at " + i);
        assertEquals(x + 1, squares.blockLight(i), "block light at " + i);
        assertEquals(15 - y, squares.skyLight(i), "sky light at " + i);
    }
}
