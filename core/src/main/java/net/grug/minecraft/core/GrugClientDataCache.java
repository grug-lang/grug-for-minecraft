package net.grug.minecraft.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The saved data a joined client last received per block position, so a block entity the chunk
 * makes again for the same block starts from it (#1354).
 *
 * <p>The server sends a block entity's first data once, to the copy the client held then. A copy
 * the game drops and asks the chunk to make again would wait for an update that never comes and
 * draw nothing. Entries are kept per world object, so leaving or joining a world starts clean, and
 * an entry only answers for the block id it was received for, so a position whose block changed
 * never hands out the earlier block's data. Each world holds a bounded number of positions, the
 * least recently used one leaving first.
 *
 * @param <D> the loader's own form of the data, which this never looks into
 */
public final class GrugClientDataCache<D> {
    private final int capacity;
    private final Map<Object, Map<Long, Held<D>>> worlds = new WeakHashMap<>();

    private static final class Held<D> {
        final int blockId;
        final D data;

        Held(int blockId, D data) {
            this.blockId = blockId;
            this.data = data;
        }
    }

    public GrugClientDataCache(int capacity) {
        this.capacity = capacity;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Remembers the data received at a position for a block. */
    public synchronized void put(Object world, int x, int y, int z, int blockId, D data) {
        worlds.computeIfAbsent(
                        world,
                        w ->
                                new LinkedHashMap<Long, Held<D>>(64, 0.75f, true) {
                                    @Override
                                    protected boolean removeEldestEntry(
                                            Map.Entry<Long, Held<D>> eldest) {
                                        return size() > capacity;
                                    }
                                })
                .put(pack(x, y, z), new Held<>(blockId, data));
    }

    /**
     * The data last received at the position for that block, or null. An entry kept for another
     * block id is forgotten, because that block is gone.
     */
    public synchronized D get(Object world, int x, int y, int z, int blockId) {
        Map<Long, Held<D>> positions = worlds.get(world);
        if (positions == null) return null;
        long key = pack(x, y, z);
        Held<D> entry = positions.get(key);
        if (entry == null) return null;
        if (entry.blockId != blockId) {
            positions.remove(key);
            return null;
        }
        return entry.data;
    }

    /** Forgets everything held for a world. */
    public synchronized void clear(Object world) {
        worlds.remove(world);
    }
}
