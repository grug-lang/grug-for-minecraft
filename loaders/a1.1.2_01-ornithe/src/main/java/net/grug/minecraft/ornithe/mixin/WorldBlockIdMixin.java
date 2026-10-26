package net.grug.minecraft.ornithe.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The random tick loop reads a block id straight out of the chunk's byte array, so an id of 128 or
 * more indexes {@code Block.TICKS_RANDOMLY} and {@code Block.BY_ID} negative. See {@link
 * WorldChunkBlockIdMixin} and #599.
 */
@Mixin(World.class)
public abstract class WorldBlockIdMixin {
    @Definition(id = "ticksRandomly", field = "Lnet/minecraft/block/Block;TICKS_RANDOMLY:[Z")
    @Expression("ticksRandomly[?]")
    @WrapOperation(method = "tickChunks", at = @At("MIXINEXTRAS:EXPRESSION"))
    private boolean grug$unsignedTicksRandomly(
            boolean[] ticksRandomly, int id, Operation<Boolean> original) {
        return original.call(ticksRandomly, id & 255);
    }

    @Definition(
            id = "byId",
            field = "Lnet/minecraft/block/Block;BY_ID:[Lnet/minecraft/block/Block;")
    @Expression("byId[?]")
    @WrapOperation(method = "tickChunks", at = @At("MIXINEXTRAS:EXPRESSION"))
    private Block grug$unsignedBlock(Block[] byId, int id, Operation<Block> original) {
        return original.call(byId, id & 255);
    }
}
