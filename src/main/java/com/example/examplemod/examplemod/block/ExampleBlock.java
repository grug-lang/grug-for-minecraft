package com.example.examplemod.examplemod.block;

import com.example.examplemod.examplemod.block.entity.ExampleBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.modificationstation.stationapi.api.template.block.TemplateBlockWithEntity;
import net.modificationstation.stationapi.api.util.Identifier;

public class ExampleBlock extends TemplateBlockWithEntity {
    public ExampleBlock(Identifier identifier) {
        super(identifier, Material.SAND);
    }

    @Override
    protected BlockEntity createBlockEntity() {
        return new ExampleBlockEntity();
    }
}
