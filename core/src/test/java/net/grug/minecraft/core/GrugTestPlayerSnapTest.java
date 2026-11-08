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

    /**
     * The server half's snap writes the block centre and a level view, and the y the floor hold
     * owns rather than a height of its own. A dedicated server has no client player object in the
     * adapter, so this state is the only record of where the player was (#1161).
     */
    @Test
    void serverSnapWritesTheBlockCentreAndALevelView() {
        GrugTestPlayerSnap.ServerSave save = new GrugTestPlayerSnap.ServerSave();
        save.save(12.3, 64.0, -7.8, 90.0F, -12.5F);

        assertEquals(12.5, save.snappedX());
        assertEquals(64.0, save.snappedY());
        assertEquals(-7.5, save.snappedZ());
        assertEquals(0.0F, save.snappedYaw());
        assertEquals(0.0F, save.snappedPitch());
    }

    /**
     * Every test end asks every loader to restore, whether the test passed, failed or aborted, so
     * the server's saved copy is what the next test inherits: exactly the position and view the
     * snap found, not the snapped half block or a view a test turned (#1161).
     */
    @Test
    void serverRestorePutsBackExactlyWhatTheSnapFound() {
        GrugTestPlayerSnap.ServerSave save = new GrugTestPlayerSnap.ServerSave();
        save.save(12.3, 64.0, -7.8, 90.0F, -12.5F);

        assertEquals(12.3, save.savedX());
        assertEquals(64.0, save.savedY());
        assertEquals(-7.8, save.savedZ());
        assertEquals(90.0F, save.savedYaw());
        assertEquals(-12.5F, save.savedPitch());

        save.clear();

        assertFalse(save.isSaved());
    }

    /** A server that has not snapped yet, or has already restored, has nothing to put back. */
    @Test
    void serverRestoreKnowsNothingBeforeASnap() {
        GrugTestPlayerSnap.ServerSave save = new GrugTestPlayerSnap.ServerSave();

        assertFalse(save.isSaved());

        save.save(0.0, 64.0, 0.0, 0.0F, 0.0F);
        save.clear();
        assertFalse(save.isSaved());

        save.save(0.0, 64.0, 0.0, 0.0F, 0.0F);
        assertTrue(save.isSaved());
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
