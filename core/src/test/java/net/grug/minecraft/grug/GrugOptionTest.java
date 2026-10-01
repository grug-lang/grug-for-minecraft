package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers {@link GrugOption}'s presence checks and equality, which unwrap entity ids. */
class GrugOptionTest {

    @Test
    void hasIsFalseForAnEmptyOption() {
        assertFalse(new GrugOption(null).has());
    }

    @Test
    void hasIsTrueForAFilledOption() {
        assertTrue(new GrugOption("value").has());
    }

    @Test
    void setReplacesTheValueAndPresence() {
        GrugOption option = new GrugOption(null);
        assertFalse(option.has());

        option.set("value");

        assertTrue(option.has());
        assertEquals("value", option.value());
    }

    @Test
    void setToNullEmptiesTheOption() {
        GrugOption option = new GrugOption("value");

        option.set(null);

        assertFalse(option.has());
        assertNull(option.value());
    }

    @Test
    void equalsIsReflexive() {
        GrugOption option = new GrugOption("value");
        assertTrue(option.equals(option));
    }

    @Test
    void equalsRejectsAnUnrelatedType() {
        assertFalse(new GrugOption("value").equals("value"));
    }

    @Test
    void equalsComparesTheWrappedValues() {
        assertEquals(new GrugOption("value"), new GrugOption("value"));
        assertNotEquals(new GrugOption("value"), new GrugOption("other"));
    }

    @Test
    void hashCodeUsesAPlainValueDirectly() {
        assertEquals("value".hashCode(), new GrugOption("value").hashCode());
    }

    @Test
    void hashCodeResolvesAnEntityIdToItsWrappedObject() {
        long id = Grug.addEntity(GrugEntityType.Item, "wrapped");
        assertEquals("wrapped".hashCode(), new GrugOption(id).hashCode());
    }

    @Test
    void hashCodeFallsBackToTheIdWhenTheEntityIsUnknown() {
        long unknown = 123456789L;
        assertEquals(Long.hashCode(unknown), new GrugOption(unknown).hashCode());
    }
}
