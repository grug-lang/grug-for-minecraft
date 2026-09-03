package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.block.material.Material;
import net.ornithemc.osl.blocks.api.BlockRegistry;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;

public final class GrugBlocks {

    public static final FooBlock FOO_BLOCK = BlockRegistry.register(
            150,
            NamespacedIdentifiers.from("grug", "foo_block"),
            new FooBlock(150, Material.STONE));

    public static void init() {
        GrugModLoader.LOGGER.info("This line is printed by GrugBlocks.init()");
    }
}
