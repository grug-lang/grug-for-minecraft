package net.grug.minecraft.ornithe.block.entity;

import net.grug.minecraft.ornithe.GrugModLoader;
import net.minecraft.block.entity.BlockEntity;

public class FooBlockEntity extends BlockEntity {

    private int tickCount = 0;

    public FooBlockEntity() {
        super();
    }

    @Override
    public void tick() {
        super.tick();

        tickCount++;

        if (tickCount >= 20) {
            GrugModLoader.LOGGER.info("FooBlockEntity is ticking at: " + x + ", " + y + ", " + z);
            tickCount = 0;
        }
    }
}
