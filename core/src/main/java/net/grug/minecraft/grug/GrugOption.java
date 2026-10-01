package net.grug.minecraft.grug;

public final class GrugOption {
    private Object value;

    /**
     * The object an entity id value points at, held strongly so a member Option keeps its value
     * alive across calls. It is replaced on every {@link #set}, so overwriting the Option releases
     * the old value for the JVM to collect, which is what the hand-written legacy branch's
     * HashMap/HashSet did by storing GrugObjects.
     */
    private GrugObject root;

    public GrugOption(Object value) {
        set(value);
    }

    public Object value() {
        return value;
    }

    public boolean has() {
        return value != null;
    }

    /** Replaces the value, releasing the old root and rooting an entity id's object. */
    public void set(Object newValue) {
        this.value = newValue;
        this.root = rootOf(newValue);
    }

    /**
     * The object an id value points at, or null when the value is not an id or the id is unknown.
     * Options are not slots, so this strong reference is the only thing that keeps an entity alive
     * after the call that created it returns.
     */
    private static GrugObject rootOf(Object newValue) {
        if (!(newValue instanceof Long)) {
            return null;
        }
        return Grug.entityData.get((Long) newValue);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GrugOption)) return false;
        GrugOption other = (GrugOption) o;

        // Delegate to our smart equality function to resolve the inner IDs!
        return HostFunctions.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        // If the value is an ID, resolve it to get a consistent hash code.
        // This ensures Options work safely as keys in HashMaps later!
        if (value instanceof Long) {
            Long id = (Long) value;
            GrugObject obj = Grug.entityData.get(id);
            if (obj != null) {
                return java.util.Objects.hashCode(obj.object);
            }
        }
        return java.util.Objects.hashCode(value);
    }

    @Override
    @GrugGenerated(
            "record-equivalent member: the compiler generated this for a record and JaCoCo filtered"
                    + " it")
    public String toString() {
        return "GrugOption[value=" + value + "]";
    }
}
