package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers the configuration and scan a test or a world pass asks the host for, and the binding pass
 * a declared key opens: the feet of the player the scan is centred on, the mod's own config, and
 * the refusals of a bad radius, no level, no player and an overlay that belongs to no mod.
 */
class HostFunctionsConfigAndScanTest {

    @TempDir Path tmp;

    private Object savedAdapter;
    private Object level;
    private Object player;
    private Object savedTestMod;
    private double playerX = 5.7;
    private int spawnX = 5;

    private static final String DECLARATION =
            "{\"keys\": [{\"id\": \"toggle\", \"name\": \"Toggle\", \"default_key\": \"L\"}],"
                    + " \"config\": ["
                    + "{\"key\": \"flag\", \"type\": \"boolean\", \"title\": \"Flag\","
                    + " \"description\": \"Flag.\", \"default\": true},"
                    + "{\"key\": \"count\", \"type\": \"number\", \"title\": \"Count\","
                    + " \"description\": \"Count.\", \"default\": 3},"
                    + "{\"key\": \"tint\", \"type\": \"color\", \"title\": \"Tint\","
                    + " \"description\": \"Tint.\", \"default\": \"#00FF00\"}]}";

    @BeforeEach
    void setUp() throws Exception {
        level = new Object();
        player = new Object();
        File gameDir = tmp.resolve("game").toFile();
        File modsDir = new File(gameDir, "mods");
        File coverage = new File(modsDir, "coverage");
        assertTrue(coverage.mkdirs());
        Files.write(
                new File(coverage, "about.json").toPath(),
                DECLARATION.getBytes(StandardCharsets.UTF_8));
        installAdapter(gameDir, modsDir);
        clear(GrugConfig.class, "cache");
        clear(GrugConfig.class, "reported");
        clear(GrugMetadata.class, "cache");
        savedTestMod = GrugTestState.currentTestMod;
        drain();
    }

    @AfterEach
    void tearDown() throws Exception {
        GrugTestState.currentTestMod = (String) savedTestMod;
        GrugTestContexts.close();
        if (GrugWorldOverlay.isWorld()) {
            GrugWorldOverlay.closeWorld();
        } else if (GrugWorldOverlay.isBinding()) {
            GrugWorldOverlay.closeBinding();
        }
        GrugWorldOverlay.clearDrawn();
        GrugEntities.entityData.clear();
        drain();
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
    }

    private static void clear(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        if (value instanceof java.util.Map) {
            ((java.util.Map<?, ?>) value).clear();
        } else {
            ((java.util.Collection<?>) value).clear();
        }
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    @Test
    void aTestContextReadsItsModConfigAndScansAroundItsPlayer() {
        GrugTestState.currentTestMod = "coverage";
        long context = GrugTestContexts.tick();

        long config = HostFunctions.TestTickContext_get_config(context);
        assertTrue(config != 0, "The test's own mod has a config.");
        assertTrue(HostFunctions.Config_bool(config, "flag"));
        assertEquals(3.0, HostFunctions.Config_number(config, "count"));
        long color = HostFunctions.Config_color(config, "tint");
        Color tint = (Color) GrugEntities.entityData.get(color).object;
        assertNotNull(tint);
        assertEquals(0, tint.r());
        assertEquals(255, tint.g());
        assertEquals(0, tint.b());

        long squares = HostFunctions.TestTickContext_get_spawn_squares(context, 0);
        assertTrue(squares != 0, "The toy level has the one spawn space around the feet.");
        assertEquals(1.0, HostFunctions.SpawnSquares_count(squares));
        assertEquals(5.0, HostFunctions.SpawnSquares_x(squares, 0));
        assertEquals(4.0, HostFunctions.SpawnSquares_y(squares, 0));
        assertEquals(6.0, HostFunctions.SpawnSquares_z(squares, 0));
        assertEquals(2.0, HostFunctions.SpawnSquares_block_light(squares, 0));
        assertEquals(12.0, HostFunctions.SpawnSquares_sky_light(squares, 0));
        assertTrue(drain().isEmpty(), "A legal read reports nothing.");
    }

    @Test
    void aTestContextWithoutAModOrALevelOrAPlayerOrASaneRadiusIsRefused() {
        GrugTestState.currentTestMod = null;
        long context = GrugTestContexts.tick();

        assertEquals(0, HostFunctions.TestTickContext_get_config(context));
        assertEquals(0, HostFunctions.TestTickContext_get_spawn_squares(context, -1));
        assertEquals(0, HostFunctions.TestTickContext_get_spawn_squares(context, 0.5));
        assertEquals(0, HostFunctions.TestTickContext_get_spawn_squares(context, 33));

        GrugTestState.currentTestMod = "coverage";
        level = null;
        assertEquals(0, HostFunctions.TestTickContext_get_spawn_squares(context, 1));

        level = new Object();
        player = null;
        assertEquals(0, HostFunctions.TestTickContext_get_spawn_squares(context, 1));

        player = new Object();
        assertTrue(
                HostFunctions.TestTickContext_get_spawn_squares(context, 1) != 0,
                "With both back, the scan answers again.");

        List<String> reports = drain();
        assertTrue(reports.get(0).contains("not one of a mod"), reports.get(0));
        assertTrue(reports.get(1).contains("whole number"), reports.get(1));
        assertTrue(reports.get(2).contains("whole number"), reports.get(2));
        assertTrue(reports.get(3).contains("whole number"), reports.get(3));
        assertTrue(reports.get(4).contains("no level"), reports.get(4));
        assertTrue(reports.get(5).contains("no player"), reports.get(5));
        assertEquals(6, reports.size(), reports.toString());
    }

    @Test
    void aWorldPassScansAroundTheFeetAndAnswersTheModsOwnConfig() {
        GrugWorldOverlay.openWorld(0.5, 100, 70, 50, 5.7, 4.2, 6.9, "coverage");
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldContext, GrugWorldOverlay.context());

        long config = HostFunctions.OverlayWorldContext_get_config(context);
        assertTrue(config != 0);
        assertTrue(HostFunctions.Config_bool(config, "flag"));

        long squares = HostFunctions.OverlayWorldContext_get_spawn_squares(context, 0);
        assertEquals(1.0, HostFunctions.SpawnSquares_count(squares));
        assertEquals(5.0, HostFunctions.SpawnSquares_x(squares, 0));
        assertEquals(4.0, HostFunctions.SpawnSquares_y(squares, 0));
        assertEquals(6.0, HostFunctions.SpawnSquares_z(squares, 0));

        assertTrue(drain().isEmpty());
        GrugWorldOverlay.closeWorld();
    }

    @Test
    void aNegativeFeetCentreIsFlooredOntoTheBlockTheFeetAreIn() {
        // A feet x of -5.7 is in block -6. Truncating toward zero would scan from -5 and miss the
        // one spawn space, which is what a test save at a negative x exposed (#1255).
        playerX = -5.7;
        spawnX = -6;

        GrugTestState.currentTestMod = "coverage";
        long context = GrugTestContexts.tick();
        long squares = HostFunctions.TestTickContext_get_spawn_squares(context, 0);
        assertEquals(
                1.0,
                HostFunctions.SpawnSquares_count(squares),
                "The test context's scan is centred on -6, the block the feet are in.");
        assertEquals(-6.0, HostFunctions.SpawnSquares_x(squares, 0));

        GrugWorldOverlay.openWorld(0.5, 100, 70, 50, -5.7, 4.2, 6.9, "coverage");
        long worldContext =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldContext, GrugWorldOverlay.context());
        long worldSquares = HostFunctions.OverlayWorldContext_get_spawn_squares(worldContext, 0);
        assertEquals(
                1.0,
                HostFunctions.SpawnSquares_count(worldSquares),
                "The world pass floors its player's x the same way.");
        assertEquals(-6.0, HostFunctions.SpawnSquares_x(worldSquares, 0));

        assertTrue(drain().isEmpty());
        GrugWorldOverlay.closeWorld();
    }

    @Test
    void aWorldPassOfNoModHasNoConfig() {
        GrugWorldOverlay.openWorld(0, 0, 0, 0, 0, 0, 0, null);
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldContext, GrugWorldOverlay.context());

        assertEquals(0, HostFunctions.OverlayWorldContext_get_config(context));

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("no config"), reports.get(0));
        GrugWorldOverlay.closeWorld();
    }

    @Test
    void aBindingPassAnswersItsIdAndItsModsConfig() {
        GrugWorldOverlay.openBinding("coverage", "toggle");
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldBindingContext, GrugWorldOverlay.context());

        assertEquals("toggle", HostFunctions.OverlayWorldBindingContext_id(context));
        long config = HostFunctions.OverlayWorldBindingContext_get_config(context);
        assertTrue(config != 0);
        assertEquals(3.0, HostFunctions.Config_number(config, "count"));
        assertTrue(drain().isEmpty());
        GrugWorldOverlay.closeBinding();

        GrugWorldOverlay.openBinding("coverage", null);
        long nameless =
                GrugEntities.addEntity(
                        GrugEntityType.OverlayWorldBindingContext, GrugWorldOverlay.context());
        assertEquals("", HostFunctions.OverlayWorldBindingContext_id(nameless));
        GrugWorldOverlay.closeBinding();
    }

    @Test
    void aBindingIsDeliveredOnceAndItsDefaultsPressTheDeclaredKey() {
        GrugWorldOverlay.binding(
                0, Grug.INVALID_GRUG_EXPORT_FN_ID, "coverage", "toggle", true, true);
        GrugWorldOverlay.binding(
                0, Grug.INVALID_GRUG_EXPORT_FN_ID, "coverage", "toggle", true, true);
        GrugWorldOverlay.binding(
                0, Grug.INVALID_GRUG_EXPORT_FN_ID, "coverage", "toggle", false, true);
        GrugWorldOverlay.binding(
                0, Grug.INVALID_GRUG_EXPORT_FN_ID, "coverage", "toggle", true, false);

        GrugWorldOverlay.bindingDefaultsFor(0, Grug.INVALID_GRUG_EXPORT_FN_ID, "coverage", 'L');
        GrugWorldOverlay.bindingDefaultsFor(0, Grug.INVALID_GRUG_EXPORT_FN_ID, "coverage", 'Q');

        assertFalse(GrugWorldOverlay.isOpen(), "Every pass closed behind itself.");
        assertTrue(drain().isEmpty());
    }

    @Test
    void aConfigOfAModThatDeclaresNothingAnswersEmptyValues() {
        GrugTestState.currentTestMod = "absent";
        long context = GrugTestContexts.tick();
        long config = HostFunctions.TestTickContext_get_config(context);

        assertFalse(HostFunctions.Config_bool(config, "anything"));
        assertEquals(0.0, HostFunctions.Config_number(config, "anything"));
        assertEquals(0xFFFF00FF, colorOf(HostFunctions.Config_color(config, "anything")));
        assertFalse(drain().isEmpty(), "An undeclared option reports.");
    }

    private static int colorOf(long id) {
        return ((Color) GrugEntities.entityData.get(id).object).getARGB();
    }

    private void installAdapter(File gameDir, File modsDir) throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getGameDirectory":
                                    return gameDir;
                                case "getGrugModsDirectory":
                                    return modsDir;
                                case "clientLevel":
                                    return level;
                                case "getLevel":
                                    return level;
                                case "testPlayer":
                                    return player;
                                case "playerPosition":
                                    return new Vec3(playerX, 4.2, 6.9);
                                case "getSide":
                                    return GrugSide.CLIENT;
                                case "getHostileBlockLightLimit":
                                    return 7;
                                case "isOutOfHeightLimit":
                                    return false;
                                case "isHostileSpawnSpace":
                                    return ((Number) args[1]).intValue() == spawnX
                                            && ((Number) args[2]).intValue() == 4
                                            && ((Number) args[3]).intValue() == 6;
                                case "getBlockLight":
                                    return 2;
                                case "getSkyLight":
                                    return 12;
                                case "logError":
                                    return null;
                                default:
                                    if (method.isDefault()) {
                                        return InvocationHandler.invokeDefault(proxy, method, args);
                                    }
                                    throw new AssertionError(
                                            "unexpected adapter call " + method.getName());
                            }
                        }));
    }
}
