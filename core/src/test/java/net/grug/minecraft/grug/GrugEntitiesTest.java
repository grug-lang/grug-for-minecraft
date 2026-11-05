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
        // An id this block entity was already registered under is replaced when it holds another
        // entity, which keeps a registration from being shadowed whatever left the entry there.
        GrugEntities.addEntityWithId(ENTITY_ID, GrugEntityType.AddResponse, new Object());
        Object blockEntity = new Object();

        GrugEntities.registerBlockEntity(ENTITY_ID, blockEntity);

        assertSame(blockEntity, GrugEntities.entityData.get(ENTITY_ID).object);
    }

    @Test
    void creatingAHostEntityLeavesALiveVmEntityUnderItsIdAlone() {
        // add_item creates an AddResponse on every call, and a host id used to be the type's index
        // alone for the first type, so those ids walked through the VM's own ids: the 513th
        // response took the id of a live wooden pipe's block entity, whose me then resolved to the
        // response and stopped saving numbers. See #1120. The low 32 bits are the type's index in
        // either scheme, so this names the id the next response gets.
        long responseId = GrugEntities.addEntity(GrugEntityType.AddResponse, new Object());
        long nextResponseIndex = (responseId & 0xFFFFFFFFL) + 1;
        Object blockEntity = new Object();
        GrugEntities.addEntityWithId(nextResponseIndex, GrugEntityType.BlockEntity, blockEntity);

        GrugEntities.addEntity(GrugEntityType.AddResponse, new Object());

        assertSame(
                blockEntity,
                HostFunctionHelpers.resolveBlockEntity(nextResponseIndex),
                "a host entity must not take the id of a live VM entity");
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
