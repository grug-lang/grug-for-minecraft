package net.grug.minecraft.stationapi;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.grug.minecraft.stationapi.grug.DummyCraftingInventory;
import net.grug.minecraft.stationapi.gui.GrugScreenHandler;
import net.grug.minecraft.stationapi.gui.StationGuiHelper;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipeManager;
import net.minecraft.world.World;
import net.modificationstation.stationapi.api.gui.screen.container.GuiHelper;
import net.modificationstation.stationapi.api.network.packet.MessagePacket;
import net.modificationstation.stationapi.api.registry.BlockRegistry;
import net.modificationstation.stationapi.api.registry.ItemRegistry;
import net.modificationstation.stationapi.api.util.Identifier;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

public class StationApiAdapter implements ModLoaderAdapter {

    @Override
    public File getGameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public File getGrugModsDirectory() {
        return InitListener.getActiveGrugModsDir();
    }

    /**
     * Note: Although StationAPI is based on Fabric, it targets Minecraft Beta 1.7.3. Therefore, we
     * do not have access to modern UI abstractions like
     * net.minecraft.screen.SimpleNamedScreenHandlerFactory (or Forge's SimpleMenuProvider).
     * Instead, we manually synchronize the GUI data using StationAPI's MessagePacket system.
     */
    @Override
    @GrugGenerated("client GUI glue: the screen and handler are excluded render glue")
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        BlockEntity be = (BlockEntity) blockEntityObj;
        GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;

        if (be instanceof Inventory inv) {
            GuiHelper.openGUI(
                    player,
                    Identifier.of("grug:dynamic_gui"),
                    inv,
                    new GrugScreenHandler(player, inv, builder),
                    messagePacket ->
                            StationGuiHelper.writeBuilderToPacket(
                                    builder,
                                    messagePacket,
                                    packetSyncId(messagePacket),
                                    be.x,
                                    be.y,
                                    be.z));
        }
    }

    @GrugGenerated("gui packet sync id: the packet always carries at least one int")
    private static int packetSyncId(MessagePacket messagePacket) {
        return (messagePacket.ints != null && messagePacket.ints.length > 0)
                ? messagePacket.ints[0]
                : 0;
    }

    // --- Logging Abstraction ---

    @Override
    public void logInfo(String message) {
        InitListener.LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        InitListener.LOGGER.error(message);
    }

    // --- Game Functions Abstraction ---

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        Entity entity = (Entity) entityObj;
        entity.velocityX = dx;
        entity.velocityY = dy;
        entity.velocityZ = dz;
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((World) levelObj).spawnEntity((Entity) entityObj);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        Inventory inv = (Inventory) blockEntityObj;
        DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
        for (int i = 0; i < matrix.size(); i++) {
            ItemStack stack = matrix.getStack(i);
            if (stack != null) {
                matrix.removeStack(i, 1);
                applyCraftingReturn(matrix, i, stack);
            }
        }
    }

    @GrugGenerated("crafting return: no item in this version carries one")
    private static void applyCraftingReturn(
            DummyCraftingInventory matrix, int slot, ItemStack stack) {
        if (stack.getItem().hasCraftingReturnItem()) {
            matrix.setStack(slot, new ItemStack(stack.getItem().getCraftingReturnItem()));
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage)
                total += stack.count;
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        BlockEntity be =
                world.getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        Inventory inv = (Inventory) be;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null) {
                world.spawnEntity(new ItemEntity(world, x, y, z, stack));
                inv.setStack(i, null);
            }
        }
    }

    @Override
    public double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.size() && remainingToExtract > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == (int) damage) {
                int extractFromSlot = Math.min(stack.count, remainingToExtract);
                inv.removeStack(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double insertItemIntoInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxSize = new ItemStack(item, 1, meta).getMaxCount();
        int remaining = (int) amount;

        // Top up existing stacks first, then use empty slots, like a player's inventory fills.
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && stack.getItem() == item && stack.getDamage() == meta) {
                int room = maxSize - stack.count;
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    stack.count += add;
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            if (inv.getStack(i) == null) {
                int add = Math.min(maxSize, remaining);
                inv.setStack(i, new ItemStack(item, add, meta));
                remaining -= add;
            }
        }

        return amount - remaining;
    }

    @Override
    public double takeItemFromSlot(Object blockEntityObj, double slot, double amount) {
        Inventory inv = (Inventory) blockEntityObj;
        ItemStack removed = inv.removeStack((int) slot, (int) amount);
        if (removed != null) {
            if (blockEntityObj instanceof GrugBlockEntity gbe) {
                gbe.notifyOutputTaken((int) slot, removed.count);
            }
            return removed.count;
        }
        return 0;
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return ((World) levelObj)
                .getBlockEntity((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((BlockEntity) blockEntityObj).world;
    }

    @Override
    public Object getClientLevel() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        return mc.world;
    }

    @Override
    public Object getPlayer() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        return mc.player;
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        net.minecraft.block.entity.BlockEntity be =
                (net.minecraft.block.entity.BlockEntity) blockEntityObj;
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        be.writeNbt(nbt);
        be.readNbt(nbt);
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        return new BlockPos(be.x, be.y, be.z);
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return ((Inventory) blockEntityObj).size();
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getStack((int) slot);
        return stack != null ? stack.count : 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getStack((int) slot);
        return stack != null ? stack.getDamage() : 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Inventory) blockEntityObj).getStack((int) slot);
        return (stack != null) ? stack.getItem() : null;
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        return ItemRegistry.INSTANCE.get((Identifier) resourceLocationObj);
    }

    @Override
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity(
                (World) levelObj, (float) x, (float) y, (float) z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj) {
        return new ItemStack((Item) itemObj);
    }

    @Override
    public Object createResourceLocation(String string) {
        return Identifier.of(string);
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        Inventory inv = (Inventory) blockEntityObj;
        ItemStack stack = inv.getStack((int) slot);
        if (stack != null) {
            if (count <= 0) inv.setStack((int) slot, null);
            else stack.count = (int) count;
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        Inventory inv = (Inventory) blockEntityObj;
        inv.setStack((int) slot, new ItemStack((Item) itemObj, (int) count));
    }

    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot, double outputSlot) {
        Inventory inv = (Inventory) blockEntityObj;
        DummyCraftingInventory matrix = new DummyCraftingInventory(inv, (int) startSlot);
        ItemStack result = CraftingRecipeManager.getInstance().craft(matrix);
        inv.setStack((int) outputSlot, result != null ? result.copy() : null);
    }

    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        // A Level argument cannot be wrong because of a script, so cast directly.
        placeBlockIn((World) levelObj, x, y, z, blockName);
    }

    private void placeBlockIn(World world, double x, double y, double z, String blockName) {
        Identifier id =
                Identifier.of(blockName.contains(":") ? blockName : "minecraft:" + blockName);
        Block targetBlock = BlockRegistry.INSTANCE.get(id);

        if (targetBlock != null) {
            int posX = (int) Math.floor(x);
            int posY = (int) Math.floor(y);
            int posZ = (int) Math.floor(z);

            world.setBlock(posX, posY, posZ, targetBlock.id);
        } else {
            InitListener.LOGGER.error("placeBlock failed: Could not resolve block " + blockName);
        }
    }

    @Override
    public String getBlock(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        int id = world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

        if (id == 0) {
            return "minecraft:air";
        }

        Block block = (id >= 0 && id < Block.BLOCKS.length) ? Block.BLOCKS[id] : null;
        if (block == null) {
            return "minecraft:unknown";
        }

        Identifier key = BlockRegistry.INSTANCE.getId(block);
        return key == null ? "minecraft:unknown" : key.toString();
    }

    @Override
    public boolean isAir(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        return world.getBlockId((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) == 0;
    }

    @Override
    public double countItemEntities(
            Object levelObj, double x, double y, double z, double radius) {
        World world = (World) levelObj;
        double radiusSquared = radius * radius;
        int count = 0;
        for (Object entity : world.entities) {
            if (entity instanceof ItemEntity item) {
                double dx = item.x - x;
                double dy = item.y - y;
                double dz = item.z - z;
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Vec3 getTestOrigin() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        return new Vec3(mc.player.x, mc.player.y + 3.0, mc.player.z);
    }

    private boolean graphicsCameraSaved = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public Vec3 setupGraphicsTestCamera() {
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        PlayerEntity player = mc.player;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.setup_graphics_camera: There is no local player to move.");
            return null;
        }

        savedX = player.x;
        savedY = player.y;
        savedZ = player.z;
        savedYaw = player.yaw;
        savedPitch = player.pitch;
        graphicsCameraSaved = true;

        // Level the view so the frame doesn't depend on which way the player was looking, but
        // deliberately do NOT move them: teleporting into a chunk the client hasn't lit yet crashes
        // this generation of the game. A screenshot test crops a rectangle with the GUI centred, so
        // the terrain behind it never matters.
        player.setPositionAndAngles(player.x, player.y, player.z, 0.0F, 0.0F);

        return new Vec3(player.x, player.y + 3.0, player.z);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restoreCameraAfterGraphicsTest() {
        if (!graphicsCameraSaved) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.restore_camera: No saved position; call Test.setup_graphics_camera"
                            + " first.");
            return;
        }
        graphicsCameraSaved = false;

        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.player != null) {
            mc.player.setPositionAndAngles(savedX, savedY, savedZ, savedYaw, savedPitch);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        @SuppressWarnings("deprecation")
        net.minecraft.client.Minecraft mc =
                (net.minecraft.client.Minecraft) FabricLoader.getInstance().getGameInstance();
        PlayerEntity player = mc.player;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // World.getBlockId() hands back a block id rather than a Block in this generation.
        int blockId = world.getBlockId(blockX, blockY, blockZ);
        Block block =
                (blockId >= 0 && blockId < Block.BLOCKS.length) ? Block.BLOCKS[blockId] : null;

        if (block instanceof GrugBlock grugBlock) {
            // Go through the block's own onUse() so the test drives the same code path a real
            // right-click does, instead of a copy of the block's GUI layout.
            grugBlock.onUse(world, blockX, blockY, blockZ, player);
        } else {
            Grug.hostFunctionErrorHappened(
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

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void assertScreenshotEquals(
            String referencePath, double x1, double y1, double x2, double y2) {
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure.
        String bad = checkCoordinate("x1", x1, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y1", y1, GrugScreenshots.HEIGHT);
        if (bad == null) bad = checkCoordinate("x2", x2, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y2", y2, GrugScreenshots.HEIGHT);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.assert_screenshot_equals: " + bad);
            return;
        }
        if (x2 <= x1 || y2 <= y1) {
            Grug.hostFunctionErrorHappened(
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

        // A defensive safety net rather than the primary sizing mechanism: R forces 1280x720 around
        // a test run. If that ever stops happening, a mismatch here is much easier to diagnose than
        // a screen of subtly wrong pixels.
        @SuppressWarnings("deprecation")
        Minecraft mc = (Minecraft) FabricLoader.getInstance().getGameInstance();
        if (mc.displayWidth != GrugScreenshots.WIDTH
                || mc.displayHeight != GrugScreenshots.HEIGHT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: the window is "
                            + mc.displayWidth
                            + "x"
                            + mc.displayHeight
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return;
        }

        BufferedImage capture =
                captureRectangle((int) x1, (int) y1, (int) x2, (int) y2, mc.displayHeight);
        if (capture == null) {
            return; // captureRectangle already reported why
        }

        // grug resolves a resource to "<mod name>/<path relative to the mod>", so joining it onto
        // the mods root gives the reference directory on disk. That directory holds numbered PNGs
        // and the assertion passes if the capture matches any of them; see GrugScreenshots.
        File referenceDirectory = new File(InitListener.getActiveGrugModsDir(), referencePath);
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
     * Reads back a rectangle of the frame that's currently on screen. Runs from Minecraft.tick(),
     * i.e. after the previous frame was rendered but before this one is drawn, so the frame of
     * interest is whatever was drawn most recently. On LWJGL 2 that is the back buffer, which is
     * also GL's default read buffer.
     *
     * <p>Coordinate convention: the rectangle (x1,y1,x2,y2) is in screen space with the origin at
     * the top left, but GL's origin is the bottom left. A screen row y is therefore GL row
     * height-1-y, which puts the bottom edge of the crop at GL row height-y2. glReadPixels fills
     * the buffer bottom row first, so rows are written into the image in reverse to land the right
     * way up.
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
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: the pixel readback failed unexpectedly: " + e);
            return null;
        }
        int glError = GL11.glGetError();
        if (glError != GL11.GL_NO_ERROR) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: glReadPixels failed with GL error 0x"
                            + Integer.toHexString(glError)
                            + ", so the capture cannot be trusted. A multisampled or otherwise "
                            + "unreadable framebuffer is the usual cause.");
            return null;
        }

        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    int index = (row * width + column) * 4;
                    int r = pixels.get(index) & 0xFF;
                    int g = pixels.get(index + 1) & 0xFF;
                    int b = pixels.get(index + 2) & 0xFF;
                    image.setRGB(column, height - 1 - row, (r << 16) | (g << 8) | b);
                }
            }
            return image;
        } catch (RuntimeException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.assert_screenshot_equals: could not build an image from the readback: "
                            + e);
            return null;
        }
    }
}
