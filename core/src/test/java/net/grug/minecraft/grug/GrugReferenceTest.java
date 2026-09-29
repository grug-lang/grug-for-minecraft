package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GrugReference}'s flag parsing, which decides which branch a recreation test takes.
 */
class GrugReferenceTest {

    @AfterEach
    void clearTheProperty() {
        System.clearProperty(GrugReference.PROPERTY);
    }

    @Test
    void anUnsetOrExplicitlyOffFlagIsFalse() {
        assertFalse(GrugReference.isTruthy(null));
        assertFalse(GrugReference.isTruthy(""));
        assertFalse(GrugReference.isTruthy("0"));
        assertFalse(GrugReference.isTruthy("false"));
        assertFalse(GrugReference.isTruthy("FALSE"));
    }

    @Test
    void anyOtherSetValueIsTrue() {
        assertTrue(GrugReference.isTruthy("1"));
        assertTrue(GrugReference.isTruthy("true"));
        assertTrue(GrugReference.isTruthy("yes"));
    }

    @Test
    void isReferenceRunReadsTheProperty() {
        System.setProperty(GrugReference.PROPERTY, "1");
        assertTrue(GrugReference.isReferenceRun());

        System.setProperty(GrugReference.PROPERTY, "0");
        assertFalse(GrugReference.isReferenceRun());

        System.setProperty(GrugReference.PROPERTY, "true");
        assertTrue(GrugReference.isReferenceRun());

        System.setProperty(GrugReference.PROPERTY, "false");
        assertFalse(GrugReference.isReferenceRun());
    }
}
