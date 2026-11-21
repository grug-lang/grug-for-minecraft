package net.grug.minecraft.forge125;

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
    static boolean received(IChunkProvider provider, int chunkX, int chunkZ) {
        if (provider instanceof ChunkProviderClient) {
            return !provider.provideChunk(chunkX, chunkZ).isEmpty();
        }
        return provider.chunkExists(chunkX, chunkZ);
    }
}
