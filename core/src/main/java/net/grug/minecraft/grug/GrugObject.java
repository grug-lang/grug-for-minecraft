package net.grug.minecraft.grug;

import java.util.Objects;

public class GrugObject {
    public final GrugEntityType type;
    public final Object object;

    public GrugObject(GrugEntityType type, Object object) {
        this.type = type;
        this.object = object;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GrugObject other = (GrugObject) o;
        return type == other.type && Objects.equals(object, other.object);
    }

    @Override
    public int hashCode() {
        // A GrugObject's wrapped object may itself be a GrugObject (for example an Option holding a
        // BlockEntity), so unwrap one level. The wrapped values never form a cycle: they are built
        // from immutable records and primitives, and collections were dropped from the API.
        int objectHash;
        if (object instanceof GrugObject) {
            GrugObject nested = (GrugObject) object;
            objectHash = nested.hashCode();
        } else {
            objectHash = object == null ? 0 : object.hashCode();
        }
        return Objects.hash(type, objectHash);
    }

    @Override
    public String toString() {
        return "GrugObject{type=" + type + ", object=" + safeToString(object) + "}";
    }

    private static String safeToString(Object obj) {
        if (obj == null) return "null";
        // A nested GrugObject formats itself the same way, so defer to it.
        if (obj instanceof GrugObject) return ((GrugObject) obj).toString();
        return obj.toString();
    }
}
