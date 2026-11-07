package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Covers where the runner snaps the test player to, and which side's player it moves. See #609. */
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
    void snapsTheLocalPlayerOnAClient() throws Throwable {
        AtomicInteger clientSnaps = new AtomicInteger();
        AtomicInteger serverSnaps = new AtomicInteger();

        adapter(GrugSide.CLIENT, clientSnaps, serverSnaps).snapPlayerForTest();

        assertEquals(1, clientSnaps.get());
        assertEquals(0, serverSnaps.get());
    }

    @Test
    void snapsTheServersPlayerCopyOnADedicatedServer() throws Throwable {
        // The server has no local player, but it does have its copy of the joined player, which is
        // the player the room, the origin and the capture are built around (#1161).
        AtomicInteger clientSnaps = new AtomicInteger();
        AtomicInteger serverSnaps = new AtomicInteger();

        adapter(GrugSide.DEDICATED_SERVER, clientSnaps, serverSnaps).snapPlayerForTest();

        assertEquals(0, clientSnaps.get());
        assertEquals(1, serverSnaps.get());
    }

    @Test
    void leavesADedicatedServerWithoutAServerSnapAlone() throws Throwable {
        // The server half's default moves nothing, so a server whose adapter has not wired a
        // player copy fails no test at the start of one.
        AtomicInteger clientSnaps = new AtomicInteger();

        adapter(GrugSide.DEDICATED_SERVER, clientSnaps).snapPlayerForTest();

        assertEquals(0, clientSnaps.get());
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

    /**
     * An adapter that says which side it is on and counts the client snaps. It leaves the server
     * half to its default, which is the no-op a server adapter without a player copy uses.
     */
    private static ModLoaderAdapter adapter(GrugSide side, AtomicInteger clientSnaps) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        ModLoaderAdapter.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getSide")) return side;
                            if (method.getName().equals("snapPlayerForTestOnClient")) {
                                clientSnaps.incrementAndGet();
                                return null;
                            }
                            return InvocationHandler.invokeDefault(proxy, method, args);
                        });
    }

    /** An adapter that says which side it is on and counts both halves of the snap. */
    private static ModLoaderAdapter adapter(
            GrugSide side, AtomicInteger clientSnaps, AtomicInteger serverSnaps) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        ModLoaderAdapter.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getSide")) return side;
                            if (method.getName().equals("snapPlayerForTestOnClient")) {
                                clientSnaps.incrementAndGet();
                                return null;
                            }
                            if (method.getName().equals("snapPlayerForTestOnServer")) {
                                serverSnaps.incrementAndGet();
                                return null;
                            }
                            return InvocationHandler.invokeDefault(proxy, method, args);
                        });
    }
}
