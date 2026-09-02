package net.grug.minecraft.stationapi.events.init;

import net.mine_diver.unsafeevents.listener.EventListener;
import net.modificationstation.stationapi.api.client.gui.screen.GuiHandler;
import net.modificationstation.stationapi.api.event.registry.GuiHandlerRegistryEvent;
import net.modificationstation.stationapi.api.network.packet.MessagePacket;
import net.modificationstation.stationapi.api.util.Identifier;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.stationapi.gui.GrugScreen;
import net.grug.minecraft.stationapi.gui.GrugScreenHandler;
import net.grug.minecraft.stationapi.gui.StationGuiHelper;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;

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

                    GrugGuiBuilder builder = StationGuiHelper.readBuilderFromPacket(message);

                    return new GrugScreen(new GrugScreenHandler(player, realInv, builder), builder);
                },
                GrugBlockEntity::new));
    }

}
