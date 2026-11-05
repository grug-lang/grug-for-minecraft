package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The lowest id a host entity carries, pinned here so that a base lowered back into the range
     * the VM's counter reaches fails the test rather than moving with it. See #1125.
     */
    private static final long HOST_ENTITY_ID_FLOOR = 1L << 48;

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
        // response and stopped saving numbers. See #1120.
        GrugEntityType type = GrugEntityType.AddResponse;
        long responseId = GrugEntities.addEntity(type, new Object());
        // A host id carries the base, and the floor is pinned here rather than read back from the
        // host: a base lowered into the range the VM's counter reaches fails this instead of making
        // the comparison below vacuous. See #1125.
        assertTrue(
                responseId >= HOST_ENTITY_ID_FLOOR, "a host id lies in the range the base defines");
        // The id the next response takes, named from the type rather than from the bits of this
        // response, so a change to the shape cannot leave this checking an id the host never takes.
        long nextResponseId = GrugEntities.nextHostEntityId(type);
        // The id that response would take without the base, which is the small id the VM's own
        // counter hands out and the one #1120 collided on.
        long vmId = nextResponseId - GrugEntities.HOST_ENTITY_ID_BASE;
        Object blockEntity = new Object();
        GrugEntities.addEntityWithId(vmId, GrugEntityType.BlockEntity, blockEntity);

        long createdId = GrugEntities.addEntity(type, new Object());

        assertEquals(nextResponseId, createdId, "the host takes the id its own range gives it");
        assertSame(
                blockEntity,
                HostFunctionHelpers.resolveBlockEntity(vmId),
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
