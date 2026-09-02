package net.grug.minecraft.grug;

public record GrugOption(Object value) {
    public boolean has() {
        return value != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof GrugOption other))
            return false;

        // Delegate to our smart equality function to resolve the inner IDs!
        return GameFunctions.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        // If the value is an ID, resolve it to get a consistent hash code.
        // This ensures Options work safely as keys in HashMaps later!
        if (value instanceof Long id) {
            GrugObject obj = Grug.entityData.get(id);
            if (obj != null) {
                return java.util.Objects.hashCode(obj.object);
            }
        }
        return java.util.Objects.hashCode(value);
    }
}
