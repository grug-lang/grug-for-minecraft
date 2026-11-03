package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.control.GrugControlPacket;
import net.minecraft.network.packet.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the test-control packet (#1040) at the end of {@code Packet}'s static initialiser, on
 * the client and on the server alike. The server's {@code Packet} has the same registry and the
 * same intermediary name as the client's, so one mixin serves both.
 */
@GrugGenerated("a mixin into the game's packet registry: the game runs it")
@Mixin(Packet.class)
public abstract class PacketRegisterMixin {
    @Shadow
    static void register(int id, boolean clientToServer, boolean serverToClient, Class<?> type) {}

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void grug$registerControlPacket(CallbackInfo ci) {
        register(GrugControlPacket.ID, true, true, GrugControlPacket.class);
    }
}
