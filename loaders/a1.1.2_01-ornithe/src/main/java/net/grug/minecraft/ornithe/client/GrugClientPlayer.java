package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.inventory.menu.SurvivalInventoryScreen;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.world.World;
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
        MinecraftInstance.get()
                .openScreen(
                        new SurvivalInventoryScreen(player.inventory, player.inventory.crafting));
    }

    /**
     * Right-clicks the block at the position on this client's own copy of the world, which is where
     * an Alpha container screen opens (#1494).
     *
     * <p>This is the block's own activation, the first half of the game's right-click ({@code
     * ClientPlayerInteractionManager.useBlock}). It is the half a joined client ran for itself:
     * {@code ChestBlock.use} calls {@code PlayerEntity.openChestMenu}, which this client overrides
     * to open the game's own chest screen, while a dedicated server's player sends nothing, because
     * Alpha has no container packet. A grug block is left alone, since the server owns its menu
     * (#1098).
     */
    public static void useBlock(int x, int y, int z) {
        Minecraft minecraft = MinecraftInstance.get();
        World world = minecraft.world;
        PlayerEntity player = minecraft.player;
        if (world == null || player == null) {
            return;
        }
        int blockId = world.getBlock(x, y, z);
        if (blockId <= 0 || blockId >= Block.BY_ID.length) {
            return;
        }
        Block block = Block.BY_ID[blockId];
        if (block == null || block instanceof GrugBlock) {
            return;
        }
        block.use(world, x, y, z, player);
    }
}
