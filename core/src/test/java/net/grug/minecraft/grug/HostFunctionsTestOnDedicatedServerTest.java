package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * Covers {@code Test.on_dedicated_server}, whose true answer is the one a dedicated server gives,
 * which only the dispatch-only multiplayer job runs (#167). Without this the whole function could
 * regress to always false and no run would go red, because a test that asked it would silently skip
 * the comparison that only has a second copy on a server (#1240).
 *
 * <p>The side is the loader's own answer, installed as the other tests that reach {@code
 * GrugCore.getAdapter()} install one, so this proves the host function reads the adapter rather
 * than a constant a run could pin on either side.
 */
class HostFunctionsTestOnDedicatedServerTest {

    private Object savedAdapter;

    @BeforeEach
    void saveAdapter() throws Exception {
        savedAdapter = adapterField().get(null);
    }

    @AfterEach
    void restoreAdapter() throws Exception {
        adapterField().set(null, savedAdapter);
    }

    @Test
    void answersTrueWhenTheLoaderIsADedicatedServer() throws Exception {
        install(GrugSide.DEDICATED_SERVER);

        assertTrue(HostFunctions.Test_on_dedicated_server());
    }

    @Test
    void answersFalseWhenTheLoaderIsAClient() throws Exception {
        install(GrugSide.CLIENT);

        assertFalse(HostFunctions.Test_on_dedicated_server());
    }

    private static Field adapterField() throws NoSuchFieldException {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        return field;
    }

    /**
     * Installs an adapter that says which side it is on, and refuses any other call: the host
     * function asks the side only, so a test that reached further would be covered elsewhere.
     */
    private void install(GrugSide side) throws Exception {
        adapterField()
                .set(
                        null,
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getSide")) return side;
                                    throw new AssertionError(
                                            "the side answer reached " + method.getName());
                                }));
    }
}
