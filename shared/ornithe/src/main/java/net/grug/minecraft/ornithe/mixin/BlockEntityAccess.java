package net.grug.minecraft.ornithe.mixin;

import net.minecraft.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code BlockEntity.register}, which maps a block entity class to the id it is saved
 * under.
 *
 * <p>The method is private, and reflecting on it by its mapped name works only in a development
 * run: an installed game names it by its intermediary name, so the lookup fails there. An invoker
 * is remapped with the rest of the jar. See {@code GrugBlocks.init}.
 */
@Mixin(BlockEntity.class)
public interface BlockEntityAccess {
    @Invoker("register")
    static void grug$register(Class<?> type, String id) {
        throw new AssertionError("BlockEntityAccess was not applied");
    }
}
