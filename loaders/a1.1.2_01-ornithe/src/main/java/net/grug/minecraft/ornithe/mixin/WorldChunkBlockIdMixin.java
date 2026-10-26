package net.grug.minecraft.ornithe.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.world.chunk.WorldChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reads a chunk's block ids as unsigned bytes, which lets a block use an id from 128 to 255.
 *
 * <p>Alpha keeps an id in one byte per cell, and most of its reads mask the byte with {@code &
 * 255}. Six do not, so an id of 128 or more comes back negative there and indexes {@code
 * Block.OPACITIES} or {@code Block.TICKS_RANDOMLY} out of bounds. Beta masks every read, which is
 * why its range already reaches 254. The save format does not change: the {@code Blocks} tag holds
 * the same bytes either way. The sixth read is in {@link WorldBlockIdMixin}. See #599.
 */
@GrugGenerated(
        "a mixin into the game's chunk and world code: the game runs it, and"
                + " high_block_id-Test.grug fails on Alpha without it")
@Mixin(WorldChunk.class)
public abstract class WorldChunkBlockIdMixin {
    /** {@code getBlockAt} returns the raw byte, so every caller sees the negative id. */
    @ModifyReturnValue(method = "getBlockAt", at = @At("RETURN"))
    private int grug$unsignedBlockAt(int id) {
        return id & 255;
    }

    /**
     * The height map loops index the opacity table with the raw byte, and {@code setBlockAt} and
     * {@code setBlockWithMetadataAt} with the id after a {@code (byte)} cast. Masking the index
     * leaves every id below 128 as it was.
     */
    @Definition(id = "opacities", field = "Lnet/minecraft/block/Block;OPACITIES:[I")
    @Expression("opacities[?]")
    @WrapOperation(
            method = {
                "populateHeightMapOnly",
                "updateHeightMap",
                "setBlockAt",
                "setBlockWithMetadataAt"
            },
            at = @At("MIXINEXTRAS:EXPRESSION"))
    private int grug$unsignedOpacity(int[] opacities, int id, Operation<Integer> original) {
        return original.call(opacities, id & 255);
    }
}
