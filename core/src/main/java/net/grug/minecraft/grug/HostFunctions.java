package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugFrameRequest;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.gui.GrugGuiBuilder;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class HostFunctions {

    @GrugGenerated("utility class: never instantiated")
    private HostFunctions() {}

    // Entities

    public static void TestTickContext_assert(long contextId, boolean condition, String message) {
        if (!requireTickContext("TestTickContext.assert", contextId)) return;
        if (!condition) {
            // A runtime error, not Grug.fatal: a failed assertion should fail just the test, not
            // take the whole game down.
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Assertion failed: " + message);
        }
    }

    public static long Test_get_client_level() {
        return addLevel(GrugCore.getAdapter().getLevel());
    }

    @GrugGenerated("no level yet: only a dedicated server reaches it, and CI launches none")
    private static long addLevel(Object level) {
        if (level == null) {
            // Which side it is matters to whoever reads this: a client has a world the moment one
            // is loaded, and a dedicated server has its level from the moment it starts, so an
            // empty one means a server with no world yet.
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.get_client_level: There is no level to test in on "
                            + GrugCore.getAdapter().getSide().description()
                            + " yet.");
            return 0;
        }
        return GrugEntities.addEntity(GrugEntityType.Level, level);
    }

    public static void BlockUseContext_decline_use(long contextId) {
        if (!requireContext(
                "BlockUseContext.decline_use", "use", contextId, GrugBlockUse.context())) {
            return;
        }
        GrugBlockUse.decline();
    }

    public static void ShapeContext_add_collision_box(
            long contextId, double x1, double y1, double z1, double x2, double y2, double z2) {
        addShapeBox("ShapeContext.add_collision_box", contextId, x1, y1, z1, x2, y2, z2, true);
    }

    public static void ShapeContext_add_selection_box(
            long contextId, double x1, double y1, double z1, double x2, double y2, double z2) {
        addShapeBox("ShapeContext.add_selection_box", contextId, x1, y1, z1, x2, y2, z2, false);
    }

    /**
     * Records one box a block's shape declared, on whichever of the two lists applies.
     *
     * <p>Both methods share this so they refuse a box the same way: the only difference between
     * them is the list and the name in the message.
     */
    private static void addShapeBox(
            String method,
            long contextId,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2,
            boolean collision) {
        if (!requireShapeContext(method, contextId)) return;

        GrugBox box = new GrugBox(x1, y1, z1, x2, y2, z2);
        boolean recorded =
                collision ? GrugShapes.recordCollision(box) : GrugShapes.recordSelection(box);
        if (!recorded) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    method
                            + ": The box was not recorded. Its corners have to be the right way"
                            + " round: the first of each pair is the near corner and must be no"
                            + " greater than the second, so add it as (0, 0, 0, 1, 1, 1) and not as"
                            + " (1, 0, 0, 0, 1, 1).");
        }
    }

    public static long Player_get_held_item(long playerId) {
        return withEntity(
                "Player.get_held_item",
                "player",
                playerId,
                player -> {
                    Object item = GrugCore.getAdapter().getHeldItem(player);
                    if (item == null) {
                        return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
                    }
                    return GrugEntities.addEntity(
                            GrugEntityType.Option,
                            new GrugOption(GrugEntities.addEntity(GrugEntityType.Item, item)));
                },
                0L);
    }

    public static void Test_set_held_item(long playerId, long itemId) {
        withEntities(
                "Test.set_held_item",
                "player",
                playerId,
                "item",
                itemId,
                (player, item) -> GrugCore.getAdapter().setHeldItem(player, item));
    }

    public static void Test_clear_held_item(long playerId) {
        withEntity(
                "Test.clear_held_item",
                "player",
                playerId,
                player -> GrugCore.getAdapter().setHeldItem(player, null));
    }

    /**
     * Test only: how many items the player's main hand holds, so a test can tell one item from a
     * full stack. The held item itself is what {@code Player.get_held_item} answers.
     */
    public static double Test_get_held_count(long playerId) {
        return withEntity(
                "Test.get_held_count",
                "player",
                playerId,
                player -> GrugCore.getAdapter().getHeldCount(player),
                0.0);
    }

    /**
     * Test only: the largest stack the item can come in, so a test of an overlay can tell a full
     * stack from one item without knowing which item this version's list puts first.
     */
    @GrugGenerated("asks the adapter about the game's own items: a unit test has no game")
    public static double Test_get_item_max_stack(long itemId) {
        return withEntity(
                "Test.get_item_max_stack",
                "item",
                itemId,
                item -> (double) GrugCore.getAdapter().getItemMaxStack(item),
                0.0);
    }

    /**
     * Test only: puts one of the item on the mouse cursor of the open container screen, which is
     * where the reference reads the stack a left click deletes.
     */
    @GrugGenerated("asks the adapter to touch the game's own screen: a unit test has no game")
    public static void Test_set_carried_item(long itemId) {
        withEntity(
                "Test.set_carried_item",
                "item",
                itemId,
                item -> GrugCore.getAdapter().setCarriedItem(item));
    }

    /**
     * Test only: the width of the open screen in scaled pixels, so a test of an overlay can work
     * out where the screen put its GUI and its display area instead of assuming a window size.
     */
    @GrugGenerated("asks the adapter about the game's own screen: a unit test has no game")
    public static double Test_screen_width() {
        return GrugCore.getAdapter().screenWidthForTest();
    }

    /** Test only: the x of the open container screen's GUI, the same measure an overlay gets. */
    @GrugGenerated("asks the adapter about the game's own screen: a unit test has no game")
    public static double Test_gui_left() {
        return GrugCore.getAdapter().guiLeftForTest();
    }

    /**
     * Test only: the width of the open container screen's GUI, the same measure an overlay gets.
     */
    @GrugGenerated("asks the adapter about the game's own screen: a unit test has no game")
    public static double Test_gui_width() {
        return GrugCore.getAdapter().guiWidthForTest();
    }

    /**
     * Test only: clears the mouse cursor of the open container screen, on the client and on the
     * integrated server, so a test starts a press with nothing carried.
     */
    @GrugGenerated("asks the adapter about the game's own screen: a unit test has no game")
    public static void Test_clear_carried_item() {
        GrugCore.getAdapter().deleteCarriedItem();
    }

    /** Test only: the item on the mouse cursor of the open container screen, if it holds one. */
    @GrugGenerated("asks the adapter about the game's own screen: a unit test has no game")
    public static long Test_get_carried_item() {
        Object item = GrugCore.getAdapter().carriedItem();
        if (item == null) {
            return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
        }
        return GrugEntities.addEntity(
                GrugEntityType.Option,
                new GrugOption(GrugEntities.addEntity(GrugEntityType.Item, item)));
    }

    public static long Test_get_player() {
        return addPlayer(GrugCore.getAdapter().testPlayer());
    }

    @GrugGenerated("no player yet: only a dedicated server reaches it, and CI launches none")
    private static long addPlayer(Object player) {
        if (player == null) {
            // Which side it is matters to whoever reads this: on a client there is a local player
            // the moment a world is loaded, so an empty one means the test is running too early,
            // and on a dedicated server it means nobody has joined yet.
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.get_player: There is no player to test from on "
                            + GrugCore.getAdapter().getSide().description()
                            + " yet.");
            return 0;
        }
        return GrugEntities.addEntity(GrugEntityType.Player, player);
    }

    public static void Test_round_trip_nbt(long blockEntityId) {
        GrugCore.getAdapter().roundTripNbt(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
    }

    /**
     * Test only: proves that a block id above 255 survives the game's own chunk save and load, and
     * that the loader's block-id search reaches such an id once the ids below are used up.
     *
     * <p>The work happens in the loader: 1.2.5's search must spill from its low range into the ids
     * above 255 when a pack uses the low range up, and the id it then hands out has to come back
     * through the save's 12-bit form. The answer is the id the test block got, so the test can
     * check it is one of the high ones rather than take the round trip on its own word; a loader
     * whose block ids never reach above 255 has no such id to round trip, and answers -1, which is
     * a no-op rather than an err. See #518.
     */
    public static double Test_high_block_round_trip() {
        return GrugCore.getAdapter().highBlockRoundTrip();
    }

    /**
     * Test only: runs a grug block entity's tick {@code ticks} times in a row, within the one game
     * tick the test is in.
     *
     * <p>A machine whose state builds over minutes, such as an engine that gains one energy every
     * 20 ticks, would otherwise make its test wait those minutes on every loader. The extra ticks
     * run within the current game tick and leave the clock {@code Level.get_time} reads alone, as
     * machine accelerators such as ProjectE's Watch of Flowing Time do, which call a block entity's
     * tick several times per game tick. Only a grug block entity has a tick to run, and only a
     * whole, non-negative number of ticks means anything, so anything else is reported rather than
     * rounded.
     */
    public static void Test_tick_block_entity(long blockEntityId, double ticks) {
        Object blockEntity = HostFunctionHelpers.resolveBlockEntity(blockEntityId);
        if (!GrugSavedNumbers.isHolder(blockEntity)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.tick_block_entity: Only a grug block entity has a tick to run.");
            return;
        }
        String problem = GrugTestState.checkTickCount(ticks);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.tick_block_entity: " + problem);
            return;
        }
        for (int tick = 0; tick < ticks; tick++) {
            GrugCore.getAdapter().tickBlockEntity(blockEntity);
        }
    }

    /**
     * Test only: runs the level's own tick {@code ticks} times in a row, within the one game tick
     * the test is in, and answers how many of them ran.
     *
     * <p>A test that waits for the world to reach a state, such as an engine gathering energy and a
     * pipe carrying an item, spends a rendered frame on every tenth tick at the speed the game's
     * clock runs, and the advance spends none: it runs the ticks the game would have run, in the
     * loader's order, with no frame, no screen and no client packet between them. What it cannot do
     * is make the client catch up, so anything the client draws or receives still needs a real
     * tick. The clock {@code Level.get_time} reads moves one per tick and the level's random is
     * drawn from, unlike {@code Test.tick_block_entity}, which leaves both alone.
     *
     * <p>Only a whole, non-negative number of ticks means anything, so anything else is reported
     * rather than rounded. The advances run past the runner's tick budget, which counts the test's
     * own ticks: a long one is bounded only by the run timeout. The answer is what the loader ran,
     * which is the count asked for on a loader that can advance its level and 0 on one that cannot
     * yet, so a test can tell an advance that did nothing from one that worked. See #753 and #780.
     */
    public static double Test_tick_level(long levelId, double ticks) {
        String problem = GrugTestState.checkTickCount(ticks);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.tick_level: " + problem);
            return 0;
        }
        return withEntity(
                "Test.tick_level",
                "level",
                levelId,
                level -> (double) GrugCore.getAdapter().tickLevel(level, (int) ticks),
                0.0);
    }

    /**
     * Puts a mod file into a named state, remembering its pristine text the first time so the
     * {@code "normal"} state restores it exactly.
     *
     * <p>The record outlives the process, so a run killed before it restored the file leaves
     * nothing behind for the next run to trip over. See {@link GrugModFileStates}.
     */
    public static void Test_set_mod_file_state(String relativePath, String state) {
        try {
            GrugModFileStates.set(
                    GrugCore.getAdapter().getGrugModsDirectory(), relativePath, state);
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
     * same state rather than flipping past it, and the next startup puts the file back.
     */
    public static double Test_hot_reload_count(String relativePath) {
        return GrugModsWatcher.reportedChangeCount(relativePath);
    }

    /**
     * How many times a loader has finished reloading after a change to {@code relativePath}.
     *
     * <p>{@link #Test_hot_reload_count} says the engine saw the change; this says the loader has
     * answered it. A test that checks what the reload rendered waits on this, because the engine's
     * report only means the loader was told.
     */
    public static double Test_reload_count(String relativePath) {
        return GrugModsWatcher.loaderReloadCount(relativePath);
    }

    /**
     * Test only: the one origin a test builds from, which is the camera's position plus 3 on every
     * loader. The runner snaps the player to the centre of their block before a test starts, so the
     * first call in a test is already at a known place, and later calls reuse it. See #609.
     */
    // TODO: Allow tests to set their own origin, and change this to 10000,100,10000
    public static long Test_get_origin() {
        return GrugEntities.addEntity(
                GrugEntityType.Vec3,
                GrugTestState.captureTestOrigin(() -> GrugCore.getAdapter().getTestOrigin()));
    }

    /**
     * Test only: asks the JVM for a full garbage collection.
     *
     * <p>Host entities are weakly held, so a test that proves an entity is still rooted has to make
     * the collection deterministic instead of hoping GC timing lines up.
     */
    public static void Test_force_gc() {
        System.gc();
    }

    /** Test only: stages a number in a block entity's own NBT. See {@link GrugBlockEntityNbt}. */
    public static void Test_set_nbt_number(
            long blockEntityId, String path, String type, double value) {
        GrugBlockEntityNbt.report(
                "Test.set_nbt_number",
                GrugBlockEntityNbt.stageNumber(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), path, type, value));
    }

    /** Test only: stages an item in a block entity's own NBT. See {@link GrugBlockEntityNbt}. */
    public static void Test_set_nbt_item(long blockEntityId, String path, long itemId) {
        withEntity(
                "Test.set_nbt_item",
                "item",
                itemId,
                item ->
                        GrugBlockEntityNbt.report(
                                "Test.set_nbt_item",
                                GrugBlockEntityNbt.stageItem(
                                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                                        path,
                                        item)));
    }

    /** Test only: loads a block entity's staged NBT edits. See {@link GrugBlockEntityNbt}. */
    public static void Test_load_nbt(long blockEntityId) {
        GrugBlockEntityNbt.report(
                "Test.load_nbt",
                GrugBlockEntityNbt.load(HostFunctionHelpers.resolveBlockEntity(blockEntityId)));
    }

    public static boolean Test_is_reference() {
        return GrugReference.isReferenceRun();
    }

    /**
     * Test only: whether this loader registers the crafting recipes a mod ships under its {@code
     * data/<namespace>/recipes} tree. Every loader does.
     *
     * <p>A test that asserts one of those recipes asks this first, so a future loader that cannot
     * register the tree does not fail the run for something it cannot do.
     */
    public static boolean Test_registers_mod_recipes() {
        return GrugCore.getAdapter().registersModRecipes();
    }

    /** The longest {@code Test.stall} may block the game, in milliseconds. */
    static final double MAX_STALL_MILLISECONDS = 10_000;

    /**
     * Test only: blocks the thread the test runs on for a while, the way a garbage collection pause
     * or a world save does.
     *
     * <p>On 1.20.6 the runner is queued on the integrated server's thread from the client's tick,
     * and the client keeps ticking while that thread is busy, so a stall leaves the client with
     * ticks the server has not acted on. The runner coalesces them to at most one frame's worth
     * rather than replaying them (see {@code GrugTickLockstep}), but a test that waits for a packet
     * or a frame still waits for the condition: a stall is how such a test proves it. See #688 and
     * #709.
     */
    public static void Test_stall(double milliseconds) {
        if (!(milliseconds >= 0 && milliseconds <= MAX_STALL_MILLISECONDS)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.stall: The time has to be from 0 to "
                            + (long) MAX_STALL_MILLISECONDS
                            + " milliseconds, and was "
                            + milliseconds
                            + ".");
            return;
        }
        sleepUninterrupted((long) milliseconds);
    }

    /** Sleeps, and keeps the interrupt of a thread that was told to stop for whoever runs next. */
    static void sleepUninterrupted(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Whether the client had a screen open the last time {@link #Test_screen_open()} asked, so that
     * call can tell a screen that has just appeared from one that was already up.
     */
    private static boolean screenWasOpen = false;

    /**
     * Test only: closes whatever screen the client has open.
     *
     * <p>One client serves the whole run, so a screen a test opened outlives it. A screenshot test
     * that follows one which opened a GUI needs this, or it captures that panel rather than the
     * world, and the golden it writes down silently becomes the panel.
     *
     * <p>The remembered state is cleared too, so the next screen to open is seen as a fresh one and
     * {@link #Test_screen_open()} forgets the settled frames for it.
     */
    public static void Test_close_screen() {
        GrugCore.getAdapter().closeOpenScreenForTest();
        screenWasOpen = false;
    }

    /**
     * Test only: whether the client has a screen open. On 1.20.6 a block's GUI is opened by a
     * packet from the server, so it is not open yet when {@code Test.use_block} returns, and a
     * settled world does not say it has arrived. See #418.
     *
     * <p>A screen that has just appeared forgets the frames counted so far. {@code screen_open}
     * alone only says the screen object exists, and {@code world_settled} only says the last frames
     * were idle, so the two together could still let a capture read a frame drawn before the screen
     * opened once the world was already settled. Forgetting the frames here means a following
     * {@code world_settled} wait can only be answered by frames drawn after the screen opened,
     * which is the frame-hook guarantee the settled-frame wait is supposed to give. See #421.
     */
    public static boolean Test_screen_open() {
        boolean open = GrugCore.getAdapter().isScreenOpenForTest();
        if (open && !screenWasOpen) {
            GrugCore.getAdapter().forgetSettledFrames();
        }
        screenWasOpen = open;
        return open;
    }

    /**
     * Test only: whether this loader ever asked an overlay's exports, which is how a test tells a
     * loader that draws overlays on the inventory and container screens from one that does not. The
     * answer stays false on a loader with no overlay, so a test that needs the overlay to have
     * drawn skips there rather than failing for a missing feature.
     */
    public static boolean Test_overlay_supported() {
        return GrugOverlay.overlaySupported();
    }

    /**
     * Test only: whether a frame has drawn the running test's own overlay on the eligible screen
     * that is open now, which a test waits for before it clicks. The mark is cleared when an
     * eligible screen opens, so a test waits for a frame of its own screen rather than for one
     * another mod drew on an earlier one. Asking also asks the client to draw the next frame, the
     * way {@code Test.world_settled} does, because a test that never captures has its frames
     * skipped and would otherwise wait for a frame that no test asked for. The answer is set on the
     * render thread, so waiting for it is how a test tells a screen that was really drawn from one
     * the game closed before a frame.
     */
    public static boolean Test_overlay_drawn() {
        GrugFrameRequest.request();
        return GrugOverlay.overlayDrawn();
    }

    /**
     * Test only: opens the player's own inventory screen, so a test can drive what an overlay draws
     * there. The screen is put up on the next frame, which is why a test waits for {@code
     * screen_open} before clicking it.
     */
    @GrugGenerated("asks the adapter to open the game's own screen: a unit test has no game")
    public static void Test_open_inventory(long playerId) {
        withEntity(
                "Test.open_inventory",
                "player",
                playerId,
                player -> GrugCore.getAdapter().openInventory(player));
    }

    /**
     * Test only: presses a mouse button at a screen position, through the same path a real press
     * takes, and answers whether an overlay took the press.
     *
     * <p>The position is in the scaled pixels a screen's own mouse handlers take, which is the same
     * measure an overlay's {@code mouse_x} and {@code mouse_y} answer. True means an overlay took
     * the press, so the vanilla slot under it never saw it. False means no overlay took it, even
     * when the screen handled the press itself, so a test can tell the overlay's take from a click
     * on a vanilla slot. The argument checks live in {@link #screenClickProblem}, which a Java test
     * drives without a game.
     */
    @GrugGenerated("asks the adapter to press on the game's own screen: a unit test has no game")
    public static boolean Test_click_screen(double x, double y, double button) {
        String problem = screenClickProblem(x, y, button);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, problem);
            return false;
        }
        return GrugCore.getAdapter().clickScreen(x, y, (int) button);
    }

    /** Why Test.click_screen refuses this press, or null when it can drive it. */
    static String screenClickProblem(double x, double y, double button) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return "Test.click_screen: The position has to be finite.";
        }
        if (!Double.isFinite(button)) {
            return "Test.click_screen: The button has to be finite.";
        }
        if (button != Math.floor(button) || button < 0) {
            return "Test.click_screen: The button has to be a whole number from 0 up, and was "
                    + button
                    + ".";
        }
        return null;
    }

    /**
     * Test only: rebuilds the sealed room the runner puts around the player, with a different
     * radius. The runner builds a small one before every test; a test whose fixture reaches further
     * than that calls this at the top of its setup. See #253.
     */
    public static void Test_set_box_radius(double radius) {
        GrugCore.getAdapter().buildTestBox((int) radius);
    }

    /**
     * Test only: whether the frame a capture would read is one the client had no work left in.
     *
     * <p>The mesh bakes a face's per-vertex light when its section is rebuilt, and a rebuild can
     * run while a light update is still settling, so a capture taken too early can land a light
     * step away from the reference. The loaders answer from their own frame hooks, because a tick
     * is not a frame and the game draws fewer frames than it runs ticks: the frame a capture reads
     * can be several ticks old, so a tick-based answer can pass while the frame it would read is
     * not the settled scene. See #253 and #257.
     */
    public static boolean Test_world_settled() {
        // A settle wait is about frames, so asking whether the client has a settled one means the
        // test wants one drawn. The frame count was forgotten when this test's room was built, so
        // the two frames that satisfy the wait are frames this call asked for. See
        // GrugFrameRequest.
        GrugFrameRequest.request();
        return GrugCore.getAdapter().isWorldSettled();
    }

    public static void Test_notify_neighbors(long levelId, double x, double y, double z) {
        withEntity(
                "Test.notify_neighbors",
                "level",
                levelId,
                level -> GrugCore.getAdapter().notifyNeighbors(level, x, y, z));
    }

    public static void Test_use_block(long levelId, double x, double y, double z) {
        withEntity(
                "Test.use_block",
                "level",
                levelId,
                level -> GrugCore.getAdapter().useBlockForTest(level, x, y, z));
    }

    public static boolean Test_mining_crack(long levelId, double x, double y, double z) {
        return withEntity(
                "Test.mining_crack",
                "level",
                levelId,
                level -> GrugCore.getAdapter().miningCrackForTest(level, x, y, z),
                false);
    }

    public static boolean Test_block_collision(
            long levelId,
            double x,
            double y,
            double z,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        return GrugCore.getAdapter()
                .blockCollisionIntersects(
                        GrugEntities.entityData.get(levelId).object,
                        x,
                        y,
                        z,
                        minX,
                        minY,
                        minZ,
                        maxX,
                        maxY,
                        maxZ);
    }

    public static boolean Test_block_selection(
            long levelId,
            double x,
            double y,
            double z,
            double pointX,
            double pointY,
            double pointZ) {
        return GrugCore.getAdapter()
                .blockSelectionContains(
                        GrugEntities.entityData.get(levelId).object,
                        x,
                        y,
                        z,
                        pointX,
                        pointY,
                        pointZ);
    }

    public static boolean Test_block_ray_trace(
            long levelId,
            double x,
            double y,
            double z,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ) {
        return GrugCore.getAdapter()
                .blockRayTraceHits(
                        GrugEntities.entityData.get(levelId).object,
                        x,
                        y,
                        z,
                        startX,
                        startY,
                        startZ,
                        endX,
                        endY,
                        endZ);
    }

    public static long Test_screenshot() {
        // A test that builds a screenshot entity is about to capture, so it wants frames drawn from
        // here, which is before the settle wait in front of the capture. See GrugFrameRequest.
        GrugFrameRequest.request();
        return GrugEntities.addEntity(GrugEntityType.Screenshot, new GrugScreenshot(0));
    }

    /**
     * Raises the running test's tick budget. Only the test's {@code init()} is passed the context
     * this lives on, so a test can neither call it twice nor after it has started.
     *
     * <p>A test can only raise its budget: the default is the ceiling for a test that did not ask
     * for more, and a smaller number would only make a slow runner fail a test that fits the
     * default. A value that is not a whole number from the default up to what an int holds is a
     * mistake in the mod, reported without changing the budget.
     */
    public static void TestInitContext_set_max_ticks(long contextId, double ticks) {
        if (!requireInitContext("TestInitContext.set_max_ticks", contextId)) return;
        if (ticks != Math.floor(ticks)
                || ticks < GrugTestRunner.DEFAULT_MAX_TEST_TICKS
                || ticks > Integer.MAX_VALUE) {
            GrugModTreeDefect.report(
                    "TestInitContext.set_max_ticks: The ticks have to be a whole number from "
                            + GrugTestRunner.DEFAULT_MAX_TEST_TICKS
                            + " to "
                            + Integer.MAX_VALUE
                            + ", and were "
                            + ticks
                            + ".");
            return;
        }
        GrugTestState.maxTestTicks = (int) ticks;
    }

    public static double TestTickContext_current_tick(long contextId) {
        if (!requireTickContext("TestTickContext.current_tick", contextId)) return 0.0;
        return (double) GrugTestState.currentTestTick;
    }

    public static void TestTickContext_not_done(long contextId) {
        if (!requireTickContext("TestTickContext.not_done", contextId)) return;
        GrugTestState.testNotDone = true;
    }

    public static void TestTickContext_expect_error(long contextId, String message) {
        if (!requireTickContext("TestTickContext.expect_error", contextId)) return;
        GrugTestState.testExpectedError = message;
    }

    // BlockEntity methods

    public static boolean BlockEntity_is_slot_extractable(long blockEntityId, double slot) {
        return GrugSlotAccess.isExtractable(
                HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
    }

    public static boolean BlockEntity_is_slot_insertable(long blockEntityId, double slot) {
        return GrugSlotAccess.isInsertable(
                HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
    }

    public static double BlockEntity_ask_extract_item(long blockEntityId, double fromSide) {
        return GrugSpecialInventory.askExtract(
                HostFunctionHelpers.resolveBlockEntity(blockEntityId), fromSide);
    }

    public static double BlockEntity_extract_item_into(
            long blockEntityId, long destinationId, double destinationSlot, double fromSide) {
        return GrugSpecialInventory.extractInto(
                HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                HostFunctionHelpers.resolveBlockEntity(destinationId),
                destinationSlot,
                fromSide);
    }

    public static double BlockEntity_add_item(
            long blockEntityId,
            long itemId,
            double damage,
            double count,
            double fromSide,
            boolean add) {
        return withEntity(
                "BlockEntity.add_item",
                "item",
                itemId,
                item ->
                        GrugSpecialInventory.addItem(
                                HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                                item,
                                itemId,
                                damage,
                                count,
                                fromSide,
                                add),
                0.0);
    }

    public static void BlockEntity_consume_crafting_ingredients(
            long blockEntityId, double startSlot) {
        GrugCore.getAdapter()
                .consumeCraftingIngredients(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), startSlot);
    }

    public static double BlockEntity_count_item_in_inventory(
            long blockEntityId, long itemId, double damage) {
        return withEntity(
                "BlockEntity.count_item_in_inventory",
                "item",
                itemId,
                item ->
                        GrugCore.getAdapter()
                                .countItemInInventory(
                                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                                        item,
                                        damage),
                0.0);
    }

    public static double BlockEntity_extract_item_from_inventory(
            long blockEntityId, long itemId, double damage, double amount) {
        return withEntity(
                "BlockEntity.extract_item_from_inventory",
                "item",
                itemId,
                item ->
                        GrugCore.getAdapter()
                                .extractItemFromInventory(
                                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                                        item,
                                        damage,
                                        amount),
                0.0);
    }

    public static double BlockEntity_insert_item_into_inventory(
            long blockEntityId, long itemId, double damage, double amount, boolean insert) {
        return withEntity(
                "BlockEntity.insert_item_into_inventory",
                "item",
                itemId,
                item ->
                        GrugCore.getAdapter()
                                .insertItemIntoInventory(
                                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                                        item,
                                        damage,
                                        amount,
                                        insert),
                0.0);
    }

    public static long BlockEntity_level(long blockEntityId) {
        Object level =
                GrugCore.getAdapter()
                        .getBlockEntityLevel(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
        return GrugEntities.addEntity(GrugEntityType.Level, level);
    }

    public static long BlockEntity_block_pos(long blockEntityId) {
        BlockPos pos =
                GrugCore.getAdapter()
                        .getBlockPosOfBlockEntity(
                                HostFunctionHelpers.resolveBlockEntity(blockEntityId));
        return GrugEntities.addEntity(GrugEntityType.BlockPos, pos);
    }

    public static double BlockEntity_get_crafting_result_damage(long blockEntityId) {
        Object stack =
                GrugCore.getAdapter()
                        .getResultStack(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
        return stack != null ? GrugCore.getAdapter().getStackDamage(stack) : 0;
    }

    public static long BlockEntity_get_crafting_result_item(long blockEntityId) {
        Object stack =
                GrugCore.getAdapter()
                        .getResultStack(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
        if (stack != null) {
            return GrugEntities.addEntity(
                    GrugEntityType.Option,
                    new GrugOption(
                            GrugEntities.addEntity(
                                    GrugEntityType.Item,
                                    GrugCore.getAdapter().getStackItem(stack))));
        }
        return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
    }

    public static double BlockEntity_get_inventory_size(long blockEntityId) {
        return GrugCore.getAdapter()
                .getInventorySize(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
    }

    public static double BlockEntity_get_item_count_in_slot(long blockEntityId, double slot) {
        return GrugCore.getAdapter()
                .getItemCountInSlot(HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
    }

    public static double BlockEntity_get_item_damage_in_slot(long blockEntityId, double slot) {
        return GrugCore.getAdapter()
                .getItemDamageInSlot(HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
    }

    public static long BlockEntity_get_item_in_slot(long blockEntityId, double slot) {
        Object item =
                GrugCore.getAdapter()
                        .getItemInSlot(HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot);
        if (item != null) {
            return GrugEntities.addEntity(
                    GrugEntityType.Option,
                    new GrugOption(GrugEntities.addEntity(GrugEntityType.Item, item)));
        }
        return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
    }

    public static double BlockEntity_get_saved_number(long blockEntityId, String name) {
        Object blockEntity = HostFunctionHelpers.resolveBlockEntity(blockEntityId);
        if (!GrugSavedNumbers.isHolder(blockEntity)) {
            reportNotSaving("get_saved_number");
            return 0;
        }
        return GrugSavedNumbers.get(blockEntity, name);
    }

    public static void BlockEntity_set_saved_number(long blockEntityId, String name, double value) {
        if (!GrugSavedNumbers.set(
                HostFunctionHelpers.resolveBlockEntity(blockEntityId), name, value)) {
            reportNotSaving("set_saved_number");
        }
    }

    public static double BlockEntity_get_saved_list_size(long blockEntityId, String name) {
        return withSavedLists(
                "get_saved_list_size",
                blockEntityId,
                blockEntity -> (double) GrugSavedLists.size(blockEntity, name),
                ZERO);
    }

    public static double BlockEntity_append_saved_list_record(long blockEntityId, String name) {
        return withSavedLists(
                "append_saved_list_record",
                blockEntityId,
                blockEntity -> (double) GrugSavedLists.append(blockEntity, name),
                ZERO);
    }

    public static void BlockEntity_remove_saved_list_record(
            long blockEntityId, String name, double index) {
        withSavedRecord(
                "remove_saved_list_record",
                blockEntityId,
                name,
                index,
                (blockEntity, record) -> {
                    GrugSavedLists.remove(blockEntity, name, (int) index);
                    return null;
                },
                NO_RESULT);
    }

    public static double BlockEntity_get_saved_list_number(
            long blockEntityId, String name, double index, String key) {
        return withSavedRecord(
                "get_saved_list_number",
                blockEntityId,
                name,
                index,
                (blockEntity, record) -> {
                    Double value = record.numbers().get(key);
                    return value != null ? value : 0.0;
                },
                ZERO);
    }

    public static void BlockEntity_set_saved_list_number(
            long blockEntityId, String name, double index, String key, double value) {
        withSavedRecord(
                "set_saved_list_number",
                blockEntityId,
                name,
                index,
                (blockEntity, record) -> {
                    GrugSavedLists.setNumber(blockEntity, record, key, value);
                    return null;
                },
                NO_RESULT);
    }

    public static long BlockEntity_get_saved_list_item(
            long blockEntityId, String name, double index) {
        return withSavedRecord(
                "get_saved_list_item",
                blockEntityId,
                name,
                index,
                (blockEntity, record) -> itemOption(record.item()),
                EMPTY_ITEM_OPTION);
    }

    public static void BlockEntity_set_saved_list_item(
            long blockEntityId, String name, double index, long itemId) {
        withSavedRecord(
                "set_saved_list_item",
                blockEntityId,
                name,
                index,
                (blockEntity, record) ->
                        withEntity(
                                "BlockEntity.set_saved_list_item",
                                "item",
                                itemId,
                                item -> {
                                    GrugSavedLists.setItem(blockEntity, record, item);
                                    return null;
                                },
                                NO_RESULT),
                NO_RESULT);
    }

    public static void BlockEntityInitContext_set_power_receiver(long contextId, double maxStored) {
        Object blockEntity =
                requireBlockEntityInitContext(
                        "BlockEntityInitContext.set_power_receiver", contextId);
        if (blockEntity == null) return;

        String problem = GrugPower.checkEnergy("The most a receiver holds", maxStored);
        if (problem != null) {
            // A bounded defect: the block entity stays what it was, and the report names the call.
            GrugModTreeDefect.report("BlockEntityInitContext.set_power_receiver: " + problem);
            return;
        }
        GrugPower.declare(blockEntity, maxStored);
    }

    /**
     * The block entity whose {@code init()} {@code contextId} was passed to, or null, after a
     * report, when that call is not the one running now.
     */
    private static Object requireBlockEntityInitContext(String method, long contextId) {
        if (!requireContext(method, "init", contextId, GrugEntities.blockEntityInitContext())) {
            return null;
        }
        return GrugEntities.blockEntityInitContext().blockEntity;
    }

    public static boolean BlockEntity_is_power_receiver(long blockEntityId) {
        return GrugPower.isReceiver(HostFunctionHelpers.resolveBlockEntity(blockEntityId));
    }

    public static double BlockEntity_get_energy_stored(long blockEntityId) {
        Object blockEntity = HostFunctionHelpers.resolveBlockEntity(blockEntityId);
        String problem = GrugPower.checkIsReceiver(blockEntity);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "get_energy_stored: " + problem);
            return 0;
        }
        return GrugPower.stored(blockEntity);
    }

    public static double BlockEntity_receive_energy(long blockEntityId, double amount) {
        Object blockEntity = HostFunctionHelpers.resolveBlockEntity(blockEntityId);
        String problem = GrugPower.checkIsReceiver(blockEntity);
        if (problem == null) {
            problem = GrugPower.checkEnergy("The amount", amount);
        }
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "receive_energy: " + problem);
            return 0;
        }
        return GrugPower.receive(blockEntity, amount);
    }

    public static double BlockEntity_extract_energy(long blockEntityId, double max) {
        Object blockEntity = HostFunctionHelpers.resolveBlockEntity(blockEntityId);
        String problem = GrugPower.checkIsReceiver(blockEntity);
        if (problem == null) {
            problem = GrugPower.checkEnergy("The most to take", max);
        }
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "extract_energy: " + problem);
            return 0;
        }
        return GrugPower.extract(blockEntity, max);
    }

    public static void BlockEntity_redraw(long blockEntityId) {
        Object blockEntity = HostFunctionHelpers.resolveBlockEntity(blockEntityId);
        if (!GrugSavedNumbers.isHolder(blockEntity)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "redraw: Only a grug block entity has a render function to draw again.");
            return;
        }
        GrugCore.getAdapter().redraw(blockEntity);
    }

    public static void BlockEntity_set_item_count_in_slot(
            long blockEntityId, double slot, double count) {
        GrugCore.getAdapter()
                .setItemCountInSlot(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot, count);
    }

    public static void BlockEntity_set_item_damage_in_slot(
            long blockEntityId, double slot, double damage) {
        GrugCore.getAdapter()
                .setItemDamageInSlot(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot, damage);
    }

    public static void BlockEntity_set_item_in_slot(
            long blockEntityId, double slot, long itemId, double count) {
        withEntity(
                "BlockEntity.set_item_in_slot",
                "item",
                itemId,
                item ->
                        GrugCore.getAdapter()
                                .setItemInSlot(
                                        HostFunctionHelpers.resolveBlockEntity(blockEntityId),
                                        slot,
                                        item,
                                        count));
    }

    public static void BlockEntityInitContext_set_extractable_slots(
            long contextId, double first, double last) {
        Object blockEntity =
                requireBlockEntityInitContext(
                        "BlockEntityInitContext.set_extractable_slots", contextId);
        if (blockEntity == null) return;

        GrugSlotAccess.declareExtractable(blockEntity, first, last);
    }

    public static void BlockEntityInitContext_set_insertable_slots(
            long contextId, double first, double last) {
        Object blockEntity =
                requireBlockEntityInitContext(
                        "BlockEntityInitContext.set_insertable_slots", contextId);
        if (blockEntity == null) return;

        GrugSlotAccess.declareInsertable(blockEntity, first, last);
    }

    public static double BlockEntity_take_crafting_result(long blockEntityId, double amount) {
        return GrugCore.getAdapter()
                .takeCraftingResult(HostFunctionHelpers.resolveBlockEntity(blockEntityId), amount);
    }

    public static double BlockEntity_take_item_from_slot(
            long blockEntityId, double slot, double amount) {
        return GrugCore.getAdapter()
                .takeItemFromSlot(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), slot, amount);
    }

    public static void BlockEntity_update_recipe_output(long blockEntityId, double startSlot) {
        GrugCore.getAdapter()
                .updateRecipeOutput(
                        HostFunctionHelpers.resolveBlockEntity(blockEntityId), startSlot);
    }

    // Classes

    public static void ExtractResponse_refuse(long responseId) {
        withEntity(
                "ExtractResponse.refuse",
                "response",
                responseId,
                response -> ((ExtractResponse) response).refuse());
    }

    public static void ExtractResponse_give_from_slot(long responseId, double slot, double amount) {
        withEntity(
                "ExtractResponse.give_from_slot",
                "response",
                responseId,
                response -> ((ExtractResponse) response).giveFromSlot(slot, amount));
    }

    public static void ExtractResponse_give_crafting_result(long responseId) {
        withEntity(
                "ExtractResponse.give_crafting_result",
                "response",
                responseId,
                response -> ((ExtractResponse) response).giveCraftingResult());
    }

    public static void ExtractResponse_give_crafting_result_consumed(long responseId) {
        withEntity(
                "ExtractResponse.give_crafting_result_consumed",
                "response",
                responseId,
                response -> ((ExtractResponse) response).giveCraftingResultConsumed());
    }

    public static void AddResponse_refuse(long responseId) {
        withEntity(
                "AddResponse.refuse",
                "response",
                responseId,
                response -> ((AddResponse) response).refuse());
    }

    public static void AddResponse_accept_into_slot(long responseId, double slot) {
        withEntity(
                "AddResponse.accept_into_slot",
                "response",
                responseId,
                response -> ((AddResponse) response).acceptIntoSlot(slot));
    }

    public static long BlockPos_above_n(long blockPosId, double n) {
        return withEntity(
                "BlockPos.above_n",
                "block position",
                blockPosId,
                position -> {
                    BlockPos pos = (BlockPos) position;
                    return GrugEntities.addEntity(
                            GrugEntityType.BlockPos,
                            new BlockPos(pos.x(), pos.y() + (int) n, pos.z()));
                },
                0L);
    }

    public static long BlockPos_center(long blockPosId) {
        return withEntity(
                "BlockPos.center",
                "block position",
                blockPosId,
                position -> {
                    BlockPos pos = (BlockPos) position;
                    return GrugEntities.addEntity(
                            GrugEntityType.Vec3,
                            new Vec3(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5));
                },
                0L);
    }

    public static long Color_rgb(double r, double g, double b) {
        return GrugEntities.addEntity(GrugEntityType.Color, new Color((int) r, (int) g, (int) b));
    }

    public static void Entity_set_delta_movement(long entityId, long vec3Id) {
        withEntities(
                "Entity.set_delta_movement",
                "entity",
                entityId,
                "vec3",
                vec3Id,
                (entity, vec) -> {
                    Vec3 movement = (Vec3) vec;
                    GrugCore.getAdapter()
                            .setEntityDeltaMovement(
                                    entity, movement.x(), movement.y(), movement.z());
                });
    }

    public static void Entity_spawn(long entityId, long levelId) {
        withEntities(
                "Entity.spawn",
                "entity",
                entityId,
                "level",
                levelId,
                (entity, world) -> GrugCore.getAdapter().spawnEntity(world, entity));
    }

    public static void GUI_add_crafting_grid(long guiId, double startSlot, double x, double y) {
        withEntity(
                "GUI.add_crafting_grid",
                "GUI",
                guiId,
                gui ->
                        ((GrugGuiBuilder) gui)
                                .craftingGrids.add(
                                        new GrugGuiBuilder.CraftingGridDef(
                                                (int) startSlot, (int) x, (int) y)));
    }

    public static void GUI_add_crafting_result(long guiId, double x, double y) {
        withEntity(
                "GUI.add_crafting_result",
                "GUI",
                guiId,
                gui ->
                        ((GrugGuiBuilder) gui)
                                .craftingResults.add(
                                        new GrugGuiBuilder.CraftingResultDef((int) x, (int) y)));
    }

    public static void GUI_add_player_inventory(
            long guiId, double mainX, double mainY, double hotbarX, double hotbarY) {
        withEntity(
                "GUI.add_player_inventory",
                "GUI",
                guiId,
                gui -> {
                    GrugGuiBuilder builder = (GrugGuiBuilder) gui;
                    builder.hasPlayerInventory = true;
                    builder.playerInvX = (int) mainX;
                    builder.playerInvY = (int) mainY;
                    builder.hotbarX = (int) hotbarX;
                    builder.hotbarY = (int) hotbarY;
                });
    }

    public static void GUI_add_text(long guiId, String text, double x, double y, long colorId) {
        withEntity(
                "GUI.add_text",
                "GUI",
                guiId,
                gui ->
                        withEntity(
                                "GUI.add_text",
                                "color",
                                colorId,
                                color ->
                                        ((GrugGuiBuilder) gui)
                                                .texts.add(
                                                        new GrugGuiBuilder.TextDef(
                                                                text,
                                                                (int) x,
                                                                (int) y,
                                                                ((Color) color).getRGB()))));
    }

    public static void GUI_open(long guiId, long playerId, long blockEntityId) {
        withEntities(
                "GUI.open",
                "GUI",
                guiId,
                "player",
                playerId,
                (gui, player) -> {
                    GrugGuiBuilder builder = (GrugGuiBuilder) gui;
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
                });
    }

    public static long ItemEntity_entity(long itemEntityId) {
        return itemEntityId;
    }

    public static double ItemEntity_damage(long itemEntityId) {
        return withEntity(
                "ItemEntity.damage",
                "item entity",
                itemEntityId,
                itemEntity -> GrugCore.getAdapter().getItemEntityDamage(itemEntity),
                0.0);
    }

    public static long Level_get_block_entity(long levelId, double x, double y, double z) {
        return withEntity(
                "Level.get_block_entity",
                "level",
                levelId,
                level -> {
                    Object be = GrugCore.getAdapter().getBlockEntity(level, x, y, z);
                    // TODO: Should this not call Grug.hostFunctionErrorHappened when be is null?
                    if (be != null) {
                        return GrugEntities.addEntity(
                                GrugEntityType.Option,
                                new GrugOption(
                                        GrugEntities.addEntity(GrugEntityType.BlockEntity, be)));
                    }
                    return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
                },
                0L);
    }

    public static void Level_place_block(
            long levelId, double x, double y, double z, String blockName) {
        withEntity(
                "Level.place_block",
                "level",
                levelId,
                level -> GrugCore.getAdapter().placeBlock(level, x, y, z, blockName));
    }

    public static void Level_explode(long levelId, double x, double y, double z, double power) {
        String[] names = {"x", "y", "z", "power"};
        double[] values = {x, y, z, power};
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr,
                        "explode: " + names[i] + " must be finite, got " + values[i]);
                return;
            }
        }
        // The games take a float, and a finite double beyond its range would become an infinite
        // explosion in the cast.
        if (power < 0 || power > Float.MAX_VALUE) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "explode: power must be from 0 to " + Float.MAX_VALUE + ", got " + power);
            return;
        }
        withEntity(
                "Level.explode",
                "level",
                levelId,
                level -> GrugCore.getAdapter().explode(level, x, y, z, power));
    }

    public static boolean Level_has_neighbor_signal(long levelId, double x, double y, double z) {
        return withEntity(
                "Level.has_neighbor_signal",
                "level",
                levelId,
                level -> GrugCore.getAdapter().hasNeighborSignal(level, x, y, z),
                false);
    }

    public static double Level_get_time(long levelId) {
        return withEntity(
                "Level.get_time",
                "level",
                levelId,
                level -> (double) GrugCore.getAdapter().getWorldTime(level),
                0.0);
    }

    public static double Level_random_int(long levelId, double bound) {
        if (bound < 1 || bound > Integer.MAX_VALUE || bound != Math.floor(bound)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "random_int: The bound has to be a whole number from 1 to "
                            + Integer.MAX_VALUE
                            + ", and was "
                            + bound
                            + ".");
            return 0;
        }
        return withEntity(
                "Level.random_int",
                "level",
                levelId,
                level -> (double) GrugCore.getAdapter().nextRandomInt(level, (int) bound),
                0.0);
    }

    public static double Level_random_float(long levelId) {
        return withEntity(
                "Level.random_float",
                "level",
                levelId,
                level -> GrugCore.getAdapter().nextRandomFloat(level),
                0.0);
    }

    public static double Level_random_gaussian(long levelId) {
        return withEntity(
                "Level.random_gaussian",
                "level",
                levelId,
                level -> GrugCore.getAdapter().nextRandomGaussian(level),
                0.0);
    }

    public static String Level_get_block(long levelId, double x, double y, double z) {
        return withEntity(
                "Level.get_block",
                "level",
                levelId,
                level -> GrugCore.getAdapter().getBlock(level, x, y, z),
                "");
    }

    public static boolean Level_is_air(long levelId, double x, double y, double z) {
        return withEntity(
                "Level.is_air",
                "level",
                levelId,
                level -> GrugCore.getAdapter().isAir(level, x, y, z),
                false);
    }

    public static double Level_count_item_entities(
            long levelId, double x, double y, double z, double radius) {
        return withEntity(
                "Level.count_item_entities",
                "level",
                levelId,
                level -> GrugCore.getAdapter().countItemEntities(level, x, y, z, radius),
                0.0);
    }

    public static long Level_find_item_entity(
            long levelId, double x, double y, double z, double radius) {
        return withEntity(
                "Level.find_item_entity",
                "level",
                levelId,
                level -> {
                    Object entity = GrugCore.getAdapter().findItemEntity(level, x, y, z, radius);
                    if (entity != null) {
                        return GrugEntities.addEntity(
                                GrugEntityType.Option,
                                new GrugOption(
                                        GrugEntities.addEntity(GrugEntityType.ItemEntity, entity)));
                    }
                    return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
                },
                0L);
    }

    public static boolean Option_has(long optionId) {
        return withEntity(
                "Option.has", "option", optionId, option -> ((GrugOption) option).has(), false);
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
        GrugObject existing = GrugEntities.entityData.get(optionId);
        if (existing == null) {
            GrugEntities.addEntityWithId(optionId, GrugEntityType.Option, new GrugOption(value));
            return;
        }
        ((GrugOption) existing.object).set(value);
    }

    public static Object Option_unwrap(long optionId) {
        return withEntity(
                "Option.unwrap",
                "option",
                optionId,
                option -> {
                    GrugOption opt = (GrugOption) option;
                    if (!opt.has()) {
                        Grug.hostFunctionErrorHappened(
                                Grug.statePtr, "Tried to unwrap an empty Option!");
                        return null;
                    }
                    return opt.value();
                },
                null);
    }

    public static long Option_new() {
        return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
    }

    public static long Screenshot_tolerance(long screenshotId, double percent) {
        String problem = GrugTestState.checkTolerance(percent);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.tolerance: " + problem);
            return 0;
        }
        // GrugScreenshot is immutable, so setting a tolerance replaces the object stored under the
        // entity id rather than mutating it in place, the same way Option.set works.
        GrugEntities.addEntityWithId(
                screenshotId, GrugEntityType.Screenshot, new GrugScreenshot(percent));
        return screenshotId;
    }

    public static void Screenshot_equals(
            long screenshotId, String referencePath, double x1, double y1, double x2, double y2) {
        // A capture reads a drawn frame, so the test wants one even if it reached this call without
        // Test.screenshot. See GrugFrameRequest.
        GrugFrameRequest.request();
        withEntity(
                "Screenshot.equals",
                "screenshot",
                screenshotId,
                screenshot ->
                        GrugCore.getAdapter()
                                .assertScreenshotEquals(
                                        referencePath,
                                        x1,
                                        y1,
                                        x2,
                                        y2,
                                        ((GrugScreenshot) screenshot).tolerancePercent()));
    }

    /**
     * Reads the rectangle into this entity, so a later {@code Screenshot.differs_from} can compare
     * it with another capture of the same rectangle.
     *
     * <p>Whole percentages only on {@code differs_from}, for the same reason {@code tolerance} is:
     * the arithmetic is over a pixel count.
     */
    public static long Screenshot_capture(
            long screenshotId, double x1, double y1, double x2, double y2) {
        // A capture reads a drawn frame. See GrugFrameRequest.
        GrugFrameRequest.request();
        withEntity(
                "Screenshot.capture",
                "screenshot",
                screenshotId,
                screenshot -> {
                    BufferedImage capture = GrugCore.getAdapter().captureScreenshot(x1, y1, x2, y2);
                    // The adapter has already reported a capture it could not take, so the null
                    // is stored rather than thrown from here. Storing it is what lets
                    // differs_from name the missing capture, which tells a test author which of
                    // the two screenshots to look at, where an unchecked one would throw a
                    // NullPointerException from inside the comparison and leave them to guess.
                    GrugEntities.addEntityWithId(
                            screenshotId,
                            GrugEntityType.Screenshot,
                            ((GrugScreenshot) screenshot).withCapture(capture));
                });
        return screenshotId;
    }

    public static void Screenshot_differs_from(long screenshotId, long otherId, double percent) {
        String name = "Screenshot.differs_from";
        withEntity(
                name,
                "screenshot",
                screenshotId,
                screenshot ->
                        withEntity(
                                name,
                                "screenshot",
                                otherId,
                                other -> {
                                    GrugScreenshot shot = (GrugScreenshot) screenshot;
                                    GrugScreenshot otherShot = (GrugScreenshot) other;
                                    if (percent < 0
                                            || percent > 100
                                            || percent != Math.floor(percent)) {
                                        Grug.hostFunctionErrorHappened(
                                                Grug.statePtr,
                                                name
                                                        + ": percent must be a whole number"
                                                        + " between 0 and 100, but got "
                                                        + percent
                                                        + ".");
                                        return null;
                                    }
                                    if (shot.captured() == null || otherShot.captured() == null) {
                                        Grug.hostFunctionErrorHappened(
                                                Grug.statePtr,
                                                name
                                                        + ": both screenshots have to be captured"
                                                        + " first, with Screenshot.capture,"
                                                        + " before they can be compared.");
                                        return null;
                                    }

                                    double changed;
                                    try {
                                        changed =
                                                GrugScreenshots.changedPercent(
                                                        shot.captured(), otherShot.captured());
                                    } catch (IllegalArgumentException e) {
                                        Grug.hostFunctionErrorHappened(
                                                Grug.statePtr, name + ": " + e.getMessage());
                                        return null;
                                    }

                                    if (changed < percent) {
                                        // One decimal place, so a share just short of the
                                        // threshold does not read as having met it, and a whole
                                        // number does not read as if it were rounded down from
                                        // something higher.
                                        Grug.hostFunctionErrorHappened(
                                                Grug.statePtr,
                                                name
                                                        + ": the frame still shows what it showed"
                                                        + " before, since only "
                                                        + String.format(
                                                                Locale.ROOT, "%.1f", changed)
                                                        + "% of its pixels changed and at least "
                                                        + (int) percent
                                                        + "% was asked for.");
                                    }
                                    return null;
                                },
                                null));
    }

    /**
     * Where a host function reports an entity id that no longer resolves.
     *
     * <p>A report goes to the running grug state, which a unit test does not have, so a Java test
     * swaps this for a recorder to drive the guard, and restores it when it is done. Nothing but a
     * test assigns it.
     */
    static Consumer<String> staleIdReport = HostFunctions::reportStaleId;

    @GrugGenerated("a report goes to the live grug state, which a unit test does not have")
    private static void reportStaleId(String message) {
        Grug.hostFunctionErrorHappened(Grug.statePtr, message);
    }

    /**
     * Runs {@code use} with the object {@code id} names, or reports the id and answers {@code
     * missing} without touching anything.
     *
     * <p>A host entity is held weakly, so an id resolves only while something roots the object
     * behind it: a test's root list for the whole test (see {@link GrugEntities#createTestEntity}),
     * a block entity's list for the one call that swapped it in, or a script's member list. An id
     * whose object was collected in the meantime dereferenced to null and threw inside the JNI
     * call, which printed and cleared the exception and took the game's main thread down without
     * naming the test. Every host function that resolves an id goes through here, so a stale one is
     * a report naming the method and the id, and the call answers what its signature needs instead
     * of dereferencing nothing. See #895.
     */
    static <T> T withEntity(
            String method, String kind, long id, Function<Object, T> use, T missing) {
        GrugObject object = GrugEntities.entityData.get(id);
        if (object == null) {
            staleIdReport.accept(
                    method
                            + ": the "
                            + kind
                            + " id "
                            + id
                            + " no longer resolves, because the object behind it was collected"
                            + " while the script still held it.");
            return missing;
        }
        return use.apply(object.object);
    }

    /** Runs {@code use} with the object {@code id} names, or reports it and runs nothing. */
    static void withEntity(String method, String kind, long id, Consumer<Object> use) {
        withEntity(
                method,
                kind,
                id,
                object -> {
                    use.accept(object);
                    return null;
                },
                null);
    }

    /**
     * Runs {@code use} with the objects the two ids name, or reports the first that no longer
     * resolves and runs nothing.
     */
    static <T> T withEntities(
            String method,
            String firstKind,
            long firstId,
            String secondKind,
            long secondId,
            BiFunction<Object, Object, T> use,
            T missing) {
        return withEntity(
                method,
                firstKind,
                firstId,
                first ->
                        withEntity(
                                method,
                                secondKind,
                                secondId,
                                second -> use.apply(first, second),
                                missing),
                missing);
    }

    /** Runs {@code use} with the objects the two ids name, or reports one and runs nothing. */
    static void withEntities(
            String method,
            String firstKind,
            long firstId,
            String secondKind,
            long secondId,
            BiConsumer<Object, Object> use) {
        withEntities(
                method,
                firstKind,
                firstId,
                secondKind,
                secondId,
                (first, second) -> {
                    use.accept(first, second);
                    return null;
                },
                null);
    }

    public static long Vec3_new(double x, double y, double z) {
        double[] values = {x, y, z};
        String[] names = {"x", "y", "z"};
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr,
                        "Vec3.new: " + names[i] + " must be finite, got " + values[i]);
                return GrugEntities.addEntity(GrugEntityType.Vec3, new Vec3(0, 0, 0));
            }
        }
        return GrugEntities.addEntity(GrugEntityType.Vec3, new Vec3(x, y, z));
    }

    public static double Vec3_x(long vec3Id) {
        return withEntity("Vec3.x", "vec3", vec3Id, vec3 -> ((Vec3) vec3).x(), 0.0);
    }

    public static double Vec3_y(long vec3Id) {
        return withEntity("Vec3.y", "vec3", vec3Id, vec3 -> ((Vec3) vec3).y(), 0.0);
    }

    public static double Vec3_z(long vec3Id) {
        return withEntity("Vec3.z", "vec3", vec3Id, vec3 -> ((Vec3) vec3).z(), 0.0);
    }

    public static long Vec3_zero() {
        return GrugEntities.addEntity(GrugEntityType.Vec3, new Vec3(0, 0, 0));
    }

    // Host functions

    public static void drop_inventory(long levelId, double x, double y, double z) {
        withEntity(
                "drop_inventory",
                "level",
                levelId,
                level -> GrugCore.getAdapter().dropInventory(level, x, y, z));
    }

    public static boolean equals(Object a, Object b) {
        return valuesEqual(a, b);
    }

    @GrugGenerated("equality: the entity-id branch cannot be produced from grug without crashing")
    private static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Long && b instanceof Long) {
            Long idA = (Long) a;
            Long idB = (Long) b;
            GrugObject objA = GrugEntities.entityData.get(idA);
            GrugObject objB = GrugEntities.entityData.get(idB);
            if (objA != null && objB != null) {
                return java.util.Objects.equals(objA.object, objB.object);
            }
        }
        return java.util.Objects.equals(a, b);
    }

    /**
     * Gives the player one of the item, the way the game gives an item to a player: into their
     * inventory, and dropped at their feet if it is full. This is what the overlay's click() calls,
     * so a click on its list hands the clicked item to the player.
     *
     * <p>The item is added to the integrated server's copy of the player, not the client's, because
     * that is the copy the game saves and the one a test reads back; see the adapter's own method.
     */
    public static void give_item(long itemId) {
        withEntity("give_item", "item", itemId, HostFunctions::giveItem);
    }

    /**
     * Gives one of {@code item} to the player a test runs as.
     *
     * <p>Reading that player and giving through it needs a game, so it stays behind {@link
     * GrugGenerated}; what a unit test drives, and does, is the id guard in the caller and the
     * stale item it reports.
     */
    @GrugGenerated("gives through the adapter's player: a unit test has no game")
    private static void giveItem(Object item) {
        Object player = playerToGiveTo("give_item");
        if (player != null) {
            GrugCore.getAdapter().giveItemToPlayer(player, item);
        }
    }

    /**
     * Gives the player a full stack of the item, the way the game gives a player an item: into
     * their inventory, and dropped at their feet if it is full. The stack holds the item's maximum
     * stack size, which is the amount the reference's stack binding gives, so a tool still comes as
     * one item and a block as 64.
     *
     * <p>Added to the integrated server's copy of the player for the same reason as {@code
     * give_item}.
     */
    public static void give_item_stack(long itemId) {
        withEntity("give_item_stack", "item", itemId, HostFunctions::giveItemStack);
    }

    /** Gives a full stack of {@code item} to the player a test runs as. */
    @GrugGenerated("gives through the adapter's player: a unit test has no game")
    private static void giveItemStack(Object item) {
        Object player = playerToGiveTo("give_item_stack");
        if (player != null) {
            GrugCore.getAdapter().giveItemStackToPlayer(player, item);
        }
    }

    /**
     * The player to give an item to, or null after reporting that there is nobody yet.
     *
     * <p>Both giving functions name themselves in the report, since which one a test called is what
     * tells it which item did not arrive.
     */
    @GrugGenerated("asks the adapter for its test player: a unit test has no game")
    private static Object playerToGiveTo(String method) {
        Object player = GrugCore.getAdapter().testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, method + ": There is no player to give to yet.");
        }
        return player;
    }

    public static long gui(String texturePath) {
        return GrugEntities.addEntity(GrugEntityType.GUI, new GrugGuiBuilder(texturePath));
    }

    public static long item(long resourceLocationId) {
        return withEntity(
                "item",
                "resource location",
                resourceLocationId,
                resourceLocation ->
                        GrugEntities.addEntity(
                                GrugEntityType.Item,
                                GrugCore.getAdapter().getItemFromRegistry(resourceLocation)),
                0L);
    }

    /**
     * The item at index, from 0 up to item_count minus 1, in the order the host lists its items.
     *
     * <p>An index outside that range is reported and answers nothing, since there is no item there
     * to hand back; the report fails the script call, so the zero never reaches the mod. The checks
     * themselves are in {@link #itemIndexProblem} and {@link #itemAtIndexProblem}, which a Java
     * test drives without a game; what is left here is the adapter's own item list.
     */
    @GrugGenerated("reads the adapter's item list: a unit test has no game")
    public static long item_at(double index) {
        String problem = itemIndexProblem(index);
        if (problem == null) {
            problem = itemAtIndexProblem((int) index, GrugCore.getAdapter().itemCount());
        }
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, problem);
            return 0;
        }

        Object item = GrugCore.getAdapter().itemByIndex((int) index);
        if (item == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "item_at: The item at index " + (int) index + " could not be read.");
            return 0;
        }
        return GrugEntities.addEntity(GrugEntityType.Item, item);
    }

    /** Why item_at refuses this index, or null when it is a whole number from 0 up. */
    static String itemIndexProblem(double index) {
        if (index < 0 || index > Integer.MAX_VALUE || index != Math.floor(index)) {
            return "item_at: The index has to be a whole number from 0 to "
                    + Integer.MAX_VALUE
                    + ", and was "
                    + index
                    + ".";
        }
        return null;
    }

    /**
     * Why item_at cannot answer this index from a list of {@code count} items, or null when it can.
     *
     * <p>A count below zero is a loader that has no item list, which is reported as such rather
     * than as an empty list that draws nothing and says nothing.
     */
    static String itemAtIndexProblem(int index, int count) {
        if (count < 0) {
            return "item_at: This loader has no item list to enumerate.";
        }
        if (index >= count) {
            return "item_at: There is no item at index "
                    + index
                    + ", since item_count is "
                    + count
                    + ".";
        }
        return null;
    }

    /** How many items the host lists, or a refusal for a loader that has no list to enumerate. */
    @GrugGenerated("reads the adapter's item list: a unit test has no game")
    public static double item_count() {
        int count = GrugCore.getAdapter().itemCount();
        if (itemListMissing(count)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "item_count: This loader has no item list to enumerate.");
            return 0.0;
        }
        return count;
    }

    /**
     * Whether a loader's answer to {@code itemCount} means it has no item list, which the adapter
     * reports as -1 rather than as an empty list.
     */
    static boolean itemListMissing(int count) {
        return count < 0;
    }

    public static long item_entity(long levelId, double x, double y, double z, long itemStackId) {
        return withEntities(
                "item_entity",
                "item stack",
                itemStackId,
                "level",
                levelId,
                (itemStack, level) ->
                        GrugEntities.addEntity(
                                GrugEntityType.ItemEntity,
                                GrugCore.getAdapter().createItemEntity(level, x, y, z, itemStack)),
                0L);
    }

    public static long item_stack(long itemId, double damage) {
        return withEntity(
                "item_stack",
                "item",
                itemId,
                item ->
                        GrugEntities.addEntity(
                                GrugEntityType.ItemStack,
                                GrugCore.getAdapter().createItemStack(item, damage)),
                0L);
    }

    public static <T> void print(T a) {
        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.add(HostFunctionHelpers.prettyFormat(a));
        }
    }

    public static <T, U> void print2(T a, U b) {
        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.add(
                    HostFunctionHelpers.prettyFormat(a)
                            + " "
                            + HostFunctionHelpers.prettyFormat(b));
        }
    }

    public static <T, U, V> void print3(T a, U b, V c) {
        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.add(
                    HostFunctionHelpers.prettyFormat(a)
                            + " "
                            + HostFunctionHelpers.prettyFormat(b)
                            + " "
                            + HostFunctionHelpers.prettyFormat(c));
        }
    }

    public static long resource_location(String resourceLocationString) {
        return GrugEntities.addEntity(
                GrugEntityType.ResourceLocation,
                GrugCore.getAdapter().createResourceLocation(resourceLocationString));
    }

    public static void BlockInitContext_set_block_entity(long contextId, String entityString) {
        GrugBlockData block =
                requireBlockInitContext("BlockInitContext.set_block_entity", contextId);
        if (block == null) return;

        String cleanName = entityString.substring(entityString.lastIndexOf(':') + 1);

        if (!GrugEntities.entityFileIdsByName.containsKey(cleanName)) {
            // A bounded defect: the block still loads, without a block entity, and the report
            // names the block and the script it asked for.
            GrugModTreeDefect.report(
                    block.id
                            + ": set_block_entity: Block entity script '"
                            + entityString
                            + "' does not exist.");
            return;
        }

        block.blockEntityString = entityString;
    }

    /**
     * Whether {@code contextId} is the context of the pass that is open right now.
     *
     * <p>A context is only valid while the export it was passed to is running. The engine makes a
     * fresh one for every pass and destroys it when the pass closes, so a script that saves it in a
     * member variable holds either a context that no longer exists or one from an earlier pass.
     * Either way what it drew no longer belongs to any pass, and silently ignoring it would leave
     * the author with a shape that is missing a piece and no line saying why.
     *
     * <p>Reported as a mod-tree defect rather than a host function error: a context used outside
     * its call is a mistake in the mod, not a call that a game refused. The report names the method
     * and the rule, and the run fails on it.
     */
    private static boolean requireContext(String method, long contextId) {
        return requireContext(method, "render", contextId, GrugRenderPass.context());
    }

    /** The same rule for the context a test's {@code tick()} was passed. */
    private static boolean requireTickContext(String method, long contextId) {
        return requireContext(method, "tick", contextId, GrugTestState.tickContext());
    }

    /** The same rule for the context a test's {@code init()} was passed. */
    private static boolean requireInitContext(String method, long contextId) {
        return requireContext(method, "init", contextId, GrugTestState.initContext());
    }

    /** The same rule for the context a block's {@code shape()} was passed. */
    private static boolean requireShapeContext(String method, long contextId) {
        return requireContext(method, "shape", contextId, GrugShapes.context());
    }

    private static boolean requireContext(
            String method, String kind, long contextId, Object openContext) {
        GrugObject object = GrugEntities.entityData.get(contextId);
        if (object != null && openContext != null && object.object == openContext) {
            return true;
        }
        reportRefusedContext(method, kind);
        return false;
    }

    private static void reportRefusedContext(String method, String kind) {
        GrugModTreeDefect.report(
                method
                        + ": A "
                        + kind
                        + " context is only valid while the export it was passed to is"
                        + " running. A context saved in a member variable and used later is"
                        + " refused.");
    }

    /**
     * The same rule for the context an overlay's {@code render()} was passed.
     *
     * <p>The kind is checked before the id is: a render method called during a click pass, even
     * with the click pass's own context, is refused, because the two passes carry different state
     * and the render methods would answer a click's geometry as if it were a frame's.
     */
    private static boolean requireOverlayRenderContext(String method, long contextId) {
        if (!GrugOverlay.isRender()) {
            reportRefusedContext(method, "render");
            return false;
        }
        return requireContext(method, "render", contextId, GrugOverlay.context());
    }

    /** The same rule for the context an overlay's {@code click()} was passed. */
    private static boolean requireOverlayClickContext(String method, long contextId) {
        if (!GrugOverlay.isClick()) {
            reportRefusedContext(method, "click");
            return false;
        }
        return requireContext(method, "click", contextId, GrugOverlay.context());
    }

    public static void RenderContext_draw_box(
            long contextId, double x1, double y1, double z1, double x2, double y2, double z2) {
        if (!requireContext("RenderContext.draw_box", contextId)) return;

        if (!GrugRenderPass.record(x1, y1, z1, x2, y2, z2)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "RenderContext.draw_box: The box was not recorded. Its corners have to be the"
                        + " right way round: the first of each pair is the near corner and must be"
                        + " no greater than the second, so draw_box(0, 0, 0, 1, 1, 1) and not"
                        + " draw_box(1, 0, 0, 0, 1, 1).");
        }
    }

    /**
     * Draws a model JSON's elements, each face with the texture the model names for it, turned
     * about the block's centre by multiples of 90 degrees, which is how a model renderer faces one
     * set of boxes six ways. The model is read with {@link GrugModels}, which every loader draws
     * from, and what can go wrong is decided there and in {@link GrugRenderPass}, where a Java test
     * reaches it.
     */
    public static void RenderContext_draw_model(
            long contextId,
            String modelPath,
            double xDegrees,
            double yDegrees,
            double zDegrees,
            double dx,
            double dy,
            double dz) {
        if (!requireContext("RenderContext.draw_model", contextId)) return;

        String problem =
                GrugRenderPass.recordModel(
                        GrugCore.getAdapter().getGrugModsDirectory(),
                        modelPath,
                        xDegrees,
                        yDegrees,
                        zDegrees,
                        dx,
                        dy,
                        dz);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "RenderContext.draw_model: " + problem);
        }
    }

    /**
     * Draws one stack the way the game draws a dropped item, on top of the chunk geometry, for this
     * frame.
     *
     * <p>A stack has to be drawn this way rather than with draw_box because a dropped item's flat
     * sprite turns to face the camera every frame, and a chunk pass bakes its geometry once. The
     * loader decides what the item looks like, because that is the game's own dropped-item drawing;
     * what is decided here is that the call is legal and that its numbers are usable. See the
     * {@code mod_api.json} description for what is drawn.
     */
    public static void RenderFrameContext_draw_item(
            long contextId,
            long itemId,
            double damage,
            double count,
            double x,
            double y,
            double z) {
        if (!requireContext("RenderFrameContext.draw_item", contextId)) return;

        String problem = GrugItemDraw.problem(damage, count, x, y, z);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "RenderFrameContext.draw_item: " + problem);
            return;
        }

        withEntity(
                "RenderFrameContext.draw_item",
                "item",
                itemId,
                item -> GrugRenderPass.recordItem(item, (int) damage, (int) count, x, y, z));
    }

    /**
     * How much of the current tick has already elapsed, from 0 up to but not including 1, for this
     * frame.
     *
     * <p>A chunk pass runs once per rebuild rather than once per frame, so it has no frame fraction
     * to answer.
     */
    public static double RenderFrameContext_partial_tick(long contextId) {
        if (!requireContext("RenderFrameContext.partial_tick", contextId)) return 0.0;

        return GrugRenderPass.partialTick();
    }

    /**
     * The direction the camera looks in, in degrees, for this frame: the game's own yaw, so 0 looks
     * towards positive z, 90 towards negative x, and it increases clockwise seen from above.
     */
    public static double RenderFrameContext_camera_yaw(long contextId) {
        if (!requireContext("RenderFrameContext.camera_yaw", contextId)) return 0.0;

        return GrugRenderPass.cameraYaw();
    }

    /**
     * The screen geometry and mouse position an overlay's render() or click() is asked about, and
     * the two draws a render pass records.
     *
     * <p>The geometry is the screen's and the GUI's own, in pixels from the window's top-left,
     * which is the same measure the game gives the mouse. The overlay lays its grid out itself from
     * these numbers, so the constants live in the mod rather than in every loader.
     */
    public static double OverlayRenderContext_screen_width(long contextId) {
        if (!requireOverlayRenderContext("OverlayRenderContext.screen_width", contextId))
            return 0.0;
        return GrugOverlay.screenWidth();
    }

    public static double OverlayRenderContext_screen_height(long contextId) {
        if (!requireOverlayRenderContext("OverlayRenderContext.screen_height", contextId))
            return 0.0;
        return GrugOverlay.screenHeight();
    }

    public static double OverlayRenderContext_gui_left(long contextId) {
        if (!requireOverlayRenderContext("OverlayRenderContext.gui_left", contextId)) return 0.0;
        return GrugOverlay.guiLeft();
    }

    public static double OverlayRenderContext_gui_top(long contextId) {
        if (!requireOverlayRenderContext("OverlayRenderContext.gui_top", contextId)) return 0.0;
        return GrugOverlay.guiTop();
    }

    public static double OverlayRenderContext_gui_width(long contextId) {
        if (!requireOverlayRenderContext("OverlayRenderContext.gui_width", contextId)) return 0.0;
        return GrugOverlay.guiWidth();
    }

    public static double OverlayRenderContext_gui_height(long contextId) {
        if (!requireOverlayRenderContext("OverlayRenderContext.gui_height", contextId)) return 0.0;
        return GrugOverlay.guiHeight();
    }

    public static double OverlayRenderContext_mouse_x(long contextId) {
        if (!requireOverlayRenderContext("OverlayRenderContext.mouse_x", contextId)) return 0.0;
        return GrugOverlay.mouseX();
    }

    public static double OverlayRenderContext_mouse_y(long contextId) {
        if (!requireOverlayRenderContext("OverlayRenderContext.mouse_y", contextId)) return 0.0;
        return GrugOverlay.mouseY();
    }

    @GrugGenerated("reports an argument refusal through the VM, which a unit test has no game for")
    public static void OverlayRenderContext_draw_item(
            long contextId, long itemId, double x, double y) {
        if (!requireOverlayRenderContext("OverlayRenderContext.draw_item", contextId)) return;

        String problem = overlayItemProblem(x, y);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "OverlayRenderContext.draw_item: " + problem);
            return;
        }

        withEntity(
                "OverlayRenderContext.draw_item",
                "item",
                itemId,
                item -> GrugOverlay.recordItem(item, x, y));
    }

    /** Why draw_item refuses this position, or null when it can record the icon. */
    static String overlayItemProblem(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return "The position has to be finite, and was " + x + ", " + y + ".";
        }
        return null;
    }

    @GrugGenerated("reports an argument refusal through the VM, which a unit test has no game for")
    public static void OverlayRenderContext_draw_box(
            long contextId, double x1, double y1, double x2, double y2) {
        if (!requireOverlayRenderContext("OverlayRenderContext.draw_box", contextId)) return;

        String problem = overlayBoxProblem(x1, y1, x2, y2);
        if (problem != null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "OverlayRenderContext.draw_box: " + problem);
            return;
        }

        GrugOverlay.recordBox(x1, y1, x2, y2);
    }

    /** Why draw_box refuses this box, or null when it can record the highlight. */
    static String overlayBoxProblem(double x1, double y1, double x2, double y2) {
        if (!Double.isFinite(x1)
                || !Double.isFinite(y1)
                || !Double.isFinite(x2)
                || !Double.isFinite(y2)) {
            return "The box was not recorded. Its corners have to be finite, and were "
                    + x1
                    + ", "
                    + y1
                    + ", "
                    + x2
                    + ", "
                    + y2
                    + ".";
        }
        if (x1 > x2 || y1 > y2) {
            return "The box was not recorded. Its corners have to be the right way round: the"
                    + " first of each pair is the near corner and must be no greater than the"
                    + " second.";
        }
        return null;
    }

    public static double OverlayClickContext_screen_width(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.screen_width", contextId)) return 0.0;
        return GrugOverlay.screenWidth();
    }

    public static double OverlayClickContext_screen_height(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.screen_height", contextId)) return 0.0;
        return GrugOverlay.screenHeight();
    }

    public static double OverlayClickContext_gui_left(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.gui_left", contextId)) return 0.0;
        return GrugOverlay.guiLeft();
    }

    public static double OverlayClickContext_gui_top(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.gui_top", contextId)) return 0.0;
        return GrugOverlay.guiTop();
    }

    public static double OverlayClickContext_gui_width(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.gui_width", contextId)) return 0.0;
        return GrugOverlay.guiWidth();
    }

    public static double OverlayClickContext_gui_height(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.gui_height", contextId)) return 0.0;
        return GrugOverlay.guiHeight();
    }

    public static double OverlayClickContext_mouse_x(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.mouse_x", contextId)) return 0.0;
        return GrugOverlay.mouseX();
    }

    public static double OverlayClickContext_mouse_y(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.mouse_y", contextId)) return 0.0;
        return GrugOverlay.mouseY();
    }

    public static double OverlayClickContext_button(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.button", contextId)) return 0.0;
        return GrugOverlay.button();
    }

    public static boolean OverlayClickContext_shift(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.shift", contextId)) return false;
        return GrugOverlay.shift();
    }

    /**
     * The item on the mouse cursor of the screen the press is over, if it holds one. This is the
     * stack the reference deletes on a left click, so an overlay can tell carrying from empty
     * before it decides what the press does.
     */
    @GrugGenerated("asks the adapter about the game's own screen: a unit test has no game")
    public static long OverlayClickContext_carried_item(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.carried_item", contextId)) {
            return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
        }
        Object item = GrugCore.getAdapter().carriedItem();
        if (item == null) {
            return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(null));
        }
        return GrugEntities.addEntity(
                GrugEntityType.Option,
                new GrugOption(GrugEntities.addEntity(GrugEntityType.Item, item)));
    }

    /**
     * Clears the item on the mouse cursor of the screen the press is over. The reference deletes
     * the carried stack on a left click over its list rather than giving another item.
     */
    @GrugGenerated("asks the adapter about the game's own screen: a unit test has no game")
    public static void OverlayClickContext_delete_carried_item(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.delete_carried_item", contextId)) {
            return;
        }
        GrugCore.getAdapter().deleteCarriedItem();
    }

    public static void OverlayClickContext_take(long contextId) {
        if (!requireOverlayClickContext("OverlayClickContext.take", contextId)) return;
        GrugOverlay.take();
    }

    /**
     * A saved number on a block entity that does not save them. Only a grug block entity writes
     * them into its NBT, so a number on a vanilla one would be lost with its chunk without a word.
     */
    private static void reportNotSaving(String hostFunction) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                hostFunction
                        + ": Only a grug block entity saves numbers, and this block entity is not"
                        + " one.");
    }

    /**
     * A saved list on a block entity that does not save lists. Same reason as {@link
     * #reportNotSaving}: only a grug block entity writes them into its NBT.
     */
    private static void reportNotSavingList(String hostFunction) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                hostFunction
                        + ": Only a grug block entity saves lists, and this block entity is not"
                        + " one.");
    }

    /**
     * The block entity a saved-list call is about, or null after reporting that it is not one.
     * Shared by every saved-list host function so the not-a-holder report has one place to live,
     * and a function that calls it can answer its own default.
     */
    private static Object listHolder(String hostFunction, long blockEntityId) {
        Object blockEntity = HostFunctionHelpers.resolveBlockEntity(blockEntityId);
        if (!GrugSavedLists.isHolder(blockEntity)) {
            reportNotSavingList(hostFunction);
            return null;
        }
        return blockEntity;
    }

    /**
     * Why {@code index} is not a record index at all, or null when it is one. An index has to be a
     * whole number, because a list has no record between two of them, and a caller that computed a
     * fraction has a bug worth reporting rather than a record worth rounding to.
     */
    private static String checkIndex(double index) {
        if (index < 0 || index > Integer.MAX_VALUE || index != Math.floor(index)) {
            return "The index has to be a whole number from 0 to "
                    + Integer.MAX_VALUE
                    + ", and was "
                    + index
                    + ".";
        }
        return null;
    }

    /**
     * The record {@code index} names, or null after reporting why it names none. Every saved-list
     * function that reads or writes a record goes through here, so a bad index is refused the same
     * way wherever it is passed.
     */
    private static GrugSavedLists.Record recordOrReport(
            String hostFunction, Object blockEntity, String name, double index) {
        String problem = checkIndex(index);
        if (problem == null) {
            GrugSavedLists.Record record = GrugSavedLists.record(blockEntity, name, (int) index);
            if (record != null) {
                return record;
            }
            problem = "There is no record at index " + (int) index + ".";
        }
        reportRecordProblem(hostFunction, name, problem);
        return null;
    }

    /** Reports a host function call that named a record it could not use, and the list it is in. */
    private static void reportRecordProblem(String hostFunction, String name, String problem) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, hostFunction + ": Saved list \"" + name + "\": " + problem);
    }

    /** The saved-list default for a call that answers a number. */
    private static final Supplier<Double> ZERO = () -> 0.0;

    /** The saved-list default for a call that answers nothing. */
    private static final Supplier<Object> NO_RESULT = () -> null;

    /**
     * The saved-list default for a call that answers an Option[Item], which has to be a new one.
     */
    private static final Supplier<Long> EMPTY_ITEM_OPTION = () -> itemOption(null);

    /**
     * Runs a saved-list call, answering the default after reporting a block entity that saves no
     * lists. One helper for every saved-list host function, so the report and the branch that
     * follows it exist once rather than once per entry.
     */
    private static <T> T withSavedLists(
            String hostFunction,
            long blockEntityId,
            Function<Object, T> call,
            Supplier<T> missing) {
        Object blockEntity = listHolder(hostFunction, blockEntityId);
        return blockEntity == null ? missing.get() : call.apply(blockEntity);
    }

    /**
     * Runs a call on one record of a saved list, answering the default after reporting a block
     * entity that saves no lists, or an index that names no record of the list.
     */
    private static <T> T withSavedRecord(
            String hostFunction,
            long blockEntityId,
            String name,
            double index,
            BiFunction<Object, GrugSavedLists.Record, T> call,
            Supplier<T> missing) {
        return withSavedLists(
                hostFunction,
                blockEntityId,
                blockEntity -> {
                    GrugSavedLists.Record record =
                            recordOrReport(hostFunction, blockEntity, name, index);
                    return record == null ? missing.get() : call.apply(blockEntity, record);
                },
                missing);
    }

    /** The item as an {@code Option[Item]}, empty when there is none. */
    private static long itemOption(Object item) {
        Object handle = item != null ? GrugEntities.addEntity(GrugEntityType.Item, item) : null;
        return GrugEntities.addEntity(GrugEntityType.Option, new GrugOption(handle));
    }

    public static void BlockInitContext_set_custom_render(long contextId) {
        GrugBlockData block =
                requireBlockInitContext("BlockInitContext.set_custom_render", contextId);
        if (block == null) return;

        // Every loader implements the render hook, so the declaration is unconditional. This
        // line is measured: a block that declares custom rendering reaches it on every loader.
        block.customRender = true;
    }

    public static void BlockInitContext_set_flat_lighting(long contextId) {
        GrugBlockData block =
                requireBlockInitContext("BlockInitContext.set_flat_lighting", contextId);
        if (block == null) return;

        if (!block.customRender) {
            // A bounded defect: the block still loads, drawing its model the way every block is
            // lit, and the report names the block and what to call first.
            GrugModTreeDefect.report(
                    block.id
                            + ": set_flat_lighting() needs set_custom_render() called before it,"
                            + " since only custom geometry can be drawn flat.");
            return;
        }

        block.flatLighting = true;
    }

    public static void BlockInitContext_set_hardness(long contextId, double value) {
        GrugBlockData block = requireBlockInitContext("BlockInitContext.set_hardness", contextId);
        if (block == null) return;

        block.hardness = (float) value;
    }

    public static void BlockInitContext_set_inventory_size(long contextId, double size) {
        GrugBlockData block =
                requireBlockInitContext("BlockInitContext.set_inventory_size", contextId);
        if (block == null) return;

        block.inventorySize = (int) size;
    }

    public static void BlockInitContext_set_material(long contextId, String materialName) {
        GrugBlockData block = requireBlockInitContext("BlockInitContext.set_material", contextId);
        if (block == null) return;

        block.material = materialName;
    }

    public static void BlockInitContext_set_shape(long contextId) {
        GrugBlockData block = requireBlockInitContext("BlockInitContext.set_shape", contextId);
        if (block == null) return;

        block.hasShape = true;
    }

    /**
     * The block whose {@code init()} {@code contextId} was passed to, or null, after a report, when
     * that call is not the one running now.
     */
    private static GrugBlockData requireBlockInitContext(String method, long contextId) {
        if (!requireContext(method, "init", contextId, GrugDeclarations.initContext())) {
            return null;
        }
        return GrugDeclarations.initContext().block;
    }

    /**
     * The largest whole number at or below the given number, which is how a position becomes the
     * block coordinate it is in.
     */
    public static double Math_floor(double value) {
        return Math.floor(value);
    }

    /**
     * What is left of {@code value} after dividing by {@code divisor}, with the sign of {@code
     * value}, which is Java's {@code %} on doubles, C's {@code fmod}, and what BuildCraft's {@code
     * % 20} reads.
     */
    public static double Math_remainder(double value, double divisor) {
        if (divisor == 0) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Math.remainder: The divisor cannot be 0.");
            return 0;
        }
        return value % divisor;
    }
}
