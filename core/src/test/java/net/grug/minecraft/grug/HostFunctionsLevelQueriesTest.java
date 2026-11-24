package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * Covers the two Level queries a pipe makes of a neighbour, which the loader and the block
 * declarations answer rather than the script: {@code is_pipe}, which reads set_pipe's declaration
 * instead of comparing block names, and {@code is_inventory}, which is the game's own inventory
 * test. The stale-level answers are covered by {@link HostFunctionsStaleIdTest}, since a stale id
 * is the same refusal for every Level method.
 */
class HostFunctionsLevelQueriesTest {

    private static final String PIPE = "grug:probe_pipe";
    private static final String PLAIN = "grug:probe_plain";

    @AfterEach
    void clearState() throws Exception {
        GrugDeclarations.declaredBlocks.clear();
        GrugEntities.entityData.clear();
        adapterField().set(null, null);
    }

    @Test
    void onlyADeclaredPipeIsAPipe() throws Exception {
        GrugBlockData pipe = new GrugBlockData(PIPE);
        pipe.pipe = true;
        GrugDeclarations.declaredBlocks.put(PIPE, pipe);
        GrugDeclarations.declaredBlocks.put(PLAIN, new GrugBlockData(PLAIN));
        installAdapter();

        long level = GrugEntities.addEntity(GrugEntityType.Level, new Object());

        assertTrue(HostFunctions.Level_is_pipe(level, 1, 2, 3), "x = 1 is the declared pipe.");
        assertFalse(HostFunctions.Level_is_pipe(level, 4, 5, 6), "A block that declared nothing.");
        assertFalse(
                HostFunctions.Level_is_pipe(level, 7, 8, 9),
                "A block no mod declared, such as a vanilla one.");
        assertTrue(
                HostFunctions.Level_is_pipe(level, 1.75, 2.75, 3.75),
                "The position floors to the block, as Level.get_block does.");
    }

    @Test
    void theInventoryAnswerIsTheLoaders() throws Exception {
        installAdapter();

        long level = GrugEntities.addEntity(GrugEntityType.Level, new Object());

        assertTrue(HostFunctions.Level_is_inventory(level, 1, 2, 3), "x = 1 is an inventory.");
        assertFalse(HostFunctions.Level_is_inventory(level, 4, 5, 6), "A block that is not one.");
    }

    /** Installs an adapter that answers these two queries for the coordinates the tests use. */
    private static void installAdapter() throws Exception {
        adapterField()
                .set(
                        null,
                        Proxy.newProxyInstance(
                                ModLoaderAdapter.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    double x =
                                            args != null && args.length > 1 ? (double) args[1] : 0;
                                    if (method.getName().equals("getBlock")) {
                                        if (x < 3) return PIPE;
                                        if (x < 6) return PLAIN;
                                        return "minecraft:stone";
                                    }
                                    if (method.getName().equals("isInventory")) return x < 3;
                                    Class<?> answer = method.getReturnType();
                                    if (answer == boolean.class) return false;
                                    if (answer == long.class) return 0L;
                                    if (answer == int.class) return 0;
                                    if (answer == double.class) return 0.0;
                                    return null;
                                }));
    }

    private static Field adapterField() throws NoSuchFieldException {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        return field;
    }
}
