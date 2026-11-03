package net.grug.minecraft.stationapi;

import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.stationapi.server.GrugServerRun;
import net.minecraft.entity.player.PlayerEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * The adapter for a b1.7.3 dedicated server, which is the same loader as {@link StationApiAdapter}
 * in a second process rather than a second mode.
 *
 * <p>It extends the client adapter because everything that is not about which process this is, is
 * the same code: the block registry, the recipes and the inventory and entity host functions have
 * no client in them. The client adapter already loads on a server (it is what {@code InitListener}
 * has always created), so extending it adds no class a server cannot load. What a server cannot
 * inherit is the pair of handles to the level and the player, and the answers about screens and
 * frames, and those are what this replaces. The client-only functions are inherited and each one
 * refuses itself through {@code ModLoaderAdapter.refuseWithoutAClient}.
 *
 * <p>Excluded from coverage because only the dispatch-only multiplayer job launches a server (#167
 * stage 4). See #165.
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class ServerStationApiAdapter extends StationApiAdapter {

    /** A player's eye is this far above their feet. */
    private static final double EYE_HEIGHT = 1.62;

    @Override
    public Object getLevel() {
        return GrugServerRun.world();
    }

    /**
     * The first player who joined.
     *
     * <p>A server has no local player, so a test that wants one to build fixtures around and to
     * right-click with is given one of the connected players. Which one does not matter: the tests
     * share one world and build in a room around the test player, so the choice only decides whose
     * position {@code Test.get_origin} reports. The inherited origin goes through {@link
     * #testPlayerEye}, so choosing the player here is all a server has to do.
     */
    @Override
    public Object testPlayer() {
        // Deliberately an untyped list: Fabric Loader will not define a class the game marked
        // server-only in a client, so no reference the verifier has to resolve may name one here.
        List<?> players = GrugServerRun.players();
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * The eye of the test player. A server player keeps the feet in {@code y} and a client keeps
     * the eye, so the eye is rebuilt from the bottom of the bounding box, rounded to a millionth to
     * shed the arithmetic's last bits, which is what the client's {@code y} is.
     */
    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        return new Vec3(
                player.x, Math.rint(player.boundingBox.minY * 1e6) / 1e6 + EYE_HEIGHT, player.z);
    }

    /** Nothing to close: the screen belongs to a client, and a server has none. */
    @Override
    public void closeOpenScreenForTest() {}

    /** No screen can be open on a server. */
    @Override
    public boolean isScreenOpenForTest() {
        return false;
    }

    /**
     * Ready once the world around the player is loaded and the player has landed. The test origin
     * is read from the player's position, and a player still falling when the first test starts
     * gives an origin whose height differs from run to run (#1052).
     */
    @Override
    public boolean isWorldReady(Object playerObj) {
        return super.isWorldReady(playerObj) && ((PlayerEntity) playerObj).onGround;
    }

    /** Settled as soon as a test asks: no client frame or block packet is owed to anyone. */
    @Override
    public boolean isWorldSettled() {
        return true;
    }

    /** The server has no frames, so there is nothing to forget. */
    @Override
    public void forgetSettledFrames() {}

    /** The tests listed in {@code server-unsupported-tests.txt}, which says why. */
    @Override
    public Set<String> serverUnsupportedTests() {
        try (InputStream in =
                ServerStationApiAdapter.class.getResourceAsStream(
                        "/server-unsupported-tests.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "server-unsupported-tests.txt is not in the layer.");
            }
            return GrugTestRunner.parseTestList(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read server-unsupported-tests.txt.", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** The client's cap of ticks for one frame, which the server takes as extra ticks per tick. */
    @Override
    public double maxGameSpeed() {
        return ModLoaderAdapter.CLIENT_TICKS_PER_FRAME;
    }

    @Override
    public boolean setGameSpeed(double speed) {
        GrugServerRun.setGameSpeed(GrugGameSpeed.wholeSteps(speed));
        return true;
    }
}
