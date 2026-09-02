package net.grug.minecraft.forge.network;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;

public class NetworkHandler {
    private static final int PROTOCOL_VERSION = 1;

    public static final SimpleChannel INSTANCE = ChannelBuilder.named(
            new ResourceLocation(GrugModLoader.MODID, "open_gui"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();

    public static void register() {
        INSTANCE.messageBuilder(GrugGuiPayload.class)
                .encoder(GrugGuiPayload::write)
                .decoder(GrugGuiPayload::new)
                .consumerMainThread(ClientPayloadHandler::handleGuiOpen)
                .add();
    }
}
