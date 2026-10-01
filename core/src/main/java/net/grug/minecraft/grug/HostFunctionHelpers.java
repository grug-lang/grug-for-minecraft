package net.grug.minecraft.grug;

public class HostFunctionHelpers {

    @GrugGenerated("utility class: never instantiated")
    private HostFunctionHelpers() {}

    /**
     * The object stored under an entity id, or null when the id has no live value. The entity map
     * is weak, so a handle can outlive its value, and an accessor branches on the null instead of
     * dereferencing it.
     */
    public static Object entityObjectOrNull(long entityId) {
        GrugObject obj = Grug.entityData.get(entityId);
        return obj != null ? obj.object : null;
    }

    public static String prettyFormat(Object value) {
        if (value instanceof Long) {
            Long id = (Long) value;
            GrugObject grugObj = Grug.entityData.get(id);
            return (grugObj != null && grugObj.object != null)
                    ? prettyFormat(grugObj.object)
                    : "<id:" + id + " (invalid)>";
        }
        return String.valueOf(value);
    }

    public static Object resolveBlockEntity(long blockEntityId) {
        GrugObject obj = Grug.entityData.get(blockEntityId);
        if (obj == null && Grug.currentlyInitializingBlockEntity != null) {
            Grug.addEntityWithId(
                    blockEntityId,
                    GrugEntityType.BlockEntity,
                    Grug.currentlyInitializingBlockEntity);
            return Grug.currentlyInitializingBlockEntity;
        }
        return obj != null ? obj.object : null;
    }
}
