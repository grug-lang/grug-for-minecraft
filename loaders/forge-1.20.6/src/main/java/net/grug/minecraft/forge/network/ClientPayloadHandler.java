package net.grug.minecraft.forge.network;

import net.grug.minecraft.forge.GrugModLoader;
import net.grug.minecraft.forge.gui.GrugMenu;
import net.grug.minecraft.forge.gui.GrugScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.network.CustomPayloadEvent;

public class ClientPayloadHandler {
    public static void handleGuiOpen(final GrugGuiPayload payload, final CustomPayloadEvent.Context context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null || mc.level == null)
                return;

            BlockEntity be = mc.level.getBlockEntity(payload.pos());
            if (!(be instanceof Container inv))
                return;

            GrugMenu menu = new GrugMenu(GrugModLoader.GRUG_MENU.get(), 0, player.getInventory(), inv,
                    payload.builder());
            GrugScreen screen = new GrugScreen(menu, player.getInventory(),
                    Component.literal("Grug GUI"), payload.builder());
            mc.setScreen(screen);
        });
        context.setPacketHandled(true);
    }
}
