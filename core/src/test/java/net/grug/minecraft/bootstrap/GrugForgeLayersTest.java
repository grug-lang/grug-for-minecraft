package net.grug.minecraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class GrugForgeLayersTest {
    private static final List<GrugForgeLayers.Layer> LAYERS =
            GrugForgeLayers.read(GrugForgeLayers.JAR, "/bootstrap/layers.json");

    @AfterEach
    void restoreFakeGame() {
        FakeFmlLoader.minecraft = "1.20.6";
        FakeFmlLoader.dist = FakeFmlLoader.Dist.CLIENT;
        FakeLayerEntry.constructed = 0;
    }

    private static GrugForgeLayers on(String minecraft) {
        return new GrugForgeLayers(LAYERS, new GrugForgeLayers.Game(minecraft, true));
    }

    @Test
    void readsEveryLayerOfTheTable() {
        assertEquals(2, LAYERS.size());
        GrugForgeLayers.Layer layer = LAYERS.get(0);
        assertEquals("fake-forge", layer.loader);
        assertEquals("1.20.6", layer.minecraft);
        assertEquals("net.grug.minecraft.bootstrap.FakeLayerEntry", layer.entrypoint);
        assertEquals("bootstrap/fake.mixins.json", layer.mixins);
    }

    @Test
    void theShippedTableNamesTheForgeLayer() {
        GrugForgeLayers layers = GrugForgeLayers.load();
        // A unit test runs on no Forge, so the game starts no layer.
        assertNull(layers.running());
        List<GrugForgeLayers.Layer> shipped =
                GrugForgeLayers.read(GrugForgeLayers.JAR, GrugForgeLayers.TABLE);
        assertEquals("1.20.6-forge", shipped.get(0).loader);
    }

    @Test
    void aMissingTableIsAnInvariant() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugForgeLayers.read(GrugForgeLayers.JAR, "/bootstrap/absent.json"));
        assertTrue(error.getMessage().contains("/bootstrap/absent.json is missing"));
    }

    @Test
    void aTableThatCannotBeReadIsAnInvariant() {
        GrugForgeLayers.Resources failing =
                name ->
                        new ByteArrayInputStream("{}".getBytes()) {
                            @Override
                            public void close() throws IOException {
                                throw new IOException("disk gone");
                            }
                        };
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugForgeLayers.read(failing, "/t.json"));
        assertTrue(error.getMessage().contains("Failed to read /t.json"));
    }

    @Test
    void readsTheGameForgeReports() {
        FakeFmlLoader.minecraft = "1.21.1";
        FakeFmlLoader.dist = FakeFmlLoader.Dist.DEDICATED_SERVER;
        GrugForgeLayers.Game game = GrugForgeLayers.game(FakeFmlLoader.class.getName());
        assertEquals("1.21.1", game.minecraft);
        assertFalse(game.client);
        FakeFmlLoader.dist = FakeFmlLoader.Dist.CLIENT;
        assertTrue(GrugForgeLayers.game(FakeFmlLoader.class.getName()).client);
    }

    @Test
    void aGameWithoutFmlsVersionInfoIsNone() {
        assertNull(GrugForgeLayers.game("net.minecraftforge.fml.loading.Absent"));
        assertNull(GrugForgeLayers.game(OldFmlLoader.class.getName()));
    }

    @Test
    void aFailingVersionInfoIsAnInvariant() {
        assertThrows(
                IllegalStateException.class,
                () -> GrugForgeLayers.game(ThrowingFmlLoader.class.getName()));
        assertThrows(
                IllegalStateException.class,
                () -> GrugForgeLayers.game("net.grug.minecraft.bootstrap.hidden.HiddenFmlLoader"));
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugForgeLayers.game(SidelessFmlLoader.class.getName()));
        assertTrue(error.getMessage().contains("no Minecraft version or side"));
    }

    @Test
    void theRunningLayerIsTheOneForTheGamesVersion() {
        assertSame(LAYERS.get(0), on("1.20.6").running());
        assertSame(LAYERS.get(1), on("1.21.1").running());
        assertNull(on("1.20.4").running());
        assertNull(new GrugForgeLayers(LAYERS, null).running());
    }

    @Test
    void twoLayersForOneVersionAreAnInvariant() {
        GrugForgeLayers.Layer twin =
                new GrugForgeLayers.Layer("twin", "1.20.6", "Twin", "twin.json");
        GrugForgeLayers layers =
                new GrugForgeLayers(
                        Arrays.asList(LAYERS.get(0), twin),
                        new GrugForgeLayers.Game("1.20.6", true));
        IllegalStateException error = assertThrows(IllegalStateException.class, layers::running);
        assertTrue(error.getMessage().contains("fake-forge and twin both target Minecraft 1.20.6"));
    }

    @Test
    void startsOnlyTheRunningLayer() {
        ClassLoader loader = getClass().getClassLoader();
        on("1.20.4").start(loader);
        assertEquals(0, FakeLayerEntry.constructed);
        on("1.20.6").start(loader);
        assertEquals(1, FakeLayerEntry.constructed);
    }

    @Test
    void aLayersOwnFailureReachesForgeUnwrapped() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> on("1.21.1").start(getClass().getClassLoader()));
        assertEquals("the layer failed", error.getMessage());
    }

    @Test
    void aLayerFailingWithAnErrorIsAnInvariant() {
        GrugForgeLayers.Layer layer =
                new GrugForgeLayers.Layer(
                        "error-forge", "1.20.6", ErrorLayerEntry.class.getName(), "e.json");
        GrugForgeLayers layers =
                new GrugForgeLayers(
                        Collections.singletonList(layer), new GrugForgeLayers.Game("1.20.6", true));
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> layers.start(getClass().getClassLoader()));
        assertTrue(error.getMessage().contains("Starting error-forge failed"));
        assertTrue(error.getCause() instanceof LinkageError);
    }

    @Test
    void aMissingEntrypointIsAnInvariant() {
        GrugForgeLayers.Layer layer =
                new GrugForgeLayers.Layer("gone-forge", "1.20.6", "net.grug.Gone", "g.json");
        GrugForgeLayers layers =
                new GrugForgeLayers(
                        Collections.singletonList(layer), new GrugForgeLayers.Game("1.20.6", true));
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> layers.start(getClass().getClassLoader()));
        assertTrue(error.getMessage().contains("Cannot construct net.grug.Gone of gone-forge"));
    }

    @Test
    void theModDoesNothingOnAGameWithoutALayer() {
        // A unit test runs on no Forge, which is a game the jar does not support.
        new GrugForgeMod();
        assertEquals(0, FakeLayerEntry.constructed);
    }

    @Test
    void anOpenedResourceIsTheJarsOwn() throws IOException {
        try (InputStream in = GrugForgeLayers.JAR.open(GrugForgeLayers.TABLE)) {
            assertTrue(in.read() >= 0);
        }
    }
}
