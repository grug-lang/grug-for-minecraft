package net.grug.minecraft.stationapi;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.grug.minecraft.stationapi.grug.DummyCraftingInventory;
import net.grug.minecraft.stationapi.gui.GrugScreenHandler;
import net.grug.minecraft.stationapi.gui.StationGuiHelper;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipeManager;
import net.minecraft.world.World;
import net.modificationstation.stationapi.api.gui.screen.container.GuiHelper;
import net.modificationstation.stationapi.api.registry.BlockRegistry;
import net.modificationstation.stationapi.api.registry.ItemRegistry;
import net.modificationstation.stationapi.api.util.Identifier;

import java.io.File;

public class StationApiAdapter implements ModLoaderAdapter {

    @Override
    public File getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public void registerBlock(String namespace, String name, long fileId) {
        Identifier blockId = Identifier.of(namespace + ":" + name);
        new GrugBlock(blockId, fileId, Material.STONE, 0.0f).setTranslationKey(blockId.namespace, blockId.path);
    }

    @Override
    public void registerItem(String namespace, String name, long fileId) {
    }

    @Override
    public void registerBlockEntity(String namespace, String name) {
    }

    /**
     * Note: Although StationAPI is based on Fabric, it targets Minecraft Beta
     * 1.7.3.
     * Therefore, we do not have access to modern UI abstractions like
     * net.minecraft.screen.SimpleNamedScreenHandlerFactory (or Forge's
     * SimpleMenuProvider).
     * Instead, we manually synchronize the GUI data using StationAPI's
     * MessagePacket system.
     */
    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        BlockEntity be = (BlockEntity) blockEntityObj;
        GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;

        if (be instanceof Inventory inv) {
            GuiHelper.openGUI(player, Identifier.of("grug:dynamic_gui"), inv,
                    new GrugScreenHandler(player, inv, builder),
                    messagePacket -> {
                        int syncId = (messagePacket.ints != null && messagePacket.ints.length > 0)
                                ? messagePacket.ints[0]
                                : 0;
                        StationGuiHelper.writeBuilderToPacket(builder, messagePacket, syncId, be.x, be.y, be.z);
                    });
        }
    }

    @Override
    public void reloadRecipe(String resourcePath) {
        InitListener.handlePossibleRecipeUpdate(resourcePath);
    }

    // --- Logging Abstraction ---

    @Override
    public void logInfo(String message) {
        InitListener.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        InitListener.LOGGER.error(message);
    }

    // --- Game Functions Abstraction ---

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        Entity entity = (Entity) entityObj;
        entity.velocityX = dx;
        entity.velocityY = dy;
        entity.velocityZ = dz;
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((World) levelObj).spawnEntity((Entity) entityObj);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        if (blockEntityObj instanceof Inventory inv) {
            DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
            for (int i = 0; i < matrix.size(); i++) {
                ItemStack stack = matrix.getStack(i);
                if (stack != null) {
                    matrix.removeStack(i, 1);
                    if (stack.getItem().hasCraftingReturnItem()) {
                        matrix.setStack(i, new ItemStack(stack.getItem().getCraftingReturnItem()));
                    }
                }
            }
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        if (!(blockEntityObj instanceof Inventory inv))
            return 0;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage)
                total += stack.count;
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        BlockEntity be = world.getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        if (be instanceof Inventory inv) {
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack != null) {
                    world.spawnEntity(new ItemEntity(world, x, y, z, stack));
                    inv.setStack(i, null);
                }
            }
        }
    }

    @Override
    public double extractItemFromInventory(Object blockEntityObj, Object itemObj, double damage, double amount) {
        if (!(blockEntityObj instanceof Inventory inv))
            return 0;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.size() && remainingToExtract > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage) {
                int extractFromSlot = Math.min(stack.count, remainingToExtract);
                inv.removeStack(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double takeItemFromSlot(Object blockEntityObj, double slot, double amount) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack removed = inv.removeStack((int) slot, (int) amount);
            if (removed != null) {
                if (blockEntityObj instanceof GrugBlockEntity gbe) {
                    gbe.notifyOutputTaken((int) slot, removed.count);
                }
                return removed.count;
            }
        }
        return 0;
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return ((World) levelObj).getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((BlockEntity) blockEntityObj).world;
    }

    @Override
    public Object getClientLevel() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc = (net.minecraft.client.Minecraft) FabricLoader.getInstance()
                .getGameInstance();
        return mc != null ? mc.world : null;
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        return new BlockPos(be.x, be.y, be.z);
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return (blockEntityObj instanceof Inventory inv) ? inv.size() : 0;
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack stack = inv.getStack((int) slot);
            return stack != null ? stack.count : 0;
        }
        return 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack stack = inv.getStack((int) slot);
            return stack != null ? stack.getDamage() : 0;
        }
        return 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack stack = inv.getStack((int) slot);
            return (stack != null) ? stack.getItem() : null;
        }
        return null;
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        return ItemRegistry.INSTANCE.get((Identifier) resourceLocationObj);
    }

    @Override
    public Object createItemEntity(Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity((World) levelObj, (float) x, (float) y, (float) z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj) {
        return new ItemStack((Item) itemObj);
    }

    @Override
    public Object createResourceLocation(String string) {
        return Identifier.of(string);
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack stack = inv.getStack((int) slot);
            if (stack != null) {
                if (count <= 0)
                    inv.setStack((int) slot, null);
                else
                    stack.count = (int) count;
            }
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        if (blockEntityObj instanceof Inventory inv)
            inv.setStack((int) slot, new ItemStack((Item) itemObj, (int) count));
    }

    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot, double outputSlot) {
        if (blockEntityObj instanceof Inventory inv) {
            DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
            ItemStack result = CraftingRecipeManager.getInstance().craft(matrix);
            inv.setStack((int) outputSlot, result != null ? result.copy() : null);
        }
    }

    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        if (levelObj instanceof World world) {
            Identifier id = Identifier.of(blockName.contains(":") ? blockName : "minecraft:" + blockName);
            Block targetBlock = BlockRegistry.INSTANCE.get(id);

            if (targetBlock != null) {
                int posX = (int) Math.floor(x);
                int posY = (int) Math.floor(y);
                int posZ = (int) Math.floor(z);

                world.setBlock(posX, posY, posZ, targetBlock.id);
            } else {
                InitListener.LOGGER.error("placeBlock failed: Could not resolve block " + blockName);
            }
        }
    }

    @Override
    public Vec3 getTestOrigin() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc = (net.minecraft.client.Minecraft) FabricLoader
                .getInstance().getGameInstance();
        return new Vec3(mc.player.x, mc.player.y + 3.0, mc.player.z);
    }
}
