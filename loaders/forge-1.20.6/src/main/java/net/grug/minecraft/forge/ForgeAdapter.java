package net.grug.minecraft.forge;

import com.mojang.logging.LogUtils;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.File;

public class ForgeAdapter implements ModLoaderAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public File getGameDirectory() {
        return FMLPaths.GAMEDIR.get().toFile();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }

    @Override
    public void registerBlock(String namespace, String name, long fileId) {
        // Registration is already dynamically handled in GrugModLoader
    }

    @Override
    public void registerItem(String namespace, String name, long fileId) {
        // Registration is already dynamically handled in GrugModLoader
    }

    @Override
    public void registerBlockEntity(String namespace, String name) {
        // Registration is already dynamically handled in GrugModLoader
    }

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        if (playerObj instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            if (blockEntityObj instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                net.grug.minecraft.gui.GrugGuiBuilder builder = (net.grug.minecraft.gui.GrugGuiBuilder) guiBuilderObj;
                net.grug.minecraft.forge.network.GrugGuiPayload payload = new net.grug.minecraft.forge.network.GrugGuiPayload(
                        be.getBlockPos(), builder);

                net.grug.minecraft.forge.network.NetworkHandler.INSTANCE.send(
                        payload, net.minecraftforge.network.PacketDistributor.PLAYER.with(serverPlayer));
            }
        }
    }

    @Override
    public void reloadRecipe(String resourcePath) {
        // Recipe hot-reloading needs advanced integration with Forge's RecipeManager
    }

    @Override
    public void logInfo(String message) {
        LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        LOGGER.error(message);
    }

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        ((Entity) entityObj).setDeltaMovement(dx, dy, dz);
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((Level) levelObj).addFreshEntity((Entity) entityObj);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        if (blockEntityObj instanceof Container inv) {
            for (int i = 0; i < 9; i++) {
                int slot = (int) startSlot + i;
                ItemStack stack = inv.getItem(slot);
                if (!stack.isEmpty()) {
                    inv.removeItem(slot, 1);
                    if (stack.getItem().hasCraftingRemainingItem()) {
                        inv.setItem(slot, new ItemStack(stack.getItem().getCraftingRemainingItem()));
                    }
                }
            }
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        if (!(blockEntityObj instanceof Container inv))
            return 0;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            // 1.20.6 has removed traditional damage values for components, but assuming
            // direct comparison here.
            if (!stack.isEmpty() && stack.is(item) && stack.getDamageValue() == (int) damage) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        BlockEntity be = world.getBlockEntity(
                new net.minecraft.core.BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        if (be instanceof Container inv) {
            Containers.dropContents(world, be.getBlockPos(), inv);
            inv.clearContent();
        }
    }

    @Override
    public double extractItemFromInventory(Object blockEntityObj, Object itemObj, double damage, double amount) {
        if (!(blockEntityObj instanceof Container inv))
            return 0;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.getContainerSize() && remainingToExtract > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item) && stack.getDamageValue() == (int) damage) {
                int extractFromSlot = Math.min(stack.getCount(), remainingToExtract);
                inv.removeItem(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return ((Level) levelObj).getBlockEntity(
                new net.minecraft.core.BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((BlockEntity) blockEntityObj).getLevel();
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        net.minecraft.core.BlockPos pos = ((BlockEntity) blockEntityObj).getBlockPos();
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return (blockEntityObj instanceof Container inv) ? inv.getContainerSize() : 0;
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Container inv) {
            ItemStack stack = inv.getItem((int) slot);
            return !stack.isEmpty() ? stack.getCount() : 0;
        }
        return 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Container inv) {
            ItemStack stack = inv.getItem((int) slot);
            return !stack.isEmpty() ? stack.getDamageValue() : 0;
        }
        return 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        if (blockEntityObj instanceof Container inv) {
            ItemStack stack = inv.getItem((int) slot);
            return !stack.isEmpty() ? stack.getItem() : null;
        }
        return null;
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        return ForgeRegistries.ITEMS.getValue((ResourceLocation) resourceLocationObj);
    }

    @Override
    public Object createItemEntity(Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity((Level) levelObj, x, y, z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj) {
        return new ItemStack((Item) itemObj);
    }

    @Override
    public Object createResourceLocation(String string) {
        return new ResourceLocation(string);
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        if (blockEntityObj instanceof Container inv) {
            ItemStack stack = inv.getItem((int) slot);
            if (!stack.isEmpty()) {
                if (count <= 0) {
                    inv.setItem((int) slot, ItemStack.EMPTY);
                } else {
                    stack.setCount((int) count);
                }
            }
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        if (blockEntityObj instanceof Container inv) {
            inv.setItem((int) slot, new ItemStack((Item) itemObj, (int) count));
        }
    }

    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot, double outputSlot) {
        // Modern crafting recipe logic goes here
    }
}
