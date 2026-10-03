package net.grug.minecraft.forge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import net.grug.minecraft.core.GrugWorldReady;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge.gui.GrugMenu;
import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockNames;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

public class ForgeAdapter implements ModLoaderAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public File getGameDirectory() {
        return FMLPaths.GAMEDIR.get().toFile();
    }

    @Override
    public File getGrugModsDirectory() {
        return GrugModLoader.getActiveGrugModsDir();
    }

    @Override
    @GrugGenerated("client GUI glue: the menu and screen are excluded render glue")
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        if (playerObj instanceof ServerPlayer serverPlayer) {
            if (blockEntityObj instanceof BlockEntity be) {
                GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;

                serverPlayer.openMenu(
                        new SimpleMenuProvider(
                                (windowId, inv, player) ->
                                        new GrugMenu(
                                                GrugModLoader.GRUG_MENU.get(),
                                                windowId,
                                                inv,
                                                (Container) be,
                                                builder),
                                Component.literal("Grug GUI")),
                        buf -> GrugMenu.writeMenuData(buf, be.getBlockPos(), builder));
            }
        }
    }

    @Override
    public void logInfo(String message) {
        LOGGER.info(message);
    }

    @Override
    public void logError(String message) {
        // A contained mod err, and the sandbox's own report of it: the JNI layer clears whatever a
        // script threw and the script carries on, so this is where a failed script call surfaces.
        LOGGER.error(message);
    }

    @Override
    public void setEntityDeltaMovement(Object entityObj, double dx, double dy, double dz) {
        ((Entity) entityObj).setDeltaMovement(dx, dy, dz);
    }

    @Override
    public void spawnEntity(Object levelObj, Object entityObj) {
        ((Level) levelObj).addFreshEntity((Entity) entityObj);
    }

    @Override
    public void consumeCraftingIngredients(Object blockEntityObj, double startSlot) {
        Container inv = (Container) blockEntityObj;
        for (int i = 0; i < 9; i++) {
            int slot = (int) startSlot + i;
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty()) {
                inv.removeItem(slot, 1);
                applyCraftingRemaining(inv, slot, stack);
            }
        }
    }

    @GrugGenerated("crafting remaining item: no item in this version carries one")
    private static void applyCraftingRemaining(Container inv, int slot, ItemStack stack) {
        if (stack.getItem().hasCraftingRemainingItem()) {
            inv.setItem(slot, new ItemStack(stack.getItem().getCraftingRemainingItem()));
        }
    }

    @Override
    public double countItemInInventory(Object blockEntityObj, Object itemObj, double damage) {
        Container inv = (Container) blockEntityObj;
        Item item = (Item) itemObj;
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            // 1.20.6 has removed traditional damage values for components, but assuming
            // direct comparison here.
            if (!stack.isEmpty() && stack.is(item) && stack.getDamageValue() == (int) damage) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public void dropInventory(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        BlockEntity be =
                world.getBlockEntity(
                        new net.minecraft.core.BlockPos(
                                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        Container inv = (Container) be;
        Containers.dropContents(world, be.getBlockPos(), inv);
        inv.clearContent();
    }

    @Override
    public double extractItemFromInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Container inv = (Container) blockEntityObj;
        Item item = (Item) itemObj;
        int remainingToExtract = (int) amount;
        for (int i = 0; i < inv.getContainerSize() && remainingToExtract > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item) && stack.getDamageValue() == (int) damage) {
                int extractFromSlot = Math.min(stack.getCount(), remainingToExtract);
                inv.removeItem(i, extractFromSlot);
                remainingToExtract -= extractFromSlot;
            }
        }
        return amount - remainingToExtract;
    }

    @Override
    public double insertItemIntoInventory(
            Object blockEntityObj, Object itemObj, double damage, double amount) {
        Container inv = (Container) blockEntityObj;
        Item item = (Item) itemObj;
        int meta = (int) damage;
        int maxSize = new ItemStack(item).getMaxStackSize();
        int remaining = (int) amount;

        // Top up existing stacks first, then use empty slots, like a player's inventory fills.
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item) && stack.getDamageValue() == meta) {
                int room = maxSize - stack.getCount();
                if (room > 0) {
                    int add = Math.min(room, remaining);
                    stack.grow(add);
                    remaining -= add;
                }
            }
        }

        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            if (inv.getItem(i).isEmpty()) {
                int add = Math.min(maxSize, remaining);
                ItemStack stack = new ItemStack(item, add);
                stack.setDamageValue(meta);
                inv.setItem(i, stack);
                remaining -= add;
            }
        }

        return amount - remaining;
    }

    @Override
    public double takeItemFromSlot(Object blockEntityObj, double slot, double amount) {
        Container inv = (Container) blockEntityObj;
        ItemStack removed = inv.removeItem((int) slot, (int) amount);
        return !removed.isEmpty() ? removed.getCount() : 0;
    }

    @Override
    public double takeCraftingResult(Object blockEntityObj, double amount) {
        if (blockEntityObj instanceof GrugBlockEntity gbe) {
            ItemStack removed = gbe.takeResultStack((int) amount);
            if (!removed.isEmpty()) {
                gbe.notifyOutputTaken(removed.getCount());
                return removed.getCount();
            }
            return 0;
        }

        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "take_crafting_result: reference result extraction is not implemented for this"
                        + " loader.");
        return 0;
    }

    @Override
    public Object getBlockEntity(Object levelObj, double x, double y, double z) {
        return ((Level) levelObj)
                .getBlockEntity(
                        new net.minecraft.core.BlockPos(
                                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    @Override
    public Object getBlockEntityLevel(Object blockEntityObj) {
        return ((BlockEntity) blockEntityObj).getLevel();
    }

    @GrugGenerated("client level: the server-vs-client choice is loader plumbing")
    @Override
    public Object getClientLevel() {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            return ServerLifecycleHooks.getCurrentServer().overworld();
        }
        return Minecraft.getInstance().level;
    }

    @Override
    public Object getPlayer() {
        return Minecraft.getInstance().player;
    }

    @Override
    public void roundTripNbt(Object blockEntityObj) {
        BlockEntity be = (BlockEntity) blockEntityObj;
        Level level = be.getLevel();
        CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
        be.loadWithComponents(tag, level.registryAccess());
    }

    @Override
    public BlockPos getBlockPosOfBlockEntity(Object blockEntityObj) {
        net.minecraft.core.BlockPos pos = ((BlockEntity) blockEntityObj).getBlockPos();
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public double getInventorySize(Object blockEntityObj) {
        return ((Container) blockEntityObj).getContainerSize();
    }

    @Override
    public double getItemCountInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Container) blockEntityObj).getItem((int) slot);
        return !stack.isEmpty() ? stack.getCount() : 0;
    }

    @Override
    public double getItemDamageInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Container) blockEntityObj).getItem((int) slot);
        return !stack.isEmpty() ? stack.getDamageValue() : 0;
    }

    @Override
    public Object getItemInSlot(Object blockEntityObj, double slot) {
        ItemStack stack = ((Container) blockEntityObj).getItem((int) slot);
        return !stack.isEmpty() ? stack.getItem() : null;
    }

    @Override
    public Object getItemFromRegistry(Object resourceLocationObj) {
        return ForgeRegistries.ITEMS.getValue((ResourceLocation) resourceLocationObj);
    }

    @Override
    public Object createItemEntity(
            Object levelObj, double x, double y, double z, Object itemStackObj) {
        return new ItemEntity((Level) levelObj, x, y, z, (ItemStack) itemStackObj);
    }

    @Override
    public Object createItemStack(Object itemObj, double damage) {
        ItemStack stack = new ItemStack((Item) itemObj);
        stack.setDamageValue((int) damage);
        return stack;
    }

    @Override
    public double getItemEntityDamage(Object itemEntityObj) {
        return ((ItemEntity) itemEntityObj).getItem().getDamageValue();
    }

    @Override
    public Object findItemEntity(Object levelObj, double x, double y, double z, double radius) {
        Level world = (Level) levelObj;
        net.minecraft.world.phys.AABB box =
                new net.minecraft.world.phys.AABB(
                        x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        java.util.List<ItemEntity> entities = world.getEntitiesOfClass(ItemEntity.class, box);
        return entities.isEmpty() ? null : entities.get(0);
    }

    @Override
    public Object createResourceLocation(String string) {
        return new ResourceLocation(string);
    }

    @Override
    public void setItemCountInSlot(Object blockEntityObj, double slot, double count) {
        Container inv = (Container) blockEntityObj;
        ItemStack stack = inv.getItem((int) slot);
        if (!stack.isEmpty()) {
            if (count <= 0) {
                inv.setItem((int) slot, ItemStack.EMPTY);
            } else {
                stack.setCount((int) count);
            }
        }
    }

    @Override
    public void setItemDamageInSlot(Object blockEntityObj, double slot, double damage) {
        ItemStack stack = ((Container) blockEntityObj).getItem((int) slot);
        if (!stack.isEmpty()) {
            stack.setDamageValue((int) damage);
        }
    }

    @Override
    public void setItemInSlot(Object blockEntityObj, double slot, Object itemObj, double count) {
        Container inv = (Container) blockEntityObj;
        inv.setItem((int) slot, new ItemStack((Item) itemObj, (int) count));
    }

    @GrugGenerated("recipe output: crafting glue that needs the level's recipe manager")
    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot) {
        if (!(blockEntityObj instanceof GrugBlockEntity gbe)
                || !(blockEntityObj instanceof BlockEntity be)
                || be.getLevel() == null
                || !(be instanceof Container inv)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "update_recipe_output: "
                            + blockEntityObj.getClass().getName()
                            + " has no crafting result container.");
            return;
        }

        Level level = be.getLevel();
        TransientCraftingContainer craftingContainer =
                new TransientCraftingContainer(
                        new AbstractContainerMenu(null, -1) {
                            @GrugGenerated("crafting menu placeholder")
                            @Override
                            public ItemStack quickMoveStack(Player player, int index) {
                                return ItemStack.EMPTY;
                            }

                            @GrugGenerated("crafting menu placeholder")
                            @Override
                            public boolean stillValid(Player player) {
                                return false;
                            }
                        },
                        3,
                        3);

        int start = (int) startSlot;
        for (int i = 0; i < 9; i++) {
            craftingContainer.setItem(i, inv.getItem(start + i));
        }

        Optional<RecipeHolder<CraftingRecipe>> recipe =
                level.getRecipeManager()
                        .getRecipeFor(RecipeType.CRAFTING, craftingContainer, level);

        if (recipe.isPresent()) {
            ItemStack result =
                    recipe.get().value().assemble(craftingContainer, level.registryAccess());
            gbe.setResultStack(result);
        } else {
            gbe.setResultStack(ItemStack.EMPTY);
        }
    }

    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        Level world = (Level) levelObj;
        ResourceLocation id =
                new ResourceLocation(
                        blockName.contains(":") ? blockName : "minecraft:" + blockName);
        Block targetBlock = resolveBlock(id);
        if (targetBlock == null) {
            // The loaders do not all spell every vanilla block the same way, so a name one of them
            // has gets one more try under the spelling the others use. See #58.
            String other = GrugBlockNames.otherSpelling(id.getPath());
            if (other != null) targetBlock = resolveBlock(id.withPath(other));
        }

        if (targetBlock != null) {
            net.minecraft.core.BlockPos pos =
                    new net.minecraft.core.BlockPos(
                            (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

            world.setBlockAndUpdate(pos, targetBlock.defaultBlockState());

            // A placement that reports false because the block was already there is not a failure,
            // so the block's presence is the check rather than the return value. A y outside the
            // build height is refused, and a position in a chunk the client has not received
            // swallows the write too. A write the game accepts and does not leave in place is
            // reported the same way: the caller asked for a block that is not there afterwards. See
            // #151.
            if (world.getBlockState(pos).getBlock() != targetBlock) {
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr,
                        "place_block: the game did not put "
                                + blockName
                                + " at "
                                + pos.getX()
                                + ", "
                                + pos.getY()
                                + ", "
                                + pos.getZ()
                                + ". A y outside this version's world height is refused, and a"
                                + " position in a chunk the client has not received is discarded.");
            }
        } else {
            // The script named a block this loader does not have, which is the script's err and
            // stays inside the sandbox: the call fails, the mod carries on, and the run sees it
            // wherever it sees host errors.
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "place_block: Could not resolve block " + blockName);
        }
    }

    @Override
    public boolean hasNeighborSignal(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        return world.hasNeighborSignal(
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    public String getBlock(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        Block block = world.getBlockState(pos).getBlock();
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    @Override
    public boolean isAir(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return world.getBlockState(pos).isAir();
    }

    @Override
    public double countItemEntities(Object levelObj, double x, double y, double z, double radius) {
        Level world = (Level) levelObj;
        net.minecraft.world.phys.AABB box =
                new net.minecraft.world.phys.AABB(
                        x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        return world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)
                .size();
    }

    @Override
    public void notifyNeighbors(Object levelObj, double x, double y, double z) {
        Level world = (Level) levelObj;
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        world.updateNeighborsAt(pos, world.getBlockState(pos).getBlock());
    }

    @GrugGenerated("block resolution: Forge returns AIR rather than null for unknown blocks")
    private Block resolveBlock(ResourceLocation id) {
        Block targetBlock = ForgeRegistries.BLOCKS.getValue(id);
        return (targetBlock != null && targetBlock != Blocks.AIR) ? targetBlock : null;
    }

    @Override
    public boolean isWorldReady(Object playerObj) {
        Player player = (Player) playerObj;
        Object level = getClientLevel();

        return GrugWorldReady.isReady(
                (x, y, z) -> isAir(level, x, y, z), player.getX(), player.getY(), player.getZ());
    }

    @GrugGenerated("test origin: server-vs-client player choice is loader plumbing")
    @Override
    public Vec3 getTestOrigin() {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            var players = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                Player player = players.get(0);
                return new Vec3(player.getX(), player.getY() + 3.0, player.getZ());
            }
        }
        Player player = Minecraft.getInstance().player;
        return new Vec3(player.getX(), player.getY() + 3.0, player.getZ());
    }

    private boolean graphicsCameraSaved = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public Vec3 setupGraphicsTestCamera() {
        // The test runner is driven from the integrated server thread, but the camera is the client
        // player's, so client-side work is marshalled onto the client (render) thread.
        final Vec3[] result = new Vec3[1];
        onClientThread(() -> setupCameraOnClient(result));
        return result[0];
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void setupCameraOnClient(Vec3[] result) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.setup_graphics_camera: There is no local player to move.");
            return;
        }

        savedX = player.getX();
        savedY = player.getY();
        savedZ = player.getZ();
        savedYaw = player.getYRot();
        savedPitch = player.getXRot();
        graphicsCameraSaved = true;

        // Level the view so the frame doesn't depend on which way the player was looking. Do not
        // move them: the screenshot crops a rectangle with the GUI centred, so terrain behind it
        // never matters, and moving risks loading unlit chunks.
        player.setYRot(0.0F);
        player.setXRot(0.0F);

        result[0] = new Vec3(player.getX(), player.getY() + 3.0, player.getZ());
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

        onClientThread(this::restoreCameraOnClient);
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void restoreCameraOnClient() {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.setPos(savedX, savedY, savedZ);
            player.setYRot(savedYaw);
            player.setXRot(savedPitch);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        onClientThread(() -> useBlockOnClient(x, y, z));
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void useBlockOnClient(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        // Drive a real right-click through the interaction manager, so the block's own use code
        // runs rather than a copy of its GUI layout.
        BlockHitResult hit =
                new BlockHitResult(
                        new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5),
                        Direction.UP,
                        pos,
                        false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void assertScreenshotEquals(
            String referencePath,
            double x1,
            double y1,
            double x2,
            double y2,
            double tolerancePercent) {
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure.
        String bad = checkCoordinate("x1", x1, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y1", y1, GrugScreenshots.HEIGHT);
        if (bad == null) bad = checkCoordinate("x2", x2, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y2", y2, GrugScreenshots.HEIGHT);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.equals: " + bad);
            return;
        }
        if (x2 <= x1 || y2 <= y1) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.equals: the rectangle is empty, since ("
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow().getWidth() != GrugScreenshots.WIDTH
                || mc.getWindow().getHeight() != GrugScreenshots.HEIGHT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Screenshot.equals: the window is "
                            + mc.getWindow().getWidth()
                            + "x"
                            + mc.getWindow().getHeight()
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return;
        }

        BufferedImage capture = captureOnClientThread((int) x1, (int) y1, (int) x2, (int) y2);
        if (capture == null) {
            return; // capture already reported why
        }

        // grug resolves a resource to "<mod name>/<path relative to the mod>", so joining it onto
        // the mods root gives the reference directory on disk. That directory holds numbered PNGs
        // and the assertion passes if the capture matches any of them; see GrugScreenshots.
        File referenceDirectory = new File(GrugModLoader.getActiveGrugModsDir(), referencePath);
        GrugScreenshots.verify(capture, referenceDirectory, referencePath, tolerancePercent);
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

    private static BufferedImage captureOnClientThread(int x1, int y1, int x2, int y2) {
        final BufferedImage[] result = new BufferedImage[1];
        onClientThread(() -> result[0] = capture(x1, y1, x2, y2));
        return result[0];
    }

    /**
     * Reads back a rectangle of the last rendered frame. Runs on the client thread and downloads
     * the main render target's colour texture (the same path vanilla screenshots use), which is
     * Y-flipped so the image's origin is the top left, matching the crop convention.
     */
    private static BufferedImage capture(int x1, int y1, int x2, int y2) {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        NativeImage image = Screenshot.takeScreenshot(target);
        try {
            int width = x2 - x1;
            int height = y2 - y1;
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    // NativeImage stores pixels as ABGR (so they upload to GL as RGBA).
                    int pixel = image.getPixelRGBA(x1 + column, y1 + row);
                    int r = pixel & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    out.setRGB(column, row, (r << 16) | (g << 8) | b);
                }
            }
            return out;
        } finally {
            image.close();
        }
    }

    /**
     * Runs a task on the client (render) thread, blocking until it has finished.
     *
     * <p>The waiting thread is inside a grug call, and the task can enter the state itself (a block
     * use, a runtime error report), so the wait releases the state lock: the blocked thread is not
     * running grug code, and the task is then the one active entry.
     */
    @GrugGenerated("client-thread marshalling: the same-thread path cannot occur in a CI run")
    private static void onClientThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            task.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mc.execute(
                () -> {
                    try {
                        task.run();
                    } finally {
                        latch.countDown();
                    }
                });
        Grug.runWithStateLockReleased(() -> awaitLatch(latch));
    }

    /**
     * Awaits the released client-thread task. The interrupt path only runs when the game shuts down
     * mid-wait, which a CI run does not do.
     */
    @GrugGenerated("client-thread marshalling: an interrupted wait cannot occur in a CI run")
    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
