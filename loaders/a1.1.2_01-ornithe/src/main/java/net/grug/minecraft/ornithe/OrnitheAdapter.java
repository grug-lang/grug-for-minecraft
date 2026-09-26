package net.grug.minecraft.ornithe;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.client.GrugScreen;
import net.grug.minecraft.ornithe.item.GrugItem;
import net.minecraft.block.Block;
import net.minecraft.block.BlockWithBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.crafting.CraftingManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.ornithemc.osl.core.api.util.NamespacedIdentifier;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
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
        if (!(blockEntityObj instanceof Inventory inv))
            return 0;
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
            ItemStack removed = inv.removeItem((int)slot, (int)amount);
            if (removed != null) {
                if (blockEntityObj instanceof GrugBlockEntity gbe) {
                    gbe.notifyOutputTaken((int)slot, removed.size);
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
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        if (levelObj instanceof World world) {
            String path = blockName.contains(":") ? blockName.split(":", 2)[1] : blockName;
            Block targetBlock = null;

            // 1. Try resolving custom Grug blocks
            for (Map.Entry<String, net.grug.minecraft.grug.GrugBlockData> entry : Grug.declaredBlocks.entrySet()) {
                if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                    Long fileId = Grug.blockDataByFileId.entrySet().stream()
                            .filter(e -> e.getValue().id.equals(entry.getKey()))
                            .map(Map.Entry::getKey)
                            .findFirst().orElse(null);

                    if (fileId != null) {
                        for (Block block : Block.BY_ID) {
                            if (block instanceof net.grug.minecraft.ornithe.block.GrugBlock gb && gb.blockFileId == fileId) {
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
                    if (Modifier.isStatic(field.getModifiers()) && Block.class.isAssignableFrom(field.getType())) {
                        if (field.getName().equalsIgnoreCase(path) || field.getName().replace("_", "").equalsIgnoreCase(path.replace("_", ""))) {
                            try {
                                targetBlock = (Block) field.get(null);
                                break;
                            } catch (Exception ignored) {}
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
                GrugModLoader.LOGGER.error("placeBlock failed: Could not resolve block " + blockName);
            }
        }
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        if (blockEntityObj instanceof BlockEntity be) {
            return be.world;
        }
        return null;
    }

    @Override
    public Object getClientLevel() {
        return MinecraftInstance.get().world;
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

        // Match custom grug items first
        for (Map.Entry<String, net.grug.minecraft.grug.GrugItemData> entry : Grug.declaredItems.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId = Grug.itemDataByFileId.entrySet().stream()
                        .filter(e -> e.getValue().id.equals(entry.getKey()))
                        .map(Map.Entry::getKey)
                        .findFirst().orElse(null);
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
        for (Map.Entry<String, net.grug.minecraft.grug.GrugBlockData> entry : Grug.declaredBlocks.entrySet()) {
            if (entry.getKey().endsWith(":" + path) || entry.getKey().equals(path)) {
                Long fileId = Grug.blockDataByFileId.entrySet().stream()
                        .filter(e -> e.getValue().id.equals(entry.getKey()))
                        .map(Map.Entry::getKey)
                        .findFirst().orElse(null);
                if (fileId != null) {
                    for (Block block : Block.BY_ID) {
                        if (block instanceof net.grug.minecraft.ornithe.block.GrugBlock gb && gb.blockFileId == fileId) {
                            return Item.BY_ID[block.id];
                        }
                    }
                }
            }
        }

        // Match Vanilla Items via reflection
        for (Field field : Item.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Item.class.isAssignableFrom(field.getType())) {
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
        for (Field field : Block.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Block.class.isAssignableFrom(field.getType())) {
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

    @Override
    public Vec3 getTestOrigin() {
        PlayerEntity player = MinecraftInstance.get().player;
        return new Vec3(player.x, player.y + 3.0, player.z);
    }

    @Override
    public boolean supportsGraphicsTests() {
        return true;
    }

    /** Fixed, deterministic spot for graphics tests to run at. */
    private static final double GRAPHICS_TEST_X = 10000;
    private static final double GRAPHICS_TEST_Y = 100;
    private static final double GRAPHICS_TEST_Z = 10000;

    private boolean graphicsCameraSaved = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    public Vec3 setupGraphicsTestCamera() {
        PlayerEntity player = MinecraftInstance.get().player;
        if (player == null) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.setup_graphics_camera: There is no local player to move.");
            return null;
        }

        savedX = player.x;
        savedY = player.y;
        savedZ = player.z;
        savedYaw = player.yaw;
        savedPitch = player.pitch;
        graphicsCameraSaved = true;

        // Far out in the open, so that neither a real world's terrain nor anything a previous test
        // placed can bleed into the edges of a screenshot. Alpha has no void dimension to hide in.
        player.setPositionAndAngles(GRAPHICS_TEST_X, GRAPHICS_TEST_Y, GRAPHICS_TEST_Z, 0.0F, 0.0F);

        // +3 mirrors getTestOrigin()'s convention: an origin a little above the player to build on.
        return new Vec3(GRAPHICS_TEST_X, GRAPHICS_TEST_Y + 3.0, GRAPHICS_TEST_Z);
    }

    @Override
    public void restoreCameraAfterGraphicsTest() {
        if (!graphicsCameraSaved) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.restore_camera: No saved position; call Test.setup_graphics_camera first.");
            return;
        }
        graphicsCameraSaved = false;

        PlayerEntity player = MinecraftInstance.get().player;
        if (player != null) {
            player.setPositionAndAngles(savedX, savedY, savedZ, savedYaw, savedPitch);
        }
    }

    /** Screenshot tests are pixel-exact against a reference captured at this resolution. */
    public static final int TEST_SCREENSHOT_WIDTH = 1280;
    public static final int TEST_SCREENSHOT_HEIGHT = 720;

    @Override
    public void assertScreenshotEquals(String referencePath, double x1, double y1, double x2, double y2) {
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure. (1300 instead of 130 is an easy mistake to
        // make while writing a new test, and is the whole reason this lists the offending name.)
        String bad = checkCoordinate("x1", x1, TEST_SCREENSHOT_WIDTH);
        if (bad == null)
            bad = checkCoordinate("y1", y1, TEST_SCREENSHOT_HEIGHT);
        if (bad == null)
            bad = checkCoordinate("x2", x2, TEST_SCREENSHOT_WIDTH);
        if (bad == null)
            bad = checkCoordinate("y2", y2, TEST_SCREENSHOT_HEIGHT);
        if (bad != null) {
            Grug.gameFunctionErrorHappened(Grug.statePtr, "Test.assert_screenshot_equals: " + bad);
            return;
        }
        if (x2 <= x1 || y2 <= y1) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.assert_screenshot_equals: the rectangle is empty, since ("
                            + (int) x2 + "," + (int) y2 + ") is not below and right of ("
                            + (int) x1 + "," + (int) y1 + ")");
            return;
        }

        // A defensive safety net rather than the primary sizing mechanism: F7 forces 1280x720
        // around a test run. If that ever stops happening, a mismatch here is much easier to
        // diagnose than a screen of subtly wrong pixels.
        Minecraft mc = MinecraftInstance.get();
        if (mc.width != TEST_SCREENSHOT_WIDTH || mc.height != TEST_SCREENSHOT_HEIGHT) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.assert_screenshot_equals: the window is " + mc.width + "x" + mc.height
                            + ", but screenshot tests are captured at " + TEST_SCREENSHOT_WIDTH + "x"
                            + TEST_SCREENSHOT_HEIGHT);
            return;
        }

        BufferedImage capture = captureRectangle((int) x1, (int) y1, (int) x2, (int) y2, mc.height);
        if (capture == null) {
            return; // captureRectangle already reported why
        }

        // grug resolves a resource to "<mod name>/<path relative to the mod>", so joining it onto
        // the mods root gives the file on disk. This is deliberately NOT GrugResourcePack's
        // new File(modDir, path) pattern, which expects the mod name already stripped off.
        File referenceFile = new File(GrugModLoader.getActiveGrugModsDir(), referencePath);

        if ("true".equals(System.getenv("GRUG_UPDATE_SCREENSHOTS"))) {
            writeReferenceImage(capture, referenceFile, referencePath);
            return;
        }

        compareAgainstReference(capture, referenceFile, referencePath);
    }

    /** Returns null if the coordinate is in range, or a message naming it if it isn't. */
    private static String checkCoordinate(String name, double value, int limit) {
        if (value < 0 || value > limit) {
            return name + " is " + (int) value + ", which is outside the " + TEST_SCREENSHOT_WIDTH + "x"
                    + TEST_SCREENSHOT_HEIGHT + " frame (0 to " + limit + ")";
        }
        return null;
    }

    /**
     * Reads back a rectangle of the frame that's currently on screen.
     *
     * <p>This runs from Minecraft.tick(), i.e. after the previous frame was rendered and swapped but
     * before this frame is drawn. So the frame we want is in the <em>front</em> buffer; a
     * double-buffered context leaves the back buffer undefined after a swap. Reading the front is
     * also correct for a single-buffered one, where the two are the same buffer.
     *
     * <p>Coordinate convention, shared with the F5 cursor-position hotkey: the rectangle
     * (x1,y1,x2,y2) is in screen space with the origin at the top left, but GL's origin is the
     * bottom left. A screen row {@code y} is therefore GL row {@code height - 1 - y}, which puts
     * the bottom edge of the crop at GL row {@code height - y2}. glReadPixels then fills the buffer
     * bottom row first, so rows are written into the image in reverse to land the right way up.
     */
    private static BufferedImage captureRectangle(int x1, int y1, int x2, int y2, int windowHeight) {
        int width = x2 - x1;
        int height = y2 - y1;
        int glY = windowHeight - y2;

        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);

        int previousReadBuffer = GL11.GL_BACK_LEFT;
        GL11.glReadBuffer(GL11.GL_FRONT_LEFT);
        GL11.glReadPixels(x1, glY, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int glError = GL11.glGetError();
        GL11.glReadBuffer(previousReadBuffer);

        if (glError != GL11.GL_NO_ERROR) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.assert_screenshot_equals: glReadPixels failed with GL error 0x"
                            + Integer.toHexString(glError)
                            + ", so the capture cannot be trusted. A multisampled or otherwise "
                            + "unreadable framebuffer is the usual cause.");
            return null;
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        pixels.flip();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int r = pixels.get() & 0xFF;
                int g = pixels.get() & 0xFF;
                int b = pixels.get() & 0xFF;
                pixels.get(); // alpha, unused
                // Reversed: GL's first row is the bottom of the crop, the image's last is the top.
                image.setRGB(column, height - 1 - row, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static void writeReferenceImage(BufferedImage capture, File referenceFile, String referencePath) {
        try {
            File parent = referenceFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            ImageIO.write(capture, "png", referenceFile);
        } catch (Exception e) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.assert_screenshot_equals: failed to write the reference image to "
                            + referencePath + ": " + e);
            return;
        }
        System.out.println("[GRUG CI] Wrote screenshot reference " + referencePath + " ("
                + capture.getWidth() + "x" + capture.getHeight() + ")");
    }

    private static void compareAgainstReference(BufferedImage capture, File referenceFile, String referencePath) {
        BufferedImage reference;
        try {
            reference = ImageIO.read(referenceFile);
        } catch (Exception e) {
            reference = null;
        }

        if (reference == null) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.assert_screenshot_equals: could not read the reference image " + referencePath
                            + ". Re-run with GRUG_UPDATE_SCREENSHOTS=true to create it.");
            return;
        }

        if (reference.getWidth() != capture.getWidth() || reference.getHeight() != capture.getHeight()) {
            throw Grug.fatal("Screenshot mismatch against " + referencePath + ": the reference is "
                    + reference.getWidth() + "x" + reference.getHeight() + " but the capture is "
                    + capture.getWidth() + "x" + capture.getHeight()
                    + ". The crop rectangle and the reference image have to agree.");
        }

        // No tolerance: CI is pinned to ubuntu-24.04 precisely so that this can be exact.
        for (int y = 0; y < capture.getHeight(); y++) {
            for (int x = 0; x < capture.getWidth(); x++) {
                if (reference.getRGB(x, y) != capture.getRGB(x, y)) {
                    throw Grug.fatal("Screenshot mismatch against " + referencePath + " at pixel ("
                            + x + "," + y + "): expected 0x" + Integer.toHexString(reference.getRGB(x, y))
                            + " but got 0x" + Integer.toHexString(capture.getRGB(x, y)));
                }
            }
        }
    }

    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        if (!(levelObj instanceof World world)) {
            return;
        }
        PlayerEntity player = MinecraftInstance.get().player;
        if (player == null) {
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.use_block: There is no local player to right-click with.");
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
            Grug.gameFunctionErrorHappened(Grug.statePtr,
                    "Test.use_block: There is no grug block at " + blockX + ", " + blockY + ", " + blockZ + ".");
        }
    }
}
