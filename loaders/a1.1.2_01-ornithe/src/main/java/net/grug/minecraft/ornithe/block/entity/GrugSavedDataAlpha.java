package net.grug.minecraft.ornithe.block.entity;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * Sends a grug block entity's saved data to the joined clients on an Alpha dedicated server, which
 * is where {@link GrugSavedDataSync} asks.
 *
 * <p>The players are reached through the world, and each player's connection by type: this layer is
 * compiled against the a1.1.2_01 client, which has no server classes, so the server's player
 * manager and player handler cannot be named here. The fields are found the way {@code
 * GrugControlAlpha} finds a connection, by what they hold rather than by what they are called,
 * since the game's names are obfuscated at run time.
 *
 * <p>A singleplayer Alpha game has no server and no second copy of the world, so it installs none:
 * the copy the script writes is the one the frame pass reads.
 */
@GrugGenerated(
        "only the dedicated-server entrypoint installs this transport, and the coverage gate counts"
                + " the pull request's client runs, not the dispatch-only server legs; its player"
                + " list is reachable only through the game's own classes, which this layer is not"
                + " compiled against")
public final class GrugSavedDataAlpha implements GrugSavedDataSync.Transport {

    @Override
    public boolean onServer(Object blockEntity) {
        return GrugCore.getAdapter().getSide() == GrugSide.DEDICATED_SERVER;
    }

    @Override
    public boolean frameOnly(Object blockEntity) {
        GrugBlockEntity entity = (GrugBlockEntity) blockEntity;
        if (entity.world == null) {
            return false;
        }
        Block block = Block.BY_ID[entity.world.getBlock(entity.x, entity.y, entity.z)];
        return block instanceof GrugBlock
                && ((GrugBlock) block).drawsCustomGeometry()
                && !((GrugBlock) block).drawsChunkGeometry();
    }

    @Override
    public void send(Object blockEntity) {
        World world = GrugServerRun.world();
        if (world == null) {
            return;
        }
        GrugSavedDataPacket packet = new GrugSavedDataPacket((GrugBlockEntity) blockEntity);
        for (Object player : new ArrayList<>(world.players)) {
            Connection connection = connectionOf(player);
            if (connection == null) {
                connection = GrugControlAlpha.connectionFor((PlayerEntity) player);
            }
            if (connection != null) {
                connection.send(packet);
            }
        }
    }

    /** The connection a player holds, or null before it has one. */
    private static Connection connectionOf(Object player) {
        for (Class<?> type = player.getClass();
                type != null && !isJdk(type);
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == Connection.class) {
                    return (Connection) get(field, player);
                }
                if (PacketHandler.class.isAssignableFrom(field.getType())) {
                    Connection connection = connectionIn(get(field, player));
                    if (connection != null) {
                        return connection;
                    }
                }
            }
        }
        return null;
    }

    /** The connection a network handler holds, or null. */
    private static Connection connectionIn(Object holder) {
        if (holder == null) {
            return null;
        }
        for (Class<?> type = holder.getClass();
                type != null && !isJdk(type);
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == Connection.class) {
                    field.setAccessible(true);
                    return (Connection) get(field, holder);
                }
            }
        }
        return null;
    }

    private static Object get(Field field, Object holder) {
        try {
            return field.get(holder);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + field + " to find the connection", e);
        }
    }

    /** Whether the walk has left the game's classes, whose fields are not ours to open. */
    private static boolean isJdk(Class<?> type) {
        return type.getName().startsWith("java.");
    }
}
