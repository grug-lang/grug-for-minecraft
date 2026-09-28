package net.grug.minecraft.ornithe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.client.GrugScreen;
import net.grug.minecraft.ornithe.item.GrugItem;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.crafting.CraftingManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;
import net.ornithemc.osl.core.api.util.NamespacedIdentifier;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Map;

public class OrnitheAdapter implements ModLoaderAdapter {

    @Override
    public File getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public File getGrugModsDirectory() {
        return GrugModLoader.getActiveGrugModsDir();
    }

    @Override
    public void logInfo(String message) {
        GrugModLoader.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        GrugModLoader.LOGGER.error(message);
    }

    // --- GUI & Inventory Methods ---

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        // The player and builder types cannot be wrong because of a script, so cast directly.
        PlayerEntity player = (PlayerEntity) playerObj;
        if (!(blockEntityObj instanceof Inventory inventory)) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr, "GUI.open: The block entity has no inventory.");
            return;
        }
        GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr,
                    "GUI.open: Opening a GUI on a dedicated server is not supported yet.");
            return;
        }

        GrugScreen.open(player, inventory, builder);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        if (blockEntityObj instanceof Inventory inv) {
            // Alpha has no recipe remainders (like the empty bucket from a milk bucket)
            for (int i = 0; i < 9; i++) {
                int slot = (int) startSlot + i;
                if (inv.getItem(slot) != null) {
                    inv.removeItem(slot, 1);
                }
            }
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        if (!(blockEntityObj instanceof Inventory inv)) return 0;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.metadata == (int) damage)
                total += stack.size;
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        if (!(levelObj instanceof World world)) return;
        BlockEntity be =
                world.getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
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
    public double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        if (!(blockEntityObj instanceof Inventory inv)) return 0;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.getSize() && remainingToExtract > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getItem() == item && stack.metadata == (int) damage) {
                int extractFromSlot = Math.min(stack.size, remainingToExtract);
                inv.removeItem(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double takeItemFromSlot(Object blockEntityObj, double slot, double amount) {
        if (blockEntityObj instanceof Inventory inv) {
            ItemStack removed = inv.removeItem((int) slot, (int) amount);
            if (removed != null) {
                if (blockEntityObj instanceof GrugBlockEntity gbe) {
                    gbe.notifyOutputTaken((int) slot, removed.size);
                }
                return removed.size;
            }
        }
        return 0;
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
            return stack != null ? stack.metadata : 0;
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
                if (count <= 0) inv.setItem((int) slot, null);
                else stack.size = (int) count;
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
            // Alpha recipes match on item ids only, with -1 for an empty cell
            int[] ids = new int[9];
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem((int) startSlot + i);
                ids[i] = stack != null ? stack.id : -1;
            }
            ItemStack result = CraftingManager.getInstance().getResult(ids);
            inv.setItem((int) outputSlot, result != null ? result.copy() : null);
        }
    }

    // --- World & Entity Methods ---

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        Entity entity = (Entity) entityObj;
        entity.velocityX = dx;
        entity.velocityY = dy;
        entity.velocityZ = dz;
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((World) levelObj).addEntity((Entity) entityObj);
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return ((World) levelObj)
                .getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        if (levelObj instanceof World world) {
            String path = blockName.contains(":") ? blockName.split(":", 2)[1] : blockName;
            Block targetBlock = null;

            // 1. Try resolving custom Grug blocks
            for (Map.Entry<String, net.grug.minecraft.grug.GrugBlockData> entry :
                    Grug.declaredBlocks.entrySet()) {
                if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                    Long fileId =
                            Grug.blockDataByFileId.entrySet().stream()
                                    .filter(e -> e.getValue().id.equals(entry.getKey()))
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                                    .orElse(null);

                    if (fileId != null) {
                        for (Block block : Block.BY_ID) {
                            if (block instanceof net.grug.minecraft.ornithe.block.GrugBlock gb
                                    && gb.blockFileId == fileId) {
                                targetBlock = block;
                                break;
                            }
                        }
                    }
                }
            }

            // 2. Try resolving Vanilla blocks via reflection
            if (targetBlock == null) {
                for (Field field : Block.class.getFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            && Block.class.isAssignableFrom(field.getType())) {
                        if (field.getName().equalsIgnoreCase(path)
                                || field.getName()
                                        .replace("_", "")
                                        .equalsIgnoreCase(path.replace("_", ""))) {
                            try {
                                targetBlock = (Block) field.get(null);
                                break;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }

            if (targetBlock != null) {
                int posX = (int) Math.floor(x);
                int posY = (int) Math.floor(y);
                int posZ = (int) Math.floor(z);

                world.setBlockQuietly(posX, posY, posZ, targetBlock.id);

                if (targetBlock instanceof net.minecraft.block.BlockWithBlockEntity) {
                    targetBlock.onAdded(world, posX, posY, posZ);
                }
            } else {
                GrugModLoader.LOGGER.error(
                        "placeBlock failed: Could not resolve block " + blockName);
            }
        }
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((BlockEntity) blockEntityObj).world;
    }

    @Override
    public Object getClientLevel() {
        return MinecraftInstance.get().world;
    }

    @Override
    public Object getPlayer() {
        return MinecraftInstance.get().player;
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        NbtCompound nbt = new NbtCompound();
        be.writeNbt(nbt);
        be.readNbt(nbt);
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        return new BlockPos(be.x, be.y, be.z);
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        String path = ((NamespacedIdentifier) resourceLocationObj).identifier();

        // Match custom grug items first
        for (Map.Entry<String, net.grug.minecraft.grug.GrugItemData> entry :
                Grug.declaredItems.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        Grug.itemDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                if (fileId != null) {
                    for (Item item : Item.BY_ID) {
                        if (item instanceof GrugItem gi && gi.itemFileId == fileId) {
                            return item;
                        }
                    }
                }
            }
        }

        // Match custom grug blocks
        for (Map.Entry<String, net.grug.minecraft.grug.GrugBlockData> entry :
                Grug.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId =
                        Grug.blockDataByFileId.entrySet().stream()
                                .filter(e -> e.getValue().id.equals(entry.getKey()))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                if (fileId != null) {
                    for (Block block : Block.BY_ID) {
                        if (block instanceof net.grug.minecraft.ornithe.block.GrugBlock gb
                                && gb.blockFileId == fileId) {
                            return Item.BY_ID[block.id];
                        }
                    }
                }
            }
        }

        // Match Vanilla Items via reflection
        for (Field field : Item.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Item.class.isAssignableFrom(field.getType())) {
                if (field.getName().equalsIgnoreCase(path)
                        || field.getName()
                                .replace("_", "")
                                .equalsIgnoreCase(path.replace("_", ""))) {
                    try {
                        return field.get(null);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Match Vanilla Blocks via reflection
        for (Field field : Block.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Block.class.isAssignableFrom(field.getType())) {
                if (field.getName().equalsIgnoreCase(path)
                        || field.getName()
                                .replace("_", "")
                                .equalsIgnoreCase(path.replace("_", ""))) {
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
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity((World) levelObj, x, y, z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj) {
        if (itemObj instanceof Item item) {
            return new ItemStack(item);
        }
        return new ItemStack((Block) itemObj);
    }

    @Override
    public Object createResourceLocation(String string) {
        if (!string.contains(":")) {
            return NamespacedIdentifiers.from("minecraft", string);
        }
        String[] parts = string.split(":", 2);
        return NamespacedIdentifiers.from(parts[0], parts[1]);
    }

    @Override
    public Vec3 getTestOrigin() {
        PlayerEntity player = MinecraftInstance.get().player;
        return new Vec3(player.x, player.y + 3.0, player.z);
    }

    private boolean graphicsCameraSaved = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public Vec3 setupGraphicsTestCamera() {
        PlayerEntity player = MinecraftInstance.get().player;
        if (player == null) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr, "Test.setup_graphics_camera: There is no local player to move.");
            return null;
        }

        savedX = player.x;
        savedY = player.y;
        savedZ = player.z;
        savedYaw = player.yaw;
        savedPitch = player.pitch;
        graphicsCameraSaved = true;

        // Level the view so the test's frame doesn't depend on which way the player happened to be
        // looking, but deliberately do NOT move them somewhere far away.
        //
        // The obvious choice would be a fixed far-off coordinate like 10000,100,10000, so no
        // terrain could bleed into the background. But that reliably crashes Alpha. Teleporting
        // into a chunk the client hasn't lit yet makes World.doLightUpdates recurse through
        // World.updateLight and LightUpdate.run until the stack overflows, which killed the test
        // run roughly one time in four. Staying put keeps the build site inside chunks that are
        // already loaded and lit.
        //
        // Nothing is lost by it: a screenshot test crops a rectangle of the frame, and the GUI is
        // always centred in the window, so the terrain behind it is never part of the comparison.
        player.setPositionAndAngles(player.x, player.y, player.z, 0.0F, 0.0F);

        // +3 mirrors getTestOrigin()'s convention: an origin a little above the player to build on.
        return new Vec3(player.x, player.y + 3.0, player.z);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restoreCameraAfterGraphicsTest() {
        if (!graphicsCameraSaved) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.restore_camera: No saved position; call Test.setup_graphics_camera"
                            + " first.");
            return;
        }
        graphicsCameraSaved = false;

        PlayerEntity player = MinecraftInstance.get().player;
        if (player != null) {
            player.setPositionAndAngles(savedX, savedY, savedZ, savedYaw, savedPitch);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void assertScreenshotEquals(
            String referencePath, double x1, double y1, double x2, double y2) {
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure. (1300 instead of 130 is an easy mistake to
        // make while writing a new test, and is the whole reason this lists the offending name.)
        String bad = checkCoordinate("x1", x1, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y1", y1, GrugScreenshots.HEIGHT);
        if (bad == null) bad = checkCoordinate("x2", x2, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y2", y2, GrugScreenshots.HEIGHT);
        if (bad != null) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "Test.assert_screenshot_equals: " + bad);
            return;
        }
        if (x2 <= x1 || y2 <= y1) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: the rectangle is empty, since ("
                            + (int) x2
                            + ","
                            + (int) y2
                            + ") is not below and right of ("
                            + (int) x1
                            + ","
                            + (int) y1
                            + ")");
            return;
        }

        // A defensive safety net rather than the primary sizing mechanism: R forces 1280x720
        // around a test run. If that ever stops happening, a mismatch here is much easier to
        // diagnose than a screen of subtly wrong pixels.
        Minecraft mc = MinecraftInstance.get();
        if (mc.width != GrugScreenshots.WIDTH || mc.height != GrugScreenshots.HEIGHT) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: the window is "
                            + mc.width
                            + "x"
                            + mc.height
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return;
        }

        BufferedImage capture = captureRectangle((int) x1, (int) y1, (int) x2, (int) y2, mc.height);
        if (capture == null) {
            return; // captureRectangle already reported why
        }

        // grug resolves a resource to "<mod name>/<path relative to the mod>", so joining it onto
        // the mods root gives the reference directory on disk. That directory holds numbered PNGs
        // and the assertion passes if the capture matches any of them; see GrugScreenshots.
        File referenceDirectory = new File(GrugModLoader.getActiveGrugModsDir(), referencePath);
        GrugScreenshots.verify(capture, referenceDirectory, referencePath);
    }

    /** Returns null if the coordinate is in range, or a message naming it if it isn't. */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private static String checkCoordinate(String name, double value, int limit) {
        if (value < 0 || value > limit) {
            return name
                    + " is "
                    + (int) value
                    + ", which is outside the "
                    + GrugScreenshots.WIDTH
                    + "x"
                    + GrugScreenshots.HEIGHT
                    + " frame (0 to "
                    + limit
                    + ")";
        }
        return null;
    }

    /**
     * Reads back a rectangle of the frame that's currently on screen.
     *
     * <p>This runs from Minecraft.tick(), i.e. after the previous frame was rendered but before
     * this one is drawn, so the frame of interest is whatever was drawn most recently. On LWJGL 2
     * that is the <em>back</em> buffer, which is also GL's default read buffer, so no glReadBuffer
     * call is needed. Reading the front buffer instead was tried first and returns solid black
     * here: this stack (LWJGL 2 + Mesa under Xvfb) doesn't leave the rendered frame readable
     * through the front buffer at this point in the loop.
     *
     * <p>Coordinate convention, shared with the M cursor-position readout: the rectangle
     * (x1,y1,x2,y2) is in screen space with the origin at the top left, but GL's origin is the
     * bottom left. A screen row {@code y} is therefore GL row {@code height - 1 - y}, which puts
     * the bottom edge of the crop at GL row {@code height - y2}. glReadPixels then fills the buffer
     * bottom row first, so rows are written into the image in reverse to land the right way up.
     */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private static BufferedImage captureRectangle(
            int x1, int y1, int x2, int y2, int windowHeight) {
        int width = x2 - x1;
        int height = y2 - y1;
        int glY = windowHeight - y2;

        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);

        try {
            GL11.glReadPixels(x1, glY, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        } catch (RuntimeException e) {
            // A Java exception escaping into the JNI layer gets described and cleared there, and
            // the script carries on, which would turn a broken capture into a passing test. Report
            // it the way every other problem here is reported, so that the script aborts instead.
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: the pixel readback failed unexpectedly: " + e);
            return null;
        }
        int glError = GL11.glGetError();

        if (glError != GL11.GL_NO_ERROR) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: glReadPixels failed with GL error 0x"
                            + Integer.toHexString(glError)
                            + ", so the capture cannot be trusted. A multisampled or otherwise "
                            + "unreadable framebuffer is the usual cause.");
            return null;
        }

        try {
            // Absolute gets, because glReadPixels leaves the buffer's position wherever it likes
            // (LWJGL rewinds it before the read), so its position and limit can't be relied on
            // afterwards. glReadPixels also fills the buffer bottom row first, hence the reversed
            // row index: the image's row 0 has to be the crop's top row, which is the buffer's
            // last.
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int index = (row * width + column) * 4;
                    int r = pixels.get(index) & 0xFF;
                    int g = pixels.get(index + 1) & 0xFF;
                    int b = pixels.get(index + 2) & 0xFF;
                    // index + 3 is alpha, which the framebuffer may not even have; unused.
                    image.setRGB(column, height - 1 - row, (r << 16) | (g << 8) | b);
                }
            }
            return image;
        } catch (RuntimeException e) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: could not build an image from the readback: "
                            + e);
            return null;
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        PlayerEntity player = MinecraftInstance.get().player;
        if (player == null) {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // In Alpha, World.getBlock() hands back a block id rather than a Block.
        int blockId = world.getBlock(blockX, blockY, blockZ);
        Block block = (blockId >= 0 && blockId < Block.BY_ID.length) ? Block.BY_ID[blockId] : null;

        if (block instanceof GrugBlock grugBlock) {
            // Go through the block's own use() so the test drives the same code path a real
            // right-click does, instead of a copy of the block's GUI layout.
            grugBlock.use(world, blockX, blockY, blockZ, player);
        } else {
            Grug.gameFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.use_block: There is no grug block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
        }
    }
}
