package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the Option host functions' lifetime behavior. A member Option must keep the entity it
 * points at alive across a collection, and overwriting it must release the old value, so a tick()
 * that keeps setting a fresh handle into a member Option does not accumulate them. See #67.
 */
class HostFunctionsOptionTest {

    private static final long INNER = 0x0000_0001_0000_0001L;
    private static final long OTHER = 0x0000_0001_0000_0002L;
    private static final long UNUSED_OPTION = 0x0000_0003_0000_0001L;
    private static final long UNKNOWN_ID = 0x0000_0003_0000_0002L;

    @AfterEach
    void clearState() {
        Grug.entityData.clear();
        Grug.fnEntities.clear();
    }

    @Test
    void anOptionKeepsTheEntityItPointsAtAlive() throws InterruptedException {
        long optionId = HostFunctions.Option_new();
        Grug.entityData.put(INNER, new GrugObject(GrugEntityType.Item, "inner"));
        HostFunctions.Option_set(optionId, INNER);

        assertFalse(collected(INNER), "the Option roots its value across a collection");
    }

    @Test
    void overwritingAnOptionReleasesTheOldEntity() throws InterruptedException {
        long optionId = HostFunctions.Option_new();
        Grug.entityData.put(INNER, new GrugObject(GrugEntityType.Item, "old"));
        HostFunctions.Option_set(optionId, INNER);
        Grug.entityData.put(OTHER, new GrugObject(GrugEntityType.Item, "new"));
        HostFunctions.Option_set(optionId, OTHER);

        assertTrue(collected(INNER), "the overwritten value is released");
        assertFalse(collected(OTHER), "the new value is still rooted");
    }

    @Test
    void optionSetCreatesTheOptionWhenTheHandleIsNew() {
        HostFunctions.Option_set(UNUSED_OPTION, "value");
        assertEquals("value", HostFunctions.Option_unwrap(UNUSED_OPTION));
    }

    @Test
    void aPlainNumberThatIsNotAnEntityIsNotRooted() {
        long optionId = HostFunctions.Option_new();
        HostFunctions.Option_set(optionId, UNKNOWN_ID);

        assertTrue(Grug.entityData.get(UNKNOWN_ID) == null);
    }

    private static boolean collected(long id) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && Grug.entityData.get(id) != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        return Grug.entityData.get(id) == null;
    }
}
