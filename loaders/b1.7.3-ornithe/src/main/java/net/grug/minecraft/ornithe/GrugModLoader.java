package net.grug.minecraft.ornithe;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.ornithemc.osl.entrypoints.api.ModInitializer;

public class GrugModLoader implements ModInitializer {

    public static final Logger LOGGER = LogManager.getLogger("Grug");

    @Override
    public void init() {
        LOGGER.info("Successfully loaded Grug into Beta 1.7.3 (Ornithe)!");
    }
}
