package net.grug.minecraft.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.grug.minecraft.grug.Grug;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The config plugin of every Forge layer's mixin config, which hands Mixin that config's mixins
 * only on the game the layer targets.
 *
 * <p>Mixin reads the {@code MixinConfigs} of the universal jar's manifest on every Forge and
 * NeoForge that ships it, whatever the version. A mixin listed in the config itself would be read
 * there, and a Java 21 mixin class fails to parse wherever Mixin's ASM is older. So the config
 * lists its mixins under a {@code grug} key that Mixin ignores, and this plugin returns them from
 * {@link #getMixins} only when {@link GrugForgeLayers#running} is the layer that owns the config.
 */
public class GrugMixinPlugin implements IMixinConfigPlugin {
    private final GrugForgeLayers layers;
    private final GrugForgeLayers.Resources jar;
    private String mixinPackage;

    public GrugMixinPlugin() {
        this(GrugForgeLayers.load(), GrugMixinPlugin::mixinResource);
    }

    /**
     * A resource as Mixin found this plugin's own config: core may sit in another module and layer
     * than the layer's config, as in a development run, where core is a library of the boot layer
     * and cannot see the mod's resources through its own class loader.
     */
    static InputStream mixinResource(String name) {
        return MixinService.getService().getResourceAsStream(name);
    }

    GrugMixinPlugin(GrugForgeLayers layers, GrugForgeLayers.Resources jar) {
        this.layers = layers;
        this.jar = jar;
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    /**
     * The mixins of the config this plugin was loaded for, when the running game starts its layer:
     * the {@code mixins} list, and the {@code client} or {@code server} list for the running side.
     */
    @Override
    public List<String> getMixins() {
        GrugForgeLayers.Layer layer = layers.running();
        if (layer == null) {
            return Collections.emptyList();
        }
        JsonObject config = GrugForgeLayers.readJson(jar, layer.mixins);
        if (!config.get("package").getAsString().equals(mixinPackage)) {
            // This plugin belongs to another layer's config, which this game does not start.
            return Collections.emptyList();
        }
        JsonObject grug = config.getAsJsonObject("grug");
        if (grug == null) {
            throw Grug.fatal(layer.mixins + " lists its mixins under no grug key");
        }
        List<String> mixins = new ArrayList<>();
        addAll(mixins, grug.getAsJsonArray("mixins"));
        addAll(mixins, grug.getAsJsonArray(layers.client() ? "client" : "server"));
        return mixins;
    }

    private static void addAll(List<String> mixins, JsonArray names) {
        if (names == null) {
            return;
        }
        for (JsonElement name : names) {
            mixins.add(name.getAsString());
        }
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {}

    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {}
}
