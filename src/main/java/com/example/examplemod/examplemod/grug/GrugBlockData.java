package com.example.examplemod.examplemod.grug;

import net.modificationstation.stationapi.api.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public class GrugBlockData {
    public final Identifier id;
    public String blockstatePath;
    public String blockModelPath;
    public String itemModelPath;
    public final List<String> textures = new ArrayList<>();
    public final List<String> langPaths = new ArrayList<>();
    public String blockEntityString;

    public GrugBlockData(Identifier id) {
        this.id = id;
    }
}
