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
        this.value = widen(newValue);
        this.root = rootOf(this.value);
    }

    /**
     * A number a host function boxed in a narrower type than the double grug's own number is, so
     * that {@link #value()} answers a double on every path.
     *
     * <p>A host function that answers {@code new GrugOption((double) Float.parseFloat(text))} is
     * right; one that answers {@code new GrugOption(Float.parseFloat(text))} compiles, passes the
     * smoketest, whose Option holds a C double, and then fails on every loader, because the JNI
     * layer has no conversion for a boxed {@code Float} (#1799). Widening here makes that slip a
     * pass rather than a crash, and the smoketest cannot be the only witness. A {@code Long} stays
     * as it is, because an id is a Long and widening it would turn an entity into a number.
     */
    private static Object widen(Object newValue) {
        if (newValue instanceof Float
                || newValue instanceof Integer
                || newValue instanceof Short
                || newValue instanceof Byte) {
            return ((Number) newValue).doubleValue();
        }
        return newValue;
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
        return GrugEntities.entityData.get((Long) newValue);
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
            GrugObject obj = GrugEntities.entityData.get(id);
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
