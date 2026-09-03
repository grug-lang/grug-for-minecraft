package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.ornithemc.osl.blocks.api.BlockRegistry;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;
import net.grug.minecraft.ornithe.block.entity.FooBlockEntity;

import java.lang.reflect.Method;

public final class GrugBlocks {
    public static final FooBlock FOO_BLOCK = BlockRegistry.register(
            99,
            NamespacedIdentifiers.from("grug", "foo_block"),
            new FooBlock(99, Material.STONE));

    public static void init() {
        GrugModLoader.LOGGER.info("This line is printed by GrugBlocks.init()");

        // Register the BlockEntity using Reflection to bypass the 'private' modifier
        try {
            Method registerMethod = BlockEntity.class.getDeclaredMethod("register", Class.class, String.class);
            registerMethod.setAccessible(true);
            registerMethod.invoke(null, FooBlockEntity.class, "grug:foo_block_entity");
        } catch (Exception e) {
            GrugModLoader.LOGGER.error("Failed to register FooBlockEntity!", e);
        }
    }
}
