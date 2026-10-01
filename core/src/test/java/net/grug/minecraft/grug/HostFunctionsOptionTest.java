package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the Option and Vec3 host accessors when the entity's value is no longer in the weak entity
 * map. A handle can outlive its value, and dereferencing the missing entry threw a Java
 * NullPointerException instead of reporting absent (issue #67).
 */
class HostFunctionsOptionTest {

    private static final long GONE = 0x0BAD_0000_0001L;

    @Test
    void optionAccessorsAreSafeWhenTheHandleIsGone() {
        assertFalse(HostFunctions.Option_has(GONE));
        assertNull(HostFunctions.Option_unwrap(GONE));
        assertEquals(0.0, HostFunctions.Vec3_x(GONE));
        assertEquals(0.0, HostFunctions.Vec3_y(GONE));
        assertEquals(0.0, HostFunctions.Vec3_z(GONE));
    }

    @Test
    void optionHasReadsALiveOption() {
        long id = Grug.addEntity(GrugEntityType.Option, new GrugOption(null));
        try {
            assertFalse(HostFunctions.Option_has(id), "an empty option is false");
            Grug.addEntityWithId(id, GrugEntityType.Option, new GrugOption("value"));
            assertTrue(HostFunctions.Option_has(id), "a filled option is true");
        } finally {
            Grug.entityData.clear();
            Grug.fnEntities.clear();
        }
    }
}
