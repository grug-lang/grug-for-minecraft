package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.Vec3;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Covers the rules every loader's {@code TestTickContext.get_origin} answer goes through: the test
 * player's eye plus three blocks, the eye one eye height above a room's feet anchor, and no origin
 * without a player.
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

    @Test
    void anEyeIsTheOriginLessTheLift() {
        Vec3 origin = new Vec3(1.5, 67.62, -3.5);
        Vec3 eye = GrugTestOrigin.eyeOf(origin);

        assertEquals(1.5, eye.x(), 0.0);
        assertEquals(64.62, eye.y(), 1e-9);
        assertEquals(-3.5, eye.z(), 0.0);
        assertEquals(origin, GrugTestOrigin.of(eye));
    }

    @Test
    void aTeleportedClientEyeNeverPutsTheBoxUnderTheFeet() {
        double clientEyeHeight = (double) 1.62F;
        for (double feet : new double[] {64.0, 63.5, 70.94899540560743, -12.25, 255.0, 0.0}) {
            double eye = GrugTestOrigin.clientEye(feet);

            assertTrue(eye - clientEyeHeight >= feet, "box bottom under the feet at " + feet);
            assertEquals(feet + clientEyeHeight, eye, 1e-12);
        }
        java.util.Random random = new java.util.Random(1688);
        for (int i = 0; i < 1_000_000; i++) {
            double feet = random.nextDouble() * 600.0 - 300.0;
            assertTrue(GrugTestOrigin.clientEye(feet) - clientEyeHeight >= feet, "at " + feet);
        }
        // The decimal eye the packet used to carry is the one that put the box under the floor.
        assertTrue(64.0 + 1.62 - clientEyeHeight < 64.0);
    }

    /**
     * The defect this guards against was two copies of the eye height, so this test names the
     * game's own value itself: the {@code float} 1.62 widened is 1.6200000047683716, while the
     * decimal 1.62 is 5e-9 under it and changes a joined camera's bits (#1629, #1662).
     */
    @Test
    void theEyeHeightIsTheGamesOwnFloatAndNotTheDecimal() {
        assertEquals((double) 1.62F, GrugTestOrigin.EYE_HEIGHT, 0.0);
        assertTrue(GrugTestOrigin.EYE_HEIGHT != 1.62, "the eye height is the decimal 1.62");
    }

    /**
     * A capture pin that names the eye of a client whose API takes the feet recovers those feet and
     * lands the camera on the eye the request named. The feet here are the ones a room is built
     * from: the eighth-block grid and the awkward heights the other tests use, both of which the
     * game's own addition reproduces exactly. It does not claim an arbitrary double, which {@link
     * GrugTestOrigin#feetOfEye} says is one unit in the last place off at worst. The pin is in a
     * loader, so this drives the arithmetic it calls.
     */
    @Test
    void theCapturePinLandsTheCameraOnTheRoomEye() {
        // Written out here rather than through the constant, so a value that drifts back to the
        // decimal 1.62 fails this as well as theEyeHeightIsTheGamesOwnFloatAndNotTheDecimal.
        assertEquals(65.62000000476837, GrugTestOrigin.clientEye(64.0), 0.0);
        assertEquals(64.0, GrugTestOrigin.feetOfEye(65.62000000476837), 0.0);

        for (double feet :
                new double[] {64.0, 63.5, 70.94899540560743, -12.25, 255.0, 0.0, 71.0, -23.5}) {
            assertThePinLandsOnTheEye(feet);
        }
        for (int eighths = -300 * 8; eighths <= 600 * 8; eighths++) {
            assertThePinLandsOnTheEye(eighths / 8.0);
        }
    }

    /** Drives the two directions and the room's own eye at the feet the game built. */
    private static void assertThePinLandsOnTheEye(double feet) {
        double eye = GrugTestOrigin.clientEye(feet);

        assertEquals(feet, GrugTestOrigin.feetOfEye(eye), 0.0, "the pin's feet at " + feet);
        assertEquals(eye, GrugTestOrigin.eyeOfFeet(0.5, feet, 0.5).y(), 0.0, "the room's eye");
        assertEquals(
                eye,
                GrugTestOrigin.clientEye(GrugTestOrigin.feetOfEye(eye)),
                0.0,
                "the pin's camera back at the eye");
    }

    @Test
    void anEyeIsOneEyeHeightAboveTheFeetOfTheRoom() {
        Vec3 eye = GrugTestOrigin.eyeOfFeet(1.5, 64.0, -3.5);

        assertEquals(1.5, eye.x(), 0.0);
        assertEquals(64.0 + (double) 1.62F, eye.y(), 0.0);
        assertEquals(-3.5, eye.z(), 0.0);
        assertEquals(eye, GrugTestOrigin.eyeOf(GrugTestOrigin.of(eye)));
    }

    @Test
    void theAdapterReportsItsOwnCameraFromThePlayerItChooses() {
        Object player = new Object();
        Vec3 eye = new Vec3(5.5, 70.0, 6.5);

        assertEquals(eye, adapterWithPlayer(player, eye).testCamera());
        assertNull(adapterWithPlayer(null, null).testCamera());
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
