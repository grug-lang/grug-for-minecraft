package net.grug.minecraft.ornithe.block;

import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.block.BlockWithBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.grug.minecraft.ornithe.block.entity.FooBlockEntity;

public class FooBlock extends BlockWithBlockEntity {

    public FooBlock(int id, Material material) {
        super(id, material);
        GrugModLoader.LOGGER.info("This line is printed by FooBlock its constructor");
    }

    @Override
    protected BlockEntity createBlockEntity() {
        return new FooBlockEntity();
    }
}
