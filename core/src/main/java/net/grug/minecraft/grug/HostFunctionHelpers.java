package net.grug.minecraft.grug;

public class HostFunctionHelpers {

    @GrugGenerated("utility class: never instantiated")
    private HostFunctionHelpers() {}

    public static String prettyFormat(Object value) {
        if (value instanceof Long) {
            Long id = (Long) value;
            GrugObject grugObj = GrugEntities.entityData.get(id);
            return (grugObj != null && grugObj.object != null)
                    ? prettyFormat(grugObj.object)
                    : "<id:" + id + " (invalid)>";
        }
        return String.valueOf(value);
    }

    public static Object resolveBlockEntity(long blockEntityId) {
        GrugObject obj = GrugEntities.entityData.get(blockEntityId);
        if (obj == null && GrugEntities.currentlyInitializingBlockEntity != null) {
            GrugEntities.addEntityWithId(
                    blockEntityId,
                    GrugEntityType.BlockEntity,
                    GrugEntities.currentlyInitializingBlockEntity);
            return GrugEntities.currentlyInitializingBlockEntity;
        }
        return obj != null ? obj.object : null;
    }
}
