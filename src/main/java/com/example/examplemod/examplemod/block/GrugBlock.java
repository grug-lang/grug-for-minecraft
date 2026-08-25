package com.example.examplemod.examplemod.block;

import com.example.examplemod.examplemod.block.entity.GrugBlockEntity;
import com.example.examplemod.examplemod.grug.Grug;
import com.example.examplemod.examplemod.grug.GrugBlockData;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.modificationstation.stationapi.api.template.block.TemplateBlockWithEntity;
import net.modificationstation.stationapi.api.util.Identifier;

public class GrugBlock extends TemplateBlockWithEntity {
    public final Identifier identifier;
    public long blockFileId;

    // We no longer require entityFileId in the constructor
    public GrugBlock(Identifier identifier, long blockFileId) {
        super(identifier, Material.SAND);
        this.identifier = identifier;
        this.blockFileId = blockFileId;
    }

    public long getEntityFileId() {
        GrugBlockData data = Grug.blockDataByFileId.get(this.blockFileId);
        if (data != null && data.blockEntityString != null) {
            // "examplemod:bar_block_entity" -> "bar_block_entity"
            String[] parts = data.blockEntityString.split(":");
            String cleanName = parts.length == 2 ? parts[1] : data.blockEntityString;

            return Grug.entityFileIdsByName.getOrDefault(cleanName, Grug.INVALID_GRUG_FILE_ID);
        }
        return Grug.INVALID_GRUG_FILE_ID;
    }

    @Override
    protected BlockEntity createBlockEntity() {
        return new GrugBlockEntity();
    }
}
