package net.grug.minecraft.grug;

public class GameFunctionHelpers {

    public static String prettyFormat(Object value) {
        if (value instanceof Long id) {
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
            Grug.addEntityWithId(blockEntityId, GrugEntityType.BlockEntity, Grug.currentlyInitializingBlockEntity);
            return Grug.currentlyInitializingBlockEntity;
        }
        return obj != null ? obj.object : null;
    }

}
