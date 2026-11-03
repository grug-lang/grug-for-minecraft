package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.inventory.menu.SurvivalInventoryScreen;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

/**
 * The client's player, read from a class of its own. The player field is typed {@code
 * ClientPlayerEntity}, so a method that assigns it to a {@link PlayerEntity} makes the JVM load
 * that client class while verifying the method's own class, and a dedicated server has no such
 * class. Reading it here keeps that load behind a call only a client makes.
 */
@GrugGenerated("a client-only accessor, which a dedicated server never loads")
public final class GrugClientPlayer {
    private GrugClientPlayer() {}

    public static PlayerEntity get() {
        return MinecraftInstance.get().player;
    }

    public static PlayerEntity of(Minecraft minecraft) {
        return minecraft.player;
    }

    /**
     * Opens the survival inventory screen. Passing a {@code SurvivalInventoryScreen} to {@code
     * openScreen} makes the verifier load {@code Screen} to check the assignment, so the call lives
     * here and not in the adapter a dedicated server also loads.
     */
    public static void openInventory(PlayerEntity player) {
        MinecraftInstance.get().openScreen(new SurvivalInventoryScreen(player));
    }
}
