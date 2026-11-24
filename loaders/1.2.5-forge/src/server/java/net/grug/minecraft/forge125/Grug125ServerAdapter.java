package net.grug.minecraft.forge125;

import cpw.mods.fml.server.FMLServerHandler;

import net.grug.minecraft.core.GrugBlockRegion;
import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugClientSavedNumbers;
import net.grug.minecraft.core.GrugClientTestCalls;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugForge125Arms;
import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugLightResend;
import net.grug.minecraft.core.GrugLightSync;
import net.grug.minecraft.core.GrugLinkArms;
import net.grug.minecraft.core.GrugOverlayCalls;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugScreenCalls;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestBox;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestOrigin;
import net.grug.minecraft.core.GrugTestPlayerMove;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.core.GrugWorldRevision;
import net.grug.minecraft.forge125.client.GrugContainer;
import net.grug.minecraft.forge125.control.GrugControlServer125;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.src.AxisAlignedBB;
import net.minecraft.src.Block;
import net.minecraft.src.Chunk;
import net.minecraft.src.ChunkCoordIntPair;
import net.minecraft.src.Entity;
import net.minecraft.src.EntityItem;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntityPlayerMP;
import net.minecraft.src.EntityTracker;
import net.minecraft.src.EnumSkyBlock;
import net.minecraft.src.IInventory;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.Packet51MapChunk;
import net.minecraft.src.StringTranslate;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The adapter for a 1.2.5 dedicated server: everything that is not about which process this is
 * lives in {@link Grug125Adapter}, and what a server answers differently (the level and the player
 * handles, no frames, no sound, no capture) is here. The server has no client, so the client-only
 * functions refuse themselves through {@code ModLoaderAdapter.refuseWithoutAClient}; {@code
 * useBlockForTest} is not one of them and is overridden, so the server right-clicks in its own
 * world, the world the test reads (#1090). {@code GUI.open} is not one either: the server owns the
 * container and the client shows it (#1098). The screen calls and the clicks on them are forwarded
 * to that client (#1094), and the cursor calls are answered from this server's own container, which
 * is the one a click is applied to.
 */
public class Grug125ServerAdapter extends Grug125Adapter {

    /** A player's eye is this far above their feet. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * The screen calls this server makes, which its joined client performs: a server has no screen
     * of its own, so every one of them is a round trip to the client of the player who started the
     * run (#1094). A failure is a host function error, which fails the test.
     */
    private final GrugScreenCalls.Forwarded screens =
            new GrugScreenCalls.Forwarded(
                    GrugControlServer125::server, Grug125ServerAdapter::forwardFailed);

    /**
     * The camera reads this server makes, which its joined client answers with its own camera: a
     * dedicated server has none, so a test that asserts where the client draws from asks there
     * (#1161). A failure is a host function error, which fails the test.
     */
    private final GrugTestCamera.Forwarded camera =
            new GrugTestCamera.Forwarded(
                    GrugControlServer125::server, Grug125ServerAdapter::forwardFailed);

    /**
     * The saved-number reads this server makes, which its joined client answers from its own copy
     * of the block entity: a dedicated server holds the simulated copy, not the one the client's
     * frame pass reads (#1162).
     */
    private final GrugClientSavedNumbers.Forwarded savedNumbers =
            new GrugClientSavedNumbers.Forwarded(
                    GrugControlServer125::server, Grug125ServerAdapter::forwardFailed);

    /**
     * The light comparisons this server makes, which its joined client answers from its own copy of
     * the section: a dedicated server holds the simulated copy, not the one the client's frame pass
     * bakes the room from (#1240).
     */
    private final GrugClientLight.Forwarded clientLight =
            new GrugClientLight.Forwarded(
                    GrugControlServer125::server, Grug125ServerAdapter::forwardFailed);

    /**
     * The overlay reads this server makes, which its joined client answers about the frame it
     * draws: an overlay belongs to a client's frame pass, so a server never records one (#1099). A
     * failure is a host function error, which fails the test.
     */
    private final GrugOverlayCalls.Forwarded overlays =
            new GrugOverlayCalls.Forwarded(
                    GrugControlServer125::server, Grug125ServerAdapter::forwardFailed);

    /**
     * The mining crack and the client resource files of a hot reload, which the joined client
     * answers from its own world and its own mods directory (#1040): the crack is its frame pass's,
     * and a texture is read by the client alone.
     */
    private final GrugClientTestCalls.Forwarded clientTestCalls =
            new GrugClientTestCalls.Forwarded(
                    GrugControlServer125::server, Grug125ServerAdapter::forwardFailed);

    /** Reports a forwarded call the client could not answer as a host function error. */
    @GrugGenerated(
            "a forwarded call fails only when the joined client cannot answer it, which a passing"
                    + " run never has")
    private static void forwardFailed(String message) {
        Grug.hostFunctionErrorHappened(Grug.statePtr, message);
    }

    @Override
    public GrugClientTestCalls.Forwarded forwardedClientTestCalls() {
        return clientTestCalls;
    }

    /**
     * The selection box of a block exists only in this version's client class: the server's {@code
     * Block} has neither {@code setBlockBoundsBasedOnState} nor {@code
     * getSelectedBoundingBoxFromPool}, so a block read here has no box to answer with (#1441).
     */
    @Override
    public boolean blockSelectionIsClientOnly() {
        return true;
    }

    /**
     * How thin the column is that asks which collision boxes cover the player's own point. It only
     * has to be non-zero so a box whose edge is exactly on the point still intersects it.
     */
    private static final double POINT_EDGE = 1e-9;

    @Override
    public GrugSide getSide() {
        return GrugSide.DEDICATED_SERVER;
    }

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        EntityPlayerMP player = (EntityPlayerMP) playerObj;
        IInventory inventory = (IInventory) blockEntityObj;
        TileEntity blockEntity = (TileEntity) blockEntityObj;
        GrugGuiBuilder layout = (GrugGuiBuilder) guiBuilderObj;

        // Whatever container the player had open is closed first, as vanilla does before it
        // installs the next one, so nothing keeps sending that container's slots.
        player.closeCraftingGui();

        GrugContainer container = new GrugContainer(player, inventory, layout);
        int windowId = nextWindowId();
        container.windowId = windowId;
        player.craftingInventory = container;

        // The layout goes first: the client builds its own container from it, numbered the same
        // way, and the slot contents onCraftGuiOpened sends below then land in that container. A
        // client whose copy of the block entity has not arrived yet builds it on a placeholder
        // (#425) and is filled by the same packets.
        player.playerNetServerHandler.sendPacket(
                new GrugScreenPacket(
                        windowId,
                        blockEntity.xCoord,
                        blockEntity.yCoord,
                        blockEntity.zCoord,
                        layout));
        container.onCraftGuiOpened(player);
    }

    /**
     * The window id the next {@code GUI.open} installs its container under, one after another as
     * vanilla's own counter does. It must not be 0: the client treats window 0 as the player's own
     * inventory, so a container opened under it would never receive the slot contents.
     */
    private static int nextWindowId() {
        windowId = windowId % 99 + 1;
        return windowId;
    }

    private static int windowId = 0;

    // The screen functions a client adapter implements. The ones a test makes on a screen are
    // forwarded to the client of the player who started the run (#1094); GUI.open builds the block
    // entity's container here, so the client's screen is the one this server's slots belong to
    // (#1098). The pointer calls are the menu's, so this server answers them itself: a click is
    // applied to this server's own container, and a carried stack only the client knows would not
    // be the one the click uses.

    /**
     * Opened by the client of the player who started the run, whose own inventory it is (#1094).
     *
     * <p>The container this server has open is closed first, so both sides are on the player's own
     * inventory, window 0, by the time the client's screen is up: a press there carries window 0,
     * which this server only accepts as its own player's inventory container.
     */
    @Override
    public void openInventory(Object playerObj) {
        GrugLinkArms.applyTo(
                playerObj, EntityPlayerMP.class, Grug125ServerAdapter::closeOpenContainer);
        screens.openInventory();
    }

    @Override
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        return screens.pressScreenKey(key, ctrl, release);
    }

    @Override
    public boolean scrollScreen(double x, double y, double delta) {
        return screens.scrollScreen(x, y, delta);
    }

    /**
     * Pressed on the client's screen, by the client of the player who started the run (#1094).
     *
     * <p>The screen is the block entity's container, which this server built in {@code GUI.open}
     * under the window id it sent to that client, so the click packet the press sends names this
     * server's container and lands in this server's slots. It is applied by {@link
     * GrugControlServer#applyClientWork} on a later tick, never inside this call, because the
     * server thread is frozen here and cannot read it.
     */
    @Override
    public boolean clickScreen(double x, double y, double button) {
        return screens.clickScreen(x, y, (int) button);
    }

    /**
     * The carried stack of this server's player, which is the one a click uses (#1094).
     *
     * <p>1.2.5 keeps the cursor in the player's inventory, and the click applies to the player's
     * container, so this answer and a click agree. The inherited answer reads the cursor of a
     * client's screen, which a dedicated server has none of.
     */
    @Override
    public Object carriedItem() {
        return GrugForge125Arms.map(
                serverPlayer(),
                player -> {
                    ItemStack stack = player.inventory.getItemStack();
                    return stack == null ? null : stack.getItem();
                });
    }

    /**
     * Sets the carried stack this server's next click uses, and tells the client, so a frame it
     * draws and a click it predicts both agree with it (#1094).
     */
    @Override
    public void setCarriedItem(Object itemObj) {
        GrugForge125Arms.ifPresent(
                serverPlayer(),
                player -> {
                    // A grug call's Item argument is never null, so there is no null arm.
                    player.inventory.setItemStack(new ItemStack((Item) itemObj));
                    // The cursor is not a slot this server's container sends, so the click's own
                    // slot update would leave the client's copy stale: window -1 slot -1 is the
                    // cursor, which is what the game's own updateHeldItem sends when a click
                    // changes it.
                    player.updateHeldItem();
                },
                Grug125ServerAdapter::forwardFailed,
                "TestTickContext.set_carried_item: There is no player to set a cursor on.");
    }

    /** Clears the carried stack, and the client's copy of it (#1094). */
    @Override
    public void deleteCarriedItem() {
        GrugForge125Arms.ifPresent(
                serverPlayer(),
                player -> {
                    player.inventory.setItemStack(null);
                    player.updateHeldItem();
                },
                Grug125ServerAdapter::forwardFailed,
                "OverlayClickContext.delete_carried_item: There is no player to clear a cursor"
                        + " on.");
    }

    /** The player who started the run, or the first joined one, whose container a click uses. */
    private static EntityPlayerMP serverPlayer() {
        return GrugForge125Arms.orElseGet(
                GrugControlServer125.starterPlayer(), GrugServerRun::firstPlayer);
    }

    /**
     * Closes the container this server has open, the way the game's own close packet does, so both
     * sides are back on the player's own inventory, window 0 (#1094).
     */
    @GrugGenerated("the no-container arm: a run's screen work happens with one container open")
    private static void closeOpenContainer(EntityPlayerMP player) {
        if (player.craftingInventory != player.inventorySlots) {
            player.closeCraftingGui();
        }
    }

    @Override
    public double screenWidthForTest() {
        return screens.read(GrugScreenCalls.SCREEN_WIDTH);
    }

    @Override
    public double guiLeftForTest() {
        return screens.read(GrugScreenCalls.GUI_LEFT);
    }

    @Override
    public double guiWidthForTest() {
        return screens.read(GrugScreenCalls.GUI_WIDTH);
    }

    @Override
    public double guiTopForTest() {
        return screens.read(GrugScreenCalls.GUI_TOP);
    }

    @Override
    public double guiHeightForTest() {
        return screens.read(GrugScreenCalls.GUI_HEIGHT);
    }

    @Override
    public Object getLevel() {
        return GrugServerRun.world();
    }

    /**
     * The first player who joined. A server has no local player, so a test that wants one to build
     * fixtures around is given a connected one: the tests share one world and build in a room
     * around the test player, so which one only decides whose position {@code
     * TestTickContext.get_origin} reports.
     */
    @Override
    public Object testPlayer() {
        // The player who started the run, when one has: forwarded calls use their screen (#1040).
        return serverPlayer();
    }

    /**
     * Whether the server's operator list holds the player, which is the game's own check. A
     * dedicated server has no singleplayer owner, so the list is the whole answer (#1331).
     */
    @Override
    public boolean isOperator(Object playerObj) {
        return GrugServerRun.isOp(((EntityPlayer) playerObj).username);
    }

    /** Adds the player to the server's operator list, which the game saves to {@code ops.txt}. */
    @Override
    public void makeOperator(Object playerObj) {
        GrugServerRun.addOp(((EntityPlayer) playerObj).username);
    }

    /**
     * Removes the player from the server's operator list, which the game saves to {@code ops.txt}.
     */
    @Override
    public void removeOperator(Object playerObj) {
        GrugServerRun.removeOp(((EntityPlayer) playerObj).username);
    }

    /**
     * Sweeps the items as the client-only game does, then tells the joined client they are gone.
     * The sweep takes a dead item out of the world's lists itself, so the world's own update never
     * reaches {@code releaseEntitySkin} for it and the entity tracker never sends the destroy
     * packet: the joined client kept a ghost of every swept item, such as a diamond a pipe test
     * dropped, and drew it into later captures (#1068).
     */
    @Override
    public void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2) {
        World world = (World) levelObj;
        List<?> items =
                new ArrayList<Object>(
                        world.getEntitiesWithinAABB(
                                EntityItem.class,
                                AxisAlignedBB.getBoundingBox(x1, y1, z1, x2, y2, z2)));
        List<Entity> mobs = new ArrayList<>(spawnedMobs);
        super.clearItemEntities(levelObj, x1, y1, z1, x2, y2, z2);
        EntityTracker tracker = FMLServerHandler.instance().getServer().getEntityTracker(0);
        for (Object item : items) {
            tracker.untrackEntity((Entity) item);
        }
        for (Entity mob : mobs) {
            tracker.untrackEntity(mob);
        }
    }

    /**
     * Removes the entities as the client-only game does, then tells the joined client they are
     * gone: the removal takes them out of the world's lists itself, so the entity tracker would
     * never send the destroy packet and the client would keep a ghost of each.
     */
    @Override
    public void removeEntities(Object levelObj, String kind) {
        List<Entity> gone = entitiesOf((World) levelObj, kind);
        super.removeEntities(levelObj, kind);
        EntityTracker tracker = FMLServerHandler.instance().getServer().getEntityTracker(0);
        for (Entity entity : gone) {
            tracker.untrackEntity(entity);
        }
    }

    /**
     * Builds the room around the height the hold keeps the player at, not wherever the client is
     * this tick. A client a fraction under the held height put the stand block a block too low, and
     * the hold then kept the player hovering above it until the game kicked them for floating
     * (#1068). A player under the held height is put back on it, so the new stand block is not
     * around their feet.
     */
    @Override
    public void buildTestBox(int radius) {
        // What an earlier test placed and never asked about is not owed to this one (#1664).
        placedRegion.reset();
        EntityPlayerMP player = (EntityPlayerMP) testPlayer();
        double held = GrugServerRun.heldFeetY();
        boolean hasHold =
                GrugLinkArms.deliverIf(
                        GrugForge125Arms.holds(held), player, p -> buildOnHold(p, held, radius));
        GrugForge125Arms.unlessDone(hasHold, radius, this::buildWithoutHold);
    }

    /** The build before a player holds the run's height, which is the shared one. */
    @GrugGenerated(
            "it runs only before the run holds a player's height, which the run starts with and a"
                    + " test never sees")
    private void buildWithoutHold(int radius) {
        super.buildTestBox(radius);
    }

    private void buildOnHold(EntityPlayerMP player, double held, int radius) {
        renderSettle.reset();
        GrugTestBox.build(
                this,
                getLevel(),
                player.posX,
                GrugPlayerFloor.buildAnchor(player.boundingBox.minY, held),
                player.posZ,
                radius,
                false);
        liftToHold(player, held);
    }

    /** Puts a player under the held height back on it. */
    @GrugGenerated(
            "the run's per-tick hold keeps the player at the held height, so a player under it when"
                    + " a room is built is a race with the client's own movement")
    private static void liftToHold(EntityPlayerMP player, double held) {
        if (player.boundingBox.minY >= held) {
            return;
        }
        player.playerNetServerHandler.teleportTo(
                player.posX,
                player.posY + (held - player.boundingBox.minY),
                player.posZ,
                player.rotationYaw,
                player.rotationPitch);
        player.motionY = 0;
    }

    /**
     * The eye of the test player. A server player keeps the feet in {@code posY} where the client
     * keeps the eye, so the eye is rebuilt from the bottom of the bounding box, rounded to a
     * millionth to shed the arithmetic's last bits, which is what the client's {@code posY} is.
     */
    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        return new Vec3(
                player.posX,
                Math.rint(player.boundingBox.minY * 1e6) / 1e6 + EYE_HEIGHT,
                player.posZ);
    }

    /**
     * Ready once the world around the player is loaded, the player has landed and the player is not
     * inside a block. The test origin is read from the player's position, and a player still
     * falling when the first test starts gives an origin whose height differs from run to run
     * (#1052).
     *
     * <p>{@code onGround} alone is not enough: the server copies it from the client's movement
     * packets, and a client that fell through the terrain while its chunks were arriving reports a
     * position a fraction of a block inside the ground, which the server accepts because the box
     * its collision check tests is contracted by 0.0625. From there the client's own collision
     * never reports support, so the wait timed out with the client in the world and the run already
     * started (#1136, the rule #1095 added to b1.7.3-ornithe). {@link GrugPlayerFloor#mayStart} is
     * that rule, so the server's own ground is enough and a player inside a block is never
     * accepted.
     */
    @Override
    public boolean isWorldReady(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        double feet = player.boundingBox.minY;
        return GrugForge125Arms.allOf(
                super.isWorldReady(playerObj),
                () ->
                        GrugPlayerFloor.mayStart(
                                solidBlocks(getLevel()),
                                player.posX,
                                feet,
                                player.posZ,
                                player.onGround));
    }

    /**
     * The collision shapes of a level's blocks, as {@link GrugPlayerFloor} reads them: the top of
     * the highest box that covers the player's own point, which is what the game's own collision
     * meets there. A slab's floor is half a block below its block top, a stair's lower step is not
     * its upper one, and a block with no collision box at all, such as a torch or a plant, is not a
     * floor (#1102, #1136).
     *
     * <p>This generation asks a block for its boxes through {@code getCollidingBoundingBoxes}, with
     * the thin column through the player's own point as the area, so a box is filtered by where the
     * player stands rather than by its block position. The block's bounds follow its metadata, so
     * they are updated for this position first, the way the game updates them before it draws or
     * ray traces the block.
     */
    @SuppressWarnings("rawtypes")
    public static GrugPlayerFloor.BlockSource solidBlocks(Object levelObj) {
        World world = (World) levelObj;
        return (x, y, z, pointX, pointZ) -> {
            Block block = blockAt(world, x, y, z);
            if (block == null) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
            block.setBlockBoundsBasedOnState(world, x, y, z);
            AxisAlignedBB point =
                    AxisAlignedBB.getBoundingBox(
                            pointX - POINT_EDGE,
                            y,
                            pointZ - POINT_EDGE,
                            pointX + POINT_EDGE,
                            y + 1.0,
                            pointZ + POINT_EDGE);
            ArrayList boxes = new ArrayList();
            block.getCollidingBoundingBoxes(world, x, y, z, point, boxes);
            double top = GrugPlayerFloor.BlockSource.NO_COLLISION;
            for (Object boxObj : boxes) {
                top = Math.max(top, ((AxisAlignedBB) boxObj).maxY - y);
            }
            return top;
        };
    }

    /** Asked of the client of the player who started the run, with the world frozen (#1040). */
    @Override
    public boolean hasSound(String path) {
        return GrugForge125Arms.forwarded(
                () -> GrugControlServer125.server().hasSound(path),
                "has_sound",
                Grug125ServerAdapter::forwardFailed,
                false);
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    @GrugGenerated(
            "In a CI run 1.2.5 downloads no sound resources, so the client's pool is empty,"
                    + " has_sound is never true and play_ui_sound refuses every name before it"
                    + " reaches the adapter")
    public void playUiSound(String path, double volume, double pitch) {
        GrugForge125Arms.forwarded(
                new PlayUiSound(path, volume, pitch),
                "play_ui_sound",
                Grug125ServerAdapter::forwardFailed,
                null);
    }

    /** The forwarded sound as a class rather than a lambda, so the exclusion covers its body. */
    @GrugGenerated(
            "In a CI run 1.2.5 downloads no sound resources, so the client's pool is empty,"
                    + " has_sound is never true and play_ui_sound refuses every name before it"
                    + " reaches the adapter")
    private static final class PlayUiSound implements GrugForge125Arms.Forward<Object> {
        private final String path;
        private final double volume;
        private final double pitch;

        PlayUiSound(String path, double volume, double pitch) {
            this.path = path;
            this.volume = volume;
            this.pitch = pitch;
        }

        @Override
        public Object get() throws GrugControlServer.ControlException {
            GrugControlServer125.server().playUiSound(path, volume, pitch);
            return null;
        }
    }

    @Override
    @GrugGenerated("a dedicated server draws no frame, so nothing calls it")
    public void onFrameRendered() {}

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     *
     * <p>A region packet still owed to the joined client is not settled, and neither is the poll
     * that only arms its send: the region is what marks the client's sections dirty, so a capture
     * taken before it reads a mesh baked from the light before it. Such a poll therefore answers
     * before it asks the client, and the send bumps the world revision, so the answer the resend
     * invalidates is one taken before it and the poll after it is a fresh ask (#1161).
     */
    @Override
    public boolean isWorldSettled() {
        return GrugForge125Arms.noStarterOr(
                GrugControlServer125.server().hasStarter(), this::settledForStarter);
    }

    private boolean settledForStarter() {
        boolean owed = lightResend.pending();
        if (owed) {
            resendLight();
        }
        // A poll that owed the region asked no client, so nothing is settled for this poll.
        World level = (World) getLevel();
        Boolean asked =
                GrugForge125Arms.forwardedUnless(
                        owed,
                        () ->
                                GrugControlServer125.server()
                                        .worldSettled(
                                                placedRegion.ask(
                                                        (x, y, z) -> level.getBlockId(x, y, z),
                                                        (cx, cz) ->
                                                                clientHasOrWillGet(level, cx, cz))),
                        "TestTickContext.world_settled",
                        Grug125ServerAdapter::forwardFailed);
        GrugForge125Arms.heldIfSettled(asked, placedRegion);
        return GrugForge125Arms.settled(owed, asked);
    }

    /**
     * The blocks a test placed, whose light the joined client lights for itself and so can light
     * differently from this server's engine (#1092). See {@link GrugLightResend}.
     */
    private final GrugLightResend lightResend = new GrugLightResend();

    /** A block placed here also owes the joined client the light around it. */
    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        super.placeBlock(levelObj, x, y, z, blockName);
        lightResend.noteBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        placedRegion.note((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * Whether the joined client holds the chunk or will be sent it: the server has it loaded and
     * the starter is listening to it, which is what {@code PlayerInstance.addPlayer} records when
     * it sends the pre-chunk packet and {@code removePlayer} undoes. A chunk outside the player's
     * view is never sent, so a placement there must not be waited for (#804, #1664).
     */
    private static boolean clientHasOrWillGet(World level, int cx, int cz) {
        return GrugForge125Arms.chunkReaches(
                GrugForge125Arms.<EntityPlayerMP, java.util.Collection<?>>map(
                        GrugControlServer125.starterPlayer(), player -> player.listeningChunks),
                level.getChunkProvider().chunkExists(cx, cz),
                new ChunkCoordIntPair(cx, cz));
    }

    /**
     * The box of blocks a test placed that the joined client has not been seen to hold, sent with
     * the settle ask so the client's own copy is compared with this server's (#1664).
     */
    private final GrugBlockRegion placedRegion = new GrugBlockRegion();

    /**
     * Sends the joined client this server's light for the blocks a test changed, once the tick that
     * changed them has been flushed to it.
     *
     * <p>This version's client lights a block-change packet from that change alone, while a chunk
     * region packet carries the server's own light, so a room that touches a chunk with few blocks
     * left that chunk's light a level off the server's, which the client-only run's golden does not
     * show. The region packet is the one carrier of light this version has, so the settle wait
     * sends it: the answer is false for the poll that sent it, so the next one asks a client that
     * has the light. See {@link GrugLightResend} and #1092.
     */
    private void resendLight() {
        List<int[]> regions = lightResend.due();
        if (regions.isEmpty()) {
            return;
        }
        GrugLinkArms.deliver(
                GrugControlServer125.starterPlayer(), starter -> sendRegions(starter, regions));
    }

    private void sendRegions(EntityPlayerMP starter, List<int[]> regions) {
        World world = (World) getLevel();
        // The placements left the field their order made, which differs from the one the final
        // blocks define and from the client-only run's, so it is recomputed before it is sent.
        int[] box = lightResend.lastBox();
        relightRoom(
                world, box[0], Math.max(box[1], 0), box[2], box[3], Math.min(box[4], 127), box[5]);
        // The tick's block changes go out first, so the client applies them to the light it holds
        // and the region then replaces that light with this server's.
        GrugServerRun.flushBlockChanges();
        for (int[] region : regions) {
            starter.playerNetServerHandler.sendPacket(buildMapChunkPacket(world, region));
        }
        // The region changed the copy of the world the client shows, so the revision a settle
        // answer belongs to moves with it: an answer the client gave before the send is not
        // reused for the world after it (#1161).
        GrugWorldRevision.bump();
    }

    /**
     * Builds the 1.2.5 region packet for the chunk the region touches, through the server's own
     * {@code Packet51MapChunk(Chunk, boolean, int)}: it packs every masked section's block ids,
     * then their metadata, block light, sky light and block-id nibbles, in the order {@code
     * Chunk.func_48494_a} reads them, and derives the packet's own minimum and maximum section
     * masks from the chunk. This version's packet cannot carry a sub-chunk box, so the whole column
     * of the sections the box spans is sent, and the client marks those sections dirty, so their
     * meshes rebuild from the server's light.
     *
     * <p>{@code includeInitialize} is false, and the mask is the sections the box spans, which is
     * how {@code PlayerInstance} sends a chunk whose sections a block changed: the client already
     * holds the chunk, so it needs the sections and not the chunk, its biome array or its tile
     * entities.
     */
    private static Packet51MapChunk buildMapChunkPacket(World world, int[] region) {
        Chunk chunk = world.getChunkFromChunkCoords(region[0] >> 4, region[2] >> 4);
        // This version's world is 128 blocks high, which is sections 0 to 7 of the chunk's 16. The
        // box a region is cut from is clamped to the world and so spans at least one section.
        return new Packet51MapChunk(
                chunk, false, GrugForge125Arms.sectionMask(region[1], region[4]));
    }

    @Override
    public void forgetSettledFrames() {}

    /**
     * Drops a give the joined client sent for the test that just ended, so the next test starts
     * with the inventory its reset cleared rather than an item that give added (#1144). The queue
     * lives in core's server, and only this loader holds that server.
     */
    @Override
    public void forgetQueuedGivesForTest() {
        GrugControlServer125.server().forgetQueuedGives();
    }

    /**
     * Closes the joined client's screen (#1094). This server has no screen of its own: 1.2.5's GUI
     * is a client-only object, so the call is the client's, and the client of the player who
     * started the run performs it.
     *
     * <p>The container this server opened is closed too, so both sides are on the player's own
     * inventory, window 0, when the call returns. Nothing else does it: the client's controller
     * sends no close packet for the screen it leaves, so a container would otherwise stay open on
     * this server and accept no press from any screen that follows.
     */
    @Override
    public void closeOpenScreenForTest() {
        GrugLinkArms.deliver(serverPlayer(), Grug125ServerAdapter::closeOpenContainer);
        screens.closeScreen();
    }

    /** Whether the joined client of the player who started the run has a screen open (#1094). */
    @Override
    public boolean isScreenOpenForTest() {
        return screens.screenOpen();
    }

    @Override
    public boolean setGameSpeed(double speed) {
        GrugServerRun.setGameSpeed(GrugGameSpeed.wholeSteps(speed));
        return true;
    }

    /** This server has no local player, so it snaps its copy of the joined one instead (#1161). */
    @Override
    @GrugGenerated("this server has no local player to snap, so nothing calls it")
    public void snapPlayerForTestOnClient() {}

    /**
     * Moves this server's copy of the test player to the feet position when the server has that
     * chunk, through the teleport the run's floor hold uses, so the joined client's camera follows
     * (#1040). False when there is no player, no world or the chunk is not loaded, which the caller
     * reports without moving anyone: a teleport into a chunk the joined client has not received
     * crashes this generation of the game.
     */
    @Override
    public boolean setPlayerPositionForTest(double x, double y, double z) {
        EntityPlayerMP player = (EntityPlayerMP) testPlayer();
        World world = (World) getLevel();
        boolean loaded =
                world.getChunkProvider()
                        .chunkExists((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        return GrugLinkArms.deliverIf(
                GrugTestPlayerMove.canMove(GrugLinkArms.known(player), true, loaded),
                player,
                mover -> {
                    GrugServerRun.moveToFeet(mover, x, y, z);
                    GrugTestState.playerMovedTo(x, y, z);
                });
    }

    @Override
    public void snapPlayerForTestOnServer() {
        GrugServerRun.snapToBlockCentre((EntityPlayerMP) testPlayer());
    }

    @Override
    public void restorePlayerAfterTest() {
        GrugServerRun.restoreTestPlayer((EntityPlayerMP) testPlayer());
    }

    @Override
    public boolean miningCrackForTest(Object levelObj, double x, double y, double z) {
        return clientTestCalls.miningCrack(x, y, z);
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's {@code PlayerControllerMP} sends a use
     * packet the server thread is blocked inside this call and so cannot process. So the click is
     * made here instead, through the server's own interaction manager, {@code ItemInWorldManager},
     * which is what {@code NetServerHandler.handlePlace} reaches for a client's use packet: the
     * block's activation first, and the held item's use against it when the block declines.
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The client path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106).
     */
    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        GrugForge125Arms.ifPresent(
                (EntityPlayerMP) testPlayer(),
                player ->
                        GrugForge125Arms.ifPresent(
                                blockAt(world, blockX, blockY, blockZ),
                                block ->
                                        player.itemInWorldManager.activeBlockOrUseItem(
                                                player,
                                                world,
                                                player.inventory.getCurrentItem(),
                                                blockX,
                                                blockY,
                                                blockZ,
                                                GrugUseBlockFace.face(
                                                        testPlayerEye(player),
                                                        blockX,
                                                        blockY,
                                                        blockZ)),
                                Grug125ServerAdapter::forwardFailed,
                                "TestTickContext.use_block: There is no block at "
                                        + blockX
                                        + ", "
                                        + blockY
                                        + ", "
                                        + blockZ
                                        + "."),
                Grug125ServerAdapter::forwardFailed,
                "TestTickContext.use_block: There is no player to right-click with.");
    }

    /**
     * Forwards the capture to the joined client of the player who started the run and waits for the
     * answer with the world frozen (#1040, option A). Arguments are checked first, as on a client.
     */
    @Override
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        return GrugForge125Arms.captured(
                GrugScreenshots.rectangleProblem(x1, y1, x2, y2),
                () ->
                        GrugControlServer125.server()
                                .capture(captureEye(), (int) x1, (int) y1, (int) x2, (int) y2),
                Grug125ServerAdapter::forwardFailed);
    }

    /**
     * The eye the capture is drawn from: where the test moved the player to, else the origin the
     * running test's room was built above, less {@link GrugTestOrigin#LIFT}, or null when this side
     * has no player to measure one from.
     */
    private Vec3 captureEye() {
        return GrugTestState.captureEye(this::getTestOrigin);
    }

    /** The joined client's own camera, which is where its captures are drawn from (#1161). */
    @Override
    public Vec3 testCamera() {
        return camera.camera();
    }

    /** The joined client's own copy of a saved number, which is the copy its frame pass reads. */
    @Override
    public double savedNumberOnClient(double x, double y, double z, String name) {
        return savedNumbers.savedNumber(x, y, z, name);
    }

    /**
     * Whether the joined client's copy holds the light this server's world has in the section the
     * position falls in (#1240), or true where this server cannot verify it.
     *
     * <p>A position in a section the last room relight swept answers true, which is the
     * cannot-verify answer: this version's incremental engine leaves the client's copy of those
     * sections at a field that depends on the changes it saw, while this server's {@link
     * #relightRoom} ends at the one stable field the blocks define, so the two copies are not
     * expected to agree there. The test's wait therefore stays a no-op for the room on this loader,
     * and #1207 records that deviation. A position in a section outside the box, such as one two
     * thousand blocks away that the joined client was never sent, is compared normally.
     *
     * <p>A server with no starter has no client frame to protect and answers true, as {@link
     * #isWorldSettled} does. No light-engine gate is needed: this version computes its light on the
     * server tick, so the tick that placed the test's blocks has already worked it out.
     */
    @Override
    public boolean lightApplied(double x, double y, double z) {
        return GrugForge125Arms.answered(
                GrugForge125Arms.noStarterOr(
                        GrugControlServer125.server().hasStarter(),
                        () -> relitSections.covers(x, y, z)),
                () -> {
                    World world = (World) getLevel();
                    long simulated =
                            GrugLightSync.lightFingerprint(
                                    ((int) Math.floor(x)) >> 4,
                                    ((int) Math.floor(y)) >> 4,
                                    ((int) Math.floor(z)) >> 4,
                                    (lightX, lightY, lightZ) ->
                                            world.getSavedLightValue(
                                                    EnumSkyBlock.Block, lightX, lightY, lightZ));
                    return clientLight.lightApplied(x, y, z, simulated);
                });
    }

    /**
     * An item's name in the language the server holds, which {@code Item.getItemDisplayName} is not
     * the way to ask for: the server's item class has no such method (the game puts it on the
     * client alone), so a search over the item list that read it on this side died with a
     * NoSuchMethodError (#1040). The client's method is the translation of the item's name key plus
     * {@code .name}, and the server carries the same language tables.
     */
    @Override
    public String itemDisplayName(Object itemObj) {
        String key = ((Item) itemObj).getItemName();
        return key == null ? null : StringTranslate.getInstance().translateKey(key + ".name");
    }

    /**
     * Whether the joined client draws overlays at all, which a server's own answer would deny
     * (#1099): the loader marks support where it registers the screen hooks, and a server has none.
     */
    @Override
    public boolean overlaySupportedForTest() {
        return overlays.supported();
    }

    /**
     * Whether a frame of the joined client drew the overlay of the running test's mod on the screen
     * it has open (#1099): the mark belongs to that client's frame pass, not to this server's.
     */
    @Override
    public boolean overlayDrawnForTest(String mod) {
        return overlays.drawn(mod);
    }

    /**
     * Whether the joined client's drawn mark was already clear when the first frame of its open
     * screen began (#1391): the frames are that client's, and it records the mark at the moment the
     * screen's first frame begins, which a server cannot reconstruct from its own empty client.
     */
    @Override
    public boolean overlayFirstFrameClearedForTest(String mod) {
        return overlays.firstFrameCleared(mod);
    }

    /**
     * Whether the joined client asks the overlays' world key, HUD and world exports, which this
     * server's own answer would deny: the loader marks support where it registers the hooks, and a
     * server has none (#1040).
     */
    @Override
    public boolean worldOverlaySupportedForTest() {
        return overlays.worldSupported();
    }

    /**
     * Whether a frame of the joined client drew the world overlay of the running test's mod since
     * its last key press (#1040): the mark belongs to that client's frame pass.
     */
    @Override
    public boolean worldOverlayDrawnForTest() {
        return overlays.worldDrawn(GrugTestState.currentTestMod);
    }

    /** The press is delivered by the joined client's own key path (#1040). */
    @Override
    public void pressWorldKey(int key, boolean ctrl) {
        overlays.pressWorldKey(GrugTestState.currentTestMod, key, ctrl);
    }

    /** This server forwards a test's client-only calls to the player who started the run. */
    @Override
    public boolean forwardsClientCalls() {
        return true;
    }
}
