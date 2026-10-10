package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the box the room build hands to {@link ModLoaderAdapter#clearItemEntities}.
 *
 * <p>This is a Java test because a grug script cannot assert the property: the test API has no
 * floor, so a script cannot turn the player's position into the block coordinates the bounds are
 * made of, and whether an item in the last cleared block is swept is exactly a question about those
 * coordinates. See #254.
 */
class GrugTestBoxTest {

    /**
     * Runs the build against a recording adapter: every block it clears, and the box it hands to
     * the sweep. The adapter is a proxy because {@link ModLoaderAdapter} is wide and the build uses
     * three of its methods.
     */
    private static final class RecordingAdapter implements InvocationHandler {
        private final List<int[]> cleared = new ArrayList<>();
        private double[] box;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "isAir":
                    return false;
                case "placeBlock":
                    if ("minecraft:air".equals(args[4])) {
                        cleared.add(
                                new int[] {
                                    (int) (double) (Double) args[1],
                                    (int) (double) (Double) args[2],
                                    (int) (double) (Double) args[3]
                                });
                    }
                    return null;
                case "clearItemEntities":
                    box =
                            new double[] {
                                (double) (Double) args[1],
                                (double) (Double) args[2],
                                (double) (Double) args[3],
                                (double) (Double) args[4],
                                (double) (Double) args[5],
                                (double) (Double) args[6]
                            };
                    return null;
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (type == void.class) {
                return null;
            }
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == double.class) {
                return 0.0d;
            }
            if (type == float.class) {
                return 0.0f;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            return (char) 0;
        }
    }

    @Test
    void theSweepBoxReachesTheLastClearedBlock() {
        RecordingAdapter recording = new RecordingAdapter();
        ModLoaderAdapter adapter =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                ModLoaderAdapter.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                recording);

        GrugTestBox.build(adapter, new Object(), 10.5, 70.0, 20.5, 3);

        assertTrue(recording.box != null, "the build should hand the sweep a box");
        for (int[] block : recording.cleared) {
            // The center of a block the build cleared has to sit inside the sweep box, or an item
            // the clearing dropped in that block is left behind. A block covers [x, x + 1), so
            // this is what fails when the bounds stop at the block's own coordinate.
            assertTrue(
                    block[0] + 0.5 >= recording.box[0]
                            && block[0] + 0.5 <= recording.box[3]
                            && block[1] + 0.5 >= recording.box[1]
                            && block[1] + 0.5 <= recording.box[4]
                            && block[2] + 0.5 >= recording.box[2]
                            && block[2] + 0.5 <= recording.box[5],
                    "cleared block ("
                            + block[0]
                            + ","
                            + block[1]
                            + ","
                            + block[2]
                            + ") is outside the sweep box");
        }
    }
}
