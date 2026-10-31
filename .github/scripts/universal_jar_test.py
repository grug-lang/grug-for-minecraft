#!/usr/bin/env python3
"""Tests universal_jar.py: the assembly of the universal jar, and every invariant check on it.

Each test starts from a miniature set of layer jars that satisfies every invariant: one jar per
loader in LAYERS, shaped like that loader's real layerJar, plus a core and a checkout with loaders/
and mods/. It then breaks one invariant and requires the check to name it, so a check that stopped
looking would fail here rather than pass on a broken jar in CI.
"""

import hashlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

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


def klass(version=universal_jar.JAVA_8, *descriptors):
    """A class file as far as the checks read one: its major version and its constant pool's
    annotation descriptors and strings."""
    return b"\xca\xfe\xba\xbe\x00\x00" + version.to_bytes(2, "big") + b"".join(descriptors)


def utf8(text):
    data = text.encode()
    return b"\x01" + len(data).to_bytes(2, "big") + data


def string_field_class(field, value, extra_constants=()):
    """A minimal Java 8 class file with one static final String field holding value as its
    ConstantValue, as javac writes mod_Grug.VERSION. extra_constants are raw constant pool entries
    placed before the ones the field needs."""

    def u2(n):
        return n.to_bytes(2, "big")

    pool, slots = [], [1]

    def constant(entry):
        pool.append(entry)
        index = slots[0]
        slots[0] += 2 if entry[0] in (5, 6) else 1  # a Long or a Double takes two slots
        return index

    for entry in extra_constants:
        constant(entry)
    this = constant(b"\x07" + u2(constant(utf8("net/grug/minecraft/forge125/mod_Grug"))))
    parent = constant(b"\x07" + u2(constant(utf8("java/lang/Object"))))
    name, descriptor = constant(utf8(field)), constant(utf8("Ljava/lang/String;"))
    attribute = constant(utf8("ConstantValue"))
    string = constant(b"\x08" + u2(constant(utf8(value))))
    field_info = u2(0x19) + u2(name) + u2(descriptor) + u2(1)
    field_info += u2(attribute) + (2).to_bytes(4, "big") + u2(string)
    header = b"\xca\xfe\xba\xbe\x00\x00" + universal_jar.JAVA_8.to_bytes(2, "big")
    body = u2(0x21) + u2(this) + u2(parent) + u2(0) + u2(1) + field_info + u2(0) + u2(0)
    return header + u2(slots[0]) + b"".join(pool) + body


JAVA_7, JAVA_17, JAVA_21 = 51, 61, 65
BOOTSTRAP_MOD = klass(
    universal_jar.JAVA_8,
    universal_jar.FORGE_MOD,
    universal_jar.NEOFORGE_MOD,
    universal_jar.FML_1_7_MOD,
    b"grug",
)

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


def fabric_layer(package, minecraft, entrypoint, extra_depends, version=universal_jar.JAVA_8):
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
        f"{package}{entrypoint.replace('.', '/')}.class": klass(version),
        f"{package}mixin/WorldMixin.class": klass(version),
    }


FORGE_MIXINS = {
    "required": True,
    "package": "net.grug.minecraft.forge.mixin",
    "plugin": universal_jar.MIXIN_PLUGIN,
    "refmap": "grug-1.20.6-forge.refmap.json",
    "grug": {"client": ["GuiMixin"]},
}


def layers():
    """Every layer's files, shaped like the real layerJar outputs."""
    return {
        "1.20.6-forge": {
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nMixinConfigs: grug-1.20.6-forge.mixins.json\r\n\r\n",
            "pack.mcmeta": b"{}",
            "grug-1.20.6-forge.mixins.json": json.dumps(FORGE_MIXINS).encode(),
            "grug-1.20.6-forge.refmap.json": b"{}",
            "net/grug/minecraft/forge/GrugModLoader.class": klass(JAVA_21),
            "net/grug/minecraft/forge/mixin/GuiMixin.class": klass(JAVA_21),
        },
        "1.2.5-forge": {
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
            "net/grug/minecraft/forge125/mod_Grug.class": string_field_class("VERSION", VERSION),
            "GrugTorchFlicker.class": klass(),
            "GrugTitleButton.class": klass(),
            "vanilla_names.txt": b"",
        },
        "a1.1.2_01-ornithe": {
            **fabric_layer(
                "net/grug/minecraft/ornithe/", "1.0.0-alpha.1.2.1", "GrugModLoader", OSL_DEPENDS
            ),
            "net/minecraft/crafting/GrugRecipeHelper.class": klass(),
        },
        "b1.7.3-ornithe": {
            **fabric_layer(
                "net/grug/minecraft/ornithe/", "1.0.0-beta.7.3", "GrugModLoader", OSL_DEPENDS
            ),
            "net/minecraft/crafting/GrugRecipeHelper.class": klass(),
        },
        "b1.7.3-stationapi": fabric_layer(
            "net/grug/minecraft/stationapi/",
            "1.0.0-beta.7.3",
            "events.init.InitListener",
            {"stationapi": ">=2.0.0-alpha.6.2"},
            JAVA_17,
        ),
    }


FORGE_LAYERS = {
    "1.20.6-forge": {
        "minecraft": "1.20.6",
        "entrypoint": "net.grug.minecraft.forge.GrugModLoader",
        "mixins": "grug-1.20.6-forge.mixins.json",
    }
}

CORE = {
    "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
    "net/grug/minecraft/grug/Grug.class": klass(),
    "net/grug/minecraft/bootstrap/GrugForgeMod.class": BOOTSTRAP_MOD,
    "net/grug/minecraft/bootstrap/GrugMixinPlugin.class": klass(),
    "net/grug/minecraft/bootstrap/GrugFabricPreLaunch.class": klass(),
    "net/grug/minecraft/shadow/gson/Gson.class": klass(),
    "grug_forge_layers.json": json.dumps(FORGE_LAYERS).encode(),
    "natives/libadapter.so": b"\x7fELF",
    "mod_api.json": b"{}",
    "grug_bundled_mods.txt": b"examplemod/about.json\nexamplemod/code/foo-Block.grug\n",
    "mods/examplemod/about.json": b"{}",
    "mods/examplemod/code/foo-Block.grug": b"",
}


# The repository version of the fixture's checkout, and the version core publishes it under.
VERSION = "1.0.0"
STAMPED = VERSION + "-0123456789ab"


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
        (self.repo / "gradle.properties").write_text(f"mod_version={VERSION}\n")
        (self.repo / "loaders" / "1.20.6-forge" / "gradle.properties").write_text(
            "minecraft_version=1.20.6\n"
        )
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
        assemble(core, self.layer_paths(skip), STAMPED, self.out, [self.dll()])
        return self.out

    def dll(self):
        """The Windows adapter, which a release builds on its own runner."""
        path = self.dir / "adapter.dll"
        path.write_bytes(b"MZ")
        return path

    def problems(self, skip=()):
        return check(self.assemble(skip), self.repo)

    def problems_after(self, changes):
        """The problems of the assembled jar with some of its root files replaced, or removed for
        None: for the files assemble writes itself rather than takes from a layer."""
        out = self.assemble()
        with zipfile.ZipFile(out) as archive:
            files = {i.filename: archive.read(i) for i in archive.infolist()}
        for name, data in changes.items():
            if data is None:
                del files[name]
            else:
                files[name] = data
        write_jar(out, files)
        return check(out, self.repo)


class AssembleTest(unittest.TestCase):
    def test_lays_out_the_root_and_the_nested_layers(self):
        fixture = Fixture(self)
        jar = Universal.read(fixture.assemble())
        self.assertEqual(
            set(jar.nested), {"a1.1.2_01-ornithe", "b1.7.3-ornithe", "b1.7.3-stationapi"}
        )
        self.assertIn("natives/libadapter.so", jar.root.files)
        self.assertIn("natives/adapter.dll", jar.root.files)
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
            problems, "Babric b1.7.3 with StationAPI stops with grug's error, but should activate"
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
        self.assertProblem(fixture.problems(), "the root holds no libadapter.so")

    def test_a_jar_without_the_windows_adapter_is_refused(self):
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        assemble(core, fixture.layer_paths(), STAMPED, fixture.out)
        self.assertProblem(check(fixture.out, fixture.repo), "the root holds no adapter.dll")

    def test_a_jar_assembled_without_the_windows_adapter_is_accepted_when_it_says_so(self):
        # A push that adds no loader assembles and tests the jar on Linux, so a Linux jar is what it
        # has and its job says so (#784). A release still requires the adapter, which is the test
        # above and, in CI, release.yml's check with no flag.
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        assemble(core, fixture.layer_paths(), STAMPED, fixture.out)
        self.assertEqual(check(fixture.out, fixture.repo, windows_native=False), [])
        self.assertProblem(check(fixture.out, fixture.repo), "the root holds no adapter.dll")

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

    def test_the_coverage_fixtures_are_not_bundled(self):
        fixture = Fixture(self)
        (fixture.repo / "mods" / "coverage").mkdir()
        self.assertEqual(fixture.problems(), [])
        fixture.core["mods/coverage/about.json"] = b"{}"
        fixture.core["grug_bundled_mods.txt"] += b"coverage/about.json\n"
        self.assertProblem(
            fixture.problems(),
            "the jar bundles mods/coverage, which holds test fixtures and stays out of it",
        )

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
        problems = Fixture(self).problems_after({"META-INF/mods.toml": b"modLoader = "})
        self.assertProblem(problems, "META-INF/mods.toml does not parse")
        self.assertProblem(problems, "Forge 1.21.1: cannot evaluate the jar's metadata")

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
        jar = Universal.read(Fixture(self).assemble())
        gaps = {"Forge 1.21.1": ("breaks the launch", 493)}
        self.assertProblem(
            check_dispatcher(jar, known_gaps=gaps),
            "Forge 1.21.1 now stops with grug's error rather than breaks the launch; "
            "update KNOWN_GAPS (#493)",
        )

    def test_no_gap_is_known(self):
        self.assertEqual(universal_jar.KNOWN_GAPS, {})

    def test_a_predicate_the_checker_cannot_read(self):
        fixture = Fixture(self)
        metadata = json.loads(fixture.layers["b1.7.3-stationapi"]["fabric.mod.json"])
        metadata["depends"]["stationapi"] = "^2.0.0"
        fixture.layers["b1.7.3-stationapi"]["fabric.mod.json"] = json.dumps(metadata).encode()
        self.assertProblem(fixture.problems(), "cannot evaluate the jar's metadata")


class ForgeFamilyTest(unittest.TestCase):
    """The root metadata every Forge and NeoForge reads, and how each of them treats the jar."""

    def assertProblem(self, problems, text):
        self.assertTrue(any(text in p for p in problems), f"no problem names {text!r}: {problems}")

    def test_writes_one_range_free_mod_for_every_forge(self):
        jar = Universal.read(Fixture(self).assemble())
        for name in (universal_jar.MODS_TOML, universal_jar.NEOFORGE_MODS_TOML):
            toml = universal_jar.tomllib.loads(jar.root.files[name].decode())
            self.assertEqual(toml["loaderVersion"], "[0,)")
            self.assertEqual(toml["license"], "MIT")
            self.assertEqual([mod["modId"] for mod in toml["mods"]], ["grug"])
            self.assertEqual(toml["mods"][0]["version"], STAMPED)
            self.assertNotIn("dependencies", toml)
        manifest = universal_jar.read_manifest(jar.root.files["META-INF/MANIFEST.MF"])
        self.assertNotIn("ModType", manifest)

    def test_refuses_a_root_layer_with_its_own_mods_toml(self):
        fixture = Fixture(self)
        fixture.layers["1.20.6-forge"]["META-INF/mods.toml"] = b""
        with self.assertRaisesRegex(AssemblyError, "META-INF/mods.toml is in both"):
            fixture.assemble()

    def range_toml(self, extra):
        return universal_jar.root_mods_toml("1") + extra

    def test_a_dependency_range_stops_every_other_version(self):
        dependency = (
            b'[[dependencies.grug]]\nmodId = "minecraft"\nmandatory = false\n'
            b'versionRange = "[1.20.6,1.21)"\n'
        )
        problems = Fixture(self).problems_after({"META-INF/mods.toml": self.range_toml(dependency)})
        self.assertProblem(problems, "META-INF/mods.toml declares dependencies")
        self.assertProblem(
            problems, "Forge 1.21.1 breaks the launch, but should stop with grug's error"
        )

    def test_a_mandatory_dependency_that_is_missing(self):
        dependency = b'[[dependencies.grug]]\nmodId = "forge"\ntype = "required"\n'
        problems = Fixture(self).problems_after({"META-INF/mods.toml": self.range_toml(dependency)})
        self.assertProblem(
            problems, "NeoForge 20.4 breaks the launch, but should stop with grug's error"
        )
        self.assertNotIn("Forge 1.21.1 breaks the launch", " ".join(problems))

    def test_a_loader_version_range(self):
        toml = universal_jar.root_mods_toml("1").replace(b'"[0,)"', b'"[50,)"')
        problems = Fixture(self).problems_after({"META-INF/neoforge.mods.toml": toml})
        self.assertProblem(problems, "neoforge.mods.toml asks for javafml '[50,)'")

    def test_a_second_mod(self):
        toml = universal_jar.root_mods_toml("1") + b'[[mods]]\nmodId = "other"\n'
        problems = Fixture(self).problems_after({"META-INF/mods.toml": toml})
        self.assertProblem(problems, "declares the mods ['grug', 'other'], rather than only grug")
        self.assertProblem(problems, "Forge 1.20.6 breaks the launch, but should activate")

    def test_neoforge_without_its_own_metadata_shows_a_warning_screen(self):
        problems = Fixture(self).problems_after({"META-INF/neoforge.mods.toml": None})
        self.assertProblem(problems, "META-INF/neoforge.mods.toml does not parse")
        self.assertProblem(
            problems, "NeoForge 21.1 shows a warning screen, but should stop with grug's error"
        )

    def test_a_jar_neoforge_does_not_recognise_at_all(self):
        jar = Universal.read(Fixture(self).assemble())
        for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "fabric.mod.json"):
            del jar.root.files[name]
        game = next(g for g in universal_jar.GAMES if g.name == "NeoForge 21.1")
        self.assertEqual(universal_jar.activate(jar, game), [])
        for name in ("Forge 1.21.1", "NeoForge 20.4"):
            game = next(g for g in universal_jar.GAMES if g.name == name)
            self.assertEqual(universal_jar.activate(jar, game), "breaks the launch")

    def test_a_layer_class_with_mod_annotation(self):
        fixture = Fixture(self)
        fixture.layers["1.20.6-forge"]["net/grug/minecraft/forge/GrugModLoader.class"] = klass(
            JAVA_21, universal_jar.FORGE_MOD, b"grug"
        )
        problems = fixture.problems()
        self.assertProblem(problems, "Lnet/minecraftforge/fml/common/Mod; is on [")
        self.assertProblem(problems, "GrugModLoader.class carries an FML annotation")
        # Two mod classes for one id on every Forge that can read Java 21, and one on the rest.
        self.assertProblem(
            problems, "Forge 1.21.1 breaks the launch, but should stop with grug's error"
        )
        self.assertNotIn("Forge 1.13.2 breaks", " ".join(problems))

    def test_an_event_bus_subscriber_in_a_layer(self):
        fixture = Fixture(self)
        subscriber = klass(JAVA_21, b"Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;")
        fixture.layers["1.20.6-forge"]["net/grug/minecraft/forge/Events.class"] = subscriber
        problems = fixture.problems()
        self.assertProblem(problems, "Events.class carries an FML annotation")
        self.assertProblem(
            problems, "Forge 1.21.1 loads net/grug/minecraft/forge/Events.class, but should"
        )

    def test_a_missing_bootstrap_mod(self):
        fixture = Fixture(self)
        fixture.core["net/grug/minecraft/bootstrap/GrugForgeMod.class"] = klass()
        problems = fixture.problems()
        self.assertProblem(problems, "Lnet/neoforged/fml/common/Mod; is on [], rather than only")
        self.assertProblem(
            problems, "Forge 1.16.5 breaks the launch, but should stop with grug's error"
        )
        self.assertProblem(
            problems, "NeoForge 21.1 breaks the launch, but should stop with grug's error"
        )

    def test_a_bootstrap_newer_than_java_8(self):
        fixture = Fixture(self)
        fixture.core["net/grug/minecraft/bootstrap/GrugForgeMod.class"] = klass(
            JAVA_17, universal_jar.FORGE_MOD, universal_jar.NEOFORGE_MOD, b"grug"
        )
        problems = fixture.problems()
        self.assertProblem(problems, "GrugForgeMod.class is newer than Java 8")
        self.assertProblem(
            problems, "Forge 1.13.2 breaks the launch, but should stop with grug's error"
        )

    def test_a_manifest_with_mod_type(self):
        problems = Fixture(self).problems_after(
            {"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nModType: grug\r\n\r\n"}
        )
        self.assertProblem(problems, "the manifest has a ModType")

    def test_a_class_old_fml_cannot_read_before_the_bootstrap(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["Aaa.class"] = klass(JAVA_21)
        problems = fixture.problems()
        self.assertProblem(problems, "Aaa.class comes before net/grug/minecraft/bootstrap/Grug")
        self.assertProblem(problems, "Forge 1.12.2 activates no layer, but should stop")

    def test_a_bootstrap_without_the_fml_1_7_mod(self):
        fixture = Fixture(self)
        fixture.core["net/grug/minecraft/bootstrap/GrugForgeMod.class"] = klass(
            universal_jar.JAVA_8, universal_jar.FORGE_MOD, universal_jar.NEOFORGE_MOD, b"grug"
        )
        problems = fixture.problems()
        self.assertProblem(problems, "Forge 1.7.10 activates no layer, but should stop")

    def test_a_first_class_old_fml_can_read(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["GrugTitleButton.class"] = klass(JAVA_7)
        self.assertProblem(
            fixture.problems(), "Forge 1.6.4 reads GrugTitleButton.class, but should activate"
        )

    def test_a_jar_without_classes_old_fml_reads_nothing_of(self):
        jar = Universal.read(Fixture(self).assemble())
        jar.root.files = {n: d for n, d in jar.root.files.items() if not n.endswith(".class")}
        game = next(g for g in universal_jar.GAMES if g.name == "Forge 1.6.4")
        self.assertEqual(universal_jar.activate(jar, game), [])

    def test_a_mod_class_at_the_root(self):
        fixture = Fixture(self)
        fixture.layers["1.2.5-forge"]["mod_Grug.class"] = klass()
        problems = fixture.problems()
        self.assertProblem(problems, "ModLoader b1.7.3 loads mod_Grug.class, but should activate")
        self.assertProblem(problems, "Forge 1.2.5 loads mod_Grug.class, but should activate")

    def test_a_forge_layer_missing_from_the_table(self):
        fixture = Fixture(self)
        fixture.core["grug_forge_layers.json"] = b"{}"
        problems = fixture.problems()
        self.assertProblem(problems, "1.20.6-forge has no entry in grug_forge_layers.json")
        self.assertProblem(
            problems, "Forge 1.20.6 stops with grug's error, but should activate 1.20.6"
        )

    def test_a_missing_table(self):
        fixture = Fixture(self)
        del fixture.core["grug_forge_layers.json"]
        problems = fixture.problems()
        self.assertProblem(problems, "grug_forge_layers.json does not parse")
        self.assertProblem(problems, "Forge 1.20.6: cannot evaluate the jar's metadata")

    def table(self, fixture, **changes):
        table = json.loads(fixture.core["grug_forge_layers.json"])
        table["1.20.6-forge"].update(changes)
        fixture.core["grug_forge_layers.json"] = json.dumps(table).encode()

    def test_a_layer_started_on_the_wrong_version(self):
        fixture = Fixture(self)
        self.table(fixture, minecraft="1.20.4")
        problems = fixture.problems()
        self.assertProblem(
            problems,
            "starts 1.20.6-forge on Minecraft 1.20.4, but its gradle.properties targets 1.20.6",
        )
        self.assertProblem(problems, "Forge 1.20.4 activates 1.20.6-forge, but should")

    def test_a_layer_without_gradle_properties(self):
        fixture = Fixture(self)
        (fixture.repo / "loaders" / "1.20.6-forge" / "gradle.properties").unlink()
        self.assertProblem(fixture.problems(), "its gradle.properties targets None")

    def test_a_missing_layer_entrypoint(self):
        fixture = Fixture(self)
        self.table(fixture, entrypoint="net.grug.minecraft.forge.Gone")
        self.assertProblem(
            fixture.problems(), "starts 1.20.6-forge at net/grug/minecraft/forge/Gone.class"
        )

    def test_a_table_entry_for_a_loader_that_is_not_forge(self):
        fixture = Fixture(self)
        table = json.loads(fixture.core["grug_forge_layers.json"])
        table["b1.7.3-ornithe"] = table["1.20.6-forge"]
        fixture.core["grug_forge_layers.json"] = json.dumps(table).encode()
        self.assertProblem(
            fixture.problems(), "grug_forge_layers.json names b1.7.3-ornithe, which is no Forge"
        )

    def test_a_layer_mixin_config_outside_the_manifest(self):
        fixture = Fixture(self)
        self.table(fixture, mixins="other.mixins.json")
        self.assertProblem(
            fixture.problems(), "mixin config other.mixins.json is not in the manifest's"
        )

    def forge_mixins(self, fixture, config):
        fixture.layers["1.20.6-forge"]["grug-1.20.6-forge.mixins.json"] = json.dumps(
            config
        ).encode()

    def test_a_mixin_config_without_the_plugin(self):
        fixture = Fixture(self)
        self.forge_mixins(fixture, {k: v for k, v in FORGE_MIXINS.items() if k != "plugin"})
        self.assertProblem(fixture.problems(), "is not handed out by " + universal_jar.MIXIN_PLUGIN)

    def test_a_mixin_config_listing_mixins_itself(self):
        fixture = Fixture(self)
        self.forge_mixins(fixture, {**FORGE_MIXINS, "client": ["GuiMixin"]})
        self.assertProblem(fixture.problems(), "lists client mixins itself")

    def test_a_mixin_config_that_does_not_parse(self):
        fixture = Fixture(self)
        fixture.layers["1.20.6-forge"]["grug-1.20.6-forge.mixins.json"] = b"{"
        self.assertProblem(
            fixture.problems(), "grug-1.20.6-forge.mixins.json in the manifest does not parse"
        )

    def test_class_versions(self):
        self.assertEqual(universal_jar.class_version(klass(JAVA_21)), 65)
        self.assertEqual(universal_jar.class_version(b""), 0)


class UnsupportedGameTest(unittest.TestCase):
    """grug's error on a game the jar has no layer for (#493)."""

    def assertProblem(self, problems, text):
        self.assertTrue(any(text in p for p in problems), f"no problem names {text!r}: {problems}")

    def test_lists_the_supported_games(self):
        jar = Universal.read(Fixture(self).assemble())
        self.assertEqual(
            jar.root.files["grug_supported_games.txt"].decode().splitlines(),
            [game.name for game in universal_jar.GAMES if game.expect],
        )

    def test_a_list_that_differs_from_games(self):
        problems = Fixture(self).problems_after({"grug_supported_games.txt": b"Forge 1.20.6\n"})
        self.assertProblem(problems, "grug_supported_games.txt does not list exactly the games")

    def outer(self, change):
        fixture = Fixture(self)
        jar = Universal.read(fixture.assemble())
        outer = json.loads(jar.root.files["fabric.mod.json"])
        change(outer)
        return fixture.problems_after({"fabric.mod.json": json.dumps(outer).encode()})

    def test_an_outer_mod_without_the_entrypoint_is_silent(self):
        problems = self.outer(lambda outer: outer.pop("entrypoints"))
        self.assertProblem(problems, "the outer fabric.mod.json's entrypoints are not exactly")
        self.assertProblem(
            problems,
            "Babric b1.7.3 without StationAPI activates no layer, but should stop with grug's error",
        )

    def test_an_outer_mod_with_a_second_entrypoint(self):
        problems = self.outer(lambda outer: outer["entrypoints"].update(main=["net.grug.X"]))
        self.assertProblem(problems, "the outer fabric.mod.json's entrypoints are not exactly")

    def test_a_missing_entrypoint_class(self):
        fixture = Fixture(self)
        del fixture.core["net/grug/minecraft/bootstrap/GrugFabricPreLaunch.class"]
        self.assertProblem(
            fixture.problems(),
            "the outer fabric.mod.json's entrypoint net.grug.minecraft.bootstrap.GrugFabricPreLaunch"
            " is missing",
        )

    def test_a_reporting_game_and_a_silent_one(self):
        jar = Universal.read(Fixture(self).assemble())
        games = {g.name: g for g in universal_jar.GAMES}
        self.assertEqual(universal_jar.activate(jar, games["Forge 1.21.1"]), universal_jar.REPORTS)
        self.assertEqual(universal_jar.activate(jar, games["Forge 1.12.2"]), universal_jar.REPORTS)
        self.assertEqual(universal_jar.activate(jar, games["Forge 1.6.4"]), [])


class VersionCheckTest(unittest.TestCase):
    """One repository version (#499)."""

    def assertProblem(self, problems, text):
        self.assertTrue(any(text in p for p in problems), f"no problem names {text!r}: {problems}")

    def test_a_dirty_build_is_still_the_repository_version(self):
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        assemble(core, fixture.layer_paths(), STAMPED + "-dirty", fixture.out, [fixture.dll()])
        self.assertEqual(check(fixture.out, fixture.repo), [])

    def test_an_outer_version_without_the_repository_version(self):
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        assemble(core, fixture.layer_paths(), "2.0.0-0123456789ab", fixture.out)
        problems = check(fixture.out, fixture.repo)
        self.assertProblem(problems, "the outer fabric.mod.json reports '2.0.0-0123456789ab'")
        self.assertProblem(problems, "META-INF/mods.toml reports '2.0.0-0123456789ab'")
        self.assertProblem(problems, "META-INF/neoforge.mods.toml reports '2.0.0-0123456789ab'")

    def test_an_unstamped_outer_version(self):
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        assemble(core, fixture.layer_paths(), VERSION, fixture.out)
        self.assertProblem(check(fixture.out, fixture.repo), "not 1.0.0 stamped with a commit")

    def test_a_layer_with_a_version_of_its_own(self):
        fixture = Fixture(self)
        metadata = json.loads(fixture.layers["a1.1.2_01-ornithe"]["fabric.mod.json"])
        metadata["version"] = "unspecified"
        fixture.layers["a1.1.2_01-ornithe"]["fabric.mod.json"] = json.dumps(metadata).encode()
        self.assertProblem(
            fixture.problems(),
            "grug-a1.1.2_01-ornithe.jar reports version 'unspecified', not the repository version",
        )

    def test_a_modloader_mod_with_a_version_of_its_own(self):
        fixture = Fixture(self)
        layer = fixture.layers["1.2.5-forge"]
        layer["net/grug/minecraft/forge125/mod_Grug.class"] = string_field_class("VERSION", "9.9.9")
        self.assertProblem(
            fixture.problems(),
            "mod_Grug.class reports VERSION '9.9.9', not the repository version 1.0.0",
        )

    def test_a_modloader_mod_without_a_version_constant(self):
        fixture = Fixture(self)
        layer = fixture.layers["1.2.5-forge"]
        layer["net/grug/minecraft/forge125/mod_Grug.class"] = string_field_class("OTHER", VERSION)
        self.assertProblem(fixture.problems(), "mod_Grug.class reports VERSION None")

    def test_reads_a_string_constant_past_every_kind_of_constant(self):
        # A Long and a Double take two slots each, which shifts every later index.
        extra = [b"\x05" + bytes(8), b"\x06" + bytes(8), b"\x03" + bytes(4), b"\x0c" + bytes(4)]
        data = string_field_class("VERSION", "1.2.3", extra)
        self.assertEqual(universal_jar.string_constant(data, "VERSION"), "1.2.3")
        self.assertIsNone(universal_jar.string_constant(data, "OTHER"))
        self.assertIsNone(universal_jar.string_constant(klass(), "VERSION"))
        self.assertIsNone(universal_jar.string_constant(data[:40], "VERSION"))

    def test_a_checkout_without_a_repository_version(self):
        fixture = Fixture(self)
        (fixture.repo / "gradle.properties").write_text("other=1\n")
        self.assertProblem(fixture.problems(), "declares no mod_version, the repository version")


class ReleaseNotesTest(unittest.TestCase):
    """The players' part of a release's notes (#503)."""

    README = "\n".join(
        [
            "# grug",
            "",
            "## For Players",
            "",
            "Download the one jar, `grug-<version>.jar`, from the latest release on the",
            "[Releases page](https://example.com/releases) and put it in `mods/`.",
            "",
            "| Game | Mod loader |",
            "| :--- | :--- |",
            "| Beta 1.7.3 | Ornithe |",
            "",
            "- one",
            "- two",
            "",
            "Only Linux",
            "is supported.",
            "",
            "### Test Keys",
            "",
            "Press R.",
            "",
            "## For Developers",
        ]
    )

    def test_takes_the_for_players_section_up_to_its_first_subsection(self):
        self.assertEqual(
            universal_jar.release_notes(self.README, "grug-0.1.1.jar"),
            "## Instructions for players\n\n"
            "Download the one jar, `grug-0.1.1.jar`, from this release's assets below and put it in "
            "`mods/`.\n\n"
            "| Game | Mod loader |\n| :--- | :--- |\n| Beta 1.7.3 | Ornithe |\n\n"
            "- one\n- two\n\n"
            "Only Linux is supported.\n",
        )

    def test_a_readme_that_no_longer_points_at_the_releases_page(self):
        readme = self.README.replace("[Releases page](https://example.com/releases)", "website")
        with self.assertRaisesRegex(universal_jar.AssemblyError, "no longer points"):
            universal_jar.release_notes(readme, "grug-0.1.1.jar")

    def test_a_readme_without_the_section(self):
        with self.assertRaises(universal_jar.AssemblyError):
            universal_jar.release_notes("# grug\n", "grug-0.1.1.jar")

    def test_the_real_readme(self):
        notes = universal_jar.release_notes(
            (universal_jar.REPO_ROOT / "README.md").read_text(), "grug-0.1.1.jar"
        )
        self.assertIn("`grug-0.1.1.jar`, from this release's assets below", notes)
        self.assertNotIn("Releases page", notes)
        self.assertNotIn("Test Keys", notes)


class ModZipsTest(unittest.TestCase):
    """The per-mod zips and the index a release attaches (#511)."""

    def mods(self, tmp):
        mods = Path(tmp) / "mods"
        (mods / "alpha" / "code").mkdir(parents=True)
        (mods / "alpha" / "about.json").write_text("{}")
        (mods / "alpha" / "code" / "a.grug").write_text("a")
        (mods / "beta").mkdir()
        (mods / "beta" / "about.json").write_text("{}")
        (mods / "coverage").mkdir()
        (mods / "coverage" / "about.json").write_text("{}")
        return mods

    def test_one_zip_per_mod_but_the_fixtures_and_an_index_naming_each(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            index = universal_jar.write_mod_zips(self.mods(tmp), "abc123", out)

            self.assertEqual(
                sorted(p.name for p in out.iterdir()), ["alpha.zip", "beta.zip", "index.json"]
            )
            self.assertEqual(json.loads((out / "index.json").read_text()), index)
            self.assertEqual(index["commit"], "abc123")
            self.assertEqual(sorted(index["mods"]), ["alpha", "beta"])
            data = (out / "alpha.zip").read_bytes()
            self.assertEqual(index["mods"]["alpha"]["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(index["mods"]["alpha"]["zip"], "alpha.zip")
            self.assertEqual(index["mods"]["alpha"]["size"], len(data))
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                self.assertEqual(archive.namelist(), ["alpha/about.json", "alpha/code/a.grug"])

    def test_the_same_files_always_make_the_same_zip(self):
        with tempfile.TemporaryDirectory() as tmp:
            mods = self.mods(tmp)
            first = universal_jar.write_mod_zips(mods, "a", Path(tmp) / "one")
            second = universal_jar.write_mod_zips(mods, "b", Path(tmp) / "two")
            self.assertEqual(first["mods"], second["mods"])

    def test_a_directory_without_mods_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "mods"
            (empty / "coverage").mkdir(parents=True)
            with self.assertRaisesRegex(AssemblyError, "holds no mod to publish"):
                universal_jar.write_mod_zips(empty, "a", Path(tmp) / "out")

    def test_the_real_mods_each_get_a_zip(self):
        with tempfile.TemporaryDirectory() as tmp:
            index = universal_jar.write_mod_zips(universal_jar.REPO_ROOT / "mods", "a", tmp)
            self.assertIn("examplemod", index["mods"])
            self.assertNotIn("coverage", index["mods"])

    def test_the_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            mods = self.mods(tmp)
            argv = [
                "mod-zips",
                "--commit",
                "abc",
                "--mods",
                str(mods),
                "--out",
                str(Path(tmp) / "o"),
            ]
            with mock.patch("sys.stdout", new_callable=io.StringIO) as out:
                self.assertEqual(universal_jar.main(argv), 0)
            self.assertIn("Wrote 2 mod zips", out.getvalue())
            (mods / "alpha").rename(mods / "x")
            for child in (mods / "x", mods / "beta"):
                for path in sorted(child.rglob("*"), reverse=True):
                    path.unlink() if path.is_file() else path.rmdir()
                child.rmdir()
            with mock.patch("sys.stderr", new_callable=io.StringIO):
                self.assertEqual(universal_jar.main(argv), 1)


class SmokeTest(unittest.TestCase):
    """The smoke jobs: one game per mechanism the jar does not support (#493)."""

    def game(self, name, kind="forge", expect=None, smoke=None):
        return universal_jar.Game(name, kind, "1.0", {}, expect, smoke=smoke)

    def script(self, *recipes):
        """A miniature smoke-launch.sh whose launch function has these recipes."""
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        cases = "".join(f"    {recipe}) start {recipe} ;;\n" for recipe in recipes)
        path = Path(directory.name) / "smoke-launch.sh"
        path.write_text(f'launch() {{\n  case "$1" in\n{cases}  esac\n}}\n')
        return path

    def check(self, games):
        return universal_jar.check_smoke_games(
            games, self.script(*{g.smoke for g in games if g.smoke})
        )

    def covered(self):
        return [
            self.game(kind, kind, smoke=kind)
            for kind in universal_jar.MECHANISMS
            if kind not in universal_jar.SMOKE_EXEMPT
        ]

    def test_the_shipped_games_cover_every_mechanism(self):
        self.assertEqual(universal_jar.check_smoke_games(), [])
        self.assertEqual(self.check(self.covered()), [])

    def test_a_smoke_game_that_gained_a_layer(self):
        games = self.covered() + [self.game("Forge 1.21.1", expect="1.21.1-forge", smoke="f")]
        problems = self.check(games)
        self.assertTrue(
            any("Forge 1.21.1 is a smoke game, but the jar supports it" in p for p in problems)
        )

    def test_a_mechanism_left_without_a_smoke_game(self):
        games = [g for g in self.covered() if g.kind != "neoforge"]
        self.assertEqual(
            self.check(games),
            [
                f"no smoke game covers the neoforge mechanism ({universal_jar.MECHANISMS['neoforge']})"
            ],
        )

    def test_a_recipe_on_two_games_and_an_unknown_mechanism(self):
        games = self.covered() + [self.game("A", smoke="forge"), self.game("B", kind="rift")]
        problems = self.check(games)
        self.assertIn("the smoke recipe forge is on more than one game", problems)
        self.assertIn("B uses the mechanism rift, which MECHANISMS lacks", problems)

    def test_a_recipe_the_script_lacks_and_one_no_game_names(self):
        games = self.covered()
        script = self.script(*[g.smoke for g in games[1:]], "forge-1.21.4")
        problems = universal_jar.check_smoke_games(games, script)
        self.assertIn(
            f"GAMES names the smoke recipe {games[0].smoke}, which smoke-launch.sh lacks", problems
        )
        self.assertIn(
            "smoke-launch.sh has the recipe forge-1.21.4, which no game in GAMES names", problems
        )

    def test_the_matrix(self):
        matrix = universal_jar.smoke_matrix()
        self.assertEqual(len(matrix), 9)
        self.assertIn({"game": "Forge 1.21.1", "recipe": "forge-1.21.1"}, matrix)
        self.assertTrue(all(g.expect is None for g in universal_jar.GAMES if g.smoke))


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
        argv = [
            "assemble",
            "--core",
            str(core),
            "--version",
            STAMPED,
            "--native",
            str(fixture.dll()),
            "--out",
            str(out),
        ]
        self.assertEqual(universal_jar.main(argv + [str(p) for p in fixture.layer_paths()]), 0)
        repo = ["--repo", str(fixture.repo)]
        self.assertEqual(universal_jar.main(["check", str(out)] + repo), 0)
        del fixture.core["mod_api.json"]
        fixture.assemble()
        self.assertEqual(universal_jar.main(["check", str(fixture.out)] + repo), 1)

    def test_release_notes(self):
        with tempfile.TemporaryDirectory() as tmp:
            readme = Path(tmp) / "README.md"
            readme.write_text(
                "## For Players\n\nGet `grug-<version>.jar` from the latest release on the "
                "[Releases page](https://example.com).\n"
            )
            out = io.StringIO()
            with mock.patch("sys.stdout", out):
                self.assertEqual(
                    universal_jar.main(
                        ["release-notes", "grug-1.0.0.jar", "--readme", str(readme)]
                    ),
                    0,
                )
            self.assertIn("Get `grug-1.0.0.jar` from this release's assets below.", out.getvalue())
            readme.write_text("# grug\n")
            with mock.patch("sys.stderr", io.StringIO()):
                self.assertEqual(
                    universal_jar.main(
                        ["release-notes", "grug-1.0.0.jar", "--readme", str(readme)]
                    ),
                    1,
                )

    def test_smoke_matrix(self):
        with mock.patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(universal_jar.main(["smoke-matrix"]), 0)
        self.assertEqual(json.loads(out.getvalue()), universal_jar.smoke_matrix())

    def test_assemble_reports_an_error(self):
        fixture = Fixture(self)
        core = write_jar(fixture.dir / "core-release.jar", fixture.core)
        bad = write_jar(fixture.dir / "not-a-layer.jar", {})
        argv = ["assemble", "--core", str(core), "--version", "1", "--out", str(fixture.out)]
        self.assertEqual(universal_jar.main(argv + [str(bad)]), 1)


if __name__ == "__main__":
    unittest.main()
