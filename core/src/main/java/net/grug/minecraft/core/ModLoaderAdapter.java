package net.grug.minecraft.core;

import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockEntityNbt;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.Vec3;

import java.awt.image.BufferedImage;
import java.io.File;

public interface ModLoaderAdapter {
    // --- TODO: Give this section a title, like the other sections below

    File getGameDirectory();

    /** The directory the loaded grug mods live in, which is what the screenshot tree is under. */
    File getGrugModsDirectory();

    /**
     * The name of this loader's directory under the repository's {@code loaders/}, which is what a
     * screenshot reference this loader produces is named after.
     *
     * <p>Every adapter already names itself for the canonical block table, so this is that same
     * name rather than a second one to keep in step. The reference validator checks every reference
     * name against the loader directory names, so a renamed loader or a misspelled reference is
     * caught instead of leaving an orphaned PNG.
     */
    String getLoaderName();

    /**
     * Which process this is: the client, or a dedicated server.
     *
     * <p>A loader knows without asking, because the game told it. A loader that has no dedicated
     * server at all still answers {@link GrugSide#CLIENT}, and its adapter then has nothing on the
     * server side to disagree with.
     */
    GrugSide getSide();

    /**
     * Refuses a call that needs a client when this process is a dedicated server, and says whether
     * it refused.
     *
     * <p>This is what the client-only functions are made of: a camera to put somewhere, a window to
     * look through it in, a frame to read back from, a local player whose right-click the game will
     * route, and the player the test bands are measured from. A dedicated server has none of those,
     * so letting one through raises something from inside the game's client classes rather than
     * saying what is wrong.
     *
     * <p>It lives here, on the interface, rather than in {@code HostFunctions} because that is
     * where "which functions need a client" belongs, and because every loader's implementations of
     * those four functions are already outside the coverage gate for being GL glue. A branch in
     * {@code HostFunctions} would have had to be excluded instead, which would have taken the
     * delegation that surrounds it out of measurement with it.
     *
     * <p>It is excluded from the coverage gate as a whole, not only its refusal: a core test can
     * reach the client answer through a stand-in adapter, but the refusal is what a dedicated
     * server does, and no core test has one. The client answer is still exercised by every loader's
     * game run, where {@code Test.use_block}, {@code Test.mining_crack} and {@code
     * Screenshot.equals} ask it, but it is not counted; the refusal waits for the dedicated-server
     * run of #163, which is when the exclusion can come off. See #188.
     *
     * <p>A refusal is a host function error rather than a fatal: it names the function, it fails
     * the test that called it, and the game keeps running, which is what a mod's mistake deserves.
     */
    @GrugGenerated("the refusal arm needs a dedicated server, which no CI run launches until #163")
    default boolean refuseWithoutAClient(String function) {
        GrugSide side = getSide();
        if (side == GrugSide.CLIENT) return false;

        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                function
                        + ": This needs a client, and grug is running on "
                        + side.description()
                        + ".");
        return true;
    }

    void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj);

    // --- Logging Abstraction --- TODO: Give this section a better title

    void logInfo(String message);

    void logError(String message);

    // --- Game Functions Abstraction --- TODO: Give this section a better title

    // TODO: Sort the below methods into entities, classes and host fns, and sort
    // each alphabetically

    void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz);

    Vec3 getEntityDeltaMovement(Object entityObj);

    void spawnEntity(Object levelObj, Object entityObj);

    void consumeCraftingIngredients(Object blockEntityObj, double startSlot);

    double countItemInInventory(Object blockEntityObj, Object itemObj, double damage);

    void dropInventory(Object levelObj, double x, double y, double z);

    double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount);

    Object getBlockEntity(Object levelObj, double x, double y, double z);

    Object getBlockEntityLevel(Object blockEntityObj);

    BlockPos getBlockPosOfBlockEntity(Object blockEntityObj);

    double getInventorySize(Object blockEntityObj);

    double insertItemIntoInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount, boolean insert);

    double getItemCountInSlot(Object blockEntityObj, double slot);

    double getItemDamageInSlot(Object blockEntityObj, double slot);

    Object getItemInSlot(Object blockEntityObj, double slot);

    Object getItemFromRegistry(Object resourceLocationObj);

    /**
     * How many items this loader lists for {@code item_count}, or -1 when it has no item list to
     * enumerate with.
     *
     * <p>-1 is reported by the host function rather than answered as 0, so a mod that asks on a
     * loader with no item list hears that the loader has none instead of drawing an empty list and
     * saying nothing. Every loader in this repository answers it; a new loader that cannot
     * enumerate items keeps this default, which the overlay's deviations name in the mod's {@code
     * about.json}.
     */
    @GrugGenerated("no item list: a new loader that cannot enumerate items keeps this default")
    default int itemCount() {
        return -1;
    }

    /**
     * The item at index, in the order {@link #itemCount} enumerates, or null when the index is out
     * of range. The host function checks the range first, so a null here is a defect rather than a
     * mod's mistake.
     */
    @GrugGenerated("no item list: a new loader that cannot enumerate items keeps this default")
    default Object itemByIndex(int index) {
        return null;
    }

    /**
     * The largest stack the item can come in, which the overlay's give and a test that checks a
     * full stack need. A loader with no item list answers 0.
     */
    @GrugGenerated("no item list: a loader that cannot enumerate items cannot be asked about one")
    default int getItemMaxStack(Object itemObj) {
        return 0;
    }

    /**
     * Gives one of the item to the player, the way the game gives a player an item: into their
     * inventory, and dropped at their feet if it is full. A loader that cannot give items keeps
     * this default, which reports the call rather than dropping the item silently, so a mod hears
     * which loader has no give yet.
     */
    @GrugGenerated("no give: a new loader that cannot give items keeps this default")
    default void giveItemToPlayer(Object playerObj, Object itemObj) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "give_item: This loader cannot give an item yet.");
    }

    /**
     * Gives a full stack of the item to the player, the way {@link #giveItemToPlayer} gives one. A
     * loader that cannot give items keeps this default, which reports the call rather than dropping
     * the stack silently.
     */
    @GrugGenerated("no give: a new loader that cannot give items keeps this default")
    default void giveItemStackToPlayer(Object playerObj, Object itemObj) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "give_item_stack: This loader cannot give an item yet.");
    }

    Object createItemEntity(Object levelObj, double x, double y, double z, Object itemStackObj);

    Object createItemStack(Object itemObj, double damage, double count);

    /**
     * The largest count an item stack this loader saves keeps.
     *
     * <p>1.2.5, both b1.7.3 loaders and Alpha write the stack count as one NBT byte, so a stack
     * beyond 127 comes back as a different count on the next load. 1.20.6 writes an int. {@code
     * item_stack} refuses a count above this rather than make a stack whose save would corrupt it.
     */
    int maxItemStackCount();

    double getItemEntityDamage(Object itemEntityObj);

    double getItemEntityCount(Object itemEntityObj);

    /** The ticks left before a player can pick the item entity up. */
    double getItemEntityPickupDelay(Object itemEntityObj);

    /** Sets the ticks left before a player can pick the item entity up. */
    void setItemEntityPickupDelay(Object itemEntityObj, int ticks);

    Object findItemEntity(Object levelObj, double x, double y, double z, double radius);

    Object createResourceLocation(String string);

    void setItemCountInSlot(Object blockEntityObj, double slot, double count);

    void setItemDamageInSlot(Object blockEntityObj, double slot, double damage);

    void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count);

    void updateRecipeOutput(Object blockEntityObj, double startSlot);

    void placeBlock(Object levelObj, double x, double y, double z, String blockName);

    /**
     * Starts a batch of {@link #placeBlock} calls that {@link #endBatch} closes, so a loader that
     * relights the world for every block can relight once at the end of the batch instead. The
     * blocks a batch leaves behind, their metadata and the neighbours it notifies are the ones the
     * same calls make one at a time.
     *
     * <p>A write that changes nothing is not relit at all. One at a time such a write relights the
     * position with the world already as it is, which recomputes light that is already correct
     * (1.2.5 does it even though its write returns false), so a loader may drop it; the room build
     * is mostly such writes, which is where the saving comes from. A loader whose placement is
     * already cheap keeps this a no-op.
     */
    default void beginBatch(Object levelObj) {}

    /** Ends the batch {@link #beginBatch} started, doing the relight it postponed. */
    default void endBatch(Object levelObj) {}

    /**
     * Creates an explosion of the given power at the given coordinates, which are not floored. The
     * caller has already rejected a non-finite coordinate and a negative power.
     */
    void explode(Object levelObj, double x, double y, double z, double power);

    /**
     * The item in the player's selected hotbar slot, or null for an empty hand. The stack's count
     * and damage are not part of the answer.
     */
    Object getHeldItem(Object playerObj);

    /**
     * Test only: puts one of the item in the player's selected hotbar slot, or empties it for a
     * null item.
     */
    void setHeldItem(Object playerObj, Object itemObj);

    /**
     * Test only: how many items the player's main hand holds. A loader that cannot answer keeps the
     * default, which reports the call rather than pretending the hand holds one item.
     */
    @GrugGenerated("test only: only the 1.20.6 adapter has an overlay test that counts a stack")
    default double getHeldCount(Object playerObj) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.get_held_count: This loader cannot count the held stack yet.");
        return 0.0;
    }

    boolean hasNeighborSignal(Object levelObj, double x, double y, double z);

    /** The world's time in ticks, which {@code World.getWorldTime()} is in BuildCraft 2.2.14. */
    long getWorldTime(Object levelObj);

    /**
     * The level's own random's next whole number below {@code bound}, which is {@code
     * World.random.nextInt(bound)} in BuildCraft 2.2.14. It is the same random the game draws from
     * for everything else, so a mod that uses it changes what the game does on that seed exactly as
     * the reference does.
     */
    int nextRandomInt(Object levelObj, int bound);

    /** The level's own random's next float from 0 up to but not including 1. */
    double nextRandomFloat(Object levelObj);

    /** The level's own random's next Gaussian, with mean 0 and standard deviation 1. */
    double nextRandomGaussian(Object levelObj);

    String getBlock(Object levelObj, double x, double y, double z);

    boolean isAir(Object levelObj, double x, double y, double z);

    /**
     * Whether the block at the position is the one {@link #placeBlock} would put there for {@code
     * blockName}, ignoring its state. The room build leaves a room that is already whole in place
     * when every one of its blocks answers true, and rebuilds it otherwise, so a loader whose block
     * placement is expensive (1.2.5 relights the world for each one) answers it and the others keep
     * the default, which makes every test's room be built from scratch as it always was.
     */
    default boolean isBlock(Object levelObj, double x, double y, double z, String blockName) {
        return false;
    }

    /**
     * Removes every item entity inside the box, which is how the items a fixture drops stop the
     * player from picking them up and carrying them into a later capture. The room builder calls it
     * before every test, so an item an earlier test left on the ground is gone before the next one
     * starts. See #254.
     */
    void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2);

    double countItemEntities(Object levelObj, double x, double y, double z, double radius);

    void notifyNeighbors(Object levelObj, double x, double y, double z);

    double takeItemFromSlot(Object blockEntityObj, double slot, double amount);

    /**
     * Takes part of a block entity's crafting result. Unlike {@link #takeItemFromSlot} this is not
     * an inventory slot: a grug block entity keeps the result in its own holder, and a reference
     * tile computes it on demand.
     */
    double takeCraftingResult(Object blockEntityObj, double amount);

    /**
     * /** The result stack a block entity's crafting result holds right now, or null when it has no
     * result. Not an inventory slot: a grug block entity keeps the result in its own holder, and a
     * reference tile computes it on demand, so only the adapter knows what "no result" is here.
     */
    Object getResultStack(Object blockEntityObj);

    /** How many items a stack holds, for {@link #getResultStack} to read. */
    double getStackCount(Object stackObj);

    /**
     * The grug entity handle of a block entity, or 0 when it is not a grug block entity. The host
     * calls a block entity's exports through it, which is how a pipe asks a neighbour per side and
     * per item.
     */
    long getGrugEntityHandle(Object blockEntityObj);

    /**
     * Puts up to {@code amount} of an item into one inventory slot, as many as the slot has room
     * for, and returns how many went in. The slot is not checked against any declared slot range,
     * because the block entity that named it has already decided. With {@code commit} false it only
     * answers how many would fit and changes nothing.
     */
    double insertItemIntoSlot(
            Object blockEntityObj,
            double slot,
            Object itemObj,
            double damage,
            double amount,
            boolean commit);

    /** The Item in a result stack, for {@link #getResultStack} to read. */
    Object getStackItem(Object stackObj);

    /** The damage of a result stack, for {@link #getResultStack} to read. */
    double getStackDamage(Object stackObj);

    /**
     * The level this process's tests build their fixtures in, or null when this side has none yet.
     *
     * <p>A dedicated server answers with its own level, because there is no client to ask. A client
     * answers with whichever world its loader says owns the block: on 1.20.6 a singleplayer
     * client's world is a mirror of the integrated server's, and a fixture has to go into the one
     * that owns it, so that loader prefers the server's answer; the other four hand back the
     * client's own world. Read each adapter's implementation rather than assuming one rule: they
     * differ because the game differs.
     *
     * <p>Null is what a side with no world yet reports, and only StationAPI does that today,
     * because it has no server-side answer until #165.
     */
    Object getLevel();

    /**
     * The player a test anchors its fixtures to and drives {@link #openGui} with: the local player
     * on a client, or a connected player on a dedicated server. Null when this side has none yet,
     * which on a dedicated server means nobody has joined.
     *
     * <p>It is named {@code testPlayer} rather than {@code getTestPlayer} because it is the role,
     * not a second getter beside {@link #getLevel}: it is the one player every part of the runtime
     * measures from, which is why a loader makes its choice of player here and nowhere else. See
     * #188.
     */
    Object testPlayer();

    /**
     * The eye position of {@code player}, which is the render camera on a client and the same field
     * of the connected player on a dedicated server. It is never asked about null: {@link
     * #getTestOrigin()} answers null for a side with no player instead.
     *
     * <p>The player comes in as an argument rather than being fetched from a singleton of its own,
     * so a loader picks its test player in {@link #testPlayer()} and nowhere else: a server adapter
     * that overrides only {@code testPlayer()} is measured from the player it chose, and {@link
     * #getTestOrigin()} needs no override of its own.
     *
     * <p>It stays per loader because the mappings differ: every version before 1.20.6 keeps the
     * player's position at the eye, while 1.20.6 keeps it at the feet and adds {@code getEyeY()}.
     */
    Vec3 testPlayerEye(Object player);

    /**
     * Whether the client has received the chunks a test setup builds in: the neighbourhood around
     * the player has terrain below the player's feet. A write into a chunk the client has not
     * received lands in a placeholder chunk that the arriving real chunk then replaces, losing the
     * block and its block entity.
     */
    boolean isWorldReady(Object playerObj);

    /**
     * Whether the frame a capture would read is one the client had no work left in. The mesh bakes
     * a face's per-vertex light when its section is rebuilt, and a rebuild can run while a light
     * update is still settling, so a capture taken too early can land a light step away from the
     * reference. A loader answers this from its own frame hook through {@link #onFrameRendered},
     * because a tick is not a frame and the game draws fewer frames than it runs ticks. The answer
     * also waits for the client to hold every block a test has placed, because on 1.20.6 the
     * client's copy arrives in a packet after the server's world already has the edit. See #253,
     * #257 and #804.
     */
    boolean isWorldSettled();

    /**
     * One completed frame, called from the loader's own frame hook, which records whether that
     * frame still had work in it for {@link #isWorldSettled} to answer with, including whether the
     * client still owed a test's block edit.
     *
     * <p>It runs once per drawn frame on the client, whatever cadence the loader's own render path
     * has, so the answer describes the frame a capture would read rather than the tick it is asked
     * on.
     */
    void onFrameRendered();

    /**
     * Forgets every frame {@link #isWorldSettled} has counted, so the next answer can only be about
     * frames drawn after this call.
     *
     * <p>A loader calls this through {@code GrugModsWatcher.noteLoaderReloadFinished}, because a
     * reload that finishes inside a game tick changes what the next frame draws and says nothing
     * about the frame already in the back buffer. See #324.
     */
    void forgetSettledFrames();

    /**
     * Whether this loader registers the crafting recipes mods ship under {@code
     * data/<namespace>/recipes}.
     *
     * <p>Every loader does, each through its own version's recipe machinery: the Ornithe loaders
     * parse the tree with {@code GrugRecipeTree} and hand the result to {@code CraftingManager},
     * StationAPI hands the files to its recipe registry, 1.20.6 serves the tree to Forge as a pack
     * so the level's own recipe manager loads it, and 1.2.5 parses the tree and calls the game's
     * own {@code ModLoader.addRecipe} and {@code addShapelessRecipe}. A test asserting a shipped
     * recipe asks this before asserting, so a future loader that cannot do it does not fail the run
     * for it.
     */
    boolean registersModRecipes();

    /**
     * Serialises a block entity's NBT and reads it straight back, so tests can cover the save/load
     * paths without the game actually saving and reloading a world.
     */
    void roundTripNbt(Object blockEntityObj);

    /**
     * This version's NBT, for the test-only edits of a block entity's own saved data that {@link
     * GrugBlockEntityNbt} stages and loads.
     */
    GrugBlockEntityNbt.Access blockEntityNbt();

    /**
     * Runs a grug block entity's tick once, as the game would on its next tick, so a test can reach
     * a state that takes thousands of ticks to build without waiting for them. Only legal on a grug
     * block entity; {@code HostFunctions} checks that before calling.
     */
    void tickBlockEntity(Object blockEntityObj);

    /**
     * Test only: runs the level's own tick {@code ticks} times in a row, at the point of the call,
     * with no frame drawn and no client packet read between them, so a test can reach a state that
     * takes the game minutes to build without waiting for those ticks to be drawn.
     *
     * <p>It runs what the game's own tick of that level runs, in this loader's own order, and moves
     * the level's clock one per tick and draws from its random as a real tick does. It does not
     * tick the client, so a test that waits for something drawn or received still waits for a real
     * tick, and it does not shrink the runner's tick budget: a long advance is bounded only by the
     * run timeout.
     *
     * <p>The answer is the number of ticks the advance ran: {@code ticks} where this loader can
     * advance its level, and 0 where it keeps this default, which does nothing. 0 is a documented
     * no-op and not an err, like the -1 {@link #highBlockRoundTrip} answers on a loader without an
     * id above 255: a test that reads 0 knows the advance is not there and falls back to waiting
     * for the game's own ticks, which is what it did before the advance existed. 1.20.6 and 1.2.5
     * implement it; the three older loaders still need their own order, since two of them split one
     * tick into two calls and each version's tick runs a different set of systems. See #753 and
     * #780.
     */
    default int tickLevel(Object levelObj, int ticks) {
        return 0;
    }

    /**
     * Test only: saves the chunk the tests run in through this loader's chunk save format and reads
     * it straight back, with a block whose id is above 255 in it, so the round trip is proven on a
     * real save. Answers the id the block got, which the loader's own block-id search hands out
     * with the ids of its low range taken by the pack's other mods.
     *
     * <p>A loader whose block ids do not reach above 255 keeps this default answer of -1, which is
     * a documented no-op and not an err: there is no such id for it to round trip. See #518.
     */
    default long highBlockRoundTrip() {
        return -1;
    }

    /**
     * How many ticks every loader's client runs for one rendered frame at most. A client draws no
     * more frames when its clock runs faster, so the speed it delivers saturates at about this
     * multiple of real time and a higher number only adds drift.
     */
    double CLIENT_TICKS_PER_FRAME = 10;

    /**
     * The speed the runner runs the game's clock at for every test, as a multiple of the 20 ticks
     * per second it runs at in real time: the loader's own maximum, which is the speed at which its
     * client takes all the ticks it can for a frame. The loader picks it because only the loader
     * knows its cap, and {@code GRUG_CI_MAX_GAME_SPEED} can lower it, to 1 for real time.
     *
     * <p>A loader that cannot speed up its clock keeps this default of 1, which is a documented
     * no-op and not an err: the game runs at the speed it always did, only slower to finish. A
     * dedicated server is such a loader for now: its clock is the server's own tick loop, and no CI
     * run launches one (#163).
     */
    default double maxGameSpeed() {
        return 1;
    }

    /**
     * Sets how fast the game's own clock runs, as a multiple of the 20 ticks per second it runs at
     * in real time, and answers whether this loader did so. {@code 1} puts the real clock back.
     *
     * <p>The clock is the game's and not a mod's: a block entity of a grug mod, of a reference mod
     * and of vanilla all tick faster, so a test that waits for the game (an item travelling along a
     * pipe, an engine's stroke) takes less wall time without a mod knowing. What changes is only
     * how long a test takes, because tests count ticks and wait for conditions, never seconds. The
     * test runner's own ticks are the client's ticks, so on a loader that answers true they speed
     * up with the world. A loader keeps the world's ticks equal to the runner's: where the world
     * ticks from the client they are the same ticks, and where it has a server of its own (1.20.6)
     * the server follows the client's ticks. What a speed-up cannot do is draw more frames: every
     * loader caps the ticks it runs for one frame at {@link #CLIENT_TICKS_PER_FRAME}, so a test
     * that reads a rendered result waits for a condition of the picture and never for a tick count.
     *
     * <p>{@code speed} is {@link #maxGameSpeed()} or lower, which the runner has already checked.
     * The runner puts 1 back when the test ends, whether it passed or failed, and never asks a
     * loader to restore a speed it did not apply.
     */
    default boolean setGameSpeed(double speed) {
        return false;
    }

    /**
     * Has the game build a block's geometry again, so a render function that reads state the world
     * does not know changed, such as a saved number, draws the new state. On a version whose client
     * keeps its own copy of the block entity this also sends that copy the block entity's saved
     * data, since that is what the client's render function reads.
     */
    void redraw(Object blockEntityObj);

    /**
     * The position the test bands are measured from: {@link #testPlayerEye(Object)}, the render
     * camera, plus {@link GrugTestOrigin#LIFT}, which is where the bands already sit in every test.
     * Null when this side has no player, which on a dedicated server means nobody has joined, and
     * {@code Test.get_origin} reports that null as an error rather than dereferencing it.
     *
     * <p>It is derived from the test player rather than read from a player of its own, so {@link
     * #testPlayer()} is the only place a loader chooses whose position a test builds from. A
     * dedicated server therefore inherits this answer from the client adapter it extends, measured
     * from the connected player that adapter's {@code testPlayer()} returns.
     *
     * <p>It is asked after {@link #snapPlayerForTest}, so the player is already at the centre of
     * their block with a level view when the answer is first captured for a test. That is what
     * makes one origin answer for a test, whatever the test does with a camera. See #609.
     */
    default Vec3 getTestOrigin() {
        Object player = testPlayer();
        return player == null ? null : GrugTestOrigin.of(testPlayerEye(player));
    }

    /**
     * Puts the player where every test starts: the centre of the block they stand in, with their
     * yaw and pitch at 0, so a fixture lands in the same place relative to every loader's camera
     * and a test need not set a camera up itself. The player stays in their own block, and so
     * inside the chunk already lit around them, because moving into a chunk that is not lit yet
     * crashes Alpha.
     *
     * <p>The runner calls this before its room build and before the first {@link #getTestOrigin}
     * answer is captured, and calls {@link #restorePlayerAfterTest} when the test ends. A dedicated
     * server has no local player to snap, so the snap is skipped there without an error. See #609.
     */
    default void snapPlayerForTest() {
        if (!GrugTestPlayerSnap.canSnap(getSide())) return;
        snapPlayerForTestOnClient();
    }

    /** The client half of {@link #snapPlayerForTest}, which needs a game's own player object. */
    void snapPlayerForTestOnClient();

    /**
     * Empties the test player's inventory and puts their selected slot back on the first, so a test
     * starts with the hotbar the previous one did not touch, as it starts with a test area the
     * previous one did not touch. Without it an item one test (or one pass of {@code
     * GRUG_CI_REPEATS}) left in a hotbar slot shows in the next capture. A no-op when this side has
     * no player yet. See #930.
     */
    default void resetPlayerInventoryForTest() {
        Object player = testPlayer();
        if (player == null) return;
        clearInventoryOf(player);
    }

    /**
     * Removes every item the player holds, armour included, and selects the first hotbar slot. The
     * loader's half of {@link #resetPlayerInventoryForTest}, which needs a game's own player.
     */
    void clearInventoryOf(Object playerObj);

    /**
     * Puts the player back where {@link #snapPlayerForTest} found them, so a test's change to the
     * camera does not outlive it. A no-op on a dedicated server, where the snap never ran. See #393
     * and #408.
     */
    void restorePlayerAfterTest();

    /**
     * Closes whatever screen the client currently has open, so a test that captures the world is
     * not looking at a panel an earlier test opened and never closed.
     *
     * <p>Tests share one client for the whole run, so an open screen outlives the test that opened
     * it. That is invisible until a later test takes a screenshot and finds the world behind a
     * panel it did not ask for.
     */
    void closeOpenScreenForTest();

    /**
     * Whether the client has a screen open, for a test that must not capture before the panel it
     * asked for has arrived. {@code Test.close_screen} leaves no screen open by this answer, even
     * on a loader that displays a placeholder screen for it.
     */
    boolean isScreenOpenForTest();

    /**
     * Opens the player's own inventory screen, for a test that drives an overlay which only appears
     * there. A loader that cannot open it keeps the default, which reports the call rather than
     * pretending a screen is up.
     */
    @GrugGenerated("default only: every current adapter opens the inventory")
    default void openInventory(Object playerObj) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.open_inventory: This loader cannot open the inventory yet.");
    }

    /**
     * Presses a mouse button at a screen position through the screen's own press path, and answers
     * whether a screen still took it. A loader that cannot drive a press keeps the default, which
     * reports the call rather than answering as if no screen had taken it.
     */
    @GrugGenerated("default only: every current adapter drives a synthetic press")
    default boolean clickScreen(double x, double y, double button) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.click_screen: This loader cannot press on a screen yet.");
        return false;
    }

    /**
     * Presses a key on the open screen through the screen's own key path, and answers whether an
     * overlay took it.
     */
    @GrugGenerated("default only: every current adapter drives a synthetic key press")
    default boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.press_screen_key: This loader cannot press a key yet.");
        return false;
    }

    /**
     * Test only: the width of the open screen in scaled pixels. A loader that cannot answer keeps
     * the default, which reports the call rather than returning a size no screen has.
     */
    @GrugGenerated("default only: every current adapter measures a screen")
    default double screenWidthForTest() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.screen_width: This loader cannot measure a screen yet.");
        return 0.0;
    }

    /** Test only: the x of the open container screen's GUI. */
    @GrugGenerated("default only: every current adapter measures a screen")
    default double guiLeftForTest() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.gui_left: This loader cannot measure a screen yet.");
        return 0.0;
    }

    /** Test only: the width of the open container screen's GUI. */
    @GrugGenerated("default only: every current adapter measures a screen")
    default double guiWidthForTest() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.gui_width: This loader cannot measure a screen yet.");
        return 0.0;
    }

    /** Test only: the y of the open container screen's GUI. */
    @GrugGenerated("default only: every current adapter measures a screen")
    default double guiTopForTest() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.gui_top: This loader cannot measure a screen yet.");
        return 0.0;
    }

    /** Test only: the height of the open container screen's GUI. */
    @GrugGenerated("default only: every current adapter measures a screen")
    default double guiHeightForTest() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "Test.gui_height: This loader cannot measure a screen yet.");
        return 0.0;
    }

    /**
     * The item on the mouse cursor of the screen that is open, or null for an empty cursor. A
     * loader that cannot answer keeps the default, which reports the call rather than answering as
     * if the cursor were empty.
     */
    @GrugGenerated("default only: every current adapter reads the cursor")
    default Object carriedItem() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "OverlayClickContext.carried_item: This loader cannot read the"
                        + " carried item yet.");
        return null;
    }

    /**
     * Test only: puts one of the item on the mouse cursor of the screen that is open, or clears it
     * for a null item. A loader that cannot do it keeps the default, which reports the call.
     */
    @GrugGenerated("default only: every current adapter sets the cursor")
    default void setCarriedItem(Object itemObj) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "Test.set_carried_item: This loader cannot set the carried item yet.");
    }

    /**
     * Clears the item on the mouse cursor of the screen that is open, which is what the reference
     * does on a left click over its list. A loader that cannot do it keeps the default, which
     * reports the call rather than leaving the item where the script asked for it to be deleted.
     */
    @GrugGenerated("default only: every current adapter deletes the cursor item")
    default void deleteCarriedItem() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "OverlayClickContext.delete_carried_item: This loader cannot delete the carried"
                        + " item yet.");
    }

    /**
     * Whether the game's own sound table has a sound under {@code path}, the part of a {@code
     * minecraft:} sound name after the colon. On 1.20.6 that is a sound event in the registry, such
     * as {@code ui.button.click}. On Alpha, Beta and 1.2.5 it is a name in the version's own sound
     * pool, such as {@code random.click}, which holds only the sound files the game has loaded: a
     * run with no resources downloaded has none, and answers false for every name.
     */
    boolean hasSound(String path);

    /**
     * Plays the game's own sound {@code path} to the player as a sound of the interface rather
     * than of the world, the way a button plays its click: it has no position, so it is heard at
     * the same loudness wherever the player stands. The caller has already checked that {@link
     * #hasSound} is true and that the volume is from 0 to 1 and the pitch from 0.5 to 2. It runs on
     * the thread that owns the sound engine, whichever thread the mod's call came from.
     */
    void playUiSound(String path, double volume, double pitch);

    /**
     * Builds the sealed room the runner puts around the player before every test, and again when a
     * test asks for a different radius through {@code Test.set_box_radius}. See {@link GrugTestBox}
     * for what the room is and why it exists.
     *
     * <p>The player's feet are the anchor, so the floor replaces the block they stand on and they
     * do not move. An adapter whose player's y is the eye height subtracts it first; one whose
     * player's y is already the feet passes it through. Nothing is built when there is no player,
     * which is the dedicated server before anyone has joined.
     *
     * <p>An adapter whose settle answer counts frames also forgets them here, because the room
     * build dirties the renderer and is the first thing a test does: an answer left over from the
     * previous test would describe frames this one must not capture. See {@code
     * GrugRenderSettle.reset}.
     */
    void buildTestBox(int radius);

    /**
     * Whether this loader's client holds its own copy of the block light the integrated server
     * computed, which the settle answer has to wait for after a room rebuild. True on a loader
     * whose client is sent the server's light; false where one world's light is both sides' answer,
     * since the two copies cannot disagree there. See #686.
     */
    default boolean separatesClientLight() {
        return false;
    }

    /**
     * Sweeps the dropped items out of the test area around the player, which the runner calls in
     * the tick every test ends so that nothing a test dropped outlives it. See {@link
     * GrugTestBox#sweepItems}.
     */
    void sweepTestItems();

    /**
     * Whether the loader has a resource reload waiting to start or running, which the runner waits
     * out before it starts a test. The reload keeps the client from processing what the server
     * sends while it runs, so a test that starts inside one looks at a client that has not caught
     * up with the test's own first tick. Only 1.20.6 reloads resources on the client at runtime
     * without pausing the game, so every other loader keeps the default. See #328.
     */
    default boolean isLoaderReloading() {
        return false;
    }

    /**
     * Whether the client still holds a block entity of an earlier test inside the test area, after
     * the room build cleared the area. Only 1.20.6 has a client that is separate from the server
     * and learns of a change from a packet, so only there can the client be behind the room build,
     * and a rebuild of its stale sections draw a block entity the test never placed. The runner
     * waits this out before a test's first tick, and every other loader keeps the default. See
     * #583.
     */
    default boolean hasStaleTestBlockEntities() {
        return false;
    }

    void useBlockForTest(Object levelObj, double x, double y, double z);

    /**
     * Whether the game draws the mining crack over the block's own geometry at the given
     * coordinates, and leaves the block's shape a full cube afterwards.
     *
     * <p>A test needs this because it cannot hold the crack on screen: the versions whose overlay
     * follows the local player's own mining clear that state on every tick the attack button is not
     * held, and a test has no way to hold it, so a capture of a mined block would show no crack
     * whatever the hook does. The versions that draw the overlay from an explicit call make one
     * overlay pass for the block at the middle of the break; 1.20.6 asks its baked model for the
     * quads the overlay would draw, which is what the fix there is about.
     *
     * <p>The block is left in place and nothing is damaged. The shape half of the answer is the
     * promise every shape-per-box loader makes: the overlay sets the block's shared shape per box,
     * so a box left behind would reshape every later block of that type in the same chunk compile.
     */
    boolean miningCrackForTest(Object levelObj, double x, double y, double z);

    /**
     * Test only: whether the block at the given position, as the game collides with it, has a
     * collision box that overlaps the given box.
     *
     * <p>The box is in block-local coordinates, 0 to 1 from the block's minimum corner, so a test
     * can put a player-sized box where a player would stand and ask the game's own answer. It goes
     * through the block's collision boxes rather than a grug call, so it answers for any block the
     * loader knows, which is what lets a port ask the same question of its reference.
     */
    boolean blockCollisionIntersects(
            Object levelObj,
            double x,
            double y,
            double z,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ);

    /**
     * Test only: whether the box the game targets and outlines for the block at the given position
     * contains the given point.
     *
     * <p>The point is in block-local coordinates, 0 to 1 from the block's minimum corner. Like
     * {@link #blockCollisionIntersects} it asks the game rather than grug, so it answers for any
     * block, a port's reference included.
     */
    boolean blockSelectionContains(
            Object levelObj,
            double x,
            double y,
            double z,
            double pointX,
            double pointY,
            double pointZ);

    /**
     * Test only: whether the game's own ray trace from one point to another first meets the block
     * at the given position.
     *
     * <p>The two points are in block-local coordinates, like the boxes of {@link
     * #blockCollisionIntersects}, and may lie outside 0 to 1 so a test can start a ray outside the
     * block. Each loader answers through the method its world's own ray trace calls for a block, so
     * this is the path a player's click takes rather than a question the adapter answers itself: a
     * loader that clips against the full cube, or against a shape it left behind on a shared block,
     * misses a ray it should hit and hits one it should miss.
     */
    boolean blockRayTraceHits(
            Object levelObj,
            double x,
            double y,
            double z,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ);

    /**
     * Captures the rectangle and compares it against the reference directory. When {@code
     * tolerancePercent} is 0 the capture must match pixel-for-pixel; otherwise it passes when at
     * most that percentage of pixels differ.
     */
    void assertScreenshotEquals(
            String referencePath,
            double x1,
            double y1,
            double x2,
            double y2,
            double tolerancePercent);

    /**
     * Reads the rectangle out of the current frame, or returns null after reporting why it could
     * not: a coordinate outside the frame, or an empty rectangle.
     *
     * <p>Separate from {@link #assertScreenshotEquals} so a test can hold two captures of the same
     * rectangle and compare them with each other, which is how it checks that the game redrew
     * something. Comparing against a reference image instead has to be accepted once per rendering,
     * and the same crop of the same block does not render identically on every version or platform.
     */
    BufferedImage captureScreenshot(double x1, double y1, double x2, double y2);
}
