package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.gui.GrugGuiBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Covers the guard every host function resolves a host entity id through: an id whose object was
 * collected is reported with the method and the id, and the call answers what its signature needs
 * without touching the object that is gone. See #895.
 *
 * <p>The guard exists because the dereference it replaces threw inside the JNI call, which printed
 * and cleared the exception, so a stale id took the game's main thread down without naming the test
 * (#883, #890). No grug test can produce such an id: a test's root list holds every entity it
 * creates for the whole test (see {@link GrugEntities#createTestEntity}), and the only ids the
 * engine removes are the context handles, whose own guard refuses a stale one. These tests
 * therefore drive the guard from Java with an id they expired themselves, one test per shape of
 * answer and per kind of id, and an adapter that fails the test if a call reaches it.
 */
class HostFunctionsStaleIdTest {

    /** What the guard reported, in the order it reported it. */
    private final List<String> reports = new ArrayList<>();

    /** What the adapter was asked to do, for the calls a resolved id does reach it with. */
    private final List<String> adapterCalls = new ArrayList<>();

    private Consumer<String> savedReport;
    private Object savedAdapter;

    @BeforeEach
    void installRecorder() throws Exception {
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
        savedReport = HostFunctions.staleIdReport;
        HostFunctions.staleIdReport = reports::add;
        savedAdapter = adapterField().get(null);
    }

    @AfterEach
    void restoreState() throws Exception {
        if (GrugRenderPass.isFrame()) {
            GrugRenderPass.closeFrame();
        } else if (GrugRenderPass.isOpen()) {
            GrugRenderPass.close();
        }
        if (GrugOverlay.isRender()) {
            GrugOverlay.closeRender();
        } else if (GrugOverlay.isClick()) {
            GrugOverlay.closeClick();
        }
        HostFunctions.staleIdReport = savedReport;
        adapterField().set(null, savedAdapter);
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
    }

    // The shapes whose answer is nothing: the call is reported and the adapter never runs.

    @Test
    void aStaleLevelIsReportedAndTheCallRunsNothing() throws Exception {
        long level = stale(GrugEntityType.Level);
        refuseAdapter();

        HostFunctions.Level_place_block(level, 1, 2, 3, "minecraft:stone");
        assertEquals(List.of(staleMessage("Level.place_block", "level", level)), reports);

        reports.clear();
        HostFunctions.Level_explode(level, 1, 2, 3, 4);
        assertEquals(List.of(staleMessage("Level.explode", "level", level)), reports);

        reports.clear();
        HostFunctions.Test_use_block(level, 1, 2, 3);
        assertEquals(List.of(staleMessage("Test.use_block", "level", level)), reports);

        reports.clear();
        HostFunctions.Test_notify_neighbors(level, 1, 2, 3);
        assertEquals(List.of(staleMessage("Test.notify_neighbors", "level", level)), reports);

        reports.clear();
        HostFunctions.drop_inventory(level, 1, 2, 3);
        assertEquals(List.of(staleMessage("drop_inventory", "level", level)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Test_tick_level(level, 5));
        assertEquals(List.of(staleMessage("Test.tick_level", "level", level)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Test_display_tick(level, 1, 2, 3, 4));
        assertEquals(List.of(staleMessage("Test.display_tick", "level", level)), reports);
        assertEquals(0, GrugDisplayTicks.recordedCount());

        reports.clear();
        HostFunctions.Entity_spawn(live(GrugEntityType.Entity, "the entity"), level);
        assertEquals(List.of(staleMessage("Entity.spawn", "level", level)), reports);
    }

    @Test
    void aStalePlayerOrItemIsReportedAndTheHeldItemIsLeftAlone() throws Exception {
        long player = stale(GrugEntityType.Player);
        long item = stale(GrugEntityType.Item);
        refuseAdapter();

        HostFunctions.Test_clear_held_item(player);
        assertEquals(List.of(staleMessage("Test.clear_held_item", "player", player)), reports);

        reports.clear();
        HostFunctions.Test_set_held_item(player, live(GrugEntityType.Item, "the item"));
        assertEquals(List.of(staleMessage("Test.set_held_item", "player", player)), reports);

        reports.clear();
        HostFunctions.Test_set_held_item(live(GrugEntityType.Player, "the player"), item);
        assertEquals(List.of(staleMessage("Test.set_held_item", "item", item)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Test_get_held_count(player));
        assertEquals(List.of(staleMessage("Test.get_held_count", "player", player)), reports);

        reports.clear();
        HostFunctions.Test_open_inventory(player);
        assertEquals(List.of(staleMessage("Test.open_inventory", "player", player)), reports);

        reports.clear();
        HostFunctions.Test_set_carried_item(item);
        assertEquals(List.of(staleMessage("Test.set_carried_item", "item", item)), reports);
    }

    @Test
    void aStaleItemIsReportedBeforeAnItemIsGivenOrAdded() throws Exception {
        long item = stale(GrugEntityType.Item);
        refuseAdapter();

        HostFunctions.give_item(item);
        assertEquals(List.of(staleMessage("give_item", "item", item)), reports);

        reports.clear();
        HostFunctions.give_item_stack(item);
        assertEquals(List.of(staleMessage("give_item_stack", "item", item)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.BlockEntity_add_item(0, item, 0, 1, 0, true));
        assertEquals(List.of(staleMessage("BlockEntity.add_item", "item", item)), reports);
    }

    @Test
    void aStaleGuiOrColourIsReportedAndTheBuilderIsLeftAlone() {
        long gui = stale(GrugEntityType.GUI);
        long color = stale(GrugEntityType.Color);

        HostFunctions.GUI_add_crafting_grid(gui, 0, 1, 2);
        assertEquals(List.of(staleMessage("GUI.add_crafting_grid", "GUI", gui)), reports);

        reports.clear();
        HostFunctions.GUI_add_crafting_result(gui, 1, 2);
        assertEquals(List.of(staleMessage("GUI.add_crafting_result", "GUI", gui)), reports);

        reports.clear();
        HostFunctions.GUI_add_player_inventory(gui, 1, 2, 3, 4);
        assertEquals(List.of(staleMessage("GUI.add_player_inventory", "GUI", gui)), reports);

        GrugGuiBuilder builder = gui();
        long liveGui = live(GrugEntityType.GUI, builder);
        reports.clear();
        HostFunctions.GUI_add_text(liveGui, "text", 1, 2, color);
        assertTrue(builder.texts.isEmpty(), "the colour a text would use is gone");
        assertEquals(List.of(staleMessage("GUI.add_text", "color", color)), reports);

        reports.clear();
        HostFunctions.GUI_add_text(
                gui, "text", 1, 2, live(GrugEntityType.Color, new Color(1, 2, 3)));
        assertEquals(List.of(staleMessage("GUI.add_text", "GUI", gui)), reports);
        assertTrue(builder.texts.isEmpty(), "the stale GUI took no text");
    }

    @Test
    void aStaleGuiOrPlayerIsReportedAndTheGuiIsNotOpened() throws Exception {
        long gui = stale(GrugEntityType.GUI);
        long player = stale(GrugEntityType.Player);
        refuseAdapter();

        HostFunctions.GUI_open(gui, live(GrugEntityType.Player, "the player"), 0);
        assertEquals(List.of(staleMessage("GUI.open", "GUI", gui)), reports);

        reports.clear();
        HostFunctions.GUI_open(live(GrugEntityType.GUI, gui()), player, 0);
        assertEquals(List.of(staleMessage("GUI.open", "player", player)), reports);
    }

    @Test
    void aStaleResponseIsReportedAndNothingIsAnswered() {
        long add = stale(GrugEntityType.AddResponse);
        long extract = stale(GrugEntityType.ExtractResponse);

        HostFunctions.ExtractResponse_refuse(extract);
        assertEquals(List.of(staleMessage("ExtractResponse.refuse", "response", extract)), reports);

        reports.clear();
        HostFunctions.ExtractResponse_give_from_slot(extract, 0, 1);
        assertEquals(
                List.of(staleMessage("ExtractResponse.give_from_slot", "response", extract)),
                reports);

        reports.clear();
        HostFunctions.ExtractResponse_give_crafting_result(extract);
        assertEquals(
                List.of(staleMessage("ExtractResponse.give_crafting_result", "response", extract)),
                reports);

        reports.clear();
        HostFunctions.ExtractResponse_give_crafting_result_consumed(extract);
        assertEquals(
                List.of(
                        staleMessage(
                                "ExtractResponse.give_crafting_result_consumed",
                                "response",
                                extract)),
                reports);

        reports.clear();
        HostFunctions.AddResponse_refuse(add);
        assertEquals(List.of(staleMessage("AddResponse.refuse", "response", add)), reports);

        reports.clear();
        HostFunctions.AddResponse_accept_into_slot(add, 0);
        assertEquals(
                List.of(staleMessage("AddResponse.accept_into_slot", "response", add)), reports);
    }

    @Test
    void aStaleScreenshotIsReportedAndIsNeitherCapturedNorCompared() {
        long screenshot = stale(GrugEntityType.Screenshot);
        long other = stale(GrugEntityType.Screenshot);

        HostFunctions.Screenshot_equals(screenshot, "reference", 0, 0, 1, 1);
        assertEquals(List.of(staleMessage("Screenshot.equals", "screenshot", screenshot)), reports);

        reports.clear();
        assertEquals(screenshot, HostFunctions.Screenshot_capture(screenshot, 0, 0, 1, 1));
        assertEquals(
                List.of(staleMessage("Screenshot.capture", "screenshot", screenshot)), reports);
        assertNull(GrugEntities.entityData.get(screenshot), "a capture stores nothing under it");

        reports.clear();
        HostFunctions.Screenshot_differs_from(screenshot, other, 1);
        assertEquals(
                List.of(staleMessage("Screenshot.differs_from", "screenshot", screenshot)),
                reports);

        reports.clear();
        long captured = live(GrugEntityType.Screenshot, new GrugScreenshot(0));
        HostFunctions.Screenshot_differs_from(captured, other, 1);
        assertEquals(
                List.of(staleMessage("Screenshot.differs_from", "screenshot", other)), reports);
    }

    @Test
    void aStaleItemLeavesASavedListRecordUnsaved() {
        long blockEntity = live(GrugEntityType.BlockEntity, holder());
        long item = stale(GrugEntityType.Item);
        GrugSavedLists.append(holderOf(blockEntity), "list");

        HostFunctions.BlockEntity_set_saved_list_item(blockEntity, "list", 0, item);

        assertEquals(
                List.of(staleMessage("BlockEntity.set_saved_list_item", "item", item)), reports);
        assertNull(
                GrugSavedLists.record(holderOf(blockEntity), "list", 0).item(),
                "the record's item is still what it was");
    }

    @Test
    void aStaleItemIsReportedBeforeAnyInventoryCallTouchesTheBlockEntity() throws Exception {
        long item = stale(GrugEntityType.Item);
        refuseAdapter();

        HostFunctions.Test_set_nbt_item(0, "path", item);
        assertEquals(List.of(staleMessage("Test.set_nbt_item", "item", item)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.BlockEntity_count_item_in_inventory(0, item, 0));
        assertEquals(
                List.of(staleMessage("BlockEntity.count_item_in_inventory", "item", item)),
                reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.BlockEntity_extract_item_from_inventory(0, item, 0, 1));
        assertEquals(
                List.of(staleMessage("BlockEntity.extract_item_from_inventory", "item", item)),
                reports);

        reports.clear();
        assertEquals(
                0.0, HostFunctions.BlockEntity_insert_item_into_inventory(0, item, 0, 1, true));
        assertEquals(
                List.of(staleMessage("BlockEntity.insert_item_into_inventory", "item", item)),
                reports);

        reports.clear();
        HostFunctions.BlockEntity_set_item_in_slot(0, 0, item, 1);
        assertEquals(List.of(staleMessage("BlockEntity.set_item_in_slot", "item", item)), reports);

        reports.clear();
        assertEquals(0L, HostFunctions.item_stack(item, 0, 1));
        assertEquals(List.of(staleMessage("item_stack", "item", item)), reports);
    }

    // The shapes whose answer is a value: the call still answers what its signature needs.

    @Test
    void aStaleIdStillAnswersTheNumberItsSignatureNeeds() throws Exception {
        long level = stale(GrugEntityType.Level);
        long vec3 = stale(GrugEntityType.Vec3);
        long itemEntity = stale(GrugEntityType.ItemEntity);
        refuseAdapter();

        assertEquals(0.0, HostFunctions.Level_get_time(level));
        assertEquals(List.of(staleMessage("Level.get_time", "level", level)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Level_random_int(level, 5));
        assertEquals(List.of(staleMessage("Level.random_int", "level", level)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Level_random_float(level));
        assertEquals(List.of(staleMessage("Level.random_float", "level", level)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Level_random_gaussian(level));
        assertEquals(List.of(staleMessage("Level.random_gaussian", "level", level)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Level_count_item_entities(level, 0, 0, 0, 1));
        assertEquals(List.of(staleMessage("Level.count_item_entities", "level", level)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.ItemEntity_damage(itemEntity));
        assertEquals(
                List.of(staleMessage("ItemEntity.damage", "item entity", itemEntity)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Vec3_x(vec3));
        assertEquals(List.of(staleMessage("Vec3.x", "vec3", vec3)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Vec3_y(vec3));
        assertEquals(List.of(staleMessage("Vec3.y", "vec3", vec3)), reports);

        reports.clear();
        assertEquals(0.0, HostFunctions.Vec3_z(vec3));
        assertEquals(List.of(staleMessage("Vec3.z", "vec3", vec3)), reports);
    }

    @Test
    void aStaleIdStillAnswersTheBooleanItsSignatureNeeds() throws Exception {
        long level = stale(GrugEntityType.Level);
        long option = stale(GrugEntityType.Option);
        refuseAdapter();

        assertFalse(HostFunctions.Level_is_air(level, 0, 0, 0));
        assertEquals(List.of(staleMessage("Level.is_air", "level", level)), reports);

        reports.clear();
        assertFalse(HostFunctions.Level_has_neighbor_signal(level, 0, 0, 0));
        assertEquals(List.of(staleMessage("Level.has_neighbor_signal", "level", level)), reports);

        reports.clear();
        assertFalse(HostFunctions.Test_mining_crack(level, 0, 0, 0));
        assertEquals(List.of(staleMessage("Test.mining_crack", "level", level)), reports);

        reports.clear();
        assertFalse(HostFunctions.Option_has(option));
        assertEquals(List.of(staleMessage("Option.has", "option", option)), reports);
    }

    @Test
    void aStaleIdStillAnswersTheStringOrDefaultItsSignatureNeeds() {
        long level = stale(GrugEntityType.Level);

        assertEquals("", HostFunctions.Level_get_block(level, 0, 0, 0));
        assertEquals(List.of(staleMessage("Level.get_block", "level", level)), reports);

        reports.clear();
        long option = stale(GrugEntityType.Option);
        assertNull(HostFunctions.Option_unwrap(option));
        assertEquals(List.of(staleMessage("Option.unwrap", "option", option)), reports);
    }

    @Test
    void aStaleIdThatAnswersAnEntityIdAnswersNone() {
        long level = stale(GrugEntityType.Level);
        long player = stale(GrugEntityType.Player);
        long blockPos = stale(GrugEntityType.BlockPos);
        long resourceLocation = stale(GrugEntityType.ResourceLocation);
        long itemStack = stale(GrugEntityType.ItemStack);

        assertEquals(0L, HostFunctions.Player_get_held_item(player));
        assertEquals(List.of(staleMessage("Player.get_held_item", "player", player)), reports);
        assertEquals(0, GrugEntities.entityData.size(), "no Option is built behind a stale id");

        reports.clear();
        assertEquals(0L, HostFunctions.BlockPos_above_n(blockPos, 1));
        assertEquals(
                List.of(staleMessage("BlockPos.above_n", "block position", blockPos)), reports);

        reports.clear();
        assertEquals(0L, HostFunctions.BlockPos_center(blockPos));
        assertEquals(List.of(staleMessage("BlockPos.center", "block position", blockPos)), reports);

        reports.clear();
        assertEquals(0L, HostFunctions.Level_get_block_entity(level, 0, 0, 0));
        assertEquals(List.of(staleMessage("Level.get_block_entity", "level", level)), reports);

        reports.clear();
        assertEquals(0L, HostFunctions.Level_find_item_entity(level, 0, 0, 0, 1));
        assertEquals(List.of(staleMessage("Level.find_item_entity", "level", level)), reports);

        reports.clear();
        assertEquals(0L, HostFunctions.item(resourceLocation));
        assertEquals(List.of(staleMessage("item", "resource location", resourceLocation)), reports);

        reports.clear();
        assertEquals(
                0L,
                HostFunctions.item_entity(
                        live(GrugEntityType.Level, "the level"), 0, 0, 0, itemStack));
        assertEquals(List.of(staleMessage("item_entity", "item stack", itemStack)), reports);

        reports.clear();
        assertEquals(
                0L,
                HostFunctions.item_entity(
                        level, 0, 0, 0, live(GrugEntityType.ItemStack, "the stack")));
        assertEquals(List.of(staleMessage("item_entity", "level", level)), reports);
    }

    @Test
    void aStaleVec3IsReportedAndTheEntityItWouldMoveIsLeftAlone() throws Exception {
        long vec3 = stale(GrugEntityType.Vec3);
        long entity = stale(GrugEntityType.Entity);
        refuseAdapter();

        HostFunctions.Entity_set_delta_movement(live(GrugEntityType.Entity, "the entity"), vec3);
        assertEquals(List.of(staleMessage("Entity.set_delta_movement", "vec3", vec3)), reports);

        reports.clear();
        HostFunctions.Entity_set_delta_movement(
                entity, live(GrugEntityType.Vec3, new Vec3(0, 0, 0)));
        assertEquals(List.of(staleMessage("Entity.set_delta_movement", "entity", entity)), reports);

        reports.clear();
        HostFunctions.Entity_spawn(entity, live(GrugEntityType.Level, "the level"));
        assertEquals(List.of(staleMessage("Entity.spawn", "entity", entity)), reports);
    }

    @Test
    void aStaleItemIsReportedAndTheFrameDrawsNothing() {
        long item = stale(GrugEntityType.Item);
        GrugRenderPass.openFrame(0.0, 0.0);
        long context = live(GrugEntityType.RenderContext, GrugRenderPass.context());

        HostFunctions.RenderFrameContext_draw_item(context, item, 0, 1, 0, 0, 0);

        assertEquals(List.of(staleMessage("RenderFrameContext.draw_item", "item", item)), reports);
        assertTrue(GrugRenderPass.closeFrame().draws().isEmpty(), "the frame holds no stack");
    }

    @Test
    void aStaleItemIsReportedAndTheOverlayDrawsNothing() {
        long item = stale(GrugEntityType.Item);
        GrugOverlay.openRender(426, 240, 125, 37, 176, 166, 310, 12);
        long context = live(GrugEntityType.OverlayRenderContext, GrugOverlay.context());

        HostFunctions.OverlayRenderContext_draw_item(context, item, 0, 0);

        assertEquals(
                List.of(staleMessage("OverlayRenderContext.draw_item", "item", item)), reports);
        assertTrue(GrugOverlay.closeRender().items().isEmpty(), "the pass holds no stack");
    }

    // A resolved id of a kind the call uses, which proves the guard hands on the object.

    @Test
    void aResolvedIdIsTheObjectTheCallUses() throws Exception {
        recordAdapter();
        long level = live(GrugEntityType.Level, "the level");

        assertEquals(42.0, HostFunctions.Level_get_time(level));
        assertEquals(List.of("getWorldTime[the level]"), adapterCalls);
        assertEquals(List.of(), reports);

        adapterCalls.clear();
        HostFunctions.Test_set_held_item(
                live(GrugEntityType.Player, "the player"), live(GrugEntityType.Item, "the item"));
        assertEquals(List.of("setHeldItem[the player, the item]"), adapterCalls);

        adapterCalls.clear();
        HostFunctions.item_entity(level, 0, 0, 0, live(GrugEntityType.ItemStack, "the stack"));
        assertEquals(
                List.of("createItemEntity[the level, 0.0, 0.0, 0.0, the stack]"), adapterCalls);
        assertEquals(List.of(), reports);
    }

    @Test
    void aResolvedObjectIsTheOneTheCallAnswersFrom() {
        long vec = live(GrugEntityType.Vec3, new Vec3(1, 2, 3));
        assertEquals(1.0, HostFunctions.Vec3_x(vec));
        assertEquals(2.0, HostFunctions.Vec3_y(vec));
        assertEquals(3.0, HostFunctions.Vec3_z(vec));

        assertTrue(HostFunctions.Option_has(live(GrugEntityType.Option, new GrugOption(7L))));
        assertFalse(HostFunctions.Option_has(live(GrugEntityType.Option, new GrugOption(null))));

        assertEquals(List.of(), reports);
    }

    /** The report the guard makes for {@code id}, which names the method and the id. */
    private static String staleMessage(String method, String kind, long id) {
        return method
                + ": the "
                + kind
                + " id "
                + id
                + " no longer resolves, because the object behind it was collected while the"
                + " script still held it.";
    }

    /** An id that resolved, then was expired the way a collected object expires it. */
    private static long stale(GrugEntityType type) {
        long id = live(type, new Object());
        GrugEntities.removeEntity(id);
        return id;
    }

    private static long live(GrugEntityType type, Object object) {
        return GrugEntities.addEntity(type, object);
    }

    private static GrugGuiBuilder gui() {
        return new GrugGuiBuilder("textures/gui/test.png");
    }

    private static GrugSavedLists.Holder holder() {
        return new GrugSavedLists.Holder() {
            @Override
            public void markSavedNumbersChanged() {}
        };
    }

    private static Object holderOf(long blockEntityId) {
        return GrugEntities.entityData.get(blockEntityId).object;
    }

    private static Field adapterField() throws NoSuchFieldException {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        return field;
    }

    /** Installs an adapter that fails the test when a call reaches it. */
    private void refuseAdapter() throws Exception {
        adapterField()
                .set(
                        null,
                        proxy(
                                (proxy, method, args) -> {
                                    throw new AssertionError(
                                            "a stale id reached the adapter through "
                                                    + method.getName());
                                }));
    }

    /** Installs an adapter that records what it is asked, answering what the call needs. */
    private void recordAdapter() throws Exception {
        adapterField()
                .set(
                        null,
                        proxy(
                                (proxy, method, args) -> {
                                    adapterCalls.add(method.getName() + Arrays.toString(args));
                                    Class<?> answer = method.getReturnType();
                                    if (answer == long.class) return 42L;
                                    if (answer == int.class) return 42;
                                    if (answer == double.class) return 42.0;
                                    if (answer == boolean.class) return true;
                                    return null;
                                }));
    }

    private ModLoaderAdapter proxy(InvocationHandler handler) {
        return (ModLoaderAdapter)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        handler);
    }
}
