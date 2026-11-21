package net.grug.minecraft.forge125;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.ChunkProviderClient;
import net.minecraft.src.IChunkProvider;

/** Whether a 1.2.5 client has received a chunk, which {@code World.blockExists} cannot tell. */
final class Grug125ChunkReceived {
    private Grug125ChunkReceived() {}

    /**
     * A multiplayer client's {@code ChunkProviderClient} answers {@code chunkExists} true for every
     * chunk, so {@code blockExists} cannot tell, and {@code provideChunk} answers the shared empty
     * chunk for one that never arrived. Any other provider is the integrated server's own, whose
     * {@code chunkExists} is the loaded-chunk question (#1427).
     */
    @GrugGenerated(
            "the chunkExists fallback: every client world, single player included, is a"
                    + " WorldClient whose provider is a ChunkProviderClient, so no CI client"
                    + " reaches another provider")
    static boolean received(IChunkProvider provider, int chunkX, int chunkZ) {
        if (provider instanceof ChunkProviderClient) {
            return !provider.provideChunk(chunkX, chunkZ).isEmpty();
        }
        return provider.chunkExists(chunkX, chunkZ);
    }
}
