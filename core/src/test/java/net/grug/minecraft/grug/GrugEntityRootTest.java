package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the strong-root policy the loaders use for a block entity's member scope: entities created
 * while {@link Grug#fnEntities} is a retained owner list stay alive, and clearing that list
 * releases them. Each loader's {@code GrugBlockEntity.childEntities} relies on this, and the
 * loaders are excluded from the coverage gate, so the policy is measured here instead. See #67.
 */
class GrugEntityRootTest {

    private static final long ENTITY = 0x0000_0002_0000_0001L;

    @AfterEach
    void clearState() {
        Grug.entityData.clear();
        Grug.globalFnEntities.clear();
        Grug.fnEntities = Grug.globalFnEntities;
    }

    @Test
    void aRetainedOwnerListKeepsItsEntitiesAlive() throws InterruptedException {
        List<GrugObject> owner = new ArrayList<>();
        List<GrugObject> saved = Grug.fnEntities;

        Grug.fnEntities = owner;
        Grug.addEntityWithId(ENTITY, GrugEntityType.Item, "member");
        Grug.fnEntities = saved;

        assertFalse(collected(ENTITY), "the owner list roots the entity");
    }

    @Test
    void clearingTheOwnerListReleasesItsEntities() throws InterruptedException {
        List<GrugObject> owner = new ArrayList<>();
        List<GrugObject> saved = Grug.fnEntities;

        Grug.fnEntities = owner;
        Grug.addEntityWithId(ENTITY, GrugEntityType.Item, "member");
        Grug.fnEntities = saved;

        owner.clear();

        assertTrue(collected(ENTITY), "clearing the owner list frees the entity");
    }

    private static boolean collected(long id) throws InterruptedException {
        for (int attempt = 0; attempt < 50 && Grug.entityData.get(id) != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        return Grug.entityData.get(id) == null;
    }
}
