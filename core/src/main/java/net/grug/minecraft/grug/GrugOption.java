package net.grug.minecraft.grug;

public final class GrugOption {
    private final Object value;

    public GrugOption(Object value) {
        this.value = value;
    }

    public Object value() {
        return value;
    }

    public boolean has() {
        return value != null;
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
    public String toString() {
        return "GrugOption[value=" + value + "]";
    }
}
