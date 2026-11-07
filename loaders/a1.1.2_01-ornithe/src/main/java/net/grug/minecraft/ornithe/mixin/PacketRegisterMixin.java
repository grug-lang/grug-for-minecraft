package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.entity.GrugSavedDataPacket;
import net.grug.minecraft.ornithe.control.GrugControlPacket;
import net.grug.minecraft.ornithe.gui.GrugScreenClickPacket;
import net.grug.minecraft.ornithe.gui.GrugScreenClosePacket;
import net.grug.minecraft.ornithe.gui.GrugScreenContentPacket;
import net.grug.minecraft.ornithe.gui.GrugScreenOpenPacket;
import net.minecraft.network.packet.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the test-control packet (#1040), the saved-data packet (#1093) and the four GUI packets
 * (#1098) at the end of {@code Packet}'s static initialiser, on the client and on the server alike.
 * The server's {@code Packet} has the same registry and the same intermediary name as the client's,
 * so one mixin serves both, and Alpha sets no direction on a registration, so each packet guards
 * its own side.
 */
@GrugGenerated("a mixin into the game's packet registry: the game runs it")
@Mixin(Packet.class)
public abstract class PacketRegisterMixin {
    @Shadow
    static void register(int id, Class<?> type) {}

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void grug$registerPackets(CallbackInfo ci) {
        register(GrugControlPacket.ID, GrugControlPacket.class);
        register(GrugSavedDataPacket.ID, GrugSavedDataPacket.class);
        // The GUI a server opens for its player (#1098): the server sends the layout and the
        // contents, and the client sends a click and a close. Both ends must know every id, or the
        // connection closes on the first one.
        register(GrugScreenOpenPacket.ID, GrugScreenOpenPacket.class);
        register(GrugScreenContentPacket.ID, GrugScreenContentPacket.class);
        register(GrugScreenClickPacket.ID, GrugScreenClickPacket.class);
        register(GrugScreenClosePacket.ID, GrugScreenClosePacket.class);
    }
}
