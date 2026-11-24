package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugBlockRegion;
import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.client.Minecraft;

/** The joined client's block ids, as {@link GrugBlockRegion.Ids}, for the server's settle ask. */
@GrugGenerated(
        "only a joined client reads its own world for a settle ask that carries a region (#1664),"
                + " and the coverage gate counts no joined client's run: a client-only run never"
                + " receives one, so no test the gate counts can reach it")
final class Grug125ClientBlocks implements GrugBlockRegion.Ids {
    private final Minecraft minecraft;

    Grug125ClientBlocks(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public int idAt(int x, int y, int z) {
        if (minecraft.theWorld == null
                || !Grug125ChunkReceived.received(
                        minecraft.theWorld.getChunkProvider(), x >> 4, z >> 4)) {
            return GrugBlockRegion.ABSENT;
        }
        return minecraft.theWorld.getBlockId(x, y, z);
    }
}
