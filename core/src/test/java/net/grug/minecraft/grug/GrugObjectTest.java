package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the value-equality, hashing and formatting helpers on {@link GrugObject}. */
class GrugObjectTest {

    @Test
    void equalsIsReflexive() {
        GrugObject object = new GrugObject(GrugEntityType.Block, "block");
        assertTrue(object.equals(object));
    }

    @Test
    void equalsRejectsNull() {
        assertFalse(new GrugObject(GrugEntityType.Block, "block").equals(null));
    }

    @Test
    void equalsRejectsAnUnrelatedType() {
        assertFalse(new GrugObject(GrugEntityType.Block, "block").equals("block"));
    }

    @Test
    void equalsComparesTypeAndWrappedObject() {
        GrugObject first = new GrugObject(GrugEntityType.Block, "block");
        GrugObject same = new GrugObject(GrugEntityType.Block, "block");
        GrugObject differentType = new GrugObject(GrugEntityType.Item, "block");
        GrugObject differentObject = new GrugObject(GrugEntityType.Block, "other");

        assertEquals(first, same);
        assertNotEquals(first, differentType);
        assertNotEquals(first, differentObject);
    }

    @Test
    void equalsHandlesNullWrappedObjects() {
        GrugObject a = new GrugObject(GrugEntityType.Option, null);
        GrugObject b = new GrugObject(GrugEntityType.Option, null);
        GrugObject c = new GrugObject(GrugEntityType.Option, "value");

        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void hashCodeHandlesNullWrappedObjects() {
        assertEquals(
                java.util.Objects.hash(GrugEntityType.Option, 0),
                new GrugObject(GrugEntityType.Option, null).hashCode());
    }

    @Test
    void hashCodeUsesTheWrappedObjectsHash() {
        GrugObject object = new GrugObject(GrugEntityType.Block, "block");
        assertEquals(
                java.util.Objects.hash(GrugEntityType.Block, "block".hashCode()),
                object.hashCode());
    }

    @Test
    void hashCodeRecursesIntoNestedGrugObjects() {
        GrugObject inner = new GrugObject(GrugEntityType.Block, "block");
        GrugObject outer = new GrugObject(GrugEntityType.Option, inner);
        assertEquals(
                java.util.Objects.hash(GrugEntityType.Option, inner.hashCode()), outer.hashCode());
    }

    @Test
    void toStringHandlesNullWrappedObjects() {
        assertEquals(
                "GrugObject{type=Option, object=null}",
                new GrugObject(GrugEntityType.Option, null).toString());
    }

    @Test
    void toStringUsesTheWrappedObjectsString() {
        assertEquals(
                "GrugObject{type=Block, object=block}",
                new GrugObject(GrugEntityType.Block, "block").toString());
    }

    @Test
    void toStringRecursesIntoNestedGrugObjects() {
        GrugObject inner = new GrugObject(GrugEntityType.Block, "block");
        GrugObject outer = new GrugObject(GrugEntityType.Option, inner);
        assertEquals(
                "GrugObject{type=Option, object=GrugObject{type=Block, object=block}}",
                outer.toString());
    }
}
