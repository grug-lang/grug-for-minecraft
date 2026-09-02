package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.gui.GrugGuiBuilder;

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
        Object entity = Grug.entityData.get(entityId).object;
        Vec3 vec = (Vec3) Grug.entityData.get(vec3Id).object;
        GrugCore.getAdapter().setEntityDeltaMovement(entity, vec.x(), vec.y(), vec.z());
    }

    public static void Entity_spawn(long entityId, long levelId) {
        Object entity = Grug.entityData.get(entityId).object;
        Object world = Grug.entityData.get(levelId).object;
        GrugCore.getAdapter().spawnEntity(world, entity);
    }

    public static void GUI_add_crafting_grid(long guiId, double startSlot, double x, double y) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef((int) startSlot, (int) x, (int) y));
    }

    public static void GUI_add_crafting_result(long guiId, double slot, double x, double y) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef((int) slot, (int) x, (int) y));
    }

    public static void GUI_add_output_slot(long guiId, double slot, double x, double y) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.blockSlots.add(new GrugGuiBuilder.SlotDef((int) slot, (int) x, (int) y, true));
    }

    public static void GUI_add_player_inventory(long guiId, double mainX, double mainY, double hotbarX,
            double hotbarY) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.hasPlayerInventory = true;
        builder.playerInvX = (int) mainX;
        builder.playerInvY = (int) mainY;
        builder.hotbarX = (int) hotbarX;
        builder.hotbarY = (int) hotbarY;
    }

    public static void GUI_add_slot(long guiId, double slot, double x, double y) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.blockSlots.add(new GrugGuiBuilder.SlotDef((int) slot, (int) x, (int) y, false));
    }

    public static void GUI_add_text(long guiId, String text, double x, double y, long colorId) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        Color color = (Color) Grug.entityData.get(colorId).object;
        builder.texts.add(new GrugGuiBuilder.TextDef(text, (int) x, (int) y, color.getRGB()));
    }

    public static void GUI_open(long guiId, long playerId, long blockEntityId) {
        Object builder = Grug.entityData.get(guiId).object;
        Object player = Grug.entityData.get(playerId).object;
        Object be = GameFunctionHelpers.resolveBlockEntity(blockEntityId);
        GrugCore.getAdapter().openGui(player, be, builder);
    }

    public static long ItemEntity_entity(long itemEntityId) {
        return itemEntityId;
    }

    public static boolean Option_has(long optionId) {
        return ((GrugOption) Grug.entityData.get(optionId).object).has();
    }

    public static Object Option_unwrap(long optionId) {
        GrugOption opt = (GrugOption) Grug.entityData.get(optionId).object;
        if (!opt.has()) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "Tried to unwrap an empty Option!");
            return null;
        }
        return opt.value();
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

    public static long color_rgb(double r, double g, double b) {
        return Grug.addEntity(GrugEntityType.Color, new Color((int) r, (int) g, (int) b));
    }

    public static void consume_crafting_ingredients(long blockEntityId, double startSlot) {
        GrugCore.getAdapter().consumeCraftingIngredients(GameFunctionHelpers.resolveBlockEntity(blockEntityId),
                startSlot);
    }

    public static double count_item_in_inventory(long blockEntityId, long itemId, double damage) {
        return GrugCore.getAdapter().countItemInInventory(
                GameFunctionHelpers.resolveBlockEntity(blockEntityId),
                Grug.entityData.get(itemId).object, damage);
    }

    public static void drop_inventory(long levelId, double x, double y, double z) {
        GrugCore.getAdapter().dropInventory(Grug.entityData.get(levelId).object, x, y, z);
    }

    public static boolean equals(Object a, Object b) {
        if (a instanceof Long idA && b instanceof Long idB) {
            GrugObject objA = Grug.entityData.get(idA);
            GrugObject objB = Grug.entityData.get(idB);
            if (objA != null && objB != null)
                return java.util.Objects.equals(objA.object, objB.object);
        }
        return java.util.Objects.equals(a, b);
    }

    public static double extract_item_from_inventory(long blockEntityId, long itemId, double damage, double amount) {
        return GrugCore.getAdapter().extractItemFromInventory(
                GameFunctionHelpers.resolveBlockEntity(blockEntityId),
                Grug.entityData.get(itemId).object, damage, amount);
    }

    public static long get_block_entity(long levelId, double x, double y, double z) {
        Object be = GrugCore.getAdapter().getBlockEntity(Grug.entityData.get(levelId).object, x, y, z);
        if (be != null) {
            return Grug.addEntity(GrugEntityType.Option,
                    new GrugOption(Grug.addEntity(GrugEntityType.BlockEntity, be)));
        }
        return Grug.addEntity(GrugEntityType.Option, new GrugOption(null));
    }

    public static long get_block_entity_level(long blockEntityId) {
        Object level = GrugCore.getAdapter().getBlockEntityLevel(GameFunctionHelpers.resolveBlockEntity(blockEntityId));
        return Grug.addEntity(GrugEntityType.Level, level);
    }

    public static long get_block_pos_of_block_entity(long blockEntityId) {
        BlockPos pos = GrugCore.getAdapter()
                .getBlockPosOfBlockEntity(GameFunctionHelpers.resolveBlockEntity(blockEntityId));
        return Grug.addEntity(GrugEntityType.BlockPos, pos);
    }

    public static double get_inventory_size(long blockEntityId) {
        return GrugCore.getAdapter().getInventorySize(GameFunctionHelpers.resolveBlockEntity(blockEntityId));
    }

    public static double get_item_count_in_slot(long blockEntityId, double slot) {
        return GrugCore.getAdapter().getItemCountInSlot(GameFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
    }

    public static double get_item_damage_in_slot(long blockEntityId, double slot) {
        return GrugCore.getAdapter().getItemDamageInSlot(GameFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
    }

    public static long get_item_in_slot(long blockEntityId, double slot) {
        Object item = GrugCore.getAdapter().getItemInSlot(GameFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
        if (item != null) {
            return Grug.addEntity(GrugEntityType.Option, new GrugOption(Grug.addEntity(GrugEntityType.Item, item)));
        }
        return Grug.addEntity(GrugEntityType.Option, new GrugOption(null));
    }

    public static long gui(String texturePath) {
        return Grug.addEntity(GrugEntityType.GUI, new GrugGuiBuilder(texturePath));
    }

    public static long item(long resourceLocationId) {
        Object id = Grug.entityData.get(resourceLocationId).object;
        Object item = GrugCore.getAdapter().getItemFromRegistry(id);
        return Grug.addEntity(GrugEntityType.Item, item);
    }

    public static long item_entity(long levelId, double x, double y, double z, long itemStackId) {
        Object itemEntity = GrugCore.getAdapter().createItemEntity(
                Grug.entityData.get(levelId).object, x, y, z, Grug.entityData.get(itemStackId).object);
        return Grug.addEntity(GrugEntityType.ItemEntity, itemEntity);
    }

    public static long item_stack(long itemId) {
        Object itemStack = GrugCore.getAdapter().createItemStack(Grug.entityData.get(itemId).object);
        return Grug.addEntity(GrugEntityType.ItemStack, itemStack);
    }

    public static <T> void print(T a) {
        synchronized (Grug.printQueue) {
            Grug.printQueue.add(GameFunctionHelpers.prettyFormat(a));
        }
    }

    public static <T, U> void print2(T a, U b) {
        synchronized (Grug.printQueue) {
            Grug.printQueue.add(GameFunctionHelpers.prettyFormat(a) + " " + GameFunctionHelpers.prettyFormat(b));
        }
    }

    public static <T, U, V> void print3(T a, U b, V c) {
        synchronized (Grug.printQueue) {
            Grug.printQueue.add(GameFunctionHelpers.prettyFormat(a) + " " + GameFunctionHelpers.prettyFormat(b) + " "
                    + GameFunctionHelpers.prettyFormat(c));
        }
    }

    public static long resource_location(String resourceLocationString) {
        return Grug.addEntity(GrugEntityType.ResourceLocation,
                GrugCore.getAdapter().createResourceLocation(resourceLocationString));
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
        } else {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "set_block_entity: Can only be called during a Block's init().");
        }
    }

    public static void set_hardness(double value) {
        if (Grug.currentlyInitializingBlock != null)
            Grug.currentlyInitializingBlock.hardness = (float) value;
        else
            Grug.gameFunctionErrorHappened(Grug.statePtr, "set_hardness: Can only be called during a Block's init().");
    }

    public static void set_inventory_size(double size) {
        if (Grug.currentlyInitializingBlock != null)
            Grug.currentlyInitializingBlock.inventorySize = (int) size;
        else
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "set_inventory_size: Can only be called during a Block's init().");
    }

    public static void set_item_count_in_slot(long blockEntityId, double slot, double count) {
        GrugCore.getAdapter().setItemCountInSlot(GameFunctionHelpers.resolveBlockEntity(blockEntityId), slot, count);
    }

    public static void set_item_in_slot(long blockEntityId, double slot, long itemId, double count) {
        GrugCore.getAdapter().setItemInSlot(GameFunctionHelpers.resolveBlockEntity(blockEntityId), slot,
                Grug.entityData.get(itemId).object, count);
    }

    public static void set_material(String materialName) {
        if (Grug.currentlyInitializingBlock != null)
            Grug.currentlyInitializingBlock.material = materialName;
        else
            Grug.gameFunctionErrorHappened(Grug.statePtr, "set_material: Can only be called during a Block's init().");
    }

    public static void update_recipe_output(long blockEntityId, double startSlot, double outputSlot) {
        GrugCore.getAdapter().updateRecipeOutput(GameFunctionHelpers.resolveBlockEntity(blockEntityId), startSlot,
                outputSlot);
    }

    public static long vec3(double x, double y, double z) {
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(x, y, z));
    }

    public static long vec3_zero() {
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(0, 0, 0));
    }
}
