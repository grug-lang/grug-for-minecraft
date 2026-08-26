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
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        GrugObject other = (GrugObject) o;
        return type == other.type && Objects.equals(object, other.object);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, object);
    }
}
