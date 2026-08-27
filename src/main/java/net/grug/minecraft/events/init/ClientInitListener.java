package net.grug.minecraft.events.init;

import net.mine_diver.unsafeevents.listener.EventListener;
import net.modificationstation.stationapi.api.client.gui.screen.GuiHandler;
import net.modificationstation.stationapi.api.event.registry.GuiHandlerRegistryEvent;
import net.modificationstation.stationapi.api.network.packet.MessagePacket;
import net.modificationstation.stationapi.api.util.Identifier;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.grug.minecraft.gui.GrugScreen;
import net.grug.minecraft.gui.GrugScreenHandler;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.block.entity.GrugBlockEntity;

public class ClientInitListener {

    @EventListener
    public void registerGuiHandlers(GuiHandlerRegistryEvent event) {
        event.register(Identifier.of(InitListener.NAMESPACE, "dynamic_gui"), new GuiHandler(
                (PlayerEntity player, Inventory dummyInv, MessagePacket message) -> {
                    if (message.strings == null || message.ints == null || message.strings.length < 2
                            || message.ints.length < 10) {
                        return null;
                    }

                    BlockEntity realBe = player.world.getBlockEntity(message.ints[1], message.ints[2], message.ints[3]);
                    if (!(realBe instanceof Inventory realInv)) {
                        return null;
                    }

                    GrugGuiBuilder builder = new GrugGuiBuilder(message.strings[1]);
                    builder.hasPlayerInventory = message.ints[4] == 1;
                    builder.playerInvX = message.ints[5];
                    builder.playerInvY = message.ints[6];
                    builder.hotbarX = message.ints[7];
                    builder.hotbarY = message.ints[8];

                    int idx = 9;
                    int blockSlotsCount = message.ints[idx++];
                    for (int i = 0; i < blockSlotsCount; i++) {
                        builder.blockSlots.add(new GrugGuiBuilder.SlotDef(
                                message.ints[idx++], message.ints[idx++], message.ints[idx++],
                                message.ints[idx++] == 1));
                    }

                    int gridCount = message.ints[idx++];
                    for (int i = 0; i < gridCount; i++) {
                        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(
                                message.ints[idx++], message.ints[idx++], message.ints[idx++]));
                    }

                    int resultsCount = message.ints[idx++];
                    for (int i = 0; i < resultsCount; i++) {
                        builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(
                                message.ints[idx++], message.ints[idx++], message.ints[idx++]));
                    }

                    return new GrugScreen(new GrugScreenHandler(player, realInv, builder), builder);
                },
                GrugBlockEntity::new));
    }

}
