package com.example.examplemod.examplemod.block.entity;

import com.example.examplemod.examplemod.block.GrugBlock;
import com.example.examplemod.examplemod.grug.Grug;
import com.example.examplemod.examplemod.grug.GrugObject;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

public class GrugBlockEntity extends BlockEntity {
    private long entityHandle = 0;
    private long tickFnId = Grug.INVALID_GRUG_EXPORT_FN_ID;
    private boolean initAttempted = false;

    private void initGrug() {
        if (entityHandle != 0 || initAttempted)
            return;
        initAttempted = true;

        Block block = Block.BLOCKS[world.getBlockId(x, y, z)];
        if (!(block instanceof GrugBlock))
            return;

        long fileId = ((GrugBlock) block).entityFileId;
        if (fileId == Grug.INVALID_GRUG_FILE_ID)
            return;

        Grug.currentlyInitializingBlockEntity = this;
        entityHandle = Grug.createEntity(fileId);
        Grug.currentlyInitializingBlockEntity = null;

        if (entityHandle != 0) {
            tickFnId = Grug.getExportFnId("BlockEntity", "tick");
        }
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

        if (entityHandle != 0) {
            Grug.destroyEntity(entityHandle);
            entityHandle = 0;
        }
    }
}
