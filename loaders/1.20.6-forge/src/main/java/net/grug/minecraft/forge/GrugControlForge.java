package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.forge.mixin.ChunkMapAccessor;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkProtocol;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * The test-control channel's packet layer on 1.20.6 Forge (#1040, option A): one Forge simple
 * channel, {@code grug:control}, with one message type in both directions whose payload is the
 * opaque byte array of core's {@code GrugControlWire}. Mod authors never see it: it is the plumbing
 * behind client-only host functions.
 *
 * <p>The channel is registered on both sides and is {@code optional()}, so a player without grug
 * can still join a grug server and the other way round. Handlers run on the network thread, never
 * the game thread: the server's answer handler only completes a future that the frozen server
 * thread waits on, which is why the freeze does not have to pump the server's task queue (see
 * {@code GrugControlServer}). The client hands the request to its own game thread with {@code
 * enqueueWork}, which keeps it ordered after the block updates that arrived before it.
 *
 * <p>This class names no client class: a dedicated server refuses to load one. The client half is
 * {@link GrugControlClientForge}, reached only from the client branch of {@link #handle}.
 */
@GrugGenerated(
        "the Forge channel glue of #1040: the multiplayer job runs it, and the coverage gate does"
                + " not count that job; every decision in it lives in core's GrugControlServer")
public final class GrugControlForge {
    /**
     * How long the server's freeze waits for the client's answer. Under the 30 s a connection may
     * stay silent before Netty's read timeout closes it, and well under the watchdog.
     */
    static final long DEFAULT_TIMEOUT_MILLIS = 20_000;

    /** The message, in either direction. */
    @GrugGenerated(
            "the Forge channel glue of #1040: no test the coverage gate counts can reach it, since"
                    + " only the multiplayer job runs it")
    public record Packet(byte[] bytes) {}

    private static SimpleChannel channel;
    private static GrugControlServer server;

    private GrugControlForge() {}

    /** Registers the channel. Called from the mod's constructor, on both sides. */
    public static void init() {
        channel =
                ChannelBuilder.named("grug:control")
                        .networkProtocolVersion(1)
                        .optional()
                        .simpleChannel()
                        .messageBuilder(Packet.class, NetworkProtocol.PLAY)
                        .encoder((packet, buf) -> buf.writeByteArray(packet.bytes()))
                        .decoder(buf -> new Packet(buf.readByteArray()))
                        .consumerNetworkThread(
                                (packet, ctx) -> {
                                    handle(packet.bytes(), ctx);
                                    ctx.setPacketHandled(true);
                                })
                        .add()
                        .build();
        MinecraftForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerLoggedOutEvent event) ->
                        server().onDisconnect(event.getEntity().getUUID()));
    }

    private static void handle(byte[] bytes, CustomPayloadEvent.Context ctx) {
        if (ctx.isServerSide()) {
            // A message whose sender is unknown still goes in: the server fails what waits on it.
            ServerPlayer sender = ctx.getSender();
            server().onMessage(sender == null ? null : sender.getUUID(), bytes);
        } else {
            GrugControlClientForge.onServerMessage(bytes, ctx);
        }
    }

    /** The server end, created on first use on a dedicated server. */
    public static synchronized GrugControlServer server() {
        if (server == null) {
            server = new GrugControlServer(new ServerLink(), timeoutMillis());
        }
        return server;
    }

    private static long timeoutMillis() {
        return GrugControlFaults.intFromEnv(
                "GRUG_CI_CONTROL_TIMEOUT_MILLIS", (int) DEFAULT_TIMEOUT_MILLIS);
    }

    /** Sends from a client to the server it is joined to. */
    static void sendToServer(byte[] bytes) {
        channel.send(new Packet(bytes), PacketDistributor.SERVER.noArg());
    }

    @GrugGenerated(
            "the Forge channel glue of #1040: no test the coverage gate counts can reach it, since"
                    + " only the multiplayer job runs it")
    private static final class ServerLink implements GrugControlServer.Link {
        @Override
        public boolean sendToStarter(Object starter, byte[] message) {
            MinecraftServer minecraft = ServerLifecycleHooks.getCurrentServer();
            ServerPlayer player = minecraft.getPlayerList().getPlayer((UUID) starter);
            if (player == null) return false;
            channel.send(new Packet(message), PacketDistributor.PLAYER.with(player));
            return true;
        }

        /**
         * Any player who has finished login may start a run (first start wins in core). Operator-
         * only starting is a possible later hardening.
         */
        @Override
        public boolean mayStartRun(Object sender) {
            MinecraftServer minecraft = ServerLifecycleHooks.getCurrentServer();
            return minecraft.getPlayerList().getPlayer((UUID) sender) != null;
        }

        /**
         * A server sends the blocks a tick changed at the end of that tick, through each chunk
         * holder, and the tick a test runs in has not ended: the run's calls are made from the
         * server tick event. Doing for every loaded chunk what the end of the tick would do makes
         * the client's copy current before it is asked to capture.
         */
        @Override
        public void flushWorld() {
            for (ServerLevel level : ServerLifecycleHooks.getCurrentServer().getAllLevels()) {
                ChunkMapAccessor chunks = (ChunkMapAccessor) level.getChunkSource().chunkMap;
                int flushed = 0;
                for (ChunkHolder holder : chunks.grug$getChunks()) {
                    LevelChunk chunk = holder.getTickingChunk();
                    if (chunk != null) {
                        holder.broadcastChanges(chunk);
                        flushed++;
                    }
                }
                System.out.println(
                        "[GRUG CONTROL] flushed the changes of "
                                + flushed
                                + " chunks of "
                                + level.dimension().location());
            }
        }
    }
}
