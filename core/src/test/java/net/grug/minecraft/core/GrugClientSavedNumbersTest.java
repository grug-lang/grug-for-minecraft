package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.grug.minecraft.grug.GrugSavedNumbers;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Covers the read of this process's own copy of a saved number. */
class GrugClientSavedNumbersTest {

    /** A block entity that saves numbers, which is all the read needs of one. */
    private static final class Saving implements GrugSavedNumbers.Holder {
        @Override
        public void markSavedNumbersChanged() {}
    }

    @Test
    void aReadAnswersTheNumberThisProcessesCopyHolds() {
        Object level = new Object();
        Saving blockEntity = new Saving();
        GrugSavedNumbers.set(blockEntity, "frame_marker", 3.0);
        List<String> seen = new ArrayList<>();
        ModLoaderAdapter adapter =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                ModLoaderAdapter.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("clientLevel")) {
                                        // The read asks the client's own copy, and the default
                                        // answer is whatever the loader's getLevel() says, which
                                        // is the same thing on every loader whose getLevel() is
                                        // the client's own world.
                                        seen.add("clientLevel");
                                        return InvocationHandler.invokeDefault(proxy, method, args);
                                    }
                                    if (method.getName().equals("getLevel")) {
                                        seen.add("getLevel");
                                        return level;
                                    }
                                    if (method.getName().equals("getBlockEntity")) {
                                        return args[0] == level
                                                        && (double) args[1] == 1.0
                                                        && (double) args[2] == 2.0
                                                        && (double) args[3] == 3.0
                                                ? blockEntity
                                                : null;
                                    }
                                    return method.getReturnType() == boolean.class
                                            ? Boolean.FALSE
                                            : method.getReturnType() == double.class ? 0.0 : null;
                                });

        assertEquals(3.0, GrugClientSavedNumbers.read(adapter, 1, 2, 3, "frame_marker"));
        assertEquals(List.of("clientLevel", "getLevel"), seen);
        assertEquals(0.0, GrugClientSavedNumbers.read(adapter, 1, 2, 3, "another"));
        assertEquals(
                0.0,
                GrugClientSavedNumbers.read(adapter, 4, 5, 6, "frame_marker"),
                "a position the copy holds no block entity at reads 0");

        // A client with no world yet holds no copy to read.
        ModLoaderAdapter noWorld =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                ModLoaderAdapter.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> null);
        assertEquals(0.0, GrugClientSavedNumbers.read(noWorld, 1, 2, 3, "frame_marker"));
    }
}
