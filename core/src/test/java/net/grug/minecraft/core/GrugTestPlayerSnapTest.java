package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Covers where the runner snaps the test player to, and the side it does not snap on. See #609. */
class GrugTestPlayerSnapTest {

    @Test
    void snapsToTheCentreOfTheBlockWhateverTheStartingOffset() {
        // The saved world starts the player at 0.8897..., so a snap that only rounded would leave
        // them off-centre and a fixture would not sit where a capture expects it on every loader.
        assertEquals(0.5, GrugTestPlayerSnap.blockCentre(0.8897178721096088));
        assertEquals(52.5, GrugTestPlayerSnap.blockCentre(52.100027604011224));
        assertEquals(0.5, GrugTestPlayerSnap.blockCentre(0.5));
        assertEquals(-0.5, GrugTestPlayerSnap.blockCentre(-0.1));
        assertEquals(-1.5, GrugTestPlayerSnap.blockCentre(-1.2));
    }

    @Test
    void snapsOnAClient() throws Throwable {
        AtomicInteger snaps = new AtomicInteger();

        adapter(GrugSide.CLIENT, snaps).snapPlayerForTest();

        assertEquals(1, snaps.get());
    }

    @Test
    void skipsTheSnapOnADedicatedServer() throws Throwable {
        // A dedicated server has no local player to move, so the runner skips the snap there rather
        // than reporting a defect every time a test starts.
        AtomicInteger snaps = new AtomicInteger();

        adapter(GrugSide.DEDICATED_SERVER, snaps).snapPlayerForTest();

        assertEquals(0, snaps.get());
    }

    @Test
    void answersWhichSidesHaveAPlayerToSnap() {
        assertTrue(GrugTestPlayerSnap.canSnap(GrugSide.CLIENT));
        assertFalse(GrugTestPlayerSnap.canSnap(GrugSide.DEDICATED_SERVER));
    }

    @Test
    void clearsTheInventoryOfTheTestPlayer() throws Throwable {
        // The runner empties the hotbar before every test, so an item an earlier test or an earlier
        // pass of a repeated run left there never reaches a capture. See #930.
        List<Object> cleared = new ArrayList<>();
        Object player = new Object();

        inventoryAdapter(player, cleared).resetPlayerInventoryForTest();

        assertEquals(List.of(player), cleared);
    }

    @Test
    void leavesTheInventoryAloneWhenThereIsNoPlayer() throws Throwable {
        List<Object> cleared = new ArrayList<>();

        inventoryAdapter(null, cleared).resetPlayerInventoryForTest();

        assertEquals(List.of(), cleared);
    }

    private static ModLoaderAdapter inventoryAdapter(Object player, List<Object> cleared) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        ModLoaderAdapter.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("testPlayer")) return player;
                            if (method.getName().equals("clearInventoryOf")) {
                                cleared.add(args[0]);
                                return null;
                            }
                            return InvocationHandler.invokeDefault(proxy, method, args);
                        });
    }

    /** An adapter that says which side it is on and counts the client snaps it is asked for. */
    private static ModLoaderAdapter adapter(GrugSide side, AtomicInteger snaps) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        ModLoaderAdapter.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getSide")) return side;
                            if (method.getName().equals("snapPlayerForTestOnClient")) {
                                snaps.incrementAndGet();
                                return null;
                            }
                            return InvocationHandler.invokeDefault(proxy, method, args);
                        });
    }
}
