package net.grug.minecraft.forge.gui;

import net.grug.minecraft.grug.GrugModManager;
import net.grug.minecraft.grug.GrugModsScreenModel;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLLoader;

/** Adds the button that opens the mod manager's screen to the title screen (#335). */
public final class GrugTitleButton {
    private GrugTitleButton() {}

    @SubscribeEvent
    public static void onTitleScreen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen title)) return;
        event.addListener(
                Button.builder(Component.literal("Grug Mods"), b -> open(title))
                        .bounds(6, 6, 80, 20)
                        .build());
    }

    private static void open(TitleScreen title) {
        GrugModManager manager = GrugModManager.of(FMLLoader.getGamePath().toFile());
        title.getMinecraft()
                .setScreen(new GrugModsScreen(title, new GrugModsScreenModel(manager, 5)));
    }
}
