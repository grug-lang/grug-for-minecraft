package net.grug.minecraft.stationapi.client;

import net.minecraft.client.Minecraft;
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
}
