package net.grug.minecraft.ornithe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.client.GrugScreen;
import net.grug.minecraft.ornithe.inventory.DummyCraftingInventory;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.crafting.CraftingManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.ornithemc.osl.core.api.util.NamespacedIdentifier;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;

import java.io.File;

public class OrnitheAdapter implements ModLoaderAdapter {

    @Override
    public File getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public void logInfo(String message) {
        GrugModLoader.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        GrugModLoader.LOGGER.error(message);
    }

    // --- Registration Methods ---

    @Override
    public void registerBlock(String namespace, String name, long fileId) {
    }

    @Override
    public void registerItem(String namespace, String name, long fileId) {
    }

    @Override
    public void registerBlockEntity(String namespace, String name) {
    }

    @Override
    public void reloadRecipe(String resourcePath) {
    }

    // --- GUI & Inventory Methods ---

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        // The player and builder types cannot be wrong because of a script, so these are loader bugs
        if (!(playerObj instanceof PlayerEntity player)) {
            throw Grug.fatal("openGui: player is not a PlayerEntity: " + playerObj);
        }
        if (!(blockEntityObj instanceof Inventory inventory)) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "GUI.open: The block entity has no inventory.");
            return;
        }
        if (!(guiBuilderObj instanceof GrugGuiBuilder builder)) {
            throw Grug.fatal("openGui: builder is not a GrugGuiBuilder: " + guiBuilderObj);
        }
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "GUI.open: Opening a GUI on a dedicated server is not supported yet.");
            return;
        }

        GrugScreen.open(player, inventory, builder);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        if (blockEntityObj instanceof Inventory inv) {
            DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
            for (int i = 0; i < matrix.getSize(); i++) {
                ItemStack stack = matrix.getItem(i);
                if (stack != null) {
                    matrix.removeItem(i, 1);
                    if (stack.getItem().hasRecipeRemainder()) {
                        matrix.setItem(i, new ItemStack(stack.getItem().getRecipeRemainder()));
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
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage)
                total += stack.size;
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        if (!(levelObj instanceof World world))
            return;
        BlockEntity be = world.getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        if (be instanceof Inventory inv) {
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack != null) {
                    world.addEntity(new ItemEntity(world, x, y, z, stack));
                    inv.setItem(i, null);
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
        for (int i = 0; i < inv.getSize() && remainingToExtract > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage) {
                int extractFromSlot = Math.min(stack.size, remainingToExtract);
                inv.removeItem(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return (blockEntityObj instanceof Inventory inv) ? inv.getSize() : 0;
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack stack = inv.getItem((int) slot);
            return stack != null ? stack.size : 0;
        }
        return 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack stack = inv.getItem((int) slot);
            return stack != null ? stack.getDamage() : 0;
        }
        return 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack stack = inv.getItem((int) slot);
            return (stack != null) ? stack.getItem() : null;
        }
        return null;
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack stack = inv.getItem((int) slot);
            if (stack != null) {
                if (count <= 0)
                    inv.setItem((int) slot, null);
                else
                    stack.size = (int) count;
            }
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        if (blockEntityObj instanceof Inventory inv)
            inv.setItem((int) slot, new ItemStack((Item) itemObj, (int) count));
    }

    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot, double outputSlot) {
        if (blockEntityObj instanceof Inventory inv) {
            DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
            ItemStack result = CraftingManager.getInstance().getResult(matrix);
            inv.setItem((int) outputSlot, result != null ? result.copy() : null);
        }
    }

    // --- World & Entity Methods ---

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        if (entityObj instanceof Entity entity) {
            entity.velocityX = dx;
            entity.velocityY = dy;
            entity.velocityZ = dz;
        }
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        if (levelObj instanceof World world && entityObj instanceof Entity entity) {
            world.addEntity(entity);
        }
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        if (levelObj instanceof World world) {
            return world.getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }
        return null;
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        if (blockEntityObj instanceof BlockEntity be) {
            return be.world;
        }
        return null;
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        if (blockEntityObj instanceof BlockEntity be) {
            return new BlockPos(be.x, be.y, be.z);
        }
        return null;
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        if (resourceLocationObj == null)
            return null;

        String path;
        if (resourceLocationObj instanceof NamespacedIdentifier nid) {
            path = nid.identifier();
        } else {
            path = resourceLocationObj.toString();
            if (path.contains(":")) {
                path = path.split(":", 2)[1];
            }
        }

        // Try Translation Keys
        for (Item item : Item.BY_ID) {
            if (item == null)
                continue;
            String key = item.getTranslationKey();
            if (key != null) {
                if (key.equals(path) || key.endsWith("." + path) || key.equalsIgnoreCase("item." + path)
                        || key.equalsIgnoreCase("tile." + path)) {
                    return item;
                }
            }
        }

        // Match Vanilla Items via reflection
        for (java.lang.reflect.Field field : Item.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && Item.class.isAssignableFrom(field.getType())) {
                if (field.getName().equalsIgnoreCase(path)
                        || field.getName().replace("_", "").equalsIgnoreCase(path.replace("_", ""))) {
                    try {
                        return field.get(null);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Match Vanilla Blocks via reflection
        for (java.lang.reflect.Field field : Block.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && Block.class.isAssignableFrom(field.getType())) {
                if (field.getName().equalsIgnoreCase(path)
                        || field.getName().replace("_", "").equalsIgnoreCase(path.replace("_", ""))) {
                    try {
                        return field.get(null);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return null;
    }

    @Override
    public Object createItemEntity(Object levelObj, double x, double y, double z, Object itemStackObj) {
        if (levelObj instanceof World world && itemStackObj instanceof ItemStack stack) {
            return new ItemEntity(world, x, y, z, stack);
        }
        return null;
    }

    @Override
    public Object createItemStack(Object itemObj) {
        if (itemObj instanceof Item item) {
            return new ItemStack(item);
        }
        if (itemObj instanceof Block block) {
            return new ItemStack(block);
        }
        return null;
    }

    @Override
    public Object createResourceLocation(String string) {
        if (!string.contains(":")) {
            return NamespacedIdentifiers.from("minecraft", string);
        }
        String[] parts = string.split(":", 2);
        return NamespacedIdentifiers.from(parts[0], parts[1]);
    }
}
