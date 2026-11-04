package net.grug.minecraft.grug;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The weak table that resolves a grug entity id to the host object behind it.
 *
 * <p>The client's render thread removes a context id while the server thread roots and resolves
 * entities, so the table is read and written from more than one thread. A {@code HashMap} can lose
 * an entry or corrupt itself under that, and a lost entry is an id that stops resolving and takes
 * the JVM down inside a host call, so the table is concurrent. See #1108.
 */
public class WeakGrugValueMap {
    private final Map<Long, WeakValue> map = new ConcurrentHashMap<>();
    private final ReferenceQueue<GrugObject> queue = new ReferenceQueue<>();

    public void put(long id, GrugObject value) {
        cleanup();
        map.put(id, new WeakValue(id, value, queue));
    }

    public GrugObject get(long id) {
        cleanup();
        WeakValue ref = map.get(id);
        if (ref == null) return null;
        return ref.get();
    }

    /** Removes the entry for {@code id}, answering what it held, or null when it held nothing. */
    public GrugObject remove(long id) {
        cleanup();
        WeakValue ref = map.remove(id);
        return ref != null ? ref.get() : null;
    }

    private void cleanup() {
        WeakValue ref;
        while ((ref = (WeakValue) queue.poll()) != null) {
            map.remove(ref.id);
        }
    }

    public void clear() {
        map.clear();
    }

    public int size() {
        cleanup();
        return map.size();
    }

    private static class WeakValue extends WeakReference<GrugObject> {
        final long id;

        WeakValue(long id, GrugObject referent, ReferenceQueue<? super GrugObject> q) {
            super(referent, q);
            this.id = id;
        }
    }
}
