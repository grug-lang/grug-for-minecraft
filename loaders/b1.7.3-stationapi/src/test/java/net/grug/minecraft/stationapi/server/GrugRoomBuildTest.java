package net.grug.minecraft.stationapi.server;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.grug.minecraft.stationapi.ServerStationApiAdapter;
import net.grug.minecraft.stationapi.StationApiAdapter;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * Which method a room build goes through, and so which world it is prepared in (#1847).
 *
 * <p>The b1.7.3 server used to override {@code buildTestBox} and call the room builder directly, so
 * its build skipped the settle reset and the clock move the client's build makes, and a room could
 * be built under night lighting. The build now lives in one method that prepares the world first,
 * and the server adds only the feet it anchors the room to. This pins that shape: a room built on
 * the server goes through the same build as the client's, so no path can skip the preparation. The
 * lighting difference itself can only be seen in a game run, where a test that leaves the world at
 * night and then builds a room is captured.
 */
class GrugRoomBuildTest {
    @Test
    void theServerBuildsItsRoomThroughThePreparedSharedBuild() throws NoSuchMethodException {
        Method build = ServerStationApiAdapter.class.getMethod("buildTestBox", int.class);

        assertSame(
                StationApiAdapter.class,
                build.getDeclaringClass(),
                "a server room build must go through the shared build, which prepares the world");
        assertThrows(
                NoSuchMethodException.class,
                () -> ServerStationApiAdapter.class.getDeclaredMethod("buildTestBox", int.class),
                "an override that calls the room builder directly skips the preparation");
    }
}
