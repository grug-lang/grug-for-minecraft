package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugControlFaults;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugLinkArms;
import net.grug.minecraft.forge.mixin.ChunkMapAccessor;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
 * <p>The protocol version is 12 since the settle ask carries the light of the box the server last
 * sent a joined client, with a third answer for a client whose own copy holds a different field
 * (#1695), so a pair built before and after must refuse each other. Version 11 was the world
 * overlay calls taking call code 23, version 10 the call code 22 and version 9 the call code 21.
 * Version 8 was the forced-normalization call (#1040). Version 7 was a client's clear of the
 * server's cursor (#1389). Version 6 was a player's own give being accepted from any client and
 * checked for operator status (#882). It was 5 since the screen-open call answers a screen's
 * identity as a number and the screen batch holds five numbers (#1289). Version 4 was the overlay
 * reads joining the wire and a give naming its item (#1099): the wire carries calls 14 and 15, and
 * a give message carries a name instead of a list index, so a client and a server built from
 * different commits must refuse each other rather than misread each other's messages. Version 3 was
 * the client-light call (#1207), whose answer is a section's light fingerprint as a number rather
 * than a value byte, and version 2 was the screen calls (#1094), which added a third number to the
 * call request and a numbered screen measurement as an answer.
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
public final class GrugControlForge {
    /**
     * How long the server's freeze waits for the client's answer. Under the 30 s a connection may
     * stay silent before Netty's read timeout closes it, and well under the watchdog.
     */
    static final long DEFAULT_TIMEOUT_MILLIS = 20_000;

    /** The message, in either direction. */
    public record Packet(byte[] bytes) {}

    private static SimpleChannel channel;
    private static GrugControlServer server;

    private GrugControlForge() {}

    /** Registers the channel. Called from the mod's constructor, on both sides. */
    public static void init() {
        channel =
                ChannelBuilder.named("grug:control")
                        // The control calls and messages a build carries, so a mixed pair refuses
                        // the channel at connect instead of failing on a call one of them does not
                        // have (#1207, #1099). Every branch that adds or changes one bumps this
                        // again: #1425 took 7 for the cursor clear, #1430 took 8 for the forwarded
                        // forced normalization, #1449 took 9 with the call code 21, #1441 took 10
                        // with the call code 22 and the world overlay calls took 11 with the call
                        // code 23. #1705 took 12 for its SETTLE_LIGHT_DIVERGED answer to the
                        // world-settled call, and #1393 took 13 for the click barrier the client
                        // sends behind the game's own click packet, so this sits at 14: past every
                        // open pull request's number, because two builds that call themselves 13
                        // while carrying different message sets silently disagree, and a client
                        // that never sends the barrier would instead trip the server's wait.
                        .networkProtocolVersion(14)
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
        MinecraftForge.EVENT_BUS.addListener(GrugControlForge::onLoggedOut);
    }

    /** Tells core's server that a player left, so what waits on that player's answer fails. */
    @GrugGenerated(
            "a player logs out of the dedicated server only when a client leaves, which no test can"
                    + " make happen without ending the run it is part of; the decision is core's"
                    + " GrugControlServer.onDisconnect")
    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        server().onDisconnect(event.getEntity().getUUID());
    }

    private static void handle(byte[] bytes, CustomPayloadEvent.Context ctx) {
        GrugLinkArms.chooseWith2(
                ctx.isServerSide(),
                bytes,
                ctx,
                GrugControlForge::handleOnServer,
                GrugControlForge::handleOnClient);
    }

    private static void handleOnServer(byte[] bytes, CustomPayloadEvent.Context ctx) {
        // A message whose sender is unknown still goes in: the server fails what waits on it.
        server().onMessage(GrugLinkArms.mapOrNull(ctx.getSender(), ServerPlayer::getUUID), bytes);
    }

    /** A joined client receiving the server's message. */
    @GrugGenerated(
            "only a joined client runs it, and its JVM is a separate process whose coverage no job"
                    + " records: the dedicated leg records the server's alone")
    private static void handleOnClient(byte[] bytes, CustomPayloadEvent.Context ctx) {
        GrugControlClientForge.onServerMessage(bytes, ctx);
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
    @GrugGenerated(
            "only a joined client calls it, and its JVM is a separate process whose coverage no job"
                    + " records: the dedicated leg records the server's alone")
    static void sendToServer(byte[] bytes) {
        channel.send(new Packet(bytes), PacketDistributor.SERVER.noArg());
    }

    /**
     * Sends a player's own give to the server of {@code connection}, and answers whether it could:
     * a server without the channel (a player without grug on it) is not asked (#882).
     */
    @GrugGenerated(
            "only a joined client's own give calls it, and its JVM is a separate process whose"
                    + " coverage no job records: the dedicated leg records the server's alone")
    static boolean sendGiveToServer(net.minecraft.network.Connection connection, byte[] bytes) {
        if (!channel.isRemotePresent(connection)) return false;
        channel.send(new Packet(bytes), connection);
        return true;
    }

    private static final class ServerLink implements GrugControlServer.Link {
        @Override
        public boolean sendToStarter(Object starter, byte[] message) {
            MinecraftServer minecraft = ServerLifecycleHooks.getCurrentServer();
            ServerPlayer player = minecraft.getPlayerList().getPlayer((UUID) starter);
            // A starter who left between the server's check and this send is core's decision.
            return GrugLinkArms.deliver(
                    player,
                    found ->
                            channel.send(
                                    new Packet(message), PacketDistributor.PLAYER.with(found)));
        }

        @Override
        public boolean givesToSender() {
            return true;
        }

        @Override
        public Object playerOfSender(Object sender) {
            return ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer((UUID) sender);
        }

        @Override
        public void tell(Object sender, String message) {
            ServerPlayer player = (ServerPlayer) playerOfSender(sender);
            GrugLinkArms.deliver(
                    player,
                    found ->
                            found.sendSystemMessage(
                                    Component.literal(message).withStyle(ChatFormatting.RED)));
        }

        @Override
        public void runOnServerThread(Runnable task) {
            ServerLifecycleHooks.getCurrentServer().execute(task);
        }

        /**
         * A CI server makes the player who starts a run an operator, so the gives its tests make
         * pass the permission check a player's own would (#882). A real server never sets GRUG_CI.
         */
        @Override
        public void onRunStarted(Object starter) {
            GrugLinkArms.runIfCi(
                    System::getenv,
                    () ->
                            GrugLinkArms.deliver(
                                    playerOfSender(starter),
                                    player -> GrugCore.getAdapter().makeOperator(player)));
        }

        /**
         * Any player who has finished login may send a start; core then judges it on the server
         * thread and accepts only an operator's (#37), the first accepted one winning.
         */
        @Override
        public boolean mayStartRun(Object sender) {
            MinecraftServer minecraft = ServerLifecycleHooks.getCurrentServer();
            return GrugLinkArms.known(minecraft.getPlayerList().getPlayer((UUID) sender));
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

        /**
         * Runs the tasks the next server tick would run, which is where the packet handlers a
         * forwarded click queued are (#1094). The server thread is not frozen here: the caller runs
         * between two runner ticks, so a click's effect lands before the test's next assertion.
         */
        @Override
        public void applyClientWork() {
            MinecraftServer minecraft = ServerLifecycleHooks.getCurrentServer();
            GrugLinkArms.drain(minecraft, () -> minecraft.pollTask());
        }
    }
}
