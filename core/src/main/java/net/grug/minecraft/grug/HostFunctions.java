package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.gui.GrugGuiBuilder;

import java.util.OptionalInt;

public class HostFunctions {

    @GrugGenerated("utility class: never instantiated")
    private HostFunctions() {}

    // Entities

    public static void Test_assert(boolean condition, String message) {
        if (!condition) {
            // A runtime error, not Grug.fatal: a failed assertion should fail just the test, not
            // take the whole game down.
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Assertion failed: " + message);
        }
    }

    public static long Test_get_client_level() {
        Object level = GrugCore.getAdapter().getClientLevel();
        return Grug.addEntity(GrugEntityType.Level, level);
    }

    public static long Test_get_player() {
        return addPlayer(GrugCore.getAdapter().getPlayer());
    }

    @GrugGenerated("no local player: impossible while a test runs")
    private static long addPlayer(Object player) {
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.get_player: There is no local player.");
            return 0;
        }
        return Grug.addEntity(GrugEntityType.Player, player);
    }

    public static void Test_round_trip_nbt(long blockEntityId) {
        GrugCore.getAdapter().roundTripNbt(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
    }

    /** The original contents of each file a test has put into a non-normal state. */
    private static final java.util.Map<String, String> modFileOriginals = new java.util.HashMap<>();

    /**
     * Puts a mod file into a named state, remembering its original contents the first time so the
     * {@code "normal"} state restores them exactly.
     *
     * <p>Idempotent on purpose: a hot-reload test sets a state, waits for {@link
     * #Test_hot_reload_count}, then sets it back to {@code "normal"}, so an aborted run leaves the
     * file disturbed rather than flipped, and a re-run sets the same state again.
     */
    public static void Test_set_mod_file_state(String relativePath, String state) {
        java.io.File file =
                new java.io.File(GrugCore.getAdapter().getGrugModsDirectory(), relativePath);
        try {
            java.nio.file.Path path = file.toPath();
            String key = relativePath.replace('\\', '/');

            if ("normal".equals(state)) {
                String original = modFileOriginals.remove(key);
                if (original == null) return;
                java.nio.file.Files.write(
                        path, original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }

            String contents =
                    new String(
                            java.nio.file.Files.readAllBytes(path),
                            java.nio.charset.StandardCharsets.UTF_8);
            // Only remember the pristine contents while the file is still normal, so restoring
            // after
            // a re-run cannot record an already-disturbed state as the original.
            modFileOriginals.putIfAbsent(key, contents);

            if ("invalid".equals(state)) {
                contents = "!\n" + contents;
            } else if ("no_trailing_newline".equals(state)) {
                if (contents.endsWith("\n")) {
                    contents = contents.substring(0, contents.length() - 1);
                }
            } else {
                throw new IllegalArgumentException(
                        "Unknown mod file state '"
                                + state
                                + "'. Expected \"normal\", \"invalid\" or"
                                + " \"no_trailing_newline\".");
            }

            java.nio.file.Files.write(
                    path, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.set_mod_file_state: " + e);
        }
    }

    /**
     * How many changes to {@code relativePath} the engine has reported so far.
     *
     * <p>A hot-reload test records this, changes the file, then waits for the count to rise instead
     * of assuming its write was observed. That makes the test independent of filesystem timestamp
     * granularity, and idempotent: an aborted run leaves the file disturbed, but a re-run sets the
     * same state rather than flipping past it.
     */
    public static double Test_hot_reload_count(String relativePath) {
        return Grug.reportedChangeCount(relativePath);
    }

    // TODO: Allow tests to set their own origin, and change this to 10000,100,10000
    public static long Test_get_origin() {
        return Grug.addEntity(GrugEntityType.Vec3, GrugCore.getAdapter().getTestOrigin());
    }

    public static boolean Test_is_reference() {
        return GrugReference.isReferenceRun();
    }

    public static long Test_setup_graphics_camera() {
        return Grug.addEntity(GrugEntityType.Vec3, GrugCore.getAdapter().setupGraphicsTestCamera());
    }

    public static void Test_restore_camera() {
        GrugCore.getAdapter().restoreCameraAfterGraphicsTest();
    }

    public static void Test_notify_neighbors(long levelId, double x, double y, double z) {
        GrugCore.getAdapter().notifyNeighbors(Grug.entityData.get(levelId).object, x, y, z);
    }

    public static void Test_use_block(long levelId, double x, double y, double z) {
        GrugCore.getAdapter().useBlockForTest(Grug.entityData.get(levelId).object, x, y, z);
    }

    public static long Test_screenshot() {
        return Grug.addEntity(GrugEntityType.Screenshot, new GrugScreenshot(0));
    }

    // TODO: Change to me.tick() once entities can have methods
    public static double Test_tick() {
        return (double) Grug.currentTestTick;
    }

    // TODO: Change to me.not_done() once entities can have methods
    public static void Test_not_done() {
        Grug.testNotDone = true;
    }

    // TODO: Change to me.expect_error() once entities can have methods
    public static void Test_expect_error(String message) {
        Grug.testExpectedError = message;
    }

    // Classes

    public static long BlockPos_above_n(long blockPosId, double n) {
        BlockPos pos = (BlockPos) Grug.entityData.get(blockPosId).object;
        return Grug.addEntity(
                GrugEntityType.BlockPos, new BlockPos(pos.x(), pos.y() + (int) n, pos.z()));
    }

    public static long BlockPos_center(long blockPosId) {
        BlockPos pos = (BlockPos) Grug.entityData.get(blockPosId).object;
        return Grug.addEntity(
                GrugEntityType.Vec3, new Vec3(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5));
    }

    public static long Color_rgb(double r, double g, double b) {
        return Grug.addEntity(GrugEntityType.Color, new Color((int) r, (int) g, (int) b));
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
        builder.craftingGrids.add(
                new GrugGuiBuilder.CraftingGridDef((int) startSlot, (int) x, (int) y));
    }

    public static void GUI_add_crafting_result(long guiId, double x, double y) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef((int) x, (int) y));
    }

    public static void GUI_add_player_inventory(
            long guiId, double mainX, double mainY, double hotbarX, double hotbarY) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        builder.hasPlayerInventory = true;
        builder.playerInvX = (int) mainX;
        builder.playerInvY = (int) mainY;
        builder.hotbarX = (int) hotbarX;
        builder.hotbarY = (int) hotbarY;
    }

    public static void GUI_add_text(long guiId, String text, double x, double y, long colorId) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        Color color = (Color) Grug.entityData.get(colorId).object;
        builder.texts.add(new GrugGuiBuilder.TextDef(text, (int) x, (int) y, color.getRGB()));
    }

    public static void GUI_open(long guiId, long playerId, long blockEntityId) {
        GrugGuiBuilder builder = (GrugGuiBuilder) Grug.entityData.get(guiId).object;
        Object player = Grug.entityData.get(playerId).object;
        Object be = HostFunctionHelpers.resolveBlockEntity(blockEntityId);

        // A script can pass any block entity, including ones without an inventory
        int inventorySize = (int) GrugCore.getAdapter().getInventorySize(be);
        OptionalInt badSlot = builder.firstSlotOutsideInventory(inventorySize);
        if (badSlot.isPresent()) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "GUI.open: The GUI uses slot "
                            + badSlot.getAsInt()
                            + ", but the block entity only has "
                            + inventorySize
                            + " inventory slots.");
            return;
        }

        GrugCore.getAdapter().openGui(player, be, builder);
    }

    public static long ItemEntity_entity(long itemEntityId) {
        return itemEntityId;
    }

    public static long Level_get_block_entity(long levelId, double x, double y, double z) {
        Object be =
                GrugCore.getAdapter().getBlockEntity(Grug.entityData.get(levelId).object, x, y, z);
        // TODO: Should this not call Grug.hostFunctionErrorHappened when be is null?
        if (be != null) {
            return Grug.addEntity(
                    GrugEntityType.Option,
                    new GrugOption(Grug.addEntity(GrugEntityType.BlockEntity, be)));
        }
        return Grug.addEntity(GrugEntityType.Option, new GrugOption(null));
    }

    public static void Level_place_block(
            long levelId, double x, double y, double z, String blockName) {
        GrugCore.getAdapter().placeBlock(Grug.entityData.get(levelId).object, x, y, z, blockName);
    }

    public static boolean Level_has_neighbor_signal(long levelId, double x, double y, double z) {
        return GrugCore.getAdapter()
                .hasNeighborSignal(Grug.entityData.get(levelId).object, x, y, z);
    }

    public static String Level_get_block(long levelId, double x, double y, double z) {
        return GrugCore.getAdapter().getBlock(Grug.entityData.get(levelId).object, x, y, z);
    }

    public static boolean Level_is_air(long levelId, double x, double y, double z) {
        return GrugCore.getAdapter().isAir(Grug.entityData.get(levelId).object, x, y, z);
    }

    public static double Level_count_item_entities(
            long levelId, double x, double y, double z, double radius) {
        return GrugCore.getAdapter()
                .countItemEntities(Grug.entityData.get(levelId).object, x, y, z, radius);
    }

    public static boolean Option_has(long optionId) {
        return ((GrugOption) Grug.entityData.get(optionId).object).has();
    }

    // The generated GenericHostFunctions bridge resolves Option's $Value generic down to one of the
    // four base types before calling in, so set() needs an overload per base type to keep the JNI
    // calls from autoboxing. grug's type inference fixes $Value at the Option's declaration, so
    // only
    // the overload matching the declared type is ever selected; the others are dead but required
    // for
    // the bridge to compile.
    public static void Option_set(long optionId, double value) {
        setOptionValue(optionId, value);
    }

    @GrugGenerated("Option.set(bool): $Value is fixed at the Option's declaration")
    public static void Option_set(long optionId, boolean value) {
        setOptionValue(optionId, value);
    }

    @GrugGenerated("Option.set(string): $Value is fixed at the Option's declaration")
    public static void Option_set(long optionId, String value) {
        setOptionValue(optionId, value);
    }

    public static void Option_set(long optionId, long value) {
        setOptionValue(optionId, value);
    }

    // Mutate the existing Option in place rather than replacing the object under its id: the
    // replacement would be created during the current call and rooted only by that call's entity
    // list, so a member Option could be collected once the call returned. Mutating in place keeps
    // the object the member already points at.
    private static void setOptionValue(long optionId, Object value) {
        GrugObject existing = Grug.entityData.get(optionId);
        if (existing == null) {
            Grug.addEntityWithId(optionId, GrugEntityType.Option, new GrugOption(value));
            return;
        }
        ((GrugOption) existing.object).set(value);
    }

    public static Object Option_unwrap(long optionId) {
        GrugOption opt = (GrugOption) Grug.entityData.get(optionId).object;
        if (!opt.has()) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Tried to unwrap an empty Option!");
            return null;
        }
        return opt.value();
    }

    public static long Option_new() {
        return Grug.addEntity(GrugEntityType.Option, new GrugOption(null));
    }

    public static long Screenshot_tolerance(long screenshotId, double percent) {
        // A whole percentage only: a fractional value has no meaning for a pixel count, and the
        // floor/ceiling arithmetic on the comparison side assumes an integer.
        if (percent < 0 || percent > 100 || percent != Math.floor(percent)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.tolerance: percent must be a whole number between 0 and 100, but"
                            + " got "
                            + percent
                            + ".");
            return 0;
        }
        // GrugScreenshot is immutable, so setting a tolerance replaces the object stored under the
        // entity id rather than mutating it in place, the same way Option.set works.
        Grug.addEntityWithId(screenshotId, GrugEntityType.Screenshot, new GrugScreenshot(percent));
        return screenshotId;
    }

    public static void Screenshot_equals(
            long screenshotId, String referencePath, double x1, double y1, double x2, double y2) {
        GrugScreenshot screenshot = (GrugScreenshot) Grug.entityData.get(screenshotId).object;
        GrugCore.getAdapter()
                .assertScreenshotEquals(
                        referencePath, x1, y1, x2, y2, screenshot.tolerancePercent());
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

    public static void consume_crafting_ingredients(long blockEntityId, double startSlot) {
        GrugCore.getAdapter()
                .consumeCraftingIngredients(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), startSlot);
    }

    public static double count_item_in_inventory(long blockEntityId, long itemId, double damage) {
        return GrugCore.getAdapter()
                .countItemInInventory(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                        Grug.entityData.get(itemId).object,
                        damage);
    }

    public static void drop_inventory(long levelId, double x, double y, double z) {
        GrugCore.getAdapter().dropInventory(Grug.entityData.get(levelId).object, x, y, z);
    }

    public static boolean equals(Object a, Object b) {
        return valuesEqual(a, b);
    }

    @GrugGenerated("equality: the entity-id branch cannot be produced from grug without crashing")
    private static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Long && b instanceof Long) {
            Long idA = (Long) a;
            Long idB = (Long) b;
            GrugObject objA = Grug.entityData.get(idA);
            GrugObject objB = Grug.entityData.get(idB);
            if (objA != null && objB != null) {
                return java.util.Objects.equals(objA.object, objB.object);
            }
        }
        return java.util.Objects.equals(a, b);
    }

    public static double extract_item_from_inventory(
            long blockEntityId, long itemId, double damage, double amount) {
        return GrugCore.getAdapter()
                .extractItemFromInventory(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                        Grug.entityData.get(itemId).object,
                        damage,
                        amount);
    }

    public static double insert_item_into_inventory(
            long blockEntityId, long itemId, double damage, double amount) {
        return GrugCore.getAdapter()
                .insertItemIntoInventory(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                        Grug.entityData.get(itemId).object,
                        damage,
                        amount);
    }

    public static long get_block_entity_level(long blockEntityId) {
        Object level =
                GrugCore.getAdapter()
                        .getBlockEntityLevel(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
        return Grug.addEntity(GrugEntityType.Level, level);
    }

    public static long get_block_pos_of_block_entity(long blockEntityId) {
        BlockPos pos =
                GrugCore.getAdapter()
                        .getBlockPosOfBlockEntity(
                                HostFunctionHelpers.resolveBlockEntity(blockEntityId));
        return Grug.addEntity(GrugEntityType.BlockPos, pos);
    }

    public static double get_inventory_size(long blockEntityId) {
        return GrugCore.getAdapter()
                .getInventorySize(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
    }

    public static double get_item_count_in_slot(long blockEntityId, double slot) {
        return GrugCore.getAdapter()
                .getItemCountInSlot(HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
    }

    public static double get_item_damage_in_slot(long blockEntityId, double slot) {
        return GrugCore.getAdapter()
                .getItemDamageInSlot(HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
    }

    public static long get_item_in_slot(long blockEntityId, double slot) {
        Object item =
                GrugCore.getAdapter()
                        .getItemInSlot(HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
        if (item != null) {
            return Grug.addEntity(
                    GrugEntityType.Option,
                    new GrugOption(Grug.addEntity(GrugEntityType.Item, item)));
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
        Object itemStackObj = Grug.entityData.get(itemStackId).object;
        Object levelObj = Grug.entityData.get(levelId).object;
        Object itemEntity = GrugCore.getAdapter().createItemEntity(levelObj, x, y, z, itemStackObj);
        return Grug.addEntity(GrugEntityType.ItemEntity, itemEntity);
    }

    public static long item_stack(long itemId) {
        Object itemObj = Grug.entityData.get(itemId).object;
        Object itemStack = GrugCore.getAdapter().createItemStack(itemObj);
        return Grug.addEntity(GrugEntityType.ItemStack, itemStack);
    }

    public static <T> void print(T a) {
        synchronized (Grug.printQueue) {
            Grug.printQueue.add(HostFunctionHelpers.prettyFormat(a));
        }
    }

    public static <T, U> void print2(T a, U b) {
        synchronized (Grug.printQueue) {
            Grug.printQueue.add(
                    HostFunctionHelpers.prettyFormat(a)
                            + " "
                            + HostFunctionHelpers.prettyFormat(b));
        }
    }

    public static <T, U, V> void print3(T a, U b, V c) {
        synchronized (Grug.printQueue) {
            Grug.printQueue.add(
                    HostFunctionHelpers.prettyFormat(a)
                            + " "
                            + HostFunctionHelpers.prettyFormat(b)
                            + " "
                            + HostFunctionHelpers.prettyFormat(c));
        }
    }

    public static long resource_location(String resourceLocationString) {
        return Grug.addEntity(
                GrugEntityType.ResourceLocation,
                GrugCore.getAdapter().createResourceLocation(resourceLocationString));
    }

    public static void set_block_entity(String entityString) {
        String cleanName = entityString.substring(entityString.lastIndexOf(':') + 1);

        if (!Grug.entityFileIdsByName.containsKey(cleanName)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "set_block_entity: Block entity script '" + entityString + "' does not exist.");
            return;
        }

        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.blockEntityString = entityString;
        } else {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "set_block_entity: Can only be called during a Block's init().");
        }
    }

    public static void set_hardness(double value) {
        if (Grug.currentlyInitializingBlock != null)
            Grug.currentlyInitializingBlock.hardness = (float) value;
        else
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "set_hardness: Can only be called during a Block's init().");
    }

    public static void set_inventory_size(double size) {
        if (Grug.currentlyInitializingBlock != null)
            Grug.currentlyInitializingBlock.inventorySize = (int) size;
        else
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "set_inventory_size: Can only be called during a Block's init().");
    }

    public static void set_item_count_in_slot(long blockEntityId, double slot, double count) {
        GrugCore.getAdapter()
                .setItemCountInSlot(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot, count);
    }

    public static void set_item_damage_in_slot(long blockEntityId, double slot, double damage) {
        GrugCore.getAdapter()
                .setItemDamageInSlot(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot, damage);
    }

    public static void set_item_in_slot(
            long blockEntityId, double slot, long itemId, double count) {
        GrugCore.getAdapter()
                .setItemInSlot(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                        slot,
                        Grug.entityData.get(itemId).object,
                        count);
    }

    public static void set_material(String materialName) {
        if (Grug.currentlyInitializingBlock != null)
            Grug.currentlyInitializingBlock.material = materialName;
        else
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "set_material: Can only be called during a Block's init().");
    }

    public static double take_crafting_result(long blockEntityId, double amount) {
        return GrugCore.getAdapter()
                .takeCraftingResult(HostFunctionHelpers.resolveBlockEntity(blockEntityId), amount);
    }

    public static double take_item_from_slot(long blockEntityId, double slot, double amount) {
        return GrugCore.getAdapter()
                .takeItemFromSlot(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot, amount);
    }

    public static void update_recipe_output(long blockEntityId, double startSlot) {
        GrugCore.getAdapter()
                .updateRecipeOutput(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), startSlot);
    }

    public static long vec3_zero() {
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(0, 0, 0));
    }
}
