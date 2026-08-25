package com.example.examplemod.examplemod.grug;

import net.modificationstation.stationapi.api.util.Identifier;
import java.util.ArrayList;
import java.util.List;

public class GrugBlockData {
    public Identifier id;
    public String texturePath;
    public String blockstatePath;
    public String blockEntityString;
    public String blockModelPath;
    public String itemModelPath;
    public List<String> langPaths = new ArrayList<>();

    public GrugBlockData(Identifier id) {
        this.id = id;
    }
}
