package com.example.examplemod.examplemod.block;

import com.example.examplemod.examplemod.block.entity.GrugBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.modificationstation.stationapi.api.template.block.TemplateBlockWithEntity;
import net.modificationstation.stationapi.api.util.Identifier;

public class GrugBlock extends TemplateBlockWithEntity {
    public final Identifier identifier;
    public long blockFileId;
    public long entityFileId;

    public GrugBlock(Identifier identifier, long blockFileId, long entityFileId) {
        super(identifier, Material.SAND);
        this.identifier = identifier;
        this.blockFileId = blockFileId;
        this.entityFileId = entityFileId;
    }

    @Override
    protected BlockEntity createBlockEntity() {
        // Minecraft requires BlockContainers to ALWAYS return a non-null BlockEntity.
        // GrugBlockEntity already gracefully ignores blocks with INVALID_GRUG_FILE_ID.
        return new GrugBlockEntity();
    }
}
