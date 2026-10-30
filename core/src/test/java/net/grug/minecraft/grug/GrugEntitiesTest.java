package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers registering a block entity under its grug entity's own id at creation, which is what makes
 * {@code me} resolve in an export the host calls outside creation. See #604.
 */
class GrugEntitiesTest {

    private static final long ENTITY_ID = 7L;

    @AfterEach
    void clearState() {
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
    }

    @Test
    void registersTheBlockEntityUnderItsOwnId() {
        Object blockEntity = new Object();
        GrugEntities.registerBlockEntity(ENTITY_ID, blockEntity);

        assertSame(blockEntity, GrugEntities.entityData.get(ENTITY_ID).object);
    }

    @Test
    void rootsTheRegistrationInTheOwnerListItIsCreatedIn() {
        List<GrugObject> owner = new ArrayList<>();
        GrugEntities.fnEntities = owner;

        GrugEntities.registerBlockEntity(ENTITY_ID, new Object());

        assertEquals(1, owner.size(), "the owner list roots the registration");
    }

    @Test
    void leavesAnIdThatMemberScopeAlreadyRegisteredAlone() {
        List<GrugObject> owner = new ArrayList<>();
        GrugEntities.fnEntities = owner;
        Object blockEntity = new Object();
        GrugEntities.addEntityWithId(ENTITY_ID, GrugEntityType.BlockEntity, blockEntity);

        GrugEntities.registerBlockEntity(ENTITY_ID, blockEntity);

        assertSame(blockEntity, GrugEntities.entityData.get(ENTITY_ID).object);
        assertEquals(1, owner.size(), "the object is rooted once, not twice");
    }

    @Test
    void replacesAnEntryAnotherEntityLeftUnderTheSameId() {
        // A host-created entity's id can fall in the range a VM entity's id uses, since the first
        // type's ids are its index alone, so an entry from one of those must not shadow this block
        // entity.
        GrugEntities.addEntityWithId(ENTITY_ID, GrugEntityType.AddResponse, new Object());
        Object blockEntity = new Object();

        GrugEntities.registerBlockEntity(ENTITY_ID, blockEntity);

        assertSame(blockEntity, GrugEntities.entityData.get(ENTITY_ID).object);
    }

    @Test
    void registersNothingForAFailedCreationOrAMissingBlockEntity() {
        List<GrugObject> owner = new ArrayList<>();
        GrugEntities.fnEntities = owner;

        GrugEntities.registerBlockEntity(Grug.INVALID_GRUG_ENTITY_ID, new Object());
        GrugEntities.registerBlockEntity(ENTITY_ID, null);

        assertNull(GrugEntities.entityData.get(ENTITY_ID));
        assertEquals(0, owner.size());
    }
}
