package com.example.examplemod.examplemod.block.entity;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public class ExampleBlockEntity extends BlockEntity {

    @Override
    public void tick() {
        super.tick();

        if (world != null && world.getTime() % 20 == 0) {
            ItemEntity diamondEntity = new ItemEntity(world, x + 0.5F, y + 1.5F, z + 0.5F, new ItemStack(Item.DIAMOND));

            diamondEntity.velocityX = 0;
            diamondEntity.velocityY = 0;
            diamondEntity.velocityZ = 0;

            world.spawnEntity(diamondEntity);
        }
    }
}
