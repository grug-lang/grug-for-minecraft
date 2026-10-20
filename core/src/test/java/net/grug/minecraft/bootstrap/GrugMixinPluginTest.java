package net.grug.minecraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

class GrugMixinPluginTest {
    private static final List<GrugForgeLayers.Layer> LAYERS =
            GrugForgeLayers.read(GrugForgeLayers.JAR, "bootstrap/layers.json");

    private static GrugMixinPlugin plugin(String minecraft, boolean client, String mixinPackage) {
        GrugForgeLayers.Game game =
                minecraft == null ? null : new GrugForgeLayers.Game(minecraft, client);
        GrugMixinPlugin plugin =
                new GrugMixinPlugin(new GrugForgeLayers(LAYERS, game), GrugForgeLayers.JAR);
        plugin.onLoad(mixinPackage);
        return plugin;
    }

    @Test
    void handsOutTheCommonAndClientMixinsOnTheLayersClient() {
        assertEquals(
                Arrays.asList("CommonMixin", "ClientMixin"),
                plugin("1.20.6", true, "net.grug.fake.mixin").getMixins());
    }

    @Test
    void handsOutTheCommonAndServerMixinsOnTheLayersServer() {
        assertEquals(
                Arrays.asList("CommonMixin", "ServerMixin"),
                plugin("1.20.6", false, "net.grug.fake.mixin").getMixins());
    }

    @Test
    void handsOutNothingOnAGameWithoutTheLayer() {
        assertEquals(
                Collections.emptyList(), plugin("1.20.4", true, "net.grug.fake.mixin").getMixins());
        assertEquals(
                Collections.emptyList(), plugin(null, true, "net.grug.fake.mixin").getMixins());
    }

    @Test
    void handsOutNothingForAnotherLayersConfig() {
        assertEquals(
                Collections.emptyList(),
                plugin("1.20.6", true, "net.grug.other.mixin").getMixins());
    }

    @Test
    void aConfigWithoutAGrugKeyIsAnInvariant() {
        GrugMixinPlugin plugin = plugin("1.21.1", true, "net.grug.other.mixin");
        IllegalStateException error = assertThrows(IllegalStateException.class, plugin::getMixins);
        assertTrue(
                error.getMessage()
                        .contains(
                                "bootstrap/other.mixins.json lists its mixins under no grug key"));
    }

    @Test
    void aConfigWithoutASidesListHandsOutTheRest() {
        GrugForgeLayers.Layer layer =
                new GrugForgeLayers.Layer(
                        "fake-forge", "1.20.6", "Fake", "bootstrap/fake.mixins.json");
        GrugForgeLayers.Resources commonOnly =
                name ->
                        new java.io.ByteArrayInputStream(
                                "{\"package\": \"p\", \"grug\": {\"client\": [\"C\"]}}".getBytes());
        GrugMixinPlugin plugin =
                new GrugMixinPlugin(
                        new GrugForgeLayers(
                                Collections.singletonList(layer),
                                new GrugForgeLayers.Game("1.20.6", false)),
                        commonOnly);
        plugin.onLoad("p");
        assertEquals(Collections.emptyList(), plugin.getMixins());
    }

    @Test
    void appliesEveryMixinItHandsOutAndChangesNothing() {
        GrugMixinPlugin plugin = plugin("1.20.6", true, "net.grug.fake.mixin");
        assertNull(plugin.getRefMapperConfig());
        assertTrue(plugin.shouldApplyMixin("Target", "Mixin"));
        plugin.acceptTargets(new HashSet<>(), new HashSet<>());
        plugin.preApply("Target", null, "Mixin", null);
        plugin.postApply("Target", null, "Mixin", null);
    }

    @Test
    void readsConfigsThroughMixinsOwnResourceLookup() {
        // The stand-in service finds nothing, while the class path holds the test configs, which
        // shows the lookup goes through Mixin rather than through a class loader.
        assertNull(GrugMixinPlugin.mixinResource("bootstrap/fake.mixins.json"));
    }

    @Test
    void theShippedPluginReadsTheShippedTable() {
        GrugMixinPlugin plugin = new GrugMixinPlugin();
        plugin.onLoad("net.grug.minecraft.forge.mixin");
        // A unit test runs on no Forge, so no layer's mixins are handed out.
        assertEquals(Collections.emptyList(), plugin.getMixins());
    }
}
