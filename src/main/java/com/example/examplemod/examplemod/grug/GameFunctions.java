package com.example.examplemod.examplemod.grug;

import com.example.examplemod.examplemod.events.init.InitListener;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.modificationstation.stationapi.api.registry.ItemRegistry;
import net.modificationstation.stationapi.api.util.Identifier;

import java.util.ArrayDeque;
import java.util.Queue;

public class GameFunctions {

    public static final Queue<String> runtimeErrorQueue = new ArrayDeque<>();

    public static final Queue<String> giveItemQueue = new ArrayDeque<>(); // TODO: Remove

    public static void onRuntimeError(String reason) {
        InitListener.LOGGER.error(reason);
        synchronized (runtimeErrorQueue) {
            runtimeErrorQueue.add(reason);
        }
    }

    private static BlockEntity resolveBlockEntity(long blockEntityId) {
        GrugObject obj = Grug.entityData.get(blockEntityId);
        if (obj == null) {
            BlockEntity be = Grug.currentlyInitializingBlockEntity;
            if (be != null) {
                Grug.addEntityWithId(blockEntityId, GrugEntityType.BlockEntity, be);
                return be;
            }
        }
        return (BlockEntity) (obj != null ? obj.object : null);
    }

    public static long get_block_entity_level(long blockEntityId) {
        BlockEntity be = resolveBlockEntity(blockEntityId);
        return Grug.addEntity(GrugEntityType.Level, be.world);
    }

    public static long get_block_pos_of_block_entity(long blockEntityId) {
        BlockEntity be = resolveBlockEntity(blockEntityId);
        return Grug.addEntity(GrugEntityType.BlockPos, new BlockPos(be.x, be.y, be.z));
    }

    public static long BlockPos_above_n(long blockPosId, double n) {
        BlockPos pos = (BlockPos) Grug.entityData.get(blockPosId).object;
        return Grug.addEntity(GrugEntityType.BlockPos, new BlockPos(pos.x(), pos.y() + (int) n, pos.z()));
    }

    public static long BlockPos_center(long blockPosId) {
        BlockPos pos = (BlockPos) Grug.entityData.get(blockPosId).object;
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5));
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

    public static void give_local_player_item(String resourceLocationString) { // TODO: Remove
        synchronized (giveItemQueue) {
            giveItemQueue.add(resourceLocationString);
        }
    }

    public static long item(long resourceLocationId) {
        Identifier id = (Identifier) Grug.entityData.get(resourceLocationId).object;
        Item item = ItemRegistry.INSTANCE.get(id);
        return Grug.addEntity(GrugEntityType.Item, item);
    }

    public static long item_entity(long levelId, double x, double y, double z, long itemStackId) {
        World world = (World) Grug.entityData.get(levelId).object;
        ItemStack stack = (ItemStack) Grug.entityData.get(itemStackId).object;
        ItemEntity itemEntity = new ItemEntity(world, (float) x, (float) y, (float) z, stack);
        return Grug.addEntity(GrugEntityType.ItemEntity, itemEntity);
    }

    public static long ItemEntity_entity(long itemEntityId) {
        return itemEntityId;
    }

    public static long item_stack(long itemId) {
        Item item = (Item) Grug.entityData.get(itemId).object;
        return Grug.addEntity(GrugEntityType.ItemStack, new ItemStack(item));
    }

    public static long resource_location(String resourceLocationString) {
        Identifier id = Identifier.of(resourceLocationString);
        return Grug.addEntity(GrugEntityType.ResourceLocation, id);
    }

    public static void Entity_set_delta_movement(long entityId, long vec3Id) {
        Entity entity = (Entity) Grug.entityData.get(entityId).object;
        Vec3 vec = (Vec3) Grug.entityData.get(vec3Id).object;
        entity.velocityX = vec.x();
        entity.velocityY = vec.y();
        entity.velocityZ = vec.z();
    }

    public static void Entity_spawn(long entityId, long levelId) {
        Entity entity = (Entity) Grug.entityData.get(entityId).object;
        World world = (World) Grug.entityData.get(levelId).object;
        world.spawnEntity(entity);
    }

    public static void set_texture(String texturePath) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.texturePath = texturePath;
        }
    }

    public static void set_blockstate(String path) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.blockstatePath = path;
        }
    }

    public static void set_block_model(String path) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.blockModelPath = path;
        }
    }

    public static void set_item_model(String path) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.itemModelPath = path;
        }
    }

    public static void add_lang(String path) {
        if (Grug.currentlyInitializingBlock != null) {
            Grug.currentlyInitializingBlock.langPaths.add(path);
        }
    }

    public static long vec3(double x, double y, double z) {
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(x, y, z));
    }

    public static long vec3_zero() {
        return Grug.addEntity(GrugEntityType.Vec3, new Vec3(0, 0, 0));
    }
}
