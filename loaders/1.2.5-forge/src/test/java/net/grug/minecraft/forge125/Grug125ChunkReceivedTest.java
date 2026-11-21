package net.grug.minecraft.forge125;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.src.ChunkProviderClient;
import net.minecraft.src.EmptyChunk;

import org.junit.jupiter.api.Test;

/**
 * A move into a chunk the client never received must be refused, and the game's own client chunk
 * provider says the chunk exists for every chunk (#1427).
 */
class Grug125ChunkReceivedTest {

    @Test
    void anUnreceivedChunkIsNotReceivedEvenThoughTheClientProviderSaysItExists() {
        ChunkProviderClient provider = new ChunkProviderClient(null);
        assertTrue(provider.chunkExists(6250, 6250), "the premise: blockExists would say yes");
        assertFalse(Grug125ChunkReceived.received(provider, 6250, 6250));
    }

    @Test
    void theEmptyChunkIsTheOneAnUnreceivedChunkAnswers() {
        ChunkProviderClient provider = new ChunkProviderClient(null);
        assertTrue(provider.provideChunk(1, 1) instanceof EmptyChunk);
    }
}
