package com.example.examplemod.examplemod.block;

import com.example.examplemod.examplemod.block.entity.FooBlockEntity;
import com.example.examplemod.examplemod.grug.Grug;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
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

    @Override
    public boolean onUse(World world, int x, int y, int z, PlayerEntity player) {
        if (!world.isRemote) {
            Grug.update();
            player.sendMessage("Requested grug script hot-reload!");
        }
        return true;
    }
}
