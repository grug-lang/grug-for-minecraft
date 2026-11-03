package net.grug.minecraft.stationapi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerEntity;

/**
 * The client's player, read from a class of its own. The player field is typed {@code
 * ClientPlayerEntity}, so a method that assigns it to a {@link PlayerEntity} makes the JVM load
 * that client class while verifying the method's own class, and a dedicated server has no such
 * class (#165). Reading it here keeps that load behind a call only a client makes.
 */
public final class GrugClientPlayer {
    private GrugClientPlayer() {}

    public static PlayerEntity of(Minecraft minecraft) {
        return minecraft.player;
    }

    /**
     * Opens the survival inventory screen. Passing an {@code InventoryScreen} to {@code setScreen}
     * makes the verifier load {@code Screen} to check the assignment, so the call lives here and
     * not in the adapter a dedicated server also loads.
     */
    public static void openInventory(Minecraft minecraft, PlayerEntity player) {
        minecraft.setScreen(new InventoryScreen(player));
    }
}
