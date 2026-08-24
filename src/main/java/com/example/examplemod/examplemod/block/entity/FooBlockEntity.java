package com.example.examplemod.examplemod.block.entity;

import com.example.examplemod.examplemod.grug.Grug;
import com.example.examplemod.examplemod.grug.GrugObject;
import net.minecraft.block.entity.BlockEntity;
import java.util.ArrayList;
import java.util.List;

public class FooBlockEntity extends BlockEntity {
    private long entityHandle = 0;
    private long tickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean initAttempted = false;

    private final List<GrugObject> childEntities = new ArrayList<>();

    private void initGrug() {
        if (entityHandle != 0 || initAttempted)
            return;
        initAttempted = true;

        Long fileId = Grug.fileIds.get("foo/foo_block_entity-BlockEntity.grug");
        if (fileId == null || fileId == Grug.INVALID_GRUG_FILE_ID)
            return;

        Grug.currentlyInitializingBlockEntity = this;
        Grug.liveBlockEntities.add(this);

        List<GrugObject> oldFnEntities = Grug.fnEntities;
        Grug.fnEntities = this.childEntities;

        entityHandle = Grug.createEntity(fileId);

        Grug.fnEntities = oldFnEntities;
        Grug.currentlyInitializingBlockEntity = null;

        if (entityHandle != 0) {
            tickFnId = Grug.getExportFnId("BlockEntity", "tick");
        }
    }

    public void reloadGrug() {
        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
            childEntities.clear();
        }
        initAttempted = false;
        initGrug();
    }

    @Override
    public void tick() {
        super.tick();

        if (entityHandle == 0) {
            initGrug();
        }

        if (entityHandle != 0 && tickFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
            List<GrugObject> oldFnEntities = Grug.fnEntities;
            Grug.fnEntities = new ArrayList<>();

            Grug.callExportFn(entityHandle, tickFnId);

            Grug.fnEntities = oldFnEntities;
        }
    }

    @Override
    public void markRemoved() {
        super.markRemoved();

        Grug.liveBlockEntities.remove(this);

        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
            childEntities.clear();
        }
    }
}
