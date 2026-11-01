package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.grug.minecraft.grug.Vec3;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Covers the one rule every loader's {@code Test.get_origin} answer goes through: the test player's
 * eye plus three blocks, and no origin without a player.
 */
class GrugTestOriginTest {

    @Test
    void liftsTheEyeThreeBlocksAndKeepsItsColumn() {
        Vec3 origin = GrugTestOrigin.of(new Vec3(1.5, 64.62, -3.5));

        assertEquals(1.5, origin.x(), 0.0);
        assertEquals(67.62, origin.y(), 1e-9);
        assertEquals(-3.5, origin.z(), 0.0);
    }

    @Test
    void theAdapterDerivesItsOriginFromThePlayerItChooses() {
        Object player = new Object();
        Vec3 eye = new Vec3(5.5, 70.0, 6.5);

        assertEquals(GrugTestOrigin.of(eye), adapterWithPlayer(player, eye).getTestOrigin());
        assertNull(adapterWithPlayer(null, null).getTestOrigin());
    }

    /**
     * A stand-in adapter that answers a player and its eye, so the default {@link
     * ModLoaderAdapter#getTestOrigin()} is the code that runs. Its other methods answer null, which
     * is enough for the origin and keeps a game out of this test.
     */
    private static ModLoaderAdapter adapterWithPlayer(Object player, Vec3 eye) {
        InvocationHandler handler =
                (proxy, method, args) -> {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    if (method.getName().equals("testPlayer")) return player;
                    if (method.getName().equals("testPlayerEye")) return eye;
                    return null;
                };
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        GrugTestOriginTest.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        handler);
    }
}
