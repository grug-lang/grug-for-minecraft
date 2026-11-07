package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugPacketDirection;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.entity.GrugSavedDataPacket;
import net.grug.minecraft.ornithe.control.GrugControlPacket;
import net.grug.minecraft.ornithe.gui.GrugScreenPacket;
import net.minecraft.network.packet.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the test-control packet (#1040), the saved-data packet (#1093) and the GUI packet
 * (#1098) at the end of {@code Packet}'s static initialiser, on the client and on the server alike.
 * The server's {@code Packet} has the same registry and the same intermediary name as the client's,
 * so one mixin serves both.
 */
@GrugGenerated("a mixin into the game's packet registry: the game runs it")
@Mixin(Packet.class)
public abstract class PacketRegisterMixin {
    @Shadow
    static void register(int id, boolean s2c, boolean c2s, Class<?> type) {}

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void grug$registerPackets(CallbackInfo ci) {
        // The game's register(int, boolean, boolean, Class) takes the server-to-client flag first:
        // deserialize(input, server) requires the client-to-server set when the server reads.
        GrugPacketDirection control = GrugPacketDirection.BOTH_WAYS;
        GrugPacketDirection serverToClient = GrugPacketDirection.SERVER_TO_CLIENT;
        register(
                GrugControlPacket.ID,
                control.serverToClient(),
                control.clientToServer(),
                GrugControlPacket.class);
        register(
                GrugSavedDataPacket.ID,
                serverToClient.serverToClient(),
                serverToClient.clientToServer(),
                GrugSavedDataPacket.class);
        // The GUI a server opens for its player (#1098): the server sends the layout, and a client
        // applies it, so both ends must know the id or the connection closes on it.
        register(
                GrugScreenPacket.ID,
                serverToClient.serverToClient(),
                serverToClient.clientToServer(),
                GrugScreenPacket.class);
    }
}
