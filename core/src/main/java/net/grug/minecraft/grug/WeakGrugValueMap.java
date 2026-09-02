package net.grug.minecraft.grug;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public class WeakGrugValueMap {
    private final Map<Long, WeakValue> map = new HashMap<>();
    private final ReferenceQueue<GrugObject> queue = new ReferenceQueue<>();

    public void put(long id, GrugObject value) {
        cleanup();
        map.put(id, new WeakValue(id, value, queue));
    }

    public GrugObject get(long id) {
        cleanup();
        WeakValue ref = map.get(id);
        if (ref == null)
            return null;
        return ref.get();
    }

    private void cleanup() {
        WeakValue ref;
        while ((ref = (WeakValue) queue.poll()) != null) {
            map.remove(ref.id);
        }
    }

    public void clear() {
        map.clear();
        while (queue.poll() != null) {
        }
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
