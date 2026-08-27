package net.grug.minecraft.grug;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.gui.GrugScreenHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.modificationstation.stationapi.api.gui.screen.container.GuiHelper;
import net.modificationstation.stationapi.api.registry.ItemRegistry;
import net.modificationstation.stationapi.api.util.Identifier;

public class GameFunctions {

    // Classes

    public static long BlockPos_above_n(long blockPosId, double n) {
        BlockPos pos = (BlockPos) Grug.entityData.get(blockPosId).object;
        return Grug.addEntity(GrugEntityType.BlockPos, new BlockPos(pos.x(), pos.y() + (int) n, pos.z()));
    }

    public static long BlockPos_center(long blockPosId) {
        BlockPos pos = (BlockPos) Grug.entityData.get(blockPosId).object;
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5));
    }

    public static void Entity_set_delta_movement(long entityId, long vec3Id) {
        Entity entity = (Entity) Grug.entityData.get(entityId).object;
        Vec3 vec = (Vec3) Grug.entityData.get(vec3Id).object;
        entity.velocityX = vec.x();
        entity.velocityY = vec.y();
        entity.velocityZ = vec.z();
    }

    public static void Entity_spawn(long entityId, long levelId) {
        Entity entity = (Entity) Grug.entityData.get(entityId).object;
        World world = (World) Grug.entityData.get(levelId).object;
        world.spawnEntity(entity);
    }

    public static void Gui_add_output_slot(long guiId, double slot, double x, double y) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.blockSlots.add(new GrugGuiBuilder.SlotDef((int) slot, (int) x, (int) y, true));
    }

    public static void Gui_add_player_inventory(long guiId, double mainX, double mainY, double hotbarX,
            double hotbarY) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.hasPlayerInventory = true;
        builder.playerInvX = (int) mainX;
        builder.playerInvY = (int) mainY;
        builder.hotbarX = (int) hotbarX;
        builder.hotbarY = (int) hotbarY;
    }

    public static void Gui_add_slot(long guiId, double slot, double x, double y) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.blockSlots.add(new GrugGuiBuilder.SlotDef((int) slot, (int) x, (int) y, false));
    }

    public static void Gui_open(long guiId, long playerId, long blockEntityId) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        PlayerEntity player = (PlayerEntity) Grug.entityData.get(playerId).object;
        BlockEntity be = (BlockEntity) Grug.entityData.get(blockEntityId).object;

        if (!(be instanceof Inventory inv)) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "Gui.open: Block entity is not an inventory.");
            return;
        }

        GuiHelper.openGUI(player, Identifier.of("grug:dynamic_gui"), inv,
                new GrugScreenHandler(player, inv, builder));
    }

    public static long ItemEntity_entity(long itemEntityId) {
        return itemEntityId;
    }

    public static double Vec3_x(long vec3Id) {
        return ((Vec3) Grug.entityData.get(vec3Id).object).x();
    }

    public static double Vec3_y(long vec3Id) {
        return ((Vec3) Grug.entityData.get(vec3Id).object).y();
    }

    public static double Vec3_z(long vec3Id) {
        return ((Vec3) Grug.entityData.get(vec3Id).object).z();
    }

    // Host functions

    public static void add_lang(String path) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.langPaths.add(path);
        } else if (Grug.currentlyInitializingItem != null) {
            Grug.currentlyInitializingItem.langPaths.add(path);
        }
    }

    public static void add_recipe(String path) {
        Grug.declaredRecipes.add(path);
    }

    public static void add_tag(String namespace, String path) {
        Grug.declaredTags.add(new Grug.TagContribution(namespace, path));
    }

    public static void add_texture(String filePath) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.textures.add(filePath);
        } else if (Grug.currentlyInitializingItem != null) {
            Grug.currentlyInitializingItem.textures.add(filePath);
        }
    }

    public static void drop_inventory(long levelId, double x, double y, double z) {
        World world = (World) Grug.entityData.get(levelId).object;
        BlockEntity be = world.getBlockEntity((int) x, (int) y, (int) z);
        if (!(be instanceof Inventory inv)) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "drop_inventory: Block entity at (" + (int) x + ", " + (int) y
                    + ", " + (int) z + ") is not an inventory.");
            return;
        }

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null) {
                ItemEntity itemEntity = new ItemEntity(world, x, y, z, stack);
                world.spawnEntity(itemEntity);
                inv.setStack(i, null);
            }
        }
    }

    public static long get_block_entity(long levelId, double x, double y, double z) {
        World world = (World) Grug.entityData.get(levelId).object;
        BlockEntity be = world.getBlockEntity((int) x, (int) y, (int) z);
        if (be != null) {
            return Grug.addEntity(GrugEntityType.BlockEntity, be);
        }
        return Grug.INVALID_GRUG_FILE_ID;
    }

    public static long get_block_entity_level(long blockEntityId) {
        BlockEntity be = GameFunctionHelpers.resolveBlockEntity(blockEntityId);
        return Grug.addEntity(GrugEntityType.Level, be.world);
    }

    public static long get_block_pos_of_block_entity(long blockEntityId) {
        BlockEntity be = GameFunctionHelpers.resolveBlockEntity(blockEntityId);
        return Grug.addEntity(GrugEntityType.BlockPos, new BlockPos(be.x, be.y, be.z));
    }

    public static double get_inventory_size(long blockEntityId) {
        BlockEntity be = GameFunctionHelpers.resolveBlockEntity(blockEntityId);
        if (!(be instanceof Inventory inv)) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "get_inventory_size: Block entity is not an inventory.");
            return 0;
        }
        return inv.size();
    }

    public static long get_item_in_slot(long blockEntityId, double slot) {
        BlockEntity be = GameFunctionHelpers.resolveBlockEntity(blockEntityId);
        if (!(be instanceof Inventory inv)) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "get_item_in_slot: Block entity is not an inventory.");
            return Grug.INVALID_GRUG_FILE_ID;
        }

        ItemStack stack = inv.getStack((int) slot);
        if (stack != null && stack.getItem() != null) {
            return Grug.addEntity(GrugEntityType.Item, stack.getItem());
        }
        return Grug.INVALID_GRUG_FILE_ID;
    }

    public static long gui(String texturePath) {
        return Grug.addEntity(GrugEntityType.Gui, new GrugGuiBuilder(texturePath));
    }

    public static long item(long resourceLocationId) {
        Identifier id = (Identifier) Grug.entityData.get(resourceLocationId).object;
        Item item = ItemRegistry.INSTANCE.get(id);
        return Grug.addEntity(GrugEntityType.Item, item);
    }

    public static long item_entity(long levelId, double x, double y, double z, long itemStackId) {
        World world = (World) Grug.entityData.get(levelId).object;
        ItemStack stack = (ItemStack) Grug.entityData.get(itemStackId).object;
        ItemEntity itemEntity = new ItemEntity(world, (float) x, (float) y, (float) z, stack);
        return Grug.addEntity(GrugEntityType.ItemEntity, itemEntity);
    }

    public static long item_stack(long itemId) {
        Item item = (Item) Grug.entityData.get(itemId).object;
        return Grug.addEntity(GrugEntityType.ItemStack, new ItemStack(item));
    }

    public static <T> void print(T value) {
        String message = GameFunctionHelpers.prettyFormat(value);
        synchronized (Grug.printQueue) {
            Grug.printQueue.add(message);
        }
    }

    public static long resource_location(String resourceLocationString) {
        Identifier id = Identifier.of(resourceLocationString);
        return Grug.addEntity(GrugEntityType.ResourceLocation, id);
    }

    public static void set_block_entity(String entityString) {
        String[] parts = entityString.split(":");
        String cleanName = parts.length == 2 ? parts[1] : entityString;

        if (!Grug.entityFileIdsByName.containsKey(cleanName)) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "set_block_entity: Block entity script '" + entityString + "' does not exist.");
            return;
        }

        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.blockEntityString = entityString;
        }
    }

    public static void set_block_model(String path) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.blockModelPath = path;
        }
    }

    public static void set_blockstate(String path) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.blockstatePath = path;
        }
    }

    public static void set_hardness(double value) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.hardness = (float) value;
        }
    }

    public static void set_item_in_slot(long blockEntityId, double slot, long itemId, double count) {
        BlockEntity be = GameFunctionHelpers.resolveBlockEntity(blockEntityId);
        if (!(be instanceof Inventory inv)) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "set_item_in_slot: Block entity is not an inventory.");
            return;
        }

        Item item = (Item) Grug.entityData.get(itemId).object;
        inv.setStack((int) slot, new ItemStack(item, (int) count));
    }

    public static void set_item_model(String path) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.itemModelPath = path;
        } else if (Grug.currentlyInitializingItem != null) {
            Grug.currentlyInitializingItem.itemModelPath = path;
        }
    }

    public static void set_material(String materialName) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.material = materialName;
        }
    }

    public static long vec3(double x, double y, double z) {
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(x, y, z));
    }

    public static long vec3_zero() {
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(0, 0, 0));
    }
}
