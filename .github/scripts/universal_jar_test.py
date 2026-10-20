#!/usr/bin/env python3
"""Tests universal_jar.py: the assembly of the universal jar, and every invariant check on it.

Each test starts from a miniature set of layer jars that satisfies every invariant: one jar per
loader in LAYERS, shaped like that loader's real layerJar, plus a core and a checkout with loaders/
and mods/. It then breaks one invariant and requires the check to name it, so a check that stopped
looking would fail here rather than pass on a broken jar in CI.
"""

import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import universal_jar  # noqa: E402
from universal_jar import (  # noqa: E402
    AssemblyError,
    Universal,
    assemble,
    check,
    check_dispatcher,
    compare_semver,
    fabric_predicate_matches,
    maven_range_matches,
)

MOD_ANNOTATION_CLASS = universal_jar.FORGE_MOD_ANNOTATION + b"grug"

OSL_DEPENDS = {
    "osl-blocks": ">=0.1.1",
    "osl-core": ">=0.9.0",
    "osl-entrypoints": ">=0.6.1",
    "osl-keybinds": ">=0.3.0",
    "osl-lifecycle-events": ">=0.6.1",
    "osl-resource-loader": ">=0.7.4",
}


def mixin_config(package, *mixins):
    return json.dumps({"package": package, "mixins": list(mixins)}).encode()


def fabric_layer(package, minecraft, entrypoint, extra_depends):
    return {
        "fabric.mod.json": json.dumps(
            {
                "schemaVersion": 1,
                "id": "grug",
                "version": "1.0.0",
                "entrypoints": {"init": [package.replace("/", ".") + entrypoint]},
                "mixins": ["grug.mixins.json"],
                "depends": {"minecraft": minecraft, **extra_depends},
            }
        ).encode(),
        "grug.mixins.json": mixin_config(package.replace("/", ".") + "mixin", "WorldMixin"),
        f"{package}{entrypoint.replace('.', '/')}.class": b"",
        f"{package}mixin/WorldMixin.class": b"",
    }


MODS_TOML = b"""modLoader = "javafml"
loaderVersion = "[0,)"
license = "MIT"
[[mods]]
modId = "grug"
version = "1.0.0"
[[dependencies.grug]]
modId = "forge"
mandatory = true
versionRange = "[0,)"
[[dependencies.grug]]
modId = "minecraft"
mandatory = true
versionRange = "[1.20.6,1.21)"
"""


def layers():
    """Every layer's files, shaped like the real layerJar outputs."""
    return {
        "1.20.6-forge": {
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nMixinConfigs: grug-1.20.6-forge.mixins.json\r\n\r\n",
            "META-INF/mods.toml": MODS_TOML,
            "pack.mcmeta": b"{}",
            "grug-1.20.6-forge.mixins.json": json.dumps(
                {
                    "package": "net.grug.minecraft.forge.mixin",
                    "refmap": "grug-1.20.6-forge.refmap.json",
                    "client": ["GuiMixin"],
                }
            ).encode(),
            "grug-1.20.6-forge.refmap.json": b"{}",
            "net/grug/minecraft/forge/GrugModLoader.class": MOD_ANNOTATION_CLASS,
            "net/grug/minecraft/forge/mixin/GuiMixin.class": b"",
        },
        "1.2.5-forge": {
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
            "net/grug/minecraft/forge125/mod_Grug.class": b"",
            "GrugTorchFlicker.class": b"",
            "vanilla_names.txt": b"",
        },
        "a1.1.2_01-ornithe": {
            **fabric_layer(
                "net/grug/minecraft/ornithe/", "1.0.0-alpha.1.2.1", "GrugModLoader", OSL_DEPENDS
            ),
            "net/minecraft/crafting/GrugRecipeHelper.class": b"",
        },
        "b1.7.3-ornithe": {
            **fabric_layer(
                "net/grug/minecraft/ornithe/", "1.0.0-beta.7.3", "GrugModLoader", OSL_DEPENDS
            ),
            "net/minecraft/crafting/GrugRecipeHelper.class": b"",
        },
        "b1.7.3-stationapi": fabric_layer(
            "net/grug/minecraft/stationapi/",
            "1.0.0-beta.7.3",
            "events.init.InitListener",
            {"stationapi": ">=2.0.0-alpha.6.2"},
        ),
    }


CORE = {
    "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
    "net/grug/minecraft/grug/Grug.class": b"",
    "net/grug/minecraft/shadow/gson/Gson.class": b"",
    "natives/libadapter.so": b"\x7fELF",
    "mod_api.json": b"{}",
    "grug_bundled_mods.txt": b"examplemod/about.json\nexamplemod/code/foo-Block.grug\n",
    "mods/examplemod/about.json": b"{}",
    "mods/examplemod/code/foo-Block.grug": b"",
}


def write_jar(path, files, duplicate=None):
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data)
        if duplicate:
            archive.writestr(duplicate, files[duplicate])
    return path


def jar_bytes(files):
    buffer = io.BytesIO()
    write_jar(buffer, files)
    return buffer.getvalue()


class Fixture:
    """A temporary checkout with a core jar and one layer jar per loader."""

    def __init__(self, test):
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.dir = Path(directory.name)
        self.repo = self.dir / "repo"
        for loader in universal_jar.LAYERS:
            (self.repo / "loaders" / loader).mkdir(parents=True)
        (self.repo / "mods" / "examplemod").mkdir(parents=True)
        self.core = dict(CORE)
        self.layers = layers()
        self.out = self.dir / "grug.jar"

    def layer_paths(self, skip=()):
        paths = []
        for loader, files in self.layers.items():
            if loader not in skip:
                paths.append(write_jar(self.dir / f"grug-{loader}.jar", files))
        return paths

    def assemble(self, skip=()):
        core = write_jar(self.dir / "core-release.jar", self.core)
        assemble(core, self.layer_paths(skip), "1.0.0-abc", self.out)
        return self.out

    def problems(self, skip=()):
        return check(self.assemble(skip), self.repo)


class AssembleTest(unittest.TestCase):
    def test_lays_out_the_root_and_the_nested_layers(self):
        fixture = Fixture(self)
        jar = Universal.read(fixture.assemble())
        self.assertEqual(
            set(jar.nested), {"a1.1.2_01-ornithe", "b1.7.3-ornithe", "b1.7.3-stationapi"}
        )
        self.assertIn("natives/libadapter.so", jar.root.files)
        self.assertIn("net/grug/minecraft/forge125/mod_Grug.class", jar.root.files)
        self.assertIn("META-INF/mods.toml", jar.root.files)
        outer = json.loads(jar.root.files["fabric.mod.json"])
        self.assertEqual(outer["id"], universal_jar.OUTER_MOD_ID)
        self.assertEqual(
            [entry["file"] for entry in outer["jars"]],
            [
                "META-INF/jars/grug-a1.1.2_01-ornithe.jar",
                "META-INF/jars/grug-b1.7.3-ornithe.jar",
                "META-INF/jars/grug-b1.7.3-stationapi.jar",
            ],
        )
        manifest = universal_jar.read_manifest(jar.root.files["META-INF/MANIFEST.MF"])
        self.assertEqual(manifest["MixinConfigs"], "grug-1.20.6-forge.mixins.json")
        with zipfile.ZipFile(fixture.out) as archive:
            self.assertEqual(archive.namelist()[:2], ["META-INF/", "META-INF/MANIFEST.MF"])
            self.assertIn("net/grug/minecraft/forge125/", archive.namelist())

    def test_assembles_the_same_bytes_twice(self):
        fixture = Fixture(self)
        first = fixture.assemble().read_bytes()
        self.assertEqual(fixture.assemble().read_bytes(), first)

    def test_holds_every_invariant(self):
        self.assertEqual(Fixture(self).problems(), [])

    def test_refuses_a_path_two_layers_hold(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["pack.mcmeta"] = b"{}"
        with self.assertRaisesRegex(AssemblyError, "pack.mcmeta is in both"):
            fixture.assemble()

    def test_refuses_a_root_layer_that_repeats_core(self):
        fixture = Fixture(self)
        fixture.layers["1.20.6-forge"]["mod_api.json"] = b"{}"
        with self.assertRaisesRegex(AssemblyError, "mod_api.json is in both"):
            fixture.assemble()

    def test_refuses_a_fabric_layer_without_metadata(self):
        fixture = Fixture(self)
        del fixture.layers["b1.7.3-stationapi"]["fabric.mod.json"]
        with self.assertRaisesRegex(AssemblyError, "lacks a fabric.mod.json"):
            fixture.assemble()

    def test_refuses_a_root_layer_with_fabric_metadata(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["fabric.mod.json"] = b"{}"
        with self.assertRaisesRegex(AssemblyError, "has a fabric.mod.json"):
            fixture.assemble()

    def test_refuses_an_unknown_loader(self):
        fixture = Fixture(self)
        fixture.layers["1.21-neoforge"] = {"net/grug/minecraft/neoforge/A.class": b""}
        with self.assertRaisesRegex(AssemblyError, "1.21-neoforge has no entry in LAYERS"):
            fixture.assemble()

    def test_refuses_a_layer_given_twice(self):
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        paths = fixture.layer_paths()
        with self.assertRaisesRegex(AssemblyError, "given twice"):
            assemble(core, paths + paths[:1], "1", fixture.out)

    def test_refuses_conflicting_manifest_attributes(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["META-INF/MANIFEST.MF"] = (
            b"Manifest-Version: 1.0\r\nMixinConfigs: other.json\r\n\r\n"
        )
        with self.assertRaisesRegex(AssemblyError, "MixinConfigs to a second value"):
            fixture.assemble()

    def test_wraps_long_manifest_lines(self):
        long = "x" * 200
        data = universal_jar.write_manifest({"Manifest-Version": "1.0", "MixinConfigs": long})
        self.assertTrue(all(len(line) <= 72 for line in data.split(b"\r\n")))
        self.assertEqual(universal_jar.read_manifest(data)["MixinConfigs"], long)


class CheckTest(unittest.TestCase):
    def assertProblem(self, problems, text):
        self.assertTrue(any(text in p for p in problems), f"no problem names {text!r}: {problems}")

    def test_duplicate_entries_in_the_root(self):
        fixture = Fixture(self)
        out = fixture.assemble()
        with zipfile.ZipFile(out) as archive:
            files = {i.filename: archive.read(i) for i in archive.infolist()}
        write_jar(out, files, duplicate="mod_api.json")
        self.assertProblem(check(out, fixture.repo), "holds mod_api.json more than once")

    def test_duplicate_entries_in_a_nested_layer(self):
        fixture = Fixture(self)
        out = fixture.assemble()
        with zipfile.ZipFile(out) as archive:
            files = {i.filename: archive.read(i) for i in archive.infolist()}
        name = "META-INF/jars/grug-b1.7.3-stationapi.jar"
        buffer = io.BytesIO()
        nested = fixture.layers["b1.7.3-stationapi"]
        write_jar(buffer, nested, duplicate="grug.mixins.json")
        files[name] = buffer.getvalue()
        write_jar(out, files)
        self.assertProblem(check(out, fixture.repo), "holds grug.mixins.json more than once")

    def test_a_resource_at_the_root_and_in_a_nested_layer(self):
        fixture = Fixture(self)
        fixture.layers["b1.7.3-ornithe"]["grug-1.20.6-forge.mixins.json"] = b"{}"
        self.assertProblem(
            fixture.problems(), "grug-1.20.6-forge.mixins.json is both at the root and in"
        )

    def test_a_vanilla_helper_at_the_root_and_in_a_nested_layer(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["net/minecraft/crafting/GrugRecipeHelper.class"] = b""
        problems = fixture.problems()
        self.assertProblem(problems, "GrugRecipeHelper.class is both at the root and in")
        self.assertProblem(problems, "GrugRecipeHelper.class at the root is neither core's")

    def test_a_nested_class_outside_its_package(self):
        fixture = Fixture(self)
        fixture.layers["b1.7.3-stationapi"]["net/grug/minecraft/ornithe/Stray.class"] = b""
        self.assertProblem(
            fixture.problems(),
            "net/grug/minecraft/ornithe/Stray.class in META-INF/jars/grug-b1.7.3-stationapi.jar "
            "is outside net/grug/minecraft/stationapi/",
        )

    def test_a_root_class_in_no_known_package(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["com/example/Stray.class"] = b""
        self.assertProblem(fixture.problems(), "com/example/Stray.class at the root is neither")

    def test_a_root_class_in_a_nested_layers_package(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["net/grug/minecraft/stationapi/Stray.class"] = b""
        self.assertProblem(fixture.problems(), "is in the package of b1.7.3-stationapi")

    def test_a_missing_layer(self):
        fixture = Fixture(self)
        problems = fixture.problems(skip=("b1.7.3-stationapi",))
        self.assertProblem(problems, "loaders/b1.7.3-stationapi has no layer in the jar")
        self.assertProblem(
            problems, "Babric b1.7.3 with StationAPI activates no layer, but should activate"
        )

    def test_a_missing_root_layer(self):
        fixture = Fixture(self)
        self.assertProblem(
            fixture.problems(skip=("1.2.5-forge",)), "loaders/1.2.5-forge has no layer in the jar"
        )

    def test_a_loader_directory_without_a_layer(self):
        fixture = Fixture(self)
        (fixture.repo / "loaders" / "1.21-neoforge").mkdir()
        problems = fixture.problems()
        self.assertProblem(problems, "loaders/1.21-neoforge has no layer in the jar")
        self.assertProblem(problems, "loaders/1.21-neoforge has no entry in LAYERS")

    def test_a_layer_whose_loader_directory_is_gone(self):
        fixture = Fixture(self)
        (fixture.repo / "loaders" / "b1.7.3-ornithe").rmdir()
        self.assertProblem(
            fixture.problems(), "the jar has a layer for b1.7.3-ornithe, which is not in loaders/"
        )

    def test_an_unknown_nested_jar(self):
        fixture = Fixture(self)
        out = fixture.assemble()
        with zipfile.ZipFile(out) as archive:
            files = {i.filename: archive.read(i) for i in archive.infolist()}
        files["META-INF/jars/other.jar"] = jar_bytes({"a": b""})
        write_jar(out, files)
        self.assertProblem(check(out, fixture.repo), "META-INF/jars/other.jar is not a nested")

    def test_a_second_native_in_a_nested_layer(self):
        fixture = Fixture(self)
        fixture.layers["a1.1.2_01-ornithe"]["natives/libadapter.so"] = b""
        self.assertProblem(fixture.problems(), "natives/libadapter.so is in META-INF/jars/grug-a1")

    def test_a_missing_native(self):
        fixture = Fixture(self)
        del fixture.core["natives/libadapter.so"]
        self.assertProblem(fixture.problems(), "the root holds no native library")

    def test_a_missing_mod_api(self):
        fixture = Fixture(self)
        del fixture.core["mod_api.json"]
        self.assertProblem(fixture.problems(), "mod_api.json is missing from the root")

    def test_core_nested_in_a_layer(self):
        fixture = Fixture(self)
        fixture.layers["b1.7.3-ornithe"]["META-INF/jars/grug-core-1.0.0-release.jar"] = b""
        fixture.layers["b1.7.3-stationapi"]["net/grug/minecraft/grug/Grug.class"] = b""
        problems = fixture.problems()
        self.assertProblem(problems, "nests META-INF/jars/grug-core-1.0.0-release.jar")
        self.assertProblem(problems, "net/grug/minecraft/grug/Grug.class is core's and is in")

    def test_a_mod_that_is_not_bundled(self):
        fixture = Fixture(self)
        (fixture.repo / "mods" / "buildcraft").mkdir()
        self.assertProblem(fixture.problems(), "mods/buildcraft is not bundled in the jar")

    def test_a_bundled_mod_that_is_gone(self):
        fixture = Fixture(self)
        fixture.core["mods/oldmod/about.json"] = b"{}"
        fixture.core["grug_bundled_mods.txt"] += b"oldmod/about.json\n"
        self.assertProblem(fixture.problems(), "the jar bundles mods/oldmod, which is not in mods/")

    def test_a_bundled_file_missing_from_the_index(self):
        fixture = Fixture(self)
        fixture.core["mods/examplemod/LICENSE"] = b""
        self.assertProblem(
            fixture.problems(), "mods/examplemod/LICENSE is not in grug_bundled_mods.txt"
        )

    def test_an_index_entry_missing_from_the_jar(self):
        fixture = Fixture(self)
        del fixture.core["mods/examplemod/code/foo-Block.grug"]
        self.assertProblem(fixture.problems(), "lists mods/examplemod/code/foo-Block.grug")

    def test_mods_toml_that_does_not_parse(self):
        fixture = Fixture(self)
        fixture.layers["1.20.6-forge"]["META-INF/mods.toml"] = b"modLoader = "
        self.assertProblem(fixture.problems(), "META-INF/mods.toml does not parse")

    def test_a_mod_id_without_its_mod_class(self):
        fixture = Fixture(self)
        fixture.layers["1.20.6-forge"]["net/grug/minecraft/forge/GrugModLoader.class"] = b""
        self.assertProblem(fixture.problems(), "no class at the root carries @Mod for modId grug")

    def test_a_manifest_mixin_config_that_is_missing(self):
        fixture = Fixture(self)
        del fixture.layers["1.20.6-forge"]["grug-1.20.6-forge.mixins.json"]
        self.assertProblem(fixture.problems(), "the manifest names mixin config")

    def test_a_missing_refmap(self):
        fixture = Fixture(self)
        del fixture.layers["1.20.6-forge"]["grug-1.20.6-forge.refmap.json"]
        self.assertProblem(fixture.problems(), "names refmap grug-1.20.6-forge.refmap.json")

    def test_a_missing_forge_mixin_class(self):
        fixture = Fixture(self)
        del fixture.layers["1.20.6-forge"]["net/grug/minecraft/forge/mixin/GuiMixin.class"]
        self.assertProblem(
            fixture.problems(), "names mixin net/grug/minecraft/forge/mixin/GuiMixin.class"
        )

    def test_two_mod_classes_for_modloader(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["net/grug/minecraft/forge125/mod_Other.class"] = b""
        self.assertProblem(fixture.problems(), "the root holds 2 mod_ classes")

    def test_a_missing_entrypoint(self):
        fixture = Fixture(self)
        del fixture.layers["b1.7.3-ornithe"]["net/grug/minecraft/ornithe/GrugModLoader.class"]
        self.assertProblem(
            fixture.problems(), "has init entrypoint net.grug.minecraft.ornithe.GrugModLoader"
        )

    def test_a_missing_fabric_mixin_class(self):
        fixture = Fixture(self)
        del fixture.layers["b1.7.3-stationapi"][
            "net/grug/minecraft/stationapi/mixin/WorldMixin.class"
        ]
        self.assertProblem(
            fixture.problems(), "names mixin net/grug/minecraft/stationapi/mixin/WorldMixin.class"
        )

    def test_a_fabric_layer_that_does_not_parse(self):
        fixture = Fixture(self)
        fixture.layers["b1.7.3-stationapi"]["fabric.mod.json"] = b"{"
        self.assertProblem(
            fixture.problems(), "grug-b1.7.3-stationapi.jar: fabric.mod.json does not parse"
        )

    def test_a_missing_access_widener_and_nested_jar(self):
        fixture = Fixture(self)
        metadata = json.loads(fixture.layers["b1.7.3-ornithe"]["fabric.mod.json"])
        metadata["accessWidener"] = "grug.accesswidener"
        metadata["jars"] = [{"file": "META-INF/jars/lib.jar"}]
        fixture.layers["b1.7.3-ornithe"]["fabric.mod.json"] = json.dumps(metadata).encode()
        problems = fixture.problems()
        self.assertProblem(problems, "names access widener grug.accesswidener")
        self.assertProblem(problems, "lists nested jar META-INF/jars/lib.jar")


class DispatcherTest(unittest.TestCase):
    def assertProblem(self, problems, text):
        self.assertTrue(any(text in p for p in problems), f"no problem names {text!r}: {problems}")

    def test_an_ornithe_layer_without_its_osl_dependency_reaches_babric(self):
        fixture = Fixture(self)
        metadata = json.loads(fixture.layers["b1.7.3-ornithe"]["fabric.mod.json"])
        metadata["depends"] = {"minecraft": "1.0.0-beta.7.3"}
        fixture.layers["b1.7.3-ornithe"]["fabric.mod.json"] = json.dumps(metadata).encode()
        problems = fixture.problems()
        self.assertProblem(
            problems, "Babric b1.7.3 without StationAPI activates b1.7.3-ornithe, but should"
        )
        self.assertProblem(
            problems,
            "Babric b1.7.3 with StationAPI activates b1.7.3-ornithe and b1.7.3-stationapi",
        )

    def test_a_layer_that_matches_the_wrong_version(self):
        fixture = Fixture(self)
        metadata = json.loads(fixture.layers["a1.1.2_01-ornithe"]["fabric.mod.json"])
        metadata["depends"]["minecraft"] = ">=1.0.0-alpha.1.2.1"
        fixture.layers["a1.1.2_01-ornithe"]["fabric.mod.json"] = json.dumps(metadata).encode()
        problems = fixture.problems()
        self.assertProblem(problems, "Ornithe b1.7.3 with OSL activates a1.1.2_01-ornithe and")
        self.assertProblem(problems, "Ornithe 1.12.2 with OSL activates a1.1.2_01-ornithe")

    def test_an_outer_dependency_breaks_every_fabric_game(self):
        fixture = Fixture(self)
        jar = Universal.read(fixture.assemble())
        outer = json.loads(jar.root.files["fabric.mod.json"])
        outer["depends"] = {"minecraft": "1.20.6"}
        jar.root.files["fabric.mod.json"] = json.dumps(outer).encode()
        self.assertProblem(
            check_dispatcher(jar), "Ornithe b1.7.3 with OSL breaks the launch, but should"
        )

    def test_a_known_gap_that_changed(self):
        fixture = Fixture(self)
        fixture.layers["1.20.6-forge"]["META-INF/mods.toml"] = MODS_TOML.replace(
            b"[1.20.6,1.21)", b"[0,)"
        )
        self.assertProblem(
            fixture.problems(),
            "Forge 1.21.1 now activates 1.20.6-forge rather than breaks the launch; "
            "update KNOWN_GAPS (#493)",
        )

    def test_a_predicate_the_checker_cannot_read(self):
        fixture = Fixture(self)
        metadata = json.loads(fixture.layers["b1.7.3-stationapi"]["fabric.mod.json"])
        metadata["depends"]["stationapi"] = "^2.0.0"
        fixture.layers["b1.7.3-stationapi"]["fabric.mod.json"] = json.dumps(metadata).encode()
        self.assertProblem(fixture.problems(), "cannot evaluate the jar's metadata")


class VersionTest(unittest.TestCase):
    def test_semver_order(self):
        ordered = [
            "1.0.0-alpha.1.2.1",
            "1.0.0-alpha.2.6",
            "1.0.0-beta.7.3",
            "1.0.0-beta.8.1",
            "1.0.0",
            "1.20.6",
            "1.21",
            "1.21.1",
        ]
        for low, high in zip(ordered, ordered[1:]):
            self.assertEqual(compare_semver(low, high), -1, (low, high))
            self.assertEqual(compare_semver(high, low), 1, (high, low))
        self.assertEqual(compare_semver("0.9.0+mca1.0.1_01-mc14w26c", "0.9.0"), 0)
        self.assertEqual(compare_semver("1.21", "1.21.0"), 0)
        self.assertEqual(compare_semver("2.0.0-alpha.6", "2.0.0-alpha.6.2"), -1)
        self.assertEqual(compare_semver("1.0.0-alpha.10", "1.0.0-alpha.9"), 1)
        self.assertEqual(compare_semver("1.0.0-alpha.beta", "1.0.0-alpha.1"), 1)

    def test_fabric_predicates(self):
        self.assertTrue(fabric_predicate_matches("1.0.0-beta.7.3", "1.0.0-beta.7.3"))
        self.assertFalse(fabric_predicate_matches("1.0.0-beta.7.3", "1.0.0-beta.8.1"))
        self.assertTrue(fabric_predicate_matches(">=0.9.0", "0.9.0"))
        self.assertTrue(fabric_predicate_matches(">=2.0.0-alpha.6.2", "2.0.0-alpha.6.2"))
        self.assertFalse(fabric_predicate_matches(">=2.0.0-alpha.6.2", "2.0.0-alpha.5"))
        self.assertTrue(fabric_predicate_matches(">=1.0.0-beta.3 <=1.0.0-beta.7.3", "1.0.0-beta.4"))
        self.assertFalse(fabric_predicate_matches(">=1.0.0-beta.3 <=1.0.0-beta.7.3", "1.12.2"))
        self.assertTrue(fabric_predicate_matches("<1.21 >1.20", "1.20.6"))
        self.assertTrue(fabric_predicate_matches("=1.20.6", "1.20.6"))
        self.assertTrue(fabric_predicate_matches("*", "anything"))
        for predicate in ("^1.0.0", "~1.0", "1.20.x"):
            with self.assertRaises(ValueError):
                fabric_predicate_matches(predicate, "1.0.0")

    def test_maven_ranges(self):
        self.assertTrue(maven_range_matches("[1.20.6,1.21)", "1.20.6"))
        self.assertFalse(maven_range_matches("[1.20.6,1.21)", "1.21"))
        self.assertFalse(maven_range_matches("[1.20.6,1.21)", "1.20.4"))
        self.assertTrue(maven_range_matches("[0,)", "50.1.0"))
        self.assertTrue(maven_range_matches("(1.20,1.21]", "1.21"))
        self.assertFalse(maven_range_matches("(1.20,1.21]", "1.20"))
        self.assertTrue(maven_range_matches("[1.20.6]", "1.20.6"))
        self.assertFalse(maven_range_matches("[1.20.6]", "1.20.5"))
        with self.assertRaises(ValueError):
            maven_range_matches("1.20.6", "1.20.6")


class CommandLineTest(unittest.TestCase):
    def test_assemble_then_check(self):
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        out = fixture.dir / "cli.jar"
        argv = ["assemble", "--core", str(core), "--version", "1", "--out", str(out)]
        self.assertEqual(universal_jar.main(argv + [str(p) for p in fixture.layer_paths()]), 0)
        repo = ["--repo", str(fixture.repo)]
        self.assertEqual(universal_jar.main(["check", str(out)] + repo), 0)
        del fixture.core["mod_api.json"]
        fixture.assemble()
        self.assertEqual(universal_jar.main(["check", str(fixture.out)] + repo), 1)

    def test_assemble_reports_an_error(self):
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        bad = write_jar(fixture.dir / "not-a-layer.jar", {})
        argv = ["assemble", "--core", str(core), "--version", "1", "--out", str(fixture.out)]
        self.assertEqual(universal_jar.main(argv + [str(bad)]), 1)


if __name__ == "__main__":
    unittest.main()
