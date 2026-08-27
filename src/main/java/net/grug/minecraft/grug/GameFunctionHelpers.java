package net.grug.minecraft.grug;

import net.minecraft.block.entity.BlockEntity;

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

    public static BlockEntity resolveBlockEntity(long blockEntityId) {
        GrugObject obj = Grug.entityData.get(blockEntityId);
        if (obj == null) {
            BlockEntity be = Grug.currentlyInitializingBlockEntity;
            if (be != null) {
                Grug.addEntityWithId(blockEntityId, GrugEntityType.BlockEntity, be);
                return be;
            }
        }
        return (BlockEntity) (obj != null ? obj.object : null);
    }

}
