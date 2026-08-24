package com.example.examplemod.examplemod.block;

import com.example.examplemod.examplemod.block.entity.FooBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.modificationstation.stationapi.api.template.block.TemplateBlockWithEntity;
import net.modificationstation.stationapi.api.util.Identifier;

public class FooBlock extends TemplateBlockWithEntity {
    public FooBlock(Identifier identifier) {
        super(identifier, Material.SAND);
    }

    @Override
    protected BlockEntity createBlockEntity() {
        return new FooBlockEntity();
    }
}
