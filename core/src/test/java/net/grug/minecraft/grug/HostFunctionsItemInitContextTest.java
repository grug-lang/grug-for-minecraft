package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the methods an item's {@code init()} reaches through its context on a loader that glides
 * and on one that does not, and the refusal of a context kept past its call, which a grug test
 * cannot make: the entity that ran {@code init()} is destroyed with it.
 */
class HostFunctionsItemInitContextTest {

    private static final String ITEM = "grug:declared";

    private Object savedAdapter;

    @BeforeEach
    void clearState() throws Exception {
        savedAdapter = adapterField().get(null);
        reset();
    }

    @AfterEach
    void restore() throws Exception {
        adapterField().set(null, savedAdapter);
        reset();
    }

    private static void reset() {
        GrugDeclarations.closeItemInitContext();
        GrugEntities.entityData.clear();
        drain();
    }

    private static Field adapterField() throws NoSuchFieldException {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        return field;
    }

    private static void loaderThatGlides(boolean glides) throws Exception {
        adapterField()
                .set(
                        null,
                        Proxy.newProxyInstance(
                                HostFunctionsItemInitContextTest.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> glides));
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    private static long contextId(GrugItemData item) {
        return GrugEntities.addEntity(
                GrugEntityType.ItemInitContext, GrugDeclarations.openItemInitContext(item));
    }

    @Test
    void aLoaderThatGlidesDeclaresTheGliderAndTheRocket() throws Exception {
        loaderThatGlides(true);
        GrugItemData item = new GrugItemData(ITEM);
        long context = contextId(item);

        assertTrue(HostFunctions.ItemInitContext_supports_gliding(context));
        HostFunctions.ItemInitContext_set_glider(context, 432);
        HostFunctions.ItemInitContext_set_firework_rocket(context);

        assertEquals(432, item.gliderMaxDamage);
        assertTrue(item.fireworkRocket);
        assertTrue(drain().isEmpty(), "A legal call reports nothing.");
    }

    @Test
    void aLoaderThatCannotGlideReportsTheDeclarationsAndKeepsAPlainItem() throws Exception {
        loaderThatGlides(false);
        GrugItemData item = new GrugItemData(ITEM);
        long context = contextId(item);

        assertFalse(HostFunctions.ItemInitContext_supports_gliding(context));
        HostFunctions.ItemInitContext_set_glider(context, 432);
        HostFunctions.ItemInitContext_set_firework_rocket(context);

        assertEquals(0, item.gliderMaxDamage);
        assertFalse(item.fireworkRocket);
        assertEquals(2, drain().size());
    }

    @Test
    void aContextFromAnEarlierCallIsRefused() throws Exception {
        loaderThatGlides(true);
        GrugItemData first = new GrugItemData(ITEM);
        long stale = contextId(first);
        GrugDeclarations.closeItemInitContext();
        GrugItemData second = new GrugItemData(ITEM);
        contextId(second);

        HostFunctions.ItemInitContext_set_glider(stale, 432);
        HostFunctions.ItemInitContext_set_firework_rocket(stale);
        assertFalse(HostFunctions.ItemInitContext_supports_gliding(stale));

        assertEquals(0, first.gliderMaxDamage);
        assertEquals(0, second.gliderMaxDamage);
        assertEquals(3, drain().size());
    }
}
