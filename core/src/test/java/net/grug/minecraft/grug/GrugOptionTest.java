package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GrugOption}'s presence checks and equality, which unwrap entity ids, and that a
 * number reaches a grug {@code Option} as a {@code Double}, whatever type the host function boxed
 * it in.
 *
 * <p>The JNI layer has no conversion for a boxed {@code Float}, so a host function answering {@code
 * new GrugOption(Float.parseFloat(text))} failed on every loader while the smoketest, whose Option
 * holds a C double, passed (#1799). Widening in {@link GrugOption#set} makes that a pass, so these
 * tests are the witness the smoketest cannot be.
 */
class GrugOptionTest {

    private static final long ITEM = GrugEntities.hostEntityId(GrugEntityType.Item, 1);

    @AfterEach
    void clearState() {
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
    }

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
        long id = GrugEntities.addEntity(GrugEntityType.Item, "wrapped");
        assertEquals("wrapped".hashCode(), new GrugOption(id).hashCode());
    }

    @Test
    void hashCodeFallsBackToTheIdWhenTheEntityIsUnknown() {
        long unknown = 123456789L;
        assertEquals(Long.hashCode(unknown), new GrugOption(unknown).hashCode());
    }

    @Test
    void aFloatBecomesTheSameDouble() {
        GrugOption option = new GrugOption(1.5f);

        assertEquals(Double.valueOf(1.5), option.value(), "a Float is widened to the same double");
        assertTrue(option.has());
    }

    @Test
    void theNarrowerIntegralTypesBecomeTheSameDouble() {
        assertEquals(Double.valueOf(7), new GrugOption(7).value(), "an Integer is widened");
        assertEquals(Double.valueOf(7), new GrugOption((short) 7).value(), "a Short is widened");
        assertEquals(Double.valueOf(7), new GrugOption((byte) 7).value(), "a Byte is widened");
    }

    @Test
    void anOptionHoldingAFloatEqualsOneHoldingTheSameDouble() {
        // The comparison is HostFunctions.equals, so a Float left in place would answer false here
        // and a grug `==` between the two would silently disagree.
        GrugOption fromFloat = new GrugOption(1.5f);
        GrugOption fromDouble = new GrugOption(1.5);

        assertEquals(fromDouble, fromFloat);
        assertEquals(fromDouble.hashCode(), fromFloat.hashCode(), "and hash alike");
    }

    @Test
    void aDoubleAndTheOtherValuesStayAsTheyAre() {
        assertEquals(Double.valueOf(2.25), new GrugOption(2.25).value());
        assertSame("text", new GrugOption("text").value(), "a non-number is untouched");
        assertNull(new GrugOption(null).value());
        assertFalse(new GrugOption(null).has());
    }

    @Test
    void anIdStaysALongSoItsEntityStaysRooted() {
        // Widening an id would turn an entity into a number, so this is the arm the guard must not
        // take: the Long is kept, and set() still roots it through rootOf. The lifetime itself is
        // GrugEntityRootTest's subject, so this only pins that the type survives.
        GrugEntities.addEntityWithId(ITEM, GrugEntityType.Item, "item");

        GrugOption option = new GrugOption(ITEM);

        assertEquals(Long.valueOf(ITEM), option.value(), "an id stays a Long");
    }
}
