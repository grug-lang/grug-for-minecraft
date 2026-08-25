package com.example.examplemod.examplemod.block;

import com.example.examplemod.examplemod.block.entity.GrugBlockEntity;
import com.example.examplemod.examplemod.grug.Grug;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.modificationstation.stationapi.api.template.block.TemplateBlockWithEntity;
import net.modificationstation.stationapi.api.util.Identifier;

public class GrugBlock extends TemplateBlockWithEntity {
    public final long blockFileId;
    public final long entityFileId;

    public GrugBlock(Identifier identifier, long blockFileId, long entityFileId) {
        super(identifier, Material.SAND);
        this.blockFileId = blockFileId;
        this.entityFileId = entityFileId;
    }

    @Override
    protected BlockEntity createBlockEntity() {
        // Only spawn an entity if an _entity-BlockEntity.grug file actually paired with this block
        if (entityFileId != Grug.INVALID_GRUG_FILE_ID) {
            return new GrugBlockEntity();
        }
        return null;
    }
}
